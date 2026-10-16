package com.aitravel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ResultValidator {
    private final Json json;
    public ResultValidator(Json json) { this.json=json; }
    public JsonNode research(JsonNode result, JsonNode sources, TripInput input, boolean demo) {
        require(result.path("candidates").isArray(),"检索资料格式不正确");
        var actualSources=new HashSet<String>();
        sources.forEach(s -> { if(s.path("id").isTextual() && (demo || s.path("url").asText().matches("https?://.+"))) actualSources.add(s.path("id").asText()); });
        var clean=json.mapper.createArrayNode();
        var mapping=json.mapper.createObjectNode();
        for(var candidate:result.path("candidates")) {
            if(clean.size()>=24) break;
            if(!candidate.isObject() || candidate.path("name").asText().isBlank() || candidate.path("name").asText().length()>100) continue;
            var refs=candidate.path("source_ids");
            if(!refs.isArray() || refs.isEmpty() || !allReferences(refs,actualSources)) continue;
            // City identity and must-visit aliases still require factual evaluation of the linked evidence.
            if(!candidate.path("city").asText().equals(input.destinationCity())) continue;
            if(candidate.path("address").asText().isBlank() && candidate.path("district").asText().isBlank()) continue;
            var c=(ObjectNode)candidate.deepCopy();
            c.put("id","C"+(clean.size()+1));
            int minutes=c.path("suggested_minutes").asInt(120);
            c.put("suggested_minutes",Math.clamp(minutes,15,720));
            if(!c.path("ticket_price").isNull() && !c.path("ticket_price").isMissingNode()) {
                money(c.path("ticket_price"));
                var priceRefs=c.path("price_source_ids");
                if(!priceRefs.isArray() || priceRefs.isEmpty() || !allReferences(priceRefs,actualSources)) c.putNull("ticket_price");
            } else c.putNull("ticket_price");
            for(String name:input.mustVisitNames()) {
                boolean matches=c.path("name").asText().equals(name);
                for(var alias:c.path("aliases")) if(alias.asText().equals(name)) matches=true;
                if(matches) {
                    if(c.path("unavailable").asBoolean()) fail("必去景点当前不可安排："+name);
                    if(mapping.has(name)) fail("必去景点有多个匹配，请补充更准确的名称："+name);
                    mapping.put(name,c.path("id").asText());
                }
            }
            if(!c.path("unavailable").asBoolean()) clean.add(c);
        }
        require(!clean.isEmpty(),"没有找到具备来源的可用景点，请调整城市或条件");
        for(String name:input.mustVisitNames()) require(mapping.has(name),"无法确认必去景点："+name);
        var out=json.mapper.createObjectNode(); out.put("destination_city",input.destinationCity());
        out.put("demo",demo); out.set("candidates",clean); out.set("sources",sources); out.set("must_visit_mapping",mapping);
        out.put("notice","检索时间不等于信息更新时间；来源内容及营业情况需出行前核实。");
        return out;
    }
    public record Validated(JsonNode content, JsonNode summary, BigDecimal total, BigDecimal difference, boolean over) {}
    public Validated itinerary(JsonNode raw,JsonNode research,TripInput input) {
        require(raw.path("result_type").asText().equals("success"),"模型未能安排符合条件的行程，请调整需求后重试");
        require(raw.isObject() && raw.path("title").isTextual() && !raw.path("title").asText().isBlank() && raw.path("title").asText().length()<=100,"方案名称格式不正确");
        require(raw.path("summary").isTextual(),"缺少行程摘要");
        require(raw.path("days").isArray() && raw.path("days").size()==input.travelDays(),"行程天数不符合要求");
        var out=(ObjectNode)raw.deepCopy(); out.put("schema_version",1); out.put("demo",research.path("demo").asBoolean());
        var candidates=new HashMap<String,JsonNode>(); research.path("candidates").forEach(c->candidates.put(c.path("id").asText(),c));
        var seen=new HashSet<String>(); var totals=new LinkedHashMap<String,BigDecimal>();
        for(String key:List.of("tickets","dining","local_transport","lodging")) totals.put(key,BigDecimal.ZERO);
        int index=0;
        for(var day:out.path("days")) {
            require(day.isObject(),"每日行程格式不正确");
            index++;
            require(day.path("day_no").isIntegralNumber() && day.path("day_no").asInt()==index && day.path("date").asText().equals(input.startDate().plusDays(index-1).toString()),"行程日期不符合要求");
            var activities=day.path("activities");
            require(activities.isArray() && !activities.isEmpty() && activities.size()<=20,"每日活动数量不符合要求");
            BigDecimal tickets=BigDecimal.ZERO;
            for(var activity:activities) {
                require(activity.isObject(),"活动格式不正确");
                String type=activity.path("type").asText();
                require(Set.of("scenic","dining","leisure").contains(type),"活动类型不正确");
                require(activity.path("description").isTextual() && !activity.path("description").asText().isBlank(),"缺少活动说明");
                require(activity.path("duration_minutes").isIntegralNumber() && activity.path("duration_minutes").asInt()>0 && activity.path("duration_minutes").asInt()<=1440,"活动时长不正确");
                if(type.equals("scenic")) {
                    String id=activity.path("candidate_id").asText(); var c=candidates.get(id); require(c!=null,"行程引用了检索集合以外的景点");
                    seen.add(id); BigDecimal estimate=money(activity.path("ticket_estimate_per_person"));
                    BigDecimal price=c.path("ticket_price").isNumber()?money(c.path("ticket_price")):estimate;
                    var a=(ObjectNode)activity; a.set("scenic",c); a.put("ticket_basis",c.path("ticket_price").isNumber()?"来源参考价":"模型估算");
                    a.put("ticket_used_per_person",price); tickets=tickets.add(price.multiply(BigDecimal.valueOf(input.travelerCount())));
                } else require(activity.path("candidate_id").isNull(),"非景点活动不应引用景点编号");
            }
            require(day.path("expenses").isObject(),"每日费用缺失");
            var expenses=(ObjectNode)day.path("expenses");
            for(String key:totals.keySet()) money(expenses.path(key));
            expenses.put("tickets",tickets.setScale(2,RoundingMode.HALF_UP));
            require(index!=input.travelDays() || money(expenses.path("lodging")).signum()==0,"最后一天不应计入住宿费");
            BigDecimal daily=BigDecimal.ZERO;
            for(String key:totals.keySet()) { BigDecimal amount=money(expenses.path(key)); totals.put(key,totals.get(key).add(amount)); daily=daily.add(amount); }
            ((ObjectNode)day).put("daily_total",daily.setScale(2,RoundingMode.HALF_UP));
        }
        research.path("must_visit_mapping").elements().forEachRemaining(id -> require(seen.contains(id.asText()),"必去景点未全部安排"));
        BigDecimal total=totals.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);
        require(total.compareTo(new BigDecimal("99999999.99"))<=0,"费用超过允许范围");
        BigDecimal diff=total.subtract(input.budget());
        out.set("sources",research.path("sources")); out.put("total_cost",total); out.put("budget_difference",diff); out.put("over_budget",diff.signum()>0);
        return new Validated(out,json.mapper.valueToTree(totals),total,diff,diff.signum()>0);
    }
    private boolean allReferences(JsonNode refs,Set<String> actual) { for(var id:refs) if(!actual.contains(id.asText())) return false; return true; }
    private BigDecimal money(JsonNode value) {
        require(value.isNumber(),"费用必须为数字"); BigDecimal n=value.decimalValue().stripTrailingZeros();
        require(n.signum()>=0 && n.scale()<=2 && n.compareTo(new BigDecimal("99999999.99"))<=0,"费用必须为非负且最多两位小数"); return n;
    }
    private void require(boolean yes,String message) { if(!yes) fail(message); }
    private void fail(String message) { throw new ApiError(422,"VALIDATION_ERROR",message); }
}

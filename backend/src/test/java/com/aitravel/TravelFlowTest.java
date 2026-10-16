package com.aitravel;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","spring.profiles.active=default","spring.datasource.url=jdbc:h2:mem:flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","travel.mode=demo","travel.per-minute=100","travel.admin-username=","travel.admin-password="})
@AutoConfigureMockMvc
class TravelFlowTest {
    @Autowired MockMvc mvc;
    @Autowired Json json;
    @Autowired TravelStore store;
    @Autowired ResultValidator validator;
    @Test void completeFlowWithIsolationIdempotencyVersionsAndDeletion() throws Exception {
        var alice=registerLogin("alice_test");var bob=registerLogin("bob_test");
        var input=conditions();var request=new LinkedHashMap<String,Object>();request.put("requestId",UUID.randomUUID().toString());request.put("conditions",input);
        var first=body(mvc.perform(post("/api/plans/generate").session(alice).with(csrf()).contentType("application/json").content(json.write(request))).andExpect(status().isAccepted()).andReturn());
        String task=first.path("task_id").asText();var done=await(alice,task);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");String plan=done.path("plan_id").asText(),version=done.path("result_version_id").asText();
        var duplicate=body(mvc.perform(post("/api/plans/generate").session(alice).with(csrf()).contentType("application/json").content(json.write(request))).andExpect(status().isAccepted()).andReturn());
        assertThat(duplicate.path("task_id").asText()).isEqualTo(task);
        input.put("budget",999);mvc.perform(post("/api/plans/generate").session(alice).with(csrf()).contentType("application/json").content(json.write(request))).andExpect(status().isConflict());input.put("budget",2500);
        mvc.perform(get("/api/plans/"+plan).session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/"+task+"/research").session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/tasks").session(alice)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/plans/"+plan).session(alice)).andExpect(status().isForbidden());
        var detail=body(mvc.perform(get("/api/plans/"+plan).session(alice)).andExpect(status().isOk()).andReturn());
        assertThat(detail.path("version").path("content").path("demo").asBoolean()).isTrue();
        assertThat(detail.path("version").path("research").path("must_visit_mapping").has("西湖")).isTrue();
        request.put("requestId",UUID.randomUUID().toString());request.put("baseVersionId",version);request.put("feedback","多留一点休息时间");
        var revision=body(mvc.perform(post("/api/plans/"+plan+"/revisions").session(alice).with(csrf()).contentType("application/json").content(json.write(request))).andExpect(status().isAccepted()).andReturn());
        assertThat(await(alice,revision.path("task_id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        var history=body(mvc.perform(get("/api/plans/"+plan+"/versions").session(alice)).andExpect(status().isOk()).andReturn());assertThat(history.size()).isEqualTo(2);
        var old=body(mvc.perform(get("/api/plans/"+plan+"/versions/"+version).session(alice)).andExpect(status().isOk()).andReturn());assertThat(old.path("version").path("content")).isEqualTo(detail.path("version").path("content"));
        request.put("requestId",UUID.randomUUID().toString());mvc.perform(post("/api/plans/"+plan+"/revisions").session(alice).with(csrf()).contentType("application/json").content(json.write(request))).andExpect(status().isConflict());
        mvc.perform(delete("/api/plans/"+plan).session(alice).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/plans/"+plan+"/versions/"+version).session(alice)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/"+task+"/research").session(alice)).andExpect(status().isNotFound());
        assertThat(store.rows("SELECT * FROM api_log")).isEmpty();
    }
    @Test void validationRejectsUnknownCandidatesMissingMustVisitAndIncorrectDates() throws Exception {
        var session=registerLogin("validate_test");var input=conditions();
        var accepted=body(mvc.perform(post("/api/plans/generate").session(session).with(csrf()).contentType("application/json").content(json.write(Map.of("requestId",UUID.randomUUID().toString(),"conditions",input)))).andExpect(status().isAccepted()).andReturn());
        var done=await(session,accepted.path("task_id").asText());var detail=body(mvc.perform(get("/api/plans/"+done.path("plan_id").asText()).session(session)).andReturn());
        var research=detail.path("version").path("research");var trip=json.mapper.convertValue(detail.path("version").path("conditions"),TripInput.class);
        var raw=detail.path("version").path("content").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("days").path(0).path("activities").path(0)).put("candidate_id","C999");
        assertThatThrownBy(()->validator.itinerary(raw,research,trip)).isInstanceOf(ApiError.class).hasMessageContaining("集合以外");
        var wrongDate=detail.path("version").path("content").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongDate.path("days").path(0)).put("date","2000-01-01");
        assertThatThrownBy(()->validator.itinerary(wrongDate,research,trip)).isInstanceOf(ApiError.class).hasMessageContaining("日期");
        var missing=detail.path("version").path("content").deepCopy();
        String required=research.path("must_visit_mapping").path("西湖").asText();
        String replacement="";
        for(var c:research.path("candidates"))if(!c.path("id").asText().equals(required)){replacement=c.path("id").asText();break;}
        for(var day:missing.path("days"))for(var a:day.path("activities"))if(a.path("candidate_id").asText().equals(required))((com.fasterxml.jackson.databind.node.ObjectNode)a).put("candidate_id",replacement);
        assertThatThrownBy(()->validator.itinerary(missing,research,trip)).isInstanceOf(ApiError.class).hasMessageContaining("必去");
    }
    @Test void rejectsInvalidInputAndPublicRoleEscalation() throws Exception {
        var s=registerLogin("role_test");mvc.perform(get("/api/users/me").session(s)).andExpect(jsonPath("$.role").value("USER"));
        var input=conditions();input.put("travelDays",0);
        mvc.perform(post("/api/plans/generate").session(s).with(csrf()).contentType("application/json").content(json.write(Map.of("requestId",UUID.randomUUID().toString(),"conditions",input)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/plans")).andExpect(status().isUnauthorized());
    }
    private MockHttpSession registerLogin(String username) throws Exception {
        var form=Map.of("username",username,"password","test-password-123","role","ADMIN");
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content(json.write(form))).andExpect(status().isOk());
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json.write(form))).andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    private Map<String,Object> conditions() {
        var m=new LinkedHashMap<String,Object>();m.put("startCity","上海");m.put("destinationCity","杭州");m.put("startDate",LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());m.put("travelDays",3);m.put("travelerCount",2);m.put("budget",2500);m.put("preferences",List.of("自然风光"));m.put("travelerType","情侣");m.put("mustVisitNames",List.of("西湖"));m.put("remark","");return m;
    }
    private JsonNode await(MockHttpSession session,String id) throws Exception {
        for(int i=0;i<100;i++) { var t=body(mvc.perform(get("/api/tasks/"+id).session(session)).andExpect(status().isOk()).andReturn());if(Set.of("SUCCEEDED","FAILED").contains(t.path("status").asText()))return t;Thread.sleep(50); }
        throw new AssertionError("Task did not finish");
    }
    private JsonNode body(MvcResult result) throws Exception { return json.mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
}

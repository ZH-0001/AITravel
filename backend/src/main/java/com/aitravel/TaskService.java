package com.aitravel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.aitravel.TravelStore.*;

@Service
public class TaskService implements ApplicationRunner {
    private final TravelStore store;
    private final Json json;
    private final TravelSettings settings;
    private final TravelProviders providers;
    private final ResultValidator validator;
    private final TransactionTemplate tx;
    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<Long,FutureTask<Void>> futures=new ConcurrentHashMap<>();
    public TaskService(TravelStore store,Json json,TravelSettings settings,TravelProviders providers,ResultValidator validator,
            TransactionTemplate tx,UserMapper users,PasswordEncoder encoder) {
        this.store=store;this.json=json;this.settings=settings;this.providers=providers;this.validator=validator;this.tx=tx;this.users=users;this.encoder=encoder;
        executor=new ThreadPoolExecutor(settings.workers(),settings.workers(),0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(settings.queueSize()),Thread.ofPlatform().name("travel-worker-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    public record Submission(String requestId,TripInput conditions,String feedback,Long baseVersionId) {}
    public Map<String,Object> submit(long uid,Long planId,Submission request) {
        if(request==null || request.conditions()==null || request.requestId()==null || !request.requestId().matches("[A-Za-z0-9_-]{8,64}")) throw ApiError.bad("缺少合法请求编号或出行条件");
        var input=request.conditions().normalized();input.validate();
        String feedback=request.feedback()==null?"":request.feedback().strip();
        if(planId!=null && (feedback.isBlank() || feedback.length()>500 || request.baseVersionId()==null)) throw ApiError.bad("修改需要基础版本和 1～500 字意见");
        String hash=hash(json.write(Arrays.asList(planId,request.baseVersionId(),input,feedback)));
        var accepted=tx.execute(status->{
            store.one("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",uid);
            if(planId!=null) store.plan(uid,planId);
            var existing=store.rows("SELECT * FROM generation_task WHERE user_id=? AND request_id=?",uid,request.requestId());
            if(!existing.isEmpty()) {
                var e=existing.getFirst();
                if(!hash.equals(string(e,"request_hash"))) throw new ApiError(409,"REQUEST_CONFLICT","相同请求编号对应不同参数");
                return new Accepted(number(e,"task_id"),false);
            }
            if(!store.rows("SELECT task_id FROM generation_task WHERE user_id=? AND status IN ('WAITING','RUNNING')",uid).isEmpty()) throw new ApiError(409,"TASK_ACTIVE","已有任务正在进行，请等待完成");
            long recent=((Number)store.one("SELECT COUNT(*) AS total FROM generation_task WHERE user_id=? AND create_time>?",uid,java.sql.Timestamp.from(Instant.now().minusSeconds(60))).get("total")).longValue();
            if(recent>=settings.perMinute()) throw new ApiError(429,"RATE_LIMIT","提交过于频繁，请稍后再试");
            if(planId!=null) {
                var plan=store.plan(uid,planId);
                if(!Objects.equals(number(plan,"current_version_id"),request.baseVersionId())) throw new ApiError(409,"STALE_VERSION","当前版本已更新，请重新打开修改页面");
                if(!input.startCity().equals(string(plan,"start_city")) || !input.destinationCity().equals(string(plan,"destination_city"))) throw ApiError.bad("同一方案不能更换出发或目的城市，请新建方案");
            }
            long id=store.insert("INSERT INTO generation_task(user_id,request_id,request_hash,task_type,plan_id,base_version_id,input_snapshot,modification_feedback,status,mode) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    uid,request.requestId(),hash,planId==null?"CREATE":"REVISE",planId,request.baseVersionId(),json.write(input),feedback,"WAITING",settings.mode());
            return new Accepted(id,true);
        });
        if(accepted==null) throw new IllegalStateException("Missing transaction result");
        if(accepted.created()) {
            var future=new FutureTask<Void>(() -> { execute(accepted.id());return null; });futures.put(accepted.id(),future);
            try { executor.execute(future); }
            catch(RejectedExecutionException e) { futures.remove(accepted.id());fail(accepted.id(),"QUEUE_FULL","当前服务繁忙，请稍后重新提交");throw new ApiError(503,"QUEUE_FULL","当前服务繁忙，请稍后重新提交"); }
        }
        return publicTask(store.task(uid,accepted.id()));
    }
    private record Accepted(long id,boolean created) {}
    private void execute(long id) {
        long taskDeadline=System.currentTimeMillis()+settings.taskSeconds()*1000L;
        try {
            if(store.update("UPDATE generation_task SET status='RUNNING',phase='RETRIEVAL',start_time=CURRENT_TIMESTAMP WHERE task_id=? AND status='WAITING'",id)!=1) return;
            var task=store.one("SELECT * FROM generation_task WHERE task_id=?",id);long uid=number(task,"user_id");
            var input=json.mapper.convertValue(json.read(string(task,"input_snapshot")),TripInput.class);
            long started=System.currentTimeMillis();
            JsonNode research;
            try { research=providers.research(id,uid,input,Math.min(taskDeadline,started+settings.phaseSeconds()*1000L)); }
            finally { stageTime(id,"RETRIEVAL",started); }
            ensureRunning(id,taskDeadline);
            tx.executeWithoutResult(status->{
                store.one("SELECT task_id FROM generation_task WHERE task_id=? AND status='RUNNING' FOR UPDATE",id);
                long snapshot=store.insert("INSERT INTO search_snapshot(task_id,destination_city,research_json) VALUES(?,?,?)",id,input.destinationCity(),json.write(research));
                store.update("UPDATE generation_task SET search_snapshot_id=?,phase='PLANNING' WHERE task_id=?",snapshot,id);
            });
            JsonNode old=null;
            if(task.get("base_version_id")!=null) old=json.read(string(store.one("SELECT content_json FROM travel_plan_version WHERE version_id=?",task.get("base_version_id")),"content_json"));
            started=System.currentTimeMillis();
            JsonNode raw;
            try { raw=providers.plan(id,uid,input,research,string(task,"modification_feedback"),old,Math.min(taskDeadline,started+settings.phaseSeconds()*1000L)); }
            finally { stageTime(id,"PLANNING",started); }
            ensureRunning(id,taskDeadline);
            store.update("UPDATE generation_task SET phase='VALIDATING' WHERE task_id=? AND status='RUNNING'",id);
            started=System.currentTimeMillis();ResultValidator.Validated result;
            try { result=validator.itinerary(raw,research,input); }
            finally { stageTime(id,"VALIDATING",started); }
            complete(id,uid,input,result,taskDeadline);
        } catch(ApiError e) { fail(id,e.code,e.status==404?"任务已结束，未写入新版本":e.getMessage()); }
        catch(Exception e) { fail(id,"GENERATION_FAILED","生成过程异常，请检查服务配置或稍后重试"); }
        finally { futures.remove(id); }
    }
    private void complete(long id,long uid,TripInput input,ResultValidator.Validated result,long deadline) {
        tx.executeWithoutResult(status->{
            // Serialize saves and deletion/submission on the user row; late responses cannot resurrect a failed task.
            store.one("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",uid);
            var task=store.one("SELECT * FROM generation_task WHERE task_id=? FOR UPDATE",id);ensureRunning(id,deadline);
            long planId;Long parent=null;int version=1;
            if(task.get("plan_id")==null) {
                planId=store.insert("INSERT INTO travel_plan(user_id,plan_name,start_city,destination_city) VALUES(?,?,?,?)",uid,result.content().path("title").asText(),input.startCity(),input.destinationCity());
                store.operation(uid,"PLAN_CREATE","PLAN",planId,id);
            } else {
                planId=number(task,"plan_id");var plan=store.plan(uid,planId);parent=number(plan,"current_version_id");
                if(!Objects.equals(parent,task.get("base_version_id")==null?null:number(task,"base_version_id"))) throw new ApiError(409,"STALE_VERSION","基础版本已更新");
                version=((Number)store.one("SELECT version_no FROM travel_plan_version WHERE version_id=?",parent).get("version_no")).intValue()+1;
            }
            long vid=store.insert("INSERT INTO travel_plan_version(plan_id,version_no,parent_version_id,task_id,search_snapshot_id,input_snapshot,modification_feedback,content_json,expense_summary,total_cost,budget_difference,over_budget) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                planId,version,parent,id,task.get("search_snapshot_id"),json.write(input),task.get("modification_feedback"),json.write(result.content()),json.write(result.summary()),result.total(),result.difference(),result.over());
            store.update("UPDATE travel_plan SET current_version_id=?,update_time=CURRENT_TIMESTAMP WHERE plan_id=?",vid,planId);
            if(System.currentTimeMillis()>deadline) throw new ApiError(504,"TIMEOUT","任务执行超过时限");
            store.update("UPDATE generation_task SET status='SUCCEEDED',result_version_id=?,plan_id=?,finish_time=CURRENT_TIMESTAMP WHERE task_id=?",vid,planId,id);
            store.operation(uid,"VERSION_CREATE","VERSION",vid,id);
        });
    }
    private void stageTime(long id,String stage,long started) {
        var row=store.one("SELECT phase_timings FROM generation_task WHERE task_id=?",id);String value=string(row,"phase_timings");
        ObjectNode timings=value.isBlank()?json.mapper.createObjectNode():(ObjectNode)json.read(value);
        timings.putObject(stage).put("startedAt",Instant.ofEpochMilli(started).toString()).put("finishedAt",Instant.now().toString()).put("durationMs",System.currentTimeMillis()-started);
        store.update("UPDATE generation_task SET phase_timings=? WHERE task_id=? AND status='RUNNING'",json.write(timings),id);
    }
    private void ensureRunning(long id,long deadline) {
        if(Thread.currentThread().isInterrupted() || System.currentTimeMillis()>deadline) throw new ApiError(504,"TIMEOUT","任务执行超过时限");
        if(!"RUNNING".equals(string(store.one("SELECT status FROM generation_task WHERE task_id=?",id),"status"))) throw new ApiError(409,"TASK_ENDED","任务已结束");
    }
    private void fail(long id,String code,String message) {
        store.update("UPDATE generation_task SET status='FAILED',error_code=?,error_summary=?,finish_time=CURRENT_TIMESTAMP WHERE task_id=? AND status IN ('WAITING','RUNNING')",code,message.substring(0,Math.min(message.length(),500)),id);
    }
    @Scheduled(fixedDelay=5000)
    void expire() {
        var expired=store.rows("SELECT task_id FROM generation_task WHERE status='RUNNING' AND start_time<?",java.sql.Timestamp.from(Instant.now().minusSeconds(settings.taskSeconds())));
        for(var row:expired) {
            long id=number(row,"task_id");fail(id,"TIMEOUT","任务执行超过时限");var future=futures.get(id);if(future!=null)future.cancel(true);
        }
    }
    @Override public void run(ApplicationArguments args) {
        if(!Set.of("demo","live").contains(settings.mode())) throw new IllegalArgumentException("TRAVEL_MODE must be demo or live");
        for(String price:List.of(settings.inputPricePerMillion(),settings.outputPricePerMillion(),settings.searchPricePerRequest())) {
            if(!price.isBlank() && new java.math.BigDecimal(price).signum()<0) throw new IllegalArgumentException("Provider prices must be non-negative");
        }
        store.update("UPDATE generation_task SET status='FAILED',error_code='RESTARTED',error_summary='服务重启中断，请主动重试',finish_time=CURRENT_TIMESTAMP WHERE status IN ('WAITING','RUNNING')");
        if(!settings.adminUsername().isBlank() && !settings.adminPassword().isBlank() && users.findByUsername(settings.adminUsername())==null) {
            if(settings.adminPassword().length()<12 || settings.adminPassword().getBytes(StandardCharsets.UTF_8).length>72) throw new IllegalArgumentException("Admin password must be 12+ characters and at most 72 bytes");
            var admin=new UserAccount();admin.username=settings.adminUsername();admin.nickname="管理员";admin.passwordHash=encoder.encode(settings.adminPassword());admin.role="ADMIN";users.insert(admin);
        }
    }
    @PreDestroy void stop() { executor.shutdownNow(); }
    public Map<String,Object> publicTask(Map<String,Object> task) {
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("task_id","task_type","plan_id","base_version_id","status","phase","result_version_id","error_code","error_summary","mode","create_time","start_time","finish_time")) if(task.get(key)!=null)out.put(key,task.get(key));
        if(task.get("phase_timings")!=null)out.put("phase_timings",json.read(string(task,"phase_timings")));return out;
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}

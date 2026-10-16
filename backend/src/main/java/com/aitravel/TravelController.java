package com.aitravel;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static com.aitravel.TravelStore.*;

@RestController
@RequestMapping("/api")
public class TravelController {
    private final TravelStore store;private final TaskService tasks;private final AuthController auth;private final Json json;private final TransactionTemplate tx;
    public TravelController(TravelStore store,TaskService tasks,AuthController auth,Json json,TransactionTemplate tx) { this.store=store;this.tasks=tasks;this.auth=auth;this.json=json;this.tx=tx; }
    private long uid(Authentication a) { return auth.account(a).userId; }
    @PostMapping("/plans/generate") ResponseEntity<?> generate(Authentication a,@RequestBody TaskService.Submission body) { return ResponseEntity.accepted().body(tasks.submit(uid(a),null,body)); }
    @PostMapping("/plans/{id}/revisions") ResponseEntity<?> revise(Authentication a,@PathVariable long id,@RequestBody TaskService.Submission body) { return ResponseEntity.accepted().body(tasks.submit(uid(a),id,body)); }
    @GetMapping("/tasks/{id}") Object task(Authentication a,@PathVariable long id) { return tasks.publicTask(store.task(uid(a),id)); }
    @GetMapping("/tasks") Object tasks(Authentication a,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        var result=store.page("SELECT t.* FROM generation_task t LEFT JOIN travel_plan p ON p.plan_id=t.plan_id WHERE t.user_id=? AND (t.plan_id IS NULL OR p.deleted=FALSE) ORDER BY t.task_id DESC",page,size,uid(a));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        return Map.of("items",items.stream().map(tasks::publicTask).toList(),"page",result.get("page"),"hasMore",result.get("hasMore"));
    }
    @GetMapping("/tasks/{id}/research") Object research(Authentication a,@PathVariable long id) {
        var t=store.task(uid(a),id);if(t.get("search_snapshot_id")==null)throw new ApiError(409,"RESEARCH_UNAVAILABLE","检索资料暂不可用");
        return json.read(string(store.one("SELECT research_json FROM search_snapshot WHERE snapshot_id=? AND task_id=?",t.get("search_snapshot_id"),id),"research_json"));
    }
    @GetMapping("/plans") Object plans(Authentication a,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size) {
        return store.page("SELECT p.*,v.version_no,v.total_cost,v.over_budget,v.input_snapshot FROM travel_plan p JOIN travel_plan_version v ON v.version_id=p.current_version_id WHERE p.user_id=? AND p.deleted=FALSE ORDER BY p.update_time DESC,p.plan_id DESC",page,size,uid(a));
    }
    @GetMapping("/plans/{id}") Object plan(Authentication a,@PathVariable long id) {
        var p=store.plan(uid(a),id);return detail(p,number(p,"current_version_id"));
    }
    @GetMapping("/plans/{id}/versions") Object versions(Authentication a,@PathVariable long id) {
        store.plan(uid(a),id);return store.rows("SELECT version_id,version_no,modification_feedback,total_cost,over_budget,create_time FROM travel_plan_version WHERE plan_id=? ORDER BY version_no DESC",id);
    }
    @GetMapping("/plans/{id}/versions/{version}") Object version(Authentication a,@PathVariable long id,@PathVariable long version) { return detail(store.plan(uid(a),id),version); }
    private Object detail(Map<String,Object> plan,long versionId) {
        var version=store.one("SELECT * FROM travel_plan_version WHERE plan_id=? AND version_id=?",number(plan,"plan_id"),versionId);
        var out=new LinkedHashMap<>(version);out.put("conditions",json.read(string(version,"input_snapshot")));out.remove("input_snapshot");
        out.put("content",json.read(string(version,"content_json")));out.remove("content_json");out.put("expense_summary",json.read(string(version,"expense_summary")));
        out.put("research",json.read(string(store.one("SELECT research_json FROM search_snapshot WHERE snapshot_id=?",version.get("search_snapshot_id")),"research_json")));
        return Map.of("plan",plan,"version",out);
    }
    @DeleteMapping("/plans/{id}") Object delete(Authentication a,@PathVariable long id) {
        long uid=uid(a);tx.executeWithoutResult(status->{
            store.one("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",uid);store.plan(uid,id);
            if(!store.rows("SELECT task_id FROM generation_task WHERE plan_id=? AND status IN ('WAITING','RUNNING')",id).isEmpty())throw new ApiError(409,"TASK_ACTIVE","请等待修改任务结束后再删除");
            store.update("UPDATE travel_plan SET deleted=TRUE,update_time=CURRENT_TIMESTAMP WHERE plan_id=?",id);store.operation(uid,"PLAN_DELETE","PLAN",id,null);
        });return Map.of("success",true);
    }
    @GetMapping("/admin/tasks") Object adminTasks(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String status) {
        String select="SELECT task_id,user_id,status,phase,mode,error_code,error_summary,phase_timings,create_time,start_time,finish_time FROM generation_task";
        return status.isBlank()?store.page(select+" ORDER BY task_id DESC",page,size):store.page(select+" WHERE status=? ORDER BY task_id DESC",page,size,status);
    }
    @GetMapping("/admin/api-logs") Object apiLogs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String stage) {
        return stage.isBlank()?store.page("SELECT * FROM api_log ORDER BY log_id DESC",page,size):store.page("SELECT * FROM api_log WHERE stage=? ORDER BY log_id DESC",page,size,stage);
    }
    @GetMapping("/admin/operation-logs") Object operationLogs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return store.page("SELECT * FROM operation_log ORDER BY operation_id DESC",page,size); }
}

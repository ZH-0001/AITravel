package com.aitravel;

import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** SQL persistence boundary. All dynamic identifiers come from application constants. */
@Repository
public class TravelStore {
    private final JdbcTemplate db;
    public TravelStore(JdbcTemplate db) { this.db=db; }
    public List<Map<String,Object>> rows(String sql,Object...args) { return db.queryForList(sql,args); }
    public Map<String,Object> one(String sql,Object...args) { var r=rows(sql,args); if(r.isEmpty()) throw ApiError.notFound(); return r.getFirst(); }
    public int update(String sql,Object...args) { return db.update(sql,args); }
    public long insert(String sql,Object...args) {
        var key=new GeneratedKeyHolder();
        db.update(con -> { var stmt=con.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++) stmt.setObject(i+1,args[i]); return stmt; },key);
        // H2 may return timestamp defaults alongside the generated identifier.
        // Read the numeric key instead of assuming exactly one generated column.
        return key.getKeyList().getFirst().values().stream().filter(Number.class::isInstance)
                .map(Number.class::cast).findFirst().orElseThrow().longValue();
    }
    public Map<String,Object> plan(long uid,long id) { return one("SELECT * FROM travel_plan WHERE plan_id=? AND user_id=? AND deleted=FALSE",id,uid); }
    public Map<String,Object> task(long uid,long id) {
        var task=one("SELECT * FROM generation_task WHERE task_id=? AND user_id=?",id,uid);
        if(task.get("plan_id")!=null) plan(uid,number(task,"plan_id"));
        return task;
    }
    public void operation(long uid,String action,String type,long target,Long task) {
        update("INSERT INTO operation_log(actor_user_id,operation_type,target_type,target_id,task_id) VALUES(?,?,?,?,?)",uid,action,type,target,task);
    }
    public static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static String string(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
    public Map<String,Object> page(String sql,int page,int size,Object...args) {
        int p=Math.clamp(page,1,100000),s=Math.clamp(size,1,50);
        var values=new ArrayList<>(Arrays.asList(args)); values.add(s+1); values.add((p-1)*s);
        var found=rows(sql+" LIMIT ? OFFSET ?",values.toArray());
        return Map.of("items",found.size()>s?found.subList(0,s):found,"page",p,"hasMore",found.size()>s);
    }
}

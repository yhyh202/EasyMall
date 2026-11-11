package com.easymall;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Db {
    final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> rows(String sql,Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql,Object... args) { return rows(sql,args).stream().findFirst().orElseThrow(BusinessException::missing); }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql,Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> { var s=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); return s; },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static boolean bool(Map<String,Object> row,String key) { Object v=row.get(key); return v instanceof Boolean b ? b : ((Number)v).intValue()!=0; }
    public Api.Page<Map<String,Object>> page(String table,String where,List<Object> args,int page,int size) {
        if(page<1 || size<1 || size>100 || page>1000000) throw new BusinessException(400,"VALIDATION_ERROR","page 为 1..1000000，size 为 1..100");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args); queryArgs.add(size); queryArgs.add((page-1)*size);
        return new Api.Page<>(rows("SELECT * FROM "+table+where+" ORDER BY id DESC LIMIT ? OFFSET ?",queryArgs.toArray()),count,page,size);
    }
}

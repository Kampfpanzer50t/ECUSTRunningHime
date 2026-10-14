package cn.huati.assistant;

import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.security.SecureRandom;
import org.json.JSONObject;

/** Only read-only preparation may retry; creation is reserved per dated start time. */
final class DailyPreparation {
    static final long LEAD_MS=2*60*1000L,RETRY_MS=30*1000L;
    interface Environment {
        JSONObject settings()throws Exception;
        void save(JSONObject value)throws Exception;
        JSONObject config()throws Exception;
        JSONObject run()throws Exception;
        JSONObject location()throws Exception;
        void refresh()throws Exception;
        JSONObject points(double lat,double lng)throws Exception;
        long now();
    }
    static final class StopException extends IllegalStateException {
        StopException(String message){super(message);}
    }
    private final Environment environment;
    DailyPreparation(Environment environment){this.environment=environment;}
    static long target(JSONObject settings,long now){
        if(settings==null||!DailySchedule.validTime(DailySchedule.hour(settings),DailySchedule.minute(settings)))return 0;
        return Instant.ofEpochMilli(now).atZone(RunMath.ZONE).withHour(DailySchedule.hour(settings))
            .withMinute(DailySchedule.minute(settings)).withSecond(0).withNano(0).toInstant().toEpochMilli();
    }
    static long opening(long at){return Instant.ofEpochMilli(at).atZone(RunMath.ZONE).withHour(6).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli();}
    static boolean matches(JSONObject settings,long at){return at>0&&target(settings,at)==at;}
    private static boolean eligible(JSONObject settings,long now,long at){
        return DailySchedule.canAttempt(settings,now,at)&&now>=at-LEAD_MS;
    }
    static boolean due(JSONObject settings,long now,long at){
        if(!eligible(settings,now,at))return false;
        if(settings.optLong("prep_target_ms")!=at)return now<at+DailySchedule.ALARM_GRACE_MS;
        String phase=settings.optString("prep_status");
        if(phase.equals("ready"))return !ready(settings,now,at);
        return (phase.equals("waiting_open")||phase.equals("waiting_points")||phase.equals("waiting_location"))
            &&now>=settings.optLong("next_location_retry_ms");
    }
    static boolean waiting(JSONObject settings,long now,long at){
        if(!eligible(settings,now,at)||settings.optLong("prep_target_ms")!=at)return false;
        String phase=settings.optString("prep_status");
        return phase.equals("waiting_open")||phase.equals("waiting_points")||phase.equals("waiting_location")
            ||phase.equals("locating")||phase.equals("fetching")||phase.equals("ready")&&!ready(settings,now,at);
    }
    /** A persisted preparation takes priority over tomorrow's alarm, even after today's target. */
    static long wakeTarget(JSONObject settings,long now,long nextStart){
        long active=settings==null?0:settings.optLong("prep_target_ms");
        return waiting(settings,now,active)||eligible(settings,now,active)&&ready(settings,now,active)&&now>=active?active:nextStart;
    }
    static long nextWake(JSONObject settings,long now,long start){
        if(settings==null||!settings.optBoolean("enabled")||!matches(settings,start))return 0;
        if(waiting(settings,now,start))return Math.max(now+1000,settings.optLong("next_location_retry_ms"));
        if(eligible(settings,now,start)&&ready(settings,now,start)&&now>=start)return now+1000;
        if(settings.optLong("prep_target_ms")==start||start<=now)return 0;
        long wake=Math.max(now+1000,start-LEAD_MS);
        return wake<start?wake:0;
    }
    static boolean ready(JSONObject settings,long now,long at){
        if(settings==null||!settings.optBoolean("enabled")||!matches(settings,at)
            ||settings.optLong("prep_target_ms")!=at||!settings.optString("prep_status").equals("ready")
            ||now<at-LEAD_MS||!DailySchedule.day(now).equals(DailySchedule.day(at)))return false;
        JSONObject points=settings.optJSONObject("prepared_preview");long refreshed=settings.optLong("prepared_refresh_ms");
        return points!=null&&points.optString("student_id").equals(settings.optString("student_id"))
            &&refreshed>=Math.max(at-LEAD_MS,opening(at))&&refreshed<=points.optLong("captured_at")
            &&points.optLong("captured_at")>=Math.max(at-LEAD_MS,opening(at))&&points.optLong("captured_at")<=now
            &&now-points.optLong("captured_at")<DailySchedule.ALARM_GRACE_MS
            &&points.optJSONArray("points")!=null&&points.optJSONArray("points").length()==3;
    }
    static void clear(JSONObject settings){
        for(String key:new String[]{"prep_target_ms","prep_status","prep_message","prepared_preview","prepared_refresh_ms",
            "prepared_origin","next_prep_ms","next_location_retry_ms","origin_source","origin_name"})settings.remove(key);
    }
    static boolean recover(JSONObject settings)throws Exception{
        if(settings==null)return false;String phase=settings.optString("prep_status");
        if(!phase.equals("locating")&&!phase.equals("fetching"))return false;
        settings.remove("prepared_preview");settings.remove("prepared_refresh_ms");
        settings.put("prep_status",settings.optJSONObject("prepared_origin")==null?"waiting_location":"waiting_points")
            .put("next_location_retry_ms",0).put("prep_message","上次准备被中断，恢复后将刷新并重新获取本次三点");return true;
    }
    private void allowed(BooleanSupplier allowed,long at){
        if(!allowed.getAsBoolean()||!DailySchedule.day(environment.now()).equals(DailySchedule.day(at)))
            throw new StopException("本次自动准备已停止或日期已变化");
    }
    synchronized String prepare(long at,BooleanSupplier allowed)throws Exception{
        long now=environment.now();JSONObject settings=environment.settings();
        if(!due(settings,now,at)||!allowed.getAsBoolean())return "";
        if(settings.optLong("prep_target_ms")!=at){clear(settings);settings.put("prep_target_ms",at);}
        JSONObject origin=settings.optJSONObject("prepared_origin");
        if(origin!=null&&(origin.optLong("captured_at")<at-LEAD_MS||origin.optLong("captured_at")>now
            ||now-origin.optLong("captured_at")>=DailySchedule.ALARM_GRACE_MS))origin=null;
        settings.put("prep_status",origin==null?"locating":"fetching").put("prep_message",origin==null?"正在读取手机当前位置":"正在刷新校园跑并获取随机三点");
        settings.remove("prepared_preview");settings.remove("prepared_refresh_ms");
        environment.save(settings); // Reserve before location requests or any network access.
        try{
            JSONObject config=environment.config();new ApiClient(config);
            if(!config.optString("student_id").equals(settings.optString("student_id")))throw new StopException("登录账号已变化，请重新开启每日自动运行");
            if(RunClock.unresolved(environment.run()))throw new StopException("还有未完成、暂停或待核实的记录");
            if(origin==null){
                origin=CampusArea.choose(environment::location,new SecureRandom()).put("captured_at",environment.now());
                settings.put("prepared_origin",origin).put("origin_source",origin.optString("source","手机当前位置"));
                if(origin.has("origin_name"))settings.put("origin_name",origin.getString("origin_name"));else settings.remove("origin_name");
            }
            double lat=origin.getDouble("lat"),lng=origin.getDouble("lng");RunMath.coordinate(lat,lng);allowed(allowed,at);
            if(environment.now()<opening(at)){
                settings.put("prep_status","waiting_open").put("next_location_retry_ms",opening(at))
                    .put("prep_message","起点已准备，等待北京时间 06:00 开放后刷新并获取当天随机三点");
                environment.save(settings);return settings.getString("prep_message");
            }
            settings.put("prep_status","fetching").put("prep_message","正在刷新校园跑并重新获取随机三点");environment.save(settings);
            JSONObject points;
            try{
                environment.refresh();settings.put("prepared_refresh_ms",environment.now());allowed(allowed,at);
                points=environment.points(lat,lng);
            }catch(ApiClient.SessionException|StopException error){throw error;}
            catch(Exception error){
                allowed(allowed,at);settings.remove("prepared_refresh_ms");
                settings.put("prep_status","waiting_points").put("next_location_retry_ms",environment.now()+RETRY_MS)
                    .put("prep_message","校园跑刷新或点位请求未成功："+(error.getMessage()==null?"暂时无法联网":error.getMessage())+"；30 秒后继续尝试，可关闭自动运行取消");
                environment.save(settings);return settings.getString("prep_message");
            }
            if(!points.getString("student_id").equals(config.getString("student_id")))throw new StopException("点位所属账号与登录会话不一致");
            int metres=RunMath.automaticMetres(points.getJSONArray("points"),points.getDouble("lat"),points.getDouble("lng"),DailySchedule.METRES);
            // Validate the geometry now; the actual duration is chosen once when starting.
            RunMath.path(points.getJSONArray("points"),points.getDouble("lat"),points.getDouble("lng"),at,metres,(int)(DailyDuration.MIN_MS/1000));
            if(points.getLong("captured_at")<Math.max(at-LEAD_MS,opening(at))||points.getLong("captured_at")>environment.now())throw new StopException("本次三点准备结果无效");
            allowed(allowed,at);
            settings.remove("next_location_retry_ms");settings.put("prepared_preview",new JSONObject(points.toString())).put("prep_status","ready")
                .put("prep_message","校园跑已刷新，起点和当天随机三点已准备；到时随机运行 12–16 分钟，计满后提交");
            environment.save(settings);return settings.getString("prep_message");
        }catch(Exception error){
            settings.remove("prepared_preview");settings.remove("prepared_refresh_ms");settings.remove("next_location_retry_ms");settings.put("prep_status","failed");
            String message="本次自动准备停止："+(error.getMessage()==null?"请检查登录和点位结果":error.getMessage())+"；请处理原因后另设启动时间";
            DailySchedule.reserve(settings,at,environment.now());
            settings.put("prep_message",message).put("last_status","failed").put("last_message",message);
            environment.save(settings);return message;
        }
    }
}

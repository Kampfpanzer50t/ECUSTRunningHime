package cn.huati.assistant;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.JSONObject;
import org.json.JSONArray;

/** Each dated start time is reserved before record creation, regardless of trigger source. */
final class DailySchedule {
    static final int METRES=2200,STEPS=2600;
    static final int DEFAULT_HOUR=6,DEFAULT_MINUTE=15,MIN_HOUR=6,MAX_HOUR=21;
    static final long ALARM_GRACE_MS=5*60*1000L;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    interface Environment {
        JSONObject settings() throws Exception;
        void save(JSONObject settings) throws Exception;
        JSONObject config() throws Exception;
        JSONObject run() throws Exception;
        long start(double lat,double lng,String day,long durationMillis) throws Exception;
    }
    static final class Result {
        final boolean attempted,started;
        final long recordId;
        final String message;
        Result(boolean attempted,boolean started,long recordId,String message){
            this.attempted=attempted;this.started=started;this.recordId=recordId;this.message=message;
        }
    }
    private final Environment environment;
    private final LongSupplier durationSource;
    DailySchedule(Environment environment){this(environment,DailyDuration::randomMillis);}
    DailySchedule(Environment environment,LongSupplier durationSource){this.environment=environment;this.durationSource=durationSource;}
    static String day(long now){return Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().toString();}
    private static String slot(long at){return String.format(java.util.Locale.CHINA,"%02d:%02d",Instant.ofEpochMilli(at).atZone(ZONE).getHour(),Instant.ofEpochMilli(at).atZone(ZONE).getMinute());}
    private static String reservationDay(JSONObject settings){
        return settings==null?"":settings.optInt("schedule_schema")>=2?settings.optString("attempt_day",""):settings.optString("last_day","");
    }
    private static String legacySlot(JSONObject settings){
        long prepared=settings.optLong("prep_target_ms");
        return prepared>0&&day(prepared).equals(settings.optString("last_day"))?slot(prepared):timeLabel(settings);
    }
    static boolean reserved(JSONObject settings,long at){
        if(settings==null||at<=0)return false;
        String savedDay=reservationDay(settings),date=day(at);
        if(savedDay.isEmpty()||date.compareTo(savedDay)>0)return false;
        if(date.compareTo(savedDay)<0)return true; // Never replay earlier dates after clock rollback.
        if(settings.optInt("schedule_schema")<2)return slot(at).equals(legacySlot(settings));
        JSONArray times=settings.optJSONArray("attempted_times");
        if(times!=null)for(int i=0;i<times.length();i++)if(slot(at).equals(times.optString(i)))return true;
        return false;
    }
    static boolean canAttempt(JSONObject settings,long now,long at){
        return settings!=null&&settings.optBoolean("enabled",false)&&validTime(hour(settings),minute(settings))
            &&DailyPreparation.matches(settings,at)&&day(now).equals(day(at))&&!reserved(settings,at)
            &&now>=settings.optLong("last_attempt_ms",0);
    }
    /** Preserve the old attempted time when upgrading; allow a different time later the same day. */
    static boolean migrate(JSONObject settings)throws Exception{
        if(settings==null||settings.optInt("schedule_schema")>=2)return false;
        String previous=settings.optString("last_day","");JSONArray times=new JSONArray();
        if(!previous.isEmpty()){
            String oldSlot=legacySlot(settings);times.put(oldSlot);settings.put("attempt_day",previous);
            if(!settings.has("last_target_ms")){
                String[] parts=oldSlot.split(":");settings.put("last_target_ms",LocalDate.parse(previous)
                    .atTime(Integer.parseInt(parts[0]),Integer.parseInt(parts[1])).atZone(ZONE).toInstant().toEpochMilli());
            }
        }
        settings.put("schedule_schema",2).put("attempted_times",times);return true;
    }
    static void reserve(JSONObject settings,long at,long now)throws Exception{
        migrate(settings);String date=day(at);JSONArray times=settings.optJSONArray("attempted_times");
        if(times==null||!date.equals(settings.optString("attempt_day")))times=new JSONArray();
        boolean found=false;for(int i=0;i<times.length();i++)if(slot(at).equals(times.optString(i)))found=true;
        if(!found)times.put(slot(at));
        settings.put("attempt_day",date).put("attempted_times",times).put("last_target_ms",at)
            .put("last_day",date).put("last_attempt_ms",now);
    }
    static int hour(JSONObject settings){return settings==null?DEFAULT_HOUR:settings.optInt("hour",DEFAULT_HOUR);}
    static int minute(JSONObject settings){return settings==null?DEFAULT_MINUTE:settings.optInt("minute",DEFAULT_MINUTE);}
    static boolean validTime(int hour,int minute){return hour>=MIN_HOUR&&hour<=MAX_HOUR&&minute>=0&&minute<60&&(hour<MAX_HOUR||minute==0);}
    static void validateTime(int hour,int minute){if(!validTime(hour,minute))throw new IllegalArgumentException("自动启动时间需在北京时间 06:00–21:00 之间；21:00 是最晚时间");}
    static String timeLabel(JSONObject settings){return String.format(java.util.Locale.CHINA,"%02d:%02d",hour(settings),minute(settings));}
    static boolean due(JSONObject settings,long now){
        if(!canAttempt(settings,now,DailyPreparation.target(settings,now)))return false;
        ZonedDateTime time=Instant.ofEpochMilli(now).atZone(ZONE);
        // Opening later does not backfill a missed run. Clock rollback cannot repeat an attempt.
        return time.getHour()==hour(settings)&&time.getMinute()==minute(settings);
    }
    static boolean dueForAlarm(JSONObject settings,long now,long scheduledAt){
        if(scheduledAt<=0||!canAttempt(settings,now,scheduledAt)||now<scheduledAt||now-scheduledAt>=ALARM_GRACE_MS)return false;
        ZonedDateTime target=Instant.ofEpochMilli(scheduledAt).atZone(ZONE);
        return target.getHour()==hour(settings)&&target.getMinute()==minute(settings)&&target.getSecond()==0&&target.getNano()==0&&day(now).equals(day(scheduledAt));
    }
    static long nextAlarm(JSONObject settings,long now){
        if(settings==null||!settings.optBoolean("enabled",false))return 0;
        int selectedHour=hour(settings),selectedMinute=minute(settings);validateTime(selectedHour,selectedMinute);
        ZonedDateTime target=Instant.ofEpochMilli(now).atZone(ZONE).withHour(selectedHour).withMinute(selectedMinute).withSecond(0).withNano(0);
        if(target.toInstant().toEpochMilli()<=now)target=target.plusDays(1);
        String savedDay=reservationDay(settings);
        if(!savedDay.isEmpty()){
            var savedDate=LocalDate.parse(savedDay).atTime(selectedHour,selectedMinute).atZone(ZONE);
            if(savedDate.isAfter(target))target=savedDate;
        }
        long lastAttempt=settings.optLong("last_attempt_ms",0);
        if(target.toInstant().toEpochMilli()<lastAttempt){
            target=Instant.ofEpochMilli(lastAttempt).atZone(ZONE).withHour(selectedHour).withMinute(selectedMinute).withSecond(0).withNano(0);
            if(target.toInstant().toEpochMilli()<lastAttempt)target=target.plusDays(1);
        }
        if(reserved(settings,target.toInstant().toEpochMilli()))target=target.plusDays(1);
        return target.toInstant().toEpochMilli();
    }
    static JSONObject enabled(JSONObject previous,JSONObject config,double lat,double lng)throws Exception{
        new ApiClient(config);RunMath.coordinate(lat,lng);
        if(config.optString("student_id").isEmpty())throw new IllegalStateException("请先登录并验证账号");
        JSONObject settings=previous==null?new JSONObject():new JSONObject(previous.toString());
        migrate(settings);
        int selectedHour=hour(settings),selectedMinute=minute(settings);validateTime(selectedHour,selectedMinute);
        return settings.put("enabled",true).put("hour",selectedHour).put("minute",selectedMinute)
            .put("student_id",config.getString("student_id")).put("lat",lat).put("lng",lng);
    }
    static JSONObject withTime(JSONObject previous,int selectedHour,int selectedMinute)throws Exception{
        validateTime(selectedHour,selectedMinute);
        JSONObject settings=previous==null?new JSONObject():new JSONObject(previous.toString());
        migrate(settings); // Migrate before replacing the old selected time.
        if(hour(settings)!=selectedHour||minute(settings)!=selectedMinute){
            settings.remove("next_alarm_ms");settings.remove("alarm_error");settings.remove("last_missed_day");settings.remove("last_missed_target_ms");
            DailyPreparation.clear(settings);
        }
        return settings.put("hour",selectedHour).put("minute",selectedMinute);
    }
    static JSONObject disabled(JSONObject previous)throws Exception{
        JSONObject settings=previous==null?new JSONObject():new JSONObject(previous.toString());
        migrate(settings);
        DailyPreparation.clear(settings);
        return settings.put("enabled",false);
    }
    synchronized Result attempt(long now,BooleanSupplier allowed)throws Exception{return attempt(now,allowed,0);}
    synchronized Result attempt(long now,BooleanSupplier allowed,long scheduledAt)throws Exception{
        JSONObject settings=environment.settings();
        long target=scheduledAt>0?scheduledAt:DailyPreparation.target(settings,now);
        if(DailyPreparation.waiting(settings,now,target))return new Result(false,false,0,"");
        boolean prepared=scheduledAt>0&&now>=scheduledAt&&canAttempt(settings,now,scheduledAt)&&DailyPreparation.ready(settings,now,scheduledAt);
        boolean due=scheduledAt>0?(dueForAlarm(settings,now,scheduledAt)||prepared):due(settings,now);
        if(!due||!allowed.getAsBoolean())return new Result(false,false,0,"");
        long durationMillis=durationSource.getAsLong();DailyDuration.validate(durationMillis);
        String date=day(now);
        reserve(settings,target,now);
        settings.put("last_duration_ms",durationMillis).put("last_status","preparing").put("last_message","正在创建本次自动记录；同一天的同一启动时间不会重复创建");
        settings.remove("last_record_id");
        environment.save(settings); // Fail closed: do not issue even a point request if this fails.
        long id=0;String message;String status;
        try{
            JSONObject config=environment.config();
            if(config==null)throw new IllegalStateException("缺少登录会话，请先登录");
            if(!config.optString("student_id").equals(settings.optString("student_id")))
                throw new IllegalStateException("登录账号已变化，请重新启用每日自动运行");
            new ApiClient(config);
            if(RunClock.unresolved(environment.run())){
                status="skipped";message="本次计划已跳过：还有未完成、暂停或待核实的记录，请先处理当前记录，再设置新的启动时间";
            }else if(!allowed.getAsBoolean()){
                status="skipped";message="本次计划已停止，同一启动时间不会补跑；可另设后续启动时间";
            }else{
                double lat=0,lng=0;
                if(!settings.optString("location_mode").equals("phone")){lat=settings.getDouble("lat");lng=settings.getDouble("lng");RunMath.coordinate(lat,lng);}
                id=environment.start(lat,lng,date,durationMillis);
                if(id<=0)throw new IllegalStateException("创建结果待核实，请查询服务器记录");
                status="started";message="已自动开始经过三点、至少 2.1 公里的模拟，本次随机时长 "+DailyDuration.label(durationMillis)+"，计满后校验并提交";
            }
        }catch(Exception error){
            status="failed";message="本次自动运行未完成："+(error.getMessage()==null?"请检查登录和网络，并查询当前记录":error.getMessage())+"；同一启动时间不会自动重试";
        }
        settings.put("last_status",status).put("last_message",message);
        if(settings.optString("location_mode").equals("phone")){
            settings.remove("prepared_preview");settings.put("prep_status","consumed")
                .put("prep_message",id>0?"自动起点和三点已用于当前记录":"本次自动准备已结束，请查看今日任务结果");
        }
        if(id>0)settings.put("last_record_id",id);
        try{environment.save(settings);}catch(Exception error){
            // A created record still needs its timer even when saving the outcome fails.
            message+="（自动任务结果保存失败，请查看当前记录；本次启动时间的尝试标记已保存）";
        }
        return new Result(true,id>0,id,message);
    }
}

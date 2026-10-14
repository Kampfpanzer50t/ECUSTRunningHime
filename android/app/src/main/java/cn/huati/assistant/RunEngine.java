package cn.huati.assistant;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.function.BooleanSupplier;
import java.util.concurrent.Callable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

final class RunEngine {
    static RunEngine get(Context context) {return ((HuatiApplication)context.getApplicationContext()).engine();}
    private final Context context;
    private final SecureStore store;
    private RoadRoute roads;
    RunEngine(Context context){
        this.context=context;store=new SecureStore(context);
        try(var input=context.getAssets().open("campus-roads.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            CampusArea.roads(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
        }catch(Exception ignored){}
        try{JSONObject state=run();if(RunClock.migrate(state,System.currentTimeMillis()))save(state);markInterrupted();markDailyInterrupted();}catch(Exception ignored){}
    }
    JSONObject config() throws Exception {return store.read("config");}
    JSONObject run() throws Exception {return store.read("run");}
    JSONObject results() throws Exception {return store.read("results");}
    JSONObject preview() throws Exception {return store.read("points");}
    JSONObject dailySettings() throws Exception {return store.read("daily_schedule");}
    JSONObject routePreview()throws Exception{return store.read("route_preview");}
    boolean roadMode()throws Exception{JSONObject settings=store.read("preferences");return settings!=null&&settings.optBoolean("road_mode");}
    boolean mapPointsVisible()throws Exception{JSONObject settings=store.read("preferences");return settings!=null&&settings.optBoolean("map_points");}
    synchronized void setMapPointsVisible(boolean enabled)throws Exception{
        JSONObject settings=store.read("preferences");if(settings==null)settings=new JSONObject();
        settings.put("map_points",enabled);store.write("preferences",settings);
    }
    JSONObject campusPointCatalog()throws Exception{
        JSONObject value=store.read("campus_points");return value==null?CampusPoints.reference():value;
    }
    synchronized void importCampusPoints(String text)throws Exception{store.write("campus_points",CampusPoints.importTable(text,System.currentTimeMillis()));}
    private void collectCampusPoints(JSONArray points){
        try{store.write("campus_points",CampusPoints.merge(store.read("campus_points"),points,System.currentTimeMillis()));}
        catch(Exception error){RunDiagnostics.error(context,"map_points_cache_error",error);}
    }
    synchronized void setRoadMode(boolean enabled)throws Exception{
        JSONObject settings=store.read("preferences");if(settings==null)settings=new JSONObject();
        settings.put("road_mode",enabled);store.write("preferences",settings);store.remove("route_preview");
    }
    private JSONObject plan(JSONObject preview,int metres,boolean scheduled)throws Exception{
        if(roadMode()){
            if(roads==null)try(var input=context.getAssets().open("campus-roads.json")){
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                roads=new RoadRoute(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
            }
            return roads.plan(preview.getJSONArray("points"),preview.getDouble("lat"),preview.getDouble("lng"),metres,scheduled,new SecureRandom());
        }
        int actual=scheduled?RunMath.automaticMetres(preview.getJSONArray("points"),preview.getDouble("lat"),preview.getDouble("lng"),metres):metres;
        return new JSONObject().put("distance",actual).put("requested_distance",metres).put("road_mode",false).put("scheduled_route",scheduled);
    }
    synchronized void previewRoute(int metres,int seconds)throws Exception{
        previewRouteMillis(metres,seconds*1000L);
    }
    synchronized void previewRouteMillis(int metres,long durationMillis)throws Exception{
        ManualDuration.validate(durationMillis);int seconds=Math.toIntExact((durationMillis+999)/1000);
        RunMath.parameters(metres,seconds,2600);
        JSONObject p=ensureManualPoints();
        long age=System.currentTimeMillis()-p.getLong("captured_at");
        if(age<0||age>1800000)throw new IllegalStateException("点位已过期，请重新获取随机三点");
        JSONObject route=plan(p,metres,false);route.put("point_snapshot",p.toString()).put("duration",seconds).put("duration_ms",durationMillis).put("points",p.getJSONArray("points"));
        if(!route.optBoolean("road_mode"))route.put("preview_path",RunMath.path(p.getJSONArray("points"),p.getDouble("lat"),p.getDouble("lng"),0,metres,seconds));
        store.write("route_preview",route);
    }
    synchronized void setDailyEnabled(boolean enabled,double lat,double lng)throws Exception{
        JSONObject previous=dailySettings();
        JSONObject value=enabled?DailySchedule.enabled(previous,config(),lat,lng):DailySchedule.disabled(previous);
        value.put("location_mode","phone");value.remove("lat");value.remove("lng");
        if(enabled&&(previous==null||!previous.optBoolean("enabled")))value.put("enabled_at",System.currentTimeMillis());
        store.write("daily_schedule",value);
        syncDailyAlarm();
    }
    synchronized void setDailyTime(int hour,int minute)throws Exception{
        DailySchedule.validateTime(hour,minute);
        JSONObject previous=dailySettings();
        JSONObject value=DailySchedule.withTime(previous,hour,minute);
        if(previous==null)value.put("enabled",false);
        store.write("daily_schedule",value);
        syncDailyAlarm();
    }
    synchronized void syncDailyAlarm()throws Exception{
        JSONObject settings=dailySettings();
        if(settings==null){RunAlarms.cancelDaily(context);return;}
        DailySchedule.migrate(settings);
        DailyPreparation.recover(settings);
        settings.put("location_mode","phone");settings.remove("lat");settings.remove("lng");
        long now=System.currentTimeMillis(),previous=settings.optLong("next_alarm_ms",0);
        if(settings.optBoolean("enabled")&&previous>0&&now>=previous+DailySchedule.ALARM_GRACE_MS&&
            !DailySchedule.reserved(settings,previous)&&DailyPreparation.matches(settings,previous)&&!DailyPreparation.waiting(settings,now,previous)
            &&!DailyPreparation.ready(settings,now,previous))settings.put("last_missed_day",DailySchedule.day(previous)).put("last_missed_target_ms",previous);
        long next=0;String problem="";
        try{
            next=RunAlarms.scheduleDaily(context,settings,now);
            if(settings.optBoolean("enabled")&&next==0)problem="尚未允许锁屏定时，请点击“允许锁屏定时”；当前只在首页前台触发";
        }catch(Exception error){
            RunDiagnostics.error(context,"daily_alarm_error",error);
            RunAlarms.cancelDaily(context);problem="系统定时注册失败，请重新打开 App 检查定时权限";
        }
        if(next>0&&next!=previous)RunDiagnostics.event(context,"daily_alarm_set",new JSONObject().put("scheduled_at",next));
        long prepTarget=DailyPreparation.wakeTarget(settings,now,next);
        settings.put("next_alarm_ms",next).put("next_prep_ms",DailyPreparation.nextWake(settings,now,prepTarget)).put("alarm_error",problem);store.write("daily_schedule",settings);
    }
    private void disableDailyForDifferentAccount(String id)throws Exception{
        JSONObject old=config();if(old!=null&&!old.optString("student_id").equals(id))store.remove("campus_points");
        JSONObject settings=dailySettings();
        if(settings!=null&&!settings.optString("student_id").equals(id)){
            settings=DailySchedule.disabled(settings);settings.remove("student_id");settings.remove("lat");settings.remove("lng");
            store.write("daily_schedule",settings);
        }
    }
    private void markDailyInterrupted()throws Exception{
        JSONObject settings=dailySettings();
        if(DailySchedule.migrate(settings))store.write("daily_schedule",settings);
        if(DailyPreparation.recover(settings))store.write("daily_schedule",settings);
        if(settings!=null&&settings.optString("last_status").equals("preparing")){
            settings.put("last_status","interrupted").put("last_message","上次自动创建被中断，请查询当前记录；同一启动时间不会重复尝试");
            store.write("daily_schedule",settings);
        }
    }
    synchronized String prepareDaily(long target,Callable<JSONObject> location,BooleanSupplier allowed)throws Exception{
        DailyPreparation preparation=new DailyPreparation(new DailyPreparation.Environment(){
            @Override public JSONObject settings()throws Exception{return dailySettings();}
            @Override public void save(JSONObject value)throws Exception{store.write("daily_schedule",value);}
            @Override public JSONObject config()throws Exception{return RunEngine.this.config();}
            @Override public JSONObject run()throws Exception{return RunEngine.this.run();}
            @Override public JSONObject location()throws Exception{return location.call();}
            @Override public long now(){return System.currentTimeMillis();}
            @Override public void refresh()throws Exception{
                RunDiagnostics.event(context,"daily_refresh_request",RunDiagnostics.deviceState(context));
                ApiClient c=client();JSONObject user=(JSONObject)ApiClient.success(c.request("/xcxapi/getUserInfo/"));
                if(!String.valueOf(user.get("id")).equals(config().getString("student_id")))throw new DailyPreparation.StopException("服务器账号与自动设置不一致");
                ApiClient.success(c.request("/xcxapi/RunningData/"));
                RunDiagnostics.event(context,"daily_refresh_ready");
            }
            @Override public JSONObject points(double lat,double lng)throws Exception{
                RunDiagnostics.event(context,"points_request",RunDiagnostics.deviceState(context));
                JSONObject points=fetchPoints(lat,lng);RunDiagnostics.event(context,"points_ready");return points;
            }
        });
        try{
            String message=preparation.prepare(target,allowed);JSONObject settings=dailySettings();
            if(!message.isEmpty()&&settings!=null)RunDiagnostics.event(context,"daily_preparation_"+settings.optString("prep_status"));
            if(!message.isEmpty()&&settings!=null&&!settings.optString("origin_source").isEmpty())
                RunDiagnostics.event(context,"origin_selected",new JSONObject().put("source",settings.getString("origin_source")));
            return message;
        }
        finally{try{syncDailyAlarm();}catch(Exception error){RunDiagnostics.error(context,"daily_alarm_error",error);}}
    }
    synchronized DailySchedule.Result startDaily(BooleanSupplier allowed)throws Exception{return startDaily(0,allowed);}
    synchronized DailySchedule.Result startDaily(long scheduledAt,BooleanSupplier allowed)throws Exception{
        DailySchedule scheduler=new DailySchedule(new DailySchedule.Environment(){
            @Override public JSONObject settings()throws Exception{return dailySettings();}
            @Override public void save(JSONObject value)throws Exception{store.write("daily_schedule",value);}
            @Override public JSONObject config()throws Exception{return RunEngine.this.config();}
            @Override public JSONObject run()throws Exception{return RunEngine.this.run();}
            @Override public long start(double lat,double lng,String day,long durationMillis)throws Exception{
                JSONObject settings=dailySettings();long now=System.currentTimeMillis();
                long target=scheduledAt>0?scheduledAt:DailyPreparation.target(settings,now);
                if(!DailyPreparation.ready(settings,now,target))throw new IllegalStateException("本次自动定位和随机三点尚未准备完成，请检查定位权限及后台排查报告");
                // Use exactly this scheduled preparation, even if the manual preview changed meanwhile.
                store.write("points",new JSONObject(settings.getJSONObject("prepared_preview").toString()));
                return createRecord(DailySchedule.METRES,durationMillis,DailySchedule.STEPS,day,allowed);
            }
        });
        try{return scheduler.attempt(System.currentTimeMillis(),allowed,scheduledAt);}
        finally{try{syncDailyAlarm();}catch(Exception error){RunDiagnostics.error(context,"daily_alarm_error",error);}}
    }
    private ApiClient client() throws Exception {return new ApiClient(config());}
    private void save(JSONObject state) throws Exception {
        store.write("run",state);RunAlarms.syncFinish(context,state,System.currentTimeMillis());
    }
    static boolean unresolved(JSONObject state) {
        return RunClock.unresolved(state);
    }
    synchronized void importConfig(String text) throws Exception {
        JSONObject value=new JSONObject(text);
        if(!ApiClient.HOST.equals(value.optString("host"))) throw new IllegalArgumentException("请导入华理跑步姬的会话文件");
        JSONObject clean=new JSONObject().put("host",ApiClient.HOST).put("headers",ApiClient.cleanHeaders(value.getJSONObject("headers")));
        JSONObject location=value.optJSONObject("location");
        if(location!=null) {RunMath.coordinate(location.getDouble("lat"),location.getDouble("lng"));clean.put("location",location);}
        JSONObject user=(JSONObject)ApiClient.success(new ApiClient(clean).request("/xcxapi/getUserInfo/"));
        JSONObject state=run();String id=LoginProtocol.checkAccount(value,user,state);
        clean.put("student_id",id);
        if(value.optLong("recent_record_id",0)>0)clean.put("recent_record_id",value.getLong("recent_record_id"));
        disableDailyForDifferentAccount(id);store.write("config",clean);store.remove("points");store.remove("route_preview");
        if(!unresolved(state)) {store.remove("run");store.remove("results");}
        syncDailyAlarm();RunAlarms.syncFinish(context,run(),System.currentTimeMillis());
    }
    synchronized long smsNextAllowed() throws Exception {
        JSONObject meta=store.read("login_meta");return meta==null?0:meta.optLong("sms_next_allowed",0);
    }
    synchronized void sendLoginCode(String phone) throws Exception {
        LoginProtocol protocol=new LoginProtocol(ApiClient::loginRequest,System::currentTimeMillis);
        long previous=smsNextAllowed();
        if(previous>System.currentTimeMillis())throw new IllegalStateException("请等待 "+((previous-System.currentTimeMillis()+999)/1000)+" 秒后再获取验证码");
        LoginProtocol.phone(phone);
        // Reserve the cooldown before sending: a timeout may still have sent the SMS.
        store.write("login_meta",new JSONObject().put("sms_next_allowed",System.currentTimeMillis()+60000));
        protocol.sendCode(phone,previous);
    }
    synchronized void phoneLogin(String phone,String secret,boolean byCode) throws Exception {
        LoginProtocol protocol=new LoginProtocol(ApiClient::loginRequest,System::currentTimeMillis);
        JSONObject value=protocol.login(phone,secret,byCode);
        JSONObject user=(JSONObject)ApiClient.success(new ApiClient(value).request("/xcxapi/getUserInfo/"));
        JSONObject state=run();String id=LoginProtocol.checkAccount(value,user,state);
        value.put("student_id",id);JSONObject previous=config();
        if(previous!=null&&id.equals(previous.optString("student_id"))) {
            if(previous.has("location"))value.put("location",previous.getJSONObject("location"));
            if(previous.has("recent_record_id"))value.put("recent_record_id",previous.getLong("recent_record_id"));
        }
        disableDailyForDifferentAccount(id);store.write("config",value);store.remove("points");store.remove("route_preview");
        if(!unresolved(state)){store.remove("run");store.remove("results");}
        syncDailyAlarm();RunAlarms.syncFinish(context,run(),System.currentTimeMillis());
    }
    synchronized String exportSession() throws Exception {return LoginProtocol.exportConfig(config(),run(),System.currentTimeMillis());}
    synchronized void clearConfig() throws Exception {
        if(unresolved(run())) throw new IllegalStateException("还有未结束或待核实的记录，请先处理该记录");
        disableDailyForDifferentAccount("");
        store.remove("config");store.remove("points");store.remove("route_preview");store.remove("results");store.remove("run");store.remove("discarded");store.remove("campus_points");
        syncDailyAlarm();RunAlarms.cancelFinish(context);
    }
    synchronized void loadPoints(double lat,double lng) throws Exception {
        store.write("points",fetchPoints(lat,lng));store.remove("route_preview");
    }
    private JSONObject fetchPoints(double lat,double lng)throws Exception{
        if(unresolved(run()))throw new IllegalStateException("请先处理当前记录，避免更换本次点位");
        JSONObject origin=CampusArea.safeGcj(lat,lng,new SecureRandom());lat=origin.getDouble("lat");lng=origin.getDouble("lng");
        RunMath.coordinate(lat,lng);ApiClient c=client();JSONObject user=(JSONObject)ApiClient.success(c.request("/xcxapi/getUserInfo/"));
        JSONObject result=c.request("/xcxtapi/activity/randrunInfo?lat="+ApiClient.query(Double.toString(lat))+"&lng="+ApiClient.query(Double.toString(lng)));
        JSONArray points=(JSONArray)ApiClient.success(result);
        if(points.length()!=3)throw new IllegalStateException("服务器没有返回三个点位");
        if(!String.valueOf(user.get("id")).equals(String.valueOf(result.get("student_id")))||!String.valueOf(user.get("id")).equals(config().getString("student_id")))throw new DailyPreparation.StopException("点位所属账号与登录会话不一致");
        double startLat=result.optDouble("lat",lat),startLng=result.optDouble("lng",lng);
        if(!CampusArea.validGcj(startLat,startLng)){startLat=lat;startLng=lng;}
        collectCampusPoints(points);
        return new JSONObject().put("points",points).put("lat",startLat).put("lng",startLng)
            .put("student_id",String.valueOf(user.get("id"))).put("captured_at",System.currentTimeMillis());
    }
    synchronized void create(int metres,int seconds,int steps) throws Exception {
        createRecord(metres,seconds*1000L,steps,null,()->true);
    }
    synchronized void createMillis(int metres,long durationMillis,int steps)throws Exception{
        ManualDuration.validate(durationMillis);ensureManualPoints();createRecord(metres,durationMillis,steps,null,()->true);
    }
    private JSONObject ensureManualPoints()throws Exception{
        JSONObject p=preview();long now=System.currentTimeMillis();
        if(p==null||!CampusArea.validGcj(p.optDouble("lat",Double.NaN),p.optDouble("lng",Double.NaN))||now<p.optLong("captured_at")||now-p.optLong("captured_at")>1800000){
            double lat=p==null?Double.NaN:p.optDouble("lat",Double.NaN),lng=p==null?Double.NaN:p.optDouble("lng",Double.NaN);
            loadPoints(lat,lng);p=preview();
        }
        return p;
    }
    private long createRecord(int metres,long durationMillis,int steps,String scheduledDay,BooleanSupplier allowed)throws Exception{
        if(unresolved(run()))throw new IllegalStateException("还有待处理的记录，请先查询状态");
        if(scheduledDay!=null)DailyDuration.validate(durationMillis);
        int seconds=Math.toIntExact((durationMillis+999)/1000);
        RunMath.parameters(metres,seconds,steps);
        JSONObject p=preview();long now=System.currentTimeMillis();
        if(p==null||now-p.getLong("captured_at")>1800000||now<p.getLong("captured_at"))throw new IllegalStateException("点位已过期，请重新获取随机三点");
        JSONArray points=p.getJSONArray("points");int requested=metres;JSONObject route=routePreview();
        boolean scheduled=scheduledDay!=null;
        if(route==null||!route.optString("point_snapshot").equals(p.toString())||route.optInt("requested_distance")!=requested||route.optBoolean("road_mode")!=roadMode()||route.optBoolean("scheduled_route")!=scheduled)route=plan(p,requested,scheduled);
        metres=route.getInt("distance");
        if(!route.optBoolean("road_mode"))RunMath.path(points,p.getDouble("lat"),p.getDouble("lng"),now,metres,seconds);
        ApiClient c=client();JSONObject user=(JSONObject)ApiClient.success(c.request("/xcxapi/getUserInfo/"));String id=String.valueOf(user.get("id"));
        if(!id.equals(p.getString("student_id")))throw new IllegalStateException("登录账号已变化，请重新获取三点");
        if(scheduledDay!=null&&!id.equals(config().getString("student_id")))throw new IllegalStateException("服务器账号与自动设置不同，请重新登录并开启自动运行");
        double exactSeconds=durationMillis/1000.0;
        double pace=exactSeconds/(metres/1000.0),frequency=steps/(exactSeconds/60.0);
        boolean paceWarning=metres<Math.max(2000,user.optInt("shortest_distance",2000))||pace<user.optDouble("min_pace",180)||pace>user.optDouble("max_pace",1000);
        if(paceWarning&&!scheduled)throw new IllegalArgumentException("里程或配速不在服务器允许的范围内");
        if(frequency<user.optDouble("min_step_frequency",30)||frequency>user.optDouble("max_step_frequency",400))throw new IllegalArgumentException("步数与时长不符合服务器步频范围");
        if(!allowed.getAsBoolean())throw new IllegalStateException("本次自动任务已停止，同一启动时间不会补跑");
        now=System.currentTimeMillis();
        JSONObject state=new JSONObject().put("status","create_pending").put("mode","simulated").put("student_id",id)
            .put("points",points).put("lat",p.getDouble("lat")).put("lng",p.getDouble("lng")).put("start_ms",now)
            .put("elapsed_start",SystemClock.elapsedRealtime()).put("boot",Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1))
            .put("distance",metres).put("duration",seconds).put("steps",steps).put("auto_submit",true).put("pauses",new JSONArray());
        state.put("requested_distance",requested).put("road_mode",route.optBoolean("road_mode")).put("scheduled_route",scheduled);
        if(paceWarning)state.put("preflight_warning","本次三点路线的里程或配速可能不符合服务器成绩要求，结束后仍会提交");
        if(route.has("route_samples"))state.put("route_samples",route.getJSONArray("route_samples"));
        state.put("duration_ms",durationMillis);if(scheduledDay!=null)state.put("scheduled_day",scheduledDay);
        save(state);
        try {
            RunDiagnostics.event(context,"create_request",RunDiagnostics.deviceState(context));
            JSONObject created=(JSONObject)ApiClient.success(c.request("/xcxapi/createLine/",new JSONObject().put("student_id",user.get("id")).put("pass_point",points)));
            if(created.optLong("record_id",0)<=0)throw new IllegalStateException("服务器没有返回记录编号");
            state.put("record_id",created.getLong("record_id")).put("status","created");save(state);
            RunDiagnostics.event(context,"created",new JSONObject().put("expected_end",now+durationMillis));
            return created.getLong("record_id");
        } catch(Exception e){RunDiagnostics.error(context,"background_error",e);state.put("status","create_unknown").put("auto_submit",false);save(state);throw new IllegalStateException("创建结果待核实。请查看小程序运动记录；App 不会重复创建。",e);}
    }
    synchronized void pauseSimulation() throws Exception {
        JSONObject state=run();RunClock.pause(state,System.currentTimeMillis());save(state);
    }
    synchronized void serviceError(long expectedRecord,String message) throws Exception {
        JSONObject state=run();
        if(state==null||state.optLong("record_id",0)!=expectedRecord||!unresolved(state))return;
        if(state.optString("status").equals("created"))RunClock.pause(state,System.currentTimeMillis());
        state.put("auto_submit",false).put("error",message);save(state);
    }
    synchronized void resumeSimulation() throws Exception {
        JSONObject state=run();RunClock.resume(state,System.currentTimeMillis());save(state);
    }
    synchronized void discardSimulation(long expectedRecord) throws Exception {
        JSONObject state=run();
        if(state==null||state.optLong("record_id",0)!=expectedRecord)throw new IllegalStateException("当前记录已经变化，请重新检查后再舍弃");
        RunClock.discard(state,System.currentTimeMillis());save(state);store.remove("points");store.remove("route_preview");
        JSONObject archive=store.read("discarded");JSONArray previous=archive==null?new JSONArray():archive.optJSONArray("runs");
        if(previous==null)previous=new JSONArray();JSONArray recent=new JSONArray();
        for(int i=Math.max(0,previous.length()-49);i<previous.length();i++)recent.put(previous.getJSONObject(i));
        recent.put(state);store.write("discarded",new JSONObject().put("runs",recent));
    }
    synchronized JSONObject finishAutomatically(long expectedRecord) throws Exception {
        if(!RunClock.automaticAllowed(run(),expectedRecord))return null;
        finish();return run();
    }
    synchronized void markInterrupted() throws Exception {
        JSONObject state=run();if(state==null)return;
        String s=state.optString("status");
        if(s.equals("checking")){
            state.put("status","created");RunClock.pause(state,System.currentTimeMillis());
        }
        else if(s.equals("create_pending"))state.put("status","create_unknown");
        else if(s.equals("finish_pending")||s.equals("record_updated"))state.put("status","finish_unknown");
        else return;
        state.put("auto_submit",false).put("error","上次操作被中断，请查询服务器记录；不会自动重试");save(state);
    }
    synchronized void finish() throws Exception {
        JSONObject state=run();
        long end=System.currentTimeMillis(),start=state.getLong("start_ms");
        RunClock.requireFinishable(state,end);
        int seconds=(int)((end-start)/1000);
        if(seconds>86400)throw new IllegalStateException("记录已超过一天，请在小程序核实；不会自动提交");
        int boot=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if(boot==state.optInt("boot",-2)&&Math.abs((SystemClock.elapsedRealtime()-state.getLong("elapsed_start"))/1000.0-seconds)>60)throw new IllegalStateException("手机时间发生变化，请校准系统时间后再处理记录");
        ApiClient c=client();
        JSONArray path=RunClock.path(state,end);
        JSONObject payload=new JSONObject().put("record_id",state.getLong("record_id")).put("pace",seconds/60.0/(state.getInt("distance")/1000.0))
            .put("running_time",seconds).put("mileage",state.getInt("distance")).put("start_time",RunMath.date(start)).put("end_time",RunMath.date(end)).put("pass_point",3).put("step_count",state.getInt("steps"));
        new RecordSubmission(new RecordSubmission.Environment(){
            @Override public JSONObject request(String path,JSONObject body)throws Exception{return c.request(path,body);}
            @Override public void save(JSONObject value)throws Exception{RunEngine.this.save(value);}
            @Override public void event(String event){RunDiagnostics.event(context,event,RunDiagnostics.deviceState(context));}
        }).submit(state,payload,path,end);
        refresh();
    }
    synchronized void refresh() throws Exception {
        ApiClient c=client();JSONObject stats=(JSONObject)ApiClient.success(c.request("/xcxapi/recordListcont/"));
        JSONObject result=new JSONObject().put("statistics",stats).put("checked_at",System.currentTimeMillis());
        JSONObject state=run(),config=config();long id=state==null?config.optLong("recent_record_id",0):state.optLong("record_id",0);
        if(id>0){JSONObject detail=(JSONObject)ApiClient.success(c.request("/xcxapi/recordInfo/?id="+id));result.put("record",detail);}
        store.write("results",result);
    }
}

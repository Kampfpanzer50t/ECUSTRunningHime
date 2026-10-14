package cn.huati.assistant;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class RunService extends Service {
    private static final String CHANNEL="run_progress";
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;
    private boolean working;
    private volatile boolean destroyed;
    private volatile long workingRecord;
    private volatile long pendingDailyStart;
    private volatile boolean locating;
    private RunEngine engine;

    @Override public void onCreate(){
        super.onCreate();
        // Meet Android's foreground-service deadline before any Keystore IO.
        channel(this);foreground("正在启动后台任务",false);
        RunDiagnostics.event(this,"service_foreground",RunDiagnostics.deviceState(this));
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"huati:runTimer");
        wake.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!wake.isHeld())wake.acquire(125*60*1000L);
        foreground(working?"后台任务正在处理":"正在恢复本条模拟记录",locating);
        if(working)return START_STICKY;
        working=true;timer.removeCallbacks(tick);
        final String action=intent==null?"":intent.getAction();
        final long at=intent==null?0:intent.getLongExtra(RunAlarms.SCHEDULED_AT,0);
        final long target=intent==null?0:intent.getLongExtra(RunAlarms.TARGET_AT,0);
        final long expected=intent==null?0:intent.getLongExtra(RunAlarms.RECORD_ID,0);
        worker.execute(()->{
            try{
                ensureEngine();
                if(RunAlarms.PREPARE.equals(action)){
                    notifyProgress("正在读取当前位置并获取本次随机三点");
                    String message=engine.prepareDaily(target,this::readLocation,()->!destroyed);
                    JSONObject preparedSettings=engine.dailySettings();
                    if(DailyPreparation.ready(preparedSettings,System.currentTimeMillis(),target)||DailyPreparation.waiting(preparedSettings,System.currentTimeMillis(),target)){
                        pendingDailyStart=target;checkPrepared();return;
                    }
                    if(RunRecovery.shouldRestore(engine.run(),System.currentTimeMillis())){checkProgress();return;}
                    done(message.isEmpty()?"本次自动准备无需重复执行":message);return;
                }
                if(RunAlarms.PROBE.equals(action)){
                    if(RunRecovery.shouldRestore(engine.run(),System.currentTimeMillis())){checkProgress();return;}
                    probe(at);return;
                }
                if(RunAlarms.DAILY.equals(action)){
                    JSONObject preparation=engine.dailySettings();
                    if(DailyPreparation.due(preparation,System.currentTimeMillis(),at)){
                        engine.prepareDaily(at,this::readLocation,()->!destroyed);preparation=engine.dailySettings();
                    }
                    if(DailyPreparation.waiting(preparation,System.currentTimeMillis(),at)){
                        pendingDailyStart=at;checkPrepared();return;
                    }
                    pendingDailyStart=0;
                    // Arranging tomorrow must never prevent today's attempt from proceeding.
                    try{engine.syncDailyAlarm();}catch(Exception error){RunDiagnostics.error(this,"daily_alarm_error",error);}
                    notifyProgress("正在准备今天的自动模拟记录");
                    DailySchedule.Result result=engine.startDaily(at,()->!destroyed);
                    RunDiagnostics.event(this,"daily_result",new JSONObject().put("result",result.started?"started":result.attempted?"skipped_or_failed":"not_due"));
                    JSONObject state=engine.run();
                    if(!RunRecovery.shouldRestore(state,System.currentTimeMillis())){
                        done(result.message.isEmpty()?"今天自动任务无需重复开始":result.message);return;
                    }
                }else if(RunAlarms.FINISH.equals(action)){
                    JSONObject state=engine.run();
                    if(!RunClock.automaticAllowed(state,expected)){
                        if(!RunRecovery.shouldRestore(state,System.currentTimeMillis())){
                            done("旧任务已暂停、舍弃或完成，不会重复提交");return;
                        }
                    }
                }
                if(intent==null){
                    JSONObject settings=engine.dailySettings();long prepared=settings==null?0:settings.optLong("prep_target_ms");
                    if(DailyPreparation.ready(settings,System.currentTimeMillis(),prepared)||DailyPreparation.waiting(settings,System.currentTimeMillis(),prepared)){
                        pendingDailyStart=prepared;checkPrepared();return;
                    }
                }
                checkProgress();
            }catch(Exception error){fail(error);}
        });
        return RunAlarms.PROBE.equals(action)?START_NOT_STICKY:START_STICKY;
    }
    private void ensureEngine(){
        if(engine==null){engine=RunEngine.get(this);RunDiagnostics.event(this,"engine_ready");}
    }
    private final Runnable tick=()->{
        if(working||destroyed)return;
        working=true;worker.execute(()->{try{ensureEngine();if(pendingDailyStart>0)checkPrepared();else checkProgress();}catch(Exception error){fail(error);}});
    };
    private void foreground(String text,boolean location){
        if(Build.VERSION.SDK_INT>=29){
            int type=location&&PhoneLocation.precise(this)&&PhoneLocation.background(this)?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(1,notification(text,true),type);
        }else startForeground(1,notification(text,true));
    }
    private JSONObject readLocation()throws Exception{
        try{
            PhoneLocation.require(this);locating=true;
            foreground("正在读取手机当前位置",true);RunDiagnostics.event(this,"location_request",RunDiagnostics.deviceState(this));
            JSONObject origin=PhoneLocation.read(this,()->!destroyed);RunDiagnostics.event(this,"location_ready");return origin;
        }catch(Exception error){RunDiagnostics.error(this,"location_failed",error);throw error;}
        finally{locating=false;if(!destroyed)foreground("正在准备随机三点",false);}
    }
    private void checkPrepared()throws Exception{
        if(destroyed)return;
        if(!wake.isHeld())wake.acquire(125*60*1000L);
        long target=pendingDailyStart,now=System.currentTimeMillis();
        JSONObject settings=engine.dailySettings();
        if(DailyPreparation.waiting(settings,now,target)){
            long retry=settings.optLong("next_location_retry_ms");
            if(DailyPreparation.due(settings,now,target)){
                engine.prepareDaily(target,this::readLocation,()->!destroyed);
                settings=engine.dailySettings();
                if(DailyPreparation.ready(settings,System.currentTimeMillis(),target)){checkPrepared();return;}
                if(!DailyPreparation.waiting(settings,System.currentTimeMillis(),target)){done(settings.optString("prep_message","自动准备已停止"));return;}
                retry=settings.optLong("next_location_retry_ms");
            }
            foreground(settings.optString("prep_message","正在准备自动任务，可关闭自动运行取消"),false);
            long delay=Math.min(1000,Math.max(1,retry-System.currentTimeMillis()));
            timer.post(()->{working=false;if(!destroyed){timer.removeCallbacks(tick);timer.postDelayed(tick,delay);}});return;
        }
        if(!DailyPreparation.ready(settings,now,target)){
            pendingDailyStart=0;
            if(RunRecovery.shouldRestore(engine.run(),now)){checkProgress();return;}
            done("自动准备已取消、失效或超时，请打开 App 查看状态");return;
        }
        if(now<target){
            notifyProgress("位置和三点已准备 · 等待 "+RunMath.date(target)+" 开始");
            // Keep this short wait alive: idle-mode exact alarms less than nine minutes apart may be delayed.
            timer.post(()->{working=false;if(!destroyed){timer.removeCallbacks(tick);timer.postDelayed(tick,Math.min(1000,Math.max(1,target-System.currentTimeMillis())));}});
            return;
        }
        pendingDailyStart=0;DailySchedule.Result result=engine.startDaily(target,()->!destroyed);
        RunDiagnostics.event(this,"daily_result",new JSONObject().put("result",result.started?"started":result.attempted?"skipped_or_failed":"not_due"));
        if(RunRecovery.shouldRestore(engine.run(),System.currentTimeMillis()))checkProgress();
        else done(result.message.isEmpty()?"今天自动任务无需重复开始":result.message);
    }
    private void checkProgress()throws Exception{
        if(destroyed)return;
        JSONObject state=engine.run();long id=state==null?0:state.optLong("record_id",0);
        workingRecord=id;
        if(!RunRecovery.shouldRestore(state,System.currentTimeMillis())){done("自动等待已结束，请打开 App 查看状态");return;}
        long leftMillis=RunClock.remainingMillis(state,System.currentTimeMillis()),left=(leftMillis+999)/1000;
        if(leftMillis>0){
            RunAlarms.syncFinish(this,state,System.currentTimeMillis());
            notifyProgress("模拟记录 · 剩余 "+left/60+" 分 "+left%60+" 秒");
            timer.post(()->{working=false;if(!destroyed){timer.removeCallbacks(tick);timer.postDelayed(tick,Math.min(5000,leftMillis));}});
        }else{
            RunDiagnostics.event(this,"finish_start",RunDiagnostics.deviceState(this));
            notifyProgress("正在校验并提交本条模拟记录");
            JSONObject finished=engine.finishAutomatically(id);
            if(finished==null)done("本次模拟已暂停或舍弃，不会继续自动提交。");
            else done(finished.optBoolean("accepted_invalid")?"本次无效记录已提交，详情见记录页。":"提交已完成，请打开 App 查看服务器判定。");
        }
    }
    private void fail(Exception error){
        RunDiagnostics.error(this,"background_error",error);
        String message=error.getMessage()==null?"处理未完成，请打开 App 查询记录状态。":error.getMessage();
        try{
            if(engine!=null)engine.serviceError(workingRecord,message);
        }catch(Exception ignored){}
        done(message);
    }
    private void probe(long scheduledAt){
        HttpsURLConnection connection=null;
        try{
            if(scheduledAt<=0||System.currentTimeMillis()<scheduledAt||System.currentTimeMillis()-scheduledAt>5*60*1000L){
                done("自检闹钟已过期，请重新设置自检");return;
            }
            // No account headers: this HEAD request cannot create or submit a running record.
            connection=(HttpsURLConnection)new URL("https://"+ApiClient.HOST+"/").openConnection();
            connection.setRequestMethod("HEAD");connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
            int code=connection.getResponseCode();
            JSONObject details=RunDiagnostics.deviceState(this);details.put("http_code",code);
            RunDiagnostics.event(this,"probe_success",details);
            done("系统唤醒及联网自检通过。是否在锁屏状态触发请查看排查报告；未创建或提交跑步记录。");
        }catch(Exception error){
            RunDiagnostics.error(this,"probe_failed",error);done("锁屏联网自检失败，请打开 App 查看排查报告，并检查电池和网络设置。");
        }finally{if(connection!=null)connection.disconnect();}
    }
    private void notifyProgress(String text){if(!destroyed)getSystemService(NotificationManager.class).notify(1,notification(text,true));}
    private void done(String text){timer.post(()->{if(!destroyed)complete(text);});}
    private Notification notification(String text,boolean ongoing){return notification(this,text,ongoing);}
    private static void channel(Context context){
        context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"记录计时与提交结果",NotificationManager.IMPORTANCE_LOW));
    }
    private static Notification notification(Context context,String text,boolean ongoing){
        PendingIntent open=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class).putExtra("selected_page",2),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(context.getString(R.string.app_name))
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).build();
    }
    static void notifyResult(Context context,String text){
        try{channel(context);context.getSystemService(NotificationManager.class).notify(2,notification(context,text,false));}catch(Exception ignored){}
    }
    private void complete(String text){
        timer.removeCallbacks(tick);stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(1,notification(text,false));stopSelf();
    }
    @Override public void onDestroy(){
        destroyed=true;timer.removeCallbacks(tick);if(wake!=null&&wake.isHeld())wake.release();worker.shutdown();
        RunDiagnostics.event(this,"service_stopped");super.onDestroy();
    }
    @Override public void onTimeout(int startId,int type){
        String message="后台运行时限已到，自动处理已停止，请打开 App 查看记录并选择继续或舍弃。";
        worker.execute(()->{try{if(engine!=null)engine.serviceError(workingRecord,message);}catch(Exception ignored){}});
        complete(message);
    }
    @Override public IBinder onBind(Intent intent){return null;}
}

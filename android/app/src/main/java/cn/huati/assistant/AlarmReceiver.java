package cn.huati.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;String action=intent.getAction();
        if(RunAlarms.DAILY.equals(action)||RunAlarms.PREPARE.equals(action)||RunAlarms.FINISH.equals(action)||RunAlarms.PROBE.equals(action)){
            // Launch first: decrypting data or arranging tomorrow cannot prevent today's FGS.
            try{
                context.startForegroundService(new Intent(context,RunService.class).setAction(action)
                    .putExtra(RunAlarms.SCHEDULED_AT,intent.getLongExtra(RunAlarms.SCHEDULED_AT,0))
                    .putExtra(RunAlarms.TARGET_AT,intent.getLongExtra(RunAlarms.TARGET_AT,0))
                    .putExtra(RunAlarms.RECORD_ID,intent.getLongExtra(RunAlarms.RECORD_ID,0)));
                String event=RunAlarms.DAILY.equals(action)?"daily_alarm_received":RunAlarms.PREPARE.equals(action)?"prepare_alarm_received":RunAlarms.FINISH.equals(action)?"finish_alarm_received":"probe_alarm_received";
                JSONObject details=RunDiagnostics.deviceState(context).put("scheduled_at",intent.getLongExtra(RunAlarms.SCHEDULED_AT,0));
                RunDiagnostics.event(context,event,details);RunDiagnostics.event(context,"service_requested");
            }catch(Exception error){
                RunDiagnostics.error(context,"service_launch_failed",error);
                RunService.notifyResult(context,"系统未允许启动后台任务，请打开 App 查看排查报告。");
            }
            return;
        }
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)||
            Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action)||
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)){
            PendingResult pending=goAsync();
            new Thread(()->{
                try{
                    RunEngine engine=RunEngine.get(context);engine.syncDailyAlarm();RunAlarms.syncFinish(context,engine.run(),System.currentTimeMillis());
                }catch(Exception error){RunDiagnostics.error(context,"daily_alarm_error",error);}
                finally{pending.finish();}
            },"huati-restore-alarms").start();
        }
    }
}

package cn.huati.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;

/** No always-running waiting service: the OS wakes the app at start and finish. */
final class RunAlarms {
    static final String DAILY="cn.huati.assistant.DAILY_ALARM",PREPARE="cn.huati.assistant.PREPARE_ALARM",FINISH="cn.huati.assistant.FINISH_ALARM",PROBE="cn.huati.assistant.PROBE_ALARM";
    static final String SCHEDULED_AT="scheduled_at",TARGET_AT="target_at",RECORD_ID="record_id";
    private static final int DAILY_ID=61,FINISH_ID=62,PROBE_ID=63,PREPARE_ID=64;
    static boolean permitted(Context context){
        return Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    private static PendingIntent pending(Context context,String action,int id,long when,long record){
        return pending(context,action,id,when,record,0);
    }
    private static PendingIntent pending(Context context,String action,int id,long when,long record,long target){
        Intent intent=new Intent(context,AlarmReceiver.class).setAction(action).putExtra(SCHEDULED_AT,when).putExtra(RECORD_ID,record).putExtra(TARGET_AT,target);
        return PendingIntent.getBroadcast(context,id,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static void cancel(Context context,String action,int id){
        Intent intent=new Intent(context,AlarmReceiver.class).setAction(action);
        PendingIntent operation=PendingIntent.getBroadcast(context,id,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_NO_CREATE);
        if(operation!=null){context.getSystemService(AlarmManager.class).cancel(operation);operation.cancel();}
    }
    static void cancelDaily(Context context){cancel(context,DAILY,DAILY_ID);cancel(context,PREPARE,PREPARE_ID);}
    static void cancelFinish(Context context){cancel(context,FINISH,FINISH_ID);}
    static long scheduleProbe(Context context){
        if(!permitted(context))throw new IllegalStateException("请先允许锁屏定时");
        long next=System.currentTimeMillis()+60000;
        context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending(context,PROBE,PROBE_ID,next,0));
        try{RunDiagnostics.event(context,"probe_scheduled",new JSONObject().put("scheduled_at",next));}catch(Exception ignored){}
        return next;
    }
    static long scheduleDaily(Context context,JSONObject settings,long now){
        if(settings==null||!settings.optBoolean("enabled",false)||!permitted(context)){cancelDaily(context);return 0;}
        long next=DailySchedule.nextAlarm(settings,now);
        context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending(context,DAILY,DAILY_ID,next,0));
        long prepTarget=DailyPreparation.wakeTarget(settings,now,next);
        long prepare=DailyPreparation.nextWake(settings,now,prepTarget);
        if(prepare>0)context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,prepare,pending(context,PREPARE,PREPARE_ID,prepare,0,prepTarget));
        else cancel(context,PREPARE,PREPARE_ID);
        return next;
    }
    static void syncFinish(Context context,JSONObject state,long now){
        try{
            long id=state==null?0:state.optLong("record_id",0);
            if(!RunClock.automaticAllowed(state,id)||!permitted(context)){cancelFinish(context);return;}
            long next=Math.max(now+1,RunClock.finishAt(state,now));
            context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending(context,FINISH,FINISH_ID,next,id));
        }catch(Exception error){
            // The foreground timer remains the fallback; absence of exact permission is shown in the UI.
            RunDiagnostics.error(context,"finish_alarm_error",error);
            try{cancelFinish(context);}catch(Exception ignoredAgain){}
        }
    }
}

package cn.huati.assistant;

import android.content.Context;
import android.os.PowerManager;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class RunDiagnostics {
    private static final Object LOCK=new Object();
    private static AtomicFile file(Context context){return new AtomicFile(new File(context.getFilesDir(),"background-diagnostics.json"));}
    static JSONObject deviceState(Context context){
        JSONObject result=new JSONObject();
        try{
            PowerManager power=context.getSystemService(PowerManager.class);
            ConnectivityManager network=context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities capabilities=network.getNetworkCapabilities(network.getActiveNetwork());
            result.put("exact_alarm",RunAlarms.permitted(context)).put("battery_exempt",power.isIgnoringBatteryOptimizations(context.getPackageName()))
                .put("idle",power.isDeviceIdleMode()).put("interactive",power.isInteractive())
                .put("network",capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }catch(Exception ignored){}
        return result;
    }
    static void event(Context context,String event){event(context,event,null);}
    static void event(Context context,String event,JSONObject details){
        synchronized(LOCK){
            try{
                AtomicFile target=file(context);JSONArray previous=target.getBaseFile().exists()?
                    new JSONArray(new String(target.readFully(),StandardCharsets.UTF_8)):null;
                byte[] bytes=DiagnosticLog.append(previous,event,System.currentTimeMillis(),details).toString().getBytes(StandardCharsets.UTF_8);
                FileOutputStream out=target.startWrite();
                try{out.write(bytes);target.finishWrite(out);}catch(Exception error){target.failWrite(out);}
            }catch(Exception ignored){} // Diagnostics must never block an authorized run.
        }
    }
    static void error(Context context,String event,Throwable error){
        try{JSONObject details=deviceState(context);details.put("error_type",DiagnosticLog.failure(error));event(context,event,details);}
        catch(Exception ignored){}
    }
    static String report(Context context,RunEngine engine)throws Exception{
        StringBuilder text=new StringBuilder("华理跑步姬 "+BuildConfig.VERSION_NAME+" 后台排查报告\n");
        text.append("报告时间：").append(RunMath.date(System.currentTimeMillis())).append("（北京时间）\n")
            .append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append('\n');
        JSONObject state=deviceState(context);
        text.append("精确定时权限：").append(state.optBoolean("exact_alarm")?"已允许":"未允许")
            .append("\n电池优化：").append(state.optBoolean("battery_exempt")?"已排除":"仍开启")
            .append("\n当前网络：").append(state.optBoolean("network")?"已联网":"未确认联网").append('\n');
        JSONObject settings=engine.dailySettings(),run=engine.run();
        text.append("每日开关：").append(settings!=null&&settings.optBoolean("enabled")?"开启":"关闭").append('\n');
        if(settings!=null){
            text.append("每日启动时间：").append(DailySchedule.timeLabel(settings)).append("（北京时间）\n");
            text.append("提前准备：2 分钟\n定位权限：").append(PhoneLocation.precise(context)?"精确位置已允许":"未允许精确位置")
                .append(" / ").append(PhoneLocation.background(context)?"后台位置已允许":"后台位置未允许").append('\n');
            text.append("准备状态：").append(settings.optString("prep_status","尚未准备")).append('\n');
            if(!settings.optString("origin_source").isEmpty())text.append("起点来源：").append(settings.optString("origin_source")).append('\n');
            text.append("最近尝试：").append(settings.optString("last_day","无")).append(" / ").append(settings.optString("last_status","未尝试")).append('\n');
            if(settings.optLong("last_target_ms")>0)text.append("最近计划启动时间：").append(RunMath.date(settings.getLong("last_target_ms"))).append('\n');
            if(settings.optJSONArray("attempted_times")!=null)text.append("最近尝试日期的启动时间：").append(settings.getJSONArray("attempted_times").join("、")).append('\n');
            if(settings.optLong("next_alarm_ms")>0)text.append("下次定时：").append(RunMath.date(settings.getLong("next_alarm_ms"))).append('\n');
        }
        text.append("当前记录状态：").append(run==null?"无":run.optString("status")).append('\n');
        if(run!=null){
            text.append("自动提交：").append(run.optBoolean("auto_submit")).append('\n');
            if(run.optLong("start_ms")>0)text.append("开始：").append(RunMath.date(run.getLong("start_ms"))).append('\n');
            if(run.has("duration"))text.append("剩余秒数：").append(RunClock.remainingSeconds(run,System.currentTimeMillis())).append('\n');
            if(run.has("duration_ms"))text.append("本次随机时长：").append(DailyDuration.label(run.getLong("duration_ms"))).append('\n')
                .append("剩余毫秒：").append(RunClock.remainingMillis(run,System.currentTimeMillis())).append('\n');
        }
        text.append("\n运行事件（升级前的事件无法追溯）：\n");
        synchronized(LOCK){
            AtomicFile target=file(context);JSONArray events=target.getBaseFile().exists()?
                new JSONArray(new String(target.readFully(),StandardCharsets.UTF_8)):new JSONArray();
            for(int i=0;i<events.length();i++){
                JSONObject entry=events.getJSONObject(i);text.append(RunMath.date(entry.getLong("time"))).append(" · ").append(DiagnosticLog.label(entry.getString("event")));
                entry.remove("time");entry.remove("event");if(entry.length()>0)text.append(" · ").append(entry);text.append('\n');
            }
        }
        return text.append("\n报告不含会话、密码、手机号、账号编号、坐标或请求正文。").toString();
    }
}

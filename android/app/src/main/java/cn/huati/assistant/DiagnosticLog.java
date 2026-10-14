package cn.huati.assistant;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only explicitly allowed metadata can enter a report; credentials and bodies are never logged. */
final class DiagnosticLog {
    private static final String[] FIELDS={"source","phase","result","scheduled_at","remaining_seconds",
        "expected_end","exact_alarm","battery_exempt","idle","interactive","network","error_type","http_code"};
    static JSONArray append(JSONArray previous,String event,long now,JSONObject details)throws Exception{
        JSONArray out=new JSONArray();
        if(previous!=null)for(int i=Math.max(0,previous.length()-99);i<previous.length();i++)out.put(previous.getJSONObject(i));
        JSONObject entry=new JSONObject().put("time",now).put("event",event);
        if(details!=null)for(String name:FIELDS)if(details.has(name))entry.put(name,details.get(name));
        return out.put(entry);
    }
    static String failure(Throwable error){
        if(error instanceof SocketTimeoutException)return "network_timeout";
        if(error instanceof UnknownHostException)return "dns_failed";
        if(error instanceof javax.net.ssl.SSLException)return "tls_failed";
        if(error instanceof SecurityException)return "system_permission_denied";
        if(error instanceof IOException)return "network_or_storage_io";
        String message=error==null?"":error.getMessage();
        if(message!=null&&(message.contains("登录")||message.contains("会话")))return "session_failed";
        return error==null?"unknown":error.getClass().getSimpleName();
    }
    static String label(String event){
        switch(event){
            case"prepare_alarm_received":return "收到提前两分钟准备闹钟";
            case"location_request":return "开始读取手机当前位置";
            case"location_ready":return "已取得新位置";
            case"location_failed":return "手机定位失败，将选择校内模拟起点";
            case"origin_selected":return "本次自动起点已选择";
            case"daily_alarm_received":return "收到每日自动启动闹钟";
            case"finish_alarm_received":return "收到计时结束闹钟";
            case"probe_alarm_received":return "收到锁屏自检闹钟";
            case"service_requested":return "已请求启动后台服务";
            case"service_launch_failed":return "系统未允许启动后台服务";
            case"service_foreground":return "后台服务已显示常驻通知";
            case"engine_ready":return "加密配置已读取";
            case"daily_result":return "当天自动准备结果";
            case"points_request":return "开始获取随机三点";
            case"points_ready":return "随机三点已取得";
            case"daily_refresh_request":return "开始刷新校园跑账号和每日数据";
            case"daily_refresh_ready":return "校园跑数据已刷新";
            case"daily_preparation_waiting_open":return "起点已准备，等待 06:00 开放后获取三点";
            case"daily_preparation_waiting_points":return "刷新或点位请求未成功，将自动重试";
            case"daily_preparation_ready":return "位置和当天三点已准备，等待自动开始";
            case"daily_preparation_failed":return "自动准备已停止，请查看自动页面原因";
            case"map_points_cache_error":return "跑步点位目录缓存未更新";
            case"create_request":return "开始创建服务器记录";
            case"created":return "服务器记录已创建";
            case"finish_start":return "模拟时长已到，准备校验提交";
            case"check_request":return "开始服务器校验";
            case"check_failed":return "服务器校验未通过";
            case"update_request":return "开始提交成绩";
            case"upload_request":return "开始上传轨迹";
            case"finished":return "成绩及轨迹提交完成";
            case"timer_recovered":return "已恢复原记录的后台计时";
            case"background_error":return "后台处理失败";
            case"daily_alarm_set":return "下一次每日闹钟已设置";
            case"daily_alarm_error":return "每日闹钟注册失败";
            case"finish_alarm_error":return "结束闹钟注册失败";
            case"probe_scheduled":return "已设置一分钟后锁屏自检";
            case"probe_success":return "系统唤醒及联网自检通过（interactive=false 表示屏幕未亮）";
            case"probe_failed":return "锁屏唤醒或联网自检失败";
            case"service_stopped":return "后台服务已结束";
            default:return event;
        }
    }
}

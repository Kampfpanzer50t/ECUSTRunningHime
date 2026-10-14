package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TreeMap;

/** Persisted simulation clock. Pauses stop progress without changing real dates. */
final class RunClock {
    static boolean unresolved(JSONObject state) {
        if(state==null)return false;
        String phase=state.optString("status");return !phase.equals("finished")&&!phase.equals("discarded");
    }
    static boolean canDiscard(JSONObject state) {
        if(state==null)return false;
        switch(state.optString("status")){
            case "created":case "paused":case "check_failed":case "create_unknown":case "finish_unknown":return true;
            default:return false;
        }
    }
    static long movingMillis(JSONObject state,long now) throws Exception {
        if(state==null)return 0;
        long end=state.optString("status").equals("discarded")?state.optLong("discarded_at",now):state.optLong("end_ms",now);
        long start=state.getLong("start_ms"),paused=0;
        JSONArray intervals=state.optJSONArray("pauses");
        if(intervals!=null)for(int i=0;i<intervals.length();i++){
            JSONObject interval=intervals.getJSONObject(i);
            long from=Math.max(start,interval.getLong("start_ms")),to=Math.min(end,interval.optLong("end_ms",end));
            paused+=Math.max(0,to-from);
        }
        return Math.max(0,end-start-paused);
    }
    static long durationMillis(JSONObject state)throws Exception{
        // Old records retain their saved, whole-second duration during upgrade.
        long duration=state.has("duration_ms")?state.getLong("duration_ms"):Math.multiplyExact(state.getLong("duration"),1000);
        if(duration<=0)throw new IllegalStateException("模拟时长无效");return duration;
    }
    static long remainingMillis(JSONObject state,long now)throws Exception{return Math.max(0,durationMillis(state)-movingMillis(state,now));}
    static long remainingSeconds(JSONObject state,long now) throws Exception {return (remainingMillis(state,now)+999)/1000;}
    static long finishAt(JSONObject state,long now)throws Exception{return Math.addExact(now,remainingMillis(state,now));}
    static void pause(JSONObject state,long now) throws Exception {
        if(state==null||!state.optString("status").equals("created"))throw new IllegalStateException("当前记录不能暂停，可能已开始提交；请查询状态");
        JSONArray intervals=state.optJSONArray("pauses");if(intervals==null)intervals=new JSONArray();
        intervals.put(new JSONObject().put("start_ms",now));
        state.put("pauses",intervals).put("status","paused").put("auto_submit",false);
    }
    static void resume(JSONObject state,long now) throws Exception {
        if(state==null||!state.optString("status").equals("paused"))throw new IllegalStateException("当前记录不是暂停状态，不能重复恢复");
        JSONArray intervals=state.getJSONArray("pauses");JSONObject last=intervals.getJSONObject(intervals.length()-1);
        if(last.has("end_ms")||now<last.getLong("start_ms"))throw new IllegalStateException("暂停时间无效，请检查手机时间");
        last.put("end_ms",now);state.put("status","created").put("auto_submit",true);state.remove("error");
    }
    static void discard(JSONObject state,long now) throws Exception {
        if(!canDiscard(state))throw new IllegalStateException("当前记录不能舍弃，可能正在提交或已经完成；请查询状态");
        String previous=state.getString("status");
        if(previous.equals("paused")){
            JSONArray intervals=state.getJSONArray("pauses");JSONObject last=intervals.getJSONObject(intervals.length()-1);
            if(now<last.getLong("start_ms"))throw new IllegalStateException("手机时间发生变化，请校准后再舍弃");
            last.put("end_ms",now);
        }
        state.put("discarded_from",previous).put("discarded_at",now).put("status","discarded").put("auto_submit",false);
        state.put("error",state.optLong("record_id",0)>0?"已舍弃本地模拟，不会继续提交。服务器上的已创建记录仍保留，可查询其状态。":"已舍弃本地任务，不会继续提交。创建结果尚不确定，请在官方小程序核实。");
    }
    static boolean automaticAllowed(JSONObject state,long expectedId) {
        return state!=null&&state.optString("status").equals("created")&&state.optBoolean("auto_submit")&&expectedId>0&&state.optLong("record_id",0)==expectedId;
    }
    static void requireFinishable(JSONObject state,long now) throws Exception {
        if(state==null||(!state.optString("status").equals("created")&&!state.optString("status").equals("check_failed")))
            throw new IllegalStateException("当前记录不能提交；暂停后请先选择继续，舍弃后不会再提交");
        if(remainingMillis(state,now)>0)throw new IllegalStateException("模拟时长尚未结束，暂停时间不计入模拟进度");
    }
    static boolean migrate(JSONObject state,long now) throws Exception {
        if(state==null||state.has("pauses"))return false;
        state.put("pauses",new JSONArray());
        if(state.optString("status").equals("created")&&!state.optBoolean("auto_submit"))pause(state,now);
        return true;
    }
    static JSONArray path(JSONObject state,long end) throws Exception {
        long start=state.getLong("start_ms"),active=movingMillis(state,end);
        if(active<=0)throw new IllegalStateException("模拟时长无效");
        int baseSeconds=Math.max(1,(int)(active/1000));
        JSONArray base=state.optBoolean("road_mode")?
            RoadRoute.timed(state.getJSONArray("route_samples"),start,baseSeconds*1000L):
            RunMath.path(state.getJSONArray("points"),state.getDouble("lat"),state.getDouble("lng"),start,state.getInt("distance"),baseSeconds);
        long baseDuration=baseSeconds*1000L;
        JSONArray intervals=state.optJSONArray("pauses");if(intervals==null)intervals=new JSONArray();
        TreeMap<Long,JSONObject> ordered=new TreeMap<>();
        for(int i=0;i<base.length();i++){
            JSONObject point=new JSONObject(base.getJSONObject(i).toString());
            long activeOffset=Math.round((point.getLong("timestamp")-start)/(double)baseDuration*active),offset=0;
            for(int j=0;j<intervals.length();j++){
                JSONObject pause=intervals.getJSONObject(j);
                long from=pause.getLong("start_ms"),to=pause.getLong("end_ms");
                if(from-start-offset<=activeOffset)offset+=to-from;else break;
            }
            long timestamp=start+activeOffset+offset;point.put("timestamp",timestamp);ordered.put(timestamp,point);
        }
        long offset=0;
        for(int i=0;i<intervals.length();i++){
            JSONObject pause=intervals.getJSONObject(i);long from=pause.getLong("start_ms"),to=pause.getLong("end_ms");
            if(to<from||from<start||to>end)throw new IllegalStateException("暂停区间与提交时间不一致，请核实手机时间");
            double progress=(from-start-offset)/(double)active;
            JSONObject stationary=interpolate(base,start+progress*baseDuration);
            ordered.put(from,new JSONObject(stationary.toString()).put("timestamp",from));
            ordered.put(to,new JSONObject(stationary.toString()).put("timestamp",to));offset+=to-from;
        }
        JSONArray out=new JSONArray();for(JSONObject point:ordered.values())out.put(point);return out;
    }
    private static JSONObject interpolate(JSONArray path,double timestamp) throws Exception {
        if(timestamp<=path.getJSONObject(0).getLong("timestamp"))return new JSONObject(path.getJSONObject(0).toString());
        for(int i=1;i<path.length();i++){
            JSONObject left=path.getJSONObject(i-1),right=path.getJSONObject(i);
            if(timestamp<=right.getLong("timestamp")){
                double ratio=(timestamp-left.getLong("timestamp"))/(right.getLong("timestamp")-left.getLong("timestamp"));
                return new JSONObject(left.toString()).put("lat",left.getDouble("lat")+(right.getDouble("lat")-left.getDouble("lat"))*ratio)
                    .put("lng",left.getDouble("lng")+(right.getDouble("lng")-left.getDouble("lng"))*ratio);
            }
        }
        return new JSONObject(path.getJSONObject(path.length()-1).toString());
    }
}

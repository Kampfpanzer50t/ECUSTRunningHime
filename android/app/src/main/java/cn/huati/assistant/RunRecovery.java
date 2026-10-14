package cn.huati.assistant;

import org.json.JSONObject;

/** Recovery may only continue a known, running record; it cannot create or resume a paused run. */
final class RunRecovery {
    static boolean shouldRestore(JSONObject state,long now){
        if(state==null||!RunClock.automaticAllowed(state,state.optLong("record_id",0)))return false;
        long start=state.optLong("start_ms",0);
        return start>0&&now>=start&&now-start<=86400000L;
    }
}

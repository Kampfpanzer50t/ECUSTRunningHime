package cn.huati.assistant;

import org.json.JSONObject;

/** Presentation only: never creates, pauses or submits a record. */
final class CompanionState {
    static boolean running(JSONObject state,long now){
        if(state==null||!state.optString("status").equals("created")||state.optLong("start_ms",0)<=0)return false;
        try{return now>=state.getLong("start_ms")&&RunClock.remainingMillis(state,now)>0;}
        catch(Exception ignored){return false;}
    }
}

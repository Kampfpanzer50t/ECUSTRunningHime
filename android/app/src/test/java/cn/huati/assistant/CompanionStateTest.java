package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompanionStateTest {
    private JSONObject record()throws Exception{return new JSONObject().put("status","created").put("start_ms",100000).put("duration",900).put("pauses",new JSONArray());}
    @Test public void onlyActiveSimulationUsesRunningPose()throws Exception{
        assertTrue(CompanionState.running(record(),200000));
        for(String phase:new String[]{"paused","finished","discarded","checking","create_pending","finish_pending","record_updated","check_failed","create_unknown","finish_unknown"})assertFalse(phase,CompanionState.running(record().put("status",phase),200000));
    }
    @Test public void manualAndAutomaticUseTheSamePoseRule()throws Exception{
        assertTrue(CompanionState.running(record(),200000));assertTrue(CompanionState.running(record().put("scheduled_day","2026-10-12"),200000));
    }
    @Test public void pausedTimeDoesNotPrematurelyEndTheRunningPose()throws Exception{
        JSONObject r=record().put("pauses",new JSONArray().put(new JSONObject().put("start_ms",200000).put("end_ms",600000)));
        assertTrue(CompanionState.running(r,1200000));assertFalse(CompanionState.running(r,1400000));
    }
    @Test public void completedOrMalformedTimeUsesNormalPose()throws Exception{
        assertFalse(CompanionState.running(null,200000));assertFalse(CompanionState.running(record(),1000000));
        assertFalse(CompanionState.running(record(),99999));assertFalse(CompanionState.running(record().put("duration",0),200000));
    }
}

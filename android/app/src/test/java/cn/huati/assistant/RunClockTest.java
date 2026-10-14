package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunClockTest {
    private static final long START=1000000;
    private JSONObject started()throws Exception {
        return new JSONObject().put("status","created").put("auto_submit",true).put("record_id",77)
            .put("start_ms",START).put("duration",900).put("pauses",new JSONArray());
    }
    private void cannotFinish(JSONObject state,long end)throws Exception{
        try{RunClock.requireFinishable(state,end);fail("Must not submit");}catch(IllegalStateException expected){}
    }
    @Test public void longPauseFreezesCountdownAndSurvivesReload()throws Exception{
        JSONObject state=started();RunClock.pause(state,START+300000);
        assertEquals(600,RunClock.remainingSeconds(state,START+300000));
        assertEquals(600,RunClock.remainingSeconds(state,START+3900000));
        JSONObject restored=new JSONObject(state.toString());assertEquals(300000,RunClock.movingMillis(restored,START+3900000));
        cannotFinish(restored,START+3900000);RunClock.resume(restored,START+3900000);
        assertEquals(600,RunClock.remainingSeconds(restored,START+3900000));
        assertEquals(1,RunClock.remainingSeconds(restored,START+4499000));cannotFinish(restored,START+4499000);
        RunClock.requireFinishable(restored,START+4500000);assertEquals(900000,RunClock.movingMillis(restored,START+4500000));
    }
    @Test public void repeatedPausesAccumulateAndDoNotRestartSimulation()throws Exception{
        JSONObject state=started();RunClock.pause(state,START+100000);RunClock.resume(state,START+160000);
        RunClock.pause(state,START+360000);assertEquals(600,RunClock.remainingSeconds(state,START+600000));
        RunClock.resume(state,START+600000);assertEquals(300000,RunClock.movingMillis(state,START+600000));
        assertEquals(0,RunClock.remainingSeconds(state,START+1200000));assertEquals(77,state.getLong("record_id"));assertEquals(START,state.getLong("start_ms"));
    }
    @Test public void discardedRunNeverResumesOrFinishesAndKeepsRecordReference()throws Exception{
        JSONObject state=started();RunClock.pause(state,START+300000);RunClock.discard(state,START+600000);
        assertFalse(RunClock.unresolved(state));assertFalse(RunClock.automaticAllowed(state,77));
        assertEquals(77,state.getLong("record_id"));assertEquals(300000,RunClock.movingMillis(state,START+9000000));
        cannotFinish(state,START+9000000);
        try{RunClock.resume(state,START+9000000);fail();}catch(IllegalStateException expected){}
        assertFalse(RunClock.canDiscard(state));
    }
    @Test public void discardBeforePauseAlsoCancelsQueuedAutomaticFinish()throws Exception{
        JSONObject state=started();RunClock.discard(state,START+100000);assertFalse(RunClock.automaticAllowed(state,77));
        JSONObject newRun=started().put("record_id",78);assertFalse(RunClock.automaticAllowed(newRun,77));assertTrue(RunClock.automaticAllowed(newRun,78));
    }
    @Test public void pausedRunIsNotAutomaticallySubmittedEvenWhenWallTimePassed()throws Exception{
        JSONObject state=started();assertTrue(RunClock.automaticAllowed(state,77));RunClock.pause(state,START+899000);
        assertFalse(RunClock.automaticAllowed(state,77));cannotFinish(state,START+10000000);
        RunClock.resume(state,START+10000000);assertEquals(1,RunClock.remainingSeconds(state,START+10000000));
    }
    @Test public void serverOperationsInFlightCannotBeDiscarded()throws Exception{
        for(String phase:new String[]{"checking","create_pending","finish_pending","record_updated","finished"}){
            JSONObject state=started().put("status",phase);assertFalse(RunClock.canDiscard(state));
            try{RunClock.discard(state,START+900000);fail(phase);}catch(IllegalStateException expected){}
        }
        assertTrue(RunClock.canDiscard(started().put("status","check_failed")));
        assertTrue(RunClock.canDiscard(started().put("status","finish_unknown")));
    }
    @Test public void oldPausedAutomaticSubmissionMigratesOnceWithoutResuming()throws Exception{
        JSONObject state=started();state.remove("pauses");state.put("auto_submit",false);
        assertTrue(RunClock.migrate(state,START+300000));assertEquals("paused",state.getString("status"));
        assertFalse(RunClock.migrate(state,START+600000));assertEquals(600,RunClock.remainingSeconds(state,START+600000));
    }
    @Test public void cannotResumeTwiceOrMoveClockBackwards()throws Exception{
        JSONObject state=started();RunClock.pause(state,START+300000);
        try{RunClock.resume(state,START+299000);fail();}catch(IllegalStateException expected){}
        RunClock.resume(state,START+400000);
        try{RunClock.resume(state,START+500000);fail();}catch(IllegalStateException expected){}
        assertEquals(1,state.getJSONArray("pauses").length());
    }
    private JSONObject routeState()throws Exception{
        return started().put("distance",2200).put("lat",30.83103).put("lng",121.503709).put("points",new JSONArray(
            "[{\"lat\":\"30.827536\",\"lng\":\"121.503933\"},{\"lat\":\"30.832999\",\"lng\":\"121.509337\"},{\"lat\":\"30.830954\",\"lng\":\"121.50272\"}]"));
    }
    @Test public void pathContainsStationaryPauseAndRealStartAndEndTimes()throws Exception{
        JSONObject state=routeState();RunClock.pause(state,START+300000);RunClock.resume(state,START+360000);
        JSONArray path=RunClock.path(state,START+960000);assertEquals(START,path.getJSONObject(0).getLong("timestamp"));
        assertEquals(START+960000,path.getJSONObject(path.length()-1).getLong("timestamp"));
        JSONObject before=null,after=null;double metres=0;
        for(int i=0;i<path.length();i++){
            JSONObject point=path.getJSONObject(i);long timestamp=point.getLong("timestamp");
            if(timestamp==START+300000)before=point;if(timestamp==START+360000)after=point;
            assertFalse(timestamp>START+300000&&timestamp<START+360000);
            if(i>0){JSONObject previous=path.getJSONObject(i-1);assertTrue(timestamp>previous.getLong("timestamp"));metres+=RunMath.distance(previous.getDouble("lat"),previous.getDouble("lng"),point.getDouble("lat"),point.getDouble("lng"));}
        }
        assertNotNull(before);assertNotNull(after);assertEquals(before.getDouble("lat"),after.getDouble("lat"),0);assertEquals(before.getDouble("lng"),after.getDouble("lng"),0);assertEquals(2200,metres,0.5);
    }
    @Test public void pauseAtStartPreservesInitialTimestampAndStationaryLocation()throws Exception{
        JSONObject state=routeState();RunClock.pause(state,START);RunClock.resume(state,START+60000);
        JSONArray path=RunClock.path(state,START+960000);assertEquals(START,path.getJSONObject(0).getLong("timestamp"));
        assertEquals(START+60000,path.getJSONObject(1).getLong("timestamp"));
        assertEquals(path.getJSONObject(0).getDouble("lat"),path.getJSONObject(1).getDouble("lat"),0);
    }
    @Test public void millisecondDeadlineDoesNotFinishOneMillisecondEarlyOrWaitUntilNextSecond()throws Exception{
        JSONObject state=started().put("duration",823).put("duration_ms",822581);long end=START+822581;
        assertEquals(822581,RunClock.durationMillis(state));
        assertEquals(1,RunClock.remainingMillis(state,end-1));assertEquals(1,RunClock.remainingSeconds(state,end-1));cannotFinish(state,end-1);
        assertEquals(0,RunClock.remainingMillis(state,end));assertEquals(0,RunClock.remainingSeconds(state,end));RunClock.requireFinishable(state,end);
        assertEquals(end,RunClock.finishAt(state,START+123456));assertEquals(end,RunClock.finishAt(state,end-1));
    }
    @Test public void pauseAndProcessRestoreKeepChosenMillisecondsAndShiftDeadlineByPauseOnly()throws Exception{
        JSONObject state=started().put("duration_ms",822581);RunClock.pause(state,START+300123);
        JSONObject restored=new JSONObject(state.toString());
        assertEquals(522458,RunClock.remainingMillis(restored,START+600999));
        RunClock.resume(restored,START+600999);long end=START+822581+300876;
        assertEquals(822581,RunClock.durationMillis(restored));assertEquals(end,RunClock.finishAt(restored,START+600999));
        cannotFinish(restored,end-1);RunClock.requireFinishable(restored,end);
    }
    @Test public void oldRecordsAndManualDurationsKeepTheirSavedWholeSeconds()throws Exception{
        JSONObject state=started();assertEquals(900000,RunClock.durationMillis(state));
        assertEquals(START+900000,RunClock.finishAt(new JSONObject(state.toString()),START+123));
        state.put("duration",1200);assertEquals(1200000,RunClock.durationMillis(state));
    }
    @Test public void trajectoryEndsOnChosenMillisecondAfterPauseAndRestore()throws Exception{
        JSONObject state=routeState().put("duration_ms",822581);RunClock.pause(state,START+300123);RunClock.resume(state,START+360456);
        long end=START+822581+60333;JSONArray path=RunClock.path(new JSONObject(state.toString()),end);
        assertEquals(START,path.getJSONObject(0).getLong("timestamp"));assertEquals(end,path.getJSONObject(path.length()-1).getLong("timestamp"));
        for(int i=1;i<path.length();i++)assertTrue(path.getJSONObject(i).getLong("timestamp")>path.getJSONObject(i-1).getLong("timestamp"));
    }
}

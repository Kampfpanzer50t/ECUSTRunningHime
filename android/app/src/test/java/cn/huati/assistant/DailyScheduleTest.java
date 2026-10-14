package cn.huati.assistant;

import java.time.Instant;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyScheduleTest {
    private static final long DUE=Instant.parse("2026-10-11T22:15:00Z").toEpochMilli();
    private static JSONObject config()throws Exception{
        return new JSONObject().put("host",ApiClient.HOST).put("student_id","test-account")
            .put("headers",new JSONObject().put("X-Session-ID","test-session"));
    }
    private static final class Fake implements DailySchedule.Environment {
        JSONObject settings,account,current;
        int writes,starts,failWrite;
        boolean networkFailure;
        String startedDay;
        double lat,lng;
        Fake()throws Exception{account=DailyScheduleTest.config();settings=DailySchedule.enabled(null,account,30.83103,121.503709);}
        @Override public JSONObject settings()throws Exception{return new JSONObject(settings.toString());}
        @Override public void save(JSONObject value)throws Exception{
            writes++;if(writes==failWrite)throw new IllegalStateException("disk failure");settings=new JSONObject(value.toString());
        }
        @Override public JSONObject config(){return account;}
        @Override public JSONObject run(){return current;}
        @Override public long start(double lat,double lng,String day,long durationMillis)throws Exception{
            starts++;this.lat=lat;this.lng=lng;startedDay=day;
            assertEquals("preparing",settings.getString("last_status"));assertEquals(day,settings.getString("last_day"));
            assertEquals(durationMillis,settings.getLong("last_duration_ms"));DailyDuration.validate(durationMillis);
            if(networkFailure)throw new IllegalStateException("network timeout; result unknown");
            return 77;
        }
    }
    @Test public void triggersOnlyDuring0615AndNeverBackfills()throws Exception{
        Fake env=new Fake();assertFalse(DailySchedule.due(env.settings,DUE-1));
        assertTrue(DailySchedule.due(env.settings,DUE));assertTrue(DailySchedule.due(env.settings,DUE+59999));
        assertFalse(DailySchedule.due(env.settings,DUE+60000));assertFalse(DailySchedule.due(env.settings,DUE+900000));
    }
    @Test public void missingOrDisabledScheduleCannotStart()throws Exception{
        assertFalse(DailySchedule.due(null,DUE));Fake env=new Fake();env.settings=DailySchedule.disabled(env.settings);
        assertFalse(new DailySchedule(env).attempt(DUE,()->true).attempted);assertEquals(0,env.writes);assertEquals(0,env.starts);
    }
    @Test public void timezoneStaysInShanghaiRegardlessOfDeviceSetting()throws Exception{
        TimeZone previous=TimeZone.getDefault();
        try{TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Fake env=new Fake();assertEquals("2026-10-12",DailySchedule.day(DUE));assertTrue(DailySchedule.due(env.settings,DUE));
        }finally{TimeZone.setDefault(previous);}
    }
    @Test public void savesReservationBeforeNetworkAndUsesSavedOrigin()throws Exception{
        Fake env=new Fake();DailySchedule.Result result=new DailySchedule(env).attempt(DUE,()->true);
        assertTrue(result.started);assertEquals(77,result.recordId);assertEquals("2026-10-12",env.startedDay);
        assertEquals(30.83103,env.lat,0);assertEquals(121.503709,env.lng,0);
        assertEquals("started",env.settings.getString("last_status"));assertEquals(77,env.settings.getLong("last_record_id"));
        assertEquals(2200,DailySchedule.METRES);DailyDuration.validate(env.settings.getLong("last_duration_ms"));assertEquals(2600,DailySchedule.STEPS);
    }
    @Test public void reopenAndRepeatedChecksCannotCreateTwice()throws Exception{
        Fake env=new Fake();assertTrue(new DailySchedule(env).attempt(DUE,()->true).started);
        assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true).attempted);
        assertFalse(new DailySchedule(env).attempt(DUE+59000,()->true).attempted);assertEquals(1,env.starts);
    }
    @Test public void persistedDurationIsChosenOncePerSlotAndAgainForNextPlan()throws Exception{
        Fake env=new Fake();int[] samples={0};long[] durations={822581,959999};
        DailySchedule schedule=new DailySchedule(env,()->durations[samples[0]++]);
        assertTrue(schedule.attempt(DUE,()->true).started);
        assertEquals(822581,env.settings.getLong("last_duration_ms"));assertTrue(env.settings.getString("last_message").contains("13 分 42.581 秒"));
        assertFalse(schedule.attempt(DUE+1,()->true).attempted);
        assertFalse(new DailySchedule(env,()->{throw new AssertionError("A saved slot must not reroll");}).attempt(DUE+1000,()->true).attempted);
        assertEquals(1,samples[0]);assertEquals(822581,env.settings.getLong("last_duration_ms"));
        assertTrue(schedule.attempt(DUE+86400000L,()->true).started);
        assertEquals(2,samples[0]);assertEquals(959999,env.settings.getLong("last_duration_ms"));
    }
    @Test public void failedCreationKeepsItsDurationAndDoesNotRerollAfterReopen()throws Exception{
        Fake env=new Fake();env.networkFailure=true;
        assertFalse(new DailySchedule(env,()->720001).attempt(DUE,()->true).started);
        assertEquals(720001,env.settings.getLong("last_duration_ms"));
        assertFalse(new DailySchedule(env,()->{throw new AssertionError("Do not reroll unknown creation");}).attempt(DUE+1000,()->true).attempted);
        assertEquals(1,env.starts);
    }
    @Test public void nextDayCanStartAnotherRun()throws Exception{
        Fake env=new Fake();DailySchedule schedule=new DailySchedule(env);
        schedule.attempt(DUE,()->true);assertTrue(schedule.attempt(DUE+86400000L,()->true).started);
        assertEquals(2,env.starts);assertEquals("2026-10-13",env.startedDay);
    }
    @Test public void clockRollbackDoesNotRepeatOrUndoReservation()throws Exception{
        Fake env=new Fake();DailySchedule schedule=new DailySchedule(env);schedule.attempt(DUE,()->true);
        assertFalse(schedule.attempt(DUE-86400000L,()->true).attempted);
        env.settings.put("last_attempt_ms",DUE+2*86400000L);
        assertFalse(schedule.attempt(DUE+86400000L,()->true).attempted);assertEquals(1,env.starts);
    }
    @Test public void pendingPausedAndUnknownRunsAreSkippedAndRemainUntouched()throws Exception{
        for(String phase:new String[]{"created","paused","check_failed","create_pending","create_unknown","checking","finish_pending","record_updated","finish_unknown"}){
            Fake env=new Fake();env.current=new JSONObject().put("status",phase).put("record_id",40);
            DailySchedule.Result result=new DailySchedule(env).attempt(DUE,()->true);
            assertTrue(phase,result.attempted);assertFalse(phase,result.started);assertEquals(0,env.starts);
            assertEquals(phase,env.current.getString("status"));assertEquals("skipped",env.settings.getString("last_status"));
            assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true).attempted);
        }
    }
    @Test public void finishedAndDiscardedRunsDoNotBlockNextDay()throws Exception{
        for(String phase:new String[]{"finished","discarded"}){
            Fake env=new Fake();env.current=new JSONObject().put("status",phase);
            assertTrue(new DailySchedule(env).attempt(DUE,()->true).started);
        }
    }
    @Test public void missingMalformedOrChangedSessionCannotReachNetwork()throws Exception{
        for(int kind=0;kind<3;kind++){
            Fake env=new Fake();if(kind==0)env.account=null;
            else if(kind==1)env.account.getJSONObject("headers").put("X-Session-ID","");
            else env.account.put("student_id","different-account");
            assertFalse(new DailySchedule(env).attempt(DUE,()->true).started);assertEquals(0,env.starts);
            assertEquals("failed",env.settings.getString("last_status"));
        }
    }
    @Test public void invalidStoredCoordinatesCannotReachNetwork()throws Exception{
        Fake env=new Fake();env.settings.put("lat",100);
        assertFalse(new DailySchedule(env).attempt(DUE,()->true).started);assertEquals(0,env.starts);
    }
    @Test public void failedReservationNeverCallsNetwork()throws Exception{
        Fake env=new Fake();env.failWrite=1;
        try{new DailySchedule(env).attempt(DUE,()->true);fail();}catch(IllegalStateException expected){}
        assertEquals(0,env.starts);assertFalse(env.settings.has("last_day"));
    }
    @Test public void networkFailureDoesNotBlindlyRetryAfterReopen()throws Exception{
        Fake env=new Fake();env.networkFailure=true;
        assertFalse(new DailySchedule(env).attempt(DUE,()->true).started);assertEquals("failed",env.settings.getString("last_status"));
        env.networkFailure=false;assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true).attempted);assertEquals(1,env.starts);
    }
    @Test public void failedOutcomeSaveKeepsReservationAndReturnsCreatedRecordForTimer()throws Exception{
        Fake env=new Fake();env.failWrite=2;
        DailySchedule.Result result=new DailySchedule(env).attempt(DUE,()->true);
        assertTrue(result.started);assertEquals(77,result.recordId);assertEquals("preparing",env.settings.getString("last_status"));
        assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true).attempted);assertEquals(1,env.starts);
    }
    @Test public void stoppedTriggerDoesNotReserveOrCreate()throws Exception{
        Fake env=new Fake();assertFalse(new DailySchedule(env).attempt(DUE,()->false).attempted);
        assertEquals(0,env.writes);assertEquals(0,env.starts);
    }
    @Test public void leavingForegroundDuringPreparationSkipsToday()throws Exception{
        Fake env=new Fake();int[] checks={0};
        DailySchedule.Result result=new DailySchedule(env).attempt(DUE,()->++checks[0]==1);
        assertTrue(result.attempted);assertFalse(result.started);assertEquals(0,env.starts);
        assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true).attempted);
    }
    @Test public void disablingAndReenablingKeepsSameDayQuotaAndUpdatesOrigin()throws Exception{
        Fake env=new Fake();new DailySchedule(env).attempt(DUE,()->true);
        env.settings=DailySchedule.enabled(DailySchedule.disabled(env.settings),env.account,30.82,121.5);
        assertEquals(30.82,env.settings.getDouble("lat"),0);
        assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true).attempted);
        assertTrue(new DailySchedule(env).attempt(DUE+86400000L,()->true).started);assertEquals(30.82,env.lat,0);
    }
    @Test public void nextAlarmIsTodayBefore0615AndTomorrowAfterwards()throws Exception{
        Fake env=new Fake();assertEquals(DUE,DailySchedule.nextAlarm(env.settings,DUE-1));
        assertEquals(DUE+86400000L,DailySchedule.nextAlarm(env.settings,DUE));
        assertEquals(DUE+86400000L,DailySchedule.nextAlarm(env.settings,DUE+120000));
        // Reopening after the grace period does not run a missed alarm immediately.
        assertEquals(DUE+86400000L,DailySchedule.nextAlarm(env.settings,DUE+3600000));
    }
    @Test public void disabledSettingsDoNotRegisterAnAlarm()throws Exception{
        assertEquals(0,DailySchedule.nextAlarm(null,DUE));Fake env=new Fake();
        assertEquals(0,DailySchedule.nextAlarm(DailySchedule.disabled(env.settings),DUE));
    }
    @Test public void nextAlarmSurvivesSameDayQuotaAndClockRollback()throws Exception{
        Fake env=new Fake();new DailySchedule(env).attempt(DUE,()->true);
        assertEquals(DUE+86400000L,DailySchedule.nextAlarm(env.settings,DUE-86400000L));
        assertEquals(DUE+86400000L,DailySchedule.nextAlarm(env.settings,DUE-1000));
        env.settings.put("last_attempt_ms",DUE+86400000L+1000);
        assertEquals(DUE+2*86400000L,DailySchedule.nextAlarm(env.settings,DUE));
    }
    @Test public void nextAlarmUsesBeijingTimeAtMonthBoundary()throws Exception{
        Fake env=new Fake();long time=Instant.parse("2026-10-31T22:16:00Z").toEpochMilli();
        long expected=Instant.parse("2026-11-01T22:15:00Z").toEpochMilli();
        TimeZone previous=TimeZone.getDefault();
        try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertEquals(expected,DailySchedule.nextAlarm(env.settings,time));}
        finally{TimeZone.setDefault(previous);}
    }
    @Test public void alarmGateAllowsBoundedSystemDelayButNeverEarlyOrStaleDelivery()throws Exception{
        Fake env=new Fake();assertFalse(DailySchedule.dueForAlarm(env.settings,DUE-1,DUE));
        assertTrue(DailySchedule.dueForAlarm(env.settings,DUE,DUE));
        assertTrue(DailySchedule.dueForAlarm(env.settings,DUE+DailySchedule.ALARM_GRACE_MS-1,DUE));
        assertFalse(DailySchedule.dueForAlarm(env.settings,DUE+DailySchedule.ALARM_GRACE_MS,DUE));
        assertFalse(DailySchedule.dueForAlarm(env.settings,DUE+86400000L,DUE));
        assertFalse(DailySchedule.dueForAlarm(env.settings,DUE,DUE+1));
        assertFalse(DailySchedule.dueForAlarm(env.settings,DUE+60000,DUE+60000));
        assertFalse(DailySchedule.dueForAlarm(env.settings,DUE,0));
    }
    @Test public void systemAlarmCanStartWhileActivityIsAbsentAndDelayedPastForegroundMinute()throws Exception{
        Fake env=new Fake();long delivered=DUE+120000;
        assertFalse(DailySchedule.due(env.settings,delivered));
        DailySchedule.Result result=new DailySchedule(env).attempt(delivered,()->true,DUE);
        assertTrue(result.started);assertEquals(1,env.starts);assertEquals("2026-10-12",env.startedDay);
        assertFalse(new DailySchedule(env).attempt(delivered+1000,()->true,DUE).attempted);
    }
    @Test public void foregroundAndAlarmShareTheSameDailyReservationInEitherOrder()throws Exception{
        Fake first=new Fake();new DailySchedule(first).attempt(DUE,()->true);
        assertFalse(new DailySchedule(first).attempt(DUE+1000,()->true,DUE).attempted);assertEquals(1,first.starts);
        Fake second=new Fake();new DailySchedule(second).attempt(DUE,()->true,DUE);
        assertFalse(new DailySchedule(second).attempt(DUE+1000,()->true).attempted);assertEquals(1,second.starts);
    }
    @Test public void disabledAndPausedStatesStillBlockBackgroundAlarm()throws Exception{
        Fake disabled=new Fake();disabled.settings=DailySchedule.disabled(disabled.settings);
        assertFalse(new DailySchedule(disabled).attempt(DUE,()->true,DUE).attempted);assertEquals(0,disabled.starts);
        Fake paused=new Fake();paused.current=new JSONObject().put("status","paused").put("record_id",40);
        assertFalse(new DailySchedule(paused).attempt(DUE,()->true,DUE).started);assertEquals(0,paused.starts);
        assertEquals("paused",paused.current.getString("status"));assertEquals("skipped",paused.settings.getString("last_status"));
    }
    @Test public void lateOrStoppedAlarmDoesNotReserveToday()throws Exception{
        Fake env=new Fake();assertFalse(new DailySchedule(env).attempt(DUE+3600000,()->true,DUE).attempted);
        assertFalse(new DailySchedule(env).attempt(DUE,()->false,DUE).attempted);
        assertEquals(0,env.writes);assertEquals(0,env.starts);assertFalse(env.settings.has("last_day"));
    }
    @Test public void failedAlarmReservationAndTimeoutNeverCreateDuplicates()throws Exception{
        Fake disk=new Fake();disk.failWrite=1;
        try{new DailySchedule(disk).attempt(DUE,()->true,DUE);fail();}catch(IllegalStateException expected){}
        assertEquals(0,disk.starts);
        Fake network=new Fake();network.networkFailure=true;
        assertFalse(new DailySchedule(network).attempt(DUE,()->true,DUE).started);
        assertFalse(new DailySchedule(network).attempt(DUE+1000,()->true).attempted);
        assertFalse(new DailySchedule(network).attempt(DUE+120000,()->true,DUE).attempted);assertEquals(1,network.starts);
    }
    @Test public void legacySettingsWithoutTimeStillUse0615()throws Exception{
        Fake env=new Fake();env.settings.remove("hour");env.settings.remove("minute");
        assertEquals("06:15",DailySchedule.timeLabel(env.settings));
        assertEquals(DUE,DailySchedule.nextAlarm(env.settings,DUE-1));
        assertTrue(DailySchedule.due(env.settings,DUE));assertTrue(DailySchedule.dueForAlarm(env.settings,DUE,DUE));
        assertTrue(new DailySchedule(env).attempt(DUE,()->true,DUE).started);
    }
    @Test public void custom0730ReplacesForegroundAndAlarmTriggerTimes()throws Exception{
        Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,7,30);
        long selected=Instant.parse("2026-10-11T23:30:00Z").toEpochMilli();
        assertEquals("07:30",DailySchedule.timeLabel(env.settings));assertFalse(DailySchedule.due(env.settings,DUE));
        assertFalse(DailySchedule.due(env.settings,selected-1));assertTrue(DailySchedule.due(env.settings,selected));
        assertTrue(DailySchedule.due(env.settings,selected+59999));assertFalse(DailySchedule.due(env.settings,selected+60000));
        assertEquals(selected,DailySchedule.nextAlarm(env.settings,DUE));
        assertEquals(selected+86400000L,DailySchedule.nextAlarm(env.settings,selected));
        assertTrue(new DailySchedule(env).attempt(selected+120000,()->true,selected).started);
    }
    @Test public void rangeIncludes0600And2100WithRandomMillisecondDuration()throws Exception{
        long[] selected={Instant.parse("2026-10-11T22:00:00Z").toEpochMilli(),Instant.parse("2026-10-12T13:00:00Z").toEpochMilli()};
        int[] hours={6,21};
        for(int i=0;i<hours.length;i++){
            Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,hours[i],0);
            assertEquals(selected[i],DailySchedule.nextAlarm(env.settings,selected[i]-1));
            assertTrue(new DailySchedule(env).attempt(selected[i],()->true,selected[i]).started);
            JSONObject run=new JSONObject().put("status","created").put("auto_submit",true).put("record_id",77)
                .put("start_ms",selected[i]).put("duration_ms",env.settings.getLong("last_duration_ms"));
            long end=selected[i]+env.settings.getLong("last_duration_ms");
            assertEquals(1,RunClock.remainingMillis(run,end-1));
            assertEquals(0,RunClock.remainingMillis(run,end));
            RunClock.requireFinishable(run,end);
        }
    }
    @Test public void invalidTimesAreRejectedWithoutChangingSavedSchedule()throws Exception{
        Fake env=new Fake();String original=env.settings.toString();
        for(int[] time:new int[][]{{5,59},{21,1},{21,59},{22,0},{6,-1},{6,60},{-1,0},{24,0}}){
            try{DailySchedule.withTime(env.settings,time[0],time[1]);fail("Invalid time must be rejected");}
            catch(IllegalArgumentException expected){}
            assertEquals(original,env.settings.toString());
        }
        env.settings.put("hour",5).put("minute",59);
        long early=Instant.parse("2026-10-11T21:59:00Z").toEpochMilli();
        assertFalse(new DailySchedule(env).attempt(early,()->true).attempted);
        assertFalse(new DailySchedule(env).attempt(early,()->true,early).attempted);assertEquals(0,env.starts);
    }
    @Test public void oldAlarmCannotStartAfterChangingTime()throws Exception{
        Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,7,30);
        assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true,DUE).attempted);
        long selected=DUE+75*60000L;
        assertFalse(new DailySchedule(env).attempt(selected,()->true,DUE).attempted);
        assertTrue(new DailySchedule(env).attempt(selected,()->true,selected).started);assertEquals(1,env.starts);
    }
    @Test public void differentTimeCanRunAgainTodayAfterDisableAndReenable()throws Exception{
        Fake env=new Fake();assertTrue(new DailySchedule(env).attempt(DUE,()->true).started);
        env.settings=DailySchedule.withTime(DailySchedule.disabled(env.settings),18,45);
        env.settings=DailySchedule.enabled(env.settings,env.account,30.82,121.5);
        long selected=Instant.parse("2026-10-12T10:45:00Z").toEpochMilli();
        assertEquals("18:45",DailySchedule.timeLabel(env.settings));assertEquals(77,env.settings.getLong("last_record_id"));
        assertEquals(selected,DailySchedule.nextAlarm(env.settings,DUE+1));
        assertTrue(new DailySchedule(env).attempt(selected,()->true).started);
        assertFalse(new DailySchedule(env).attempt(selected,()->true,selected).attempted);
        assertEquals(selected+86400000L,DailySchedule.nextAlarm(env.settings,DUE+1));
        assertTrue(new DailySchedule(env).attempt(selected+86400000L,()->true,selected+86400000L).started);
        assertEquals(3,env.starts);
    }
    @Test public void multipleChangesKeepAllUsedTimesReservedWhileAllowingUnusedTime()throws Exception{
        Fake env=new Fake();DailySchedule scheduler=new DailySchedule(env);assertTrue(scheduler.attempt(DUE,()->true).started);
        long second=DUE+45*60000;env.settings=DailySchedule.withTime(env.settings,7,0);
        assertTrue(scheduler.attempt(second,()->true,second).started);
        env.settings=DailySchedule.withTime(env.settings,6,15);
        assertTrue(DailySchedule.reserved(env.settings,DUE));assertFalse(scheduler.attempt(DUE,()->true,DUE).attempted);
        env.settings=DailySchedule.withTime(env.settings,7,0);assertFalse(scheduler.attempt(second+1000,()->true,second).attempted);
        env.settings=DailySchedule.withTime(env.settings,8,0);long third=DUE+105*60000;
        assertEquals(third,DailySchedule.nextAlarm(env.settings,second+60000));assertTrue(scheduler.attempt(third,()->true,third).started);
        assertEquals(3,env.starts);assertEquals(3,env.settings.getJSONArray("attempted_times").length());
    }
    @Test public void savingSameTimeNeverClearsReservationEvenAcrossReload()throws Exception{
        Fake env=new Fake();new DailySchedule(env).attempt(DUE,()->true);
        env.settings=DailySchedule.withTime(new JSONObject(env.settings.toString()),6,15);
        assertFalse(new DailySchedule(env).attempt(DUE+1000,()->true,DUE).attempted);
        assertEquals(DUE+86400000L,DailySchedule.nextAlarm(env.settings,DUE-1000));assertEquals(1,env.starts);
    }
    @Test public void upgradeMigratesOldAttemptBeforeChangingTimeAndAllowsLaterSlotToday()throws Exception{
        Fake env=new Fake();env.settings.remove("schedule_schema");env.settings.remove("attempted_times");
        env.settings.put("last_day","2026-10-12").put("last_attempt_ms",DUE+1000).put("last_status","started");
        JSONObject original=new JSONObject(env.settings.toString());
        assertTrue(DailySchedule.reserved(env.settings,DUE));assertFalse(DailySchedule.dueForAlarm(env.settings,DUE+2000,DUE));
        env.settings=DailySchedule.withTime(env.settings,18,45);long selected=DUE+750*60000;
        assertTrue(DailySchedule.reserved(env.settings,DUE));assertFalse(DailySchedule.reserved(env.settings,selected));
        assertEquals(selected,DailySchedule.nextAlarm(env.settings,DUE+10000));assertTrue(new DailySchedule(env).attempt(selected,()->true,selected).started);
        assertFalse(original.has("schedule_schema"));assertEquals(2,env.settings.getJSONArray("attempted_times").length());
    }
    @Test public void migrationUsesPreparedTargetEvenIfOldSelectedTimeWasChanged()throws Exception{
        Fake env=new Fake();env.settings.remove("schedule_schema");env.settings.put("hour",18).put("minute",45)
            .put("last_day","2026-10-12").put("last_attempt_ms",DUE+1000).put("prep_target_ms",DUE).put("prep_status","consumed");
        assertTrue(DailySchedule.migrate(env.settings));assertFalse(DailySchedule.migrate(env.settings));
        assertEquals("06:15",env.settings.getJSONArray("attempted_times").getString(0));
        assertFalse(DailySchedule.reserved(env.settings,DUE+750*60000));
    }
    @Test public void pendingPreviousRecordStillPreventsNewTimeCreatingAnotherRecord()throws Exception{
        Fake env=new Fake();new DailySchedule(env).attempt(DUE,()->true);env.current=new JSONObject().put("status","created").put("record_id",77);
        env.settings=DailySchedule.withTime(env.settings,7,0);long selected=DUE+45*60000;
        DailySchedule.Result result=new DailySchedule(env).attempt(selected,()->true,selected);
        assertTrue(result.attempted);assertFalse(result.started);assertEquals(1,env.starts);assertEquals(77,env.current.getLong("record_id"));
        env.current.put("status","finished");assertFalse(new DailySchedule(env).attempt(selected+1000,()->true,selected).attempted);
        env.settings=DailySchedule.withTime(env.settings,8,0);assertTrue(new DailySchedule(env).attempt(DUE+105*60000,()->true).started);assertEquals(2,env.starts);
    }
    @Test public void nextDayResetsTimeListAndSameDayFailuresOnlyReserveTheirOwnSlot()throws Exception{
        Fake env=new Fake();env.networkFailure=true;new DailySchedule(env).attempt(DUE,()->true);
        env.networkFailure=false;env.settings=DailySchedule.withTime(env.settings,7,0);long second=DUE+45*60000;
        assertTrue(new DailySchedule(env).attempt(second,()->true).started);assertEquals(2,env.settings.getJSONArray("attempted_times").length());
        assertTrue(new DailySchedule(env).attempt(second+86400000L,()->true).started);
        assertEquals(1,env.settings.getJSONArray("attempted_times").length());assertEquals("07:00",env.settings.getJSONArray("attempted_times").getString(0));
        assertFalse(DailySchedule.canAttempt(env.settings,DUE,DUE));
    }
    @Test public void changingToPastTimeSchedulesTomorrowWithoutBackfill()throws Exception{
        Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,6,0);
        long next=Instant.parse("2026-10-12T22:00:00Z").toEpochMilli();
        assertEquals(next,DailySchedule.nextAlarm(env.settings,DUE));
        assertFalse(new DailySchedule(env).attempt(DUE,()->true).attempted);assertEquals(0,env.starts);
        env.settings=DailySchedule.withTime(env.settings,7,30);
        assertEquals(DUE+75*60000L,DailySchedule.nextAlarm(env.settings,DUE));
    }
    @Test public void customTimeUsesBeijingTimezoneAcrossMonthBoundary()throws Exception{
        Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,18,45);
        long now=Instant.parse("2026-10-31T10:46:00Z").toEpochMilli();
        long next=Instant.parse("2026-11-01T10:45:00Z").toEpochMilli();
        TimeZone previous=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(next,DailySchedule.nextAlarm(env.settings,now));
            assertTrue(DailySchedule.due(env.settings,next));assertTrue(DailySchedule.dueForAlarm(env.settings,next,next));
        }finally{TimeZone.setDefault(previous);}
    }
    @Test public void timeChangeClearsStaleAlarmHintsAndPreservesOriginAndQuota()throws Exception{
        Fake env=new Fake();env.settings.put("next_alarm_ms",DUE).put("alarm_error","old alarm error")
            .put("last_missed_day","2026-10-12").put("last_day","2026-10-11").put("last_attempt_ms",DUE-86400000L);
        JSONObject changed=DailySchedule.withTime(env.settings,18,45);
        assertFalse(changed.has("next_alarm_ms"));assertFalse(changed.has("alarm_error"));assertFalse(changed.has("last_missed_day"));
        assertEquals("2026-10-11",changed.getString("last_day"));assertEquals(DUE-86400000L,changed.getLong("last_attempt_ms"));
        assertEquals(env.settings.getDouble("lat"),changed.getDouble("lat"),0);
        assertEquals(env.settings.getDouble("lng"),changed.getDouble("lng"),0);assertTrue(changed.getBoolean("enabled"));
        assertTrue(env.settings.has("next_alarm_ms"));
        assertTrue(DailySchedule.withTime(env.settings,6,15).has("last_missed_day"));
    }
}

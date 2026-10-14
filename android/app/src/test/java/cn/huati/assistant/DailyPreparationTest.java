package cn.huati.assistant;

import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyPreparationTest {
    private static final long START=Instant.parse("2026-10-11T22:15:00Z").toEpochMilli();
    private static final class Fake implements DailyPreparation.Environment {
        JSONObject settings,account,current;
        long now=START-DailyPreparation.LEAD_MS;
        long delay;
        int locations,points,refreshes,writes,failWrite;
        boolean refreshFailure,pointFailure,expiredSession;
        JSONArray customPoints;
        final ArrayList<String> requests=new ArrayList<>();
        boolean locationFailure;
        double requestedLat,requestedLng;
        Fake()throws Exception{
            account=new JSONObject().put("host",ApiClient.HOST).put("student_id","test-account").put("headers",new JSONObject().put("X-Session-ID","test-session"));
            settings=DailySchedule.enabled(null,account,0,0).put("location_mode","phone");
        }
        @Override public JSONObject settings()throws Exception{return new JSONObject(settings.toString());}
        @Override public void save(JSONObject value)throws Exception{writes++;if(writes==failWrite)throw new IllegalStateException("disk");settings=new JSONObject(value.toString());}
        @Override public JSONObject config(){return account;}
        @Override public JSONObject run(){return current;}
        @Override public long now(){return now;}
        @Override public JSONObject location()throws Exception{
            locations++;assertEquals("locating",settings.getString("prep_status"));
            if(locationFailure)throw new IllegalStateException("location unavailable");
            return new JSONObject().put("lat",30.8335).put("lng",121.499);
        }
        @Override public void refresh()throws Exception{
            refreshes++;requests.add("refresh");assertTrue(now>=DailyPreparation.opening(DailyPreparation.target(settings,now)));
            if(expiredSession)throw new ApiClient.SessionException("登录已失效");
            if(refreshFailure)throw new IOException("network unavailable");
        }
        @Override public JSONObject points(double lat,double lng)throws Exception{
            points++;requests.add("points");requestedLat=lat;requestedLng=lng;now+=delay;
            if(pointFailure)throw new IOException("points unavailable");
            return new JSONObject().put("student_id","test-account").put("lat",lat).put("lng",lng).put("captured_at",now)
                .put("points",customPoints==null?new JSONArray("[{\"lat\":\"30.827536\",\"lng\":\"121.503933\"},{\"lat\":\"30.832999\",\"lng\":\"121.509337\"},{\"lat\":\"30.829844\",\"lng\":\"121.507996\"}]"):customPoints);
        }
    }
    @Test public void preparationRunsTwoMinutesBeforeStartUsingFreshOriginWithoutCreatingRecord()throws Exception{
        Fake env=new Fake();assertFalse(DailyPreparation.due(env.settings,env.now-1,START));
        assertTrue(DailyPreparation.due(env.settings,env.now,START));new DailyPreparation(env).prepare(START,()->true);
        double[] mapped=MapCoordinates.gcj(30.8335,121.499);
        assertEquals(1,env.locations);assertEquals(1,env.points);assertEquals(mapped[0],env.requestedLat,0);assertEquals(mapped[1],env.requestedLng,0);
        assertEquals("ready",env.settings.getString("prep_status"));assertNull(env.current);assertFalse(env.settings.has("last_day"));
        assertTrue(DailyPreparation.ready(env.settings,START,START));
    }
    @Test public void repeatedForegroundAndAlarmPreparationOnlyFetchPointsOnce()throws Exception{
        Fake env=new Fake();DailyPreparation prep=new DailyPreparation(env);prep.prepare(START,()->true);prep.prepare(START,()->true);
        assertEquals(1,env.locations);assertEquals(1,env.points);assertEquals(0,DailyPreparation.nextWake(env.settings,env.now,START));
    }
    @Test public void preparationAlarmIsEarlyAndCanBeScheduledImmediatelyInsideWindow()throws Exception{
        Fake env=new Fake();assertEquals(START-DailyPreparation.LEAD_MS,DailyPreparation.nextWake(env.settings,START-3600000,START));
        assertEquals(START-59000,DailyPreparation.nextWake(env.settings,START-60000,START));
        assertEquals(0,DailyPreparation.nextWake(env.settings,START,START));
        assertTrue(DailyPreparation.due(env.settings,START,START));
        assertFalse(DailyPreparation.due(env.settings,START+DailySchedule.ALARM_GRACE_MS,START));
        assertFalse(DailyPreparation.due(env.settings,START-60000,START+60000));
    }
    @Test public void failedReservationCannotReadLocationOrContactPointsEndpoint()throws Exception{
        Fake env=new Fake();env.failWrite=1;
        try{new DailyPreparation(env).prepare(START,()->true);fail();}catch(IllegalStateException expected){}
        assertEquals(0,env.locations);assertEquals(0,env.points);
    }
    @Test public void pendingRunAndAccountChangePreventLocationAndPointRequests()throws Exception{
        for(int kind=0;kind<2;kind++){
            Fake env=new Fake();if(kind==0)env.current=new JSONObject().put("status","paused");else env.account.put("student_id","changed");
            new DailyPreparation(env).prepare(START,()->true);assertEquals(0,env.locations);assertEquals(0,env.points);
            assertEquals("2026-10-12",env.settings.getString("last_day"));
        }
    }
    @Test public void timeAndAccountBindingRejectYesterdayOrPreviousTimePreparation()throws Exception{
        Fake env=new Fake();new DailyPreparation(env).prepare(START,()->true);
        assertFalse(DailyPreparation.ready(env.settings,START+86400000L,START));
        assertFalse(DailyPreparation.ready(env.settings,START+DailySchedule.ALARM_GRACE_MS,START));
        JSONObject changed=DailySchedule.withTime(env.settings,7,30);assertFalse(changed.has("prepared_preview"));
        assertFalse(DailyPreparation.ready(changed,START,START));
        env.settings.put("student_id","changed");assertFalse(DailyPreparation.ready(env.settings,START,START));
    }
    @Test public void locationFailureStillPreparesRandomCampusOriginWithoutSkippingRecord()throws Exception{
        Fake env=new Fake();env.locationFailure=true;new DailyPreparation(env).prepare(START,()->true);
        assertEquals("ready",env.settings.getString("prep_status"));assertFalse(env.settings.has("last_day"));assertEquals(1,env.points);
        assertTrue(env.settings.getString("origin_source").contains("定位失败"));
        double[] wgs=MapCoordinates.wgs(env.requestedLat,env.requestedLng);assertTrue(CampusArea.containsWgs(wgs[0],wgs[1]));
    }
    @Test public void disablingPreparedTaskCancelsFutureCreationButPreservesDailyQuota()throws Exception{
        Fake env=new Fake();new DailyPreparation(env).prepare(START,()->true);DailySchedule.reserve(env.settings,START,START);
        JSONObject disabled=DailySchedule.disabled(env.settings);assertFalse(disabled.has("prepared_preview"));
        assertEquals("2026-10-12",disabled.getString("last_day"));assertFalse(DailyPreparation.ready(disabled,START,START));
    }
    @Test public void preparedRecordIsCreatedOnlyAtStartAndDailyQuotaPreventsDuplicates()throws Exception{
        Fake env=new Fake();new DailyPreparation(env).prepare(START,()->true);int[] creates={0};
        DailySchedule schedule=new DailySchedule(new DailySchedule.Environment(){
            @Override public JSONObject settings()throws Exception{return env.settings();}
            @Override public void save(JSONObject value)throws Exception{env.save(value);}
            @Override public JSONObject config(){return env.account;}
            @Override public JSONObject run(){return env.current;}
            @Override public long start(double lat,double lng,String day,long durationMillis){creates[0]++;return 77;}
        });
        assertFalse(schedule.attempt(START-1,()->true,START).attempted);assertEquals(0,creates[0]);
        assertTrue(schedule.attempt(START,()->true,START).started);assertEquals(1,creates[0]);
        assertFalse(schedule.attempt(START+1000,()->true,START).attempted);assertEquals(1,creates[0]);
    }
    @Test public void activePreparationFinishingLateMayStartWithFreshPointsAndStillCreatesOnlyOnce()throws Exception{
        Fake env=new Fake();env.delay=12*60000;new DailyPreparation(env).prepare(START,()->true);
        assertTrue(DailyPreparation.ready(env.settings,env.now,START));int[] creates={0};
        DailySchedule schedule=new DailySchedule(new DailySchedule.Environment(){
            @Override public JSONObject settings()throws Exception{return env.settings();}
            @Override public void save(JSONObject value)throws Exception{env.save(value);}
            @Override public JSONObject config(){return env.account;}
            @Override public JSONObject run(){return env.current;}
            @Override public long start(double lat,double lng,String day,long durationMillis){creates[0]++;return 77;}
        });
        assertTrue(schedule.attempt(env.now,()->true,START).started);
        assertFalse(schedule.attempt(env.now+1000,()->true,START).attempted);assertEquals(1,creates[0]);
    }
    @Test public void cancellingLocationPreparationNeverFetchesPointsOrCreatesRecord()throws Exception{
        Fake env=new Fake();env.locationFailure=true;int[] checks={0};new DailyPreparation(env).prepare(START,()->++checks[0]==1);
        assertEquals(0,env.points);assertNull(env.current);assertEquals("failed",env.settings.getString("prep_status"));
    }
    @Test public void sixOclockPreparationWaitsForOpeningThenRefreshesBeforeFetching()throws Exception{
        Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,6,0);long at=START-15*60000;
        env.now=at-DailyPreparation.LEAD_MS;DailyPreparation prep=new DailyPreparation(env);prep.prepare(at,()->true);
        assertEquals("waiting_open",env.settings.getString("prep_status"));assertEquals(1,env.locations);
        assertEquals(0,env.refreshes);assertEquals(0,env.points);assertFalse(env.settings.has("last_day"));
        assertFalse(DailyPreparation.ready(env.settings,at-1,at));assertFalse(DailyPreparation.due(env.settings,at-1,at));
        assertEquals(at,DailyPreparation.nextWake(env.settings,at-DailyPreparation.LEAD_MS,at));
        env.now=at;prep.prepare(at,()->true);
        assertEquals(1,env.locations);assertEquals(1,env.refreshes);assertEquals(1,env.points);
        assertEquals("refresh",env.requests.get(0));assertEquals("points",env.requests.get(1));
        assertTrue(DailyPreparation.ready(env.settings,at,at));
    }
    @Test public void sixOhOneAlsoWaitsButNormalPreparationRefreshesImmediately()throws Exception{
        for(int minute:new int[]{1,2,15}){
            Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,6,minute);long at=START+(minute-15)*60000L;
            env.now=at-DailyPreparation.LEAD_MS;DailyPreparation prep=new DailyPreparation(env);prep.prepare(at,()->true);
            if(minute==1){assertEquals(0,env.refreshes);assertEquals("waiting_open",env.settings.getString("prep_status"));env.now=DailyPreparation.opening(at);prep.prepare(at,()->true);}
            assertEquals(1,env.refreshes);assertEquals(1,env.points);assertTrue(DailyPreparation.ready(env.settings,at,at));
        }
    }
    @Test public void readOnlyFailuresRetryWithoutConsumingDailyQuotaAndReuseFreshOrigin()throws Exception{
        for(boolean failRefresh:new boolean[]{true,false}){
            Fake env=new Fake();env.refreshFailure=failRefresh;env.pointFailure=!failRefresh;DailyPreparation prep=new DailyPreparation(env);prep.prepare(START,()->true);
            assertEquals("waiting_points",env.settings.getString("prep_status"));assertFalse(env.settings.has("last_day"));assertNull(env.current);
            assertFalse(DailyPreparation.due(env.settings,env.now+DailyPreparation.RETRY_MS-1,START));
            env.now+=DailyPreparation.RETRY_MS;env.refreshFailure=false;env.pointFailure=false;prep.prepare(START,()->true);
            assertTrue(DailyPreparation.ready(env.settings,START,START));assertEquals(1,env.locations);
            assertEquals(2,env.refreshes);assertEquals(failRefresh?1:2,env.points);
        }
    }
    @Test public void retryWakeStillTargetsTodayAfterScheduledTime()throws Exception{
        Fake env=new Fake();env.refreshFailure=true;new DailyPreparation(env).prepare(START,()->true);
        env.now=START+10*60000;long tomorrow=DailySchedule.nextAlarm(env.settings,env.now);
        assertEquals(START+86400000L,tomorrow);assertEquals(START,DailyPreparation.wakeTarget(env.settings,env.now,tomorrow));
        assertEquals(env.now+1000,DailyPreparation.nextWake(env.settings,env.now,START));
        assertFalse(DailyPreparation.waiting(env.settings,START+86400000L,START));
    }
    @Test public void staleOrPreOpeningReadyPreparationIsRefreshedRatherThanReused()throws Exception{
        Fake env=new Fake();new DailyPreparation(env).prepare(START,()->true);
        env.now=START+6*60000;assertFalse(DailyPreparation.ready(env.settings,env.now,START));
        new DailyPreparation(env).prepare(START,()->true);assertTrue(DailyPreparation.ready(env.settings,env.now,START));assertEquals(2,env.locations);
        env.settings=DailySchedule.withTime(env.settings,6,0);long at=START-15*60000;env.now=at;
        env.settings.put("prep_target_ms",at).put("prep_status","ready").put("prepared_refresh_ms",at-60000)
            .put("prepared_preview",env.points(env.requestedLat,env.requestedLng).put("captured_at",at-60000));
        assertFalse(DailyPreparation.ready(env.settings,at,at));assertTrue(DailyPreparation.due(env.settings,at,at));
        new DailyPreparation(env).prepare(at,()->true);assertTrue(DailyPreparation.ready(env.settings,at,at));
    }
    @Test public void sessionFailureStopsRatherThanRetryingAndCancelledWaitingDoesNotFetch()throws Exception{
        Fake env=new Fake();env.expiredSession=true;new DailyPreparation(env).prepare(START,()->true);
        assertEquals("failed",env.settings.getString("prep_status"));assertEquals(0,env.points);assertTrue(env.settings.has("last_day"));
        Fake other=new Fake();other.refreshFailure=true;new DailyPreparation(other).prepare(START,()->true);
        other.settings=DailySchedule.disabled(other.settings);other.now+=DailyPreparation.RETRY_MS;new DailyPreparation(other).prepare(START,()->true);
        assertEquals(1,other.refreshes);assertEquals(0,other.points);assertFalse(other.settings.has("prepared_origin"));
    }
    @Test public void interruptedFetchRestoresRetryAndKeepsOriginAndTimeChangeClearsIt()throws Exception{
        Fake env=new Fake();env.refreshFailure=true;new DailyPreparation(env).prepare(START,()->true);
        env.settings.put("prep_status","fetching");assertTrue(DailyPreparation.recover(env.settings));
        assertEquals("waiting_points",env.settings.getString("prep_status"));env.refreshFailure=false;
        new DailyPreparation(env).prepare(START,()->true);assertTrue(DailyPreparation.ready(env.settings,START,START));assertEquals(1,env.locations);
        JSONObject changed=DailySchedule.withTime(env.settings,7,0);assertFalse(changed.has("prepared_origin"));assertFalse(changed.has("prepared_refresh_ms"));assertFalse(changed.has("next_location_retry_ms"));
    }
    @Test public void automaticLongThreePointRouteIsNotRejectedByManualDistanceTarget()throws Exception{
        Fake env=new Fake();env.customPoints=new JSONArray("[{\"lat\":30.836,\"lng\":121.510},{\"lat\":30.829,\"lng\":121.498},{\"lat\":30.836,\"lng\":121.499}]");
        new DailyPreparation(env).prepare(START,()->true);assertTrue(DailyPreparation.ready(env.settings,START,START));
        assertTrue(RunMath.automaticMetres(env.customPoints,env.requestedLat,env.requestedLng,2200)>2200);
    }
    @Test public void startAlarmDuringPointRetryCannotConsumeQuotaThenFreshPreparationStartsOnce()throws Exception{
        Fake env=new Fake();env.refreshFailure=true;new DailyPreparation(env).prepare(START,()->true);int[] creates={0};
        DailySchedule schedule=new DailySchedule(new DailySchedule.Environment(){
            @Override public JSONObject settings()throws Exception{return env.settings();}
            @Override public void save(JSONObject value)throws Exception{env.save(value);}
            @Override public JSONObject config(){return env.account;}
            @Override public JSONObject run(){return env.current;}
            @Override public long start(double lat,double lng,String day,long durationMillis){creates[0]++;return 77;}
        });
        assertFalse(schedule.attempt(START,()->true,START).attempted);assertFalse(env.settings.has("last_day"));assertEquals(0,creates[0]);
        env.now=START+6*60000;env.refreshFailure=false;new DailyPreparation(env).prepare(START,()->true);
        assertTrue(schedule.attempt(env.now,()->true,START).started);assertFalse(schedule.attempt(env.now+1000,()->true,START).attempted);assertEquals(1,creates[0]);
    }
    @Test public void preparationFinishingAfterTargetKeepsTodayWakeUntilCreationReserved()throws Exception{
        Fake env=new Fake();env.settings=DailySchedule.withTime(env.settings,6,0);long at=START-15*60000;
        env.now=at+1000;new DailyPreparation(env).prepare(at,()->true);
        long next=DailySchedule.nextAlarm(env.settings,env.now);
        assertEquals(at,DailyPreparation.wakeTarget(env.settings,env.now,next));
        assertEquals(env.now+1000,DailyPreparation.nextWake(env.settings,env.now,at));
        DailySchedule.reserve(env.settings,at,env.now);
        assertEquals(next,DailyPreparation.wakeTarget(env.settings,env.now,next));
        assertEquals(0,DailyPreparation.nextWake(env.settings,env.now,at));
    }
}

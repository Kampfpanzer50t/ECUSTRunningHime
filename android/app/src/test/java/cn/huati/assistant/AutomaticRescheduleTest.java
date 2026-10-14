package cn.huati.assistant;

import java.time.Instant;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises two preparations, reservations, timers and submissions without a live server. */
public class AutomaticRescheduleTest {
    private static final long FIRST=Instant.parse("2026-10-11T23:30:00Z").toEpochMilli();
    private static final class Fake implements DailyPreparation.Environment {
        final JSONObject account;
        JSONObject settings,current;
        long now=FIRST-DailyPreparation.LEAD_MS;
        int locations,refreshes,points,creates,checks,updates,uploads,verdict=1;
        final ArrayList<Long> submitted=new ArrayList<>();
        Fake()throws Exception{
            account=new JSONObject().put("host",ApiClient.HOST).put("student_id","test-account")
                .put("headers",new JSONObject().put("X-Session-ID","test-session"));
            settings=DailySchedule.withTime(DailySchedule.enabled(null,account,0,0),7,30).put("location_mode","phone");
        }
        @Override public JSONObject settings()throws Exception{return new JSONObject(settings.toString());}
        @Override public void save(JSONObject value)throws Exception{settings=new JSONObject(value.toString());}
        @Override public JSONObject config(){return account;}
        @Override public JSONObject run(){return current;}
        @Override public JSONObject location()throws Exception{locations++;return new JSONObject().put("lat",30.8335).put("lng",121.499);}
        @Override public void refresh(){refreshes++;}
        @Override public long now(){return now;}
        @Override public JSONObject points(double lat,double lng)throws Exception{
            points++;return new JSONObject().put("student_id","test-account").put("lat",lat).put("lng",lng).put("captured_at",now)
                .put("points",CampusPoints.reference().getJSONArray("points"));
        }
        DailySchedule.Result start(long target)throws Exception{
            return new DailySchedule(new DailySchedule.Environment(){
                @Override public JSONObject settings()throws Exception{return Fake.this.settings();}
                @Override public void save(JSONObject value)throws Exception{Fake.this.save(value);}
                @Override public JSONObject config(){return account;}
                @Override public JSONObject run(){return current;}
                @Override public long start(double lat,double lng,String date,long durationMillis)throws Exception{
                    assertTrue(DailySchedule.reserved(settings,target));assertTrue(DailyPreparation.ready(settings,now,target));
                    JSONObject prepared=settings.getJSONObject("prepared_preview");long id=++creates;
                    int metres=RunMath.automaticMetres(prepared.getJSONArray("points"),prepared.getDouble("lat"),prepared.getDouble("lng"),2200);
                    current=new JSONObject().put("status","created").put("auto_submit",true).put("record_id",id)
                        .put("student_id","test-account").put("start_ms",now).put("duration",(durationMillis+999)/1000).put("duration_ms",durationMillis).put("distance",metres)
                        .put("points",prepared.getJSONArray("points")).put("lat",prepared.getDouble("lat")).put("lng",prepared.getDouble("lng"))
                        .put("scheduled_day",date).put("pauses",new JSONArray());return id;
                }
            }).attempt(now,()->true,target);
        }
        void finish()throws Exception{
            assertEquals(0,RunClock.remainingSeconds(current,now));RunClock.requireFinishable(current,now);
            JSONObject payload=new JSONObject().put("record_id",current.getLong("record_id")).put("mileage",current.getInt("distance")).put("running_time",(now-current.getLong("start_ms"))/1000);
            JSONArray path=RunClock.path(current,now);
            new RecordSubmission(new RecordSubmission.Environment(){
                @Override public JSONObject request(String route,JSONObject body)throws Exception{
                    Object data;
                    switch(route){
                        case"/xcxapi/getUserInfo/":data=new JSONObject().put("id","test-account");break;
                        case"/xcxapi/checkRecord/":checks++;data=new JSONObject().put("status",verdict).put("warning",verdict==0?"今日已有跑步记录":"");break;
                        case"/xcxapi/updateRecordNew/":updates++;submitted.add(current.getLong("record_id"));data=new JSONObject();break;
                        case"/xcxapi/uploadPathPointV3/":uploads++;data=new JSONObject();break;
                        default:throw new AssertionError(route);
                    }
                    return new JSONObject().put("code",1).put("data",data);
                }
                @Override public void save(JSONObject value)throws Exception{current=new JSONObject(value.toString());}
                @Override public void event(String event){}
            }).submit(current,payload,path,now);
        }
    }
    @Test public void changingTimeAfterCompletionRepeatsFullAutomaticWorkflowAndSubmitsInvalidSecondRun()throws Exception{
        Fake env=new Fake();new DailyPreparation(env).prepare(FIRST,()->true);env.now=FIRST;
        assertTrue(env.start(FIRST).started);env.now+=RunClock.durationMillis(env.current);env.finish();assertEquals("finished",env.current.getString("status"));
        long second=FIRST+90*60000;env.settings=DailySchedule.withTime(env.settings,9,0);
        assertEquals(second,DailySchedule.nextAlarm(env.settings,env.now));assertFalse(env.start(FIRST).attempted);
        env.now=second-DailyPreparation.LEAD_MS;new DailyPreparation(env).prepare(second,()->true);
        assertEquals(second-DailyPreparation.LEAD_MS,env.settings.getJSONObject("prepared_preview").getLong("captured_at"));
        env.now=second;assertTrue(env.start(second).started);assertFalse(env.start(second).attempted);
        assertEquals(2,env.locations);assertEquals(2,env.refreshes);assertEquals(2,env.points);assertEquals(2,env.creates);
        env.verdict=0;env.now+=RunClock.durationMillis(env.current);env.finish();assertEquals("finished",env.current.getString("status"));
        assertTrue(env.current.getBoolean("accepted_invalid"));assertEquals(RunClock.durationMillis(env.current)/1000,env.current.getInt("actual_duration"));
        assertEquals(2,env.checks);assertEquals(2,env.updates);assertTrue(env.uploads>2);
        assertEquals(Long.valueOf(1),env.submitted.get(0));assertEquals(Long.valueOf(2),env.submitted.get(1));
        assertEquals(2,env.settings.getJSONArray("attempted_times").length());assertFalse(env.start(second).attempted);
    }
}

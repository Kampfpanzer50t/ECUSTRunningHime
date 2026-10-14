package cn.huati.assistant;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ManualDurationTest {
    @Test public void inputAndPersistedPauseKeepMilliseconds()throws Exception{
        long duration=ManualDuration.parse("15","12","345");assertEquals(912345,duration);
        JSONObject run=new JSONObject().put("status","created").put("start_ms",100000).put("duration_ms",duration).put("auto_submit",true);
        RunClock.pause(run,200000);JSONObject reopened=new JSONObject(run.toString());
        assertEquals(812345,RunClock.remainingMillis(reopened,800000));
        RunClock.resume(reopened,800000);assertEquals(0,RunClock.remainingMillis(reopened,1612345));
        assertEquals(1,RunClock.remainingMillis(reopened,1612344));assertEquals(duration,reopened.getLong("duration_ms"));
    }
    @Test public void exactBoundariesAreAccepted(){assertEquals(60000,ManualDuration.parse("1","0","0"));assertEquals(7200000,ManualDuration.parse("120","0","0"));}
    @Test public void invalidComponentsCannotCreateADuration(){
        String[][] values={{"15","60","0"},{"15","0","1000"},{"15.2","0","0"},{"1","0","-1"},{"0","59","999"},{"120","0","1"},{"","0","0"},{"99999999999999","0","0"}};
        for(String[] value:values){assertThrows(IllegalArgumentException.class,()->ManualDuration.parse(value[0],value[1],value[2]));}
    }
}

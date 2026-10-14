package cn.huati.assistant;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundRegressionTest {
    private JSONObject run()throws Exception{
        return new JSONObject().put("status","created").put("record_id",77).put("auto_submit",true)
            .put("start_ms",1000000).put("duration",900).put("pauses",new JSONArray());
    }
    @Test public void killedTimerMayResumeSameRecordBeforeAndAfterCountdownEnds()throws Exception{
        JSONObject state=run();assertTrue(RunRecovery.shouldRestore(state,1100000));
        assertTrue(RunRecovery.shouldRestore(state,2000000));assertEquals(77,state.getLong("record_id"));
    }
    @Test public void userPausedOrDiscardedRunNeverRestartsOnOpen()throws Exception{
        for(String phase:new String[]{"paused","discarded","check_failed","finished","checking","create_unknown","finish_unknown","finish_pending","record_updated"}){
            JSONObject state=run().put("status",phase);assertFalse(phase,RunRecovery.shouldRestore(state,2000000));
        }
    }
    @Test public void missingRecordAndDisabledAutoSubmissionCannotRecover()throws Exception{
        assertFalse(RunRecovery.shouldRestore(null,2000000));assertFalse(RunRecovery.shouldRestore(run().put("record_id",0),2000000));
        assertFalse(RunRecovery.shouldRestore(run().put("auto_submit",false),2000000));
    }
    @Test public void clockRollbackAndOverDayRecordsAreNotResubmitted()throws Exception{
        assertFalse(RunRecovery.shouldRestore(run(),999999));assertFalse(RunRecovery.shouldRestore(run(),87400001));
        assertTrue(RunRecovery.shouldRestore(run(),87400000));
    }
    @Test public void diagnosticsNeverCopyCredentialsCoordinatesPayloadsOrRawErrorMessages()throws Exception{
        JSONObject input=new JSONObject().put("source","alarm").put("phase","created").put("exact_alarm",true)
            .put("X-Session-ID","secret-session").put("headers",new JSONObject().put("Authorization","secret-auth"))
            .put("lat",30.8).put("lng",121.5).put("phone","secret-phone").put("student_id","secret-student")
            .put("password","secret-password").put("payload","secret-payload").put("error_message","secret-error");
        String logged=DiagnosticLog.append(null,"created",100,input).toString();
        assertFalse(logged.contains("secret"));assertFalse(logged.contains("30.8"));assertFalse(logged.contains("121.5"));
        assertTrue(logged.contains("created"));assertTrue(logged.contains("exact_alarm"));
    }
    @Test public void diagnosticsBoundSizeAndKeepTheMostRecentEvents()throws Exception{
        JSONArray events=new JSONArray();for(int i=0;i<125;i++)events=DiagnosticLog.append(events,"event_"+i,i,null);
        assertEquals(100,events.length());assertEquals("event_25",events.getJSONObject(0).getString("event"));
        assertEquals("event_124",events.getJSONObject(99).getString("event"));
    }
    @Test public void failureTypesSeparateNetworkAndPermissionFailuresWithoutDumpingExceptionText(){
        assertEquals("network_timeout",DiagnosticLog.failure(new SocketTimeoutException("secret")));
        assertEquals("dns_failed",DiagnosticLog.failure(new UnknownHostException("secret")));
        assertEquals("tls_failed",DiagnosticLog.failure(new javax.net.ssl.SSLException("secret")));
        assertEquals("system_permission_denied",DiagnosticLog.failure(new SecurityException("secret")));
        assertEquals("network_or_storage_io",DiagnosticLog.failure(new IOException("secret")));
        assertEquals("session_failed",DiagnosticLog.failure(new IllegalStateException("登录已失效 secret")));
    }
}

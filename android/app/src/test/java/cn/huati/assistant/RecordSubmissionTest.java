package cn.huati.assistant;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordSubmissionTest {
    private static final String USER="/xcxapi/getUserInfo/",CHECK="/xcxapi/checkRecord/",UPDATE="/xcxapi/updateRecordNew/",UPLOAD="/xcxapi/uploadPathPointV3/";
    private JSONObject state()throws Exception{return new JSONObject().put("status","created").put("student_id","test-account").put("record_id",77).put("start_ms",1000000).put("duration",900).put("auto_submit",true).put("pauses",new JSONArray());}
    private JSONObject payload()throws Exception{return new JSONObject().put("record_id",77).put("mileage",2200).put("running_time",900);}
    private JSONArray path()throws Exception{JSONArray points=new JSONArray();for(int i=0;i<123;i++)points.put(new JSONObject().put("lat",30.83).put("lng",121.50+i*0.00001).put("timestamp",1000000+i*7000));return points;}
    private static JSONObject decode(String encrypted)throws Exception{
        String[] parts=encrypted.split(":");Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec("A7fK92LmXyQw8vRp".getBytes(StandardCharsets.US_ASCII),"AES"),new IvParameterSpec(Base64.getDecoder().decode(parts[0])));
        return new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8));
    }
    private static JSONObject success(Object data)throws Exception{return new JSONObject().put("code",1).put("data",data);}
    private static class Fake implements RecordSubmission.Environment {
        int verdict=1,failUploadAt=0,uploads=0;String account="test-account",failPath="";
        ArrayList<String> requests=new ArrayList<>(),states=new ArrayList<>();ArrayList<Integer> batches=new ArrayList<>();JSONObject saved;
        @Override public JSONObject request(String path,JSONObject body)throws Exception{
            requests.add(path);if(path.equals(failPath))throw new SocketTimeoutException("test timeout");
            if(path.equals(USER))return success(new JSONObject().put("id",account));
            if(path.equals(CHECK)){assertEquals(77,decode(body.getString("a")).getInt("record_id"));return success(new JSONObject().put("status",verdict).put("warning",verdict==0?"今日已有跑步记录":""));}
            if(path.equals(UPDATE)){assertEquals(2200,decode(body.getString("a")).getInt("mileage"));return success(new JSONObject());}
            if(path.equals(UPLOAD)){
                if(++uploads==failUploadAt)throw new SocketTimeoutException("test upload timeout");
                JSONObject batch=decode(body.getString("img_url"));assertEquals(77,batch.getLong("record_id"));batches.add(batch.getJSONArray("path_point").length());return success(new JSONObject());
            }throw new AssertionError("Unexpected network call "+path);
        }
        @Override public void save(JSONObject state)throws Exception{saved=new JSONObject(state.toString());states.add(state.getString("status"));}
        @Override public void event(String event){}
    }
    private void fails(Fake fake,JSONObject state)throws Exception{
        try{new RecordSubmission(fake).submit(state,payload(),path(),1900000);fail("Expected failure");}catch(IllegalStateException|SocketTimeoutException expected){}
    }
    @Test public void invalidVerdictImmediatelyUpdatesAndUploadsAllBatches()throws Exception{
        Fake fake=new Fake();fake.verdict=0;JSONObject state=state();new RecordSubmission(fake).submit(state,payload(),path(),1900000);
        assertEquals(Arrays.asList(USER,CHECK,UPDATE,UPLOAD,UPLOAD,UPLOAD),fake.requests);assertEquals(Arrays.asList(50,50,23),fake.batches);
        assertEquals("finished",fake.saved.getString("status"));assertTrue(fake.saved.getBoolean("accepted_invalid"));assertEquals("今日已有跑步记录",fake.saved.getString("invalid_reason"));
        assertEquals(123,fake.saved.getInt("uploaded_points"));assertFalse(fake.saved.getBoolean("auto_submit"));assertFalse(fake.states.contains("check_failed"));
        assertEquals(1900000,fake.saved.getLong("end_ms"));assertEquals(900,fake.saved.getInt("actual_duration"));
    }
    @Test public void validVerdictAlsoFinishesSameRecord()throws Exception{
        Fake fake=new Fake();new RecordSubmission(fake).submit(state(),payload(),path(),1900000);
        assertEquals("finished",fake.saved.getString("status"));assertFalse(fake.saved.getBoolean("accepted_invalid"));assertEquals(Arrays.asList(50,50,23),fake.batches);
    }
    @Test public void unknownVerdictPausesWithoutUpdatingOrUploading()throws Exception{
        Fake fake=new Fake();fake.verdict=2;JSONObject state=state();fails(fake,state);
        assertEquals(Arrays.asList(USER,CHECK),fake.requests);assertEquals("paused",fake.saved.getString("status"));assertFalse(fake.saved.optBoolean("auto_submit"));
    }
    @Test public void differentAccountStopsBeforeCheckingOrSubmitting()throws Exception{
        Fake fake=new Fake();fake.account="different";fails(fake,state());assertEquals(Arrays.asList(USER),fake.requests);assertEquals("paused",fake.saved.getString("status"));
    }
    @Test public void checkTimeoutPausesAndPreservesRecordId()throws Exception{
        Fake fake=new Fake();fake.failPath=CHECK;fails(fake,state());assertEquals(Arrays.asList(USER,CHECK),fake.requests);assertEquals(77,fake.saved.getLong("record_id"));assertEquals("paused",fake.saved.getString("status"));
    }
    @Test public void updateTimeoutBlocksBlindResubmission()throws Exception{
        Fake fake=new Fake();fake.failPath=UPDATE;JSONObject state=state();fails(fake,state);assertEquals("finish_unknown",fake.saved.getString("status"));
        int count=fake.requests.size();fails(fake,state);assertEquals(count,fake.requests.size());assertFalse(fake.saved.optBoolean("auto_submit"));
    }
    @Test public void partialUploadTimeoutKeepsProgressAndBlocksResubmission()throws Exception{
        Fake fake=new Fake();fake.failUploadAt=2;JSONObject state=state();fails(fake,state);
        assertEquals("finish_unknown",fake.saved.getString("status"));assertEquals(50,fake.saved.getInt("uploaded_points"));assertEquals(Arrays.asList(50),fake.batches);
        int count=fake.requests.size();fails(fake,state);assertEquals(count,fake.requests.size());
    }
    @Test public void finishedRecordsCannotBeSubmittedTwice()throws Exception{
        Fake fake=new Fake();JSONObject state=state();new RecordSubmission(fake).submit(state,payload(),path(),1900000);
        int count=fake.requests.size();fails(fake,state);assertEquals(count,fake.requests.size());
    }
    @Test public void legacyInvalidRecordIsSubmittedWithoutAnotherPrompt()throws Exception{
        Fake fake=new Fake();fake.verdict=0;new RecordSubmission(fake).submit(state().put("status","check_failed"),payload(),path(),1900000);assertEquals("finished",fake.saved.getString("status"));
    }
    @Test public void submissionRetainsLocalMillisecondsWhileKeepingServerWholeSecondFields()throws Exception{
        Fake fake=new Fake();JSONObject state=state().put("duration_ms",822581);long end=1000000+822581;
        RunClock.requireFinishable(state,end);JSONObject payload=payload().put("running_time",822);
        new RecordSubmission(fake).submit(state,payload,path(),end);
        assertEquals(822581,fake.saved.getLong("duration_ms"));assertEquals(end,fake.saved.getLong("end_ms"));
        assertEquals(822,fake.saved.getInt("actual_duration"));assertEquals(822581,fake.saved.getLong("active_duration_ms"));
        assertEquals(822581,fake.saved.getLong("actual_duration_ms"));
    }
}

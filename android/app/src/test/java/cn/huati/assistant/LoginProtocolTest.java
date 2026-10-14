package cn.huati.assistant;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LoginProtocolTest {
    private static final String PHONE="13800000000";
    private JSONObject success(String session)throws Exception{
        return new JSONObject().put("code",1).put("data",new JSONObject().put("id",12345).put("session_keys",session));
    }
    @Test public void codeLoginUsesOfficialFieldsAndExportsOnlySession()throws Exception{
        List<String> paths=new ArrayList<>();List<JSONObject> bodies=new ArrayList<>();
        LoginProtocol protocol=new LoginProtocol((path,body)->{paths.add(path);bodies.add(body);return success("fake-test-session");},()->1000);
        JSONObject config=protocol.login(PHONE," 123456 ",true);
        assertEquals(LoginProtocol.CODE_PATH,paths.get(0));assertEquals(PHONE,bodies.get(0).getString("iphone"));assertEquals("123456",bodies.get(0).getString("code"));
        assertEquals("fake-test-session",config.getJSONObject("headers").getString("X-Session-ID"));
        String exported=LoginProtocol.exportConfig(config,null,1000);assertFalse(exported.contains(PHONE));assertFalse(exported.contains("123456"));assertFalse(exported.contains("password"));
        assertEquals("run.ecust.edu.cn",new JSONObject(exported).getString("host"));
    }
    @Test public void passwordLoginPreservesPasswordWithoutSavingIt()throws Exception{
        LoginProtocol protocol=new LoginProtocol((path,body)->{
            assertEquals(LoginProtocol.PASSWORD_PATH,path);assertEquals(" Abc!123 ",body.getString("password"));assertFalse(body.has("code"));return success("fake-password-session");
        },()->1000);
        JSONObject config=protocol.login(PHONE," Abc!123 ",false);assertFalse(config.toString().contains("Abc!123"));
    }
    @Test public void smsCooldownBlocksSendingAndAcceptsResponseWithoutData()throws Exception{
        AtomicLong now=new AtomicLong(1000);List<String> requests=new ArrayList<>();
        LoginProtocol protocol=new LoginProtocol((path,body)->{requests.add(path);return new JSONObject().put("code",1);},now::get);
        long next=protocol.sendCode(PHONE,0);assertEquals(61000,next);
        try{protocol.sendCode(PHONE,next);fail("Should not resend");}catch(IllegalStateException expected){}
        assertEquals(1,requests.size());assertEquals(LoginProtocol.SMS_PATH,requests.get(0));
        now.set(next);protocol.sendCode(PHONE,next);assertEquals(2,requests.size());
    }
    @Test public void rejectsInvalidInputBeforeNetwork()throws Exception{
        LoginProtocol protocol=new LoginProtocol((path,body)->{fail("Invalid input must not reach server");return null;},()->1000);
        for(String phone:new String[]{"", "123", "13800000000\r\nX-Test: bad"}){
            try{protocol.login(phone,"123456",true);fail();}catch(IllegalArgumentException expected){}
        }
        try{protocol.login(PHONE,"abcdef",true);fail();}catch(IllegalArgumentException expected){}
        try{protocol.login(PHONE,"",false);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void refusesMissingOrPlaceholderLoginSession()throws Exception{
        for(JSONObject response:new JSONObject[]{success("1111"),success(""),new JSONObject().put("code",1).put("data",new JSONObject().put("id",1))}){
            try{LoginProtocol.sessionConfig(response,0);fail();}catch(IllegalArgumentException|IllegalStateException expected){}
        }
    }
    @Test public void surfacesServerLoginFailure()throws Exception{
        JSONObject response=new JSONObject().put("code",0).put("message","验证码已过期");
        try{LoginProtocol.sessionConfig(response,0);fail();}catch(IllegalStateException expected){assertEquals("验证码已过期",expected.getMessage());}
    }
    @Test public void unresolvedRunCannotSwitchAccount()throws Exception{
        JSONObject pending=new JSONObject().put("status","created").put("student_id","12345");
        JSONObject verified=new JSONObject().put("id",67890);
        try{LoginProtocol.checkAccount(new JSONObject(),verified,pending);fail();}catch(IllegalStateException expected){}
        assertEquals("12345",LoginProtocol.checkAccount(new JSONObject().put("student_id","12345"),new JSONObject().put("id",12345),pending));
        try{LoginProtocol.checkAccount(new JSONObject().put("student_id","99999"),new JSONObject().put("id",12345),pending);fail();}catch(IllegalStateException expected){}
    }
    @Test public void anonymousTransportCannotCallRunningEndpoints(){
        try{LoginProtocol.requireLoginPath("/xcxapi/createLine/");fail();}catch(IllegalArgumentException expected){}
        try{LoginProtocol.requireLoginPath("https://example.com/login");fail();}catch(IllegalArgumentException expected){}
        LoginProtocol.requireLoginPath(LoginProtocol.PASSWORD_PATH);
    }
    @Test public void exportPreservesLocationAndLatestFinishedRecord()throws Exception{
        JSONObject config=LoginProtocol.sessionConfig(success("test-session"),0).put("location",new JSONObject().put("lat",30.8).put("lng",121.5)).put("recent_record_id",9);
        JSONObject finished=new JSONObject().put("status","finished").put("record_id",10);
        JSONObject exported=new JSONObject(LoginProtocol.exportConfig(config,finished,0));assertEquals(10,exported.getLong("recent_record_id"));assertEquals(30.8,exported.getJSONObject("location").getDouble("lat"),0);
        assertEquals(9,new JSONObject(LoginProtocol.exportConfig(config,new JSONObject().put("status","created").put("record_id",11),0)).getLong("recent_record_id"));
    }
}

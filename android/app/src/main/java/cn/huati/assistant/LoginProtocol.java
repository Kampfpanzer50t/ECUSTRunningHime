package cn.huati.assistant;

import org.json.JSONObject;
import java.util.function.LongSupplier;

/** Official phone login protocol; passwords and codes are never persisted. */
final class LoginProtocol {
    static final String SMS_PATH="/xcxapi/sendSMS/";
    static final String CODE_PATH="/xcxapi/login/";
    static final String PASSWORD_PATH="/xcxapi/userLogin/";
    interface Transport {JSONObject request(String path,JSONObject body) throws Exception;}
    private final Transport transport;
    private final LongSupplier clock;
    LoginProtocol(Transport transport,LongSupplier clock){this.transport=transport;this.clock=clock;}
    static void requireLoginPath(String path) {
        if(!path.equals(SMS_PATH)&&!path.equals(CODE_PATH)&&!path.equals(PASSWORD_PATH))
            throw new IllegalArgumentException("该路径不支持手机登录");
    }
    static String phone(String value) {
        String normalized=value==null?"":value.trim();
        if(!normalized.matches("1[3-9][0-9]{9}"))throw new IllegalArgumentException("请输入小程序绑定的 11 位手机号");
        return normalized;
    }
    static JSONObject passwordBody(String phone,String password) throws Exception {
        if(password==null||password.isEmpty()||password.length()>256)throw new IllegalArgumentException("请输入华体账号密码");
        return new JSONObject().put("iphone",phone(phone)).put("password",password);
    }
    static JSONObject codeBody(String phone,String code) throws Exception {
        String normalized=code==null?"":code.trim();
        if(!normalized.matches("[0-9]{4,8}"))throw new IllegalArgumentException("请输入短信中的数字验证码");
        return new JSONObject().put("iphone",phone(phone)).put("code",normalized);
    }
    long sendCode(String phone,long nextAllowed) throws Exception {
        String number=phone(phone);long now=clock.getAsLong();
        if(nextAllowed>now)throw new IllegalStateException("请等待 "+((nextAllowed-now+999)/1000)+" 秒后再获取验证码");
        JSONObject sent=transport.request(SMS_PATH,new JSONObject().put("iphone",number));
        if(sent.optInt("code",-1)!=1)ApiClient.success(sent);
        return clock.getAsLong()+60000;
    }
    JSONObject login(String phone,String secret,boolean byCode) throws Exception {
        JSONObject result=transport.request(byCode?CODE_PATH:PASSWORD_PATH,byCode?codeBody(phone,secret):passwordBody(phone,secret));
        return sessionConfig(result,clock.getAsLong());
    }
    static JSONObject sessionConfig(JSONObject response,long timestamp) throws Exception {
        Object value=ApiClient.success(response);
        if(!(value instanceof JSONObject))throw new IllegalStateException("登录响应格式已变化，请使用会话导入");
        JSONObject data=(JSONObject)value;
        Object session=data.opt("session_keys");
        if(!(session instanceof String))throw new IllegalStateException("服务器未返回登录会话，请使用会话导入");
        JSONObject headers=ApiClient.cleanHeaders(new JSONObject().put("X-Session-ID",session));
        JSONObject config=new JSONObject().put("format","huati-mobile-session").put("version",1)
            .put("host",ApiClient.HOST).put("headers",headers).put("exported_at",RunMath.date(timestamp)).put("login_source","phone");
        String id=data.optString("id","");if(!id.isEmpty()&&!id.equals("null"))config.put("student_id",id);
        return config;
    }
    static String checkAccount(JSONObject candidate,JSONObject verified,JSONObject pending) throws Exception {
        String id=verified.optString("id","");
        if(id.isEmpty()||id.equals("null"))throw new IllegalStateException("服务器未返回账号信息，请稍后再试");
        if(candidate.has("student_id")&&!candidate.getString("student_id").equals(id))throw new IllegalStateException("登录响应与查询账号不一致，未保存会话");
        if(RunEngine.unresolved(pending)&&!pending.optString("student_id").equals(id))throw new IllegalStateException("还有另一账号的待处理记录，请先处理该记录再更换账号");
        return id;
    }
    static String exportConfig(JSONObject config,JSONObject run,long now) throws Exception {
        if(config==null)throw new IllegalStateException("请先登录或导入会话");
        JSONObject out=new JSONObject().put("format","huati-mobile-session").put("version",1).put("host",ApiClient.HOST)
            .put("headers",ApiClient.cleanHeaders(config.getJSONObject("headers"))).put("exported_at",RunMath.date(now));
        if(config.has("location"))out.put("location",config.getJSONObject("location"));
        if(run!=null&&run.optString("status").equals("finished")&&run.optLong("record_id",0)>0)out.put("recent_record_id",run.getLong("record_id"));
        else if(config.optLong("recent_record_id",0)>0)out.put("recent_record_id",config.getLong("recent_record_id"));
        return out.toString(2);
    }
}

package cn.huati.assistant;

import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

final class ApiClient {
    static final String HOST="run.ecust.edu.cn";
    static final class SessionException extends IllegalStateException {
        SessionException(String message){super(message);}
    }
    private final JSONObject headers;
    ApiClient(JSONObject config) throws Exception {
        if(config==null || !HOST.equals(config.optString("host"))) throw new IllegalArgumentException("请先导入有效的会话文件");
        headers=cleanHeaders(config.getJSONObject("headers"));
    }
    static JSONObject cleanHeaders(JSONObject source) throws Exception {
        JSONObject out=new JSONObject(); boolean valid=false;
        for(Iterator<String> i=source.keys();i.hasNext();) {
            String k=i.next(), lower=k.toLowerCase(Locale.ROOT),v=source.optString(k,"");
            if(!lower.equals("x-session-id")&&!lower.equals("authorization")&&!lower.equals("user-agent")&&!lower.equals("referer")) continue;
            if(v.indexOf('\r')>=0 || v.indexOf('\n')>=0 || v.length()>8192) throw new IllegalArgumentException("会话文件格式无效");
            if(lower.equals("x-session-id")) {if(v.isEmpty()||v.equals("1111")) throw new IllegalArgumentException("会话文件缺少有效登录信息");valid=true;}
            if(!v.isEmpty()) out.put(k,v);
        }
        if(!valid) throw new IllegalArgumentException("会话文件缺少有效登录信息");
        return out;
    }
    static String query(String value) {
        try {return URLEncoder.encode(value,"UTF-8");}
        catch(java.io.UnsupportedEncodingException e){throw new AssertionError(e);}
    }
    JSONObject request(String path) throws Exception {return request(path,null);}
    JSONObject request(String path,JSONObject data) throws Exception {
        if(!path.startsWith("/xcxapi/")&&!path.startsWith("/xcxtapi/")) throw new IllegalArgumentException("接口路径无效");
        return exchange(path,data,headers);
    }
    static JSONObject loginRequest(String path,JSONObject data) throws Exception {
        LoginProtocol.requireLoginPath(path);
        return exchange(path,data,new JSONObject().put("X-Session-ID","1111"));
    }
    private static JSONObject exchange(String path,JSONObject data,JSONObject headers) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://"+HOST+path).openConnection();
        connection.setConnectTimeout(20000);connection.setReadTimeout(25000);connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);connection.setRequestProperty("Cache-Control","no-cache, no-store");
        connection.setRequestProperty("Pragma","no-cache");
        connection.setRequestMethod(data==null?"GET":"POST");connection.setRequestProperty("Content-Type","application/json");
        connection.setRequestProperty("Accept-Encoding","identity");
        for(Iterator<String> i=headers.keys();i.hasNext();) {String k=i.next();connection.setRequestProperty(k,headers.getString(k));}
        try {
            if(data!=null) {
                byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
                try(var out=connection.getOutputStream()){out.write(bytes);}
            }
            int code=connection.getResponseCode();
            if(code==401||code==403) throw new SessionException("登录未通过或已失效，请重新登录，也可以导入会话");
            if(code<200||code>=300) throw new IllegalStateException("服务器返回 "+code+"，请先查询记录状态");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1){if(bytes.size()+count>2*1024*1024)throw new IllegalStateException("响应过大");bytes.write(buffer,0,count);}
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally {connection.disconnect();}
    }
    static Object success(JSONObject result) throws Exception {
        if(result.optInt("code",-1)==-2)throw new SessionException("登录已失效，请在手机上重新登录或导入新会话");
        if(result.optInt("code",-1)!=1) throw new IllegalStateException(result.optString("message",result.optString("msg","请求未成功，请检查登录会话")));
        return result.get("data");
    }
}

package cn.huati.assistant;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;

public class RunMathTest {
    private JSONArray points() throws Exception {
        return new JSONArray("[{\"lat\":\"30.827536\",\"lng\":\"121.503933\",\"point_name\":\"38\"},{\"lat\":\"30.832999\",\"lng\":\"121.509337\",\"point_name\":\"单双杠练习场\"},{\"lat\":\"30.830954\",\"lng\":\"121.50272\",\"point_name\":\"信息楼南\"}]");
    }
    @Test public void pathVisitsThreeStopsAndMatchesDistanceAndDuration() throws Exception {
        JSONArray p=points(),path=RunMath.path(p,30.83103,121.503709,1000000,2200,900);
        double length=0;
        for(int i=1;i<path.length();i++) {
            JSONObject a=path.getJSONObject(i-1),b=path.getJSONObject(i);
            length+=RunMath.distance(a.getDouble("lat"),a.getDouble("lng"),b.getDouble("lat"),b.getDouble("lng"));
            assertTrue(b.getLong("timestamp")>a.getLong("timestamp"));
        }
        assertEquals(2200,length,0.5);
        assertEquals(900000,path.getJSONObject(path.length()-1).getLong("timestamp")-path.getJSONObject(0).getLong("timestamp"));
        for(int i=0;i<3;i++) {
            double min=Double.POSITIVE_INFINITY;JSONObject stop=p.getJSONObject(i);
            for(int j=0;j<path.length();j++){JSONObject q=path.getJSONObject(j);min=Math.min(min,RunMath.distance(q.getDouble("lat"),q.getDouble("lng"),stop.getDouble("lat"),stop.getDouble("lng")));}
            assertTrue(min<0.1);
        }
        assertEquals(277,path.length());
    }
    @Test public void cryptoWireFormatRoundTripsUnicodeAndFreshIv() throws Exception {
        JSONObject payload=new JSONObject().put("mileage",2200).put("text","校园跑");
        String first=RunMath.encrypt(payload);assertNotEquals(first,RunMath.encrypt(payload));
        String[] parts=first.split(":");assertEquals(2,parts.length);byte[] iv=Base64.getDecoder().decode(parts[0]);assertEquals(16,iv.length);
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec("A7fK92LmXyQw8vRp".getBytes(StandardCharsets.US_ASCII),"AES"),new IvParameterSpec(iv));
        JSONObject decoded=new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8));
        assertEquals(2200,decoded.getInt("mileage"));assertEquals("校园跑",decoded.getString("text"));
    }
    @Test(expected=IllegalArgumentException.class) public void refusesInsufficientRouteLength() throws Exception {
        JSONArray p=new JSONArray("[{\"lat\":0,\"lng\":1},{\"lat\":1,\"lng\":0},{\"lat\":1,\"lng\":1}]");RunMath.path(p,0,0,0,2200,900);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateStops() throws Exception {
        JSONArray p=points();p.put(2,p.getJSONObject(0));RunMath.path(p,30.83103,121.503709,0,2200,900);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsNonFiniteCoordinate(){RunMath.coordinate(Double.NaN,120);}
    @Test public void datesUseShanghaiZone(){assertEquals("1970-01-01 08:00:00",RunMath.date(0));}
    @Test public void requiresSessionAndDropsUnrelatedHeaders() throws Exception {
        JSONObject h=ApiClient.cleanHeaders(new JSONObject().put("X-Session-ID","test-only").put("Cookie","do-not-forward").put("Authorization",""));
        assertEquals("test-only",h.getString("X-Session-ID"));assertFalse(h.has("Cookie"));assertFalse(h.has("Authorization"));
    }
    @Test(expected=IllegalArgumentException.class) public void blocksNewlineHeaderInjection() throws Exception {
        ApiClient.cleanHeaders(new JSONObject().put("X-Session-ID","test\r\nCookie: leak"));
    }
}

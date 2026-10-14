package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class RunMath {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter PRECISE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZONE);
    static String date(long millis) { return FORMAT.format(Instant.ofEpochMilli(millis)); }
    static String preciseDate(long millis){return PRECISE_FORMAT.format(Instant.ofEpochMilli(millis));}

    static void coordinate(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat)>90 || Math.abs(lng)>180)
            throw new IllegalArgumentException("起点坐标无效");
    }
    static double distance(double aLat, double aLng, double bLat, double bLng) {
        double dl=Math.toRadians(bLat-aLat), dg=Math.toRadians(bLng-aLng);
        double h=Math.pow(Math.sin(dl/2),2)+Math.cos(Math.toRadians(aLat))*Math.cos(Math.toRadians(bLat))*Math.pow(Math.sin(dg/2),2);
        return 6371000*2*Math.asin(Math.min(1,Math.sqrt(h)));
    }
    static void parameters(int metres,int seconds,int steps) {
        if(metres<2000||metres>30000||seconds<60||seconds>7200||steps<=0||steps>100000)
            throw new IllegalArgumentException("请检查里程、时长和步数；里程 2–30 公里，时长 1–120 分钟");
    }
    static int automaticMetres(JSONArray points,double lat,double lng,int requested)throws Exception {
        if(points.length()!=3)throw new IllegalArgumentException("需要三个考核点");
        coordinate(lat,lng);double mandatory=0;
        for(int i=0;i<3;i++){
            JSONObject p=points.getJSONObject(i);double nextLat=p.getDouble("lat"),nextLng=p.getDouble("lng");coordinate(nextLat,nextLng);
            mandatory+=distance(lat,lng,nextLat,nextLng);lat=nextLat;lng=nextLng;
        }
        return Math.max(Math.max(2100,requested),(int)Math.ceil(mandatory)+2);
    }
    static JSONArray path(JSONArray points, double lat, double lng, long start, int metres, int seconds) throws Exception {
        coordinate(lat,lng);
        if (points.length()!=3 || metres<2000 || seconds<=0) throw new IllegalArgumentException("需要三个点位、至少 2 公里和正数时长");
        double[][] stops=new double[5][2]; stops[0]=new double[]{lat,lng}; stops[4]=stops[0];
        for(int i=0;i<3;i++) {
            JSONObject p=points.getJSONObject(i); stops[i+1]=new double[]{p.getDouble("lat"),p.getDouble("lng")};
            coordinate(stops[i+1][0],stops[i+1][1]);
            for(int j=1;j<=i;j++) if(stops[j][0]==stops[i+1][0] && stops[j][1]==stops[i+1][1])
                throw new IllegalArgumentException("三个点位坐标必须不同");
        }
        JSONArray out=new JSONArray(); out.put(point(lat,lng,start));
        double travelled=0; double[] current=stops[0]; int index=1;
        while(travelled<metres) {
            double[] target=stops[index]; double segment=distance(current[0],current[1],target[0],target[1]);
            if(segment==0) {index=index%4+1;continue;}
            double use=Math.min(segment,metres-travelled); int count=Math.max(1,(int)Math.ceil(use/8));
            for(int k=1;k<=count;k++) {
                double part=use*k/count, ratio=part/segment;
                out.put(point(current[0]+(target[0]-current[0])*ratio,current[1]+(target[1]-current[1])*ratio,
                    start+(long)(seconds*1000.0*(travelled+part)/metres)));
            }
            travelled+=use;current=target;index=index%4+1;
        }
        for(int i=1;i<=3;i++) {
            double nearest=Double.POSITIVE_INFINITY;
            for(int j=0;j<out.length();j++) {JSONObject p=out.getJSONObject(j);nearest=Math.min(nearest,distance(p.getDouble("lat"),p.getDouble("lng"),stops[i][0],stops[i][1]));}
            if(nearest>20) throw new IllegalArgumentException("指定里程不足以经过本次三个点位，请增加里程");
        }
        return out;
    }
    private static JSONObject point(double lat,double lng,long timestamp) throws Exception {
        return new JSONObject().put("name","").put("lat",lat).put("lng",lng).put("timestamp",timestamp).put("accuracy",5);
    }
    static String encrypt(JSONObject value) throws Exception {
        byte[] iv=new byte[16];new SecureRandom().nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec("A7fK92LmXyQw8vRp".getBytes(StandardCharsets.US_ASCII),"AES"),new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
}

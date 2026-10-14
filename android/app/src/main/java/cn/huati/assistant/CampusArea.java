package cn.huati.assistant;

import java.util.Random;
import java.util.concurrent.Callable;
import org.json.JSONObject;
import org.json.JSONArray;

final class CampusArea {
    // OSM way 304214471, WGS84, retrieved 2026-10-12. See android/campus-data/fengxian-boundary.json.
    private static final double[][] BOUNDARY={
        {30.833463,121.4919501},{30.8335415,121.4920781},{30.8336969,121.4923639},
        {30.8341438,121.4922949},{30.8342605,121.4923117},{30.8343413,121.4923458},
        {30.8344155,121.4923854},{30.8384256,121.5001315},{30.8391466,121.5015652},
        {30.8329235,121.5073882},{30.8321253,121.5060542},{30.8276723,121.4976667},
        {30.8278105,121.4971228},{30.8314375,121.4937366}
    };
    private static volatile JSONObject roadGraph;
    static void roads(JSONObject graph){roadGraph=graph;}
    static boolean containsWgs(double lat,double lng){
        if(!Double.isFinite(lat)||!Double.isFinite(lng))return false;
        boolean inside=false;
        for(int i=0,j=BOUNDARY.length-1;i<BOUNDARY.length;j=i++){
            double y1=BOUNDARY[i][0],x1=BOUNDARY[i][1],y2=BOUNDARY[j][0],x2=BOUNDARY[j][1];
            double cross=(lng-x1)*(y2-y1)-(lat-y1)*(x2-x1);
            if(Math.abs(cross)<1e-12&&lng>=Math.min(x1,x2)&&lng<=Math.max(x1,x2)&&lat>=Math.min(y1,y2)&&lat<=Math.max(y1,y2))return true;
            if((y1>lat)!=(y2>lat)&&lng<(x2-x1)*(lat-y1)/(y2-y1)+x1)inside=!inside;
        }
        return inside;
    }
    static JSONObject randomOrigin(Random random,String reason)throws Exception{
        JSONObject graph=roadGraph;JSONArray nodes=graph==null?null:graph.optJSONArray("nodes"),edges=graph==null?null:graph.optJSONArray("edges");
        if(nodes!=null&&edges!=null&&edges.length()>0)for(int i=0;i<256;i++){
            JSONArray edge=edges.getJSONArray(random.nextInt(edges.length()));JSONArray a=nodes.getJSONArray(edge.getInt(0)),b=nodes.getJSONArray(edge.getInt(1));double ratio=random.nextDouble();
            double lat=a.getDouble(0)+(b.getDouble(0)-a.getDouble(0))*ratio,lng=a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*ratio;
            if(containsWgs(lat,lng))return fallback(lat,lng,reason,"校园道路随机点位");
        }
        double minLat=90,maxLat=-90,minLng=180,maxLng=-180;
        for(double[] p:BOUNDARY){minLat=Math.min(minLat,p[0]);maxLat=Math.max(maxLat,p[0]);minLng=Math.min(minLng,p[1]);maxLng=Math.max(maxLng,p[1]);}
        for(int i=0;i<2048;i++){double lat=minLat+random.nextDouble()*(maxLat-minLat),lng=minLng+random.nextDouble()*(maxLng-minLng);if(containsWgs(lat,lng))return fallback(lat,lng,reason,"校内随机点位");}
        throw new IllegalStateException("无法选择有效校内起点");
    }
    private static JSONObject fallback(double lat,double lng,String reason,String name)throws Exception{
        double[] point=MapCoordinates.gcj(lat,lng);return new JSONObject().put("lat",point[0]).put("lng",point[1]).put("source","校内随机模拟起点（"+reason+"）").put("origin_name",name);
    }
    static boolean validGcj(double lat,double lng){
        if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180)return false;
        double[] wgs=MapCoordinates.wgs(lat,lng);return containsWgs(wgs[0],wgs[1]);
    }
    static JSONObject safeGcj(double lat,double lng,Random random)throws Exception{
        if(validGcj(lat,lng))return new JSONObject().put("lat",lat).put("lng",lng).put("source","校内起点");
        return randomOrigin(random,"起点无效或在校外");
    }
    static JSONObject choose(Callable<JSONObject> location,Random random)throws Exception{
        JSONObject fix;
        try{fix=location.call();RunMath.coordinate(fix.getDouble("lat"),fix.getDouble("lng"));}
        catch(Exception error){return randomOrigin(random,"定位失败");}
        if(!containsWgs(fix.getDouble("lat"),fix.getDouble("lng")))return randomOrigin(random,"当前位置在校外");
        double[] mapped=MapCoordinates.gcj(fix.getDouble("lat"),fix.getDouble("lng"));
        return new JSONObject().put("lat",mapped[0]).put("lng",mapped[1]).put("source","手机当前位置（奉贤校区内）");
    }
}

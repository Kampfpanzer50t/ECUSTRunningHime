package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

/** A point directory is separate from the three points used to create a running record. */
final class CampusPoints {
    static final String CAMPUS="华东理工大学奉贤校区";
    static JSONObject reference()throws Exception{
        return new JSONObject().put("campus",CAMPUS).put("coordinate_system","GCJ02").put("complete",false)
            .put("source","公开项目列出的 3 个已知点位")
            .put("points",new JSONArray("[{\"point_name\":\"单双杠练习场\",\"lat\":30.832999,\"lng\":121.509337},{\"point_name\":\"考核点 38\",\"lat\":30.827536,\"lng\":121.503933},{\"point_name\":\"体育馆主馆\",\"lat\":30.829844,\"lng\":121.507996}]"));
    }
    static JSONObject importTable(String text,long now)throws Exception{
        JSONObject data=new JSONObject(text);
        if(!CAMPUS.equals(data.getString("campus")))throw new IllegalArgumentException("点位表需属于华东理工大学奉贤校区");
        String system=data.getString("coordinate_system");
        if(!system.equals("GCJ02")&&!system.equals("WGS84"))throw new IllegalArgumentException("点位表坐标系需为 GCJ02 或 WGS84");
        JSONArray input=data.getJSONArray("points");
        if(input.length()==0||input.length()>600)throw new IllegalArgumentException("点位表需包含 1–600 个跑步点位");
        JSONArray points=new JSONArray();
        for(int i=0;i<input.length();i++)add(points,normal(input.getJSONObject(i),system));
        String source=data.optString("source","用户导入的点位表").trim();
        if(source.isEmpty()||source.length()>200)throw new IllegalArgumentException("点位表来源需为 1–200 字");
        return new JSONObject().put("campus",CAMPUS).put("coordinate_system","GCJ02").put("complete",data.optBoolean("complete",false))
            .put("imported",true).put("source",source).put("updated_at",now).put("points",points);
    }
    static JSONObject merge(JSONObject previous,JSONArray input,long now)throws Exception{
        JSONObject result=new JSONObject((previous==null?reference():previous).toString());JSONArray points=result.getJSONArray("points");
        boolean added=false;
        for(int i=0;i<input.length();i++)added|=add(points,normal(input.getJSONObject(i),"GCJ02"));
        if(points.length()>600)throw new IllegalArgumentException("已收集的点位过多，请重新导入点位表");
        if(added&&result.optBoolean("complete"))result.put("complete",false).put("completeness_changed",true);
        if(!result.optBoolean("imported"))result.put("source","公开参考与服务器历次返回的点位");
        return result.put("updated_at",now);
    }
    private static JSONObject normal(JSONObject point,String system)throws Exception{
        double lat=point.getDouble("lat"),lng=point.getDouble("lng");RunMath.coordinate(lat,lng);
        double[] wgs=system.equals("WGS84")?new double[]{lat,lng}:MapCoordinates.wgs(lat,lng);
        if(!CampusArea.containsWgs(wgs[0],wgs[1]))throw new IllegalArgumentException("点位不在已配置的奉贤校区范围内");
        if(system.equals("WGS84")){double[] mapped=MapCoordinates.gcj(lat,lng);lat=mapped[0];lng=mapped[1];}
        String name=point.optString("point_name",point.optString("name","考核点")).trim();
        if(name.isEmpty()||name.length()>80)throw new IllegalArgumentException("点位名称需为 1–80 字");
        JSONObject out=new JSONObject().put("point_name",name).put("lat",lat).put("lng",lng);
        String id=point.optString("id","");if(!id.isEmpty()&&id.length()<=80)out.put("id",id);return out;
    }
    private static boolean add(JSONArray points,JSONObject next)throws Exception{
        for(int i=0;i<points.length();i++){
            JSONObject old=points.getJSONObject(i);
            boolean sameId=next.has("id")&&next.optString("id").equals(old.optString("id"));
            boolean nearby=RunMath.distance(old.getDouble("lat"),old.getDouble("lng"),next.getDouble("lat"),next.getDouble("lng"))<10;
            boolean compatibleIds=!old.has("id")||!next.has("id")||sameId;
            if(sameId||nearby&&compatibleIds){if(!next.has("id")&&old.has("id"))next.put("id",old.getString("id"));points.put(i,next);return false;}
        }
        points.put(next);return true;
    }
}

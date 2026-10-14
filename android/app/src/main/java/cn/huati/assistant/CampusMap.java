package cn.huati.assistant;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read once; route coordinates and map geometry are both GCJ-02 at rendering time. */
final class CampusMap {
    static final class Area {
        final String kind;final double[][][] rings;
        Area(String kind,double[][][] rings){this.kind=kind;this.rings=rings;}
    }
    static final class Road {
        final int width;final double[][] points;
        Road(int width,double[][] points){this.width=width;this.points=points;}
    }
    static final class Label {
        final String name;final double[] point;
        Label(String name,double[] point){this.name=name;this.point=point;}
    }
    final double[][] boundary;
    final ArrayList<Area> areas=new ArrayList<>();
    final ArrayList<Road> roads=new ArrayList<>();
    final ArrayList<Label> labels=new ArrayList<>();
    private static CampusMap cached;
    private static boolean attempted;
    static synchronized CampusMap load(Context context){
        if(attempted)return cached;attempted=true;
        try(var input=context.getAssets().open("campus-map.json")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);
            cached=new CampusMap(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
        }catch(Exception ignored){}return cached;
    }
    CampusMap(JSONObject data)throws Exception{
        if(!data.getString("coordinate_system").equals("WGS84"))throw new IllegalArgumentException("地图坐标系无效");
        boundary=coordinates(data.getJSONArray("boundary"));
        JSONArray polygons=data.getJSONArray("areas");
        for(int i=0;i<polygons.length();i++){
            JSONObject area=polygons.getJSONObject(i);JSONArray rings=area.getJSONArray("rings");double[][][] points=new double[rings.length()][][];
            for(int j=0;j<rings.length();j++)points[j]=coordinates(rings.getJSONArray(j));
            areas.add(new Area(area.getString("kind"),points));
        }
        JSONArray lines=data.getJSONArray("roads");
        for(int i=0;i<lines.length();i++){JSONObject road=lines.getJSONObject(i);roads.add(new Road(road.getInt("width"),coordinates(road.getJSONArray("points"))));}
        JSONArray names=data.getJSONArray("labels");
        for(int i=0;i<names.length();i++){JSONObject label=names.getJSONObject(i);JSONArray p=label.getJSONArray("point");labels.add(new Label(label.getString("name"),MapCoordinates.gcj(p.getDouble(0),p.getDouble(1))));}
    }
    private static double[][] coordinates(JSONArray input)throws Exception{
        double[][] out=new double[input.length()][2];for(int i=0;i<out.length;i++){
            JSONArray p=input.getJSONArray(i);out[i]=MapCoordinates.gcj(p.getDouble(0),p.getDouble(1));
        }return out;
    }
}

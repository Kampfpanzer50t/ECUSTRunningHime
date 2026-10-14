package cn.huati.assistant;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Random;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;

/** Desktop QA of public map geometry and coordinate alignment; not an Android screenshot. */
public final class MapPreview {
    private final int W=286,H;
    private MapPreview(){this(250);}
    private MapPreview(int height){H=height;}
    private final MapViewport viewport=new MapViewport();
    private double minLng,maxLat,cosine,scale,left,top;
    private double x(double lng){return viewport.x((float)(left+(lng-minLng)*cosine*scale));}
    private double y(double lat){return viewport.y((float)(top+(maxLat-lat)*scale));}
    private Path2D line(double[][] points,boolean close){
        Path2D out=new Path2D.Double(Path2D.WIND_EVEN_ODD);if(points.length==0)return out;
        out.moveTo(x(points[0][1]),y(points[0][0]));for(int i=1;i<points.length;i++)out.lineTo(x(points[i][1]),y(points[i][0]));if(close)out.closePath();return out;
    }
    private BufferedImage render(CampusMap map,JSONArray route,JSONArray stops,float zoom)throws Exception{
        viewport.resize(W,H);viewport.scaleBy(zoom,W/2f,H/2f);
        ArrayList<double[]> bounds=new ArrayList<>();
        if(route==null)for(double[] p:map.boundary)bounds.add(p);else for(int i=0;i<route.length();i++){JSONObject p=route.getJSONObject(i);bounds.add(new double[]{p.getDouble("lat"),p.getDouble("lng")});}
        double lowLat=90,highLat=-90,lowLng=180,highLng=-180;
        for(double[] p:bounds){lowLat=Math.min(lowLat,p[0]);highLat=Math.max(highLat,p[0]);lowLng=Math.min(lowLng,p[1]);highLng=Math.max(highLng,p[1]);}
        double latSpan=Math.max(0.0055,(highLat-lowLat)*1.20),lngSpan=Math.max(0.0055,(highLng-lowLng)*1.20),centreLat=(lowLat+highLat)/2;
        maxLat=centreLat+latSpan/2;minLng=(lowLng+highLng-lngSpan)/2;cosine=Math.cos(Math.toRadians(centreLat));scale=Math.min((W-28)/(lngSpan*cosine),(H-65)/latSpan);left=(W-lngSpan*cosine*scale)/2;top=31+(H-65-latSpan*scale)/2;
        BufferedImage image=new BufferedImage(W*3,H*3,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.scale(3,3);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setClip(new RoundRectangle2D.Double(0,0,W,H,36,36));
        g.setColor(new Color(246,247,240));g.fillRect(0,0,W,H);g.setColor(new Color(237,243,232));g.fill(line(map.boundary,true));
        String[] layers={"green","water","sports","building"};int[][] fills={{216,233,206},{199,226,235},{216,225,188},{221,221,207}},outlines={{197,218,186},{171,208,219},{183,199,153},{188,196,178}};
        for(int i=0;i<layers.length;i++)for(CampusMap.Area area:map.areas){
            if(!area.kind.equals(layers[i]))continue;Path2D polygon=new Path2D.Double(Path2D.WIND_EVEN_ODD);for(double[][] ring:area.rings)polygon.append(line(ring,true),false);
            g.setColor(new Color(fills[i][0],fills[i][1],fills[i][2]));g.fill(polygon);g.setStroke(new BasicStroke(.65f));g.setColor(new Color(outlines[i][0],outlines[i][1],outlines[i][2]));g.draw(polygon);
        }
        for(int pass=0;pass<2;pass++)for(CampusMap.Road road:map.roads){g.setColor(pass==0?new Color(205,216,196):new Color(255,254,249));g.setStroke(new BasicStroke(road.width==2?(pass==0?4.5f:3.2f):(pass==0?2.6f:1.5f),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(line(road.points,false));}
        g.setColor(new Color(160,185,146));g.setStroke(new BasicStroke(1,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{5,4},0));g.draw(line(map.boundary,true));
        Font font=Font.createFont(Font.TRUETYPE_FONT,Path.of("android/app/src/main/assets/fonts/campus-sans.ttf").toFile());
        g.setFont(font.deriveFont(10f));ArrayList<Rectangle2D> occupied=new ArrayList<>();
        if(stops!=null)for(int i=0;i<stops.length();i++){JSONObject p=stops.getJSONObject(i);occupied.add(new Rectangle2D.Double(x(p.getDouble("lng"))-15,y(p.getDouble("lat"))-15,30,30));}
        int count=0;for(CampusMap.Label label:map.labels){
            double cx=x(label.point[1]),cy=y(label.point[0]),width=g.getFontMetrics().stringWidth(label.name);Rectangle2D box=new Rectangle2D.Double(cx-width/2-3,cy-7,width+6,14);
            if(box.getMinX()<8||box.getMaxX()>W-8||box.getMinY()<33||box.getMaxY()>H-50)continue;boolean overlap=false;for(Rectangle2D previous:occupied)if(previous.intersects(box)){overlap=true;break;}if(overlap)continue;
            occupied.add(box);g.setColor(new Color(248,250,243));g.fill(box);g.setColor(new Color(89,107,84));g.drawString(label.name,(float)(cx-width/2),(float)(cy+3));if(++count>=(zoom>1.5f?24:12))break;
        }
        if(route!=null){
            double[][] points=new double[route.length()][2];for(int i=0;i<points.length;i++){JSONObject p=route.getJSONObject(i);points[i]=new double[]{p.getDouble("lat"),p.getDouble("lng")};}
            g.setStroke(new BasicStroke(6,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(Color.WHITE);g.draw(line(points,false));g.setStroke(new BasicStroke(3.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(8,127,114));g.draw(line(points,false));
            for(int i=0;i<stops.length();i++){JSONObject p=stops.getJSONObject(i);double cx=x(p.getDouble("lng")),cy=y(p.getDouble("lat"));g.setColor(Color.WHITE);g.fill(new Ellipse2D.Double(cx-12.5,cy-12.5,25,25));g.setColor(new Color(28,46,44));g.fill(new Ellipse2D.Double(cx-10.5,cy-10.5,21,21));g.setFont(new Font("Microsoft YaHei",Font.BOLD,12));g.setColor(Color.WHITE);g.drawString(Integer.toString(i+1),(float)cx-4,(float)cy+4);}
        }
        g.setColor(new Color(255,255,255,236));g.fill(new RoundRectangle2D.Double(10,8,81,21,14,14));g.setFont(font.deriveFont(11f));g.setColor(new Color(74,96,65));g.drawString("奉贤校园地图",17,23);g.drawString("N ↑",W-31,23);
        g.setColor(new Color(250,252,248));g.fillRect(0,H-22,W,22);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,9));g.setColor(new Color(97,116,91));g.drawString("© OpenStreetMap | 模拟路线 · 非实时定位",12,H-8);g.dispose();return image;
    }
    public static void main(String[] args)throws Exception{
        CampusMap map=new CampusMap(new JSONObject(Files.readString(Path.of("android/app/src/main/assets/campus-map.json"))));
        RoadRoute planner=new RoadRoute(new JSONObject(Files.readString(Path.of("android/app/src/main/assets/campus-roads.json"))));
        JSONArray stops=new JSONArray("[{\"lat\":30.827536,\"lng\":121.503933},{\"lat\":30.832999,\"lng\":121.509337},{\"lat\":30.829844,\"lng\":121.507996}]");
        JSONArray route=planner.plan(stops,30.83103,121.503709,2200,false,new Random(8)).getJSONArray("route_samples");
        if(args.length>1&&args[1].equals("zoom")){
            BufferedImage overview=new MapPreview(420).render(map,null,null,1),enlarged=new MapPreview(420).render(map,null,null,3);
            BufferedImage combined=new BufferedImage(overview.getWidth()*2+36,overview.getHeight()+70,BufferedImage.TYPE_INT_RGB);Graphics2D g=combined.createGraphics();g.setColor(new Color(243,246,245));g.fillRect(0,0,combined.getWidth(),combined.getHeight());g.drawImage(overview,0,55,null);g.drawImage(enlarged,overview.getWidth()+36,55,null);g.setColor(new Color(24,43,44));g.setFont(new Font("Microsoft YaHei",Font.PLAIN,23));g.drawString("校园全图 · 1×",12,34);g.drawString("放大 · 3×（桌面渲染，非手机截图）",overview.getWidth()+48,34);g.dispose();ImageIO.write(combined,"png",Path.of(args[0]).toFile());return;
        }
        BufferedImage overview=new MapPreview().render(map,null,null,1),overlay=new MapPreview().render(map,route,stops,1);
        BufferedImage combined=new BufferedImage(overview.getWidth()*2+36,overview.getHeight()+70,BufferedImage.TYPE_INT_RGB);Graphics2D g=combined.createGraphics();g.setColor(new Color(244,247,246));g.fillRect(0,0,combined.getWidth(),combined.getHeight());g.drawImage(overview,0,55,null);g.drawImage(overlay,overview.getWidth()+36,55,null);g.setColor(new Color(60,90,76));g.setFont(new Font("Microsoft YaHei",Font.PLAIN,23));g.drawString("校园全图",12,34);g.drawString("地图与模拟路线对齐预览（非手机截图）",overview.getWidth()+48,34);g.dispose();
        ImageIO.write(combined,"png",Path.of(args[0]).toFile());System.out.println("Public campus map and route preview written: "+args[0]);
    }
}

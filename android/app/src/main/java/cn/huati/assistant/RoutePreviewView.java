package cn.huati.assistant;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline campus map with generated coordinates; this view never reads phone location. */
final class RoutePreviewView extends View {
    private static final int[] SCALE_METRES={100,50,20,10,5};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath=new Path(),donePath=new Path(),mapPath=new Path(),clipPath=new Path();
    private final RectF frame=new RectF(),badge=new RectF(),emptyHint=new RectF();
    private final CampusMap map;
    private final DashPathEffect boundaryDash;
    private final MapViewport viewport=new MapViewport();
    private final ScaleGestureDetector scaleGesture;
    private final GestureDetector tapGesture;
    private final float touchSlop;
    private float downX,downY,lastX,lastY;
    private boolean gestureMoved;
    private Bitmap mapLayer;
    private JSONArray route,stops;
    private float[][] routePixels=new float[0][2],stopPixels=new float[0][2];
    private double[] fractions=new double[0];
    private double minLng,maxLat,cosine,scale;
    private float left,top,progress;
    private String snapshot="";
    private boolean campusMode;
    private boolean[] currentStops=new boolean[0];
    private java.util.function.Consumer<String> pointSelected;

    RoutePreviewView(Context context){
        super(context);map=CampusMap.load(context);
        boundaryDash=new DashPathEffect(new float[]{dp(5),dp(4)},0);
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();setClickable(true);
        scaleGesture=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){gestureMoved=true;holdParent(true);return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){
                if(viewport.scaleBy(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY())){rebuildScene();invalidate();}return true;
            }
        });
        scaleGesture.setQuickScaleEnabled(false);
        tapGesture=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent event){
                if(!gestureMoved)selectPoint(event.getX(),event.getY());return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event){
                gestureMoved=true;
                if(viewport.zoom()>=MapViewport.MAX_ZOOM-0.01f)resetViewport();
                else if(viewport.scaleBy(2,event.getX(),event.getY())){rebuildScene();invalidate();}
                return true;
            }
        });
        setContentDescription("奉贤校园离线地图与模拟路线；双指缩放，放大后拖动，双击放大；编号表示三个考核点，不是实时定位地图");
    }
    void show(JSONArray path,JSONArray points,float fraction){
        String next=(path==null?"":path.toString())+(points==null?"":points.toString());
        float value=Float.isFinite(fraction)?Math.max(0,Math.min(1,fraction)):0;
        boolean changed=!snapshot.equals(next);
        if(!changed&&Math.abs(progress-value)<0.001)return;
        snapshot=next;route=path;stops=points;progress=value;
        if(changed){viewport.reset();rebuildScene();}invalidate();
    }
    void onPointSelected(java.util.function.Consumer<String> listener){pointSelected=listener;}
    void showCampus(JSONArray catalog,JSONArray current)throws Exception{
        campusMode=true;
        setContentDescription("奉贤校园离线地图；双指缩放，放大后拖动，双击放大；绿色标记为本次三点，深色标记为点位目录；点击查看点位名称");
        JSONArray visible=new JSONArray();ArrayList<Boolean> selected=new ArrayList<>();
        if(catalog!=null)for(int i=0;i<catalog.length();i++){visible.put(catalog.getJSONObject(i));selected.add(false);}
        if(current!=null)for(int i=0;i<current.length();i++){
            JSONObject p=current.getJSONObject(i);int found=-1;
            for(int j=0;j<visible.length();j++){
                JSONObject q=visible.getJSONObject(j);
                if(RunMath.distance(p.getDouble("lat"),p.getDouble("lng"),q.getDouble("lat"),q.getDouble("lng"))<10){found=j;break;}
            }
            if(found<0){visible.put(p);selected.add(true);}else selected.set(found,true);
        }
        currentStops=new boolean[selected.size()];for(int i=0;i<selected.size();i++)currentStops[i]=selected.get(i);
        String next=visible.toString()+selected.toString();
        if(snapshot.equals(next))return;snapshot=next;route=null;stops=visible;progress=0;rebuildScene();invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            downX=lastX=event.getX();downY=lastY=event.getY();gestureMoved=false;holdParent(viewport.zoom()>1);
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN){gestureMoved=true;holdParent(true);}
        scaleGesture.onTouchEvent(event);tapGesture.onTouchEvent(event);
        if(action==MotionEvent.ACTION_MOVE&&event.getPointerCount()==1&&!scaleGesture.isInProgress()){
            float currentX=event.getX(),currentY=event.getY();
            if(Math.hypot(currentX-downX,currentY-downY)>touchSlop)gestureMoved=true;
            if(gestureMoved&&viewport.zoom()>1){
                holdParent(true);if(viewport.panBy(currentX-lastX,currentY-lastY)){rebuildScene();invalidate();}
            }
            lastX=currentX;lastY=currentY;
        }else if(action==MotionEvent.ACTION_POINTER_UP){
            int remaining=event.getActionIndex()==0?1:0;lastX=event.getX(remaining);lastY=event.getY(remaining);
        }
        if(action==MotionEvent.ACTION_UP&&!gestureMoved)performClick();
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){holdParent(false);if(action==MotionEvent.ACTION_CANCEL)gestureMoved=true;}
        return true;
    }
    private void holdParent(boolean hold){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(hold);}
    private void selectPoint(float px,float py){
        if(!campusMode||pointSelected==null)return;
        int nearest=-1;double distance=dp(26);
        for(int i=0;i<stopPixels.length;i++){
            double d=Math.hypot(px-stopPixels[i][0],py-stopPixels[i][1]);if(d<distance){nearest=i;distance=d;}
        }
        if(nearest>=0){JSONObject p=stops.optJSONObject(nearest);
            pointSelected.accept("点位 "+(nearest+1)+" · "+p.optString("point_name","考核点")+(currentStops[nearest]?"（本次三点）":""));}
    }
    void zoomBy(float factor){if(viewport.scaleBy(factor,getWidth()/2f,getHeight()/2f)){rebuildScene();invalidate();}}
    void resetViewport(){viewport.reset();rebuildScene();invalidate();}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.map_zoom_in,getContext().getString(R.string.map_zoom_in)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.map_zoom_out,getContext().getString(R.string.map_zoom_out)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.map_reset,getContext().getString(R.string.map_reset)));
    }
    @Override public boolean performAccessibilityAction(int action,Bundle arguments){
        if(action==R.id.map_zoom_in){zoomBy(1.4f);return true;}
        if(action==R.id.map_zoom_out){zoomBy(0.7f);return true;}
        if(action==R.id.map_reset){resetViewport();return true;}
        return super.performAccessibilityAction(action,arguments);
    }
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float x(double lng){return viewport.x(left+(float)((lng-minLng)*cosine*scale));}
    private float y(double lat){return viewport.y(top+(float)((maxLat-lat)*scale));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);viewport.resize(w,h);frame.set(0,0,w,h);clipPath.reset();clipPath.addRoundRect(frame,dp(18),dp(18),Path.Direction.CW);
        badge.set(dp(10),dp(8),dp(91),dp(29));emptyHint.set(dp(10),h-dp(57),w-dp(10),h-dp(30));rebuildScene();
    }
    private void rebuildScene(){
        int w=getWidth(),h=getHeight();if(w<=dp(32)||h<=dp(72))return;
        try{
            double lowLat=90,highLat=-90,lowLng=180,highLng=-180;
            if(!campusMode&&route!=null&&route.length()>0)for(int i=0;i<route.length();i++){
                JSONObject p=route.getJSONObject(i);lowLat=Math.min(lowLat,p.getDouble("lat"));highLat=Math.max(highLat,p.getDouble("lat"));lowLng=Math.min(lowLng,p.getDouble("lng"));highLng=Math.max(highLng,p.getDouble("lng"));
            }
            if(!campusMode&&stops!=null)for(int i=0;i<stops.length();i++){
                JSONObject p=stops.getJSONObject(i);lowLat=Math.min(lowLat,p.getDouble("lat"));highLat=Math.max(highLat,p.getDouble("lat"));lowLng=Math.min(lowLng,p.getDouble("lng"));highLng=Math.max(highLng,p.getDouble("lng"));
            }
            if(lowLat==90&&map!=null)for(double[] p:map.boundary){lowLat=Math.min(lowLat,p[0]);highLat=Math.max(highLat,p[0]);lowLng=Math.min(lowLng,p[1]);highLng=Math.max(highLng,p[1]);}
            if(lowLat==90){lowLat=30.827;highLat=30.838;lowLng=121.498;highLng=121.510;}
            double centreLat=(lowLat+highLat)/2,centreLng=(lowLng+highLng)/2;
            double latSpan=Math.max(0.0055,(highLat-lowLat)*1.20),lngSpan=Math.max(0.0055,(highLng-lowLng)*1.20);
            maxLat=centreLat+latSpan/2;minLng=centreLng-lngSpan/2;cosine=Math.cos(Math.toRadians(centreLat));
            scale=Math.min((w-dp(28))/(lngSpan*cosine),(h-dp(65))/latSpan);
            left=(float)((w-lngSpan*cosine*scale)/2);top=dp(31)+(float)((h-dp(65)-latSpan*scale)/2);
            routePath.reset();int routeCount=route==null?0:route.length();
            if(routePixels.length!=routeCount){routePixels=new float[routeCount][2];fractions=new double[routeCount];}
            double start=routePixels.length==0?0:route.getJSONObject(0).optDouble("timestamp",0),end=routePixels.length==0?1:route.getJSONObject(routePixels.length-1).optDouble("timestamp",1);
            for(int i=0;i<routePixels.length;i++){
                JSONObject p=route.getJSONObject(i);float[] position=routePixels[i];position[0]=x(p.getDouble("lng"));position[1]=y(p.getDouble("lat"));
                fractions[i]=p.has("fraction")?p.getDouble("fraction"):p.has("timestamp")?(p.getDouble("timestamp")-start)/Math.max(1,end-start):i/(double)Math.max(1,routePixels.length-1);
                if(i==0)routePath.moveTo(position[0],position[1]);else routePath.lineTo(position[0],position[1]);
            }
            int stopCount=stops==null?0:stops.length();if(stopPixels.length!=stopCount)stopPixels=new float[stopCount][2];
            for(int i=0;i<stopPixels.length;i++){JSONObject p=stops.getJSONObject(i);stopPixels[i][0]=x(p.getDouble("lng"));stopPixels[i][1]=y(p.getDouble("lat"));}
            // The map bitmap is rebuilt only when the viewport, route, or view dimensions change.
            if(mapLayer==null||mapLayer.getWidth()!=w||mapLayer.getHeight()!=h){
                if(mapLayer!=null)mapLayer.recycle();mapLayer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            }
            Canvas background=new Canvas(mapLayer);drawMap(background);
        }catch(Exception ignored){mapLayer=null;routePixels=new float[0][2];stopPixels=new float[0][2];}
    }
    private void polygon(double[][] points,boolean close){
        if(points.length==0)return;mapPath.moveTo(x(points[0][1]),y(points[0][0]));
        for(int i=1;i<points.length;i++)mapPath.lineTo(x(points[i][1]),y(points[i][0]));if(close)mapPath.close();
    }
    private void drawMap(Canvas canvas){
        canvas.drawColor(Color.rgb(246,247,240));if(map==null)return;
        paint.setTypeface(UiKit.font(getContext(),false));paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStyle(Paint.Style.FILL);
        mapPath.reset();polygon(map.boundary,true);paint.setColor(Color.rgb(237,243,232));canvas.drawPath(mapPath,paint);
        mapPath.setFillType(Path.FillType.EVEN_ODD);
        String[] layers={"green","water","sports","building"};
        int[] fills={Color.rgb(216,233,206),Color.rgb(199,226,235),Color.rgb(216,225,188),Color.rgb(221,221,207)};
        int[] outlines={Color.rgb(197,218,186),Color.rgb(171,208,219),Color.rgb(183,199,153),Color.rgb(188,196,178)};
        for(int layer=0;layer<layers.length;layer++)for(CampusMap.Area area:map.areas){
            if(!area.kind.equals(layers[layer]))continue;mapPath.reset();for(double[][] ring:area.rings)polygon(ring,true);
            paint.setStyle(Paint.Style.FILL);paint.setColor(fills[layer]);canvas.drawPath(mapPath,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(0.65f));paint.setColor(outlines[layer]);canvas.drawPath(mapPath,paint);
        }
        for(int pass=0;pass<2;pass++)for(CampusMap.Road road:map.roads){
            mapPath.reset();polygon(road.points,false);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(road.width==2?(pass==0?4.5f:3.2f):(pass==0?2.6f:1.5f)));
            paint.setColor(pass==0?Color.rgb(205,216,196):Color.rgb(255,254,249));canvas.drawPath(mapPath,paint);
        }
        mapPath.reset();polygon(map.boundary,true);paint.setColor(Color.rgb(160,185,146));paint.setStrokeWidth(dp(1));paint.setPathEffect(boundaryDash);canvas.drawPath(mapPath,paint);paint.setPathEffect(null);
        drawLabels(canvas);paint.setStyle(Paint.Style.FILL);
    }
    private void drawLabels(Canvas canvas){
        ArrayList<RectF> occupied=new ArrayList<>();paint.setTextSize(dp(10));paint.setTextAlign(Paint.Align.CENTER);
        for(float[] p:stopPixels)occupied.add(new RectF(p[0]-dp(15),p[1]-dp(15),p[0]+dp(15),p[1]+dp(15)));
        int count=0;
        for(CampusMap.Label label:map.labels){
            float cx=x(label.point[1]),cy=y(label.point[0]),half=paint.measureText(label.name)/2;
            RectF bounds=new RectF(cx-half-dp(3),cy-dp(7),cx+half+dp(3),cy+dp(7));
            if(bounds.left<dp(8)||bounds.right>getWidth()-dp(8)||bounds.top<dp(33)||bounds.bottom>getHeight()-dp(50))continue;
            boolean overlap=false;for(RectF previous:occupied)if(RectF.intersects(previous,bounds)){overlap=true;break;}if(overlap)continue;
            occupied.add(bounds);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(Color.rgb(248,250,243));canvas.drawText(label.name,cx,cy+dp(3),paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(89,107,84));canvas.drawText(label.name,cx,cy+dp(3),paint);if(++count>=(viewport.zoom()>1.5f?24:12))break;
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);int save=canvas.save();canvas.clipPath(clipPath);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(246,247,240));canvas.drawRect(frame,paint);
        if(mapLayer!=null)canvas.drawBitmap(mapLayer,0,0,null);
        if(routePixels.length>=2)drawRoute(canvas);
        for(int i=0;i<stopPixels.length;i++){
            float[] p=stopPixels[i];paint.setColor(Color.WHITE);canvas.drawCircle(p[0],p[1],dp(campusMode?11:12.5f),paint);
            paint.setColor(campusMode&&currentStops[i]?Color.rgb(8,127,114):Color.rgb(28,46,44));canvas.drawCircle(p[0],p[1],dp(campusMode?9:10.5f),paint);
            paint.setColor(Color.WHITE);paint.setTextSize(dp(campusMode?10:12));paint.setTypeface(UiKit.font(getContext(),true));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(Integer.toString(i+1),p[0],p[1]+dp(campusMode?3.5f:4),paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(UiKit.font(getContext(),false));paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.argb(236,255,255,255));canvas.drawRoundRect(badge,dp(7),dp(7),paint);paint.setTextSize(dp(11));paint.setColor(Color.rgb(74,96,65));canvas.drawText("奉贤校园地图",dp(17),dp(23),paint);
        paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText("N ↑",getWidth()-dp(13),dp(23),paint);paint.setTextAlign(Paint.Align.LEFT);
        if(routePixels.length<2&&!campusMode){
            paint.setColor(Color.argb(238,255,255,255));canvas.drawRoundRect(emptyHint,dp(8),dp(8),paint);paint.setColor(Color.rgb(87,109,78));paint.setTextSize(dp(11));paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("获取三点后，点击预览叠加模拟路线",getWidth()/2f,getHeight()-dp(39),paint);paint.setTextAlign(Paint.Align.LEFT);
        }else{
            int metres=100;double pixelsPerMetre=scale*viewport.zoom()/111195.0;
            for(int candidate:SCALE_METRES){metres=candidate;if(candidate*pixelsPerMetre<=Math.min(dp(100),getWidth()/3f))break;}
            float bar=(float)(metres*pixelsPerMetre);paint.setColor(Color.rgb(89,107,84));paint.setStrokeWidth(dp(1.5f));
            canvas.drawLine(dp(14),getHeight()-dp(29),dp(14)+bar,getHeight()-dp(29),paint);paint.setTextSize(dp(9));canvas.drawText(metres+" m",dp(14),getHeight()-dp(34),paint);
        }
        paint.setColor(Color.argb(240,250,252,248));canvas.drawRect(0,getHeight()-dp(22),getWidth(),getHeight(),paint);paint.setColor(Color.rgb(97,116,91));paint.setTextSize(dp(9));
        canvas.drawText(map==null?"校园地图暂不可用":campusMode?"© OpenStreetMap | 奉贤校区 · 离线底图":"© OpenStreetMap | 模拟路线 · 非实时定位",dp(12),getHeight()-dp(8),paint);canvas.restoreToCount(save);
    }
    private void drawRoute(Canvas canvas){
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Color.WHITE);paint.setStrokeWidth(dp(6));canvas.drawPath(routePath,paint);
        paint.setColor(Color.rgb(92,147,125));paint.setStrokeWidth(dp(3.5f));canvas.drawPath(routePath,paint);
        donePath.reset();float[] first=routePixels[0];donePath.moveTo(first[0],first[1]);float markerX=first[0],markerY=first[1];
        for(int i=1;i<routePixels.length;i++){
            float[] p=routePixels[i],before=routePixels[i-1];
            if(fractions[i]<=progress){donePath.lineTo(p[0],p[1]);markerX=p[0];markerY=p[1];}
            else{
                double ratio=Math.max(0,Math.min(1,(progress-fractions[i-1])/Math.max(1e-9,fractions[i]-fractions[i-1])));
                markerX=before[0]+(float)((p[0]-before[0])*ratio);markerY=before[1]+(float)((p[1]-before[1])*ratio);donePath.lineTo(markerX,markerY);break;
            }
        }
        paint.setColor(Color.rgb(8,127,114));canvas.drawPath(donePath,paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);canvas.drawCircle(first[0],first[1],dp(7),paint);paint.setColor(Color.rgb(8,127,114));canvas.drawCircle(first[0],first[1],dp(4.5f),paint);
        if(progress>0){paint.setColor(Color.WHITE);canvas.drawCircle(markerX,markerY,dp(8),paint);paint.setColor(Color.rgb(8,127,114));canvas.drawCircle(markerX,markerY,dp(5.5f),paint);}
    }
}

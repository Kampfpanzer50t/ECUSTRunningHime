package cn.huati.assistant;

/** Screen-space map transform, independent of Android and geographic coordinates. */
final class MapViewport {
    static final float MIN_ZOOM=1,MAX_ZOOM=6;
    private float width,height,zoom=1,panX,panY;
    float zoom(){return zoom;}
    void resize(float w,float h){width=Math.max(0,w);height=Math.max(0,h);clamp();}
    void reset(){zoom=1;panX=panY=0;}
    float x(float base){return width/2+(base-width/2)*zoom+panX;}
    float y(float base){return height/2+(base-height/2)*zoom+panY;}
    boolean scaleBy(float factor,float focusX,float focusY){
        if(!Float.isFinite(factor)||factor<=0||!Float.isFinite(focusX)||!Float.isFinite(focusY))return false;
        float next=Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,zoom*factor));
        if(next==zoom)return false;
        float ratio=next/zoom;
        panX=focusX-width/2+(panX+width/2-focusX)*ratio;
        panY=focusY-height/2+(panY+height/2-focusY)*ratio;
        zoom=next;clamp();return true;
    }
    boolean panBy(float dx,float dy){
        if(!Float.isFinite(dx)||!Float.isFinite(dy))return false;
        float beforeX=panX,beforeY=panY;panX+=dx;panY+=dy;clamp();
        return beforeX!=panX||beforeY!=panY;
    }
    private void clamp(){
        float limitX=width*(zoom-1)/2,limitY=height*(zoom-1)/2;
        panX=Math.max(-limitX,Math.min(limitX,panX));panY=Math.max(-limitY,Math.min(limitY,panY));
    }
}

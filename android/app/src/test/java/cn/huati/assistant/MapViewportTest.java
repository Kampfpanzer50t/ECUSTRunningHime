package cn.huati.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapViewportTest {
    private MapViewport view(){MapViewport v=new MapViewport();v.resize(360,420);return v;}
    @Test public void fullMapIsIdentityAndCannotPan(){
        MapViewport v=view();assertFalse(v.panBy(200,-80));assertEquals(73,v.x(73),0.001);assertEquals(350,v.y(350),0.001);
    }
    @Test public void pinchKeepsTheTouchedMapPointAnchored(){
        MapViewport v=view();assertTrue(v.scaleBy(2,90,120));assertEquals(90,v.x(90),0.001);assertEquals(120,v.y(120),0.001);
        assertEquals(110,v.x(100),0.001);assertEquals(140,v.y(130),0.001);
    }
    @Test public void focalAnchorRemainsCorrectAfterDragging(){
        MapViewport v=view();v.scaleBy(2,180,210);v.panBy(25,-30);
        float beforeX=v.x(100),beforeY=v.y(140);v.scaleBy(1.25f,beforeX,beforeY);
        assertEquals(beforeX,v.x(100),0.001);assertEquals(beforeY,v.y(140),0.001);
    }
    @Test public void centerButtonsScaleAroundTheScreenCenter(){
        MapViewport v=view();v.scaleBy(1.4f,180,210);
        assertEquals(180,v.x(180),0.001);assertEquals(210,v.y(210),0.001);assertEquals(1.4f,v.zoom(),0.001);
    }
    @Test public void panStopsBeforeMovingTheMapOffscreen(){
        MapViewport v=view();v.scaleBy(2,180,210);v.panBy(100000,100000);
        assertEquals(0,v.x(0),0.001);assertEquals(0,v.y(0),0.001);
        v.panBy(-200000,-200000);assertEquals(360,v.x(360),0.001);assertEquals(420,v.y(420),0.001);
    }
    @Test public void pinchClampsToOneAndSixAndResetsPanAtOne(){
        MapViewport v=view();v.scaleBy(100,180,210);assertEquals(6,v.zoom(),0.001);
        assertFalse(v.scaleBy(2,180,210));v.panBy(100,200);v.scaleBy(0.001f,180,210);
        assertEquals(1,v.zoom(),0.001);assertEquals(0,v.x(0),0.001);assertEquals(420,v.y(420),0.001);
    }
    @Test public void resetRestoresTheWholeMapAfterZoomAndDrag(){
        MapViewport v=view();v.scaleBy(3,80,130);v.panBy(90,-80);v.reset();
        assertEquals(1,v.zoom(),0.001);assertEquals(90,v.x(90),0.001);assertEquals(80,v.y(80),0.001);
    }
    @Test public void resizingKeepsZoomAndReclampsTheViewport(){
        MapViewport v=view();v.scaleBy(2,180,210);v.panBy(180,210);v.resize(200,240);
        assertEquals(2,v.zoom(),0.001);assertEquals(0,v.x(0),0.001);assertEquals(0,v.y(0),0.001);
    }
    @Test public void malformedGestureValuesDoNotChangeTheTransform(){
        MapViewport v=view();assertFalse(v.scaleBy(Float.NaN,30,50));assertFalse(v.scaleBy(-2,30,50));
        assertFalse(v.scaleBy(Float.POSITIVE_INFINITY,30,50));assertFalse(v.scaleBy(2,Float.NaN,50));
        assertFalse(v.panBy(Float.NaN,20));assertFalse(v.panBy(20,Float.POSITIVE_INFINITY));assertEquals(1,v.zoom(),0.001);
    }
}

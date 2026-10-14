package cn.huati.assistant;

import java.util.HashSet;
import java.util.Random;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CampusAreaTest {
    @Test public void polygonIncludesCampusInteriorAndBoundaryButExcludesOutsideCorners(){
        assertTrue(CampusArea.containsWgs(30.8335,121.499));
        assertTrue(CampusArea.containsWgs(30.833463,121.4919501));
        assertFalse(CampusArea.containsWgs(30.8278,121.492));
        assertFalse(CampusArea.containsWgs(31.2,121.5));
        assertFalse(CampusArea.containsWgs(Double.NaN,121.5));
    }
    @Test public void everyFallbackCandidateIsWithinCampusAfterCoordinateConversion()throws Exception{
        CampusArea.roads(null);Random random=new Random(41);HashSet<String> positions=new HashSet<>();
        for(int i=0;i<100;i++){
            JSONObject origin=CampusArea.randomOrigin(random,"定位失败");double[] wgs=MapCoordinates.wgs(origin.getDouble("lat"),origin.getDouble("lng"));
            assertTrue(CampusArea.containsWgs(wgs[0],wgs[1]));positions.add(origin.getDouble("lat")+","+origin.getDouble("lng"));
        }
        assertEquals(100,positions.size());
    }
    @Test public void randomRoadOriginsUseArbitraryPositionsAndInvalidGcjRecoversAutomatically()throws Exception{
        CampusArea.roads(new JSONObject("{\"nodes\":[[30.8335,121.499],[30.8338,121.499]],\"edges\":[[0,1]]}"));
        try{
            Random random=new Random(31);HashSet<Double> positions=new HashSet<>();
            for(int i=0;i<100;i++){
                JSONObject origin=CampusArea.safeGcj(Double.NaN,0,random);
                assertTrue(CampusArea.validGcj(origin.getDouble("lat"),origin.getDouble("lng")));
                double[] wgs=MapCoordinates.wgs(origin.getDouble("lat"),origin.getDouble("lng"));
                assertEquals(121.499,wgs[1],1e-8);assertTrue(wgs[0]>=30.8335&&wgs[0]<=30.8338);positions.add(wgs[0]);
            }
            assertEquals(100,positions.size());
            double[] valid=MapCoordinates.gcj(30.8335,121.499);JSONObject preserved=CampusArea.safeGcj(valid[0],valid[1],random);
            assertEquals(valid[0],preserved.getDouble("lat"),0);assertEquals(valid[1],preserved.getDouble("lng"),0);
            JSONObject outside=CampusArea.safeGcj(31.2,121.5,random);
            assertTrue(CampusArea.validGcj(outside.getDouble("lat"),outside.getDouble("lng")));
        }finally{CampusArea.roads(null);}
    }
    @Test public void freshInsideLocationIsKeptAndMappedToCampusPointCoordinateSystem()throws Exception{
        JSONObject origin=CampusArea.choose(()->new JSONObject().put("lat",30.8335).put("lng",121.499),new Random(7));
        assertEquals("手机当前位置（奉贤校区内）",origin.getString("source"));
        double[] restored=MapCoordinates.wgs(origin.getDouble("lat"),origin.getDouble("lng"));
        assertEquals(30.8335,restored[0],1e-8);assertEquals(121.499,restored[1],1e-8);
    }
    @Test public void outsideLocationSelectsCampusFallbackInsteadOfOutsideCoordinate()throws Exception{
        JSONObject origin=CampusArea.choose(()->new JSONObject().put("lat",31.2).put("lng",121.5),new Random(7));
        assertTrue(origin.getString("source").contains("当前位置在校外"));
        double[] wgs=MapCoordinates.wgs(origin.getDouble("lat"),origin.getDouble("lng"));assertTrue(CampusArea.containsWgs(wgs[0],wgs[1]));
    }
    @Test public void permissionFailureOrLocationTimeoutStillReturnsRandomCampusOrigin()throws Exception{
        for(Exception error:new Exception[]{new SecurityException("permission"),new IllegalStateException("timeout")}){
            JSONObject origin=CampusArea.choose(()->{throw error;},new Random(7));
            assertTrue(origin.getString("source").contains("定位失败"));assertTrue(origin.has("origin_name"));
        }
    }
    @Test public void malformedLocationCannotBecomeAnAutomaticOrigin()throws Exception{
        JSONObject origin=CampusArea.choose(()->new JSONObject().put("lat",100).put("lng",121),new Random(7));
        assertTrue(origin.getString("source").contains("定位失败"));
    }
    @Test public void coordinateConversionRoundTripsCampusAndLeavesOutsideChinaUnchanged(){
        double[] gcj=MapCoordinates.gcj(30.8335,121.499),back=MapCoordinates.wgs(gcj[0],gcj[1]);
        assertTrue(Math.abs(gcj[1]-121.499)>0.001);assertEquals(30.8335,back[0],1e-8);assertEquals(121.499,back[1],1e-8);
        double[] outside=MapCoordinates.gcj(40.7,-74);assertEquals(40.7,outside[0],0);assertEquals(-74,outside[1],0);
    }
}

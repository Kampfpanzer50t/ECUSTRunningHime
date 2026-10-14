package cn.huati.assistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoadRouteTest {
    private JSONArray stops()throws Exception{
        return new JSONArray("[{\"lat\":30.827536,\"lng\":121.503933},{\"lat\":30.832999,\"lng\":121.509337},{\"lat\":30.829844,\"lng\":121.507996}]");
    }
    static RoadRoute campus()throws Exception{
        return new RoadRoute(new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/campus-roads.json")),StandardCharsets.UTF_8)));
    }
    private double length(JSONArray samples)throws Exception{
        double metres=0;for(int i=1;i<samples.length();i++){
            JSONObject a=samples.getJSONObject(i-1),b=samples.getJSONObject(i);
            double segment=RunMath.distance(a.getDouble("lat"),a.getDouble("lng"),b.getDouble("lat"),b.getDouble("lng"));
            assertTrue("Sampling must preserve road bends",segment<=8.01);metres+=segment;
        }return metres;
    }
    private void visits(JSONArray samples,JSONArray stops)throws Exception{
        for(int i=0;i<3;i++){
            JSONObject stop=stops.getJSONObject(i);double best=Double.POSITIVE_INFINITY;
            for(int j=0;j<samples.length();j++){
                JSONObject p=samples.getJSONObject(j);best=Math.min(best,RunMath.distance(p.getDouble("lat"),p.getDouble("lng"),stop.getDouble("lat"),stop.getDouble("lng")));
            }assertTrue("Must reach checkpoint "+i,best<0.1);
        }
    }
    @Test public void realCampusRoutesMeetBoundsAndAllStopsAcrossSeedsAndOrigins()throws Exception{
        RoadRoute planner=campus();JSONArray points=stops();
        double[] phone=MapCoordinates.gcj(30.8335,121.499);
        double[][] origins={{30.83103,121.503709},MapCoordinates.gcj(30.835440,121.502185),phone};
        for(double[] origin:origins)for(int seed=0;seed<12;seed++){
            JSONObject plan=planner.plan(points,origin[0],origin[1],2200,false,new Random(seed));JSONArray samples=plan.getJSONArray("route_samples");
            double measured=length(samples);assertTrue(measured>=2100);assertTrue(measured<=2700);
            assertEquals((int)Math.ceil(measured),plan.getInt("distance"));assertEquals(measured,plan.getDouble("measured_metres"),0.01);
            visits(samples,points);
            for(int j=0;j<samples.length();j++){
                JSONObject p=samples.getJSONObject(j);double[] wgs=MapCoordinates.wgs(p.getDouble("lat"),p.getDouble("lng"));
                assertTrue("Route should stay within the campus: origin="+origin[0]+","+origin[1]+" seed="+seed+" sample="+j+" WGS="+wgs[0]+","+wgs[1],CampusArea.containsWgs(wgs[0],wgs[1]));
            }
        }
    }
    @Test public void manualDifferentTargetsRespectBothToleranceAndFloor()throws Exception{
        RoadRoute planner=campus();
        for(int target:new int[]{2000,2200,3000,5000}){
            JSONObject plan=planner.plan(stops(),30.83103,121.503709,target,false,new Random(target));
            double measured=length(plan.getJSONArray("route_samples"));
            assertTrue(measured>=RoadRoute.minimum(target));assertTrue(plan.getInt("distance")<=RoadRoute.maximum(target));
        }
    }
    private RoadRoute longRoad()throws Exception{
        return new RoadRoute(new JSONObject("{\"nodes\":[[0,0],[0,0.009],[0,0.019],[0,0.029]],\"edges\":[[0,1],[1,2],[2,3]]}"));
    }
    private JSONArray distantStops()throws Exception{
        return new JSONArray("[{\"lat\":0,\"lng\":0.009},{\"lat\":0,\"lng\":0.019},{\"lat\":0,\"lng\":0.029}]");
    }
    @Test public void automaticVisitsLongMandatoryRouteWithoutUpperTolerance()throws Exception{
        JSONObject plan=longRoad().plan(distantStops(),0,0,2200,true,new Random(5));
        assertTrue(plan.getInt("distance")>2700);assertTrue(plan.optBoolean("scheduled_route"));
        visits(plan.getJSONArray("route_samples"),distantStops());assertTrue(length(plan.getJSONArray("route_samples"))>=2100);
    }
    @Test(expected=IllegalArgumentException.class) public void manualCannotTrimAwayRequiredStopsToFitTolerance()throws Exception{
        longRoad().plan(distantStops(),0,0,2200,false,new Random(5));
    }
    @Test public void automaticShortRoutesAreExtendedPastTheMinimum()throws Exception{
        for(int seed=0;seed<20;seed++){
            JSONObject plan=campus().plan(stops(),30.83103,121.503709,2200,true,new Random(seed));
            assertTrue(length(plan.getJSONArray("route_samples"))>=2100);visits(plan.getJSONArray("route_samples"),stops());
        }
    }
    @Test public void speedChangesAreSmoothAndWireTimestampsAreStrictlyIncreasing()throws Exception{
        JSONObject plan=campus().plan(stops(),30.83103,121.503709,2200,false,new Random(8));
        JSONArray path=RoadRoute.timed(plan.getJSONArray("route_samples"),1000000,900000);double previousSpeed=0;
        assertEquals(1000000,path.getJSONObject(0).getLong("timestamp"));assertEquals(1900000,path.getJSONObject(path.length()-1).getLong("timestamp"));
        for(int i=1;i<path.length();i++){
            JSONObject a=path.getJSONObject(i-1),b=path.getJSONObject(i);long elapsed=b.getLong("timestamp")-a.getLong("timestamp");assertTrue(elapsed>0);
            double speed=RunMath.distance(a.getDouble("lat"),a.getDouble("lng"),b.getDouble("lat"),b.getDouble("lng"))/elapsed*1000;
            assertTrue(speed>1.5&&speed<4);if(previousSpeed>0)assertTrue(Math.abs(speed-previousSpeed)<0.15);previousSpeed=speed;
        }
    }
    @Test(expected=IllegalArgumentException.class) public void cannotConnectArbitraryOffCampusCoordinates()throws Exception{
        campus().plan(stops(),31.2,121.5,2200,true,new Random(1));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsOutsideCampusOriginEvenNearARoad()throws Exception{
        campus().plan(stops(),30.835440,121.502185,2200,true,new Random(1));
    }
    @Test(expected=IllegalArgumentException.class) public void disconnectedRoadGraphCannotGenerateFakeConnections()throws Exception{
        RoadRoute planner=new RoadRoute(new JSONObject("{\"nodes\":[[0,0],[0,0.01],[0,0.02],[0,0.03]],\"edges\":[[0,1],[2,3]]}"));
        planner.plan(new JSONArray("[{\"lat\":0,\"lng\":0.01},{\"lat\":0,\"lng\":0.02},{\"lat\":0,\"lng\":0.03}]"),0,0,2200,true,new Random(1));
    }
    @Test(expected=IllegalArgumentException.class) public void refusesDuplicateCheckpoints()throws Exception{
        JSONArray points=stops();points.put(2,points.getJSONObject(0));campus().plan(points,30.83103,121.503709,2200,false,new Random(1));
    }
    @Test public void basicAutomaticRouteAlsoExpandsToVisitDistantThreePoints()throws Exception{
        int metres=RunMath.automaticMetres(distantStops(),0,0,2200);
        assertTrue(metres>2700);visits(RunMath.path(distantStops(),0,0,0,metres,900),distantStops());
    }
    @Test public void reloadedRoadRouteAndPausePreserveThePlannedGeometry()throws Exception{
        JSONObject plan=campus().plan(stops(),30.83103,121.503709,2200,false,new Random(10));
        JSONObject state=new JSONObject().put("status","created").put("start_ms",1000000).put("duration",900)
            .put("road_mode",true).put("route_samples",plan.getJSONArray("route_samples")).put("pauses",new JSONArray());
        RunClock.pause(state,1300000);RunClock.resume(state,1360000);state=new JSONObject(state.toString());
        JSONArray path=RunClock.path(state,1960000);JSONObject before=null,after=null;double metres=0;
        for(int i=0;i<path.length();i++){
            JSONObject p=path.getJSONObject(i);long t=p.getLong("timestamp");if(t==1300000)before=p;if(t==1360000)after=p;
            assertFalse(t>1300000&&t<1360000);if(i>0){JSONObject a=path.getJSONObject(i-1);assertTrue(t>a.getLong("timestamp"));metres+=RunMath.distance(a.getDouble("lat"),a.getDouble("lng"),p.getDouble("lat"),p.getDouble("lng"));}
        }
        assertNotNull(before);assertNotNull(after);assertEquals(before.getDouble("lat"),after.getDouble("lat"),0);assertEquals(before.getDouble("lng"),after.getDouble("lng"),0);
        assertEquals(plan.getDouble("measured_metres"),metres,0.1);assertEquals(1000000,path.getJSONObject(0).getLong("timestamp"));assertEquals(1960000,path.getJSONObject(path.length()-1).getLong("timestamp"));
        visits(path,stops());
    }
}

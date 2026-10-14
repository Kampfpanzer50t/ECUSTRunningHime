package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CampusPointsTest {
    @Test public void publicReferenceIsExplicitlyPartialAndInsideCampus()throws Exception{
        JSONObject table=CampusPoints.reference();assertFalse(table.getBoolean("complete"));assertEquals(3,table.getJSONArray("points").length());
        assertEquals(3,CampusPoints.importTable(table.toString(),100).getJSONArray("points").length());
    }
    @Test public void repeatedThreePointResponsesDoNotCreateDuplicateDirectoryEntries()throws Exception{
        JSONObject seed=CampusPoints.reference();JSONArray points=seed.getJSONArray("points");
        points.getJSONObject(0).put("id",123);JSONObject first=CampusPoints.merge(null,points,100);
        JSONObject second=CampusPoints.merge(first,points,200);assertEquals(3,second.getJSONArray("points").length());
        assertEquals(200,second.getLong("updated_at"));assertEquals("123",second.getJSONArray("points").getJSONObject(0).getString("id"));
        assertFalse(second.has("student_id"));assertFalse(second.has("headers"));
    }
    @Test public void importedWgsPointsAreConvertedIntoSameCoordinatesAsMap()throws Exception{
        JSONObject table=CampusPoints.reference();JSONObject p=table.getJSONArray("points").getJSONObject(0);
        double lat=p.getDouble("lat"),lng=p.getDouble("lng");double[] wgs=MapCoordinates.wgs(lat,lng);
        table.put("coordinate_system","WGS84").put("points",new JSONArray().put(new JSONObject().put("point_name","测试点").put("lat",wgs[0]).put("lng",wgs[1])));
        JSONObject mapped=CampusPoints.importTable(table.toString(),100).getJSONArray("points").getJSONObject(0);
        assertEquals(lat,mapped.getDouble("lat"),0.000001);assertEquals(lng,mapped.getDouble("lng"),0.000001);
    }
    @Test public void completeImportIsDowngradedWhenServerReturnsAnUnlistedPoint()throws Exception{
        JSONObject full=CampusPoints.importTable(CampusPoints.reference().put("complete",true).toString(),100);
        assertTrue(CampusPoints.merge(full,full.getJSONArray("points"),200).getBoolean("complete"));
        JSONArray fresh=new JSONArray().put(new JSONObject().put("point_name","新点位").put("lat",30.8332).put("lng",121.509337));
        JSONObject updated=CampusPoints.merge(full,fresh,200);assertFalse(updated.getBoolean("complete"));assertTrue(updated.getBoolean("completeness_changed"));
        assertEquals(4,updated.getJSONArray("points").length());assertEquals(3,full.getJSONArray("points").length());
    }
    @Test public void wrongCampusAndOffCampusPointsAreRejectedBeforeReplacingDirectory()throws Exception{
        for(int kind=0;kind<3;kind++){
            JSONObject table=CampusPoints.reference();
            if(kind==0)table.put("campus","其他学校");
            else if(kind==1)table.getJSONArray("points").getJSONObject(0).put("lat",0).put("lng",0);
            else table.put("coordinate_system","unknown");
            try{CampusPoints.importTable(table.toString(),100);fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void serverNameCanReplacePublicPlaceholderWithoutAddingSamePhysicalPoint()throws Exception{
        JSONObject table=CampusPoints.reference();JSONArray reply=new JSONArray().put(new JSONObject(table.getJSONArray("points").getJSONObject(1).toString()).put("id",38).put("point_name","服务器名称"));
        JSONObject updated=CampusPoints.merge(table,reply,100);assertEquals(3,updated.getJSONArray("points").length());
        assertEquals("服务器名称",updated.getJSONArray("points").getJSONObject(1).getString("point_name"));
    }
}

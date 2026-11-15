package kr.cabinstory;

import android.test.InstrumentationTestCase;
import org.json.*;

/** GPT-owned contract tests; fixtures avoid loading public map servers. */
public final class MapServiceTest extends InstrumentationTestCase {
    private interface Check {void run()throws Exception;}
    private void rejects(Check fn)throws Exception {try {fn.run();fail("Expected rejected map request");}catch(Exception expected){}}
    private JSONObject searchFixture()throws Exception {
        return new JSONObject("{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[129.219,35.8347]},\"properties\":{\"name\":\"첨성대\",\"city\":\"경주시\"}},{\"geometry\":{\"type\":\"Point\",\"coordinates\":[129,91]},\"properties\":{\"name\":\"bad\"}}]}");
    }
    private JSONObject routeFixture()throws Exception {
        return new JSONObject("{\"code\":\"Ok\",\"waypoints\":[{\"distance\":0},{\"distance\":0}],\"routes\":[{\"distance\":3477.7,\"duration\":530.8,\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[129.219,35.8347],[129.2121,35.8384]]},\"legs\":[{\"distance\":3477.7,\"duration\":530.8}]}]}");
    }
    private JSONObject plan()throws Exception {
        return new JSONObject().put("tripId","qa-map").put("routeRevision",1)
            .put("origin",new JSONObject().put("name","출발").put("longitude",129.219).put("latitude",35.8347))
            .put("stops",new JSONArray("[\"destination\"]"))
            .put("places",new JSONArray().put(new JSONObject().put("id","destination").put("name","도착").put("longitude",129.2121).put("latitude",35.8384)));
    }
    public void testSearchValidatesCoordinatesAndCachesExplicitQuery()throws Exception {
        int[] calls={0};MapService map=new MapService(url->{calls[0]++;return searchFixture();});
        JSONArray first=map.search("첨성대",null,null);assertEquals(1,first.length());assertEquals("첨성대",first.getJSONObject(0).getString("name"));assertEquals(129.219,first.getJSONObject(0).getDouble("longitude"),.000001);
        map.search("첨성대",null,null);assertEquals(1,calls[0]);
    }
    public void testRateLimitDoesNotMakeRepeatedUpstreamRequests()throws Exception {
        int[] calls={0};MapService map=new MapService(url->{calls[0]++;return searchFixture();});map.search("첨성대",null,null);
        rejects(()->map.search("다른 검색",null,null));assertEquals(1,calls[0]);
    }
    public void testRoadRouteUsesOrderedCoordinatesAndRealProviderValues()throws Exception {
        int[] calls={0};String[] sent={""};MapService map=new MapService(url->{calls[0]++;sent[0]=url;return routeFixture();});
        JSONObject route=map.route(plan());String[] coordinates=sent[0].substring(sent[0].indexOf("/driving/")+9).split("\\?")[0].split(";");assertEquals(2,coordinates.length);
        String[] first=coordinates[0].split(","),last=coordinates[1].split(",");assertEquals(129.219,Double.parseDouble(first[0]),.000001);assertEquals(35.8347,Double.parseDouble(first[1]),.000001);assertEquals(129.2121,Double.parseDouble(last[0]),.000001);assertEquals(35.8384,Double.parseDouble(last[1]),.000001);
        assertEquals("LineString",route.getJSONObject("geometry").getString("type"));assertEquals(3477.7,route.getDouble("distanceMeters"),.001);assertEquals(530.8,route.getDouble("durationSeconds"),.001);assertEquals(1,route.getJSONArray("legs").length());
        map.route(plan());assertEquals(1,calls[0]);
    }
    public void testUnavailableRoadRouteHasNoStraightLineFallback()throws Exception {
        MapService map=new MapService(url->new JSONObject("{\"code\":\"NoRoute\",\"routes\":[]}"));rejects(()->map.route(plan()));
        MapService malformed=new MapService(url->{JSONObject body=routeFixture();body.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").put("coordinates",new JSONArray("[[129,35],[129,91]]"));return body;});rejects(()->malformed.route(plan()));
    }
    public void testInvalidInputDoesNotReachPublicNetwork()throws Exception {
        int[] calls={0};MapService map=new MapService(url->{calls[0]++;throw new AssertionError("Bad input reached transport");});
        rejects(()->map.search("a",null,null));rejects(()->map.search("valid",91.0,129.0));
        JSONObject noOrigin=plan();noOrigin.put("origin",JSONObject.NULL);rejects(()->map.route(noOrigin));assertEquals(0,calls[0]);
    }
    public void testDistantRoadSnapIsRejected()throws Exception {
        MapService map=new MapService(url->{JSONObject body=routeFixture();body.getJSONArray("waypoints").getJSONObject(0).put("distance",5000);return body;});rejects(()->map.route(plan()));
    }
    public void testProviderFailureStatusIsUsefulAndRateStatusPreserved()throws Exception {
        MapService failed=new MapService(url->{throw new java.io.IOException("private upstream details");});
        try{failed.search("첨성대",null,null);fail("Expected provider error");}catch(MapService.ServiceException e){assertEquals(503,e.status);assertFalse(e.getMessage().contains("private upstream"));}
        MapService limited=new MapService(url->{throw new MapService.ServiceException(429,"잠시 뒤 다시 시도");});
        try{limited.search("첨성대",null,null);fail("Expected rate error");}catch(MapService.ServiceException e){assertEquals(429,e.status);}
    }
}

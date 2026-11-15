package kr.cabinstory;

import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.*;

/** GPT acceptance: editable routes, trip isolation and trustworthy visit records. */
public final class PlannerStoreTest extends InstrumentationTestCase {
    private static final String STORAGE="qa-planner-only";
    private TripStore store;
    private Context context;
    @Override protected void setUp() throws Exception {super.setUp();context=getInstrumentation().getTargetContext();context.getSharedPreferences(STORAGE,0).edit().clear().commit();store=new TripStore(context,STORAGE);}
    @Override protected void tearDown() throws Exception {context.getSharedPreferences(STORAGE,0).edit().clear().commit();super.tearDown();}
    private JSONObject request(String action)throws Exception{return new JSONObject().put("action",action).put("revision",store.snapshot().getInt("revision"));}
    private void driver(String action)throws Exception{store.mutate("driver",request(action),true);}
    private JSONObject point(String name,double lat,double lon)throws Exception{return new JSONObject().put("name",name).put("latitude",lat).put("longitude",lon).put("address","공개 샘플 장소");}
    private interface Check{void run()throws Exception;}
    private void rejects(Check fn)throws Exception{try{fn.run();fail("Invalid operation accepted");}catch(IllegalArgumentException|SecurityException|IllegalStateException expected){}}
    private void start()throws Exception{store.mutate("driver",request("setOrigin").put("placeId","cheomseongdae"),true);driver("approve");driver("startTrip");}
    private JSONObject route()throws Exception{return new JSONObject().put("geometry",new JSONObject().put("type","LineString").put("coordinates",new JSONArray("[[129.219,35.8347],[129.2121,35.8384]]"))).put("distanceMeters",1200).put("durationSeconds",180).put("legs",new JSONArray("[{\"distanceMeters\":1200,\"durationSeconds\":180}]" )).put("provider","OSRM / FOSSGIS").put("computedAt",System.currentTimeMillis());}

    public void testNewTripAndSwitchPreserveBothPlans()throws Exception{
        String original=store.snapshot().getString("tripId");int revision=store.snapshot().getInt("revision");
        store.mutate("driver",request("newTrip").put("title","두 번째 여행"),true);
        JSONObject fresh=store.snapshot();assertFalse(original.equals(fresh.getString("tripId")));assertEquals(0,fresh.getJSONArray("stops").length());assertTrue(fresh.getInt("revision")>revision);
        String second=fresh.getString("tripId");store.mutate("driver",request("add").put("placeId","museum"),true);
        store.mutate("driver",request("openTrip").put("tripId",original),true);assertEquals(3,store.snapshot().getJSONArray("stops").length());
        store.mutate("driver",request("openTrip").put("tripId",second),true);assertEquals("museum",store.snapshot().getJSONArray("stops").getString(0));
        TripStore reopened=new TripStore(context,STORAGE);assertEquals(second,reopened.snapshot().getString("tripId"));assertEquals(2,reopened.snapshot().getJSONArray("savedTrips").length());
    }
    public void testCustomCoordinatesAndOriginValidationAreAtomic()throws Exception{
        String before=store.snapshot().toString();
        rejects(()->store.mutate("driver",request("addCustom").put("place",point("bad",91,129)),true));assertEquals(before,store.snapshot().toString());
        rejects(()->store.mutate("driver",request("setOrigin").put("place",point("bad\nname",35,129)),true));assertEquals(before,store.snapshot().toString());
        store.mutate("driver",request("addCustom").put("place",point("새 방문지",35.84,129.22)),true);
        JSONObject state=store.snapshot();String id=state.getJSONArray("stops").getString(3);JSONObject custom=store.place(id);assertEquals("새 방문지",custom.getString("name"));assertEquals(0,custom.getJSONArray("adult").length());assertEquals(0,custom.getJSONArray("kids").length());assertFalse(custom.optBoolean("hasDocent",true));
        before=state.toString();rejects(()->store.mutate("driver",request("addCustom").put("place",point("새 방문지",35.84,129.22)),true));assertEquals(before,store.snapshot().toString());
    }
    public void testRouteCacheRejectsStaleTripAndInvalidatesOnOrderChange()throws Exception{
        JSONObject s=store.snapshot();assertFalse(store.cacheRoute("different-trip",s.getInt("routeRevision"),route()));
        assertTrue(store.cacheRoute(s.getString("tripId"),s.getInt("routeRevision"),route()));assertFalse(store.snapshot().isNull("route"));
        store.mutate("driver",request("move").put("placeId","cheomseongdae").put("delta",1),true);assertTrue(store.snapshot().isNull("route"));
        assertFalse(store.cacheRoute(s.getString("tripId"),s.getInt("routeRevision"),route()));
    }
    public void testStartedPlanAndFirstVisitTimeStayImmutable()throws Exception{
        start();String planned=store.snapshot().getJSONObject("startedPlan").toString();
        store.mutate("driver",request("visit").put("placeId","cheomseongdae"),true);JSONArray events=store.snapshot().getJSONArray("visitEvents");long first=events.getJSONObject(0).getLong("completedAt");assertTrue(first>0);
        store.mutate("driver",request("visit").put("placeId","cheomseongdae"),true);assertEquals(1,store.snapshot().getJSONArray("visitEvents").length());assertEquals(first,store.snapshot().getJSONArray("visitEvents").getJSONObject(0).getLong("completedAt"));
        store.mutate("driver",request("remove").put("placeId","cheomseongdae"),true);assertEquals(1,store.snapshot().getJSONArray("visitEvents").length());assertEquals(planned,store.snapshot().getJSONObject("startedPlan").toString());
        driver("finishTrip");JSONObject done=store.snapshot();assertEquals("completed",done.getString("status"));assertTrue(done.getLong("completedAt")>=first);assertEquals(1,done.getJSONArray("records").length());
        rejects(()->store.mutate("driver",request("add").put("placeId","cheomseongdae"),true));
        store.mutate("driver",request("newTrip").put("title","다음 여행"),true);assertEquals(1,store.snapshot().getJSONArray("records").length());
        TripStore restored=new TripStore(context,STORAGE);assertEquals(first,restored.snapshot().getJSONArray("records").getJSONObject(0).getJSONArray("visitEvents").getJSONObject(0).getLong("completedAt"));
    }
    public void testViewerAndProposerCannotCreateOrChangeOrigin()throws Exception{
        String actor=store.join("조회 기기").getString("memberId");
        rejects(()->store.mutate(actor,request("newTrip").put("title","unauthorized"),false));
        rejects(()->store.mutate(actor,request("setOrigin").put("place",point("출발",35,129)),false));
        store.role(actor,"proposer",true);store.mutate(actor,request("proposeCustom").put("place",point("제안 장소",35.84,129.23)),false);
        assertEquals(4,store.snapshot().getJSONArray("stops").length());assertEquals(0,store.snapshot().getJSONArray("proposals").length());
    }
    public void testLegacyVisitTimestampRemainsUnknown()throws Exception{
        JSONObject old=new JSONObject().put("tripId","gyeongju-family-01").put("revision",3).put("routeRevision",1).put("approvedRevision",1).put("stops",new JSONArray("[\"cheomseongdae\",\"wolji\"]")).put("visited",new JSONArray("[\"cheomseongdae\"]")).put("selected","wolji").put("history",new JSONArray());
        context.getSharedPreferences(STORAGE,0).edit().putString("trip",old.toString()).commit();TripStore migrated=new TripStore(context,STORAGE);JSONObject s=migrated.snapshot();assertEquals("gyeongju-family-01",s.getString("tripId"));assertEquals(1,s.getJSONArray("visited").length());assertTrue(s.getJSONArray("visitEvents").getJSONObject(0).isNull("completedAt"));
    }
    public void testCannotSwitchAwayFromActiveAndEmptyTripIsValidDraft()throws Exception{
        start();String before=store.snapshot().toString();rejects(()->store.mutate("driver",request("newTrip").put("title","유실되면 안 됨"),true));assertEquals(before,store.snapshot().toString());
        driver("finishTrip");store.mutate("driver",request("newTrip").put("title","빈 계획"),true);assertEquals("",store.snapshot().getString("selected"));rejects(()->driver("startTrip"));
    }
}

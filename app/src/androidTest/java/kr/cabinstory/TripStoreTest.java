package kr.cabinstory;

import android.test.InstrumentationTestCase;
import org.json.*;
import java.util.ConcurrentModificationException;

public final class TripStoreTest extends InstrumentationTestCase {
    private TripStore store;
    private static final String STORAGE="qa-trip-only";
    @Override protected void setUp() throws Exception {
        super.setUp();getInstrumentation().getTargetContext().getSharedPreferences(STORAGE,0).edit().clear().commit();
        store=new TripStore(getInstrumentation().getTargetContext(),STORAGE);
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().getTargetContext().getSharedPreferences(STORAGE,0).edit().clear().commit();super.tearDown();
    }
    private JSONObject request(String action,String id)throws Exception{return new JSONObject().put("action",action).put("placeId",id).put("revision",store.snapshot().getInt("revision"));}
    private interface Check{void run()throws Exception;}
    private void rejects(Class<?> type,Check run)throws Exception{try{run.run();fail("Expected "+type.getSimpleName());}catch(Exception e){if(!type.isInstance(e))throw e;}}
    public void testViewerCannotEditOrEscalate()throws Exception{
        JSONObject joined=store.join("아이 기기");String member=store.memberFor(joined.getString("token"));store.role(member,"viewer",true);
        rejects(SecurityException.class,()->store.mutate(member,request("add","museum"),false));
        rejects(SecurityException.class,()->store.mutate(member,request("approve",""),true));
        rejects(SecurityException.class,()->store.mutate(member,request("propose","museum"),false));
        assertEquals(3,store.snapshot().getJSONArray("stops").length());
        assertFalse(store.snapshot().toString().contains(joined.getString("token")));
    }
    public void testEditorCanShareButCannotApprove()throws Exception{
        JSONObject joined=store.join("동승자");String member=joined.getString("memberId");store.role(member,"editor",true);
        store.mutate(member,request("add","museum"),false);
        JSONObject state=store.snapshot();assertEquals(4,state.getJSONArray("stops").length());
        assertTrue(state.getInt("approvedRevision")<state.getInt("routeRevision"));
        rejects(SecurityException.class,()->store.mutate(member,request("approve",""),true));
        store.mutate("driver",request("approve",""),true);
        assertEquals(store.snapshot().getInt("routeRevision"),store.snapshot().getInt("approvedRevision"));
    }
    public void testFamilyAddsPlacesWithoutApproval()throws Exception{
        String member=store.join("가족").getString("memberId");   // a joined device may add places straight away
        final String[] heard={""};store.onAdded=(who,place)->heard[0]=who+"|"+place;
        store.mutate(member,request("propose","museum"),false);
        store.mutate(member,new JSONObject().put("action","proposeCustom").put("revision",store.snapshot().getInt("revision"))
            .put("place",new JSONObject().put("name","황리단길").put("address","경주시").put("latitude",35.8375).put("longitude",129.2096)),false);
        JSONObject state=store.snapshot();
        assertEquals(5,state.getJSONArray("stops").length());assertEquals(0,state.getJSONArray("proposals").length());
        assertEquals("가족|황리단길",heard[0]);
        rejects(IllegalArgumentException.class,()->store.mutate(member,request("add","museum"),false));   // already on the list
        rejects(SecurityException.class,()->store.mutate(member,request("remove","museum"),false));     // tidying needs the editor role
        store.role(member,"viewer",true);
        rejects(SecurityException.class,()->store.mutate(member,request("propose","wolji"),false));
    }
    public void testDriverPicksDestinationAndTripStarts()throws Exception{
        String member=store.join("가족").getString("memberId");
        rejects(SecurityException.class,()->store.mutate(member,request("go","wolji"),false));
        assertEquals("planning",store.snapshot().getString("status"));
        store.mutate("driver",request("go","wolji"),true);   // no origin, no route preview, no approval
        JSONObject state=store.snapshot();
        assertEquals("active",state.getString("status"));assertEquals("wolji",state.getString("selected"));assertFalse(state.isNull("startedAt"));
        assertTrue(store.arrive("wolji"));
        store.mutate("driver",request("go","wolji"),true);   // heading back to a place already visited
        assertFalse(TripStore.contains(store.snapshot().getJSONArray("visited"),"wolji"));
        rejects(IllegalArgumentException.class,()->store.mutate("driver",request("go","museum"),true));   // not on the list
    }
    public void testStaleRevisionRejectedAndInvalidEditAtomic()throws Exception{
        JSONObject old=request("add","museum");store.mutate("driver",request("select","daereungwon"),true);
        rejects(ConcurrentModificationException.class,()->store.mutate("driver",old,true));
        String before=store.snapshot().toString();
        rejects(IllegalArgumentException.class,()->store.mutate("driver",request("move","cheomseongdae").put("delta",-1),true));
        assertEquals(before,store.snapshot().toString());
        rejects(IllegalArgumentException.class,()->store.mutate("driver",request("add","unknown"),true));
        assertEquals(before,store.snapshot().toString());
    }
    public void testRevocationAndDriverSafetyEnforced()throws Exception{
        JSONObject joined=store.join("편집자");String member=joined.getString("memberId");store.role(member,"editor",true);
        rejects(SecurityException.class,()->store.role(member,"viewer",false));
        rejects(SecurityException.class,()->store.mutate("driver",request("remove","wolji"),false));
        store.role(member,"viewer",true);
        rejects(SecurityException.class,()->store.mutate(member,request("add","museum"),false));
        store.role(member,"revoked",true);
        rejects(SecurityException.class,()->store.memberFor(joined.getString("token")));
    }
    public void testVisitsRemovalAndPersistence()throws Exception{
        store.mutate("driver",request("setOrigin","cheomseongdae"),true);
        store.mutate("driver",request("approve",""),true);
        store.mutate("driver",request("startTrip",""),true);
        store.mutate("driver",request("visit","cheomseongdae"),true);assertEquals("daereungwon",store.snapshot().getString("selected"));
        store.mutate("driver",request("remove","cheomseongdae"),true);assertEquals(0,store.snapshot().getJSONArray("visited").length());
        store.mutate("driver",request("remove","daereungwon"),true);assertEquals("wolji",store.snapshot().getString("selected"));
        store.mutate("driver",request("remove","wolji"),true);
        assertEquals(0,store.snapshot().getJSONArray("stops").length());assertEquals("",store.snapshot().getString("selected"));
        TripStore restored=new TripStore(getInstrumentation().getTargetContext(),STORAGE);
        assertEquals(store.snapshot().getJSONArray("stops").toString(),restored.snapshot().getJSONArray("stops").toString());
        assertEquals(store.snapshot().getInt("revision"),restored.snapshot().getInt("revision"));
    }
    public void testContentAndCoordinates()throws Exception{
        JSONObject state=store.snapshot();assertEquals(4,state.getJSONArray("places").length());
        for(int i=0;i<4;i++){
            JSONObject place=state.getJSONArray("places").getJSONObject(i);
            assertEquals(8,place.getJSONArray("kids").length());assertTrue(place.getJSONArray("adult").length()>=7);
            assertTrue(place.getDouble("latitude")>35 && place.getDouble("latitude")<36);
            assertTrue(place.getDouble("longitude")>129 && place.getDouble("longitude")<130);
            assertTrue(place.getString("sourceUrl").startsWith("https://"));
        }
        assertEquals(0.0,VehicleBridge.distance(35.8,129.2,35.8,129.2),.001);
        assertTrue(VehicleBridge.distance(35.8,129.2,35.81,129.2)>1000);
        rejects(IllegalArgumentException.class,()->store.join(" "));
        rejects(IllegalArgumentException.class,()->store.join("bad\nname"));
    }
    public void testCorruptStorageRecoveryRetainsOriginal()throws Exception{
        getInstrumentation().getTargetContext().getSharedPreferences(STORAGE,0).edit().putString("trip","broken").commit();
        TripStore restored=new TripStore(getInstrumentation().getTargetContext(),STORAGE);
        assertEquals(3,restored.snapshot().getJSONArray("stops").length());assertFalse(restored.loadWarning.isEmpty());
        assertEquals("broken",getInstrumentation().getTargetContext().getSharedPreferences(STORAGE,0).getString("recovery",""));
    }
}

package kr.cabinstory;

import android.test.InstrumentationTestCase;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class FamilyServerTest extends InstrumentationTestCase {
    private TripStore store;
    private FamilyServer server;
    private String token,member;
    private int status;
    @Override protected void setUp()throws Exception{
        super.setUp();getInstrumentation().getTargetContext().getSharedPreferences("qa-http-only",0).edit().clear().commit();
        store=new TripStore(getInstrumentation().getTargetContext(),"qa-http-only");server=new FamilyServer(getInstrumentation().getTargetContext(),store);
    }
    @Override protected void tearDown()throws Exception{server.close();getInstrumentation().getTargetContext().getSharedPreferences("qa-http-only",0).edit().clear().commit();super.tearDown();}
    private JSONObject http(String path,JSONObject data,String session,String origin)throws Exception{
        return httpHost(path,data,session,origin,"127.0.0.1:8787");
    }
    private JSONObject httpHost(String path,JSONObject data,String session,String origin,String host)throws Exception{
        byte[] body=data==null?new byte[0]:data.toString().getBytes(StandardCharsets.UTF_8);
        String headers=(data==null?"GET":"POST")+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\n"+
            "Content-Type: application/json\r\nContent-Length: "+body.length+"\r\n"+
            (session==null?"":"Authorization: Bearer "+session+"\r\n")+(origin==null?"":"Origin: "+origin+"\r\n")+"\r\n";
        try(Socket socket=new Socket("127.0.0.1",8787)){
            socket.setSoTimeout(4000);socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];for(int n;(n=socket.getInputStream().read(b))!=-1;)bytes.write(b,0,n);
            String response=new String(bytes.toByteArray(),StandardCharsets.UTF_8);status=Integer.parseInt(response.split(" ")[1]);
            return new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4));
        }
    }
    private void join()throws Exception{
        JSONObject data=http("/api/join",new JSONObject().put("name","HTTP 가족").put("invitation",server.invitation),null,null);
        assertEquals(200,status);token=data.getString("token");member=data.getString("memberId");
    }
    private JSONObject edit(String action,String place)throws Exception{return new JSONObject().put("action",action).put("placeId",place).put("revision",store.snapshot().getInt("revision"));}
    public void testUnauthenticatedAndWrongInvitationRejected()throws Exception{
        http("/api/state",null,null,null);assertEquals(403,status);
        http("/api/join",new JSONObject().put("name","test").put("invitation","wrong"),null,null);assertEquals(403,status);
    }
    public void testHttpRoundtripPermissionConflictAndRevocation()throws Exception{
        join();store.role(member,"viewer",true);http("/api/edit",edit("add","museum"),token,null);assertEquals(403,status);
        store.role(member,"editor",true);JSONObject old=edit("remove","wolji");
        http("/api/edit",edit("add","museum"),token,null);assertEquals(200,status);
        JSONObject response=http("/api/state",null,token,null);assertEquals(200,status);assertEquals(4,response.getJSONArray("stops").length());
        assertEquals("gyeongju-family-01",response.getString("tripId"));assertFalse(response.toString().contains(token));
        http("/api/edit",old,token,null);assertEquals(409,status);assertEquals(4,store.snapshot().getJSONArray("stops").length());
        store.role(member,"revoked",true);http("/api/state",null,token,null);assertEquals(403,status);
    }
    public void testCrossOriginAndPathTraversalRejected()throws Exception{
        join();store.role(member,"editor",true);
        http("/api/edit",edit("add","museum"),token,"https://untrusted.example");assertEquals(403,status);
        assertEquals(3,store.snapshot().getJSONArray("stops").length());
        http("/art/../places.json",null,null,null);assertEquals(404,status);
    }
    public void testDriverAuthorityAndEditorVisitOverHttp()throws Exception{
        join();store.role(member,"editor",true);
        http("/api/edit",edit("approve",""),token,null);assertEquals(403,status);
        http("/api/edit",edit("visit","cheomseongdae"),token,null);assertEquals(400,status);
        store.mutate("driver",edit("setOrigin","cheomseongdae"),true);
        store.mutate("driver",edit("approve",""),true);
        store.mutate("driver",edit("startTrip",""),true);
        http("/api/edit",edit("visit","cheomseongdae"),token,null);assertEquals(200,status);
        http("/api/edit",edit("select","wolji"),token,null);assertEquals(403,status);
        store.disconnectAll();http("/api/state",null,token,null);assertEquals(403,status);
        JSONObject lateSession=store.join("종료 검증");
        server.close();server.close();
        try{store.memberFor(lateSession.getString("token"));fail("Server close must invalidate its sessions");}
        catch(SecurityException expected){}
    }
    public void testNativeDriverSecretRequiresLocalHostAndParkedState()throws Exception{
        http("/api/state",null,server.invitation,null);assertEquals(403,status);
        httpHost("/api/state",null,server.driverToken,null,"untrusted.example:8787");assertEquals(403,status);
        server.driverParked=()->false;http("/api/edit",edit("approve",""),server.driverToken,null);assertEquals(403,status);
        server.driverParked=()->true;http("/api/edit",edit("approve",""),server.driverToken,null);assertEquals(200,status);
        JSONObject state=http("/api/state",null,server.driverToken,null);assertEquals(200,status);assertEquals("driver",state.getString("role"));assertFalse(state.toString().contains(server.driverToken));
    }
    public void testPassengerCannotUseNativeRoleEndpoint()throws Exception{
        join();JSONObject role=new JSONObject().put("memberId",member).put("role","editor");
        http("/api/role",role,token,null);assertEquals(403,status);
        server.driverParked=()->true;http("/api/role",role,server.driverToken,null);assertEquals(200,status);
        assertEquals("editor",http("/api/state",null,token,null).getString("role"));
    }
    public void testMapRequestsAuthenticateBeforeAnyPublicCall()throws Exception{
        http("/api/search",new JSONObject().put("query","첨성대"),null,null);assertEquals(403,status);
        http("/api/route",new JSONObject().put("tripId","fake").put("routeRevision",1),null,null);assertEquals(403,status);
    }
}

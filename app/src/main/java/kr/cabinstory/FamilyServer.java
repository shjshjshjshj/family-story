package kr.cabinstory;

import android.content.Context;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A small local companion endpoint, active only while the vehicle app is visible. */
public final class FamilyServer implements Closeable {
    public static final int PORT=8787;
    /** Short code typed on the phone app. Joins are rate limited to one per second per address. */
    public final String invitation=code();
    private static String code() {
        String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom random=new java.security.SecureRandom();
        StringBuilder out=new StringBuilder();
        for(int i=0;i<6;i++)out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return out.toString();
    }
    private final Context context;
    private final TripStore store;
    private final ServerSocket server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24));
    private final Map<String, Long> joinAttempts = new HashMap<>();
    private final Object life = new Object();
    private volatile boolean closed;
    public volatile String status="연결 준비 중";
    public java.util.function.Supplier<JSONObject> telemetry=JSONObject::new;
    /** In-process only: never written to a page, response, or log. */
    final String driverToken=UUID.randomUUID().toString();
    public java.util.function.BooleanSupplier driverParked=()->false;
    private static final String STATIC_IMAGES="/vendor/images/(layers|layers-2x|marker-icon|marker-icon-2x|marker-shadow)\\.png";
    private static final String STATIC_ART="/art/(cheomseongdae|daereungwon|wolji|museum)\\.svg";
    private static final String MEDIA="/media/(photo|kids)/([A-Za-z0-9\\-]{1,80})\\.jpg";
    private static final String SPRITE="/sprites/(dog|cat)/(wave|point|walk|think|read|cheer|stargaze|photo|eat|surprise|sleep|listen)\\.png";
    private final MapService maps=new MapService();
    public volatile ContentService content;

    public FamilyServer(Context context,TripStore store) throws IOException {
        this.context=context;this.store=store;
        server=new ServerSocket(); server.setReuseAddress(true);server.bind(new InetSocketAddress(PORT));
        Thread accept=new Thread(() -> {
            while(!closed) {
                try {
                    Socket client=server.accept();
                    try {workers.execute(() -> handle(client));}
                    catch(RejectedExecutionException e){client.close();}
                } catch(IOException e) {if(!closed) Log.e("CabinStory","Local server",e);}
            }
        },"cabin-family");
        accept.setDaemon(true);accept.start();status="로컬 연결 준비됨";
    }

    private void handle(Socket socket) {
        try(Socket client=socket) {
          try {
            client.setSoTimeout(4000);
            InputStream in=client.getInputStream();OutputStream out=client.getOutputStream();
            String first=line(in,2048);
            String[] request=first.split(" ");
            if(request.length!=3 || !request[2].equals("HTTP/1.1")) {error(out,400,"잘못된 요청");return;}
            Map<String,String> headers=new HashMap<>();
            int total=first.length();
            while(true) {
                String h=line(in,4096);total+=h.length();
                if(total>8192) throw new IOException("Headers too large");
                if(h.isEmpty())break;
                int colon=h.indexOf(':');if(colon<1)throw new IOException("Header");
                String key=h.substring(0,colon).toLowerCase(Locale.ROOT);
                if(headers.put(key,h.substring(colon+1).trim())!=null)throw new IOException("Duplicate header");
            }
            String method=request[0],path=request[1];
            if(!Arrays.asList("GET","POST").contains(method) || headers.containsKey("transfer-encoding")) {error(out,405,"지원하지 않는 요청");return;}
            byte[] postedBody=new byte[0];
            if(method.equals("POST")) {
                int length;
                try{length=Integer.parseInt(headers.getOrDefault("content-length","-1"));}catch(NumberFormatException e){error(out,400,"요청 크기를 확인해 주세요.");return;}
                if(length<0 || length>4096) {error(out,413,"요청 크기를 확인해 주세요.");return;}
                postedBody=new byte[length];int offset=0;
                while(offset<length) {int n=in.read(postedBody,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
                String origin=headers.get("origin"), host=headers.get("host");
                if(origin!=null && !origin.equals("http://"+host)) {error(out,403,"같은 로컬 페이지에서만 변경할 수 있어요.");return;}
                if(!headers.getOrDefault("content-type","").startsWith("application/json")) {error(out,415,"JSON 요청이 필요합니다.");return;}
            }
            if(method.equals("GET") && (path.equals("/") || path.equals("/phone") || path.equals("/vehicle"))) {
                if(path.equals("/vehicle") && !localHost(client,headers)) {error(out,404,"페이지가 없습니다.");return;}
                asset(out,"phone.html","text/html; charset=utf-8");return;
            }
            if(method.equals("GET") && path.matches(MEDIA)) {
                ContentService media=content;
                java.util.regex.Matcher m=java.util.regex.Pattern.compile(MEDIA).matcher(path);
                if(media==null || !m.matches()) {error(out,404,"페이지가 없습니다.");return;}
                try(InputStream file=media.open(m.group(1),m.group(2));ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] b=new byte[16384];for(int n;(n=file.read(b))!=-1;)bytes.write(b,0,n);send(out,200,"image/jpeg",bytes.toByteArray());
                } catch(FileNotFoundException e){error(out,404,"이미지가 없습니다.");}
                return;
            }
            if(isStatic(path)) {
                if(!method.equals("GET")) {error(out,405,"지원하지 않는 요청");return;}
                asset(out,path.substring(1),type(path));return;
            }
            if(!Arrays.asList("/api/join","/api/state","/api/edit","/api/role","/api/audience","/api/search","/api/route").contains(path)) {error(out,404,"페이지가 없습니다.");return;}
            try {
                if(method.equals("GET")) {
                    if(!path.equals("/api/state")) {error(out,405,"POST 요청이 필요합니다.");return;}
                    boolean driver=isDriver(client,headers);
                    String member=driver?"driver":store.memberFor(token(headers));
                    JSONObject data=store.snapshot();
                    if(driver) data.put("memberId","driver").put("role","driver");
                    else data.put("memberId",member).put("role",roleOf(data,member)).put("audience",fieldOf(data,member,"audience","general"));
                    data.put("vehicle",telemetry.get());
                    send(out,200,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8));return;
                }
                JSONObject data=new JSONObject(new String(postedBody,StandardCharsets.UTF_8));
                if(path.equals("/api/state")) {error(out,405,"GET 요청이 필요합니다.");return;}
                if(path.equals("/api/join")) {
                    String remote=client.getInetAddress().getHostAddress();long now=System.currentTimeMillis();
                    synchronized(joinAttempts) {
                        if(now-joinAttempts.getOrDefault(remote,0L)<1000) {error(out,429,"잠시 뒤 다시 연결해 주세요.");return;}
                        if(joinAttempts.size()>128)joinAttempts.clear();
                        joinAttempts.put(remote,now);
                    }
                    if(!invitation.equalsIgnoreCase(data.optString("invitation").trim())) throw new SecurityException("차량의 초대 링크를 확인해 주세요.");
                    JSONObject joined;
                    synchronized(life) {
                        if(closed) throw new SecurityException("가족 연결 서버가 종료되었습니다.");
                        joined=store.join(data.getString("name"));
                    }
                    send(out,200,"application/json",joined.toString().getBytes(StandardCharsets.UTF_8));return;
                }
                if(path.equals("/api/role")) {
                    if(!isDriver(client,headers)) throw new SecurityException("권한 변경은 정차한 차량에서만 할 수 있습니다.");
                    store.role(data.getString("memberId"),data.getString("role"),driverParked.getAsBoolean());
                    send(out,200,"application/json","{\"ok\":true}".getBytes(StandardCharsets.UTF_8));return;
                }
                if(path.equals("/api/audience")) {
                    if(!isDriver(client,headers)) throw new SecurityException("안내 유형은 정차한 차량에서만 바꿀 수 있습니다.");
                    store.audience(data.getString("memberId"),data.getString("audience"),driverParked.getAsBoolean());
                    send(out,200,"application/json","{\"ok\":true}".getBytes(StandardCharsets.UTF_8));return;
                }
                if(path.equals("/api/search")) {
                    actor(client,headers);
                    Double lat=data.isNull("latitude")?null:data.optDouble("latitude",Double.NaN);
                    Double lon=data.isNull("longitude")?null:data.optDouble("longitude",Double.NaN);
                    JSONArray places=maps.search(data.optString("query",""),lat,lon);
                    send(out,200,"application/json; charset=utf-8",
                        new JSONObject().put("places",places).toString().getBytes(StandardCharsets.UTF_8));return;
                }
                if(path.equals("/api/route")) {
                    actor(client,headers);
                    JSONObject snapshot=store.snapshot();
                    String tripId=data.optString("tripId","");
                    int routeRevision=data.optInt("routeRevision",-1);
                    if(snapshot.optString("tripId").equals(tripId) && "completed".equals(snapshot.optString("status"))) {
                        error(out,409,"완료된 여행은 저장된 경로를 사용해요.");return;
                    }
                    if(!snapshot.optString("tripId").equals(tripId) || snapshot.optInt("routeRevision",-1)!=routeRevision) {
                        error(out,409,"일정이 바뀌었어요. 최신 일정으로 다시 요청해 주세요.");return;
                    }
                    JSONObject route=maps.route(snapshot);
                    if(closed) {error(out,503,"가족 연결이 종료되었습니다.");return;}
                    if(!store.cacheRoute(tripId,routeRevision,route)) {
                        JSONObject latest=store.snapshot();
                        if(latest.optString("tripId").equals(tripId) && "completed".equals(latest.optString("status")))
                            error(out,409,"완료된 여행은 저장된 경로를 사용해요.");
                        else if(latest.optString("tripId").equals(tripId) && latest.optInt("routeRevision",-1)==routeRevision)
                            error(out,503,"경로를 저장하지 못했어요. 다시 시도해 주세요.");
                        else error(out,409,"일정이 바뀌었어요. 최신 일정으로 다시 요청해 주세요.");
                        return;
                    }
                    send(out,200,"application/json; charset=utf-8",new JSONObject().put("route",route)
                        .put("tripId",tripId).put("routeRevision",routeRevision).toString().getBytes(StandardCharsets.UTF_8));return;
                }
                String actor=actor(client,headers);
                store.mutate(actor,data,"driver".equals(actor) && driverParked.getAsBoolean());
                send(out,200,"application/json","{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } catch(SecurityException e){error(out,403,e.getMessage());}
              catch(MapService.ServiceException e){error(out,e.status,e.getMessage());}
              catch(ConcurrentModificationException e){error(out,409,e.getMessage());}
              catch(IllegalStateException e){error(out,400,e.getMessage());}
              catch(JSONException|IllegalArgumentException e){error(out,400,e.getMessage());}
              catch(Exception e){Log.e("CabinStory","Request failed",e);error(out,500,"변경을 저장하지 못했어요. 다시 시도해 주세요.");}
          } finally { linger(client); }
        } catch(IOException e){if(!closed)Log.w("CabinStory","Local connection ended: "+e.getClass().getSimpleName());}
    }

    /** Wait briefly for the peer to finish reading and close first, so relays never cut a response short. */
    private static void linger(Socket client) {
        try {
            client.setSoTimeout(2000);
            InputStream in=client.getInputStream();
            byte[] sink=new byte[512];
            for(int total=0,n;total<65536 && (n=in.read(sink))!=-1;)total+=n;
        } catch(IOException ignored) {}
    }
    private static String token(Map<String,String> headers) {
        String value=headers.getOrDefault("authorization","");
        if(!value.startsWith("Bearer ")) throw new SecurityException("가족 연결이 필요합니다.");
        return value.substring(7);
    }
    /** Native driver authority: secret, loopback peer, and the exact local host, all three. */
    private boolean isDriver(Socket client,Map<String,String> headers) {
        String value=headers.getOrDefault("authorization","");
        return !closed && value.startsWith("Bearer ") && value.substring(7).equals(driverToken)
            && localHost(client,headers);
    }
    private String actor(Socket client,Map<String,String> headers) {
        return isDriver(client,headers)?"driver":store.memberFor(token(headers));
    }
    private static boolean localHost(Socket client,Map<String,String> headers) {
        return ("127.0.0.1:"+PORT).equals(headers.get("host")) && client.getInetAddress().isLoopbackAddress();
    }
    private static String fieldOf(JSONObject snapshot,String member,String key,String fallback) {
        JSONArray members=snapshot.optJSONArray("members");
        if(members!=null) for(int i=0;i<members.length();i++) {
            JSONObject entry=members.optJSONObject(i);
            if(entry!=null && member.equals(entry.optString("id"))) return entry.optString(key,fallback);
        }
        return fallback;
    }
    private static String roleOf(JSONObject snapshot,String member) {
        JSONArray members=snapshot.optJSONArray("members");
        if(members!=null) for(int i=0;i<members.length();i++) {
            JSONObject entry=members.optJSONObject(i);
            if(entry!=null && member.equals(entry.optString("id"))) return entry.optString("role","viewer");
        }
        return "viewer";
    }
    private static boolean isStatic(String path) {
        return Arrays.asList("/phone.js","/phone.css","/map.js","/vendor/leaflet.js","/vendor/leaflet.css").contains(path)
            || path.matches(STATIC_IMAGES) || path.matches(STATIC_ART) || path.matches(SPRITE);
    }
    private static String type(String path) {
        if(path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if(path.endsWith(".css")) return "text/css; charset=utf-8";
        if(path.endsWith(".png")) return "image/png";
        return "image/svg+xml";
    }
    private void asset(OutputStream out,String path,String type) throws IOException {
        try(InputStream in=context.getAssets().open(path);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)bytes.write(b,0,n);send(out,200,type,bytes.toByteArray());
        } catch(FileNotFoundException e){error(out,404,"페이지가 없습니다.");}
    }
    private static String line(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int b;(b=in.read())!=-1;) {
            if(b=='\n')return bytes.toString(StandardCharsets.US_ASCII.name()).replace("\r","");
            if(bytes.size()>=limit)throw new IOException("Line too large");bytes.write(b);
        }
        throw new EOFException();
    }
    private static void error(OutputStream out,int code,String message) throws IOException {
        JSONObject error=new JSONObject();try{error.put("error",message);}catch(JSONException impossible){throw new IOException(impossible);}
        send(out,code,"application/json; charset=utf-8",error.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void send(OutputStream out,int code,String type,byte[] body) throws IOException {
        String headers="HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: "+type+
            "\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n"+
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: strict-origin-when-cross-origin\r\n"+
            "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self'; object-src 'none'; frame-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    public String localAddress() {
        try {
            for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces()))
                for(InetAddress ip:Collections.list(nic.getInetAddresses()))
                    if(!ip.isLoopbackAddress() && ip instanceof Inet4Address)return "http://"+ip.getHostAddress()+":"+PORT;
        }catch(SocketException ignored){}
        return "http://127.0.0.1:"+PORT;
    }
    @Override public void close() throws IOException {
        synchronized(life) {
            if(closed)return;
            closed=true;
            driverParked=()->false;
            store.disconnectAll();
        }
        try{server.close();} finally {workers.shutdownNow();}
    }
}

package kr.cabinstory;

import android.content.Context;
import android.os.*;
import android.util.Log;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.navi.data.*;
import ai.pleos.playground.navi.constants.*;
import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.listener.GearSelectionListener;
import kotlin.Unit;
import org.json.*;
import java.util.*;

public final class VehicleBridge {
    private final Handler main=new Handler(Looper.getMainLooper());
    private NaviHelper navi;
    private FusedLocation location;
    private Vehicle vehicle;
    private volatile boolean closed,naviReady;
    private final Context context;
    private volatile long gearAt,locationAt;
    private volatile VehicleGear gear=VehicleGear.GEAR_UNKNOWN;
    private volatile JSONObject target,requestedDestination;
    private volatile String requested="",triggered="";
    private volatile int requestedCount,requestedTotal,requestedRevision=-1;
    private volatile long requestedAt;
    public volatile Boolean demoParked=null;
    public volatile boolean autoDocent=true;
    public volatile String naviStatus="내비 연결 준비 중",locationStatus="위치 수신 대기",gearStatus="차량 상태 확인 중";
    public volatile String event="자동 안내 대기 · 실제 이벤트 수신 전";
    public volatile Runnable onChange=()->{};
    public volatile java.util.function.Consumer<String> onApproach=id->{};
    /** Navigation reported arrival at the selected stop. */
    public volatile java.util.function.Consumer<String> onArrived=id->{};
    public void demoArrive(){
        if(!emulator || demoParked==null)throw new SecurityException("에뮬레이터 모의 시연 모드에서만 실행할 수 있습니다.");
        JSONObject current=target;
        if(current==null)throw new IllegalStateException("선택된 방문지가 없어요.");
        event="모의 시연 · 도착 버튼 입력 (실제 차량 이벤트 아님)";changed();onArrived.accept(current.optString("id"));
    }
    /** Navi is the source of truth for places: (placeId, navi place) after the navigation resolved a request. */
    public volatile java.util.function.BiConsumer<String,JSONObject> onNaviResolved=(id,place)->{};
    private volatile List<JSONObject> requestedBatch=Collections.emptyList();
    public volatile JSONObject naviDestination;
    private volatile boolean naviTerms=true;
    private volatile String naviMode="";
    public volatile JSONArray naviRecent=new JSONArray();

    // ---- remaining time / distance to the selected stop: navigation first, GPS estimate second, demo simulation in demo mode
    private volatile int remainSec=-1,remainMeters=-1,startSec=-1,startMeters=-1;
    private volatile long remainAt;
    private volatile String remainSource="";
    private volatile boolean demoDriving,demoApproached;
    private boolean fresh(){return remainAt!=0 && SystemClock.elapsedRealtime()-remainAt<90000;}
    public int remainSec(){return fresh()?remainSec:-1;}
    public int remainMeters(){return fresh()?remainMeters:-1;}
    public int startSec(){return startSec;}
    public int startMeters(){return startMeters;}
    /** "navi" (navigation SDK), "gps" (straight-line estimate), "demo" (emulator simulation) or "". */
    public String remainSource(){return fresh()?remainSource:"";}
    public boolean demoDriving(){return demoDriving;}
    private void setRemain(String source,int sec,int meters){
        if(target==null || sec<0 || meters<0)return;
        if((demoParked!=null)!="demo".equals(source))return;
        if("gps".equals(source) && "navi".equals(remainSource) && fresh())return;
        int oldMin=remainSec<0?-1:(remainSec+59)/60;String oldSource=remainSource;
        remainSec=sec;remainMeters=meters;remainSource=source;remainAt=SystemClock.elapsedRealtime();
        if(!source.equals(oldSource)){startSec=sec;startMeters=meters;}   // a new source measures differently: restart the progress
        if(sec>startSec)startSec=sec;
        if(meters>startMeters)startMeters=meters;
        if(oldMin!=(sec+59)/60 || !source.equals(oldSource))changed();
    }
    private void clearRemain(){remainSec=-1;remainMeters=-1;startSec=-1;startMeters=-1;remainAt=0;remainSource="";}
    private static boolean same(String a,String b){
        if(a==null || b==null)return false;
        String x=a.replace(" ",""),y=b.replace(" ","");
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }
    /** Emulator only: plays a whole leg (set off, approach, arrive) in about two minutes. */
    public void demoDrive(){
        if(!emulator || demoParked==null)throw new SecurityException("에뮬레이터 모의 시연 모드에서만 실행할 수 있습니다.");
        if(target==null)throw new IllegalStateException("선택된 방문지가 없어요.");
        demoParked=false;demoDriving=true;demoApproached=false;clearRemain();
        event="모의 시연 · 이동 시작 (실제 차량 이벤트 아님)";
        setRemain("demo",600,4200);changed();
        main.removeCallbacks(demoStep);main.postDelayed(demoStep,1000);
    }
    private final Runnable demoStep=new Runnable(){@Override public void run(){
        JSONObject current=target;
        if(closed || !demoDriving || !Boolean.FALSE.equals(demoParked) || current==null){demoDriving=false;return;}
        int next=remainSec-5;
        if(next<=0){
            demoDriving=false;setRemain("demo",0,0);demoParked=true;
            event="모의 시연 · 도착 (실제 차량 이벤트 아님)";changed();onArrived.accept(current.optString("id"));return;
        }
        setRemain("demo",next,next*7);
        if(next<=40 && !demoApproached){
            demoApproached=true;event="모의 시연 · 250m 이내 접근 (실제 차량 이벤트 아님)";changed();
            if(autoDocent)onApproach.accept(current.optString("id"));
        }
        main.postDelayed(this,1000);
    }};
    private final Runnable pollRemain=new Runnable(){@Override public void run(){
        if(closed)return;
        if(naviReady && navi!=null && demoParked==null && target!=null && naviMode.contains("ROUTE_GUIDING")){
            try{navi.getDestinationInfo();navi.getWaypointInfo();}catch(Exception ignored){}
        }
        main.postDelayed(this,20000);
    }};

    private static JSONObject naviPlace(String name,String address,double lat,double lon,String poiId) {
        JSONObject out=new JSONObject();
        try{out.put("name",name==null?"":name).put("address",address==null?"":address).put("latitude",lat).put("longitude",lon)
            .put("poiId",poiId==null?"":poiId).put("source","navi");}catch(JSONException ignored){}
        return out;
    }
    public boolean naviReady(){return naviReady && navi!=null && demoParked==null;}
    /** Opens the vehicle navigation's own search so the driver picks the navi POI. */
    public void openNaviSearch(String keyword) {
        if(!parked())throw new SecurityException("정차 확인 후 이용해 주세요.");
        if(!naviReady())throw new IllegalStateException("실제 내비 연결 후 이용할 수 있어요.");
        navi.requestNavigationScreen(new NavigationScreenRequest.SearchPlace(keyword==null?"":keyword.trim()));
    }
    public void refreshNaviPlaces() {
        if(!naviReady || navi==null)return;
        try{navi.getRecentDestinationInfo();navi.getDestinationInfo();}catch(Exception e){Log.w("CabinStory","Navi places",e);}
    }
    public final boolean emulator=Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish");

    public VehicleBridge(Context context) {
        this.context=context.getApplicationContext();
        try {
            vehicle=new Vehicle(context.getApplicationContext());vehicle.initialize();vehicle.getGear().registerGearSelection(gearListener);
            pollGear.run();
        }catch(Exception e){gearStatus="주차 신호 미지원 · 편집 제한";Log.w("CabinStory","Vehicle",e);}
        try {
            navi=new NaviHelper(context.getApplicationContext());
            navi.initialize(()->{main.post(()->{
                if(closed)return;naviReady=true;navi.addListener(naviListener);naviStatus="NaviHelper 연결됨 · 경로 미요청";
                navi.getDestinationInfo();main.postDelayed(pollRemain,20000);changed();
            });return Unit.INSTANCE;},error->{main.post(()->{naviStatus="내비 연결 실패 · 모의 시연 가능";changed();});return Unit.INSTANCE;});
        }catch(Exception e){naviStatus="NaviHelper 사용 불가";Log.w("CabinStory","Navi",e);}
        startLocation();
    }
    public void startLocation(){
        if(context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION")!=android.content.pm.PackageManager.PERMISSION_GRANTED){locationStatus="위치 접근 권한 필요 · 진단 화면에서 허용";changed();return;}
        try {
            if(location!=null)location.release();
            location=new FusedLocation(context);
            if(location.isSupported()){location.initialize();location.registerFusedLocationCallback(locationListener);location.getLastKnownLocation(locationListener);}
            else locationStatus="FusedLocation 미지원";
        }catch(Exception e){locationStatus="위치 접근 불가";Log.w("CabinStory","Location",e);}
    }
    public boolean parked(){
        if(demoParked!=null && emulator)return demoParked;
        return gear==VehicleGear.GEAR_PARK && SystemClock.elapsedRealtime()-gearAt<6000;
    }
    public String safetyLabel(){return demoParked!=null?"모의 시연 · "+(parked()?"정차":"주행"):parked()?"차량 P단 확인":"주행 또는 상태 미확인 · 편집 제한";}
    public void setTarget(JSONObject place){
        if(place==null){target=null;triggered="";clearRemain();return;}
        JSONObject current=target;
        if(current==null || !current.optString("id").equals(place.optString("id"))){triggered="";if(!demoDriving)clearRemain();}
        target=place;
    }
    public void requestRoute(JSONObject place,int revision) {
        if(place==null)throw new IllegalArgumentException("목적지를 선택해 주세요.");
        requestRoute(Collections.singletonList(place),1,revision);
    }
    public void requestRoute(List<JSONObject> destinations,int revision) {
        requestRoute(destinations,destinations==null?0:destinations.size(),revision);
    }
    public void requestRoute(List<JSONObject> destinations,int totalRemainingCount,int revision) {
        if(!parked())throw new SecurityException("정차 확인 후 요청해 주세요.");
        if(demoParked!=null)throw new IllegalStateException("모의 시연에서는 실제 내비를 변경하지 않습니다. 실제 SDK 모드로 전환해 주세요.");
        if(!naviReady || navi==null)throw new IllegalStateException("내비 연결을 기다려 주세요.");
        if(destinations==null || destinations.isEmpty() || destinations.size()>3)
            throw new IllegalArgumentException("목적지는 1~3곳만 요청할 수 있습니다.");
        for(JSONObject item:destinations){
            if(item==null)throw new IllegalArgumentException("유효한 목적지 정보가 필요합니다.");
            double lat=item.optDouble("latitude",Double.NaN),lon=item.optDouble("longitude",Double.NaN);
            String name=item.optString("name","").trim();
            if(!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90.0 || Math.abs(lon)>180.0 || name.isEmpty())
                throw new IllegalArgumentException("유효한 목적지 좌표와 이름이 필요합니다.");
        }
        int size=destinations.size();
        int total=Math.max(size,totalRemainingCount);
        JSONObject finalPlace=destinations.get(size-1);
        JSONObject destSnapshot;
        try{destSnapshot=new JSONObject(finalPlace.toString());}
        catch(JSONException e){throw new IllegalArgumentException("목적지 정보를 읽을 수 없습니다.");}
        long sentAt=SystemClock.elapsedRealtime();
        requestedDestination=destSnapshot;requested=finalPlace.optString("id","");
        requestedBatch=new ArrayList<>(destinations);
        requestedCount=size;requestedTotal=total;requestedRevision=revision;requestedAt=sentAt;
        String countNote=total>size?" ("+size+"곳 / 전체 "+total+"곳 중)":size>1?" ("+size+"곳)":"";
        naviStatus="내비 경로 요청 전송"+countNote+" · 완료 콜백 대기";changed();
        // The published Java example swaps coordinates; the actual 3.0.5 bytecode uses latitude first.
        RouteInfo destinationInfo=new RouteInfo(finalPlace.optDouble("latitude"),finalPlace.optDouble("longitude"),null,null,
            finalPlace.optString("name","").trim(),"",finalPlace.optString("address",""),"",RouteOption.RECOMMENDED,"",false);
        if(size>=2){
            List<RequestWaypointInfo> waypoints=new ArrayList<>(size-1);
            WaypointIndex[] indices={WaypointIndex.FIRST,WaypointIndex.SECOND};
            for(int i=0;i<size-1;i++){
                JSONObject wp=destinations.get(i);
                waypoints.add(new RequestWaypointInfo(wp.optDouble("latitude"),wp.optDouble("longitude"),
                    wp.optString("name","").trim(),indices[i],"",wp.optString("address",""),"",""));
            }
            navi.requestRouteWithWaypoints(new RouteWithWaypointsRequest(destinationInfo,waypoints));
        }else navi.requestRoute(destinationInfo);
        main.postDelayed(()->{
            if(!closed && requestedAt==sentAt && naviStatus.startsWith("내비 경로 요청 전송")){naviStatus="내비 완료 미수신 · 적용 확인 안 됨";changed();}
        },12000);
    }
    private final NaviHelperEventListener naviListener=new NaviHelperEventListener(){
        @Override public void onRouteStateInfo(RouteStateInfo info){main.post(()->{
            if(info==null)return;
            naviTerms=info.getTermsAgreement();naviMode=String.valueOf(info.getNaviMode());
            Log.i("CabinStory","Navi state terms="+naviTerms+" mode="+naviMode);
            // Guidance began for our request: ask the navigation what it actually resolved.
            if(naviMode.contains("ROUTE_GUIDING") && !requested.isEmpty() && SystemClock.elapsedRealtime()-requestedAt<60000){
                naviStatus="내비 안내 시작 · 목적지 확인 중";changed();
                try{navi.getDestinationInfo();if(requestedBatch.size()>1)navi.getWaypointInfo();}catch(Exception ignored){}
            }
        });}
        @Override public void onDrivingInfo(DrivingInfo info){main.post(()->{
            JSONObject t=target;
            if(info==null || t==null)return;
            String name=t.optString("name");DrivingState pick=null;
            List<DrivingState> ws=info.getWaypoints();
            if(ws!=null)for(DrivingState w:ws)if(w!=null && same(w.getName(),name)){pick=w;break;}
            DrivingState dest=info.getDestination();
            if(pick==null && dest!=null && same(dest.getName(),name))pick=dest;
            // The selected stop is the next one on a route we asked for: the first leg of the navigation.
            if(pick==null && !requested.isEmpty())pick=ws!=null && !ws.isEmpty() && ws.get(0)!=null?ws.get(0):dest;
            if(pick==null)return;
            Log.i("CabinStory","Navi driving "+pick.getName()+" duration="+pick.getDuration()+" distance="+pick.getDistance());
            setRemain("navi",pick.getDuration(),pick.getDistance());
        });}
        @Override public void onRouteEnded(){main.post(()->{
            if(!requested.isEmpty() && SystemClock.elapsedRealtime()-requestedAt<30000 && naviStatus.startsWith("내비 경로 요청 전송") && !naviMode.contains("ROUTE_GUIDING")){
                naviStatus=(naviTerms?"내비가 경로를 바로 종료함":"내비 이용 약관 동의가 필요해 보여요")+" · 적용 확인 안 됨 (모드 "+naviMode+")";changed();
            }
        });}
        // The emulator navigation reports onRouteStarted (not onRoutingCompleted) for an accepted request.
        @Override public void onRouteStarted(RouteStartInfo info){onRoutingCompleted();}
        @Override public void onRoutingCompleted(){main.post(()->{
            if(requested.isEmpty() || SystemClock.elapsedRealtime()-requestedAt>30000){naviStatus="다른 경로 완료 이벤트 수신";changed();return;}
            naviStatus="경로 완료 이벤트 수신 · 목적지 확인 중";navi.getDestinationInfo();
            if(requestedBatch.size()>1)try{navi.getWaypointInfo();}catch(Exception ignored){}
            changed();
        });}
        @Override public void onDestinationChanged(){main.post(()->{try{navi.getDestinationInfo();}catch(Exception ignored){}});}
        @Override public void onRecentDestinationInfo(List<RecentDestinationInfo> list){main.post(()->{
            JSONArray out=new JSONArray();
            if(list!=null)for(RecentDestinationInfo r:list){
                if(out.length()>=8)break;
                if(r==null || r.getName()==null || r.getName().trim().isEmpty())continue;
                out.put(naviPlace(r.getName(),r.getAddress(),r.getLatitude(),r.getLongitude(),r.getPoiId()));
            }
            naviRecent=out;changed();
        });}
        @Override public void onWayPointInfo(List<WaypointInfo> list){main.post(()->{
            List<JSONObject> batch=requestedBatch;
            JSONObject heading=target;
            if(list!=null && heading!=null)for(WaypointInfo w:list)
                if(w!=null && w.getRemainTime()>0 && distance(w.getLatitude(),w.getLongitude(),heading.optDouble("latitude"),heading.optDouble("longitude"))<300){
                    Log.i("CabinStory","Navi waypoint remain time="+w.getRemainTime()+" distance="+w.getRemainDistance());
                    setRemain("navi",w.getRemainTime(),w.getRemainDistance());break;
                }
            if(list==null || batch.size()<2 || SystemClock.elapsedRealtime()-requestedAt>60000)return;
            for(int i=0;i<list.size() && i<batch.size()-1;i++){
                WaypointInfo w=list.get(i);JSONObject asked=batch.get(i);
                if(w==null || distance(w.getLatitude(),w.getLongitude(),asked.optDouble("latitude"),asked.optDouble("longitude"))>2000)continue;
                onNaviResolved.accept(asked.optString("id"),naviPlace(w.getName(),w.getAddress(),w.getLatitude(),w.getLongitude(),w.getPoiId()));
            }
        });}
        @Override public void onDestinationInfo(DestinationInfo info){main.post(()->{
            naviDestination=info==null || info.getName()==null || info.getName().trim().isEmpty()?null
                :naviPlace(info.getName(),info.getAddress(),info.getLatitude(),info.getLongitude(),info.getPoiId());
            JSONObject heading=target;
            if(info!=null && heading!=null && info.getRemainTime()>0
                && distance(info.getLatitude(),info.getLongitude(),heading.optDouble("latitude"),heading.optDouble("longitude"))<300){
                Log.i("CabinStory","Navi destination remain time="+info.getRemainTime()+" distance="+info.getRemainDistance());
                setRemain("navi",info.getRemainTime(),info.getRemainDistance());
            }
            JSONObject asked=requestedDestination;
            if(info!=null && asked!=null && !requested.isEmpty() && SystemClock.elapsedRealtime()-requestedAt<60000
                && distance(info.getLatitude(),info.getLongitude(),asked.optDouble("latitude"),asked.optDouble("longitude"))<2000)
                onNaviResolved.accept(requested,naviDestination==null?naviPlace(asked.optString("name"),"",info.getLatitude(),info.getLongitude(),info.getPoiId()):naviDestination);
            JSONObject dest=requestedDestination;
            if(info!=null && dest!=null && !requested.isEmpty() &&
                distance(info.getLatitude(),info.getLongitude(),dest.optDouble("latitude"),dest.optDouble("longitude"))<200){
                String suffix=requestedTotal>requestedCount?" ("+requestedCount+"곳 / 전체 "+requestedTotal+"곳 중)":requestedCount>1?" ("+requestedCount+"곳)":"";
                naviStatus="선택 목적지 확인됨 · 요청 일정 v"+requestedRevision+suffix;
            } else naviStatus="내비 정보 수신 · 선택 장소와 연결 미확인";
            Log.i("CabinStory","Navi destination "+(info==null?"null":info.getName()+" @"+info.getLatitude()+","+info.getLongitude())+" -> "+naviStatus);
            changed();
        });}
        @Override public void onWayPointArrived(WaypointArrivedInfo info){main.post(()->{
            JSONObject current=target;
            String arrivedName=info==null || info.getName()==null?"":info.getName();
            if(current!=null && !arrivedName.isEmpty() && arrivedName.equals(current.optString("name"))) {trigger("NaviHelper 경유지 도착 이벤트");onArrived.accept(current.optString("id"));}
            else {event=arrivedName.isEmpty()?"다른 경유지 도착 이벤트 · 무시":"경유지 도착 이벤트 · "+arrivedName;changed();}
        });}
        @Override public void onDestinationArrived(DestinationArrivedInfo info){main.post(()->{
            JSONObject current=target;
            if(current!=null && info!=null && current.optString("name").equals(info.getName())) {trigger("NaviHelper 실제 도착 이벤트");onArrived.accept(current.optString("id"));}
            else {event="다른 목적지 도착 이벤트 · 무시";changed();}
        });}
        @Override public void onRouteCancelled(){main.post(()->{
            // Replacing an existing route cancels the old one first; that is not a cancel of our new request.
            if(!requested.isEmpty() && SystemClock.elapsedRealtime()-requestedAt<20000){Log.i("CabinStory","Navi cancel ignored (route being replaced)");return;}
            requested="";requestedDestination=null;requestedCount=0;requestedTotal=0;naviStatus="내비 경로 취소됨";changed();});}
        @Override public void onError(NaviErrorCode error){main.post(()->{naviStatus="내비 오류 · "+error;changed();});}
    };
    private final FusedLocationListener locationListener=new FusedLocationListener(){
        @Override public void onChanged(LocationData data){main.post(()->{
            if(data==null){locationStatus="위치 데이터 없음";changed();return;}
            long age=Math.abs(System.currentTimeMillis()-data.getTimestampMillis());
            locationAt=SystemClock.elapsedRealtime();
            locationStatus=String.format(Locale.KOREA,"실제 위치 %.4f, %.4f%s",data.getLatitude(),data.getLongitude(),age>30000?" · 오래된 위치":"");
            if(target!=null && age<=30000 && demoParked==null && distance(data.getLatitude(),data.getLongitude(),target.optDouble("latitude"),target.optDouble("longitude"))<250)
                trigger("FusedLocation 실제 위치 · 250m 이내 접근");
            if(target!=null && age<=30000 && demoParked==null){
                // Fallback without a navigation route: straight line x1.3 at about 40 km/h. Shown as an estimate.
                double meters=distance(data.getLatitude(),data.getLongitude(),target.optDouble("latitude"),target.optDouble("longitude"))*1.3;
                if(meters<=300000)setRemain("gps",(int)(meters/11.1),(int)meters);   // farther than a day trip: not a usable estimate
            }
            changed();
        });}
        @Override public void onError(Exception e){main.post(()->{locationStatus="위치 수신 오류 · "+e.getClass().getSimpleName();changed();});}
    };
    private final GearSelectionListener gearListener=new GearSelectionListener(){
        @Override public void onSelected(VehicleGear selected){main.post(()->updateGear(selected));}
        @Override public void onFailed(Exception e){main.post(()->setGearError("주차 신호 미확인 · 편집 제한"));}
    };
    private final Runnable pollGear=new Runnable(){@Override public void run(){
        if(closed)return;
        try{if(vehicle!=null)vehicle.getGear().getCurrentGearSelectionState(value->{main.post(()->updateGear(value));return Unit.INSTANCE;},error->{main.post(()->setGearError("주차 신호 접근 불가"));return Unit.INSTANCE;});}
        catch(Exception e){setGearError("주차 신호 접근 불가");}
        main.postDelayed(this,2000);
    }};
    private void setGearError(String msg){boolean wasParked=parked();gearAt=0;boolean diff=!msg.equals(gearStatus) || wasParked!=parked();gearStatus=msg;if(diff)changed();}
    private void updateGear(VehicleGear value){
        boolean wasParked=parked();gear=value==null?VehicleGear.GEAR_UNKNOWN:value;gearAt=SystemClock.elapsedRealtime();
        String next="Vehicle · "+gear;boolean diff=!next.equals(gearStatus) || wasParked!=parked();gearStatus=next;if(diff)changed();
    }
    public void demoApproach(){if(!emulator || demoParked==null)throw new SecurityException("에뮬레이터 모의 시연 모드에서만 실행할 수 있습니다.");event="모의 시연 · 접근 버튼 입력 (실제 차량 이벤트 아님)";changed();if(target!=null && autoDocent)onApproach.accept(target.optString("id"));}
    private void trigger(String source){
        if(target==null || closed || demoParked!=null)return;
        String id=target.optString("id");if(id.equals(triggered))return;triggered=id;
        event=source;Log.i("CabinStory",event+" id="+id);changed();if(autoDocent)onApproach.accept(id);
    }
    public JSONObject telemetry(){
        JSONObject out=new JSONObject();try{out.put("safety",safetyLabel()).put("parked",parked()).put("navi",naviStatus).put("location",locationStatus).put("event",event).put("auto",autoDocent).put("locationAgeMs",locationAt==0?-1:SystemClock.elapsedRealtime()-locationAt);}catch(JSONException ignored){}return out;
    }
    private void changed(){if(!closed)onChange.run();}
    static double distance(double lat,double lon,double lat2,double lon2){
        double dlat=Math.toRadians(lat2-lat),dlon=Math.toRadians(lon2-lon);
        double a=Math.pow(Math.sin(dlat/2),2)+Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(lat2))*Math.pow(Math.sin(dlon/2),2);
        return 6371000*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));
    }
    public void release(){closed=true;main.removeCallbacksAndMessages(null);
        try{if(navi!=null){if(naviReady)navi.removeListener(naviListener);navi.release();}}catch(Exception e){Log.w("CabinStory","Navi release",e);}
        try{if(location!=null){location.unregisterFusedLocationCallback(locationListener);location.release();}}catch(Exception e){Log.w("CabinStory","Location release",e);}
        try{if(vehicle!=null){vehicle.getGear().unregisterGearSelection(gearListener);vehicle.release();}}catch(Exception e){Log.w("CabinStory","Vehicle release",e);}
    }
}

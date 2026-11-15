package kr.cabinstory;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The vehicle is the single writer; stale edits are rejected instead of merged. */
public final class TripStore {
    private final SharedPreferences prefs;
    private final JSONObject catalog;
    private JSONObject trip;
    private JSONArray archive = new JSONArray();
    private final Map<String, JSONObject> members = new LinkedHashMap<>();
    private final Map<String, String> sessions = new HashMap<>();
    private final Map<String, JSONObject> proposals = new LinkedHashMap<>();
    public String loadWarning = "";
    public Runnable onChange = () -> {};
    /** (who, place name) after a family device puts a place on the list. */
    public java.util.function.BiConsumer<String,String> onAdded = (who, place) -> {};
    /** (kind, placeId) -> true when a photo/kids image is available to serve. */
    public java.util.function.BiPredicate<String,String> media = (kind, id) -> false;
    private JSONObject content = new JSONObject();
    private String voice = "female";

    public TripStore(Context context, String storage) throws Exception {
        prefs = context.getSharedPreferences(storage, Context.MODE_PRIVATE);
        try (InputStream in = context.getAssets().open("places.json"); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;)bytes.write(buffer,0,n);
            catalog = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        String saved = prefs.getString("trip", null);
        boolean savedValid = false;
        if (saved != null) {
            JSONObject parsed = null;
            try { parsed = new JSONObject(saved); migrate(parsed); validate(parsed); }
            catch (Exception e) { parsed = null; }
            if (parsed != null) { trip = parsed; savedValid = true; }
            else {
                prefs.edit().putString("recovery", saved).commit();
                loadWarning = "저장 일정을 읽지 못해 복구 사본을 보관하고 기본 여행을 열었습니다.";
                trip = initial();
            }
        } else trip = initial();
        loadArchive(savedValid);
        try { content = new JSONObject(prefs.getString("content", "{}")); } catch (Exception e) { content = new JSONObject(); }
        voice = "male".equals(prefs.getString("voice", "female")) ? "male" : "female";
    }

    /** Quizzes, window games, stretches and the serial story used on any leg. */
    public JSONObject program() { JSONObject p = catalog.optJSONObject("program"); return p == null ? new JSONObject() : p; }

    /** Voice drives the guide character: male voice = dog (보리), female voice = cat (나비). */
    public synchronized String voice() { return voice; }
    public synchronized void setVoice(String value) {
        String next = "male".equals(value) ? "male" : "female";
        if (next.equals(voice)) return;
        voice = next; prefs.edit().putString("voice", voice).apply(); onChange.run();
    }
    public static String guideName(String voice) { return "male".equals(voice) ? "보리" : "나비"; }

    /** Generated docent text for places that have no bundled script. */
    public synchronized boolean hasContent(String placeId) { return content.has(placeId); }
    /** Which story kit specification wrote a place's generated content; 0 for the short fallback or older content. */
    public synchronized int contentKit(String placeId) { JSONObject c = content.optJSONObject(placeId); return c == null ? 0 : c.optInt("kit", 0); }
    public synchronized void putContent(String placeId, JSONObject value) {
        try { content.put(placeId, value); prefs.edit().putString("content", content.toString()).apply(); } catch (JSONException ignored) {}
        onChange.run();
    }
    public synchronized void mediaChanged() { onChange.run(); }

    private JSONObject decorate(JSONObject place) throws JSONException {
        String id = place.getString("id");
        JSONObject extra = content.optJSONObject(id);
        if (extra != null && !place.optBoolean("hasDocent", false)) {
            JSONArray adult = extra.optJSONArray("adult"), kids = extra.optJSONArray("kids");
            if (adult != null && adult.length() > 0) {
                place.put("adult", new JSONArray(adult.toString())).put("hasDocent", true)
                    .put("summary", extra.optString("summary", place.optString("summary")))
                    .put("sourceTitle", extra.optString("sourceTitle", "")).put("sourceUrl", extra.optString("sourceUrl", ""));
                if (kids != null) place.put("kids", new JSONArray(kids.toString()));
                // generated places get the same journey fields as bundled ones: quizzes, look-out, recall, facts, mission
                JSONObject journey = extra.optJSONObject("journey"), mission = extra.optJSONObject("mission");
                if (journey != null && !place.has("journey")) place.put("journey", new JSONObject(journey.toString()));
                if (mission != null && !place.has("mission")) place.put("mission", new JSONObject(mission.toString()));
            }
        }
        place.put("photo", media.test("photo", id) ? "/media/photo/" + id + ".jpg" : "");
        place.put("kidsImage", media.test("kids", id) ? "/media/kids/" + id + ".jpg" : "");
        return place;
    }

    /** Navi is the source of truth: overwrite a custom stop with what the vehicle navigation resolved. */
    public synchronized boolean syncNavi(String placeId, String name, String address, double lat, double lon, String poiId) {
        try {
            JSONArray custom = trip.getJSONArray("customPlaces");
            for (int i=0;i<custom.length();i++) {
                JSONObject p = custom.getJSONObject(i);
                if (!placeId.equals(p.getString("id"))) continue;
                if (trip.getString("status").equals("completed")) return false;
                JSONObject before = copy(trip);
                try {
                    boolean moved = VehicleBridge.distance(p.getDouble("latitude"), p.getDouble("longitude"), lat, lon) > 30;
                    boolean renamed = name != null && !name.trim().isEmpty() && !name.trim().equals(p.getString("name"));
                    if (p.optBoolean("naviSynced", false) && !moved && !renamed) return false;
                    if (renamed && name.trim().length() <= 120 && !hasControl(name)) p.put("name", name.trim());
                    if (address != null && !address.trim().isEmpty() && address.length() <= 300 && !hasControl(address)) p.put("address", address.trim());
                    if (moved) { p.put("latitude", range(lat, -90, 90)).put("longitude", range(lon, -180, 180)); trip.put("route", JSONObject.NULL); bumpRoute(); }
                    p.put("naviSynced", true).put("poiId", poiId == null ? "" : poiId);
                    bumpRevision();
                    appendHistory("차량 내비 · " + p.getString("name") + (moved || renamed ? " 내비 기준으로 맞춤" : " 내비 확인"));
                    validate(trip); commit();
                } catch (Exception e) { trip = before; return false; }
                onChange.run();
                return true;
            }
        } catch (JSONException ignored) {}
        return false;
    }

    private JSONObject initial() throws JSONException {
        return new JSONObject().put("tripId", catalog.getString("tripId"))
            .put("revision", 1).put("routeRevision", 1).put("approvedRevision", 1)
            .put("stops", new JSONArray(catalog.getJSONArray("initialStops").toString()))
            .put("visited", new JSONArray()).put("selected", "cheomseongdae").put("history", new JSONArray())
            .put("status", "planning").put("origin", JSONObject.NULL).put("customPlaces", new JSONArray())
            .put("createdAt", JSONObject.NULL).put("startedAt", JSONObject.NULL).put("completedAt", JSONObject.NULL)
            .put("visitEvents", new JSONArray()).put("startedPlan", JSONObject.NULL)
            .put("completedPlan", JSONObject.NULL).put("route", JSONObject.NULL);
    }

    /** Adds planner fields to a legacy trip; unknown legacy times stay null. */
    private boolean migrate(JSONObject t) throws JSONException {
        boolean changed = false;
        if (!t.has("customPlaces")) { t.put("customPlaces", new JSONArray()); changed = true; }
        if (!t.has("status")) { t.put("status", "planning"); changed = true; }
        for (String key : new String[]{"createdAt","startedAt","completedAt","origin","startedPlan","completedPlan","route"})
            if (!t.has(key)) { t.put(key, JSONObject.NULL); changed = true; }
        if (!t.has("history")) { t.put("history", new JSONArray()); changed = true; }
        if (!t.has("visited")) { t.put("visited", new JSONArray()); changed = true; }
        if (!t.has("stops")) { t.put("stops", new JSONArray()); changed = true; }
        if (!t.has("selected")) { t.put("selected", ""); changed = true; }
        if (!t.has("visitEvents")) {
            JSONArray events = new JSONArray(), visited = t.getJSONArray("visited");
            for (int i=0;i<visited.length();i++)
                events.put(new JSONObject().put("place", placeIn(t, visited.getString(i))).put("completedAt", JSONObject.NULL));
            t.put("visitEvents", events); changed = true;
        }
        return changed;
    }

    private void loadArchive(boolean protectActive) {
        String raw = prefs.getString("archive", null);
        if (raw == null) return;
        JSONArray kept = new JSONArray();
        boolean bad = false;
        try {
            JSONArray parsed = new JSONArray(raw);
            Set<String> ids = new HashSet<>();
            if (protectActive) try { ids.add(trip.getString("tripId")); } catch (Exception e) { bad = true; }
            for (int i=0;i<parsed.length();i++) {
                try {
                    JSONObject t = parsed.getJSONObject(i);
                    migrate(t); validate(t);
                    if (!ids.add(t.getString("tripId"))) { bad = true; continue; }
                    kept.put(t);
                } catch (Exception e) { bad = true; }
            }
        } catch (Exception e) { bad = true; }
        archive = kept;
        if (bad) recoverArchive(raw);
    }

    private void recoverArchive(String raw) {
        if (!loadWarning.isEmpty()) loadWarning += " ";
        loadWarning += "저장된 다른 여행 일부를 읽지 못해 복구 사본을 보관했습니다.";
        SharedPreferences.Editor editor = prefs.edit().putString("recoveryArchive", raw);
        if (prefs.getString("recovery", null) == null) editor.putString("recovery", raw);
        editor.commit();
    }

    private void validate(JSONObject t) throws JSONException {
        String tripId = t.getString("tripId");
        if (tripId.trim().isEmpty()) throw new JSONException("trip id");
        if (!t.isNull("title")) {
            String title = t.getString("title");
            if (title.trim().isEmpty() || title.length() > 100 || hasControl(title)) throw new JSONException("title");
        }
        String status = t.getString("status");
        if (!Arrays.asList("planning","active","completed").contains(status)) throw new JSONException("status");
        JSONArray custom = t.getJSONArray("customPlaces");
        if (custom.length() > 100) throw new JSONException("customPlaces");
        Set<String> customIds = new HashSet<>();
        for (int i=0;i<custom.length();i++) {
            JSONObject p = custom.getJSONObject(i);
            String id = p.getString("id");
            if (id.trim().isEmpty() || !customIds.add(id)) throw new JSONException("custom id");
            String name = p.getString("name");
            if (name.trim().isEmpty() || name.length() > 120 || hasControl(name)) throw new JSONException("custom name");
            String address = p.optString("address", "");
            if (address.length() > 300 || hasControl(address)) throw new JSONException("custom address");
            range(p.get("latitude"), -90, 90); range(p.get("longitude"), -180, 180);
        }
        JSONArray stops = t.getJSONArray("stops");
        if (stops.length() > 20) throw new JSONException("stops");
        Set<String> unique = new HashSet<>();
        for (int i=0;i<stops.length();i++) {
            String id = stops.getString(i);
            placeIn(t, id);
            if (!unique.add(id)) throw new JSONException("duplicate");
        }
        String selected = t.getString("selected");
        if (stops.length() == 0) { if (!selected.isEmpty()) throw new JSONException("selection"); }
        else if (!contains(stops, selected)) throw new JSONException("selection");
        JSONArray visited = t.getJSONArray("visited");
        for (int i=0;i<visited.length();i++)
            if (!contains(stops, visited.getString(i))) throw new JSONException("visited");
        if (t.getInt("revision") < 1 || t.getInt("routeRevision") < 1 ||
            t.getInt("approvedRevision") < 0 || t.getInt("approvedRevision") > t.getInt("routeRevision"))
            throw new JSONException("revision");
        t.getJSONArray("history");
        JSONArray events = t.getJSONArray("visitEvents");
        for (int i=0;i<events.length();i++) {
            JSONObject event = events.getJSONObject(i);
            event.getJSONObject("place").getString("id");
            if (!event.isNull("completedAt") && !(event.get("completedAt") instanceof Number)) throw new JSONException("visit time");
        }
        for (String key : new String[]{"startedPlan","completedPlan"})
            if (!t.isNull(key) && !(t.get(key) instanceof JSONObject)) throw new JSONException(key);
        if (!t.isNull("origin")) {
            JSONObject origin = t.getJSONObject("origin");
            String name = origin.getString("name");
            if (name.trim().isEmpty() || name.length() > 120 || hasControl(name)) throw new JSONException("origin name");
            String address = origin.optString("address", "");
            if (address.length() > 300 || hasControl(address)) throw new JSONException("origin address");
            range(origin.get("latitude"), -90, 90); range(origin.get("longitude"), -180, 180);
        }
        if (!t.isNull("route")) {
            try {
                JSONObject route = t.getJSONObject("route");
                if (!tripId.equals(route.optString("tripId")) || t.getInt("routeRevision") != route.optInt("routeRevision"))
                    throw new JSONException("stale route");
                t.put("route", cleanRoute(route, tripId, t.getInt("routeRevision"), signature(t)));
            }
            catch (Exception e) { t.put("route", JSONObject.NULL); }
        }
    }

    public synchronized JSONObject place(String id) throws JSONException {
        return decorate(placeIn(trip, id));
    }

    private JSONObject placeIn(JSONObject t, String id) throws JSONException {
        JSONArray custom = t.optJSONArray("customPlaces");
        if (custom != null) for (int i=0;i<custom.length();i++)
            if (id.equals(custom.getJSONObject(i).getString("id")))
                return new JSONObject(custom.getJSONObject(i).toString());
        JSONArray all = catalog.getJSONArray("places");
        for (int i=0;i<all.length();i++) if (id.equals(all.getJSONObject(i).getString("id"))) {
            JSONObject clone = new JSONObject(all.getJSONObject(i).toString());
            clone.put("hasDocent", true);
            return clone;
        }
        throw new IllegalArgumentException("등록되지 않은 장소입니다.");
    }

    public synchronized JSONObject snapshot() throws JSONException {
        JSONObject out = new JSONObject(trip.toString());
        out.put("title", title(trip)).put("subtitle", catalog.getString("subtitle"))
            .put("places", places(trip)).put("members", new JSONArray(members.values()))
            .put("proposals", new JSONArray(proposals.values()))
            .put("savedTrips", savedTrips()).put("records", records())
            .put("voice", voice).put("guide", "male".equals(voice) ? "dog" : "cat").put("guideName", guideName(voice));
        return out;
    }

    private JSONArray places(JSONObject t) throws JSONException {
        JSONArray out = new JSONArray(), all = catalog.getJSONArray("places");
        for (int i=0;i<all.length();i++) {
            JSONObject place = new JSONObject(all.getJSONObject(i).toString());
            place.put("hasDocent", true);
            out.put(decorate(place));
        }
        JSONArray custom = t.optJSONArray("customPlaces");
        if (custom != null) for (int i=0;i<custom.length();i++)
            out.put(decorate(new JSONObject(custom.getJSONObject(i).toString())));
        return out;
    }

    private JSONArray savedTrips() throws JSONException {
        JSONArray out = new JSONArray();
        for (JSONObject t : allTrips())
            out.put(new JSONObject().put("tripId", t.getString("tripId")).put("title", title(t))
                .put("status", t.getString("status"))
                .put("origin", t.isNull("origin") ? JSONObject.NULL : new JSONObject(t.getJSONObject("origin").toString()))
                .put("stopCount", t.getJSONArray("stops").length())
                .put("stops", t.getJSONArray("stops").length())
                .put("startedAt", time(t, "startedAt")).put("completedAt", time(t, "completedAt")));
        return out;
    }

    private JSONArray records() throws JSONException {
        JSONArray out = new JSONArray();
        for (JSONObject t : allTrips()) {
            if (!t.getString("status").equals("completed")) continue;
            JSONArray stops = new JSONArray();
            JSONObject plan = t.isNull("completedPlan") ? null : t.optJSONObject("completedPlan");
            if (plan != null && plan.optJSONArray("stops") != null) stops = new JSONArray(plan.getJSONArray("stops").toString());
            else for (int i=0;i<t.getJSONArray("stops").length();i++) stops.put(placeIn(t, t.getJSONArray("stops").getString(i)));
            out.put(new JSONObject().put("tripId", t.getString("tripId")).put("title", title(t))
                .put("status", "completed")
                .put("origin", t.isNull("origin") ? JSONObject.NULL : new JSONObject(t.getJSONObject("origin").toString()))
                .put("createdAt", time(t, "createdAt")).put("startedAt", time(t, "startedAt")).put("completedAt", time(t, "completedAt"))
                .put("stops", stops).put("startedPlan", cloneOrNull(t, "startedPlan"))
                .put("completedPlan", cloneOrNull(t, "completedPlan"))
                .put("visitEvents", new JSONArray(t.getJSONArray("visitEvents").toString())));
        }
        return out;
    }

    private List<JSONObject> allTrips() throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int i=0;i<archive.length();i++) out.add(archive.getJSONObject(i));
        out.add(trip);
        return out;
    }

    private String title(JSONObject t) throws JSONException {
        String title = t.isNull("title") ? "" : t.optString("title", "").trim();
        return title.isEmpty() ? catalog.getString("title") : title;
    }

    private static Object time(JSONObject t, String key) { return t.isNull(key) ? JSONObject.NULL : t.opt(key); }

    private static Object cloneOrNull(JSONObject t, String key) throws JSONException {
        return t.isNull(key) ? JSONObject.NULL : new JSONObject(t.getJSONObject(key).toString());
    }

    public synchronized JSONObject join(String name) throws JSONException {
        name = name.trim();
        if (name.isEmpty() || name.length()>20 || hasControl(name))
            throw new IllegalArgumentException("이름은 1~20자로 입력해 주세요.");
        if (members.size() >= 8) throw new IllegalArgumentException("가족 연결은 최대 8대입니다.");
        String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        members.put(id, new JSONObject().put("id", id).put("name", name).put("role", "proposer").put("audience", "general"));
        sessions.put(token, id);
        onChange.run();
        return new JSONObject().put("token", token).put("memberId", id);
    }

    public synchronized String memberFor(String token) {
        String id = sessions.get(token);
        if (id == null || !members.containsKey(id)) throw new SecurityException("다시 가족 연결을 해 주세요.");
        return id;
    }

    public synchronized void role(String id, String role, boolean parked) throws JSONException {
        if (!parked) throw new SecurityException("권한은 정차 확인 후 변경할 수 있습니다.");
        if (!Arrays.asList("viewer", "proposer", "editor", "revoked").contains(role) || !members.containsKey(id))
            throw new IllegalArgumentException("권한을 확인해 주세요.");
        if (role.equals("revoked")) {
            members.remove(id); sessions.values().removeIf(id::equals); proposals.values().removeIf(p -> id.equals(p.optString("memberId")));
        } else {
            members.get(id).put("role", role);
            if (role.equals("viewer")) proposals.values().removeIf(p -> id.equals(p.optString("memberId")));
        }
        onChange.run();
    }

    /** Owner (vehicle) decides which docent a device gets: general or kids. */
    public synchronized void audience(String id, String audience, boolean parked) throws JSONException {
        if (!parked) throw new SecurityException("권한은 정차 확인 후 변경할 수 있습니다.");
        if (!Arrays.asList("general", "kids").contains(audience) || !members.containsKey(id))
            throw new IllegalArgumentException("안내 유형을 확인해 주세요.");
        members.get(id).put("audience", audience);
        onChange.run();
    }

    public synchronized void mutate(String actor, JSONObject request, boolean parked) throws Exception {
        boolean driver = actor.equals("driver");
        JSONObject member = driver ? null : members.get(actor);
        if (!driver && member == null) throw new SecurityException("다시 가족 연결을 해 주세요.");
        String role = driver ? "driver" : member.getString("role");
        String asked = request.getString("action");
        // A family device that may add places puts them straight on the list: nothing waits for approval.
        final String action = asked.equals("propose") ? "add" : asked.equals("proposeCustom") ? "addCustom" : asked;
        if (request.getInt("revision") != trip.getInt("revision"))
            throw new ConcurrentModificationException("일정이 바뀌었어요. 최신 일정을 확인하고 다시 시도해 주세요.");
        if (driver && !parked) throw new SecurityException("정차 확인 후 이용해 주세요.");
        boolean switching = action.equals("newTrip") || action.equals("openTrip");
        if (!switching && trip.getString("status").equals("completed"))
            throw new SecurityException("완료된 여행은 수정할 수 없습니다.");
        String who = driver ? "운전자" : member.getString("name");

        JSONObject beforeTrip = copy(trip);
        JSONArray beforeArchive = new JSONArray(archive.toString());
        Map<String,JSONObject> beforeProposals = proposalsCopy();
        String note = "", addedName = "";
        try {
            JSONArray stops = trip.getJSONArray("stops");
            String id = request.optString("placeId");
            switch (action) {
                case "newTrip":
                    requireEditor(driver, role);
                    if (trip.getString("status").equals("active")) throw new IllegalStateException("진행 중인 여행을 먼저 완료해 주세요.");
                    String newTitle = text(request, "title", 100);
                    int revision = trip.getInt("revision")+1;
                    archive.put(copy(trip));
                    proposals.clear();
                    trip = new JSONObject().put("tripId", UUID.randomUUID().toString()).put("title", newTitle)
                        .put("revision", revision).put("routeRevision", 1).put("approvedRevision", 0)
                        .put("stops", new JSONArray()).put("visited", new JSONArray()).put("selected", "")
                        .put("customPlaces", new JSONArray()).put("visitEvents", new JSONArray()).put("history", new JSONArray())
                        .put("status", "planning").put("origin", JSONObject.NULL).put("route", JSONObject.NULL)
                        .put("createdAt", System.currentTimeMillis()).put("startedAt", JSONObject.NULL)
                        .put("completedAt", JSONObject.NULL).put("startedPlan", JSONObject.NULL).put("completedPlan", JSONObject.NULL);
                    break;
                case "openTrip":
                    requireEditor(driver, role);
                    if (trip.getString("status").equals("active")) throw new IllegalStateException("진행 중인 여행을 먼저 완료해 주세요.");
                    String wanted = request.getString("tripId");
                    int found = -1;
                    for (int i=0;i<archive.length();i++) if (wanted.equals(archive.getJSONObject(i).getString("tripId"))) found = i;
                    if (found < 0) throw new IllegalArgumentException("저장된 여행을 찾을 수 없습니다.");
                    revision = trip.getInt("revision")+1;
                    JSONObject restored = archive.getJSONObject(found);
                    archive.remove(found);
                    archive.put(copy(trip));
                    proposals.clear();
                    trip = copy(restored);
                    trip.put("revision", revision);
                    break;
                case "renameTrip":
                    requireEditor(driver, role);
                    trip.put("title", text(request, "title", 100));
                    bumpRevision(); note = "여행 제목 변경"; break;
                case "setOrigin":
                    requireEditor(driver, role);
                    JSONObject origin;
                    String originId = request.optString("placeId", "");
                    if (!originId.isEmpty()) {
                        JSONObject source = place(originId);
                        origin = new JSONObject().put("name", source.getString("name"))
                            .put("latitude", source.getDouble("latitude")).put("longitude", source.getDouble("longitude"))
                            .put("address", source.optString("address", ""));
                    } else origin = normalizeOrigin(request.getJSONObject("place"));
                    trip.put("origin", origin).put("route", JSONObject.NULL);
                    bumpRoute(); bumpRevision(); note = "출발지 설정"; break;
                case "add":
                case "accept":
                    if (action.equals("accept")) {
                        if (!driver) throw new SecurityException("이 변경은 운전자 또는 편집 권한이 필요합니다.");
                        JSONObject proposal = proposals.get(request.optString("proposalId"));
                        if (proposal == null) for (JSONObject p : proposals.values())   // older clients name the member only: its first proposal
                            if (p.optString("memberId").equals(request.optString("memberId"))) { proposal = p; break; }
                        if (proposal == null) throw new IllegalArgumentException("이미 처리된 제안입니다.");
                        id = proposal.getString("placeId");
                    } else requireAdder(driver, role);
                    JSONObject added = place(id);
                    addedName = added.getString("name");
                    if (contains(stops, id)) throw new IllegalArgumentException("이미 일정에 있어요.");
                    if (stops.length() >= 20) throw new IllegalArgumentException("방문지는 20곳까지 추가할 수 있습니다.");
                    stops.put(id);
                    if (stops.length() == 1) trip.put("selected", id);
                    note = added.getString("name") + " 추가";
                    trip.put("route", JSONObject.NULL); bumpRoute(); bumpRevision(); break;
                case "addCustom":
                    requireAdder(driver, role);
                    JSONObject candidate = customPlace(request.getJSONObject("place"));
                    addedName = candidate.getString("name");
                    JSONObject existing = findCustom(trip, candidate);
                    String customId;
                    if (existing != null) {
                        customId = existing.getString("id");
                        if (contains(stops, customId)) throw new IllegalArgumentException("이미 일정에 있어요.");
                    } else {
                        if (trip.getJSONArray("customPlaces").length() >= 100)
                            throw new IllegalArgumentException("지정한 장소는 여행당 100곳까지 저장할 수 있습니다.");
                        customId = candidate.getString("id");
                        trip.getJSONArray("customPlaces").put(candidate);
                    }
                    if (stops.length() >= 20) throw new IllegalArgumentException("방문지는 20곳까지 추가할 수 있습니다.");
                    stops.put(customId);
                    if (stops.length() == 1) trip.put("selected", customId);
                    note = candidate.getString("name") + " 추가";
                    trip.put("route", JSONObject.NULL); bumpRoute(); bumpRevision(); break;
                case "remove":
                    requireEditor(driver, role);
                    JSONObject removed = place(id);
                    stops.remove(index(stops, id));
                    removeValue(trip.getJSONArray("visited"), id);
                    if (stops.length() == 0) trip.put("selected", "");
                    else if (id.equals(trip.getString("selected"))) trip.put("selected", stops.getString(0));
                    trip.put("route", JSONObject.NULL); bumpRoute(); bumpRevision();
                    note = removed.getString("name") + " 삭제"; break;
                case "move":
                    requireEditor(driver, role);
                    int from = index(stops, id), delta = request.getInt("delta"), to = from + delta;
                    if (Math.abs(delta) != 1 || to < 0 || to >= stops.length()) throw new IllegalArgumentException("이동할 수 없는 순서입니다.");
                    Object other = stops.get(to); stops.put(to, id); stops.put(from, other);
                    trip.put("route", JSONObject.NULL); bumpRoute(); bumpRevision(); note = "방문 순서 변경"; break;
                case "select":
                    if (!driver) throw new SecurityException("이 변경은 운전자 또는 편집 권한이 필요합니다.");
                    index(stops, id); trip.put("selected", id); bumpRevision();
                    note = place(id).getString("name") + " 선택"; break;
                case "go":   // the driver picks where to head now; the first pick starts the trip
                    if (!driver) throw new SecurityException("목적지는 차량에서 정해요.");
                    index(stops, id); trip.put("selected", id);
                    removeValue(trip.getJSONArray("visited"), id);
                    if (trip.getString("status").equals("planning"))
                        trip.put("startedPlan", plan()).put("startedAt", System.currentTimeMillis()).put("status", "active");
                    bumpRevision(); note = place(id).getString("name") + " 목적지로 정함"; break;
                case "visit":
                    requireEditor(driver, role);
                    if (!trip.getString("status").equals("active")) throw new IllegalStateException("여행을 시작한 뒤 방문을 기록할 수 있습니다.");
                    index(stops, id);
                    if (contains(trip.getJSONArray("visited"), id)) return;
                    JSONArray events = trip.getJSONArray("visitEvents");
                    if (!logged(events, id))
                        events.put(new JSONObject().put("place", place(id)).put("completedAt", System.currentTimeMillis()));
                    trip.getJSONArray("visited").put(id);
                    for (int i=0;i<stops.length();i++) if (!contains(trip.getJSONArray("visited"), stops.getString(i))) {
                        trip.put("selected", stops.getString(i)); break;
                    }
                    bumpRevision(); note = place(id).getString("name") + " 방문 완료"; break;
                case "approve":
                    if (!driver) throw new SecurityException("내비 확인은 운전자만 할 수 있습니다.");
                    trip.put("approvedRevision", trip.getInt("routeRevision")); bumpRevision();
                    note = "운전자 일정 확인 · 내비 적용은 별도"; break;
                case "startTrip":
                    requireEditor(driver, role);
                    if (!trip.getString("status").equals("planning")) throw new IllegalStateException("계획 여행만 시작할 수 있습니다.");
                    if (stops.length() < 1) throw new IllegalArgumentException("방문지를 한 곳 이상 추가해 주세요.");
                    trip.put("startedPlan", plan()).put("startedAt", System.currentTimeMillis())
                        .put("status", "active"); bumpRevision(); note = "여행 시작"; break;
                case "finishTrip":
                    requireEditor(driver, role);
                    if (!trip.getString("status").equals("active")) throw new IllegalStateException("진행 중인 여행만 완료할 수 있습니다.");
                    trip.put("completedPlan", plan()).put("completedAt", System.currentTimeMillis())
                        .put("status", "completed"); bumpRevision(); note = "여행 완료"; break;
                default: throw new IllegalArgumentException("지원하지 않는 변경입니다.");
            }
            if (!switching) appendHistory(who + " · " + note);
            validate(trip);
            commit();
        } catch (Exception e) { trip = beforeTrip; archive = beforeArchive; proposals.clear(); proposals.putAll(beforeProposals); throw e; }
        if (action.equals("add") || action.equals("accept") || action.equals("addCustom")) {
            JSONArray stops = trip.getJSONArray("stops");
            proposals.values().removeIf(p -> contains(stops, p.optString("placeId")));
        }
        onChange.run();
        if (!driver && !addedName.isEmpty()) onAdded.accept(who, addedName);
    }

    /** Vehicle arrival event: records the first visit time and moves on to the next stop. */
    public synchronized boolean arrive(String placeId) {
        try {
            if (!trip.getString("status").equals("active")) return false;
            JSONArray stops = trip.getJSONArray("stops");
            if (!contains(stops, placeId) || contains(trip.getJSONArray("visited"), placeId)) return false;
            JSONObject before = copy(trip);
            try {
                JSONArray events = trip.getJSONArray("visitEvents");
                if (!logged(events, placeId))
                    events.put(new JSONObject().put("place", place(placeId)).put("completedAt", System.currentTimeMillis()));
                trip.getJSONArray("visited").put(placeId);
                for (int i=0;i<stops.length();i++) if (!contains(trip.getJSONArray("visited"), stops.getString(i))) {
                    trip.put("selected", stops.getString(i)); break;
                }
                bumpRevision();
                appendHistory("차량 도착 · " + place(placeId).getString("name") + " 방문 기록");
                validate(trip); commit();
            } catch (Exception e) { trip = before; return false; }
            onChange.run();
            return true;
        } catch (JSONException e) { return false; }
    }

    public synchronized void reject(String member, boolean parked) {
        if (!parked) throw new SecurityException("정차 확인이 필요합니다.");
        // one proposal by its id, or everything a member proposed
        if (proposals.remove(member) == null) proposals.values().removeIf(p -> member.equals(p.optString("memberId")));
        onChange.run();
    }

    public synchronized void disconnectAll(){members.clear();sessions.clear();proposals.clear();}

    /** R2 server hook: only the current revision's route is stored. */
    public synchronized boolean cacheRoute(String tripId, int routeRevision, JSONObject route) {
        try {
            if (!trip.getString("tripId").equals(tripId)) return false;
            if (routeRevision != trip.getInt("routeRevision")) return false;
            if (trip.getString("status").equals("completed")) return false;
            JSONObject clean = cleanRoute(route, tripId, routeRevision, signature(trip));
            JSONObject existing = trip.optJSONObject("route");
            if (existing != null && existing.toString().equals(clean.toString())) return true;
            JSONObject before = copy(trip);
            try {
                trip.put("route", clean).put("revision", trip.getInt("revision")+1);
                commit();
            } catch (Exception e) { trip = before; return false; }
            onChange.run();
            return true;
        } catch (Exception e) { return false; }
    }

    private JSONObject plan() throws JSONException {
        JSONObject origin = trip.isNull("origin") ? null : new JSONObject(trip.getJSONObject("origin").toString());
        JSONArray stops = new JSONArray(), ids = trip.getJSONArray("stops");
        for (int i=0;i<ids.length();i++) stops.put(place(ids.getString(i)));
        JSONObject route = trip.optJSONObject("route");
        if (route == null || !trip.getString("tripId").equals(route.optString("tripId"))
            || trip.getInt("routeRevision") != route.optInt("routeRevision")) route = null;
        return new JSONObject().put("tripId", trip.getString("tripId")).put("title", title(trip))
            .put("origin", origin == null ? JSONObject.NULL : origin).put("stops", stops)
            .put("route", route == null ? JSONObject.NULL : new JSONObject(route.toString()))
            .put("routeRevision", trip.getInt("routeRevision"));
    }

    private static JSONObject cleanRoute(JSONObject in, String tripId, int routeRevision, String planSignature) throws JSONException {
        if (in == null) throw new JSONException("route");
        JSONObject geometry = in.getJSONObject("geometry");
        if (!"LineString".equals(geometry.getString("type"))) throw new JSONException("geometry");
        JSONArray coordinates = geometry.getJSONArray("coordinates");
        if (coordinates.length() < 2 || coordinates.length() > 20000) throw new JSONException("coordinates");
        JSONArray copy = new JSONArray();
        for (int i=0;i<coordinates.length();i++) {
            JSONArray pair = coordinates.getJSONArray(i);
            if (pair.length() != 2) throw new JSONException("coordinate");
            double lon = number(pair.get(0)), lat = number(pair.get(1));
            if (lon < -180 || lon > 180 || lat < -90 || lat > 90) throw new JSONException("coordinate range");
            copy.put(new JSONArray().put(lon).put(lat));
        }
        JSONArray legsIn = in.getJSONArray("legs");
        if (legsIn.length() > 2000) throw new JSONException("legs");
        JSONArray legs = new JSONArray();
        for (int i=0;i<legsIn.length();i++) {
            JSONObject leg = legsIn.getJSONObject(i);
            legs.put(new JSONObject().put("distanceMeters", nonNegative(leg.get("distanceMeters")))
                .put("durationSeconds", nonNegative(leg.get("durationSeconds"))));
        }
        String provider = in.getString("provider");
        if (!"OSRM / FOSSGIS".equals(provider)) throw new JSONException("provider");
        double computedAt = number(in.get("computedAt"));
        if (computedAt <= 0) throw new JSONException("computedAt");
        return new JSONObject().put("tripId", tripId).put("routeRevision", routeRevision)
            .put("planSignature", planSignature)
            .put("geometry", new JSONObject().put("type", "LineString").put("coordinates", copy))
            .put("distanceMeters", nonNegative(in.get("distanceMeters")))
            .put("durationSeconds", nonNegative(in.get("durationSeconds")))
            .put("legs", legs).put("provider", provider).put("computedAt", computedAt);
    }

    private static String signature(JSONObject t) throws JSONException {
        JSONObject origin = t.optJSONObject("origin");
        StringBuilder out = new StringBuilder(origin == null ? "" : origin.optDouble("latitude") + "@" + origin.optDouble("longitude"));
        JSONArray stops = t.getJSONArray("stops");
        for (int i=0;i<stops.length();i++) out.append('|').append(stops.getString(i));
        return out.toString();
    }

    private void commit() throws IOException {
        if (!prefs.edit().putString("trip", trip.toString()).putString("archive", archive.toString()).commit())
            throw new IOException("일정을 저장하지 못했습니다. 다시 시도해 주세요.");
    }

    private void bumpRoute() throws JSONException {trip.put("routeRevision",trip.getInt("routeRevision")+1);}
    private void bumpRevision() throws JSONException {trip.put("revision",trip.getInt("revision")+1);}

    private void appendHistory(String text) throws JSONException {
        JSONArray history = trip.getJSONArray("history");
        history.put(text);
        if (history.length()>20) history.remove(0);
    }

    private static void requireEditor(boolean driver, String role) {
        if (!driver && !role.equals("editor")) throw new SecurityException("이 변경은 운전자 또는 편집 권한이 필요합니다.");
    }

    private static void requireAdder(boolean driver, String role) {
        if (!driver && !Arrays.asList("proposer", "editor").contains(role)) throw new SecurityException("장소를 담으려면 차량에서 권한을 받아야 해요.");
    }

    private Map<String,JSONObject> proposalsCopy() throws JSONException {
        Map<String,JSONObject> out = new LinkedHashMap<>();
        for (Map.Entry<String,JSONObject> entry : proposals.entrySet()) out.put(entry.getKey(), copy(entry.getValue()));
        return out;
    }

    private static JSONObject copy(JSONObject value) throws JSONException { return value == null ? null : new JSONObject(value.toString()); }

    private static JSONObject customPlace(JSONObject in) throws JSONException {
        if (in == null) throw new IllegalArgumentException("장소 정보를 입력해 주세요.");
        String name = text(in, "name", 120), address = freeText(in, "address", 300);
        double lat = range(in.get("latitude"), -90, 90), lon = range(in.get("longitude"), -180, 180);
        return new JSONObject().put("id", "custom-" + UUID.randomUUID()).put("name", name)
            .put("latitude", lat).put("longitude", lon).put("address", address)
            .put("tag", "지정한 장소").put("category", "여행 장소")
            .put("summary", "등록된 해설이 없는 장소입니다.").put("art", "")
            .put("adult", new JSONArray()).put("kids", new JSONArray())
            .put("sourceTitle", "").put("sourceUrl", "").put("hasDocent", false)
            .put("source", Arrays.asList("navi", "map").contains(in.optString("source")) ? in.optString("source") : "search")
            .put("naviSynced", "navi".equals(in.optString("source"))).put("poiId", "");
    }

    private static JSONObject normalizeOrigin(JSONObject in) throws JSONException {
        if (in == null) throw new IllegalArgumentException("출발지 정보를 입력해 주세요.");
        return new JSONObject().put("name", text(in, "name", 120)).put("address", freeText(in, "address", 300))
            .put("latitude", range(in.get("latitude"), -90, 90)).put("longitude", range(in.get("longitude"), -180, 180));
    }

    private static JSONObject findCustom(JSONObject t, JSONObject candidate) {
        JSONArray custom = t.optJSONArray("customPlaces");
        if (custom == null) return null;
        String name = candidate.optString("name");
        double lat = candidate.optDouble("latitude"), lon = candidate.optDouble("longitude");
        for (int i=0;i<custom.length();i++) {
            JSONObject place = custom.optJSONObject(i);
            if (place != null && place.optString("name").equals(name)
                && Double.compare(place.optDouble("latitude"), lat) == 0
                && Double.compare(place.optDouble("longitude"), lon) == 0) return place;
        }
        return null;
    }

    private static boolean logged(JSONArray events, String id) {
        for (int i=0;i<events.length();i++) {
            JSONObject place = events.optJSONObject(i) == null ? null : events.optJSONObject(i).optJSONObject("place");
            if (place != null && id.equals(place.optString("id"))) return true;
        }
        return false;
    }

    private static String text(JSONObject source, String key, int max) {
        String value = source == null || source.isNull(key) ? "" : source.optString(key, "").trim();
        if (value.isEmpty() || value.length() > max || hasControl(value))
            throw new IllegalArgumentException("내용은 1~" + max + "자로 입력해 주세요.");
        return value;
    }

    private static String freeText(JSONObject source, String key, int max) {
        String value = source == null || source.isNull(key) ? "" : source.optString(key, "").trim();
        if (value.length() > max || hasControl(value)) throw new IllegalArgumentException("주소는 " + max + "자 이하로 입력해 주세요.");
        return value;
    }

    private static boolean hasControl(String value) { return value.chars().anyMatch(Character::isISOControl); }

    private static double number(Object value) throws JSONException {
        if (!(value instanceof Number)) throw new JSONException("number");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) throw new JSONException("number");
        return number;
    }

    private static double nonNegative(Object value) throws JSONException {
        double number = number(value);
        if (number < 0) throw new JSONException("negative");
        return number;
    }

    private static double range(Object value, double min, double max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("좌표를 확인해 주세요.");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < min || number > max)
            throw new IllegalArgumentException("좌표를 확인해 주세요.");
        return number;
    }

    private static int index(JSONArray a,String id) throws JSONException {
        for(int i=0;i<a.length();i++) if(id.equals(a.getString(i))) return i;
        throw new IllegalArgumentException("일정에 없는 장소입니다.");
    }
    public static boolean contains(JSONArray a,String id) {for(int i=0;i<a.length();i++) if(id.equals(a.optString(i)))return true;return false;}
    private static void removeValue(JSONArray a,String id) {for(int i=a.length()-1;i>=0;i--)if(id.equals(a.optString(i)))a.remove(i);}
}

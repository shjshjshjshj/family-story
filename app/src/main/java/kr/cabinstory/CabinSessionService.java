package kr.cabinstory;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.io.IOException;
import java.util.*;

public final class CabinSessionService extends Service {
    public static final String ACTION_STOP_SESSION = "kr.cabinstory.action.STOP_SESSION";
    private static final String CHANNEL_ID = "cabin_session";
    private static final int NOTIFICATION_ID = 1001;

    public interface StateListener { void onStateChanged(); }
    public interface AudioListener { void onAudioChanged(); }
    public interface SessionStopListener { void onSessionStopped(); }

    public final class LocalBinder extends Binder {
        public CabinSessionService getService() { return CabinSessionService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<StateListener> stateListeners = new LinkedHashSet<>();
    private final Set<AudioListener> audioListeners = new LinkedHashSet<>();
    private final Set<SessionStopListener> stopListeners = new LinkedHashSet<>();

    private TripStore store;
    private DocentPlayer player;
    private VehicleBridge bridge;
    private FamilyServer server;
    private ContentService content;
    private String loadedVoice = "", preparedTrip = "";
    private String initError;
    private boolean stopped, kidsMode, loadedKidsMode, loadedHasDocent;
    private String loadedPlaceId = "", lastFingerprint = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        try {
            store = new TripStore(this, "cabin-trip");
            player = new DocentPlayer(this);
            bridge = new VehicleBridge(this);
            content = new ContentService(this, store);
            store.media = content::has;
            content.onChange = () -> main.post(this::forceNotifyState);
            player.setVoice(store.voice());
            server = new FamilyServer(this, store);
            server.content = content;
            bridge.onNaviResolved = (id, place) -> store.syncNavi(id, place.optString("name"), place.optString("address"),
                place.optDouble("latitude"), place.optDouble("longitude"), place.optString("poiId"));
            server.telemetry = this::telemetry;
            bridge.onArrived = id -> main.post(() -> {
                if (store == null) return;
                if (store.arrive(id)) { lastArrived = id; pendingMission = id; }
            });
            main.postDelayed(journeyTick, 3000);
            server.driverParked = bridge::parked;
            store.onChange = () -> main.post(this::syncAndNotifyState);
            store.onAdded = (who, name) -> main.post(() -> {
                if (bridge == null) return;
                if (bridge.parked()) { note = who + " · " + name + " 담음"; forceNotifyState(); }
                else if (proposalQueue.size() < 5) proposalQueue.add(new String[]{who, name});
            });
            bridge.onChange = () -> main.post(this::syncAndNotifyState);
            bridge.onApproach = id -> main.post(() -> handleApproach(id));
            player.onChange = () -> main.post(this::notifyAudio);
            syncAndNotifyState();
        } catch (Exception e) {
            Log.e("CabinStory", "CabinSessionService init failed", e);
            initError = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : e.getClass().getSimpleName();
            releaseResources();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SESSION.equals(intent.getAction())) {
            stopSessionAndExit();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public TripStore store() { return store; }
    public DocentPlayer player() { return player; }
    public VehicleBridge bridge() { return bridge; }
    public FamilyServer server() { return server; }
    public ContentService content() { return content; }
    private void forceNotifyState() { lastFingerprint = ""; syncAndNotifyState(); }
    private boolean pendingReload;
    // ---- journey: what to narrate on the way to the selected stop
    private String journeyFor = "", pendingApproach = "", pendingMission = "", lastArrived = "";
    private final Set<String> journeyDone = new HashSet<>();
    private long previewAt;
    // ---- proposals that arrive while driving are announced by voice and decided after parking
    private final List<String[]> proposalQueue = new ArrayList<>();
    private String note = "";
    /** A short line for the screen (who added what); read once. */
    public String consumeNote() { String out = note; note = ""; return out; }
    // ---- the programme between stops: quizzes, window games, stretches and a serial story, one after another
    private int legStep, legQuiz, legFact, quizCursor, gameCursor, moveCursor, taleCursor;
    private long lastVoiceAt;
    private static final String[] KIDS_ORDER = {Journey.QUIZ, Journey.GAME, Journey.TALE, Journey.QUIZ, Journey.MOVE, Journey.GAME, Journey.TALE};

    /** The next thing to say on the way, or nothing when the programme has run out (general guide after its facts). */
    private boolean playNextSegment(JSONObject place, String guide) {
        JSONObject program = store.program();
        if (!kidsMode) {
            JSONArray facts = Journey.placeFacts(place);
            if (legFact >= facts.length()) return false;
            return player.say(Journey.FACT, Journey.fact(facts.optString(legFact++)));
        }
        for (int tries = 0; tries < KIDS_ORDER.length; tries++) {
            String kind = KIDS_ORDER[legStep++ % KIDS_ORDER.length];
            if (kind.equals(Journey.QUIZ)) {
                JSONArray own = Journey.placeQuizzes(place), any = program.optJSONArray("kidsQuizzes");
                JSONObject q = legQuiz < own.length() ? own.optJSONObject(legQuiz++)
                    : any == null || any.length() == 0 ? null : any.optJSONObject(quizCursor++ % any.length());
                if (q != null) return player.say(Journey.QUIZ, Journey.quizLines(q, guide));
            } else if (kind.equals(Journey.GAME)) {
                JSONArray games = program.optJSONArray("kidsGames");
                if (games != null && games.length() > 0) return player.say(Journey.GAME, Journey.game(games.optString(gameCursor++ % games.length()), guide));
            } else if (kind.equals(Journey.MOVE)) {
                JSONArray moves = program.optJSONArray("kidsMoves");
                if (moves != null && moves.length() > 0) return player.say(Journey.MOVE, Journey.move(moves.optString(moveCursor++ % moves.length()), guide));
            } else {
                JSONArray tale = program.optJSONArray("kidsTale");
                if (tale != null && tale.length() > 0) {
                    int index = taleCursor++ % tale.length();
                    JSONArray episode = tale.optJSONArray(index);
                    if (episode != null) return player.say(Journey.TALE, Journey.tale(episode, index + 1, tale.length(), guide));
                }
            }
        }
        return false;
    }
    private final Runnable journeyTick = new Runnable() { @Override public void run() {
        if (stopped) return;
        try { tickJourney(); } catch (Exception e) { Log.w("CabinStory", "Journey", e); }
        main.postDelayed(this, 3000);
    }};

    /** Stage of the current leg for the screens: ready (parked, not left yet), moving, near, arrived. */
    private String journeyStage(JSONObject data) {
        String selected = data.optString("selected", "");
        JSONArray visited = data.optJSONArray("visited");
        if (!"active".equals(data.optString("status")) || selected.isEmpty()) return "";
        if (visited != null && TripStore.contains(visited, selected)) return "arrived";
        int remain = bridge.remainSec();
        if (bridge.parked() && !journeyDone.contains(Journey.PREVIEW)) return "ready";
        return remain >= 0 && remain <= 120 ? "near" : "moving";
    }

    public JSONObject journey() {
        JSONObject out = new JSONObject();
        if (!isInitialized()) return out;
        try {
            JSONObject data = store.snapshot();
            String stage = journeyStage(data);
            if (stage.isEmpty()) return out;
            JSONObject place = store.place(data.optString("selected"));
            out.put("placeId", place.optString("id")).put("name", place.optString("name")).put("stage", stage)
                .put("remainSec", bridge.remainSec()).put("remainMeters", bridge.remainMeters())
                .put("startSec", bridge.startSec()).put("startMeters", bridge.startMeters()).put("source", bridge.remainSource());
            if (!player.interlude().isEmpty() && player.isPlaying())
                out.put("say", new JSONObject().put("kind", player.interlude()).put("label", Journey.label(player.interlude()))
                    .put("text", player.line()).put("activity", Journey.activity(player.interlude())).put("kids", kidsMode));
        } catch (Exception ignored) {}
        return out;
    }

    private void tickJourney() throws JSONException {
        if (!isInitialized()) return;
        JSONObject data = store.snapshot();
        long now = SystemClock.elapsedRealtime();
        if (player.isPlaying()) lastVoiceAt = now;
        if (bridge.parked()) proposalQueue.clear();   // parked: the screen shows them, no need to speak
        else if (!proposalQueue.isEmpty() && !player.isPlaying() && player.isReady()) {
            String[] next = proposalQueue.remove(0);
            if (player.say(Journey.PROPOSAL, Journey.proposal(next[0], next[1], 1))) return;
        }
        if (!"active".equals(data.optString("status"))) { pendingMission = ""; return; }
        String guide = TripStore.guideName(data.optString("voice", "female"));
        if (!pendingMission.isEmpty() && !player.isPlaying() && !pendingReload) {
            String id = pendingMission; pendingMission = "";
            if (kidsMode && bridge.autoDocent) { player.say(Journey.MISSION, Journey.mission(store.place(id), guide)); return; }
        }
        String selected = data.optString("selected", "");
        JSONArray visited = data.optJSONArray("visited");
        if (selected.isEmpty() || (visited != null && TripStore.contains(visited, selected))) return;
        if (!selected.equals(journeyFor)) { journeyFor = selected; journeyDone.clear(); previewAt = 0; legStep = 0; legQuiz = 0; legFact = 0; }
        if (!bridge.autoDocent || bridge.parked() || player.isPlaying() || !player.isReady() || !pendingApproach.isEmpty()) return;
        JSONObject place = store.place(selected);
        int remain = bridge.remainSec(), start = bridge.startSec();
        if (!journeyDone.contains(Journey.PREVIEW)) {
            journeyDone.add(Journey.PREVIEW); previewAt = now;
            if (remain >= 0 && remain < 90) return;   // already at the door: the place story is next
            JSONObject previous = lastArrived.isEmpty() || lastArrived.equals(selected) ? null : store.place(lastArrived);
            player.say(Journey.PREVIEW, Journey.preview(place, previous, remain, kidsMode, guide));
            return;
        }
        if (remain >= 0 && remain <= 120) {
            if (!journeyDone.contains(Journey.LOOKOUT)) {
                journeyDone.add(Journey.LOOKOUT);
                if (remain > 45) player.say(Journey.LOOKOUT, Journey.lookout(place, kidsMode, guide));
            }
            return;
        }
        if (!Journey.IN_TRANSIT_PROGRAM) return;
        if (remain >= 0 && remain < 150) return;   // keep the last stretch quiet for the look-out hint
        // Children get something new after a short breath; the general guide speaks rarely.
        long gap = bridge.demoDriving() ? 6000 : kidsMode ? 40000 : 120000;
        if (now - Math.max(lastVoiceAt, previewAt) < gap) return;
        playNextSegment(place, guide);
    }
    /** What the vehicle is narrating right now, so a child's phone can turn pages together with the car. */
    private JSONObject telemetry() {
        JSONObject out = bridge == null ? new JSONObject() : bridge.telemetry();
        try {
            if (player != null) out.put("docent", new JSONObject().put("placeId", loadedPlaceId).put("kids", loadedKidsMode)
                .put("scene", Math.max(0, Math.min(player.paragraph(), Math.max(0, player.length() - 1))))
                .put("playing", player.isPlaying() && player.interlude().isEmpty()));
            JSONObject journey = journey();
            if (journey.length() > 0) out.put("journey", journey);
        } catch (JSONException ignored) {}
        return out;
    }
    public String initError() { return initError; }
    public boolean isStopped() { return stopped; }
    public boolean isInitialized() { return !stopped && initError == null && store != null && player != null && bridge != null; }
    public boolean isKidsMode() { return kidsMode; }

    public void setKidsMode(boolean kids) {
        if (this.kidsMode == kids) return;
        this.kidsMode = kids;
        syncAndNotifyState();
    }

    public String consumeLoadWarning() {
        if (store == null) return "";
        String warning = store.loadWarning;
        store.loadWarning = "";
        return warning;
    }

    public List<JSONObject> getUnvisitedStops(JSONObject snapshot) throws JSONException {
        if (store == null) return Collections.emptyList();
        JSONObject snap = snapshot != null ? snapshot : store.snapshot();
        JSONArray stops = snap.optJSONArray("stops");
        JSONArray visited = snap.optJSONArray("visited");
        if (stops == null || stops.length() == 0) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < stops.length(); i++) {
            String id = stops.optString(i, "");
            if (!id.isEmpty() && (visited == null || !TripStore.contains(visited, id))) {
                out.add(store.place(id));
            }
        }
        return out;
    }

    public void addStateListener(StateListener l) { if (l != null) stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    public void addAudioListener(AudioListener l) { if (l != null) audioListeners.add(l); }
    public void removeAudioListener(AudioListener l) { audioListeners.remove(l); }
    public void addSessionStopListener(SessionStopListener l) { if (l != null) stopListeners.add(l); }
    public void removeSessionStopListener(SessionStopListener l) { stopListeners.remove(l); }

    public void stopSessionAndExit() {
        if (stopped) return;
        stopped = true;
        releaseResources();
        for (SessionStopListener l : new ArrayList<>(stopListeners)) l.onSessionStopped();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        releaseResources();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "가족 여행 공유", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("가족 기기 연결과 차량 도슨트 세션을 유지합니다.");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        PendingIntent openIntent = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 2,
            new Intent(this, CabinSessionService.class).setAction(ACTION_STOP_SESSION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cabin)
            .setContentTitle("Family Story")
            .setContentText("가족 기기와 여행 계획 공유 중")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(new Notification.Action.Builder((Icon) null, "공유 종료", stopIntent).build())
            .build();
    }

    private void syncAndNotifyState() {
        if (!isInitialized()) return;
        try {
            JSONObject data = store.snapshot();
            String selected = data.optString("selected", "");
            String voice = data.optString("voice", "female");
            player.setVoice(voice);
            // Prepare media once per started trip so the rest of the trip works offline.
            if ("active".equals(data.optString("status")) && content != null) {
                String key = data.optString("tripId") + "#" + data.optJSONArray("stops");
                if (!key.equals(preparedTrip)) { preparedTrip = key; content.prepare(data, false); }
            }
            if (selected.isEmpty()) {
                bridge.setTarget(null);
                if (!loadedPlaceId.isEmpty() || loadedHasDocent) {
                    loadedPlaceId = "";
                    loadedHasDocent = false;
                    loadedKidsMode = kidsMode;
                    player.load(new String[0]);
                }
            } else {
                JSONObject place = store.place(selected);
                bridge.setTarget(place);
                boolean hasDocent = place.optBoolean("hasDocent", false);
                boolean placeOnly = !selected.equals(loadedPlaceId) && kidsMode == loadedKidsMode && voice.equals(loadedVoice);
                if (placeOnly && player.isPlaying() && !loadedPlaceId.isEmpty()) {
                    pendingReload = true;   // let the current story finish; the arrival just moved the selection on
                } else if (!selected.equals(loadedPlaceId) || kidsMode != loadedKidsMode || hasDocent != loadedHasDocent || !voice.equals(loadedVoice)) {
                    pendingReload = false;
                    loadedVoice = voice;
                    loadedPlaceId = selected;
                    loadedKidsMode = kidsMode;
                    loadedHasDocent = hasDocent;
                    player.load(hasDocent ? extractScript(place, kidsMode, TripStore.guideName(voice)) : new String[0]);
                }
            }
            String fp = fingerprint(data);
            if (!fp.equals(lastFingerprint)) {
                lastFingerprint = fp;
                for (StateListener l : new ArrayList<>(stateListeners)) l.onStateChanged();
            }
        } catch (Exception e) {
            Log.e("CabinStory", "State sync failed", e);
        }
    }

    private String fingerprint(JSONObject data) {
        JSONArray visited = data.optJSONArray("visited");
        JSONArray members = data.optJSONArray("members");
        JSONArray proposals = data.optJSONArray("proposals");
        return data.optString("tripId") + "|" + data.optInt("revision") + "|" + data.optInt("routeRevision")
            + "|" + data.optInt("approvedRevision") + "|" + data.optString("status") + "|" + data.optString("selected")
            + "|" + (visited == null ? 0 : visited.length()) + "|" + (members == null ? "[]" : members.toString())
            + "|" + (proposals == null ? "[]" : proposals.toString()) + "|" + data.isNull("route")
            + "|" + bridge.parked() + "|" + bridge.safetyLabel() + "|" + bridge.demoParked + "|" + bridge.autoDocent
            + "|" + bridge.gearStatus + "|" + bridge.naviStatus + "|" + bridge.locationStatus + "|" + bridge.event
            + "|" + (bridge.remainSec() < 0 ? -1 : (bridge.remainSec() + 59) / 60) + "|" + bridge.remainSource() + "|" + journeyStage(data) + "|" + bridge.demoDriving()
            + "|" + kidsMode + "|" + data.optString("voice") + "|" + (content == null ? "" : content.status) + "|" + data.optInt("revision")
            + "|" + bridge.naviRecent.length() + "|" + (bridge.naviDestination == null ? "" : bridge.naviDestination.optString("name")) + "|" + (server != null) + "|" + stopped + "|" + initError;
    }

    private static String[] extractScript(JSONObject place, boolean kids, String guide) {
        JSONArray arr = place.optJSONArray(kids ? "kids" : "adult");
        if (arr == null || arr.length() == 0) return new String[0];
        List<String> lines = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            if (kids) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    String title = obj.optString("title", "").trim();
                    String body = obj.optString("text", "").trim();
                    String line = title.isEmpty() ? body : (body.isEmpty() ? title : title + ". " + body);
                    if (!line.isEmpty()) lines.add(line.replace("{g}", guide));
                    continue;
                }
            }
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) lines.add(s);
        }
        return lines.toArray(new String[0]);
    }

    private void handleApproach(String id) {
        if (!isInitialized() || id == null) return;
        if (!bridge.autoDocent || !id.equals(loadedPlaceId) || !loadedHasDocent || player.length() == 0) return;
        if (!player.isPlaying()) player.toggle();
        else if (!player.interlude().isEmpty()) pendingApproach = id;   // start the place story right after the interlude
    }

    private void notifyAudio() {
        if (stopped) return;
        if (pendingReload && player != null && !player.isPlaying() && (player.paragraph() == 0 || player.paragraph() >= player.length())) {
            pendingReload = false;
            syncAndNotifyState();
        }
        if (!pendingApproach.isEmpty() && player != null && !player.isPlaying()) {
            String id = pendingApproach; pendingApproach = "";
            if (id.equals(loadedPlaceId) && loadedHasDocent && player.length() > 0 && player.paragraph() == 0) main.post(() -> { if (player != null && !player.isPlaying()) player.toggle(); });
        }
        for (AudioListener l : new ArrayList<>(audioListeners)) l.onAudioChanged();
    }

    private void releaseResources() {
        if (store != null) store.onChange = () -> {};
        if (bridge != null) {
            bridge.onChange = () -> {};
            bridge.onApproach = id -> {};
            bridge.release();
            bridge = null;
        }
        if (player != null) {
            player.onChange = () -> {};
            player.release();
            player = null;
        }
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
            server = null;
        }
        if (content != null) { content.onChange = () -> {}; content.close(); content = null; }
    }
}

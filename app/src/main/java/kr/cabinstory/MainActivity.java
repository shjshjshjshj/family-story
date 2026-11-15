package kr.cabinstory;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Vehicle app: the family hub and docent player.
 * GNB: 경로(진행 중인 여행) · 계획(경로 기획) · 안내(도슨트) · 설정(가족·기록·음성·연동).
 * While driving only 경로 and 안내 stay available, each reduced to glanceable text and one or two large actions.
 * Every screen is laid out to fit without scrolling; long lists are paged.
 */
public final class MainActivity extends Activity {
    private static final int BG = 0xFFF8FAFC, CARD = 0xFFFFFFFF, PALE = 0xFFF1F5F9, INK = 0xFF16324F, MUTED = 0xFF475569;
    private static final int PRIMARY = 0xFF245AE8, PRIMARY_SOFT = 0xFFE8F0FE, GREEN = 0xFF1B7F59, GREEN_SOFT = 0xFFE6F3ED;
    private static final int ORANGE = 0xFF8A4A10, ORANGE_SOFT = 0xFFFBF0E4, DANGER = 0xFFA3312B, BORDER = 0xFFCBD5E1;
    private static final int TAB_ROUTE = 0, TAB_GUIDE = 1, TAB_SETTINGS = 2, TABS = 3;
    private static final String[] TAB_LABELS = {"경로", "안내", "설정"};
    private static final String[] TAB_ICONS = {"➤", "♪", "⚙"};
    private static final String[] SETTINGS_LABELS = {"가족", "기록", "음성·안내", "연동"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Button[] tabButtons = new Button[TABS];
    private final Map<String, Bitmap> sprites = new HashMap<>();
    private CabinSessionService service;
    private boolean bound, active, sessionStopped;
    private int selectedTab = TAB_ROUTE, settingsTab = 0, memberPage = 0, recordPage = 0, stopPage = 0;
    private String openRecordId = "";
    private float scale = 1f;

    private LinearLayout root, rail;
    private FrameLayout nativeContainer;
    private TextView safetyChip, audioStatus, sceneTitle, sceneText;
    private Button playButton;
    private ImageView spriteView, stageImage, npcView;
    private LinearLayout propsBox;
    private Bitmap stagePicture;
    private String shownScene = "";
    private ValueAnimator spriteAnimator;
    private String shownActivity = "";
    private FrameLayout sheetLayer;
    private TextView noticeBar;
    private final Runnable hideNotice = () -> { if (noticeBar != null) noticeBar.setVisibility(View.GONE); };

    private final CabinSessionService.StateListener stateListener = this::onServiceStateChanged;
    private final CabinSessionService.AudioListener audioListener = this::updateAudio;
    private final CabinSessionService.SessionStopListener stopListener = () -> main.post(this::handleSessionStopped);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!active) return;
            service = ((CabinSessionService.LocalBinder) binder).getService();
            if (service.isStopped()) { handleSessionStopped(); return; }
            if (service.initError() != null) { renderAll(); return; }
            service.addStateListener(stateListener);
            service.addAudioListener(audioListener);
            service.addSessionStopListener(stopListener);
            String warning = service.consumeLoadWarning();
            if (!warning.isEmpty()) notice(warning);
            service.bridge().refreshNaviPlaces();
            renderAll();
        }
        @Override public void onServiceDisconnected(ComponentName name) { handleConnectionLost(); }
        @Override public void onBindingDied(ComponentName name) { handleConnectionLost(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        updateScale(getResources().getConfiguration());
        if (saved != null) {
            selectedTab = Math.max(0, Math.min(TABS - 1, saved.getInt("selectedTab", 0)));
            settingsTab = Math.max(0, Math.min(3, saved.getInt("settingsTab", 0)));
        }
        buildShell();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        if (!sessionStopped) startAndBindSession();
        renderAll();
    }

    @Override protected void onStop() {
        active = false;
        dismissDialog();
        stopSpriteAnimation();
        detachServiceListeners();
        unbindSafely();
        service = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        dismissDialog();
        stopSpriteAnimation();
        detachServiceListeners();
        unbindSafely();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScale(newConfig);
        renderAll();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("selectedTab", selectedTab);
        out.putInt("settingsTab", settingsTab);
        super.onSaveInstanceState(out);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 41 && active && isServiceReady()) service.bridge().startLocation();
        if (active) renderAll();
    }

    @Override public void onBackPressed() {
        if (sheetLayer != null && sheetLayer.getVisibility() == View.VISIBLE) dismissDialog();
        else if (!openRecordId.isEmpty()) { openRecordId = ""; renderAll(); }
        else if (selectedTab != TAB_ROUTE) { selectedTab = TAB_ROUTE; renderAll(); }
        else super.onBackPressed();
    }

    private void updateScale(Configuration cfg) { scale = Math.max(1.0f, Math.min(1.6f, cfg.screenWidthDp / 640f)); }

    private void startAndBindSession() {
        sessionStopped = false;
        Intent intent = new Intent(this, CabinSessionService.class);
        startForegroundService(intent);
        if (!bound) bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void restartSession() {
        dismissDialog();
        detachServiceListeners();
        unbindSafely();
        service = null;
        stopService(new Intent(this, CabinSessionService.class));
        startAndBindSession();
        renderAll();
    }

    private void unbindSafely() {
        if (bound) {
            try { unbindService(serviceConnection); } catch (IllegalArgumentException ignored) {}
            bound = false;
        }
    }

    private void detachServiceListeners() {
        if (service != null) {
            service.removeStateListener(stateListener);
            service.removeAudioListener(audioListener);
            service.removeSessionStopListener(stopListener);
        }
    }

    private void handleSessionStopped() {
        sessionStopped = true;
        dismissDialog();
        detachServiceListeners();
        unbindSafely();
        service = null;
        if (active) renderAll();
    }

    private void handleConnectionLost() {
        service = null;
        unbindSafely();
        sessionStopped = true;
        if (active) renderAll();
    }

    private boolean isServiceReady() { return service != null && service.isInitialized(); }
    private boolean parked() { return isServiceReady() && service.bridge().parked(); }

    private void onServiceStateChanged() {
        if (!active) return;
        if (isServiceReady() && !service.bridge().parked()) dismissDialog();
        renderAll();
        if (isServiceReady()) { String added = service.consumeNote(); if (!added.isEmpty()) notice(added); }
    }

    // ---------------------------------------------------------------- shell

    private void buildShell() {
        root = row();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(20), dp(16));

        rail = column();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, dp(4), dp(16), 0);
        root.addView(rail, new LinearLayout.LayoutParams(dp(132), -1));
        TextView brand = text("Family\nStory", 20, INK, true);
        brand.setGravity(Gravity.CENTER);
        rail.addView(brand);
        space(rail, 18);
        for (int i = 0; i < TABS; i++) {
            final int tab = i;
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText(TAB_ICONS[i] + "\n" + TAB_LABELS[i]);
            item.setTextSize(16 * scale);
            item.setLineSpacing(dp(2), 1f);
            item.setContentDescription(TAB_LABELS[i]);
            item.setOnClickListener(v -> selectTab(tab));
            tabButtons[i] = item;
            rail.addView(item, new LinearLayout.LayoutParams(-1, dp(88)));
            space(rail, 8);
        }
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        safetyChip = text("확인 중", 13, MUTED, true);
        safetyChip.setGravity(Gravity.CENTER);
        safetyChip.setPadding(dp(6), dp(10), dp(6), dp(10));
        safetyChip.setOnClickListener(v -> demoMenu());
        rail.addView(safetyChip, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout content = column();
        root.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout body = new FrameLayout(this);
        content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        nativeContainer = new FrameLayout(this);
        body.addView(nativeContainer, new FrameLayout.LayoutParams(-1, -1));
        // In-app confirmation panel and status band: no system dialogs or toasts on the vehicle screen.
        sheetLayer = new FrameLayout(this);
        sheetLayer.setVisibility(View.GONE);
        body.addView(sheetLayer, new FrameLayout.LayoutParams(-1, -1));
        noticeBar = text("", 20, Color.WHITE, true);
        noticeBar.setGravity(Gravity.CENTER_VERTICAL);
        noticeBar.setMaxLines(2); noticeBar.setEllipsize(TextUtils.TruncateAt.END);
        noticeBar.setPadding(dp(24), dp(14), dp(24), dp(14));
        noticeBar.setMinHeight(dp(64));
        GradientDrawable noticeBg = new GradientDrawable();
        noticeBg.setColor(INK); noticeBg.setCornerRadius(dp(18));
        noticeBar.setBackground(noticeBg);
        noticeBar.setElevation(dp(8));
        noticeBar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        noticeBar.setVisibility(View.GONE);
        noticeBar.setOnClickListener(v -> hideNotice.run());
        FrameLayout.LayoutParams noticeLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        noticeLp.setMargins(dp(48), dp(8), dp(48), 0);
        body.addView(noticeBar, noticeLp);
        setContentView(root);
    }

    private boolean tabAllowed(int tab, boolean parked) { return parked || tab == TAB_ROUTE || tab == TAB_GUIDE; }

    private void selectTab(int tab) {
        if (!isServiceReady()) return;
        if (!tabAllowed(tab, parked())) { notice("주행 중에는 경로와 안내만 볼 수 있어요."); return; }
        selectedTab = tab;
        openRecordId = "";
        renderAll();
    }

    private void renderAll() {
        if (!active || root == null) return;
        boolean ready = isServiceReady();
        boolean parked = ready && service.bridge().parked();
        int visibleTab = !ready ? -1 : tabAllowed(selectedTab, parked) ? selectedTab : TAB_ROUTE;

        for (int i = 0; i < TABS; i++) {
            boolean enabled = ready && tabAllowed(i, parked);
            tabButtons[i].setEnabled(enabled);
            styleTabButton(tabButtons[i], i == visibleTab, enabled);
        }
        if (ready) {
            boolean demo = service.bridge().demoParked != null;
            safetyChip.setText((demo ? "모의 " : "") + (parked ? "● 정차" : "● 주행 중"));
            chipStyle(safetyChip, parked ? GREEN_SOFT : ORANGE_SOFT, parked ? GREEN : ORANGE);
        } else {
            safetyChip.setText(sessionStopped ? "○ 공유 종료" : "연결 중");
            chipStyle(safetyChip, PALE, MUTED);
        }

        nativeContainer.setVisibility(View.VISIBLE);
        renderNative(visibleTab);
    }

    private void renderNative(int visibleTab) {
        nativeContainer.removeAllViews();
        playButton = null; audioStatus = null; sceneTitle = null; sceneText = null; spriteView = null; stageImage = null; npcView = null; propsBox = null; stagePicture = null;
        stopSpriteAnimation();

        if (sessionStopped || (service != null && service.isStopped())) {
            statusCard("가족 여행 공유가 종료되었어요", "다시 시작하면 새 초대 코드와 가족 연결이 열려요. 저장된 여행은 그대로 남아 있어요.",
                "공유 다시 시작", this::restartSession, null, null);
            return;
        }
        if (service != null && service.initError() != null) {
            statusCard("세션을 시작하지 못했어요", service.initError(), "다시 시작", this::restartSession, "닫기", this::finish);
            return;
        }
        if (!isServiceReady()) {
            statusCard("차량 세션을 연결하고 있어요", "가족 연결과 음성 안내를 준비하는 중이에요.", null, null, null, null);
            return;
        }
        try {
            JSONObject data = service.store().snapshot();
            if (visibleTab == TAB_ROUTE) renderRoute(data);
            else if (visibleTab == TAB_GUIDE) renderGuide(data);
            else renderSettings(data);
        } catch (Exception e) {
            android.util.Log.e("FamilyStory", "Render failed", e);
            statusCard("화면을 불러오지 못했어요", String.valueOf(e.getMessage()), "새로고침", this::renderAll, null, null);
        }
    }

    private void statusCard(String title, String body, String primaryLabel, Runnable primaryClick, String secondaryLabel, Runnable secondaryClick) {
        LinearLayout box = card(CARD, true);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(text(title, 30, INK, true));
        space(box, 10);
        box.addView(text(body, 18, MUTED, false));
        if (primaryLabel != null || secondaryLabel != null) {
            space(box, 24);
            LinearLayout actions = row();
            box.addView(actions);
            if (primaryLabel != null) actions.addView(button(primaryLabel, true, primaryClick), new LinearLayout.LayoutParams(-2, dp(72)));
            if (primaryLabel != null && secondaryLabel != null) spaceH(actions, 12);
            if (secondaryLabel != null) actions.addView(button(secondaryLabel, false, secondaryClick), new LinearLayout.LayoutParams(-2, dp(72)));
        }
        nativeContainer.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---------------------------------------------------------------- 경로: the trip in progress

    private void renderRoute(JSONObject data) throws Exception {
        boolean parked = parked();
        String status = data.optString("status", "planning");
        JSONArray stops = data.getJSONArray("stops"), visited = data.getJSONArray("visited");
        String selected = data.optString("selected", "");
        JSONObject next = selected.isEmpty() ? null : service.store().place(selected);
        boolean allVisited = stops.length() > 0 && visited.length() >= stops.length();

        if (!parked) {   // driving: one glance, one action
            LinearLayout box = card(CARD, true);
            box.addView(label("active".equals(status) ? "다음 방문지" : "여행 준비 중"));
            space(box, 6);
            box.addView(text(next == null ? data.optString("title") : next.optString("name"), 52, INK, true));
            space(box, 8);
            box.addView(text("active".equals(status) ? visited.length() + " / " + stops.length() + "곳 방문" : "정차하면 갈 곳을 고를 수 있어요.", 24, MUTED, false));
            View strip = journeyStrip(true);
            if (strip != null) { space(box, 22); box.addView(strip); }
            box.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            audioStatus = text(service.player().status, 18, MUTED, false);
            box.addView(audioStatus);
            space(box, 12);
            boolean hasDocent = next != null && next.optBoolean("hasDocent", false);
            playButton = button("▶ 안내 듣기", true, () -> { if (isServiceReady()) service.player().toggle(); });
            playButton.setTextSize(24 * scale);
            playButton.setEnabled(hasDocent);
            box.addView(playButton, new LinearLayout.LayoutParams(-1, dp(104)));
            nativeContainer.addView(box, new FrameLayout.LayoutParams(-1, -1));
            updateAudio();
            return;
        }

        boolean done = "completed".equals(status);
        boolean heading = "active".equals(status) && next != null && !TripStore.contains(visited, selected);
        LinearLayout page = column();
        LinearLayout hero = card(heading ? PRIMARY_SOFT : CARD, true);
        hero.setPadding(dp(28), dp(18), dp(20), dp(18));
        page.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout heroRow = row();
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(heroRow);
        LinearLayout words = column();
        heroRow.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (heading) {
            words.addView(text("지금 가는 곳", 18, PRIMARY, true));
            space(words, 2);
            TextView name = text(next.optString("name"), 40, INK, true);
            name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            View strip = journeyStrip(false);
            if (strip != null) { space(words, 8); words.addView(strip); }
        } else {
            String title = done ? "여행을 마쳤어요" : stops.length() == 0 ? "가고 싶은 곳이 아직 없어요" : allVisited ? "모든 곳을 다녀왔어요" : "어디로 갈까요?";
            String help = done ? visited.length() + "곳을 다녀왔어요. 기록에 보관했어요."
                : stops.length() == 0 ? "가족 휴대폰에서 장소를 담거나 내비에서 가져오면 여기에 나와요."
                : allVisited ? "다시 가고 싶은 곳을 누르거나 여행을 마칠 수 있어요." : "갈 곳을 누르면 내비 안내를 시작해요.";
            words.addView(text(title, 34, INK, true));
            space(words, 4);
            words.addView(text(help, 18, MUTED, false));
        }
        spaceH(heroRow, 16);
        if (done) heroRow.addView(button("새 여행", true, this::newTrip), new LinearLayout.LayoutParams(dp(220), dp(76)));
        else if (allVisited) heroRow.addView(button("여행 마치기", true, this::confirmFinish), new LinearLayout.LayoutParams(dp(220), dp(76)));
        else heroRow.addView(button("내비에서 가져오기", false, this::importFromNavi), new LinearLayout.LayoutParams(dp(260), dp(76)));

        space(page, 14);
        int per = 6, pages = Math.max(1, (stops.length() + per - 1) / per);
        stopPage = Math.max(0, Math.min(stopPage, pages - 1));
        LinearLayout grid = column();
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int r = 0; r < 3; r++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, 0, 1);
            if (r > 0) lineLp.topMargin = dp(12);
            grid.addView(line, lineLp);
            for (int c = 0; c < 2; c++) {
                int i = stopPage * per + r * 2 + c;
                if (c > 0) spaceH(line, 12);
                if (i >= stops.length()) { line.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1)); continue; }
                String id = stops.getString(i);
                boolean seen = TripStore.contains(visited, id);
                line.addView(placeCell(i + 1, service.store().place(id), seen, heading && id.equals(selected), !done), new LinearLayout.LayoutParams(0, -1, 1));
            }
        }
        if (pages > 1) { space(page, 10); page.addView(pager(stopPage, pages, d -> { stopPage += d; renderAll(); })); }
        nativeContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    /** One place on the list: a large tile; tapping it makes it the destination. */
    private View placeCell(int number, JSONObject place, boolean seen, boolean current, boolean tappable) {
        LinearLayout cell = row();
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(current ? PRIMARY_SOFT : CARD); shape.setCornerRadius(dp(22));
        shape.setStroke(dp(current ? 3 : 1), current ? PRIMARY : BORDER);
        cell.setBackground(shape);
        TextView badge = text(seen ? "✓" : String.valueOf(number), 28, seen ? GREEN : current ? PRIMARY : MUTED, true);
        badge.setGravity(Gravity.CENTER);
        cell.addView(badge, new LinearLayout.LayoutParams(dp(52), -2));
        spaceH(cell, 10);
        LinearLayout words = column();
        cell.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text(place.optString("name"), 26, INK, true);
        name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        space(words, 4);
        TextView sub = text(current ? "가는 중" : seen ? "다녀왔어요" : place.optString("address", ""), 16, current ? PRIMARY : seen ? GREEN : MUTED, current || seen);
        sub.setMaxLines(1); sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(sub);
        final String id = place.optString("id");
        cell.setContentDescription(place.optString("name") + (current ? ", 가는 중" : seen ? ", 다녀온 곳" : ", 누르면 목적지로 정해요"));
        if (tappable) cell.setOnClickListener(v -> goTo(id));
        return cell;
    }

    /** One tap: this place becomes the destination and the vehicle navigation is asked to guide there. */
    private void goTo(String id) {
        if (!requireParked()) return;
        try {
            TripStore store = service.store();
            store.mutate("driver", new JSONObject().put("action", "go").put("placeId", id).put("revision", store.snapshot().getInt("revision")), true);
            JSONObject place = store.place(id);
            String name = place.optString("name");
            VehicleBridge bridge = service.bridge();
            if (bridge.demoParked != null) { notice(name + Journey.josa(name, "으로", "로") + " 가요. 모의 시연에서는 실제 내비를 바꾸지 않아요."); return; }
            try { bridge.requestRoute(place, store.snapshot().optInt("routeRevision")); notice(name + "까지 내비 안내를 요청했어요."); }
            catch (Exception e) { notice(name + Journey.josa(name, "으로", "로") + " 가요. 내비: " + e.getMessage()); }
        } catch (Exception e) { notice(e.getMessage()); }
    }

    private void newTrip() {
        if (!requireParked()) return;
        try {
            service.store().mutate("driver", new JSONObject().put("action", "newTrip")
                .put("title", new SimpleDateFormat("M월 d일 여행", Locale.KOREA).format(new Date()))
                .put("revision", service.store().snapshot().getInt("revision")), true);
            stopPage = 0;
            notice("새 여행을 열었어요. 가족 휴대폰이나 내비에서 갈 곳을 담아 주세요.");
        } catch (Exception e) { notice(e.getMessage()); }
    }

    private static String josa(String word) { return Journey.josa(word, "을", "를"); }

    private TextView naviChip(JSONObject place) {
        boolean bundled = !place.has("source");
        boolean synced = place.optBoolean("naviSynced", false);
        TextView chip = text(synced ? "내비 기준 장소" : bundled ? "내장 안내 장소" : "내비 확인 전 · 안내를 요청하면 내비 기준으로 맞춰요", 15, synced ? GREEN : bundled ? PRIMARY : ORANGE, true);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chipStyle(chip, synced ? GREEN_SOFT : bundled ? PRIMARY_SOFT : ORANGE_SOFT, synced ? GREEN : bundled ? PRIMARY : ORANGE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void driverAction(String action, String placeId, String done) {
        if (!requireParked()) return;
        try {
            JSONObject r = new JSONObject().put("action", action).put("revision", service.store().snapshot().getInt("revision"));
            if (placeId != null) r.put("placeId", placeId);
            service.store().mutate("driver", r, true);
            if (done != null) notice(done);
        } catch (Exception e) { notice(e.getMessage()); }
    }

    private void confirmFinish() {
        if (!requireParked()) return;
        sheet("여행을 마칠까요?", "마치면 기록으로 보관하고, 새 여행을 열 수 있어요.",
            new String[]{"여행 마치기"}, new Runnable[]{() -> driverAction("finishTrip", null, "여행을 기록에 보관했어요.")});
    }

    // ---------------------------------------------------------------- places from the vehicle navigation

    /** Places the navigation already knows (current destination, recent destinations) are added as navi-synced stops. */
    private void importFromNavi() {
        if (!requireParked()) return;
        VehicleBridge bridge = service.bridge();
        bridge.refreshNaviPlaces();
        final List<JSONObject> options = new ArrayList<>();
        if (bridge.naviDestination != null) options.add(bridge.naviDestination);
        for (int i = 0; i < bridge.naviRecent.length(); i++) {
            JSONObject r = bridge.naviRecent.optJSONObject(i);
            if (r == null) continue;
            boolean dup = false;
            for (JSONObject o : options) if (o.optString("name").equals(r.optString("name"))) dup = true;
            if (!dup) options.add(r);
        }
        if (options.isEmpty()) {
            if (!bridge.naviReady()) { notice("실제 내비가 연결되면 내비의 장소를 가져올 수 있어요."); return; }
            try { bridge.openNaviSearch(""); notice("내비에서 장소를 찾은 뒤 다시 '내비에서 가져오기'를 눌러 주세요."); }
            catch (Exception e) { notice(e.getMessage()); }
            return;
        }
        int count = Math.min(6, options.size());
        String[] labels = new String[count];
        Runnable[] picks = new Runnable[count];
        for (int i = 0; i < count; i++) {
            final JSONObject option = options.get(i);
            labels[i] = option.optString("name") + (i == 0 && bridge.naviDestination != null ? " · 현재 목적지" : "");
            picks[i] = () -> {
                try {
                    JSONObject r = new JSONObject().put("action", "addCustom").put("revision", service.store().snapshot().getInt("revision")).put("place", option);
                    service.store().mutate("driver", r, parked());
                    notice(option.optString("name") + josa(option.optString("name")) + " 목록에 담았어요.");
                } catch (Exception e) { notice(e.getMessage()); }
            };
        }
        sheet("내비에서 가져오기", "누르면 바로 목록에 담아요.", labels, picks);
    }

    // ---------------------------------------------------------------- 안내: docent player

    private void renderGuide(JSONObject data) throws Exception {
        boolean parked = parked();
        DocentPlayer player = service.player();
        String selected = data.optString("selected", "");
        JSONObject place = selected.isEmpty() ? null : service.store().place(selected);
        boolean hasDocent = place != null && place.optBoolean("hasDocent", false);
        boolean kids = service.isKidsMode();
        String guide = data.optString("guide", "cat"), guideName = data.optString("guideName", "나비");

        if (!parked) {   // driving: audio only
            LinearLayout box = card(CARD, true);
            box.addView(label(kids ? guideName + "의 이야기" : "음성 안내"));
            space(box, 6);
            box.addView(text(place == null ? "선택된 방문지 없음" : place.optString("name"), 52, INK, true));
            space(box, 8);
            box.addView(text("주행 중에는 화면 대신 소리로 안내해요.", 22, MUTED, false));
            View strip = journeyStrip(true);
            if (strip != null) { space(box, 22); box.addView(strip); }
            box.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            audioStatus = text(hasDocent ? player.status : "이 장소는 아직 안내가 없어요.", 18, MUTED, false);
            box.addView(audioStatus);
            space(box, 12);
            LinearLayout controls = row();
            box.addView(controls);
            playButton = button("▶ 안내 듣기", true, () -> { if (isServiceReady()) service.player().toggle(); });
            playButton.setTextSize(24 * scale);
            playButton.setEnabled(hasDocent);
            controls.addView(playButton, new LinearLayout.LayoutParams(0, dp(104), 1));
            spaceH(controls, 12);
            Button stop = button("■ 멈춤", false, () -> { if (isServiceReady()) service.player().stop(); });
            stop.setTextSize(22 * scale);
            controls.addView(stop, new LinearLayout.LayoutParams(dp(200), dp(104)));
            nativeContainer.addView(box, new FrameLayout.LayoutParams(-1, -1));
            updateAudio();
            return;
        }

        // One column: the story card takes the screen; the place, audience and player sit in one bar below it.
        LinearLayout page = column();
        LinearLayout story = card(CARD, true);
        // Picture above the words; only a very wide screen puts them side by side.
        int roomW = nativeContainer.getWidth() > 0 ? nativeContainer.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int roomH = nativeContainer.getHeight() > 0 ? nativeContainer.getHeight() : Math.round(getResources().getDisplayMetrics().heightPixels * 0.75f);
        final boolean wide = roomW > roomH * 1.9f;
        final int pictureHeight = Math.round(roomH * 0.54f);
        story.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        story.setPadding(dp(16), dp(16), dp(wide ? 24 : 16), dp(16));
        story.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        page.addView(story, new LinearLayout.LayoutParams(-1, 0, 1));
        // The whole 16:9 picture stays visible: as tall as the card allows, at most 62% of its width.
        FrameLayout stage = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                int height = wide ? MeasureSpec.getSize(h) : pictureHeight, room = MeasureSpec.getSize(w);
                int width = Math.min(Math.round(height * 16f / 9f), wide ? Math.round(room * 0.62f) : room);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.min(height, Math.round(width * 9f / 16f)), MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable stageBg = new GradientDrawable();
        stageBg.setColor(PRIMARY_SOFT); stageBg.setCornerRadius(dp(18));
        stage.setBackground(stageBg);
        stage.setClipToOutline(true);
        story.addView(stage, wide ? new LinearLayout.LayoutParams(-2, -1) : new LinearLayout.LayoutParams(-1, -2));
        Bitmap picture = place == null || service.content() == null ? null : service.content().bitmap(kids ? "kids" : "photo", selected, 1280);
        if (picture == null && place != null && kids) picture = assetBitmap("art/" + selected + ".png");
        if (picture != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(picture);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription(place.optString("name") + (kids ? " 동화 그림" : " 사진"));
            stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
            stageImage = image; stagePicture = picture;
        } else {
            TextView empty = text(place == null ? "경로에서 갈 곳을 골라 주세요" : kids ? "동화 그림은 여행을 시작하면 준비돼요" : "이 장소의 사진이 아직 없어요", 20, MUTED, true);
            empty.setGravity(Gravity.CENTER);
            stage.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        }
        if (kids && place != null) {
            spriteView = new ImageView(this);
            spriteView.setContentDescription("길잡이 " + guideName);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(190), dp(190), Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, dp(12), dp(4));
            stage.addView(spriteView, lp);
            // the place's own character stands on the left, the props a page mentions float above it
            npcView = new ImageView(this);
            npcView.setVisibility(View.GONE);
            FrameLayout.LayoutParams npcLp = new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM | Gravity.START);
            npcLp.setMargins(dp(14), 0, 0, dp(6));
            stage.addView(npcView, npcLp);
            propsBox = row();
            FrameLayout.LayoutParams propsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            propsLp.setMargins(0, dp(12), dp(14), 0);
            stage.addView(propsBox, propsLp);
        }
        if (wide) spaceH(story, 24); else space(story, 14);
        LinearLayout words = column();
        words.setPadding(wide ? 0 : dp(10), 0, wide ? 0 : dp(10), 0);
        story.addView(words, wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        sceneTitle = text("", 26, PRIMARY, true);
        sceneTitle.setMaxLines(wide ? 2 : 1); sceneTitle.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(sceneTitle);
        space(words, wide ? 10 : 6);
        sceneText = text("", 26, INK, false);
        sceneText.setLineSpacing(dp(6), 1f);
        sceneText.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(sceneText, new LinearLayout.LayoutParams(-1, 0, 1));

        space(page, 12);
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, 0, 0);
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(84)));
        LinearLayout title = column();
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text(place == null ? "갈 곳을 아직 고르지 않았어요" : place.optString("name"), 26, INK, true);
        name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(name);
        audioStatus = text(hasDocent ? player.status : place == null ? "경로에서 갈 곳을 골라 주세요." : "이 장소는 아직 안내가 없어요.", 15, MUTED, false);
        audioStatus.setMaxLines(1); audioStatus.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(audioStatus);
        spaceH(bar, 12);
        bar.addView(button("일반", !kids, () -> service.setKidsMode(false)), new LinearLayout.LayoutParams(dp(120), dp(76)));
        spaceH(bar, 8);
        bar.addView(button("키즈", kids, () -> service.setKidsMode(true)), new LinearLayout.LayoutParams(dp(120), dp(76)));
        spaceH(bar, 24);
        Button prev = button("◀", false, () -> { if (isServiceReady()) service.player().seek(service.player().paragraph() - 1); });
        prev.setContentDescription("이전 장면");
        bar.addView(prev, new LinearLayout.LayoutParams(dp(84), dp(76)));
        spaceH(bar, 8);
        playButton = button("▶ 듣기", true, () -> { if (isServiceReady()) service.player().toggle(); });
        playButton.setEnabled(hasDocent);
        bar.addView(playButton, new LinearLayout.LayoutParams(dp(220), dp(76)));
        spaceH(bar, 8);
        Button nextScene = button("▶", false, () -> { if (isServiceReady()) service.player().seek(service.player().paragraph() + 1); });
        nextScene.setContentDescription("다음 장면");
        bar.addView(nextScene, new LinearLayout.LayoutParams(dp(84), dp(76)));
        nativeContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));
        shownActivity = ""; shownScene = "";
        updateAudio();
    }

    /** Layers for one picture-book page: its scene cut, the place's character and the props it mentions. */
    private void showScene(String placeId, int index, JSONObject page) {
        if (stageImage == null) return;
        String key = placeId + "#" + index;
        if (key.equals(shownScene)) return;
        shownScene = key;
        Bitmap scene = art("media/cuts/" + placeId + "-" + page.optString("cut") + ".jpg");
        stageImage.setImageBitmap(scene != null ? scene : stagePicture);
        JSONObject npc = page.optJSONObject("npc");
        Bitmap who = npc == null ? null : art("sprites/npc/" + npc.optString("id") + "/" + npc.optString("pose", "wave") + ".png");
        if (npcView != null) {
            npcView.setImageBitmap(who);
            npcView.setVisibility(who == null ? View.GONE : View.VISIBLE);
            npcView.setContentDescription(npc == null ? "" : npc.optString("name"));
        }
        if (propsBox != null) {
            propsBox.removeAllViews();
            JSONArray props = page.optJSONArray("props");
            for (int i = 0; props != null && i < props.length() && i < 3; i++) {
                Bitmap prop = art("props/" + props.optString(i) + ".png");
                if (prop == null) continue;
                ImageView view = new ImageView(this);
                view.setImageBitmap(prop);
                view.setRotation(i % 2 == 0 ? -6 : 6);
                propsBox.addView(view, new LinearLayout.LayoutParams(dp(96), dp(96)));
            }
        }
    }

    /** A bundled picture by path; names come from content, so only plain names are opened. */
    private Bitmap art(String path) {
        if (!path.matches("[a-z0-9/\\-]+\\.(png|jpg)")) return null;
        Bitmap cached = sprites.get(path);
        if (cached == null) { cached = assetBitmap(path); if (cached != null) sprites.put(path, cached); }
        return cached;
    }

    /** Syncs play state, the current scene text and the guide's activity sticker with the player. */
    private void updateAudio() {
        if (!active || !isServiceReady()) return;
        DocentPlayer player = service.player();
        if (audioStatus != null && player.length() > 0) audioStatus.setText(player.status);
        if (playButton != null) {
            boolean big = !parked();
            playButton.setText(player.isPlaying() ? (big ? "Ⅱ 잠시 멈춤" : "Ⅱ 멈춤") : player.paragraph() > 0 ? "▶ 이어 듣기" : (big ? "▶ 안내 듣기" : "▶ 듣기"));
            playButton.setEnabled(player.isReady() && player.length() > 0);
        }
        if (sceneTitle == null) return;
        try {
            JSONObject data = service.store().snapshot();
            String selected = data.optString("selected", "");
            if (selected.isEmpty()) return;
            JSONObject place = service.store().place(selected);
            boolean kids = service.isKidsMode();
            String guideName = data.optString("guideName", "나비");
            if (!player.interlude().isEmpty()) {   // between stops: preview, quiz, look-out, mission
                sceneTitle.setText(Journey.label(player.interlude()));
                sceneText.setText(player.line());
                if (spriteView != null) showSprite(data.optString("guide", "cat"), Journey.activity(player.interlude()), true);
                if (Journey.PROPOSAL.equals(player.interlude())) sceneTitle.setText("가족 제안");
                return;
            }
            JSONArray pages = place.optJSONArray(kids ? "kids" : "adult");
            int count = pages == null ? 0 : pages.length();
            int index = Math.max(0, Math.min(player.paragraph(), count - 1));
            if (count == 0) { sceneTitle.setText(""); sceneText.setText(place.optString("summary", "")); return; }
            String activity = "wave";
            if (kids) {
                JSONObject page = pages.getJSONObject(index);
                sceneTitle.setText((index + 1) + " / " + count + "  " + page.optString("title").replace("{g}", guideName));
                sceneText.setText(page.optString("text").replace("{g}", guideName));
                activity = page.optString("activity", ContentService.activityFor(page.optString("text")));
                showScene(selected, index, page);
            } else {
                sceneTitle.setText((index + 1) + " / " + count);
                sceneText.setText(pages.optString(index));
            }
            if (spriteView != null) showSprite(data.optString("guide", "cat"), activity, player.isPlaying());
        } catch (Exception ignored) {}
    }

    private void showSprite(String guide, String activity, boolean lively) {
        String key = guide + "/" + activity;
        if (!key.equals(shownActivity)) {
            shownActivity = key;
            Bitmap bitmap = sprites.get(key);
            if (bitmap == null) { bitmap = assetBitmap("sprites/" + key + ".png"); if (bitmap != null) sprites.put(key, bitmap); }
            spriteView.setImageBitmap(bitmap);
            stopSpriteAnimation();
            spriteView.setRotation(0); spriteView.setTranslationX(0); spriteView.setTranslationY(0); spriteView.setScaleX(1); spriteView.setScaleY(1);
            spriteAnimator = spriteAnimation(spriteView, activity);
            spriteAnimator.start();
        }
        if (spriteAnimator != null) { if (lively) spriteAnimator.resume(); }
    }

    /** One small looping motion per activity type; honours the system "remove animations" setting. */
    private ValueAnimator spriteAnimation(View v, String activity) {
        ObjectAnimator a;
        float d = getResources().getDisplayMetrics().density * scale;
        switch ("surprise".equals(activity) ? "cheer" : "sleep".equals(activity) ? "stargaze" : "eat".equals(activity) ? "read" : "listen".equals(activity) ? "think" : activity) {
            case "wave": v.setPivotX(dp(105)); v.setPivotY(dp(200)); a = ObjectAnimator.ofFloat(v, "rotation", -5f, 5f); a.setDuration(520); break;
            case "walk": a = ObjectAnimator.ofFloat(v, "translationX", -14 * d, 14 * d); a.setDuration(900); break;
            case "cheer": a = ObjectAnimator.ofFloat(v, "translationY", 0, -26 * d); a.setDuration(380); break;
            case "think": v.setPivotX(dp(105)); v.setPivotY(dp(200)); a = ObjectAnimator.ofFloat(v, "rotation", -3f, 3f); a.setDuration(1400); break;
            case "stargaze": a = ObjectAnimator.ofFloat(v, "translationY", 0, -8 * d); a.setDuration(1500); break;
            case "photo": a = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.06f); a.setDuration(450); a.addUpdateListener(x -> v.setScaleY((float) x.getAnimatedValue())); break;
            case "read": a = ObjectAnimator.ofFloat(v, "translationY", 0, -6 * d); a.setDuration(1200); break;
            default: a = ObjectAnimator.ofFloat(v, "translationX", 0, 10 * d); a.setDuration(700); break;
        }
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        return a;
    }

    private void stopSpriteAnimation() {
        if (spriteAnimator != null) { spriteAnimator.cancel(); spriteAnimator = null; }
        shownActivity = "";
    }

    private Bitmap assetBitmap(String path) {
        try (InputStream in = getAssets().open(path)) { return BitmapFactory.decodeStream(in); }
        catch (Exception ignored) { return null; }
    }

    // ---------------------------------------------------------------- 설정: 가족 · 기록 · 음성·안내 · 연동

    private void renderSettings(JSONObject data) throws Exception {
        LinearLayout page = column();
        LinearLayout tabs = row();
        page.addView(tabs);
        for (int i = 0; i < SETTINGS_LABELS.length; i++) {
            final int index = i;
            tabs.addView(button(SETTINGS_LABELS[i], i == settingsTab, () -> { settingsTab = index; openRecordId = ""; renderAll(); }),
                new LinearLayout.LayoutParams(0, dp(64), 1));
            if (i < SETTINGS_LABELS.length - 1) spaceH(tabs, 8);
        }
        space(page, 12);
        FrameLayout body = new FrameLayout(this);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (settingsTab == 0) body.addView(familySettings(data));
        else if (settingsTab == 1) body.addView(recordSettings(data));
        else if (settingsTab == 2) body.addView(voiceSettings(data));
        else body.addView(linkSettings());
        nativeContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private View familySettings(JSONObject data) throws Exception {
        LinearLayout panels = row();
        FamilyServer server = service.server();
        LinearLayout invite = card(CARD, true);
        panels.addView(invite, new LinearLayout.LayoutParams(0, -1, 1));
        invite.addView(text("가족 초대", 24, INK, true));
        space(invite, 6);
        invite.addView(text("휴대폰 Family Story 앱에서 QR을 찍거나 주소와 코드를 입력해요. 같은 Wi-Fi여야 해요.", 16, MUTED, false));
        space(invite, 12);
        String address = server == null ? "연결 서버를 열지 못했어요" : server.localAddress();
        String code = server == null ? "" : server.invitation;
        invite.addView(label("차량 주소"));
        TextView addressView = text(address, 22, PRIMARY, true);
        addressView.setTextIsSelectable(true);
        invite.addView(addressView);
        space(invite, 8);
        invite.addView(label("초대 코드"));
        TextView codeView = text(code, 34, INK, true);
        codeView.setLetterSpacing(0.2f);
        codeView.setTextIsSelectable(true);
        invite.addView(codeView);
        if (server != null) {
            // Scan with the phone app (or the phone camera) to join without typing.
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrBitmap("familystory://join?h=" + address.replace("http://", "") + "&c=" + code, 420));
            qr.setContentDescription("가족 초대 QR 코드");
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(-1, 0, 1);
            qrLp.setMargins(0, dp(8), 0, dp(8));
            invite.addView(qr, qrLp);
        } else invite.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout actions = row();
        invite.addView(actions);
        if (server != null) {
            actions.addView(button("초대 링크 복사", true, () -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Family Story 초대", address + "/phone#" + code));
                notice("초대 링크를 복사했어요.");
            }), new LinearLayout.LayoutParams(0, dp(64), 1));
            spaceH(actions, 8);
        }
        actions.addView(styledButton("공유 종료", CARD, DANGER, DANGER, this::confirmStopSession), new LinearLayout.LayoutParams(-2, dp(64)));

        spaceH(panels, 16);
        LinearLayout list = card(PALE, true);
        panels.addView(list, new LinearLayout.LayoutParams(0, -1, 1.35f));
        JSONArray members = data.getJSONArray("members");
        list.addView(text("가족 기기 " + members.length() + " / 8", 24, INK, true));
        list.addView(text("권한과 안내 유형(일반·키즈)은 이 차량에서만 정해요.", 15, MUTED, false));
        space(list, 10);
        int per = 3, pages = Math.max(1, (members.length() + per - 1) / per);
        memberPage = Math.max(0, Math.min(memberPage, pages - 1));
        if (members.length() == 0) list.addView(text("아직 연결된 가족이 없어요.", 18, MUTED, false));
        for (int i = memberPage * per; i < Math.min(members.length(), memberPage * per + per); i++) {
            JSONObject member = members.getJSONObject(i);
            String id = member.getString("id");
            boolean kid = "kids".equals(member.optString("audience"));
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(14), dp(8), dp(8), dp(8));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(CARD); shape.setCornerRadius(dp(16)); shape.setStroke(dp(1), BORDER);
            line.setBackground(shape);
            TextView name = text(member.getString("name"), 20, INK, true);
            name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(button(roleLabel(member.getString("role")), false, () -> roles(id)), new LinearLayout.LayoutParams(dp(150), dp(64)));
            spaceH(line, 8);
            line.addView(button(kid ? "키즈 안내" : "일반 안내", kid, () -> {
                if (!requireParked()) return;
                try { service.store().audience(id, kid ? "general" : "kids", true); } catch (Exception e) { notice(e.getMessage()); }
            }), new LinearLayout.LayoutParams(dp(150), dp(64)));
            list.addView(line, new LinearLayout.LayoutParams(-1, dp(84)));
            space(list, 8);
        }
        list.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        if (pages > 1) list.addView(pager(memberPage, pages, d -> { memberPage += d; renderAll(); }));
        return panels;
    }

    private View recordSettings(JSONObject data) throws Exception {
        JSONArray records = data.getJSONArray("records");
        LinearLayout box = card(CARD, true);
        JSONObject open = null;
        for (int i = 0; i < records.length(); i++) if (openRecordId.equals(records.getJSONObject(i).optString("tripId"))) open = records.getJSONObject(i);
        if (open != null) {
            LinearLayout head = row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            box.addView(head);
            head.addView(button("◀ 목록", false, () -> { openRecordId = ""; renderAll(); }), new LinearLayout.LayoutParams(-2, dp(64)));
            spaceH(head, 12);
            TextView title = text(open.optString("title"), 24, INK, true);
            title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            space(box, 8);
            box.addView(text("시작 " + time(open, "startedAt") + "  ·  종료 " + time(open, "completedAt"), 16, MUTED, false));
            space(box, 10);
            JSONArray stops = open.optJSONArray("stops"), events = open.optJSONArray("visitEvents");
            Map<String, Long> done = new HashMap<>();
            if (events != null) for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i), p = e.optJSONObject("place");
                if (p != null) done.put(p.optString("id"), e.isNull("completedAt") ? 0L : e.optLong("completedAt"));
            }
            for (int i = 0; stops != null && i < Math.min(stops.length(), 6); i++) {
                JSONObject p = stops.getJSONObject(i);
                Long at = done.get(p.optString("id"));
                TextView line = text((i + 1) + ". " + p.optString("name") + "   " + (at == null ? "방문 기록 없음" : "방문 " + (at == 0 ? "시각 기록 없음" : format(at))), 18, at == null ? MUTED : GREEN, at != null);
                line.setMaxLines(1); line.setEllipsize(TextUtils.TruncateAt.END);
                line.setPadding(0, dp(7), 0, dp(7));
                box.addView(line);
            }
            if (stops != null && stops.length() > 6) box.addView(text("외 " + (stops.length() - 6) + "곳", 16, MUTED, false));
            box.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            box.addView(text("기록은 계획 경로와 방문 완료 시각만 보관해요. GPS 이동 궤적은 저장하지 않아요.", 14, MUTED, false));
            return box;
        }
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top);
        top.addView(text("여행 기록", 24, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if ("active".equals(data.optString("status"))) top.addView(button("지금 여행 마치기", false, this::confirmFinish), new LinearLayout.LayoutParams(-2, dp(64)));
        space(box, 10);
        if (records.length() == 0) { box.addView(text("아직 완료한 여행이 없어요. 여행을 마치면 여기에 남아요.", 18, MUTED, false)); return box; }
        int per = 4, pages = (records.length() + per - 1) / per;
        recordPage = Math.max(0, Math.min(recordPage, pages - 1));
        for (int i = recordPage * per; i < Math.min(records.length(), recordPage * per + per); i++) {
            JSONObject r = records.getJSONObject(records.length() - 1 - i);
            JSONArray events = r.optJSONArray("visitEvents"), stops = r.optJSONArray("stops");
            Button line = button(r.optString("title") + "   ·   " + (stops == null ? 0 : stops.length()) + "곳 중 " + (events == null ? 0 : events.length()) + "곳 방문   ·   " + time(r, "completedAt"),
                false, () -> { openRecordId = r.optString("tripId"); renderAll(); });
            line.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            line.setMaxLines(1); line.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(line, new LinearLayout.LayoutParams(-1, dp(72)));
            space(box, 8);
        }
        box.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        if (pages > 1) box.addView(pager(recordPage, pages, d -> { recordPage += d; renderAll(); }));
        return box;
    }

    private View voiceSettings(JSONObject data) {
        LinearLayout panels = row();
        String voice = data.optString("voice", "female");
        for (String option : new String[]{"female", "male"}) {
            boolean on = option.equals(voice);
            LinearLayout choice = card(on ? PRIMARY_SOFT : CARD, true);
            choice.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView face = new ImageView(this);
            face.setImageBitmap(assetBitmap("sprites/" + ("male".equals(option) ? "dog" : "cat") + "/wave.png"));
            face.setContentDescription("male".equals(option) ? "강아지 보리" : "고양이 나비");
            choice.addView(face, new LinearLayout.LayoutParams(-1, 0, 1));
            choice.addView(text("male".equals(option) ? "보리 · 남성 목소리" : "나비 · 여성 목소리", 22, INK, true));
            space(choice, 10);
            choice.addView(button(on ? "✓ 사용 중" : "이 목소리로", on, () -> service.store().setVoice(option)), new LinearLayout.LayoutParams(-1, dp(64)));
            panels.addView(choice, new LinearLayout.LayoutParams(0, -1, 1));
            spaceH(panels, 16);
        }
        LinearLayout extra = card(PALE, true);
        panels.addView(extra, new LinearLayout.LayoutParams(0, -1, 1.2f));
        VehicleBridge bridge = service.bridge();
        extra.addView(text("안내 설정", 24, INK, true));
        space(extra, 6);
        extra.addView(text("목소리를 바꾸면 키즈 안내의 길잡이 캐릭터도 함께 바뀌어요.", 16, MUTED, false));
        space(extra, 14);
        extra.addView(button(bridge.autoDocent ? "✓ 이동 중·도착 때 자동으로 안내" : "이동 중·도착 때 자동으로 안내", bridge.autoDocent, () -> {
            if (!requireParked()) return;
            bridge.autoDocent = !bridge.autoDocent; renderAll();
        }), new LinearLayout.LayoutParams(-1, dp(64)));
        space(extra, 14);
        ContentService content = service.content();
        String status = content == null ? "" : content.status;
        extra.addView(text(status.isEmpty() ? "사진과 동화 그림은 여행을 시작할 때 한 번 준비하고, 그 뒤에는 인터넷 없이 보여 줘요." : status, 16, MUTED, false));
        extra.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        Button prepare = button("안내 자료 지금 준비", false, () -> {
            try { service.content().prepare(service.store().snapshot(), true); notice("안내 자료를 준비하고 있어요."); } catch (Exception e) { notice(e.getMessage()); }
        });
        prepare.setEnabled(content != null && !content.isBusy());
        extra.addView(prepare, new LinearLayout.LayoutParams(-1, dp(64)));
        return panels;
    }

    private View linkSettings() {
        LinearLayout panels = row();
        VehicleBridge bridge = service.bridge();
        LinearLayout info = card(CARD, true);
        panels.addView(info, new LinearLayout.LayoutParams(0, -1, 1.2f));
        info.addView(text("차량 연동 상태", 24, INK, true));
        space(info, 10);
        for (String msg : new String[]{"차량: " + bridge.gearStatus, "내비: " + bridge.naviStatus, "위치: " + bridge.locationStatus,
            "도착 이벤트: " + bridge.event, "음성: " + service.player().status}) {
            TextView line = text(msg, 17, MUTED, false);
            line.setMaxLines(1); line.setEllipsize(TextUtils.TruncateAt.END);
            line.setPadding(0, dp(5), 0, dp(5));
            info.addView(line);
        }
        info.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout perms = row();
        info.addView(perms);
        boolean needLoc = checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED;
        boolean needNoti = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (needLoc) {
            perms.addView(button("위치 접근 허용", true, () -> { if (requireParked()) requestPermissions(new String[]{"pleos.car.permission.FUSED_LOCATION"}, 41); }), new LinearLayout.LayoutParams(-2, dp(64)));
            spaceH(perms, 8);
        }
        if (needNoti) {
            perms.addView(button("알림 허용", false, () -> { if (requireParked()) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42); }), new LinearLayout.LayoutParams(-2, dp(64)));
            spaceH(perms, 8);
        }
        perms.addView(button("새로고침", false, () -> { bridge.refreshNaviPlaces(); renderAll(); }), new LinearLayout.LayoutParams(-2, dp(64)));

        spaceH(panels, 16);
        LinearLayout demo = card(PALE, true);
        panels.addView(demo, new LinearLayout.LayoutParams(0, -1, 1));
        demo.addView(text("에뮬레이터 모의 시연", 22, INK, true));
        space(demo, 6);
        demo.addView(text(bridge.emulator ? "앱 안에서만 흉내 내는 상태예요. 실제 차량 신호나 내비 결과가 아니에요." : "실제 차량에서는 모의 시연을 쓸 수 없어요.", 15, MUTED, false));
        space(demo, 12);
        if (bridge.emulator) {
            LinearLayout r1 = row();
            demo.addView(r1);
            r1.addView(button("모의 정차", Boolean.TRUE.equals(bridge.demoParked), () -> { bridge.demoParked = true; renderAll(); }), new LinearLayout.LayoutParams(0, dp(64), 1));
            spaceH(r1, 8);
            r1.addView(button("모의 주행", Boolean.FALSE.equals(bridge.demoParked), () -> { bridge.demoParked = false; renderAll(); }), new LinearLayout.LayoutParams(0, dp(64), 1));
            space(demo, 8);
            LinearLayout r2 = row();
            demo.addView(r2);
            r2.addView(button("모의 접근", false, () -> { try { bridge.demoApproach(); notice("접근 · 안내를 자동으로 시작해요."); } catch (Exception e) { notice(e.getMessage()); } }), new LinearLayout.LayoutParams(0, dp(64), 1));
            spaceH(r2, 8);
            r2.addView(button("모의 도착", false, () -> { try { bridge.demoArrive(); notice("도착 · 방문을 기록하고 다음 방문지로 넘어가요."); } catch (Exception e) { notice(e.getMessage()); } }), new LinearLayout.LayoutParams(0, dp(64), 1));
            space(demo, 8);
            LinearLayout r3 = row();
            demo.addView(r3);
            r3.addView(button(bridge.demoDriving() ? "이동 중…" : "모의 이동 · 2분", bridge.demoDriving(), () -> {
                try { bridge.demoDrive(); selectedTab = TAB_ROUTE; renderAll(); notice("모의 이동 · 출발 안내와 도착 안내가 차례로 나와요."); }
                catch (Exception e) { notice(e.getMessage()); }
            }), new LinearLayout.LayoutParams(0, dp(64), 1));
            spaceH(r3, 8);
            r3.addView(button("실제 SDK", bridge.demoParked == null, () -> { bridge.demoParked = null; renderAll(); }), new LinearLayout.LayoutParams(0, dp(64), 1));
        }
        return panels;
    }

    private Bitmap qrBitmap(String content, int size) {
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? 0xFF16324F : 0xFFFFFFFF;
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) { return null; }
    }

    private interface PageTurn { void by(int delta); }

    private LinearLayout pager(int page, int pages, PageTurn turn) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = button("◀ 이전", false, () -> turn.by(-1));
        prev.setEnabled(page > 0);
        line.addView(prev, new LinearLayout.LayoutParams(-2, dp(64)));
        TextView count = text((page + 1) + " / " + pages, 16, MUTED, true);
        count.setGravity(Gravity.CENTER);
        line.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        Button next = button("다음 ▶", false, () -> turn.by(1));
        next.setEnabled(page < pages - 1);
        line.addView(next, new LinearLayout.LayoutParams(-2, dp(64)));
        return line;
    }

    private static String time(JSONObject o, String key) { return o.isNull(key) ? "시각 기록 없음" : format(o.optLong(key)); }
    private static String format(long millis) { return new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date(millis)); }

    // ---------------------------------------------------------------- dialogs

    private void roles(String id) {
        if (!requireParked()) return;
        String[] labels = {"보기만", "장소 담기", "담기와 정리", "연결 해제"};
        String[] values = {"viewer", "proposer", "editor", "revoked"};
        Runnable[] picks = new Runnable[values.length];
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            picks[i] = () -> {
                try { if (isServiceReady()) service.store().role(id, value, service.bridge().parked()); }
                catch (Exception e) { notice(e.getMessage()); }
            };
        }
        sheet("가족 기기 권한", "연결하면 바로 장소를 담을 수 있어요. 바꿀 때만 골라 주세요.", labels, picks);
    }

    private void confirmStopSession() {
        if (!requireParked()) return;
        sheet("가족 공유를 종료할까요?", "연결된 가족 기기가 모두 끊겨요. 저장된 여행과 기록은 그대로 남아요.",
            new String[]{"공유 종료"}, new Runnable[]{() -> { if (service != null) service.stopSessionAndExit(); }});
    }

    private void demoMenu() {
        if (!isServiceReady() || !service.bridge().emulator) return;
        VehicleBridge bridge = service.bridge();
        sheet("에뮬레이터 모의 시연", "앱 안에서만 흉내 내는 상태예요. 실제 차량 신호와 내비 결과로 쓰지 마세요.",
            new String[]{"모의 정차", "모의 주행", "실제 SDK"},
            new Runnable[]{() -> { bridge.demoParked = true; renderAll(); }, () -> { bridge.demoParked = false; renderAll(); }, () -> { bridge.demoParked = null; renderAll(); }});
    }

    private boolean requireParked() {
        if (parked()) return true;
        notice("정차 확인 후 이용해 주세요.");
        return false;
    }

    private void dismissDialog() {
        if (sheetLayer == null) return;
        sheetLayer.removeAllViews();
        sheetLayer.setVisibility(View.GONE);
    }

    /**
     * Vehicle-style confirmation: a wide panel inside the app with large targets instead of a system dialog.
     * One or two choices sit in a single row next to "취소"; more choices become a two-column grid.
     */
    private void sheet(String title, String message, String[] labels, Runnable[] actions) {
        if (sheetLayer == null) return;
        dismissDialog();
        hideNotice.run();
        sheetLayer.setBackgroundColor(0x990B1220);
        sheetLayer.setOnClickListener(v -> dismissDialog());
        LinearLayout panel = card(CARD, false);
        panel.setPadding(dp(32), dp(28), dp(32), dp(28));
        panel.setElevation(dp(12));
        panel.setClickable(true);
        TextView heading = text(title, 30, INK, true);
        heading.setMaxLines(2); heading.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(heading);
        if (message != null && !message.isEmpty()) {
            space(panel, 10);
            TextView body = text(message, 20, MUTED, false);
            body.setMaxLines(6); body.setEllipsize(TextUtils.TruncateAt.END);
            panel.addView(body);
        }
        space(panel, 24);
        Button cancel = button("취소", false, this::dismissDialog);
        cancel.setTextSize(21 * scale);
        if (labels.length <= 2) {
            LinearLayout line = row();
            panel.addView(line);
            line.addView(cancel, new LinearLayout.LayoutParams(0, dp(88), 1));
            for (int i = 0; i < labels.length; i++) {
                final Runnable action = actions[i];
                spaceH(line, 12);
                Button choice = button(labels[i], i == labels.length - 1, () -> { dismissDialog(); action.run(); });
                choice.setTextSize(21 * scale);
                line.addView(choice, new LinearLayout.LayoutParams(0, dp(88), 1.4f));
            }
        } else {
            for (int i = 0; i < labels.length; i += 2) {
                LinearLayout line = row();
                panel.addView(line);
                for (int k = i; k < i + 2; k++) {
                    if (k > i) spaceH(line, 12);
                    if (k >= labels.length) { line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); continue; }
                    final Runnable action = actions[k];
                    Button choice = button(labels[k], false, () -> { dismissDialog(); action.run(); });
                    choice.setTextSize(20 * scale);
                    choice.setMaxLines(1); choice.setEllipsize(TextUtils.TruncateAt.END);
                    line.addView(choice, new LinearLayout.LayoutParams(0, dp(80), 1));
                }
                space(panel, 10);
            }
            space(panel, 6);
            panel.addView(cancel, new LinearLayout.LayoutParams(-1, dp(80)));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        lp.setMargins(dp(96), dp(16), dp(96), dp(16));
        sheetLayer.addView(panel, lp);
        sheetLayer.setVisibility(View.VISIBLE);
        panel.announceForAccessibility(title);
    }

    /** Status band at the top of the content area; large enough to read at a glance and gone after a few seconds. */
    private void notice(String message) {
        main.post(() -> {
            if (noticeBar == null || message == null || message.isEmpty()) return;
            noticeBar.setText(message);
            noticeBar.setVisibility(View.VISIBLE);
            main.removeCallbacks(hideNotice);
            main.postDelayed(hideNotice, 6000);
        });
    }

    /** Remaining time and distance to the selected stop with a progress bar. Null when nothing is known. */
    private View journeyStrip(boolean big) {
        JSONObject j = service.journey();
        int sec = j.optInt("remainSec", -1), meters = j.optInt("remainMeters", -1), start = j.optInt("startMeters", -1);
        if (sec < 0 || "arrived".equals(j.optString("stage"))) return null;
        LinearLayout box = column();
        String source = j.optString("source");
        String basis = "navi".equals(source) ? "내비 기준" : "gps".equals(source) ? "직선거리로 어림한 값" : "모의 이동";
        LinearLayout line = row();
        line.setGravity(Gravity.BOTTOM);
        line.addView(text(Journey.remainLabel(sec, meters), big ? 34 : 22, PRIMARY, true));
        spaceH(line, 12);
        line.addView(text("남음 · " + basis, big ? 18 : 14, MUTED, false));
        box.addView(line);
        space(box, big ? 10 : 6);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(start > 0 && meters >= 0 ? Math.max(20, Math.min(1000, Math.round(1000f * (start - meters) / start))) : 20);
        bar.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        bar.setContentDescription("도착까지 " + Journey.remainLabel(sec, meters));
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(big ? 14 : 10)));
        return box;
    }

    // ---------------------------------------------------------------- view helpers

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size * scale);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(4), 1.08f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 15, PRIMARY, true);
        t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private Button button(String label, boolean filled, Runnable click) {
        return styledButton(label, filled ? PRIMARY : CARD, filled ? Color.WHITE : INK, filled ? PRIMARY : BORDER, click);
    }

    private Button styledButton(String label, int bg, int fg, int stroke, Runnable click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18 * scale);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{0xFF94A3B8, fg}));
        b.setMinHeight(dp(64)); b.setMinimumHeight(dp(64));
        b.setMinWidth(dp(76)); b.setMinimumWidth(dp(76));
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), stroke);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26245AE8), shape, null));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void styleTabButton(Button b, boolean selected, boolean enabled) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(selected ? PRIMARY_SOFT : Color.TRANSPARENT);
        shape.setCornerRadius(dp(20));
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26245AE8), shape, null));
        b.setTextColor(enabled ? (selected ? PRIMARY : INK) : 0xFF94A3B8);
        b.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    }

    private void chipStyle(TextView chip, int bg, int fg) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(dp(14));
        chip.setBackground(shape);
        chip.setTextColor(fg);
    }

    private LinearLayout card(int color, boolean bordered) {
        LinearLayout c = column();
        c.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(24));
        if (bordered) shape.setStroke(dp(1), BORDER);
        c.setBackground(shape);
        return c;
    }

    private void space(LinearLayout p, int h) { p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h))); }
    private void spaceH(LinearLayout p, int w) { p.addView(new View(this), new LinearLayout.LayoutParams(dp(w), 1)); }
    private int dp(float v) { return Math.round(v * scale * getResources().getDisplayMetrics().density); }
    static String roleLabel(String role) { return role.equals("editor") ? "담기와 정리" : role.equals("proposer") ? "장소 담기" : "보기만"; }
}

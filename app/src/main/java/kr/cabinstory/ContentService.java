package kr.cabinstory;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prepares docent media once (at trip start) so the trip can run offline afterwards.
 * General photo and overview: 한국관광공사 TourAPI. Kids storybook background and kids script: Gemini.
 * Bundled demo media in assets/media is used first; generated files live in filesDir/media.
 * Only the public place name is sent upstream; no device names, tokens or visit times.
 */
public final class ContentService {
    private static final String TAG = "FamilyStory";
    private static final String TOUR = "https://apis.data.go.kr/B551011/KorService2/";
    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String TEXT_MODEL = "gemini-3.5-flash-lite";

    private final Context context;
    private final TripStore store;
    private final File root;
    private final Set<String> bundled = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "family-content"); t.setDaemon(true); return t; });
    private final Set<String> attempted = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean closed, busy;
    public volatile String status = "";
    public volatile Runnable onChange = () -> {};

    public ContentService(Context context, TripStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        root = new File(context.getFilesDir(), "media");
        for (String kind : new String[]{"photo", "kids"}) {
            new File(root, kind).mkdirs();
            try { String[] names = context.getAssets().list("media/" + kind); if (names != null) for (String n : names) bundled.add(kind + "/" + n); }
            catch (IOException ignored) {}
        }
    }

    public boolean has(String kind, String id) {
        return safe(id) && (bundled.contains(kind + "/" + id + ".jpg") || new File(root, kind + "/" + id + ".jpg").isFile());
    }

    public InputStream open(String kind, String id) throws IOException {
        if (!safe(id) || !("photo".equals(kind) || "kids".equals(kind))) throw new FileNotFoundException();
        File file = new File(root, kind + "/" + id + ".jpg");
        if (file.isFile()) return new FileInputStream(file);
        return context.getAssets().open("media/" + kind + "/" + id + ".jpg");
    }

    public Bitmap bitmap(String kind, String id, int maxWidth) {
        try (InputStream in = open(kind, id)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = maxWidth <= 700 ? 2 : 1;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) { return null; }
    }

    public boolean isBusy() { return busy; }
    public boolean configured() { return !BuildConfig.TOUR_API_KEY.isEmpty() || generates(); }
    /** Debug test route: an OpenAI-compatible server on the development PC instead of the Gemini API. */
    private static boolean local() { return !BuildConfig.LOCAL_LLM_URL.isEmpty(); }
    private static boolean generates() { return local() || !BuildConfig.GEMINI_API_KEY.isEmpty(); }

    /** Queue one pass over the trip's stops. Each place is tried once per session unless forced. */
    public void prepare(JSONObject snapshot, boolean force) {
        if (closed || snapshot == null) return;
        final List<JSONObject> places = new ArrayList<>();
        try {
            JSONArray stops = snapshot.getJSONArray("stops"), all = snapshot.getJSONArray("places");
            for (int i = 0; i < stops.length(); i++) for (int j = 0; j < all.length(); j++)
                if (stops.getString(i).equals(all.getJSONObject(j).getString("id"))) places.add(all.getJSONObject(j));
        } catch (JSONException e) { return; }
        if (force) attempted.clear();
        worker.execute(() -> {
            busy = true;
            int done = 0, made = 0;
            for (JSONObject place : places) {
                if (closed) break;
                done++;
                String id = place.optString("id"), name = place.optString("name");
                if (!safe(id) || name.isEmpty() || !attempted.add(id)) continue;
                update("안내 자료 준비 중 · " + done + " / " + places.size() + " · " + name);
                try { if (prepareOne(id, place)) made++; }
                catch (Exception e) { Log.w(TAG, "content " + id + ": " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            }
            busy = false;
            int missing = 0;
            for (JSONObject place : places) if (!has("photo", place.optString("id")) || !has("kids", place.optString("id"))) missing++;
            update(missing == 0 ? "안내 자료 준비 완료 · 오프라인에서도 볼 수 있어요" : "안내 자료 " + missing + "곳은 준비하지 못했어요 · 인터넷 연결 후 다시 시도");
            if (made > 0) store.mediaChanged();
        });
    }

    private boolean prepareOne(String id, JSONObject place) throws Exception {
        boolean changed = false;
        String name = place.optString("name");
        // Bundled places carry their own script. Others are written once, and again when the story kit specification is newer.
        boolean stale = store.hasContent(id) && store.contentKit(id) < StoryKit.VERSION && generates();
        boolean needText = (!store.hasContent(id) || stale) && (stale || !place.optBoolean("hasDocent", false));
        String overview = "";
        if ((!has("photo", id) || needText) && !BuildConfig.TOUR_API_KEY.isEmpty()) {
            long began = System.currentTimeMillis();
            JSONObject item = tourSearch(name);
            if (item != null) {
                String image = item.optString("firstimage", "");
                if (!has("photo", id) && image.startsWith("http")) {
                    byte[] raw = download(image.replace("http://", "https://"), 6_000_000);
                    if (saveJpeg(raw, new File(root, "photo/" + id + ".jpg"))) changed = true;
                }
                if (needText) overview = tourOverview(item.optString("contentid", ""));
            }
            Log.i(TAG, "timing source " + id + " " + (System.currentTimeMillis() - began) + " ms, overview " + overview.length() + " chars");
        }
        if (needText && !overview.isEmpty()) {
            JSONArray adult = paragraphs(overview);
            JSONObject kit = null;
            long began = System.currentTimeMillis();
            try { kit = storyKit(name, overview, adult); } catch (Exception e) { Log.w(TAG, "story kit fallback: " + e.getMessage()); }
            Log.i(TAG, "timing text " + id + " " + (System.currentTimeMillis() - began) + " ms, " + (kit == null ? "failed" : kit.optJSONArray("kids") == null ? "kit" : kit.optJSONArray("kids").length() + " pages")
                + " (" + (local() ? BuildConfig.LOCAL_LLM_TEXT_MODEL : TEXT_MODEL) + ")");
            if (kit == null && stale) return changed;   // keep what is already there rather than replace it with the short fallback
            if (kit == null) kit = StoryKit.fallback(name, adult);
            store.putContent(id, kit.put("adult", adult)
                .put("summary", adult.optString(0, "")).put("sourceTitle", "한국관광공사 TourAPI").put("sourceUrl", "https://korean.visitkorea.or.kr"));
            changed = true;
        }
        if (!has("kids", id) && generates()) {
            long began = System.currentTimeMillis();
            byte[] image = kidsImage(name, place.optString("address", ""));
            Log.i(TAG, "timing image " + id + " " + (System.currentTimeMillis() - began) + " ms, " + (image == null ? "none" : image.length + " bytes"));
            if (image != null && saveJpeg(image, new File(root, "kids/" + id + ".jpg"))) changed = true;
        }
        return changed;
    }

    private JSONObject tourSearch(String name) throws Exception {
        String url = TOUR + "searchKeyword2?serviceKey=" + BuildConfig.TOUR_API_KEY
            + "&MobileOS=AND&MobileApp=FamilyStory&_type=json&numOfRows=5&keyword=" + URLEncoder.encode(name, "UTF-8");
        JSONObject body = new JSONObject(new String(download(url, 400_000), StandardCharsets.UTF_8)).getJSONObject("response").getJSONObject("body");
        JSONObject items = body.optJSONObject("items");
        JSONArray list = items == null ? null : items.optJSONArray("item");
        if (list == null) return null;
        for (int i = 0; i < list.length(); i++) if (!list.getJSONObject(i).optString("firstimage").isEmpty()) return list.getJSONObject(i);
        return list.length() > 0 ? list.getJSONObject(0) : null;
    }

    private String tourOverview(String contentId) {
        if (!contentId.matches("\\d{1,12}")) return "";
        try {
            String url = TOUR + "detailCommon2?serviceKey=" + BuildConfig.TOUR_API_KEY
                + "&MobileOS=AND&MobileApp=FamilyStory&_type=json&contentId=" + contentId;
            JSONObject body = new JSONObject(new String(download(url, 400_000), StandardCharsets.UTF_8)).getJSONObject("response").getJSONObject("body");
            JSONArray list = body.getJSONObject("items").getJSONArray("item");
            String text = list.getJSONObject(0).optString("overview", "");
            return text.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        } catch (Exception e) { return ""; }
    }

    /** Split an overview into up to three TTS-sized paragraphs. */
    private static JSONArray paragraphs(String overview) {
        String[] sentences = overview.split("(?<=[.!?。])\\s+");
        JSONArray out = new JSONArray();
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            if (out.length() == 3) break;
            if (current.length() > 0 && current.length() + s.length() > 170) { out.put(current.toString().trim()); current.setLength(0); }
            current.append(s).append(' ');
        }
        if (out.length() < 3 && current.length() > 0) out.put(current.toString().trim());
        return out;
    }

    /** Pages, quizzes, look-out, recall and mission for one place, written to the StoryKit guideline and checked by it. */
    private JSONObject storyKit(String name, String overview, JSONArray adult) throws Exception {
        if (local()) {
            // No response schema on this route, so the shape is spelled out in the request.
            String shape = "\n\n[출력] 아래 모양의 JSON 객체 하나만 출력한다. 설명, 머리말, 코드 블록 표시는 쓰지 않는다.\n"
                + "{\"pages\":[{\"title\":\"\",\"text\":\"\",\"activity\":\"\"}],\"quizzes\":[{\"question\":\"\",\"option1\":\"\",\"option2\":\"\",\"answer\":1,\"explain\":\"\"}],"
                + "\"lookout\":\"\",\"recall\":\"\",\"mission\":\"\"}";
            String raw = localChat(BuildConfig.LOCAL_LLM_TEXT_MODEL, StoryKit.prompt(name, overview) + shape, false);
            int a = raw.indexOf("{\""), b = raw.lastIndexOf('}');
            if (a < 0) a = raw.indexOf("{\n");
            if (a < 0 || b <= a) throw new IOException("not JSON");
            return StoryKit.parse(raw.substring(a, b + 1), adult);
        }
        if (BuildConfig.GEMINI_API_KEY.isEmpty()) return null;
        JSONArray contents = new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", StoryKit.prompt(name, overview)))));
        JSONObject out;
        try {
            out = gemini(TEXT_MODEL, new JSONObject().put("contents", contents)
                .put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("responseSchema", StoryKit.schema())));
        } catch (IOException e) {
            if (!String.valueOf(e.getMessage()).endsWith(" 400")) throw e;
            // The model refused the schema: ask for plain JSON; StoryKit.parse still checks every field.
            out = gemini(TEXT_MODEL, new JSONObject().put("contents", contents)
                .put("generationConfig", new JSONObject().put("responseMimeType", "application/json")));
        }
        String text = out.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
        return StoryKit.parse(text, adult);
    }

    public static String activityFor(String text) {
        String t = text == null ? "" : text;
        if (t.matches(".*(별|하늘|달|망원경|올려다).*")) return "stargaze";
        if (t.matches(".*(사진|찰칵|카메라).*")) return "photo";
        if (t.matches(".*(먹|간식|맛있|냠냠).*")) return "eat";
        if (t.matches(".*(깜짝|놀랐|우와|세상에).*")) return "surprise";
        if (t.matches(".*(잠|쉬었|졸|꿈).*")) return "sleep";
        if (t.matches(".*(들어 봐|귀를|소리가|쉿).*")) return "listen";
        if (t.matches(".*(책|이야기책|읽).*")) return "read";
        if (t.matches(".*(신나|폴짝|만세|와!|외쳤).*")) return "cheer";
        if (t.matches(".*(궁금|생각|왜|어떻게|\\?).*")) return "think";
        if (t.matches(".*(걸|산책|길을|총총).*")) return "walk";
        if (t.matches(".*(안녕|반가).*")) return "wave";
        return "point";
    }

    private byte[] kidsImage(String name, String address) throws Exception {
        String prompt = "A warm children's storybook illustration of the real Korean travel place \"" + name + "\""
            + (address.isEmpty() ? "" : " located at " + address)
            + ". Soft gouache and colored-pencil picture-book texture, gentle rounded shapes, cozy pastel palette, friendly daytime mood, "
            + "wide landscape 16:9 composition with open empty ground space in the lower right for a character sticker. "
            + "No people, no animals, no characters, no text, no letters.";
        if (local()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("data:image/(?:png|jpeg|webp);base64,([A-Za-z0-9+/=]+)").matcher(localChat(BuildConfig.LOCAL_LLM_IMAGE_MODEL, prompt, true));
            return m.find() ? Base64.decode(m.group(1), Base64.DEFAULT) : null;
        }
        JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
            .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("IMAGE")));
        JSONObject out = gemini(BuildConfig.GEMINI_IMAGE_MODEL.replace("models/", ""), body);
        JSONArray parts = out.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
            if (inline != null) return Base64.decode(inline.getString("data"), Base64.DEFAULT);
        }
        return null;
    }

    /** One answer from the local server. Pictures only come over a streamed answer, so that is read as server-sent events. */
    private static String localChat(String model, String prompt, boolean stream) throws Exception {
        URL u = new URL(BuildConfig.LOCAL_LLM_URL + "/chat/completions");
        if (!"http".equals(u.getProtocol()) || !"10.0.2.2".equals(u.getHost())) throw new IOException("host");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(300000); c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.LOCAL_LLM_KEY);
            JSONObject body = new JSONObject().put("model", model).put("stream", stream)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("local HTTP " + code);
            String raw = new String(read(c.getInputStream(), 24_000_000), StandardCharsets.UTF_8);
            if (!stream) return new JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            StringBuilder all = new StringBuilder();
            for (String line : raw.split("\n")) {
                if (!line.startsWith("data: ") || line.trim().equals("data: [DONE]")) continue;
                try {
                    JSONObject delta = new JSONObject(line.substring(6)).getJSONArray("choices").getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    Object piece = delta.opt("content");
                    if (piece instanceof String) all.append((String) piece);
                    else if (piece instanceof JSONArray) for (int i = 0; i < ((JSONArray) piece).length(); i++) all.append(String.valueOf(((JSONArray) piece).opt(i)).replace("\\/", "/"));
                    JSONArray images = delta.optJSONArray("images");
                    if (images != null) all.append(images.toString().replace("\\/", "/"));
                } catch (JSONException ignored) {}
            }
            return all.length() > 0 ? all.toString() : raw;
        } finally { c.disconnect(); }
    }

    private JSONObject gemini(String model, JSONObject body) throws Exception {
        if (!model.matches("[a-z0-9.\\-]{3,60}")) throw new IOException("model");
        HttpURLConnection c = (HttpURLConnection) new URL(GEMINI + model + ":generateContent").openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(90000); c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY);
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Gemini HTTP " + code);
            return new JSONObject(new String(read(c.getInputStream(), 12_000_000), StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }

    private static byte[] download(String url, int max) throws Exception {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !Arrays.asList("apis.data.go.kr", "tong.visitkorea.or.kr").contains(u.getHost())) throw new IOException("host");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(6000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "FamilyStory/0.3 (Android; contest demo)");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream(), max);
        } finally { c.disconnect(); }
    }

    private static byte[] read(InputStream raw, int max) throws IOException {
        try (InputStream in = raw; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            for (int n, total = 0; (n = in.read(buffer)) != -1; ) {
                total += n;
                if (total > max) throw new IOException("too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Re-encode to a bounded JPEG so stored media is always a valid, modest image. */
    private static boolean saveJpeg(byte[] raw, File target) {
        if (raw == null) return false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (bitmap == null) return false;
        if (bitmap.getWidth() > 1280) bitmap = Bitmap.createScaledBitmap(bitmap, 1280, Math.round(bitmap.getHeight() * 1280f / bitmap.getWidth()), true);
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 84, out); }
        catch (IOException e) { return false; }
        return temp.renameTo(target);
    }

    private static boolean safe(String id) { return id != null && id.matches("[A-Za-z0-9\\-]{1,80}"); }
    private void update(String message) { status = message; Log.i(TAG, message); if (!closed) onChange.run(); }
    public void close() { closed = true; worker.shutdownNow(); }
}

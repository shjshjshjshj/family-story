package kr.cabinstory;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public place search and road routing: bounded HTTPS, small caches, one request per second per service. */
public final class MapService {
    private static final String AGENT = "CabinStory-Competition/0.2";
    private static final String SEARCH_URL = "https://photon.komoot.io/api/";
    private static final String ROUTE_URL = "https://routing.openstreetmap.de/routed-car/route/v1/driving/";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_RESULTS = 6;
    private static final String PROVIDER_SEARCH = "Photon / OpenStreetMap";
    private static final String PROVIDER_ROUTE = "OSRM / FOSSGIS";

    interface Transport { JSONObject get(String url) throws Exception; }

    /** Upstream/provider failure with the HTTP status the local server should return. */
    static final class ServiceException extends Exception {
        final int status;
        ServiceException(int status, String message) { super(message); this.status = status; }
    }

    private final Transport transport;
    private final TtlCache searches = new TtlCache(64, 24 * 60 * 60 * 1000L);
    private final TtlCache routes = new TtlCache(32, 60 * 60 * 1000L);
    private final Gate searchGate = new Gate();
    private final Gate routeGate = new Gate();

    private JSONObject fetch(String url) throws Exception {
        try { return transport.get(url); }
        catch (ServiceException e) { throw e; }
        catch (IOException | JSONException e) {
            if (e instanceof InterruptedIOException) Thread.currentThread().interrupt();
            throw new ServiceException(503, "지도 서비스를 지금 사용할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    public MapService() { this(new Https()); }

    MapService(Transport transport) { this.transport = transport; }

    public JSONArray search(String raw, Double lat, Double lon) throws Exception {
        String query = normalize(raw);
        boolean bias = lat != null || lon != null;
        if (bias && (lat == null || lon == null)) throw new IllegalArgumentException("위치 기준은 위도와 경도를 함께 보내 주세요.");
        if (bias && !legal(lat, lon)) throw new IllegalArgumentException("위치 기준을 확인해 주세요.");
        String key = (bias ? lat + "," + lon + "|" : "") + query.toLowerCase(Locale.ROOT);
        String cached = searches.get(key);
        if (cached != null) return new JSONArray(cached);
        StringBuilder url = new StringBuilder(SEARCH_URL).append("?q=").append(URLEncoder.encode(query, "UTF-8"))
            .append("&limit=").append(MAX_RESULTS);
        if (bias) url.append("&lat=").append(lat).append("&lon=").append(lon);
        JSONObject doc;
        searchGate.enter();
        try { doc = fetch(url.toString()); }
        finally { searchGate.leave(); }
        JSONArray places = parseSearch(doc);
        if (places.length() > 0) searches.put(key, places.toString());
        return places;
    }

    public JSONObject route(JSONObject trip) throws Exception {
        if (trip == null) throw new IllegalArgumentException("일정을 확인해 주세요.");
        JSONObject origin = trip.optJSONObject("origin");
        if (origin == null) throw new IllegalArgumentException("출발지를 먼저 지정해 주세요.");
        JSONArray stops = trip.optJSONArray("stops");
        int count = stops == null ? 0 : stops.length();
        if (count < 1 || count > 20) throw new IllegalArgumentException("방문지는 1~20곳까지 경로를 계산할 수 있어요.");
        Map<String, JSONObject> byId = new HashMap<>();
        JSONArray places = trip.optJSONArray("places");
        if (places != null) for (int i = 0; i < places.length(); i++) {
            JSONObject place = places.optJSONObject(i);
            if (place != null) byId.put(place.optString("id"), place);
        }
        StringBuilder coords = new StringBuilder(point(origin));
        for (int i = 0; i < count; i++) {
            JSONObject stop = byId.get(stops.optString(i));
            if (stop == null) throw new IllegalArgumentException("일정의 장소를 찾지 못했습니다.");
            coords.append(';').append(point(stop));
        }
        String key = coords.toString();
        String cached = routes.get(key);
        if (cached != null) return new JSONObject(cached);
        JSONObject doc;
        routeGate.enter();
        try { doc = fetch(ROUTE_URL + key + "?overview=simplified&geometries=geojson&steps=false"); }
        finally { routeGate.leave(); }
        JSONObject route = parseRoute(doc, count);
        routes.put(key, route.toString());
        return route;
    }

    private static JSONArray parseSearch(JSONObject doc) throws JSONException {
        JSONArray out = new JSONArray();
        JSONArray features = doc == null ? null : doc.optJSONArray("features");
        if (features == null) return out;
        for (int i = 0; i < features.length() && out.length() < MAX_RESULTS; i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;
            JSONObject geometry = feature.optJSONObject("geometry");
            if (geometry == null || !"Point".equals(geometry.optString("type"))) continue;
            JSONArray pair = geometry.optJSONArray("coordinates");
            if (pair == null || pair.length() != 2) continue;
            double lon = pair.optDouble(0, Double.NaN), lat = pair.optDouble(1, Double.NaN);
            if (!legal(lat, lon)) continue;
            JSONObject props = feature.optJSONObject("properties");
            if (props == null) props = new JSONObject();
            String street = clean(props.optString("street", ""), 120);
            String number = clean(props.optString("housenumber", ""), 24);
            String city = clean(props.optString("city", ""), 80);
            String country = clean(props.optString("country", ""), 80);
            String road = (number + " " + street).trim();
            String name = clean(props.optString("name", ""), 120);
            if (name.isEmpty()) name = road.isEmpty() ? city : road;
            if (name.isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            if (!road.isEmpty()) parts.add(road);
            if (!city.isEmpty()) parts.add(city);
            if (!country.isEmpty()) parts.add(country);
            out.put(new JSONObject().put("name", name).put("address", clean(String.join(", ", parts), 300))
                .put("latitude", lat).put("longitude", lon).put("provider", PROVIDER_SEARCH));
        }
        return out;
    }

    private static JSONObject parseRoute(JSONObject doc, int legs) throws JSONException, ServiceException {
        if (doc == null) throw new ServiceException(503, "도로 경로 서비스를 지금 사용할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
        String code = doc.optString("code", "");
        if ("NoRoute".equals(code) || "NoSegment".equals(code))
            throw new ServiceException(503, "도로 경로를 찾지 못했어요. 방문 순서나 장소를 바꿔 다시 시도해 주세요.");
        if (!"Ok".equals(code)) throw new ServiceException(503, "도로 경로 서비스를 지금 사용할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
        JSONArray waypoints = doc.optJSONArray("waypoints"), routeList = doc.optJSONArray("routes");
        if (waypoints == null || waypoints.length() != legs + 1 || routeList == null || routeList.length() == 0)
            throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        for (int i = 0; i < waypoints.length(); i++) {
            JSONObject waypoint = waypoints.optJSONObject(i);
            double snap = waypoint == null ? Double.NaN : waypoint.optDouble("distance", Double.NaN);
            if (!finite(snap) || snap < 0)
                throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
            if (snap > 2000)
                throw new ServiceException(503, "장소가 차량 도로에서 2000m 넘게 떨어져 있어요. 차량이 갈 수 있는 지점을 선택해 주세요.");
        }
        JSONObject route = routeList.optJSONObject(0);
        JSONObject geometry = route == null ? null : route.optJSONObject("geometry");
        if (geometry == null || !"LineString".equals(geometry.optString("type")))
            throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        JSONArray raw = geometry.optJSONArray("coordinates");
        if (raw == null || raw.length() < 2 || raw.length() > 20000)
            throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        JSONArray coordinates = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONArray pair = raw.optJSONArray(i);
            if (pair == null || pair.length() != 2) throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
            double lon = pair.optDouble(0, Double.NaN), lat = pair.optDouble(1, Double.NaN);
            if (!legal(lat, lon)) throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
            coordinates.put(new JSONArray().put(lon).put(lat));
        }
        double distance = route.optDouble("distance", Double.NaN), duration = route.optDouble("duration", Double.NaN);
        if (!finite(distance) || distance < 0 || !finite(duration) || duration < 0)
            throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        JSONArray legsIn = route.optJSONArray("legs");
        if (legsIn == null || legsIn.length() != legs)
            throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        JSONArray out = new JSONArray();
        double sum = 0;
        for (int i = 0; i < legsIn.length(); i++) {
            JSONObject leg = legsIn.optJSONObject(i);
            double legDistance = leg == null ? Double.NaN : leg.optDouble("distance", Double.NaN);
            double legDuration = leg == null ? Double.NaN : leg.optDouble("duration", Double.NaN);
            if (!finite(legDistance) || legDistance < 0 || !finite(legDuration) || legDuration < 0)
                throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
            sum += legDistance;
            out.put(new JSONObject().put("distanceMeters", legDistance).put("durationSeconds", legDuration));
        }
        if (Math.abs(sum - distance) > Math.max(1.0, distance * 0.01))
            throw new ServiceException(503, "도로 경로 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        return new JSONObject().put("geometry", new JSONObject().put("type", "LineString").put("coordinates", coordinates))
            .put("distanceMeters", distance).put("durationSeconds", duration).put("legs", out)
            .put("provider", PROVIDER_ROUTE).put("computedAt", System.currentTimeMillis());
    }

    private static String point(JSONObject place) {
        double lat = place.optDouble("latitude", Double.NaN), lon = place.optDouble("longitude", Double.NaN);
        if (!legal(lat, lon)) throw new IllegalArgumentException("장소 좌표를 확인해 주세요.");
        return lon + "," + lat;
    }

    private static boolean legal(double lat, double lon) {
        return finite(lat) && finite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }

    private static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < 2 || value.length() > 120 || hasControl(value))
            throw new IllegalArgumentException("검색어는 2~120자로 입력해 주세요.");
        return value.replaceAll("\\s+", " ");
    }

    private static boolean hasControl(String value) { return value.chars().anyMatch(Character::isISOControl); }

    /** Upstream text is display-only: no markup, no control characters, short. */
    private static String clean(String value, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '<' || c == '>') continue;
            out.append(c);
        }
        return out.toString().trim();
    }

    /** One upstream request per second per service, no sleeping: extra callers get a busy/rate error. */
    private static final class Gate {
        private boolean busy;
        private long nextAt;
        synchronized void enter() throws ServiceException {
            long now = SystemClock.elapsedRealtime();
            if (busy) throw new ServiceException(503, "지도 요청이 이미 진행 중이에요. 잠시 뒤 다시 시도해 주세요.");
            if (now < nextAt) throw new ServiceException(429, "지도 요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요.");
            busy = true;
            nextAt = now + 1000;
        }
        synchronized void leave() { busy = false; }
    }

    /** Bounded access-order cache of JSON text, so callers always get their own copy. */
    private static final class TtlCache {
        private final int max;
        private final long ttl;
        private final LinkedHashMap<String, String[]> entries = new LinkedHashMap<>(16, 0.75f, true);
        TtlCache(int max, long ttl) { this.max = max; this.ttl = ttl; }
        synchronized String get(String key) {
            String[] entry = entries.get(key);
            if (entry == null) return null;
            if (SystemClock.elapsedRealtime() > Long.parseLong(entry[1])) { entries.remove(key); return null; }
            return entry[0];
        }
        synchronized void put(String key, String json) {
            entries.put(key, new String[]{json, String.valueOf(SystemClock.elapsedRealtime() + ttl)});
            while (entries.size() > max) {
                Iterator<String> oldest = entries.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** Fixed upstream hosts, redirects off, 5s/12s timeouts, 1MB cap, identified requests. */
    private static final class Https implements Transport {
        @Override public JSONObject get(String url) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", AGENT);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code == 429) throw new ServiceException(429, "지도 서비스 요청 한도를 넘었어요. 잠시 뒤 다시 시도해 주세요.");
                if (code != 200) throw new ServiceException(503, "지도 서비스를 지금 사용할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
                return new JSONObject(new String(read(connection.getInputStream()), StandardCharsets.UTF_8));
            } finally {
                connection.disconnect();
            }
        }
        private static byte[] read(InputStream raw) throws Exception {
            try (InputStream in = raw; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    total += n;
                    if (total > MAX_BYTES) throw new ServiceException(503, "지도 응답이 너무 커서 처리하지 못했어요.");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        }
    }
}

package kr.cabinstory;

import org.json.JSONObject;

/**
 * Short narration for the drive between stops: a preview when the car sets off, a quiz half way,
 * a look-out hint just before arrival and a mission on arrival. A line starting with '~' is spoken
 * after a thinking pause.
 */
final class Journey {
    private Journey() {}

    /** Quizzes, window games, stretches and the serial tale between the place stories. Off: the proposal lists them as a later addition. */
    static final boolean IN_TRANSIT_PROGRAM = false;
    static final String PREVIEW = "preview", QUIZ = "quiz", LOOKOUT = "lookout", MISSION = "mission", PROPOSAL = "proposal",
        GAME = "game", TALE = "tale", MOVE = "move", FACT = "fact";

    /** Spoken while driving so nobody has to look at the screen; the decision waits until the car is parked. */
    static String[] proposal(String who, String place, int waiting) {
        return new String[]{who + josa(who, "이", "가") + " " + place + josa(place, "을", "를") + " 가고 싶은 곳에 담았어요. 정차하면 목록에서 고를 수 있어요."};
    }

    static String label(String kind) {
        switch (kind) {
            case PREVIEW: return "출발 이야기";
            case QUIZ: return "이동 중 퀴즈";
            case LOOKOUT: return "곧 도착";
            case MISSION: return "도착 미션";
            case PROPOSAL: return "새 장소";
            case GAME: return "창밖 놀이";
            case TALE: return "별 조각 이야기";
            case MOVE: return "몸 풀기";
            case FACT: return "알아 두면 좋은 이야기";
            default: return "";
        }
    }

    /** Sticker pose shown while an interlude is spoken. */
    static String activity(String kind) {
        switch (kind) {
            case PREVIEW: return "walk";
            case QUIZ: return "think";
            case LOOKOUT: return "point";
            case MISSION: return "cheer";
            case GAME: return "point";
            case TALE: return "read";
            case MOVE: return "cheer";
            case FACT: return "read";
            default: return "wave";
        }
    }

    static int minutes(int seconds) { return Math.max(1, (seconds + 30) / 60); }
    /** "12분" or "3시간 43분". */
    static String duration(int seconds) {
        int m = minutes(seconds);
        return m < 60 ? m + "분" : (m / 60) + "시간" + (m % 60 == 0 ? "" : " " + (m % 60) + "분");
    }
    /** A child's unit of time: one song is about three minutes. */
    static int songs(int seconds) { return Math.max(1, Math.round(seconds / 180f)); }

    static String distance(int meters) {
        if (meters < 0) return "";
        return meters >= 1000 ? String.format(java.util.Locale.KOREA, "%.1fkm", meters / 1000f) : Math.max(10, meters / 10 * 10) + "m";
    }

    /** "12분 · 4.2km" or "" when nothing is known. */
    static String remainLabel(int seconds, int meters) {
        if (seconds < 0) return "";
        String d = distance(meters);
        return (seconds < 60 ? "곧 도착" : duration(seconds)) + (d.isEmpty() ? "" : " · " + d);
    }

    static String kidsEta(int seconds) {
        if (seconds < 0) return "";
        if (seconds < 90) return "이제 곧 도착해!";
        if (seconds > 1500) return "조금 먼 길이야. " + duration(seconds) + "쯤 걸려. 이야기 들으면서 가자!";
        int songs = songs(seconds);
        return "노래 " + (songs == 1 ? "한" : songs == 2 ? "두" : songs == 3 ? "세" : songs == 4 ? "네" : String.valueOf(songs)) + " 곡쯤 들으면 도착해!";
    }

    static String[] preview(JSONObject place, JSONObject previous, int remainSec, boolean kids, String guide) {
        String name = place.optString("name");
        JSONObject j = place.optJSONObject("journey");
        JSONObject pj = previous == null ? null : previous.optJSONObject("journey");
        StringBuilder out = new StringBuilder();
        if (kids) {
            if (previous != null) {
                String recall = pj == null ? "" : pj.optString("kidsRecall");
                out.append(recall.isEmpty() ? previous.optString("name") + " 재미있었지? " : recall + " ");
            }
            out.append(guide).append("랑 이번에는 ").append(name).append(josa(name, "으로", "로")).append(" 가 보자! ");
            String tag = place.optString("tag");
            if (!tag.isEmpty()) out.append(name).append(josa(name, "은", "는")).append(" ").append(tag).append(josa(tag, "이야", "야")).append(". ");
            if (remainSec >= 90) out.append(kidsEta(remainSec));
        } else {
            if (previous != null) out.append(previous.optString("name")).append(josa(previous.optString("name"), "을", "를")).append(" 떠나 ");
            out.append("다음 방문지 ").append(name).append(josa(name, "으로", "로")).append(" 이동합니다. ");
            if (remainSec >= 90) out.append("약 ").append(duration(remainSec)).append(" 걸립니다. ");
            String summary = place.optString("summary");
            if (!summary.isEmpty()) out.append(summary);
        }
        return new String[]{out.toString().trim()};
    }

    static String[] quiz(JSONObject place, boolean kids, String guide) {
        JSONObject j = place.optJSONObject("journey");
        if (kids) {
            JSONObject q = j == null ? null : j.optJSONObject("kidsQuiz");
            if (q != null && !q.optString("q").isEmpty())
                return new String[]{guide + "의 퀴즈 시간! " + q.optString("q"), "~하나, 둘, 셋! " + q.optString("a")};
            return new String[]{guide + "랑 창밖 놀이 하자! 도착할 때까지 빨간색 자동차를 몇 대 찾을 수 있을까? 찾을 때마다 하나, 둘, 세어 봐."};
        }
        String fact = j == null ? "" : j.optString("fact");
        if (fact.isEmpty()) return new String[0];
        return new String[]{"도착 전에 알아 두면 좋은 이야기입니다. " + fact};
    }

    /** Every quiz a place has: the list, or the single older one. */
    static org.json.JSONArray placeQuizzes(JSONObject place) {
        JSONObject j = place.optJSONObject("journey");
        org.json.JSONArray out = j == null ? null : j.optJSONArray("kidsQuizzes");
        if (out != null && out.length() > 0) return out;
        out = new org.json.JSONArray();
        JSONObject one = j == null ? null : j.optJSONObject("kidsQuiz");
        if (one != null && !one.optString("q").isEmpty()) out.put(one);
        return out;
    }
    static org.json.JSONArray placeFacts(JSONObject place) {
        JSONObject j = place.optJSONObject("journey");
        org.json.JSONArray out = j == null ? null : j.optJSONArray("facts");
        if (out != null && out.length() > 0) return out;
        out = new org.json.JSONArray();
        if (j != null && !j.optString("fact").isEmpty()) out.put(j.optString("fact"));
        return out;
    }
    static String[] quizLines(JSONObject q, String guide) {
        return new String[]{guide + "의 퀴즈 시간! " + q.optString("q"), "~하나, 둘, 셋! " + q.optString("a")};
    }
    static String[] game(String text, String guide) { return new String[]{guide + "랑 창밖 놀이 하자! " + text.replace("{g}", guide)}; }
    static String[] move(String text, String guide) { return new String[]{guide + "랑 몸 풀기 시간! " + text.replace("{g}", guide)}; }
    static String[] fact(String text) { return new String[]{"도착 전에 알아 두면 좋은 이야기입니다. " + text}; }
    /** One episode of the serial the guide tells across the whole trip. */
    static String[] tale(org.json.JSONArray episode, int number, int total, String guide) {
        String[] out = new String[episode.length()];
        for (int i = 0; i < out.length; i++) out[i] = episode.optString(i).replace("{g}", guide);
        if (out.length > 0) out[0] = guide + "의 별 조각 이야기, " + ordinal(number) + " 이야기. " + out[0];
        if (out.length > 0 && number < total) out[out.length - 1] += " 다음 이야기는 조금 뒤에 들려줄게.";
        return out;
    }
    static String ordinal(int n) {
        String[] words = {"첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열"};
        return (n >= 1 && n <= words.length ? words[n - 1] : String.valueOf(n)) + " 번째";
    }

    static String[] lookout(JSONObject place, boolean kids, String guide) {
        String name = place.optString("name");
        JSONObject j = place.optJSONObject("journey");
        if (kids) {
            String hint = j == null ? "" : j.optString("kidsLookout");
            return new String[]{"이제 거의 다 왔어! " + (hint.isEmpty() ? "창밖에 " + name + josa(name, "이", "가") + " 보이는지 찾아봐." : hint)};
        }
        String hint = j == null ? "" : j.optString("lookout");
        return new String[]{"곧 " + name + "에 도착합니다. " + hint};
    }

    static String[] mission(JSONObject place, String guide) {
        JSONObject m = place.optJSONObject("mission");
        String q = m == null ? "" : m.optString("question");
        if (q.isEmpty()) return new String[0];
        return new String[]{place.optString("name") + "에 도착! " + guide + "의 미션이야. " + q + " 찾으면 휴대폰에서 도장을 찍어 줘!"};
    }

    /** Picks the particle by whether the last Hangul syllable has a final consonant ("으로" also treats ㄹ as open). */
    static String josa(String word, String closed, String open) {
        if (word == null || word.isEmpty()) return open;
        char c = word.charAt(word.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return open;
        int jong = (c - 0xAC00) % 28;
        if (jong == 0) return open;
        if (jong == 8 && "으로".equals(closed)) return open;
        return closed;
    }
}

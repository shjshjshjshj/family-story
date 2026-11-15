package kr.cabinstory;

import android.test.InstrumentationTestCase;
import org.json.*;

/** The generated story kit must come out in the same shape as the bundled places, or not at all. */
public final class StoryKitTest extends InstrumentationTestCase {
    private TripStore store;
    private static final String STORAGE = "qa-kit";
    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().getTargetContext().getSharedPreferences(STORAGE, 0).edit().clear().commit();
        store = new TripStore(getInstrumentation().getTargetContext(), STORAGE);
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().getTargetContext().getSharedPreferences(STORAGE, 0).edit().clear().commit();
        super.tearDown();
    }

    private static JSONArray adult() {
        return new JSONArray().put("불국사는 신라 시대에 지은 절입니다. 토함산 기슭에 있습니다.")
            .put("경내에는 다보탑과 석가탑이 나란히 서 있습니다. 두 탑은 모양이 서로 다릅니다.").put("짧음.");
    }
    private static JSONObject page(String title, String text, String activity) throws JSONException {
        return new JSONObject().put("title", title).put("text", text).put("activity", activity);
    }
    private static JSONObject quiz(String q, String one, String two, int answer, String explain) throws JSONException {
        return new JSONObject().put("question", q).put("option1", one).put("option2", two).put("answer", answer).put("explain", explain);
    }
    private static JSONObject answer(int pages) throws JSONException {
        JSONArray list = new JSONArray();
        for (int i = 0; i < pages; i++)
            list.put(page("탑이 두 개 " + (i + 1), "{g}가 마당에서 탑 두 개를 발견했어. 하나는 화려하고 하나는 단정해. 서로 다르게 생겼지?", i == 0 ? "wave" : "dance"));
        return new JSONObject().put("pages", list)
            .put("quizzes", new JSONArray()
                .put(quiz("불국사 마당에는 탑이 몇 개 서 있을까?", "두 개.", "백 개", 1, "정답은 두 개가 나란히 서 있어."))
                .put(quiz("불국사는 어디에 있을까?", "바닷속", "산기슭", 2, "토함산 기슭에 있어."))
                .put(quiz("둘이 같은 보기", "같음", "같음", 1, "버려져야 해."))
                .put(quiz("번호가 틀린 퀴즈일까?", "하나", "둘", 3, "버려져야 해.")))
            .put("lookout", "창밖에 기와지붕이 보이는지 찾아봐.").put("recall", "불국사에서 탑 두 개를 봤지?")
            .put("mission", "마당에서 서로 다르게 생긴 탑 두 개를 찾아볼까?");
    }

    public void testGuidelineStatesTheRules() {
        String prompt = StoryKit.prompt("불국사", "불국사는 신라 시대에 지은 절입니다.");
        for (String rule : new String[]{"{g}", "없는 연도, 숫자, 인물", "8쪽", "반말", "퀴즈 3개", "[장소] 불국사", "[소개] 불국사는", "눈으로 보기"})
            assertTrue(rule, prompt.contains(rule));
        assertTrue(StoryKit.prompt("x", new String(new char[5000]).replace('\0', '가')).length() < 4000);
    }

    public void testSchemaListsEveryField() throws Exception {
        JSONObject schema = StoryKit.schema();
        assertEquals("OBJECT", schema.getString("type"));
        for (String key : new String[]{"pages", "quizzes", "lookout", "recall", "mission"}) assertTrue(key, schema.getJSONObject("properties").has(key));
        assertEquals(12, schema.getJSONObject("properties").getJSONObject("pages").getJSONObject("items").getJSONObject("properties").getJSONObject("activity").getJSONArray("enum").length());
    }

    public void testValidAnswerBecomesTheBundledShape() throws Exception {
        JSONObject kit = StoryKit.parse(answer(9).toString(), adult());
        assertEquals(StoryKit.VERSION, kit.getInt("kit"));
        JSONArray pages = kit.getJSONArray("kids");
        assertEquals(8, pages.length());                                    // never more than the picture book holds
        assertEquals("wave", pages.getJSONObject(0).getString("activity"));
        assertFalse("dance".equals(pages.getJSONObject(1).getString("activity")));   // an unknown pose is replaced
        JSONArray quizzes = kit.getJSONObject("journey").getJSONArray("kidsQuizzes");
        assertEquals(2, quizzes.length());                                  // the two broken quizzes are dropped
        assertEquals("불국사 마당에는 탑이 몇 개 서 있을까? 일 번, 두 개. 이 번, 백 개.", quizzes.getJSONObject(0).getString("q"));
        assertEquals("정답은 일 번! 두 개가 나란히 서 있어.", quizzes.getJSONObject(0).getString("a"));
        assertTrue(quizzes.getJSONObject(1).getString("a").startsWith("정답은 이 번! "));
        assertEquals("마당에서 서로 다르게 생긴 탑 두 개를 찾아볼까?", kit.getJSONObject("mission").getString("question"));
        // facts for the general guide are cut from the official overview, not written by the model
        JSONArray facts = kit.getJSONObject("journey").getJSONArray("facts");
        assertEquals(2, facts.length());
        assertEquals("불국사는 신라 시대에 지은 절입니다.", facts.getString(0));
    }

    public void testThinAnswerIsRejectedAndFallbackStillHasAMission() throws Exception {
        try { StoryKit.parse(answer(3).toString(), adult()); fail("3 pages must not pass"); } catch (JSONException expected) {}
        try { StoryKit.parse("not json", adult()); fail(); } catch (JSONException expected) {}
        JSONObject fallback = StoryKit.fallback("불국사", adult());
        assertEquals(0, fallback.getInt("kit"));
        assertTrue(fallback.getJSONArray("kids").getJSONObject(0).getString("text").contains("불국사라는"));
        assertTrue(fallback.getJSONObject("mission").getString("question").endsWith("?"));
    }

    public void testGeneratedPlaceFeedsQuizAndMissionLikeABundledOne() throws Exception {
        store.mutate("driver", new JSONObject().put("action", "addCustom").put("revision", store.snapshot().getInt("revision"))
            .put("place", new JSONObject().put("name", "불국사").put("address", "경주시").put("latitude", 35.79).put("longitude", 129.33)), true);
        JSONArray stops = store.snapshot().getJSONArray("stops");
        String id = stops.getString(stops.length() - 1);
        assertEquals(0, store.contentKit(id));
        store.putContent(id, StoryKit.parse(answer(8).toString(), adult()).put("adult", adult()));
        JSONObject place = store.place(id);
        assertEquals(StoryKit.VERSION, store.contentKit(id));
        assertEquals(8, place.getJSONArray("kids").length());
        assertEquals(2, Journey.placeQuizzes(place).length());
        assertTrue(Journey.lookout(place, true, "보리")[0].contains("기와지붕"));
        assertTrue(Journey.mission(place, "보리")[0].contains("탑 두 개"));
        assertEquals(2, Journey.placeFacts(place).length());
    }
}

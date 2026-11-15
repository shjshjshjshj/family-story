package kr.cabinstory;

import android.test.InstrumentationTestCase;
import org.json.*;

public final class JourneyTest extends InstrumentationTestCase {
    private TripStore store;
    private static final String STORAGE = "qa-journey";
    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().getTargetContext().getSharedPreferences(STORAGE, 0).edit().clear().commit();
        store = new TripStore(getInstrumentation().getTargetContext(), STORAGE);
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().getTargetContext().getSharedPreferences(STORAGE, 0).edit().clear().commit();
        super.tearDown();
    }

    public void testParticles() {
        assertEquals("으로", Journey.josa("대릉원", "으로", "로"));
        assertEquals("로", Journey.josa("첨성대", "으로", "로"));
        assertEquals("로", Journey.josa("서울", "으로", "로"));   // ㄹ final takes 로
        assertEquals("을", Journey.josa("서울", "을", "를"));
        assertEquals("로", Journey.josa("Cafe 21", "으로", "로"));
    }

    public void testProposalAnnouncement() {
        String one = Journey.proposal("민지", "황리단길", 1)[0];
        assertTrue(one, one.contains("민지가 황리단길을") && one.contains("담았어요") && one.contains("정차하면"));
    }

    public void testRemainLabel() {
        assertEquals("", Journey.remainLabel(-1, -1));
        assertEquals("곧 도착 · 300m", Journey.remainLabel(40, 300));
        assertEquals("12분 · 4.2km", Journey.remainLabel(720, 4200));
        assertEquals("3시간 43분 · 313.0km", Journey.remainLabel(13380, 313000));
        assertEquals("2시간", Journey.duration(7200));
        assertFalse(Journey.kidsEta(13380).contains("곡"));
        assertEquals(4, Journey.songs(720));
        assertEquals(1, Journey.songs(30));
    }

    public void testBundledPlacesHaveJourneyCopy() throws Exception {
        for (String id : new String[]{"cheomseongdae", "daereungwon", "wolji", "museum"}) {
            JSONObject place = store.place(id);
            String[] quiz = Journey.quiz(place, true, "보리");
            assertEquals(id, 2, quiz.length);
            assertTrue(id, quiz[1].startsWith("~"));
            assertFalse(id, Journey.quiz(place, false, "보리")[0].contains("{g}"));
            assertEquals(1, Journey.lookout(place, true, "보리").length);
            assertTrue(id, Journey.mission(place, "보리")[0].contains("보리"));
        }
    }

    public void testProgrammeFillsTheDrive() throws Exception {
        JSONObject program = store.program();
        assertTrue(program.getJSONArray("kidsQuizzes").length() >= 10);
        assertTrue(program.getJSONArray("kidsGames").length() >= 10);
        assertTrue(program.getJSONArray("kidsMoves").length() >= 5);
        JSONArray tale = program.getJSONArray("kidsTale");
        assertEquals(8, tale.length());
        String[] first = Journey.tale(tale.getJSONArray(0), 1, tale.length(), "나비");
        assertTrue(first[0], first[0].startsWith("나비의 별 조각 이야기, 첫 번째 이야기.") && !first[0].contains("{g}"));
        assertTrue(first[first.length - 1].endsWith("들려줄게."));
        assertFalse(Journey.tale(tale.getJSONArray(7), 8, tale.length(), "나비")[1].contains("다음 이야기"));
        for (String id : new String[]{"cheomseongdae", "daereungwon", "wolji", "museum"}) {
            JSONObject place = store.place(id);
            assertEquals(id, 3, Journey.placeQuizzes(place).length());
            assertEquals(id, 3, Journey.placeFacts(place).length());
            assertEquals(id, 8, place.getJSONArray("kids").length());
            assertTrue(id, place.getJSONArray("adult").length() >= 7);
        }
        assertFalse(Journey.game(program.getJSONArray("kidsGames").getString(6), "보리")[0].contains("{g}"));
        assertEquals(0, Journey.placeQuizzes(new JSONObject().put("name", "우리 집")).length());
    }

    public void testPreviewMentionsTimeAndPreviousStop() throws Exception {
        JSONObject place = store.place("daereungwon"), previous = store.place("cheomseongdae");
        String kids = Journey.preview(place, previous, 720, true, "나비")[0];
        assertTrue(kids, kids.contains("대릉원으로") && kids.contains("노래 네 곡") && kids.contains("첨성대"));
        String adult = Journey.preview(place, null, 720, false, "나비")[0];
        assertTrue(adult, adult.contains("약 12분") && !adult.contains("나비"));
        // without a known time nothing is promised
        assertFalse(Journey.preview(place, null, -1, true, "나비")[0].contains("곡"));
    }

    public void testUnknownPlaceFallsBackToWindowGame() throws Exception {
        JSONObject custom = new JSONObject().put("name", "우리 집").put("id", "x");
        assertEquals(1, Journey.quiz(custom, true, "보리").length);
        assertEquals(0, Journey.quiz(custom, false, "보리").length);
        assertEquals(0, Journey.mission(custom, "보리").length);
        assertTrue(Journey.lookout(custom, true, "보리")[0].contains("우리 집이"));
    }
}

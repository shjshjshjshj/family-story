package kr.cabinstory;

import android.content.Context;
import android.media.*;
import android.os.*;
import android.util.Log;
import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.*;
import ai.pleos.playground.tts.listener.*;
import java.util.Locale;

public final class DocentPlayer {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private TextToSpeech tts;
    private String[] script=new String[0];
    private int paragraph, generation;
    private boolean ready, playing, released;
    public String status="음성 엔진 준비 중";
    public Runnable onChange=()->{};
    public boolean isPlaying(){return playing;}
    public boolean isReady(){return ready;}
    public int paragraph(){return interlude.isEmpty()?paragraph:savedParagraph;}
    public int length(){return interlude.isEmpty()?script.length:saved.length;}
    private String[] saved=new String[0];
    private int savedParagraph;
    private String interlude="";
    /** Kind of the interlude being spoken ("" while the place story is loaded). */
    public String interlude(){return interlude;}
    /** The interlude line being spoken, without the pause marker. */
    public String line(){
        if(interlude.isEmpty() || paragraph>=script.length)return "";
        String s=script[paragraph];return s.startsWith("~")?s.substring(1):s;
    }
    /** Speaks a short piece between stops. Refused while something is playing; the loaded story keeps its position. */
    public boolean say(String kind,String[] lines){
        if(playing || !ready || lines==null || lines.length==0 || released)return false;
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return false;
        if(interlude.isEmpty()){saved=script;savedParagraph=paragraph;}
        script=lines.clone();paragraph=0;interlude=kind;
        playing=true;update(Journey.label(kind));schedule();
        return true;
    }
    private void restore(){
        if(interlude.isEmpty())return;
        script=saved;paragraph=savedParagraph;saved=new String[0];interlude="";
    }

    public DocentPlayer(Context context) {
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(change->{if(change<0)pause("길 안내·다른 오디오를 위해 일시정지");},main).build();
        try {
            tts=new TextToSpeech(context.getApplicationContext(),Mode.ON_DEVICE);
            tts.initialize(new OnInitializeListener(){
                @Override public void onSuccess(){main.post(()->{
                    if(released)return;
                    tts.addEventListener(listener);tts.setLocale(Locale.KOREA);tts.setAudioAttributes(attributes);
                    ready=true;applySpeaker();update("한국어 온디바이스 음성 준비됨");
                });}
                @Override public void onFailure(ErrorType error){main.post(()->update("음성 초기화 실패 · 앱을 다시 열어 주세요: "+error));}
            });
        } catch(Exception e){update("Pleos 음성을 사용할 수 없는 환경입니다.");Log.e("CabinStory","TTS init",e);}
    }
    private final EventListener listener=new EventListener(){
        @Override public void onReady(){Log.i("CabinStory","TTS onReady ON_DEVICE ko-KR");}
        @Override public void onStart(){main.post(()->{if(playing)update(interlude.isEmpty()?"이야기 재생 중 · "+(paragraph+1)+" / "+script.length:Journey.label(interlude));});}
        @Override public void onUpdatedRms(double rms){}
        @Override public void onDone(){main.post(()->{
            if(!playing || released)return;
            Log.i("CabinStory","TTS onDone paragraph="+paragraph);
            paragraph++;
            if(paragraph>=script.length){
                boolean between=!interlude.isEmpty();
                restore();playing=false;audio.abandonAudioFocusRequest(focus);update(between?"이동 중 이야기를 들었어요":"이야기를 모두 들었어요");
            }
            else schedule();
        });}
        @Override public void onStop(){Log.i("CabinStory","TTS onStop");}
        @Override public void onError(ErrorType error){main.post(()->{
            restore();playing=false;generation++;audio.abandonAudioFocusRequest(focus);
            update("음성을 준비하지 못했어요 · 재생을 눌러 재시도: "+error);
        });}
    };
    private String speaker="";
    /** Pleos on-device Korean speakers: HAEUN/SEOYOON (female), EUNWOO/SANGHYEOK (male). */
    public void setVoice(String voice) {
        String next="male".equals(voice)?"EUNWOO":"HAEUN";
        if(next.equals(speaker))return;
        speaker=next;
        if(ready && tts!=null) applySpeaker();
    }
    private void applySpeaker() {
        if(speaker.isEmpty())return;
        boolean was=playing && interlude.isEmpty();
        if(playing)pause("목소리를 바꾸는 중");
        try{tts.setSpeakerType(speaker);Log.i("CabinStory","TTS speaker "+speaker);}catch(Exception e){Log.w("CabinStory","TTS speaker",e);}
        if(was)toggle();
    }
    public void seek(int index) {
        if(!interlude.isEmpty())pause("장면 이동");
        if(index<0 || index>=script.length)return;
        boolean was=playing;
        if(was)pause("장면 이동");
        paragraph=index;
        if(was)toggle(); else update("장면 "+(index+1)+" / "+script.length);
    }
    public void load(String[] text) {stop();script=text.clone();paragraph=0;}
    public void toggle() {
        if(playing){pause("일시정지 · 이어듣기는 현재 문단부터");return;}
        if(!ready || script.length==0){update("음성 준비 후 다시 재생해 주세요.");return;}
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){update("다른 오디오가 끝나면 재생해 주세요.");return;}
        if(paragraph>=script.length)paragraph=0;
        playing=true;update("이야기 준비 중");schedule();
    }
    private void schedule() {
        int expected=++generation;
        // The SDK has no utterance ids: settle stop callbacks before starting the next paragraph.
        String next=script[paragraph];
        boolean wait=next.startsWith("~");   // thinking pause before a quiz answer
        main.postDelayed(()->{if(expected==generation && playing && !released){
            try{tts.speak(wait?next.substring(1):next,true);}catch(Exception e){pause("음성 요청 실패 · 다시 재생해 주세요.");}
        }},wait?4000:300);
    }
    public void pause(String reason) {
        generation++;playing=false;restore();
        if(ready && tts!=null)try{tts.stop();}catch(Exception e){Log.w("CabinStory","TTS stop",e);}
        audio.abandonAudioFocusRequest(focus);update(reason);
    }
    public void stop(){pause("이야기 멈춤");paragraph=0;}
    private void update(String message){if(released)return;status=message;Log.i("CabinStory",message);onChange.run();}
    public void release(){stop();released=true;main.removeCallbacksAndMessages(null);if(tts!=null){if(ready)tts.removeEventListener(listener);tts.release();tts=null;}}
}

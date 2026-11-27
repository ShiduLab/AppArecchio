package org.shidulab.apparecchio;

import android.app.Activity;

import android.view.View;
import android.widget.*;
import java.util.TreeMap;

final class TranslationPanel implements AutoCloseable {
    private final Activity activity;
    private final Button transcribe, translate;
    private final Spinner language;
    private final TextView status, transcript;
    private final Switch original, italian, speech, remote, events;
    private final SeekBar eventThreshold;
    private final ScrollView history;
    private final SeekBar ambient, translated;
    private final TreeMap<Integer, AmbientTranslation.Segment> segments = new TreeMap<>();
    private final String[] codes = {"it", "en", "ar", "cmn", "es", "auto"};
    private boolean translating;
    private boolean readingHistory;
    private AmbientTranslation translation, remoteTranslation;
    private final TextView remoteTranscript;
    private ListeningSession attached;
    private ItalianVoice voice;

    TranslationPanel(Activity activity, LinearLayout panel) {
        this.activity = activity;
        TextView title = new TextView(activity);
        title.setText("AMBIENTE LIVE");
        title.setTextSize(22);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        language = new Spinner(activity);
        String[] languages = {"Italiano", "Inglese", "Arabo standard", "Mandarino", "Spagnolo", "Rileva automaticamente"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(adapter);
        language.setContentDescription("Lingua sorgente");
        panel.addView(language);
        transcribe = new Button(activity);
        transcribe.setText("Trascrivi ambiente");
        transcribe.setOnClickListener(v -> { if (translation == null) start(false); else close(); });
        panel.addView(transcribe);
        translate = new Button(activity);
        translate.setText("Traduci ambiente");
        translate.setOnClickListener(v -> { if (translation == null) start(true); else close(); });
        panel.addView(translate);
        status = new TextView(activity);
        status.setText("Trascrizione e traduzione sul dispositivo");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        original = control(panel, "Mostra originale", true);
        italian = control(panel, "Mostra traduzione italiana", true);
        events = control(panel, "Mostra eventi sonori", true);
        var thresholdLabel = new TextView(activity);
        thresholdLabel.setText("Soglia eventi sonori · 55%");
        panel.addView(thresholdLabel);
        eventThreshold = new SeekBar(activity);
        eventThreshold.setMin(30);
        eventThreshold.setMax(95);
        eventThreshold.setProgress(55);
        eventThreshold.setContentDescription("Soglia di attendibilità degli eventi sonori");
        panel.addView(eventThreshold);
        eventThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                thresholdLabel.setText("Soglia eventi sonori · " + value + "%");
                eventOptions();
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        events.setOnCheckedChangeListener((button, checked) -> eventOptions());
        speech = control(panel, "Riproduci voce sintetica italiana", false);
        history = new ScrollView(activity);
        history.setFillViewport(true);
        history.setVerticalScrollBarEnabled(true);
        history.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            readingHistory = action != android.view.MotionEvent.ACTION_UP
                && action != android.view.MotionEvent.ACTION_CANCEL;
            history.getParent().requestDisallowInterceptTouchEvent(readingHistory);
            return false;
        });
        panel.addView(history, new LinearLayout.LayoutParams(-1,
            Math.round(200 * activity.getResources().getDisplayMetrics().density)));
        transcript = new TextView(activity);
        transcript.setTextSize(16);
        transcript.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        history.addView(transcript);
        original.setOnCheckedChangeListener((button, checked) -> renderHistory());
        italian.setOnCheckedChangeListener((button, checked) -> renderHistory());
        speech.setOnCheckedChangeListener((button, enabled) -> {
            if (!enabled) {
                if (voice != null) voice.close();
                voice = null;
                if (attached != null) attached.mix().clear();
            } else if (translation != null && translating && voice == null) {
                voice = new ItalianVoice(activity);
            }
        });
        remote = control(panel,"Traduci anche la voce del dialogo",false);
        remoteTranscript = new TextView(activity);
        remoteTranscript.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        panel.addView(remoteTranscript);
        remote.setOnCheckedChangeListener((button,on)->updateRemote());
        ambient = volume(panel, "Mix ambiente", 100);
        translated = volume(panel, "Mix italiano", 35);
    }

    private Switch control(LinearLayout panel, String label, boolean enabled) {
        Switch control = new Switch(activity);
        control.setText(label);
        control.setChecked(enabled);
        panel.addView(control);
        return control;
    }

    private SeekBar volume(LinearLayout panel, String label, int value) {
        TextView text = new TextView(activity);
        text.setText(label);
        panel.addView(text);
        SeekBar slider = new SeekBar(activity);
        slider.setMax(100);
        slider.setProgress(value);
        slider.setContentDescription(label);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int v, boolean user) { setVolume(); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        panel.addView(slider);
        return slider;
    }

    private void setVolume() {
        if (attached != null) attached.mix().volume(ambient.getProgress()/100f, translated.getProgress()/100f);
    }

    private void eventOptions() {
        if (translation != null) {
            translation.eventOptions(events.isChecked(), eventThreshold.getProgress() / 100f);
        }
    }

    private void start(boolean translateSpeech) {
        String code = codes[language.getSelectedItemPosition()];
        try { DeviceLanguage.check(activity, code); }
        catch (java.io.IOException error) { showStatus(error.getMessage()); return; }
        attached = ListeningService.currentSession();
        if (attached == null) { showStatus("Attiva prima Ascolta con le cuffie."); return; }
        translating = translateSpeech && !code.equals("it");
        segments.clear();
        transcript.setText("");
        if (translating && speech.isChecked()) voice = new ItalianVoice(activity);
        translation = new AmbientTranslation(activity, code, this::show, this::showStatus,
            true, translating, events.isChecked(), eventThreshold.getProgress() / 100f);
        attached.translation(translation);
        updateRemote();
        setVolume();
        language.setEnabled(false);
        transcribe.setEnabled(!translateSpeech);
        translate.setEnabled(translateSpeech);
        if (translateSpeech) translate.setText("Ferma ambiente LIVE");
        else transcribe.setText("Ferma trascrizione");
        showStatus("In ascolto · " + (translating ? "originale e traduzione" : "trascrizione"));
    }

    private void show(AmbientTranslation.Segment entry) {
        if (entry.original().isEmpty()) segments.remove(entry.number());
        else if (segments.size() < 120 || entry.number() >= segments.firstKey()) segments.put(entry.number(), entry);
        while (segments.size() > 120) segments.pollFirstEntry();
        renderHistory();
        ListeningSession target = attached;
        if (!entry.provisional() && speech.isChecked() && voice != null && !entry.italian().isEmpty()
                && !voice.speak(entry.italian(), pcm -> activity.runOnUiThread(() -> {
                    if (target != attached || !speech.isChecked()) return;
                    if (pcm != null) target.mix().play(pcm);
                    else showStatus("Voce italiana non disponibile");
                }))) showStatus("Voce italiana occupata o non installata");
    }

    private void renderHistory() {
        int previousY = history.getScrollY();
        boolean follow = !readingHistory && !history.canScrollVertically(1);
        var text = new android.text.SpannableStringBuilder();
        var bidi = android.text.BidiFormatter.getInstance();
        for (var item : segments.values()) {
            boolean showSource = !translating || item.spokenItalian() || original.isChecked();
            boolean showItalian = translating && italian.isChecked() && !item.italian().isEmpty();
            if (!item.event() && !showSource && !showItalian) continue;
            if (text.length() > 0) text.append("\n\n");
            int start = text.length();
            if (item.event()) {
                text.append(item.original());
                text.setSpan(new android.text.style.ForegroundColorSpan(0xff80dfe7), start, text.length(), 0);
            } else {
                if (showSource) {
                    text.append(bidi.unicodeWrap(item.original()));
                    if (item.provisional()) text.append(" …");
                }
                if (showItalian) {
                    if (text.length() > start) text.append("\n");
                    text.append("Italiano · ").append(item.italian());
                }
                if (item.provisional()) {
                    text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                        start, text.length(), 0);
                }
            }
        }
        transcript.setText(text);
        if (follow) history.post(() -> {
            if (!readingHistory && history.getScrollY() == previousY) {
                history.scrollTo(0, transcript.getHeight());
            }
        });
    }

    private void showStatus(String message) {
        if (message != null && !message.contentEquals(status.getText())) status.setText(message);
    }

    void remoteAudio(short[] pcm) {
        if (remoteTranslation != null) remoteTranslation.capture(pcm,pcm.length);
    }

    private void updateRemote() {
        if (remoteTranslation != null) remoteTranslation.close();
        remoteTranslation = null;
        if (remote.isChecked() && translation != null && translating) {
            remoteTranslation = new AmbientTranslation(activity,codes[language.getSelectedItemPosition()],entry->{
                remoteTranscript.setText("COMUNICAZIONE REMOTA\nOriginale\n"+entry.original()+
                    "\nItaliano\n"+(entry.italian().isBlank()?"In elaborazione…":entry.italian()));
                if (voice != null && speech.isChecked() && !entry.italian().isBlank()) {
                    ListeningSession target=attached;
                    voice.speak(entry.italian(),pcm->activity.runOnUiThread(()->{
                        if(pcm!=null&&target==attached&&remote.isChecked()&&speech.isChecked())target.mix().play(pcm);
                    }));
                }
            },status::setText);
        }
    }

    void listeningChanged(boolean busy) {
        if (!busy && translation != null) close();
    }

    @Override public void close() {
        if (attached != null) {
            attached.translation(null);
            attached.mix().clear();
            attached.mix().volume(1, .35f);
        }
        if (translation != null) translation.close();
        translation = null;
        updateRemote();
        if (voice != null) voice.close();
        voice = null;
        attached = null;
        language.setEnabled(true);
        transcribe.setEnabled(true);
        translate.setEnabled(true);
        transcribe.setText("Trascrivi ambiente");
        translate.setText("Traduci ambiente");
        status.setText("Ambiente LIVE fermo");
    }
}

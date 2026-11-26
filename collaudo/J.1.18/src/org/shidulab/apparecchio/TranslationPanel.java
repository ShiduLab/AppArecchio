package org.shidulab.apparecchio;

import android.app.Activity;
import android.app.AlertDialog;

import android.view.View;
import android.widget.*;
import java.util.LinkedHashMap;

final class TranslationPanel implements AutoCloseable {
    private final Activity activity;
    private final Button toggle;
    private final Spinner language;
    private final TextView status, transcript;
    private final Switch captions, speech, remote;
    private final SeekBar ambient, translated;
    private final LinkedHashMap<Integer, AmbientTranslation.Segment> segments = new LinkedHashMap<>();
    private final String[] codes = {"auto", "en", "ar", "cmn", "es"};
    private AmbientTranslation translation, remoteTranslation;
    private final TextView remoteTranscript;
    private ListeningSession attached;
    private ItalianVoice voice;

    TranslationPanel(Activity activity, LinearLayout panel) {
        this.activity = activity;
        TextView title = new TextView(activity);
        title.setText("TRADUCI AMBIENTE");
        title.setTextSize(22);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        language = new Spinner(activity);
        String[] languages = {"Rileva automaticamente", "Inglese", "Arabo standard", "Mandarino", "Spagnolo"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(adapter);
        language.setContentDescription("Lingua sorgente");
        panel.addView(language);
        toggle = new Button(activity);
        toggle.setText("Avvia traduzione in italiano");
        toggle.setOnClickListener(v -> { if (translation == null) ask(); else close(); });
        panel.addView(toggle);
        status = new TextView(activity);
        status.setText("Trascrizione e traduzione sul dispositivo");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        captions = control(panel, "Mostra originale e italiano", true);
        speech = control(panel, "Riproduci voce sintetica italiana", false);
        transcript = new TextView(activity);
        transcript.setTextSize(16);
        transcript.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        panel.addView(transcript);
        captions.setOnCheckedChangeListener((button, enabled) -> transcript.setVisibility(enabled ? View.VISIBLE : View.GONE));
        speech.setOnCheckedChangeListener((button, enabled) -> {
            if (!enabled) {
                if (voice != null) voice.close();
                voice = null;
                if (attached != null) attached.mix().clear();
            } else if (translation != null && voice == null) {
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

    private void ask() {
        if (ListeningService.currentSession() == null) {
            status.setText("Attiva prima Ascolta con le cuffie.");
            return;
        }
        try {
            DeviceLanguage.check(activity, codes[language.getSelectedItemPosition()]);
            new AlertDialog.Builder(activity).setTitle("Traduzione sul dispositivo")
                .setMessage("Al primo utilizzo potrebbe servire Internet per scaricare i modelli. L’audio viene elaborato sul telefono.")
                .setNegativeButton("Annulla",null)
                .setPositiveButton("Avvia",(dialog,which)->start()).show();
        } catch(Exception error) { status.setText(error.getMessage()); }
    }

    private void start() {
        attached = ListeningService.currentSession();
        if (attached == null) { status.setText("Riavvia prima Ascolta."); return; }
        segments.clear();
        transcript.setText("");
        if (speech.isChecked()) voice = new ItalianVoice(activity);
        translation = new AmbientTranslation(activity, codes[language.getSelectedItemPosition()], this::show, status::setText);
        attached.translation(translation);
        updateRemote();
        setVolume();
        language.setEnabled(false);
        toggle.setText("Ferma traduzione");
        status.setText("In ascolto per la traduzione");
    }

    private void show(AmbientTranslation.Segment entry) {
        segments.put(entry.number(), entry);
        while (segments.size() > 12) segments.remove(segments.keySet().iterator().next());
        StringBuilder text = new StringBuilder();
        for (var item : segments.values()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(item.number()).append("  Originale\n").append(android.text.BidiFormatter.getInstance().unicodeWrap(item.original()))
                .append("\nItaliano\n").append(item.italian().isEmpty() ? "In elaborazione…" : item.italian());
        }
        transcript.setText(text);
        ListeningSession target = attached;
        if (speech.isChecked() && voice != null && !entry.italian().isEmpty()
                && !voice.speak(entry.italian(), pcm -> activity.runOnUiThread(() -> {
                    if (target != attached || !speech.isChecked()) return;
                    if (pcm != null) target.mix().play(pcm);
                    else status.setText("Voce italiana non disponibile");
                }))) status.setText("Voce italiana occupata o non installata");
    }

    void remoteAudio(short[] pcm) {
        if (remoteTranslation != null) remoteTranslation.capture(pcm,pcm.length);
    }

    private void updateRemote() {
        if (remoteTranslation != null) remoteTranslation.close();
        remoteTranslation = null;
        if (remote.isChecked() && translation != null) {
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
        toggle.setText("Avvia traduzione in italiano");
        status.setText("Traduzione ferma");
    }
}

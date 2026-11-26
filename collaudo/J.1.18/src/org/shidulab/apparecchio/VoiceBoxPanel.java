package org.shidulab.apparecchio;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.widget.*;
import java.util.concurrent.Executors;

final class VoiceBoxPanel implements AutoCloseable {
    private final Activity activity;
    private final VoiceBox box;
    private final EditText recipient, message;
    private final Button record;
    private final TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService storage = Executors.newSingleThreadExecutor();
    private final Runnable timeout = this::recording;
    private ListeningSession session;
    private VoiceBox.Capture capture;
    private String recordingFor;
    private boolean closed;

    private final java.util.function.Predicate<short[]> queueVoice;
    VoiceBoxPanel(Activity activity, LinearLayout panel, java.util.function.Predicate<short[]> queueVoice) {
        this.activity = activity;
        this.queueVoice = queueVoice;
        box = new VoiceBox(activity.getApplicationContext());
        TextView title = new TextView(activity);
        title.setText("VoiceBox");
        title.setTextSize(22);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        recipient = field(panel, "Destinatario della bozza", 80);
        message = field(panel, "Messaggio", 2000);
        button(panel, "Salva testo in VoiceBox", () -> {
            String person = recipient.getText().toString().trim();
            String text = message.getText().toString().trim();
            if (person.isEmpty() || text.isEmpty()) {
                status("Inserisci destinatario e messaggio.");
                return;
            }
            save(person, text, null);
        });
        record = button(panel, "Registra bozza vocale", this::recording);
        button(panel, "Apri le bozze locali", this::show);
        status = new TextView(activity);
        status.setText("Bozze sul dispositivo");
        panel.addView(status);
    }

    private Button button(LinearLayout panel, String title, Runnable action) {
        Button b = new Button(activity);
        b.setText(title);
        b.setOnClickListener(v -> action.run());
        panel.addView(b);
        return b;
    }

    private EditText field(LinearLayout panel, String hint, int limit) {
        EditText f = new EditText(activity);
        f.setHint(hint);
        f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        panel.addView(f);
        return f;
    }

    private void recording() {
        if (capture != null) {
            main.removeCallbacks(timeout);
            session.voiceNote(null);
            short[] audio = capture.finish();
            capture = null;
            session = null;
            record.setText("Registra bozza vocale");
            if (audio.length < 4800) { status("Bozza troppo breve."); return; }
            save(recordingFor, "", audio);
            return;
        }
        String person = recipient.getText().toString().trim();
        ListeningSession listening = ListeningService.currentSession();
        if (person.isEmpty() || listening == null) { status("Indica il destinatario e attiva Ascolta."); return; }
        recordingFor = person;
        capture = new VoiceBox.Capture();
        session = listening;
        session.voiceNote(capture);
        record.setText("Ferma e salva");
        status("Registrazione locale, fino a 15 secondi.");
        main.postDelayed(timeout, 15000);
    }

    private void save(String person, String text, short[] pcm) {
        status("Salvataggio…");
        storage.execute(() -> {
            try {
                box.save(person, text, pcm);
                main.post(() -> status("Bozza salvata sul dispositivo."));
            } catch (Exception e) { main.post(() -> status("Salvataggio non riuscito.")); }
        });
    }

    private void show() {
        storage.execute(() -> {
            try {
                var drafts = box.drafts();
                main.post(() -> {
                    if (closed) return;
                    if (drafts.isEmpty()) { status("Nessuna bozza."); return; }
                    String[] labels = drafts.stream().map(VoiceBox.Draft::toString).toArray(String[]::new);
                    new AlertDialog.Builder(activity).setTitle("Bozze locali")
                        .setItems(labels, (dialog, i) -> open(drafts.get(i)))
                        .setNegativeButton("Chiudi", null).show();
                });
            } catch (Exception e) { main.post(() -> status("Archivio non disponibile.")); }
        });
    }

    private void open(VoiceBox.Draft draft) {
        if (!draft.audio()) {
            new AlertDialog.Builder(activity).setTitle("Per " + draft.recipient())
                .setMessage(draft.message()).setPositiveButton("Chiudi", null).show();
            return;
        }
        ListeningSession listening = ListeningService.currentSession();
        if (listening == null) { status("Attiva Ascolta per riprodurre nelle cuffie."); return; }
        storage.execute(() -> {
            try {
                short[] pcm = box.audio(draft.id());
                main.post(() -> {
                    if (closed || pcm == null) return;
                    new AlertDialog.Builder(activity).setTitle("Bozza per " + draft.recipient())
                        .setItems(new String[]{"Ascolta in cuffia","Accoda al partecipante selezionato in Comunica"},(dialog,choice)->{
                            if(choice==0&&listening==ListeningService.currentSession())listening.mix().playNote(pcm);
                            if(choice==1&&!queueVoice.test(pcm))status("Collega prima il servizio in Comunica.");
                        }).show();
                });
            } catch (Exception e) { main.post(() -> status("Riproduzione non disponibile.")); }
        });
    }

    void listeningChanged(boolean active) { if (!active && capture != null) recording(); }
    private void status(String text) { if (!closed) status.setText(text); }

    @Override public void close() {
        if (capture != null) recording();
        closed = true;
        storage.execute(box::close);
        storage.shutdown();
    }
}

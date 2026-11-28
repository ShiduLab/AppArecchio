package org.shidulab.apparecchio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.json.JSONObject;

final class EmergencyPanel {
    static final int PICK_CONTACT = 827;
    private static final int SMS_PERMISSION = 828, MIC_PERMISSION = 829, LOCATION_PERMISSION = 830;
    private final Activity activity;
    private final SharedPreferences draft;
    private final EditText text;
    private final TextView status, chosen;
    private final CheckBox location;
    private final Button record, send;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable stopRecording = this::finishRecording;
    private final java.util.concurrent.ExecutorService storage = Executors.newSingleThreadExecutor();
    private MediaRecorder recorder;
    private String recordingId;
    private File recordingFile;

    EmergencyPanel(Activity activity, LinearLayout parent) {
        this.activity = activity;
        draft = activity.getSharedPreferences("emergency-draft", Activity.MODE_PRIVATE);
        var toggle = new Button(activity);
        toggle.setText("Emergenza");
        parent.addView(toggle);
        var panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        parent.addView(panel);
        toggle.setOnClickListener(view -> {
            boolean open = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? "Chiudi Emergenza" : "Emergenza");
        });
        button(panel, "112 · Apri telefono", this::confirmEmergencyDial);
        chosen = new TextView(activity);
        panel.addView(chosen);
        showContact();
        button(panel, "Destinatario · Apri rubrica", this::pickContact);
        text = new EditText(activity);
        text.setHint("Messaggio di emergenza");
        text.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(600)});
        text.setText(draft.getString("text", ""));
        text.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft.edit().putString("text", s.toString()).apply();
            }
            public void afterTextChanged(Editable s) { }
        });
        panel.addView(text);
        record = button(panel, "Registra vocale · 15 secondi", this::recording);
        button(panel, "Rimuovi il vocale dalla bozza", () -> {
            draft.edit().remove("audio").apply();
            show("Vocale tolto dalla bozza.");
        });
        location = new CheckBox(activity);
        location.setText("Includi l’ultima posizione conosciuta");
        panel.addView(location);
        location.setOnCheckedChangeListener((view, checked) -> {
            if (checked && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            }
        });
        send = button(panel, "Salva e avvia invio", this::confirm);
        button(panel, "Stato degli invii", this::showJobs);
        status = new TextView(activity);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        show(draft.contains("audio") ? "Bozza vocale salvata sul telefono." : "SMS per testo e posizione. MMS per il vocale.");
        storage.execute(() -> {
            try { EmergencyOutbox.restore(activity.getApplicationContext()); }
            catch (Exception ignored) { }
        });
    }

    private void confirmEmergencyDial() {
        new AlertDialog.Builder(activity).setTitle("Numero di emergenza 112")
            .setMessage("Vuoi proseguire per aprire il tastierino del 112?")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Continua", (dialog, which) -> new AlertDialog.Builder(activity)
                .setTitle("Conferma apertura del tastierino")
                .setMessage("Vedrai il 112 già composto. Per chiamare dovrai premere il tasto di chiamata del telefono.")
                .setNegativeButton("Annulla", null)
                .setNeutralButton("Apri tastierino", (confirmation, action) -> openEmergencyDial())
                .show())
            .show();
    }

    private void openEmergencyDial() {
        try { activity.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))); }
        catch (Exception e) { show("Telefono non disponibile su questo dispositivo."); }
    }

    private Button button(LinearLayout panel, String label, Runnable click) {
        var button = new Button(activity);
        button.setText(label);
        button.setOnClickListener(view -> click.run());
        panel.addView(button);
        return button;
    }

    private void pickContact() {
        try { activity.startActivityForResult(new Intent(Intent.ACTION_PICK, Phone.CONTENT_URI), PICK_CONTACT); }
        catch (Exception e) { show("Rubrica non disponibile su questo telefono."); }
    }

    void contactSelected(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        try (var cursor = activity.getContentResolver().query(uri,
                new String[]{Phone.DISPLAY_NAME, Phone.NUMBER}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalArgumentException();
            String name = cursor.getString(0);
            String number = PhoneNumberUtils.normalizeNumber(cursor.getString(1));
            if (number == null || !number.matches("\\+?[0-9]{5,18}")) throw new IllegalArgumentException();
            draft.edit().putString("name", name).putString("number", number).apply();
            showContact();
        } catch (Exception e) { show("Il contatto selezionato non ha un numero utilizzabile."); }
    }

    private void showContact() {
        String number = draft.getString("number", "");
        chosen.setText(number.isEmpty() ? "Scegli il numero dalla rubrica"
                : draft.getString("name", "") + " · " + number);
    }

    private void recording() {
        if (recorder != null) { finishRecording(); return; }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        if (ListeningService.currentSession() != null) {
            show("Ferma Ascolta prima di registrare il vocale di emergenza.");
            return;
        }
        try {
            recordingId = UUID.randomUUID().toString();
            recordingFile = new File(EmergencyOutbox.directory(activity, "recordings"), recordingId + ".3gp");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(activity) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            record.setText("Ferma e salva il vocale");
            show("Registrazione in corso.");
            main.postDelayed(stopRecording, 15000);
        } catch (Exception e) {
            releaseRecorder();
            show("Registrazione non avviata.");
        }
    }

    private void finishRecording() {
        if (recorder == null) return;
        main.removeCallbacks(stopRecording);
        try {
            recorder.stop();
            if (recordingFile.length() <= 0) throw new IllegalStateException();
            draft.edit().putString("audio", recordingId).apply();
            show("Bozza vocale salvata sul telefono.");
        } catch (RuntimeException e) {
            if (recordingFile != null) recordingFile.delete();
            show("Registrazione troppo breve o non riuscita.");
        } finally { releaseRecorder(); }
    }

    private void releaseRecorder() {
        if (recorder != null) recorder.release();
        recorder = null;
        recordingId = null;
        recordingFile = null;
        record.setText("Registra vocale · 15 secondi");
    }

    private Location lastPosition() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        Location latest = null;
        var manager = activity.getSystemService(LocationManager.class);
        if (manager == null) return null;
        for (String provider : manager.getProviders(true)) {
            try {
                Location sample = manager.getLastKnownLocation(provider);
                if (sample != null && (latest == null || sample.getElapsedRealtimeNanos() > latest.getElapsedRealtimeNanos())) {
                    latest = sample;
                }
            } catch (RuntimeException ignored) { }
        }
        return latest;
    }

    private void confirm() {
        if (recorder != null) finishRecording();
        String name = draft.getString("name", ""), number = draft.getString("number", "");
        String message = text.getText().toString().trim(), audio = draft.getString("audio", "");
        if (number.isEmpty()) { pickContact(); return; }
        Location point = location.isChecked() ? lastPosition() : null;
        if (message.isEmpty() && audio.isEmpty() && point == null) { show("Aggiungi testo, vocale o posizione."); return; }
        String when = point == null ? "" : DateFormat.getDateTimeInstance().format(new Date(point.getTime()));
        String payload = point == null ? message : message
            + (message.isEmpty() ? "" : "\n\n") + "Ultima posizione nota · " + when + "\n"
            + "https://maps.google.com/?q=" + point.getLatitude() + "," + point.getLongitude();
        String info = name + " · " + number;
        if (location.isChecked()) info += point == null ? "\nPosizione assente." : "\nUltima posizione del " + when;
        new AlertDialog.Builder(activity).setTitle("Invio di emergenza")
            .setMessage(info + "\nSMS e MMS seguono la tariffa del gestore. L’app riprova finché il gestore accetta l’invio.")
            .setNegativeButton("Annulla", null).setPositiveButton("Salva e avvia", (dialog, which) -> {
                send.setEnabled(false);
                storage.execute(() -> {
                    try {
                        EmergencyOutbox.add(activity.getApplicationContext(), name, number, payload, audio);
                        main.post(() -> {
                            send.setEnabled(true);
                            show("Messaggio salvato nella coda locale.");
                            if (activity.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                                activity.requestPermissions(new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION);
                            }
                        });
                    } catch (Exception e) {
                        main.post(() -> { send.setEnabled(true); show("Impossibile accodare il messaggio. La bozza resta sul telefono."); });
                    }
                });
            }).show();
    }

    void permissionResult(int code, int[] results) {
        if (code != SMS_PERMISSION && code != MIC_PERMISSION && code != LOCATION_PERMISSION) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == MIC_PERMISSION && granted) recording();
        if (code == LOCATION_PERMISSION && !granted) location.setChecked(false);
        if (code == SMS_PERMISSION && !granted) show("Messaggio in coda. Consenti SMS per avviare l’invio.");
        if (code == SMS_PERMISSION && granted) show("Messaggio in coda. Il telefono tenta l’invio.");
    }

    private void showJobs() {
        storage.execute(() -> {
            try {
                var jobs = EmergencyOutbox.list(activity);
                main.post(() -> {
                    if (activity.isDestroyed()) return;
                    if (jobs.isEmpty()) { show("Nessun invio di emergenza."); return; }
                    String[] rows = jobs.stream().map(this::describe).toArray(String[]::new);
                    new AlertDialog.Builder(activity).setTitle("Invii di emergenza")
                        .setItems(rows, (dialog, which) -> details(jobs.get(which))).setNegativeButton("Chiudi", null).show();
                });
            } catch (Exception e) { main.post(() -> show("Archivio degli invii non disponibile.")); }
        });
    }

    private String describe(JSONObject job) {
        var info = new StringBuilder(job.optString("name") + " · " + job.optString("number"));
        if (job.optBoolean("stopped")) return info.append("\n").append(EmergencyOutbox.STOPPED).toString();
        if (!job.optString("text").isEmpty()) info.append("\nSMS · ").append(job.optString("sms"));
        if (!job.optString("audio").isEmpty()) info.append("\nMMS · ").append(job.optString("mms"));
        return info.toString();
    }

    private void details(JSONObject job) {
        var dialog = new AlertDialog.Builder(activity).setTitle("Stato invio")
            .setMessage(describe(job)).setNegativeButton("Chiudi", null);
        if (EmergencyOutbox.active(job)) dialog.setNeutralButton("Consenti SMS", (d, i) -> {
            if (activity.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION);
            }
        }).setPositiveButton("Ferma i nuovi tentativi", (d, i) -> storage.execute(() -> {
            try {
                EmergencyOutbox.stop(activity, job.optString("id"));
                main.post(() -> show("Nuovi tentativi fermati."));
            } catch (Exception e) { main.post(() -> show("Impossibile aggiornare l’invio.")); }
        }));
        dialog.show();
    }

    void pause() { if (recorder != null) finishRecording(); }
    void close() { pause(); storage.shutdown(); }
    private void show(String message) { if (!activity.isDestroyed()) status.setText(message); }
}

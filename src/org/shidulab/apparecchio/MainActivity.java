package org.shidulab.apparecchio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.view.WindowInsets;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;

public final class MainActivity extends Activity {
    private final SeekBar[] bands = new SeekBar[5];
    private LinearLayout panel;
    private TextView status;
    private SeekBar gain, noise;
    private Switch voice, dynamics, impulse;
    private boolean applyingProfile;
    private Button toggle;
    private AudioManager audio;
    private SharedPreferences profiles;
    private boolean visible;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = getSystemService(AudioManager.class);
        profiles = getSharedPreferences("profili", MODE_PRIVATE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff141617);
        root.setPadding(dp(20), dp(10), dp(20), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(20) + safe.left, dp(10) + safe.top,
                    dp(20) + safe.right, dp(8) + safe.bottom);
            return insets;
        });
        setContentView(root);
        root.addView(title("AppArecchio", 28));
        status = new TextView(this);
        status.setTextSize(16);
        status.setText("Ascolto fermo. Collega le cuffie e parti a volume basso.");
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        toggle = new Button(this);
        toggle.setText("Avvia ascolto");
        toggle.setMinHeight(dp(52));
        toggle.setOnClickListener(view -> {
            if (ListeningService.isBusy()) ListeningService.stopFromScreen();
            else requestStart();
        });
        root.addView(toggle);

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        gain = slider("Guadagno", -24, 12, -12, " dB");
        panel.addView(title("BustaBanda", 22));
        for (int i = 0; i < bands.length; i++) {
            bands[i] = slider(BustaBanda.FREQUENCIES[i] + " Hz", -12, 6, 0, " dB");
        }
        noise = slider("Riduzione rumore", 0, 3, 0, "");
        panel.addView(title("Ascolto ambientale", 22));
        voice = settingSwitch("Enfasi voce", false);
        dynamics = settingSwitch("Suoni deboli", false);
        impulse = settingSwitch("Attenua picchi improvvisi", true);
        panel.addView(title("Profili locali", 22));
        button("Salva profilo", this::saveProfile);
        button("Carica profilo", this::loadProfile);
        button("Ripristina controlli", () -> apply("-12,0,0,0,0,0,0"));
        if (state != null) apply(state.getString("controls", "-12,0,0,0,0,0,0"));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("controls", encode());
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        ListeningSession.Settings running = ListeningService.activeSettings();
        if (running != null) apply(encode(running));
        ListeningService.observe(state -> {
            status.setText(state.message());
            toggle.setText(state.busy() ? "Ferma ascolto" : "Avvia ascolto");
            toggle.setEnabled(!state.stopping() && (!state.busy() || ListeningService.activeSettings() != null));
        });
    }

    @Override protected void onPause() {
        visible = false;
        ListeningService.observe(null);
        super.onPause();
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private TextView title(String text, int size) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(size);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(6));
        title.setAccessibilityHeading(true);
        return title;
    }

    private SeekBar slider(String label, int min, int max, int initial, String unit) {
        TextView value = new TextView(this);
        value.setTextSize(16);
        value.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(value);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        bar.setContentDescription(label);
        bar.setMinimumHeight(dp(48));
        panel.addView(bar);
        showValue(value, bar, label, initial, unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar item, int progress, boolean fromUser) {
                showValue(value, item, label, progress + min, unit);
                publishSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar item) {}
            @Override public void onStopTrackingTouch(SeekBar item) {}
        });
        return bar;
    }

    private void showValue(TextView display, SeekBar bar, String label, int value, String unit) {
        String setting = unit.isEmpty() && value == 0 ? "Spenta" : value + unit;
        display.setText(label + "   " + setting);
        if (android.os.Build.VERSION.SDK_INT >= 30) bar.setStateDescription(setting);
    }

    private void button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        panel.addView(button);
    }

    private Switch settingSwitch(String label, boolean initial) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setMinHeight(dp(48));
        control.setChecked(initial);
        control.setOnCheckedChangeListener((view, checked) -> publishSettings());
        panel.addView(control);
        return control;
    }

    private void publishSettings() {
        if (!applyingProfile) ListeningService.updateSettings(settings());
    }

    private ListeningSession.Settings settings() {
        int[] eq = new int[bands.length];
        for (int i = 0; i < bands.length; i++) eq[i] = bands[i].getProgress() - 12;
        return new ListeningSession.Settings(eq, gain.getProgress() - 24, noise.getProgress(),
                voice.isChecked(), dynamics.isChecked(), impulse.isChecked());
    }

    private String encode() {
        return encode(settings());
    }

    private static String encode(ListeningSession.Settings active) {
        StringBuilder data = new StringBuilder().append(active.gain()).append(',').append(active.noise());
        for (int db : active.bands()) data.append(',').append(db);
        data.append(',').append(active.voice() ? 1 : 0)
                .append(',').append(active.dynamics() ? 1 : 0)
                .append(',').append(active.impulse() ? 1 : 0);
        return data.toString();
    }

    private void apply(String data) {
        if (data == null) return;
        String[] fields = data.split(",");
        int originalLength = bands.length + 2;
        if (fields.length != originalLength && fields.length != originalLength + 3) return;
        int[] values = new int[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) values[i] = Integer.parseInt(fields[i]);
        } catch (NumberFormatException invalid) {
            return;
        }
        applyingProfile = true;
        gain.setProgress(values[0] + 24);
        noise.setProgress(values[1]);
        for (int i = 0; i < bands.length; i++) bands[i].setProgress(values[i + 2] + 12);
        boolean extended = values.length > originalLength;
        voice.setChecked(extended && values[originalLength] == 1);
        dynamics.setChecked(extended && values[originalLength + 1] == 1);
        impulse.setChecked(!extended || values[originalLength + 2] == 1);
        applyingProfile = false;
        publishSettings();
    }

    private void saveProfile() {
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setHint("Nome del profilo");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Salva profilo").setView(name)
                .setNegativeButton("Annulla", null).setPositiveButton("Salva", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String key = name.getText().toString().trim();
                    if (key.isEmpty() || key.length() > 40) {
                        name.setError("Inserisci da 1 a 40 caratteri.");
                        return;
                    }
                    dialog.dismiss();
                    if (profiles.contains(key)) {
                        new AlertDialog.Builder(this).setTitle("Sostituire il profilo?")
                                .setMessage(key).setNegativeButton("Annulla", null)
                                .setPositiveButton("Sostituisci", (d, w) -> store(key)).show();
                    } else store(key);
                }));
        dialog.show();
    }

    private void store(String name) {
        profiles.edit().putString(name, encode()).apply();
        Toast.makeText(this, "Profilo salvato", Toast.LENGTH_SHORT).show();
    }

    private void loadProfile() {
        ArrayList<String> names = new ArrayList<>(profiles.getAll().keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) {
            Toast.makeText(this, "Nessun profilo salvato", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Carica profilo")
                .setItems(names.toArray(new String[0]),
                        (dialog, which) -> apply(profiles.getString(names.get(which), null)))
                .setNegativeButton("Annulla", null).show();
    }

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences permissions = getSharedPreferences("permessi", MODE_PRIVATE);
            if (!permissions.getBoolean("notifiche_richieste", false)) {
                permissions.edit().putBoolean("notifiche_richieste", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
                return;
            }
        }
        chooseOutput();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            status.setText("Consenti il microfono nelle impostazioni di AppArecchio per ascoltare.");
        }
    }

    private void chooseOutput() {
        AudioDeviceInfo microphone = null;
        for (AudioDeviceInfo input : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (input.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                microphone = input;
                break;
            }
        }
        if (microphone == null) {
            status.setText("Microfono interno non disponibile.");
            return;
        }
        ArrayList<AudioDeviceInfo> headphones = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!ListeningSession.isHeadphoneType(device.getType())) continue;
            headphones.add(device);
            names.add(ListeningSession.outputLabel(device) + " · " + device.getProductName());
        }
        if (headphones.isEmpty()) {
            status.setText("Collega cuffie con cavo, USB compatibili o Bluetooth per audio multimediale.");
            return;
        }
        AudioDeviceInfo phoneMic = microphone;
        if (headphones.size() == 1) {
            start(phoneMic, headphones.get(0));
            return;
        }
        new AlertDialog.Builder(this).setTitle("Scegli le cuffie")
                .setItems(names.toArray(new String[0]), (dialog, which) -> start(phoneMic, headphones.get(which)))
                .setNegativeButton("Annulla", null).show();
    }

    private void start(AudioDeviceInfo microphone, AudioDeviceInfo headphones) {
        if (visible) ListeningService.begin(this, microphone.getId(), headphones.getId(), settings());
    }
}

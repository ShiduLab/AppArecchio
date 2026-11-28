package org.shidulab.apparecchio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.view.WindowInsets;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;

public final class MainActivity extends Activity {
    private static final int DEFAULT_GAIN = -12;
    private static final int DEFAULT_NOISE = 0;
    private final SeekBar[] bands = new SeekBar[BustaBanda.FREQUENCIES.length];
    private final int[] frequencies = BustaBanda.FREQUENCIES.clone();
    private final int[] widths = new int[bands.length];
    private final Button[] bandControls = new Button[bands.length];
    private LinearLayout panel;
    private TextView status;
    private SeekBar gain, noise;
    private Switch voice, dynamics, impulse;
    private boolean applyingProfile;
    private Button toggle;
    private AudioManager audio;
    private SharedPreferences profiles;
    private boolean visible;
    private TranslationPanel translation;
    private VoiceBoxPanel voiceBox;
    private ConnectPanel connect;
    private NearbyPanel nearby;

    private EmergencyPanel emergency;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = getSystemService(AudioManager.class);
        profiles = getSharedPreferences("profili", MODE_PRIVATE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff141617);
        root.setPadding(dp(14), dp(10), dp(14), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(14) + safe.left, dp(10) + safe.top,
                    dp(14) + safe.right, dp(8) + safe.bottom);
            return insets;
        });
        setContentView(root);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        ImageButton shidulab = new ImageButton(this);
        shidulab.setBackground(null);
        @SuppressWarnings("DiscouragedApi")
        int logoId = getResources().getIdentifier("botologo", "drawable", getPackageName());
        shidulab.setImageResource(logoId);
        shidulab.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shidulab.setPadding(dp(6), 0, dp(6), 0);
        shidulab.setContentDescription("AppArecchio. Apri il manuale");
        shidulab.setOnClickListener(view -> openLink("https://www.shidulab.org/SofficiWare-ShiduLab/Manuale-AppArecchio.html"));
        header.addView(shidulab, new LinearLayout.LayoutParams(dp(124), dp(52)));
        panel.addView(title("AppArecchio", 28));
        status = new TextView(this);
        status.setTextSize(16);
        status.setText("Ascolto fermo. Collega le cuffie e parti a volume basso.");
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        toggle = new Button(this);
        toggle.setText("Ascolta");
        toggle.setMinHeight(dp(52));
        toggle.setOnClickListener(view -> {
            if (ListeningService.isBusy()) ListeningService.stopFromScreen();
            else requestStart();
        });
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(0, -2, 1);
        action.setMarginStart(dp(8));
        header.addView(toggle, action);

        gain = slider("Guadagno", -24, 12, DEFAULT_GAIN, " dB");
        panel.addView(title("BustaBanda", 22));
        addBands();
        noise = slider("Riduzione rumore", 0, 3, DEFAULT_NOISE, "");
        panel.addView(title("Ascolto ambientale", 22));
        voice = settingSwitch("Enfasi voce", false);
        dynamics = settingSwitch("Suoni deboli", false);
        impulse = settingSwitch("Attenua picchi improvvisi", true);
        panel.addView(title("Profili locali", 22));
        button("Salva profilo", this::saveProfile);
        button("Carica profilo", this::loadProfile);
        button("Ripristina controlli", () -> apply("-12,0,0,0,0,0,0"));
        translation = new TranslationPanel(this, panel);
        final Button dialogBar = new Button(this);
        dialogBar.setAllCaps(false);
        dialogBar.setText("Dialogo, voce e file  ▾");
        dialogBar.setMinHeight(dp(52));
        panel.addView(dialogBar);
        final LinearLayout dialogPanel = new LinearLayout(this);
        dialogPanel.setOrientation(LinearLayout.VERTICAL);
        dialogPanel.setVisibility(android.view.View.GONE);
        panel.addView(dialogPanel);
        Runnable expandDialog = () -> {
            boolean open = dialogPanel.getVisibility() != android.view.View.VISIBLE;
            dialogPanel.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
            dialogBar.setText(open ? "Dialogo, voce e file  ▴" : "Dialogo, voce e file  ▾");
            dialogBar.setContentDescription(open ? "Chiudi dialogo, voce e file" : "Apri dialogo, voce e file");
            if (open) scroll.post(() -> scroll.smoothScrollTo(0, dialogBar.getTop()));
        };
        dialogBar.setContentDescription("Apri dialogo, voce e file");
        dialogBar.setOnClickListener(view -> expandDialog.run());
        nearby = new NearbyPanel(this, dialogPanel, translation::remoteAudio);
        LinearLayout internetPanel = new LinearLayout(this);
        internetPanel.setOrientation(LinearLayout.VERTICAL);
        dialogPanel.addView(internetPanel);
        connect = new ConnectPanel(this, internetPanel, translation::remoteAudio);
        if (BuildConfig.OCCHIO_SERVICE.isBlank()) internetPanel.setVisibility(android.view.View.GONE);
        voiceBox = new VoiceBoxPanel(this, dialogPanel, connect::enqueueAudio);
        addCarrierActions(dialogPanel);
        emergency = new EmergencyPanel(this, panel);
        Button shareApp = new Button(this);
        shareApp.setText("Condividi App");
        shareApp.setAllCaps(false);
        shareApp.setOnClickListener(view -> {
            Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "AppArecchio")
                .putExtra(Intent.EXTRA_TEXT,
                    "https://www.shidulab.org/SofficiWare-ShiduLab/AppArecchio-Tattico.html");
            startActivity(Intent.createChooser(share, "Condividi App"));
        });
        panel.addView(shareApp);
        Button support = link("♥  Il tuo supporto", "https://paypal.me/JostaSeu");
        support.setContentDescription("Il tuo supporto. Apri PayPal");
        support.setAllCaps(false);
        panel.addView(support);
        if (state != null) apply(state.getString("controls", "-12,0,0,0,0,0,0"));
        if (state == null && getIntent() != null) connect.acceptLink(getIntent().getData());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(connect!=null)connect.acceptLink(intent.getData());
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
            if (translation != null) translation.listeningChanged(state.busy());
            if (voiceBox != null) voiceBox.listeningChanged(state.busy());
            if (connect != null) connect.listeningChanged(state.busy());
            if (nearby != null) nearby.listeningChanged(state.busy());
            toggle.setText(state.busy() ? "Ferma ascolto" : "Ascolta");
            toggle.setEnabled(!state.stopping() && (!state.busy() || ListeningService.activeSettings() != null));
        });
    }

    @Override protected void onPause() {
        if (emergency != null) emergency.pause();
        visible = false;
        ListeningService.observe(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (emergency != null) emergency.close();
        if (translation != null) translation.close();
        if (voiceBox != null) voiceBox.close();
        if (connect != null) connect.close();
        if (nearby != null) nearby.close();
        super.onDestroy();
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
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(heading);
        TextView value = new TextView(this);
        value.setTextSize(16);
        value.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        SeekBar bar = new SeekBar(this);
        heading.addView(resetButton(label, bar, initial - min), resetSize());
        panel.addView(bar);
        bindSlider(bar, value, label, min, max, initial, unit, false);
        return bar;
    }

    private void addBands() {
        java.util.Arrays.fill(widths, BustaBanda.DEFAULT_Q);
        panel.addView(title("10 bande · Scorri a lato. Tocca Hz per frequenza e Q.", 14));
        BandStrip strip = new BandStrip(this);
        strip.setFillViewport(true);
        strip.setHorizontalScrollBarEnabled(true);
        strip.setScrollbarFadingEnabled(false);
        panel.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        strip.addView(row, new ViewGroup.LayoutParams(-2, -2));
        for (int i = 0; i < bands.length; i++) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            row.addView(column, new LinearLayout.LayoutParams(dp(68), -2, 1));
            TextView value = new TextView(this);
            value.setTextSize(13);
            value.setGravity(Gravity.CENTER);
            value.setMinLines(3);
            value.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final int index = i;
            Button options = new Button(this);
            options.setTextSize(11);
            options.setPadding(0, 0, 0, 0);
            options.setMinWidth(0);
            options.setMinimumWidth(0);
            options.setOnClickListener(view -> editBand(index));
            bandControls[i] = options;
            updateBandControl(i);
            column.addView(options, new LinearLayout.LayoutParams(-1, dp(48)));
            column.addView(value);
            VerticalBand band = new VerticalBand(this);
            column.addView(band, new LinearLayout.LayoutParams(-1, dp(156)));
            bands[i] = band.slider;
            String label = "Banda " + (i + 1);
            bindSlider(band.slider, value, label,
                    BustaBanda.EXCLUDED, BustaBanda.MAX_BAND_GAIN, 0, " dB", true);
            LinearLayout.LayoutParams reset = resetSize();
            reset.gravity = Gravity.CENTER_HORIZONTAL;
            column.addView(resetButton(label, band.slider, -BustaBanda.EXCLUDED), reset);
        }
    }

    private void updateBandControl(int index) {
        bandControls[index].setText(frequencies[index] + " Hz");
        bandControls[index].setContentDescription("Modifica frequenza e Q, banda " + (index + 1));
    }

    private void editBand(int index) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText frequency = new EditText(this);
        frequency.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        frequency.setContentDescription("Frequenza in hertz");
        frequency.setText(String.valueOf(frequencies[index]));
        EditText width = new EditText(this);
        width.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        width.setContentDescription("Q della banda");
        width.setText(String.valueOf(widths[index] / 100.0));
        fields.addView(title("Frequenza in Hz", 14));
        fields.addView(frequency);
        fields.addView(title("Q · più alto, banda più stretta", 14));
        fields.addView(width);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Banda " + (index + 1))
                .setView(fields).setNegativeButton("Annulla", null)
                .setNeutralButton("Ripristina", (box, which) -> setBand(index,
                        BustaBanda.FREQUENCIES[index], BustaBanda.DEFAULT_Q))
                .setPositiveButton("Applica", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int hz;
                    double q;
                    try { hz = Integer.parseInt(frequency.getText().toString().trim()); }
                    catch (NumberFormatException e) { hz = 0; }
                    try { q = Double.parseDouble(width.getText().toString().trim().replace(',', '.')); }
                    catch (NumberFormatException e) { q = 0; }
                    if (hz < 20 || hz > 20000) { frequency.setError("Da 20 a 20000 Hz"); return; }
                    if (!(q >= .3 && q <= 12)) { width.setError("Da 0,3 a 12"); return; }
                    setBand(index, hz, (int) Math.round(q * 100));
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void setBand(int index, int frequency, int q) {
        frequencies[index] = frequency;
        widths[index] = q;
        updateBandControl(index);
        publishSettings();
    }

    private void bindSlider(SeekBar bar, TextView value, String label, int min, int max,
                            int initial, String unit, boolean stacked) {
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        bar.setThumbTintList(null);
        bar.setThumb(getDrawable(drawableId("mixer_thumb")));
        bar.setProgressTintList(ColorStateList.valueOf(0xff79a9b4));
        bar.setContentDescription(label);
        bar.setMinimumHeight(dp(48));
        showValue(value, bar, label, initial, unit, stacked);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar item, int progress, boolean fromUser) {
                showValue(value, item, label, progress + min, unit, stacked);
                publishSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar item) {}
            @Override public void onStopTrackingTouch(SeekBar item) {}
        });
    }

    private void showValue(TextView display, SeekBar bar, String label, int value, String unit, boolean stacked) {
        String setting = unit.isEmpty() && value == 0 ? "Spenta" : value + unit;
        if (stacked && value == BustaBanda.EXCLUDED) setting = "−∞ dB";
        display.setText(stacked ? label.replace(' ', '\n') + "\n" + setting : label + "   " + setting);
        if (android.os.Build.VERSION.SDK_INT >= 30) bar.setStateDescription(setting);
    }

    private LinearLayout.LayoutParams resetSize() {
        return new LinearLayout.LayoutParams(dp(48), dp(48));
    }

    private ImageButton resetButton(String label, SeekBar slider, int defaultProgress) {
        ImageButton reset = new ImageButton(this);
        android.util.TypedValue background = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
        reset.setBackgroundResource(background.resourceId);
        reset.setImageResource(drawableId("reset_control"));
        reset.setContentDescription("Ripristina " + label);
        reset.setOnClickListener(view -> slider.setProgress(defaultProgress));
        return reset;
    }

    private int drawableId(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private Button link(String label, String address) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> openLink(address));
        return button;
    }

    private void addCarrierActions(LinearLayout group) {
        LinearLayout row = new LinearLayout(this);
        group.addView(row);
        Button phone = new Button(this);
        phone.setText("Telefono");
        phone.setOnClickListener(v -> openCarrier(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))));
        row.addView(phone, new LinearLayout.LayoutParams(0,-2,1));
        Button sms = new Button(this);
        sms.setText("SMS");
        sms.setOnClickListener(v -> openCarrier(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))));
        row.addView(sms, new LinearLayout.LayoutParams(0,-2,1));
    }

    private void openCarrier(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException failure) {
            Toast.makeText(this,"Funzione non disponibile su questo dispositivo.",Toast.LENGTH_SHORT).show();
        }
    }

    private void openLink(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "Non c’è un browser disponibile.", Toast.LENGTH_SHORT).show();
        }
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
        for (int i = 0; i < bands.length; i++) eq[i] = bands[i].getProgress() + BustaBanda.EXCLUDED;
        return new ListeningSession.Settings(eq, frequencies.clone(), widths.clone(), gain.getProgress() - 24, noise.getProgress(),
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
        for (int i = 0; i < BustaBanda.FREQUENCIES.length; i++)
            data.append(',').append(active.frequencies() == null ? BustaBanda.FREQUENCIES[i] : active.frequencies()[i]);
        for (int i = 0; i < BustaBanda.FREQUENCIES.length; i++)
            data.append(',').append(active.widths() == null ? BustaBanda.DEFAULT_Q : active.widths()[i]);
        return data.toString();
    }

    private void apply(String data) {
        if (data == null) return;
        String[] fields = data.split(",");
        boolean legacy = fields.length == 7 || fields.length == 10;
        int originalLength = legacy ? 7 : 2 + bands.length;
        if (!legacy && fields.length != originalLength + 3 + 2 * bands.length) return;
        int[] values = new int[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) values[i] = Integer.parseInt(fields[i]);
        } catch (NumberFormatException invalid) {
            return;
        }
        applyingProfile = true;
        gain.setProgress(values[0] + 24);
        noise.setProgress(values[1]);
        for (int i = 0; i < bands.length; i++) {
            int old = i - BustaBanda.LEGACY_OFFSET;
            int db = legacy ? (old >= 0 && old < 5 ? values[old + 2] : 0) : values[i + 2];
            bands[i].setProgress(db - BustaBanda.EXCLUDED);
            frequencies[i] = legacy ? BustaBanda.FREQUENCIES[i] : Math.max(20, Math.min(20000, values[originalLength + 3 + i]));
            widths[i] = legacy ? BustaBanda.DEFAULT_Q : Math.max(30, Math.min(1200, values[originalLength + 3 + bands.length + i]));
            updateBandControl(i);
        }
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
        if (request == NearbyPanel.PERMISSIONS && nearby != null) nearby.permissionResult(results);
        if (emergency != null) emergency.permissionResult(request, results);
        if (request == 1 && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            status.setText("Consenti il microfono nelle impostazioni di AppArecchio per ascoltare.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        if (nearby != null && request == NearbyPanel.PICK) nearby.selected(uri);
        if (nearby != null && request == NearbyPanel.SAVE) nearby.save(uri);
        if (emergency != null && request == EmergencyPanel.PICK_CONTACT) emergency.contactSelected(uri);
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

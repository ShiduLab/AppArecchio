package org.shidulab.apparecchio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.IBinder;
import java.util.function.Consumer;

public final class ListeningService extends Service {
    private static final String CHANNEL = "ascolto";
    private static final int NOTIFICATION = 1;
    private static ListeningService active;
    private static Consumer<State> observer;
    static record State(boolean busy, boolean stopping, String message) {}
    private static State state = new State(false, false,
            "Ascolto fermo. Collega le cuffie e parti a volume basso.");

    private AudioManager audio;
    private AudioFocusRequest focus;
    private ListeningSession session;
    private ListeningSession.Settings settings;
    private int inputId, outputId;
    private boolean watchingDevices;

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {

    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            for (AudioDeviceInfo device : removed) {
                if (device.getId() == inputId || device.getId() == outputId) {
                    stop("Dispositivo scollegato. Ascolto fermato.");
                    return;
                }
            }
        }
    };

    // The service, activity and status callbacks all access these fields on the main thread.
    static void observe(Consumer<State> listener) {
        observer = listener;
        if (listener != null) listener.accept(state);
    }

    private static void report(State next) {
        state = next;
        if (observer != null) observer.accept(next);
    }

    static boolean isBusy() { return state.busy(); }

    static ListeningSession.Settings activeSettings() {
        return active == null ? null : active.settings;
    }

    static void updateSettings(ListeningSession.Settings settings) {
        if (active == null || active.session == null || state.stopping()) return;
        active.settings = settings;
        active.session.update(settings);
    }

    static void begin(Context context, int input, int output, ListeningSession.Settings settings) {
        if (isBusy()) return;
        Intent intent = new Intent(context, ListeningService.class)
                .putExtra("input", input).putExtra("output", output)
                .putExtra("bands", settings.bands()).putExtra("frequencies", settings.frequencies())
                .putExtra("widths", settings.widths()).putExtra("gain", settings.gain())
                .putExtra("noise", settings.noise()).putExtra("voice", settings.voice())
                .putExtra("dynamics", settings.dynamics()).putExtra("impulse", settings.impulse());
        report(new State(true, false, "Verifica del percorso audio in corso."));
        try {
            context.startForegroundService(intent);
        } catch (IllegalStateException | SecurityException denied) {
            report(new State(false, false, "Android non ha consentito l’avvio dell’ascolto. Riprova dall’app aperta."));
        }
    }

    static void stopFromScreen() {
        if (active != null) active.stop("Ascolto fermato.");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (session != null) return START_NOT_STICKY;
        int[] bands = intent == null ? null : intent.getIntArrayExtra("bands");
        if (bands == null || bands.length != BustaBanda.FREQUENCIES.length) {
            finish("Ascolto fermato.");
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            audio = getSystemService(AudioManager.class);
            inputId = intent.getIntExtra("input", -1);
            outputId = intent.getIntExtra("output", -1);
            AudioDeviceInfo microphone = device(inputId, AudioManager.GET_DEVICES_INPUTS);
            AudioDeviceInfo headphones = device(outputId, AudioManager.GET_DEVICES_OUTPUTS);
            if (microphone == null || headphones == null) {
                finish("Il microfono o le cuffie non sono più disponibili.");
                return START_NOT_STICKY;
            }
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(ListeningSession.ATTRIBUTES)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0) stop("Ascolto fermato per un’interruzione audio.");
                    }).setWillPauseWhenDucked(true).build();
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                finish("L’audio è occupato. Riprova più tardi.");
                return START_NOT_STICKY;
            }

            settings = new ListeningSession.Settings(bands, bandExtra(intent, "frequencies", 20, 20000),
                    bandExtra(intent, "widths", 30, 1200), intent.getIntExtra("gain", -12),
                    intent.getIntExtra("noise", 0), intent.getBooleanExtra("voice", false),
                    intent.getBooleanExtra("dynamics", false), intent.getBooleanExtra("impulse", true));
            session = new ListeningSession(microphone, headphones, settings,
                    message -> report(new State(true, false, message)), this::finish);
            audio.registerAudioDeviceCallback(devices, null);
            watchingDevices = true;
            active = this;
            session.start();
            report(new State(true, false, "Verifica del percorso audio in corso."));
        } catch (IllegalStateException | SecurityException denied) {
            if (session != null) session.stop("Ascolto fermato.");
            finish("Android non ha consentito l’avvio del microfono.");
        }
        return START_NOT_STICKY;
    }

    private AudioDeviceInfo device(int id, int direction) {
        for (AudioDeviceInfo device : audio.getDevices(direction)) {
            if (device.getId() == id) return device;
        }
        return null;
    }

    private Notification notification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Ascolto ambientale", NotificationManager.IMPORTANCE_LOW));
        Intent screen = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, screen, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder message = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AppArecchio in ascolto")
                .setContentText("Tocca per tornare ai controlli.")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            message.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return message.build();
    }

    private void stop(String reason) {
        if (session == null || state.stopping()) return;
        report(new State(true, true, "Arresto dell’ascolto in corso."));
        session.stop(reason);
    }

    private void releaseAudio() {
        if (watchingDevices) {
            audio.unregisterAudioDeviceCallback(devices);
            watchingDevices = false;
        }
        if (focus != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }

    private void finish(String reason) {
        session = null;
        settings = null;
        if (active == this) active = null;
        releaseAudio();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        report(new State(false, false, reason));
    }

    @Override public void onDestroy() {
        if (session != null) {
            session.stop("Ascolto interrotto da Android.");
            if (active == this) active = null;
        }
        releaseAudio();
        super.onDestroy();
    }
    private static int[] bandExtra(Intent intent, String key, int min, int max) {
        int[] values = intent.getIntArrayExtra(key);
        if (values == null || values.length != BustaBanda.FREQUENCIES.length) return null;
        for (int value : values) if (value < min || value > max) return null;
        return values;
    }
}

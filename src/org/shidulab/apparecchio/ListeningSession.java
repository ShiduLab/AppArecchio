package org.shidulab.apparecchio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.util.function.Consumer;

final class ListeningSession {
    static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    record Settings(int[] bands, int gain, int noise, boolean voice, boolean dynamics, boolean impulse) {}

    private final AudioDeviceInfo microphone, headphones;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> report, finished;
    private volatile Settings settings;
    private volatile boolean stopped;
    private volatile String stopReason = "Ascolto fermato.";
    private volatile boolean verifiedRoute;

    ListeningSession(AudioDeviceInfo microphone, AudioDeviceInfo headphones, Settings settings,
                     Consumer<String> report, Consumer<String> finished) {
        this.microphone = microphone;
        this.headphones = headphones;
        this.settings = settings;
        this.report = report;
        this.finished = finished;
    }

    void update(Settings next) { settings = next; }
    void start() { new Thread(this::listen, "AppArecchio audio").start(); }
    void stop(String reason) {
        stopReason = reason;
        stopped = true;
    }

    static boolean isHeadphoneType(int type) {
        return switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                 AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                 AudioDeviceInfo.TYPE_BLE_HEADSET -> true;
            default -> false;
        };
    }

    static String outputLabel(AudioDeviceInfo device) {
        return switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET -> "Cuffie USB";
            default -> "Cuffie con cavo";
        };
    }

    private static boolean uses(AudioRouting stream, AudioDeviceInfo expected) {
        AudioDeviceInfo actual = stream.getRoutedDevice();
        return actual != null && actual.getId() == expected.getId();
    }

    private AudioFormat format(int rate, int channel) {
        return new AudioFormat.Builder().setSampleRate(rate).setChannelMask(channel)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
    }

    private void listen() {
        AudioRecord input = null;
        AudioTrack output = null;
        String result = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            int rate = 48000;
            short[] block = new short[240];
            int inSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int outSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (inSize <= 0 || outSize <= 0) throw new IllegalStateException("Formato audio non supportato.");
            input = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format(rate, AudioFormat.CHANNEL_IN_MONO))
                    .setBufferSizeInBytes(Math.max(inSize, block.length * 4)).build();
            output = new AudioTrack.Builder().setAudioAttributes(ATTRIBUTES)
                    .setAudioFormat(format(rate, AudioFormat.CHANNEL_OUT_MONO))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(Math.max(outSize, block.length * 4)).build();
            if (input.getState() != AudioRecord.STATE_INITIALIZED ||
                    output.getState() != AudioTrack.STATE_INITIALIZED ||
                    !input.setPreferredDevice(microphone) ||
                    !output.setPreferredDevice(headphones)) {
                throw new IllegalStateException("Impossibile impostare microfono e cuffie.");
            }
            AudioRouting.OnRoutingChangedListener check = stream -> {
                AudioDeviceInfo expected = stream instanceof AudioRecord ? microphone : headphones;
                if (verifiedRoute && !uses(stream, expected)) stop("Percorso audio cambiato. Ascolto fermato.");
            };
            input.addOnRoutingChangedListener(check, main);
            output.addOnRoutingChangedListener(check, main);
            input.startRecording();
            output.play();
            long deadline = SystemClock.elapsedRealtime() + 1500;
            while (!stopped && !(uses(input, microphone) && uses(output, headphones))) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new IllegalStateException("Android non ha confermato il percorso richiesto.");
                }
                write(output, block, block.length);
            }
            verifiedRoute = !stopped;
            BustaBanda dsp = new BustaBanda(rate);
            Settings applied = null;
            int hotSamples = 0;
            int activeBlocks = 0;
            while (!stopped) {
                int count = input.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) throw new IllegalStateException("Microfono interrotto.");
                if (stopped) break;
                if (!uses(input, microphone) || !uses(output, headphones)) {
                    stop("Percorso audio cambiato. Ascolto fermato.");
                    break;
                }
                Settings current = settings;
                if (applied != current) {
                    dsp.configure(current.bands(), current.gain(), current.noise(),
                            current.voice(), current.dynamics(), current.impulse());
                    applied = current;
                }
                boolean limiting = dsp.process(block, count);
                hotSamples = limiting ? hotSamples + count : Math.max(0, hotSamples - 4 * count);
                if (hotSamples >= rate / 4) {
                    stop("Picchi persistenti. Abbassa il guadagno prima di ripartire.");
                    break;
                }
                write(output, block, count);
                if (activeBlocks++ % 200 == 0) {
                    String status = "Ascolto attivo. " + outputLabel(headphones)
                            + ". Interruzioni di buffer: " + output.getUnderrunCount() + ".";
                    main.post(() -> { if (!stopped) report.accept(status); });
                }
            }
        } catch (RuntimeException failure) {
            result = failure.getMessage() == null ? "Audio non disponibile." : failure.getMessage();
        } finally {
            verifiedRoute = false;
            if (output != null) {
                try {
                    output.setVolume(0);
                    output.pause();
                    output.flush();
                } catch (IllegalStateException ignored) {
                    // The audio service may already have invalidated the stream.
                } finally {
                    output.release();
                }
            }
            if (input != null) input.release();
            String message = stopped || result == null ? stopReason : result;
            main.post(() -> finished.accept(message));
        }
    }

    private void write(AudioTrack output, short[] block, int count) {
        int offset = 0;
        while (!stopped && offset < count) {
            if (verifiedRoute && !uses(output, headphones)) {
                stop("Uscita cuffie persa. Ascolto fermato.");
                return;
            }
            int written = output.write(block, offset, count - offset, AudioTrack.WRITE_BLOCKING);
            if (written <= 0) throw new IllegalStateException("Riproduzione interrotta.");
            offset += written;
        }
    }
}

package org.shidulab.apparecchio;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.function.Consumer;

final class ItalianVoice implements AutoCloseable {
    private final Context context;
    private final TextToSpeech engine;
    private volatile boolean ready, closed;
    private File pending;
    private String activeId;

    ItalianVoice(Context context) {
        this.context = context.getApplicationContext();
        engine = new TextToSpeech(this.context, status -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> initialized(status)));
    }

    private void initialized(int status) {
        if (status != TextToSpeech.SUCCESS || closed) return;
        var voices = engine.getVoices();
        if (voices == null) return;
        var voice = voices.stream().filter(v -> v.getLocale().getLanguage().equals(Locale.ITALIAN.getLanguage())
                    && !v.isNetworkConnectionRequired()).findFirst();
        ready = voice.isPresent() && engine.setVoice(voice.get()) == TextToSpeech.SUCCESS;
    }

    synchronized boolean speak(String text, Consumer<short[]> play) {
        if (!ready || closed || pending != null) return false;
        try {
            File file = File.createTempFile("italiano-", ".wav", context.getCacheDir());
            String id = file.getName();
            pending = file;
            activeId = id;
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String tag) {}
                @Override public void onError(String tag) { finish(tag, false); }
                @Override public void onDone(String tag) { finish(tag, true); }
                private void finish(String tag, boolean ok) {
                    synchronized (ItalianVoice.this) {
                        if (!id.equals(tag) || !id.equals(activeId)) return;
                        try {
                            if (ok && !closed) play.accept(readWave(file));
                            else if (!closed) play.accept(null);
                        } catch (Exception ignored) {
                            if (!closed) play.accept(null);
                        } finally {
                            pending = null;
                            activeId = null;
                            file.delete();
                        }
                    }
                }
            });
            if (engine.synthesizeToFile(text, new Bundle(), file, id) == TextToSpeech.SUCCESS) return true;
            pending = null;
            activeId = null;
            file.delete();
        } catch (Exception ignored) { pending = null; activeId = null; }
        return false;
    }

    private static short[] readWave(File file) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.readInt() != 0x52494646) throw new IllegalArgumentException("Formato voce");
            in.skipBytes(4);
            if (in.readInt() != 0x57415645) throw new IllegalArgumentException("Formato voce");
            int rate = 0, channels = 0, bits = 0, format = 0;
            while (in.getFilePointer() + 8 <= in.length()) {
                int type = in.readInt(), length = Integer.reverseBytes(in.readInt());
                long start = in.getFilePointer();
                if (length < 0 || start + length > in.length()) break;
                if (type == 0x666d7420 && length >= 16) {
                    format = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
                    channels = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
                    rate = Integer.reverseBytes(in.readInt());
                    in.skipBytes(6);
                    bits = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
                } else if (type == 0x64617461 && format == 1 && (channels == 1 || channels == 2)
                           && rate >= 8000 && rate <= 96000 && bits == 16 && length <= 4_000_000) {
                    short[] source = new short[length / (2 * channels)];
                    for (int i = 0; i < source.length; i++) {
                        int value = Short.reverseBytes(in.readShort());
                        if (channels == 2) value = (value + Short.reverseBytes(in.readShort())) / 2;
                        source[i] = (short) value;
                    }
                    short[] result = new short[(int) ((long) source.length * 48000 / rate)];
                    for (int i = 0; i < result.length; i++) result[i] = source[(int) ((long) i * rate / 48000)];
                    return result;
                }
                in.seek(start + length + (length & 1));
            }
        }
        throw new IllegalArgumentException("Audio italiano non supportato");
    }

    @Override public synchronized void close() {
        closed = true;
        engine.stop();
        engine.shutdown();
        if (pending != null) pending.delete();
        pending = null;
        activeId = null;
    }
}

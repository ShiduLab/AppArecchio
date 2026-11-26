package org.shidulab.apparecchio;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

final class AmbientTranslation implements AutoCloseable {
    record Segment(int number, String original, String italian) {}
    private final DeviceLanguage engine;
    private final String language;
    private final Consumer<Segment> show;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<>(2);
    private short[] buffer = new short[48000];
    private int position, phase;
    private int sampleSum;
    private long energy;
    private volatile boolean closed;
    private final Thread worker;

    AmbientTranslation(android.content.Context context, String language, Consumer<Segment> show, Consumer<String> status) {
        engine = new DeviceLanguage(context, status);
        this.language = language;
        this.show = show;
        this.status = status;
        worker = new Thread(this::translate, "Traduzione ambientale");
        worker.start();
    }

    void capture(short[] samples, int count) {
        if (closed) return;
        for (int i = 0; i < count; i++) {
            sampleSum += samples[i];
            if (++phase != 3) continue;
            int value = sampleSum / 3;
            phase = 0;
            sampleSum = 0;
            buffer[position++] = (short) value;
            energy += (long) value * value;
            if (position == buffer.length) {
                if (energy / position >= 100 && queue.offer(buffer)) buffer = new short[buffer.length];
                position = 0;
                energy = 0;
            }
        }
    }

    private void translate() {
        int number = 0;
        while (!closed) {
            try {
                short[] samples = queue.take();
                int id = ++number;
                update("Trascrizione in corso");
                DeviceLanguage.Transcript result = engine.transcribe(samples, language);
                String original = result.text();
                if (original.isBlank()) continue;
                post(new Segment(id, original, ""));
                String italian = engine.translate(result);
                post(new Segment(id, original, italian));
                update("In ascolto per la traduzione");
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                update(e instanceof java.io.IOException ? e.getMessage() : "Risposta del servizio non leggibile");
            }
        }
    }

    private void post(Segment s) { main.post(() -> { if (!closed) show.accept(s); }); }
    private void update(String s) { main.post(() -> { if (!closed) status.accept(s); }); }

    @Override public void close() {
        closed = true;
        queue.clear();
        worker.interrupt();
        engine.close();
    }
}

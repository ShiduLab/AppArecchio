package org.shidulab.apparecchio;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.OutputStream;
import java.io.IOException;
import android.content.Context;

final class AmbientTranslation implements AutoCloseable {
    record Segment(int number, String original, String italian, boolean provisional, boolean event, boolean spokenItalian) {
        Segment(int number, String original, String italian) {
            this(number, original, italian, false, false, false);
        }
    }
    private record Pending(int id, DeviceLanguage.Transcript transcript) {}
    private record Frame(short[] samples, boolean audible) {}
    private final DeviceLanguage engine;
    private final String language;
    private final Consumer<Segment> show;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(4);
    private final ArrayBlockingQueue<Pending> pending = new ArrayBlockingQueue<>(8);
    private final AtomicInteger sequence = new AtomicInteger();
    private final boolean live, translate;
    private final Context context;
    private short[] buffer;
    private volatile float threshold;
    private volatile boolean events;
    private String lastEvent = "";
    private long lastEventTime;
    private int position, phase;
    private int sampleSum;
    private long energy;
    private volatile boolean closed;
    private final Thread worker;
    private final Thread translator;

    AmbientTranslation(android.content.Context context, String language, Consumer<Segment> show, Consumer<String> status) {
        this(context, language, show, status, false, true, false, .55f);
    }

    AmbientTranslation(Context context, String language, Consumer<Segment> show, Consumer<String> status,
            boolean live, boolean translate, boolean events, float threshold) {
        this.context = context.getApplicationContext();
        this.live = live;
        this.translate = translate;
        this.events = events;
        this.threshold = threshold;
        buffer = new short[live ? SoundEvents.WINDOW : 48000];
        engine = new DeviceLanguage(context, status);
        this.language = language;
        this.show = show;
        this.status = status;
        translator = live && translate ? new Thread(this::translatePending, "Traduzione dei sottotitoli") : null;
        if (translator != null) translator.start();
        worker = new Thread(live ? this::recognizeLive : this::translate, "Riconoscimento ambientale");
        worker.start();
    }

    void eventOptions(boolean enabled, float minimum) {
        events = enabled;
        threshold = minimum;
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
                boolean audible = energy / position >= 100;
                if (live || audible) {
                    var frame = new Frame(buffer, audible);
                    if (!queue.offer(frame)) {
                        queue.poll();
                        queue.offer(frame);
                    }
                    buffer = new short[buffer.length];
                }
                position = 0;
                energy = 0;
            }
        }
    }

    private void translate() {
        int number = 0;
        while (!closed) {
            try {
                short[] samples = queue.take().samples();
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

    private void recognizeLive() {
        try (var model = new SoundEvents(context)) {
            while (!closed) {
                Frame first = queue.take();
                if (!isSpeech(model, first)) continue;
                int id = sequence.incrementAndGet();
                try {
                    DeviceLanguage.Transcript result = engine.transcribe(language,
                        output -> streamSpeech(model, output, first),
                        interim -> post(new Segment(id, interim.text(), "", true, false, isItalian(interim))));
                    if (result.text().isBlank()) {
                        post(new Segment(id, "", ""));
                        update("Parlato poco intelligibile");
                    } else {
                        boolean spokenItalian = isItalian(result);
                        post(new Segment(id, result.text(), "", false, false, spokenItalian));
                        if (translate && !spokenItalian
                                && !pending.offer(new Pending(id, result))) {
                            update("La traduzione è in ritardo. La trascrizione continua.");
                        }
                    }
                } catch (IOException e) {
                    post(new Segment(id, "", ""));
                    update(e.getMessage());
                    Thread.sleep(1250);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError e) {
            update("Classificatore acustico non disponibile su questo dispositivo.");
        }
    }

    private void streamSpeech(SoundEvents model, OutputStream output, Frame first)
            throws IOException, InterruptedException {
        DeviceLanguage.writePcm(output, first.samples());
        short[] silence = new short[SoundEvents.WINDOW];
        int pauses = 0;
        for (int i = 1; i < 10 && !closed && pauses < 2; i++) {
            Frame next = queue.poll(2, TimeUnit.SECONDS);
            if (next == null) return;
            boolean speech = isSpeech(model, next);
            pauses = speech ? 0 : pauses + 1;
            DeviceLanguage.writePcm(output, speech ? next.samples() : silence);
        }
    }

    private boolean isSpeech(SoundEvents model, Frame frame) {
        if (!frame.audible()) {
            update("In ascolto · nessuna voce");
            return false;
        }
        SoundEvents.Detection result = model.detect(frame.samples(), threshold);
        String label = result.event();
        long now = android.os.SystemClock.elapsedRealtime();
        if (events && !label.isEmpty() && (!label.equals(lastEvent) || now - lastEventTime >= 3000)) {
            lastEvent = label;
            lastEventTime = now;
            post(new Segment(sequence.incrementAndGet(), "[" + label + "]", "", false, true, false));
        }
        update(result.speech() ? "Parlato in ascolto" : "Suono ambientale · nessuna voce rilevata");
        return result.speech();
    }

    private void translatePending() {
        while (!closed) {
            try {
                Pending next = pending.take();
                String italian = engine.translate(next.transcript());
                post(new Segment(next.id(), next.transcript().text(), italian));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                update(e.getMessage());
            }
        }
    }

    private static boolean isItalian(DeviceLanguage.Transcript transcript) {
        return "it".equals(java.util.Locale.forLanguageTag(transcript.language()).getLanguage());
    }

    private void post(Segment s) { main.post(() -> { if (!closed) show.accept(s); }); }
    private void update(String s) { main.post(() -> { if (!closed) status.accept(s); }); }

    @Override public void close() {
        closed = true;
        queue.clear();
        pending.clear();
        worker.interrupt();
        if (translator != null) translator.interrupt();
        engine.close();
    }
}

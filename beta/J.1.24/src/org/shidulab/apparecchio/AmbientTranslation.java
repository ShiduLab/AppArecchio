package org.shidulab.apparecchio;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayDeque;
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
    private static final int LIVE_FRAME = 320;
    private static final int LEAD_IN_FRAMES = 12;
    private static final int PAUSE_FRAMES = 90;
    private final ArrayBlockingQueue<Frame> queue;
    private final ArrayBlockingQueue<short[]> eventFrames = new ArrayBlockingQueue<>(2);
    private final ArrayBlockingQueue<Pending> pending = new ArrayBlockingQueue<>(8);
    private final AtomicInteger sequence = new AtomicInteger();
    private final boolean live, translate;
    private final Context context;
    private short[] buffer;
    private short[] eventBuffer;
    private int eventPosition;
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
    private final Thread soundWorker;

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
        queue = new ArrayBlockingQueue<>(live ? 100 : 4);
        buffer = new short[live ? LIVE_FRAME : 48000];
        eventBuffer = live ? new short[SoundEvents.WINDOW] : null;
        engine = new DeviceLanguage(context, status);
        this.language = language;
        this.show = show;
        this.status = status;
        translator = live && translate ? new Thread(this::translatePending, "Traduzione dei sottotitoli") : null;
        if (translator != null) translator.start();
        soundWorker = live ? new Thread(this::classifySounds, "Eventi acustici") : null;
        if (soundWorker != null) soundWorker.start();
        worker = new Thread(live ? this::recognizeLive : this::translate, "Riconoscimento ambientale");
        worker.start();
    }

    void eventOptions(boolean enabled, float minimum) {
        events = enabled;
        threshold = minimum;
        if (!enabled) eventFrames.clear();
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
            if (eventBuffer != null && events) {
                eventBuffer[eventPosition++] = (short) value;
                if (eventPosition == eventBuffer.length) {
                    if (events && eventFrames.offer(eventBuffer)) eventBuffer = new short[SoundEvents.WINDOW];
                    eventPosition = 0;
                }
            } else if (eventPosition != 0) {
                eventPosition = 0;
            }
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
        var leadIn = new ArrayDeque<Frame>(LEAD_IN_FRAMES);
        try {
            while (!closed) {
                Frame first = queue.take();
                leadIn.addLast(first);
                if (!first.audible()) {
                    if (leadIn.size() == LEAD_IN_FRAMES) leadIn.removeFirst();
                    continue;
                }
                Frame[] start = leadIn.toArray(new Frame[0]);
                leadIn.clear();
                int id = sequence.incrementAndGet();
                try {
                    DeviceLanguage.Transcript result = engine.transcribe(language,
                        output -> streamSpeech(output, start),
                        interim -> post(new Segment(id, interim.text(), "", true, false, isItalian(interim))));
                    if (result.text().isBlank()) {
                        post(new Segment(id, "", ""));
                        update("Nessuna frase riconosciuta");
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
        } catch (Exception e) {
            update("Problema tecnico del riconoscitore Android.");
        }
    }

    private void streamSpeech(OutputStream output, Frame[] start)
            throws IOException, InterruptedException {
        byte[] pcm = new byte[LIVE_FRAME * 2];
        for (Frame frame : start) writeFrame(output, frame, pcm);
        int pauses = 0;
        for (int i = 0; i < 2200 && !closed && pauses < PAUSE_FRAMES; i++) {
            Frame next = queue.poll(2, TimeUnit.SECONDS);
            if (next == null) return;
            pauses = next.audible() ? 0 : pauses + 1;
            writeFrame(output, next, pcm);
        }
    }

    private void writeFrame(OutputStream output, Frame frame, byte[] pcm) throws IOException {
        for (int i = 0; i < frame.samples().length; i++) {
            short sample = frame.samples()[i];
            pcm[2 * i] = (byte)sample;
            pcm[2 * i + 1] = (byte)(sample >> 8);
        }
        output.write(pcm);
    }

    private void classifySounds() {
        SoundEvents model = null;
        try {
            while (!closed) {
                short[] samples = eventFrames.take();
                if (!events) continue;
                if (model == null) model = new SoundEvents(context);
                String label = model.detect(samples, threshold).event();
                long now = android.os.SystemClock.elapsedRealtime();
                if (events && !label.isEmpty() && (!label.equals(lastEvent) || now - lastEventTime >= 3000)) {
                    lastEvent = label;
                    lastEventTime = now;
                    post(new Segment(sequence.incrementAndGet(), "[" + label + "]", "", false, true, false));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError e) {
            update("Eventi sonori non disponibili. La trascrizione prosegue.");
        } finally {
            if (model != null) model.close();
        }
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
        eventFrames.clear();
        pending.clear();
        worker.interrupt();
        if (soundWorker != null) soundWorker.interrupt();
        if (translator != null) translator.interrupt();
        engine.close();
    }
}

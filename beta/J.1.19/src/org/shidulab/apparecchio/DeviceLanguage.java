package org.shidulab.apparecchio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.io.OutputStream;

final class DeviceLanguage implements AutoCloseable {
    record Transcript(String text, String language) {}

    private static final Map<String, String> TAGS = Map.of(
        "it", "it-IT", "en", "en-US", "ar", "ar-SA", "cmn", "zh-CN", "es", "es-ES");
    private static final Semaphore SPEECH = new Semaphore(1, true);
    private final Context context;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Translator> translators = new HashMap<>();
    private volatile boolean closed;

    DeviceLanguage(Context context, Consumer<String> status) {
        this.context = context.getApplicationContext();
        this.status = status;
    }

    static void check(Context context, String code) throws IOException {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw new IOException("Serve il riconoscimento vocale sul dispositivo con Android 13 o successivo.");
        }
        if (code.equals("auto") && Build.VERSION.SDK_INT < 34) {
            throw new IOException("Per il rilevamento automatico serve Android 14. Scegli una lingua.");
        }
    }

    Transcript transcribe(short[] samples, String code) throws Exception {
        return transcribe(code, output -> writePcm(output, samples), ignored -> {});
    }

    interface AudioSource { void write(OutputStream output) throws IOException, InterruptedException; }

    Transcript transcribe(String code, AudioSource source, Consumer<Transcript> interim) throws Exception {
        SPEECH.acquire();
        Recognition call = new Recognition(code, source, interim);
        try {
            if (closed) throw new InterruptedException();
            main.post(call::start);
            return call.result.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException error) throw error;
            throw new IOException("Riconoscimento vocale non disponibile.", e);
        } catch (TimeoutException e) {
            throw new IOException("Il riconoscimento vocale non ha risposto.", e);
        } finally {
            call.result.cancel(false);
            main.post(call::dispose);
            SPEECH.release();
        }
    }

    String translate(Transcript transcript) throws Exception {
        if (transcript.language().isBlank()) {
            throw new IOException("Lingua non rilevata. Scegli la lingua del parlato.");
        }
        String language = TranslateLanguage.fromLanguageTag(transcript.language());
        if (language == null) throw new IOException("Lingua non supportata.");
        if (TranslateLanguage.ITALIAN.equals(language)) return transcript.text();
        Translator translator;
        synchronized (translators) {
            if (closed) throw new InterruptedException();
            translator = translators.computeIfAbsent(language, source -> Translation.getClient(
                new TranslatorOptions.Builder().setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.ITALIAN).build()));
        }
        report("Preparazione dei modelli di traduzione");
        try {
            Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()),
                2, TimeUnit.MINUTES);
            return Tasks.await(translator.translate(transcript.text()), 12, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Modelli di traduzione non disponibili. Controlla la connessione al primo uso.", e);
        }
    }

    private void report(String message) {
        main.post(() -> {
            if (!closed) status.accept(message);
        });
    }

    @Override public void close() {
        closed = true;
        synchronized (translators) {
            for (Translator translator : translators.values()) translator.close();
            translators.clear();
        }
    }

    private final class Recognition implements RecognitionListener {
        private final CompletableFuture<Transcript> result = new CompletableFuture<>();
        private final List<String> pieces = new ArrayList<>();
        private final String code;
        private final AudioSource source;
        private final Consumer<Transcript> interim;
        private String language;
        private SpeechRecognizer recognizer;
        private ParcelFileDescriptor reader;
        private ParcelFileDescriptor writer;
        private Thread audioThread;

        Recognition(String code, AudioSource source, Consumer<Transcript> interim) {
            this.code = code;
            this.source = source;
            this.interim = interim;
            language = TAGS.getOrDefault(code, "");
        }

        void start() {
            if (closed || result.isDone()) return;
            if (Build.VERSION.SDK_INT < 33) {
                result.completeExceptionally(new IOException("Serve Android 13 o successivo."));
                return;
            }
            try {
                check(context, code);
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                reader = pipe[0];
                writer = pipe[1];
                Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, reader)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                if (code.equals("auto") && Build.VERSION.SDK_INT >= 34) {
                    var allowed = new ArrayList<>(TAGS.values());
                    request.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                        .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
                        .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed);
                } else {
                    request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
                }
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
                recognizer.startListening(request);
                ParcelFileDescriptor output = writer;
                audioThread = new Thread(() -> writeAudio(output), "Audio del riconoscimento");
                audioThread.start();
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }

        private void writeAudio(ParcelFileDescriptor output) {
            try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                source.write(stream);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (!result.isDone()) result.completeExceptionally(
                    new IOException("Il riconoscitore Android non ha accettato l’audio.", e));
            }
        }

        private String phrase(Bundle data) {
            var values = data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return values == null || values.isEmpty() ? "" : values.get(0).trim();
        }

        private void collect(Bundle data) {
            String phrase = phrase(data);
            if (!phrase.isEmpty() && (pieces.isEmpty() || !pieces.get(pieces.size() - 1).equals(phrase))) {
                pieces.add(phrase);
            }
        }

        private void finish() {
            result.complete(new Transcript(String.join(" ", pieces), language));
        }

        void dispose() {
            if (audioThread != null) audioThread.interrupt();
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
            }
            closePipe(reader);
            closePipe(writer);
        }

        private void closePipe(ParcelFileDescriptor pipe) {
            if (pipe == null) return;
            try { pipe.close(); } catch (IOException ignored) { }
        }

        @Override public void onLanguageDetection(Bundle details) {
            if (Build.VERSION.SDK_INT < 34) return;
            String detected = details.getString(SpeechRecognizer.DETECTED_LANGUAGE);
            if (code.equals("auto") && detected != null) language = detected;
        }
        @Override public void onSegmentResults(Bundle results) {
            if (result.isDone()) return;
            collect(results);
            interim.accept(new Transcript(String.join(" ", pieces), language));
        }
        @Override public void onEndOfSegmentedSession() { finish(); }
        @Override public void onResults(Bundle results) {
            if (pieces.isEmpty()) collect(results);
            finish();
        }
        @Override public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                finish();
            } else if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                prepareSpeechModels();
                result.completeExceptionally(new IOException("Preparazione della lingua di riconoscimento."));
            } else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                result.completeExceptionally(new IOException("Lingua non supportata dal riconoscitore Android."));
            } else {
                result.completeExceptionally(new IOException("Problema tecnico del riconoscitore Android (" + error + ")."));
            }
        }

        private void prepareSpeechModels() {
            if (Build.VERSION.SDK_INT < 33) return;
            var request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            try {
                for (String tag : code.equals("auto") ? TAGS.values() : List.of(language)) {
                    recognizer.triggerModelDownload(new Intent(request)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag));
                }
            } catch (RuntimeException ignored) { }
        }

        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rms) {}
        @Override public void onBufferReceived(byte[] data) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle results) {
            if (result.isDone()) return;
            String partial = phrase(results);
            if (partial.isEmpty()) return;
            String confirmed = String.join(" ", pieces);
            interim.accept(new Transcript(confirmed.isEmpty() ? partial : confirmed + " " + partial, language));
        }
        @Override public void onEvent(int event, Bundle params) {}
    }

    static void writePcm(OutputStream output, short[] samples) throws IOException {
        var pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        pcm.asShortBuffer().put(samples);
        output.write(pcm.array());
    }
}

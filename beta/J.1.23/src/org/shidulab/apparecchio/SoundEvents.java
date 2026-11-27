package org.shidulab.apparecchio;

import android.content.Context;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.tensorflow.lite.support.audio.TensorAudio;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;

final class SoundEvents implements AutoCloseable {
    record Detection(boolean speech, String event) {}

    static final int WINDOW = 15600;
    private static final Set<String> VOICE = Set.of("Speech", "Conversation",
        "Narration, monologue", "Child speech, kid speaking", "Male speech, man speaking",
        "Female speech, woman speaking");
    private static final Map<String, String> LABELS = Map.of("Bark", "Cane che abbaia",
        "Doorbell", "Campanello", "Siren", "Sirena", "Knock", "Bussano alla porta",
        "Shatter", "Oggetto che si frantuma");
    private final AudioClassifier classifier;
    private final TensorAudio input;
    private boolean closed;

    SoundEvents(Context context) throws IOException {
        classifier = AudioClassifier.createFromFile(context, "yamnet.tflite");
        input = classifier.createInputTensorAudio();
    }

    synchronized Detection detect(short[] samples, float threshold) {
        if (closed) return new Detection(false, "");
        input.load(samples);
        float speech = 0, best = threshold;
        String event = "";
        for (var head : classifier.classify(input)) {
            for (var category : head.getCategories()) {
                String label = category.getLabel();
                float score = category.getScore();
                if (VOICE.contains(label)) speech = Math.max(speech, score);
                if (LABELS.containsKey(label) && score >= best) {
                    best = score;
                    event = LABELS.get(label);
                }
            }
        }
        return new Detection(speech >= .22f && (event.isEmpty() || speech >= best * .6f), event);
    }

    @Override public synchronized void close() {
        if (!closed) classifier.close();
        closed = true;
    }
}

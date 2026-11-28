package org.shidulab.apparecchio;

final class AudioMix {
    private static final class Clip {
        final short[] pcm;
        int position;
        final Runnable completed;
        Clip(short[] pcm) { this(pcm, null); }
        Clip(short[] pcm, Runnable completed) { this.pcm = pcm; this.completed = completed; }
    }

    private volatile Clip voice, note;
    private Clip remote;
    private final java.util.concurrent.ArrayBlockingQueue<Clip> remoteQueue = new java.util.concurrent.ArrayBlockingQueue<>(5);
    private volatile float remoteLevel = .4f;
    void remoteVolume(float value) { remoteLevel = value; }
    void playRemote(short[] pcm) { if (!remoteQueue.offer(new Clip(pcm))) { remoteQueue.poll(); remoteQueue.offer(new Clip(pcm)); } }
    void clearRemote() { remoteQueue.clear(); remote = null; }
    void playNote(short[] pcm) { note = new Clip(pcm); }
    void playNote(short[] pcm, Runnable completed) { note = new Clip(pcm, completed); }
    private volatile float ambient = 1, translated = .35f;
    void volume(float ambient, float translated) {
        this.ambient = ambient;
        this.translated = translated;
    }
    void clear() { voice = null; }
    void play(short[] pcm) { voice = new Clip(pcm); }

    void apply(short[] block, int count) {
        Clip clip = voice, draft = note;
        float amb = ambient, speech = translated;
        if ((clip == null || clip.position >= clip.pcm.length) && (draft == null || draft.position >= draft.pcm.length) && remote == null && remoteQueue.isEmpty() && amb == 1) return;
        for (int i = 0; i < count; i++) {
            int synthetic = clip == null || clip.position >= clip.pcm.length ? 0 : clip.pcm[clip.position++];
            int recording = draft == null || draft.position >= draft.pcm.length ? 0 : draft.pcm[draft.position++];
            Clip incoming = remote;
            if (incoming == null || incoming.position >= incoming.pcm.length) remote = incoming = remoteQueue.poll();
            int spoken = incoming == null ? 0 : incoming.pcm[incoming.position++];
            if (draft != null && draft.position == draft.pcm.length && draft.completed != null) {
                draft.position++;
                draft.completed.run();
            }
            int sample = Math.round(block[i] * amb + synthetic * speech + spoken * remoteLevel + recording * .65f);
            block[i] = (short) Math.max(-16384, Math.min(16384, sample));
        }
    }
}

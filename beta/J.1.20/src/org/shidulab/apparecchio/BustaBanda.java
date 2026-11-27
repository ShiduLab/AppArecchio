package org.shidulab.apparecchio;

final class BustaBanda {
    static final int EXCLUDED = -13;
    static final int MAX_BAND_GAIN = 12;
    static final int[] FREQUENCIES = {31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final int LEGACY_OFFSET = 3;
    static final int DEFAULT_Q = 140;
    private final EqualizerBand[] bands = new EqualizerBand[FREQUENCIES.length];
    private final int rate;
    private final double limiterRelease;
    private double targetGain, gain, limiter = 1, gate = 1, envelope, threshold;
    private final double smoothing;
    private final double lowCutStep, highCutStep, impulseRelease;
    private boolean voice, dynamics, impulse;
    private double low, voiceBand, voiceMix, dynamicsGain = 1, impulseGain = 1;
    private int impulseHold;

    BustaBanda(int rate) {
        this.rate = rate;
        limiterRelease = 1 - Math.exp(-1.0 / (rate * .080));
        smoothing = 1 - Math.exp(-1.0 / (rate * .020));
        lowCutStep = 1 - Math.exp(-2 * Math.PI * 300 / rate);
        highCutStep = 1 - Math.exp(-2 * Math.PI * 3400 / rate);
        impulseRelease = 1 - Math.exp(-1.0 / (rate * .120));
        for (int i = 0; i < bands.length; i++) bands[i] = new EqualizerBand();
    }

    void configure(int[] eq, int gainDb, int noise) {
        configure(eq, gainDb, noise, false, false, false);
    }

    void configure(int[] eq, int gainDb, int noise, boolean voice, boolean dynamics, boolean impulse) {
        configure(eq, null, null, gainDb, noise, voice, dynamics, impulse);
    }

    void configure(int[] eq, int[] frequencies, int[] widths, int gainDb, int noise,
                   boolean voice, boolean dynamics, boolean impulse) {
        this.voice = voice;
        this.dynamics = dynamics;
        this.impulse = impulse;
        if (!impulse) impulseHold = 0;
        for (int i = 0; i < bands.length; i++) {
            int source = eq.length == 5 ? i - LEGACY_OFFSET : i;
            int db = source >= 0 && source < eq.length ? eq[source] : 0;
            int frequency = frequencies == null ? FREQUENCIES[i] : frequencies[i];
            double q = widths == null ? DEFAULT_Q / 100.0 : widths[i] / 100.0;
            bands[i].configure(2 * Math.PI * Math.min(frequency, rate * .45) / rate, db, q);
        }
        targetGain = Math.pow(10, gainDb / 20.0);
        threshold = noise == 0 ? 0 : Math.pow(10, (-60 + 10 * noise) / 20.0);
    }

    boolean process(short[] samples, int count) {
        boolean limiting = false;
        for (int i = 0; i < count; i++) {
            double input = samples[i] / 32768.0;
            envelope = Math.max(Math.abs(input), envelope * .999);
            double gateTarget = threshold == 0 ? 1
                    : Math.min(1, Math.max(.18, envelope / threshold * .4));
            gate += (gateTarget - gate) * smoothing;
            gain += (targetGain - gain) * smoothing;
            double sample = input * gate;
            low += (sample - low) * lowCutStep;
            voiceBand += (sample - low - voiceBand) * highCutStep;
            voiceMix += ((voice ? 1 : 0) - voiceMix) * smoothing;
            sample += (voiceBand - sample) * voiceMix;
            for (EqualizerBand band : bands) sample = band.filter(sample, smoothing);
            double compressionTarget = dynamics
                    ? 2 / Math.pow(Math.max(1, envelope / .125), 2.0 / 3) : 1;
            dynamicsGain += (compressionTarget - dynamicsGain)
                    * (compressionTarget < dynamicsGain ? .05 : smoothing);
            if (impulse && Math.abs(input) >= .65) impulseHold = rate * 60 / 1000;
            if (impulseHold > 0) {
                impulseGain = .125;
                impulseHold--;
            } else {
                impulseGain += (1 - impulseGain) * impulseRelease;
            }
            sample *= gain * dynamicsGain * impulseGain;
            double required = .5 / Math.max(.5, Math.abs(sample));
            limiting |= required < 1;
            limiter = Math.min(required, limiter + (1 - limiter) * limiterRelease);
            samples[i] = (short) Math.round(sample * limiter * 32768.0);
        }
        return limiting;
    }

    private static final class EqualizerBand {
        private final Band peak = new Band();
        private final Band reject = new Band();
        private boolean excluding;
        private boolean retiringReject;

        void configure(double omega, int db, double q) {
            boolean exclude = db == EXCLUDED;
            peak.configure(omega, exclude ? 0 : Math.min(db, MAX_BAND_GAIN), q);
            double notchQ = Math.max(4, Math.min(18, q * 4));
            if (exclude) {
                reject.configure(omega, EXCLUDED, notchQ);
                retiringReject = false;
            } else if (excluding) {
                reject.configure(omega, 0, notchQ);
                retiringReject = true;
            }
            excluding = exclude;
        }

        double filter(double input, double step) {
            double output = peak.filter(input, step);
            if (excluding || retiringReject) {
                output = reject.filter(output, step);
                if (retiringReject && reject.isUnity()) retiringReject = false;
            }
            return output;
        }
    }

    private static final class Band {
        private double b0 = 1, b2, a2, shared;
        private double targetB0 = 1, targetB2, targetA2;
        private double x1, x2, y1, y2;

        void configure(double omega, int db, double q) {
            boolean excluded = db == EXCLUDED;
            double amplitude = excluded ? 1 : Math.pow(10, db / 40.0);
            double alpha = Math.sin(omega) / (2 * q);
            double boost = excluded ? 0 : alpha * amplitude;
            double a0 = 1 + alpha / amplitude;
            targetB0 = (1 + boost) / a0;
            shared = -2 * Math.cos(omega) / a0;
            targetB2 = (1 - boost) / a0;
            targetA2 = (1 - alpha / amplitude) / a0;
            // Initialize the poles before processing; subsequent changes interpolate
            // between stable coefficient sets.
            if (!initialized) {
                b0 = targetB0;
                b2 = targetB2;
                a2 = targetA2;
                feedback = shared;
                initialized = true;
            }
        }

        private boolean initialized;
        private double feedback;

        boolean isUnity() {
            return Math.abs(b0 - 1) + Math.abs(b2 - a2) < 1e-8;
        }

        double filter(double input, double step) {
            b0 += (targetB0 - b0) * step;
            feedback += (shared - feedback) * step;
            b2 += (targetB2 - b2) * step;
            a2 += (targetA2 - a2) * step;
            double output = b0 * input + feedback * (x1 - y1) + b2 * x2 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;
            return output;
        }
    }
}

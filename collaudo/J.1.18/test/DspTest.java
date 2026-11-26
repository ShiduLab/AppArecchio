package org.shidulab.apparecchio;

public final class DspTest {
    private static double rms(int frequency, int[] eq, int gain, int noise, int level) {
        return rms(frequency, eq, gain, noise, level, null, null);
    }

    private static double rms(int frequency, int[] eq, int gain, int noise, int level,
                              int[] frequencies, int[] widths) {
        BustaBanda dsp = new BustaBanda(48000);
        dsp.configure(eq, frequencies, widths, gain, noise, false, false, false);
        short[] block = new short[240];
        double sum = 0;
        for (int b = 0; b < 900; b++) {
            for (int i = 0; i < block.length; i++)
                block[i] = (short) (level * Math.sin(2 * Math.PI * frequency * (b * 240 + i) / 48000));
            dsp.process(block, block.length);
            if (b < 800) continue;
            for (short sample : block) {
                if (Math.abs(sample) > 16384) throw new AssertionError("Peak ceiling");
                sum += (double) sample * sample;
            }
        }
        return Math.sqrt(sum / 24000);
    }

    public static void main(String[] args) {
        int[] flat = new int[5];
        int[] boost = {0, 0, 6, 0, 0};
        double reference = rms(1000, flat, 0, 0, 500);
        if (Math.abs(reference - 500 / Math.sqrt(2)) > 2) throw new AssertionError("Unity");
        if (rms(1000, boost, 0, 0, 500) < reference * 1.9) throw new AssertionError("EQ");
        if (rms(1000, flat, 6, 0, 500) < reference * 1.9) throw new AssertionError("Gain");
        if (rms(1000, flat, 0, 3, 500) > reference * .5) throw new AssertionError("Noise gate");
        if (rms(1000, boost, 12, 0, 32000) < 1000) throw new AssertionError("Limiter output");
        for (int band = 0; band < BustaBanda.FREQUENCIES.length; band++) {
            int[] cut = new int[BustaBanda.FREQUENCIES.length];
            cut[band] = BustaBanda.EXCLUDED;
            int center = BustaBanda.FREQUENCIES[band];
            if (rms(center, cut, 0, 0, 500) > 1) throw new AssertionError("Band exclusion " + center);
            for (double ratio : new double[]{.85, 1.15}) {
                double side = rms((int) Math.round(center * ratio), cut, 0, 0, 500);
                if (side > reference * .003) throw new AssertionError("Rejected range " + center + ": " + side);
            }
            int distant = center < 1000 ? center * 10 : center / 10;
            if (rms(distant, cut, 0, 0, 500) < reference * .65)
                throw new AssertionError("Distant tone " + center);
            int[] max = new int[BustaBanda.FREQUENCIES.length];
            max[band] = BustaBanda.MAX_BAND_GAIN;
            double boostRatio = rms(center, max, 0, 0, 500) / reference;
            if (Math.abs(20 * Math.log10(boostRatio) - 12) > .2)
                throw new AssertionError("Maximum gain " + center + ": " + boostRatio);
        }
        int[] allBoost = new int[BustaBanda.FREQUENCIES.length];
        java.util.Arrays.fill(allBoost, BustaBanda.MAX_BAND_GAIN);
        rms(1000, allBoost, 12, 0, 32760);
        int[] frequencies = BustaBanda.FREQUENCIES.clone();
        int[] widths = new int[frequencies.length];
        java.util.Arrays.fill(widths, BustaBanda.DEFAULT_Q);
        int[] cut = new int[frequencies.length];
        frequencies[3] = 320;
        cut[3] = -12;
        double center = rms(320, cut, 0, 0, 500, frequencies, widths);
        widths[3] = 800;
        if (center > reference * .27 || rms(400, cut, 0, 0, 500, frequencies, widths) < reference * .85)
            throw new AssertionError("Adjustable frequency and Q");
        System.out.println("OK: unity, EQ, gain, noise gate, peak ceiling");
    }
}

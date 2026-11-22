package org.shidulab.apparecchio;

public final class DspTest {
    private static double rms(int frequency, int[] eq, int gain, int noise, int level) {
        BustaBanda dsp = new BustaBanda(48000);
        dsp.configure(eq, gain, noise);
        short[] block = new short[240];
        double sum = 0;
        for (int b = 0; b < 200; b++) {
            for (int i = 0; i < block.length; i++)
                block[i] = (short) (level * Math.sin(2 * Math.PI * frequency * (b * 240 + i) / 48000));
            dsp.process(block, block.length);
            if (b < 100) continue;
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
        System.out.println("OK: unity, EQ, gain, noise gate, peak ceiling");
    }
}

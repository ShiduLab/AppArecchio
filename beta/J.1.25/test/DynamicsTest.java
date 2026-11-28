package org.shidulab.apparecchio;

public final class DynamicsTest {
    public static void main(String[] args) {
        BustaBanda dsp = new BustaBanda(48000);
        dsp.configure(new int[5], 0, 0, false, false, true);
        short[] loud = new short[4800];
        dsp.process(loud, loud.length);
        loud[0] = 30000;
        loud[1] = 10000;
        dsp.process(loud, 2);
        if (Math.abs(loud[0]) > 5000 || Math.abs(loud[1]) > 2000)
            throw new AssertionError("Impulse and hold");
        java.util.Arrays.fill(loud, (short) 500);
        for (int i = 0; i < 8; i++) dsp.process(loud.clone(), loud.length);
        dsp.process(loud, loud.length);
        if (Math.abs(loud[100] - 500) > 15) throw new AssertionError("Recovery");
        if (level(1000, false, true) < level(1000, false, false) * 1.7)
            throw new AssertionError("Quiet sound lift");
        if (level(80, true, false) > level(80, false, false) * .4)
            throw new AssertionError("Voice filter");
        System.out.println("OK: impulse, hold, recovery, quiet lift, voice filter");
    }

    private static double level(int frequency, boolean voice, boolean dynamics) {
        BustaBanda dsp = new BustaBanda(48000);
        dsp.configure(new int[5], 0, 0, voice, dynamics, false);
        short[] block = new short[48000];
        for (int i = 0; i < block.length; i++)
            block[i] = (short)(500 * Math.sin(2 * Math.PI * frequency * i / 48000));
        dsp.process(block, block.length);
        double sum = 0;
        for (int i = 24000; i < block.length; i++) sum += Math.abs((int) block[i]);
        return sum / 24000;
    }
}

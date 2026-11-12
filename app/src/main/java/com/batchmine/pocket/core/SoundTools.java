package com.batchmine.pocket.core;

/** The sound of a card made a little nicer: the silence at its ends cut off, the loudness evened out, soft fades at both ends. Mono 16-bit samples. */
public final class SoundTools {
    private SoundTools() { }

    public static final class Opts {
        public boolean normalise, trim;
        public int fadeMs;                 // 0 = no fade
        public int keepMs = 80;            // silence left at each end when trimming
        public boolean any() { return normalise || trim || fadeMs > 0; }
    }

    public static short[] process(short[] s, int sr, Opts o) {
        if (s.length == 0 || !o.any()) return s;
        short[] r = s;
        if (o.trim) r = trim(r, sr, o.keepMs);
        if (o.normalise) r = normalise(r);
        if (o.fadeMs > 0) r = fade(r, sr, o.fadeMs);
        return r;
    }

    /** Drops the quiet stretch at the start and the end (quiet = below 4 % of the loudest 10 ms window). */
    public static short[] trim(short[] s, int sr, int keepMs) {
        int w = Math.max(1, sr / 100), n = s.length / w;
        if (n < 3) return s;
        double[] rms = new double[n];
        double top = 0;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int k = 0; k < w; k++) { double v = s[i * w + k]; sum += v * v; }
            rms[i] = Math.sqrt(sum / w);
            top = Math.max(top, rms[i]);
        }
        if (top < 50) return s;                                  // all quiet: nothing to find
        double th = Math.max(40, top * 0.04);
        int a = 0, b = n - 1;
        while (a < n && rms[a] < th) a++;
        while (b > a && rms[b] < th) b--;
        int keep = keepMs * sr / 1000;
        int from = Math.max(0, a * w - keep), to = Math.min(s.length, (b + 1) * w + keep);
        if (to - from < sr / 5) return s;
        return java.util.Arrays.copyOfRange(s, from, to);
    }

    /** Scales so the loudest sample reaches about 90 % (at most 6× louder: a quiet line does not become hiss). */
    public static short[] normalise(short[] s) {
        int peak = 1;
        for (short v : s) peak = Math.max(peak, Math.abs((int) v));
        double g = Math.min(6.0, 0.9 * 32767 / peak);
        if (Math.abs(g - 1) < 0.02) return s;
        short[] r = new short[s.length];
        for (int i = 0; i < s.length; i++) r[i] = (short) Math.max(-32768, Math.min(32767, Math.round(s[i] * g)));
        return r;
    }

    public static short[] fade(short[] s, int sr, int ms) {
        int f = Math.min(s.length / 2, ms * sr / 1000);
        if (f < 2) return s;
        short[] r = s.clone();
        for (int i = 0; i < f; i++) {
            double g = i / (double) f;
            r[i] = (short) Math.round(r[i] * g);
            r[r.length - 1 - i] = (short) Math.round(r[r.length - 1 - i] * g);
        }
        return r;
    }

    /** A mono 16-bit .wav file. */
    public static byte[] wav(short[] s, int sr) {
        int len = s.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + len).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + len).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(len);
        for (short v : s) b.putShort(v);
        return b.array();
    }

    /** The samples of a mono 16-bit .wav as written by wav(); the sample rate is returned in RATE[0]. Null if it is something else. */
    public static short[] readWav(byte[] d, int[] rate) {
        if (d.length < 44 || d[0] != 'R' || d[8] != 'W') return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(22) != 1 || b.getShort(34) != 16) return null;
        rate[0] = b.getInt(24);
        int len = Math.min(b.getInt(40), d.length - 44) / 2;
        short[] s = new short[len];
        for (int i = 0; i < len; i++) s[i] = b.getShort(44 + i * 2);
        return s;
    }
}

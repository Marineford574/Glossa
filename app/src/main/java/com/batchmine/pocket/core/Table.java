package com.batchmine.pocket.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A sorted key -> value table on disk: NAME.dat holds the records ([int keyLen][key][int valLen][value], UTF-8) sorted by
 * key, NAME.idx the offset of every record (8 bytes each). A lookup is a binary search, so even 700 000 inflected forms
 * open instantly and use almost no memory. A key may have several values (consecutive records).
 * The Builder sorts in chunks written to temporary files and merges them, so building needs little memory too.
 */
public final class Table implements Closeable {
    private final RandomAccessFile raf;
    private final FileChannel ch;
    private final long[] offsets;

    public Table(File dir, String name) throws IOException {
        File idx = new File(dir, name + ".idx");
        long n = idx.length() / 8;
        offsets = new long[(int) n];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(idx), 1 << 16))) {
            for (int i = 0; i < n; i++) offsets[i] = in.readLong();
        }
        raf = new RandomAccessFile(new File(dir, name + ".dat"), "r");
        ch = raf.getChannel();
    }

    public static boolean exists(File dir, String name) { return new File(dir, name + ".idx").isFile() && new File(dir, name + ".dat").isFile(); }

    public int size() { return offsets.length; }

    /** All values stored under exactly this key (in the order they were added). */
    public synchronized List<String> get(String key) throws IOException {
        List<String> out = new ArrayList<>();
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = offsets.length;          // lower bound: first record whose key >= k
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(readKey(offsets[mid]), k) < 0) lo = mid + 1; else hi = mid;
        }
        for (int i = lo; i < offsets.length; i++) {
            byte[][] kv = readRecord(offsets[i]);
            if (compare(kv[0], k) != 0) break;
            out.add(new String(kv[1], StandardCharsets.UTF_8));
            if (out.size() > 200) break;
        }
        return out;
    }

    /** Keys that start with PREFIX (for search as you type), at most LIMIT. */
    public synchronized List<String> keysWithPrefix(String prefix, int limit) throws IOException {
        List<String> out = new ArrayList<>();
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = offsets.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(readKey(offsets[mid]), p) < 0) lo = mid + 1; else hi = mid;
        }
        String last = null;
        for (int i = lo; i < offsets.length && out.size() < limit; i++) {
            byte[] k = readKey(offsets[i]);
            if (k.length < p.length || compare(Arrays.copyOf(k, p.length), p) != 0) break;
            String s = new String(k, StandardCharsets.UTF_8);
            if (!s.equals(last)) out.add(s);
            last = s;
        }
        return out;
    }

    private byte[] readKey(long off) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4);
        ch.read(b, off);
        b.flip();
        int kl = b.getInt();
        ByteBuffer kb = ByteBuffer.allocate(kl);
        ch.read(kb, off + 4);
        return kb.array();
    }

    private byte[][] readRecord(long off) throws IOException {
        byte[] k = readKey(off);
        ByteBuffer b = ByteBuffer.allocate(4);
        ch.read(b, off + 4 + k.length);
        b.flip();
        int vl = b.getInt();
        ByteBuffer vb = ByteBuffer.allocate(vl);
        ch.read(vb, off + 8 + k.length);
        return new byte[][]{k, vb.array()};
    }

    static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xff, y = b[i] & 0xff;
            if (x != y) return x - y;
        }
        return a.length - b.length;
    }

    @Override public void close() throws IOException { raf.close(); }

    // ================================================================ building
    public static final class Builder implements Closeable {
        private static final int CHUNK = 60000;
        private final File dir;
        private final String name;
        private final List<Rec> buf = new ArrayList<>();
        private final List<File> chunks = new ArrayList<>();
        private long seq = 0;
        private int count = 0;

        public Builder(File dir, String name) { this.dir = dir; this.name = name; dir.mkdirs(); }

        private static final class Rec {
            final byte[] k, v; final long seq;
            Rec(byte[] k, byte[] v, long seq) { this.k = k; this.v = v; this.seq = seq; }
        }

        private static final Comparator<Rec> ORDER = (a, b) -> {
            int c = compare(a.k, b.k);
            return c != 0 ? c : Long.compare(a.seq, b.seq);
        };

        public void add(String key, String value) throws IOException {
            buf.add(new Rec(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8), seq++));
            count++;
            if (buf.size() >= CHUNK) spill();
        }

        public int count() { return count; }

        private void spill() throws IOException {
            if (buf.isEmpty()) return;
            buf.sort(ORDER);
            File f = new File(dir, name + ".chunk" + chunks.size());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
                for (Rec r : buf) { out.writeLong(r.seq); out.writeInt(r.k.length); out.write(r.k); out.writeInt(r.v.length); out.write(r.v); }
            }
            chunks.add(f);
            buf.clear();
        }

        private static final class Cursor {
            final DataInputStream in; Rec cur;
            Cursor(File f) throws IOException { in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16)); advance(); }
            void advance() throws IOException {
                try {
                    long s = in.readLong();
                    byte[] k = new byte[in.readInt()]; in.readFully(k);
                    byte[] v = new byte[in.readInt()]; in.readFully(v);
                    cur = new Rec(k, v, s);
                } catch (EOFException e) { cur = null; in.close(); }
            }
        }

        /** Merges the chunks into NAME.dat / NAME.idx (written under temporary names first, then renamed). */
        public void finish() throws IOException {
            spill();
            File dat = new File(dir, name + ".dat.tmp"), idx = new File(dir, name + ".idx.tmp");
            PriorityQueue<Cursor> pq = new PriorityQueue<>((a, b) -> ORDER.compare(a.cur, b.cur));
            for (File f : chunks) { Cursor c = new Cursor(f); if (c.cur != null) pq.add(c); }
            try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dat), 1 << 16));
                 DataOutputStream x = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(idx), 1 << 16))) {
                long off = 0;
                while (!pq.isEmpty()) {
                    Cursor c = pq.poll();
                    Rec r = c.cur;
                    x.writeLong(off);
                    d.writeInt(r.k.length); d.write(r.k); d.writeInt(r.v.length); d.write(r.v);
                    off += 8 + r.k.length + r.v.length;
                    c.advance();
                    if (c.cur != null) pq.add(c);
                }
            }
            for (File f : chunks) //noinspection ResultOfMethodCallIgnored
                f.delete();
            chunks.clear();
            File fd = new File(dir, name + ".dat"), fi = new File(dir, name + ".idx");
            //noinspection ResultOfMethodCallIgnored
            fd.delete();
            //noinspection ResultOfMethodCallIgnored
            fi.delete();
            if (!dat.renameTo(fd) || !idx.renameTo(fi)) throw new IOException("could not write " + fd);
        }

        @Override public void close() {
            for (File f : chunks) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }
}

package com.batchmine.pocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * The card's picture and sound, taken from the video on the phone with Android's own tools (no ffmpeg): a frame at a
 * time, and the sound of a line copied out of the file as it is (no re-encoding, so it is quick). Sound copying works for
 * AAC (most MP4 / MKV files: .m4a) and Opus / Vorbis (.webm); other sound formats leave the card to the PC.
 */
public final class MediaCut {
    private MediaCut() { }

    public static File frame(Context c, Uri video, double t, File out) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, video);
            long us = (long) (Math.max(0, t) * 1_000_000);
            Bitmap b = null;
            if (Build.VERSION.SDK_INT >= 27) {
                String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                        h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                int vw = w == null ? 0 : Integer.parseInt(w), vh = h == null ? 0 : Integer.parseInt(h);
                if (vw > 0 && vh > 0) {
                    float s = Math.min(1f, 640f / vw);
                    b = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, Math.round(vw * s), Math.round(vh * s));
                }
            }
            if (b == null) b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
            if (b == null) throw new IOException("no picture at " + Ui.time(t));
            if (b.getWidth() > 640) b = Bitmap.createScaledBitmap(b, 640, Math.round(b.getHeight() * 640f / b.getWidth()), true);
            try (OutputStream o = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.JPEG, 82, o); }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("could not take the picture: " + e.getMessage());
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** The sound from START to END seconds into DIR/BASE.m4a or .webm. */
    public static File audio(Context c, Uri video, double start, double end, File dir, String base) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        File out = null;
        boolean ok = false;
        try {
            ex.setDataSource(c, video, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("the video has no sound");
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int kind;
            String ext;
            if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) { kind = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4; ext = ".m4a"; }
            else if (MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime) || MediaFormat.MIMETYPE_AUDIO_VORBIS.equals(mime)) {
                kind = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM; ext = ".webm";
            } else {
                // MP3, FLAC, AC-3 …: the phone decodes the few seconds and keeps them as a plain .wav (if it has a decoder for them)
                ex.selectTrack(track);
                return toWav(ex, fmt, start, end, new File(dir, base + ".wav"));
            }
            ex.selectTrack(track);
            out = new File(dir, base + ext);
            mux = new MediaMuxer(out.getPath(), kind);
            int outTrack = mux.addTrack(fmt);
            mux.start();
            long a = (long) (Math.max(0, start) * 1_000_000), b = (long) (end * 1_000_000);
            ex.seekTo(a, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int cap = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 65536) : 1 << 18;
            ByteBuffer buf = ByteBuffer.allocate(cap);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int n = 0;
            while (true) {
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long t = ex.getSampleTime();
                if (t > b) break;
                if (t >= a) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = t - a;
                    info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mux.writeSampleData(outTrack, buf, info);
                    n++;
                }
                if (!ex.advance()) break;
            }
            if (n == 0) throw new IOException("no sound found between " + Ui.time(start) + " and " + Ui.time(end));
            mux.stop();
            ok = true;
            return out;
        } catch (RuntimeException e) {
            throw new IOException("could not cut the sound: " + e.getMessage());
        } finally {
            try { if (mux != null) mux.release(); } catch (RuntimeException ignored) { }
            ex.release();
            if (!ok && out != null) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }

    /** Decodes the sound from START to END (the extractor is on the audio track) and writes it as a mono 16-bit .wav. */
    private static File toWav(MediaExtractor ex, MediaFormat fmt, double start, double end, File out) throws IOException {
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec dec = null;
        boolean ok = false;
        try {
            try { dec = MediaCodec.createDecoderByType(mime); dec.configure(fmt, null, null, 0); dec.start(); }
            catch (Exception e) { throw new IOException("the video's sound is " + mime.replace("audio/", "") + ", which this phone cannot play back to cut: the PC makes this card"); }
            long a = (long) (Math.max(0, start) * 1_000_000), b = (long) (end * 1_000_000);
            ex.seekTo(a, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int enc = 2;                                                        // AudioFormat.ENCODING_PCM_16BIT
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            long deadline = System.currentTimeMillis() + 20000;
            while (!outDone) {
                if (System.currentTimeMillis() > deadline) throw new IOException("decoding the sound took too long");
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int size = ex.readSampleData(ib, 0);
                        long t = ex.getSampleTime();
                        if (size < 0 || t > b + 200_000) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ii, 0, size, t, 0); ex.advance(); }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    enc = of.containsKey("pcm-encoding") ? of.getInteger("pcm-encoding") : 2;
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    int bytesPer = enc == 4 ? 4 : 2, frames = info.size / (bytesPer * Math.max(1, ch));
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    ob.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (int f = 0; f < frames; f++) {
                        long ft = info.presentationTimeUs + (long) f * 1_000_000L / sr;
                        double sum = 0;
                        for (int c = 0; c < ch; c++) sum += enc == 4 ? ob.getFloat() * 32767.0 : ob.getShort();
                        if (ft < a || ft > b) continue;
                        int v = (int) Math.max(-32768, Math.min(32767, Math.round(sum / Math.max(1, ch))));
                        pcm.write(v & 0xff);
                        pcm.write((v >> 8) & 0xff);
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            byte[] data = pcm.toByteArray();
            if (data.length < sr / 10) throw new IOException("no sound found between " + Ui.time(start) + " and " + Ui.time(end));
            try (java.io.DataOutputStream w = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
                w.writeBytes("RIFF"); w.writeInt(Integer.reverseBytes(36 + data.length)); w.writeBytes("WAVEfmt ");
                w.writeInt(Integer.reverseBytes(16)); w.writeShort(Short.reverseBytes((short) 1)); w.writeShort(Short.reverseBytes((short) 1));
                w.writeInt(Integer.reverseBytes(sr)); w.writeInt(Integer.reverseBytes(sr * 2)); w.writeShort(Short.reverseBytes((short) 2)); w.writeShort(Short.reverseBytes((short) 16));
                w.writeBytes("data"); w.writeInt(Integer.reverseBytes(data.length)); w.write(data);
            }
            ok = true;
            return out;
        } catch (RuntimeException e) {
            throw new IOException("could not cut the sound: " + e.getMessage());
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (RuntimeException ignored) { }
            if (!ok && out.exists()) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }

    public interface Progress { void at(double fraction); }

    /**
     * The sound of a whole video as a SMALL file for Whisper: mono, 16 kHz (what Whisper listens at anyway), AAC at about 24 kbit/s, so an hour is about
     * 11 MB instead of 60+. Decoded and encoded again on the phone; when that does not work on this phone the sound track is copied unchanged.
     */
    public static File smallAudio(Context c, Uri video, File dir, String base, Progress pg) throws IOException {
        try {
            return reencode(c, video, new File(dir, base + ".m4a"), pg);
        } catch (IOException | RuntimeException e) {
            File f = new File(dir, base + ".m4a");
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            return audio(c, video, 0, 24 * 3600, dir, base);          // as it is: bigger, but it works
        }
    }

    private static File reencode(Context c, Uri video, File out, Progress pg) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        boolean ok = false, muxOn = false;
        try {
            ex.setDataSource(c, video, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("the video has no sound");
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            final int RATE = 16000;
            MediaFormat ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 24000);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int inCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean floatPcm = false;
            byte[] q = new byte[1 << 17];
            int qLen = 0;
            long outSamples = 0, inIndex = 0;
            double nextPos = 0, prev = 0;
            boolean extEos = false, decEos = false, encInEos = false, encOutEos = false;
            long lastUs = 0;
            int idle = 0;
            while (!encOutEos) {
                boolean moved = false;
                if (!extEos) {
                    int ib = dec.dequeueInputBuffer(0);
                    if (ib >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ib);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); extEos = true; }
                        else { lastUs = ex.getSampleTime(); dec.queueInputBuffer(ib, 0, n, lastUs, 0); ex.advance(); }
                        moved = true;
                    }
                }
                if (!decEos && qLen < q.length / 2) {
                    int ob = dec.dequeueOutputBuffer(info, 2000);
                    if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        floatPcm = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4;
                        moved = true;
                    } else if (ob >= 0) {
                        ByteBuffer b = dec.getOutputBuffer(ob);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.order(java.nio.ByteOrder.nativeOrder());
                        double ratio = inRate / (double) RATE;
                        int step = (floatPcm ? 4 : 2) * Math.max(1, inCh);
                        while (b.remaining() >= step) {
                            double sum = 0;
                            for (int ch = 0; ch < inCh; ch++) sum += floatPcm ? b.getFloat() * 32768.0 : b.getShort();
                            double x = sum / Math.max(1, inCh);
                            while (nextPos <= inIndex) {
                                double fr = nextPos - (inIndex - 1);
                                double y = inIndex == 0 ? x : prev + (x - prev) * fr;
                                int v = (int) Math.max(-32768, Math.min(32767, Math.round(y)));
                                if (qLen + 2 > q.length) q = java.util.Arrays.copyOf(q, q.length * 2);
                                q[qLen++] = (byte) (v & 0xff);
                                q[qLen++] = (byte) ((v >> 8) & 0xff);
                                nextPos += ratio;
                            }
                            prev = x;
                            inIndex++;
                        }
                        dec.releaseOutputBuffer(ob, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decEos = true;
                        moved = true;
                    }
                }
                if (!encInEos && (qLen >= 2 || decEos)) {
                    int ib = enc.dequeueInputBuffer(0);
                    if (ib >= 0) {
                        ByteBuffer b = enc.getInputBuffer(ib);
                        b.clear();
                        int n = Math.min(b.remaining(), qLen) & ~1;
                        long pts = outSamples * 1_000_000L / RATE;
                        if (n > 0) {
                            b.put(q, 0, n);
                            System.arraycopy(q, n, q, 0, qLen - n);
                            qLen -= n;
                            outSamples += n / 2;
                            enc.queueInputBuffer(ib, 0, n, pts, 0);
                        } else {
                            enc.queueInputBuffer(ib, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInEos = true;
                        }
                        moved = true;
                    }
                }
                int eb = enc.dequeueOutputBuffer(info, 2000);
                if (eb == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxOn = true;
                    moved = true;
                } else if (eb >= 0) {
                    ByteBuffer b = enc.getOutputBuffer(eb);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxOn) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        mux.writeSampleData(muxTrack, b, info);
                    }
                    enc.releaseOutputBuffer(eb, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encOutEos = true;
                    moved = true;
                }
                if (pg != null && durUs > 0 && (outSamples & 0x3fff) < 600) pg.at(Math.min(1.0, lastUs / (double) durUs));
                idle = moved ? 0 : idle + 1;
                if (idle > 5000) throw new IOException("the phone's sound codec stopped answering");
            }
            if (!muxOn) throw new IOException("no sound was written");
            mux.stop();
            ok = true;
            return out;
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (RuntimeException ignored) { }
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (RuntimeException ignored) { }
            try { if (mux != null) mux.release(); } catch (RuntimeException ignored) { }
            ex.release();
            if (!ok && out.exists()) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }
}

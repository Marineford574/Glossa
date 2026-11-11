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
}

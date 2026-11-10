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
            } else throw new IOException("the video's sound is " + mime.replace("audio/", "") + ", which the phone cannot cut: the PC makes this card");
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
}

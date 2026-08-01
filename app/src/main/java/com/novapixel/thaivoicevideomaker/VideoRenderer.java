package com.novapixel.thaivoicevideomaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VideoRenderer {
    public interface Callback { void onSuccess(); void onError(Exception error); }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int WIDTH = 720, HEIGHT = 1280, FPS = 24;

    public static void render(Context context, Uri image, File wav, File output, Callback callback) {
        WORKER.execute(() -> {
            File video = new File(context.getCacheDir(), "silent-" + System.nanoTime() + ".mp4");
            File audio = new File(context.getCacheDir(), "audio-" + System.nanoTime() + ".m4a");
            try {
                WavInfo info = WavInfo.read(wav);
                double seconds = Math.max(1.0, info.dataSize / (double)(info.sampleRate * info.channels * 2));
                Bitmap source;
                try (java.io.InputStream in = context.getContentResolver().openInputStream(image)) {
                    source = BitmapFactory.decodeStream(in);
                }
                if (source == null) throw new IllegalArgumentException("ไม่สามารถอ่านภาพได้");
                encodeVideo(source, seconds, video);
                source.recycle();
                encodeAudio(wav, info, audio);
                mux(video, audio, output);
                callback.onSuccess();
            } catch (Exception e) {
                callback.onError(e);
            } finally {
                video.delete();
                audio.delete();
            }
        });
    }

    private static void encodeVideo(Bitmap source, double seconds, File file) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        int color = chooseColor(codec);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        codec.start();
        int track = -1;
        boolean started = false;
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
        int frames = Math.max(FPS, (int)Math.ceil(seconds * FPS));
        Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        byte[] yuv = new byte[WIDTH * HEIGHT * 3 / 2];

        for (int i = 0; i < frames; i++) {
            int input = codec.dequeueInputBuffer(20_000);
            if (input >= 0) {
                drawFrame(source, frame, i / (float)Math.max(1, frames - 1));
                argbToYuv(frame, yuv, color);
                ByteBuffer buffer = codec.getInputBuffer(input);
                buffer.clear();
                buffer.put(yuv);
                codec.queueInputBuffer(input, 0, yuv.length, i * 1_000_000L / FPS, 0);
            } else { i--; }
            DrainResult dr = drain(codec, muxer, track, started, outInfo, false);
            track = dr.track; started = dr.started;
        }

        int input;
        do { input = codec.dequeueInputBuffer(20_000); } while (input < 0);
        codec.queueInputBuffer(input, 0, 0, frames * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

        boolean eos = false;
        while (!eos) {
            int out = codec.dequeueOutputBuffer(outInfo, 20_000);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (started) throw new IllegalStateException("รูปแบบวิดีโอเปลี่ยนซ้ำ");
                track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true;
            } else if (out >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(out);
                if (outInfo.size > 0 && started) {
                    buffer.position(outInfo.offset); buffer.limit(outInfo.offset + outInfo.size);
                    muxer.writeSampleData(track, buffer, outInfo);
                }
                eos = (outInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(out, false);
            }
        }
        frame.recycle();
        codec.stop(); codec.release();
        if (started) muxer.stop();
        muxer.release();
    }

    private static DrainResult drain(MediaCodec codec, MediaMuxer muxer, int track, boolean started,
                                     MediaCodec.BufferInfo info, boolean wait) {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, wait ? 20_000 : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) { track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true; }
            } else if (out >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(out);
                if (info.size > 0 && started) {
                    buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    muxer.writeSampleData(track, buffer, info);
                }
                codec.releaseOutputBuffer(out, false);
            }
        }
        return new DrainResult(track, started);
    }

    private static void drawFrame(Bitmap source, Bitmap target, float progress) {
        Canvas canvas = new Canvas(target);
        canvas.drawColor(0xff101325);
        float base = Math.max(WIDTH / (float)source.getWidth(), HEIGHT / (float)source.getHeight());
        float motion = (float)Math.sin(progress * Math.PI);
        float zoom = base * (1.00f + 0.11f * progress);
        float w = source.getWidth() * zoom, h = source.getHeight() * zoom;
        float left = (WIDTH - w) / 2f + WIDTH * 0.025f * motion;
        float top = (HEIGHT - h) / 2f - HEIGHT * 0.045f * progress;
        Matrix matrix = new Matrix();
        matrix.postScale(zoom, zoom);
        matrix.postTranslate(left, top);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, matrix, paint);
    }

    private static int chooseColor(MediaCodec codec) {
        int[] colors = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats;
        for (int c : colors) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return c;
        for (int c : colors) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c;
        for (int c : colors) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return c;
        throw new IllegalStateException("เครื่องไม่รองรับรูปแบบสีสำหรับสร้างวิดีโอ");
    }

    private static void argbToYuv(Bitmap bitmap, byte[] output, int colorFormat) {
        int[] pixels = new int[WIDTH * HEIGHT];
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        int frame = WIDTH * HEIGHT, y = 0, u = frame, v = frame + frame / 4, uv = frame;
        boolean semi = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
        for (int row = 0; row < HEIGHT; row++) {
            for (int col = 0; col < WIDTH; col++) {
                int p = pixels[row * WIDTH + col];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int yy = clamp(((66*r + 129*g + 25*b + 128) >> 8) + 16);
                int uu = clamp(((-38*r - 74*g + 112*b + 128) >> 8) + 128);
                int vv = clamp(((112*r - 94*g - 18*b + 128) >> 8) + 128);
                output[y++] = (byte)yy;
                if ((row & 1) == 0 && (col & 1) == 0) {
                    if (semi) { output[uv++] = (byte)uu; output[uv++] = (byte)vv; }
                    else { output[u++] = (byte)uu; output[v++] = (byte)vv; }
                }
            }
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static void encodeAudio(File wav, WavInfo wavInfo, File file) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wavInfo.sampleRate, wavInfo.channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, wavInfo.channels == 1 ? 96_000 : 128_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean started = false, inputDone = false, outputDone = false;
        long samples = 0;
        try (RandomAccessFile input = new RandomAccessFile(wav, "r")) {
            input.seek(wavInfo.dataOffset);
            long remaining = wavInfo.dataSize;
            while (!outputDone) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(20_000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        buffer.clear();
                        int wanted = (int)Math.min(buffer.remaining(), remaining);
                        byte[] bytes = new byte[wanted];
                        int read = wanted > 0 ? input.read(bytes) : -1;
                        if (read > 0) {
                            buffer.put(bytes, 0, read);
                            long timeUs = samples * 1_000_000L / wavInfo.sampleRate;
                            codec.queueInputBuffer(index, 0, read, timeUs, 0);
                            samples += read / (wavInfo.channels * 2);
                            remaining -= read;
                        } else {
                            codec.queueInputBuffer(index, 0, 0, samples * 1_000_000L / wavInfo.sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int out = codec.dequeueOutputBuffer(info, 20_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true;
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started) {
                        buffer.position(info.offset); buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, false);
                }
            }
        }
        codec.stop(); codec.release();
        if (started) muxer.stop();
        muxer.release();
    }

    private static void mux(File video, File audio, File output) throws Exception {
        MediaExtractor videoEx = new MediaExtractor(), audioEx = new MediaExtractor();
        videoEx.setDataSource(video.getAbsolutePath());
        audioEx.setDataSource(audio.getAbsolutePath());
        int videoTrack = findTrack(videoEx, true), audioTrack = findTrack(audioEx, false);
        videoEx.selectTrack(videoTrack); audioEx.selectTrack(audioTrack);
        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int outVideo = muxer.addTrack(videoEx.getTrackFormat(videoTrack));
            int outAudio = muxer.addTrack(audioEx.getTrackFormat(audioTrack));
            muxer.start();
            copyTrack(videoEx, muxer, outVideo);
            copyTrack(audioEx, muxer, outAudio);
            muxer.stop();
        } finally {
            muxer.release(); videoEx.release(); audioEx.release();
        }
    }

    private static int findTrack(MediaExtractor ex, boolean video) {
        String prefix = video ? "video/" : "audio/";
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        throw new IllegalStateException(video ? "ไม่พบแทร็กวิดีโอ" : "ไม่พบแทร็กเสียง");
    }

    private static void copyTrack(MediaExtractor ex, MediaMuxer muxer, int track) {
        ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0; info.size = size; info.presentationTimeUs = ex.getSampleTime(); info.flags = ex.getSampleFlags();
            muxer.writeSampleData(track, buffer, info);
            ex.advance();
        }
    }

    private static final class DrainResult {
        final int track; final boolean started;
        DrainResult(int track, boolean started) { this.track = track; this.started = started; }
    }

    private static final class WavInfo {
        final int sampleRate, channels; final long dataOffset, dataSize;
        WavInfo(int sampleRate, int channels, long dataOffset, long dataSize) {
            this.sampleRate = sampleRate; this.channels = channels; this.dataOffset = dataOffset; this.dataSize = dataSize;
        }
        static WavInfo read(File file) throws Exception {
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                if (!"RIFF".equals(readFour(in))) throw new IllegalArgumentException("ไฟล์เสียงไม่ใช่ WAV");
                readLe32(in);
                if (!"WAVE".equals(readFour(in))) throw new IllegalArgumentException("รูปแบบเสียงไม่ถูกต้อง");
                int channels = 1, rate = 22050, bits = 16;
                while (in.getFilePointer() + 8 <= in.length()) {
                    String id = readFour(in); long size = readLe32(in);
                    long next = in.getFilePointer() + size + (size & 1);
                    if ("fmt ".equals(id)) {
                        int type = readLe16(in); channels = readLe16(in); rate = (int)readLe32(in);
                        readLe32(in); readLe16(in); bits = readLe16(in);
                        if (type != 1 || bits != 16) throw new IllegalArgumentException("รองรับเสียง PCM 16-bit เท่านั้น");
                    } else if ("data".equals(id)) {
                        return new WavInfo(rate, channels, in.getFilePointer(), size);
                    }
                    in.seek(next);
                }
            }
            throw new IllegalArgumentException("ไม่พบข้อมูลเสียงในไฟล์ WAV");
        }
        private static String readFour(RandomAccessFile in) throws Exception {
            byte[] b = new byte[4]; in.readFully(b); return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
        }
        private static int readLe16(RandomAccessFile in) throws Exception {
            return (in.readUnsignedByte() | in.readUnsignedByte() << 8);
        }
        private static long readLe32(RandomAccessFile in) throws Exception {
            return (long)in.readUnsignedByte() | (long)in.readUnsignedByte() << 8 |
                    (long)in.readUnsignedByte() << 16 | (long)in.readUnsignedByte() << 24;
        }
    }
}

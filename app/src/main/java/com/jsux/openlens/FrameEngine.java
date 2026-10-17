package com.jsux.openlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.graphics.ImageFormat;
import android.media.MediaMetadataRetriever;
import android.os.Process;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class FrameEngine {
    private static final String TAG = "OpenLens/FrameEngine";
    private static final Object SOURCE_LOCK = new Object();
    private static final Object CACHE_LOCK = new Object();
    private static volatile Context context;
    private static volatile boolean enabled;
    private static volatile String mediaType = "image";
    private static volatile Bitmap currentBitmap;
    private static volatile long frameVersion;
    private static volatile long durationMs = 1_000L;
    private static volatile long configVersion = Long.MIN_VALUE;
    private static volatile long retryAfterMs = 0L;
    private static ScheduledExecutorService configExecutor;
    private static ScheduledExecutorService videoExecutor;
    private static ScheduledFuture<?> videoTask;
    private static MediaMetadataRetriever retriever;
    private static long videoStartedAt;
    private static byte[] cachedPreviewFrame;
    private static long cachedFrameVersion = Long.MIN_VALUE;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;
    private static int cachedFormat = -1;

    private FrameEngine() {
    }

    interface BitmapConsumer {
        boolean accept(Bitmap bitmap, long version);
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean hasSource() {
        Bitmap bitmap = currentBitmap;
        return enabled && bitmap != null && !bitmap.isRecycled();
    }

    static boolean withCurrentBitmap(BitmapConsumer consumer) {
        synchronized (SOURCE_LOCK) {
            Bitmap bitmap = currentBitmap;
            if (!enabled || bitmap == null || bitmap.isRecycled()) return false;
            try {
                return consumer.accept(bitmap, frameVersion);
            } catch (Throwable error) {
                Log.w(TAG, "Bitmap consumer failed", error);
                return false;
            }
        }
    }

    static byte[] currentJpeg(int maxDimension, int quality) {
        synchronized (SOURCE_LOCK) {
            Bitmap bitmap = currentBitmap;
            if (!enabled || bitmap == null || bitmap.isRecycled()) return null;
            Bitmap output = bitmap;
            boolean temporary = Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxDimension;
            if (temporary) {
                float scale = maxDimension / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
                int width = Math.max(2, Math.round(bitmap.getWidth() * scale));
                int height = Math.max(2, Math.round(bitmap.getHeight() * scale));
                output = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, Math.max(50, Math.min(100, quality)), bytes)) return null;
                return bytes.toByteArray();
            } catch (Throwable error) {
                Log.w(TAG, "JPEG conversion failed", error);
                return null;
            } finally {
                if (temporary && output != bitmap && !output.isRecycled()) output.recycle();
            }
        }
    }

    static synchronized void initialize(Context hostContext) {
        if (hostContext == null) return;
        context = hostContext.getApplicationContext();
        if (context == null) context = hostContext;
        if (configExecutor != null && !configExecutor.isShutdown()) return;
        configExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OpenLens-config");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        configExecutor.scheduleWithFixedDelay(FrameEngine::refreshConfig, 0, 300, TimeUnit.MILLISECONDS);
    }

    private static void refreshConfig() {
        Context ctx = context;
        if (ctx == null || System.currentTimeMillis() < retryAfterMs) return;
        try (InputStream in = ctx.getContentResolver().openInputStream(ModuleContract.CONFIG_URI)) {
            if (in == null) return;
            java.io.ByteArrayOutputStream configBytes = new java.io.ByteArrayOutputStream(512);
            byte[] buffer = new byte[4 * 1024];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > 16 * 1024) throw new IllegalStateException("Configuration exceeds 16 KiB");
                configBytes.write(buffer, 0, count);
            }
            if (total == 0) return;
            JSONObject json = new JSONObject(configBytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
            retryAfterMs = 0L;
            long updatedAt = json.optLong("updatedAt", 0L);
            if (updatedAt == configVersion) return;
            configVersion = updatedAt;
            boolean nextEnabled = json.optBoolean("enabled", false);
            String nextType = json.optString("mediaType", "image");
            if (!"video".equals(nextType)) nextType = "image";
            applyConfig(nextEnabled, nextType);
        } catch (java.io.FileNotFoundException | SecurityException e) {
            retryAfterMs = System.currentTimeMillis() + 3_000L;
            if (enabled) {
                Log.w(TAG, "Configuration access was lost; stopping replacement", e);
                configVersion = Long.MIN_VALUE;
                applyConfig(false, mediaType);
            }
        } catch (Throwable e) {
            retryAfterMs = System.currentTimeMillis() + 1_000L;
            Log.w(TAG, "Could not read configuration; leaving current state unchanged", e);
        }
    }

    private static void applyConfig(boolean nextEnabled, String nextType) {
        synchronized (SOURCE_LOCK) {
            boolean changed = enabled != nextEnabled || !mediaType.equals(nextType);
            if (!changed) return;
            enabled = nextEnabled;
            mediaType = nextType;
            stopVideoLocked();
            Bitmap previous = currentBitmap;
            currentBitmap = null;
            if (previous != null && !previous.isRecycled()) previous.recycle();
            synchronized (CACHE_LOCK) {
                cachedPreviewFrame = null;
                cachedFrameVersion = Long.MIN_VALUE;
                cachedFormat = -1;
            }
            frameVersion++;
            if (!enabled) return;
            if ("image".equals(mediaType)) {
                loadImageLocked();
            } else {
                startVideoLocked();
            }
        }
    }

    private static void loadImageLocked() {
        Context ctx = context;
        if (ctx == null) return;
        try (InputStream in = ctx.getContentResolver().openInputStream(ModuleContract.MEDIA_URI)) {
            if (in == null) throw new IllegalStateException("No media stream");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / sample > 1920) sample *= 2;
            try (InputStream decodeIn = ctx.getContentResolver().openInputStream(ModuleContract.MEDIA_URI)) {
                if (decodeIn == null) throw new IllegalStateException("No media stream");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap decoded = BitmapFactory.decodeStream(decodeIn, null, options);
                if (decoded == null) throw new IllegalStateException("Image decoder returned no frame");
                Bitmap previous = currentBitmap;
                currentBitmap = decoded;
                frameVersion++;
                if (previous != null && previous != decoded && !previous.isRecycled()) previous.recycle();
            }
        } catch (Throwable e) {
            enabled = false;
            Log.e(TAG, "Unable to decode selected image", e);
        }
    }

    private static void startVideoLocked() {
        Context ctx = context;
        if (ctx == null) return;
        MediaMetadataRetriever local = new MediaMetadataRetriever();
        try {
            local.setDataSource(ctx, ModuleContract.MEDIA_URI);
            String duration = local.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = Math.max(1_000L, duration == null ? 1_000L : Long.parseLong(duration));
            retriever = local;
            videoStartedAt = System.currentTimeMillis();
            videoExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "OpenLens-video");
                thread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
                return thread;
            });
            videoTask = videoExecutor.scheduleWithFixedDelay(FrameEngine::decodeVideoFrame, 0, 66, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            try { local.release(); } catch (Throwable ignored) { }
            enabled = false;
            Log.e(TAG, "Unable to open selected video", e);
        }
    }

    private static void decodeVideoFrame() {
        synchronized (SOURCE_LOCK) {
            MediaMetadataRetriever local = retriever;
            if (!enabled || local == null || !"video".equals(mediaType)) return;
            try {
                long elapsed = Math.max(0L, System.currentTimeMillis() - videoStartedAt);
                long atMs = durationMs == 0 ? 0 : elapsed % durationMs;
                Bitmap frame;
                if (Build.VERSION.SDK_INT >= 27) {
                    String widthValue = local.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String heightValue = local.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    int videoWidth = widthValue == null ? 1280 : Integer.parseInt(widthValue);
                    int videoHeight = heightValue == null ? 720 : Integer.parseInt(heightValue);
                    float scale = Math.min(1f, 1280f / Math.max(1, Math.max(videoWidth, videoHeight)));
                    int outWidth = Math.max(2, ((int) (videoWidth * scale)) & ~1);
                    int outHeight = Math.max(2, ((int) (videoHeight * scale)) & ~1);
                    frame = local.getScaledFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, outWidth, outHeight);
                } else {
                    frame = local.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (frame != null) {
                    Bitmap previous = currentBitmap;
                    currentBitmap = frame;
                    frameVersion++;
                    if (previous != null && previous != frame && !previous.isRecycled()) previous.recycle();
                }
            } catch (Throwable e) {
                Log.w(TAG, "Video frame decode failed", e);
            }
        }
    }

    private static void stopVideoLocked() {
        if (videoTask != null) {
            videoTask.cancel(true);
            videoTask = null;
        }
        if (videoExecutor != null) {
            videoExecutor.shutdownNow();
            videoExecutor = null;
        }
        if (retriever != null) {
            try { retriever.release(); } catch (Throwable ignored) { }
            retriever = null;
        }
    }

    static void inject(byte[] destination, Camera camera) {
        if (destination == null || camera == null) return;
        synchronized (SOURCE_LOCK) {
            if (!enabled) return;
            Bitmap source = currentBitmap;
            if (source == null || source.isRecycled()) return;
            try {
                Camera.Parameters parameters = camera.getParameters();
                Camera.Size size = parameters.getPreviewSize();
                if (size == null || size.width < 2 || size.height < 2 ||
                        (long) size.width * size.height > 16_777_216L) return;
                int format = parameters.getPreviewFormat();
                byte[] frame = getOrRenderPreviewFrame(source, size.width, size.height, format);
                if (frame == null || destination.length < frame.length) return;
                System.arraycopy(frame, 0, destination, 0, frame.length);
            } catch (Throwable e) {
                Log.w(TAG, "Skipping unsupported Camera1 frame format", e);
            }
        }
    }

    /**
     * Converts the selected bitmap into common Camera1 preview formats. Android devices
     * are required to expose NV21 and YV12; YUY2 and RGB_565 are device-dependent.
     */
    private static byte[] getOrRenderPreviewFrame(Bitmap source, int width, int height, int format) {
        long version = frameVersion;
        synchronized (CACHE_LOCK) {
            if (cachedPreviewFrame != null && cachedFrameVersion == version &&
                    cachedWidth == width && cachedHeight == height && cachedFormat == format) {
                return cachedPreviewFrame;
            }
        }
        if ((format == ImageFormat.NV21 || format == ImageFormat.YV12) &&
                ((width & 1) != 0 || (height & 1) != 0)) return null;
        if (format != ImageFormat.NV21 && format != ImageFormat.YV12 &&
                format != ImageFormat.YUY2 && format != ImageFormat.RGB_565) return null;

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int[] sourcePixels = new int[Math.multiplyExact(sourceWidth, sourceHeight)];
        try {
            source.getPixels(sourcePixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight);
            byte[] output;
            switch (format) {
                case ImageFormat.NV21:
                    output = toNv21(sourcePixels, sourceWidth, sourceHeight, width, height);
                    break;
                case ImageFormat.YV12:
                    output = toYv12(sourcePixels, sourceWidth, sourceHeight, width, height);
                    break;
                case ImageFormat.YUY2:
                    output = toYuy2(sourcePixels, sourceWidth, sourceHeight, width, height);
                    break;
                case ImageFormat.RGB_565:
                    output = toRgb565(sourcePixels, sourceWidth, sourceHeight, width, height);
                    break;
                default:
                    return null;
            }
            synchronized (CACHE_LOCK) {
                cachedPreviewFrame = output;
                cachedFrameVersion = version;
                cachedWidth = width;
                cachedHeight = height;
                cachedFormat = format;
            }
            return output;
        } catch (Throwable error) {
            Log.w(TAG, "Camera1 conversion failed for format " + format, error);
            return null;
        }
    }

    private static byte[] toNv21(int[] pixels, int sourceWidth, int sourceHeight, int width, int height) {
        byte[] output = new byte[Math.multiplyExact(width, height) * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = sample(pixels, sourceWidth, sourceHeight, width, height, x, y);
                output[yIndex++] = (byte) luma(pixel);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    output[uvIndex++] = (byte) chromaV(pixel);
                    output[uvIndex++] = (byte) chromaU(pixel);
                }
            }
        }
        return output;
    }

    private static byte[] toYv12(int[] pixels, int sourceWidth, int sourceHeight, int width, int height) {
        int yStride = align16(width);
        int chromaStride = align16(yStride / 2);
        int yBytes = Math.multiplyExact(yStride, height);
        int chromaBytes = Math.multiplyExact(chromaStride, height / 2);
        byte[] output = new byte[Math.addExact(yBytes, Math.multiplyExact(chromaBytes, 2))];
        int vStart = yBytes;
        int uStart = yBytes + chromaBytes;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                output[y * yStride + x] = (byte) luma(sample(pixels, sourceWidth, sourceHeight, width, height, x, y));
            }
        }
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int pixel = sample(pixels, sourceWidth, sourceHeight, width, height, x, y);
                int offset = (y / 2) * chromaStride + x / 2;
                output[vStart + offset] = (byte) chromaV(pixel);
                output[uStart + offset] = (byte) chromaU(pixel);
            }
        }
        return output;
    }

    private static byte[] toYuy2(int[] pixels, int sourceWidth, int sourceHeight, int width, int height) {
        if ((width & 1) != 0) return null;
        byte[] output = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 2)];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x += 2) {
                int first = sample(pixels, sourceWidth, sourceHeight, width, height, x, y);
                int second = sample(pixels, sourceWidth, sourceHeight, width, height, x + 1, y);
                output[offset++] = (byte) luma(first);
                output[offset++] = (byte) ((chromaU(first) + chromaU(second)) / 2);
                output[offset++] = (byte) luma(second);
                output[offset++] = (byte) ((chromaV(first) + chromaV(second)) / 2);
            }
        }
        return output;
    }

    private static byte[] toRgb565(int[] pixels, int sourceWidth, int sourceHeight, int width, int height) {
        byte[] output = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 2)];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = sample(pixels, sourceWidth, sourceHeight, width, height, x, y);
                int r = (pixel >> 16) & 255;
                int g = (pixel >> 8) & 255;
                int b = pixel & 255;
                int packed = ((r * 31 / 255) << 11) | ((g * 63 / 255) << 5) | (b * 31 / 255);
                output[offset++] = (byte) (packed & 255);
                output[offset++] = (byte) ((packed >>> 8) & 255);
            }
        }
        return output;
    }

    private static int sample(int[] pixels, int sourceWidth, int sourceHeight, int width, int height, int x, int y) {
        int sourceX = Math.min(sourceWidth - 1, (int) (((long) x * sourceWidth) / width));
        int sourceY = Math.min(sourceHeight - 1, (int) (((long) y * sourceHeight) / height));
        return pixels[sourceY * sourceWidth + sourceX];
    }

    private static int luma(int pixel) {
        int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
        return clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
    }

    private static int chromaU(int pixel) {
        int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
        return clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
    }

    private static int chromaV(int pixel) {
        int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
        return clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
    }

    private static int align16(int value) { return (value + 15) & ~15; }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}

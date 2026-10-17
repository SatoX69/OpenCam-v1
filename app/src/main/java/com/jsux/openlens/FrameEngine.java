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
    private static byte[] cachedNv21;
    private static long cachedFrameVersion = Long.MIN_VALUE;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;

    private FrameEngine() {
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
            byte[] buffer = new byte[16 * 1024];
            int count = in.read(buffer);
            if (count <= 0) return;
            JSONObject json = new JSONObject(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
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
            currentBitmap = null;
            synchronized (CACHE_LOCK) {
                cachedNv21 = null;
                cachedFrameVersion = Long.MIN_VALUE;
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
                currentBitmap = decoded;
                frameVersion++;
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
            videoTask = videoExecutor.scheduleWithFixedDelay(FrameEngine::decodeVideoFrame, 0, 120, TimeUnit.MILLISECONDS);
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
                    currentBitmap = frame;
                    frameVersion++;
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
        if (!enabled || destination == null || camera == null) return;
        Bitmap source = currentBitmap;
        if (source == null) return;
        try {
            Camera.Parameters parameters = camera.getParameters();
            if (parameters.getPreviewFormat() != ImageFormat.NV21) return;
            Camera.Size size = parameters.getPreviewSize();
            if (size == null || size.width < 2 || size.height < 2 || (size.width & 1) != 0 || (size.height & 1) != 0) return;
            byte[] frame = getOrRenderNv21(source, size.width, size.height);
            if (frame == null || destination.length < frame.length) return;
            System.arraycopy(frame, 0, destination, 0, frame.length);
        } catch (Throwable e) {
            Log.w(TAG, "Skipping unsupported camera frame", e);
        }
    }

    private static byte[] getOrRenderNv21(Bitmap source, int width, int height) {
        long version = frameVersion;
        synchronized (CACHE_LOCK) {
            if (cachedNv21 != null && cachedFrameVersion == version && cachedWidth == width && cachedHeight == height) {
                return cachedNv21;
            }
            Bitmap scaled = source;
            boolean temporary = source.getWidth() != width || source.getHeight() != height;
            if (temporary) scaled = Bitmap.createScaledBitmap(source, width, height, true);
            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] output = new byte[width * height * 3 / 2];
            int yIndex = 0;
            int uvIndex = width * height;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[row + x];
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;
                    int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    output[yIndex++] = (byte) clamp(yy);
                    if ((y & 1) == 0 && (x & 1) == 0) {
                        int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                        output[uvIndex++] = (byte) clamp(v);
                        output[uvIndex++] = (byte) clamp(u);
                    }
                }
            }
            cachedNv21 = output;
            cachedFrameVersion = version;
            cachedWidth = width;
            cachedHeight = height;
            return output;
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}

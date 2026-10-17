package com.jsux.openlens;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/** Replaces standard CPU-visible Android Image planes (common flexible YUV layouts and JPEG). */
final class CameraImageAdapter {
    private static final String TAG = "OpenLens/ImageAdapter";
    private static final long MAX_TOTAL_PLANE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_SINGLE_PLANE_BYTES = 32 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final Set<Method> HOOKED = new HashSet<>();
    private static final Map<Object, PlaneInfo> PLANES = new WeakHashMap<>();
    private static final Map<Object, CachedImage> IMAGE_CACHE = new WeakHashMap<>();
    private static CachedImage sharedFrameCache;
    private static boolean readerHooksInstalled;

    private CameraImageAdapter() { }

    static void install(OpenLensModule module) {
        synchronized (LOCK) {
            if (readerHooksInstalled) return;
            readerHooksInstalled = true;
        }
        // MultiResolutionImageReader supplies ordinary ImageReader instances to its
        // listeners; observing ImageReader acquisition therefore covers those images too.
        installReaderClassHooks(module, ImageReader.class);
    }

    private static void installReaderClassHooks(OpenLensModule module, Class<?> readerClass) {
        for (Method method : readerClass.getDeclaredMethods()) {
            if (!method.getName().equals("acquireNextImage") &&
                    !method.getName().equals("acquireLatestImage") &&
                    !method.getName().equals("acquireNextImageNoThrowISE")) continue;
            synchronized (LOCK) {
                if (!HOOKED.add(method)) continue;
            }
            try {
                module.hook(method).setId("image_reader_" + Integer.toHexString(method.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                            Object result = chain.proceed();
                            if (result != null) watchImage(module, result);
                            return result;
                        });
            } catch (Throwable error) {
                synchronized (LOCK) { HOOKED.remove(method); }
                Log.d(TAG, "Image reader hook skipped: " + method, error);
            }
        }
    }

    private static void watchImage(OpenLensModule module, Object image) {
        Method getPlanes = findMethod(image.getClass(), "getPlanes");
        if (getPlanes == null) return;
        hookOnce(module, getPlanes, "image_planes_" + image.getClass().getName().replace('.', '_'), chain -> {
            Object result = chain.proceed();
            if (result instanceof Object[]) registerPlanes(module, chain.getThisObject(), (Object[]) result);
            return result;
        });
        Method close = findMethod(image.getClass(), "close");
        if (close != null) hookOnce(module, close, "image_close_" + image.getClass().getName().replace('.', '_'), chain -> {
            Object owner = chain.getThisObject();
            Object result = chain.proceed();
            synchronized (LOCK) {
                IMAGE_CACHE.remove(owner);
                PLANES.entrySet().removeIf(entry -> entry.getValue().image.get() == owner);
            }
            return result;
        });
        try {
            Object planes = getPlanes.invoke(image);
            if (planes instanceof Object[]) registerPlanes(module, image, (Object[]) planes);
        } catch (Throwable ignored) { }
    }

    private static void registerPlanes(OpenLensModule module, Object image, Object[] planes) {
        if (planes == null || planes.length == 0) return;
        int width = callInt(image, "getWidth", 0);
        int height = callInt(image, "getHeight", 0);
        int format = callInt(image, "getFormat", 0);
        if (width < 2 || height < 2 || !isSupportedFormat(format)) return;
        synchronized (LOCK) {
            for (int index = 0; index < planes.length; index++) {
                Object plane = planes[index];
                if (plane == null) continue;
                PLANES.put(plane, new PlaneInfo(image, index, width, height, format));
                Method getBuffer = findMethod(plane.getClass(), "getBuffer");
                if (getBuffer == null) continue;
                hookOnce(module, getBuffer, "image_plane_buffer_" + plane.getClass().getName().replace('.', '_'), chain -> {
                    ByteBuffer original = (ByteBuffer) chain.proceed();
                    PlaneInfo info;
                    synchronized (LOCK) { info = PLANES.get(chain.getThisObject()); }
                    if (info == null || !FrameEngine.isEnabled()) return original;
                    try {
                        ByteBuffer injected = getInjectedBuffer(chain.getThisObject(), info);
                        return injected == null ? original : injected;
                    } catch (Throwable error) {
                        Log.d(TAG, "Image plane was left unchanged", error);
                        return original;
                    }
                });
            }
        }
    }

    private interface Interceptor { Object intercept(XposedInterface.Chain chain) throws Throwable; }

    private static void hookOnce(OpenLensModule module, Method method, String id, Interceptor interceptor) {
        synchronized (LOCK) {
            if (!HOOKED.add(method)) return;
        }
        try {
            module.hook(method).setId(id).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(interceptor::intercept);
        } catch (Throwable error) {
            synchronized (LOCK) { HOOKED.remove(method); }
            Log.d(TAG, "Image method hook skipped: " + method, error);
        }
    }

    private static ByteBuffer getInjectedBuffer(Object plane, PlaneInfo info) {
        Object image = info.image.get();
        if (image == null) return null;
        CachedImage cached;
        synchronized (LOCK) {
            cached = IMAGE_CACHE.get(image);
            if (cached != null) return cached.bufferFor(plane, info.index);
        }
        final CachedImage[] generated = new CachedImage[1];
        boolean success = FrameEngine.withCurrentBitmap((bitmap, version) -> {
            try {
                generated[0] = createCachedImage(image, bitmap, version, info);
                return generated[0] != null;
            } catch (Throwable error) {
                Log.d(TAG, "Could not create replacement image", error);
                return false;
            }
        });
        if (!success || generated[0] == null) return null;
        synchronized (LOCK) {
            CachedImage existing = IMAGE_CACHE.get(image);
            if (existing != null) return existing.bufferFor(plane, info.index);
            IMAGE_CACHE.put(image, generated[0]);
            return generated[0].bufferFor(plane, info.index);
        }
    }

    private static CachedImage createCachedImage(Object image, Bitmap source, long version, PlaneInfo firstPlane) throws Exception {
        int width = firstPlane.width;
        int height = firstPlane.height;
        int format = firstPlane.format;
        if (width < 2 || height < 2 || width > 4096 || height > 4096 ||
                (long) width * height > 16_777_216L) return null;
        int[] rowStride = new int[3];
        int[] pixelStride = new int[3];
        Object[] planes = (Object[]) image.getClass().getMethod("getPlanes").invoke(image);
        if (planes == null || planes.length == 0) return null;

        if (isYuvFormat(format)) {
            int subsampleX = format == ImageFormat.YUV_444_888 ? 1 : 2;
            for (int i = 0; i < 3 && i < planes.length; i++) {
                int planeWidth = i == 0 ? width : (width + subsampleX - 1) / subsampleX;
                int defaultPixelStride = i == 0 ? 1 : 2;
                pixelStride[i] = callInt(planes[i], "getPixelStride", defaultPixelStride);
                if (pixelStride[i] < 1 || pixelStride[i] > 16) pixelStride[i] = defaultPixelStride;
                int minimumRowStride = Math.addExact(Math.multiplyExact(Math.max(0, planeWidth - 1), pixelStride[i]), 1);
                int defaultRowStride = Math.max(minimumRowStride, Math.multiplyExact(planeWidth, pixelStride[i]));
                rowStride[i] = callInt(planes[i], "getRowStride", defaultRowStride);
                if (rowStride[i] < minimumRowStride) rowStride[i] = defaultRowStride;
            }
            if (planes.length < 3) return null;
        } else if (format == ImageFormat.Y8) {
            pixelStride[0] = callInt(planes[0], "getPixelStride", 1);
            rowStride[0] = callInt(planes[0], "getRowStride", width);
            if (pixelStride[0] != 1 || rowStride[0] < width) return null;
        } else if (format == ImageFormat.YCBCR_P010) {
            if (planes.length < 2 || (width & 1) != 0 || (height & 1) != 0) return null;
            pixelStride[0] = callInt(planes[0], "getPixelStride", 2);
            pixelStride[1] = callInt(planes[1], "getPixelStride", 4);
            if (pixelStride[0] != 2 || pixelStride[1] < 4 || pixelStride[1] > 8) return null;
            rowStride[0] = callInt(planes[0], "getRowStride", width * 2);
            rowStride[1] = callInt(planes[1], "getRowStride", (width / 2) * pixelStride[1]);
            if (rowStride[0] < width * 2 || rowStride[1] < (width / 2) * pixelStride[1]) return null;
        }

        synchronized (LOCK) {
            if (sharedFrameCache != null && sharedFrameCache.matches(version, width, height, format, rowStride, pixelStride)) return sharedFrameCache;
        }
        if (format == ImageFormat.JPEG) {
            Bitmap output = source;
            boolean temporary = source.getWidth() != width || source.getHeight() != height;
            if (temporary) output = Bitmap.createScaledBitmap(source, width, height, true);
            try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 94, stream)) return null;
                byte[] jpeg = stream.toByteArray();
                if (jpeg.length > MAX_TOTAL_PLANE_BYTES) return null;
                CachedImage created = new CachedImage(version, width, height, format, rowStride, pixelStride,
                        new byte[][]{jpeg});
                synchronized (LOCK) { sharedFrameCache = created; }
                return created;
            } finally {
                if (temporary && output != source && !output.isRecycled()) output.recycle();
            }
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int[] sourcePixels = new int[Math.multiplyExact(sourceWidth, sourceHeight)];
        source.getPixels(sourcePixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight);
        byte[][] bytes;
        if (format == ImageFormat.Y8) {
            ensurePlaneBudget((long) rowStride[0] * height, 0L);
            bytes = new byte[][]{makeLuma(sourcePixels, sourceWidth, sourceHeight, width, height, rowStride[0], pixelStride[0])};
        } else if (format == ImageFormat.YCBCR_P010) {
            int chromaRows = height / 2;
            long lumaBytes = (long) rowStride[0] * height;
            long chromaBytes = (long) rowStride[1] * chromaRows;
            ensurePlaneBudget(lumaBytes, chromaBytes);
            bytes = new byte[][]{
                    makeP010Luma(sourcePixels, sourceWidth, sourceHeight, width, height, rowStride[0]),
                    makeP010Chroma(sourcePixels, sourceWidth, sourceHeight, width, height, rowStride[1], pixelStride[1])
            };
        } else {
            int subsampleX = format == ImageFormat.YUV_444_888 ? 1 : 2;
            int subsampleY = format == ImageFormat.YUV_420_888 ? 2 : 1;
            long lumaBytes = (long) rowStride[0] * height;
            long chromaRows = (height + subsampleY - 1L) / subsampleY;
            long chromaBytesU = (long) rowStride[1] * chromaRows;
            long chromaBytesV = (long) rowStride[2] * chromaRows;
            ensurePlaneBudget(lumaBytes, chromaBytesU + chromaBytesV);
            bytes = new byte[][]{
                    makeLuma(sourcePixels, sourceWidth, sourceHeight, width, height, rowStride[0], pixelStride[0]),
                    makeChroma(sourcePixels, sourceWidth, sourceHeight, width, height,
                            rowStride[1], pixelStride[1], true, subsampleX, subsampleY),
                    makeChroma(sourcePixels, sourceWidth, sourceHeight, width, height,
                            rowStride[2], pixelStride[2], false, subsampleX, subsampleY)
            };
        }
        CachedImage created = new CachedImage(version, width, height, format, rowStride, pixelStride, bytes);
        synchronized (LOCK) { sharedFrameCache = created; }
        return created;
    }

    private static byte[] makeLuma(int[] pixels, int sourceWidth, int sourceHeight,
                                   int width, int height, int rowStride, int pixelStride) {
        byte[] data = new byte[checkedPlaneLength((long) rowStride * height)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = sample(pixels, sourceWidth, sourceHeight, width, height, x, y);
                int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                int offset = y * rowStride + x * pixelStride;
                if (offset >= 0 && offset < data.length) data[offset] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        return data;
    }

    private static byte[] makeChroma(int[] pixels, int sourceWidth, int sourceHeight,
                                     int width, int height, int rowStride, int pixelStride,
                                     boolean wantU, int subsampleX, int subsampleY) {
        int chromaWidth = (width + subsampleX - 1) / subsampleX;
        int chromaHeight = (height + subsampleY - 1) / subsampleY;
        byte[] data = new byte[checkedPlaneLength((long) rowStride * chromaHeight)];
        for (int y = 0; y < chromaHeight; y++) {
            for (int x = 0; x < chromaWidth; x++) {
                int rgb = sample(pixels, sourceWidth, sourceHeight, width, height,
                        Math.min(width - 1, x * subsampleX), Math.min(height - 1, y * subsampleY));
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                int value = wantU
                        ? ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128
                        : ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                int offset = y * rowStride + x * pixelStride;
                if (offset >= 0 && offset < data.length) data[offset] = (byte) clamp(value);
            }
        }
        return data;
    }

    private static int sample(int[] pixels, int sourceWidth, int sourceHeight,
                              int width, int height, int x, int y) {
        int sourceX = Math.min(sourceWidth - 1, (int) (((long) x * sourceWidth) / width));
        int sourceY = Math.min(sourceHeight - 1, (int) (((long) y * sourceHeight) / height));
        return pixels[sourceY * sourceWidth + sourceX];
    }

    private static byte[] makeP010Luma(int[] pixels, int sourceWidth, int sourceHeight,
                                       int width, int height, int rowStride) {
        byte[] data = new byte[checkedPlaneLength((long) rowStride * height)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = sample(pixels, sourceWidth, sourceHeight, width, height, x, y);
                int tenBit = (luma(rgb) * 1023 + 127) / 255;
                int value = tenBit << 6;
                int offset = y * rowStride + x * 2;
                data[offset] = (byte) (value & 0xff);
                data[offset + 1] = (byte) ((value >>> 8) & 0xff);
            }
        }
        return data;
    }

    private static byte[] makeP010Chroma(int[] pixels, int sourceWidth, int sourceHeight,
                                         int width, int height, int rowStride, int pixelStride) {
        byte[] data = new byte[checkedPlaneLength((long) rowStride * (height / 2))];
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                int rgb = sample(pixels, sourceWidth, sourceHeight, width, height, x * 2, y * 2);
                int u = ((chromaU(rgb) * 1023 + 127) / 255) << 6;
                int v = ((chromaV(rgb) * 1023 + 127) / 255) << 6;
                int offset = y * rowStride + x * pixelStride;
                if (offset + 3 >= data.length || offset + 3 >= (y + 1L) * rowStride) continue;
                data[offset] = (byte) (u & 0xff);
                data[offset + 1] = (byte) ((u >>> 8) & 0xff);
                data[offset + 2] = (byte) (v & 0xff);
                data[offset + 3] = (byte) ((v >>> 8) & 0xff);
            }
        }
        return data;
    }

    private static void ensurePlaneBudget(long first, long rest) {
        if (first < 1 || first > MAX_SINGLE_PLANE_BYTES || rest < 0 || rest > MAX_TOTAL_PLANE_BYTES ||
                first + rest > MAX_TOTAL_PLANE_BYTES) {
            throw new IllegalArgumentException("Camera image planes exceed the configured memory budget");
        }
        if (rest > 0) {
            // The caller passes a combined remaining-plane size, so individual sizes are validated before allocation too.
            if (rest > MAX_TOTAL_PLANE_BYTES - first) throw new IllegalArgumentException("Camera image buffer budget exceeded");
        }
    }

    private static int checkedPlaneLength(long size) {
        if (size < 1 || size > MAX_SINGLE_PLANE_BYTES) throw new IllegalArgumentException("Camera image plane is too large");
        return (int) size;
    }

    private static boolean isYuvFormat(int format) {
        return format == ImageFormat.YUV_420_888 || format == ImageFormat.YUV_422_888 || format == ImageFormat.YUV_444_888;
    }

    private static boolean isSupportedFormat(int format) {
        return format == ImageFormat.JPEG || format == ImageFormat.Y8 || format == ImageFormat.YCBCR_P010 || isYuvFormat(format);
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static final class PlaneInfo {
        final java.lang.ref.WeakReference<Object> image;
        final int index;
        final int width;
        final int height;
        final int format;
        PlaneInfo(Object image, int index, int width, int height, int format) {
            this.image = new java.lang.ref.WeakReference<>(image);
            this.index = index;
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    private static final class CachedImage {
        final long version;
        final int width;
        final int height;
        final int format;
        final int[] rowStride;
        final int[] pixelStride;
        final byte[][] buffers;
        final ByteBuffer[] directBuffers;
        CachedImage(long version, int width, int height, int format, int[] rowStride, int[] pixelStride, byte[][] buffers) {
            this.version = version;
            this.width = width;
            this.height = height;
            this.format = format;
            this.rowStride = rowStride.clone();
            this.pixelStride = pixelStride.clone();
            this.buffers = buffers;
            this.directBuffers = new ByteBuffer[buffers.length];
        }
        boolean matches(long currentVersion, int currentWidth, int currentHeight, int currentFormat, int[] currentRows, int[] currentPixels) {
            if (version != currentVersion || width != currentWidth || height != currentHeight || format != currentFormat) return false;
            return java.util.Arrays.equals(rowStride, currentRows) && java.util.Arrays.equals(pixelStride, currentPixels);
        }
        synchronized ByteBuffer bufferFor(Object plane, int index) {
            if (buffers.length == 0) return null;
            int safeIndex = Math.min(index, buffers.length - 1);
            byte[] data = buffers[safeIndex];
            if (data == null) return null;
            if (directBuffers[safeIndex] == null) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
                buffer.put(data);
                buffer.flip();
                directBuffers[safeIndex] = buffer;
            }
            ByteBuffer copy = directBuffers[safeIndex].duplicate().order(ByteOrder.nativeOrder());
            copy.position(0);
            copy.limit(data.length);
            return copy;
        }
    }
}

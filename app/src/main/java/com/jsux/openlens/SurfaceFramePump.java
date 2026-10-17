package com.jsux.openlens;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Surface-backed camera adapter. The real camera writes to a private SurfaceTexture;
 * this worker displays either OpenLens' selected frame or the physical-camera texture
 * on the app's original preview Surface. All EGL and SurfaceTexture operations stay
 * on one dedicated handler thread.
 */
final class SurfaceFramePump implements AutoCloseable {
    private static final String TAG = "OpenLens/SurfacePump";
    private static final long FRAME_INTERVAL_MS = 33L;
    private static final float[] VERTICES = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
    };

    private final Surface outputSurface;
    private final int width;
    private final int height;
    private final boolean ownsOutputSurface;
    private final HandlerThread thread;
    private final Handler handler;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean initialized;
    private volatile String initError;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;
    private android.opengl.EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext context = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLConfig config;
    private android.opengl.EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private android.opengl.EGLSurface outputEglSurface = EGL14.EGL_NO_SURFACE;
    private int cameraTextureId;
    private int bitmapTextureId;
    private int bitmapProgram;
    private int externalProgram;
    private int bitmapPosition;
    private int bitmapTexCoord;
    private int bitmapSampler;
    private int bitmapMatrix;
    private int externalPosition;
    private int externalTexCoord;
    private int externalSampler;
    private int externalMatrix;
    private final float[] transform = new float[16];
    private final AtomicBoolean newCameraFrame = new AtomicBoolean(false);
    private final FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private long uploadedVersion = Long.MIN_VALUE;
    private boolean hasCameraFrame;
    private int consecutiveRenderFailures;

    SurfaceFramePump(Surface outputSurface, int width, int height) {
        this(outputSurface, width, height, false);
    }

    SurfaceFramePump(Surface outputSurface, int width, int height, boolean ownsOutputSurface) {
        this.outputSurface = outputSurface;
        this.ownsOutputSurface = ownsOutputSurface;
        this.width = Math.max(2, width & ~1);
        this.height = Math.max(2, height & ~1);
        thread = new HandlerThread("OpenLens-EGL-" + Integer.toHexString(System.identityHashCode(outputSurface)));
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::initializeOnThread);
    }

    boolean awaitReady(long timeoutMs) {
        try {
            return ready.await(timeoutMs, TimeUnit.MILLISECONDS) && initialized && cameraSurface != null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    Surface getCameraSurface() {
        return cameraSurface;
    }

    SurfaceTexture getCameraSurfaceTexture() {
        return cameraTexture;
    }

    private void initializeOnThread() {
        try {
            vertexBuffer.put(VERTICES).position(0);
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("EGL display unavailable");
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) throw new IllegalStateException("EGL initialization failed");
            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw new IllegalStateException("No suitable EGL config");
            }
            config = configs[0];
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("EGL context creation failed");
            int[] pbufferAttributes = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, pbufferAttributes, 0);
            if (pbuffer == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL pbuffer creation failed");
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) throw new IllegalStateException("EGL make-current failed");

            int[] textureIds = new int[1];
            GLES20.glGenTextures(1, textureIds, 0);
            cameraTextureId = textureIds[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            cameraTexture = new SurfaceTexture(cameraTextureId);
            CameraSurfaceRouter.markInternalTexture(cameraTexture);
            cameraTexture.setDefaultBufferSize(width, height);
            cameraTexture.setOnFrameAvailableListener(surfaceTexture -> newCameraFrame.set(true), handler);
            cameraSurface = new Surface(cameraTexture);

            int[] bitmapIds = new int[1];
            GLES20.glGenTextures(1, bitmapIds, 0);
            bitmapTextureId = bitmapIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            bitmapProgram = createProgram(
                    "attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uTexMatrix; varying vec2 vTexCoord; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }",
                    "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture; void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }");
            externalProgram = createProgram(
                    "#extension GL_OES_EGL_image_external : require\nattribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uTexMatrix; varying vec2 vTexCoord; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }",
                    "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES uTexture; void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }");
            bitmapPosition = GLES20.glGetAttribLocation(bitmapProgram, "aPosition");
            bitmapTexCoord = GLES20.glGetAttribLocation(bitmapProgram, "aTexCoord");
            bitmapSampler = GLES20.glGetUniformLocation(bitmapProgram, "uTexture");
            bitmapMatrix = GLES20.glGetUniformLocation(bitmapProgram, "uTexMatrix");
            externalPosition = GLES20.glGetAttribLocation(externalProgram, "aPosition");
            externalTexCoord = GLES20.glGetAttribLocation(externalProgram, "aTexCoord");
            externalSampler = GLES20.glGetUniformLocation(externalProgram, "uTexture");
            externalMatrix = GLES20.glGetUniformLocation(externalProgram, "uTexMatrix");

            outputEglSurface = EGL14.eglCreateWindowSurface(display, config, outputSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (outputEglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("Could not bind EGL to the app preview surface");
            initialized = true;
            ready.countDown();
            handler.post(frameTask);
        } catch (Throwable error) {
            initError = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.w(TAG, "Surface adapter unavailable; keeping physical camera path", error);
            cleanupEgl();
            ready.countDown();
            close();
        }
    }

    private final Runnable frameTask = new Runnable() {
        @Override public void run() {
            if (closed.get() || !initialized) return;
            try {
                if (newCameraFrame.getAndSet(false)) {
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
                    try {
                        cameraTexture.updateTexImage();
                        cameraTexture.getTransformMatrix(transform);
                        hasCameraFrame = true;
                    } catch (Throwable error) {
                        Log.d(TAG, "Camera texture update skipped", error);
                    }
                }
                EGL14.eglMakeCurrent(display, outputEglSurface, outputEglSurface, context);
                int[] viewport = new int[2];
                EGL14.eglQuerySurface(display, outputEglSurface, EGL14.EGL_WIDTH, viewport, 0);
                EGL14.eglQuerySurface(display, outputEglSurface, EGL14.EGL_HEIGHT, viewport, 1);
                GLES20.glViewport(0, 0, Math.max(1, viewport[0]), Math.max(1, viewport[1]));
                boolean renderedMedia = FrameEngine.isEnabled() && FrameEngine.withCurrentBitmap((bitmap, version) -> {
                    if (bitmap == null || bitmap.isRecycled()) return false;
                    if (version != uploadedVersion) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                        uploadedVersion = version;
                    }
                    drawBitmap();
                    return true;
                });
                if (!renderedMedia && hasCameraFrame) drawCameraTexture();
                else if (!renderedMedia) {
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                }
                if (!EGL14.eglSwapBuffers(display, outputEglSurface)) {
                    throw new IllegalStateException("EGL swap failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                }
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
                consecutiveRenderFailures = 0;
            } catch (Throwable error) {
                consecutiveRenderFailures++;
                if (consecutiveRenderFailures == 1 || consecutiveRenderFailures % 30 == 0) {
                    Log.w(TAG, "Preview render failed; retaining camera sink and retrying (failure " +
                            consecutiveRenderFailures + ")", error);
                }
                // Do not close the camera-facing Surface on a transient EGL/output error.
                // The camera can continue producing and a later frame may recover the route.
                try {
                    if (display != EGL14.EGL_NO_DISPLAY && pbuffer != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
                    }
                } catch (Throwable ignored) { }
                long retryDelay = Math.min(1000L, FRAME_INTERVAL_MS * Math.max(2, consecutiveRenderFailures));
                if (!closed.get()) handler.postDelayed(this, retryDelay);
                return;
            }
            if (!closed.get()) handler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    private void drawBitmap() {
        GLES20.glUseProgram(bitmapProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId);
        GLES20.glUniform1i(bitmapSampler, 0);
        float[] identity = new float[16];
        android.opengl.Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(bitmapMatrix, 1, false, identity, 0);
        drawQuad(bitmapPosition, bitmapTexCoord);
    }

    private void drawCameraTexture() {
        GLES20.glUseProgram(externalProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(externalSampler, 0);
        GLES20.glUniformMatrix4fv(externalMatrix, 1, false, transform, 0);
        drawQuad(externalPosition, externalTexCoord);
    }

    private void drawQuad(int positionLocation, int texCoordLocation) {
        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        vertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texCoordLocation);
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionLocation);
        GLES20.glDisableVertexAttribArray(texCoordLocation);
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            String message = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("GL program link failed: " + message);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("GL shader compile failed: " + message);
        }
        return shader;
    }

    private void cleanupEgl() {
        try { if (cameraSurface != null) cameraSurface.release(); } catch (Throwable ignored) { }
        cameraSurface = null;
        try { if (cameraTexture != null) cameraTexture.release(); } catch (Throwable ignored) { }
        cameraTexture = null;
        try { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); } catch (Throwable ignored) { }
        try { if (display != EGL14.EGL_NO_DISPLAY && outputEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, outputEglSurface); } catch (Throwable ignored) { }
        outputEglSurface = EGL14.EGL_NO_SURFACE;
        try { if (display != EGL14.EGL_NO_DISPLAY && pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer); } catch (Throwable ignored) { }
        pbuffer = EGL14.EGL_NO_SURFACE;
        try { if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context); } catch (Throwable ignored) { }
        context = EGL14.EGL_NO_CONTEXT;
        try { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display); } catch (Throwable ignored) { }
        display = EGL14.EGL_NO_DISPLAY;
        if (ownsOutputSurface) {
            try { outputSurface.release(); } catch (Throwable ignored) { }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (handler != null) handler.post(() -> {
                cleanupEgl();
                thread.quitSafely();
            });
        } catch (Throwable ignored) {
            thread.quitSafely();
        }
    }

    String getInitError() { return initError; }
}

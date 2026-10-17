package com.jsux.openlens;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/** Tracks app-owned preview outputs and replaces only surfaces that can be safely mirrored. */
final class CameraSurfaceRouter {
    private static final String TAG = "OpenLens/SurfaceRouter";
    private static final Object LOCK = new Object();
    private static final Map<Surface, SurfaceInfo> SURFACES = new WeakHashMap<>();
    private static final Map<SurfaceTexture, SurfaceInfo> TEXTURES = new WeakHashMap<>();
    private static final Map<SurfaceHolder, SurfaceInfo> HOLDERS = new WeakHashMap<>();
    private static final Map<Surface, SurfaceFramePump> ROUTES = new IdentityHashMap<>();
    private static final Map<Surface, Set<Object>> ROUTE_OWNERS = new IdentityHashMap<>();
    private static final Map<Object, Set<Surface>> OWNER_SURFACES = new IdentityHashMap<>();
    private static final Map<Object, Object> OWNER_PARENT = new IdentityHashMap<>();
    private static final Set<Surface> INTERNAL_SURFACES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<SurfaceTexture> INTERNAL_TEXTURES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Method> INSTALLED_HOLDER_HOOKS = new HashSet<>();
    private static boolean baseHooksInstalled;

    private CameraSurfaceRouter() { }

    static void installHooks(OpenLensModule module) {
        synchronized (LOCK) {
            if (baseHooksInstalled) return;
            baseHooksInstalled = true;
        }
        try {
            Method setDefaultBufferSize = SurfaceTexture.class.getDeclaredMethod("setDefaultBufferSize", int.class, int.class);
            module.hook(setDefaultBufferSize).setId("surface_texture_size").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                registerTextureSize((SurfaceTexture) chain.getThisObject(), (Integer) chain.getArg(0), (Integer) chain.getArg(1));
                return result;
            });
        } catch (Throwable error) {
            Log.w(TAG, "SurfaceTexture size tracking is unavailable", error);
        }

        for (Constructor<?> constructor : Surface.class.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 1 || parameters[0] != SurfaceTexture.class) continue;
            try {
                module.hook(constructor).setId("surface_texture_surface_ctor").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Surface && chain.getArg(0) instanceof SurfaceTexture) {
                        registerSurfaceTexturePair((Surface) chain.getThisObject(), (SurfaceTexture) chain.getArg(0));
                    }
                    return result;
                });
            } catch (Throwable error) {
                Log.w(TAG, "Could not hook Surface(SurfaceTexture)", error);
            }
        }

        try {
            Method release = Surface.class.getDeclaredMethod("release");
            module.hook(release).setId("surface_release").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                releaseSurface((Surface) chain.getThisObject());
                return chain.proceed();
            });
        } catch (Throwable error) {
            Log.w(TAG, "Surface release hook unavailable", error);
        }

        try {
            Method release = SurfaceTexture.class.getDeclaredMethod("release");
            module.hook(release).setId("surface_texture_release").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                releaseTexture((SurfaceTexture) chain.getThisObject());
                return chain.proceed();
            });
        } catch (Throwable error) {
            Log.w(TAG, "SurfaceTexture release hook unavailable", error);
        }

        try {
            Method getHolder = SurfaceView.class.getDeclaredMethod("getHolder");
            module.hook(getHolder).setId("surface_view_holder_tracking").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof SurfaceHolder) trackHolder(module, (SurfaceHolder) result);
                return result;
            });
        } catch (Throwable error) {
            Log.w(TAG, "SurfaceView holder tracking is unavailable", error);
        }
    }

    private static void trackHolder(OpenLensModule module, SurfaceHolder holder) {
        if (holder == null) return;
        synchronized (LOCK) {
            if (HOLDERS.containsKey(holder)) return;
            HOLDERS.put(holder, new SurfaceInfo(null, 0, 0));
        }
        installHolderMethodHook(module, holder.getClass(), "getSurface", 0, chain -> {
            Object result = chain.proceed();
            if (result instanceof Surface) registerHolderSurface(holder, (Surface) result);
            return result;
        });
        installHolderMethodHook(module, holder.getClass(), "setFixedSize", 2, chain -> {
            int width = (Integer) chain.getArg(0);
            int height = (Integer) chain.getArg(1);
            Object result = chain.proceed();
            registerHolderSize(holder, width, height);
            return result;
        });
        installHolderMethodHook(module, holder.getClass(), "getSurfaceFrame", 0, chain -> {
            Object result = chain.proceed();
            if (result instanceof Rect) {
                Rect rectangle = (Rect) result;
                if (rectangle.width() > 1 && rectangle.height() > 1) registerHolderSize(holder, rectangle.width(), rectangle.height());
            }
            return result;
        });
        try {
            Surface surface = holder.getSurface();
            if (surface != null) registerHolderSurface(holder, surface);
            Rect rectangle = holder.getSurfaceFrame();
            if (rectangle != null && rectangle.width() > 1 && rectangle.height() > 1) registerHolderSize(holder, rectangle.width(), rectangle.height());
        } catch (Throwable ignored) { }
    }

    private interface Interceptor {
        Object intercept(XposedInterface.Chain chain) throws Throwable;
    }

    private static void installHolderMethodHook(OpenLensModule module, Class<?> klass, String name, int count, Interceptor interceptor) {
        for (Method method : klass.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != count) continue;
            synchronized (LOCK) {
                if (!INSTALLED_HOLDER_HOOKS.add(method)) continue;
            }
            try {
                module.hook(method).setId("holder_" + name + "_" + Integer.toHexString(System.identityHashCode(method)))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(interceptor::intercept);
            } catch (Throwable error) {
                Log.d(TAG, "Holder method hook skipped: " + method, error);
            }
        }
    }

    static void markInternalTexture(SurfaceTexture texture) {
        if (texture == null) return;
        synchronized (LOCK) { INTERNAL_TEXTURES.add(texture); }
    }

    private static void registerTextureSize(SurfaceTexture texture, int width, int height) {
        if (texture == null || width < 2 || height < 2) return;
        synchronized (LOCK) {
            if (INTERNAL_TEXTURES.contains(texture)) return;
            SurfaceInfo info = TEXTURES.get(texture);
            if (info == null) info = new SurfaceInfo(null, width, height);
            else { info.width = width; info.height = height; }
            TEXTURES.put(texture, info);
            for (Map.Entry<Surface, SurfaceInfo> entry : SURFACES.entrySet()) {
                if (entry.getValue().texture == texture) {
                    entry.getValue().width = width;
                    entry.getValue().height = height;
                }
            }
        }
    }

    private static void registerSurfaceTexturePair(Surface surface, SurfaceTexture texture) {
        synchronized (LOCK) {
            if (INTERNAL_TEXTURES.contains(texture)) {
                INTERNAL_SURFACES.add(surface);
                return;
            }
            SurfaceInfo textureInfo = TEXTURES.get(texture);
            int width = textureInfo == null ? 0 : textureInfo.width;
            int height = textureInfo == null ? 0 : textureInfo.height;
            SURFACES.put(surface, new SurfaceInfo(texture, width, height));
        }
    }

    private static void registerHolderSurface(SurfaceHolder holder, Surface surface) {
        if (holder == null || surface == null) return;
        int width = 0;
        int height = 0;
        synchronized (LOCK) {
            SurfaceInfo holderInfo = HOLDERS.get(holder);
            if (holderInfo != null) { width = holderInfo.width; height = holderInfo.height; }
        }
        if (width < 2 || height < 2) {
            try {
                Rect frame = holder.getSurfaceFrame();
                if (frame != null) { width = frame.width(); height = frame.height(); }
            } catch (Throwable ignored) { }
        }
        synchronized (LOCK) {
            SURFACES.put(surface, new SurfaceInfo(null, width, height));
            SurfaceInfo holderInfo = HOLDERS.get(holder);
            if (holderInfo == null) holderInfo = new SurfaceInfo(null, width, height);
            holderInfo.width = width;
            holderInfo.height = height;
            HOLDERS.put(holder, holderInfo);
        }
    }

    private static void registerHolderSize(SurfaceHolder holder, int width, int height) {
        if (holder == null || width < 2 || height < 2) return;
        synchronized (LOCK) {
            SurfaceInfo info = HOLDERS.get(holder);
            if (info == null) info = new SurfaceInfo(null, width, height);
            info.width = width;
            info.height = height;
            HOLDERS.put(holder, info);
            try {
                Surface surface = holder.getSurface();
                SurfaceInfo surfaceInfo = SURFACES.get(surface);
                if (surfaceInfo == null) surfaceInfo = new SurfaceInfo(null, width, height);
                surfaceInfo.width = width;
                surfaceInfo.height = height;
                SURFACES.put(surface, surfaceInfo);
            } catch (Throwable ignored) { }
        }
    }

    static Surface prepareSurface(Surface original) {
        return prepareSurface(original, 0, 0, null);
    }

    static Surface prepareSurface(Surface original, int fallbackWidth, int fallbackHeight) {
        return prepareSurface(original, fallbackWidth, fallbackHeight, null);
    }

    static Surface prepareSurface(Surface original, int fallbackWidth, int fallbackHeight, Object owner) {
        if (original == null) return original;
        synchronized (LOCK) {
            if (INTERNAL_SURFACES.contains(original)) return original;
            SurfaceFramePump existing = ROUTES.get(original);
            if (existing != null && existing.getCameraSurface() != null) {
                registerOwnerLocked(original, owner);
                return existing.getCameraSurface();
            }
        }
        if (!FrameEngine.isEnabled() || !FrameEngine.hasSource()) return original;
        int width = fallbackWidth;
        int height = fallbackHeight;
        if (width < 2 || height < 2) {
            synchronized (LOCK) {
                SurfaceInfo info = SURFACES.get(original);
                if (info != null && info.width > 1 && info.height > 1) {
                    width = info.width;
                    height = info.height;
                }
            }
        }
        if (width < 2 || height < 2) return original;
        return createRoute(original, width, height, false, owner);
    }

    private static Surface createRoute(Surface original, int width, int height, boolean ownsOutputSurface, Object owner) {
        SurfaceFramePump pump = new SurfaceFramePump(original, width, height, ownsOutputSurface);
        if (!pump.awaitReady(1800L)) {
            pump.close();
            Log.d(TAG, "Surface left unchanged; adapter initialization failed: " + pump.getInitError());
            return original;
        }
        synchronized (LOCK) {
            SurfaceFramePump race = ROUTES.get(original);
            if (race != null) {
                registerOwnerLocked(original, owner);
                pump.close();
                return race.getCameraSurface();
            }
            ROUTES.put(original, pump);
            registerOwnerLocked(original, owner);
        }
        Log.i(TAG, "Routed preview surface at " + width + "x" + height);
        return pump.getCameraSurface();
    }

    static SurfaceTexture prepareTexture(SurfaceTexture original, int width, int height) {
        return prepareTexture(original, width, height, null);
    }

    static SurfaceTexture prepareTexture(SurfaceTexture original, int width, int height, Object owner) {
        if (original == null) return original;
        synchronized (LOCK) {
            if (INTERNAL_TEXTURES.contains(original)) return original;
            SurfaceInfo existing = TEXTURES.get(original);
            if (existing != null && existing.pump != null && existing.pump.getCameraSurfaceTexture() != null) {
                attachOwnerToPumpLocked(existing.pump, owner);
                return existing.pump.getCameraSurfaceTexture();
            }
        }
        if (!FrameEngine.isEnabled() || !FrameEngine.hasSource()) return original;
        Surface target;
        try { target = new Surface(original); }
        catch (Throwable error) { return original; }
        if (width < 2 || height < 2) {
            synchronized (LOCK) {
                SurfaceInfo info = TEXTURES.get(original);
                if (info != null) { width = info.width; height = info.height; }
            }
        }
        if (width < 2 || height < 2) { target.release(); return original; }
        Surface fake = createRoute(target, width, height, true, owner);
        if (fake == target) { target.release(); return original; }
        synchronized (LOCK) {
            SurfaceFramePump pump = ROUTES.get(target);
            if (pump != null) TEXTURES.put(original, new SurfaceInfo(null, width, height, pump));
        }
        return fake == null ? original : findRoutedTexture(target, original);
    }

    private static SurfaceTexture findRoutedTexture(Surface original, SurfaceTexture fallback) {
        synchronized (LOCK) {
            SurfaceFramePump pump = ROUTES.get(original);
            return pump == null || pump.getCameraSurfaceTexture() == null ? fallback : pump.getCameraSurfaceTexture();
        }
    }

    static SurfaceHolder prepareHolder(SurfaceHolder holder, int fallbackWidth, int fallbackHeight) {
        return prepareHolder(holder, fallbackWidth, fallbackHeight, null);
    }

    static SurfaceHolder prepareHolder(SurfaceHolder holder, int fallbackWidth, int fallbackHeight, Object owner) {
        if (holder == null) return null;
        Surface original;
        try { original = holder.getSurface(); } catch (Throwable error) { return holder; }
        Surface replacement = prepareSurface(original, fallbackWidth, fallbackHeight, owner);
        if (replacement == original) return holder;
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getSurface".equals(name)) return replacement;
            if ("toString".equals(name)) return "OpenLensSurfaceHolder(" + holder + ")";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            try { return method.invoke(holder, args); }
            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        };
        try {
            return (SurfaceHolder) Proxy.newProxyInstance(SurfaceHolder.class.getClassLoader(), new Class<?>[]{SurfaceHolder.class}, handler);
        } catch (Throwable error) {
            // If the producer cannot receive the proxy, tear down the route rather than
            // leaving EGL and the physical camera competing to write to the same Surface.
            releaseOwnerSurface(owner, original);
            Log.w(TAG, "Could not proxy SurfaceHolder; keeping the physical camera path", error);
            return holder;
        }
    }

    static Surface mapToCameraSurface(Surface appSurface) {
        synchronized (LOCK) {
            if (INTERNAL_SURFACES.contains(appSurface)) return appSurface;
            SurfaceFramePump pump = ROUTES.get(appSurface);
            return pump == null ? appSurface : pump.getCameraSurface();
        }
    }

    static boolean hasRoute(Surface surface) {
        synchronized (LOCK) { return ROUTES.containsKey(surface); }
    }

    static void releaseSurface(Surface surface) {
        SurfaceFramePump pump;
        synchronized (LOCK) {
            pump = ROUTES.remove(surface);
            SURFACES.remove(surface);
            removeRouteOwnersLocked(surface);
            if (pump != null) removePumpReferenceLocked(pump);
            INTERNAL_SURFACES.remove(surface);
        }
        if (pump != null) pump.close();
    }

    /** Detach one failed output route without disturbing other sessions using the same surface. */
    static void releaseOwnerSurface(Object owner, Surface surface) {
        if (surface == null) return;
        SurfaceFramePump pump = null;
        synchronized (LOCK) {
            Set<Object> owners = ROUTE_OWNERS.get(surface);
            if (owners != null && owner != null) {
                owners.remove(owner);
                Set<Surface> surfaces = OWNER_SURFACES.get(owner);
                if (surfaces != null) {
                    surfaces.remove(surface);
                    if (surfaces.isEmpty()) OWNER_SURFACES.remove(owner);
                }
                if (owners.isEmpty()) {
                    ROUTE_OWNERS.remove(surface);
                    pump = ROUTES.remove(surface);
                    SURFACES.remove(surface);
                    if (pump != null) removePumpReferenceLocked(pump);
                }
            } else if (owner == null) {
                pump = ROUTES.remove(surface);
                SURFACES.remove(surface);
                removeRouteOwnersLocked(surface);
                if (pump != null) removePumpReferenceLocked(pump);
            }
        }
        if (pump != null) pump.close();
    }

    static void linkOwner(Object child, Object parent) {
        if (child == null || parent == null || child == parent) return;
        synchronized (LOCK) { OWNER_PARENT.put(child, parent); }
    }

    /** Move all routes from a callback owner to the configured camera session. */
    static void transferOwner(Object oldOwner, Object newOwner) {
        if (oldOwner == null || newOwner == null || oldOwner == newOwner) return;
        synchronized (LOCK) {
            Set<Surface> surfaces = OWNER_SURFACES.remove(oldOwner);
            if (surfaces != null) {
                for (Surface surface : surfaces) {
                    Set<Object> owners = ROUTE_OWNERS.get(surface);
                    if (owners != null) owners.remove(oldOwner);
                    registerOwnerLocked(surface, newOwner);
                }
            }
            Object parent = OWNER_PARENT.remove(oldOwner);
            if (parent != null) OWNER_PARENT.put(newOwner, parent);
            for (Map.Entry<Object, Object> entry : OWNER_PARENT.entrySet()) {
                if (entry.getValue() == oldOwner) entry.setValue(newOwner);
            }
        }
    }

    /** Release only routes belonging to this camera/session, plus its child sessions. */
    static void releaseOwner(Object owner) {
        if (owner == null) return;
        Set<Object> ownersToRelease = Collections.newSetFromMap(new IdentityHashMap<>());
        ownersToRelease.add(owner);
        Set<SurfaceFramePump> pumpsToClose = Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (LOCK) {
            boolean added;
            do {
                added = false;
                for (Map.Entry<Object, Object> entry : OWNER_PARENT.entrySet()) {
                    if (ownersToRelease.contains(entry.getValue()) && ownersToRelease.add(entry.getKey())) added = true;
                }
            } while (added);
            for (Object currentOwner : ownersToRelease) {
                OWNER_PARENT.remove(currentOwner);
                Set<Surface> surfaces = OWNER_SURFACES.remove(currentOwner);
                if (surfaces == null) continue;
                for (Surface surface : surfaces) {
                    Set<Object> owners = ROUTE_OWNERS.get(surface);
                    if (owners == null) continue;
                    owners.remove(currentOwner);
                    if (owners.isEmpty()) {
                        ROUTE_OWNERS.remove(surface);
                        SurfaceFramePump pump = ROUTES.remove(surface);
                        SURFACES.remove(surface);
                        if (pump != null) {
                            pumpsToClose.add(pump);
                            removePumpReferenceLocked(pump);
                        }
                    }
                }
            }
        }
        for (SurfaceFramePump pump : pumpsToClose) pump.close();
    }

    private static void registerOwnerLocked(Surface surface, Object owner) {
        if (surface == null || owner == null) return;
        Set<Object> owners = ROUTE_OWNERS.get(surface);
        if (owners == null) {
            owners = Collections.newSetFromMap(new IdentityHashMap<>());
            ROUTE_OWNERS.put(surface, owners);
        }
        owners.add(owner);
        Set<Surface> surfaces = OWNER_SURFACES.get(owner);
        if (surfaces == null) {
            surfaces = Collections.newSetFromMap(new IdentityHashMap<>());
            OWNER_SURFACES.put(owner, surfaces);
        }
        surfaces.add(surface);
    }

    private static void removeRouteOwnersLocked(Surface surface) {
        Set<Object> owners = ROUTE_OWNERS.remove(surface);
        if (owners == null) return;
        for (Object owner : owners) {
            Set<Surface> surfaces = OWNER_SURFACES.get(owner);
            if (surfaces != null) {
                surfaces.remove(surface);
                if (surfaces.isEmpty()) OWNER_SURFACES.remove(owner);
            }
        }
    }

    private static void attachOwnerToPumpLocked(SurfaceFramePump pump, Object owner) {
        if (pump == null || owner == null) return;
        for (Map.Entry<Surface, SurfaceFramePump> entry : ROUTES.entrySet()) {
            if (entry.getValue() == pump) {
                registerOwnerLocked(entry.getKey(), owner);
                return;
            }
        }
    }

    private static void removePumpReferenceLocked(SurfaceFramePump pump) {
        for (Map.Entry<SurfaceTexture, SurfaceInfo> entry : TEXTURES.entrySet()) {
            SurfaceInfo info = entry.getValue();
            if (info != null && info.pump == pump) entry.setValue(new SurfaceInfo(null, info.width, info.height));
        }
    }

    static void releaseTexture(SurfaceTexture texture) {
        Set<Surface> toRelease = Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (LOCK) {
            for (Map.Entry<Surface, SurfaceInfo> entry : SURFACES.entrySet()) {
                if (entry.getValue().texture == texture) toRelease.add(entry.getKey());
            }
            for (Map.Entry<Surface, SurfaceFramePump> entry : ROUTES.entrySet()) {
                SurfaceInfo info = SURFACES.get(entry.getKey());
                if ((info != null && info.texture == texture) || entry.getValue().getCameraSurfaceTexture() == texture) toRelease.add(entry.getKey());
            }
            TEXTURES.remove(texture);
            INTERNAL_TEXTURES.remove(texture);
        }
        for (Surface surface : toRelease) releaseSurface(surface);
    }

    static void releaseAll() {
        SurfaceFramePump[] pumps;
        synchronized (LOCK) {
            pumps = ROUTES.values().toArray(new SurfaceFramePump[0]);
            ROUTES.clear();
            ROUTE_OWNERS.clear();
            OWNER_SURFACES.clear();
            OWNER_PARENT.clear();
            for (Map.Entry<SurfaceTexture, SurfaceInfo> entry : TEXTURES.entrySet()) {
                SurfaceInfo info = entry.getValue();
                if (info.pump != null) entry.setValue(new SurfaceInfo(null, info.width, info.height));
            }
        }
        for (SurfaceFramePump pump : pumps) if (pump != null) pump.close();
    }

    static int[] previewSize(Camera camera) {
        try {
            Camera.Size size = camera.getParameters().getPreviewSize();
            if (size != null && size.width > 1 && size.height > 1) return new int[]{size.width, size.height};
        } catch (Throwable ignored) { }
        return new int[]{0, 0};
    }

    private static final class SurfaceInfo {
        final SurfaceTexture texture;
        int width;
        int height;
        final SurfaceFramePump pump;
        SurfaceInfo(SurfaceTexture texture, int width, int height) { this(texture, width, height, null); }
        SurfaceInfo(SurfaceTexture texture, int width, int height, SurfaceFramePump pump) {
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.pump = pump;
        }
    }
}

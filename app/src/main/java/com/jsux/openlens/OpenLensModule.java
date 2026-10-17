package com.jsux.openlens;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OpenLensModule extends XposedModule {
    private static final String TAG = "OpenLens";
    private static final Object HOOK_LOCK = new Object();
    private static final Set<Method> CAMERA2_HOOKS = new HashSet<>();
    private static final Set<Method> CALLBACK_HOOKS = new HashSet<>();
    private static final Set<Method> SESSION_CALLBACK_HOOKS = new HashSet<>();
    private static final Set<Method> CAMERA1_HOOKS = new HashSet<>();

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (ModuleContract.PACKAGE_NAME.equals(packageName)) return;

        Context packageContext = null;
        try {
            packageContext = createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
            FrameEngine.initialize(packageContext);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not initialize media IPC for " + packageName, error);
        }

        CameraSurfaceRouter.installHooks(this);
        CameraImageAdapter.install(this);
        installCamera1Hooks();
        installCamera2Hooks();
        log(Log.INFO, TAG, "Installed camera adapters in " + packageName + "; unsupported/native paths remain pass-through");
    }

    private void installCamera1Hooks() {
        for (Method method : Camera.class.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("setPreviewCallback") && !name.equals("setOneShotPreviewCallback") &&
                    !name.equals("setPreviewCallbackWithBuffer") && !name.equals("setPreviewTexture") &&
                    !name.equals("setPreviewDisplay") && !name.equals("takePicture") &&
                    !name.equals("release")) continue;
            if (!claimHook(CAMERA1_HOOKS, method)) continue;
            try {
                hook(method).setId(hookId("camera1", method))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> interceptCamera1(chain, method));
            } catch (Throwable error) {
                unclaimHook(CAMERA1_HOOKS, method);
                log(Log.WARN, TAG, "Camera1 method hook skipped: " + method, error);
            }
        }
    }

    private Object interceptCamera1(XposedInterface.Chain chain, Method method) throws Throwable {
        String name = method.getName();
        if ("release".equals(name)) {
            try { return chain.proceed(); }
            finally { CameraSurfaceRouter.releaseOwner(chain.getThisObject()); }
        }
        Object[] args = chain.getArgs().toArray();
        if ("setPreviewTexture".equals(name) && args.length > 0 && args[0] instanceof SurfaceTexture && FrameEngine.isEnabled()) {
            int[] size = CameraSurfaceRouter.previewSize((Camera) chain.getThisObject());
            SurfaceTexture replacement = CameraSurfaceRouter.prepareTexture((SurfaceTexture) args[0], size[0], size[1], chain.getThisObject());
            if (replacement != args[0]) args[0] = replacement;
            return chain.proceed(args);
        }
        if ("setPreviewDisplay".equals(name) && args.length > 0 && args[0] instanceof SurfaceHolder && FrameEngine.isEnabled()) {
            int[] size = CameraSurfaceRouter.previewSize((Camera) chain.getThisObject());
            SurfaceHolder replacement = CameraSurfaceRouter.prepareHolder((SurfaceHolder) args[0], size[0], size[1], chain.getThisObject());
            if (replacement != args[0]) args[0] = replacement;
            return chain.proceed(args);
        }
        if ("takePicture".equals(name) && FrameEngine.isEnabled()) {
            int lastPictureCallback = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Camera.PictureCallback) lastPictureCallback = i;
            }
            if (lastPictureCallback >= 0) args[lastPictureCallback] = wrapPictureCallback((Camera.PictureCallback) args[lastPictureCallback]);
            return chain.proceed(args);
        }
        if ("setPreviewCallback".equals(name) || "setOneShotPreviewCallback".equals(name) || "setPreviewCallbackWithBuffer".equals(name)) {
            if (args.length == 0 || !(args[0] instanceof Camera.PreviewCallback)) return chain.proceed();
            Camera.PreviewCallback original = (Camera.PreviewCallback) args[0];
            Camera.PreviewCallback wrapper = (data, camera) -> {
                try { FrameEngine.inject(data, camera); }
                catch (Throwable error) { Log.w(TAG, "Camera1 callback replacement failed; passing through", error); }
                original.onPreviewFrame(data, camera);
            };
            args[0] = wrapper;
            return chain.proceed(args);
        }
        return chain.proceed();
    }

    private Camera.PictureCallback wrapPictureCallback(Camera.PictureCallback original) {
        if (original == null) return null;
        return (data, camera) -> {
            byte[] replacement = FrameEngine.currentJpeg(4096, 94);
            original.onPictureTaken(replacement == null ? data : replacement, camera);
        };
    }

    private void installCamera2Hooks() {
        for (Method method : CameraManager.class.getDeclaredMethods()) {
            if (!"openCamera".equals(method.getName())) continue;
            boolean hasCallback = false;
            for (Class<?> type : method.getParameterTypes()) {
                if (CameraDevice.StateCallback.class.isAssignableFrom(type)) { hasCallback = true; break; }
            }
            if (!hasCallback || !claimHook(CAMERA2_HOOKS, method)) continue;
            try {
                hook(method).setId(hookId("camera_manager_open", method))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                            for (Object arg : chain.getArgs()) {
                                if (arg instanceof CameraDevice.StateCallback) hookDeviceCallback((CameraDevice.StateCallback) arg);
                            }
                            return chain.proceed();
                        });
            } catch (Throwable error) {
                unclaimHook(CAMERA2_HOOKS, method);
                log(Log.DEBUG, TAG, "CameraManager.openCamera overload skipped: " + method, error);
            }
        }

        for (String targetMethodName : new String[]{"addTarget", "removeTarget"}) {
            try {
                Method targetMethod = CaptureRequest.Builder.class.getDeclaredMethod(targetMethodName, Surface.class);
                if (!claimHook(CAMERA2_HOOKS, targetMethod)) continue;
                try {
                    hook(targetMethod).setId(hookId("camera2_builder_" + targetMethodName, targetMethod))
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                                Surface original = (Surface) chain.getArg(0);
                                Surface replacement = CameraSurfaceRouter.mapToCameraSurface(original);
                                if (replacement == original) return chain.proceed();
                                return chain.proceed(new Object[]{replacement});
                            });
                } catch (Throwable error) {
                    unclaimHook(CAMERA2_HOOKS, targetMethod);
                    throw error;
                }
            } catch (Throwable error) {
                log(Log.WARN, TAG, "CaptureRequest.Builder." + targetMethodName + " hook unavailable", error);
            }
        }

        try {
            Method build = CaptureRequest.Builder.class.getDeclaredMethod("build");
            if (claimHook(CAMERA2_HOOKS, build)) {
                try {
                    hook(build).setId(hookId("camera2_builder_build", build))
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                                rewriteBuilderTargets(chain.getThisObject());
                                return chain.proceed();
                            });
                } catch (Throwable error) {
                    unclaimHook(CAMERA2_HOOKS, build);
                    throw error;
                }
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "CaptureRequest.Builder.build hook unavailable", error);
        }
    }

    private void hookDeviceCallback(CameraDevice.StateCallback callback) {
        Method onOpened = findMethod(callback.getClass(), "onOpened", CameraDevice.class);
        if (onOpened == null || onOpened.getReturnType() != void.class) return;
        if (!claimHook(CALLBACK_HOOKS, onOpened)) return;
        try {
            hook(onOpened).setId(hookId("camera2_state_on_opened", onOpened))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object device = chain.getArg(0);
                        if (device != null) installDeviceHooks(device.getClass());
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            synchronized (HOOK_LOCK) { CALLBACK_HOOKS.remove(onOpened); }
            log(Log.DEBUG, TAG, "CameraDevice callback hook skipped: " + onOpened, error);
        }
    }

    private void installDeviceHooks(Class<?> deviceClass) {
        for (Class<?> type = deviceClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.startsWith("createCaptureSession") && !name.startsWith("createReprocessableCaptureSession") &&
                        !name.equals("createConstrainedHighSpeedCaptureSession") && !name.equals("close")) continue;
                if (Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
                if (!claimHook(CAMERA2_HOOKS, method)) continue;
                try {
                    hook(method).setId(hookId("camera2_session", method))
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                                if ("close".equals(method.getName())) {
                                    try { return chain.proceed(); }
                                    finally { CameraSurfaceRouter.releaseOwner(chain.getThisObject()); }
                                }
                                return rewriteSessionCall(chain, method);
                            });
                } catch (Throwable error) {
                    unclaimHook(CAMERA2_HOOKS, method);
                    log(Log.DEBUG, TAG, "Camera2 session method skipped: " + method, error);
                }
            }
        }
    }

    private void hookSessionCallback(CameraCaptureSession.StateCallback callback) {
        if (callback == null) return;
        for (String callbackName : new String[]{"onConfigured", "onConfigureFailed", "onClosed"}) {
            Method callbackMethod = findMethod(callback.getClass(), callbackName, CameraCaptureSession.class);
            if (callbackMethod == null || callbackMethod.getReturnType() != void.class) continue;
            if (!claimHook(SESSION_CALLBACK_HOOKS, callbackMethod)) continue;
            try {
                hook(callbackMethod).setId(hookId("camera2_session_" + callbackName, callbackMethod))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                            Object callbackOwner = chain.getThisObject();
                            Object session = chain.getArg(0);
                            if ("onConfigured".equals(callbackName) && session instanceof CameraCaptureSession) {
                                CameraSurfaceRouter.transferOwner(callbackOwner, session);
                                installSessionHooks(session.getClass());
                            } else if ("onConfigureFailed".equals(callbackName)) {
                                CameraSurfaceRouter.releaseOwner(callbackOwner);
                            } else if ("onClosed".equals(callbackName) && session != null) {
                                CameraSurfaceRouter.releaseOwner(callbackOwner);
                                CameraSurfaceRouter.releaseOwner(session);
                            }
                            return chain.proceed();
                        });
            } catch (Throwable error) {
                unclaimHook(SESSION_CALLBACK_HOOKS, callbackMethod);
                log(Log.DEBUG, TAG, "CameraCaptureSession callback hook skipped: " + callbackMethod, error);
            }
        }
    }

    private void installSessionHooks(Class<?> sessionClass) {
        for (Class<?> type = sessionClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                boolean outputMethod = name.equals("finalizeOutputConfigurations") || name.equals("updateOutputConfigurations") ||
                        name.equals("prepare") || name.equals("tearDown");
                boolean requestMethod = name.equals("capture") || name.equals("captureBurst") ||
                        name.equals("setRepeatingRequest") || name.equals("setRepeatingBurst") ||
                        name.equals("captureSingleRequest") || name.equals("captureBurstRequests") ||
                        name.equals("setSingleRepeatingRequest") || name.equals("setRepeatingRequests");
                boolean closeMethod = name.equals("close");
                if (!outputMethod && !requestMethod && !closeMethod) continue;
                if (Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
                if (!claimHook(CAMERA2_HOOKS, method)) continue;
                try {
                    hook(method).setId(hookId("camera2_session_output", method))
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                                if (closeMethod) {
                                    try { return chain.proceed(); }
                                    finally { CameraSurfaceRouter.releaseOwner(chain.getThisObject()); }
                                }
                                Object[] args = chain.getArgs().toArray();
                                if (requestMethod) {
                                    rewriteCaptureRequests(args);
                                    return chain.proceed();
                                }
                                boolean changed = false;
                                for (int i = 0; i < args.length; i++) {
                                    Object arg = args[i];
                                    if (arg instanceof Surface) {
                                        Surface original = (Surface) arg;
                                        Surface replacement = CameraSurfaceRouter.mapToCameraSurface(original);
                                        if (replacement != original) { args[i] = replacement; changed = true; }
                                    } else if (arg instanceof List<?>) {
                                        List<?> values = (List<?>) arg;
                                        if (!values.isEmpty() && values.get(0) != null &&
                                                values.get(0).getClass().getName().equals("android.hardware.camera2.params.OutputConfiguration")) {
                                            List<?> replacement = rewriteOutputConfigurations(values, chain.getThisObject());
                                            if (replacement != values) { args[i] = replacement; changed = true; }
                                        }
                                    }
                                }
                                return changed ? chain.proceed(args) : chain.proceed();
                            });
                } catch (Throwable error) {
                    unclaimHook(CAMERA2_HOOKS, method);
                    log(Log.DEBUG, TAG, "Camera session output hook skipped: " + method, error);
                }
            }
        }
    }

    /** Last-resort request interception catches apps that built CaptureRequest before session routing. */
    private void rewriteCaptureRequests(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof CaptureRequest) rewriteBuiltRequestTargets(arg);
            else if (arg instanceof List<?>) {
                for (Object request : (List<?>) arg) if (request instanceof CaptureRequest) rewriteBuiltRequestTargets(request);
            }
        }
    }

    private void rewriteBuiltRequestTargets(Object request) {
        if (request == null) return;
        for (Class<?> type = request.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(request);
                    if (!(value instanceof Set<?>)) continue;
                    Set<?> originalSet = (Set<?>) value;
                    if (originalSet.isEmpty()) continue;
                    List<Surface> originals = new ArrayList<>();
                    boolean surfaceOnly = true;
                    for (Object item : originalSet) {
                        if (!(item instanceof Surface)) { surfaceOnly = false; break; }
                        originals.add((Surface) item);
                    }
                    if (!surfaceOnly) continue;
                    List<Surface> mapped = new ArrayList<>(originals.size());
                    boolean changed = false;
                    for (Surface original : originals) {
                        Surface replacement = CameraSurfaceRouter.mapToCameraSurface(original);
                        mapped.add(replacement);
                        changed |= replacement != original;
                    }
                    if (!changed) continue;
                    @SuppressWarnings("unchecked") Set<Object> mutableTargets = (Set<Object>) originalSet;
                    try {
                        mutableTargets.clear();
                        mutableTargets.addAll(mapped);
                    } catch (Throwable mutationError) {
                        try {
                            mutableTargets.clear();
                            mutableTargets.addAll(originals);
                        } catch (Throwable rollbackError) {
                            mutationError.addSuppressed(rollbackError);
                        }
                        log(Log.DEBUG, TAG, "Built CaptureRequest target set is immutable; request may keep original surfaces", mutationError);
                    }
                    return;
                } catch (Throwable error) {
                    log(Log.DEBUG, TAG, "Could not inspect built CaptureRequest field " + field.getName(), error);
                }
            }
        }
    }

    /**
     * CaptureRequest.Builder does not expose its target set in the public API. Some apps add
     * targets before creating their capture session, so the earlier addTarget hook cannot know
     * the replacement surface yet. Before build(), find the builder's Surface set and move any
     * newly routable target through the public removeTarget/addTarget API.
     */
    private void rewriteBuilderTargets(Object builder) {
        if (builder == null) return;
        Method removeTarget = findMethod(builder.getClass(), "removeTarget", Surface.class);
        Method addTarget = findMethod(builder.getClass(), "addTarget", Surface.class);
        if (removeTarget == null || addTarget == null) return;
        for (Class<?> type = builder.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(builder);
                    if (!(value instanceof Set<?>)) continue;
                    Set<?> surfaces = (Set<?>) value;
                    if (surfaces.isEmpty()) continue;
                    List<Surface> targets = new ArrayList<>();
                    boolean surfaceOnly = true;
                    for (Object target : surfaces) {
                        if (!(target instanceof Surface)) { surfaceOnly = false; break; }
                        targets.add((Surface) target);
                    }
                    String fieldName = field.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!surfaceOnly || !(fieldName.contains("surface") || fieldName.contains("target"))) continue;
                    for (Surface original : targets) {
                        Surface replacement = CameraSurfaceRouter.mapToCameraSurface(original);
                        if (replacement == original) continue;
                        try {
                            removeTarget.invoke(builder, original);
                            addTarget.invoke(builder, replacement);
                        } catch (Throwable error) {
                            log(Log.DEBUG, TAG, "A capture request target could not be remapped", error);
                        }
                    }
                    return;
                } catch (Throwable error) {
                    // Hidden field access can be blocked on some releases; other candidate fields may work.
                    log(Log.DEBUG, TAG, "Could not inspect capture request builder field " + field.getName(), error);
                }
            }
        }
    }

    private static boolean claimHook(Set<Method> registry, Method method) {
        synchronized (HOOK_LOCK) { return registry.add(method); }
    }

    private static void unclaimHook(Set<Method> registry, Method method) {
        synchronized (HOOK_LOCK) { registry.remove(method); }
    }

    private static String hookId(String prefix, Method method) {
        StringBuilder signature = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) signature.append(parameter.getName()).append(';');
        signature.append(')').append(method.getReturnType().getName());
        String safe = signature.toString().replaceAll("[^A-Za-z0-9_]+", "_");
        if (safe.length() > 64) safe = safe.substring(0, 42) + "_" + Integer.toHexString(signature.toString().hashCode());
        return prefix + "_" + safe;
    }

    private Object rewriteSessionCall(XposedInterface.Chain chain, Method method) throws Throwable {
        Object[] args = chain.getArgs().toArray();
        boolean changed = false;
        Object parentOwner = chain.getThisObject();
        Object routeOwner = parentOwner;

        // Establish callback ownership before rewriting any outputs, regardless of parameter order.
        for (Object arg : args) {
            if (arg instanceof CameraCaptureSession.StateCallback) {
                routeOwner = arg;
                CameraSurfaceRouter.linkOwner(arg, parentOwner);
                hookSessionCallback((CameraCaptureSession.StateCallback) arg);
            } else if (arg != null && arg.getClass().getName().equals("android.hardware.camera2.params.SessionConfiguration")) {
                CameraCaptureSession.StateCallback callback = sessionCallbackFromConfiguration(arg);
                if (callback != null) {
                    routeOwner = callback;
                    CameraSurfaceRouter.linkOwner(callback, parentOwner);
                    hookSessionCallback(callback);
                }
            }
        }

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            String className = arg.getClass().getName();
            if (className.equals("android.hardware.camera2.params.SessionConfiguration")) {
                Object replacement = rewriteSessionConfiguration(arg, parentOwner);
                if (replacement != arg) { args[i] = replacement; changed = true; }
                continue;
            }
            if (arg instanceof List<?>) {
                List<?> list = (List<?>) arg;
                if (list.isEmpty()) continue;
                Object first = list.get(0);
                if (first instanceof Surface) {
                    List<Surface> replacements = new ArrayList<>(list.size());
                    boolean listChanged = false;
                    for (Object item : list) {
                        if (!(item instanceof Surface)) { listChanged = false; replacements.clear(); break; }
                        Surface original = (Surface) item;
                        Surface replacement = CameraSurfaceRouter.prepareSurface(original, 0, 0, routeOwner);
                        replacements.add(replacement);
                        listChanged |= replacement != original;
                    }
                    if (listChanged && replacements.size() == list.size()) { args[i] = replacements; changed = true; }
                } else if (first != null && first.getClass().getName().equals("android.hardware.camera2.params.OutputConfiguration")) {
                    List<?> replacements = rewriteOutputConfigurations(list, routeOwner);
                    if (replacements != list) { args[i] = replacements; changed = true; }
                }
            }
        }
        try {
            return changed ? chain.proceed(args) : chain.proceed();
        } catch (Throwable error) {
            if (changed) CameraSurfaceRouter.releaseOwner(routeOwner);
            throw error;
        }
    }

    private CameraCaptureSession.StateCallback sessionCallbackFromConfiguration(Object configuration) {
        try {
            Object callback = configuration.getClass().getMethod("getStateCallback").invoke(configuration);
            return callback instanceof CameraCaptureSession.StateCallback
                    ? (CameraCaptureSession.StateCallback) callback : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object rewriteSessionConfiguration(Object source, Object parentOwner) {
        Object routeOwner = parentOwner;
        try {
            Class<?> type = source.getClass();
            Object outputsValue = type.getMethod("getOutputConfigurations").invoke(source);
            if (!(outputsValue instanceof List<?>)) return source;
            CameraCaptureSession.StateCallback callback = sessionCallbackFromConfiguration(source);
            routeOwner = callback == null ? parentOwner : callback;
            if (callback != null) {
                CameraSurfaceRouter.linkOwner(callback, parentOwner);
                hookSessionCallback(callback);
            }
            List<?> outputs = (List<?>) outputsValue;
            List<?> newOutputs = rewriteOutputConfigurations(outputs, routeOwner);
            if (newOutputs == outputs) return source;
            int sessionType = ((Number) type.getMethod("getSessionType").invoke(source)).intValue();
            Object executor = type.getMethod("getExecutor").invoke(source);
            Constructor<?> constructor = type.getConstructor(int.class, List.class, java.util.concurrent.Executor.class, CameraCaptureSession.StateCallback.class);
            Object replacement = constructor.newInstance(sessionType, newOutputs, executor, callback);
            try {
                Object parameters = type.getMethod("getSessionParameters").invoke(source);
                if (parameters != null) type.getMethod("setSessionParameters", CaptureRequest.class).invoke(replacement, parameters);
            } catch (Throwable ignored) { }
            try {
                Object inputConfig = type.getMethod("getInputConfiguration").invoke(source);
                if (inputConfig != null) type.getMethod("setInputConfiguration", inputConfig.getClass()).invoke(replacement, inputConfig);
            } catch (Throwable ignored) { }
            return replacement;
        } catch (Throwable error) {
            releaseRoutesFromSessionConfiguration(routeOwner, source);
            log(Log.DEBUG, TAG, "SessionConfiguration was left unchanged", error);
            return source;
        }
    }

    private void releaseRoutesFromSessionConfiguration(Object owner, Object source) {
        try {
            Object outputsValue = source.getClass().getMethod("getOutputConfigurations").invoke(source);
            if (!(outputsValue instanceof List<?>)) return;
            for (Object configuration : (List<?>) outputsValue) {
                Object surfacesValue;
                try { surfacesValue = configuration.getClass().getMethod("getSurfaces").invoke(configuration); }
                catch (NoSuchMethodException error) {
                    surfacesValue = Collections.singletonList(configuration.getClass().getMethod("getSurface").invoke(configuration));
                }
                if (surfacesValue instanceof List<?>) {
                    for (Object surface : (List<?>) surfacesValue) {
                        if (surface instanceof Surface) CameraSurfaceRouter.releaseOwnerSurface(owner, (Surface) surface);
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    private List<?> rewriteOutputConfigurations(List<?> originalList, Object owner) {
        List<Object> output = new ArrayList<>(originalList.size());
        boolean changed = false;
        for (Object configuration : originalList) {
            Object replacement = rewriteOutputConfiguration(configuration, owner);
            output.add(replacement);
            changed |= replacement != configuration;
        }
        return changed ? output : originalList;
    }

    private Object rewriteOutputConfiguration(Object source, Object owner) {
        List<Surface> preparedSurfaces = new ArrayList<>();
        try {
            Class<?> type = source.getClass();
            Object surfacesValue;
            try { surfacesValue = type.getMethod("getSurfaces").invoke(source); }
            catch (NoSuchMethodException error) { surfacesValue = Collections.singletonList(type.getMethod("getSurface").invoke(source)); }
            if (!(surfacesValue instanceof List<?>)) return source;
            List<?> oldSurfaces = (List<?>) surfacesValue;
            if (oldSurfaces.isEmpty()) return source;
            List<Surface> mapped = new ArrayList<>(oldSurfaces.size());
            boolean changed = false;
            for (Object value : oldSurfaces) {
                if (!(value instanceof Surface)) return source;
                Surface original = (Surface) value;
                Surface replacement = CameraSurfaceRouter.prepareSurface(original, 0, 0, owner);
                mapped.add(replacement);
                if (replacement != original) preparedSurfaces.add(original);
                changed |= replacement != original;
            }
            if (!changed) return source;
            int groupId = -1;
            try { groupId = ((Number) type.getMethod("getSurfaceGroupId").invoke(source)).intValue(); } catch (Throwable ignored) { }
            Class<?> outputType = Class.forName("android.hardware.camera2.params.OutputConfiguration");
            Object replacementConfig;
            if (groupId >= 0) replacementConfig = outputType.getConstructor(int.class, Surface.class).newInstance(groupId, mapped.get(0));
            else replacementConfig = outputType.getConstructor(Surface.class).newInstance(mapped.get(0));
            boolean sharingEnabled = mapped.size() > 1;
            try {
                sharingEnabled |= Boolean.TRUE.equals(type.getMethod("isSurfaceSharingEnabled").invoke(source));
            } catch (Throwable ignored) { }
            if (sharingEnabled) outputType.getMethod("enableSurfaceSharing").invoke(replacementConfig);
            for (int i = 1; i < mapped.size(); i++) outputType.getMethod("addSurface", Surface.class).invoke(replacementConfig, mapped.get(i));
            copyOptional(source, replacementConfig, type, outputType, "getPhysicalCameraId", "setPhysicalCameraId", String.class);
            copyOptional(source, replacementConfig, type, outputType, "getDynamicRangeProfile", "setDynamicRangeProfile", long.class);
            copyOptional(source, replacementConfig, type, outputType, "getStreamUseCase", "setStreamUseCase", long.class);
            copyOptional(source, replacementConfig, type, outputType, "getMirrorMode", "setMirrorMode", int.class);
            copyOptional(source, replacementConfig, type, outputType, "getTimestampBase", "setTimestampBase", int.class);
            copyOptional(source, replacementConfig, type, outputType, "isReadoutTimestampEnabled", "setReadoutTimestampEnabled", boolean.class);
            return replacementConfig;
        } catch (Throwable error) {
            for (Surface surface : preparedSurfaces) CameraSurfaceRouter.releaseOwnerSurface(owner, surface);
            log(Log.DEBUG, TAG, "OutputConfiguration was left unchanged", error);
            return source;
        }
    }

    private void copyOptional(Object from, Object to, Class<?> fromType, Class<?> toType, String getter, String setter, Class<?> parameterType) {
        try {
            Object value = fromType.getMethod(getter).invoke(from);
            if (value != null) toType.getMethod(setter, parameterType).invoke(to, value);
        } catch (Throwable ignored) { }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) { }
        }
        try { return type.getMethod(name, parameters); } catch (Throwable ignored) { return null; }
    }
}

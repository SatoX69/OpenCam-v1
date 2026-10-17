package com.jsux.openlens;

import android.content.Context;
import android.hardware.Camera;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Method;

public final class OpenLensModule extends XposedModule {
    private static final String TAG = "OpenLens";

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (ModuleContract.PACKAGE_NAME.equals(packageName)) return;

        Context packageContext = null;
        try {
            packageContext = createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Could not create target context for " + packageName, error);
        }

        try {
            ClassLoader loader = param.getDefaultClassLoader();
            Class<?> cameraClass = Class.forName("android.hardware.Camera", false, loader);
            installCallbackHook(cameraClass, "setPreviewCallback", packageName, packageContext);
            installCallbackHook(cameraClass, "setOneShotPreviewCallback", packageName, packageContext);
            installCallbackHook(cameraClass, "setPreviewCallbackWithBuffer", packageName, packageContext);
            log(Log.INFO, TAG, "Installed Camera1 preview callback adapter in " + packageName);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Camera1 adapter unavailable in " + packageName, error);
        }
    }

    private void installCallbackHook(Class<?> cameraClass, String methodName, String packageName, Context packageContext) throws Exception {
        Method method = cameraClass.getDeclaredMethod(methodName, Camera.PreviewCallback.class);
        hook(method)
                .setId("camera1_" + methodName)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object original = chain.getArg(0);
                    if (!(original instanceof Camera.PreviewCallback)) return chain.proceed();
                    Camera.PreviewCallback originalCallback = (Camera.PreviewCallback) original;
                    Context host = packageContext;
                    if (host == null) {
                        try { host = createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY); }
                        catch (Throwable ignored) { }
                    }
                    if (host != null) FrameEngine.initialize(host);
                    Camera.PreviewCallback replacingCallback = (data, camera) -> {
                        try {
                            FrameEngine.inject(data, camera);
                        } catch (Throwable error) {
                            Log.w(TAG, "Frame replacement failed; forwarding camera data", error);
                        }
                        originalCallback.onPreviewFrame(data, camera);
                    };
                    return chain.proceed(new Object[]{replacingCallback});
                });
    }
}

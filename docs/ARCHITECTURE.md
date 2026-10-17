# OpenLens architecture and compatibility boundaries

## Runtime components

- `MainActivity`: image/video import, target-package allowlist, enable/disable state and URI grants.
- `MediaProvider`: read-only cross-process configuration/media provider; media URIs are granted only to packages selected in the app UI.
- `OpenLensModule`: modern libxposed API 102 entry point. Installs app-process Camera1/Camera2 hooks only when the framework scopes the target package.
- `FrameEngine`: cross-process configuration polling, image/video decoding, Camera1 format conversion and JPEG encoding.
- `CameraSurfaceRouter` / `SurfaceFramePump`: tracks known `SurfaceTexture` / `SurfaceHolder` surfaces, redirects compatible camera outputs to a private `SurfaceTexture`, consumes physical frames and draws selected media on the original output through EGL/OpenGL ES 2. When disabled, the adapter draws the physical camera texture.
- `CameraImageAdapter`: hooks ordinary `ImageReader` acquisition (including reader instances delivered by multi-resolution camera flows), then adapts Java `Image.Plane.getBuffer()` for selected formats.

## Interception matrix

| API path | Mechanism | Limits |
|---|---|---|
| Camera1 preview callbacks: standard, one-shot, callback-with-buffer | Replaces byte data after the camera callback arrives | Supports `NV21`, `YV12`, `YUY2`, and `RGB_565`; unsupported formats and nonstandard memory layouts pass through. |
| Camera1 `setPreviewTexture` | Supplies a private camera `SurfaceTexture` and renders into the app texture's output Surface | Requires correct dimensions and an EGL-compatible output Surface. |
| Camera1 `setPreviewDisplay` | Attempts to proxy `SurfaceHolder.getSurface()` through a Java dynamic proxy | OEM framework code may require its concrete holder implementation and reject the proxy. |
| Camera1 `takePicture` | Replaces the last `Camera.PictureCallback` data with a JPEG encoding of the selected media | The adapter assumes the last callback argument is the JPEG callback, as in common overloads; RAW/postview/vendor-specific capture paths are not fully virtualized. |
| Camera2 CameraManager / CameraDevice | Hooks open callbacks and concrete CameraDevice implementations, including common session-creation overloads | Internal concrete implementations vary by Android release and OEM. Unmatched methods remain unchanged. |
| Camera2 sessions | Rewrites known Surface lists, `SessionConfiguration` outputs and `OutputConfiguration` surfaces; attempts to preserve session/input parameters and optional output metadata | Reflective reconstruction is best-effort. Providers may reject output size, format, dataspace, stream-use-case, physical-camera, sharing, or deferred-output combinations. |
| Camera2 request builders | Hooks `addTarget`, `removeTarget`, and `build()` so recognized outputs can be mapped even when the request was built before session setup | Builder and built-request target-set reflection can fail on hidden/immutable implementation details. |
| Camera2 request submission | Rewrites recognizable target sets on capture/repeating requests when a request is submitted to a compatible session | This is a last-resort path and depends on reflective access to the implementation's target collection. |
| ImageReader | Hooks acquired `Image` objects and returns synthetic direct Java buffers for JPEG, flexible YUV 4:2:0 / 4:2:2 / 4:4:4, Y8 and P010 when valid plane layouts are exposed | It replaces the returned Java ByteBuffer, not the underlying camera DMA/HAL allocation. JNI consumers retaining native addresses may still see the physical buffer. |
| Images from multi-resolution camera flows | Adapts the ordinary `ImageReader` instances delivered by the multi-resolution flow | The returned Image metadata may still reflect physical-camera timestamps/capture results; OEM-specific implementations may be inaccessible. |
| CameraX Preview / ImageAnalysis / ImageCapture | Works indirectly through underlying Camera2 surface routing and standard Android plane buffers | No direct CameraX dependency; exact behavior varies by CameraX version, preview implementation, extensions, and capture path. |
| NDK Camera2 / Camera HAL / vendor camera extensions | None at the native buffer/HAL level | This module is not a virtual Camera HAL, does not virtualize camera IDs, and cannot replace protected or vendor-private buffers generically. |

## Frame formats and memory bounds

- Camera1 preview conversions cover `NV21`, `YV12`, `YUY2`, and `RGB_565`. YV12 uses aligned Y and chroma strides; unsupported dimensions/formats pass through.
- CPU-visible `ImageReader` formats include JPEG and flexible three-plane YUV formats. The single-plane Y8 path creates a grayscale image; P010 creates two little-endian 10-bit planes. Odd-sized P010 frames or unexpected plane strides fall back to the physical buffer.
- Image dimensions are bounded to 4096 per side and 16,777,216 pixels. Replacement plane allocations are budgeted, and oversized or malformed metadata is rejected before a new plane array is allocated.
- Input images are decoded to a bounded source bitmap. Video frames are decoded periodically from the selected local file; rendering is limited by decoder throughput and device GPU/CPU capabilities.

## Surface lifecycle

Camera session outputs are routed only after replacement is enabled and the selected media has decoded successfully. The original display/consumer Surface remains the EGL output; a private SurfaceTexture serves as the new camera output, so camera producers continue draining while OpenLens renders selected media. This requires the camera provider to accept a substituted surface with compatible size and usage. If dimensions are unknown, EGL initialization fails, or an output is protected/unsupported, the original surface is retained.

The renderer polls the cross-process enabled state through `FrameEngine`. For a configured route, toggling off returns rendering to the live camera texture. The user should close/reopen the camera if it does not reconfigure; an app may also have a separate image/video capture path.

## Limitations

There is no single Android “camera feed” hook. Surface previews, YUV analysis, JPEG still captures, RAW captures, GPU/native buffers and app-specific pipelines are distinct outputs. A Java Xposed module cannot honestly guarantee replacement for every camera API/interface. `PRIVATE`/protected surfaces, `HardwareBuffer`-only paths, `RAW_SENSOR`/RAW10/12/14, NDK Camera2, direct native camera clients, camera extensions and OEM-private outputs remain outside reliable coverage. Disabling replacement does not erase media an app already received; it only stops current OpenLens reads/replacement.

## Verification checklist

1. Run `python3 scripts/check_source.py` for dependency-free structural checks.
2. Build with Android API 35, Gradle 8.11.1 and Android Gradle Plugin 8.9.2.
3. Confirm the signed APK contains `META-INF/xposed/java_init.list`, `module.prop` and `scope.list`; validate APK signing and checksum.
4. Test Camera1 NV21/YV12/YUY2/RGB565 callback paths, callback buffers, front/rear switching, JPEG callback and `SurfaceTexture`/`SurfaceHolder` routes.
5. Test Camera2 `TextureView`, `SurfaceView`, shared/deferred `OutputConfiguration`, `SessionConfiguration`, capture request setup/submission, JPEG and YUV readers, P010/Y8 where devices support them, and camera teardown/disable/re-enable.
6. Test CameraX Preview, ImageAnalysis and ImageCapture independently, then test each target app and camera ID on real hardware.
7. For QR scanning/link flows, verify that the app actually recognizes the selected frame, does not demand another output path, and the QR data is current. Do not infer success from a substituted preview alone.

The environment used to prepare this source did not have an Android SDK or an accessible Gradle distribution and had no physical device attached. Structural checks are not a claim of compilation or runtime compatibility; the workflow is intended to perform the first SDK-backed build.

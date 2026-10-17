# Architecture and adapter boundary

## Components

- `MainActivity`: native UI, file picker, file import, target package selection, persisted enabled state.
- `MediaProvider`: read-only content provider. Target packages must have an explicit URI grant before the module can read configuration or media.
- `OpenLensModule`: modern libxposed API 102 entry point. Installs hooks only in processes included in the framework's scope.
- `FrameEngine`: polls the provider-backed configuration, decodes a static image or looped video frames, and converts bitmaps into NV21 byte arrays.

## Hook path supported today

`Camera.setPreviewCallback`, `Camera.setOneShotPreviewCallback`, and `Camera.setPreviewCallbackWithBuffer` are intercepted. The original callback is wrapped. Before forwarding the callback, OpenLens tries to replace its byte array with a frame of the active media source. Any exception falls through to the original camera bytes. This applies to whichever physical Camera1 instance supplies that callback; there is no front/rear camera ID selection.

## What would be required for broader coverage

Camera2/CameraX and direct preview surfaces may deliver data through native BufferQueues or `ImageReader`/`Image` objects. Those paths do not route their pixel data through Camera1's `PreviewCallback`, so this adapter cannot replace them. A broader implementation needs separate, format-aware adapters for camera session output surfaces, YUV/JPEG still capture and analysis, and any native camera paths. A true device-wide virtual camera may require lower-level camera-provider/HAL work with firmware/API-specific compatibility and testing. Simply hooking more high-level methods does not guarantee that a target receives fabricated pixels.

## Fail-open rules

- No content grant or malformed config: do not alter camera data.
- Disabled: forward the original byte array.
- Preview format other than NV21: forward the original byte array.
- Unexpected dimensions or short callback buffer: forward original bytes.
- Conversion/decode exceptions: log and forward original bytes.

## Verification checklist for maintainers

1. Build on a clean runner with API 102 available only as `compileOnly`.
2. Confirm `META-INF/xposed/java_init.list`, `module.prop`, and `scope.list` are present in the APK.
3. Verify the release APK signature and SHA-256.
4. Test one Camera1/NV21 app with image input, looping video, camera switching, Enable/Disable, target process restart, app process crash/restart, denied URI access, and unsupported preview formats.
5. Separately test target apps using Camera2, CameraX, direct Surface preview, or native capture and confirm they remain unsupported unless an adapter is added.

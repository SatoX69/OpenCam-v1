# OpenLens

OpenLens is an open-source Android companion app and modern libxposed module that attempts to substitute a selected local image or looping video into camera paths used by selected applications. The project now combines Camera1 callback conversion, EGL-backed preview-surface routing, Camera2 session/request rewriting, and CPU-visible `Image` plane adapters.

**Compatibility is broad but not universal.** Android camera output is not one API: it may travel through Camera1 byte arrays, Camera2 `Surface` streams, `ImageReader` planes, private/native hardware buffers, or vendor-specific code. An app-process Xposed module cannot replace every pipeline safely, so OpenLens fails open when a path cannot be identified or transformed without guessing.

## Features

- Native Android UI for importing an image or video, selecting target packages, and enabling/disabling replacement.
- Modern `io.github.libxposed:api:102.0.0` module metadata for a compatible modern LSPosed/Vector build; this is not a legacy XposedBridge module.
- Camera1 preview callback conversion for `NV21`, `YV12`, `YUY2`, and `RGB_565` when those formats are used; common Camera1 JPEG callback replacement.
- EGL/OpenGL ES 2 surface adapter for compatible `SurfaceTexture` and `SurfaceHolder` previews. It consumes the physical camera stream to keep the producer active, draws selected media while enabled, and returns to the physical stream when disabled.
- Camera2 session-output and request routing across common `createCaptureSession` overloads, `SessionConfiguration`, `OutputConfiguration` (including shared/deferred outputs where rewritable), builder targets, and capture/repeating request submission paths.
- CPU-visible `ImageReader` plane substitution for JPEG, YUV 4:2:0 / 4:2:2 / 4:4:4, grayscale Y8, and P010 where the implementation exposes compatible Java buffers. This also applies to ordinary `ImageReader` instances delivered through the multi-resolution reader flow.
- Best-effort CameraX coverage through its underlying Camera2 surfaces and standard Android `Image` planes. CameraX compatibility depends on its implementation, use case, library version, and device.
- Read-only cross-process provider, scoped URI permissions, bounded dimensions/buffer allocations, no analytics or network calls, and a GitHub Actions release pipeline with checksum and signing verification.

## Requirements

- Android 8.0 / API 26 or newer.
- Root and an Xposed-family framework build compatible with libxposed API 102.
- Enable OpenLens in LSPosed/Vector scope for each target app, then select that same package in OpenLens. Framework scope determines where the hooks load; the in-app target list separately restricts which packages receive media URI access.

## Install and use

1. Install the APK from the GitHub Actions artifact or a GitHub Release.
2. In LSPosed/Vector, activate OpenLens and add the target package(s) to module scope.
3. Open OpenLens and select the same package(s) in its target list.
4. Select an image or video, then tap **Enable replacement**.
5. Select the media and enable replacement before opening the target app's camera, then close and reopen its camera. Existing sessions that were configured before OpenLens installed a route cannot always be redirected afterward.
6. Tap **Disable and return to camera** to stop replacing frames. A working surface adapter switches its output back to the live physical-camera texture; the route stays attached for re-enabling until the camera/session is closed. If a session was created while no replacement was configured, reopen the camera after enabling.

Front, rear, macro, and ultrawide cameras may be affected when the target app opens a compatible camera path. OpenLens does not create camera IDs or change the phone's physical camera inventory.

## Compatibility matrix

| Camera path | Adapter coverage | Important limitations |
|---|---|---|
| Camera1 `PreviewCallback`, one-shot callback, callback-with-buffer | `NV21`, `YV12`, `YUY2`, `RGB_565` byte conversion | Unsupported formats, vendor-specific strides, and callback paths not using the expected formats pass through. |
| Camera1 `setPreviewTexture` / `setPreviewDisplay` | EGL surface routing | Requires known dimensions and a working EGL-compatible output. Some OEM `SurfaceHolder` implementations may reject a proxy. |
| Camera1 JPEG `takePicture` callback | Compresses current media frame to JPEG | Only the common JPEG callback slot is replaced. RAW data, vendor callbacks, and saved files created by other paths are outside this adapter. |
| Camera2 preview surface | Maps recognized surfaces from `SurfaceTexture` / `SurfaceHolder` to a private camera sink | The camera provider must accept the substituted surface, size, dataspace, and use case. Unknown-size or protected outputs remain unchanged. |
| Camera2 session configuration and targets | Common session overloads, `SessionConfiguration`, shared/deferred `OutputConfiguration`, request-builder targets and selected request-submit methods | Reflection into hidden implementation fields is best-effort and can be blocked or changed by Android/OEM releases. |
| `ImageReader` including readers delivered by `MultiResolutionImageReader` | JPEG, flexible YUV 4:2:0 / 4:2:2 / 4:4:4, Y8 and P010 CPU buffers | Works only when consumers retrieve data through hooked Java plane-buffer methods. Not a generic native or `HardwareBuffer` replacement. |
| CameraX `Preview` | Via compatible Camera2 output surfaces | Depends on PreviewView implementation, stream size, extensions and device pipeline. |
| CameraX `ImageAnalysis` / `ImageCapture` | Via the underlying exposed Android `Image` planes and shared Camera2 hooks | Still requires per-version/per-device validation; encoded output, RAW and native buffers may bypass it. |
| RAW (`RAW_SENSOR`, RAW10/12/14), `PRIVATE`, secure/protected streams, `HardwareBuffer`, native/vendor camera paths | No reliable generic replacement | Passed through when possible. OpenLens is not a virtual camera HAL. |

The current implementation should therefore be treated as an experimental compatibility layer, not a promise that every app will show or consume the selected media. A replaced preview does not guarantee that still photos or videos saved by an app are replaced: preview, analysis, video recording, and still capture can use different outputs. Android's camera output formats and surfaces impose pipeline-specific constraints; see the [Android ImageFormat reference](https://developer.android.com/reference/android/graphics/ImageFormat) and [Camera2 OutputConfiguration reference](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration).

### WhatsApp companion-device linking

OpenLens may affect the QR scanner only if that WhatsApp version uses a supported Camera1/Camera2 path. Companion-device linking has not been validated on a device. A working preview substitution does not guarantee that the QR code is recognized or that the linking flow succeeds; current codes may expire or be refreshed by WhatsApp. Test only with accounts and devices you are authorized to link.

## Build locally

Install Android Studio or configure an Android SDK, then run:

```sh
./gradlew assembleDebug
./gradlew assembleRelease
```

The debug APK is under `app/build/outputs/apk/debug/`. The release variant is intentionally unsigned; GitHub Actions signs it. Gradle 8.11.1, Android Gradle Plugin 8.9.2, and Android API 35 are pinned in the project configuration. `python3 scripts/check_source.py` runs dependency-free structural checks; it is not a replacement for an Android SDK compilation.

## GitHub Actions release signing

`.github/workflows/build.yml` runs source checks, compiles the release variant, signs it, verifies archive and signing integrity, confirms Xposed module metadata is packaged, calculates SHA-256, and uploads the signed APK. It runs for pushes to `main` / `master`, pull requests, manual dispatch, and `v*` tags. A GitHub Release is published for a `v*` tag only when a persistent signing key is configured.

Configure these repository secrets for upgrade-compatible releases:

- `OPENLENS_KEYSTORE_BASE64` — single-line base64 representation of your `.jks` file.
- `OPENLENS_KEYSTORE_PASSWORD` — keystore password.
- `OPENLENS_KEY_ALIAS` — signing key alias.
- `OPENLENS_KEY_PASSWORD` — private-key password (may equal the keystore password).

Generate a key locally:

```sh
./scripts/generate-release-key.sh
```

Keep the key and passwords backed up privately; never commit the keystore. Without stable signing secrets, CI creates a temporary key for a test artifact. An APK signed with a different key cannot update an existing installation in place, so CI will not publish a stable GitHub Release from an ephemeral-key build.

## Privacy and safety

- Imported media is copied into OpenLens private app storage.
- The provider is exported only to pass media/configuration to scoped target processes. Media reads require a URI grant to a package selected in OpenLens, and reads are denied when replacement is disabled.
- Target apps may process or transmit whatever data their own code receives. Use only with applications you are authorized to test.
- OpenLens has no telemetry, account system, advertising or network requests.

## Debugging and verification

If the physical feed remains visible, check framework activation and app scope first, then the separate target list in OpenLens. Close and reopen the target app's camera. Inspect logcat tags `OpenLens`, `OpenLens/SurfaceRouter`, `OpenLens/SurfacePump`, and `OpenLens/ImageAdapter`. Unsupported dimensions, image formats, camera extensions, hidden-field restrictions, and vendor-specific outputs intentionally pass through rather than forcing a potentially broken camera session.

**Build/test status:** source structural checks can be run locally. The Android SDK/Gradle distribution was unavailable in the development environment, and no physical-device test was performed; the first SDK-backed compile happens in GitHub Actions. Treat each app/API combination as unverified until tested on hardware.

## License

Apache-2.0. See [LICENSE](LICENSE).

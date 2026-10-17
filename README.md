# OpenLens

OpenLens is an open-source Android app and modern libxposed module for selecting a local image/video and substituting frames delivered through the legacy **Camera1 `PreviewCallback` API** in apps enabled in LSPosed/Vector scope.

**Compatibility is deliberately limited and experimental.** This first version replaces `Camera1.PreviewCallback` byte arrays only when the camera's preview format is NV21. It does not replace every camera path on Android. Apps built on Camera2, CameraX, direct `Surface`/`SurfaceTexture` previews, still-photo capture, native camera pipelines, or non-NV21 callback formats can continue to receive the physical camera. A complete virtual camera that works across those paths generally needs per-API output-surface handling or a lower-level camera-provider/HAL implementation, followed by device-specific testing.

## Features in this source drop

- Native Android UI with image/video file selection, preview, target app list, and Enable/Disable controls.
- Module based on `io.github.libxposed:api:102.0.0`, intended for modern LSPosed/Vector implementations that support that API.
- A content provider that shares a copied local media file and configuration only with packages explicitly selected in OpenLens through URI read grants.
- Callback wrapping for `Camera.setPreviewCallback`, `setOneShotPreviewCallback`, and `setPreviewCallbackWithBuffer`.
- Static images and looping video-frame extraction, conversion to NV21, a bounded 256 MiB media import size, and fail-open behavior if frame conversion fails.
- GitHub Actions build, APK signing, SHA-256 output, Actions artifacts on pushes/PRs, and GitHub Releases for `v*` tags.
- No runtime analytics, accounts, advertising, network calls, or third-party runtime libraries.

## Requirements

- An Android device running Android 8.0/API 26 or newer.
- A modern Xposed-family framework that supports libxposed API 102. This project does **not** target legacy XposedBridge/EdXposed APIs.
- The target package must be enabled in the module's scope in the framework manager **and** checked in OpenLens so the module can read the selected media through a temporary content-URI grant.

The module cannot reliably verify from its own UI whether the framework has activated its scope, so the displayed enabled state means that OpenLens wrote the shared configuration; it is not proof that a given target received replacement frames.

## Install / use

1. Install the APK from the `openlens-release-*.apk` Actions artifact or a GitHub Release.
2. Open LSPosed/Vector and enable OpenLens for the target apps you want to test. Use the modern libxposed-compatible framework build.
3. Open OpenLens and check the same target apps in **Target Apps**. This second selection grants those apps read-only access to OpenLens' config and source URI; it is a privacy boundary, not a substitute for framework scope.
4. Choose an image or video.
5. Tap **Enable replacement**.
6. Force-stop/reopen the target app or close and reopen its camera. Some apps retain a camera session and callback until it is reopened.
7. Tap **Disable and return to camera** before changing the media or target list.

Front, rear, macro, and other physical camera instances use the same selected source when they invoke the hooked Camera1 callback and use NV21 preview data. This does not create extra camera IDs or alter the phone's physical camera inventory.

## Build locally

The included `gradlew` bootstrap script downloads the pinned Gradle 8.11.1 distribution on first use. Android Studio or a local Android SDK is also required to compile the APK.

```sh
./gradlew assembleDebug
./gradlew assembleRelease
```

Outputs are under `app/build/outputs/apk/`. `assembleRelease` intentionally creates an unsigned APK; use the workflow or sign it with your own key. The workflow performs signing with Android's `apksigner` tool.

## GitHub Actions release signing

`.github/workflows/build.yml` builds on pushes to `main`/`master`, pull requests, manual dispatch, and `v*` tags. It uploads the signed APK plus checksum as an Actions artifact. For a `v*` tag **with persistent signing secrets configured**, it also creates a GitHub Release; without those secrets, the APK artifact is built but release publication is skipped to avoid publishing an upgrade-incompatible APK.

### Recommended persistent signing key

An Android update must be signed with the same certificate as the installed version. The workflow therefore supports a persistent keystore stored in repository Actions secrets:

- `OPENLENS_KEYSTORE_BASE64` — base64-encoded `.jks` file (single-line base64 recommended).
- `OPENLENS_KEYSTORE_PASSWORD` — keystore password.
- `OPENLENS_KEY_ALIAS` — alias of the signing key.
- `OPENLENS_KEY_PASSWORD` — key password; may equal the keystore password.

Generate a key securely on your own machine:

```sh
./scripts/generate-release-key.sh
```

Keep the generated keystore and passwords private and backed up. Add the four values above under **Repository → Settings → Secrets and variables → Actions**. Do not commit the `.jks` file.

If signing secrets are absent, CI generates a one-run temporary key so a signed APK artifact can still be produced. That APK can be installed, but later builds cannot update it in place unless you retain that exact key and configure the secrets. The private key is never uploaded by the workflow. Do not use the ephemeral fallback for public stable releases.

## Security and privacy

- Selected files are copied into OpenLens' private app storage.
- The provider is exported only because scoped target processes need to read its content URIs. `openFile()` checks the caller's URI grant; media reads are denied while the module is disabled.
- OpenLens grants URI read permission only to the packages selected in its UI. The media grant is revoked shortly after Disable; config access remains only for selected targets so they can observe the disabled state. Unchecking a target while disabled revokes both grants.
- The provider does not provide write access.
- A target app receiving image/video-derived camera data can still save, analyse, or transmit it according to its own behavior. Only use media and target apps for which you are authorized.

## Failure behavior

The hook passes through the original camera buffer when the module is disabled, cannot read configuration, encounters an unsupported preview format, or encounters a per-frame conversion error. Failure to load the source stops the replacement path. This is designed to avoid crashing a target app merely because OpenLens fails, but it cannot guarantee that every camera implementation tolerates injected modules.

## Project status

This is an early, openly licensed implementation intended as a base for further camera-adapter work, not a drop-in, universally compatible alternative to proprietary virtual-camera products. No physical device was available to this build environment, and the Android SDK/Gradle dependencies were not installed here; the source has not been compiled or validated on a device in this session. The GitHub Actions workflow is included to do the actual SDK-backed build and emit diagnostics. Treat the first release as a test build until CI is green and it has been tested on each intended target app.

## License

Apache-2.0. See [`LICENSE`](LICENSE).

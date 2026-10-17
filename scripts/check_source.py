#!/usr/bin/env python3
"""Dependency-free structural checks for OpenLens before an Android SDK build."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ERRORS = []
CHECKS = 0


def fail(message: str) -> None:
    ERRORS.append(message)


def check(condition: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if not condition:
        fail(message)


def java_delimiter_check(path: Path) -> None:
    """Balance Java delimiters while ignoring comments and quoted literals."""
    text = path.read_text(encoding="utf-8")
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    opening = set(pairs.values())
    state = "code"
    i = 0
    line = 1
    start_line = 1
    while i < len(text):
        c = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if c == "\n":
            line += 1
        if state == "line_comment":
            if c == "\n":
                state = "code"
            i += 1
            continue
        if state == "block_comment":
            if c == "*" and nxt == "/":
                state = "code"
                i += 2
            else:
                i += 1
            continue
        if state in ("string", "char"):
            end = '"' if state == "string" else "'"
            if c == "\\":
                i += 2
                continue
            if c == end:
                state = "code"
            i += 1
            continue
        if c == "/" and nxt == "/":
            state = "line_comment"
            i += 2
            continue
        if c == "/" and nxt == "*":
            state = "block_comment"
            i += 2
            continue
        if c == '"':
            state = "string"
            i += 1
            continue
        if c == "'":
            state = "char"
            i += 1
            continue
        if c in opening:
            stack.append((c, line))
        elif c in pairs:
            if not stack or stack[-1][0] != pairs[c]:
                fail(f"{path.relative_to(ROOT)}:{line}: unexpected {c}")
                return
            stack.pop()
        i += 1
    if state in ("string", "char", "block_comment"):
        fail(f"{path.relative_to(ROOT)}: unterminated {state}")
    if stack:
        delimiter, opened_line = stack[-1]
        fail(f"{path.relative_to(ROOT)}:{opened_line}: unclosed {delimiter}")


def main() -> int:
    java_files = sorted((ROOT / "app/src/main/java").rglob("*.java"))
    check(bool(java_files), "No Java source files found")
    for source in java_files:
        java_delimiter_check(source)
        text = source.read_text(encoding="utf-8")
        check("package com.jsux.openlens;" in text, f"Missing package declaration: {source}")

    xml_files = [ROOT / "app/src/main/AndroidManifest.xml"] + sorted((ROOT / "app/src/main/res").rglob("*.xml"))
    for xml_file in xml_files:
        try:
            ET.parse(xml_file)
            check(True, f"XML parses: {xml_file.relative_to(ROOT)}")
        except ET.ParseError as exc:
            fail(f"Invalid XML {xml_file.relative_to(ROOT)}: {exc}")

    required_files = [
        "app/src/main/resources/META-INF/xposed/module.prop",
        "app/src/main/resources/META-INF/xposed/java_init.list",
        "app/src/main/resources/META-INF/xposed/scope.list",
        "app/src/main/java/com/jsux/openlens/FrameEngine.java",
        "app/src/main/java/com/jsux/openlens/CameraSurfaceRouter.java",
        "app/src/main/java/com/jsux/openlens/SurfaceFramePump.java",
        "app/src/main/java/com/jsux/openlens/CameraImageAdapter.java",
        "app/src/main/java/com/jsux/openlens/OpenLensModule.java",
        ".github/workflows/build.yml",
    ]
    for relative in required_files:
        check((ROOT / relative).is_file(), f"Required project file exists: {relative}")

    module_prop = (ROOT / "app/src/main/resources/META-INF/xposed/module.prop").read_text(encoding="utf-8")
    init_list = (ROOT / "app/src/main/resources/META-INF/xposed/java_init.list").read_text(encoding="utf-8")
    check("minApiVersion=" in module_prop and "targetApiVersion=" in module_prop, "Xposed API version metadata is present")
    check("com.jsux.openlens.OpenLensModule" in init_list, "Xposed entry point is listed")

    module_source = (ROOT / "app/src/main/java/com/jsux/openlens/OpenLensModule.java").read_text(encoding="utf-8")
    engine_source = (ROOT / "app/src/main/java/com/jsux/openlens/FrameEngine.java").read_text(encoding="utf-8")
    image_source = (ROOT / "app/src/main/java/com/jsux/openlens/CameraImageAdapter.java").read_text(encoding="utf-8")
    surface_source = (ROOT / "app/src/main/java/com/jsux/openlens/CameraSurfaceRouter.java").read_text(encoding="utf-8")
    matrix = {
        "Camera1 preview and still-image hooks": "setPreviewCallback" in module_source and "takePicture" in module_source,
        "Camera2 request and session hooks": "CaptureRequest.Builder" in module_source and "rewriteSessionConfiguration" in module_source,
        "Camera1 NV21/YV12/YUY2/RGB565 conversions": all(name in engine_source for name in ("toNv21", "toYv12", "toYuy2", "toRgb565")),
        "Camera2 YUV/JPEG/Y8/P010 image adapters": "YUV_420_888" in image_source and "ImageFormat.JPEG" in image_source and "ImageFormat.Y8" in image_source and "ImageFormat.YCBCR_P010" in image_source,
        "Multi-resolution images routed through ordinary ImageReader acquisition": "ordinary ImageReader instances" in image_source,
        "Per-camera/session surface lifecycle cleanup": "releaseOwner" in module_source and "transferOwner" in module_source and "releaseOwner" in surface_source,
        "EGL surface router exists": "new SurfaceFramePump" in surface_source,
        "Optional modern output metadata copied": "setStreamUseCase" in module_source and "setTimestampBase" in module_source,
    }
    for description, present in matrix.items():
        check(present, f"Adapter marker: {description}")

    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    check(version is not None, "Android versionName is declared")
    if version:
        check(version.group(1) in workflow, "CI signing metadata matches Android versionName")
    check("python3 scripts/check_source.py" in workflow, "CI runs source structural checks")
    check("verify --verbose --print-certs" in workflow and "unzip -t" in workflow, "CI verifies signed APK and archive integrity")
    check("OPENLENS_KEYSTORE_BASE64" in workflow, "CI supports persistent signing credentials")

    if ERRORS:
        print(f"OpenLens source checks failed ({len(ERRORS)} errors, {CHECKS} checks):", file=sys.stderr)
        for error in ERRORS:
            print(f" - {error}", file=sys.stderr)
        return 1
    print(f"OpenLens source checks passed: {CHECKS} structural checks across {len(java_files)} Java files and {len(xml_files)} XML files.")
    print("Note: these checks do not replace Gradle compilation, APK verification, or physical-device tests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

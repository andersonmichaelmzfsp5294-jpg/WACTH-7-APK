"""Deterministic, source-only S4 companion build; never installs or deploys.

Usage:
  python build_s4.py --variant standard --offline
  python build_s4.py --variant research --offline

Only the research flavor may resolve a separately provided Samsung SDK AAR.
No API tokens, signing keys or vendor binaries are bundled by this script.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent
GRADLE_LOCAL = ROOT / ".tools/gradle/gradle-8.13/bin/gradle.bat"
GRADLE_WRAPPER = ROOT / "gradlew.bat"
SDK_LOCAL = ROOT / ".tools/android-sdk"
VARIANTS = {"standard": "Standard", "research": "Research"}


def toolchain():
    gradle = os.environ.get("GRADLE_EXECUTABLE") or (
        str(GRADLE_LOCAL) if GRADLE_LOCAL.is_file() else
        str(GRADLE_WRAPPER) if GRADLE_WRAPPER.is_file() else
        shutil.which("gradle")
    )
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or SDK_LOCAL)
    if not gradle:
        raise RuntimeError("Gradle 8.13 not found; set GRADLE_EXECUTABLE")
    if not (sdk / "platforms/android-36").is_dir():
        raise RuntimeError("Android SDK platform 36 not found; set ANDROID_HOME")
    env = dict(os.environ)
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    java = shutil.which("java")
    if not java:
        roots = list(Path("C:/Program Files/Microsoft").glob("jdk-17*"))
        if roots:
            env["JAVA_HOME"] = str(roots[0])
            env["PATH"] = str(roots[0] / "bin") + os.pathsep + env.get("PATH", "")
    return gradle, sdk, env


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", choices=list(VARIANTS), default="standard")
    ap.add_argument("--offline", action="store_true",
                    help="Use only already cached Gradle dependencies")
    ap.add_argument("--write-locks", action="store_true",
                    help="Record resolved Gradle dependencies for source reproducibility")
    ap.add_argument("--no-samsung-sdk", action="store_true",
                    help="Prove the standard flavor needs no Samsung AAR")
    opts = ap.parse_args()
    if opts.no_samsung_sdk and opts.variant != "standard":
        ap.error("--no-samsung-sdk applies only to standard")

    gradle, sdk, env = toolchain()
    if opts.variant == "research":
        aar = Path(env.get("SAMSUNG_SDK_AAR", "")) if env.get("SAMSUNG_SDK_AAR") else (
            ROOT.parent / "research/watch-capability-probe/app/libs/samsung-health-sensor-api.aar"
        )
        if not aar.is_file():
            raise RuntimeError("Research build requires operator-provided Samsung SDK AAR")
    variant = opts.variant
    task = f":app:assemble{VARIANTS[variant]}Debug"
    log = ROOT / f"build_s4_{variant}.log"
    argv = [gradle, "-p", str(ROOT), task, "--no-daemon",
            "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process",
            "-Pkotlin.incremental=false", "--console=plain"]
    if opts.offline:
        argv.append("--offline")
    if opts.write_locks:
        argv.append("--write-locks")
    if opts.no_samsung_sdk:
        argv.append("-PsamsungSdkAar=__intentionally_missing_for_standard_test__.aar")
        env.pop("SAMSUNG_SDK_AAR", None)
    print("S4_BUILD", variant, "task", task, "offline", opts.offline, flush=True)
    started = time.monotonic()
    with log.open("wb") as stream:
        try:
            result = subprocess.run(argv, cwd=ROOT, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=480, check=False)
            rc = result.returncode
        except subprocess.TimeoutExpired:
            rc = 124
    report = log.read_bytes().decode("utf-8", errors="replace")
    print("S4_BUILD_EXIT", rc, "elapsed_seconds", round(time.monotonic()-started, 1),
          "log", log.name, flush=True)
    if rc:
        print("GRADLE_LOG_TAIL", report[-3500:], flush=True)
        return rc
    if "BUILD SUCCESSFUL" not in report:
        print("BUILD_SUCCESS_MARKER_MISSING", flush=True)
        return 3
    apk = ROOT / f"app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk"
    if not apk.is_file():
        print("APK_NOT_FOUND", str(apk), flush=True)
        return 4
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            print("APK_CORRUPTED", flush=True)
            return 5
    hash_value = hashlib.sha256(apk.read_bytes()).hexdigest()
    print("S4_APK_PATH", apk, flush=True)
    print("S4_APK_SIZE_BYTES", apk.stat().st_size, flush=True)
    print("S4_APK_SHA256", hash_value, flush=True)
    print("S4_BUILD_SUCCESS", variant, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

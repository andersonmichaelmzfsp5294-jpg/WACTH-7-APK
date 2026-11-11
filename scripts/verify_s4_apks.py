"""Verify S4 APK structure, identity, permissions and hashes; never install.

Expected build: python build_s4.py --variant standard --offline --no-samsung-sdk
                python build_s4.py --variant research --offline
Run this script only after both exit zero. Development/debug packages are not
signed production releases.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SDK_ROOT = Path(__import__("os").environ.get("ANDROID_HOME", ROOT / ".tools/android-sdk"))
AAPT = next(
    (p for p in (
        SDK_ROOT / "build-tools/36.0.0/aapt.exe",
        SDK_ROOT / "build-tools/35.0.0/aapt.exe",
    ) if p.is_file()), None
)
if AAPT is None:
    raise SystemExit("AAPT_NOT_FOUND: Android SDK build-tools 35/36 required")
APKSIGNER = AAPT.with_name("apksigner.bat")
if not APKSIGNER.is_file():
    raise SystemExit("APKSIGNER_NOT_FOUND")

VERSIONS = {
    "standard": {
        "package": "com.phanthymotus.watch7",
        "version": "0.2.0-standard",
        "samsung": False,
    },
    "research": {
        "package": "com.example.heartrateweartest",
        "version": "0.2.0-research",
        "samsung": True,
    },
}
SAMSUNG_PERMISSIONS = {
    "android.permission.health.READ_OXYGEN_SATURATION",
    "android.permission.health.READ_SKIN_TEMPERATURE",
    "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
}
report = {"schema_version": 1, "protocol_supported": [1, 2], "artifacts": {}}


def inspect(variant: str, spec: dict) -> dict:
    apk = ROOT / "app/build/outputs/apk" / variant / "debug" / f"app-{variant}-debug.apk"
    if not apk.is_file():
        raise AssertionError(f"{variant}: missing APK")
    # aapt.exe cannot open CJK paths on some Windows hosts.
    with tempfile.TemporaryDirectory(prefix="watch7-s4-aapt-") as directory:
        staged_apk = Path(directory) / f"{variant}.apk"
        shutil.copy2(apk, staged_apk)
        process = subprocess.run(
            [str(AAPT), "dump", "badging", str(staged_apk)],
            capture_output=True, timeout=45, check=False,
        )
        signature = subprocess.run(
            [str(APKSIGNER), "verify", "--verbose", str(staged_apk)],
            capture_output=True, timeout=45, check=False,
        )
    if signature.returncode or b"Verifies" not in signature.stdout:
        raise AssertionError(f"{variant}: APK signature verification failed")
    if process.returncode:
        raise AssertionError(f"{variant}: AAPT_FAILED")
    data = process.stdout.decode("utf-8", "replace")
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", data, re.M)
    if not match:
        raise AssertionError(f"{variant}: badging identity not found")
    package, code, version = match.groups()
    assert package == spec["package"], (variant, package)
    assert code == "2", (variant, code)
    assert version == spec["version"], (variant, version)
    launcher = re.search(r"^launchable-activity: name='([^']+)'", data, re.M)
    assert launcher and launcher.group(1) == "com.example.heartrateweartest.MainActivity", (
        variant, launcher.group(1) if launcher else None
    )
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", data, re.M))
    assert "android.permission.health.READ_HEART_RATE" in permissions, (variant, permissions)
    if spec["samsung"]:
        assert SAMSUNG_PERMISSIONS <= permissions, (variant, sorted(permissions))
    else:
        assert not (SAMSUNG_PERMISSIONS & permissions), (variant, sorted(permissions))
    assert "sdkVersion:'30'" in data, f"{variant}: unexpected minSdk"
    assert "targetSdkVersion:'36'" in data, f"{variant}: unexpected targetSdk"
    assert "application-debuggable" in data, f"{variant}: expected debug build"
    with ZipFile(apk) as archive:
        assert archive.testzip() is None, f"{variant}: corrupt zip"
        dex = b"".join(
            archive.read(name) for name in archive.namelist()
            if name.startswith("classes") and name.endswith(".dex")
        )
        # Verify actual Samsung SDK bytecode, not just a reflection string
        # in the common UI.
        sdk_bytecode = b"HealthTrackerType" in dex and b"HealthTrackingService" in dex
        assert sdk_bytecode == spec["samsung"], (variant, sdk_bytecode)
        assert b"/v1/telemetry" in dex, f"{variant}: lost v1 protocol"
        assert b"/v2/telemetry" in dex, f"{variant}: lost v2 protocol"
    return {
        "path": str(apk.relative_to(ROOT)).replace("\\", "/"),
        "package": package,
        "version_code": int(code),
        "version_name": version,
        "build_type": "debug",
        "samsung_sdk_bytecode_present": sdk_bytecode,
        "size_bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "permissions": sorted(permissions),
        "apk_signature_verified": True,
        "installation": "development_only_not_production_signed",
    }


for name, spec in VERSIONS.items():
    report["artifacts"][name] = inspect(name, spec)
    row = report["artifacts"][name]
    print("S4_VERIFIED", name, row["package"], row["version_name"], row["size_bytes"])
    print("SHA256", row["sha256"])
report_path = ROOT / "S4_APK_VERIFICATION.json"
report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print("S4_VERIFICATION_REPORT", report_path)
print("S4_VERIFICATION_PASS")

"""Bundle the Standard debug APK for trusted-device acceptance only.

Never bundle Research/Samsung SDK APKs into a distribution archive.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
report_file = ROOT / "S4_APK_VERIFICATION.json"
report = json.loads(report_file.read_text(encoding="utf-8"))
standard = report["artifacts"]["standard"]
assert standard["package"] == "com.phanthymotus.watch7"
assert standard["samsung_sdk_bytecode_present"] is False
assert standard["apk_signature_verified"] is True
apk = ROOT / standard["path"]
assert apk.is_file()
assert hashlib.sha256(apk.read_bytes()).hexdigest() == standard["sha256"]
directory = ROOT / "releases/s4-dev"
directory.mkdir(parents=True, exist_ok=True)
output_apk = directory / "watch7-companion-standard-0.2.0-debug.apk"
shutil.copy2(apk, output_apk)
notes = (
    "Watch7 S4 Standard debug build — for trusted laboratory acceptance ONLY.\n"
    "Package: com.phanthymotus.watch7\n"
    "Version: 0.2.0-standard (versionCode 2)\n"
    f"APK SHA-256: {standard['sha256']}\n"
    "On-Watch settings: Driver LAN endpoint http://<DRIVER-IP>:15751\n"
    "Needs individually configured Watch7 device token. Never share credentials.\n"
    "Not a production APK: debug-signed, HTTP data path, S5 live acceptance pending.\n"
    "Samsung proprietary SDK intentionally not included.\n"
)
(directory / "INSTALL.txt").write_text(notes, encoding="utf-8")
(directory / "manifest.json").write_text(
    json.dumps({"schema_version": 1, "artifact": standard}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
zippath = directory / "watch7-standard-0.2.0-debug.zip"
with ZipFile(zippath, "w", compression=ZIP_DEFLATED) as z:
    for name in (output_apk.name, "manifest.json", "INSTALL.txt"):
        z.write(directory / name, arcname=name)
print("S4_PACKAGE_CREATED", zippath)
print("STANDARD_APK_SHA256", standard["sha256"])
print("ZIP_BYTES", zippath.stat().st_size)
print("S4_PACKAGE_SCOPE", "standard_debug_only_no_samsung_binary")

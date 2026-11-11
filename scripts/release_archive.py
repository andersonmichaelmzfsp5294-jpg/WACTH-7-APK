"""Validate and package an exact Git source revision for Watch7.

Only committed source is archived. The Samsung AAR, private measurements,
APK files, signing material and machine-specific toolchains are excluded.
Run from the Companion root:
    python scripts/release_archive.py audit
    python scripts/release_archive.py create
    python scripts/release_archive.py verify
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "compatibility/watch7-r17.json"
REQUIRED = {
    ".gitignore", ".gitattributes", "README.md",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "build.gradle.kts",
    "settings.gradle.kts", "gradle.properties", "app/build.gradle.kts",
    "app/gradle.lockfile",
    "build_s4.py", "scripts/release_archive.py", "compatibility/watch7-r17.json",
    "app/src/main/AndroidManifest.xml", "app/src/research/AndroidManifest.xml",
    "app/src/main/java/com/example/heartrateweartest/MainActivity.kt",
    "app/src/research/java/com/example/heartrateweartest/ResearchV3Gateway.kt",
    "app/src/research/java/com/example/heartrateweartest/SamsungSweatTracker.java",
}
BANNED_SUFFIXES = {
    ".apk", ".aab", ".aar", ".jks", ".keystore", ".p12", ".pfx", ".pem",
    ".key", ".zip", ".tar", ".gz", ".bundle", ".db", ".sqlite", ".sqlite3",
    ".jsonl", ".wav", ".pcm", ".log", ".env", ".secret", ".credentials",
}
BANNED_COMPONENTS = {
    ".tools", ".gradle", ".kotlin", "__pycache__", "build", "releases",
    "recordings", "measurements", "secrets", "credentials", ".idea",
}
EXPOSED_SIGNATURES = {
    "private_key_header": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "github_pat": re.compile(rb"github_pat_[a-zA-Z0-9_]{16,}"),
    "github_token": re.compile(rb"gh[pousr]_[a-zA-Z0-9]{20,}"),
    "openai_key": re.compile(rb"sk-(?:proj-|live-|test-)?[A-Za-z0-9_-]{24,}"),
}
ALLOWED_BINARIES = {"gradle/wrapper/gradle-wrapper.jar"}
RELEASE = json.loads(LOCK.read_text(encoding="utf-8"))["release_id"]


def git(*arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", *arguments], cwd=ROOT, capture_output=True, check=False
    )
    if completed.returncode:
        raise RuntimeError(
            "git command failed: " + " ".join(arguments)
            + " (exit=" + str(completed.returncode) + ")"
        )
    return completed.stdout


def files() -> list[str]:
    return sorted(x.decode("utf-8") for x in
                  git("ls-files", "-z").split(b"\0") if x)


def audit() -> list[str]:
    tracked = files()
    missing = REQUIRED - set(tracked)
    if missing:
        raise RuntimeError("Required source missing: " + ", ".join(sorted(missing)))
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if len(lock["driver"]["commit"]) != 40 or lock["driver"]["card_count"] != 17:
        raise RuntimeError("Invalid exact Driver lock")
    if not tracked:
        raise RuntimeError("No files tracked")
    bad = []
    for rel in tracked:
        f = Path(rel)
        lower = rel.lower()
        if (f.suffix.lower() in BANNED_SUFFIXES or
            any(part.lower() in BANNED_COMPONENTS for part in f.parts) or
            (f.suffix.lower() == ".jar" and rel not in ALLOWED_BINARIES) or
            any(word in lower for word in ("keystore", "password.txt", "token.txt",
                                           "recording.", "secret.", "local.properties"))):
            bad.append((rel, "excluded path"))
            continue
        content = (ROOT / rel).read_bytes()
        if len(content) > 2_000_000:
            bad.append((rel, "unexpected large source"))
            continue
        if rel not in ALLOWED_BINARIES and b"\0" in content:
            bad.append((rel, "unexpected binary content"))
            continue
        if rel not in ALLOWED_BINARIES:
            for signature, pattern in EXPOSED_SIGNATURES.items():
                if pattern.search(content):
                    bad.append((rel, signature))
    if bad:
        # Do not print secrets; only offending paths and finding categories.
        raise RuntimeError("Archive audit blocked: " + repr(bad))
    print("RELEASE_AUDIT_PASS", RELEASE, "files", len(tracked),
          "Driver", lock["driver"]["commit"], "variants", 2)
    return tracked


def outputs() -> tuple[Path, Path, Path]:
    folder = ROOT.parent / "archive" / RELEASE
    return (folder / f"{RELEASE}.source.zip",
            folder / f"{RELEASE}.git.bundle",
            folder / "ARCHIVE_PROVENANCE.json")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def create() -> None:
    tracked = audit()
    if git("status", "--porcelain").strip():
        raise RuntimeError("Commit all source edits before creating a release")
    revision = git("rev-parse", "HEAD").decode().strip()
    tree = git("rev-parse", "HEAD^{tree}").decode().strip()
    archive, bundle, meta = outputs()
    archive.parent.mkdir(parents=True, exist_ok=True)
    git("archive", "--format=zip", "-o", str(archive), revision)
    git("bundle", "create", str(bundle), "--all")
    with zipfile.ZipFile(archive) as zf:
        if zf.testzip() is not None:
            raise RuntimeError("Corrupt source archive")
        names = {n for n in zf.namelist() if not n.endswith("/")}
    if names != set(tracked):
        raise RuntimeError("ZIP contents differ from committed Git source")
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    manifest = {
        "release_id": RELEASE,
        "companion_commit": revision,
        "companion_tree": tree,
        "driver_repository": lock["driver"]["repository"],
        "driver_commit": lock["driver"]["commit"],
        "companion_variants": [v["flavor"] for v in lock["companion"]["variants"]],
        "source_count": len(tracked),
        "source_zip": archive.name,
        "source_zip_sha256": sha256(archive),
        "git_bundle": bundle.name,
        "git_bundle_sha256": sha256(bundle),
        "contains_samsung_sdk_binary": False,
        "contains_device_credentials": False,
        "hardware_verified": False,
    }
    meta.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")
    print("RELEASE_ARCHIVE_PASS", archive.parent, "companion", revision,
          "Driver", manifest["driver_commit"], "files", len(tracked))


def verify() -> None:
    audit()
    archive, bundle, meta = outputs()
    if not (archive.is_file() and bundle.is_file() and meta.is_file()):
        raise RuntimeError("Archive artifacts missing")
    manifest = json.loads(meta.read_text(encoding="utf-8"))
    for path, field in ((archive, "source_zip_sha256"),
                        (bundle, "git_bundle_sha256")):
        if sha256(path) != manifest[field]:
            raise RuntimeError("Archive checksum mismatch: " + path.name)
    with zipfile.ZipFile(archive) as zf:
        if zf.testzip() is not None:
            raise RuntimeError("ZIP corruption")
        if "compatibility/watch7-r17.json" not in zf.namelist():
            raise RuntimeError("Version lock missing from ZIP")
        lock = json.loads(zf.read("compatibility/watch7-r17.json"))
        if lock["driver"]["commit"] != manifest["driver_commit"]:
            raise RuntimeError("Driver SHA mismatch")
    if manifest["companion_commit"] != git("rev-parse", "HEAD").decode().strip():
        raise RuntimeError("Companion Git SHA mismatch")
    git("bundle", "verify", str(bundle))
    print("RELEASE_VERIFY_PASS", RELEASE, "companion",
          manifest["companion_commit"], "Driver", manifest["driver_commit"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("audit", "create", "verify"))
    opts = parser.parse_args()
    try:
        {"audit": audit, "create": create, "verify": verify}[opts.action]()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as err:
        print("RELEASE_ARCHIVE_FAILED", err, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

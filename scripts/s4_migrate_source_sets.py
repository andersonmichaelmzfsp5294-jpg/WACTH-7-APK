"""S4 one-time, idempotent move of Samsung-only sources into research flavor.

This script never deletes content: it only atomically relocates two source files
under the same filesystem. It aborts if both old and new files exist.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
package = Path("java/com/example/heartrateweartest")
names = ("SamsungMeasurementActivity.kt", "SamsungTrackerBridge.java")

for name in names:
    old = root / "app/src/main" / package / name
    new = root / "app/src/research" / package / name
    if old.is_file() and new.exists():
        raise SystemExit(f"Both source copies exist; refused: {name}")
    if not old.is_file() and not new.is_file():
        raise SystemExit(f"Missing original and research source: {name}")
    if old.is_file():
        new.parent.mkdir(parents=True, exist_ok=True)
        old.replace(new)
        print("MOVED", name)
    else:
        print("ALREADY_MIGRATED", name)

#!/usr/bin/env python3
"""Run three real scenarios; retain only raw records from this app and log tag."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT")))
parser.add_argument("--device", required=True, help="ADB serial: only this device and the demo package are targeted")
parser.add_argument("--output", type=Path, default=ROOT / "target/sstic2027/demo")
args = parser.parse_args()
if not args.sdk:
    parser.error("Set ANDROID_HOME or use --sdk.")
adb = [str(Path(args.sdk) / "platform-tools/adb"), "-s", args.device]
out = args.output.resolve()
package = "com.example.andrologsstic"
def run(*command):
    return subprocess.check_output([*adb, *command], text=True)
def shell(*command):
    # adb shell joins its arguments; quoting here preserves the '&' in deep links.
    return run("shell", shlex.join(command))

scenarios = [
    ("accepted", "andrologdemo://open?url=%20https%3A%2F%2Ftrusted.example%2Faccount%20", "https://trusted.example/account", True, "HostPolicy"),
    ("rejected", "andrologdemo://open?url=https%3A%2F%2Funtrusted.example%2Faccount", "https://untrusted.example/account", False, "HostPolicy"),
    ("deny-policy", "andrologdemo://open?url=https%3A%2F%2Ftrusted.example%2Faccount&policy=deny", "https://trusted.example/account", False, "DenyPolicy"),
]
print(run("install", "-r", str(out / "instrumented.apk")), end="")
for name, uri, normalized, allowed, policy in scenarios:
    directory = out / name
    directory.mkdir(parents=True, exist_ok=True)
    shell("am", "force-stop", package)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    temporary = directory / "capture.tmp"
    with temporary.open("w") as stream:
        capture = subprocess.Popen([*adb, "logcat", "-v", "threadtime", "-T", "1", "-s", "SSTIC_DEMO:V", "*:S"], stdout=stream, stderr=subprocess.PIPE)
        try:
            time.sleep(0.3)
            launch = shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", uri, "-n", package + "/.MainActivity")
            if "Status: ok" not in launch:
                raise RuntimeError(launch)
            pid = shell("pidof", package).strip()
            if not pid.isdigit():
                raise RuntimeError("Expected exactly one running demo process: " + pid)
            time.sleep(1)
        finally:
            capture.send_signal(signal.SIGINT)
            _, errors = capture.communicate(timeout=10)
    pattern = re.compile(r"^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+" + re.escape(pid) + r"\s+\d+\s+[VDIWEFA]\s+SSTIC_DEMO\s*:")
    # Keep original complete lines, without rewriting or inventing any event.
    lines = [line for line in temporary.read_text().splitlines(keepends=True) if pattern.match(line)]
    if not lines:
        raise RuntimeError("No AndroLog records: " + errors.decode(errors="replace"))
    log = directory / "runtime.log"
    log.write_text("".join(lines))
    temporary.unlink()
    metadata = {
        "scenario": name, "uri": uri, "device": args.device, "androidRelease": shell("getprop", "ro.build.version.release").strip(),
        "apiLevel": shell("getprop", "ro.build.version.sdk").strip(), "pid": int(pid), "startedAt": started,
        "expected": {"normalized": normalized, "allowed": allowed, "policy": policy},
        "logLines": len(lines), "logSha256": hashlib.sha256(log.read_bytes()).hexdigest(),
        "instrumentedSha256": hashlib.sha256((out / "instrumented.apk").read_bytes()).hexdigest(),
        "selection": "Original logcat threadtime lines, filtered by PID and exact SSTIC_DEMO tag; no global log buffer cleared."
    }
    (directory / "capture.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(f"{name}: {len(lines)} original records")
shell("am", "force-stop", package)

#!/usr/bin/env python3
"""Build the training APK using only a JDK and Android SDK (no Gradle download)."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT")))
parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
parser.add_argument("--build-tools", default="36.1.0")
parser.add_argument("--platform", default="android-36")
parser.add_argument("--output", type=Path, default=ROOT / "target/sstic2027/password-demo")
args = parser.parse_args()
if not args.sdk or not args.java_home:
    parser.error("Set ANDROID_HOME and JAVA_HOME, or use --sdk and --java-home.")
sdk, java, out = Path(args.sdk).resolve(), Path(args.java_home).resolve(), args.output.resolve()
build = out / "build"
build.mkdir(parents=True, exist_ok=True)
tools, android = sdk / "build-tools" / args.build_tools, sdk / "platforms" / args.platform / "android.jar"
env = dict(os.environ, JAVA_HOME=str(java), PATH=str(java / "bin") + os.pathsep + os.environ.get("PATH", ""))
def run(*command):
    subprocess.run([str(v) for v in command], check=True, env=env)
classes, dex = build / "classes", build / "dex"
for directory in (classes, dex):
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir()
run(java / "bin/javac", "-source", "8", "-target", "8", "-bootclasspath", android,
    "-encoding", "UTF-8", "-g", "-d", classes, *sorted((HERE / "src").rglob("*.java")))
run(tools / "d8", "--lib", android, "--min-api", "23", "--output", dex, *sorted(classes.rglob("*.class")))
unsigned, aligned = build / "unsigned.apk", build / "aligned.apk"
run(tools / "aapt", "package", "-f", "-M", HERE / "AndroidManifest.xml", "-I", android, "-F", unsigned)
with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write(dex / "classes.dex", "classes.dex")
run(tools / "zipalign", "-p", "-f", "4", unsigned, aligned)
# Dedicated public training key; never use it to sign a production application.
key = build / "training.keystore"
if not key.exists():
    run(java / "bin/keytool", "-genkeypair", "-keystore", key, "-alias", "training", "-storepass", "training-only",
        "-keypass", "training-only", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
        "-dname", "CN=AndroLog SSTIC Password Demo")
run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "training", "--ks-pass", "pass:training-only",
    "--key-pass", "pass:training-only", "--out", out / "original.apk", aligned)
run(tools / "apksigner", "verify", out / "original.apk")
print(out / "original.apk")

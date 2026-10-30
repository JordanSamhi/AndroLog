#!/usr/bin/env bash
set -euo pipefail

# Run from the AndroLog repository root on macOS ARM64.
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo 'Use macOS ARM64.' >&2; exit 1; }
[[ -f pom.xml && -f electron-gui/package-lock.json ]] || { echo 'Run from the repository root.' >&2; exit 1; }
[[ "$(node -p process.arch)" == arm64 ]] || { echo 'Use ARM64 Node.js.' >&2; exit 1; }
androlog_work="$PWD/target/sstic2027/artifact"
mkdir -p "$androlog_work/jdk" "$androlog_work/evidence"
androlog_release='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9'
androlog_jdk='OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz'
androlog_sources='OpenJDK21U-jdk-sources_21.0.13_9.tar.gz'
curl --fail --location "$androlog_release/$androlog_jdk" -o "$androlog_work/$androlog_jdk"
curl --fail --location "$androlog_release/$androlog_sources" -o "$androlog_work/$androlog_sources"
(
  cd "$androlog_work"
  printf '%s  %s\n' bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a "$androlog_jdk" b5232c9980d8a0f0459676c084c41c6e2c771ed9e785890b03c6a438406ad1ca "$androlog_sources" | shasum -a 256 -c -
)
tar -xzf "$androlog_work/$androlog_jdk" -C "$androlog_work/jdk"
export JAVA_HOME="$androlog_work/jdk/jdk-21.0.13+9/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
python3 - <<'PY'
from pathlib import Path
props = Path('src/main/resources/config.properties').read_text()
for line in props.splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, _, value = line.partition('=')
        if key.strip() in ('apksignerPath', 'zipalignPath') and value.strip():
            raise SystemExit('Keep machine-specific signing paths outside the tracked configuration.')
PY
mvn package | tee "$androlog_work/maven-package.log"
mvn dependency:sources -DincludeScope=runtime > "$androlog_work/evidence/dependency-sources.log"
mvn dependency:list -DincludeScope=runtime -DoutputFile="$androlog_work/evidence/runtime-dependencies.txt" > "$androlog_work/evidence/dependency-list.log"
(
  cd electron-gui
  npm ci
  npm test | tee "$androlog_work/electron-tests.log"
  npm run dist -- --config.artifactName=AndroLog-0.1-SSTIC2027-macos-arm64.zip | tee "$androlog_work/package.log"
)
echo 'Re-run the launch and demo smoke tests before assembling a new distribution.'

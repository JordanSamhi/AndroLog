#!/bin/sh
# Optional self-contained desktop app. Run with JDK 17+ providing jpackage.
set -eu
androlog_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
androlog_jar="$androlog_root/target/androlog-gui-0.1.jar"
if [ ! -f "$androlog_jar" ]; then
    printf '%s\n' "Run mvn package in the repository before packaging the desktop app." >&2
    exit 1
fi
androlog_package=jpackage
if [ -n "${JAVA_HOME:-}" ]; then androlog_package="$JAVA_HOME/bin/jpackage"; fi
androlog_input=$(mktemp -d "$androlog_root/target/gui-package-input.XXXXXX")
androlog_stage=$(mktemp -d "${TMPDIR:-/tmp}/androlog-desktop.XXXXXX")
trap 'rm -rf "$androlog_input" "$androlog_stage"' EXIT HUP INT TERM
androlog_destination=${1:-"$androlog_root/target/desktop"}
androlog_app=AndroLog
androlog_output=AndroLog
if [ "$(uname -s)" = Darwin ]; then androlog_app=AndroLog.app; androlog_output=AndroLog-macos.zip; fi
if [ -e "$androlog_destination/$androlog_output" ]; then
    printf '%s\n' "Destination already exists: $androlog_destination/$androlog_output. Choose a new output folder as the first argument." >&2
    exit 1
fi
cp "$androlog_jar" "$androlog_input/androlog-gui-0.1.jar"
# Native bundle build number (macOS requires a positive first component).
set -- --type app-image --name AndroLog --app-version 1 --vendor "Jordan Samhi" \
    --description "Android APK instrumentation studio" \
    --input "$androlog_input" --main-jar androlog-gui-0.1.jar \
    --dest "$androlog_stage" \
    --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs \
    --jlink-options "--strip-debug --no-header-files --no-man-pages"
if [ "$(uname -s)" = Darwin ]; then
    set -- "$@" --mac-package-identifier com.jordansamhi.androlog --mac-package-name AndroLog
fi
# Keep native Java commands: every instrumentation run starts its own JVM.
"$androlog_package" "$@"
mkdir -p "$androlog_destination"
if [ "$(uname -s)" = Darwin ]; then
    # Keep a signed app intact in cloud-synced checkouts, which attach Finder metadata to .app folders.
    /usr/bin/codesign --verify --deep --strict "$androlog_stage/$androlog_app"
    /usr/bin/ditto -c -k --keepParent "$androlog_stage/$androlog_app" "$androlog_destination/$androlog_output"
else
    cp -R "$androlog_stage/$androlog_app" "$androlog_destination/$androlog_output"
fi
printf '%s\n' "Desktop package created at $androlog_destination/$androlog_output"

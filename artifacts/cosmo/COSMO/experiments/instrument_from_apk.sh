#!/usr/bin/env bash

export PATH="${PATH}:${ANDROID_HOME}/build-tools/30.0.2:${GITHUB_WORKSPACE}/dex2jar-2.0"

mkdir -p apks

repo="${1}"

app_name=$(basename "${repo}")

echo "Cloning and building ${app_name}"

git clone --depth 1 --recurse-submodules --shallow-submodules --quiet "${repo}"

# Skip projects using Kotlin.
find "${app_name}" -name "*.kt" | grep -q . \
    && { echo "${repo}" >>apks/kotlin-apps.csv; rm -rf "${app_name}"; exit 0; } \
    || echo "${repo}" >>apks/java-apps.csv

gradlew_file="$(find "${app_name}" -name "gradlew" | head -1)"

chmod +x "${gradlew_file}"
"${gradlew_file}" assembleDebug -p "$(dirname "${gradlew_file}")" </dev/null \
    && echo "${repo}" >>apks/original-build-success.csv \
    || { echo "${repo}" >>apks/original-build-error.csv; rm -rf "${app_name}" .gradle/; exit 0; }

mkdir -p "${app_name}/tmp"
apk_file="$(find "${app_name}" -name "*.apk" | tail -1)"
cp -v "${apk_file}" "${app_name}/tmp/${app_name}.apk"

# Skip multidex projects.
dex_count="$(unzip -l "${apk_file}" | grep "\.dex" | wc -l)"
if [ "${dex_count}" -ne 1 ]; then
    echo "${repo}" >>apks/apk-multi-dex.csv
    rm -rf "${app_name}" .gradle/
    exit 0
else
    echo "${repo}" >>apks/apk-single-dex.csv
fi

python cli.py "${app_name}/tmp/${app_name}.apk" \
    && echo "${repo}" >>apks/apk-instrument-success.csv \
    || { echo "${repo}" >>apks/apk-instrument-error.csv; rm -rf "${app_name}" .gradle/; exit 0; }

cp -v "output_apks/${app_name}.apk" "apks/${app_name}.apk"

rm -rf "${app_name}" .gradle/

killall java

exit 0

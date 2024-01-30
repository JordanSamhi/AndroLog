#!/usr/bin/env bash

mkdir -p apks

repo="${1}"

app_name=$(basename "${repo}")

echo "Cloning and building ${app_name}"

git clone --depth 1 --recurse-submodules --shallow-submodules --quiet "${repo}"

gradlew_file="$(find "${app_name}" -name "gradlew" | head -1)"

chmod +x "${gradlew_file}"
"${gradlew_file}" assembleDebug -p "$(dirname "${gradlew_file}")" </dev/null \
    && echo "${repo}" >>apks/build-success.csv \
    || { echo "${repo}" >>apks/build-error.csv; rm -rf "${app_name}" .gradle/; exit 0; }

find "${app_name}" -name "*.apk" -exec cp -v "{}" "apks/${app_name}.apk" ";"

rm -rf "${app_name}" .gradle/

killall java

exit 0

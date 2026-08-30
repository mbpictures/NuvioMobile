#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "${repository_root}"

container_flag="${IOS_PACKAGE_CONTAINER_FLAG:--project}"
container_path="${IOS_PACKAGE_CONTAINER:-iosApp/iosApp.xcodeproj}"
scheme="${IOS_SCHEME:-iosApp}"
attempts="${IOS_PACKAGE_RESOLVE_ATTEMPTS:-3}"

resolve_command=(
    xcodebuild
    -resolvePackageDependencies
    "${container_flag}" "${container_path}"
    -scheme "${scheme}"
    -skipPackagePluginValidation
    -skipMacroValidation
)
if [[ -n "${IOS_DERIVED_DATA_PATH:-}" ]]; then
    resolve_command+=(-derivedDataPath "${IOS_DERIVED_DATA_PATH}")
fi

for (( attempt = 1; attempt <= attempts; attempt++ )); do
    if "${resolve_command[@]}"; then
        exit 0
    fi

    echo "Swift package resolution failed (attempt ${attempt}/${attempts})." >&2
    if (( attempt == attempts )); then
        break
    fi

    echo "Clearing the SwiftPM caches before retrying." >&2
    rm -rf \
        "${HOME}/Library/Caches/org.swift.swiftpm" \
        "${HOME}/Library/org.swift.swiftpm"
    if [[ -n "${IOS_DERIVED_DATA_PATH:-}" ]]; then
        rm -rf "${IOS_DERIVED_DATA_PATH}/SourcePackages"
    fi
    sleep $(( attempt * 15 ))
done

echo "Could not resolve the iOS Swift package graph after ${attempts} attempts." >&2
exit 1

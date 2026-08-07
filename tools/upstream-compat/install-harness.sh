#!/usr/bin/env bash
set -euo pipefail

target_root="${1:?usage: install-harness.sh TARGET_ROOT}"
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
apple_sha="1f59e814f90c3f489f48d68262cb1bf640bf6181"
android_sha="094657efa0aabbb6f71c9050149d1d01aee96400"

test "$(git -C "$target_root/apple" rev-parse HEAD)" = "$apple_sha"
test "$(git -C "$target_root/android" rev-parse HEAD)" = "$android_sha"
test -z "$(git -C "$target_root/apple" status --porcelain)"
test -z "$(git -C "$target_root/android" status --porcelain)"

cp "$script_root/apple/BitMessageFixtureExportTests.swift" "$target_root/apple/localPackages/BitFoundation/Tests/BitFoundationTests/"
cp "$script_root/apple/BitMessageFixtureAcceptanceTests.swift" "$target_root/apple/localPackages/BitFoundation/Tests/BitFoundationTests/"
cp "$script_root/apple/BitMessageAnnouncementFixtureExportTests.swift" "$target_root/apple/bitchatTests/"
cp "$script_root/apple/BitMessageAnnouncementFixtureAcceptanceTests.swift" "$target_root/apple/bitchatTests/"
cp "$script_root/android/BitMessageFixtureExportTest.kt" "$target_root/android/app/src/test/kotlin/com/bitchat/android/protocol/"
cp "$script_root/android/BitMessageFixtureAcceptanceTest.kt" "$target_root/android/app/src/test/kotlin/com/bitchat/android/protocol/"

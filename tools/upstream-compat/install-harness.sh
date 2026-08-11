#!/usr/bin/env bash
set -euo pipefail

target_root="${1:?usage: install-harness.sh TARGET_ROOT}"
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
apple_sha="1f59e814f90c3f489f48d68262cb1bf640bf6181"
android_sha="094657efa0aabbb6f71c9050149d1d01aee96400"
apple_foundation_test_dir="$target_root/apple/localPackages/BitFoundation/Tests/BitFoundationTests"
apple_app_test_dir="$target_root/apple/bitchatTests"
android_protocol_test_dir="$target_root/android/app/src/test/kotlin/com/bitchat/android/protocol"

test "$(git -C "$target_root/apple" rev-parse HEAD)" = "$apple_sha"
test "$(git -C "$target_root/android" rev-parse HEAD)" = "$android_sha"

# Installation is intentionally repeatable for the disposable clones. Remove
# only the harness-owned files before proving no unrelated upstream edits exist.
rm -f \
  "$apple_foundation_test_dir/BitMessageFixtureExportTests.swift" \
  "$apple_foundation_test_dir/BitMessageFixtureAcceptanceTests.swift" \
  "$apple_app_test_dir/BitMessageAnnouncementFixtureExportTests.swift" \
  "$apple_app_test_dir/BitMessageAnnouncementFixtureAcceptanceTests.swift" \
  "$apple_app_test_dir/BitMessagePhase4EvidenceTests.swift" \
  "$android_protocol_test_dir/BitMessageFixtureExportTest.kt" \
  "$android_protocol_test_dir/BitMessageFixtureAcceptanceTest.kt" \
  "$android_protocol_test_dir/BitMessagePhase4EvidenceTest.kt"

test -z "$(git -C "$target_root/apple" status --porcelain)"
test -z "$(git -C "$target_root/android" status --porcelain)"

mkdir -p "$android_protocol_test_dir"

cp "$script_root/apple/BitMessageFixtureExportTests.swift" "$apple_foundation_test_dir/"
cp "$script_root/apple/BitMessageFixtureAcceptanceTests.swift" "$apple_foundation_test_dir/"
cp "$script_root/apple/BitMessageAnnouncementFixtureExportTests.swift" "$apple_app_test_dir/"
cp "$script_root/apple/BitMessageAnnouncementFixtureAcceptanceTests.swift" "$apple_app_test_dir/"
cp "$script_root/apple/BitMessagePhase4EvidenceTests.swift" "$apple_app_test_dir/"
cp "$script_root/android/BitMessageFixtureExportTest.kt" "$android_protocol_test_dir/"
cp "$script_root/android/BitMessageFixtureAcceptanceTest.kt" "$android_protocol_test_dir/"
cp "$script_root/android/BitMessagePhase4EvidenceTest.kt" "$android_protocol_test_dir/"

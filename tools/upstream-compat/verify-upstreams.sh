#!/usr/bin/env bash
set -euo pipefail

target_root="${1:?usage: verify-upstreams.sh TARGET_ROOT}"
: "${ANDROID_HOME:?ANDROID_HOME must identify an installed Android SDK}"

swift test --package-path "$target_root/apple/localPackages/BitFoundation" --filter BitMessageFixtureExportTests
swift test --package-path "$target_root/apple/localPackages/BitFoundation" --filter BitMessageFixtureAcceptanceTests

xcodebuild test \
  -project "$target_root/apple/bitchat.xcodeproj" \
  -scheme "bitchat (macOS)" \
  -destination "platform=macOS" \
  -derivedDataPath /tmp/bitmessage-phase1-apple-derived-data \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  -only-testing:bitchatTests_macOS/BitMessageAnnouncementFixtureExportTests \
  -only-testing:bitchatTests_macOS/BitMessageAnnouncementFixtureAcceptanceTests

"$target_root/android/gradlew" -p "$target_root/android" :app:testDebugUnitTest \
  --tests "com.bitchat.android.protocol.BitMessageFixtureExportTest" \
  --tests "com.bitchat.android.protocol.BitMessageFixtureAcceptanceTest"

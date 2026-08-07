#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

./gradlew \
  :androidApp:assembleDebug \
  :sharedLogic:linkDebugFrameworkIosSimulatorArm64 \
  :core:common:allTests \
  :sharedLogic:allTests \
  :sharedUI:allTests \
  :feature:root:allTests \
  :core:testing:allTests \
  :core:testing:compatibilityCheck

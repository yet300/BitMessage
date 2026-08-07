#!/usr/bin/env bash
set -euo pipefail

target_root="${1:?usage: prepare-upstreams.sh TARGET_ROOT}"
apple_sha="1f59e814f90c3f489f48d68262cb1bf640bf6181"
android_sha="094657efa0aabbb6f71c9050149d1d01aee96400"

mkdir -p "$target_root"

clone_pinned() {
  local repository="$1"
  local directory="$2"
  local sha="$3"
  if [[ ! -d "$directory/.git" ]]; then
    git clone "$repository" "$directory"
  fi
  git -C "$directory" fetch origin "$sha"
  git -C "$directory" checkout --detach "$sha"
  test "$(git -C "$directory" rev-parse HEAD)" = "$sha"
}

clone_pinned "https://github.com/permissionlesstech/bitchat.git" "$target_root/apple" "$apple_sha"
clone_pinned "https://github.com/permissionlesstech/bitchat-android.git" "$target_root/android" "$android_sha"

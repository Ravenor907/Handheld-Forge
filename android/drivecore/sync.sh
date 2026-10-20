#!/usr/bin/env bash
# Copies the shared drive engine from a Raven Disk Utility checkout into drivecore/src.
#   drivecore/sync.sh ../../Raven-Disk-Utility        (a local checkout)
#   drivecore/sync.sh --check                          (CI: compare with the commit in SOURCE)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if [ "${1:-}" = "--check" ]; then
  commit=$(cat "$here/SOURCE")
  tmp=$(mktemp -d)
  git clone -q https://github.com/Ravenor907/Raven-Disk-Utility "$tmp/raven"
  git -C "$tmp/raven" checkout -q "$commit"
  if diff -r "$tmp/raven/core/src/main/kotlin" "$here/src/main/kotlin" >/dev/null; then
    echo "drivecore matches Raven Disk Utility $commit"
  else
    echo "::error title=drivecore drifted::drivecore/src differs from Raven Disk Utility $commit. Edit the engine in Raven, then run drivecore/sync.sh."
    diff -r "$tmp/raven/core/src/main/kotlin" "$here/src/main/kotlin" | head -40
    exit 1
  fi
  exit 0
fi
raven="${1:?usage: sync.sh <path to Raven-Disk-Utility checkout>}"
rm -rf "$here/src/main/kotlin"
mkdir -p "$here/src/main"
cp -r "$raven/core/src/main/kotlin" "$here/src/main/kotlin"
git -C "$raven" rev-parse HEAD > "$here/SOURCE"
echo "Synced from Raven Disk Utility $(cat "$here/SOURCE")"

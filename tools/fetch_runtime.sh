#!/usr/bin/env bash
# Fetches the PRoot binary used by the Linux runtime (com.winlator.LinuxRuntime).
#
# proot v5.3.0 ships an official static aarch64 build. It is not bundled in the repo
# because it is a 1.4 MB binary; the checksum below is the same one LinuxRuntime.java
# verifies against before it trusts the file. Run this before :app:assembleDebug when
# building from a fresh clone.
#
# proot is GPL-2.0-or-later. It is redistributed unmodified; see NOTICE.

set -euo pipefail

VERSION="v5.3.0"
URL="https://github.com/proot-me/proot/releases/download/${VERSION}/proot-${VERSION}-aarch64-static"
SHA256="fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${ROOT_DIR}/app/src/main/assets/runtime"
DEST="${DEST_DIR}/proot-aarch64"

mkdir -p "${DEST_DIR}"

if [ ! -f "${DEST}" ]; then
    echo "downloading ${URL}"
    curl -fL "${URL}" -o "${DEST}"
fi

echo "${SHA256}  ${DEST}" | sha256sum -c -
chmod +x "${DEST}"
echo "proot ready at ${DEST}"

#! /bin/bash
set -euxo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

cd ${SCRIPT_DIR}/build/_deps/absl-src/ && patch -p1 < ${SCRIPT_DIR}/patch-absl.txt
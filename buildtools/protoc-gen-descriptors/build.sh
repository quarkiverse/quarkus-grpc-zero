#! /bin/bash
set -euxo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

${SCRIPT_DIR}/get_protobuf.sh

${SCRIPT_DIR}/patch_protobuf.sh

${SCRIPT_DIR}/prepare_build.sh
${SCRIPT_DIR}/patch_absl.sh

${SCRIPT_DIR}/build_protoc-gen-descriptors.sh

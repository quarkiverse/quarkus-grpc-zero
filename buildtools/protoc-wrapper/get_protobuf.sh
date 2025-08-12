#! /bin/bash
set -euxo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

mkdir -p ${SCRIPT_DIR}/protobuf
curl -L https://github.com/protocolbuffers/protobuf/archive/refs/tags/$(cat ${SCRIPT_DIR}/protobuf-version.txt | awk '{$1=$1};1').tar.gz | tar -xz --strip-components 1 -C ${SCRIPT_DIR}/protobuf

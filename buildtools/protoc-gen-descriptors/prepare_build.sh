#! /bin/bash
set -euxo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

CFLAGS="-D_WASI_EMULATED_MMAN -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_SIGNAL -DABSL_HAVE_MMAP -DABSL_FORCE_THREAD_IDENTITY_MODE=1"
CXXFLAGS="$CFLAGS -fno-exceptions"
LDFLAGS="-lwasi-emulated-process-clocks -lwasi-emulated-mman -lwasi-emulated-signal -Wl,--max-memory=4294967296 -Wl,--global-base=1024"

mkdir -p $SCRIPT_DIR/build

(
    cd $SCRIPT_DIR/build

    cmake \
        -DCMAKE_TOOLCHAIN_FILE="/usr/share/cmake/wasi-sdk-pthread.cmake" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$CFLAGS" \
        -DCMAKE_CXX_FLAGS="$CXXFLAGS" \
        -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS" \
        -Dprotobuf_BUILD_TESTS=off \
        -S $SCRIPT_DIR/protobuf
)
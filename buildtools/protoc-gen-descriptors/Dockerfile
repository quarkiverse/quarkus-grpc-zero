FROM ghcr.io/webassembly/wasi-sdk:sha-7ff81cb

RUN apt-get update && apt-get install -y binaryen git patch curl

WORKDIR /workspace

ADD buildtools/protoc-gen-descriptors/* .

RUN ./get_protobuf.sh

RUN ./patch_protobuf.sh

RUN ./prepare_build.sh
RUN ./patch_absl.sh

RUN cmake --build build --target plugins -j2

RUN wasm-opt -o build/protoc-gen-descriptors.wasm --low-memory-unused --flatten --rereloop --converge -O3 build/protoc-gen-descriptor-31.1.0


.PHONY: clean
clean:
	rm -f wasm/*

.PHONY: build
build: build-protoc-gen-grpc-java build-protoc-gen-descriptors

.PHONY: build-protoc-gen-grpc-java
build-protoc-gen-grpc-java:
	docker build . -f buildtools/protoc-gen-grpc-java/Dockerfile -t protoc-gen-grpc-java
	docker create --name dummy-protoc-gen-grpc-java protoc-gen-grpc-java
	docker cp dummy-protoc-gen-grpc-java:/workspace/build/protoc-gen-grpc-java.wasm wasm/protoc-gen-grpc-java.wasm
	docker rm -f dummy-protoc-gen-grpc-java

.PHONY: build-protoc-gen-descriptors
build-protoc-gen-descriptors:
	docker build . -f buildtools/protoc-gen-descriptors/Dockerfile -t protoc-gen-descriptors
	docker create --name dummy-protoc-gen-descriptors protoc-gen-descriptors
	docker cp dummy-protoc-gen-descriptors:/workspace/build/protoc-gen-descriptors.wasm wasm/protoc-gen-descriptors.wasm
	docker rm -f dummy-protoc-gen-descriptors
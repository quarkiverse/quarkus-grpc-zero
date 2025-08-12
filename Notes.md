Next:

- compile also `protoc` to get the FileDescriptors. -> DONE
- integrate the java-grpc plugin: https://gist.github.com/andreaTP/9dfbfb6610666f462af4b69c62537a31 -> DONE
- integrate the mutiny plugin -> DONE
- integrate the kotlin plugin -> DONE
- integrate all the plugins in the same Wasm module build -> DONE
- review and refactor the VFS handling(use only one?) -> DONE
- fix the runtime module classpath it doesn't include all the required libraries
- compiling a protoc version different from the one used, currently, by Quarkus - cannot compile the result - check what to do
- port all of the quarkus/integration-tests/grpc-*** tests
- test on real world use cases

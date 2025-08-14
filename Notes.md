Next:

- compile also `protoc` to get the FileDescriptors. -> DONE
- integrate the java-grpc plugin: https://gist.github.com/andreaTP/9dfbfb6610666f462af4b69c62537a31 -> DONE
- integrate the mutiny plugin -> DONE
- integrate the kotlin plugin -> DONE
- integrate all the plugins in the same Wasm module build -> DONE
- review and refactor the VFS handling(use only one?) -> DONE
- the quarkus-grpc dependency is still pulling in the transitive dep on native protoc - can be solved while merging upstream - changed the skip flag - re-using the rest of the infra
- compiling a protoc version different from the one used, currently, by Quarkus - aligned to the current version used by quarkus - DONE
- port all of the quarkus/integration-tests/grpc-*** tests
- hello-world-kotlin is not testing Kotlin but just the java generation - fixed - DONE
- fix the runtime module classpath it doesn't include all the required libraries - worked around by disabling only generation from the quarkus-grpc dependency - fixed resolving recursively the dependencies - seems to work - DONE
- better error handling when something goes wrong?
- mixing and matching mutiny and grpc-kotlin on the classpath starts to look fragile - should we shade dependencies?
- test on real world use cases

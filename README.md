# Quarkus – gRPC Zero Codegen

[![Build](https://img.shields.io/github/actions/workflow/status/quarkiverse/quarkus-grpc-zero/build.yml?branch=main\&logo=GitHub\&style=flat-square)](https://github.com/quarkiverse/quarkus-grpc-zero/actions?query=workflow%3ABuild)
[![Maven Central](https://img.shields.io/maven-central/v/io.quarkiverse.grpc.zero/quarkus-grpc-zero.svg?label=Maven%20Central\&style=flat-square)](https://search.maven.org/artifact/io.quarkiverse.grpc.zero/quarkus-grpc-zero)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://opensource.org/licenses/Apache-2.0)

**Documentation:** https://docs.quarkiverse.io/quarkus-grpc-zero/dev (after registration with Quarkiverse docs) or see the [`docs/`](docs/) module in this repository.

---

## What

**gRPC Zero** is a drop-in replacement for [`io.quarkus:quarkus-grpc-codegen`](https://quarkus.io/guides/grpc), with one major difference:

It removes the need for native `protoc` executables and plugins.
Instead, everything runs directly on the JVM as a single, portable Java dependency.

`io.quarkus:quarkus-grpc-codegen` is what the `io.quarkus:quarkus-grpc` extension uses to generate proto messages and services.

---

## Why

The traditional `quarkus-grpc-codegen` module relies on platform-specific binaries (`protoc` and plugins). This approach introduces several challenges:

* **OS/architecture compatibility issues**: binaries must be shipped for every possible environment.
* **External dependencies**: requires tools that may not be available in constrained or hermetic build environments.
* **Maintenance overhead**: keeping native executables up to date across platforms is difficult.

**gRPC Zero** solves these problems by providing:

* **Self-contained code generation**: no native tools required.
* **Full portability**: identical behavior on any JVM.
* **Lightweight dependency**: ~1.1 MB at the time of writing.
* **Consistent results**: passes all Quarkus integration tests with no regressions.

The result is a safer, smaller, more reliable way to enable gRPC codegen in Quarkus projects.

---

## How

Instead of downloading native `protoc` binaries, gRPC Zero uses [protobuf4j](https://github.com/roastedroot/protobuf4j) to run protoc plugins in-process on the JVM:

1. Collect `.proto` files from the project and optional Maven dependencies.
2. Parse descriptors and resolve imports through protobuf4j.
3. Run the Java, grpc-java, Mutiny, and optional Kotlin plugins without external processes.
4. Post-process generated sources for Quarkus compatibility.

See [How It Works](docs/modules/ROOT/pages/how-it-works.adoc) for the full pipeline.

---

## Getting Started

To enable gRPC code generation in your [Quarkus](https://quarkus.io/) project, add the dependency:

```xml
<dependency>
  <groupId>io.quarkiverse.grpc.zero</groupId>
  <artifactId>quarkus-grpc-zero</artifactId>
  <version>VERSION</version>
</dependency>
```

If you are migrating from `io.quarkus:quarkus-grpc`, first exclude the original codegen dependency.

```xml
    <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-grpc</artifactId>
    <exclusions>
        <exclusion>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-grpc-codegen</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Also ensure your `quarkus-maven-plugin` configuration includes the `generate-code` goal:

```xml
<plugin>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals>
        <goal>build</goal>
        <goal>generate-code</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

See [Getting Started](docs/modules/ROOT/pages/getting-started.adoc) for more detail.

---

## Configuration

Build-time properties are documented in [Configuration Reference](docs/modules/ROOT/pages/configuration.adoc).

To skip code generation:

```bash
-Dquarkus.zero.grpc.codegen.skip=true
```

Or in `application.properties`:

```properties
quarkus.zero.grpc.codegen.skip=true
```

The JVM-only alternative is `-Dgrpc.zero.codegen.skip=true`.

# Thanks

This project is building on the shoulders of giants. Special thanks to:

- [protobuf4j](https://github.com/roastedroot/protobuf4j) for in-process protoc plugin execution on the JVM
- [wasilibs/go-protoc-gen-grpc-java](https://github.com/wasilibs/go-protoc-gen-grpc-java) for prior work and invaluable help

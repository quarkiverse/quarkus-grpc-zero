# Quarkus – gRPC Zero Codegen (Experimental)

> 🚧 **Experimental**
> This extension is in its early stages. It successfully passes all relevant Quarkus integration tests, but hasn’t yet been battle-tested in production.
> We’d love for you to try it, push its boundaries, and share feedback to make it better.

[![Build](https://img.shields.io/github/actions/workflow/status/quarkiverse/quarkus-grpc-zero/build.yml?branch=main\&logo=GitHub\&style=flat-square)](https://github.com/quarkiverse/quarkus-grpc-zero/actions?query=workflow%3ABuild)
[![Maven Central](https://img.shields.io/maven-central/v/io.quarkiverse.grpc.zero/quarkus-grpc-zero.svg?label=Maven%20Central\&style=flat-square)](https://search.maven.org/artifact/io.quarkiverse.grpc.zero/quarkus-grpc-zero)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://opensource.org/licenses/Apache-2.0)

---

## What

**gRPC Zero** is a drop-in replacement for [`io.quarkus:quarkus-grpc-codegen`](https://quarkus.io/guides/grpc), with one major difference:

👉 It removes the need for native `protoc` executables and plugins.
Instead, everything runs directly on the JVM as a single, portable Java dependency.

ℹ️ `io.quarkus:quarkus-grpc-codegen` is what `io.quarkus:quarkus-grpc` extension uses (currently) to generate proto messages and services

---

## Why

The traditional `quarkus-grpc-codegen` module relies on platform-specific binaries (`protoc` and plugins). This approach introduces several challenges:

* ❌ **OS/architecture compatibility issues** – binaries must be shipped for every possible environment.
* ❌ **External dependencies** – requires tools that may not be available in constrained or hermetic build environments.
* ❌ **Maintenance overhead** – keeping native executables up to date across platforms is difficult.

**gRPC Zero** solves these problems by providing:

* ✅ **Self-contained code generation** – no native tools required.
* ✅ **Full portability** – identical behavior on any JVM.
* ✅ **Lightweight dependency** – \~1.1 MB at the time of writing.
* ✅ **Consistent results** – passes all Quarkus integration tests with no regressions.

The result: a safer, smaller, more reliable way to enable gRPC codegen in Quarkus projects.

---

## How

Instead of relying on external `protoc` CLI binaries, this module embeds all necessary functionality within Java itself, by following these steps:

1. **Strip out the CLI interface** from `libprotobuf` (to avoid spawning external processes).
2. **Compile the modified `libprotobuf` into WebAssembly (.wasm)** using `wasi-sdk`.
3. **Translate the resulting WebAssembly into pure Java bytecode** at build time using [Chicory](https://github.com/dylibso/chicory).
4. **Use this generated Java dependency**, which contains the full `protoc` capabilities (and plugin support), to perform gRPC code generation **in-process on the JVM**.

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

---

## Configuration

`quarkus-grpc-zero` supports the same configuration options as [`quarkus-grpc`](https://quarkus.io/guides/grpc-generation-reference).

Additionally, you can skip code generation with:

```bash
-Dquarkus.zero.grpc.codegen.skip=true
```

> Must be set at the **Maven/JVM level** — it does **not** work when placed in `application.properties`.

# Thanks

This project is building on the shoulders of giants. Special thanks to:

- [wasilibs/go-protoc-gen-grpc-java](wasilibs/go-protoc-gen-grpc-java) – for prior work and invaluable help
- [dylibso/chicory](https://github.com/dylibso/chicory) – for the Wasm compiler to Java Bytecode

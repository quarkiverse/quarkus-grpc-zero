# Quarkus - Grpc Zero Codegen

[![Build](<https://img.shields.io/github/actions/workflow/status/quarkiverse/quarkus-grpc-zero/build.yml?branch=main&logo=GitHub&style=flat-square>)](https://github.com/quarkiverse/quarkus-grpc-zero/actions?query=workflow%3ABuild)
[![Maven Central](https://img.shields.io/maven-central/v/io.quarkiverse.grpc.zero/quarkus-grpc-zero.svg?label=Maven%20Central&style=flat-square)](https://search.maven.org/artifact/io.quarkiverse.grpc.zero/quarkus-grpc-zero)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://opensource.org/licenses/Apache-2.0)


## Getting Started

If you have a supersonic, subatomic [Quarkus](https://quarkus.io/) project you can use this extension to generate code with Grpc:

```xml
<dependency>
  <groupId>io.quarkiverse.grpc.zero</groupId>
  <artifactId>quarkus-grpc-zero</artifactId>
  <version>VERSION</version>
</dependency>
```

remember to enable the code generation in the `quarkus-maven-plugin` configuration, if not already present, add `<goal>generate-code</goal>`:

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

> If you want to improve the docs, please feel free to contribute editing the docs in [Docs](https://github.com/quarkiverse/quarkus-grpc-zero/tree/main/docs/modules/ROOT). But first, read [this page](CONTRIBUTING.md).

## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="14.28%"><a href="https://github.com/andreaTP"><img src="https://avatars.githubusercontent.com/u/5792097?v=4?s=100" width="100px;" alt="Andrea Peruffo"/><br /><sub><b>Andrea Peruffo</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-grpc-zero/commits?author=andreaTP" title="Code">💻</a> <a href="#maintenance-andreaTP" title="Maintenance">🚧</a></td>
      <td align="center" valign="top" width="14.28%"><a href="http://gastaldi.wordpress.com"><img src="https://avatars.githubusercontent.com/u/54133?v=4?s=100" width="100px;" alt="George Gastaldi"/><br /><sub><b>George Gastaldi</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-grpc-zero/commits?author=gastaldi" title="Code">💻</a> <a href="#maintenance-gastaldi" title="Maintenance">🚧</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!

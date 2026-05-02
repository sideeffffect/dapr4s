# Scala CLI Dependencies Management Guide

> Source: https://scala-cli.virtuslab.org/docs/guides/introduction/dependencies/
> Collected: 2026-05-01
> Published: Unknown

## Overview

This documentation explains how to declare, manage, and configure dependencies in Scala CLI projects.

## Dependency Declaration Syntax

Dependencies follow the format: `groupID:artifactID:revision`

**Standard example:**
```
org.scala-lang.modules:scala-parallel-collections_2.13:1.0.4
```

**Scala shortcut** (using `::` to omit explicit Scala version):
```
org.scala-lang.modules::scala-parallel-collections:1.0.4
```

**Java dependencies** (no `::` needed):
```
org.postgresql:postgresql:42.2.8
```

## Repository Configuration

Additional repositories can be specified via directives or command-line options:

- Directive: `//> using repository sonatype:snapshots`
- CLI: `--repository "https://maven-central.storage-download.googleapis.com/maven2"`

**Predefined repositories include:**
- `central` – Default Maven repository
- `sonatype:snapshots` – Scala nightly builds
- `sonatype-s01:snapshots` – Alternative Sonatype for newer accounts
- `ivy2local` – Local Ivy repository
- `m2Local` – Local Maven repository
- `jitpack` – GitHub repository support

## Excluding Transitive Dependencies

Use the `exclude` parameter to remove unwanted dependencies:

```
//> using dep com.lihaoyi::pprint:0.9.0,exclude=com.lihaoyi%%sourcecode
```

For Scala modules: `exclude=org%%name` or `exclude=org%name_version`

## Dependency Classifiers

Specify classifiers with the `classifier` parameter:

```
//> using dep org.bytedeco:pytorch:2.5.1-1.5.11,classifier=linux-x86_64
```

**Important:** "When using parameters like `classifier` or `exclude`, wrap values in double quotes" to avoid parsing errors.

## Scope-Specific Dependencies

**Test dependencies:**
```
//> using test.dep org.scalameta::munit::1.0.2
```

**Compile-only dependencies:**
```
//> using compileOnly.dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:2.23.2
```

## Command-Line Specification

Dependencies can be added via `--dependency` flag:
```
scala-cli compile Sample.sc --dependency org.scala-lang.modules::scala-parallel-collections:1.0.4
```

Optional URL fallback for JAR files:
```
scala-cli compile Sample.sc --dependency "org::name::version,url=https://url-to-jar"
```

## Local JAR Dependencies

**Via command line:**
```
scala-cli compile Sample.sc --extra-jar "./path/to/custom.jar"
scala-cli compile Sample.sc --extra-source-jar "./path/to/custom-sources.jar"
```

**Via directives:**
```
//> using jar ./path/to/custom.jar
//> using sourceJar ./path/to/custom-sources.jar
```

Note: Files with `*-sources.jar` suffix are automatically treated as source JARs.

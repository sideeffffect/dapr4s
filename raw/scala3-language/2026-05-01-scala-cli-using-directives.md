# Scala CLI Using Directives

> Source: https://scala-cli.virtuslab.org/docs/guides/introduction/using-directives/
> Collected: 2026-05-01
> Published: Unknown

## Overview

The `using` directives mechanism allows developers to define configuration directly within `.scala` source files, eliminating the need for separate build tool configuration. These are key-value pairs placed in special comments using the syntax: `//> using foo bar baz`

## Deprecated Syntax

Earlier versions experimented with alternative syntaxes including `@using` annotations and plain comments. These older formats will continue functioning through version 0.1.x but will be ignored starting with version 1.0.x.

## Key Semantics

Directives must appear before any Scala code in a file. They apply to the entire compilation scope of that file, meaning settings affect the whole application or test suite. The notable exception is `using target` directives, which only apply to individual files and serve as experimental markers for file requirements.

## Common Directives

The documentation lists these frequently-used directives:

- Scala version specification
- Library dependencies (Scala and Java)
- Resource directories
- Java runtime options
- Test framework selection

## Path Handling

The `${.}` pattern within directive values automatically expands to the parent directory of the file containing the directive, enabling relative file references. To prevent expansion, use `$${.}` instead.

## Commenting Out Directives

Since directives are code, they can be commented like standard comments: `// //> using dep org::lib:version`

## Test-Scoped Variants

Certain directives offer test-specific equivalents (prefixed with `test.`), allowing developers to declare test-only dependencies anywhere in the project rather than solely within test files.

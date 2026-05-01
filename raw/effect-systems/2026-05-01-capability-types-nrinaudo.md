# The right(?) way to work with capabilities

> Source: https://nrinaudo.github.io/articles/capability_types.html
> Collected: 2026-05-01
> Published: Unknown

**Author:** Nicolas Rinaudo

## Overview

This article explores best practices for declaring effectful computations using capabilities in Scala 3, focusing on the `Rand` capability as a primary example. It compares value-based vs. function-based approaches and covers effect polymorphism.

## Introduction to Rand Capability

Rinaudo introduces a simple `Rand` capability for producing random values, with atomic operations for generating integers and booleans.

## Declaring Effectful Computations

Two approaches compared:

1. **Value-based**: Using `Rand ?-> A` type directly
2. **Function-based**: Using `def` with `using Rand` parameter

While initially preferring the first approach for semantic clarity, Rinaudo argues the second approach offers advantages:

> "the less esoteric the code, the more comfortable it will be to work with" when using standard Scala conventions.

## Understanding By-Name vs Context Functions

The article distinguishes between:
- `a: => A` — effectful computation over any capabilities
- `a: Rand ?=> A` — computation requiring the Rand capability specifically

This distinction enables **effect polymorphism** in Scala 3.

## The `or` Combinator Evolution

The article demonstrates how the `using Rand` approach simplifies the `or` function, eliminating complex capture checking annotations and producing cleaner, more conventional Scala code.

## Variadic Parameters Limitation

Since "by-name parameters cannot be variadic," developers face trade-offs when designing functions like `oneOf`.

The article explores `DummyImplicit` as a workaround, though acknowledging uncertainty about its appropriateness.

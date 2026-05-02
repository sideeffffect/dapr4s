# Scala 3 Opaque Types — Official Documentation

> Source: https://docs.scala-lang.org/scala3/book/types-opaque-types.html
> Collected: 2026-05-01
> Published: Unknown

## Overview

"Opaque type aliases provide type abstraction without any overhead." They represent a Scala 3 feature that improves upon Scala 2's value classes approach.

## The Problem: Abstraction Overhead

The documentation illustrates the issue through a `Logarithm` class example. When wrapping `Double` values to represent numbers stored as logarithms, every mathematical operation requires extracting and rewrapping values in new instances, creating severe performance penalties.

## Module Abstractions Approach

An alternative strategy uses type aliases within trait interfaces. The `Logarithms` trait defines an abstract `Logarithm` type, with implementations like `LogarithmsImpl` that equate it to `Double`. However, this introduces "leaky abstractions"—users might accidentally discover that `Logarithm = Double` and misuse the type.

Additionally, type abstractions erase to `Any`, causing boxing overhead for primitive types like `Double`.

## Opaque Types Solution

Scala 3 introduces the `opaque type` keyword to solve these issues:

```scala
object Logarithms:
  opaque type Logarithm = Double
  
  object Logarithm:
    def apply(d: Double): Logarithm = math.log(d)
  
  extension (x: Logarithm)
    def toDouble: Double = math.exp(x)
```

The type equality is visible only within the defining scope. External code cannot discover the underlying `Double` implementation, preventing accidental misuse while maintaining zero runtime overhead.

## Key Benefits

- Sound abstraction without performance penalties
- Clean integration with extension methods
- No boxing overhead for primitives
- Simple, convenient implementation compared to manual module splitting

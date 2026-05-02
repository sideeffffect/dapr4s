# Scala 3 Given Instances Reference

> Source: https://docs.scala-lang.org/scala3/reference/contextual/givens.html
> Collected: 2026-05-01
> Published: Unknown

## Overview

Given instances, or "givens," establish canonical values of specific types that automatically provide arguments to context parameters. They form a key component of Scala 3's contextual abstraction system.

## Core Example

The reference demonstrates givens through an `Ord` trait defining comparison operations:

```scala
trait Ord[T]:
  def compare(x: T, y: T): Int
  extension (x: T)
    def < (y: T) = compare(x, y) < 0
    def > (y: T) = compare(x, y) > 0

given intOrd: Ord[Int]:
  def compare(x: Int, y: Int) =
    if x < y then -1 else if x > y then +1 else 0
```

The `listOrd` instance demonstrates conditional givens using context bounds `[T: Ord]`, enabling givens for parameterized types when dependencies exist.

## Anonymous Givens

Names can be omitted from given declarations. The compiler synthesizes readable names automatically:

```scala
given Ord[Int]: ...
given [T: Ord] => Ord[List[T]]: ...
```

The compiler generates names like `given_Ord_Int` and `given_Ord_List`. However, libraries should prefer named instances for "robust binary compatibility."

## Alias Givens

Alias givens equal some expression and cache results:

```scala
given global: ExecutionContext = ForkJoinPool()
```

This creates a thread-safe singleton initialized on first access. Anonymous alias givens also work:

```scala
given Position = enclosingTree.position
```

## Initialization Semantics

Unconditional givens without parameters initialize on-demand at first access. Immutable alias givens act as simple forwarders without caching overhead. Conditional givens create fresh instances per reference.

## Syntax Structure

Given declarations follow this pattern: optional name, optional conditions, implemented type(s), and implementation (either alias or structural). The syntax was updated in Scala 3.6, with previous syntax remaining supported during a transition period.

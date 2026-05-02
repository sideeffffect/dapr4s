# scala.caps.Capability — Scala 3 API Documentation

> Source: https://www.scala-lang.org/api/3.x/scala/caps/Capability.html
> Collected: 2026-05-01
> Published: Unknown

## Package Context

`scala.caps.Capability` is a core class (trait/marker) within Scala 3's capability system, located in the `scala.caps` package.

The `Capability` class exists within a broader capability framework that includes:

- **Core capability types**: `ExclusiveCapability`, `SharedCapability`
- **Utility capabilities**: `Pure`, `Unscoped`, `Mutable`, `Stateful`, `Read`
- **Capability operations**: `Contains`, `Separate`, `Exists`
- **Classification**: `Classifier`

## Related Components

The capability system integrates with:

- **Control flow**: `Control` for capability-aware control structures
- **Internal utilities**: `consume`, `refineOverride`, `rootCapability`, `inferredDepFun`
- **Safety mechanisms**: `unsafe` with `untrackedCaptures`
- **Constraint support**: Reserve patterns and usage tracking

## Semantics

When a class extends `scala.caps.Capability`, it becomes a tracked capability within Scala 3's capture checking (`-Ycc`) system. This means:

1. Instances of the class are tracked as capabilities — their capture sets are monitored by the compiler.
2. The class participates in Scala 3's structural capability hierarchy alongside `SharedCapability`, `ExclusiveCapability`, etc.
3. Code that holds a reference to a `Capability` instance must declare this in its capture set (using the `^` notation).
4. The `Capability` trait is intended as a marker for things that represent effects or resources whose lifetime and scope should be tracked.

## Usage in Practice

Extending `scala.caps.Capability` is the standard way to define custom capability types in user code. For example:

```scala
import scala.caps.Capability

trait Database extends Capability:
  def query(sql: String): List[Row]
```

A function that uses a `Database` capability must declare this in its signature under capture checking:

```scala
def findUsers()(using db: Database^): List[User] = ...
```

This allows the compiler to enforce that the `Database` capability does not escape its intended scope.

## Relationship to Capture Checking

The `Capability` marker is central to the `-Ycc` (capture checking) experiment in Scala 3. It anchors the type-level tracking of capabilities, ensuring that:
- Resources like file handles, database connections, or concurrency scopes don't leak
- Effect-like capabilities (e.g., `Async`, `CanThrow`) are explicitly declared and handled
- The compiler can enforce scoped usage via the `^` capture syntax

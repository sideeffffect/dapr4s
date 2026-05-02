# Opaque Types

> Sources: Scala Documentation, Unknown; Rock the JVM, Unknown
> Raw: [Scala 3 Opaque Types — Official](../../raw/scala3-language/2026-05-01-scala3-opaque-types-official.md); [Scala 3 Opaque Types — Rock the JVM](../../raw/scala3-language/2026-05-01-scala3-opaque-types-rockthejvm.md)

## Overview

Opaque types are a Scala 3 feature that provides zero-cost type abstraction: you define a new type that is backed by an existing type at runtime, but the backing type is hidden from all callers outside the defining scope. This enables strong type-safety boundaries around Java or primitive types without any boxing or allocation overhead, making them ideal for wrapping Java SDK types in a safe Scala library.

## Motivation: The Cost of Abstraction

Traditional wrapper classes incur overhead. Every operation on a `case class Name(value: String)` or `class Logarithm(val d: Double)` forces allocation and indirection. Scala 2's `AnyVal`/value classes partially mitigate this but have restrictions (only one field, special boxing rules). Abstract type members in traits avoid cost but create "leaky abstractions"—callers can discover and exploit the underlying type equality.

## The `opaque type` Keyword

Scala 3 solves this cleanly:

```scala
object Logarithms:
  opaque type Logarithm = Double

  object Logarithm:
    def apply(d: Double): Logarithm = math.log(d)

  extension (x: Logarithm)
    def toDouble: Double = math.exp(x)
```

Inside the `Logarithms` object, `Logarithm` and `Double` are the same type — you can pass one where the other is expected and no conversion is needed. Outside the object, `Logarithm` is an opaque, distinct type. The compiler enforces this: external code cannot pass a raw `Double` where a `Logarithm` is expected.

## API Design Pattern

The canonical pattern has three components:

1. **The opaque type declaration** — lives in a companion object or dedicated module.
2. **A smart constructor** in the companion — validates and wraps the value.
3. **Extension methods** — provide operations without exposing the underlying type.

```scala
object SocialNetwork:
  opaque type Name = String

  object Name:
    def fromString(s: String): Option[Name] =
      if s.isEmpty || s.charAt(0).isLower then None else Some(s)

  extension (n: Name)
    def length: Int = n.length   // delegates to String.length
```

The smart constructor returns `Option[Name]`, enforcing invariants at the boundary. Once a `Name` is created, it is an opaque handle — callers cannot recover the raw `String`.

## Type Bounds

Opaque types can be bounded, allowing subtype relationships without exposing equality:

```scala
opaque type Pixels <: Int = Int
opaque type Em     <: Double = Double
```

With an upper bound, external code can use the opaque type wherever the bound type is expected (e.g., pass `Pixels` to a function taking `Int`), but cannot go the other direction without the smart constructor.

## Zero-Cost Guarantee

At runtime, an opaque type is exactly its underlying representation — no wrapper class, no boxing. A `Logarithm` is a JVM `double`. A `Name` is a `java.lang.String`. The abstraction layer exists entirely at compile time and is erased before bytecode generation.

## Application to Java Type Wrapping

For wrapping Java SDK types (e.g., Dapr's `DaprClient`), opaque types are the right tool:

```scala
object DaprTypes:
  opaque type StateKey = String
  opaque type AppId    = String

  object StateKey:
    def apply(s: String): StateKey = s

  object AppId:
    def apply(s: String): AppId = s
```

This prevents accidental interchange of `StateKey` and `AppId` (both are strings at runtime) while adding zero overhead. The companion object becomes the trust boundary: only code inside it can construct or unwrap values.

## Comparison with Value Classes (Scala 2)

| Feature | Value Class (`AnyVal`) | Opaque Type |
|---|---|---|
| Zero overhead | Mostly (boxing in some contexts) | Always |
| Hides underlying type | No | Yes |
| Companion methods | Awkward | Natural |
| Bounded abstraction | No | Yes (`opaque type T <: U`) |
| Works with primitives | Yes | Yes |

## See Also

- [Context Functions and Capability Passing](context-functions-capability-passing.md)
- [Given/Using](given-using.md)
- [Java Interop and Safe Scala](java-interop-safe-scala.md)
- [Safe Mode](../scala-capture-checking/safe-mode.md)

# Scala Best Practices — Algebraic Data Types

> Source: https://nrinaudo.github.io/scala-best-practices/definitions/adt.html
> Collected: 2026-05-02
> Published: Unknown

## Overview

Algebraic Data Types (ADTs) represent a fundamental approach to structuring data in Scala. They excel because "they work with pattern matching and how easy it is to use them to make illegal states impossible to represent."

## Product Types

Product types combine multiple values into a single structure. Case classes exemplify this pattern:

```scala
final case class Foo(b1: Boolean, b2: Boolean)
```

The term "product" derives from computing cardinality through multiplication. Since each Boolean holds 2 possible values, `Foo` yields 4 combinations.

## Sum Types

Sum types represent different alternative forms. A basic enumeration demonstrates this:

```scala
sealed abstract class Bool extends Product with Serializable

object Bool {
  final case object True extends Bool
  final case object False extends Bool
}
```

The cardinality equals the sum of component arities.

## Practical Application

Consider a command system supporting movement and rotation. A naive approach using optional fields creates problematic invalid states:

```scala
final case class Command(label: String, meters: Option[Int], degrees: Option[Int])
```

This permits meaningless combinations. A better ADT design eliminates such impossibilities:

```scala
sealed abstract class Command extends Product with Serializable

object Command {
  final case class Move(meters: Int) extends Command
  final case class Rotate(degrees: Int) extends Command
}
```

This structure integrates seamlessly with pattern matching, enabling clean handling of each variant.

## Best Practices Navigation (from site)

The nrinaudo.github.io/scala-best-practices site covers:

### ADTs section:
- Declare ADT data constructors in the companion object
- Make ADTs subtypes of Product and Serializable
- Make error ADTs subtypes of Exception
- Mark case objects as final
- Use ADTs to implement enumerations

### Tricky Behaviours section:
- Make subtypes of sealed types final
- Mark case classes as final

### Referential Transparency section:
- Avoid mutability
- Do not throw exceptions
- Do not use return

### Unsafe Patterns section:
- Avoid implicit conversions
- Do not use null

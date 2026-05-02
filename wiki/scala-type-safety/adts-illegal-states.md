# ADTs and Making Illegal States Unrepresentable

> Sources: nrinaudo Scala Best Practices, Unknown; nrinaudo Scala Best Practices (ADT article), Unknown
> Raw: [nrinaudo-scala-best-practices-adts.md](../../raw/scala-type-safety/nrinaudo-scala-best-practices-adts.md)

## Overview

Algebraic Data Types (ADTs) are sum types that enumerate exactly the valid states a value can be in. Exhaustive pattern matching catches missing cases at compile time. The discipline of making illegal states *unrepresentable* reduces the number of runtime errors to zero for an entire class of bugs.

## Product vs Sum Types

**Product types** combine values; their cardinality is the *product* of component cardinalities:

```scala
final case class Point(x: Int, y: Int) // Int * Int possible values
```

**Sum types** choose one of N variants; their cardinality is the *sum*:

```scala
sealed trait Direction
case object North extends Direction
case object South extends Direction
case object East  extends Direction
case object West  extends Direction
// exactly 4 possible values
```

## The Nullable Fields Anti-Pattern

Using optional fields in a product type to represent mutually exclusive states is the most common source of representable illegal states:

```scala
// WRONG: both fields could be Some at the same time
final case class Command(
  moveMeters:  Option[Int],
  rotateDegrees: Option[Int]
)
```

The ADT fix:

```scala
sealed abstract class Command extends Product with Serializable
object Command:
  final case class Move(meters: Int)   extends Command
  final case class Rotate(degrees: Int) extends Command
```

Now a `Command` is always exactly one of the variants. Impossible to have a `Move` and `Rotate` simultaneously.

## Scala Best Practice Rules

### Make subtypes of sealed types `final`

```scala
sealed trait Status
// WRONG: client code can extend Status
class Active extends Status

// CORRECT: compiler knows the full set
sealed trait Status
final class Active  extends Status
final class Expired extends Status
```

Non-final subtypes allow extension, breaking exhaustivity guarantees. If a subtype can be further extended, the pattern match is no longer exhaustive even if all declared subtypes are covered.

### Mark case objects as `final`

```scala
// WRONG
sealed trait Color
case object Red   extends Color
case object Green extends Color

// CORRECT
sealed trait Color
final case object Red   extends Color
final case object Green extends Color
```

Case objects without `final` can technically be extended (though unusual). `final` prevents this and signals intent.

### Mark case classes as `final`

```scala
// WRONG: allows extending Foo with additional fields
case class Foo(x: Int)
class Bar(x: Int, y: Int) extends Foo(x) // surprising behavior

// CORRECT
final case class Foo(x: Int)
```

Extending case classes breaks the copy/equals/hashCode contract. Always mark case classes final.

### Extend `Product with Serializable`

```scala
// WRONG: inferred type is Foo | Bar, not FooOrBar
sealed trait FooOrBar
final case class Foo(x: Int) extends FooOrBar
final case class Bar(y: Int) extends FooOrBar

val xs: List[FooOrBar] = List(Foo(1), Bar(2)) // compiles fine

// BETTER (Scala 2 only — Scala 3 derives Product and Serializable automatically)
sealed abstract class FooOrBar extends Product with Serializable
```

In Scala 2, without explicit `Product with Serializable`, the inferred type of mixed ADT collections is the least upper bound of `Foo with Product with Serializable` and `Bar with Product with Serializable`, which is `Product with Serializable` — not the ADT root. In Scala 3 this is handled automatically.

### Use ADTs to Implement Enumerations

Avoid `scala.Enumeration` (Scala 2) and prefer sealed hierarchies or `enum`:

```scala
// Scala 2 anti-pattern
object Status extends Enumeration {
  val Active, Expired = Value
}

// Preferred in Scala 2
sealed abstract class Status extends Product with Serializable
object Status:
  final case object Active  extends Status
  final case object Expired extends Status

// Scala 3 — use enum
enum Status:
  case Active, Expired
```

`scala.Enumeration` uses `Value` type, losing the ability to pattern match exhaustively and bypassing type safety.

### Declare ADT Data Constructors in the Companion Object

```scala
// With data constructors outside, imports are messy:
sealed trait Expr
final case class Lit(n: Int)         extends Expr
final case class Add(l: Expr, r: Expr) extends Expr

// Prefer companion:
sealed trait Expr
object Expr:
  final case class Lit(n: Int)         extends Expr
  final case class Add(l: Expr, r: Expr) extends Expr
```

Companion placement groups related types, controls namespace pollution, and signals that the variants are not top-level concepts.

### Make Error ADTs Subtypes of Exception

```scala
// WRONG: error type separate from exception hierarchy
sealed trait AppError
final case class NotFound(id: String) extends AppError

// CORRECT: works with both ADT pattern matching and exception handling
sealed abstract class AppError(message: String)
    extends Exception(message) with Product with Serializable
final case class NotFound(id: String)
    extends AppError(s"Not found: $id")
```

Being a subtype of `Exception` means error ADTs integrate with `throws` clauses (including `saferExceptions`) and `try/catch` blocks without wrapping.

## Exhaustivity in Practice

The compiler verifies all variants are handled in a `match`:

```scala
def describe(cmd: Command): String = cmd match
  case Command.Move(m)   => s"move $m meters"
  case Command.Rotate(d) => s"rotate $d degrees"
  // missing case → compile warning/error with -Wconf:cat=other-match-analysis:error
```

With `-Xfatal-warnings`, unmatched cases are errors. No runtime `MatchError` surprises.

## See Also

- [Parse, Don't Validate](parse-dont-validate.md)
- [Primitive Obsession and Opaque Types](primitive-obsession-opaque-types.md)

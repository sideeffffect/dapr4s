# Scala Best Practices (nrinaudo)

> Sources: nrinaudo, Unknown
> Raw: [nrinaudo-scala-best-practices-complete.md](../../raw/scala-type-safety/nrinaudo-scala-best-practices-complete.md); [nrinaudo-scala-best-practices-adts.md](../../raw/scala-type-safety/nrinaudo-scala-best-practices-adts.md)

## Overview

A comprehensive set of Scala best practices covering correctness traps, type safety, ADT design, referential transparency, and binary compatibility. The most impactful rules for library design are: make sealed subtypes `final`, prefer `abstract class` to `trait` for ADT roots, declare ADT constructors in companion objects, and avoid unsafe partial operations.

## Numeric Pitfalls

- `x % 2 == 1` is wrong for negative numbers (`-3 % 2 == -1`); use `x % 2 != 0`.
- `NaN != NaN` per IEEE 754; use `d.isNaN`, never `d == Double.NaN`.
- Use uppercase `L`/`F` for `Long`/`Float` literals; lowercase `l` looks like `1`.

## Tricky Behaviours

### Sealed Types Must Have Final Subtypes

`sealed` restricts *direct* subtypes to the same file, but non-final subtypes can be extended from any other file. Always mark sealed subtypes `final`:

```scala
// Bug: Bar can be extended outside the file
sealed trait Foo
class Bar extends Foo     // non-final — anyone can extend Bar

// Fix:
sealed trait Foo
final class Bar extends Foo
```

Detected by WartRemover's `LeakingSealed` rule.

### Case Classes Must Be Final

Extending a case class silently ignores subclass fields in `equals`/`hashCode`/`toString`:

```scala
case class Point(x: Int, y: Int)
class NamedPoint(x: Int, y: Int, name: String) extends Point(x, y)

NamedPoint(1, 2, "A") == NamedPoint(1, 2, "B")  // true — name ignored!
```

**Exception** — `sealed abstract case class` for validated newtypes (prevents direct construction and eliminates `copy`, preserving invariants):

```scala
sealed abstract case class PositiveInt(value: Int)
object PositiveInt {
  def fromInt(i: Int): Option[PositiveInt] =
    if (i > 0) Some(new PositiveInt(i) {}) else None
}
```

### Implicits

- Always annotate implicit vals/defs even when private — a compiler bug (SI-8697) can cause silent wrong-implicit selection without annotations.
- Give implicits unique names — two implicits with the same name from different imported objects cause "could not find implicit" even if types differ.
- Unicode operators (`→`, `⇒`) have different precedence than their ASCII equivalents and are being deprecated.

### String Concatenation

Use string interpolation, never `+`. `List("foo") + "bar"` produces a `String` (via `any2stringadd`), not a `List` — misleading and error-prone.

### Futures in For-Comprehensions

For-comprehensions desugar to nested `flatMap`. Futures created *inside* the comprehension execute sequentially. Start independent Futures before the `for`:

```scala
// Bad — sequential:
for { i <- Future(work1()); j <- Future(work2()) } yield i + j

// Good — concurrent:
val f1 = Future(work1()); val f2 = Future(work2())
for { i <- f1; j <- f2 } yield i + j
```

## Unsafe Patterns

### Partial Collection Operations

Never use these on potentially-empty collections:

| Partial (throws) | Total alternative |
|-----------------|-------------------|
| `.head`          | `.headOption`     |
| `.last`          | `.lastOption`     |
| `.tail`          | `.drop(1)`        |
| `.init`          | `.dropRight(1)`   |
| `.reduce`        | `.reduceOption`   |
| `.get` on Option | `.getOrElse` / `.fold` |
| `.get` on Try    | `.getOrElse` / `.fold` |
| `.right.get` on Either | `.getOrElse` / `.fold` |

### Null

Use `Option` instead of `null`. `null: String` compiles but crashes with `NullPointerException` at runtime. Only acceptable when a Java API explicitly requires `null` for absent optional arguments.

### Recursion

Non-tail-recursive functions risk `StackOverflowError` on large inputs. Convert to accumulator + inner `loop` pattern and annotate with `@tailrec` to let the compiler verify tail-call optimization applies.

### Array Comparison

Arrays use reference equality. Use `sameElements` for value equality, or `.deep ==`.

### Collection Emptiness

Use `isEmpty`/`nonEmpty`, never `size == 0`. `List.size` is O(n); `Stream.size` hangs forever.

### Custom Extractors

Total custom extractors should return `Some`, not `Option`. Returning `Option` disables the compiler's exhaustivity check.

### Structural Types

Structural types rely on reflection (slow, security-manager-dependent). Use type classes.

### Implicit Conversions

Non-total implicit conversions fail at runtime. Only safe case: total enrichment via `implicit class` / extension methods.

## Referential Transparency

### Avoid Mutability

Mutable code breaks RT — the same call can return different values depending on when it's called. **Exception**: locally-scoped mutable variables invisible to callers preserve RT.

### Avoid Throwing Exceptions

Throwing breaks RT:

```scala
def f() = { val a = throw new Exception; if(false) a else 2 }
// Throws even though `throw` is in a branch that's never reached
```

Use `Option`/`Either`/`Try` for expected failures. Throwing is acceptable only for truly unrecoverable scenarios (OOM, hardware failure).

### Avoid `return`

Multiple exit paths add cognitive complexity, break RT, and produce confusing type errors in higher-order functions.

## ADTs

### Root Type: sealed abstract class with Product and Serializable

Without `extends Product with Serializable` (Scala 2) or `sealed abstract class` (both versions), mixed ADT collections infer an ugly common supertype:

```scala
// Without:
List(Status.Ok, Status.Nok)  // List[Product with Serializable with Status]

// With sealed abstract class Status extends Product with Serializable:
List(Status.Ok, Status.Nok)  // List[Status]
```

In Scala 3, `enum` handles this automatically. For manual sealed hierarchies, use `sealed abstract class`.

### Data Constructors in Companion Object

Place ADT subtypes inside the companion object:

```scala
sealed abstract class Result[+A] extends Product with Serializable
object Result:
  final case class Success[A](value: A) extends Result[A]
  final case class Failure(error: String) extends Result[Nothing]
```

Top-level subtypes pollute the package namespace and risk name collisions.

### Error ADTs Extend Exception

Make error ADT roots extend `Exception` so they work with `Try`, `Future`, and `throws` clauses.

### case objects Must Be Final

Without `final`, JIT cannot apply static dispatch optimizations, and Java code can extend the object.

### Use Scala 3 enum, Not scala.Enumeration

`scala.Enumeration` compiles without warning when pattern match cases are missing. Scala 3 `enum` (or sealed `final case object` hierarchy) triggers exhaustivity warnings.

## Binary Compatibility

### Explicit Types on Public Members

Always annotate public defs, even when inference is "obvious". Implementation changes can narrow inferred types, silently breaking binary compatibility for library consumers.

### Prefer abstract class to trait for ADT Roots

Two reasons:
1. Adding concrete methods to a published trait is a binary-breaking change; adding to an abstract class is not.
2. Java callers can use `AbstractClass.method()` syntax; trait companions require `Trait$.MODULE$.method()`.

**Note**: traits are still appropriate for mixin composition and capability interfaces. The abstract-class preference applies specifically to ADT root types that will be extended and published.

## OOP

### Declare Abstract Fields as def

`def` can be implemented with either `def` or `val`; `val` can only be implemented with `val`. Declaring as `def` leaves all options open.

**Exception**: path-dependent types require `val` (a `def` is not a stable path).

### Always Use override

Without `override`, a typo in the method name compiles silently and the abstract method goes unimplemented. With `override`, the compiler immediately reports "method X overrides nothing."

## See Also

- [ADTs and Making Illegal States Unrepresentable](adts-illegal-states.md)
- [Primitive Obsession and Opaque Types](primitive-obsession-opaque-types.md)
- [Parse, Don't Validate](parse-dont-validate.md)

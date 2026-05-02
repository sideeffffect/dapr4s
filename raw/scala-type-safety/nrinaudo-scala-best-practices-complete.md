# Scala Best Practices — Complete Reference

> Source: https://nrinaudo.github.io/scala-best-practices/
> Collected: 2026-05-02
> Published: Unknown

## Warming Up

### checking_for_odd — Compare Remainder to 0 When Checking for Oddness
`x % 2 == 1` is wrong for negative numbers (`-3 % 2 == -1`, not 1). Use `x % 2 != 0`.

### checking_for_nan — Use isNaN When Checking for NaN
Per IEEE 754, `NaN != NaN`. Use `d.isNaN`. For hot paths, `d == d` is faster (only NaN fails self-equality) but profile first.

### numeric_literals — Use Uppercase Numeric Literal Suffixes
Use `L` and `F` (not `l` and `f`). Lowercase `l` is indistinguishable from `1` in many fonts.

## Tricky Behaviours

### type_implicits — Add Explicit Type Annotations to Implicits
Always annotate implicit vals/defs even when private. Compiler bug 8697: missing annotation can cause silent wrong-implicit selection.

### unicode_operators — Avoid Unicode Versions of ASCII Operators
`→` has different precedence than `->`, causing subtle evaluation order bugs. Being deprecated.

### string_concatenation — Avoid String Concatenation with +
`List("foo") + "bar"` yields a String due to `any2stringadd`. Use string interpolation.

### implicit_shadowing — Give Implicits Unique Names
Two implicits with the same name from imported objects cause "could not find implicit" — even if types differ. Very hard to debug.

### leaky_sealed_types — Make Subtypes of Sealed Types Final
`sealed` restricts only direct subtypes to the same file. Non-final subtypes can be extended elsewhere. Mark all sealed subtypes `final`.

```scala
// File 1:
sealed trait Foo
class Bar extends Foo      // not final

// File 2:
class FooBar extends Bar   // compiles! sealed doesn't stop it
```

### final_case_classes — Mark Case Classes as Final
Extending a case class silently breaks `equals`/`hashCode`/`toString` — subclass fields are ignored.

```scala
case class Foo(i: Int)
class Bar(i: Int, s: String) extends Foo(i)
// Two Bars with same i but different s compare equal
```

**Exception:** `sealed abstract case class` for validated newtypes (prevents direct instantiation, eliminates `copy`):
```scala
sealed abstract case class PositiveInt(value: Int)
object PositiveInt {
  def fromInt(i: Int): Option[PositiveInt] =
    if(i > 0) Some(new PositiveInt(i) {}) else None
}
```

### future_in_comprehensions — Start Independent Futures Outside For-Comprehensions
For-comprehensions desugar to nested `flatMap` — Futures created inside run sequentially.

## Unsafe Patterns

### implicit_conversions — Avoid Implicit Conversions
Non-total conversions fail at runtime. Only safe: total enrichment via `implicit class`.

### structural_types — Avoid Structural Types
Structural types use reflection (slow, can fail with SecurityManager). Use type classes instead.

### array_comparison — Array Comparison
Arrays use reference equality. Use `sameElements` or `.deep ==` for value equality.

### checking_empty_collection — Use isEmpty Not size == 0
`List.size` is O(n). `Stream.size` hangs forever. Always use `isEmpty`/`nonEmpty`.

### custom_extractors — Return Some (Not Option) from Total Custom Extractors
Returning `Option` disables exhaustivity checking. Return `Some` for total extractors.

### avoid_null — Avoid Null
Use `Option` instead. Null compiles as `String` but crashes at runtime.

### recursion — Make Recursive Functions Tail-Recursive
Non-tail recursion risks StackOverflowError. Convert to accumulator + inner loop pattern.

### tail_recursion — Mark Tail-Recursive Functions with @tailrec
Forces compiler to verify the optimization actually applies; catches non-optimizable overridable methods.

## Partial Functions

### traversable_head/init/last/reduce/tail — Avoid Partial Collection Operations
| Dangerous | Safe alternative |
|-----------|-----------------|
| `head`    | `headOption` |
| `last`    | `lastOption` |
| `tail`    | `drop(1)` |
| `init`    | `dropRight(1)` |
| `reduce`  | `reduceOption` |

All throw exceptions on empty collections. The safe variants return `Option` or empty collection.

### either_projection_get / option_get / try_get — Avoid get() on Projections
`None.get`, `Left(x).right.get`, `Failure(e).get` all throw `NoSuchElementException` / the wrapped exception.
Use `getOrElse`, `fold`, or pattern matching.

## Binary Compatibility

### explicit_type_annotations — Explicit Types on Public Members
Always annotate public members. Type inference can narrow types unexpectedly when implementations change, silently breaking binary compatibility.

### abstract_over_trait — Prefer Abstract Classes to Traits
Two reasons:
1. **Binary compat**: adding concrete methods to a trait breaks binary compat; adding to abstract class does not.
2. **Java interop**: trait companions require `Trait$.MODULE$.foo()` from Java; abstract class allows `Class.foo()`.

## Referential Transparency

### avoid_mutability — Avoid Mutability
Mutable code breaks referential transparency. **Exception**: local mutable vars invisible to callers (encapsulated) maintain RT.

### avoid_throwing_exceptions — Do Not Throw Exceptions
Throwing breaks referential transparency:
```scala
def foo1() = if(false) throw new Exception else 2   // returns 2
def foo2() = { val a = throw new Exception; if(false) a else 2 }  // throws!
```
Use `Option`, `Either`, `Try` instead.
**Acceptable**: truly exceptional scenarios (missing hardware, OOM), not expected business failures.

### avoid_return — Do Not Use return
1. Multiple exit paths (Dijkstra's GOTO argument)
2. Breaks referential transparency
3. Unintuitive in higher-order functions (type errors involving Nothing)

## ADTs

### data_constructors_in_companion_object — Declare ADT Constructors in the Companion Object
Top-level constructors pollute the package namespace and risk collisions (e.g., `Failure` vs other libraries). Place in companion, importable with `import Foo._`.

```scala
sealed abstract class Option[+A] extends Product with Serializable
object Option {
  final case class Some[A](value: A) extends Option[A]
  final case object None extends Option[Nothing]
}
```

### product_with_serializable — ADTs Should Extend Product with Serializable
Without it, the compiler infers `Product with Serializable with Status` as the type of mixed ADT collections, not `Status`.

```scala
// Without:
List(Status.Ok, Status.Nok)  // List[Product with Serializable with Status]

// With sealed abstract class Status extends Product with Serializable:
List(Status.Ok, Status.Nok)  // List[Status]
```

### errors_extend_exception — Error ADTs Should Extend Exception
Makes custom errors usable with `Try`, `Future`, and other APIs requiring `Throwable`.
**Caveat**: using a shared `Exception` supertype loses exhaustive pattern matching.

### final_case_objects — Mark Case Objects as Final
Without `final`, JIT can't optimize (virtual dispatch vs. static). Java code may extend it.

### enumerations_as_adt — Use ADTs for Enumerations
`scala.Enumeration` does not check pattern match exhaustivity. Use sealed ADTs with `final case object` or `enum`.

## OOP

### abstract_fields_as_defs — Declare Abstract Fields as Paren-less Methods
Declare as `def` (not `val`). `def` can be implemented with `val`; `val` cannot be implemented with `def`.

### always_override — Use override When Implementing Abstract Members
Without `override`, a typo in the method name compiles silently but the abstract method remains unimplemented.

## Definitions

### type_class — Type Class
A trait defining behaviours; instances provided via implicits. Composes: a `HasLabel[A]` instance can be auto-derived from `HasId[A]`.

### referential_transparency — Referential Transparency
An expression is referentially transparent if it can be replaced by its value without changing behavior. Side effects (I/O, mutation) break this.

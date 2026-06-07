# Scala 3 — Compile-time Operations (Reference)

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/compiletime-ops.html
> Collected: 2026-06-07
> Published: Unknown

## The `scala.compiletime` Package

### constValue and constValueOpt

Extracts the constant value represented by a type (or compile error if not constant):

```scala
import scala.compiletime.constValue
import scala.compiletime.ops.int.S

transparent inline def toIntC[N]: Int =
  inline constValue[N] match
    case 0        => 0
    case _: S[n1] => 1 + toIntC[n1]

inline val ctwo = toIntC[2]
```

`constValueOpt` returns `Option[T]`. `S` is the successor of a singleton type (`S[1]` is `2`). For tuple types, `constValueTuple` converts `(X1, ..., Xn)` into `(constValue[X1], ..., constValue[Xn])`.

### erasedValue

Enables type-based case distinctions; pretends to return a value of its type argument but always results in a compile error unless removed during inlining:

```scala
import scala.compiletime.erasedValue

transparent inline def defaultValue[T] =
  inline erasedValue[T] match
    case _: Byte    => Some(0: Byte)
    case _: Int     => Some(0)
    case _: Boolean => Some(false)
    case _: Unit    => Some(())
    case _          => None

val dInt: Some[Int] = defaultValue[Int]
val dAny: None.type = defaultValue[Any]
```

Type-level recursion:

```scala
transparent inline def toIntT[N <: Nat]: Int =
  inline scala.compiletime.erasedValue[N] match
    case _: Zero.type => 0
    case _: Succ[n] => toIntT[n] + 1
```

`erasedValue` is an `erased` method — no runtime behavior.

### error

```scala
inline def error(inline msg: String): Nothing

import scala.compiletime.{error, codeOf}
inline def fail(inline p1: Any) =
  error("failed on: " + codeOf(p1))

fail(identity("foo")) // error: failed on: identity[String]("foo")
```

### The `scala.compiletime.ops` Package

Type-level primitive operations on singleton types:

```scala
import scala.compiletime.ops.int.*
import scala.compiletime.ops.boolean.*

val conjunction: true && true = true
val multiplication: 3 * 5 = 15
val x: 1 + 2 * 3 = 7
```

Distinguish operations on different types with match types:

```scala
type +[X <: Int | String, Y <: Int | String] = (X, Y) match
  case (Int, Int) => int.+[X, Y]
  case (String, String) => string.+[X, Y]

val concat: "a" + "b" = "ab"
val addition: 1 + 1 = 2
```

## Summoning Givens Selectively — summonFrom

```scala
import scala.compiletime.summonFrom

inline def setFor[T]: Set[T] = summonFrom {
  case ord: Ordering[T] => new TreeSet[T]()(using ord)
  case _                => new HashSet[T]
}
```

Patterns are tried sequentially; first match wins. Pattern-bound given instances also supported (`case given Ordering[T] => ...`). Multiple givens in scope can cause ambiguity errors.

## summonInline

Shorthand for delayed `summon` that yields implicit-not-found errors:

```scala
import scala.compiletime.summonInline

transparent inline def summonInlineCheck[T <: Int](inline t : T) : Any =
  inline t match
    case 1 => summonInline[Missing1]
    case 2 => summonInline[Missing2]
    case _ => summonInline[NotMissing]
```

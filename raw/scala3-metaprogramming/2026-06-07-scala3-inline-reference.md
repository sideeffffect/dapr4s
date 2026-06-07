# Scala 3 — Inline (Reference)

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html
> Collected: 2026-06-07
> Published: Unknown

## Inline Definitions

`inline` is a soft modifier guaranteeing that a definition will be inlined at the point of use.

```scala
object Config:
  inline val logging = false

object Logger:

  private var indent = 0

  inline def log[T](msg: String, indentMargin: =>Int)(op: => T): T =
    if Config.logging then
      println(s"${"  " * indent}start $msg")
      indent += indentMargin
      val result = op
      indent -= indentMargin
      println(s"${"  " * indent}$msg = $result")
      result
    else op
end Logger
```

An **inline value** is treated as a constant (RHS is a constant expression), equivalent to Java's `final`. An **inline method** is always inlined at the call site. If-then-else with constant conditions are rewritten to the selected branch.

```scala
def factorial(n: BigInt): BigInt =
  log(s"factorial($n)", indentSetting) {
    if n == 0 then 1
    else n * factorial(n - 1)
  }
```

When `Config.logging == false` this simplifies to the bare recursion (unused by-name params referenced once don't generate closures).

Inline methods must be fully applied (wildcard args permitted: `Logger.log[String]("some op", indentSetting)(_)`).

### Recursive Inline Methods

```scala
inline def power(x: Double, n: Int): Double =
  if n == 0 then 1.0
  else if n == 1 then x
  else
    val y = power(x, n / 2)
    if n % 2 == 0 then y * y else y * y * x

power(expr, 10)
// translates to straight-line code:
//   val x = expr
//   val y1 = x * x   // ^2
//   val y2 = y1 * y1 // ^4
//   val y3 = y2 * x  // ^5
//   y3 * y3          // ^10
```

Max successive inlines defaults to 32, configurable via `-Xmax-inlines`.

### Inline parameters

```scala
inline def funkyAssertEquals(actual: Double, expected: =>Double, inline delta: Double): Unit =
  if (actual - expected).abs > delta then
    throw new AssertionError(s"difference between ${expected} and ${actual} was larger than ${delta}")
```

`inline` params inline the actual argument in the body (by-name call semantics but allowing code duplication).

### Rules for Overriding

1. An inline method implementing a non-inline method can be invoked at runtime with consistent results.

```scala
abstract class A:
  def f: Int
  def g: Int = f

class B extends A:
  inline def f = 22
  override inline def g = f + 11
```

2. Inline methods are effectively final.
3. Abstract inline methods can only be implemented by other inline methods and cannot be invoked directly.

### Relationship to `@inline`

Scala 2's `@inline` hints the backend. The `inline` modifier is stronger: expansion is guaranteed, happens in the frontend, applies to recursive methods.

### Constant expressions

RHS of inline values / inline parameter arguments must be constant expressions (SLS §6.24). Inline values have literal types: `inline val four = 4` ≡ `inline val four: 4 = 4`.

## Transparent Inline Methods

`transparent` specializes return types to more precise types upon expansion:

```scala
class A
class B extends A:
  def m = true

transparent inline def choose(b: Boolean): A =
  if b then new A else new B

val obj1 = choose(true)  // static type is A
val obj2 = choose(false) // static type is B
obj2.m    // OK
```

Transparent inline methods are expanded during type checking; other inline methods are inlined after typing. For `transparent inline given`, errors during inlining are treated as implicit search mismatches (search continues).

## Inline Conditionals

```scala
inline def update(delta: Int) =
  inline if delta >= 0 then increaseBy(delta)
  else decreaseBy(-delta)
```

`inline if` enforces a constant condition; non-constant conditions are compile-time errors.

## Inline Matches

```scala
transparent inline def g(x: Any): Any =
  inline x match
    case x: String => (x, x) // Tuple2[String, String](x, x)
    case x: Double => x

g(1.0d)    // type 1.0d <: Double
g("test")  // type (String, String)
```

Works with ADTs:

```scala
trait Nat
case object Zero extends Nat
case class Succ[N <: Nat](n: N) extends Nat

transparent inline def toInt(n: Nat): Int =
  inline n match
    case Zero     => 0
    case Succ(n1) => toInt(n1) + 1
```

Reference: "Scala 2020: Semantics-preserving inlining for metaprogramming."

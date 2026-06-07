# Scala 3 — Macros: Quotes and Splices (Reference)

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/macros.html
> Collected: 2026-06-07
> Published: Unknown

## Multi-Staging

### Quoted expressions

Quotes `'{..}` delay execution; splices `${..}` evaluate and insert code. Quoted expressions have type `Expr[T]` (covariant):

```scala
import scala.quoted.*
def unrolledPowerCode(x: Expr[Double], n: Int)(using Quotes): Expr[Double] =
  if n == 0 then '{ 1.0 }
  else if n == 1 then x
  else '{ $x * ${ unrolledPowerCode(x, n-1) } }

'{
  val x = ...
  ${ unrolledPowerCode('{x}, 3) } // evaluates to: x * x * x
}
```

Quotes and splices are duals: `${'{x}} = x` and `'{${e}} = e`.

### Abstract types

Generic/abstract types require a `Type[T]` context bound:

```scala
def singletonListExpr[T: Type](x: Expr[T])(using Quotes): Expr[List[T]] =
  '{ List[T]($x) }
def emptyListExpr[T](using Type[T], Quotes): Expr[List[T]] =
  '{ List.empty[T] }
```

`Type.of[T]` is the default. The compiler provides it when `T` is statically known or composed of types with available `Type[Ui]`.

### Quote context

A given `Quotes` tracks the quotation context. Creating `'{..}` requires `(using Quotes)`. Each splice provides a new `Quotes`.

## Quoted Values

### Lifting

```scala
val expr2: Expr[Int] = Expr(1 + 1) // lift 2 into '{ 2 }

trait ToExpr[T]:
  def apply(x: T)(using Quotes): Expr[T]

given OptionToExpr: [T: {Type, ToExpr}] => ToExpr[Option[T]]:
  def apply(opt: Option[T])(using Quotes): Expr[Option[T]] =
    opt match
      case Some(x) => '{ Some[T]( ${Expr(x)} ) }
      case None => '{ None }
```

### Extracting values from quotes

```scala
def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] =
  n match
    case Expr(m) => unrolledPowerCode(x, m)
    case _ => '{ power($x, $n) }
// or: unrolledPowerCode(x, n.valueOrAbort)

trait FromExpr[T]:
  def unapply(x: Expr[T])(using Quotes): Option[T]
```

## Macros and Multi-Stage Programming

A macro = a top-level splice evaluated during compilation; generated code replaces the splice.

```scala
def power2(x: Double): Double =
  ${ unrolledPowerCode('x, 2) } // x * x
```

### Inline macros

Top-level splices must appear in inline methods for ergonomic use:

```scala
inline def powerMacro(x: Double, inline n: Int): Double =
  ${ powerCode('x, 'n) }

def power2(x: Double): Double = powerMacro(x, 2) // x * x
```

### Avoiding a complete interpreter

Restrict splice contents to: a single call to a compiled static method; literal constants, quoted expressions (parameters), `Type.of` calls, and `Quotes` references.

### Compilation stages

Macro implementations can come from pre-compiled libraries. Cyclic dependencies are errors.

## Safety

### Static Safety
- **Hygiene**: all identifiers are symbolic references to the quote context, preventing accidental rebinding.
- **Well-typed**: if a quote is well-typed, generated code is well-typed.

### Cross-Stage Safety
- **Level consistency**: the staging level = (#surrounding quotes − #splices). Local variables must be defined and used at the same level.

```scala
def badPower(x: Double, n: Int): Double =
  ${ unrolledPowerCode('x, n) } // error: n not known yet
```

Global definitions can be referenced across stages. `'{ power(2, 4) }` refers to the compiled `power`.

- **Type consistency**: generic types require `Type[T]` to preserve information across stages.
- **Scope extrusion**: the system checks at runtime whether quoted scope matches splice scope, catching extruded (out-of-scope) references.

## Staged Lambdas

```scala
def later[T: Type, U: Type](f: Expr[T] => Expr[U]): Expr[T => U] =
  '{ (x: T) => ${ f('x) } }
def now[T: Type, U: Type](f: Expr[T => U]): Expr[T] => Expr[U] =
  (x: Expr[T]) => Expr.betaReduce('{ $f($x) })
```

## Staged Classes

Quoted code supports local class definitions:

```scala
def mkRunnable(x: Int)(using Quotes): Expr[Runnable] = '{
  class MyRunnable extends Runnable:
    def run(): Unit = ... // custom code using x
  new MyRunnable
}
```

Local class types cannot escape the quote, but instances using known interfaces can be returned.

## Quote Pattern Matching

```scala
def fusedPowCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] =
  x match
    case '{ power($y, $m) } => fusedPowCode(y, '{ $n * $m })
    case _ => '{ power($x, $n) }
```

### Sub-patterns / value extraction

```scala
case '{ power($y, ${Expr(m)}) } => fusedUnrolledPowCode(y, n * m)
```

### Closed patterns / HOAS patterns

```scala
'{ ((x: Int) => x + 1).apply(2) } match
  case '{ ((y: Int) => $f(y): Int).apply($z: Int) } =>
    Expr.betaReduce('{ $f($z)}) // '{ 2 + 1 }
```

### Type variables

```scala
def fuseMapCode(x: Expr[List[Int]]): Expr[List[Int]] =
  x match
    case '{ ($ls: List[t]).map[u]($f).map[Int]($g) } =>
      '{ $ls.map($g.compose($f)) }

def let(x: Expr[Any])(using Quotes): Expr[Any] =
  x match
    case '{ $x: t } => '{ val y: t = $x; y }
```

Formal type-variable definitions: `case '{ type t; $x: t } =>`, with bounds `case '{ type t >: List[Int] <: Seq[Int]; $x: t } =>`.

### Type patterns

```scala
def empty[T: Type](using Quotes): Expr[T] =
  Type.of[T] match
    case '[String] => '{ "" }
    case '[List[t]] => '{ List.empty[t] }
```

### Type testing and casting

`expr.isExprOf[T]` / `expr.asExprOf[T]` use `Type[T]` to circumvent erasure (unlike `isInstanceOf[Expr[T]]`).

## Sub-Expression Transformation — ExprMap

```scala
trait ExprMap:
  def transform[T](e: Expr[T])(using Type[T])(using Quotes): Expr[T]
  def transformChildren[T](e: Expr[T])(using Type[T])(using Quotes): Expr[T]

object OptimizeIdentity extends ExprMap:
  def transform[T](e: Expr[T])(using Type[T])(using Quotes): Expr[T] =
    transformChildren(e) match
      case '{ identity($x) } => x
      case _ => e
```

## Staged Implicit Summoning

```scala
def summon[T: Type](using Quotes): Option[Expr[T]]

def setForExpr[T: Type]()(using Quotes): Expr[Set[T]] =
  Expr.summon[Ordering[T]] match
    case Some(ord) => '{ new TreeSet[T]()($ord) }
    case _ => '{ new HashSet[T] }
```

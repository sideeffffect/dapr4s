# Separation Checking

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/separation-checking.md
> Collected: 2026-05-01
> Published: Unknown

## Introduction

Separation checking is an extension of capture checking that enforces unique, un-aliased access to capabilities. Enabled by:

```scala
import language.experimental.separationChecking
```

(in addition to `language.experimental.captureChecking`)

The purpose is to ensure that certain accesses to capabilities are not aliased. Example — matrix multiplication:

```scala
def multiply(a: Matrix, b: Matrix, c: Matrix^): Unit = ???
```

The `^` on `c` enforces:
- `a` and `b` are read-only; `c` can be updated
- `a` and `b` are different from `c` (but `a` and `b` may alias each other)

## Core Mechanism

Each occurrence of `any` is interpreted as a separate top capability. The system tracks which capabilities are _hidden_ by each `any`. If capture checking widens capability `x` to top capability `anyᵢ`, then `x` is hidden by `anyᵢ`. Any capability hidden by `anyᵢ` cannot be referenced independently or hidden in another `anyⱼ` in code that can see `anyᵢ`.

These checks apply only to exclusive capabilities. `SharedCapability` types are exempted.

```scala
val y = Ref(1)
val x: Ref^ = y
x.get
y.get // error — y is hidden by the any of x
```

## Key Definitions

- **Transitive capture set** `tcs(c)` of capability `c` with capture set `C`: `c` itself plus `tcs(C)`
- **Transitive capture set** `tcs(C)`: union of `tcs(c)` for all `c` in `C`
- **Interference**: Two capture sets interfere if one contains exclusive capability `x` and the other also contains `x` or `x.rd`
- **Separation**: Two capture sets are separated if their transitive capture sets don't interfere

## Checking Applications

When checking `f(e₁, ..., eₙ)`, the hidden set of the instantiated top capability for each argument `eᵢ` must be separated from the capture sets of all other arguments, the function prefix, and the function result.

```scala
multiply(a, b, a) // error: a in hidden set of last arg, also in first arg
```

Exception: no separation error is reported between two sets if a formal parameter's capture set explicitly names a conflicting parameter:

```scala
def seq(f: () => Unit, g: () ->{any, f} Unit): Unit = ...
// safe to pass same function twice: g's any doesn't need to hide f
seq(plusOne, plusOne) // ok
```

## Checking Statement Sequences

When capability `x` is used in a statement sequence, `{x}` must be separated from the hidden sets of all previous definitions:

```scala
val a: Ref^ = Ref(1)
val b: Ref^ = a      // b hides a
val x = a.get        // error: a is hidden by b's any
```

Using `Ref^{a}` instead of `Ref^` for `b` avoids the error since there's no hidden set.

## Checking Types

Top capabilities in a type must not have interfering hidden sets with other parts of the same type:

```scala
val b: (Ref^, Ref^) = (a, a)       // error: both ^s hide a
val c: (Ref^, Ref^{a}) = (a, a)    // error: hidden set of first ^ contains a
val d: (Ref^{a}, Ref^{a}) = (a, a) // ok: no hidden sets
```

## Checking Return Types

The hidden set of an `any` in a return type cannot reference exclusive or read-only capabilities defined outside the function (including parameters):

```scala
def newRef(): Ref^ = Ref(1)     // ok: fresh capability
def newRef(): Ref^ =            // ok
  val a = Ref(1); a

def newRef(): Ref^ = a          // error: a defined outside
def incr(a: Ref^): Ref^ = ...   // error: parameter a in hidden set
```

## `fresh` in Function Type Results

`fresh` creates existentially-bound capabilities: `() -> Ref^{fresh}` means `() -> ∃fresh. Ref^{fresh}`. Each call yields a distinct capability, proving non-aliasing across calls:

```scala
val mkRef: () -> Ref^{fresh} = () => Ref(1)
val a = mkRef()  // Ref^{fresh₁}
val b = mkRef()  // Ref^{fresh₂}
// fresh₁ ≠ fresh₂, so a and b are separated
```

The hidden set of a result `fresh` cannot contain capabilities from outside the function.

## Consume Parameters

The `consume` modifier on a parameter ensures the actual argument is not used after the call:

```scala
def incr(consume a: Ref^): Ref^ =
  a.set(a.get + 1)
  a

val a1 = Ref(1)
val a2 = incr(a1)  // a1 consumed here
val a3 = incr(a2)  // a2 consumed here
// val x = incr(a1) // error: a1 already consumed
```

Consume parameters enforce linear access to resources. This enables treating mutable buffers as if purely functional:

```scala
def linearAdd[T](consume buf: Buffer[T]^, elem: T): Buffer[T]^ =
  buf += elem
```

Read-only capabilities can also be consumed:

```scala
def contents[T](consume buf: Buffer[T]): Int ->{buf.rd} T =
  i => buf(i)
```

Consuming `buf.rd` freezes the buffer: no further writes possible, but reads remain available.

## Consume Methods

Adding `consume` to a method implies `update` in a `Mutable` class:

```scala
class Buffer[T] extends Mutable:
  consume def +=(x: T): Buffer[T]^ = this // returns new version

val b = Buffer[Int]() += 1 += 2  // linear chain
```

## The `freeze` Wrapper

Creates an immutable view of a mutable structure after initialization:

```scala
import caps.freeze

val f: IArr[String] =
  val a = Arr[String](2)
  a(0) = "hello"
  a(1) = "world"
  freeze(a)
```

`freeze` is defined as:
```scala
def freeze(consume x: Mutable): x.type = x
```

It consumes the mutable value and returns it with its capture set mapped to `{}`. Only safe with separation checking enabled.

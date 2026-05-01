# Capabilities and Resources

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [overview](../../raw/scala-capture-checking/2026-05-01-overview.md); [basics](../../raw/scala-capture-checking/2026-05-01-basics.md); [classes](../../raw/scala-capture-checking/2026-05-01-classes.md); [classifiers](../../raw/scala-capture-checking/2026-05-01-classifiers.md)
> Updated: 2026-05-01

## Overview

In Scala 3 capture checking, a _capability_ is a value "of interest" whose usage is tracked in the type system. Capabilities model effects: file handles enable file I/O, `CanThrow[E]` enables throwing exception `E`, `Async` enables suspension. By tracking capabilities in types, the compiler can reason statically about what effects a computation may perform and whether resources are safely scoped.

## Defining Capabilities

A value becomes a capability when its type extends `Capability` (directly or indirectly) or has a non-empty capture set. The recommended pattern is to extend one of the predefined subtypes:

```scala
class File(path: String) extends ExclusiveCapability
class FileSystem extends SharedCapability
class CanThrow[E] extends Control  // extends SharedCapability
```

Values of capturing types are themselves capabilities — a `Logger^{fs}` is a capability even though `Logger` doesn't directly extend `Capability`.

## Capability Hierarchy

```
              Capability (sealed)
              /                 \
   SharedCapability          ExclusiveCapability
   ----------------                  |
        |                         Unscoped
     Control                      --------
     -------
```

Classifiers are underlined (enforcing capture set restrictions):

- **`SharedCapability`** — Base for shared (non-exclusive) capabilities; is a classifier. Values can be aliased freely.
- **`ExclusiveCapability`** — Base for capabilities with anti-aliasing constraints (governed by separation checking). Not a classifier.
- **`Control`** — Extends `SharedCapability`; classifier for control-flow capabilities: `CanThrow`, boundary `Label`, `Async`. Control capabilities cannot capture exclusive (mutable) capabilities.
- **`Unscoped`** — Extends `ExclusiveCapability`; classifier for capabilities that can escape their defining environment (e.g., `Ref` cells that don't capture external resources).
- **`Mutable`** — Extends `Stateful` and `Unscoped`; the standard base for mutable data structures.

Since `Capability` is sealed, all capability types are either shared or exclusive.

## The Universal Capability

`any` is the root capability from which all others ultimately derive. `T^` is shorthand for `T^{any}` — a type that can retain arbitrary capabilities. Any capability is a subtype of `any` in the capture-set ordering.

## Capability Classifiers

Classifiers restrict what a parameter can capture at the classifier boundary. A function parameter typed `Async^` where `Async extends Control` can only receive an `async` argument that captures other `Control` capabilities — no I/O or mutable state.

The `.only[C]` projection restricts a capture set to capabilities compatible with classifier `C`:

```scala
object Try:
  def apply[T](body: => T): Try[T]^{body.only[Control]} = ???
```

If `body` uses `{io, async}` where only `async` is a `Control` capability, the result is `Try^{async}`. The `io` capability is dropped. A fully effect-polymorphic value is kept because we cannot prove it doesn't carry `Control` capabilities.

## Implicit Capability Passing

Capabilities are naturally passed as implicit parameters via `using` clauses:

```scala
def processData(using Async): Data =
  readDataEventually(file)  // Async forwarded implicitly
```

This reduces boilerplate compared to explicit threading of capabilities through every call in the chain.

## Global Capabilities

Unlike traditional object capability systems, Scala 3 allows global capabilities because type-level tracking provides a second control mechanism:

```scala
object Console extends SharedCapability:
  val out: File = new File("stdout")

object SimpleLogger uses Console:
  def log(str: String): Unit = Console.out.println(str)
```

A function using `Console.out` gets type `() ->{Console.out} Unit` — it cannot be passed to a context expecting a pure function. Global objects that reference capabilities declare the dependency in a `uses` clause.

## Resource Lifetimes

Tracking capabilities in types enables lifetime control. The compiler enforces that a capability cannot appear in a type that outlives the scope where the capability was defined:

```scala
def logged[T](op: Logger^ => T): T =
  val f = new File("logfile")
  val l: Logger^{f} = new Logger(f)
  val result = op(l)
  f.close()
  result
// val bad = logged { l => () => l.log("later") } // rejected: l escapes
```

The type parameter `T` must be independent of the identity of `l`.

## Class Capture Sets

A class retains _local capabilities_ (defined outside the class, referenced from its body) and _argument capabilities_ (passed as constructor parameters). Local capabilities are inherited.

For classes visible across compilation units, external capabilities must be declared explicitly with a `uses` clause:

```scala
class C(x: () => Unit) uses out, io:
  val f: File^{io} = File()
  def g() = out.println("one"); f.write("two"); x()
```

The `uses ... initially` variant declares capabilities accessed only during initialization (not retained in the instance).

## Traits and Open Classes

For non-open, non-abstract classes all subclasses are known at compile time, so `this`'s capture set can be precisely inferred. For traits, abstract classes, and `open` classes, the checker conservatively assumes `this` can capture anything (`any`). Sealed abstract classes are safe to infer precisely.

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capturing Types](capturing-types.md)
- [Capability Classifiers](capability-classifiers.md)
- [Separation and Mutability](separation-and-mutability.md)
- [Safe Exceptions](safe-exceptions.md)

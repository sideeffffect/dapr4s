# Context Functions and Capability Passing

> Sources: Scala Documentation, Unknown; Krzysztof Ciesielski / SoftwareMill, 2024-04-29; SoftwareMill blog (unavailable), Unknown
> Raw: [Scala 3 Context Functions Reference](../../raw/scala3-language/2026-05-01-scala3-context-functions-reference.md); [Callbacks with Structured Concurrency — Ox](../../raw/scala3-language/2026-05-01-callbacks-structured-concurrency-scala-ox.md); [Context is King (placeholder)](../../raw/scala3-language/scala3-context-functions-context-is-king.md)

## Overview

Context functions (`A ?=> B`) are Scala 3's mechanism for functions whose parameters are filled implicitly from the surrounding context. They extend `given`/`using` from named parameters to the function type level, enabling capability-passing patterns where a capability (an object granting access to a resource or effect) is threaded through an entire call graph without being mentioned at every site. This is the core mechanism behind libraries like Ox and the Safe Scala agent harness.

## Syntax

A context function type is written with `?=>`:

```scala
type Executable[T] = ExecutionContext ?=> T
type WithIO[T]     = IO ?=> T
```

A function returning such a type is a context function. When the compiler expects a context function and the expression in that position is not already one, it automatically wraps the expression:

```scala
// These are equivalent:
val f: Config ?=> String = "hello"
val g: Config ?=> String = (c: Config) ?=> "hello"
```

The synthesized parameter becomes available as a `given` inside the body.

## Explicit vs Implicit Application

Context functions can be applied either explicitly or implicitly:

```scala
given ec: ExecutionContext = ...
def f(x: Int): ExecutionContext ?=> Int = x + 1

f(2)          // implicit: compiler fills ExecutionContext from scope
f(2)(using ec) // explicit: caller provides it directly
```

## Capability Passing Pattern

The pattern has three roles:

1. **The capability type** — a trait or object that represents permission to perform a class of effects.
2. **The provider** — creates the capability and establishes the scope in which it is available using `given`.
3. **The consumers** — demand the capability via `using` in their parameter list, or accept a context function `Cap ?=> T`.

```scala
// 1. Capability
trait IO

// 2. Provider — safe runtime boundary
def withIO[T](body: IO ?=> T): T =
  given io: IO = new IO {}
  body

// 3. Consumer
def readFile(path: String)(using IO): String = ...
def writeFile(path: String, content: String)(using IO): Unit = ...

// Usage
withIO {
  val content = readFile("/etc/hosts")
  writeFile("/tmp/out", content)
}
```

Outside `withIO`, there is no `IO` in scope. Code that calls `readFile` without being inside such a scope will not compile.

## Context Functions as First-Class Types

Because `IO ?=> T` is a type, it can appear in data structures and function parameters:

```scala
// A pipeline that requires Ox (structured concurrency capability)
type Pipe[A, B] = Ox ?=> Source[A] => Source[B]

// A handler that requires a scope
class MessageHandler(pipeline: Ox ?=> Source[Msg] => Source[Msg]):
  def start()(using ox: Ox): Unit = ...
```

This is exactly the pattern used by the Ox library and Tapir's `NettySyncServer` for WebSocket pipelines (`Ox ?=> Source[Req] => Source[Resp]`). The capability requirement is encoded in the type; no runtime checks are needed.

## OxDispatcher: Context Functions Bridging Callbacks

A concrete real-world application: callback-based APIs (e.g., Netty handlers) can't use structured concurrency directly because they fire outside any supervision scope. The `OxDispatcher` bridges this gap by holding a long-lived scope and accepting thunks typed as `Ox ?=> Unit`:

```scala
class OxDispatcher()(using ox: Ox):
  def runAsync(thunk: Ox ?=> Unit)(onError: Throwable => Unit): Unit =
    actor.tell(_.runAsync(thunk, onError))
```

The `Ox ?=> Unit` type tells the compiler: "this thunk needs a structured concurrency scope, and the dispatcher will provide it." Callers write normal direct-style Ox code; the dispatcher injects the scope at the right moment.

## Builder DSL Pattern

Context functions enable clean, boilerplate-free DSLs by automatically threading a builder through nested blocks:

```scala
def table(init: Table ?=> Unit): Table =
  given t: Table = Table()
  init; t

def row(init: Row ?=> Unit)(using t: Table): Unit =
  given r: Row = Row()
  init; t.add(r)

def cell(str: String)(using r: Row): Unit =
  r.add(Cell(str))

// Usage — no explicit passing:
table {
  row { cell("A"); cell("B") }
  row { cell("C"); cell("D") }
}
```

## Postcondition / `ensuring` Pattern

Opaque types and context functions combine to provide zero-overhead postcondition assertions:

```scala
object PostConditions:
  opaque type WrappedResult[T] = T

  def result[T](using r: WrappedResult[T]): T = r

  extension [T](x: T)
    def ensuring(condition: WrappedResult[T] ?=> Boolean): T =
      assert(condition(using x)); x

// Usage:
List(1, 2, 3).sum.ensuring(result == 6)
```

`result` is available inside `ensuring`'s lambda as a given, injected by the context function mechanism. No allocation, no intermediate objects.

## Design Guidelines for Library APIs

- **Name your capability traits clearly** — `IO`, `Ox`, `DaprScope`, etc. The name is the documentation.
- **Narrow capability scope** — provide capabilities only in tightly bounded `with*` functions; never leak them into top-level `given` definitions.
- **Prefer `using` for method parameters, `?=>` for function-valued parameters** — `using` is explicit at call sites; `?=>` is for higher-order functions where the capability propagates through a value.
- **Combine with opaque types** — opaque types encode the capability holder itself, preventing fabrication outside the module.

## See Also

- [Given/Using](given-using.md)
- [Opaque Types](opaque-types.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
- [Safe Mode](../scala-capture-checking/safe-mode.md)

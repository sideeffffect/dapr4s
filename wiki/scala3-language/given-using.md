# Given Instances and Using Clauses

> Sources: Scala Documentation (Book), Unknown; Scala Documentation (Reference), Unknown
> Raw: [Context Parameters — Scala 3 Book](../../raw/scala3-language/2026-05-01-scala3-given-using-book.md); [Given Instances Reference](../../raw/scala3-language/2026-05-01-scala3-givens-reference.md)

## Overview

`given` and `using` are Scala 3's replacement for Scala 2's `implicit` keyword, split into two distinct roles: `given` declares a canonical contextual value, and `using` marks parameters that the compiler fills from the ambient context. Together they form the foundation for capability passing, type class instances, and implicit dependency injection — without the ambiguity of the unified `implicit` system.

## The Problem: Explicit Parameter Threading

Passing configuration or capabilities through deep call chains is noisy:

```scala
case class Config(port: Int, baseUrl: String)

def renderWebsite(path: String, config: Config): String =
  "<html>" + renderWidget(List("cart"), config) + "</html>"

def renderWidget(items: List[String], config: Config): String = ???
```

Every function in the chain must accept and forward `config`, even if it only uses it transitively.

## `using` Parameters

Mark a parameter as contextual with `using`. The compiler supplies it automatically from the surrounding scope:

```scala
def renderWebsite(path: String)(using config: Config): String =
  "<html>" + renderWidget(List("cart")) + "</html>"

def renderWidget(items: List[String])(using config: Config): String = ???
```

The `config` at the `renderWidget` call is inferred — the compiler looks for a `Config` in scope. The parameter name can be omitted when the body doesn't reference it by name:

```scala
def renderWebsite(path: String)(using Config): String = ...
```

Explicit provision is always possible when the inferred value is wrong:

```scala
renderWebsite("/home")(using prodConfig)
```

## `given` Declarations

`given` introduces a canonical value of a type into scope:

```scala
given Config = Config(8080, "docs.scala-lang.org")
```

Anonymous: the compiler generates a stable name (`given_Config`). Named givens are preferred in libraries for binary compatibility:

```scala
given defaultConfig: Config = Config(8080, "docs.scala-lang.org")
```

### Conditional Givens (Type Class Instances)

`given` shines for type class patterns. A `given` for `Ord[List[T]]` can depend on a `given Ord[T]`:

```scala
given intOrd: Ord[Int]:
  def compare(x: Int, y: Int) =
    if x < y then -1 else if x > y then +1 else 0

given [T: Ord] => listOrd: Ord[List[T]]:
  def compare(xs: List[T], ys: List[T]) = ...
```

The `[T: Ord]` context bound ensures the instance is only synthesized when `Ord[T]` is available.

### Alias Givens

An alias given forwards to an existing value or expression:

```scala
given global: ExecutionContext = ForkJoinPool()
```

This initializes lazily (on first access) as a singleton. Immutable alias givens (pointing to `val`s) are simple forwarders. Conditional givens (parameterized) create a fresh instance each time.

## Initialization Semantics

| Kind | Initialization |
|---|---|
| Unconditional structural (`given T: …`) | Lazy singleton, on first use |
| Alias (`given T = expr`) | Lazy singleton |
| Conditional (`given [A: TC] => T: …`) | Fresh instance per reference |

## Summoning Capabilities

`summon[T]` retrieves the `given` of type `T` in scope — equivalent to Scala 2's `implicitly[T]`:

```scala
val ctx: ExecutionContext = summon[ExecutionContext]
```

## Capability Injection Pattern

For capability-based designs, `given` combined with `using` provides safe injection:

```scala
trait DaprScope   // capability: access to Dapr
trait LogScope    // capability: structured logging

def withDapr[T](body: DaprScope ?=> T): T =
  given ds: DaprScope = new DaprScope {}
  body

def callService(appId: String)(using DaprScope, LogScope): Unit = ...
```

The `withDapr` function is the trust boundary: it creates the capability and provides it as a `given` for the duration of `body`. Code outside `withDapr` cannot accidentally have `DaprScope` in scope.

## Interaction with Context Functions

`given`/`using` and context functions (`?=>`) are two expressions of the same mechanism:

- `(using Cap): T` in a method signature = the caller's scope must have `Cap`
- `Cap ?=> T` as a function type = the function value itself carries the requirement

When a method accepts `(using Cap)`, the compiler looks for a `given Cap` in the lexical scope. When it accepts `Cap ?=> T`, it expects a function value; the `Cap` is provided when that function is applied.

## Scala 2 Migration

| Scala 2 | Scala 3 |
|---|---|
| `implicit val x: T = …` | `given x: T = …` |
| `implicit def f(implicit x: T): U` | `def f(using x: T): U` |
| `implicitly[T]` | `summon[T]` |
| `def f(implicit x: T, y: U)` | `def f(using x: T)(using y: U)` or `def f(using x: T, y: U)` |

## See Also

- [Context Functions and Capability Passing](context-functions-capability-passing.md)
- [Opaque Types](opaque-types.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)

# Safe Exceptions

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [checked-exceptions](../../raw/scala-capture-checking/2026-05-01-checked-exceptions.md); [overview](../../raw/scala-capture-checking/2026-05-01-overview.md); [classifiers](../../raw/scala-capture-checking/2026-05-01-classifiers.md)
> Updated: 2026-05-01

## Overview

Scala 3 integrates checked exceptions into capture checking via `CanThrow` capabilities and the `throws` clause syntax. Rather than using checked exception types in method signatures as in Java, Scala 3 tracks throwability as a capability — making the feature opt-in, composable, and compatible with capture checking's escape prevention guarantees.

## Enabling Checked Exceptions

```scala
import language.experimental.saferExceptions
```

When also using capture checking:

```scala
import language.experimental.captureChecking
import language.experimental.saferExceptions
```

## The `throws` Clause and `CanThrow`

A `throws` clause desugars to an implicit `CanThrow` capability parameter:

```scala
def f(x: Double): Double throws LimitExceeded
// equivalent to:
def f(x: Double)(using CanThrow[LimitExceeded]): Double
```

`CanThrow[E]` extends `Control` (a classifier), which means it is a control-flow capability that:
- Can be passed implicitly down call chains
- Cannot capture exclusive (mutable) capabilities

## Capability Creation by `try`

A `try` expression creates `CanThrow` capabilities during compilation. Code inside the `try` block gets an implicit `CanThrow[E]` for the caught exception types, enabling throwing within that scope.

## Escape Prevention

Under capture checking, a `CanThrow` capability cannot escape into a returned closure:

```scala
// This would be rejected:
def escape(using ct: CanThrow[IOException]): () => Unit =
  () => throw IOException()  // error: ct escapes
```

The `try` expression's result type cannot capture the internal `CanThrow` capabilities. This prevents deferred throws — a closure that throws only when called later, outside the `try` block.

Integration requirements with capture checking:
1. `CanThrow` is declared to extend `Control` for tracking
2. Escape checking is extended to `try` expressions so result types cannot capture internal capabilities

## The `Try` Type and Classifier Filtering

The `Try.apply` method uses the `.only[Control]` classifier projection to retain only control-flow capabilities from its body in the result:

```scala
object Try:
  def apply[T](body: => T): Try[T]^{body.only[Control]} = ???
```

If the body uses `{io, async, ct}` where `ct: CanThrow[E]` extends `Control`, the resulting `Try^{async, ct}` drops the `io` capability. This models that `Try` can propagate exceptions but does not propagate I/O or mutable state.

## Error Handling and Suggestions

When a required `CanThrow` capability is absent, the compiler reports an error with fix suggestions:
- Add a `using` clause
- Add a `throws` clause to the method
- Wrap the call in an appropriate `try` block

## Safe Mode and Exceptions

Safe mode (the `safe.md` feature) permits exceptions specifically. Rather than tracking all exception types through capabilities in untrusted code, safe mode relies on wrapping untrusted code calls in `Try` blocks to contain thrown exceptions as values. This keeps the safety model tractable for agent-submitted code.

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capabilities and Resources](capabilities-and-resources.md)
- [Capability Classifiers](capability-classifiers.md)
- [Safe Mode](safe-mode.md)

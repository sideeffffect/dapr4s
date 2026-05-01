# Checked Exceptions

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/checked-exceptions.md
> Collected: 2026-05-01
> Published: Unknown

Scala enables checked exceptions through a language import:

```scala
import language.experimental.saferExceptions
```

## Key Mechanism

The `throws` clause expands into an implicit `CanThrow` capability parameter. For example:

```scala
def f(x: Double): Double throws LimitExceeded
// equivalent to:
def f(x: Double)(using CanThrow[LimitExceeded]): Double
```

## Error Handling

When the required capability is absent, the compiler reports an error with suggested fixes:
- Add a `using` clause
- Include a `throws` clause
- Wrap code in an appropriate `try` block

## Capability Creation and Safety

The `try` expression creates `CanThrow` capabilities during compilation.

The system includes safeguards against capability escape — preventing capabilities from persisting beyond their intended scope. Under `language.experimental.captureChecking`, code that would allow a `CanThrow` capability to escape into a returned closure is rejected, preventing unhandled exceptions at runtime.

## Integration with Capture Checking

Two modifications are required when integrating with capture checking:
1. Declare `CanThrow` as extending `Control` for reference tracking
2. Extend escape checking to `try` expressions so their result types cannot capture internal capabilities

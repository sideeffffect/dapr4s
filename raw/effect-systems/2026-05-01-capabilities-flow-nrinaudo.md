# Controlling Program Flow with Capabilities

> Source: https://nrinaudo.github.io/articles/capabilities_flow.html
> Collected: 2026-05-01
> Published: Unknown

**Author:** Nicolas Rinaudo

## Overview

This article explores using capabilities in Scala to implement flow control mechanisms, specifically examining how to "jump around in a program, short-circuiting computations as and when necessary."

## Main Problem

Simple context functions cannot impact program flow. This article addresses that gap by demonstrating how to implement control flow without losing desirable properties of capability-based code.

## Sequencing Data as Running Example

The `sequence` operation — converting `List[Option[A]]` to `Option[List[A]]`. If any element is `None`, the result is `None`; otherwise, it returns `Some` containing unwrapped values.

**Without capabilities:** A monadic approach using `foldLeft` that processes the entire list even after encountering `None`, then requires reversing the result.

**With capabilities using `boundary` and `break`:**

```scala
def sequence[A](oas: List[Option[A]]): Option[List[A]] =
  boundary:
    Some(oas.map:
      case Some(a) => a
      case None    => break(Option.empty)
    )
```

## Implementation Details

The core mechanism requires:

- **`Label[A]`**: A marker for jump destinations
- **`Break[A]`**: An exception carrying a value and its associated label
- **`boundary[A]`**: A prompt creating and managing the label within a try-catch block

The implementation leverages exceptions for control flow while maintaining type safety through the `Label` type parameter.

## Specialized Boundaries

A generalized `handle` combinator allows customizing success and failure behaviors:

```scala
def handle[E, S, A](
  la: Label[E] ?=> S,
  success: S => A,
  error: E => A
): A
```

An `option` variant specifically for `Option` types simplifies `sequence`:

```scala
def sequence[A](oas: List[Option[A]]): Option[List[A]] =
  option:
    oas.map:
      case Some(a) => a
      case None    => break
```

## The `?` Extension Method

Inspired by Rust's error operator, an extension method mimics idiomatic error handling:

```scala
extension [A](oa: Option[A]) def ? : Label[Unit] ?=> A =
  oa match
    case Some(a) => a
    case None    => break
```

This yields the cleanest version: `option: oas.map(_.?)`

## Supporting `Either`

The same pattern extends to `Either` with corresponding `either` prompt and extension methods.

## Nested Prompts

Multiple boundaries can nest, with explicit label passing controlling which boundary catches a `break`:

```scala
def sequencePositive(ois: List[Option[Int]]): Either[String, Option[List[Int]]] =
  either: fail ?=>
    option:
      ois.map: oi =>
        val i = oi.?
        if i >= 0 then i
        else break(s"Negative number: $i")(using fail)
```

## Safety Through Capture Checking

Labels must not escape their prompt scope. Scala's capture checking mechanism prevents this by flagging `Label` as a `SharedCapability`. However, implementation details require unsafe assertions to allow internal escape within the library code.

## Key Takeaway

The author demonstrates that capabilities enable elegant, direct-style control flow that "drastically simplify[ies] code," while emphasizing that capture checking is essential for safe capability semantics.

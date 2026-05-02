# Understanding Capture Checking in Scala

> Source: https://softwaremill.com/understanding-capture-checking-in-scala/
> Collected: 2026-05-01
> Published: Unknown

Author: Adam Warski, SoftwareMill.

Capture checking is an upcoming Scala feature enabling compile-time tracking of which designated values (capabilities) are captured and stored as references by other values.

## What is a Capture?

A value captures another when it retains a reference to it within its object tree. For example, a `JsonParser` instance capturing an `InputStream` means the stream is retained within the parser's structure.

## The `^` Symbol

The caret notation designates tracked capabilities. A type like `InputStream^` indicates a tracked capability with an unknown capture set, while `JsonParser^{in}` specifies exactly which capabilities are captured.

## Core Examples

### Resource Management

The article demonstrates a `withFile` function that opens files, applies operations, and guarantees closure:

```
def withFile[T](name: String)(op: InputStream^ => T): T
```

Attempting to leak the stream via `withFile("data.txt")(identity)` triggers compiler errors, preventing unsafe resource handling.

### Concurrency with Ox

Capture checking prevents concurrency scope leakage, ensuring spawned threads cannot outlive their scope.

## Type Hierarchy

The capture system creates a subtyping relationship:
- `JsonParser` (no captures) ⊆ `JsonParser^{in1}` (specific capture) ⊆ `JsonParser^` (unknown captures)

This hierarchy reflects that fewer instances capture specifically `in1` than capture arbitrary values.

## Function Types

Under capture checking, regular functions (`A => B`) capture unknown capabilities and become capabilities themselves. Pure functions use `A -> B` notation, guaranteeing no captures.

## Advanced Features

Separation checking — tracking mutable value usage and reference aliasing — builds on capture checking foundations, potentially bringing Rust-like borrow checker benefits to Scala.

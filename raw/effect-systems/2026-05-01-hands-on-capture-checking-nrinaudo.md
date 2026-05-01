# Hands on Capture Checking

> Source: https://nrinaudo.github.io/articles/capture_checking.html
> Collected: 2026-05-01
> Published: Unknown

**Author:** Nicolas Rinaudo
**Context:** Written companion to a live-coding session originally given at Scala Days 2025

## Introduction

The author describes giving a live coding session on capture checking at Scala Days 2025. This article is a written version of that session. The author notes that code examples won't compile directly in a REPL due to scoping issues with capture checking; the GitHub repository contains compilable code.

## What Problem Are We Solving?

"Capture" occurs when a function references values from outside its scope. The author provides examples showing how this can be problematic:

- **Try-with-resource pattern:** A function capturing a closed resource leads to runtime errors
- **Secrets API:** Preventing sensitive values from escaping their intended scope

The core problem: "allowing code authors to prevent some _specific_ values from escaping."

## Capture Sets

The solution involves adding capture sets to the type system. Key concepts:

**Basic syntax:** `A^{a1}` indicates type `A` capturing value `a1`

**Root capability:** `cap` is the base tracked value; `A^` is syntactic sugar for `A^{cap}`

**Key principle:** Values should never be dropped from capture sets, only added.

### Fixing `withFile`

By declaring the parameter as `OutputStream^{cap} => T`, the function explicitly prevents the resource from escaping. The compiler rejects attempts to capture and return the stream.

### Fixing `withSecret`

Similar approach works for secrets, though primitive types require wrapper classes since strings cannot meaningfully track values.

## Capture Sets and Subtyping

**The subset rule:** "If `c1` and `c2` are two capture sets such that `c1` is a subset of `c2`, then `T^c1` is a subtype of `T^c2`."

This allows passing values with narrower capture sets where wider ones are expected, without losing information.

## Syntactic Sugar

Three function arrow variants exist:
- `A => B` — tracked function (impure, captures `cap`)
- `A -> B` — untracked function (pure, no captures)
- `A ->{a1, a2} B` — function capturing specific named values

The design choice to make `=>` impure by default allows seamless use of pure functions where impure ones are expected, avoiding code duplication in standard library combinators.

## Tracking Transitivity

The transitivity rule permits dropping intermediate values from capture sets when transitive relationships exist. If `a3` tracks `a2` which tracks `a1`, then `a3` can be treated as capturing only `a1`. This works because the semantic goal is preventing `a1` from escaping, not preventing `a2` from escaping incidentally.

## Capturing Values

### Capturing Functions

Functions capture free variables mentioned in their body. A function referring to tracked value `a1` must have `a1` in its capture set: `Int ->{a1} Int`

### Capturing Classes

Classes must declare field types that accommodate captured values. Passing `A^` to a field expecting `A` fails type checking, requiring the class to accept `A^`.

### Type Parameters and Capture Tunneling

When a polymorphic class holds a tracked type parameter, "capture tunneling" prevents the capture set from propagating to the class itself. Only direct interaction with captured fields causes the class to inherit their capture requirements. This reduces syntactic overhead while maintaining safety.

## Catching Undesired Escapes

The compiler uses "avoidance" to reject code where values escape their scope. When a type parameter is inferred to capture `cap` through avoidance, it signals an escaped value. The heuristic flags `T` parameters inferred to anything capturing `cap` as problematic.

## Conclusion

The author characterizes capture checking as largely transparent for most developers:

> "the vast majority of the code I've written with capture checking was exactly the code I'd have written without it"

The compiler occasionally flags unsafe patterns. The author expresses enthusiasm about capture checking becoming a standard feature and its role in enabling safe capability-based programming.

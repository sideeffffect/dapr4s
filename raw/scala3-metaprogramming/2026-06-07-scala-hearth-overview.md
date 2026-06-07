# Scala-Hearth — Overview

> Source: https://scala-hearth.readthedocs.io/en/latest/
> Collected: 2026-06-07
> Published: Unknown

## What is Scala-Hearth?

Scala-Hearth is "the first Scala macros' standard library" designed to help developers write robust, maintainable macros that work across both Scala 2 and Scala 3. It provides a unified API for macro development that abstracts away the differences between the two macro systems, enabling true cross-compilation.

## Key Problems It Solves

**Cross-Compilation Challenge**: Scala 2 and Scala 3 have fundamentally different macro systems, making it difficult to maintain shared macro code.

**Solution Approach**: Hearth provides:
- A unified macro API with implementations for both Scala versions
- "Cross-quotes" compiler plugin technology for Scala 3
- Shared trait-based macro logic that works in both versions
- Version-specific adapter code that bridges differences

This pattern enables developers to write macro logic once and deploy it across both compiler versions with minimal boilerplate.

## Documentation Structure

| Section |
|---------|
| Basic Utilities |
| Better Printers |
| Cross Quotes |
| Micro FP |
| Standard Extensions |
| Debug Utilities |
| Source Utilities |
| Type Name Utilities |
| Best Practices |
| Derivation Checklist |
| Prior Art & Influences |
| Resources & Further Reading |
| FAQ |

(Pages under https://scala-hearth.readthedocs.io/en/latest/)

## Notes

Hearth's lineage traces to the cross-version macro infrastructure pioneered by **chimney** (data transformations) and **jsoniter-scala** — generalised into a reusable standard library. It targets library authors who write derivation macros (codecs, transformers, typeclass instances) and want a single codebase covering Scala 2.13 and Scala 3 rather than maintaining two parallel `scala-2`/`scala-3` source trees.

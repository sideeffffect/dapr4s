# Scala 3 Metaprogramming — Reference Overview

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/index.html
> Collected: 2026-06-07
> Published: Unknown

The metaprogramming redesign introduces six fundamental facilities:

1. **`inline`** — A modifier guaranteeing definition inlining at point of use, performed during the Typer compiler phase. "The reason is that inlining in Scala can drive other compile-time operations, like inline pattern matching (enabling type-level programming), macros (enabling compile-time, generative, metaprogramming) and runtime code generation (multi-stage programming)."

2. **Compile-time ops** — Standard library helper definitions supporting compile-time operations over values and types.

3. **Macros** — Built on quotation (converting code to data via `'{...}` for expressions and `'[...]` for types) and splicing (converting representation back to code via `${...}`). "Together with `inline`, these two abstractions allow to construct program code programmatically."

4. **Runtime Staging** — Enabling runtime code construction where "code generation can depend not only on static data but also on data available at runtime." Uses quotes and splices but omits `inline`.

5. **Reflection** — TASTy reflection reveals code structure through a "typed abstract syntax tree" API, analyzing quotations beyond their black-box representation.

6. **TASTy Inspection** — Loading and analyzing typed abstract syntax trees serialized in compressed binary format within `.tasty` files.

## Linked Sub-Pages

| Title | URL |
|-------|-----|
| Inline | ../inline.html |
| Compile-time operations | ../compiletime-ops.html |
| Macros | ../macros.html |
| The Meta-theory of Symmetric Metaprogramming | ../simple-smp.html |
| Run-Time Multi-Stage Programming | ../staging.html |
| Reflection | ../reflection.html |
| TASTy Inspection | ../tasty-inspect.html |

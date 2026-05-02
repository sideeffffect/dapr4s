# Scala's Gamble with Direct Style

> Source: https://alexn.org/blog/2025/08/29/scala-gamble-with-direct-style/
> Collected: 2026-05-01
> Published: 2025-08-29

Author: Alexandru Nedelcu (alexn.org).

## Main Argument

Alexandru Nedelcu argues that Scala 3's shift toward "direct style" programming represents a strategic misstep that alienates the community while failing to adequately support either approach.

## The Core Problem

Scala has historically excelled at functional programming with monadic IO libraries (Cats-Effect, ZIO, Kyo). However, Scala 3 abandons this path without properly supporting alternatives. The language "does not move in the direction of more monadic IO, but rather in the direction of direct style."

## Three Attempted Solutions — Each Flawed

**dotty-cps-async**: Theoretically sound but plagued by edge cases. Interruption models clash with JVM constructs, and lack of native language support dooms it to perpetual bug-fighting.

**gears**: Relies on virtual threads or continuations — "incredibly limiting." No JavaScript support; blocking threads remain resource-intensive on non-virtual-thread platforms; execution cannot be fine-tuned.

**ox**: JVM-only library for blocking threads. While acceptable with Java 21+ virtual threads, it abandons cross-platform ambitions entirely.

## The Kotlin Contrast

Kotlin Coroutines demonstrate superior multi-platform support across JVM, Android, iOS, JavaScript, and WasmGC. Its structured concurrency design, context parameters, and growing ecosystem outpace Scala's current trajectory.

## The Verdict

Scala faces a critical inflection point. The language excels at safety through features like capture checking, yet ignores that most daily problems involve I/O. Without a "consistent story that targets the mainstream," its future appears uncertain — especially as Java 25 and other alternatives strengthen their positions.

The article ultimately reflects ambivalence: genuine affection for Scala tempered by realistic assessment of its strategic positioning challenges.

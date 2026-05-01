# Effects as Capabilities

> Source: https://nrinaudo.github.io/articles/capabilities.html
> Collected: 2026-05-01
> Published: Unknown

**Author:** Nicolas Rinaudo

## Overview

This article explores how Scala's capabilities feature enables direct-style programming while maintaining the ability to track effects through types. It demonstrates capabilities as a fancy form of dependency injection using context functions.

## Main Problem Solved

The article addresses how to achieve "direct style" programming — where control passes implicitly between lines of code — without sacrificing the reasoning benefits of monadic style, which typically requires explicit ceremony around effectful operations.

> "A direct style of programming is one in which you can write code without faffing about with monads."

## Capabilities as Dependency Injection

Capabilities function as fancy dependency injection using context functions (functions with implicit parameters, denoted `A ?=> B`).

## Practical Example: Number Guessing Game

The article demonstrates capabilities through a guessing game that requires three core effects:

1. **Rand** — Generate random numbers
2. **Print** — Output text
3. **Read** — Accept user input

## Key Technical Insights

**Eager Application:** Context functions are automatically applied immediately, so `nextInt(10)` returns an `Int` rather than remaining unapplied.

**Automatic Conversion:** When the compiler expects `A ?=> B` but finds `B`, it automatically wraps it: `(a: A) ?=> B`.

**Order Independence:** Capabilities can be reordered without breaking code due to automatic conversion mechanics.

## Practical Benefits

- Code maintains readability of imperative style
- Compiler enforces transitive capability requirements
- Handlers enable easy testing and dependency swapping
- Aggregate capabilities can compose simpler ones

## Limitations Noted

Simple context functions cannot impact program flow — error handling, structured concurrency, and other control-flow effects require more sophisticated approaches (covered in the follow-up "Controlling Program Flow with Capabilities").

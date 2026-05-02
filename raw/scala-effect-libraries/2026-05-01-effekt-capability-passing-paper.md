# Effekt: Capability-Passing Style for Type- and Effect-Safe Extensible Effect Handlers in Scala

> Source: https://www.cambridge.org/core/services/aop-cambridge-core/content/view/A19680B18FB74AD95F8D83BC4B097D4F/S0956796820000027a.pdf/effekt_capabilitypassing_style_for_type_and_effectsafe_extensible_effect_handlers_in_scala.pdf
> Collected: 2026-05-01
> Published: 2020

Authors: Jonathan Brachthäuser, Philipp Schuster, Klaus Ostermann. Published in Journal of Functional Programming (Cambridge University Press).

## Overview

This paper presents **Effekt**, a Scala library implementing effect handlers through "capability-passing style" — a novel approach to managing side effects safely and extensibly. The work addresses limitations in existing effect systems by combining type safety with practical extensibility.

## Key Contributions

1. **Capability-Passing Style**: A new programming model where effects are passed as capabilities through function parameters rather than handled globally, enabling lexically-scoped effect management.

2. **Type-Safe Effect System**: Integrates effect types into Scala's type system, allowing programmers to declare which effects a function may perform at compile time.

3. **Extensible Handler Architecture**: The framework supports defining new effect handlers without modifying existing code, addressing the expression problem in effect systems.

4. **Practical Implementation**: Demonstrates that capability-passing achieves both type safety and extensibility without runtime overhead.

## Core Design

The approach centers on treating effects as first-class capabilities passed explicitly through function signatures. This differs from:
- **Algebraic effect handlers** (used in languages like Koka) — which use continuations and global handler stacks
- **Monad transformer stacks** (common in Haskell) — which require significant boilerplate

Capability-passing offers a middle ground between expressiveness and practical usability.

**Effect declarations** specify which computational effects a function permits. Handlers then intercept these effects at defined scopes, allowing controlled effect execution and composition.

## Technical Highlights

- Effect types enable static verification that functions only perform declared side effects
- The capability-passing mechanism maintains safety while avoiding the boilerplate of monad transformers
- Handler composition supports complex effect combinations
- Integration with Scala's type system ensures compile-time guarantees
- Effect types are represented as intersection types in function signatures

## Significance

This work demonstrates that carefully designed effect systems can provide strong safety guarantees in mainstream languages without sacrificing practical usability or requiring complete program restructuring. The paper shows that capability-passing is semantically equivalent to algebraic effect handlers while being implementable as a Scala library without compiler modifications.

## Relationship to Standalone Effekt Language

The Scala library proved the concept, and development was subsequently superseded by the standalone Effekt programming language (https://effekt-lang.org/), which implements these ideas more completely with its own compiler and type system.

# Effekt Language Overview

> Source: https://effekt-lang.org/
> Collected: 2026-05-01
> Published: Unknown

## Core Identity

Effekt is a research-level programming language featuring "lexical effect handlers and lightweight effect polymorphism." The project emphasizes experimental status, acknowledging potential bugs and future changes while inviting community testing.

## Key Distinguishing Features

**Effect Handlers**: The language implements algebraic effects as advanced control-flow structures. These allow "advanced control-flow structures like generators as user libraries" that compose seamlessly. Notably, handlers can resume execution at the original call site, extending exception-handling capabilities.

**Effect Safety**: Effekt employs a static type and effect system ensuring all effects are handled. The system represents required effects in function signatures using set notation (e.g., `Int / { raise }`), making unhandled effects compile-time errors rather than runtime surprises.

**Contextual Effect Polymorphism**: Rather than requiring explicit effect annotations for higher-order functions, Effekt uses implicit polymorphism based on scope. Functions like `map` work with blocks having different effect requirements without annotation burdens typical of other effect systems.

## Design Philosophy

The language prioritizes developer experience by avoiding unnecessary annotation overhead while maintaining safety guarantees. The documentation notes that "no need to understand effect polymorphic functions or annotate them. Just use what is in scope."

## Learning Resources

The project provides online playgrounds, installation guides, academic papers exploring theoretical foundations, and video demonstrations through YouTube playlists explaining various system aspects.

## Relationship to Scala Effekt

Effekt (the language) grew out of the Scala Effekt library work. In 2020, the standalone language superseded the Scala library. Both carry "Effekt" in their name but are distinct projects:
- **Scala Effekt**: Original Scala library (discontinued, last version 0.4-SNAPSHOT)
- **Effekt**: Standalone programming language with its own compiler

The evolution is explained at https://effekt-lang.org/evolution.

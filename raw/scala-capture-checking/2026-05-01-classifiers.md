# Capability Classifiers

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/classifiers.md
> Collected: 2026-05-01
> Published: Unknown

## Introduction

Capabilities can express concepts from many different domains: exceptions, continuations, I/O, mutation, information flow, security permissions. Sometimes it is important to restrict what kind of capabilities are expected or returned in a context. This is achieved by having a capability class extend a _classifier_.

## Classifier Trait

A class or trait becomes a classifier by extending directly the marker trait `caps.Classifier`. The `scala.caps` package defines the `Control` classifier:

```scala
trait Control extends SharedCapability, Classifier
```

Unlike normal inheritance, classifiers also restrict the capture set of a capability. A parameter of type `Async^` where `Async extends Control` can only capture capabilities whose types extend `Control` — no mutation or I/O capabilities.

Classifiers are unique: a class cannot extend two unrelated classifier traits simultaneously.

## Predefined Classifiers

```
              Capability
              /        \
 SharedCapability     ExclusiveCapability
 ----------------            |
        |                 Unscoped
     Control              --------
     -------
```

- `SharedCapability` — shared capabilities, is a classifier
- `ExclusiveCapability` — base for capabilities with anti-aliasing restrictions (separation checking); not a classifier
- `Control` — extends `SharedCapability`; for exceptions, boundary labels, async suspension
- `Unscoped` — extends `ExclusiveCapability`; capabilities that can escape their defining environment (e.g., `Ref` cells)

Since `Capability` is sealed, all capability classes are either shared or exclusive. Exclusive capabilities can have shared capabilities in their capture set but not vice versa.

## Restricted Capabilities and `.only[C]`

The form `c.only[A]` is a _restricted capability_ where:
- `c` is a regular, unrestricted capability
- `A` is a classifier trait

When substituting a capability set for the underlying capability, capabilities unrelated to the classifier are dropped.

Example — a `Try.apply` that retains only `Control` capabilities from its body:

```scala
object Try:
  def apply[T](body: => T): Try[T]^{body.only[Control]} = ???
```

If `body` uses `{io, async}` where `IO` extends `SharedCapability` but not `Control`, and `async` is a `Control` capability, then `Try { expr }` has type `Try^{async}`. The `io` capability is dropped.

An effect-polymorphic value like `proc: () => Unit` would be kept in the restricted set since we cannot exclude that it retains `Control` capabilities.

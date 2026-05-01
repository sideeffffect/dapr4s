# Stateful Capabilities

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/mutability.md
> Collected: 2026-05-01
> Published: Unknown

## Core Concepts

The system distinguishes two access patterns:
- Full access: `x` — allows mutations
- Read-only access: `x.rd` — allows observation only

**The `Stateful` Trait** — Classes extending `caps.Stateful` indicate they can consult and modify global program state. `Stateful` by itself doesn't classify a type as a capability; that requires combining with capability classifiers like `ExclusiveCapability`.

## Update Methods

The `update` soft modifier marks methods that perform state changes. In a `Stateful` class, ordinary methods are checked as read-only with respect to the receiver: they may observe state but cannot mutate it or call `update` methods.

```scala
abstract class Buffer[T] extends Mutable {
  update def append(elem: T): Unit
  def apply(pos: Int): T   // read-only
  def size: Int             // read-only
}
```

A reference of type `Buffer` allows only regular methods. `Buffer^` also allows update methods.

## Important Traits

**`Unscoped` Classifier** — Capabilities classified as `Unscoped` can escape their defining environment. Useful for stateful values like `Ref` cells that don't capture external resources like files.

**`Mutable` Trait** — Combines `Stateful` and `Unscoped`, the common pattern for stateful, unscoped classes:

```scala
trait Mutable extends Stateful, Unscoped
```

## Read-Only Access Rules

Read-only access occurs when:
- Accessing `this` from non-`update` methods
- Following a read-only path
- Converting to non-stateful types
- Selecting regular (non-`update`) methods

References with exclusive capture sets can widen to read-only sets, enabling flexible capability usage while maintaining safety guarantees.

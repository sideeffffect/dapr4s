# scala.caps.Capability

> Sources: Scala 3 Language Team / scala-lang.org, Unknown; Adam Warski / SoftwareMill, Unknown
> Raw: [scala.caps.Capability API Docs](../../raw/scala-effect-libraries/2026-05-01-scala-caps-capability.md); [Understanding Capture Checking in Scala](../../raw/scala-effect-libraries/2026-05-01-softwaremill-capture-checking.md)
> Updated: 2026-05-01

## Overview

`scala.caps.Capability` is a marker trait in Scala 3's `scala.caps` package that anchors the experimental capture-checking (`-Ycc`) type system. Any class extending `Capability` becomes a **tracked capability**: the compiler monitors where instances of that class are referenced, stored, or returned, and enforces that they do not escape their intended lexical scope. This is the primary mechanism by which Scala 3 enforces effect safety without monadic wrappers.

## What It Means to Extend `Capability`

When a type `C` extends `scala.caps.Capability`:

1. **Instances are tracked**: the compiler records which values "capture" (hold a reference to) a `C` instance.
2. **The `^` notation activates**: values of type `C` or types that capture `C` must use the capture annotation `^` in their types (e.g., `C^` or `Parser^{c}` where `c: C`).
3. **Scope enforcement**: you cannot return or store a value with a `C^` capture outside a scope that provides `c`, because that would allow the capability to outlive its provider.

Simple example:

```scala
import scala.caps.Capability

trait Database extends Capability:
  def query(sql: String): List[Row]

def withDatabase[T](url: String)(body: Database^ => T): T =
  val db: Database = openConnection(url)
  try body(db)
  finally db.close()

// This is a compile error — the Database capability escapes withDatabase:
val leaked: Database^ = withDatabase("jdbc:...")(identity)
```

## The `scala.caps` Package

The full set of types in `scala.caps` forms a capability hierarchy:

| Type | Role |
|------|------|
| `Capability` | Root marker — any user-defined capability extends this |
| `SharedCapability` | A capability that can be shared across threads safely |
| `ExclusiveCapability` | A capability that must not be aliased (like Rust's `&mut`) |
| `Stateful` | Base trait for capabilities that can read or modify state |
| `Mutable` | Extends `Stateful` and `Unscoped`; the standard base for mutable data structures subject to separation checking |
| `Pure` | Marks that a value/function has no capabilities |
| `Unscoped` | Opts out of scope checking (escape hatch) |
| `Read` | A read-only capability |

Alongside these, the package provides:
- `Contains` — evidence that a capture set includes a specific capability
- `Separate` — evidence that two capture sets are disjoint
- `Classifier` — for capability-brand patterns (classifying sets of capabilities)

## The `^` Notation

Under `-Ycc`, the caret `^` marks a type as a **capturing type**:

| Notation | Meaning |
|----------|---------|
| `T^` | `T` captures something (unknown capture set) |
| `T^{c}` | `T` captures exactly the capability `c` |
| `T^{c, d}` | `T` captures both `c` and `d` |

Function types follow the same pattern:
- `A => B` — a function that may capture anything (it is itself a `Capability`)
- `A -> B` — a **pure** function that captures nothing

Subtyping: `T` (no captures) <: `T^{c}` (specific capture) <: `T^` (unknown captures). This mirrors the "fewer instances qualify" intuition.

## Capture Sets and Subcapturing

A value's **capture set** is the set of tracked capabilities it holds. The subcapturing relation `{c} <: {d}` holds when having `c` implies having `d`. This is used for:

- **Resource management**: ensuring a `FileInputStream^` cannot outlive the `withFile` scope that created it.
- **Concurrency safety**: ensuring a `Fork^{scope}` cannot escape its `supervised` block.
- **Effect tracking**: ensuring an effect capability (e.g., `CanThrow[E]^`) is only used where the corresponding handler is in scope.

## Using `Capability` for Effect Modeling

`scala.caps.Capability` provides the foundation for encoding effects as capabilities in user code:

```scala
import scala.caps.Capability

// Model DAPR state store as a capability
trait StateStore extends Capability:
  def get(key: String): Option[String]
  def set(key: String, value: String): Unit

// A function that requires a StateStore capability
def readCounter()(using store: StateStore^): Int =
  store.get("counter").flatMap(_.toIntOption).getOrElse(0)
```

The key semantic difference from a plain dependency injection approach: the compiler prevents `store` from escaping the scope where it was provided.

## Interaction with Ox

Ox uses scoped values and virtual threads on the JVM, and is positioned to integrate with capture checking. SoftwareMill's article shows that capture checking can statically verify Ox's structured concurrency properties:
- A `Fork` spawned inside `supervised` cannot be stored outside it.
- Channels created in a scope cannot be returned to callers.

As Scala 3's CC feature stabilizes, Ox plans to use `^` annotations to make these properties compiler-checked rather than convention-enforced.

## Interaction with Existing CC Articles

More detailed coverage of the full capture checking system lives in the `scala-capture-checking` topic of this wiki:

- **[Capture Checking Overview](../scala-capture-checking/capture-checking-overview.md)** — enabling imports, escape checking, the `^` syntax
- **[Capturing Types](../scala-capture-checking/capturing-types.md)** — full type syntax, subcapturing, function types, polymorphism
- **[Capabilities and Resources](../scala-capture-checking/capabilities-and-resources.md)** — the full hierarchy including `SharedCapability`, `ExclusiveCapability`, `Mutable`
- **[Safe Mode](../scala-capture-checking/safe-mode.md)** — the restricted subset for safe agent code

## See Also

- [Ox Structured Concurrency](ox-structured-concurrency.md)
- [Effekt Capability Passing](effekt-capability-passing.md)
- [Scala Effect Libraries Comparison](scala-effect-libraries-comparison.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
- [Capabilities for Safe Agents](../capabilities-research/capabilities-for-safe-agents.md)

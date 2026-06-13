# Java Interop and Safe Scala

> Sources: Adam Warski / VirtusLab, 2026-04-14; Krzysztof Ciesielski / SoftwareMill, 2024-04-29
> Raw: [Safe Scala: An Introduction](../../raw/scala3-language/2026-05-01-safe-scala-introduction-virtuslab.md); [Callbacks with Structured Concurrency — Ox](../../raw/scala3-language/2026-05-01-callbacks-structured-concurrency-scala-ox.md)

## Overview

Safe Scala is an experimental Scala 3 language feature (`import language.experimental.safe`) that restricts the language and standard library to a provably-safe subset, providing compile-time guarantees about the absence of dangerous side effects. Its central mechanism for Java interop is `@assumeSafe`: an annotation that declares external Java (or pre-compiled Scala) code as trusted, allowing it to be called from safe-mode programs without triggering safe-mode violations. The annotation cannot be used from within safe-mode code itself, which prevents circumvention.

## The Safety Guarantee

Once a Safe Scala program passes compilation, it is guaranteed not to perform certain dangerous operations:

- Runtime reflection (`asInstanceOf`, `isInstanceOf` in unsafe ways)
- Arbitrary type casts
- Console I/O
- File, network, and system process operations
- Any operation that the safe-mode restrictions identify as unsafe

Normal pure computations compile and run unchanged:

```scala
// OK in safe mode
val xs = List(1, 2, 3).map(_ * 2)
val doubled = xs.filter(_ > 2)
```

Unsafe operations become compile errors:

```scala
// ERROR: file operations not allowed in safe mode
new java.io.File("test.txt").delete()
```

## Enabling Safe Mode

```scala
import language.experimental.safe
```

Or via Scala CLI directive:

```scala
//> using options -language:experimental.safe
```

Available in Scala 3 nightly builds. The import restricts the compilation unit to the safe subset.

## `@assumeSafe`: The Trust Boundary

Java libraries and pre-compiled Scala code cannot be analyzed by the safe-mode checker. `@assumeSafe` marks a method, object, or class as safe to call from safe-mode code — the developer takes responsibility for the safety guarantee.

```scala
// Trusted library code — compiled WITHOUT safe mode
import scala.annotation.experimental

@assumeSafe
object DaprOps:
  def invokeService(appId: String, method: String, data: Array[Byte]): Array[Byte] =
    // Calls Dapr Java SDK — side-effectful, but controlled
    daprClient.invokeMethod(appId, method, data, HttpExtension.POST, Array[Byte].classOf)
```

Agent-generated code running in safe mode can call `DaprOps.invokeService` because of the annotation, but cannot directly instantiate `java.io.File` or call `System.exit`.

### Critical Security Property

`@assumeSafe` **cannot be written inside safe-mode code**. An agent generating safe Scala cannot grant itself new capabilities by adding `@assumeSafe` to its own code. The annotation is only meaningful in non-safe-mode library code, which must be compiled separately and provided as a trusted dependency.

## `@rejectSafe`: Inverse Annotation

`@rejectSafe` marks code that must not be called from safe-mode programs, even if it would otherwise pass the checker:

```scala
@rejectSafe
def unsafeDebugDump(obj: Any): Unit = ...
```

This is useful for marking escape hatches and low-level internals that are technically pure but semantically dangerous.

## Wrapping Java SDK Types Behind Capability Boundaries

The recommended pattern for wrapping a Java SDK (e.g., Dapr's `DaprClient`) has four layers:

### 1. Opaque Type Wrapper

```scala
// Not in safe mode — this is library code
object DaprTypes:
  opaque type StateKey = String
  opaque type AppId    = String

  object StateKey:
    def apply(s: String): StateKey = s
  object AppId:
    def apply(s: String): AppId = s
```

### 2. Capability Trait

```scala
trait DaprCapability  // presence in scope = permission to call Dapr
```

### 3. `@assumeSafe` Wrapper Methods

```scala
@assumeSafe
object DaprAPI:
  def getState(key: StateKey)(using DaprCapability): String =
    // delegates to DaprClient Java SDK call
    client.getState(classOf[String], key, null).block().getValue

  @assumeSafe
  def saveState(key: StateKey, value: String)(using DaprCapability): Unit =
    client.saveState(storeName, key, value).block()
```

### 4. Capability Provider

```scala
// Not safe-mode — creates the capability scope
def withDapr[T](client: DaprClient)(body: DaprCapability ?=> T): T =
  given DaprCapability = new DaprCapability {}
  body
```

Safe-mode agent code can then do:

```scala
// Runs in safe mode
withDapr(client) {
  val v = DaprAPI.getState(StateKey("my-key"))
  DaprAPI.saveState(StateKey("result"), v + "-processed")
}
```

The agent code cannot call `DaprClient` directly (Java type, safe mode blocks it). It can only use the `@assumeSafe`-annotated wrappers that the library author has vetted.

## Interaction with Capture Checking (Level 2)

Safe mode (Level 1) is a blunt instrument — it blocks entire categories of operations. Capture checking (Level 2, `-language:experimental.captureChecking`) is more fine-grained: it tracks which capabilities flow where at the type level, preventing capabilities from being stored in closures and transmitted to untrusted code.

The two can be combined:

```scala
import language.experimental.safe
import language.experimental.captureChecking

// Capability that tracks Dapr access
class DaprCap

// `using DaprCap` means: this call captures the capability
@assumeSafe
def callDapr(appId: String)(using cap: DaprCap): String^{cap} = ...
```

The return type `String^{cap}` means the result is "tainted" with the `DaprCap` capability. If agent code tries to pass this string to an untrusted channel, the compiler rejects it. See [Safe Mode](../scala-capture-checking/safe-mode.md) and [Capabilities for Safe Agents](../capabilities-research/capabilities-for-safe-agents.md) for the full picture.

## Structured Concurrency and Callbacks

Java SDK callbacks (e.g., Netty handlers, Dapr subscriptions) fire outside any structured concurrency scope. The `OxDispatcher` pattern bridges this:

```scala
@assumeSafe
class DaprSubscriber(dispatcher: OxDispatcher)(using DaprCapability):
  def onMessage(msg: CloudEvent)(using Ox): Unit =
    dispatcher.runAsync { processEvent(msg) }
```

The `Ox ?=> Unit` signature of `runAsync` ensures the processing body has a managed structured concurrency scope even though the callback itself does not. This is safe because the dispatcher holds a supervised scope that outlives any individual callback.

## Summary: Trusted vs. Untrusted Code

| Code | Mode | Can use `@assumeSafe` | Can call unsafe Java |
|---|---|---|---|
| Library author code | Non-safe-mode | Yes | Yes (responsibility: annotate) |
| Agent-generated code | Safe mode | No | No |
| Wrapper methods | Non-safe-mode, `@assumeSafe` | N/A | Yes (declared trusted) |

## See Also

- [Opaque Types](opaque-types.md)
- [Context Functions and Capability Passing](context-functions-capability-passing.md)
- [Safe Mode](../scala-capture-checking/safe-mode.md)
- [Capabilities for Safe Agents](../capabilities-research/capabilities-for-safe-agents.md)
- [Cross-building with Scala CLI](../scala-js/scala-js-cross-building-scala-cli.md)

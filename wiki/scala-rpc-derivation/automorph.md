# Automorph

> Sources: automorph-org/automorph (main, commit eeb3adb), 2026-06-07
> Raw: [Automorph macro source](../../raw/scala-rpc-derivation/2026-06-07-automorph-scala3-macro-source.md)

## Overview

Automorph is a feature-rich JSON-RPC / REST-RPC library. `client.bind[Api]` derives a type-safe local proxy from an API trait at compile time. It is the one Tier 1 library that **does not synthesize a class** — instead the macro produces per-method binding metadata and the callable instance is a JDK dynamic `java.lang.reflect.Proxy`. This is the canonical example of mechanism bucket 2 in the [overview](trait-to-impl-derivation-overview.md). (A parallel Scala 2 macro path exists; only Scala 3 is in scope.)

## Entry point

```scala
inline def bind[Api <: AnyRef]: Api = bind[Api](identity)
inline def bind[Api <: AnyRef](mapName: String => String): Api = /* generate bindings + Proxy */
```

## Mechanism

1. **Compile time:** `ClientBindingGenerator.generate[...]` is an inline macro splicing into `generateMacro`. It uses `ApiReflection.apiMethods[Api, Effect]` to enumerate the trait's public methods (subtracting `AnyRef`/`Product` members) and **validates** them — rejecting type-parameterized, non-runtime-callable, wrong-effect-return, and overloaded methods via `report.errorAndAbort`. So binding errors are compile errors. Each valid method becomes a `ClientBinding` (an `RpcFunction` descriptor + per-parameter `Any => Node` argument encoders calling `codec.encode` + a `(Node, Context) => Any` result decoder calling `codec.decode` + an `acceptsContext` flag), combined with `Expr.ofSeq`.
2. **Runtime:** `bind` keys the bindings by method name and constructs `Proxy.newProxyInstance(classLoader, Array(runtimeClass), handler).asInstanceOf[Api]`. This works because compiled Scala traits are JVM interfaces.
3. **Dispatch:** the proxy's `InvocationHandler` looks up the binding by `method.getName`, splits off a trailing `Context` argument if `acceptsContext`, encodes each remaining argument to a `Node`, and calls the abstract `performCall(name, argumentNodes, decodeResult, requestContext)`. `RpcClient.performCall` builds the RPC request via the protocol, sends it over the transport, and decodes the response into `Effect[Result]`.

## Trade-offs vs. class synthesis

- **Pro:** simpler macro (no `Symbol.newClass`); not gated on experimental class-synthesis APIs.
- **Con:** the actual `invoke` is name-based reflective dispatch (type-safety comes from the compile-time binding generation/validation, not from the dispatch). Cannot bind a *class* (only a trait/interface) — the docs disallow it.

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md) (Automorph as the JDK-Proxy deviation)
- [Sloth](sloth.md) (contrast: class synthesis)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)

# Dapr Java SDK — Virtual Threads Deep Dive

> Sources: SDK source code investigation (v1.17.2), grpc-java issues
> Raw: [dapr-java-sdk-virtual-threads](../../raw/dapr/2026-05-02-dapr-java-sdk-virtual-threads.md)
> Updated: 2026-05-02

## How the SDK communicates with the sidecar

All building blocks (state, pub/sub, secrets, config, bindings, locks) go through **gRPC** via `io.grpc:grpc-netty` 1.79.0. `DaprClientImpl` holds a `DaprGrpc.DaprStub` (async stub) and wraps every call in `Mono.create()` + `StreamObserver`. Netty's NIO event loops handle the actual I/O on dedicated platform threads — completely separate from whatever thread calls `.block()`.

HTTP is used only for `waitForSidecar()` and the deprecated `invokeMethod()` with `HttpExtension`.

## Virtual thread safety of current approaches

Both bridging strategies park the calling thread correctly without pinning a carrier:

**`Mono.block()`** — internally extends `CountDownLatch` → `LockSupport.park()`. VT unmounts from carrier while waiting. The subscriber's `onNext`/`onComplete`/`onError` methods are `synchronized`, meaning the *gRPC worker thread* that delivers the result can briefly pin its own carrier on JDK < 24. Under high concurrency this could reduce scalability of the Netty worker pool, but the calling VT is always handled correctly.

**`.toFuture().get()`** — `CompletableFuture.complete()` uses CAS with no `synchronized`. `.get()` also parks via `LockSupport.park()` with no `synchronized`. Neither the calling VT nor the Netty worker risks pinning. This is why `dapr4s` uses `MonoOps.awaitResult()` instead of `.block()`.

Reactor 3.5.x does **not** flag virtual threads as non-blocking, so `.block()` does not throw `IllegalStateException` from a VT.

## The official bypass: `DaprClient.newGrpcStub()`

The `DaprClient` interface exposes a public escape hatch to the underlying gRPC channel:

```java
<T extends AbstractStub<T>> T newGrpcStub(String appId, Function<Channel, T> stubBuilder);
```

This is fully intercepted — all six SDK interceptors are applied (API token auth, app-ID routing, timeouts, distributed tracing, baggage, metadata). Usage:

```java
// Blocking stub — call directly from a virtual thread, no Reactor anywhere
DaprGrpc.DaprBlockingStub stub =
    daprClient.newGrpcStub(null, DaprGrpc::newBlockingStub);
GetStateResponse resp = stub.getState(GetStateRequest.newBuilder()
    .setStoreName("statestore").setKey("key").build());
```

The blocking stub uses `ThreadlessExecutor` (grpc-java internal) which parks via `LockSupport.park()` — no `synchronized`, VT unmounts cleanly. This is the most VT-native path available: zero Reactor, zero `CompletableFuture` overhead.

For the ARM64/aarch64 audience: grpc-java issue #12648 documents a rare `ThreadlessExecutor` "thread parked forever" race. It affects all thread types (not VT-specific) and is unreproduced in most environments.

## Why bypassing DaprClientImpl is expensive

`DaprClientImpl` is the deserialization layer. Using the blocking stub directly means:
1. Building proto request messages manually (`GetStateRequest`, `SaveStateRequest`, etc.)
2. Parsing proto response bytes manually
3. Handling the `ObjectSerializer` serialization contract yourself

This is significant additional code for every building block operation. The high-level `DaprClient` API is the right abstraction for most users.

## Future stub variant (avoids ThreadlessExecutor entirely)

If the ARM64 race is a concern, use the future stub:

```java
DaprGrpc.DaprFutureStub futureStub =
    daprClient.newGrpcStub(null, DaprGrpc::newFutureStub);
// Returns ListenableFuture<T>; convert via Guava and call .get() from VT:
var resp = Futures.toCompletableFuture(futureStub.getState(req)).get();
```

This requires Guava. The `CompletableFuture.get()` parks the VT cleanly via `LockSupport.park()`.

## Reactor scheduler configuration — ineffective for Dapr SDK

Setting `Schedulers.setFactory()` or creating a `Schedulers.fromExecutorService(newVirtualThreadPerTaskExecutor())` has **no effect** on how Dapr SDK calls execute. `DaprClientImpl` uses no `subscribeOn()` or `publishOn()` — all pipelines execute on the subscribing thread. gRPC I/O always runs on Netty's event loops regardless of Reactor configuration.

## No blocking DaprClient variant exists

`AbstractDaprClient` and `DaprClientImpl` are package-private. There is no `DaprClientBlocking`. Issue #964 ("Remove Reactor, use CompletableFuture") has been open since November 2023 with community interest but no implementation milestone.

## Injection points — full survey

None of these preserve the high-level typed API without reflection:

| Hook | Accessible? | Notes |
|---|---|---|
| `DaprClientBuilder.withChannel(ManagedChannel)` | No — doesn't exist | Builder has no channel injection |
| `DaprClientBuilder.withExecutorService(...)` | No — doesn't exist | No executor hook at all |
| `AbstractDaprClient` subclassing | No — package-private constructor | Cannot extend from outside `io.dapr.client` |
| `DaprClientImpl` direct construction | Reflection only | Package-private ctors; `GrpcChannelFacade` also package-private |
| `NetworkUtils.buildGrpcManagedChannel(props, interceptors...)` | Yes (public) | Can add `ClientInterceptor` varargs; no way to set executor |
| `DaprHttpBuilder` | Public constructor | Can construct it, but `DaprHttp` itself is package-private; cannot inject custom `HttpClient` |
| `DaprClientProxy` / decorator | Doesn't exist | No proxy or wrapper pattern in the SDK |

**The only path that keeps the typed API and injects a custom executor is reflection:** reflectively construct `GrpcChannelFacade` from a `ManagedChannel` built with `.executor(Executors.newVirtualThreadPerTaskExecutor())`, then reflectively call the package-private `DaprClientImpl` constructor. The test suite (`DaprClientGrpcTest`, in the same package) does exactly this — so the shape of the constructor is stable — but it is entirely unsupported and will break on any SDK refactor.

### Effect of a custom channel executor

Setting the gRPC channel executor to a VT pool makes `StreamObserver` callbacks (`.onNext`, `.onComplete`, `.onError`) run on virtual threads. Since `DaprClientImpl` has no `subscribeOn()` or `publishOn()`, the `MonoSink.success(value)` call fires from the gRPC executor thread — so a VT executor here moves the Mono completion onto a VT. However, `.block()` still blocks whichever thread *calls* `.block()`. The channel executor change only affects the delivery side, not the waiting side.

### `.publishOn(vtScheduler)` — does not help

`.publishOn(scheduler)` moves downstream operators to the scheduler but does NOT move the `.block()` wait. The thread calling `.block()` is always the one that parks, regardless of `.publishOn()`. Correct VT use requires calling `.block()` (or `.awaitResult()`) from a VT-dispatched thread, not pipeline transformation.

## Recommendation for dapr4s

The current `MonoOps.awaitResult()` approach (`.toFuture().get()` from a VT) is the correct and practical choice:
- No additional dependencies
- Slightly better than `.block()` for VT (no `synchronized` in the completion path)
- Avoids the proto/serialization boilerplate of `newGrpcStub()`

Switching to `newGrpcStub()` with the blocking stub would eliminate Reactor entirely but requires rewriting all seven capability implementations to work with raw proto types — a significant effort for marginal VT benefit (the current approach already achieves correct VT unmounting).

If the SDK ships a `DaprClientBlocking` or the community merges issue #964, migration would be worthwhile.

## See Also

- [Dapr Java SDK](dapr-java-sdk.md)
- [Dapr Overview](dapr-overview.md)

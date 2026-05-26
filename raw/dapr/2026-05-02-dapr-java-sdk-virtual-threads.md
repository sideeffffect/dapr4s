---
source: https://github.com/dapr/java-sdk (source code investigation, SDK v1.17.2)
collected: 2026-05-02
published: Unknown
---

# Dapr Java SDK internals for Virtual Threads (JDK 21+)

## Transports used by the SDK

Two transports:

**gRPC (primary — all building blocks):**
- `NetworkUtils.buildGrpcManagedChannel()` builds a `ManagedChannel` using `io.grpc:grpc-netty` 1.79.0 with `NettyChannelBuilder`.
- `DaprClientImpl` holds a `DaprGrpc.DaprStub` (async stub) and wraps every call in `Mono.create()` + `StreamObserver`.
- No custom executor is set on the channel — uses gRPC's internal cached thread pool.
- No `subscribeOn()` or `publishOn()` anywhere in the SDK — all Mono pipelines are cold publishers on the subscribing thread.

**HTTP (narrow use):**
- `DaprHttp` (package-private) uses JDK 11+ `java.net.http.HttpClient`.
- Backed by `Executors.newFixedThreadPool(maxRequests)` — hardcoded, not injectable.
- Only used for: `waitForSidecar()` healthcheck and the deprecated `invokeMethod()` with `HttpExtension`.

## newGrpcStub() — the official bypass

`DaprClient` interface exposes:

```java
<T extends AbstractStub<T>> T newGrpcStub(String appId, Function<Channel, T> stubBuilder);
```

Implementation in `DaprClientImpl` (line 312–314):

```java
public <T extends AbstractStub<T>> T newGrpcStub(String appId, Function<Channel, T> stubBuilder) {
    return this.grpcInterceptors.intercept(appId, stubBuilder.apply(this.channel.getGrpcChannel()));
}
```

All six SDK interceptors are applied: `DaprAppIdInterceptor`, `DaprApiTokenInterceptor`,
`DaprTimeoutInterceptor`, `DaprTracingInterceptor`, `DaprBaggageInterceptor`,
`DaprMetadataReceiverInterceptor`. This is fully production-ready.

Usage with a **blocking stub** (VT-safe):

```java
DaprGrpc.DaprBlockingStub blockingStub =
    daprClient.newGrpcStub(null, DaprGrpc::newBlockingStub);
// Call directly from a virtual thread — no Reactor
blockingStub.getState(GetStateRequest.newBuilder()...build());
```

Usage with a **future stub** (returns `ListenableFuture<T>`):

```java
DaprGrpc.DaprFutureStub futureStub =
    daprClient.newGrpcStub(null, DaprGrpc::newFutureStub);
// Convert to CompletableFuture if needed:
CompletableFuture<Resp> cf = Futures.toCompletableFuture(futureStub.getState(req));
```

(Requires Guava for `Futures.toCompletableFuture()`.)

## Blocking stub and virtual threads: ThreadlessExecutor

The gRPC blocking stub uses an internal `ThreadlessExecutor` (in `ClientCalls.java`, line 794–858) that parks the calling thread via `LockSupport.park()` while waiting for the response. `LockSupport.park()` correctly unmounts a virtual thread from its carrier — the carrier is freed to run other work.

**No `synchronized` blocks in `ThreadlessExecutor`** — no carrier pinning.

Known caveat: a rare "thread parked forever" race condition exists in `ThreadlessExecutor` on ARM64/aarch64 (grpc-java issue #12648). It affects platform threads too and is unrelated to virtual threads specifically.

## Mono.block() and .toFuture().get() on virtual threads

Both approaches park the calling VT correctly via `LockSupport.park()`:

- **`Mono.block()`**: `BlockingSingleSubscriber` extends `CountDownLatch`. `CountDownLatch.await()` uses AQS / `LockSupport.park()`. VT unmounts from carrier. Reactor 3.5.x does NOT flag virtual threads as `NonBlocking`, so `.block()` does not throw `IllegalStateException` from a VT. However, `BlockingSingleSubscriber` declares `onNext`/`onComplete`/`onError` as `synchronized` — on JDK < 24 this can briefly pin the carrier of the *emission* (gRPC Netty worker) thread when delivering the result.

- **`.toFuture().get()`**: `CompletableFuture.complete()` uses CAS, no `synchronized`. `CompletableFuture.get()` parks via `LockSupport.park()` with no `synchronized`. Neither waiting nor emitting thread risks pinning. Slightly cleaner than `.block()` for VT use.

## Reactor scheduler configuration — has no effect on SDK

`DaprClientImpl` uses no `subscribeOn()` or `publishOn()`. Setting `Schedulers.setFactory(...)` or wrapping executors in `Schedulers.fromExecutorService()` does not change how Dapr SDK operations run. gRPC I/O always runs on Netty's NIO event loops (platform threads).

## No blocking variant of DaprClient exists

- `AbstractDaprClient` and `DaprClientImpl` are package-private — cannot be subclassed or instantiated directly.
- No `DaprClientBlocking`, `DaprClientSync`, or similar exists.
- Issue #964 ("Remove Reactor, use CompletableFuture") has been open since Nov 2023 with community interest but no implementation milestone set.

## Cost of bypassing DaprClientImpl via blocking stub

`DaprClientImpl` does three things beyond just calling gRPC:
1. Converts high-level Java objects (`String key`, `MyType.class`) to proto request messages.
2. Converts proto responses back to typed Java domain objects.
3. Deserializes byte payloads via the configured `ObjectSerializer`.

Using `DaprGrpc.DaprBlockingStub` directly requires handling all three manually using proto-generated request/response builders and raw byte serialization. This is significantly more verbose than the high-level client API.

## GitHub issues on virtual threads / Project Loom

Zero relevant results for "virtual thread", "loom", "Project Loom" in the dapr/java-sdk repository. No VT-specific work planned or merged.

## Summary of options

| Approach | Reactor dependency at call site | VT safety | Extra effort |
|---|---|---|---|
| `Mono.block()` from VT | Yes | Good (park via CountDownLatch) | None |
| `.toFuture().get()` from VT | Minimal (convert to CF) | Best (CAS + park, no synchronized) | Minimal (current approach in dapr4s) |
| Blocking stub via `newGrpcStub()` | None | Best (LockSupport.park, no synchronized) | High (manual proto serialization) |
| Future stub via `newGrpcStub()` + Guava | None | Best | High + Guava dep |
| Custom JDK HttpClient | None | Best | Very high (reimplement full client) |

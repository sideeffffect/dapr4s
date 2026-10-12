# Dapr error codes and the dapr4s exception mapping

> Sources: live daprd 1.17.0 probe (HTTP + gRPC) and dapr/dapr v1.17.0, dapr/kit v0.18.1, java-sdk v1.17.2, @dapr/dapr 3.18.0 sources, 2026-10-12
> Raw: [daprd 1.17 error-codes probe](../../raw/dapr/2026-10-12-daprd-1.17-error-codes-probe.md)

## Overview

daprd reports errors in two families. **Legacy** `messages.APIError`s send `{"errorCode","message"}` over HTTP and
only a status code + message over gRPC (no details, so **no `ERR_*` code reaches a gRPC client**). **Kit** errors
(state, pub/sub, some configuration/binding paths) add a `google.rpc.ErrorInfo` with a `reason` on both protocols.
This matters because the Java SDK talks gRPC for everything except `invokeMethod`, while dapr4s on Scala.js talks HTTP
for most building blocks: the same failure arrives as `ERR_LOCK_STORE_NOT_FOUND` on JS and as a bare
`INVALID_ARGUMENT: lock store x not found` on the JVM. dapr4s therefore maps errors by **(code or ErrorInfo reason)
first, exact daprd message format second**, and takes the component name from the request, not from the message
(`src/shared/internal/DaprErrors.scala`).

## The mapping (dapr4s `DaprException` subtypes)

| dapr4s type | Matches | HTTP | gRPC |
|---|---|---|---|
| `ComponentNotFoundException(State, name)` | `ERR_STATE_STORE_NOT_FOUND`, `ERR_STATE_STORE_NOT_CONFIGURED`, reasons `DAPR_STATE_NOT_FOUND` / `DAPR_STATE_NOT_CONFIGURED`; msg `state store <n> is not found` | 400 / 500 | InvalidArgument / FailedPrecondition (+ reason) |
| `ComponentNotFoundException(PubSub, name)` | `ERR_PUBSUB_NOT_FOUND`, `ERR_PUBSUB_NOT_CONFIGURED`, reasons `DAPR_PUBSUB_NOT_FOUND`/`_NOT_CONFIGURED`; msg `pubsub <n> is not found` | **404** / 400 | InvalidArgument (+ reason) |
| `ComponentNotFoundException(Secrets, name)` | `ERR_SECRET_STORE_NOT_FOUND`, `ERR_SECRET_STORES_NOT_CONFIGURED`; msg `failed finding secret store with key <n>` | **401** / 500 | InvalidArgument (no details) |
| `ComponentNotFoundException(Configuration, name)` | `ERR_CONFIGURATION_STORE_NOT_FOUND`, `ERR_CONFIGURATION_STORE_NOT_CONFIGURED` (gRPC: as reason); msg `configuration store <n> not found` | 400 / 500 | InvalidArgument (+ reason = ERR code) |
| `ComponentNotFoundException(Lock, name)` | `ERR_LOCK_STORE_NOT_FOUND`, `ERR_LOCK_STORE_NOT_CONFIGURED`; msg `lock store <n> not found` | 400 / 500 | InvalidArgument (no details) |
| `ComponentNotFoundException(Crypto, name)` | `ERR_CRYPTO_PROVIDER_NOT_FOUND`, `ERR_CRYPTO_PROVIDERS_NOT_CONFIGURED`; msg `crypto provider <n> not found` | 400 / 500 | InvalidArgument / **Internal** |
| `ComponentNotFoundException(Conversation, name)` | `ERR_CONVERSATION_NOT_FOUND`; msg `failed finding conversation component <n>` | 400 | InvalidArgument (no details) |
| `ComponentNotFoundException(Binding, name)` | msg `couldn't find output binding <n>` inside `ERR_INVOKE_OUTPUT_BINDING` (no dedicated code) | 500 | Internal (+ reason `ERR_INVOKE_OUTPUT_BINDING`) |
| `PermissionDeniedException` | `ERR_PERMISSION_DENIED` (secret scopes), `ERR_PUBSUB_FORBIDDEN` / `DAPR_PUBSUB_FORBIDDEN` (topic scopes), gRPC `PERMISSION_DENIED`, HTTP 403, invoke ACL (`ERR_DIRECT_INVOKE` embedding `code = PermissionDenied`) | 403 | PermissionDenied |
| `AppUnreachableException(appId)` | `ERR_DIRECT_INVOKE` / msg `failed to invoke, id: …` in an invoke call (unknown app id → mDNS `couldn't find service: <id>`; app channel down) | 500 | Internal |
| `InvokeHttpException(appId, method, status, body)` | the target app answered a typed `invoke` with non-2xx (3xx included) — the app's own answer, passed through by daprd | app's status | — |
| `DaprApiException(errorCode, message)` | everything else; `errorCode` = ERR code > ErrorInfo reason > gRPC status name > `UNKNOWN`; `UNAVAILABLE` when the sidecar cannot be reached | any | any |

"Not configured" (no component of that kind exists at all) is also `ComponentNotFoundException`: from the caller's view
the named component does not exist. `ERR_WORKFLOW_COMPONENT_NOT_FOUND` exists in predefined.go but is never raised in
1.17, and dapr4s never names a workflow component, so there is no `Workflow` kind.

## Gotchas

- **Status codes are not a reliable signal**: secret-store-not-found is 401, pubsub-not-found 404, state-not-found
  400, lock/config-not-configured 500. Never map by HTTP status alone.
- **Missing secret key is store-specific**: `secretstores.local.env` answers 200 with `{"key":""}`; `local.file`
  answers 500 `ERR_SECRET_GET`. dapr4s: `None` for the first (empty value filtered), `DaprApiException` for the second.
- **`errorCode` of `DaprApiException` is transport-specific**: the JVM (gRPC) sees `INTERNAL`/a reason where Scala.js
  (HTTP) sees `ERR_SECRET_GET`. Only the typed subclasses are the cross-platform contract.
- **Invoke: daprd vs app errors look alike.** daprd passes the app's status/headers/body through and reports its own
  failures as `{"errorCode":"ERR_DIRECT_INVOKE",…}`. dapr4s treats a non-2xx invoke answer as daprd's only when its
  body parses with code `ERR_DIRECT_INVOKE` or a permission code; any other non-2xx is the app's answer
  (`InvokeHttpException` / returned by `invokeHttp`).
- **Java SDK `invokeMethod` is lossy**: it throws on every non-2xx (3xx too) and drops response headers, so dapr4s's
  `invokeHttp` uses the sidecar's raw `/v1.0/invoke` HTTP API on both platforms (`java.net.http` / `fetch`).
- **JS SDK soft failures**: `state.save`/`state.delete`/`pubsub.publish` resolve `{error}`; `publishBulk` fabricates
  per-entry failures sharing one error object for a whole-request failure. dapr4s rethrows these translated.
- **gRPC `ErrorInfo` on the JVM**: read it with `io.grpc.protobuf.StatusProto.fromThrowable` + `Any.unpack(ErrorInfo)`;
  the SDK's `DaprErrorDetails` map can hold protobuf `ByteString`/`MapField` values on the gRPC path.

## See also

- [dapr-wasm-components WIT vs dapr4s](dapr-wasm-components-vs-dapr4s.md) — D2/D3 came from this overlap analysis
- [Dapr Java SDK](dapr-java-sdk.md), [Dapr JS SDK](dapr-js-sdk.md) — transport per building block
- [Dapr Service Invocation](dapr-service-invocation.md)

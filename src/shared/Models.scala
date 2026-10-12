package dapr4s

import language.experimental.safe

/** Standard HTTP methods for service invocation requests.
  *
  * Cross-cutting: used by [[dapr4s.invoke.InvokeCapability]] / `InvokeRequest` and shareable by any HTTP-shaped call,
  * so it stays in the root `dapr4s` package rather than a capability family.
  */
enum HttpMethod:
  case Get, Post, Put, Patch, Delete, Head, Options

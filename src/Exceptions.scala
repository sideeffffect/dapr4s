package dapr.safe

import language.experimental.saferExceptions

// Base — all library exceptions extend Exception (not RuntimeException)
// so they work with saferExceptions / throws clauses.
@scala.caps.assumeSafe
class DaprException(message: String, cause: Exception | Null = null)
    extends Exception(message, cause)

// JSON decode errors
@scala.caps.assumeSafe
class JsonDecodeException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Connectivity
@scala.caps.assumeSafe
class DaprConnectionException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// State management
@scala.caps.assumeSafe
class DaprStateException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

@scala.caps.assumeSafe
class ETagMismatchException(key: StateKey, etag: ETag)
    extends DaprStateException(s"ETag mismatch for key '${key.value}' (provided: ${etag.value})")

@scala.caps.assumeSafe
class StateTransactionException(message: String, cause: Exception | Null = null)
    extends DaprStateException(message, cause)

// Pub/Sub
@scala.caps.assumeSafe
class DaprPubSubException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Service invocation
@scala.caps.assumeSafe
class DaprServiceInvocationException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Secrets
@scala.caps.assumeSafe
class DaprSecretsException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Configuration
@scala.caps.assumeSafe
class DaprConfigurationException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Bindings
@scala.caps.assumeSafe
class DaprBindingsException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Distributed Lock
@scala.caps.assumeSafe
class DaprLockException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

// Subscriber HTTP server
@scala.caps.assumeSafe
class DaprAppServerException(message: String, cause: Exception | Null = null)
    extends DaprException(message, cause)

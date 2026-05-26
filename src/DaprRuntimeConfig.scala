package dapr4s

import java.net.URI
import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}

/** Top-level configuration for [[DaprRuntime]].
  *
  * All configuration is explicit and typed — no environment variable reads or system property manipulation anywhere in
  * production code. Pass a `DaprRuntimeConfig` to [[DaprRuntime.run]] or [[DaprRuntime.serve]].
  *
  * @param sidecar
  *   Connection settings for the Dapr sidecar (HTTP/gRPC endpoints, timeouts, TLS, retries).
  * @param appServer
  *   HTTP server settings for the inbound Dapr app channel (pub/sub, invocations, bindings, actors).
  * @param actors
  *   Actor runtime settings reported to the sidecar via `GET /dapr/config`.
  */
case class DaprRuntimeConfig(
    sidecar: SidecarConfig = SidecarConfig(),
    appServer: AppServerConfig = AppServerConfig(),
    actors: ActorRuntimeConfig = ActorRuntimeConfig(),
)

/** Sidecar connection configuration, mapping to [[io.dapr.config.Properties]] constants.
  *
  * @param httpEndpoint
  *   URI of the Dapr sidecar HTTP API (default `http://localhost:3500`).
  * @param grpcEndpoint
  *   URI of the Dapr sidecar gRPC API (default `http://localhost:50001`).
  * @param apiToken
  *   Optional API token for authenticating requests to the sidecar (Dapr `DAPR_API_TOKEN`).
  * @param httpClientReadTimeout
  *   Read timeout for the underlying OkHttp client (default 60 seconds).
  * @param httpClientMaxRequests
  *   Maximum number of concurrent HTTP requests (default 1024).
  * @param httpClientMaxIdleConnections
  *   Maximum idle connections in the OkHttp connection pool (default 128).
  * @param grpcMaxInboundMessageSizeBytes
  *   Maximum inbound gRPC message size in bytes (default 4 MiB).
  * @param grpcMaxInboundMetadataSizeBytes
  *   Maximum inbound gRPC metadata size in bytes (default 8 KiB).
  * @param grpcEnableKeepAlive
  *   Whether to enable gRPC keep-alive pings (default false).
  * @param grpcKeepAliveTime
  *   Interval between keep-alive pings (default 10 seconds, only used when keep-alive is enabled).
  * @param grpcKeepAliveTimeout
  *   Timeout for keep-alive ping responses (default 5 seconds).
  * @param grpcKeepAliveWithoutCalls
  *   Whether to send keep-alive pings even when there are no active calls (default true).
  * @param grpcTlsInsecure
  *   Disable TLS on the gRPC channel (default true, i.e. plaintext by default).
  * @param grpcTlsCertPath
  *   Path to the client TLS certificate file (PEM). Required when TLS is enabled.
  * @param grpcTlsKeyPath
  *   Path to the client TLS private key file (PEM). Required when TLS is enabled.
  * @param grpcTlsCaPath
  *   Path to the CA certificate file (PEM) for server verification. Required when TLS is enabled.
  * @param maxRetries
  *   Number of times to retry failed SDK calls (default 0 = no retries).
  * @param timeout
  *   Global call timeout; `Duration.Zero` means no timeout (default no timeout).
  */
case class SidecarConfig(
    httpEndpoint: URI = URI.create("http://localhost:3500"),
    grpcEndpoint: URI = URI.create("http://localhost:50001"),
    apiToken: Option[ApiToken] = None,
    httpClientReadTimeout: FiniteDuration = 60.seconds,
    httpClientMaxRequests: Int = 1024,
    httpClientMaxIdleConnections: Int = 128,
    grpcMaxInboundMessageSizeBytes: Int = 4194304,
    grpcMaxInboundMetadataSizeBytes: Int = 8192,
    grpcEnableKeepAlive: Boolean = false,
    grpcKeepAliveTime: FiniteDuration = 10.seconds,
    grpcKeepAliveTimeout: FiniteDuration = 5.seconds,
    grpcKeepAliveWithoutCalls: Boolean = true,
    grpcTlsInsecure: Boolean = true,
    grpcTlsCertPath: Option[java.nio.file.Path] = None,
    grpcTlsKeyPath: Option[java.nio.file.Path] = None,
    grpcTlsCaPath: Option[java.nio.file.Path] = None,
    maxRetries: Int = 0,
    timeout: FiniteDuration = Duration.Zero,
)

/** Inbound HTTP app-server configuration.
  *
  * @param port
  *   Port on which [[dapr4s.internal.DaprAppServer]] listens (default 8080).
  * @param httpBacklog
  *   TCP accept backlog for [[com.sun.net.httpserver.HttpServer]] (0 = OS default).
  * @param shutdownGrace
  *   Time to allow in-flight requests to complete on JVM shutdown (default 2 seconds).
  */
case class AppServerConfig(
    port: DaprPort = DaprPort(8080),
    httpBacklog: Int = 0,
    shutdownGrace: FiniteDuration = 2.seconds,
)

/** Actor runtime configuration reported by the app to the Dapr sidecar via `GET /dapr/config`.
  *
  * @param actorIdleTimeout
  *   How long an actor instance is kept idle before being deactivated (default 1 hour).
  * @param actorScanInterval
  *   How often the sidecar scans for idle actors to deactivate (default 30 seconds).
  * @param drainOngoingCallTimeout
  *   How long to wait for an in-flight actor call to complete during rebalancing (default 30 seconds).
  * @param drainRebalancedActors
  *   Whether to wait for in-flight calls to finish before deactivating a rebalanced actor (default true).
  * @param reentrancy
  *   Reentrancy configuration (disabled by default).
  * @param remindersStoragePartitions
  *   Number of partitions for actor reminders storage; 0 = no partitioning (default 0).
  * @param entitiesConfig
  *   Per-actor-type overrides for timeouts and reentrancy settings (default empty).
  */
case class ActorRuntimeConfig(
    actorIdleTimeout: DaprDuration = DaprDuration(1.hour),
    actorScanInterval: DaprDuration = DaprDuration(30.seconds),
    drainOngoingCallTimeout: DaprDuration = DaprDuration(30.seconds),
    drainRebalancedActors: Boolean = true,
    reentrancy: ActorReentrancyConfig = ActorReentrancyConfig(),
    remindersStoragePartitions: Int = 0,
    entitiesConfig: List[ActorEntityConfig] = Nil,
)

/** Actor reentrancy configuration.
  *
  * @param enabled
  *   Whether reentrancy is enabled (default false).
  * @param maxStackDepth
  *   Maximum reentrancy call stack depth to prevent runaway recursion (default 32).
  */
case class ActorReentrancyConfig(
    enabled: Boolean = false,
    maxStackDepth: Int = 32,
)

/** Per-actor-type overrides for the global [[ActorRuntimeConfig]] settings.
  *
  * Only the `Some` fields override the global setting; `None` fields inherit from [[ActorRuntimeConfig]].
  *
  * @param entities
  *   Actor types this override applies to (must be non-empty).
  * @param actorIdleTimeout
  *   Override for [[ActorRuntimeConfig.actorIdleTimeout]].
  * @param actorScanInterval
  *   Override for [[ActorRuntimeConfig.actorScanInterval]].
  * @param drainOngoingCallTimeout
  *   Override for [[ActorRuntimeConfig.drainOngoingCallTimeout]].
  * @param drainRebalancedActors
  *   Override for [[ActorRuntimeConfig.drainRebalancedActors]].
  * @param reentrancy
  *   Override for [[ActorRuntimeConfig.reentrancy]].
  * @param remindersStoragePartitions
  *   Override for [[ActorRuntimeConfig.remindersStoragePartitions]].
  */
case class ActorEntityConfig(
    entities: List[ActorType],
    actorIdleTimeout: Option[DaprDuration] = None,
    actorScanInterval: Option[DaprDuration] = None,
    drainOngoingCallTimeout: Option[DaprDuration] = None,
    drainRebalancedActors: Option[Boolean] = None,
    reentrancy: Option[ActorReentrancyConfig] = None,
    remindersStoragePartitions: Option[Int] = None,
)

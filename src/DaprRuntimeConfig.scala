package dapr.safe

import language.experimental.safe

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
  *   Base URL of the Dapr sidecar HTTP API (e.g. `"http://localhost:3500"`).
  * @param grpcEndpoint
  *   Base URL of the Dapr sidecar gRPC API (e.g. `"http://localhost:50001"`).
  * @param apiToken
  *   Optional API token for authenticating requests to the sidecar (Dapr `DAPR_API_TOKEN`).
  * @param httpClientReadTimeoutSeconds
  *   Read timeout for the underlying OkHttp client in seconds (default 60).
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
  * @param grpcKeepAliveTimeSeconds
  *   Interval between keep-alive pings in seconds (default 10, only used when keep-alive is enabled).
  * @param grpcKeepAliveTimeoutSeconds
  *   Timeout for keep-alive ping responses in seconds (default 5).
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
  * @param timeoutSeconds
  *   Global call timeout in seconds; 0 means no timeout (default 0).
  */
case class SidecarConfig(
    httpEndpoint: String = "http://localhost:3500",
    grpcEndpoint: String = "http://localhost:50001",
    apiToken: Option[ApiToken] = None,
    httpClientReadTimeoutSeconds: Int = 60,
    httpClientMaxRequests: Int = 1024,
    httpClientMaxIdleConnections: Int = 128,
    grpcMaxInboundMessageSizeBytes: Int = 4194304,
    grpcMaxInboundMetadataSizeBytes: Int = 8192,
    grpcEnableKeepAlive: Boolean = false,
    grpcKeepAliveTimeSeconds: Int = 10,
    grpcKeepAliveTimeoutSeconds: Int = 5,
    grpcKeepAliveWithoutCalls: Boolean = true,
    grpcTlsInsecure: Boolean = true,
    grpcTlsCertPath: Option[String] = None,
    grpcTlsKeyPath: Option[String] = None,
    grpcTlsCaPath: Option[String] = None,
    maxRetries: Int = 0,
    timeoutSeconds: Int = 0,
)

/** Inbound HTTP app-server configuration.
  *
  * @param port
  *   Port on which [[dapr.safe.internal.DaprAppServer]] listens (default 8080).
  * @param httpBacklog
  *   TCP accept backlog for [[com.sun.net.httpserver.HttpServer]] (0 = OS default).
  * @param shutdownGraceSeconds
  *   Seconds to allow in-flight requests to complete on JVM shutdown (default 2).
  */
case class AppServerConfig(
    port: DaprPort = DaprPort(8080),
    httpBacklog: Int = 0,
    shutdownGraceSeconds: Int = 2,
)

/** Actor runtime configuration reported by the app to the Dapr sidecar via `GET /dapr/config`.
  *
  * @param actorIdleTimeout
  *   How long an actor instance is kept idle before being deactivated (default "1h").
  * @param actorScanInterval
  *   How often the sidecar scans for idle actors to deactivate (default "30s").
  * @param drainOngoingCallTimeout
  *   How long to wait for an in-flight actor call to complete during rebalancing (default "30s").
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
    actorIdleTimeout: DaprDuration = DaprDuration("1h"),
    actorScanInterval: DaprDuration = DaprDuration("30s"),
    drainOngoingCallTimeout: DaprDuration = DaprDuration("30s"),
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

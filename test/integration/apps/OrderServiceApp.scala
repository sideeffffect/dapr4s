package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given

/** Standalone entry point for the Order microservice.
  *
  * Starts an HTTP server on port 8080 (configurable via APP_PORT env var) that receives Dapr sidecar traffic: service
  * invocation and (optionally) pub/sub subscriptions.
  *
  * Run locally (requires Dapr sidecar on localhost:3500/50001):
  * {{{
  *   dapr run --app-id order-service --app-port 8080 -- \
  *     scala-cli run . --main-class "dapr4s.test.integration.apps.orderServiceMain"
  * }}}
  *
  * Build a fat jar for Docker:
  * {{{
  *   scala-cli --power package . --assembly -o order-service.jar \
  *     --main-class "dapr4s.test.integration.apps.orderServiceMain"
  * }}}
  */
@main def orderServiceMain(): Unit =
  val port = sys.env.getOrElse("APP_PORT", "8080").toInt
  val config = DaprConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"[order-service] starting on port $port")
  Dapr(config).serve:
    val app = OrderServiceHandlers.daprApp
    println("[order-service] handlers declared, serving...")
    app

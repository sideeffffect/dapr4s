package dapr4s.test.integration.apps

import dapr4s.*

/** Standalone entry point for the Inventory microservice.
  *
  * Starts an HTTP server on port 8081 (configurable via APP_PORT env var) that receives Dapr sidecar traffic: pub/sub
  * subscription deliveries and service invocation calls.
  *
  * Run locally (requires Dapr sidecar on localhost:3500/50001):
  * {{{
  *   dapr run --app-id inventory-service --app-port 8081 -- \
  *     scala-cli run . --main-class "dapr4s.test.integration.apps.inventoryServiceMain"
  * }}}
  *
  * Build a fat jar for Docker:
  * {{{
  *   scala-cli --power package . --assembly -o inventory-service.jar \
  *     --main-class "dapr4s.test.integration.apps.inventoryServiceMain"
  * }}}
  */
@main def inventoryServiceMain(): Unit =
  val port = sys.env.getOrElse("APP_PORT", "8081").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"[inventory-service] starting on port $port")
  DaprRuntime.serve(config):
    val app = InventoryServiceHandlers.daprApp
    println("[inventory-service] handlers declared, serving...")
    app

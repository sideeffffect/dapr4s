package dapr.safe.test.integration.apps

import dapr.safe.*
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

/** Business logic for the Order microservice.
  *
  * Each public handler method declares its capability requirements explicitly via
  * `using` parameters, following the capability-as-effect-system pattern:
  * the compiler tracks which effects (state reads/writes, pub/sub publishes) each
  * operation may perform, and call sites must provide the corresponding
  * capabilities.
  *
  * The `configure` method wires these pure handler methods into an [[AppHandlers]]
  * instance for use with a real Dapr sidecar or a [[dapr.safe.test.integration.TestAppHandlers]]
  * for in-process testing.
  *
  * Configured against Dapr component names:
  *   - state store : `statestore`
  *   - pub/sub     : `pubsub`
  *   - topic       : `orders`
  *
  * === Escape hatches and their justification ===
  *
  * The `configure` method contains two structured workarounds:
  *
  * 1. '''`try { ... } catch case e: Exception => throw e` in each handler lambda'''
  *    WHY: In Scala 3.9 CC, each lambda that calls a `throws`-annotated method
  *    creates a fresh anonymous `CanThrow` capability.  Sibling lambdas (defined in
  *    the same method body) cannot share these capabilities — a CC constraint that
  *    prevents CanThrow "leaking" across unrelated lambdas.  The try/catch absorbs
  *    the CanThrow at each lambda's boundary so the next sibling lambda starts
  *    fresh.  Without this, the second and later lambdas fail to compile with
  *    "capability `any` cannot flow into capture set {any²}".
  *    The re-throw preserves the original exception without swallowing it.
  *    See the AGENTS.md section on CC sibling lambda patterns.
  *
  * 2. '''`given StateCapability = ...` — capability injection at configure time'''
  *    WHY: The handler lambdas capture `StateCapability` and `PubSubCapability`
  *    from the enclosing `configure` scope.  This is intentional: the capabilities
  *    are bound once per configure call and shared across all handler invocations,
  *    matching the lifecycle of the underlying Dapr client connection.  The
  *    `AppHandlers.onInvoke` parameter type uses `{cap}` (universal capture set)
  *    precisely to allow this pattern without requiring `asInstanceOf` cast.
  */
object OrderServiceHandlers:

  val StateName   = StoreName("statestore")
  val PubSubComp  = PubSubName("pubsub")
  val OrdersTopic = Topic("orders")

  // ---------------------------------------------------------------------------
  // Handler methods — pure functions with explicit capability requirements
  // ---------------------------------------------------------------------------

  /** Place a new order: persist to state store and publish an [[OrderEvent]].
    * Returns the assigned order ID and acceptance status.
    */
  def placeOrder(req: OrderRequest)(using state: StateCapability, pubsub: PubSubCapability): OrderResponse throws Exception =
    val orderId = java.util.UUID.randomUUID().toString
    state.save(StateKey(orderId), req)
    pubsub.publish(OrdersTopic, OrderEvent(orderId, req.item, req.quantity))
    OrderResponse(orderId, "accepted")

  /** Retrieve a previously placed order by ID.  Returns `None` if not found. */
  def getOrder(orderId: String)(using state: StateCapability): Option[OrderRequest] throws Exception =
    state.get[OrderRequest](StateKey(orderId))

  /** Query orders using a raw JSON filter expression.
    * Returns a JSON array of `{"value": ..., "etag": ...}` objects.
    */
  def queryOrders(queryJson: String)(using state: StateCapability): String throws Exception =
    val results = state.queryState[OrderRequest](StateQuery(queryJson))
    val entries = results.map { e =>
      val v    = e.value.map(r => s"""{"item":"${r.item}","quantity":${r.quantity}}""").getOrElse("null")
      val etag = e.etag.map(t => s""""${t.value}"""").getOrElse("null")
      s"""{"value":$v,"etag":$etag}"""
    }
    entries.mkString("[", ",", "]")

  // ---------------------------------------------------------------------------
  // Handler registration
  // ---------------------------------------------------------------------------

  /** Register all handlers with `handlers`.
    *
    * Injects `StateCapability` and `PubSubCapability` as givens so the handler
    * lambdas below can call the pure handler methods without capturing the scope
    * directly.
    */
  def configure()(using scope: DaprScope, handlers: AppHandlers): Unit =
    given StateCapability  = scope.state(StateName)
    given PubSubCapability = scope.pubsub(PubSubComp)

    handlers.onInvoke[OrderRequest](MethodName("place-order"))[OrderResponse] { req =>
      // WHY TRY/CATCH: sibling-lambda CanThrow isolation — see class-level scaladoc.
      try placeOrder(req)
      catch case e: Exception => throw e
    }

    handlers.onInvoke[String](MethodName("get-order"))[Option[OrderRequest]] { orderId =>
      try getOrder(orderId)
      catch case e: Exception => throw e
    }

    handlers.onInvoke[String](MethodName("query-orders"))[String] { queryJson =>
      try queryOrders(queryJson)
      catch case e: Exception => throw e
    }

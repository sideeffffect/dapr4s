package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given
import language.experimental.safe

/** Business logic for the Order microservice.
  *
  * Each public handler method declares its capability requirements via anonymous `using` parameters — the capability
  * type itself is the requirement, and the compiler enforces it statically. Business logic calls companion-object
  * methods (e.g. [[StateCapability.save]], [[PubSubCapability.publish]]) rather than naming a capability value, so the
  * handler code reads like regular function calls:
  *
  * {{{
  *   def placeOrder(req: OrderRequest)(using StateCapability, PubSubCapability): OrderResponse =
  *     StateCapability.save(StateKey(orderId), req)
  *     PubSubCapability.publish(OrdersTopic, event)
  * }}}
  *
  * The `apply` method injects capabilities as `given`s and builds a [[DaprApp]] describing all inbound routes. The
  * resulting value is immutable and can be passed to [[Dapr.serve]] or [[dapr4s.test.integration.TestDaprApp]] for
  * in-process testing. Exposing the app builder as `apply` on a dedicated `*App` object is the idiom this library
  * promotes — see [[dapr4s.DaprCapability]].
  *
  * Configured against Dapr component names:
  *   - state store : `statestore`
  *   - pub/sub : `pubsub`
  *   - topic : `orders`
  */
object OrderServiceApp:

  val StateName = StoreName("statestore")
  val PubSubComp = PubSubName("pubsub")
  val OrdersTopic = Topic("orders")

  // ---------------------------------------------------------------------------
  // Handler methods — pure functions with explicit capability requirements
  // ---------------------------------------------------------------------------

  /** Place a new order: persist to state store and publish an [[OrderEvent]]. Returns the assigned order ID and
    * acceptance status.
    */
  def placeOrder(req: OrderRequest)(using StateCapability, PubSubCapability): OrderResponse =
    val orderId = java.util.UUID.randomUUID().toString
    StateCapability.save(StateKey(orderId), req)
    PubSubCapability.publish(OrdersTopic, OrderEvent(orderId, req.item, req.quantity))
    OrderResponse(orderId, "accepted")

  /** Retrieve a previously placed order by ID.  Returns `None` if not found. */
  def getOrder(orderId: String)(using StateCapability): Option[OrderRequest] =
    StateCapability.get[OrderRequest](StateKey(orderId))

  /** Query orders using a raw JSON filter expression. Returns a JSON array of `{"value": ..., "etag": ...}` objects.
    */
  def queryOrders(queryJson: String)(using StateCapability): String =
    val results = StateCapability.queryState[OrderRequest](StateQuery(queryJson))
    val entries = results.map { e =>
      val v = e.value.map(r => s"""{"item":"${r.item}","quantity":${r.quantity}}""").getOrElse("null")
      val etag = e.etag.map(t => s""""${t.value}"""").getOrElse("null")
      s"""{"value":$v,"etag":$etag}"""
    }
    entries.mkString("[", ",", "]")

  // ---------------------------------------------------------------------------
  // Declarative app description
  // ---------------------------------------------------------------------------

  /** Build a [[DaprApp]] with all inbound routes for the Order service.
    *
    * Uses the [[DaprCapability]] transformer API to introduce `StateCapability` and `PubSubCapability` into the body
    * scope, so the handler lambdas capture them without requiring explicit `given` declarations.
    */
  def apply()(using DaprCapability): DaprApp =
    DaprCapability.state(StateName) {
      DaprCapability.pubsub(PubSubComp) {
        DaprApp(
          invocations = List(
            InvocationRoute[OrderRequest, OrderResponse](InvocationMethodName("place-order"))(placeOrder),
            InvocationRoute[String, Option[OrderRequest]](InvocationMethodName("get-order"))(getOrder),
            InvocationRoute[String, String](InvocationMethodName("query-orders"))(queryOrders),
          ),
        )
      }
    }

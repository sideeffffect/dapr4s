package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
// NOT language.experimental.safe (unlike InventoryServiceApp): minting unique order ids needs an effectful
// counter, and safe mode forbids untracked mutable state — the same reason the shared `ItNames` counter is
// not safe-mode. Global capture checking still applies.

/** Business logic for the Order microservice.
  *
  * Each public handler method declares its capability requirements via anonymous `using` parameters — the capability
  * type itself is the requirement, and the compiler enforces it statically. Business logic calls companion-object
  * methods (e.g. [[StateCapability.save]], [[PublishCapability.publish]]) rather than naming a capability value, so the
  * handler code reads like regular function calls:
  *
  * {{{
  *   def placeOrder(req: OrderRequest)(using StateCapability, PublishCapability): OrderResponse =
  *     StateCapability.save(StateStoreKey(orderId), req)
  *     PublishCapability.publish(OrdersTopic, event)
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

  val StateName = StateStoreName("statestore")
  val PubSubComp = PubSubName("pubsub")
  val OrdersTopic = Topic("orders")

  // Monotonic order-id counter on the (singleton) object, so ids stay unique across all `placeOrder` calls in a run.
  // A plain Scala var — NOT `java.util.UUID.randomUUID` (reaches `java.security.SecureRandom`, which does not link on
  // Scala.js) and NOT a `java.*` counter (safe mode forbids unsafe-tagged Java APIs). Requests are issued sequentially
  // in the tests, so the unsynchronised increment never races.
  private var orderSeq: Long = 0L

  // ---------------------------------------------------------------------------
  // Handler methods — pure functions with explicit capability requirements
  // ---------------------------------------------------------------------------

  /** Place a new order: persist to state store and publish an [[OrderEvent]]. Returns the assigned order ID and
    * acceptance status.
    */
  def placeOrder(req: OrderRequest)(using StateCapability, PublishCapability): OrderResponse =
    orderSeq += 1
    val orderId = s"order-$orderSeq"
    StateCapability.save(StateStoreKey(orderId), req)
    PublishCapability.publish(OrdersTopic, OrderEvent(orderId, req.item, req.quantity))
    OrderResponse(orderId, "accepted")

  /** Retrieve a previously placed order by ID.  Returns `None` if not found. */
  def getOrder(orderId: String)(using StateCapability): Option[OrderRequest] =
    StateCapability.get[OrderRequest](StateStoreKey(orderId))

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
    * Uses the [[DaprCapability]] transformer API to introduce `StateCapability` and `PublishCapability` into the body
    * scope, so the handler lambdas capture them without requiring explicit `given` declarations.
    */
  def apply()(using DaprCapability): DaprApp =
    DaprCapability.state(StateName) {
      DaprCapability.publish(PubSubComp) {
        DaprApp(
          invokeRoutes = List(
            InvokeRoute[OrderRequest, OrderResponse](InvokeMethodName("place-order"))(placeOrder),
            InvokeRoute[String, Option[OrderRequest]](InvokeMethodName("get-order"))(getOrder),
            InvokeRoute[String, String](InvokeMethodName("query-orders"))(queryOrders),
          ),
        )
      }
    }

package dapr4s.test.integration.apps

import dapr4s.*
import language.experimental.safe

/** Business logic for the Inventory microservice.
  *
  * Each handler method declares its capability requirements via anonymous `using` parameters, calling companion-object
  * methods ([[StateCapability.get]], [[DistributedLockCapability.tryLock]], etc.) rather than naming a capability. See
  * [[OrderServiceHandlers]] for a full explanation of the capability-as-effect-system pattern.
  *
  * Subscribes to [[OrderEvent]] messages on the `orders` pub/sub topic and decrements the corresponding item's stock
  * count in the state store. The decrement is protected by a distributed lock to prevent concurrent over-decrements
  * when multiple events for the same item arrive simultaneously.
  *
  * Exposes two invocation methods:
  *   - `get-stock` : retrieve current stock level for an item
  *   - `seed-stock` : set the initial stock level for an item (test helper)
  *
  * Configured against Dapr component names:
  *   - state store : `statestore`
  *   - pub/sub : `pubsub`
  *   - topic : `orders`
  *   - distributed lock : `lockstore`
  */
object InventoryServiceHandlers:

  val StateName = StoreName("statestore")
  val PubSubComp = PubSubName("pubsub")
  val OrdersTopic = Topic("orders")
  val LockStoreName = StoreName("lockstore")

  /** Default stock level when no seed has been set. */
  val DefaultStock = 100

  // ---------------------------------------------------------------------------
  // Handler methods — explicit capability requirements via `using`
  // ---------------------------------------------------------------------------

  /** Handle an incoming order event: decrement stock for the ordered item.
    *
    * Acquires a distributed lock on the item name before reading and writing the stock level, preventing concurrent
    * handlers from racing on the same key. If the lock cannot be acquired the event is retried.
    */
  def handleOrderEvent(event: CloudEvent[OrderEvent])(using
      StateCapability,
      DistributedLockCapability,
  ): SubscriptionResult =
    val item = event.data.item
    val qty = event.data.quantity
    val key = StateKey(s"stock-$item")
    val owner = LockOwner(s"inv-${event.id.value}")

    if DistributedLockCapability.tryLock(LockResourceId(item), owner, 10) then
      try
        val current = StateCapability.get[Int](key).getOrElse(DefaultStock)
        val updated = math.max(0, current - qty)
        StateCapability.save(key, updated)
      finally DistributedLockCapability.unlock(LockResourceId(item), owner)
      SubscriptionResult.Success
    else SubscriptionResult.Retry

  /** Return current stock level for the given item name. */
  def getStock(item: String)(using StateCapability): StockLevel =
    val available = StateCapability.get[Int](StateKey(s"stock-$item")).getOrElse(DefaultStock)
    StockLevel(item, available)

  /** Seed the stock level for an item (test helper and k8s init). */
  def seedStock(stock: StockLevel)(using StateCapability): StockLevel =
    StateCapability.save(StateKey(s"stock-${stock.item}"), stock.available)
    stock

  // ---------------------------------------------------------------------------
  // Declarative app description
  // ---------------------------------------------------------------------------

  /** Build a [[DaprApp]] with all inbound routes for the Inventory service. */
  def daprApp(using DaprCapability): DaprApp =
    DaprCapability.state(StateName) {
      DaprCapability.lock(LockStoreName) {
        DaprApp(
          subscriptions = List(
            Subscription[OrderEvent](PubSubComp, OrdersTopic)(handleOrderEvent),
          ),
          invocations = List(
            InvocationRoute[String, StockLevel](MethodName("get-stock"))(getStock),
            InvocationRoute[StockLevel, StockLevel](MethodName("seed-stock"))(seedStock),
          ),
        )
      }
    }

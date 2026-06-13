package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given

/** Domain models shared by all demo microservices. */

/** A request to place an order. */
final case class OrderRequest(item: String, quantity: Int)
@scala.caps.assumeSafe
object OrderRequest:
  given JsonCodec[OrderRequest] = upickleCodec(using upickle.default.macroRW)

/** Response returned after placing an order. */
final case class OrderResponse(orderId: String, status: String)
@scala.caps.assumeSafe
object OrderResponse:
  given JsonCodec[OrderResponse] = upickleCodec(using upickle.default.macroRW)

/** The pub/sub event published when an order is accepted. */
final case class OrderEvent(orderId: String, item: String, quantity: Int)
@scala.caps.assumeSafe
object OrderEvent:
  given JsonCodec[OrderEvent] = upickleCodec(using upickle.default.macroRW)

/** Query for the stock level of a named item. */
final case class StockQuery(item: String)
@scala.caps.assumeSafe
object StockQuery:
  given JsonCodec[StockQuery] = upickleCodec(using upickle.default.macroRW)

/** Current stock level for an item. */
final case class StockLevel(item: String, available: Int)
@scala.caps.assumeSafe
object StockLevel:
  given JsonCodec[StockLevel] = upickleCodec(using upickle.default.macroRW)

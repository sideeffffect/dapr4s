package dapr.safe.test.integration.apps

import upickle.default.{ReadWriter, macroRW}

/** Domain models shared by all demo microservices. */

/** A request to place an order. */
final case class OrderRequest(item: String, quantity: Int)
object OrderRequest:
  given ReadWriter[OrderRequest] = macroRW

/** Response returned after placing an order. */
final case class OrderResponse(orderId: String, status: String)
object OrderResponse:
  given ReadWriter[OrderResponse] = macroRW

/** The pub/sub event published when an order is accepted. */
final case class OrderEvent(orderId: String, item: String, quantity: Int)
object OrderEvent:
  given ReadWriter[OrderEvent] = macroRW

/** Query for the stock level of a named item. */
final case class StockQuery(item: String)
object StockQuery:
  given ReadWriter[StockQuery] = macroRW

/** Current stock level for an item. */
final case class StockLevel(item: String, available: Int)
object StockLevel:
  given ReadWriter[StockLevel] = macroRW

package dapr.safe

import language.experimental.safe

/** JSON-encoded state query expression for [[StateCapability.queryState]].
  *
  * Follows the Dapr query API format: a JSON object that may contain `filter`, `sort`, and `page` clauses. Support for
  * query operations depends on the backing state store; not all stores implement the query API. Construct the JSON
  * string manually or via a JSON library and wrap it with `StateQuery(jsonString)`.
  */
opaque type StateQuery = String
object StateQuery:
  def apply(query: String): StateQuery = query
  extension (s: StateQuery) def value: String = s

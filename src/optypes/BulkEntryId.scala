package dapr.safe

import language.experimental.safe

opaque type BulkEntryId = String
object BulkEntryId:
  def apply(s: String): BulkEntryId = s
  extension (id: BulkEntryId) def value: String = id

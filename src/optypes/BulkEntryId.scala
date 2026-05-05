package dapr.safe

import language.experimental.safe

/** Caller-assigned identifier for one entry in a bulk publish request.
  *
  * Used to correlate failures in [[BulkPublishResult]]: any entry whose ID appears in `BulkPublishResult.failedEntries`
  * was not successfully published. May be empty; uniqueness within the batch is the caller's responsibility.
  */
opaque type BulkEntryId = String
object BulkEntryId:
  def apply(s: String): BulkEntryId = s
  extension (id: BulkEntryId) def value: String = id

package dapr.safe

/** Concurrency control mode for conditional Dapr state writes.
  *
  * Determines what happens when two concurrent ETag-conditional writes race. Use [[StateConcurrency.Default]] to let
  * the state store decide.
  *
  * @see
  *   [[StateCapability.saveWithETag]], [[StateCapability.deleteWithETag]]
  */
@scala.caps.assumeSafe
enum StateConcurrency:
  /** Use the state store's default concurrency mode. */
  case Default

  /** Only the first writer wins; subsequent concurrent writes with the same ETag are rejected. */
  case FirstWrite

  /** Last writer wins; the most recent write always succeeds regardless of concurrent races. */
  case LastWrite

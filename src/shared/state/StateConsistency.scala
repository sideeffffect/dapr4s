package dapr4s.state

import dapr4s.*

/** Consistency level for Dapr state operations.
  *
  * Controls how strongly consistent read or write operations must be. Use [[StateConsistency.Default]] to let the
  * backing state store decide (typically eventual consistency).
  *
  * @see
  *   [[StateCapability.get]], [[StateCapability.saveWithETag]], [[StateCapability.deleteWithETag]]
  */
@scala.caps.assumeSafe
enum StateConsistency:
  /** Use the state store's default consistency (no explicit preference sent to the sidecar). */
  case Default

  /** Eventual consistency — lower latency, weaker guarantees. */
  case Eventual

  /** Strong consistency — higher latency, strongest guarantees. */
  case Strong

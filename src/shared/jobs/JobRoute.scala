package dapr4s.jobs

import dapr4s.*

/** Existential wrapper for a job trigger handler.
  *
  * '''Dual:''' the inbound counterpart of [[JobsCapability]] — a job scheduled via [[JobsCapability.schedule]] fires as
  * an inbound trigger the sidecar POSTs to `/job/<name>`, answered by the `JobRoute` for that same [[JobName]]. The
  * `Payload` type member binds [[codec]] to the payload the job was scheduled with. (Derivation binds the two through
  * one trait: `Jobs.derive` ↔ `JobRoutes.deriveChecked`.)
  *
  * Use [[JobRoute.apply]] to construct instances.
  */
sealed abstract class JobRoute:
  type Payload
  val name: JobName
  val codec: JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[JobRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object JobRoute:

  def apply[T: JsonCodec](name: JobName)(
      handler: T => Unit,
  ): JobRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val nm = name
    val c = summon[JsonCodec[T]]
    new JobRoute:
      type Payload = T
      val name = nm
      val codec = c
      val rawHandler = handler.asInstanceOf[AnyRef]

//> using target.platform "jvm"
package dapr4s

/** JVM half of the [[DaprCapability]] surface — the factory methods for building blocks the Dapr
  * Java SDK supports but the Dapr JS SDK does not (jobs, conversation).
  *
  * [[DaprCapability]] extends this trait, so on the JVM these methods are ordinary members of
  * `DaprCapability`. The Scala.js twin of this trait is empty, so on that platform the methods do
  * not exist and using them is a compile error (see the platform-surface note on
  * [[DaprCapability]]).
  *
  * WHY a self-type instead of `extends scala.caps.ExclusiveCapability`: the `^{this}` return
  * types must refer to the same tracked capability instance as the rest of the `DaprCapability`
  * factory methods. The self-type makes `this` have type `DaprCapability` (a tracked capability
  * class), so `^{this}` here is checked identically to the `^{this}` annotations in the shared
  * trait — sub-capabilities cannot outlive the root scope.
  *
  * WHY @assumeSafe: same reason as on [[DaprCapability]] itself — implementations live behind the
  * `dapr4s.internal` SDK-interop wall and safe-mode user code consumes them through this trait.
  */
@scala.caps.assumeSafe
trait DaprCapabilityPlatform:
  this: DaprCapability =>

  /** Obtain the [[JobsCapability]] (shared; no named component). */
  def jobs: JobsCapability^{this}

  /** Obtain a [[ConversationCapability]] for the named conversation (LLM) component. */
  def conversation(componentName: ConversationComponentName): ConversationCapability^{this}

/** JVM half of the [[DaprCapability$ DaprCapability companion]] transformer API — the
  * transformer methods for the JVM-only building blocks (jobs, conversation), inherited by
  * `object DaprCapability`. The Scala.js twin of this trait is empty.
  *
  * WHY @assumeSafe: identical to the shared companion — `cap.jobs` / `cap.conversation(...)`
  * return capabilities carrying a `^{cap}` capture set, and passing them to a context function
  * expecting the unannotated capability type widens the capture set. That is safe because the
  * `^{this}` return types on [[DaprCapabilityPlatform]] prevent the sub-capabilities from
  * outliving the root scope; see the full argument on the shared `DaprCapability` companion.
  */
@scala.caps.assumeSafe
trait DaprCapabilityCompanionPlatform:

  def jobs[T](body: JobsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.jobs.asInstanceOf[JobsCapability])

  def conversation(componentName: ConversationComponentName)[T](body: ConversationCapability ?=> T)(using
      cap: DaprCapability
  ): T =
    body(using cap.conversation(componentName).asInstanceOf[ConversationCapability])

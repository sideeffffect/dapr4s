package dapr4s

import language.experimental.safe

/** Name of a service-invocation method.
  *
  * Must not be empty. Used as the URL path segment that identifies the target route when one app invokes another via
  * [[ServiceInvocationCapability.invoke]], and as the route key when registering an incoming [[InvocationRoute]].
  *
  * Distinct from [[ActorMethodName]]: this addresses an HTTP route on a remote app, not a method on a stateful actor.
  */
opaque type InvocationMethodName = String
object InvocationMethodName:
  def apply(s: String): InvocationMethodName =
    require(s.nonEmpty, "InvocationMethodName must not be empty")
    s
  extension (n: InvocationMethodName) def value: String = n

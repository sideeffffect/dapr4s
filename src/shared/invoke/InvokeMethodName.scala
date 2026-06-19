package dapr4s.invoke

import dapr4s.*

import language.experimental.safe

/** Name of a service-invocation method.
  *
  * Must not be empty. Used as the URL path segment that identifies the target route when one app invokes another via
  * [[InvokeCapability.invoke]], and as the route key when registering an incoming [[InvokeRoute]].
  *
  * Distinct from [[ActorMethodName]]: this addresses an HTTP route on a remote app, not a method on a stateful actor.
  */
opaque type InvokeMethodName = String
object InvokeMethodName:
  def apply(s: String): InvokeMethodName =
    require(s.nonEmpty, "InvokeMethodName must not be empty")
    s
  extension (n: InvokeMethodName) def value: String = n

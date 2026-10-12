package dapr4s.invoke

import dapr4s.*

import language.experimental.safe

/** An incoming service invocation request. `httpMethod` is the HTTP verb (GET, POST, PUT, DELETE, etc.) used by the
  * calling app.
  */
final case class InvokeRequest[T](
    methodName: InvokeMethodName,
    httpMethod: HttpMethod,
    data: T,
)

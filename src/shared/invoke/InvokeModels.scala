package dapr4s.invoke

import dapr4s.*

import language.experimental.safe
import scala.collection.immutable.ArraySeq

/** An incoming service invocation request. `httpMethod` is the HTTP verb (GET, POST, PUT, DELETE, etc.) used by the
  * calling app.
  */
final case class InvokeRequest[T](
    methodName: InvokeMethodName,
    httpMethod: HttpMethod,
    data: T,
)

/** The target app's HTTP answer to [[InvokeCapability.Instance.invokeHttp]], whatever its status.
  *
  * @param status
  *   the HTTP status code the target app answered with (daprd passes it through unchanged)
  * @param headers
  *   the response headers, names lower-cased; a header sent several times is joined into one value with `", "` (the RFC
  *   9110 field-combination rule)
  * @param body
  *   the response body exactly as the app sent it
  */
final case class InvokeResponse(
    status: Int,
    headers: Map[MetadataKey, MetadataValue],
    body: ArraySeq[Byte],
):
  /** True for a 2xx status — the statuses for which the typed `invoke` decodes the body instead of throwing
    * [[InvokeHttpException]].
    */
  def isSuccess: Boolean = status >= 200 && status < 300

  /** The response body decoded as UTF-8. */
  def bodyText: String = Charsets.decodeString(body, Charsets.Utf8)

  /** The value of response header `name` (case-insensitive), if present. */
  def header(name: String): Option[MetadataValue] =
    headers.get(MetadataKey(name.toLowerCase(java.util.Locale.ROOT).nn))

/** Name of a URL query parameter sent with [[InvokeCapability.Instance.invokeHttp]]. Must not be empty. */
opaque type QueryParamName = String
object QueryParamName:
  def apply(s: String): QueryParamName =
    require(s.nonEmpty, "QueryParamName must not be empty")
    s
  extension (n: QueryParamName) def value: String = n

/** Value of a URL query parameter sent with [[InvokeCapability.Instance.invokeHttp]]. May be empty (`?flag=`). */
opaque type QueryParamValue = String
object QueryParamValue:
  def apply(s: String): QueryParamValue = s
  extension (v: QueryParamValue) def value: String = v

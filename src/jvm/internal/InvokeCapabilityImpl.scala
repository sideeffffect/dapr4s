//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import dapr4s.invoke.*
import io.dapr.config.Properties
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters.*
import NullOps.*

@scala.caps.assumeSafe
private object InvokeCapabilityImpl:

  /** One HTTP client for every invoke call of the process.
    *
    * WHY raw HTTP and not the Java SDK's `DaprClient.invokeMethod`: the SDK turns every non-2xx answer (3xx included)
    * into its `DaprException` and drops the response headers on success, so neither `invokeHttp`'s status/headers/body
    * result nor a faithful [[InvokeHttpException]] can be built from it. The sidecar's `/v1.0/invoke` HTTP API passes
    * the target app's status, headers and body through unchanged.
    *
    * HTTP/1.1 pinned: daprd's HTTP API does not speak h2c, and the JDK client's default `HTTP_2` would send an
    * `Upgrade: h2c` header on every request for nothing. Redirects are never followed (`Redirect.NEVER`, the default,
    * stated explicitly): a 3xx is the target's answer and is returned as such. `HttpClient.send` parks a virtual thread
    * without pinning its carrier, like `MonoOps.awaitResult`.
    *
    * The client is never closed: it holds no per-sidecar state (the sidecar URL is per request), its selector thread is
    * a daemon, and it lives as long as the class — the same lifetime as the JDK's own shared clients.
    */
  private lazy val http: HttpClient =
    HttpClient
      .newBuilder()
      .nn
      .version(HttpClient.Version.HTTP_1_1)
      .nn
      .followRedirects(HttpClient.Redirect.NEVER)
      .nn
      .build()
      .nn

  /** `http.send` with the interrupt flag restored.
    *
    * WHY WE CATCH InterruptedException HERE — the same contract as `MonoOps.awaitResult` (see the full argument there):
    * `HttpClient.send` waits on a `CompletableFuture`, whose `get()` CLEARS the interrupt flag when it throws
    * `InterruptedException`, and the JDK client rethrows it as is. Restoring the flag immediately keeps the
    * cancellation signal visible to every caller up the stack. We rethrow the original exception unchanged — never
    * wrapped in a `DaprException` (that would hide the interrupt) — so this is propagation, not suppression.
    * `JvmErrors.guard` around this call matches only `NonFatal`, which excludes `InterruptedException`, so it passes
    * through untouched.
    */
  private def send(request: HttpRequest): HttpResponse[Array[Byte]] =
    given CanThrow[Exception] = unsafeExceptions.canThrowAny
    try http.send(request, HttpResponse.BodyHandlers.ofByteArray()).nn
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw e

  private def verb(m: HttpMethod): String =
    m match
      case HttpMethod.Get     => "GET"
      case HttpMethod.Post    => "POST"
      case HttpMethod.Put     => "PUT"
      case HttpMethod.Delete  => "DELETE"
      case HttpMethod.Patch   => "PATCH"
      case HttpMethod.Head    => "HEAD"
      case HttpMethod.Options => "OPTIONS"

@scala.caps.assumeSafe
private[internal] final class InvokeCapabilityImpl(
    scope: DaprCapabilityImpl,
    val appId: AppId,
) extends InvokeCapability.Instance:

  import InvokeCapabilityImpl.*

  private val ctx = ErrorContext.App(appId)

  def invoke[Req: JsonCodec](
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Resp =
    InvokeSupport.invokeTyped[Req, Resp](this, method, data, httpMethod, metadata)

  def invoke[Resp: JsonCodec](method: InvokeMethodName): Resp =
    InvokeSupport.invokeTypedNoBody[Resp](this, method)

  def invokeHttp(
      method: InvokeMethodName,
      body: ArraySeq[Byte] = ArraySeq.empty,
      httpMethod: HttpMethod = HttpMethod.Post,
      headers: Map[MetadataKey, MetadataValue] = Map.empty,
      query: Map[QueryParamName, QueryParamValue] = Map.empty,
  ): InvokeResponse =
    val base = scope.sidecarProperties.getValue(Properties.HTTP_ENDPOINT).toOption.getOrElse("http://localhost:3500")
    val uri = URI.create(base.stripSuffix("/") + InvokeSupport.invokePath(appId, method, query)).nn
    val sent = InvokeSupport.sendableBody(httpMethod, body)
    val publisher =
      if sent.isEmpty then HttpRequest.BodyPublishers.noBody().nn
      else HttpRequest.BodyPublishers.ofByteArray(sent.toArray).nn
    val builder = HttpRequest.newBuilder(uri).nn.method(verb(httpMethod), publisher).nn
    // SidecarConfig.httpClientReadTimeout (Dapr.sidecarProperties): an invoke of a hung app fails with
    // DaprApiException("DEADLINE_EXCEEDED") instead of blocking forever, as the SDK's OkHttp client did.
    scope.sidecarProperties
      .getValue(Properties.HTTP_CLIENT_READ_TIMEOUT_SECONDS)
      .toOption
      .filter(_.intValue > 0)
      .foreach(s => builder.timeout(java.time.Duration.ofSeconds(s.longValue)): Unit)
    // The API token goes first; a caller header of the same name replaces it (setHeader overwrites), mirroring the JS
    // twin where caller metadata replaces the base headers.
    scope.sidecarProperties
      .getValue(Properties.API_TOKEN)
      .toOption
      .filter(_.nonEmpty)
      .foreach(t => builder.setHeader("dapr-api-token", t): Unit)
    InvokeSupport.sendableHeaders(headers).foreach((k, v) => builder.setHeader(k.value, v.value): Unit)
    // send() throws IOException on a transport failure (→ DaprApiException("UNAVAILABLE") via the guard).
    val response = JvmErrors.guard(ctx)(send(builder.build().nn))
    val status = response.statusCode
    val bytes = response.body.toOption.getOrElse(Array.emptyByteArray)
    if status < 200 || status >= 300 then
      InvokeSupport.throwIfSidecarError(
        appId,
        status,
        JvmErrors.parseErrorBody(status, new String(bytes, java.nio.charset.StandardCharsets.UTF_8)),
      )
    val rawHeaders = response.headers.nn.map.nn.asScala.toList.flatMap { (name, values) =>
      values.asScala.map(v => name -> v)
    }
    // unsafeWrapArray (no copy): `bytes` is the fresh array the body handler allocated for this response and no
    // other reference to it survives this method, so the ArraySeq is effectively immutable.
    InvokeResponse(status, InvokeSupport.normaliseHeaders(rawHeaders), ArraySeq.unsafeWrapArray(bytes))

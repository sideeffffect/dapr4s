//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import JsInterop.*
import dapr4styped.daprDapr.typesPubsubPubSubBulkPublishMessageDottypeMod.{
  PubSubBulkPublishMessage,
  PubSubBulkPublishMessageExplicit,
}
import dapr4styped.daprDapr.typesPubsubPubSubPublishOptionsDottypeMod.PubSubPublishOptions
import dapr4styped.daprDapr.typesPubsubPubSubPublishResponseDottypeMod.PubSubPublishResponseType
import dapr4styped.node.globalsMod.global as NodeGlobals
import dapr4styped.undiciTypes.fetchMod.RequestInit

@scala.caps.assumeSafe
private[internal] final class PublishCapabilityImpl(
    scope: DaprCapabilityImpl,
    val pubsubName: PubSubName,
) extends PublishCapability:

  import PublishCapabilityImpl.*

  // The pre-encoded JSON is parsed into a JS value and published with an explicit
  // contentType = "application/json": with the header set, the SDK's serializeHttp
  // (utils/Serializer.util.js) takes the JSON branch and JSON.stringify-s the value — yielding
  // exactly our original document on the wire with the application/json content type, identical
  // to the JVM impl (raw JSON bytes). WITHOUT the override the SDK would INFER the content type
  // from the JS value (utils/Client.util.js getContentType), and a JSON scalar payload (string/
  // number/boolean) would be inferred as text/plain and sent via toString() — e.g. the JSON
  // string "hi" would arrive as the 2 bytes `hi` instead of the 4 bytes `"hi"`.
  //
  // FALSY payloads cannot take the SDK path at all: HTTPClient.execute guards the request body
  // with a JS truthiness check (`if (params?.body)` — implementation/Client/HTTPClient/
  // HTTPClient.js), so a payload parsing to 0, false, null or "" would be silently DROPPED and
  // an empty body published. Those documents go through rawPublish (raw fetch) instead — see
  // JsInterop.isFalsyJson.
  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    val json = summon[JsonCodec[T]].encode(data)
    val parsed = parseJson(json)
    if isFalsyJson(parsed) then rawPublish(scope.sidecar, pubsubName, topic, json, Map.empty)
    else
      val response = JsAwait.await(
        scope.client.pubsub.publish(pubsubName.value, topic.value, asPublishData(parsed), jsonContentTypeOptions),
      )
      throwIfFailed(response)

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    val json = summon[JsonCodec[T]].encode(data)
    val parsed = parseJson(json)
    // Falsy payloads bypass the SDK — see the publish comment above.
    if isFalsyJson(parsed) then rawPublish(scope.sidecar, pubsubName, topic, json, metadata)
    else
      val options = PubSubPublishOptions().setContentType("application/json").setMetadata(toDict(metadata))
      val response = JsAwait.await(
        scope.client.pubsub.publish(pubsubName.value, topic.value, asPublishData(parsed), options),
      )
      throwIfFailed(response)

  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult =
    // The explicit {entryID, event, contentType} message shape keeps our entry IDs authoritative
    // (the SDK generates random UUIDs otherwise — utils/Client.util.js getBulkPublishEntries; the
    // explicit form is detected via `"event" in message`) and pins application/json per entry for
    // the same scalar-payload reason as publish above.
    // The explicit element-type ascription widens each entry to the SDK's PubSubBulkPublishMessage union
    // (Explicit | js.Object | String) — js.Array is invariant, so an Array of the Explicit member type alone
    // would not conform to the publishBulk parameter.
    val messages = entries
      .map[PubSubBulkPublishMessage] { entry =>
        PubSubBulkPublishMessageExplicit(asPublishData(parseJson(summon[JsonCodec[T]].encode(entry.event))))
          .setEntryID(entry.entryId.value)
          .setContentType("application/json")
      }
      .toJSArray
    val response = JsAwait.await(scope.client.pubsub.publishBulk(pubsubName.value, topic.value, messages))
    val failed = response.failedMessages.toList
    // Whole-request failures must THROW (the JVM twin's bulkPublish throws DaprException there),
    // but the SDK never lets them escape: publishBulk catches the rejection and fabricates a
    // per-entry failure list covering every entry, all sharing the ONE caught error object
    // (handleBulkPublishError/getBulkPublishResponse — implementation/Client/HTTPClient/pubsub.js
    // + utils/Client.util.js). Genuine partial failures are built entry-by-entry with a fresh
    // `new Error(entry.error)` each, so the SDK's whole-request fabrication is recognisable by
    // this heuristic: every entry failed AND the error objects are reference-identical AND the
    // error follows HTTPClient.execute's {error, error_msg, status} rejection convention
    // (sdkFailureOf). Anything else stays a per-entry BulkPublishResult, mirroring the JVM.
    failed.headOption match
      case Some(first)
          if failed.sizeIs == entries.size
            && failed.forall(_.error eq first.error)
            && sdkFailureOf(first.error).isDefined =>
        throw js.JavaScriptException(first.error)
      case _ =>
        BulkPublishResult(failed.map(fm => BulkEntryId(fm.message.entryID)))

@scala.caps.assumeSafe
private object PublishCapabilityImpl:
  private val jsonContentTypeOptions = PubSubPublishOptions().setContentType("application/json")

  /** WHAT: asInstanceOf viewing a parsed JSON value (`js.Any`) as `js.Object`, the SDK's publish-data type.
    *
    * WHY: the TypeScript signature (`data?: object | string`) is narrower than the runtime contract — with the explicit
    * application/json content type, `serializeHttp` JSON.stringify-s '''any''' JS value, and dapr4s must pass JSON
    * scalar documents (truthy numbers/booleans/strings like `1.5`/`true`/`"hi"`) down this same path. (The String side
    * of the TS union is not used: it would route scalar documents into the SDK's text/plain inference — see the
    * `publish` comment.) Only JS-falsy values are excluded (they take the rawPublish bypass).
    *
    * WHY SAFE: erased, zero-cost view change; every value here came out of `JSON.parse`, and the SDK's only operation
    * on it is the single `JSON.stringify` that puts our original document back on the wire.
    */
  private def asPublishData(parsed: js.Any): js.Object =
    parsed.asInstanceOf[js.Object]

  /** `pubsub.publish` soft-fails (`{error}` instead of rejecting — `implementation/Client/HTTPClient/pubsub.js`, typed
    * `PubSubPublishResponseType` with an optional `error`); rethrow to mirror the JVM impl, where a failed
    * `publishEvent` throws `DaprException`.
    */
  private def throwIfFailed(response: PubSubPublishResponseType): Unit =
    response.error.toOption.foreach(e => throw js.JavaScriptException(e))

  /** Publish over the raw sidecar HTTP API (`POST /v1.0/publish/{pubsub}/{topic}`), bypassing the SDK.
    *
    * Exists because the SDK cannot transmit JS-falsy payloads: `HTTPClient.execute` only attaches a body when
    * `if (params?.body)` is truthy (`node_modules/@dapr/dapr/implementation/Client/HTTPClient/HTTPClient.js`), so the
    * JSON documents `0`, `false`, `null` and `""` would be published with an '''empty''' body. Same raw-fetch precedent
    * as [[ActorCapabilityImpl]]/[[StateCapabilityImpl.getWithETag]]; the pre-encoded JSON string goes on the wire
    * verbatim with `Content-Type: application/json` (no SDK serializer involved, so no double encoding), and metadata
    * becomes `metadata.{key}` query parameters exactly like the SDK's `createHTTPQueryParam` builds them. Non-2xx
    * answers throw with the shared raw-fetch message shape.
    */
  private def rawPublish(
      sidecar: SidecarConfig,
      pubsubName: PubSubName,
      topic: Topic,
      json: String,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    import ActorCapabilityImpl.urlSegment
    val query =
      if metadata.isEmpty then ""
      else
        metadata
          .map { case (k, v) => s"metadata.${urlSegment(k.value)}=${urlSegment(v.value)}" }
          .mkString("?", "&", "")
    val base = ActorCapabilityImpl.httpBase(sidecar)
    val url = s"$base/v1.0/publish/${urlSegment(pubsubName.value)}/${urlSegment(topic.value)}$query"
    val init = RequestInit().setMethod("POST").setHeaders(ActorCapabilityImpl.baseHeaders(sidecar)).setBody(json)
    val response = JsAwait.await(NodeGlobals.fetch(url, init))
    // Always consume the body (Node fetch keep-alive invariant — see HttpActorContext.postJson).
    val text = JsAwait.await(response.text())
    if response.status >= 400 then throw new RuntimeException(s"Dapr API error ${response.status} at $url: $text")

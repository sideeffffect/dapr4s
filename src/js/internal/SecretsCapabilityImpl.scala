//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import dapr4s.secrets.*
import scala.scalajs.js
import JsInterop.*

@scala.caps.assumeSafe
private[internal] final class SecretsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: SecretStoreName,
) extends SecretsCapability:

  import SecretsCapabilityImpl.*

  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty): Option[SecretValue] =
    val promise = metadataQuery(metadata) match
      case None     => scope.client.secret.get(storeName.value, key.value)
      case Some(qs) => scope.client.secret.get(storeName.value, key.value, qs)
    val response = JsAwait.await(promise)
    // None-vs-throw semantics mirror the JVM impl exactly: a sidecar error (e.g. secret not found → 500/404)
    // REJECTS the promise and propagates (the JVM's DaprException does too); None is returned only when the call
    // succeeds but the {key: value} response object is empty or lacks the key. The single-entry fallback covers
    // stores that answer under a different key name, same as the JVM's sizeIs == 1 branch.
    val entries = toStringMap(response)
    if entries.isEmpty then None
    else
      entries
        .get(key.value)
        .orElse(if entries.sizeIs == 1 then entries.valuesIterator.nextOption() else None)
        .map(SecretValue(_))

  def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[SecretKey, SecretValue] =
    // The SDK's getBulk takes no metadata parameter (implementation/Client/HTTPClient/secret.js); the dapr4s
    // metadata argument cannot be forwarded and is ignored, like the other knobs without a JS equivalent. The
    // response is {secretName: {key: value}} — flattened to "secretName/key" compound keys exactly like the JVM.
    val response = JsAwait.await(scope.client.secret.getBulk(storeName.value))
    if isAbsent(response) then Map.empty
    else
      val outer = response.asInstanceOf[js.Dictionary[js.Any]]
      // WHAT: asInstanceOf views the parsed JSON response as a dictionary.
      // WHY: the SDK types the response as `object`; the secrets bulk API contract is a JSON object of objects.
      // WHY SAFE: js.Dictionary is a zero-cost view of any JS object (no runtime cast); inner values are
      // re-checked via toStringMap before use.
      outer.iterator.flatMap { case (secretKey, subMap) =>
        toStringMap(subMap).map { case (subKey, v) => SecretKey(s"$secretKey/$subKey") -> SecretValue(v) }
      }.toMap

@scala.caps.assumeSafe
private object SecretsCapabilityImpl:

  /** Render dapr4s metadata as the pre-built `metadata.k=v&...` query string `secret.get` expects (it is appended
    * verbatim after `?` — `implementation/Client/HTTPClient/secret.js`). The `metadata.` prefix is the Dapr HTTP API
    * convention the SDK's own `createHTTPQueryParam` uses elsewhere.
    */
  private def metadataQuery(metadata: Map[MetadataKey, MetadataValue]): Option[String] =
    if metadata.isEmpty then None
    else
      Some(
        metadata.iterator
          .map { case (k, v) =>
            s"metadata.${js.URIUtils.encodeURIComponent(k.value)}=${js.URIUtils.encodeURIComponent(v.value)}"
          }
          .mkString("&"),
      )

  /** View a `{key: value}` JS response object as a Scala string map, dropping non-string values. */
  private def toStringMap(v: js.Any): Map[String, String] =
    if isAbsent(v) then Map.empty
    else
      val dict = v.asInstanceOf[js.Dictionary[js.Any]]
      // WHAT: asInstanceOf views a parsed JSON value as a dictionary.
      // WHY: the SDK types secret responses as `object`; property enumeration needs the dictionary view.
      // WHY SAFE: js.Dictionary is a zero-cost view of any JS object; each value is pattern-matched for
      // String below before being trusted, so a non-object/non-string payload degrades to an empty/smaller
      // map rather than a ClassCastException.
      dict.iterator.flatMap { case (k, value) =>
        (value: Any) match
          case s: String => Some(k -> s)
          case _         => None
      }.toMap

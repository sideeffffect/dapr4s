//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.scalajs.js
import scala.scalajs.js.typedarray.{Int8Array, Uint8Array}
import typings.daprDapr.typesCryptoRequestsMod.{DecryptRequest, EncryptRequest}
import typings.node.bufferMod.global.Buffer
import typings.std.ArrayBufferLike

@scala.caps.assumeSafe
private[internal] final class CryptoCapabilityImpl(
    scope: DaprCapabilityImpl,
    val componentName: CryptoComponentName,
) extends CryptoCapability:

  import CryptoCapabilityImpl.*

  // Both operations go through scope.grpcClient (the lazily-created gRPC-protocol DaprClient):
  // crypto is gRPC-only in the JS SDK — the HTTP implementation throws HTTPNotSupportedError.
  // The buffered encrypt/decrypt overload is used: passing the payload as an ArrayBufferView makes
  // the SDK collect the response stream into one Buffer (implementation/Client/GRPCClient/crypto.js
  // processStream), the same whole-payload semantics as the JVM impl's Flux collectBytes().

  def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm): ArraySeq[Byte] =
    val request = EncryptRequest(
      componentName = componentName.value,
      keyName = keyName.value,
      keyWrapAlgorithm = toJsKeyWrapAlgorithm(algorithm),
    )
    val result = JsAwait.await(scope.grpcClient.crypto.encrypt(toInt8Array(plaintext), request))
    fromBuffer(result)

  // The ciphertext embeds a reference to the wrapping key, so decryption needs only the component.
  def decrypt(ciphertext: ArraySeq[Byte]): ArraySeq[Byte] =
    val request = DecryptRequest(componentName = componentName.value)
    val result = JsAwait.await(scope.grpcClient.crypto.decrypt(toInt8Array(ciphertext), request))
    fromBuffer(result)

@scala.caps.assumeSafe
private object CryptoCapabilityImpl:

  import typings.daprDapr.daprDaprStrings

  /** The SDK's `keyWrapAlgorithm` type: ScalablyTyped's rendering of the TS string-literal union on `EncryptRequest`
    * (`types/crypto/Requests.ts`).
    */
  private type JsKeyWrapAlgorithm = daprDaprStrings.A256KW | daprDaprStrings.A128CBC | daprDaprStrings.A192CBC |
    daprDaprStrings.A256CBC | daprDaprStrings.`RSA-OAEP-256` | daprDaprStrings.AES | daprDaprStrings.RSA

  /** WHAT: asInstanceOf conjuring the SDK's `keyWrapAlgorithm` string-literal union from the dapr4s value.
    *
    * WHY: the TypeScript type is a closed union of algorithm name literals (`"A256KW" | "A128CBC" | ... | "RSA"`),
    * which ScalablyTyped renders as a union of phantom string traits no plain `String` conforms to. dapr4s's
    * [[KeyWrapAlgorithm]] is deliberately open (an opaque String): the set of valid algorithms is a property of the
    * configured crypto component, not of the client — the Java SDK models it as a plain string too.
    *
    * WHY SAFE: a TS string-literal union is erased to the string itself at runtime; the SDK passes the value verbatim
    * into the protobuf request (`implementation/Client/GRPCClient/crypto.js`), and the sidecar/component validates it —
    * an unsupported name fails the call exactly as it does on the JVM.
    */
  private def toJsKeyWrapAlgorithm(algorithm: KeyWrapAlgorithm): JsKeyWrapAlgorithm =
    algorithm.value.asInstanceOf[JsKeyWrapAlgorithm]

  private def toInt8Array(bytes: ArraySeq[Byte]): Int8Array =
    val arr = bytes.toArray
    val typed = new Int8Array(arr.length)
    var i = 0
    while i < arr.length do
      typed(i) = arr(i)
      i += 1
    typed

  private def fromBuffer(buffer: Buffer[ArrayBufferLike]): ArraySeq[Byte] =
    // The SDK returns a Node Buffer (a Uint8Array subclass, possibly a view into a larger pool
    // allocation, hence the byteOffset-respecting copy). Bytes are copied out into a fresh
    // Array[Byte]; unsafeWrapArray is then safe because the array never escapes.
    //
    // WHAT: asInstanceOf viewing the ScalablyTyped Buffer as the Scala.js-native js.typedarray.Uint8Array.
    // WHY: ST's Buffer extends typings.std.Uint8Array, a structural re-typing of the ECMAScript class that
    // exposes no element access (no @JSBracketAccess member), so the bytes cannot be read through the ST type.
    // WHY SAFE: a Node Buffer IS an instance of the runtime Uint8Array class (Buffer extends Uint8Array is the
    // documented Node contract), so the erased cast only switches to Scala.js's first-class typed-array view of
    // the very same object.
    val typed = buffer.asInstanceOf[Uint8Array]
    val out = new Array[Byte](typed.length)
    var i = 0
    while i < typed.length do
      out(i) = typed(i).toByte
      i += 1
    ArraySeq.unsafeWrapArray(out)

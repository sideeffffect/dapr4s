//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.scalajs.js.typedarray.{Int8Array, Uint8Array}

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
    val request = new facade.EncryptRequest(
      componentName = componentName.value,
      keyName = keyName.value,
      keyWrapAlgorithm = algorithm.value,
    )
    val result = JsAwait.await(scope.grpcClient.crypto.encrypt(toInt8Array(plaintext), request))
    fromUint8Array(result)

  // The ciphertext embeds a reference to the wrapping key, so decryption needs only the component.
  def decrypt(ciphertext: ArraySeq[Byte]): ArraySeq[Byte] =
    val request = new facade.DecryptRequest(componentName = componentName.value)
    val result = JsAwait.await(scope.grpcClient.crypto.decrypt(toInt8Array(ciphertext), request))
    fromUint8Array(result)

@scala.caps.assumeSafe
private object CryptoCapabilityImpl:

  private def toInt8Array(bytes: ArraySeq[Byte]): Int8Array =
    val arr = bytes.toArray
    val typed = new Int8Array(arr.length)
    var i = 0
    while i < arr.length do
      typed(i) = arr(i)
      i += 1
    typed

  private def fromUint8Array(buffer: Uint8Array): ArraySeq[Byte] =
    // The SDK returns a Node Buffer (a Uint8Array subclass, possibly a view into a larger pool
    // allocation, hence the byteOffset-respecting copy). Bytes are copied out into a fresh
    // Array[Byte]; unsafeWrapArray is then safe because the array never escapes.
    val out = new Array[Byte](buffer.length)
    var i = 0
    while i < buffer.length do
      out(i) = buffer(i).toByte
      i += 1
    ArraySeq.unsafeWrapArray(out)

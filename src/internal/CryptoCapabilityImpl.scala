package dapr4s.internal

import dapr4s.*
import io.dapr.client.DaprPreviewClient
import io.dapr.client.domain.{DecryptRequestAlpha1, EncryptRequestAlpha1}
import reactor.core.publisher.Flux
import scala.collection.immutable.ArraySeq
import FluxOps.*

@scala.caps.assumeSafe
private[dapr4s] final class CryptoCapabilityImpl(
    scope: DaprCapabilityImpl,
    val componentName: CryptoComponentName,
) extends CryptoCapability:

  // The concrete DaprClient (AbstractDaprClient) implements DaprPreviewClient too, so the single
  // client built by Dapr.run carries the alpha crypto API without a second channel.
  private def preview: DaprPreviewClient = scope.client.asInstanceOf[DaprPreviewClient]

  def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm): ArraySeq[Byte] =
    val req = new EncryptRequestAlpha1(
      componentName.value,
      Flux.just(plaintext.toArray),
      keyName.value,
      algorithm.value,
    )
    preview.encrypt(req).collectBytes()

  // The ciphertext embeds a reference to the wrapping key, so decryption needs only the component.
  def decrypt(ciphertext: ArraySeq[Byte]): ArraySeq[Byte] =
    val req = new DecryptRequestAlpha1(componentName.value, Flux.just(ciphertext.toArray))
    preview.decrypt(req).collectBytes()

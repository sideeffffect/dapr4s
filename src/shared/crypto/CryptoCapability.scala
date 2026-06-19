package dapr4s.crypto

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Accessor (rung 2) for crypto components: an "any component" handle obtained argument-less via
  * [[DaprCapability.crypto]], whose [[apply]] narrows to a [[CryptoCapability]] bound to one component.
  */
@scala.caps.assumeSafe
trait AccessCryptoCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[CryptoCapability]] for the named crypto component. */
  def apply(componentName: CryptoComponentName): CryptoCapability^{this}

/** Capability for DAPR cryptography operations against a named crypto component.
  *
  * Encryption wraps a freshly generated data encryption key with the named component key using `algorithm`; decryption
  * reads the key reference embedded in the ciphertext, so it needs only the ciphertext and the component. Payloads are
  * immutable `ArraySeq[Byte]`; the `*String` helpers encode/decode text as UTF-8.
  *
  * Acquired via [[DaprCapability.crypto]].
  */
@scala.caps.assumeSafe
trait CryptoCapability extends scala.caps.ExclusiveCapability:
  val componentName: CryptoComponentName

  /** Encrypt `plaintext`, wrapping the data key with the component key `keyName` via `algorithm`. */
  def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm): ArraySeq[Byte]

  /** Decrypt `ciphertext` previously produced by [[encrypt]] against the same component. */
  def decrypt(ciphertext: ArraySeq[Byte]): ArraySeq[Byte]

  /** Encrypt a UTF-8 string. The returned bytes are the raw ciphertext, suitable for [[decryptString]]. */
  def encryptString(keyName: CryptoKeyName, plaintext: String, algorithm: KeyWrapAlgorithm): ArraySeq[Byte] =
    encrypt(keyName, Charsets.encodeString(plaintext, Charsets.Utf8), algorithm)

  /** Decrypt ciphertext into a UTF-8 string. */
  def decryptString(ciphertext: ArraySeq[Byte]): String =
    new String(decrypt(ciphertext).toArray, Charsets.Utf8)

/** Companion-object API for [[CryptoCapability]].
  *
  * Forwards to the `CryptoCapability` in the enclosing `using` context:
  * {{{
  *   def seal(secret: String)(using CryptoCapability): ArraySeq[Byte] =
  *     CryptoCapability.encryptString(CryptoKeyName("rsa-key"), secret, KeyWrapAlgorithm.Rsa)
  * }}}
  */
@scala.caps.assumeSafe
object CryptoCapability:
  def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm)(using
      cap: CryptoCapability,
  ): ArraySeq[Byte] =
    cap.encrypt(keyName, plaintext, algorithm)
  def decrypt(ciphertext: ArraySeq[Byte])(using cap: CryptoCapability): ArraySeq[Byte] =
    cap.decrypt(ciphertext)

  /** Encrypt a UTF-8 string. The returned bytes are the raw ciphertext, suitable for [[decryptString]]. */
  def encryptString(keyName: CryptoKeyName, plaintext: String, algorithm: KeyWrapAlgorithm)(using
      cap: CryptoCapability,
  ): ArraySeq[Byte] =
    cap.encryptString(keyName, plaintext, algorithm)

  /** Decrypt ciphertext into a UTF-8 string. */
  def decryptString(ciphertext: ArraySeq[Byte])(using cap: CryptoCapability): String =
    cap.decryptString(ciphertext)

package dapr4s.crypto

import dapr4s.*
import scala.collection.immutable.ArraySeq

/** Universal capability for DAPR cryptography: reaches **any** crypto component, so every operation names its
  * component.
  *
  * Obtained via [[DaprCapability.crypto]]. Narrow to one component with [[apply]] (`crypto(componentName)`), which
  * yields a [[CryptoCapability.Instance]].
  *
  * Encryption wraps a freshly generated data encryption key with the named component key using `algorithm`; decryption
  * reads the key reference embedded in the ciphertext, so it needs only the ciphertext and the component. Payloads are
  * immutable `ArraySeq[Byte]`; the `*String` helpers encode/decode text as UTF-8.
  */
@scala.caps.assumeSafe
trait CryptoCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to the named crypto component. */
  def apply(componentName: CryptoComponentName): CryptoCapability.Instance^{this}

  /** Encrypt `plaintext` with the named component; see [[CryptoCapability.Instance.encrypt]]. */
  def encrypt(
      componentName: CryptoComponentName,
      keyName: CryptoKeyName,
      plaintext: ArraySeq[Byte],
      algorithm: KeyWrapAlgorithm,
  ): ArraySeq[Byte]

  /** Decrypt `ciphertext` with the named component; see [[CryptoCapability.Instance.decrypt]]. */
  def decrypt(componentName: CryptoComponentName, ciphertext: ArraySeq[Byte]): ArraySeq[Byte]

  /** Encrypt a UTF-8 string. The returned bytes are the raw ciphertext, suitable for [[decryptString]]. */
  def encryptString(
      componentName: CryptoComponentName,
      keyName: CryptoKeyName,
      plaintext: String,
      algorithm: KeyWrapAlgorithm,
  ): ArraySeq[Byte] =
    encrypt(componentName, keyName, Charsets.encodeString(plaintext, Charsets.Utf8), algorithm)

  /** Decrypt ciphertext into a UTF-8 string. */
  def decryptString(componentName: CryptoComponentName, ciphertext: ArraySeq[Byte]): String =
    new String(decrypt(componentName, ciphertext).toArray, Charsets.Utf8)

/** The [[CryptoCapability]] family: the component-bound [[CryptoCapability.Instance]] and the companion-object
  * forwarders for both interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def seal(secret: String)(using CryptoCapability.Instance): ArraySeq[Byte] =
  *     CryptoCapability.Instance.encryptString(CryptoKeyName("rsa-key"), secret, KeyWrapAlgorithm.Rsa)
  * }}}
  */
@scala.caps.assumeSafe
object CryptoCapability:

  /** Capability bound to one named crypto component. Obtained via `crypto(componentName)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The crypto component this capability is bound to. */
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

  /** Forwarders for [[CryptoCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm)(using
        cap: Instance,
    ): ArraySeq[Byte] =
      cap.encrypt(keyName, plaintext, algorithm)
    def decrypt(ciphertext: ArraySeq[Byte])(using cap: Instance): ArraySeq[Byte] =
      cap.decrypt(ciphertext)

    /** Encrypt a UTF-8 string. The returned bytes are the raw ciphertext, suitable for [[decryptString]]. */
    def encryptString(keyName: CryptoKeyName, plaintext: String, algorithm: KeyWrapAlgorithm)(using
        cap: Instance,
    ): ArraySeq[Byte] =
      cap.encryptString(keyName, plaintext, algorithm)

    /** Decrypt ciphertext into a UTF-8 string. */
    def decryptString(ciphertext: ArraySeq[Byte])(using cap: Instance): String =
      cap.decryptString(ciphertext)

  /** Narrow the universal capability in scope to one component and return it. */
  def instance(componentName: CryptoComponentName)(using cap: CryptoCapability): Instance^{cap} =
    cap.apply(componentName)
  def encrypt(
      componentName: CryptoComponentName,
      keyName: CryptoKeyName,
      plaintext: ArraySeq[Byte],
      algorithm: KeyWrapAlgorithm,
  )(using cap: CryptoCapability): ArraySeq[Byte] =
    cap.encrypt(componentName, keyName, plaintext, algorithm)
  def decrypt(componentName: CryptoComponentName, ciphertext: ArraySeq[Byte])(using
      cap: CryptoCapability,
  ): ArraySeq[Byte] =
    cap.decrypt(componentName, ciphertext)

  /** Encrypt a UTF-8 string. The returned bytes are the raw ciphertext, suitable for [[decryptString]]. */
  def encryptString(
      componentName: CryptoComponentName,
      keyName: CryptoKeyName,
      plaintext: String,
      algorithm: KeyWrapAlgorithm,
  )(using cap: CryptoCapability): ArraySeq[Byte] =
    cap.encryptString(componentName, keyName, plaintext, algorithm)

  /** Decrypt ciphertext into a UTF-8 string. */
  def decryptString(componentName: CryptoComponentName, ciphertext: ArraySeq[Byte])(using
      cap: CryptoCapability,
  ): String =
    cap.decryptString(componentName, ciphertext)

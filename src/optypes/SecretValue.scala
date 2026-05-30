package dapr4s

import com.fasterxml.jackson.databind.ObjectMapper
import unsafeExceptions.canThrowAny

/** The plaintext value of a Dapr secret.
  *
  * Returned by [[SecretsCapability.get]] and [[SecretsCapability.getBulk]]. Wrapping the value in a distinct type
  * prevents accidental confusion with other string-typed values and makes leakage (e.g. into logs) more visible at call
  * sites.
  *
  * @see
  *   [[SecretsCapability.get]], [[SecretsCapability.getBulk]]
  */
opaque type SecretValue = String

@scala.caps.assumeSafe
object SecretValue:
  private val mapper = new ObjectMapper()
  def apply(value: String): SecretValue = value
  extension (sv: SecretValue) def value: String = sv
  given JsonCodec[SecretValue] with
    def encode(value: SecretValue): String = mapper.writeValueAsString(value: String)
    def decode(json: String | Null): Either[JsonDecodeException, SecretValue] =
      if json == null then Left(JsonDecodeException("null input"))
      else
        try Right(SecretValue(mapper.readValue(json, classOf[String])))
        catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

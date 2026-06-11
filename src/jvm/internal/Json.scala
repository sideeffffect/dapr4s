//> using target.platform "jvm"
package dapr4s.internal

import com.fasterxml.jackson.databind.ObjectMapper

/** Single shared Jackson mapper for internal Dapr-protocol plumbing (CloudEvent envelopes, actor state bodies, workflow
  * inputs, etc.) — distinct from user-supplied [[dapr4s.JsonCodec]] instances.
  *
  * An `ObjectMapper` is fully thread-safe once configured, and this instance is never reconfigured after creation, so
  * all read/write operations (`readTree`, `writeValueAsString`, `createObjectNode`, `readValue`, ...) are safe to call
  * concurrently — including from the virtual-thread-per-request server.
  */
@scala.caps.assumeSafe
private[internal] object Json:
  val mapper: ObjectMapper = new ObjectMapper()

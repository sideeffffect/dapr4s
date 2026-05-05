package dapr.safe

import language.experimental.safe

/** MIME type of a data payload.
  *
  * Must not be empty. Common values include `"application/json"` and `"text/plain"`. Appears in the `datacontenttype`
  * field of a [[CloudEvent]] and may be used by bindings or service invocation to set the HTTP `Content-Type` header.
  */
opaque type ContentType = String
object ContentType:
  def apply(s: String): ContentType =
    require(s.nonEmpty, "ContentType must not be empty")
    s
  extension (ct: ContentType) def value: String = ct

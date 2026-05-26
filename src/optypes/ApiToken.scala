package dapr4s

import language.experimental.safe

/** Dapr sidecar API authentication token.
  *
  * Must not be empty. Set via the `DAPR_API_TOKEN` environment variable on the sidecar process. The same token must be
  * provided when constructing the client so that every request carries the correct `dapr-api-token` header.
  */
opaque type ApiToken = String
object ApiToken:
  def apply(s: String): ApiToken =
    require(s.nonEmpty, "ApiToken must not be empty")
    s
  extension (t: ApiToken) def value: String = t

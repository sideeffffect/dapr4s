package dapr4s

import language.experimental.safe

/** The Dapr application identifier.
  *
  * Must not be empty. Corresponds to the `--app-id` CLI flag (or `APP_ID` environment variable) given to the sidecar at
  * startup. Used by [[InvokeCapability]] to route calls to the correct remote application.
  */
opaque type AppId = String
object AppId:
  def apply(s: String): AppId =
    require(s.nonEmpty, "AppId must not be empty")
    s
  extension (n: AppId) def value: String = n

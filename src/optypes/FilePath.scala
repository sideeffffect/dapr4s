package dapr.safe

/** An absolute or relative filesystem path.
  *
  * Used for TLS certificate and key paths in [[SidecarConfig]].
  *
  * @see
  *   [[SidecarConfig.grpcTlsCertPath]], [[SidecarConfig.grpcTlsKeyPath]], [[SidecarConfig.grpcTlsCaPath]]
  */
opaque type FilePath = String

@scala.caps.assumeSafe
object FilePath:
  def apply(path: String): FilePath = path
  extension (fp: FilePath) def value: String = fp

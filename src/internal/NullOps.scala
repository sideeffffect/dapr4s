package dapr.safe.internal

/** Null-safety helpers for Java interop under Scala 3 explicit nulls. */
@scala.caps.assumeSafe
private[internal] object NullOps:

  extension [T <: AnyRef](x: T | Null) inline def toOption: Option[T] = if x == null then None else Some(x)

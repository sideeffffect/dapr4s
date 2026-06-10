//> using target.platform "jvm"
package dapr4s.internal

import scala.collection.immutable.ArraySeq
import MonoOps.*

/** Reactor `Flux` ↔ virtual-thread bridge for byte streams. See [[FluxOps.collectBytes]]. */
@scala.caps.assumeSafe
private[internal] object FluxOps:

  extension (flux: reactor.core.publisher.Flux[Array[Byte]])

    /** Block the calling thread until the [[reactor.core.publisher.Flux]] of byte chunks completes, concatenating all
      * emitted chunks into a single immutable [[ArraySeq]].
      *
      * Uses [[MonoOps.awaitResult]] under the hood (via `collectList`), so it carries the same virtual-thread-friendly
      * blocking semantics as the rest of the library.
      */
    def collectBytes(): ArraySeq[Byte] =
      val list = flux.collectList().awaitResult()
      if list == null then ArraySeq.empty[Byte]
      else
        val builder = ArraySeq.newBuilder[Byte]
        list.forEach(chunk => if chunk != null then builder ++= ArraySeq.unsafeWrapArray(chunk))
        builder.result()

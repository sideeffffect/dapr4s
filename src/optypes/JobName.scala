package dapr4s

import language.experimental.safe

/** Name of a Dapr job.
  *
  * Must not be empty. Used as the identifier when scheduling, fetching, or deleting a job via [[JobsCapability]], and
  * as the route key for the inbound trigger the sidecar delivers to a [[JobRoute]] (`POST /job/<name>`).
  */
opaque type JobName = String
object JobName:
  def apply(s: String): JobName =
    require(s.nonEmpty, "JobName must not be empty")
    s
  extension (n: JobName) def value: String = n

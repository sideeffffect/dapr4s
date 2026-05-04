package dapr.safe

import language.experimental.safe

// Opaque domain types — keep separate from Models.scala to avoid a
// CC compile-time exponential blowup that occurs when many opaque-String
// types with `.value` extensions accumulate in a single safe-mode file.
// If this file exceeds ~25 types, split it further.

opaque type StoreName = String
object StoreName:
  def apply(s: String): StoreName =
    require(s.nonEmpty, "StoreName must not be empty")
    s
  extension (n: StoreName) def value: String = n

opaque type PubSubName = String
object PubSubName:
  def apply(s: String): PubSubName =
    require(s.nonEmpty, "PubSubName must not be empty")
    s
  extension (n: PubSubName) def value: String = n

opaque type Topic = String
object Topic:
  def apply(s: String): Topic =
    require(s.nonEmpty, "Topic must not be empty")
    s
  extension (n: Topic) def value: String = n

opaque type AppId = String
object AppId:
  def apply(s: String): AppId =
    require(s.nonEmpty, "AppId must not be empty")
    s
  extension (n: AppId) def value: String = n

opaque type SecretStoreName = String
object SecretStoreName:
  def apply(s: String): SecretStoreName =
    require(s.nonEmpty, "SecretStoreName must not be empty")
    s
  extension (n: SecretStoreName) def value: String = n

opaque type ConfigStoreName = String
object ConfigStoreName:
  def apply(s: String): ConfigStoreName =
    require(s.nonEmpty, "ConfigStoreName must not be empty")
    s
  extension (n: ConfigStoreName) def value: String = n

opaque type BindingName = String
object BindingName:
  def apply(s: String): BindingName =
    require(s.nonEmpty, "BindingName must not be empty")
    s
  extension (n: BindingName) def value: String = n

opaque type ETag = String
object ETag:
  def apply(s: String): ETag = s
  extension (n: ETag) def value: String = n

opaque type StateQuery = String
object StateQuery:
  def apply(query: String): StateQuery = query
  extension (s: StateQuery) def value: String = s

opaque type StateKey = String
object StateKey:
  def apply(s: String): StateKey = s
  extension (k: StateKey) def value: String = k

opaque type MethodName = String
object MethodName:
  def apply(s: String): MethodName =
    require(s.nonEmpty, "MethodName must not be empty")
    s
  extension (n: MethodName) def value: String = n

opaque type BindingOperation = String
object BindingOperation:
  def apply(s: String): BindingOperation =
    require(s.nonEmpty, "BindingOperation must not be empty")
    s
  extension (n: BindingOperation) def value: String = n

opaque type SecretKey = String
object SecretKey:
  def apply(s: String): SecretKey = s
  extension (k: SecretKey) def value: String = k

opaque type ConfigKey = String
object ConfigKey:
  def apply(s: String): ConfigKey = s
  extension (k: ConfigKey) def value: String = k

opaque type LockResourceId = String
object LockResourceId:
  def apply(s: String): LockResourceId =
    require(s.nonEmpty, "LockResourceId must not be empty")
    s
  extension (id: LockResourceId) def value: String = id

opaque type LockOwner = String
object LockOwner:
  def apply(s: String): LockOwner =
    require(s.nonEmpty, "LockOwner must not be empty")
    s
  extension (o: LockOwner) def value: String = o

opaque type BulkEntryId = String
object BulkEntryId:
  def apply(s: String): BulkEntryId = s
  extension (id: BulkEntryId) def value: String = id

opaque type Route = String
object Route:
  def apply(s: String): Route =
    require(s.nonEmpty, "Route must not be empty")
    s
  extension (r: Route) def value: String = r

opaque type ActorType = String
object ActorType:
  def apply(s: String): ActorType =
    require(s.nonEmpty, "ActorType must not be empty")
    s
  extension (t: ActorType) def value: String = t

opaque type ActorId = String
object ActorId:
  def apply(s: String): ActorId = s
  extension (id: ActorId) def value: String = id

opaque type ReminderName = String
object ReminderName:
  def apply(s: String): ReminderName =
    require(s.nonEmpty, "ReminderName must not be empty")
    s
  extension (n: ReminderName) def value: String = n

opaque type TimerName = String
object TimerName:
  def apply(s: String): TimerName =
    require(s.nonEmpty, "TimerName must not be empty")
    s
  extension (n: TimerName) def value: String = n

opaque type WorkflowName = String
object WorkflowName:
  def apply(s: String): WorkflowName =
    require(s.nonEmpty, "WorkflowName must not be empty")
    s
  extension (n: WorkflowName) def value: String = n

opaque type WorkflowInstanceId = String
object WorkflowInstanceId:
  def apply(s: String): WorkflowInstanceId = s
  extension (id: WorkflowInstanceId) def value: String = id

opaque type EventName = String
object EventName:
  def apply(s: String): EventName =
    require(s.nonEmpty, "EventName must not be empty")
    s
  extension (n: EventName) def value: String = n

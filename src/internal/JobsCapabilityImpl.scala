package dapr4s.internal

import dapr4s.*
import io.dapr.client.domain.{DeleteJobRequest, GetJobRequest, ScheduleJobRequest, JobSchedule as JJobSchedule}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[internal] final class JobsCapabilityImpl(
    scope: DaprCapabilityImpl,
) extends JobsCapability:

  import JobsCapabilityImpl.*

  def schedule[T: JsonCodec](
      name: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  ): Unit =
    val req = new ScheduleJobRequest(name.value, toJavaSchedule(schedule)).setData(encodeData(data))
    dueTime.foreach(t => req.setDueTime(t))
    repeats.foreach(r => req.setRepeat(r))
    ttl.foreach(t => req.setTtl(t))
    scope.client.scheduleJob(req).awaitResult(): Unit

  def scheduleOnce[T: JsonCodec](
      name: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  ): Unit =
    val req = new ScheduleJobRequest(name.value, dueTime).setData(encodeData(data))
    ttl.foreach(t => req.setTtl(t))
    scope.client.scheduleJob(req).awaitResult(): Unit

  def get(name: JobName): Option[JobDetails] =
    scope.client
      .getJob(new GetJobRequest(name.value))
      .awaitResult()
      .toOption
      .map { resp =>
        JobDetails(
          name = JobName(resp.getName.nn),
          data = Option(resp.getData).map(b => SerializedJson(new String(b, UTF_8))),
          scheduleExpression = Option(resp.getSchedule).map(_.getExpression.nn),
          dueTime = Option(resp.getDueTime),
          repeats = Option(resp.getRepeats).map(_.intValue),
          ttl = Option(resp.getTtl),
        )
      }

  def delete(name: JobName): Unit =
    scope.client.deleteJob(new DeleteJobRequest(name.value)).awaitResult(): Unit

@scala.caps.assumeSafe
private object JobsCapabilityImpl:
  private def encodeData[T: JsonCodec](data: T): Array[Byte] =
    summon[JsonCodec[T]].encode(data).getBytes(UTF_8).nn

  private def toJavaSchedule(s: JobSchedule): JJobSchedule = s match
    case JobSchedule.Cron(expr)    => JJobSchedule.fromString(expr)
    case JobSchedule.Every(period) => JJobSchedule.fromPeriod(JDuration.ofNanos(period.toNanos))
    case JobSchedule.Daily         => JJobSchedule.daily()
    case JobSchedule.Hourly        => JJobSchedule.hourly()
    case JobSchedule.Weekly        => JJobSchedule.weekly()
    case JobSchedule.Monthly       => JJobSchedule.monthly()
    case JobSchedule.Yearly        => JJobSchedule.yearly()

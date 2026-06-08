package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.JobsCapability]] facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to a [[dapr4s.JobName]].
  *
  *   - returns `Unit` with a `schedule: JobSchedule` parameter → [[dapr4s.JobsCapability.schedule]] (plus optional
  *     `dueTime: Option[Instant]`, `repeats: Option[Int]`, `ttl: Option[Instant]`)
  *   - returns `Unit` with a `dueTime: Instant` parameter (no schedule) → [[dapr4s.JobsCapability.scheduleOnce]] (plus
  *     optional `ttl: Option[Instant]`)
  *   - returns `Option[JobDetails]` with no value parameters → [[dapr4s.JobsCapability.get]]
  */
@scala.caps.assumeSafe
object Jobs:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the [[dapr4s.JobName]] it schedules or reads — `def nightlyReport` addresses the job
    * `"nightlyReport"` — overridable per method with [[name `@name`]]. The job is identified by this name alone;
    * whether the method schedules (recurring), schedules once, or gets is chosen by its parameters and return type, so
    * `derive` takes no argument.
    *
    * {{{
    *   trait ReportJobs:
    *     def nightlyReport(spec: ReportSpec, schedule: JobSchedule)(using JobsCapability, JsonCodec[ReportSpec]): Unit
    *     @name("nightlyReport") def status()(using JobsCapability): Option[JobDetails]
    *   lazy val ReportJobs: ReportJobs = Jobs.derive[ReportJobs]
    *
    *   DaprCapability.jobs {
    *     ReportJobs.nightlyReport(spec, JobSchedule.Every(1.day)) // schedules job "nightlyReport"
    *     ReportJobs.status()                                      // gets that same job "nightlyReport"
    *   }
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Jobs"
    val capTpe = TypeRepr.of[JobsCapability]
    val scheduleTpe = TypeRepr.of[JobSchedule]
    val instantTpe = TypeRepr.of[java.time.Instant]
    val instantOptTpe = TypeRepr.of[Option[java.time.Instant]]
    val intOptTpe = TypeRepr.of[Option[Int]]
    val jobDetailsOpt = TypeRepr.of[Option[JobDetails]]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a JobsCapability."))
        .asExprOf[JobsCapability]

      def codecFor(arg: TypeRepr): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec for the payload (JsonCodec[${arg.show}])."))

      def named(name: String, expectTpe: TypeRepr): Option[Term] =
        values.collectFirst {
          case (n, r, t, _) if n == name =>
            if !(t =:= expectTpe) then fail(s"parameter `$name` must have type ${expectTpe.show}.")
            r
        }

      val nm = MacroSupport.wireName(origSym)
      val nameExpr = '{ JobName(${ Expr(nm) }) }

      val scheduleRef = named("schedule", scheduleTpe)
      val isGet = resTpe =:= jobDetailsOpt

      if isGet then
        if values.nonEmpty then fail("a job getter (Option[JobDetails]) takes no value parameters.")
        '{ Forwarders.jobGet(${ capExpr }, ${ nameExpr }) }.asTerm
      else if !MacroSupport.isUnit(resTpe) then
        fail("a job scheduling method must return Unit; a job getter must return Option[JobDetails].")
      else
        // data is the first value parameter that is not one of the recognised knobs.
        val knobs = Set("schedule", "dueTime", "repeats", "ttl")
        val dataEntry =
          values.find(v => !knobs.contains(v._1)).getOrElse(fail("a job scheduling method needs a payload parameter."))
        val (_, dataRef, dataTpe, _) = dataEntry
        dataTpe.asType match
          case '[d] =>
            val dataCodec = codecFor(dataTpe).asExprOf[JsonCodec[d]]
            val ttlExpr = named("ttl", instantOptTpe).map(_.asExprOf[Option[java.time.Instant]]).getOrElse('{ None })
            scheduleRef match
              case Some(sched) =>
                val dueExpr =
                  named("dueTime", instantOptTpe).map(_.asExprOf[Option[java.time.Instant]]).getOrElse('{ None })
                val repeatsExpr = named("repeats", intOptTpe).map(_.asExprOf[Option[Int]]).getOrElse('{ None })
                '{
                  Forwarders.jobSchedule[d](
                    ${ capExpr },
                    ${ nameExpr },
                    ${ dataRef.asExprOf[d] },
                    ${ sched.asExprOf[JobSchedule] },
                    ${ dueExpr },
                    ${ repeatsExpr },
                    ${ ttlExpr },
                    ${ dataCodec },
                  )
                }.asTerm
              case None =>
                val dueRef = named("dueTime", instantTpe).getOrElse(
                  fail("a job scheduling method needs a `schedule: JobSchedule` or a `dueTime: Instant` parameter."),
                )
                '{
                  Forwarders.jobScheduleOnce[d](
                    ${ capExpr },
                    ${ nameExpr },
                    ${ dataRef.asExprOf[d] },
                    ${ dueRef.asExprOf[java.time.Instant] },
                    ${ ttlExpr },
                    ${ dataCodec },
                  )
                }.asTerm
    }

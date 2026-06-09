package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Server-side derivation of [[dapr4s.JobRoute]]s from a handler type.
  *
  * `derive[T]` turns each handler method of `T` (an `object` of handlers, or a class with a no-arg constructor) into a
  * [[dapr4s.JobRoute]]: the method name maps verbatim (override with [[name `@name`]]) to the [[dapr4s.JobName]] whose
  * trigger it answers (the sidecar POSTs to `/job/<name>` when the job fires), the single value parameter is the
  * decoded payload the job was scheduled with, and the method returns `Unit`. The handler's `using` capabilities/codecs
  * and the route's own codec are resolved from the ambient scope at the `derive` call site.
  *
  * This is the inbound (trigger) counterpart of the outbound [[Jobs]] scheduling facade: a job scheduled under name `X`
  * with payload `P` fires back into the app at the `JobRoute` for `X`, which decodes `P`. The [[deriveChecked]]
  * overload binds the two through the same `Contract` trait.
  *
  * {{{
  *   object JobHandlers:
  *     def nightlyReport(spec: ReportSpec): Unit = ...
  *     @name("cleanup") def onCleanup(req: CleanupRequest)(using Logger): Unit = ...
  *
  *   DaprApp(jobs = JobRoutes.derive[JobHandlers.type])
  * }}}
  */
@scala.caps.assumeSafe
object JobRoutes:

  /** Derive the [[dapr4s.JobRoute]]s exposed by handler type `T`.
    *
    * Each handler method's Scala name is the [[dapr4s.JobName]] whose trigger it answers — `def nightlyReport` handles
    * the firing of job `"nightlyReport"` — overridable per method with [[name `@name`]]. Each method takes the decoded
    * payload as its single value parameter and returns `Unit`.
    *
    * {{{
    *   object JobHandlers:
    *     def nightlyReport(spec: ReportSpec): Unit = ...
    *     @name("cleanup") def onCleanup(req: CleanupRequest): Unit = ...
    *
    *   // serves JobName("nightlyReport") and JobName("cleanup"):
    *   DaprApp(jobs = JobRoutes.derive[JobHandlers.type])
    * }}}
    */
  inline def derive[T]: List[JobRoute] = ${ deriveImpl[T] }

  /** Derive the [[dapr4s.JobRoute]]s of handler type `Impl`, '''checked''' against scheduling contract trait
    * `Contract`.
    *
    * Same result as [[derive]], but bound to the dual [[Jobs]] facade through the shared `Contract` trait: a `Contract`
    * scheduling method names a [[dapr4s.JobName]] and a payload, and the macro verifies that for every job the contract
    * schedules there is an `Impl` handler for the same job name decoding the same payload type. Matching is by job name
    * (the wire name, since the two sides name their Scala methods independently). `Contract` job getters (those
    * returning `Option[JobDetails]`, which carry no payload) are not triggers and are not checked. `Impl` stays a plain
    * handler: payload in, `Unit` out, free to take its own ambient `using` dependencies.
    *
    * {{{
    *   trait ReportJobs:
    *     def nightlyReport(spec: ReportSpec, schedule: JobSchedule)(using JobsCapability, JsonCodec[ReportSpec]): Unit
    *
    *   object ReportJobHandlers:
    *     def nightlyReport(spec: ReportSpec): Unit = ...
    *
    *   // checked against ReportJobs; serves JobName("nightlyReport"):
    *   DaprApp(jobs = JobRoutes.deriveChecked[ReportJobs, ReportJobHandlers.type])
    * }}}
    *
    * @see
    *   [[Jobs.derive]] — the dual scheduling facade derived from the same `Contract`.
    */
  inline def deriveChecked[Contract, Impl]: List[JobRoute] = ${ deriveCheckedImpl[Contract, Impl] }

  /** The scheduling knobs of a [[Jobs]] contract method, skipped when reading the job payload. */
  private val jobKnobs = Set("schedule", "dueTime", "repeats", "ttl")

  private def deriveImpl[T: Type](using Quotes): Expr[List[JobRoute]] =
    import quotes.reflect.*
    val engine = "JobRoutes"
    val inst = MacroSupport.instanceOf[T]
    val methods = MacroSupport.handlerMethods[T]
    if methods.isEmpty then
      report.errorAndAbort(s"$engine.derive: ${TypeRepr.of[T].typeSymbol.name} has no handler methods to derive.")
    Expr.ofList(methods.map(route(engine, inst, _)))

  private def deriveCheckedImpl[Contract: Type, Impl: Type](using Quotes): Expr[List[JobRoute]] =
    import quotes.reflect.*
    val engine = "JobRoutes"
    val inst = MacroSupport.instanceOf[Impl]
    val implName = TypeRepr.of[Impl].typeSymbol.name.stripSuffix("$")
    val handlers = MacroSupport.handlerMethods[Impl]

    // One route per job the contract schedules (getters carry no payload, so they are not triggers).
    val routes = MacroSupport.contractMethods[Contract](engine).flatMap { cm =>
      MacroSupport.bodyParamType(cm, jobKnobs).map { payload =>
        val jobName = MacroSupport.wireName(cm)
        val implM = MacroSupport.requireImplByWireName(engine, cm, jobName, handlers, implName, "job")
        MacroSupport.checkInOut(
          engine,
          cm,
          implName,
          Some(payload),
          TypeRepr.of[Unit],
          MacroSupport.valueParamType(implM),
          TypeRepr.of[Unit],
        )
        route(engine, inst, implM, Some(jobName))
      }
    }
    Expr.ofList(routes)

  /** Build one [[dapr4s.JobRoute]] from handler method `m` on `inst`. `wireOverride` supplies the [[dapr4s.JobName]]
    * when it must come from the contract rather than the handler itself.
    */
  private def route(using
      q: Quotes,
  )(
      engine: String,
      inst: q.reflect.Term,
      m: q.reflect.Symbol,
      wireOverride: Option[String] = None,
  ): Expr[JobRoute] =
    import q.reflect.*
    val nm = wireOverride.getOrElse(MacroSupport.wireName(m))
    val inTpe =
      MacroSupport.valueParamType(m).getOrElse(MacroSupport.fail(engine, m, "a job handler needs a payload parameter."))
    if !MacroSupport.isUnit(MacroSupport.resultTypeOf(m)) then
      MacroSupport.fail(engine, m, "a job handler must return Unit (job triggers are fire-and-forget).")
    inTpe.asType match
      case '[t] =>
        val handler = Lambda(
          Symbol.spliceOwner,
          MethodType(List("payload"))(_ => List(inTpe), _ => TypeRepr.of[Unit]),
          (lam, args) =>
            MacroSupport.callSummoning(engine, inst, m, Some(args.head.asInstanceOf[Term])).changeOwner(lam),
        ).asExprOf[t => Unit]
        val codec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[t]]).asExprOf[JsonCodec[t]]
        '{ Forwarders.jobRoute[t](JobName(${ Expr(nm) }), ${ handler }, ${ codec }) }

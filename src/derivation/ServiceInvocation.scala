package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.ServiceInvocationCapability]] client facades from a trait.
  *
  * Instead of hand-writing every remote call:
  * {{{
  *   ServiceInvocationCapability.invoke(appId, InvocationMethodName("double"), req)[CounterState]
  * }}}
  *
  * declare a trait whose methods describe the calls and let dapr4s implement it:
  * {{{
  *   trait GreetingService:
  *     def greet(
  *       req: GreetRequest,
  *       httpMethod: HttpMethod = HttpMethod.Post,
  *       metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  *     )(using ServiceInvocationCapability, JsonCodec[GreetRequest], JsonCodec[GreetResponse]): GreetResponse
  *
  *     def stats()(using ServiceInvocationCapability, JsonCodec[StatsResponse]): StatsResponse
  *
  *   val svc: GreetingService = ServiceInvocation.derive[GreetingService](AppId("greeting-service"))
  * }}}
  *
  * The derived instance captures only the [[dapr4s.AppId]] (a plain value), never a capability — capture-checking
  * forbids storing an `ExclusiveCapability`, so the `ServiceInvocationCapability` and the `JsonCodec`s arrive per call
  * via each method's `using` clause.
  *
  * '''Method contract''' (enforced at compile time):
  *   - The Scala method name is used verbatim as the [[dapr4s.InvocationMethodName]]; override per method with
  *     [[name `@name`]].
  *   - The first value parameter, if present, is the request body (its type is free). A method with no value parameters
  *     maps to the no-body `invoke` overload.
  *   - `httpMethod` and `metadata`, if present, must be named exactly `httpMethod` (type [[dapr4s.HttpMethod]]) and
  *     `metadata` (type `Map[MetadataKey, MetadataValue]`), with `httpMethod` before `metadata`. Either may be omitted;
  *     the macro then supplies `HttpMethod.Post` / `Map.empty`.
  *   - The `using` clause must provide a [[dapr4s.ServiceInvocationCapability]] and the required `JsonCodec`s
  *     (`JsonCodec[Resp]` always; `JsonCodec[Req]` when there is a body).
  */
object ServiceInvocation:

  /** Derive an implementation of trait `T` that routes its methods to `appId`. */
  inline def derive[T](appId: AppId): T = ${ deriveImpl[T]('appId) }

  /** Companion-object mixin sugar for [[derive]].
    *
    * Mix into a trait's companion to expose a `derive(appId)` factory without naming the trait twice at every call
    * site:
    *
    * {{{
    *   trait MyService:
    *     def double(req: IncrRequest)(using ServiceInvocationCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
    *   object MyService extends ServiceInvocation.Derived[MyService]
    *
    *   val svc = MyService.derive(AppId("doubler"))
    * }}}
    *
    * `derive` is `inline`, so it expands to the [[ServiceInvocation.derive]] engine at the call site — and, unlike a
    * macro-annotation-generated member, it is a genuine inherited member visible within the same compilation run.
    */
  trait Derived[T]:
    inline def derive(appId: AppId): T = ServiceInvocation.derive[T](appId)

  private def deriveImpl[T: Type](appId: Expr[AppId])(using Quotes): Expr[T] =
    import quotes.reflect.*

    val tRepr = TypeRepr.of[T]
    val tSym = tRepr.typeSymbol

    if !tSym.flags.is(Flags.Trait) then
      report.errorAndAbort(
        s"ServiceInvocation.derive expects a trait, but ${tSym.fullName} is not a trait.",
      )

    val httpMethodTpe = TypeRepr.of[HttpMethod]
    val metadataTpe = TypeRepr.of[Map[MetadataKey, MetadataValue]]
    val capTpe = TypeRepr.of[ServiceInvocationCapability]
    val jsonCodecSym = Symbol.requiredClass("dapr4s.JsonCodec")
    val nameAnnotTpe = TypeRepr.of[name]

    // Abstract members of the trait. Only methods are derivable.
    val absFields = tSym.declaredFields.filter(_.flags.is(Flags.Deferred))
    if absFields.nonEmpty then
      report.errorAndAbort(
        s"ServiceInvocation.derive: trait ${tSym.name} has abstract non-method members " +
          s"(${absFields.map(_.name).mkString(", ")}); only abstract methods can be derived.",
      )
    val absMethods = tSym.declaredMethods.filter(_.flags.is(Flags.Deferred))
    if absMethods.isEmpty then
      report.errorAndAbort(
        s"ServiceInvocation.derive: trait ${tSym.name} has no abstract methods to derive.",
      )

    // ---- helpers ------------------------------------------------------------

    /** Wire name: verbatim Scala name unless overridden by `@name("...")`. */
    def wireName(m: Symbol): String =
      m.annotations
        .collectFirst {
          case Apply(Select(New(tpt), _), List(Literal(StringConstant(s)))) if tpt.tpe =:= nameAnnotTpe => s
        }
        .getOrElse(m.name)

    /** Flatten a method type into its (paramType, isGiven) entries (in clause order) and the result type. */
    def collectParams(tpe: TypeRepr): (List[(TypeRepr, Boolean)], TypeRepr) =
      tpe match
        case mt: MethodType =>
          val (rest, res) = collectParams(mt.resType)
          (mt.paramTypes.map(t => (t, mt.isImplicit)) ++ rest, res)
        case pt: PolyType => collectParams(pt.resType)
        case other        => (Nil, other)

    /** Extract `X` from a `JsonCodec[X]` type, if it is one. */
    def jsonCodecArg(tpe: TypeRepr): Option[TypeRepr] =
      tpe.dealias match
        case AppliedType(tycon, List(arg)) if tycon.typeSymbol == jsonCodecSym => Some(arg)
        case _                                                                 => None

    // ---- class synthesis ----------------------------------------------------

    val clsName = tSym.name + "$Derived"

    // new method symbol -> original abstract method symbol
    var pairs: List[(Symbol, Symbol)] = Nil
    def decls(cls: Symbol): List[Symbol] =
      val syms = absMethods.map { m =>
        Symbol.newMethod(cls, m.name, tRepr.memberType(m), Flags.Override, Symbol.noSymbol)
      }
      pairs = syms.zip(absMethods)
      syms

    val clsSym = Symbol.newClass(
      Symbol.spliceOwner,
      clsName,
      parents = List(TypeRepr.of[Object], tRepr),
      decls = decls,
      selfType = None,
    )

    def buildBody(newSym: Symbol, origSym: Symbol, argss: List[List[Tree]]): Term =
      val mt = tRepr.memberType(origSym)
      val (clauseParams, _) = collectParams(mt)
      val resTpe = origSym.tree.asInstanceOf[DefDef].returnTpt.tpe

      val termSyms = newSym.paramSymss.flatten.filter(_.isTerm)
      val termRefs = argss.flatten.collect { case t: Term => t }

      // (symbol, ref, type, isGiven), in clause order
      val info =
        clauseParams.zip(termSyms).zip(termRefs).map { case (((tpe, isGiven), sym), ref) =>
          (sym, ref, tpe, isGiven)
        }
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)

      def fail(msg: String): Nothing =
        report.errorAndAbort(
          s"ServiceInvocation.derive: method `${origSym.name}` — $msg",
          origSym.pos.getOrElse(Position.ofMacroExpansion),
        )

      // Recognise the optional knobs by name + type + position.
      val httpRef = values.collectFirst {
        case (s, r, t, _) if s.name == "httpMethod" =>
          if !(t =:= httpMethodTpe) then fail("parameter `httpMethod` must have type HttpMethod.")
          (values.indexWhere(_._1 == s), r)
      }
      val metaRef = values.collectFirst {
        case (s, r, t, _) if s.name == "metadata" =>
          if !(t =:= metadataTpe) then fail("parameter `metadata` must have type Map[MetadataKey, MetadataValue].")
          (values.indexWhere(_._1 == s), r)
      }
      // First value param is the body, unless it is itself a knob (then there is no body).
      val bodyEntryOpt = values.headOption.filterNot(v => v._1.name == "httpMethod" || v._1.name == "metadata")

      // Reject any unexpected value parameters and enforce ordering.
      val knobNames = Set("httpMethod", "metadata")
      values.zipWithIndex.foreach { case ((s, _, _, _), idx) =>
        val isBody = bodyEntryOpt.exists(_._1 == s)
        if !isBody && !knobNames.contains(s.name) then
          fail(s"unexpected parameter `${s.name}`; only the request body, `httpMethod`, and `metadata` are allowed.")
        if isBody && idx != 0 then fail("the request body must be the first value parameter.")
      }
      (httpRef, metaRef) match
        case (Some((hi, _)), Some((mi, _))) if hi > mi =>
          fail("`httpMethod` must come before `metadata`.")
        case _ => ()

      val capRef = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a ServiceInvocationCapability."))

      def codecRefFor(arg: TypeRepr, role: String): Term =
        givens
          .collectFirst { case (_, r, t, _) if jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[$role] (JsonCodec[${arg.show}])."))

      val nm = wireName(origSym)
      val respCodec = codecRefFor(resTpe, "Resp")

      // Emit a single flat call to the runtime helper, passing the capability and codecs as
      // plain explicit arguments. No synthesised givens (which the compiler would lift and
      // capture into the enclosing class) and no by-hand reconstruction of invoke's clauses.
      val capExpr = capRef.asExprOf[ServiceInvocationCapability]

      resTpe.asType match
        case '[resp] =>
          val respCodecExpr = respCodec.asExprOf[JsonCodec[resp]]
          bodyEntryOpt match
            case None =>
              '{
                ServiceInvocationDerivationRuntime.invokeNoBody[resp](
                  ${ capExpr },
                  ${ appId },
                  InvocationMethodName(${ Expr(nm) }),
                  ${ respCodecExpr },
                )
              }.asTerm
            case Some((_, bodyRef, reqTpe, _)) =>
              val httpExpr =
                httpRef.map(_._2.asExprOf[HttpMethod]).getOrElse('{ HttpMethod.Post })
              val metaExpr =
                metaRef
                  .map(_._2.asExprOf[Map[MetadataKey, MetadataValue]])
                  .getOrElse('{ Map.empty[MetadataKey, MetadataValue] })
              reqTpe.asType match
                case '[req] =>
                  val reqCodecExpr = codecRefFor(reqTpe, "Req").asExprOf[JsonCodec[req]]
                  '{
                    ServiceInvocationDerivationRuntime.invokeBody[req, resp](
                      ${ capExpr },
                      ${ appId },
                      InvocationMethodName(${ Expr(nm) }),
                      ${ bodyRef.asExprOf[req] },
                      ${ httpExpr },
                      ${ metaExpr },
                      ${ reqCodecExpr },
                      ${ respCodecExpr },
                    )
                  }.asTerm

    val methodDefs: List[DefDef] =
      pairs.map { case (newSym, origSym) =>
        DefDef(newSym, argss => Some(buildBody(newSym, origSym, argss)))
      }

    val clsDef = ClassDef(clsSym, parents = List(TypeTree.of[Object], TypeTree.of[T]), body = methodDefs)
    val instance =
      Typed(
        Apply(Select(New(TypeIdent(clsSym)), clsSym.primaryConstructor), Nil),
        TypeTree.of[T],
      )
    Block(List(clsDef), instance).asExprOf[T]

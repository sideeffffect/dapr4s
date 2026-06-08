package dapr4s.derivation

import scala.quoted.*

/** Shared plumbing for the `*.derive` macros in this package.
  *
  * Every client-capability derivation has the same skeleton: validate that `T` is a trait of abstract methods,
  * synthesise a class that extends it, and implement each method by forwarding to a capability. [[deriveTrait]] owns
  * that skeleton; each capability supplies a `bodyFn` that builds one method body. The helpers ([[paramInfo]],
  * [[wireName]], [[jsonCodecArg]], [[resultTypeOf]]) cover the analysis every `bodyFn` needs.
  */
private[derivation] object MacroSupport:

  /** Build an instance of trait `T`, implementing each abstract method via `bodyFn`.
    *
    * `bodyFn` receives the original (abstract) method symbol, the synthesised override symbol, and the override's
    * parameter trees (as handed to `DefDef`), and returns the method body.
    */
  def deriveTrait[T: Type](engine: String)(using
      q: Quotes,
  )(
      bodyFn: (q.reflect.Symbol, q.reflect.Symbol, List[List[q.reflect.Tree]]) => q.reflect.Term,
  ): Expr[T] =
    import q.reflect.*

    val tRepr = TypeRepr.of[T]
    val tSym = tRepr.typeSymbol

    if !tSym.flags.is(Flags.Trait) then
      report.errorAndAbort(s"$engine.derive expects a trait, but ${tSym.fullName} is not a trait.")

    val absFields = tSym.declaredFields.filter(_.flags.is(Flags.Deferred))
    if absFields.nonEmpty then
      report.errorAndAbort(
        s"$engine.derive: trait ${tSym.name} has abstract non-method members " +
          s"(${absFields.map(_.name).mkString(", ")}); only abstract methods can be derived.",
      )

    val absMethods = tSym.declaredMethods.filter(_.flags.is(Flags.Deferred))
    if absMethods.isEmpty then
      report.errorAndAbort(s"$engine.derive: trait ${tSym.name} has no abstract methods to derive.")

    var pairs: List[(Symbol, Symbol)] = Nil
    def decls(cls: Symbol): List[Symbol] =
      val syms =
        absMethods.map(m => Symbol.newMethod(cls, m.name, tRepr.memberType(m), Flags.Override, Symbol.noSymbol))
      pairs = syms.zip(absMethods)
      syms

    val clsSym = Symbol.newClass(
      Symbol.spliceOwner,
      tSym.name + "$Derived",
      parents = List(TypeRepr.of[Object], tRepr),
      decls = decls,
      selfType = None,
    )

    val methodDefs = pairs.map { case (newSym, origSym) =>
      DefDef(newSym, argss => Some(bodyFn(origSym, newSym, argss)))
    }

    val clsDef = ClassDef(clsSym, parents = List(TypeTree.of[Object], TypeTree.of[T]), body = methodDefs)
    val instance = Typed(Apply(Select(New(TypeIdent(clsSym)), clsSym.primaryConstructor), Nil), TypeTree.of[T])
    Block(List(clsDef), instance).asExprOf[T]

  /** The explicit `@name("…")` override on a method, if present. */
  def nameOverride(using q: Quotes)(m: q.reflect.Symbol): Option[String] =
    import q.reflect.*
    val nameAnnotTpe = TypeRepr.of[name]
    m.annotations.collectFirst {
      case Apply(Select(New(tpt), _), List(Literal(StringConstant(s)))) if tpt.tpe =:= nameAnnotTpe => s
    }

  /** The wire name for a derived method: its Scala name verbatim, unless overridden by `@name`. */
  def wireName(using q: Quotes)(m: q.reflect.Symbol): String =
    nameOverride(m).getOrElse(m.name)

  /** The "name" a name-derived `derive` overload binds to: the type's `@name` override, else its simple Scala name.
    *
    * The trailing `$` of a module (`object Foo` ⇒ `Foo$`) is stripped, so `Foo.type` yields `Foo`.
    */
  def derivedTypeName(using q: Quotes)(tSym: q.reflect.Symbol): String =
    nameOverride(tSym).getOrElse(tSym.name.stripSuffix("$"))

  /** Stable activity name for a method of an implementation class: `<impl-full-name>#<method-wire-name>`.
    *
    * Both the server reification ([[dapr4s.derivation.WorkflowActivities]]) and the caller derivation
    * ([[dapr4s.derivation.WorkflowActivityCalls]]) compute it from the same implementation class symbol, so the two
    * sides always agree on the dispatch string.
    */
  def activityName(using q: Quotes)(implSym: q.reflect.Symbol, m: q.reflect.Symbol): String =
    s"${implSym.fullName}#${wireName(m)}"

  /** The declared result type of an abstract method symbol. */
  def resultTypeOf(using q: Quotes)(m: q.reflect.Symbol): q.reflect.TypeRepr =
    import q.reflect.*
    m.tree.asInstanceOf[DefDef].returnTpt.tpe

  /** Extract `X` from a `JsonCodec[X]` type, if it is one. */
  def jsonCodecArg(using q: Quotes)(tpe: q.reflect.TypeRepr): Option[q.reflect.TypeRepr] =
    import q.reflect.*
    val jsonCodecSym = Symbol.requiredClass("dapr4s.JsonCodec")
    tpe.dealias match
      case AppliedType(tycon, List(arg)) if tycon.typeSymbol == jsonCodecSym => Some(arg)
      case _                                                                 => None

  /** Per-parameter info for a derived method body: `(name, ref, type, isGiven)` in clause order.
    *
    * `ref` is a `q.reflect.Term` pointing at the synthesised override's parameter; `type` is the parameter's declared
    * type (taken from the original abstract method).
    */
  def paramInfo(using
      q: Quotes,
  )(
      origSym: q.reflect.Symbol,
      newSym: q.reflect.Symbol,
      argss: List[List[q.reflect.Tree]],
  ): List[(String, q.reflect.Term, q.reflect.TypeRepr, Boolean)] =
    import q.reflect.*
    val defdef = origSym.tree.asInstanceOf[DefDef]
    val orig = defdef.paramss.collect { case tc: TermParamClause =>
      tc.params.map(p => (p.name, p.tpt.tpe, p.symbol.flags.is(Flags.Given)))
    }.flatten
    val termRefs = argss.flatten.collect { case t: Term => t }
    orig.zip(termRefs).map { case ((n, t, g), ref) => (n, ref, t, g) }

  /** Abort compilation with a message attributed to a derived method. */
  def fail(using q: Quotes)(engine: String, m: q.reflect.Symbol, msg: String): Nothing =
    import q.reflect.*
    report.errorAndAbort(s"$engine.derive: method `${m.name}` — $msg", m.pos.getOrElse(Position.ofMacroExpansion))

  /** Extract `X` from an `Option[X]` type, if it is one. */
  def optionArg(using q: Quotes)(tpe: q.reflect.TypeRepr): Option[q.reflect.TypeRepr] =
    import q.reflect.*
    val optionSym = Symbol.requiredClass("scala.Option")
    tpe.dealias match
      case AppliedType(tycon, List(arg)) if tycon.typeSymbol == optionSym => Some(arg)
      case _                                                              => None

  /** True if `tpe` is `Unit`. */
  def isUnit(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    tpe =:= TypeRepr.of[Unit]

  // ---- server-route derivation helpers --------------------------------------

  /** A term holding an instance of `T`: the singleton itself if `T` is an `object`, else `new T()`. */
  def instanceOf[T: Type](using q: Quotes): q.reflect.Term =
    import q.reflect.*
    val tr = TypeRepr.of[T]
    val ts = tr.typeSymbol
    if ts.flags.is(Flags.Module) then Ref(tr.termSymbol)
    else Apply(Select(New(TypeTree.of[T]), ts.primaryConstructor), Nil)

  /** Declared handler methods of `T` (user `def`s — excludes constructors, synthetic, inherited). */
  def handlerMethods[T: Type](using q: Quotes): List[q.reflect.Symbol] =
    import q.reflect.*
    TypeRepr.of[T].typeSymbol.declaredMethods.filter { m =>
      !m.isClassConstructor && !m.flags.is(Flags.Synthetic) && m.name != "$init$"
    }

  /** A given instance of `tpe`, resolved at the macro-expansion (derive call) site. */
  def summonExpr(using q: Quotes)(tpe: q.reflect.TypeRepr): q.reflect.Term =
    import q.reflect.*
    tpe.asType match
      case '[u] =>
        Expr.summon[u] match
          case Some(e) => e.asTerm
          case None    =>
            report.errorAndAbort(s"derivation: no given instance for ${tpe.show} in scope at the derive site.")

  /** Build `inst.m(<valueArg?>)(using summon[…]…)`, summoning every `using` parameter (resolved at the splice site).
    * Value clauses take `valueArg` (one param) or nothing (empty clause).
    */
  def callSummoning(using
      q: Quotes,
  )(engine: String, inst: q.reflect.Term, m: q.reflect.Symbol, valueArg: Option[q.reflect.Term]): q.reflect.Term =
    import q.reflect.*
    m.tree.asInstanceOf[DefDef].paramss.foldLeft(Select(inst, m): Term) { (acc, clause) =>
      clause match
        case tc: TermParamClause =>
          val isGiven = tc.params.headOption.exists(_.symbol.flags.is(Flags.Given))
          val args =
            if isGiven then tc.params.map(p => summonExpr(p.tpt.tpe))
            else if tc.params.isEmpty then Nil
            else if tc.params.sizeIs == 1 then List(valueArg.getOrElse(fail(engine, m, "missing value argument.")))
            else fail(engine, m, "a derived route method takes at most one request parameter.")
          Apply(acc, args)
        case _ => acc
    }

  /** The single value-parameter type of a handler method, if it has one. */
  def valueParamType(using q: Quotes)(m: q.reflect.Symbol): Option[q.reflect.TypeRepr] =
    import q.reflect.*
    m.tree.asInstanceOf[DefDef].paramss.collectFirst {
      case tc: TermParamClause
          if !tc.params.headOption.exists(_.symbol.flags.is(Flags.Given)) && tc.params.sizeIs == 1 =>
        tc.params.head.tpt.tpe
    }

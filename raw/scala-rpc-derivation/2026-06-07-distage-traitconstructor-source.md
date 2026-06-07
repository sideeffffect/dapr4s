# distage TraitConstructor (izumi) — Scala 3 macro source

> Source: https://github.com/7mind/izumi (branch develop, HEAD 5419992c82dc)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `distage/distage-core-api/src/main/scala-3/izumi/distage/constructors/TraitConstructorMacro.scala`
- `distage/distage-core-api/src/main/scala-3/izumi/distage/constructors/ConstructorUtil.scala`
- `distage/distage-core-api/src/main/scala-3/izumi/distage/constructors/constructors.scala`
- `fundamentals/fundamentals-platform/src/main/scala/izumi/fundamentals/reflection/ReflectiveCall.scala`

```scala
final class TraitConstructor[T](val provider: Functoid[T]) extends AnyVal with AnyConstructorBase[T]
object TraitConstructor {
  inline implicit def materialize[T]: TraitConstructor[T] = ${ TraitConstructorMacro.make[T] }
  def wrapInitialization[A](init: => A)(implicit weakTag: WeakTag[A]): A = try init catch { ... TraitInitializationFailedException ... }
}

object TraitConstructorMacro {
  def make[R: Type](using qctx: Quotes): Expr[TraitConstructor[R]] = try {
    import qctx.reflect.*
    val util = new ConstructorUtil[qctx.type]()
    util.requireConcreteTypeConstructor(TypeRepr.of[R], "TraitConstructor")
    val context = new ConstructorContext[R, qctx.type, util.type](util)
    makeImpl[R](util, context)
  } catch { case t: StopMacroExpansion => throw t; case t => qctx.reflect.report.errorAndAbort(t.stacktraceString) }
}
```

## implementTraitAutoImplBody (the class synthesis)

```scala
def implementTraitAutoImplBody(lamSym: Symbol, lamOnlyCtorArguments: List[Term], lamOnlyMethodArguments: List[Term]): Typed = {
  val parents = util.buildParentConstructorCallTerms(constructorParamLists, lamOnlyCtorArguments)
  val name = s"${resultTpeSyms.map(_.name).mkString("With")}TraitAutoImpl"
  val clsSym =
    // Symbol.newClass(lamSym, name, parents = parentTypesParameterized, decls = methodDecls.generateDeclSymbols(forceLazyVals = true), None)
    ReflectiveCall.call[Symbol](Symbol, "newClass", lamSym, name, parentTypesParameterized, methodDecls.generateDeclSymbols(forceLazyVals = true), None)
  val defs = methodDecls.zip(lamOnlyMethodArguments).map { case (MemberRepr(name, ...), methodImpl) =>
    ValDef(clsSym.declaredField(name), Some(methodImpl)) }
  val clsDef = ReflectiveCall.call[ClassDef](ClassDef, "apply", clsSym, parents.toList, defs)
  val applyNewTree = Typed(Apply(Select(New(TypeIdent(clsSym)), clsSym.primaryConstructor), Nil), resultTpeTree)
  val traitCtorTree = '{ TraitConstructor.wrapInitialization[R](${ applyNewTree.asExpr.asInstanceOf[Expr[R]] })(compiletime.summonInline[WeakTag[R]]) }.asTerm
  Typed(Block(List(clsDef), traitCtorTree), resultTpeTree)
}
```

## ReflectiveCall shim (why)

```scala
object ReflectiveCall {
  def call[Out](on: Any, name: String, args: AnyRef*): Out = {
    val mm = on.getClass.getMethods.collectFirst { case m if m.getName == name && m.getParameterCount == args.size => m }.get
    mm.invoke(on, args*).asInstanceOf[Out]
  }
}
```

`Symbol.newClass` (with custom constructor) and `ClassDef.apply(sym, parents, body)` were experimental/private at the time, so distage calls them via `java.lang.reflect` to bypass the compile-time access check.

Mechanism: given an abstract trait/class `R` whose abstract parameterless defs/vals are dependencies, synthesize a concrete subclass whose members are overriding **lazy vals** wired from a by-name parameter list; package it as a `Functoid[R]` (a reflected `Seq[Any] => R` carrying param Tags) for distage's planner to inject. DI auto-implementation — not RPC — but the identical trait-to-impl-via-Scala-3-macro mechanism (`Symbol.newClass` + `ClassDef` + synthesized member bodies). `generateDeclSymbols(forceLazyVals = true)` makes each member an overriding lazy val.

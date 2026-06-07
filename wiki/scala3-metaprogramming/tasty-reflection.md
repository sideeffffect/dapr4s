# TASTy Reflection

> Sources: Scala 3 Reference — Reflection, Unknown
> Raw: [Reflection Reference](../../raw/scala3-metaprogramming/2026-06-07-scala3-reflection-reference.md)

## Overview

TASTy reflection (`quotes.reflect.*`) is the typed-AST API for *inspecting* and *constructing* code inside a macro. It is more powerful but less safe than plain [quotes and splices](macros-quotes-and-splices.md): the type-correctness guarantee no longer holds automatically, so the macro author must check things explicitly and failures surface at macro-expansion time. **This is the single most important page for understanding trait-to-implementation derivation** — the constructive API here (`Symbol.newClass`, `Symbol.newMethod`, `ClassDef`, `DefDef`) is exactly what synthesizes an anonymous class implementing a user trait.

## Getting in and out

Add `(using Quotes)` and `import quotes.reflect.*`. Convert between worlds with:
- `expr.asTerm : Term`
- `term.asExpr : Expr[Any]` / `term.asExprOf[T] : Expr[T]` (throws at expansion if the type doesn't conform)
- `TypeRepr.of[T]` (needs given `Type[T]`); back via `typeRepr.asType match { case '[t] => ... }`

## The three construct families

### 1. Trees and Terms

`Tree` is the post-typing AST; `Term` is the subtype that has a value and a `.tpe`. Examples: `ValDef`, `DefDef`, `ClassDef`, `Block`, `Apply`, `Select`, `New`, `TypeIdent`, `Ident`, `Ref`, `Literal`, `Typed`, `Lambda`. Inspect any tree's shape with `Printer.TreeStructure.show(tree)`. Note `apply`/`unapply` for one node often take different arguments (e.g. `ValDef.apply(sym, rhs)` vs the `unapply` triple).

### 2. Symbols and Flags

`Symbol`s are the named declarations. Every symbol needs an **owner** (`Symbol.spliceOwner` is the macro-expansion owner). Construction primitives:

```scala
Symbol.newClass(owner, name, parents: List[TypeRepr], decls: Symbol => List[Symbol], selfType)
Symbol.newMethod(owner, name, tpe: TypeRepr, flags, privateWithin)
Symbol.newVal(owner, name, tpe, flags, privateWithin)
```

`Flags` is a bit set (`.is`, `.|`, `.&`). Flags relevant to derivation:
- `Flags.Deferred` — an abstract (unimplemented) member; **this is how you find the methods to implement** when scanning a trait.
- `Flags.Override` — set on the synthesized methods so they override the trait's abstract members.
- `Flags.Trait` — a trait symbol carries `Trait`, not `Abstract`.

Reference a created symbol with `Ref(sym)` (terms) or `TypeIdent(sym)` (types).

### 3. TypeReprs and TypeTrees

`TypeRepr` is the type representation for reading/assigning types on symbols (`AppliedType`, `MethodType`, `TypeRef`, `TermRef`, `ByNameType`, `PolyType`, …). Key operations for derivation:
- `TypeRepr.of[T].typeSymbol.methodMembers` / `.declaredMethods` — enumerate methods.
- `prefix.memberType(methodSymbol)` — the method's type *as seen through the trait type* (resolving the trait's own type parameters). Symbols alone hold incomplete type info, so always go through `memberType`.
- `MethodType(paramNames)(_ => paramTypes, _ => resultType)` — build a method signature, including the curried/implicit/given structure.

## The canonical class-synthesis recipe

Nearly every trait-to-impl deriver follows this exact shape (here distilled from sloth/oxygen/spice/kreuzberg/cats-tagless):

```scala
import quotes.reflect.*
val tpe = TypeRepr.of[T]
// 1. collect abstract methods
val methods = tpe.typeSymbol.methodMembers.filter(_.flags.is(Flags.Deferred))
// 2. declare overriding method symbols for the new class
def decls(cls: Symbol): List[Symbol] = methods.map { m =>
  Symbol.newMethod(cls, m.name, tpe.memberType(m), Flags.Override, Symbol.noSymbol)
}
// 3. create the class symbol extending [Object, T]
val cls = Symbol.newClass(Symbol.spliceOwner, "Anon",
  parents = List(TypeRepr.of[Object], tpe), decls, selfType = None)
// 4. generate each method body via DefDef(sym, argss => Some(rhs))
val body = cls.declaredMethods.map { m => DefDef(m, argss => Some(/* ... call transport ... */)) }
// 5. define and instantiate the class
val clsDef = ClassDef(cls, parents, body)
val instance = Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[T])
Block(List(clsDef), instance).asExprOf[T]
```

What differs between libraries is only step 4 — the generated method body (serialize args + call a transport, apply a natural transformation, read from a `ScopedRef`, etc.). See [Derivation Mechanism Pattern](../scala-rpc-derivation/derivation-mechanism-pattern.md).

## Other utilities

- `Position.ofMacroExpansion` — source position info for diagnostics.
- `TreeAccumulator[X]` / `TreeTraverser` / `TreeMap` — traverse and transform trees.
- `ValDef.let(rhs)(body)` / `ValDef.lets(terms)(body)` — bind terms to vals and use them in a body (used to bind a single transport/impl instance referenced by every generated method).
- Ownership matters: spliced bodies sometimes need `.changeOwner(methodSym)` so the tree is reparented to the right method symbol.

## Caveats

- Some construction APIs (`Symbol.newClass` with a custom constructor, `Symbol.newTypeAlias`) were experimental/private in earlier Scala 3 versions; libraries either mark code `@experimental` or use a Java-reflection shim to call them (see [distage TraitConstructor](../scala-rpc-derivation/distage-traitconstructor.md), [tagless-redux](../scala-rpc-derivation/tagless-redux.md)).

## See Also

- [Macros: Quotes and Splices](macros-quotes-and-splices.md)
- [Derivation Mechanism Pattern](../scala-rpc-derivation/derivation-mechanism-pattern.md)
- [Metaprogramming Overview](metaprogramming-overview.md)
- [Inline](inline.md)

# Scala 3 — TASTy Reflection (Reference)

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/reflection.html
> Collected: 2026-06-07
> Published: Unknown

Reflection enables inspection and construction of Typed Abstract Syntax Trees (Typed-AST). Usable on quoted expressions/types (from Macros / multi-staging) or on whole TASTy files via TASTy Inspection.

> Macros provide the guarantee that the generation of code will be type-correct. Using quote reflection will break these guarantees and may fail at macro expansion time, hence additional explicit checks must be done.

To use reflection in a macro: add `(using Quotes)` and `import quotes.reflect.*`:

```scala
import scala.quoted.*

inline def natConst(inline x: Int): Int = ${natConstImpl('{x})}

def natConstImpl(x: Expr[Int])(using Quotes): Expr[Int] =
  import quotes.reflect.*
  ...
```

## Converting Exprs to TASTy reflect trees and back

```scala
val term: Term = x.asTerm
```

Change a `Term` back into an `Expr` with `.asExpr` (→ `Expr[Any]`) or `.asExprOf[T]` (→ `Expr[T]`, throws at macro-expansion time if the type doesn't conform).

## Constructing and Analysing trees

Three main constructs: **Trees**, **Symbols with Flags**, **TypeReprs**.

### Typed Abstract Syntax Trees

`Tree` is the tree-like representation after typing. `Term`s are subtypes of trees representing an expression with a value (`.tpe` accessible; `.asExpr` available).

```scala
val foo: Int = 0
// ValDef(foo,Ident(Int),Literal(Constant(0)))  -- subtype of Tree, not Term
```

```scala
val foo: Int = 0
foo + 1
// Block(
//   List(ValDef(foo,Ident(Int),Literal(Constant(0)))),
//   Apply(Select(Ident(foo),+), List(Literal(Constant(1)))))
```

Print code shape: `println('{ scalaCode }.asTerm)` (always a `Term`).

#### Tree Extractors and Constructors

```scala
def natConstImpl(x: Expr[Int])(using Quotes): Expr[Int] =
  import quotes.reflect.*
  val tree: Term = x.asTerm
  tree match
    case Inlined(_, _, Literal(IntConstant(n))) =>
      if n <= 0 then
        report.error("Parameter must be natural number")
        '{0}
      else tree.asExprOf[Int]
    case _ =>
      report.error("Parameter must be a known constant")
      '{0}
```

Inspect structure with `Printer.TreeStructure.show(tree)` (or `tree.show(using Printer.TreeStructure)`). Note `apply` and `unapply` for the same tree can have different arguments (e.g. `ValDef.apply` takes `(Symbol, Option[Term])`; `unapply` gives `(String, TypeTree, Option[Term])`).

### Symbols

Symbols represent the "named" parts of the code (declarations referenceable later). To create `val name: Int = 0`:

```scala
import quotes.reflect._
val fooSym = Symbol.newVal(
  parent = Symbol.spliceOwner,
  name = "foo",
  tpe = TypeRepr.of[Int],
  flags = Flags.EmptyFlags,
  privateWithin = Symbol.noSymbol
)
val tree = ValDef(fooSym, Some(Literal(IntConstant(0))))
```

Every `Symbol` needs a parent/owner. Reference a created val with `Ref(fooSym)`; reference types (from `Symbol.newType` / `Symbol.newClass`) with `TypeIdent`.

#### Flags

`Flags` describe attributes of symbols (access modifiers, Scala-2/Java origin, `inline`/`transparent`, compiler-generated, …). Bit set: `.is` (subset test), `.|` (union), `.&` (intersection). Think of flags as explicitly stated modifiers — e.g. a trait symbol has the `Trait` flag, not `Abstract`.

### TypeReprs and TypeTrees

`scala.quoted.Type` assigns types in quoted code; convert to `TypeRepr` with `TypeRepr.of[T]` (needs a given `Type[T]`), and back via:

```scala
typeRepr.asType match
  case '[t] => // given Type[t] in scope
```

```scala
List[String]
// AppliedType(
//   TypeRef(TermRef(ThisType(...immutable)),List),
//   List(TypeRef(TermRef(ThisType(...lang)),String)))
```

- `TypeRef(prefix, typeSymbol)` — selection of a type (`prefix.SomeType`).
- `TermRef(prefix, termSymbol)` — selection on a term (path-dependent type `prefix.someVal.type`); `.widenByTermRef` widens it.

Insert a type as part of a tree (e.g. `TypeApply` type parameter) using a `TypeTree`.

#### Extracting TypeReprs from Symbols

`.typeRef`/`.termRef` only produce refs usable in the owner's scope. Symbols hold incomplete type information:

```scala
class Outer[T]:
  val inner: List[T] = ???
```

Use the prefixing `TypeRepr`: `prefix.memberType(symbol)` or `prefix.select(symbol)`:

```scala
val prefix = TypeRepr.of[Outer[String]]
prefix.memberType(innerSymbol) // AppliedType(...List..., List(...String))
```

### Positions

```scala
val pos = Position.ofMacroExpansion
val start = pos.start; val startLine = pos.startLine; val sourceCode = pos.sourceCode
```

## Tree Utilities

- `TreeAccumulator[X]` — traverse and aggregate `X` (`foldTree`/`foldOverTree`).
- `TreeTraverser` — `TreeAccumulator[Unit]`.
- `TreeMap` — transform trees (e.g. override `transformStatement`).

```scala
def collectPatternVariables(tree: Tree)(using ctx: Context): List[Symbol] =
  val acc = new TreeAccumulator[List[Symbol]]:
    def foldTree(syms: List[Symbol], tree: Tree)(owner: Symbol): List[Symbol] = tree match
      case ValDef(_, _, rhs) => foldTree(tree.symbol :: syms, body)(tree.symbol)
      case _ => foldOverTree(syms, tree)(owner)
  acc(Nil, tree)
```

### ValDef.let

```scala
def let(rhs: Term)(body: Ident => Term): Term = ...
def lets(terms: List[Term])(body: List[Term] => Term): Term = ...
```

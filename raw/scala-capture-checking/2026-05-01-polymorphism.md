# Capability Polymorphism

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/polymorphism.md
> Collected: 2026-05-01
> Published: Unknown

## Key Concepts

Capture checking supports two complementary approaches to capture-polymorphic programming.

**Implicit Capture Polymorphism** is the default approach requiring minimal syntax. It leverages existing conventions so higher-order functions like `map` naturally work with both pure and effectful code. Capability polymorphism equals effect polymorphism when capabilities are the sole source of side effects.

**Explicit Capture Polymorphism** uses capture-set variables (denoted `X^`) to parameterize definitions. This allows APIs to precisely specify which capabilities clients may use:

```scala
class Source[X^]:
  private var listeners: Set[Listener^{X}]
```

This enables storing listeners constrained to a specific capture set.

## Access Control Pattern

"Brand" capabilities can restrict access. The `runSecure` function accepts a block parameter and enforces that only capabilities within a `{trusted}` set can be accessed. Code attempting to use untrusted loggers or channels within such a block fails compilation.

## Lexical Control Safety

Preventing label leakage in delimited control operators uses:
- Labels storing free capabilities in a type member `Fv`
- Suspension handlers constrained to capture "at most the capabilities that occur freely at the `boundary` that introduced the label"

This prevents nested labels from escaping their defining scopes at compile time.

## Practical Guidance

Prefer implicit polymorphism initially; introduce explicit capture-set parameters only when capture relationships cannot be expressed implicitly or would benefit from clarity.

**Capability Members** offer an alternative to capture parameters — they tie capture information to object identity through path-dependent annotations like `{this.X}`. Useful for abstract interfaces where capture constraints should vary by implementation.

Internally, capture-set variables are specialized type parameters with bounds ranging across all possible capture sets.

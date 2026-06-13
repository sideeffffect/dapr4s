# Capture Checking on Scala.js

> Sources: Empirical probe (/tmp/cc-js-probe, dapr4s nightly 3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY), scala/scala3 Compiler.scala, scala/scala3 issue tracker, scala-cli v1.13.0 release notes, 2026-06-11
> Raw: [CC on Scala.js probe report](../../raw/scala-js/2026-06-11-cc-on-scalajs-probe.md)
> Updated: 2026-06-11

## Overview

Capture checking works end-to-end on Scala.js with **zero known incompatibilities and zero observed friction**: dapr4s's exact nightly and full flag set compiles, links, runs on Node, and passes munit tests on the JS platform with no warnings. The reason is architectural — CC never reaches the JS backend.

## Why it works: CC is erased before GenSJSIR

In `compiler/src/dotty/tools/dotc/Compiler.scala` (scala/scala3 main), `cc.Setup` and `cc.CheckCaptures` live in **`picklerPhases`** (the frontend group), while `backend.sjs.GenSJSIR` is in `backendPhases`. Capture sets are type annotations erased long before SJSIR generation — the JS backend never sees CC artifacts. Consistently, the scala/scala3 issue tracker has **zero** CC × Scala.js issues (searches for "capture checking scala.js"/"captureChecking scalajs" and filtering the `area:capture-checking` label for scalajs: 0 results).

## Empirical probe: dapr4s's exact nightly + full flag set

The probe (`/tmp/cc-js-probe`) used `3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` (resolves for Scala.js: `scala3-library_sjs1_3` nightly from repo.scala-lang.org, `scalajs-library_2.13` from Central) with dapr4s's complete flag set:

```scala
//> using platform scala-js
//> using jsVersion 1.21.0
//> using jsEsVersionStr es2017
//> using options "-language:experimental.captureChecking" "-language:experimental.pureFunctions"
//> using options "-Ycc-verbose" "-Yexplicit-nulls" "-experimental" "-Wconf:any:error"
```

Verified, all with **zero diagnostics under `-Wconf:any:error`** (no exclusions, no `@nowarn`):

- Capability trait extending `scala.caps.ExclusiveCapability`; methods taking `FileSystem ?=> A`; capture-set-annotated function types `(String => Unit)^{fs}`; `@js.native @JSGlobal` facades used from CC code — compile, link, run.
- Per-file `import language.experimental.safe` — accepted on JS.
- `js.async { js.await(p) + 1 }` — compiles and runs (needs `jsEsVersionStr es2017`, see [js.async, JSPI and the Wasm backend](scala-js-async-jspi-wasm.md)).
- munit + upickle tests pass on Node: `Test run finished: 0 failed, 0 ignored, 2 total`.

## Explicit nulls × js.native facades

Under `-Yexplicit-nulls`, only **Java-defined** symbols get nullified types. JS facades are **Scala-defined**, so member types are taken verbatim (`String`, not `String | Null`) — hence zero warnings and no friction. The flip side: **the compiler will not protect you from a facade member that returns `null` at runtime — model nullability yourself** by declaring such members `X | Null`. A modeling concern, not a compiler one.

## Gotcha: `caps.Capability` is sealed (nightly semantics, not JS-specific)

In current nightlies `trait Foo extends caps.Capability` fails with `Cannot extend sealed trait Capability in a different source file`. User code must extend the classifier subtraits: `scala.caps.ExclusiveCapability`, `SharedCapability`, etc. dapr4s already does (`trait DaprCapability extends scala.caps.ExclusiveCapability`), but any docs/examples extending bare `Capability` break identically on JVM and JS. See [Capability Classifiers](../scala-capture-checking/capability-classifiers.md).

## Toolchain requirements (none CC-specific)

- **scala-cli >= 1.13.0**: munit 1.3.0's JS artifacts carry Scala.js 1.21 IR; older scala-cli bundles a 1.20 linker that fails with `IRVersionNotSupportedException` regardless of `//> using jsVersion`.
- **Platform-suffixed dep syntax** `org::name::version` for JS deps — `munit_sjs1_3:1.3.0` and `upickle_sjs1_3:3.3.1` both exist on Central and resolve; the single-colon form silently picks the JVM artifact and dies at link time with `No framework found by Scala.js test bridge`.

Both detailed in [Cross-Building JVM + Scala.js with Scala CLI](scala-js-cross-building-scala-cli.md).

## See Also

- [Capture Checking Overview](../scala-capture-checking/capture-checking-overview.md) — what CC is and how to enable it
- [How to Use Capture Checking](../scala-capture-checking/how-to-use.md) — flags (`-Ycc-verbose` etc.) used in the probe
- [Capability Classifiers](../scala-capture-checking/capability-classifiers.md) — the classifier subtraits to extend instead of sealed `Capability`
- [Cross-Building JVM + Scala.js with Scala CLI](scala-js-cross-building-scala-cli.md)
- [js.async, JSPI and the Wasm backend](scala-js-async-jspi-wasm.md)

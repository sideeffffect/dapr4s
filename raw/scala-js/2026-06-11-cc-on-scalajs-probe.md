# Scala 3 Capture Checking on Scala.js — research + empirical probe report

> Source: Empirical probe in /tmp/cc-js-probe (dapr4s nightly 3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY, scala-cli 1.12.2/1.14.0, Node v22.20.0); scala/scala3 Compiler.scala phase list; scala/scala3 issue tracker searches; scala-cli v1.13.0 release notes; Maven Central artifact probes
> Collected: 2026-06-11
> Published: Unknown

## TL;DR
Capture checking (plus pureFunctions, per-file `experimental.safe`, `-Yexplicit-nulls`, `-experimental`, `-Wconf:any:error`, `-Ycc-verbose`) **works end-to-end on Scala.js with the exact dapr4s nightly** `3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY`. Compile, link, run on Node, and munit tests all pass. The only toolchain gotcha: **scala-cli >= 1.13.0 is required** (the locally installed 1.12.2 embeds a Scala.js 1.20 linker that cannot read the IR 1.21 emitted by munit 1.3.0's JS artifacts), and JS-platform deps need the `org::name::version` (double-colon-before-version) form.

## Q1: Known incompatibilities CC x Scala.js backend — NONE found

- **Architecture**: confirmed from `compiler/src/dotty/tools/dotc/Compiler.scala` on scala/scala3 main — `cc.Setup` and `cc.CheckCaptures` (lines 87–88) live in `picklerPhases`, i.e. the frontend group; `backend.sjs.GenSJSIR` is in `backendPhases` (line 151). Capture sets are type annotations erased long before SJSIR generation, so the JS backend never sees CC artifacts. This matches the "CC is a typer/refchecks-level feature" hypothesis.
- **Issue tracker**: `gh search issues --repo scala/scala3` for "capture checking scala.js", "captureChecking scalajs", "cc Scala.js" → **0 results**. Filtering the `area:capture-checking` label for "scalajs"/"scala.js" → **0 results**. Web searches likewise surfaced no CC-vs-Scala.js bug reports.
- Sources: [scala3 Compiler.scala](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/Compiler.scala), [CC reference docs](https://docs.scala-lang.org/scala3/reference/experimental/cc.html), [issue #19855](https://github.com/scala/scala3/issues/19855), [issue #23027](https://github.com/scala/scala3/issues/23027) (general CC bugs, none JS-specific).

## Q2: Empirical probe — /tmp/cc-js-probe

**The nightly resolves for Scala.js.** scala-cli found `scala3-library_sjs1_3-3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` and `scalajs-scalalib_2.13-3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` on `repo.scala-lang.org/artifactory/maven-nightlies` (after expected misses on Central snapshots), plus `scalajs-library_2.13`/`scalajs-javalib` from Maven Central. scala-cli 1.12.2 auto-picked Scala.js 1.20.2; scala-cli 1.14.0 picks 1.21.0.

**Final working `/tmp/cc-js-probe/project.scala`:**
```scala
//> using scala "3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY"
//> using platform scala-js
//> using jsVersion 1.21.0
//> using jsEsVersionStr es2017
//> using options "-language:experimental.captureChecking"
//> using options "-language:experimental.pureFunctions"
//> using options "-Ycc-verbose"
//> using options "-Yexplicit-nulls"
//> using options "-experimental"
//> using options "-Wconf:any:error"
//> using test.dep "org.scalameta::munit::1.3.0"
//> using test.dep "com.lihaoyi::upickle::3.3.1"
```
(The flag set deliberately mirrors `/home/ondra/.t3/worktrees/dapr4s/t3code-c916dd05/project.scala`, including `-Ycc-verbose` and `-Wconf:any:error`.)

**Sources** (all compile clean, zero diagnostics, under `-Wconf:any:error`):
- `/tmp/cc-js-probe/Probe.scala` — capability trait extending `scala.caps.ExclusiveCapability`, a method taking `FileSystem ?=> A`, capture-set-annotated function type `(String => Unit)^{fs}`, plus a `@js.native @JSGlobal("console")` facade used from CC code.
- `/tmp/cc-js-probe/SafeProbe.scala` — `import language.experimental.safe` per-file: **accepted on JS** (an unknown language feature would have errored).
- `/tmp/cc-js-probe/JsInterop.scala`, `AsyncProbe.scala`, `MainApp.scala`, `probe.test.scala` — see Q3–Q5.

**One code-level finding** (nightly semantics, not JS-specific): in this nightly `caps.Capability` is **sealed**. My first probe `trait FileSystem extends Capability` failed with:
```
[error] ./Probe.scala:9:7
[error] Cannot extend sealed trait Capability in a different source file
```
User code must extend the classifier subtraits (`scala.caps.ExclusiveCapability`, `SharedCapability`, ...). dapr4s already does this (`trait DaprCapability extends scala.caps.ExclusiveCapability` in `src/DaprCapability.scala`), so this is a non-issue for the cross-build — but any docs/examples extending bare `Capability` will break identically on JVM and JS.

**Run verification**: `scala-cli run .` (with scala-cli 1.14.0) prints:
```
hello from capture-checked Scala.js
js.async result: 42
```

## Q3: js.Promise + js.native facades under -Yexplicit-nulls + CC + -Wconf:any:error

**Zero friction observed.** `/tmp/cc-js-probe/JsInterop.scala` defines a `@js.native trait` with `def ...: String = js.native`, `val version: Int = js.native`, `js.Promise`-returning members, a `@JSGlobal` object, and `.then` chaining (backticked `` `then` `` — Scala 3 keyword, unrelated to CC). It compiles with **no warnings at all**, so `-Wconf:any:error` passes with **no exclusions or `@nowarn` needed**. Explanation: under explicit nulls only *Java-defined* symbols get nullified types; JS facades are Scala-defined, so their member types are taken verbatim (`String`, not `String | Null`). Caveat: if a real-world facade member can return `null` at runtime, you must declare it `X | Null` yourself — a modeling concern, not a compiler friction.

## Q4: js.async / js.await

- Available and compiles in both Scala.js 1.20.2 and 1.21.0 (`js.async { js.await(p) + 1 }` in `/tmp/cc-js-probe/AsyncProbe.scala`), no compiler/plugin flag needed.
- **Linker requires ES2017 output.** Without it, linking fails:
```
Uses an async block with an ECMAScript version older than ES 2017
  called from ccjsprobe.AsyncProbe$.roundTrip(scala.scalajs.js.Promise)scala.scalajs.js.Promise
  ...
org.scalajs.linker.interface.LinkingException: There were linking errors
```
- Fix: `//> using jsEsVersionStr es2017`. After that, runtime output on Node v22.20.0 is correct (`js.async result: 42`). No orphan-await/JSPI flags needed for directly-nested awaits.

## Q5: munit + upickle on JS — both exist, two real gotchas

`munit_sjs1_3:1.3.0` and `upickle_sjs1_3:3.3.1` **both exist and resolve**; `scala-cli test .` ends with:
```
Test run ccjsprobe.ProbeSuite finished: 0 failed, 0 ignored, 2 total
```
(one test round-trips a `Map` through `upickle.default.write/read`, one exercises a capture-checked closure.)

**Gotcha A — dependency syntax**: dapr4s's current `//> using test.dep "org.scalameta::munit:1.3.0"` (single colon before version) resolves the **JVM** artifact `munit_3` even on the JS platform. Compilation still succeeds (TASTy is there) but there are no `.sjsir` files, so the test run dies with the misleading `[error] No framework found by Scala.js test bridge` (verbose log confirmed `munit_3-1.3.0.jar` on the JS classpath). The platform-suffixed form `org.scalameta::munit::1.3.0` → `munit_sjs1_3` is required; this form also resolves correctly on JVM, so it is safe to use unconditionally in a cross-build.

**Gotcha B — linker IR version / scala-cli version**: munit 1.3.0's JS artifacts pull `scalajs-library_2.13:1.21.0` (IR 1.21). The installed scala-cli **1.12.2** embeds a Scala.js **1.20.2** linker in its native binary, which fails hard regardless of `//> using jsVersion 1.21.0`:
```
org.scalajs.ir.IRVersionNotSupportedException: Failed to deserialize a file compiled with
Scala.js 1.21 (supported up to: 1.20): .../scalajs-library_2.13-1.21.0.jar:/scala/scalajs/runtime/package.sjsir
```
Fix: scala-cli **>= 1.13.0** ("Support for Scala.js 1.21.0", [release notes](https://github.com/VirtusLab/scala-cli/releases/tag/v1.13.0)). I downloaded the 1.14.0 launcher to `/tmp/scala-cli-1.14.0` (global install untouched); it defaults to Scala.js 1.21.0 (verified with a bare probe in `/tmp/js-default-probe`), so the explicit `jsVersion` pin is optional-but-recommended. Scala.js 1.21.0 (2026-04-04) is the latest release ([scala-js releases](https://github.com/scala-js/scala-js/releases)). **CI implication for dapr4s**: pin/setup scala-cli >= 1.13.0 for any JS build.

## Q6: Fallback to stable 3.7.x/3.8.x
Not needed — the dapr4s nightly works on JS. (Incidentally, the bare `/tmp/js-default-probe` compile also confirms stable 3.8.3 + Scala.js 1.21.0 works under scala-cli 1.14.0.)

## Out-of-scope caveats for the actual dapr4s cross-build
- The main deps (`io.dapr:dapr-sdk*`) and test deps (`testcontainers-*`) are JVM-only Java libraries; a JS build needs a different transport/test strategy. That is an architecture question, not a toolchain blocker.
- The transient `Bloop 'bsp' command exited with code 1` seen once with scala-cli 1.12.2 did not reproduce with 1.14.0 (default server mode worked).

## Artifacts
- Probe project: `/tmp/cc-js-probe/` (`project.scala`, `Probe.scala`, `SafeProbe.scala`, `JsInterop.scala`, `AsyncProbe.scala`, `MainApp.scala`, `probe.test.scala`)
- scala-cli 1.14.0 launcher: `/tmp/scala-cli-1.14.0`

Sources: [scala3 Compiler.scala phase list](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/Compiler.scala), [Capture Checking docs](https://docs.scala-lang.org/scala3/reference/experimental/cc.html), [scala-cli v1.13.0 release](https://github.com/VirtusLab/scala-cli/releases/tag/v1.13.0), [scala-js releases](https://github.com/scala-js/scala-js/releases), [scala3 issue #19855](https://github.com/scala/scala3/issues/19855), [scala3 issue #23027](https://github.com/scala/scala3/issues/23027), [Capture Checking in Scala 3.4](https://www.scalamatters.io/post/capture-checking-in-scala-3-4), [Introduction to CC and Separation Checking](https://tanishiking.github.io/posts/introduction-to-scala-3s-capture-checking-and-separation-checking/)

## VERDICT
Capture checking is fully viable on Scala.js with the exact dapr4s nightly (3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY): the complete dapr4s flag set (-language:experimental.captureChecking, pureFunctions, per-file experimental.safe, -Yexplicit-nulls, -Ycc-verbose, -experimental, -Wconf:any:error) compiles, links, runs on Node, and passes munit tests on the JS platform with zero warnings and no -Wconf exclusions — CC is erased in the pickler-phase group before GenSJSIR, and no CC×Scala.js issues exist in the scala/scala3 tracker. The empirically verified requirements are: scala-cli >= 1.13.0 (the installed 1.12.2's embedded 1.20 linker rejects the IR 1.21 in munit 1.3.0's JS artifacts; munit_sjs1_3:1.3.0 and upickle_sjs1_3:3.3.1 otherwise resolve fine), platform-suffixed dependency syntax org::name::version for JS deps, and //> using jsEsVersionStr es2017 for js.async/js.await (available and runtime-verified on Scala.js 1.20.2/1.21.0).

## BLOCKERS

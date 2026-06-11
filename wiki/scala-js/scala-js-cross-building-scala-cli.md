# Cross-Building JVM + Scala.js with Scala CLI

> Sources: Empirical probes (scala-cli 1.12.2 / 1.14.0, Node 22), scala-cli.virtuslab.org docs, VirtusLab/scala-cli releases & issues, Maven Central artifact probes, 2026-06-11
> Raw: [scala-cli cross-platform probe report](../../raw/scala-js/2026-06-11-scala-cli-crossplatform.md)
> Updated: 2026-06-11

## Overview

Scala CLI (v1.x) can cross-compile and cross-publish a JVM + Scala.js library from a **single source tree** — no sbt-crossproject needed. Everything below was verified empirically (compile/test/publish-local on both platforms, jar/POM inspection), including with dapr4s's exact Scala 3.10.0-RC1 nightly. The two hard constraints are the **dependency-directive platform leak** (no platform-scoped deps) and the **scala-cli >= 1.13.0 floor** for Scala.js 1.21 IR.

## The platform directive: first entry = default

```scala
//> using platform jvm scala-js    // also: //> using platforms (plural alias)
```

Grammar: `//> using platform (jvm|scala-js|js|scala-native|native)+`. This directive is **not** experimental (unlike `target.platform` and `publish.*`).

- Plain `scala-cli compile/test/run .` builds **only ONE platform: the first one listed**. The list order is the default-platform choice.
- Select the other platform per invocation: `scala-cli test --js .` (or `--platform js`).
- `--cross` (requires `--power`) runs the command against **all** declared platforms in one invocation — verified for `compile --cross` and `test --cross` (munit suites run once per platform). Earlier issues #3590/#3591 (`run`/`package --cross` only compiling) are fixed.

## Per-file platform targeting (no directory convention)

```scala
//> using target.platform jvm        // file compiled only for JVM
//> using target.platform scala-js   // file compiled only for Scala.js
```

- The only directive class that applies **per-file** rather than build-wide (a "require" directive). Marked **experimental** — a warning per use; suppress with `--suppress-experimental-feature-warning` or `scala-cli config suppress-warning.experimental-features true`.
- **There is NO `jvm/`/`js/` directory convention** (issue #1632). You can organize files into platform subdirectories for readability, but every platform-specific file must carry its own `target.platform` directive.
- Verified: jar contents are correctly platform-split after packaging/publishing (JS jar = `.sjsir` + shared/js-only classes, no jvm-only classes; vice versa for JVM).

## CRITICAL: dependency directives leak across platforms

`using dep` / `using test.dep` directives written inside a `target.platform`-tagged file are **NOT scoped to that platform** — they apply to all platforms. Verified failure: `//> using test.dep com.dimafeng::testcontainers-scala-munit::0.43.6` inside a jvm-tagged test file made `scala-cli test --js .` fail resolving `testcontainers-scala-munit_sjs1_3` (404). There is **no platform-conditional dependency directive**.

**The dapr4s pattern (`jvm-deps.scala` + `--exclude`):** put all JVM-only dep directives (`io.dapr:*`, testcontainers) into a dedicated `jvm-deps.scala` file at the repo root. Default invocations (`scala-cli compile/test .`) include it, so JVM workflows are unchanged; JS invocations pass `--exclude jvm-deps.scala`, keeping both resolution and the published `_sjs1_3` POM clean. (Alternative: pass JVM-only deps as CLI `--dep` flags on JVM invocations only.) Note that plain Java deps (single `:`) resolve fine on JS — but they'd still pollute the `_sjs1_3` POM, so they need the same treatment. Cost either way: single-shot `test --cross` can't be used when any platform needs excluded/CLI-only deps; run `test .` and `test --js . --exclude jvm-deps.scala` as two steps.

## Dependency syntax: `::` before the version for cross deps

`org::name:version` (single colon before version) resolves the **JVM** artifact (`munit_3`) even on the JS platform — compilation still succeeds (TASTy is present) but linking dies with the misleading `No framework found by Scala.js test bridge` (no `.sjsir` in the jar). Use the platform-suffixed form **`org::name::version`** (→ `munit_sjs1_3`); it also resolves correctly on JVM, so it's safe unconditionally in a cross-build.

## Version floor: scala-cli >= 1.13.0 for Scala.js 1.21 IR

The Scala.js linker is **bundled per scala-cli release** (1.12.x → Scala.js 1.20.2; **1.13.0+ → 1.21.0**) and `//> using jsVersion` **cannot raise the linker's IR ceiling** — verified: `jsVersion 1.21.0` on scala-cli 1.12.2 still fails with `IRVersionNotSupportedException: ... compiled with Scala.js 1.21 (supported up to: 1.20)`. munit 1.3.0's JS artifact pulls `scalajs-library_2.13:1.21.0` (IR 1.21), so **any JS build using munit 1.3.0 requires scala-cli >= 1.13.0**. CI's `scala-cli-setup` installs latest, so only local installs are affected.

Scala 3 nightlies work on JS: dapr4s's pinned `3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` compiled for Scala.js 1.21.0 and passed munit 1.3.0 + upickle 3.3.1 tests on Node (`scala3-library_sjs1_3` nightlies live on repo.scala-lang.org; `scalajs-library_2.13` is Scala-3-version-agnostic).

## Publishing both platforms

```
scala-cli --power publish --cross .
→ io.github.example:probe_3:0.1.0          (JVM)
→ io.github.example:probe_sjs1_3:0.1.0     (Scala.js)
```

- Without `--cross`, `publish` publishes **only the first/default platform**. Two separate invocations (`publish .` then `publish --js .`) are an equivalent alternative (and the one dapr4s uses, to combine with `--exclude jvm-deps.scala`).
- Per-platform POMs verified correct: `_sjs1_3` POM depends on `scalajs-library_2.13`, `scala3-library_sjs1_3`, `upickle_sjs1_3`; the `_3` POM on the JVM artifacts. Both modules get jar + sources + javadoc + POM.
- `publish.computeVersion git:dynver` works for cross publish; `publish.repository central` goes via the Central Portal OSSRH Staging API since scala-cli 1.8.4. `--cross` is undocumented in the publish docs (only `--help-full`); remote Central staging of two modules in one `--cross` run is untested — verify on first release.
- `publish local` does not support publishing the test scope.

## Scala.js directives & test runtime

```scala
//> using jsVersion 1.21.0        // cannot exceed the bundled linker
//> using jsModuleKind es         // commonjs/common, es/esmodule, none/nomodule
//> using jsEsVersionStr es2017   // required for js.async/js.await
//> using jsDom true              // JSDOMNodeJSEnv (still Node)
//> using jsEmitWasm true         // experimental Wasm backend (since scala-cli 1.5.2); needs jsModuleKind es
```

- `test --js` runs on **Node.js** (plain `NodeJSEnv`) by default.
- **npm module resolution is cwd-based**: scala-cli feeds the launcher to node via **stdin** (`requireStack: [ '/tmp/[stdin]' ]`), so `require()` resolves against `node_modules` in the directory **you invoke scala-cli from** — not the project-dir argument, not the output dir. Run scala-cli from the directory containing `node_modules`, or set `NODE_PATH=/path/to/node_modules` (CommonJS only, not ES modules). scala-cli has no bundler integration.

## GitHub Actions

Canonical job: `actions/checkout` (with `fetch-depth: 0` for `git:dynver`) + `coursier/cache-action` + `VirtusLab/scala-cli-setup` (`with: power: true`). For JS: add `actions/setup-node@v4` if you need a specific Node version (Node 25+ recommended for Wasm/JSPI, see [js.async, JSPI and the Wasm backend](scala-js-async-jspi-wasm.md)); JS test step is `scala-cli test --js .` — npm setup only needed for `--js-dom` or runtime `require()` of npm packages (install in the step's cwd). Publish: gate the JS publish invocation on the JS test job.

## See Also

- [js.async, JSPI and the Wasm backend](scala-js-async-jspi-wasm.md) — the directives/Node versions needed for direct-style code on JS
- [Capture Checking on Scala.js](capture-checking-on-scala-js.md) — the same toolchain floor applies; CC adds zero extra constraints
- [Dapr JS SDK](../dapr/dapr-js-sdk.md) — the npm dependency dapr4s's JS platform binds to (cwd-based resolution applies)

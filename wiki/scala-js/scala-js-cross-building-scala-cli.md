# Cross-Building JVM + Scala.js with Scala CLI

> Sources: Empirical probes (scala-cli 1.12.2 / 1.14.0, Node 22), scala-cli.virtuslab.org docs, VirtusLab/scala-cli releases & issues, Maven Central artifact probes, 2026-06-11; dep-scoping correction + deps-file pattern verified in the dapr4s cross-build restructure, 2026-06-12
> Raw: [scala-cli cross-platform probe report](../../raw/scala-js/2026-06-11-scala-cli-crossplatform.md)
> Updated: 2026-06-12

## Overview

Scala CLI (v1.x) can cross-compile and cross-publish a JVM + Scala.js library from a **single source tree** — no sbt-crossproject needed. Everything below was verified empirically (compile/test/publish-local on both platforms, jar/POM inspection), including with dapr4s's exact Scala 3.10.0-RC1 nightly. The two hard constraints are the **`test.dep` platform leak** (plain `using dep` *is* platform-scopable — see below) and the **scala-cli >= 1.13.0 floor** for Scala.js 1.21 IR.

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

## Platform-scoping dependencies: `target.platform` deps files (and the `test.dep` leak)

**CORRECTION (2026-06-12, supersedes the earlier "dependency directives leak" claim):** a plain `//> using dep` directive written in a file that carries a `//> using target.platform` directive **IS scoped to that platform** — `scala-cli compile|test --js .` simply never resolves a dep declared in a jvm-tagged file, and the published `_sjs1_3` POM stays clean. The earlier verified failure was specific to **`using test.dep`, which is NOT platform-scoped**: a `test.dep` in a jvm-tagged file still leaks into the JS *test* build (the original 404 repro used `test.dep`; the conclusion was over-generalised to all dep directives).

**The pattern (dapr4s, replacing its older `--exclude jvm-deps.scala` mechanism):** dedicated per-platform deps files at the repo root, each starting with a `target.platform` directive — no `--exclude` flags anywhere:

- `jvm-deps.scala` — `target.platform "jvm"` + plain `using dep` lines (Dapr Java SDK).
- `js-deps.scala` — `target.platform "scala-js"` + plain `using dep` lines (ScalablyTyped facades).
- `jvm-test-deps.test.scala` — `target.platform "jvm"` + plain `using dep` lines, with **test scope coming from the `.test.scala` filename suffix**. This is the workaround for the `test.dep` leak: never write `test.dep` for a platform-specific dependency; put a plain `dep` in a platform-tagged `*.test.scala` file instead.

Bonus: single-shot `test --cross` works again with this pattern as far as *dependencies* are concerned — though dapr4s itself still cannot use it: its Wasm-only orphan-await test suites must stay excluded from the plain-JS leg (see the next section), and its JVM integration suites need Docker.

## `--exclude` has no inverse

If you do exclude files (`--exclude path`), note there is **no `--include` flag**, and naming an excluded file as a positional argument is **silently ignored** — you cannot re-include per invocation. Excludes are only subtractive; design the build so excludes are rare (dapr4s's sole remaining one hides Wasm-only orphan-await test sources from the plain-JS linker, which would otherwise hang — see [the JSPI article](scala-js-async-jspi-wasm.md)).

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

- Without `--cross`, `publish` publishes **only the first/default platform**. Two separate invocations (`publish .` then `publish --js .`) are an equivalent alternative (and the one dapr4s uses — with `target.platform`-scoped deps files, each invocation resolves exactly its own platform's deps).
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

- [js.async, JSPI and the Wasm backend](scala-js-async-jspi-wasm.md) — the directives/Node versions needed for direct-style code on JS, plus testing-under-scala-cli field notes
- [ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md) — generating the npm-package facades the `js-deps.scala` pattern pins
- [Capture Checking on Scala.js](capture-checking-on-scala-js.md) — the same toolchain floor applies; CC adds zero extra constraints
- [Dapr JS SDK](../dapr/dapr-js-sdk.md) — the npm dependency dapr4s's JS platform binds to (cwd-based resolution applies)

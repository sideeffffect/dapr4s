# Scala CLI: Cross-compiling & cross-publishing JVM + Scala.js from one source tree

> Source: Empirical probes in /tmp/sjs-probe, /tmp/sjs-nightly2, /tmp/sjs-wasm (scala-cli 1.12.2 and 1.14.0, Node v22.20.0); scala-cli.virtuslab.org docs (directives reference, Scala.js guide, publish, GH Actions cookbook); VirtusLab/scala-cli releases & issues #1632/#3590/#3591; Maven Central artifact probes
> Collected: 2026-06-11
> Published: Unknown

All load-bearing claims below were **verified empirically** in `/tmp/sjs-probe`, `/tmp/sjs-nightly2`, and `/tmp/sjs-wasm` with scala-cli **1.12.2** (locally installed) and **1.14.0** (current latest, via `cs launch scala-cli:1.14.0 --`). Node v22.20.0.

## 1. Multi-platform directive and cross-building

**Yes, one directive can declare both platforms.** Both forms verified working:

```scala
//> using platform jvm scala-js
//> using platforms jvm scala-js     // plural alias, identical behavior
```

Grammar per the [directive reference](https://scala-cli.virtuslab.org/docs/reference/directives/): `//> using platform (jvm|scala-js|js|scala-native|native)+`. This directive is **not** experimental (no warning emitted), unlike `target.platform` and `publish.*` which are.

Verified semantics:
- **Plain `scala-cli compile/test/run .` builds only ONE platform: the FIRST one listed.** With `jvm scala-js` it built `(Scala 3.3.6, JVM (17))`; after reordering to `scala-js jvm` it built `(Scala 3.3.6, Scala.js 1.20.2)`. The list order is the default-platform choice.
- **CLI override selects the other platform:** `scala-cli compile --js .` / `scala-cli test --js .` (also `--platform js`). Verified.
- **`--cross` (requires `--power`) runs the command against ALL declared platforms in one invocation.** Help text: `--cross  Run given command against all provided Scala versions and/or platforms`. Verified on `compile --cross` (2 "Compiled project" lines: JVM + JS) and **`test --cross`** (ran munit suites twice: once on JVM, then `Running tests for Scala 3.3.6, JS`). Issues [#3590](https://github.com/VirtusLab/scala-cli/issues/3590)/[#3591](https://github.com/VirtusLab/scala-cli/issues/3591) (`run`/`package` `--cross` only compiling) are closed/fixed.

**Publishing both platforms — ONE invocation with `--cross`** (verified with `publish local`, same code path as remote `publish`):

```
scala-cli --power publish local --cross . --ivy2-home /tmp/probe-ivy
→ Publishing io.github.example:sjs-probe_3:0.1.0
→ Publishing io.github.example:sjs-probe_sjs1_3:0.1.0
```

Both modules got jar + sources jar + javadoc jar + POM. **Without `--cross`, `publish` publishes only the first/default platform** (verified: only `sjs-probe_3` appeared). Two separate invocations (`publish .` and `publish --js .`) also work as an alternative.

POMs are correct per platform (verified by inspection):
- `sjs-probe_3.pom`: `scala3-library_3:3.3.6`, `upickle_3:3.3.1`
- `sjs-probe_sjs1_3.pom`: `scalajs-library_2.13:1.21.0`, `scala3-library_sjs1_3:3.3.6`, `upickle_sjs1_3:3.3.1`

Jar contents are correctly platform-split: JS jar contains `.sjsir` + `.class` for shared and js-only files, no jvm-only classes; JVM jar contains jvm-only + shared, no js-only.

## 2. Per-file platform targeting

Exact verified syntax (placed at the top of the specific file, NOT in `project.scala`):

```scala
//> using target.platform jvm        // file only compiled for JVM
//> using target.platform scala-js   // file only compiled for Scala.js
```

- It's a **"require" directive**: the only directive class that applies per-file rather than build-wide ([using-directives guide](https://scala-cli.virtuslab.org/docs/guides/introduction/using-directives): "The only exceptions are `using target` directives, which only apply to the given file"). Marked **experimental** — scala-cli prints a warning for each use (suppress with `--suppress-experimental-feature-warning` or `scala-cli config suppress-warning.experimental-features true`).
- Verified: a file with `import scala.scalajs.js` + `//> using target.platform scala-js` is cleanly skipped on JVM builds and vice versa; jar contents confirm the split survives packaging/publishing.
- **There is NO directory convention** (no `jvm/`/`js/` source-dir magic like sbt-crossproject). scala-cli picks up all `.scala` under the input dirs; you can *organize* files into `src/jvm/`, `src/js/` dirs for readability, but **every platform-specific file must carry its own `target.platform` directive**. Related UX complaint: [issue #1632](https://github.com/VirtusLab/scala-cli/issues/1632).
- **CRITICAL LIMITATION (verified):** `using dep`/`using test.dep` directives written inside a platform-targeted file are NOT scoped to that platform — they leak into all platforms. A `//> using test.dep com.dimafeng::testcontainers-scala-munit::0.43.6` inside a `target.platform jvm` test file made `scala-cli test --js .` fail trying to resolve `testcontainers-scala-munit_sjs1_3` (404). There is no platform-conditional dependency directive.
  - **Verified workaround:** keep JVM-only deps out of directives entirely; mark the tests that need them `//> using target.platform jvm`, and pass the dep on the JVM invocation only: `scala-cli test . --dep com.dimafeng::testcontainers-scala-munit::0.43.6` (passes) while `scala-cli test --js .` stays green. Cost: you can't use single-shot `test --cross` if any platform needs CLI-only deps; run `test .` and `test --js .` as two commands/CI steps.
  - Note for dapr4s: plain **Java** deps (single `:`, e.g. `io.dapr:dapr-sdk`) resolve without platform suffix, so they don't break JS *resolution* — but they would be listed in the published `_sjs1_3` POM, which is wrong for consumers. Main-scope JVM-only deps need the same CLI-flag treatment (or a split project layout) for a clean JS artifact.

## 3. Scala.js options, tests, Node, npm

Directives (from [reference](https://scala-cli.virtuslab.org/docs/reference/directives/), several verified):

```scala
//> using jsVersion 1.21.0        // pins scalajs-library; CANNOT exceed the bundled linker (see below)
//> using jsModuleKind es         // values: commonjs/common, es/esmodule, none/nomodule
//> using jsEsVersionStr es2017   // verified accepted
//> using jsDom true              // or --js-dom flag; uses JSDOMNodeJSEnv
//> using jsEmitWasm true         // WebAssembly backend — VERIFIED WORKING
//> using jsMode <dev|release>    // also jsHeader, jsEmitSourceMaps, jsSmallModuleForPackage...
```

- **Default Scala.js version is fixed per scala-cli release** ([compat table](https://scala-cli.virtuslab.org/docs/guides/advanced/scala-js/)): 1.8.0–1.9.0 → Scala.js 1.19.0; 1.9.1–1.11.0 → 1.20.1; **1.12.0–1.12.5 → 1.20.2; 1.13.0–current (1.14.0) → 1.21.0**. So yes, 1.20.x and 1.21.0 are available.
- **`jsVersion` cannot raise the linker's IR ceiling** (verified): on scala-cli 1.12.2, `//> using jsVersion 1.21.0` still failed with `IRVersionNotSupportedException: ... compiled with Scala.js 1.21 (supported up to: 1.20)`. The linker (scala-js-cli) is pinned per scala-cli release; only same-or-lower jsVersion works. Docs confirm: "In the future, Scala CLI will be able to support any version of Scala.js independently... but for now, there are some limitations".
- **Wasm**: `//> using jsEmitWasm true` + `//> using jsModuleKind es` ran `hello from wasm` successfully on BOTH scala-cli 1.12.2 (Scala.js 1.20.2) and 1.14.0 (1.21.0) with Node 22.20. Feature added in [scala-cli v1.5.2](https://github.com/VirtusLab/scala-cli/releases/tag/v1.5.2) (PR #3255), experimental; backend itself is Scala.js ≥ 1.17.0 experimental ([scala-js wasm docs](https://www.scala-js.org/doc/project/webassembly.html)). Requires ESModule kind.
- **`test --js` runs on Node.js** (plain NodeJSEnv) by default — verified, munit suites execute under Node. With `--js-dom`/`jsDom true` it uses JSDOMNodeJSEnv (jsdom-simulated DOM, still Node).
- **npm module resolution is CWD-based** (verified): scala-cli feeds the launcher to node via **stdin** (failure showed `requireStack: [ '/tmp/[stdin]' ]`), so `require()` resolves against `node_modules` in the **directory you invoke scala-cli from**, NOT the project dir argument and NOT scala-cli's output dir. `npm install jsdom` in the project root + running scala-cli from that root worked (`--js-dom` + mutating `document.title` succeeded); running the same from the parent dir failed; **`NODE_PATH=/path/to/project/node_modules` from elsewhere worked** (verified). Note NODE_PATH only helps CommonJS, not ES modules. scala-cli has no bundler integration ("you'll have to handle bundling yourself" — [Scala.js guide](https://scala-cli.virtuslab.org/docs/guides/advanced/scala-js/)).

## 4. Scala 3 nightlies + Scala.js

**Works.** Verified twice with scala-cli 1.14.0:
- `-S 3.nightly` resolved to `3.10.0-RC1-bin-20260609-b34a019-NIGHTLY`, compiled with Scala.js 1.21.0, ran on Node.
- **dapr4s's exact pinned nightly** `//> using scala 3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` (from `/home/ondra/.t3/worktrees/dapr4s/t3code-c916dd05/project.scala`) compiled for Scala.js 1.21.0 and **passed a munit 1.3.0 + upickle 3.3.1 test on Node**. The JS backend ships inside the Scala 3 compiler; `scala3-library_sjs1_3` exists for nightlies, and `scalajs-library_2.13`/`scalajs-scalalib_2.13` are Scala-3-version-agnostic (a harmless failed probe for a nightly-versioned scalajs-scalalib falls back to the release artifact).

## 5. Publishing: git:dynver + central + JS

- `//> using publish.computeVersion git:dynver` worked for the cross publish (exact tag `v0.1.0` → version `0.1.0` for both `_3` and `_sjs1_3` modules).
- `publish.repository central`: scala-cli ≥ **1.8.4** publishes to Maven Central via the **Central Portal's OSSRH Staging API** ([release notes v1.8.4](https://github.com/VirtusLab/scala-cli/releases/tag/v1.8.4)) — required since [OSSRH sunset June 30, 2025](https://central.sonatype.org/pages/ossrh-eol/). dapr4s already publishes this way (`scala-cli publish .` in `.github/workflows/ci.yml` with `PUBLISH_USER/PASSWORD/SECRET_KEY` secrets, scala-cli-setup@v1 installs latest), so switching to `scala-cli --power publish --cross .` is a one-word CI change.
- No open scala-cli issues found specifically about `publish --cross` + Scala.js breakage; the local cross-publish produced clean per-platform POMs (no Gradle module metadata is published — POM only, which is normal for Scala libs). Residual risk: remote Central staging of two modules in one `--cross` run is untested here — verify on first real release (this matches the docs gap: the [publish docs](https://scala-cli.virtuslab.org/docs/commands/publishing/publish/) don't document `--cross`; it's only in `--help-full`).
- `publish local` caveat from docs: "does not currently support publishing of the test scope."

## 6. Library availability matrix (verified against repo1.maven.org, HTTP codes)

| Artifact | JVM | Scala.js |
|---|---|---|
| `org.scalameta::munit:1.3.0` | `munit_3` ✓ | `munit_sjs1_3` ✓ (200) — **but its IR is Scala.js 1.21, so JS tests REQUIRE scala-cli ≥ 1.13.0**; on 1.12.x the linker fails with `IRVersionNotSupportedException` (verified both ways) |
| `com.lihaoyi::upickle:3.3.1` | ✓ | `upickle_sjs1_3` ✓ (200, compiled+ran) |
| `com.dimafeng::testcontainers-scala-{munit,core}` | `_3` ✓ | `_sjs1_3` **404 — JVM-only, confirmed** |
| `org.testcontainers:testcontainers` | pure Java ✓ | n/a (no Scala suffix at all) |
| `io.dapr:dapr-sdk*` | pure Java | n/a |

## 7. GitHub Actions

Canonical scala-cli job ([cookbook](https://scala-cli.virtuslab.org/docs/cookbooks/introduction/gh-action/)): `actions/checkout` (with `fetch-depth: 0` — required for `git:dynver`) + `coursier/cache-action` + `VirtusLab/scala-cli-setup` (use `with: power: true`), then `scala-cli test .`. For JS jobs:
- ubuntu-latest runners have Node preinstalled; add `actions/setup-node@v4` with `node-version: 22` (or higher) if you need a specific version (recommended for Wasm).
- JS step: `scala-cli test --js .` — no npm setup needed unless you use `--js-dom` or `js.Dynamic.global.require`; in that case `npm ci`/`npm install jsdom` **in the directory the scala-cli command runs from** (cwd-based resolution, see §3).
- `scala-cli publish setup --ci` can generate `.github/workflows/ci.yml` + upload `PUBLISH_USER`, `PUBLISH_PASSWORD`, `PUBLISH_SECRET_KEY`, `PUBLISH_SECRET_KEY_PASSWORD` secrets ([publish-setup docs](https://scala-cli.virtuslab.org/docs/commands/publishing/publish-setup/)) — dapr4s already has the equivalent by hand.

## Exact recipe that worked end-to-end (scala-cli 1.14.0)

`project.scala`:
```scala
//> using scala 3.3.6                       // or the 3.10 nightly — both verified
//> using platform jvm scala-js             // first entry = default platform
//> using dep com.lihaoyi::upickle::3.3.1
//> using test.dep org.scalameta::munit::1.3.0
//> using publish.organization io.github.example
//> using publish.name sjs-probe
//> using publish.computeVersion git:dynver
```
Per-file: `//> using target.platform jvm` / `//> using target.platform scala-js` at the top of platform-specific files. Commands: `scala-cli test .` (+ `--dep` for JVM-only test deps), `scala-cli test --js .`, `scala-cli --power test --cross .` (only if no CLI-only deps), `scala-cli --power publish --cross .`.

## Sources
- [Directives reference](https://scala-cli.virtuslab.org/docs/reference/directives/) — `platform`/`platforms` grammar, `target.platform`, all `js*` directives
- [Scala.js guide + compat table](https://scala-cli.virtuslab.org/docs/guides/advanced/scala-js/)
- [Publish](https://scala-cli.virtuslab.org/docs/commands/publishing/publish/), [Publish setup](https://scala-cli.virtuslab.org/docs/commands/publishing/publish-setup/)
- [Using directives guide (target directives experimental)](https://scala-cli.virtuslab.org/docs/guides/introduction/using-directives)
- [GH Actions cookbook](https://scala-cli.virtuslab.org/docs/cookbooks/introduction/gh-action/)
- [v1.5.2 release (jsEmitWasm, PR #3255)](https://github.com/VirtusLab/scala-cli/releases/tag/v1.5.2), [v1.8.4 release (Central Portal)](https://github.com/VirtusLab/scala-cli/releases/tag/v1.8.4), [Releases index](https://github.com/VirtusLab/scala-cli/releases)
- Issues: [#1632 target.platform warning/scoping](https://github.com/VirtusLab/scala-cli/issues/1632), [#3590](https://github.com/VirtusLab/scala-cli/issues/3590), [#3591](https://github.com/VirtusLab/scala-cli/issues/3591)
- [OSSRH sunset](https://central.sonatype.org/pages/ossrh-eol/), [Scala.js Wasm backend](https://www.scala-js.org/doc/project/webassembly.html), [Scala.js cross-build (sbt baseline)](https://www.scala-js.org/doc/project/cross-build.html)
- Maven Central directory probes for `munit_sjs1_3/1.3.0`, `upickle_sjs1_3/3.3.1`, `testcontainers-scala-*_sjs1_3` (404)
- Local experiments: `/tmp/sjs-probe` (cross compile/test/publish-local, target.platform split, jsdom/NODE_PATH, dep-leak repro), `/tmp/sjs-nightly`+`/tmp/sjs-nightly2` (nightlies incl. dapr4s's exact pin), `/tmp/sjs-wasm` (jsEmitWasm); dapr4s context from `/home/ondra/.t3/worktrees/dapr4s/t3code-c916dd05/project.scala` and `.github/workflows/ci.yml`

## VERDICT
Scala CLI fully supports JVM+Scala.js cross-compiling and cross-publishing from one source tree, verified empirically: declare `//> using platform jvm scala-js` (first entry = default platform; non-default selected via `--js`), mark platform-specific files with experimental `//> using target.platform jvm|scala-js` (per-file directive — there is no directory convention), and publish BOTH `_3` and `_sjs1_3` artifacts in a single `scala-cli --power publish --cross .` invocation (verified with `publish local --cross`: correct per-platform POMs/jars, `git:dynver` versioning works; `publish.repository central` works via the Central Portal since scala-cli 1.8.4). JS tests run on Node (jsdom optional via `--js-dom`, with cwd-based node_modules resolution; NODE_PATH works as fallback), `test --cross` runs both platforms in one shot, the Wasm backend works via `//> using jsEmitWasm true` + `jsModuleKind es` (since v1.5.2), and dapr4s's exact Scala 3.10.0-RC1 nightly compiles/tests on Scala.js 1.21.0 with munit 1.3.0 + upickle 3.3.1 (both have `_sjs1_3` artifacts; testcontainers does not). Two hard constraints: munit 1.3.0's JS artifact needs the Scala.js 1.21 linker, i.e. scala-cli >= 1.13.0 (the locally installed 1.12.2 fails with IRVersionNotSupportedException and `jsVersion` cannot override the bundled linker), and dependency directives cannot be platform-scoped (deps in a `target.platform` file still leak into all platforms), so JVM-only deps (testcontainers, io.dapr SDK) must be passed via CLI flags per-invocation or isolated by project layout.

## BLOCKERS
- scala-cli >= 1.13.0 is required for munit 1.3.0 (and any lib built with Scala.js 1.21 IR) on JS — the locally installed 1.12.2 ships a Scala.js 1.20.2 linker that fails with IRVersionNotSupportedException, and //> using jsVersion cannot raise the bundled linker's IR ceiling (upgrade scala-cli; CI's scala-cli-setup@v1 already installs latest so only local installs are affected)
- No platform-scoped dependency directives exist: using dep / test.dep declared in a //> using target.platform file still apply to ALL platforms (verified failure resolving testcontainers-scala-munit_sjs1_3). JVM-only deps must be passed as CLI flags on JVM-only invocations or isolated via project layout — for dapr4s this affects testcontainers test deps AND the main-scope io.dapr Java SDK deps, which would otherwise pollute the published _sjs1_3 POM. This also means single-shot 'test --cross' can't be used when any platform needs CLI-only deps.
# scala-wasm Component Spike (dapr4s Phase 0)

> Sources: dapr4s Phase-0 spike, 2026-10-12, ported into dapr4s 2026-10-13 ([PR #44](https://github.com/sideeffffect/dapr4s/pull/44): `wasm/`, `src/wasm/`, `test/wasm/`, `scripts/wasm/`); scala-wasm project docs "Limitations", 2026-10-03; Rikito Taniguchi (VirtusLab), 2026-08-24
> Raw: [spike build log and evidence](../../raw/scala-js/2026-10-12-dapr4s-wasm-component-spike-log.md); [scala-wasm Limitations](../../raw/scala-js/2026-10-03-scala-wasm-limitations.md); [Scala and Wasm Component Model](../../raw/scala-js/2026-08-24-virtuslab-scala-wasm-component-model.md)
> Updated: 2026-10-13

## Overview

Phase 0 of [docs/WASM-COMPONENTS-PROPOSAL.md](../../docs/WASM-COMPONENTS-PROPOSAL.md) is done. A counter app was
written against dapr4s 3.0.0's `StateCapability.Instance` + `JsonCodec` and compiled by dapr4s's exact Scala 3
nightly with dapr4s's flags. It linked as a WASI component with the scala-wasm fork (plugin 1.22.1-wasm.6),
composed with dapr-wasm-components 0.7.0's wasi-http providers, ran in `wasmtime serve` next to daprd 1.17.7 +
Redis, and counted 1 → 2. **Verdict: partial go.** The pipeline works today, but only with 5 workarounds.
The central one: no Scala 3 compiler that tracks current scala-wasm can emit WIT IR, so the bindings and the
export glue must be compiled by **Scala 2.13** + the scala-wasm compiler plugin. Two linker gaps need upstream
fixes (a local patch was used for one of them).

## Toolchain facts

- Newest scala-wasm is `1.22.1-wasm.6` on Maven Central (GitHub's newest release: wasm.5). There is no wasm.6 tag;
  its release commit is `e24a46b6f`.
- Bindings: the plugin runs **`wit-bindgen-scala`** (crate 0.1.0), not `wit-bindgen scala`. The `scala` branch of
  scala-wasm/wit-bindgen is archived in its favour. The plugin runs it from `scalaJSWitDirectory` /
  `scalaJSWitWorld` / `scalaJSWitPackage` into `src_managed/main/wit-bindgen`.
- Generated shape: imports are `@WitImport ... = scala.scalajs.wit.native` in `package object <iface>`. For exports
  it generates **types only**: you write `@WitExport(WitScope(...), "fn")` on static object methods, with
  `@WitName` on every parameter. WIT `option`/`result` become `scala.scalajs.wit.Option`/`Result` (`Result` is not
  sealed, so use `.fold`, because a Scala 3 type test on `wit.Err[E]` is an error). `u8` etc. are aliases
  (`UByte = Byte`).
- WIT imports are lowered **at the call site** by the compiler backend (`genWitNativeMemberCall`). So only code
  compiled by a WIT-aware compiler can call an import. Other code calls plain forwarders.
- Components need no `-W` flags on wasmtime 49 (GC + EH are on by default).
- WIT via OCI: `world app { include dapr-wasm-components:interfaces/app@0.7.0; }` + the upstream
  `wkg-config.toml` (namespace → `ghcr.io`, prefix `sideeffffect/`). `wkg wit fetch --config wkg-config.toml`
  (wkg 0.16.1) writes `wit/deps/` + `wkg.lock`. Run it as an sbt task before the bindgen task.

## The five blockers and their workarounds

| # | Blocker | Workaround used | Needed upstream |
|---|---|---|---|
| 1 | The nightly's Scala.js stdlib (`org.scala-js:scalajs-scalalib_2.13:<nightly>`, built on Scala.js 1.20.2) uses JS: `BoxesRunTime.equals` → `(x === y)`, reachable from `Object.toString` | Overlay the `.sjsir` of the 4 files scala-wasm's Scala 3 fork patched (BoxesRunTime, Symbol, ArrayBuilder, Buffer) from `io.github.scala-wasm:scalajs-scalalib_2.13:3.8.3-RC1-wasm.4`, first on the linker classpath (first IR file per class wins) | A pure-Wasm-safe Scala 3 library-js, or a scala-wasm Scala 3 build per nightly |
| 2 | Mainline Scala 3 has no WIT codegen. The nightly compiles the bindings (zero warnings under all dapr4s flags) but emits no WIT IR, and the linker writes an **empty output with `[success]`**. The fork compiler 3.8.3-RC1-wasm.4 is stale and rejects wit-bindgen-scala 0.1.0's output (96× "not compatible with the Component Model") | Bindings + `@WitExport` glue + import forwarders in **Scala 2.13 + plugin** projects. Scala 3 code depends on them | Scala 3 WIT support tracking scala-wasm (the scala-wasm docs: "No Scala 3" until upstreamed) |
| 3 | A reactor has no `main`: module initializers run only behind a configured export, and `Reflect` is JS-backed | Link-time contract: the glue references a fixed-FQN object compiled against a `Provided` stub, and the app defines it. Missing = link error | — (design choice) |
| 4 | Linker: export params via linear memory (> 16 flat params) is `???`. The import side is a no-op `TODO`. Hits `on-topic-event` (22), `on-timer` (17), `jobs.schedule` (24), `converse` (28), `register-reminder` (18), `register-timer` (21) | Local linker 1.22.1-wasm.6-spike1 with a ~20-line export-side patch (load the param tuple with `genLoadMemory`, free it in post-return) | Both sides in scala-wasm |
| 5 | Linker synthesizes `component-type` from dealiased IR. Exported instances lack alias type exports (`metadata`), so `wac` fails with "instance is missing expected list export `metadata`". `withWitDirectory`/`withWitWorld` are ignored | Extract the core module, strip `component-type.*`, `wasm-tools component embed --encoding utf16 wit --world app`, `component new`. **utf16 is mandatory**: with utf8 `counter` arrives as `潣湵整r` | Linker should use the configured WIT world (or keep aliases) |

## What dapr4s's shared code needs on a no-JS target

- Links: dapr4s opaque types, `JsonCodec`, `StateCapability` tiers, `DaprApp`/`InvokeRoute`/validation,
  exceptions, `Charsets`, `java.time.Duration`, `FiniteDuration`, `UUID.fromString`, `String.format`.
  dapr4s's JS wall is never reachable unless something calls `Dapr(...)`.
- Does not link: any `java.time` **formatting/parsing** (`Instant.parse`/`toString`, `ZonedDateTime`, so also
  `WorkflowSnapshot.toString`). The chain is scala-java-time → `Locale.getDefault` → scala-java-locales
  `DefaultLocale` → `global:window`. Also `Instant.now`/`currentTimeMillis` (`Date.now`), `println` (`console`),
  `UUID.randomUUID` (no `SecureRandom`).
- Whole-program trap: once anything instantiates `js.WrappedDictionary`, virtual stdlib calls such as
  `List.groupBy` → `newSpecificBuilder` become JS-tainted everywhere (hit in `DaprAppValidation`).
- POM hygiene: `dapr4s_sjs1_3` brings mainline `org.scala-js:scalajs-library_2.13:1.22.0` + javalib next to the
  fork's. Exclude them, or classpath order decides which IR wins.

## Exports from a library

They work. The 15 `@WitExport` functions can live in a library jar (tested with `exportJars := true`); the linker
treats them as entry points from any classpath entry. The application supplies only a `DaprComponent` via one
fixed-name object. The CC side is ordinary: the wasm `StateCapability` needed exactly the same documented
`asInstanceOf[...Instance]` cast as the JVM/JS walls.

## Runtime and numbers

- Authority (W1) holds through dapr4s: the app imports `state-read` {get} and `state` {save} only, plus type-only
  `types`/`invocation`/`configuration-read`. This holds even with all 11 state operations implemented, because
  the linker drops unreachable forwarders and `component new` drops unused imports.
- Sizes: raw-bindings app 200 KB (fullopt) / 501 KB (fastopt). dapr4s app 498 KB / 1415 KB. Providers 1.12 MB
  (http-out) + 0.72 MB (http-in). Composed 2.0–3.2 MB.
- Cold start (spawn `wasmtime serve` → first 200, uncached): ~0.33 s raw, ~0.8 s dapr4s (Cranelift compile).
  With wasmtime's cache: 25 / 40 ms. Through the sidecar (fresh instance per request): first 1.8–2.9 ms, warm
  median 1.4 ms raw / 2.4 ms dapr4s, incl. a Redis get + save.
- Upstream papercuts: `compose.sh` exits 1 on success (`tmp` set in a `$(...)` subshell, so the EXIT trap
  returns 1). The wasi-http provider's `store-not-found(string)` payload is daprd's raw error body, not the
  store name.

## The code in dapr4s (2026-10-13)

The spike lives in this repo now, as an opt-in prototype (`publishLocal` only, never Maven Central). The rules
are in AGENTS.md "Wasm component layer rules".

- `wasm/` is a **nested sbt build**. The scala-wasm plugin shares classes with mainline `sbt-scalajs`, so it cannot be
  in the root meta-build. Root POMs are unchanged (diffed against master). The build compiles dapr4s's own
  `src/shared` (+ `src/wasm/core`) for the platform instead of depending on the published `dapr4s_sjs1_3`.
- **All 2.13 code is generated** by `wasm/project/WitGlue.scala`, which runs scalameta over the wit-bindgen-scala
  output plus `wasm-tools component wit --json` (wit-bindgen-scala emits only the *types* of exported interfaces).
  The bindings are generated with `scalaJSWitPackage := "dapr4swit.lowered"` (a dotted `--base-package` works). The
  generator then emits a **same-name facade** under `dapr4swit`:
  `type X = lowered.X`, `val X: lowered.X.type = lowered.X` per record/variant/enum, and one forwarder per
  `@WitImport`. Scala 3 code then writes `state_read.get(...)` and `case state_read.GetError.KeyNotFound`.
  **Exhaustivity holds through the aliases** under `-Wconf:any:error` (a deliberately partial match is an E029
  error; the message names the `lowered` FQN). Caveat: a `use`d alias of a record (e.g. `bindings_callback.AppError`)
  has no companion val, as in the raw bindings, so construct it through its owner (`types.AppError(...)`).
- Each imported interface also becomes `trait <Iface>Imports` + `object <Iface>Imports` (the live implementation).
  Each exported interface becomes `trait <Iface>Exports`, implemented in Scala 3 and reached by the generated
  `@WitExport` trampolines through the generated `AppRoot` stub. The wall depends on the traits, so
  `wallWasm/test` (15 munit tests with fakes) runs as **plain Scala.js on Node**.
- Trap: any project whose classpath has the trampolines cannot be linked as plain JS. Every `@WitExport` is a
  top-level export entry point ("Invalid top level export ... when emitting a Script (NoModule)"). So only the
  component projects depend on `witExports`.
- Not generated yet: WIT resources (`crypto` encryptor/decryptor/output, configuration `subscription`). Their
  methods are imports too, so Scala 3 would need generated wrapper classes.
- `scripts/wasm/e2e.sh` automates what the spike checked by hand: the W1 import set, counter 1 → 2 for both
  components, `missing-store` → `ComponentNotFoundException`, and `stale-etag` → `ETagMismatchException` (the stale
  write does not land). CI runs it as a non-gating `wasm` matrix leg, which builds the patched linker
  (`1.22.1-wasm.6-dapr4s.1`) from source.
- The patched linker builds fine on JDK 25 in ~1 min (`scripts/wasm/build-patched-linker.sh`).
- **Packaging for external apps (publishLocal).** The build-level workarounds became an sbt plugin,
  `sbt-dapr4s-wasm` (`wasm/sbt-plugin`), compiled into `wasm/`'s meta-build and published by the same build. Facts
  learned on the way:
  - sbt's coursier `dependencyResolution` is per project and **applies the project's `excludeDependencies`** — a
    `retrieve` of the fork's `scalajs-scalalib_2.13` (the stdlib overlay) through it returns *nothing*, silently,
    because the exclusions drop that exact artifact. Retrieve through `Global / scalaJSLinkerImpl /
    dependencyResolution` instead (as the linker override does), and fail if the overlay comes out empty.
  - Mixed Scala 3 → 2.13 `dependsOn` publishes correctly: the Scala 3 wall's ivy file depends on
    `dapr4s-wasm-wit-facade_sjs1_2.13`. A consumer's own Scala 3 project needs
    `CrossVersion.for3Use2_13With("sjs1_", "")` for a 2.13 artifact (`dapr4sWasmWitExports(v)` in the plugin).
  - `Provided` link-time stubs stay out of a consumer's classpath (ivy conf `provided`), which is what keeps them
    from shadowing the real `AppRoot`/`ComponentRoot` IR.
  - A published plugin can learn its own version from its jar manifest (`Implementation-Version`, written by sbt's
    default `packageOptions`), which `dapr4sWasmVersion` uses for the library dependencies.

## See Also

- [dapr-wasm-components WIT vs dapr4s](../dapr/dapr-wasm-components-vs-dapr4s.md)
- [js.async / js.await, JSPI, and the WebAssembly Backend](scala-js-async-jspi-wasm.md)
- [Capture Checking on Scala.js](capture-checking-on-scala-js.md)
- [ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md)

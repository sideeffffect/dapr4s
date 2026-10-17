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
- **The 2.13 glue is small and hand-written** (`src/wasm/glue*`). The bindings are generated by the scala-wasm
  plugin straight into `dapr4swit` (`scalaJSWitPackage`); Scala 3 code uses their record/variant/enum types
  directly and `case state_read.GetError.KeyNotFound` is exhaustivity-checked. What Scala 3 cannot do is CALL a
  `@WitImport` or DEFINE a `@WitExport`, so 2.13 code holds: `trait StateReadImports` / `StateImports` (+ live
  `object`s, the only import call sites), the 7 `*CallbackExports` traits, the `AppRoot` stub and 15 `@WitExport`
  trampolines. The wall depends on the traits, so `wallWasm/test` (15 munit tests with fakes) runs as **plain
  Scala.js on Node**.
  - History: an earlier iteration generated all of it with scalameta (`WitGlue.scala` in the meta-build: a
    "same-name facade" over bindings in a shadow package `dapr4swit.lowered`, plus traits and trampolines from
    `wasm-tools component wit --json`). Facts from it that still hold: a dotted `--base-package` works;
    exhaustivity holds through type aliases; wit-bindgen-scala emits only the *types* of exported interfaces, not
    their function signatures (so export traits must be written from the WIT); a `use`d alias of a record (e.g.
    `bindings_callback.AppError`) has no companion, so construct it through its owner (`types.AppError(...)`).
    Dropped (2026-10-13) as more machinery than a prototype that touches 6 import functions needs.
- Trap: any project whose classpath has the trampolines cannot be linked as plain JS. Every `@WitExport` is a
  top-level export entry point ("Invalid top level export ... when emitting a Script (NoModule)"). So only the
  component projects depend on `witExports`.
- Not wired yet: WIT resources (`crypto` encryptor/decryptor/output, configuration `subscription`). Their methods
  are imports too, so Scala 3 would need 2.13 wrapper classes.
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
    `dapr4s-wasm-glue_sjs1_2.13`. A consumer's own Scala 3 project needs
    `CrossVersion.for3Use2_13With("sjs1_", "")` for a 2.13 artifact (`dapr4sWasmGlueExports(v)` in the plugin).
  - `Provided` link-time stubs stay out of a consumer's classpath (ivy conf `provided`), which is what keeps them
    from shadowing the real `AppRoot`/`ComponentRoot` IR.
  - The fork's patched `Symbol.scala` also defines `UniquenessCache`/`JSUniquenessCache`; the overlay needs them
    too, or any app creating a `Symbol` (upickle does) fails to link on the nightly's js.Dictionary-backed cache.
  - **Release mode**: the wasm layer can sit on the PUBLISHED `dapr4s_sjs1_3` (Central) instead of dapr4s's
    sources — the scala-wasm linker links that mainline-Scala.js IR fine (the JS wall is unreachable and dropped),
    because the wasm platform traits equal the JS ones (all empty).
  - **Six artifacts, our own linker and overlay (2026-10-13).** The patched linker and the overlay are now
    artifacts too (`dapr4s-wasm-linker_2.12`, `dapr4s-wasm-scalalib-overlay`), so a consumer needs only the plugin
    + `dapr4s-wasm` + its WIT files + `ComponentRoot`. Republishing a third-party jar from sbt: a project whose
    `Compile / packageBin / mappings` are the unzipped jar (minus its `META-INF/MANIFEST.MF`, or the zip writer
    fails on a duplicate entry) + `META-INF/LICENSE`/`NOTICE`, and whose `libraryDependencies` are the original
    POM's compile deps. Merging unpublished helper projects into a jar: add their `packageBin`/`packageSrc`
    mappings and drop them from `projectDependencies` (that only affects the POM/update, not the compile
    classpath) — also the way to keep `Provided` stubs out of a POM.
  - **The released linker's IMPORT side of params-via-memory is missing, not `???`:** `genComponentAdapterFunction`
    has `case Some(offset) => // TODO`, so it pushes nothing and `wasm-tools component new` fails with "type
    mismatch: expected i32 but nothing on stack" — for `jobs.schedule`, `conversation.converse`,
    `actors.register-reminder`/`-timer`. dapr4s's patch now `malloc`s the params tuple (each param aligned, tuple
    aligned to the max), stores each with `ScalaJSToCABI.genStoreMemory` and passes the pointer. Proven against
    daprd: `converse("no-such-llm", ...)` comes back with "failed finding conversation component no-such-llm",
    `register-timer` makes daprd log "can't create timer for actor ProbeActor||probe-1".
  - dapr-wasm-components **0.7.1 wasi-http provider gaps** seen on the way (not ours to fix here): `converse` against
    daprd 1.17's echo component traps (daprd encodes `usage` token counts as JSON strings, the provider expects
    u64); `jobs.schedule` calls `/v1.0/jobs/...`, which daprd 1.17 does not route ("failed getting app id");
    `register-timer` traps on daprd's HTTP 500 instead of returning a WIT error.
  - **0.7.2 (2026-10-13) fixed all three** (WIT unchanged, only the package version moved). The e2e now asserts:
    `converse("echo", ...)` against daprd's built-in `conversation.echo` component (no LLM, no network; one
    component YAML in `scripts/wasm/components/`) answers `ok: hello from scala-wasm`; `schedule` answers
    `ok: scheduled probe-job` once a scheduler runs; `register-reminder` / `register-timer` on an actor type the
    app does not host answer `PermissionDenied(<daprd's message>)`, the timer's naming `ProbeActor||probe-1`.
    Without a scheduler, 0.7.2's `schedule` still traps (daprd: 500 `DAPR_SCHEDULER_SCHEDULE_JOB` "context
    deadline exceeded", which the provider does not map).
  - **0.7.3 – 0.7.5 (2026-10-15 – 2026-10-17)**: WIT unchanged again (only the package version moved),
    `wkg-config.toml` unchanged. 0.7.4 moved every OCI module to `ghcr.io/sideeffffect/dapr-wasm-components/<module>`
    (the flat `dapr-wasm-components-<module>` packages were deleted), but its `compose.sh` still pulled the flat
    names; 0.7.5's `compose.sh` pulls the new ones, so `scripts/wasm/compose.sh` runs upstream `compose.sh` at the
    v0.7.5 tag with `--tag 0.7.5` again (no own provider pull). dapr4s now targets 0.7.5.
  - **A scheduler next to a slim daprd**: `dapr init --slim` installs no placement/scheduler, and daprd dials the
    scheduler at `localhost:50006` by default. `docker run -p 50006:50006 daprio/dapr:<daprd version> ./scheduler
    --port 50006 --etcd-data-dir /tmp/etcd` is enough (ready when its log says "api is ready"); no mTLS needed.
  - daprd 1.17.7 quirk: `GET /v1.0-alpha1/jobs/<name>` of a job scheduled without `data` answers 500
    `ERR_INTERNAL` "failed to encode response as JSON: proto: google.protobuf.Value: none of the oneof fields is
    set" (daprd's bug, not the provider's).
  - A source-file stamp guards rebuilding the linker; picking the built jar by glob once grabbed a STALE jar of an
    older build with a different version in the same target dir — delete old jars before building.
  - A published plugin can learn its own version from its jar manifest (`Implementation-Version`, written by sbt's
    default `packageOptions`), which `dapr4sWasmVersion` uses for the library dependencies.

## See Also

- [dapr-wasm-components WIT vs dapr4s](../dapr/dapr-wasm-components-vs-dapr4s.md)
- [js.async / js.await, JSPI, and the WebAssembly Backend](scala-js-async-jspi-wasm.md)
- [Capture Checking on Scala.js](capture-checking-on-scala-js.md)
- [ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md)

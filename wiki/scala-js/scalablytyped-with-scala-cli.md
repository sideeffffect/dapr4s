# ScalablyTyped Facades with Scala CLI

> Sources: empirical dapr4s ScalablyTyped migration — converter CLI 1.0.0-beta45 runs over @dapr/dapr 3.18.0 / @types/express 4.17.21 / @types/node 22.13.0, runtime-verified against a live daprd sidecar; scripts/generate-st-facades.sh, js-deps.scala, package.json, src/js/internal/ in the dapr4s repo, 2026-06-12
> Raw: none — the verified findings live in the dapr4s repo itself (the script headers, js-deps.scala comments, and ExpressModule.scala scaladoc are the canonical record)
> Updated: 2026-06-12

## Overview

ScalablyTyped (ST) converts TypeScript type definitions into Scala.js facades. Its sbt plugin is the documented path; with **Scala CLI there is no plugin**, but the **converter CLI** works fine as a one-shot generation step: it reads `package.json`/`package-lock.json`/`node_modules` from the working directory, converts every top-level dependency that ships (or has `@types/*`) typings, and **publishes the facade jars to the local ivy repository** (`~/.ivy2/local/org.scalablytyped/...`), which scala-cli resolves with zero configuration. dapr4s replaced its entire hand-written `@dapr/dapr`/express/Node facade layer with this (one shim survives — see below).

```bash
cs launch "org.scalablytyped.converter:cli_3:1.0.0-beta45" -- \
  --scala 3.3.6 --scalajs 1.21.0 -s es2022 --outputPackage dapr4styped
```

Then depend on the printed coordinates: `//> using compileOnly.dep "org.scalablytyped::dapr__dapr::3.18.0-d3e034"` (in a `target.platform "scala-js"`-scoped deps file — see [Cross-Building JVM + Scala.js with Scala CLI](scala-js-cross-building-scala-cli.md); compileOnly because the classes are embedded into the published jar — see the consumer-problem section below).

## Flag landmines (all empirically hit)

- **`--scala 3` is a trap**: it resolves to the *latest* Scala 3 (3.7.3 at the time), under which the converter's `std` (TS stdlib) conversion breaks. **Pin `--scala 3.3.6`.** This does not constrain the consuming build: ST publishes `_sjs1_3` jars, which any Scala 3 compiler (including dapr4s's 3.10 nightly) consumes as ordinary TASTy-bearing dependencies.
- **`--scalajs` needs a full version** (`1.21.0`), not a prefix.
- **`-s es2022`** selects which TS stdlib pieces to convert; the default set includes the **`dom` stdlib, which fails to convert** — `es2022` skips it (fine for Node-only targets).
- **The `typescript` npm package must be installed** in the working directory (the converter drives the TS compiler API). Keep it in `devDependencies` — it is a tool, not a conversion root.
- The converter drops its generated `.scala` sources into `./out` of the working directory. **Delete that scratch tree** after the run (scala-cli would otherwise compile it as project sources); the ivy2Local jars are the only output that matters.

## Conversion roots: `@types/*` must be top-level *dependencies*

The converter converts the packages in `dependencies` — `devDependencies` are skipped. So `@types/express` and `@types/node` go into **`dependencies`** even though at runtime they are type-only. Transitive type-level deps get converted and published too (dapr4s's three roots pull in ~a dozen `org.scalablytyped` jars; you only declare the roots).

## Deterministic digests — the reproducibility contract

Each published coordinate is `org.scalablytyped::<name>::<npmVersion>-<digest>` (e.g. `3.18.0-d1e27c`). The digest is **deterministic in exactly three inputs**: the `package-lock.json` contents, the converter version, and the converter flags. Consequences:

- **Commit `package-lock.json`** — it is what makes every machine produce identical coordinates.
- Pin the converter tuple (version + flags) and the expected digests in one script; make the script **fail loudly** if the deps file and the script's pins drift apart (dapr4s: `scripts/generate-st-facades.sh` greps `js-deps.scala` for its `EXPECTED_*` values before doing anything).
- Make the script **idempotent**: check for a marker jar and exit 0 — re-runs become instant, so "run the script unconditionally" is a valid CI step.
- CI caching: cache `~/.ivy2/local/org.scalablytyped` + `~/.cache/scalablytyped` keyed on the converter tuple + `hashFiles('package-lock.json')`.

Updating a pinned npm version: bump `package.json`, `npm install`, rerun the converter, copy the printed coordinates into both the deps file and the script pins.

## ESM gotchas (the Wasm/JSPI production target is ESM-only)

- **Never reference a deep-module ST object in value position.** ST models deep modules (e.g. `@dapr/dapr/enum/HttpMethod.enum`) with `@JSImport` specifiers that carry no `.js` extension; if the npm package has no `exports` map, Node ESM throws `ERR_MODULE_NOT_FOUND` **at load time**. Deep **types** are fine (erased, no import emitted); for **values** use the root re-exports (`typings.<pkg>.mod.*`), and where no root re-export exists, pin the runtime values by hand with a documented source reference. Compile-green does not catch this — only running under Node ESM does.
- **Callable CJS default exports break ST's entry point.** ST captures a module root as a namespace import, and an ESM `import * as ns` namespace object is **never callable** — so `typings.express.mod.apply()` throws `TypeError: ns is not a function`. The fix is a tiny hand-written `@JSImport("express", JSImport.Default)` shim (callable under both module kinds), typed against the ST-generated types so everything else stays on the generated surface (dapr4s: `src/js/internal/facade/ExpressModule.scala`, which also recovers `express.text` — lost to a `ResolveTypeQueries` converter warning that degrades the member to `Any`).

## ST API shapes you will meet

- **`Partial<...>` options objects become builder-style traits**: `PartialDaprClientOptions().setDaprHost(...).setDaprPort(...)` (MutableBuilder setters), not case-class-like constructors.
- **The TS types are erased and occasionally wrong** — treat ST types as the *signatures* and verify *behaviour* against the installed JS sources. Verified examples from `@dapr/dapr`: `SubscribeConfigurationStream.stop()` is typed `void` but returns a Promise; transaction etags are typed as an `IEtag = {value}` object but go on the wire as plain strings. Where type and runtime diverge, keep the runtime behaviour and document the divergence at the cast site.
- The ST jars are **precompiled with their own flags** — your `-Wconf:any:error`/explicit-nulls/CC flags do not apply to them. Under `-Yexplicit-nulls`, ST results must **not** be `.nn`-ed (it is an error: unnecessary `.nn`).

## The published-library consumer problem — solved by embedding

`org.scalablytyped` coordinates from the CLI exist **only in ivy2Local — they are not on Maven Central**. If you *publish* a Scala.js library whose POM references them, downstream users cannot resolve them from any remote repository. dapr4s's implemented answer (verified end-to-end by publishing locally, hiding `~/.ivy2/local/org.scalablytyped`, and compiling+linking+running a consumer against the published jar alone) is a three-part embedding scheme:

1. **Rename the generated package** with `--outputPackage` (dapr4s: `dapr4styped`) — the classes will ship inside your jar, and a consumer running its own ST generation always gets `typings.*` (with its own `typings.std`/`typings.node`), so the default package would collide at link time. The flag is parsed as a **single `Name`** (`Main.scala`: `Name(x)`); a dotted value is backtick-escaped into one bizarre identifier, not a nested package — use one identifier. The flag is digest-relevant like every other flag.
2. **Declare the ST deps as `//> using compileOnly.dep`** (scala-cli >= 1.14 verified): platform-scoped by `target.platform` exactly like `using dep`, on the compile classpath, but **completely absent from the published POM** (not even scope `provided`). Add as *regular* deps the Central-hosted libraries the generated code links against — `com.olvind::scalablytyped-runtime` and `org.scala-js::scalajs-dom` (versions: read them from a generated ivy-local POM) — which previously arrived transitively through the now-unreferenced ST POMs.
3. **Embed the facade classes at publish time**: resolve the exact transitive `org.scalablytyped` jar set of the roots with `cs fetch` from the deps-file pins (never glob the ivy directory — it accumulates stale digests), unpack only `*.class`/`*.tasty`/`*.sjsir` (no META-INF) into a staging dir, and publish with `scala-cli publish --js . --resource-dirs <staging>` — resource dirs land in the jar as-is. (dapr4s: `scripts/embed-st-facades.sh` → `.scala-build/st-embed`.)

The result: the published `_sjs1_3` jar contains your own sjsir plus the renamed facade tree, the POM references Maven Central only, and consumers compile/link/run with zero ST involvement. Generation remains a build-time prerequisite for the library repo itself.

Alternatives considered:

- **Ship the generation recipe** (dapr4s's previous choice): commit `package.json` + `package-lock.json` + the pinned generation script; consumers run the script once and — thanks to digest determinism — materialise *exactly* the coordinates the POM references in their own ivy2Local. Works, but every consumer pays the converter toll and CI complexity.
- Republish the facades under your own organisation to a real repository (heavier: you own ~a dozen artifacts and their upgrade cadence; the sbt-plugin world solves this with a private Maven repo).
- Vendor the generated sources into your repo (rejected for dapr4s: hundreds of thousands of generated lines, unreviewable diffs).

## See Also

- [Cross-Building JVM + Scala.js with Scala CLI](scala-js-cross-building-scala-cli.md) — the `target.platform`-scoped deps files the facade coordinates live in
- [js.async, JSPI and the Wasm backend](scala-js-async-jspi-wasm.md) — the ESM-only Wasm target these facades must load under
- [Capture Checking on Scala.js](capture-checking-on-scala-js.md) — explicit nulls × facades; CC does not apply to the precompiled ST jars
- [Dapr JS SDK](../dapr/dapr-js-sdk.md) — the runtime behaviour behind the `typings.daprDapr` types (wire formats, soft failures, executor driving rules)

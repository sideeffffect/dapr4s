//> using target.platform "scala-js"
// Scala.js-only main-scope dependencies and settings, the JS twin of jvm-deps.scala: the
// `target.platform` directive above scopes the `using dep`/`using js*` directives in this file
// to the Scala.js platform, so JVM builds never see them.
//
// jsEsVersionStr es2017 is required by js.async/js.await (used by the JS internal layer);
// scala-java-time provides java.time on Scala.js (java.time.Instant is part of the public
// WorkflowSnapshot/Models API) — on the JVM the JDK provides java.time, so neither belongs in
// project.scala.
//> using jsEsVersionStr "es2017"
//> using dep "io.github.cquiroz::scala-java-time::2.6.0"
//
// ==ScalablyTyped-generated facades==
//
// The three compileOnly deps below are Scala.js facades GENERATED from the TypeScript type
// definitions of the npm packages pinned in package.json (@dapr/dapr 3.18.0, @types/express
// 4.17.21, @types/node 22.13.0) by scripts/generate-st-facades.sh, into the dapr4s-specific
// `dapr4styped.*` package (see the script header for why not the default `typings.*`). They
// are published into the LOCAL ivy repository (~/.ivy2/local/org.scalablytyped/...) — never to
// a remote repository and never committed — so every machine that BUILDS dapr4s (developer or
// CI) must run that script once before the first `scala-cli compile --js .` (and again
// whenever the digests change). scala-cli resolves ivy2Local out of the box, no configuration
// needed.
//
// The version suffix after the npm version (e.g. `-d3e034`) is the converter's deterministic
// digest of (package-lock.json contents, converter version, converter flags — --outputPackage
// included). To update:
//   1. change the pinned versions in package.json and run `npm install`,
//   2. run scripts/generate-st-facades.sh — it prints the new coordinates,
//   3. update the three deps below AND the matching digest variables at the top of the script
//      (the script fails loudly if this file and its variables ever disagree).
//
// Consumer note: consumers of the published dapr4s _sjs1_3 artifact need NOTHING beyond Maven
// Central. The facade classes are EMBEDDED in the published jar: at publish time,
// scripts/embed-st-facades.sh unpacks the sjsir/tasty/class entries of every org.scalablytyped
// jar the three roots transitively require into a staging dir that `scala-cli publish
// --resource-dirs` packs into dapr4s_sjs1_3.jar. The deps are `compileOnly.dep` (not `dep`) so
// that the ivy-local-only org.scalablytyped coordinates never appear in the published POM —
// verified: scala-cli 1.14 omits compileOnly deps from the POM entirely (not even scope
// `provided`). Embedding + compileOnly is the whole trick; the two regular deps below it are
// the Central-hosted runtime libraries the generated facade code itself links against, which
// must stay in the POM precisely because the org.scalablytyped POMs that used to carry them
// transitively are gone.
//
//> using compileOnly.dep "org.scalablytyped::dapr__dapr::3.18.0-d3e034"
//> using compileOnly.dep "org.scalablytyped::express::4.17.21-8ee06b"
//> using compileOnly.dep "org.scalablytyped::node::22.13.0-e98bda"
//
// Runtime (link-time) libraries of the EMBEDDED facade classes — versions are exactly what the
// generated org.scalablytyped POMs reference (com.olvind:scalablytyped-runtime_sjs1_3:2.4.2,
// org.scala-js:scalajs-dom_sjs1_3:2.8.1); re-check them in
// ~/.ivy2/local/org.scalablytyped/dapr__dapr_sjs1_3/<version>/poms/ after every regeneration.
//> using dep "com.olvind::scalablytyped-runtime::2.4.2"
//> using dep "org.scala-js::scalajs-dom::2.8.1"

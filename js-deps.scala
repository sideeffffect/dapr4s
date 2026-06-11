//> using target.platform "scala-js"
// Scala.js-only main-scope dependencies, the JS twin of jvm-deps.scala: the `target.platform`
// directive above scopes any `using dep` in this file to the Scala.js platform, so JVM builds
// never resolve them.
//
// ==ScalablyTyped-generated facades==
//
// The three deps below are Scala.js facades GENERATED from the TypeScript type definitions of
// the npm packages pinned in package.json (@dapr/dapr 3.18.0, @types/express 4.17.21,
// @types/node 22.13.0) by scripts/generate-st-facades.sh. They are published into the LOCAL ivy
// repository (~/.ivy2/local/org.scalablytyped/...) — never to a remote repository and never
// committed — so every machine (developer or CI) must run that script once before the first
// `scala-cli compile --js .` (and again whenever the digests change). scala-cli resolves
// ivy2Local out of the box, no configuration needed.
//
// The version suffix after the npm version (e.g. `-d1e27c`) is the converter's deterministic
// digest of (package-lock.json contents, converter version, converter flags). To update:
//   1. change the pinned versions in package.json and run `npm install`,
//   2. run scripts/generate-st-facades.sh — it prints the new coordinates,
//   3. update the three deps below AND the matching digest variables at the top of the script
//      (the script fails loudly if this file and its variables ever disagree).
//
// Consumer note: the published dapr4s _sjs1_3 POM references these org.scalablytyped
// coordinates. They do not exist on Maven Central, so downstream Scala.js users must run the
// same generation (same package-lock.json, same converter version + flags — all shipped in
// this repository) to materialise them in their own ivy2Local before depending on dapr4s JS.
//
//> using dep "org.scalablytyped::dapr__dapr::3.18.0-d1e27c"
//> using dep "org.scalablytyped::express::4.17.21-bf7291"
//> using dep "org.scalablytyped::node::22.13.0-22253f"

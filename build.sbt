import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.{ESVersion, ModuleKind}
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

// =================================================================================================
// dapr4s build
//
// Projects:
//   coreJVM / coreJS          the published library (dapr4s_3 / dapr4s_sjs1_3) + its unit tests
//   integrationJVM / -JS      the Docker-based integration suites (never published)
//   stFacades                 the ScalablyTyped-generated Scala.js facades (never published on its
//                             own; its .sjsir are embedded into dapr4s_sjs1_3, see below)
//
// Source layout (unchanged from the scala-cli days, so the projects point INTO it rather than
// owning it): src/{shared,jvm,js} and test/{shared,jvm,js}. The integration suites are the
// `integration/` subdirectories of the test trees; every other test source is a unit test.
// =================================================================================================

// Always the newest nightly: capture checking and safe mode are still moving fast.
val scala3Nightly = "3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY"

// The ScalablyTyped converter needs a released Scala 3 to target (it renders version-specific
// syntax), and the facades are plain Scala.js code that does not need capture checking — so they
// are compiled with the LTS compiler, exactly as the old CLI pipeline did (`--scala 3.3.6`). The
// nightly compiler reads the older TASTy without complaint.
val scala3Facades = "3.3.6"

val versions = new {
  val daprJava = "1.17.2"
  val munit = "1.3.0"
  val upickle = "3.3.1" // pinned: 4.x crashes on CC-annotated types
  val testcontainersScala = "0.43.6"
  val scalaJavaTime = "2.8.0"
  val scalablytypedRuntime = "2.4.2"
  val scalajsDom = "2.8.1"
}

// -------------------------------------------------------------------------------------------------
// Build-wide metadata (POM). Must stay identical to what scala-cli published up to 1.0.0.
// -------------------------------------------------------------------------------------------------
inThisBuild(
  List(
    scalaVersion := scala3Nightly,
    organization := "com.github.sideeffffect",
    organizationName := "com.github.sideeffffect",
    organizationHomepage := Some(url("https://github.com/sideeffffect/dapr4s/")),
    homepage := Some(url("https://github.com/sideeffffect/dapr4s/")),
    licenses := List("Apache-2.0" -> url("https://spdx.org/licenses/Apache-2.0.html")),
    developers := List(
      Developer("sideeffffect", "Ondra Pelech", "", url("https://github.com/sideeffffect/")),
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/sideeffffect/dapr4s.git"),
        "scm:git:github.com/sideeffffect/dapr4s.git",
        Some("scm:git:git@github.com:sideeffffect/dapr4s.git"),
      ),
    ),
    // dynver (via sbt-ci-release): the version is computed from git, there is no version file.
    // Scala 3 nightlies (compiler + sbt bridge) live in the scala-lang nightly repository, which
    // scala-cli added implicitly. It is a BUILD resolver only: never write it into the POM.
    resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies",
    pomIncludeRepository := { _ => false },
    // scala-cli never wrote test-scope dependencies (munit, upickle, the Scala.js test bridge)
    // into the POM. They are meaningless to consumers; keep the POM identical by dropping them.
    pomPostProcess := { (node: scala.xml.Node) =>
      new scala.xml.transform.RuleTransformer(new scala.xml.transform.RewriteRule {
        override def transform(n: scala.xml.Node): Seq[scala.xml.Node] =
          if (n.label == "dependency" && (n \ "scope").text == "test") Nil else n
      }).transform(node).head
    },
  ),
)

// JDK 25 is required for stable virtual-thread support (no carrier pinning on `synchronized`),
// see AGENTS.md. scala-cli downloaded it on its own (`using jvm "zulu:25.0.3"`); sbt runs on
// whatever JDK launched it, so fail fast with a clear message instead of building on an older one.
Global / onLoad := {
  val old = (Global / onLoad).value
  old.andThen { state =>
    val spec = sys.props.getOrElse("java.specification.version", "0")
    if (scala.util.Try(spec.takeWhile(_.isDigit).toInt).getOrElse(0) < 25)
      sys.error(s"dapr4s needs JDK 25+ (Zulu 25 in CI); sbt is running on Java $spec. Set JAVA_HOME / --java-home.")
    state
  }
}

val rootDir = file(".").getAbsoluteFile

lazy val compilerFlags = Seq(
  "-language:experimental.captureChecking",
  "-language:experimental.pureFunctions",
  "-Ycc-verbose",
  "-Yexplicit-nulls",
  "-experimental",
  "-Wconf:any:error",
)
// Note: -language:experimental.safe is NOT applied globally because non-safe-mode files (Dapr,
// JsonCodec, internal/*) need to use @scala.caps.assumeSafe. Safe mode is enabled per-file via
// `import language.experimental.safe`.

// Sources of a test tree split into unit tests (everything) and integration suites (`integration/`).
// (Relative to the checkout, so a checkout that itself sits under some `integration/` dir still works.)
def isIntegrationSource(f: File): Boolean =
  IO.relativize(rootDir, f).exists(_.split('/').contains("integration"))

lazy val noPublish = Seq(publish / skip := true, publishLocal / skip := true)

// -------------------------------------------------------------------------------------------------
// The library
// -------------------------------------------------------------------------------------------------
lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure) // project dirs are .jvm/.js (target only); sources are wired below
  .in(file("."))
  .settings(
    name := "dapr4s",
    scalacOptions ++= compilerFlags,
    Compile / unmanagedSourceDirectories := Seq(rootDir / "src" / "shared"),
    Test / unmanagedSourceDirectories := Seq(rootDir / "test" / "shared"),
    Compile / unmanagedResourceDirectories := Nil,
    Test / unmanagedResourceDirectories := Nil,
    Test / unmanagedSources := (Test / unmanagedSources).value.filterNot(isIntegrationSource),
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % versions.munit % Test,
      "com.lihaoyi" %%% "upickle" % versions.upickle % Test,
    ),
  )
  .jvmSettings(
    Compile / unmanagedSourceDirectories += rootDir / "src" / "jvm",
    Test / unmanagedSourceDirectories += rootDir / "test" / "jvm",
    libraryDependencies ++= Seq(
      "io.dapr" % "dapr-sdk" % versions.daprJava,
      "io.dapr" % "dapr-sdk-actors" % versions.daprJava,
      "io.dapr" % "dapr-sdk-workflows" % versions.daprJava,
    ),
    Test / fork := true,
    // `coreJVM / Test / assembly`: fat jar of the test classpath, for the example service mains in
    // test/jvm/apps (scripts/k8s-test.sh sets mainClass/assemblyOutputPath per service).
    inConfig(Test)(sbtassembly.AssemblyPlugin.baseAssemblySettings),
    Test / assembly / assemblyMergeStrategy := {
      case PathList(ps @ _*) if ps.lastOption.contains("module-info.class") => MergeStrategy.discard
      // Classes must still be identical across jars (deduplicate); duplicated metadata resources
      // (netty's versions.properties, protobuf .proto copies, ...) only need one copy.
      case x
          if !x.endsWith(".class") && (Test / assembly / assemblyMergeStrategy).value(x) == MergeStrategy.deduplicate =>
        MergeStrategy.first
      case x => (Test / assembly / assemblyMergeStrategy).value(x)
    },
  )
  .jsSettings(
    Compile / unmanagedSourceDirectories += rootDir / "src" / "js",
    Test / unmanagedSourceDirectories += rootDir / "test" / "js",
    // js.async/js.await (the JS internal layer) need ES2017 output.
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2017))),
    libraryDependencies ++= Seq(
      // java.time on Scala.js (java.time.Instant is part of the public WorkflowSnapshot/Models API).
      "io.github.cquiroz" %%% "scala-java-time" % versions.scalaJavaTime,
      // Link-time runtime libraries of the EMBEDDED facade classes (the versions the converter's
      // generated code is written against). They are regular POM dependencies of dapr4s_sjs1_3.
      "com.olvind" %%% "scalablytyped-runtime" % versions.scalablytypedRuntime,
      "org.scala-js" %%% "scalajs-dom" % versions.scalajsDom,
    ),
  )
  .jsConfigure(_.dependsOn(stFacades))
  .jsSettings(
    // stFacades is a COMPILE-TIME dependency only as far as consumers are concerned: its .sjsir
    // are embedded into dapr4s_sjs1_3.jar (below), so the POM must not reference it (it is never
    // published). Dropping it from projectDependencies removes it from the POM/ivy metadata while
    // the dependsOn above keeps its classes on every classpath of this build.
    projectDependencies := projectDependencies.value.filterNot(_.name == (stFacades / moduleName).value),
    Compile / packageBin / mappings ++= embeddedFacades.value,
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

// -------------------------------------------------------------------------------------------------
// Integration suites (Docker). Never published.
//
// They live in their own projects for two reasons: the JS suites must be linked with a different
// linker configuration (Wasm + JSPI, see below) than the plain-JS unit tests, and keeping them out
// of core means `coreJVM/test` / `coreJS/test` ARE the unit-test legs, no --test-only filter needed.
// -------------------------------------------------------------------------------------------------
lazy val integration = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file(".integration"))
  .dependsOn(core % "test->test")
  .settings(noPublish)
  .settings(
    name := "dapr4s-integration",
    scalacOptions ++= compilerFlags,
    // Suites run strictly one after another in ONE runtime: the server-delivery suites share one
    // sidecar + one in-process app server (fixed ports) for the whole run. On Scala.js, parallel
    // execution would spawn one Node worker per suite, each binding the same ports (EADDRINUSE).
    Test / parallelExecution := false,
    Compile / unmanagedSourceDirectories := Nil,
    Test / unmanagedSourceDirectories := Seq(rootDir / "test" / "shared" / "integration"),
    Compile / unmanagedResourceDirectories := Nil,
    Test / unmanagedResourceDirectories := Nil,
  )
  .jvmSettings(
    Test / unmanagedSourceDirectories += rootDir / "test" / "jvm" / "integration",
    libraryDependencies ++= Seq(
      // testcontainers-scala 0.43.6 pulls TC 1.21.1; testcontainers-dapr 1.17.2 pulls TC 1.21.4.
      // Both resolve to 1.21.4 with no conflict. Upgrade to testcontainers-scala 0.44+ only after
      // testcontainers-dapr ships a TC 2.x-compatible release (fix merged to dapr/java-sdk master,
      // awaiting release as v1.18.0).
      "com.dimafeng" %% "testcontainers-scala-munit" % versions.testcontainersScala % Test,
      "io.dapr" % "testcontainers-dapr" % versions.daprJava % Test,
    ),
    // One forked JVM, suites run sequentially in it: the server-delivery suites share ONE sidecar +
    // ONE app server for the whole run (see wiki: dapr4s server IT consolidation).
    Test / fork := true,
  )
  .jsSettings(
    Test / unmanagedSourceDirectories += rootDir / "test" / "js" / "integration",
    // The suites block on the Dapr JS SDK through orphan js.await (JsAwait), which only exists on
    // the WebAssembly backend with JSPI. That needs ES modules, ES2022 and Wasm, plus the explicit
    // JSPI switch (Scala.js >= 1.22; scala-cli exposes none of these, which is why it was pinned).
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
        .withWasmFeatures(_.withUseJSPI(true))
    },
    // Node >= 25: JSPI and Wasm 3.0 are on by default there (checked by the Node version guard in
    // the CI job). The resolve hook appends ".js" to the deep, extensionless CommonJS submodule
    // paths ScalablyTyped emits for the testcontainers facades, which Node's ESM resolver rejects
    // (scripts/js-it/node-resolve-hook.mjs). DAPR4S_REPO_ROOT tells the suites where the canonical
    // component files (scripts/it/) are.
    Test / jsEnv := new NodeJSEnv(
      NodeJSEnv
        .Config()
        .withArgs(List("--import", (rootDir / "scripts" / "js-it" / "node-resolve-hook.mjs").getAbsolutePath))
        .withEnv(Map("DAPR4S_REPO_ROOT" -> rootDir.getAbsolutePath)),
    ),
  )

lazy val integrationJVM = integration.jvm
lazy val integrationJS = integration.js

// -------------------------------------------------------------------------------------------------
// ScalablyTyped facades for @dapr/dapr, express and node (main) plus testcontainers and
// @dapr/testcontainer-node (JS integration suites only), generated from the npm packages pinned in
// package.json / package-lock.json into the dapr4s-specific `dapr4styped.*` package.
//
// WHY a separate project: the generated code is plain Scala.js facade code. It cannot be compiled
// with dapr4s's flags (capture checking, explicit nulls, fatal warnings would all reject it), and
// it is compiled with the Scala LTS the converter targets.
//
// WHY `dapr4styped` and not ScalablyTyped's default `typings`: the facades are EMBEDDED into the
// published jar, and a consumer running their own ScalablyTyped generation (which always emits
// `typings.*`, including its own `typings.std`/`typings.node`) would collide with them at link time.
// -------------------------------------------------------------------------------------------------

// npm packages of package.json "dependencies": the conversion roots. package.json stays the single
// source of truth; the build only reads it.
lazy val npmRoots: Seq[(String, String)] = {
  val json = IO.read(rootDir / "package.json")
  val deps = """(?s)"dependencies"\s*:\s*\{([^}]*)\}""".r
    .findFirstMatchIn(json)
    .getOrElse(sys.error("package.json has no \"dependencies\" object"))
    .group(1)
  """"([^"]+)"\s*:\s*"([^"]+)"""".r.findAllMatchIn(deps).map(m => m.group(1) -> m.group(2)).toList
}

// Installs package-lock.json into <repo>/node_modules (`npm ci`) unless it is already installed.
// The SAME node_modules serves the converter (here) and the JS integration test runtime.
def ensureNodeModules(log: Logger): File = {
  val nodeModules = rootDir / "node_modules"
  val marker = nodeModules / ".package-lock.json" // npm writes it on every install
  if (!marker.exists || marker.lastModified < (rootDir / "package-lock.json").lastModified) {
    log.info("Running `npm ci` in " + rootDir)
    val exit = scala.sys.process.Process(Seq("npm", "ci"), rootDir).!
    if (exit != 0) sys.error(s"`npm ci` failed with exit code $exit")
  }
  nodeModules
}

lazy val stFacades = project
  .in(file(".st-facades"))
  .enablePlugins(ScalablyTypedConverterGenSourcePlugin)
  .settings(noPublish)
  .settings(
    name := "dapr4s-st-facades",
    scalaVersion := scala3Facades,
    stOutputPackage := "dapr4styped",
    stStdlib := List("es2022"),
    Compile / npmDependencies ++= npmRoots,
    // scalajs-bundler would write its own package.json and run a non-locked `npm install` in
    // target/. Instead the converter reads the repo's own node_modules, installed from
    // package-lock.json, so the facades are generated from exactly the modules the runtime loads.
    // The converter looks for `<npmUpdate / crossTarget>/node_modules`, so that path is a symlink.
    Compile / npmUpdate := {
      val nodeModules = ensureNodeModules(streams.value.log)
      val dir = (Compile / npmUpdate / crossTarget).value
      val link = (dir / "node_modules").toPath
      IO.createDirectory(dir)
      if (!java.nio.file.Files.isSymbolicLink(link)) {
        IO.delete(link.toFile)
        java.nio.file.Files.createSymbolicLink(link, nodeModules.toPath)
      }
      dir
    },
    // Facade sources are generated, not documented.
    Compile / doc / sources := Nil,
  )

// The ScalablyTyped library packages (`dapr4styped.<pkg>`) of the main roots' transitive closure:
// these are embedded into dapr4s_sjs1_3. The ones only the testcontainers roots need stay out.
// Every generated package must be in exactly one list; a converter or npm change that adds a
// package fails the build until it is classified (an unclassified MAIN package would otherwise be
// silently missing from the published jar and break consumers at link time).
val mainFacadePackages = Set(
  "daprDapr",
  "express",
  "expressServeStaticCore",
  "googleProtobuf",
  "grpcGrpcJs",
  "grpcProtoLoader",
  "httpErrors",
  "httpTerminator",
  "jsSdslOrderedMap",
  "jsTemporalPolyfill",
  "long",
  "nodeFetch",
  "node",
  "protobufjs",
  "qs",
  "rangeParser",
  "roarr",
  "send",
  "serveStatic",
  "std",
  "typeFest",
  "undiciTypes",
)
val testOnlyFacadePackages = Set(
  "daprTestcontainerNode",
  "dockerCompose",
  "dockerModem",
  "dockerode",
  "ssh2",
  "testcontainers",
  "undici",
)

// ONLY .sjsir is embedded, deliberately not .class/.tasty: consumers only ever LINK against the
// facades (they are internal to dapr4s and never surface in the public API), and embedded .tasty
// would make scaladoc document the full node/express/@dapr typings (a 1.1 GB -javadoc.jar, over
// Central's upload limit: the 0.20.0 publish failure).
lazy val embeddedFacades = Def.task {
  val classes = (stFacades / Compile / classDirectory).value
  val _ = (stFacades / Compile / compile).value
  val root = classes / "dapr4styped"
  val pkgs = IO.listFiles(root).filter(_.isDirectory).map(_.getName).toSet
  val unknown = pkgs -- mainFacadePackages -- testOnlyFacadePackages
  if (unknown.nonEmpty)
    sys.error(
      s"Unclassified ScalablyTyped packages ${unknown.mkString(", ")}: add each to mainFacadePackages or " +
        "testOnlyFacadePackages in build.sbt.",
    )
  val missing = mainFacadePackages -- pkgs
  if (missing.nonEmpty) sys.error(s"Expected ScalablyTyped packages not generated: ${missing.mkString(", ")}")
  val strays = IO.listFiles(root).filterNot(_.isDirectory)
  if (strays.nonEmpty) sys.error(s"Unexpected top-level dapr4styped entries: ${strays.mkString(", ")}")
  mainFacadePackages.toSeq.flatMap { pkg =>
    Path.allSubpaths(root / pkg).collect {
      case (f, rel) if f.getName.endsWith(".sjsir") => f -> s"dapr4styped/$pkg/$rel"
    }
  }
}

lazy val root = project
  .in(file("."))
  .aggregate(coreJVM, coreJS, integrationJVM, integrationJS, stFacades)
  .settings(noPublish)
  .settings(name := "dapr4s-root")

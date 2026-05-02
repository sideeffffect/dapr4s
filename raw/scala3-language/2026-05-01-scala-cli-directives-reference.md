# Scala CLI Directives Reference

> Source: https://scala-cli.virtuslab.org/docs/reference/directives/
> Collected: 2026-05-01
> Published: Unknown

## Using Directives

### Benchmarking Options
Enable JMH benchmarking with `//> using jmh` and specify version via `//> using jmhVersion`.

### BuildInfo
Generate project metadata using `//> using buildInfo`.

### Compiler Options
Configure Scala compiler settings:
- `//> using scalacOption` / `//> using options` for main code
- `//> using test.scalacOption` / `//> using test.options` for tests

Example: `//> using options -Xasync -Xfatal-warnings`

### Compiler Plugins
Add plugins with `//> using plugin org:name:version`
Example: `//> using plugin org.typelevel:::kind-projector:0.13.4`

### Compute Version
Set version computation method: `//> using computeVersion git`, `git:tag`, or `git:dynver`

### Custom JAR
Include JARs via `//> using jar path` or `//> using jars path1 path2`. Test-specific and source variants available.

### Custom Sources
Add sources with `//> using file path` or `//> using files path1 path2`. Supports URLs and local paths.

### Dependency
Declare dependencies: `//> using dep org:name:version`
Scopes: main, test, compileOnly, scalafix
Example: `//> using dep com.lihaoyi::os-lib:0.9.1`

### Exclude Sources
Remove files matching patterns: `//> using exclude *.sc` or `//> using exclude examples/*`

### JVM Version
Specify JVM: `//> using jvm 11`, `temurin:11`, `graalvm:21`, or `system`

### Java Home
Set Java location: `//> using javaHome /path/to/jdk`

### Java Options
Pass runtime options: `//> using javaOpt -Xmx2g` or test variants

### Java Properties
Define properties: `//> using javaProp key=value` or test-specific versions

### Javac Options
Configure Java compiler: `//> using javacOpt -source 1.8 -target 1.8`

### Main Class
Specify entry point: `//> using mainClass HelloWorld`

### ObjectWrapper
Use object wrapper for scripts: `//> using objectWrapper`

### Packaging
Configure output with `packaging.packageType`, `output`, `provided` modules, GraalVM settings, and Docker configuration.

### Platform
Target platforms: `//> using platform scala-js` or `//> using platforms jvm scala-native`

### Publish
Set publishing metadata (organization, name, version, URL, license, VCS, description, developers, and suffix configurations).

### Publish (CI)
Configure CI publishing: `publish.ci.computeVersion`, `repository`, `secretKey`

### Publish (contextual)
Contextual publishing settings including `computeVersion`, `repository`, `secretKey`, and `doc` toggle.

### Python
Enable Python support: `//> using python`

### Repository
Add Maven repositories: `//> using repository jitpack` or `//> using repository https://...`

### Resource Directories
Include resources: `//> using resourceDir ./resources` or test variants

### Scala Native Options
Configure native compilation: garbage collection (`nativeGc`), mode, LTO, version, and compile/linking flags.

### Scala Version
Set version: `//> using scala 3.0.2` or multiple versions

### Scala.js Options
Configure JS compilation: `jsVersion`, `jsMode`, optimization, module kind, source maps, DOM support, and output formatting.

### Test Framework
Specify framework: `//> using testFramework utest.runner.Framework`

### Toolkit
Use Scala Toolkit: `//> using toolkit default` or specific version (0.8.0 default)

### Watch Additional Inputs
Monitor files in watch mode: `//> using watching ./data`

## Target Directives

### Platform
Require platform: `//> using target.platform scala-js`

### Scala Version
Require version: `//> using target.scala 3`

### Scala Version Bounds
Set constraints: `//> using target.scala.>= 2.13`

### Scope
Require scope: `//> using target.scope test`

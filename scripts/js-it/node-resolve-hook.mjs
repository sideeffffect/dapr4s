// Entry point for Node's module-customization hooks, activated by the JS integration test
// environment (build.sbt, integrationJS: `NodeJSEnv` argv `--import <abs path to this file>`).
//
// WHY: ScalablyTyped emits deep, extensionless CommonJS submodule imports for the testcontainers
// facades (e.g. "testcontainers/build/network/network"), which Node's ESM resolver rejects — a
// file import needs its ".js". This registers node-resolve-delegate.mjs (a separate file: the
// hooks module runs on a dedicated loader thread, so self-registration would recurse), which
// retries such failed bare-specifier resolutions with ".js" appended, and from the repo root.
// (Under scala-cli the linked module also lived in a /tmp dir from which the repo's node_modules
// was unreachable; sbt links inside the checkout, so the repo-root retry is now only a fallback.)
import { register } from "node:module";

register(new URL("./node-resolve-delegate.mjs", import.meta.url));

// Entry point for Node's module-customization hooks, activated via
//   NODE_OPTIONS="--import <abs path to this file>"
// by scripts/test-js-integration.sh.
//
// WHY: `scala-cli test` on Scala.js links the test module into a temp dir (e.g.
// /tmp/mainXXXX.mjs/main.js) and runs Node there. ESM resolution of bare specifiers walks up
// from the importing MODULE's own path — it ignores both the working directory and NODE_PATH —
// so `import '@dapr/dapr'` cannot find the repo's node_modules from /tmp. This registers
// node-resolve-delegate.mjs (a separate file: the hooks module runs on a dedicated loader
// thread, so self-registration would recurse), which retries failed bare-specifier resolutions
// with the repo root as the parent.
import { register } from "node:module";

register(new URL("./node-resolve-delegate.mjs", import.meta.url));

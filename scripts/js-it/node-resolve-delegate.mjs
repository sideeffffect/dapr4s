// Module-resolution delegate registered by node-resolve-hook.mjs — see that file for WHY.
//
// Resolution strategy: let Node resolve normally first; only when a BARE specifier (an npm
// package name — not relative, not absolute, not a URL) fails with ERR_MODULE_NOT_FOUND, retry
// from <repo root>/package.json (so Node's walk-up finds the repo's node_modules) AND — for the
// deep, extensionless CommonJS submodule paths ScalablyTyped emits (e.g.
// "@dapr/testcontainer-node/dist/DaprContainer", "testcontainers/build/network/network") — also
// try the ".js" form, which Node's ESM resolver requires for a file import. The main facades
// (@dapr/dapr, express, node) import package ROOTS, which resolve via the first candidate;
// the testcontainers facades the JS integration suites use import deep submodules, which need
// the ".js" retry. DAPR4S_REPO_ROOT is exported by scripts/test-js-integration.sh; the cwd
// fallback covers manual invocations from the repo root.
import { pathToFileURL } from "node:url";

const repoRoot = process.env.DAPR4S_REPO_ROOT ?? process.cwd();
const fallbackParent = pathToFileURL(`${repoRoot}/package.json`).href;

const isBare = (specifier) =>
  !specifier.startsWith(".") &&
  !specifier.startsWith("/") &&
  !specifier.startsWith("file:") &&
  !specifier.startsWith("node:") &&
  !specifier.startsWith("data:");

export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context);
  } catch (err) {
    if (err?.code !== "ERR_MODULE_NOT_FOUND" || !isBare(specifier)) throw err;
    const candidates = specifier.endsWith(".js") ? [specifier] : [specifier, `${specifier}.js`];
    for (const candidate of candidates) {
      try {
        return await nextResolve(candidate, { ...context, parentURL: fallbackParent });
      } catch {
        // try the next candidate
      }
    }
    throw err;
  }
}

// Module-resolution delegate registered by node-resolve-hook.mjs — see that file for WHY.
//
// Resolution strategy: let Node resolve normally first; only when a BARE specifier (an npm
// package name — not relative, not absolute, not a URL) fails with ERR_MODULE_NOT_FOUND, retry
// the resolution as if the import came from <repo root>/package.json, which makes Node's
// walk-up find the repo's node_modules. DAPR4S_REPO_ROOT is exported by
// scripts/test-js-integration.sh; the cwd fallback covers manual invocations from the repo root.
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
    if (err?.code === "ERR_MODULE_NOT_FOUND" && isBare(specifier)) {
      return nextResolve(specifier, { ...context, parentURL: fallbackParent });
    }
    throw err;
  }
}

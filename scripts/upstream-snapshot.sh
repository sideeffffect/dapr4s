#!/usr/bin/env bash
# Builds the UNRELEASED upstream mill-scalablytyped that this build uses, from a pinned commit, and publishes it
# locally (~/.ivy2/local) unless it is there already:
#
#   mill-scalablytyped  0.4.1-14-c21896  main (has https://github.com/lolgab/mill-scalablytyped/pull/50 and /pull/51)
#                                        + /pull/52 merged (branch dapr4s-snapshot of
#                                        github.com/sideeffffect/mill-scalablytyped).
#
# Mill itself is released: Mill 1.3.1 contains https://github.com/com-lihaoyi/mill/pull/7783 (scalaJSOrganization,
# ModuleKind.WasmComponent) and https://github.com/com-lihaoyi/mill/pull/7786, so `./mill` downloads it as usual.
#
# RETIRES when mill-scalablytyped releases these changes: pin the release in build.mill and delete this script and
# its CI steps. CI caches the ~/.ivy2/local organization by the hash of this file, so a new pin rebuilds.
set -euo pipefail

MST_SHA=c218964986ac520c3f0bbf6bcc7e684e1a3d16f2
MST_VERSION=0.4.1-14-c21896

work="${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/upstream-snapshot"
ivy="$HOME/.ivy2/local"

checkout() { # <dir> <upstream url> <fork url> <fork branch> <sha>
  local dir=$1
  if [[ ! -d "$dir/.git" ]]; then
    git clone --quiet "$2" "$dir"
  fi
  git -C "$dir" fetch --quiet --tags origin
  git -C "$dir" fetch --quiet "$3" "$4"
  git -C "$dir" checkout --quiet --force --detach "$5"
}

if [[ -d "$ivy/com.github.lolgab/mill-scalablytyped_mill1_3/$MST_VERSION" ]]; then
  echo "mill-scalablytyped $MST_VERSION: already installed"
else
  echo "mill-scalablytyped $MST_VERSION: building from source"
  checkout "$work/mill-scalablytyped" https://github.com/lolgab/mill-scalablytyped.git \
    https://github.com/sideeffffect/mill-scalablytyped.git dapr4s-snapshot "$MST_SHA"
  (cd "$work/mill-scalablytyped" && ./mill --ticker false __.publishLocal)
  (cd "$work/mill-scalablytyped" && ./mill --ticker false shutdown) || true
  [[ -d "$ivy/com.github.lolgab/mill-scalablytyped_mill1_3/$MST_VERSION" ]] ||
    { echo "mill-scalablytyped did not publish $MST_VERSION" >&2; exit 1; }
fi

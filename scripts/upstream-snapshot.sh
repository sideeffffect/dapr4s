#!/usr/bin/env bash
# Builds the UNRELEASED upstream Mill and mill-scalablytyped that this build uses, from pinned commits, and
# publishes them locally (~/.ivy2/local) unless they are there already:
#
#   Mill                1.3.0-18-28026a  com-lihaoyi/mill main + https://github.com/com-lihaoyi/mill/pull/7783
#                                        (scalaJSOrganization, ModuleKind.WasmComponent); it already contains
#                                        https://github.com/com-lihaoyi/mill/pull/7786 (Scala.js test linking on
#                                        Scala 3.8+). Installed as the launcher's cached download, so `./mill`
#                                        (header `mill-version: 1.3.0-18-28026a-jvm`) finds it and never downloads.
#   mill-scalablytyped  0.4.1-7-ba56bf   https://github.com/lolgab/mill-scalablytyped/pull/50 + /pull/51 + /pull/52
#                                        merged (branch dapr4s-snapshot of github.com/sideeffffect/mill-scalablytyped).
#
# RETIRES when Mill and mill-scalablytyped release these changes: pin the releases in build.mill and delete this
# script and its CI steps. CI caches ~/.cache/mill/download/<version> and the two ~/.ivy2/local organizations by
# the hash of this file, so a new pin rebuilds.
set -euo pipefail

MILL_SHA=28026a334cb0111e2c1ff67b7934153dd6b625d2
MILL_VERSION=1.3.0-18-28026a
MST_SHA=ba56bf068bd5039a220f9b76841631d34684d300
MST_VERSION=0.4.1-7-ba56bf

work="${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/upstream-snapshot"
ivy="$HOME/.ivy2/local"
launcher="${XDG_CACHE_HOME:-$HOME/.cache}/mill/download/$MILL_VERSION"

checkout() { # <dir> <upstream url> <fork url> <fork branch> <sha>
  local dir=$1
  if [[ ! -d "$dir/.git" ]]; then
    git clone --quiet "$2" "$dir"
  fi
  git -C "$dir" fetch --quiet --tags origin
  git -C "$dir" fetch --quiet "$3" "$4"
  git -C "$dir" checkout --quiet --force --detach "$5"
}

if [[ -s "$launcher" && -d "$ivy/com.lihaoyi/mill-libs_3/$MILL_VERSION" ]]; then
  echo "Mill $MILL_VERSION: already installed"
else
  echo "Mill $MILL_VERSION: building from source"
  checkout "$work/mill" https://github.com/com-lihaoyi/mill.git https://github.com/sideeffffect/mill.git \
    scalajs-organization-wasm-component "$MILL_SHA"
  # CI=true: the version ignores local changes, so it is the same on every machine.
  got="$(cd "$work/mill" && CI=true ./mill --ticker false show millVersion | tail -1 | tr -d '"')"
  [[ "$got" == "$MILL_VERSION" ]] || { echo "Mill computed version $got, expected $MILL_VERSION" >&2; exit 1; }
  (cd "$work/mill" && CI=true ./mill --ticker false dist.installLocal --binFile "$work/mill-$MILL_VERSION")
  (cd "$work/mill" && ./mill --ticker false shutdown) || true
  mkdir -p "$(dirname "$launcher")"
  cp "$work/mill-$MILL_VERSION" "$launcher"
  chmod +x "$launcher"
fi

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

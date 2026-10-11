#!/bin/sh
# Probe: run scala-cli 1.14.0 on the JVM (via coursier) instead of the native-image launcher.
exec cs launch scala-cli:1.14.0 -- "$@"

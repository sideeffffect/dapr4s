#!/bin/sh
# Probe: scala-cli 1.14.0 on the JVM with its temp dir (Scala.js linking dir) on tmpfs.
mkdir -p /dev/shm/scala-cli-tmp
exec cs launch --java-opt -Djava.io.tmpdir=/dev/shm/scala-cli-tmp scala-cli:1.14.0 -- "$@"

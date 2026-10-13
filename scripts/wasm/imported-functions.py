#!/usr/bin/env python3
"""Print the functions a component IMPORTS, one interface per line: `<interface>: f1,f2` (sorted).

Reads `wasm-tools component wit <component>`. Used by e2e.sh for the W1 authority check: a component
imports only the WIT functions its code can reach (the linker drops unreachable forwarders and
`wasm-tools component new` drops the then-unused imports), so an app that only calls `get` + `save`
must not import `delete`, `execute-transaction`, ... Interfaces imported for their types only print
with an empty function list.
    scripts/wasm/imported-functions.py wasm/target/components/counter.wasm
"""
import re
import subprocess
import sys

wit = subprocess.run(["wasm-tools", "component", "wit", sys.argv[1]], check=True, capture_output=True, text=True).stdout
imported = set(re.findall(r"^\s*import [\w-]+:[\w-]+/([\w-]+)@", wit, re.M))
funcs, current = {}, None
for line in wit.splitlines():
    m = re.match(r"^\s*interface ([\w-]+) \{", line)
    if m:
        current = m.group(1)
        funcs.setdefault(current, [])
        continue
    m = re.match(r"^\s*([\w-]+): func\(", line)
    if m and current:
        funcs[current].append(m.group(1))
for iface in sorted(imported):
    print(f"{iface}: {','.join(sorted(funcs.get(iface, [])))}")

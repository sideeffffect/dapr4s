#!/usr/bin/env python3
"""Measurements of the wasm test components: sizes, wasmtime compile time, cold start (process spawn ->
first 200 from the app's own port), first request through the sidecar, warm latency through the sidecar.

Needs the composed components (scripts/wasm/e2e.sh, plus `sbt counterRaw/componentWasmFull
counter/componentWasmFull` and compose.sh for the -opt variants) and the e2e Redis on localhost:26480.
Starts its own sidecar-only `dapr run`. WASMTIME_FLAGS="-C cache=n" measures uncached cold starts."""
import os, subprocess, sys, time, statistics, urllib.request, urllib.error, signal

HERE = os.path.dirname(os.path.abspath(__file__))
COMPONENTS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "wasm", "target", "components")
APP_PORT, DAPR_HTTP, DAPR_GRPC = 18087, 3587, 50087
EXTRA = os.environ.get("WASMTIME_FLAGS", "").split()
variants = sys.argv[1:] or ["counterRaw", "counterRaw-opt", "counter", "counter-opt"]

def post(url, timeout=5):
    req = urllib.request.Request(url, method="POST", data=b"")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()

def wait(pred, limit=60.0, step=0.002):
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < limit:
        try:
            if pred(): return time.perf_counter() - t0
        except Exception: pass
        time.sleep(step)
    raise TimeoutError

sidecar = subprocess.Popen(["dapr", "run", "--app-id", "dapr4s-wasm", "--app-port", str(APP_PORT),
    "--dapr-http-port", str(DAPR_HTTP), "--dapr-grpc-port", str(DAPR_GRPC),
    "--resources-path", os.path.join(HERE, "components")],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
try:
    wait(lambda: urllib.request.urlopen(f"http://127.0.0.1:{DAPR_HTTP}/v1.0/healthz/outbound", timeout=1).status == 204)
    print(f"{'variant':16} {'app KB':>8} {'composed KB':>11} {'compile s':>9} {'cold start ms (3 runs)':>24} {'1st via sidecar ms':>18} {'warm median ms':>14}")
    for v in variants:
        app = os.path.join(COMPONENTS, f"{v}.wasm")
        composed = os.path.join(COMPONENTS, f"composed-{v}.wasm")
        t = time.perf_counter()
        subprocess.run(["wasmtime", "compile", composed, "-o", "/tmp/dapr4s-wasm-precompiled.cwasm"], check=True)
        compile_s = time.perf_counter() - t
        colds, firsts, warms = [], [], []
        for run in range(3):
            t0 = time.perf_counter()
            p = subprocess.Popen(["wasmtime", "serve", *EXTRA, "-S", "cli", "--addr", f"127.0.0.1:{APP_PORT}",
                "--env", f"DAPR_HTTP_PORT={DAPR_HTTP}", composed],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                wait(lambda: post(f"http://127.0.0.1:{APP_PORT}/counter", timeout=2)[0] == 200)
                colds.append((time.perf_counter() - t0) * 1000)
                t1 = time.perf_counter(); post(f"http://127.0.0.1:{DAPR_HTTP}/v1.0/invoke/dapr4s-wasm/method/counter")
                firsts.append((time.perf_counter() - t1) * 1000)
                if run == 2:
                    for _ in range(50):
                        t2 = time.perf_counter(); post(f"http://127.0.0.1:{DAPR_HTTP}/v1.0/invoke/dapr4s-wasm/method/counter")
                        warms.append((time.perf_counter() - t2) * 1000)
            finally:
                p.kill(); p.wait(10)
                time.sleep(0.3)
        print(f"{v:16} {os.path.getsize(app)/1024:8.0f} {os.path.getsize(composed)/1024:11.0f} {compile_s:9.2f} "
              f"{' / '.join(f'{c:.0f}' for c in colds):>24} {statistics.median(firsts):18.1f} {statistics.median(warms):14.2f}")
finally:
    os.killpg(sidecar.pid, signal.SIGTERM)
    subprocess.run(["dapr", "stop", "--app-id", "dapr4s-wasm"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

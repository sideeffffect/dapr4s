// Probe-only: attach to a hung node via the inspector (opened with SIGUSR1) and dump what it waits on.
const list = await (await fetch("http://127.0.0.1:9229/json/list")).json();
console.log("targets:", JSON.stringify(list.map((t) => ({ type: t.type, title: t.title, url: t.url }))));
const ws = new WebSocket(list[0].webSocketDebuggerUrl);
let id = 0;
const send = (method, params = {}) =>
  new Promise((res) => {
    const myId = ++id;
    const h = (m) => { const d = JSON.parse(m.data); if (d.id === myId) { ws.removeEventListener("message", h); res(d); } };
    ws.addEventListener("message", h);
    ws.send(JSON.stringify({ id: myId, method, params }));
  });
ws.onopen = async () => {
  const expr = `JSON.stringify({
    resources: process.getActiveResourcesInfo(),
    argv: process.argv, execArgv: process.execArgv, nodeOptions: process.env.NODE_OPTIONS,
    handles: process.report.getReport().libuv.filter(h => h.is_active || h.is_referenced),
    workers: process.report.getReport().workers?.length
  }, null, 1)`;
  const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true });
  console.log(r.result?.result?.value ?? JSON.stringify(r).slice(0, 4000));
  process.exit(0);
};
setTimeout(() => { console.log("inspector timeout"); process.exit(1); }, 15000);

// Cliente: mide latencia observada por el cliente en tres modos de transporte sobre loopback.
const https = require('https');
const http2 = require('http2');
const N = parseInt(process.env.N || '1500');
const payload = () => JSON.stringify({
  templateKey: 'welcome', templateVersion: null,
  to: { email: 'ana@example.com', name: 'Ana Pérez' }, cc: [], bcc: [], replyTo: 'soporte@colmena.cr',
  variables: { firstName: 'Ana', activationUrl: 'https://colmena.cr/activate?t=abc123def456', expiresInHours: 24 },
  tags: ['signup', 'onboarding'], metadata: { userId: 'u_8817', requestSource: 'web' }
});
const headers = (i) => ({ 'content-type': 'application/json', authorization: 'Bearer esk_live_7fA2_' + 'S'.repeat(43), 'idempotency-key': 'k-' + process.pid + '-' + Date.now() + '-' + i });
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };
const now = () => Number(process.hrtime.bigint()) / 1e6;

function h1(agent, i) {
  return new Promise((resolve, reject) => {
    const body = payload();
    const t = now();
    const req = https.request({ host: 'localhost', port: 8443, path: '/v1/emails', method: 'POST', agent, rejectUnauthorized: false, headers: { ...headers(i), 'content-length': Buffer.byteLength(body) } }, res => {
      res.resume(); res.on('end', () => res.statusCode === 202 ? resolve(now() - t) : reject(new Error('status ' + res.statusCode)));
    });
    req.on('error', reject); req.end(body);
  });
}
function h2req(session, i) {
  return new Promise((resolve, reject) => {
    const body = payload(); const t = now();
    const s = session.request({ ':method': 'POST', ':path': '/v1/emails', ...headers(i) });
    let st; s.on('response', h => st = h[':status']); s.resume();
    s.on('end', () => st === 202 ? resolve(now() - t) : reject(new Error('h2 ' + st))); s.on('error', reject); s.end(body);
  });
}
async function get(path) { return new Promise(r => https.get({ host: 'localhost', port: 8443, path, rejectUnauthorized: false, agent: false }, res => { let d = ''; res.on('data', c => d += c); res.on('end', () => r(d)); })); }

async function run(name, fn) {
  await get('/reset');
  const lat = []; for (let i = 0; i < N; i++) lat.push(await fn(i));
  const st = JSON.parse(await get('/stats'));
  console.log(JSON.stringify({ mode: name, client_p50: +pct(lat, 50).toFixed(3), client_p95: +pct(lat, 95).toFixed(3), client_p99: +pct(lat, 99).toFixed(3),
    server_total_p50: +st.total.p50.toFixed(3), server_total_p95: +st.total.p95.toFixed(3), db_p50: +st.db.p50.toFixed(3), db_p95: +st.db.p95.toFixed(3),
    parse_p50_us: +(st.parse.p50 * 1000).toFixed(1), auth_p50_us: +(st.auth.p50 * 1000).toFixed(1), validate_p50_us: +(st.validate.p50 * 1000).toFixed(1), respond_p50_us: +(st.respond.p50 * 1000).toFixed(1) }));
}
async function throughput(name, conc, mk) {
  const total = 4000; let i = 0; const t = now(); const lat = [];
  await Promise.all(Array.from({ length: conc }, async () => { const f = mk(); while (i < total) { const k = i++; lat.push(await f(k)); } }));
  const secs = (now() - t) / 1000;
  console.log(JSON.stringify({ mode: name, concurrency: conc, req_per_s: Math.round(total / secs), p95_ms: +pct(lat, 95).toFixed(2) }));
}
(async () => {
  const noKA = new https.Agent({ keepAlive: false, maxSockets: 1 });
  const KA = new https.Agent({ keepAlive: true, maxSockets: 1 });
  for (let w = 0; w < 300; w++) await h1(KA, 'w' + w); // warm-up JIT y pool
  await run('https1.1_nueva_conexion_TLS_por_peticion', i => h1(noKA, i));
  await run('https1.1_keep_alive', i => h1(KA, i));
  const sess = http2.connect('https://localhost:8443', { rejectUnauthorized: false });
  await run('http2_una_sesion', i => h2req(sess, i));
  await throughput('https1.1_keep_alive', 16, () => { const a = new https.Agent({ keepAlive: true, maxSockets: 1 }); return i => h1(a, i); });
  await throughput('http2_una_sesion_multiplexada', 16, () => i => h2req(sess, i));
  sess.close();
})();

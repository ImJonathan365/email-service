// Servidor mínimo que reproduce el camino de aceptación de POST /v1/emails del email-service:
// parse JSON -> auth (SHA-256 + comparación en tiempo constante, con caché) -> validación ->
// transacción PG (rate limit minuto+día con UPSERT, INSERT idempotente) -> COMMIT -> 202 JSON.
const http2 = require('http2');
const fs = require('fs');
const crypto = require('crypto');
const { Pool } = require('pg');

const pool = new Pool({ host: '/tmp', port: 55432, user: 'bench', database: 'postgres', max: 10 });
const TENANT = '0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90';
const TPL = '0199c2f1-0000-7a3e-9c44-2f7a1e5d8b90';
const SECRET = 'S'.repeat(43);
const KEYHASH = crypto.createHash('sha256').update(SECRET).digest();
const phases = { parse: [], auth: [], validate: [], db: [], respond: [], total: [] };
const EMAIL_RE = /^[^\s@\r\n]+@[^\s@\r\n]+\.[^\s@\r\n]+$/;
const ns = () => process.hrtime.bigint();
const ms = (a, b) => Number(b - a) / 1e6;

async function accept(headers, raw) {
  const t0 = ns();
  const body = JSON.parse(raw);
  const t1 = ns();
  const auth = headers['authorization'] || '';
  const secret = auth.split('_').pop();
  const h = crypto.createHash('sha256').update(secret).digest();
  if (!crypto.timingSafeEqual(h, KEYHASH)) throw Object.assign(new Error('401'), { status: 401 });
  const t2 = ns();
  if (!body.templateKey || !body.to || !EMAIL_RE.test(body.to.email)) throw Object.assign(new Error('422'), { status: 422 });
  if (typeof body.variables !== 'object') throw Object.assign(new Error('422'), { status: 422 });
  const canon = JSON.stringify(body);
  const reqHash = crypto.createHash('sha256').update(canon).digest('hex');
  const t3 = ns();
  const c = await pool.connect();
  let id;
  try {
    await c.query('BEGIN');
    await c.query(`INSERT INTO rate_limit_counter VALUES ($1,'MINUTE',date_trunc('minute',now()),1)
                   ON CONFLICT (tenant_id,window_kind,window_start) DO UPDATE SET count=rate_limit_counter.count+1 RETURNING count`, [TENANT]);
    await c.query(`INSERT INTO rate_limit_counter VALUES ($1,'DAY',date_trunc('day',now()),1)
                   ON CONFLICT (tenant_id,window_kind,window_start) DO UPDATE SET count=rate_limit_counter.count+1 RETURNING count`, [TENANT]);
    id = crypto.randomUUID();
    const r = await c.query(`INSERT INTO email_message(id,tenant_id,idempotency_key,request_hash,template_id,template_version_id,
        to_email,to_name,from_email,from_name,variables,status,tags,metadata)
        VALUES ($1,$2,$3,$4,$5,$5,$6,$7,'no-reply@x.com','X',$8,'QUEUED',$9,$10)
        ON CONFLICT (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING RETURNING id, created_at`,
      [id, TENANT, headers['idempotency-key'] || null, reqHash, TPL, body.to.email, body.to.name, body.variables, body.tags, body.metadata]);
    await c.query('COMMIT');
    if (r.rowCount === 0) id = 'dup';
  } catch (e) { await c.query('ROLLBACK'); throw e; } finally { c.release(); }
  const t4 = ns();
  const out = JSON.stringify({ id, status: 'QUEUED', templateKey: body.templateKey, templateVersion: 3, to: body.to.email, createdAt: new Date().toISOString() });
  const t5 = ns();
  phases.parse.push(ms(t0, t1)); phases.auth.push(ms(t1, t2)); phases.validate.push(ms(t2, t3));
  phases.db.push(ms(t3, t4)); phases.respond.push(ms(t4, t5)); phases.total.push(ms(t0, t5));
  return out;
}

function pct(a, p) { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; }

const server = http2.createSecureServer({ key: fs.readFileSync(__dirname + '/key.pem'), cert: fs.readFileSync(__dirname + '/cert.pem'), allowHTTP1: true });
server.on('request', (req, res) => {
  if (req.url === '/stats') {
    const o = {}; for (const k in phases) o[k] = { n: phases[k].length, p50: pct(phases[k], 50), p95: pct(phases[k], 95), p99: pct(phases[k], 99) };
    res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify(o));
  }
  if (req.url === '/reset') { for (const k in phases) phases[k] = []; res.writeHead(204); return res.end(); }
  const chunks = []; req.on('data', d => chunks.push(d));
  req.on('end', async () => {
    try { const out = await accept(req.headers, Buffer.concat(chunks).toString()); res.writeHead(202, { 'content-type': 'application/json' }); res.end(out); }
    catch (e) { res.writeHead(e.status || 500); res.end(String(e.message)); }
  });
});
server.listen(8443, () => console.log('listening 8443'));

const { Pool } = require('pg'); const crypto=require('crypto');
const pool = new Pool({ host:'/tmp', port:55432, user:'bench', database:'postgres', max:2 });
const T='0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90';
const ins = (c,i)=>c.query(`INSERT INTO email_message(id,tenant_id,idempotency_key,template_id,template_version_id,to_email,from_email,from_name,variables,status)
 VALUES ($1,$2,$3,$2,$2,'a@b.com','x@y.com','X','{"a":1}','QUEUED') ON CONFLICT (tenant_id,idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING`,[crypto.randomUUID(),T,'b'+Date.now()+Math.random()]);
(async()=>{ const c=await pool.connect(); const N=100;
 for(let r=0;r<3;r++){
 let t=process.hrtime.bigint(); for(let i=0;i<N;i++){await c.query('BEGIN');await ins(c,i);await c.query('COMMIT');} const a=Number(process.hrtime.bigint()-t)/1e6;
 t=process.hrtime.bigint(); await c.query('BEGIN'); for(let i=0;i<N;i++) await ins(c,i); await c.query('COMMIT'); const b=Number(process.hrtime.bigint()-t)/1e6;
 console.log(`100 mensajes: 100 transacciones = ${a.toFixed(1)} ms (${(a/N).toFixed(2)} ms/msg); 1 transacción = ${b.toFixed(1)} ms (${(b/N).toFixed(2)} ms/msg)`);}
 c.release(); await pool.end(); })();

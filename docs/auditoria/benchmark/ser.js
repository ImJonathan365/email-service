const { pack, unpack } = require('msgpackr'); const cbor = require('cbor-x'); const protobuf = require('protobufjs');
const obj = { templateKey:'welcome', to:{email:'ana@example.com',name:'Ana Pérez'}, cc:[], bcc:[], replyTo:'soporte@colmena.cr',
 variables:{firstName:'Ana',activationUrl:'https://colmena.cr/activate?t=abc123def456',expiresInHours:24}, tags:['signup','onboarding'], metadata:{userId:'u_8817',requestSource:'web'} };
const root = protobuf.parse(`syntax="proto3"; message Addr{string email=1;string name=2;}
message Send{string templateKey=1; Addr to=2; repeated string cc=3; repeated string bcc=4; string replyTo=5; map<string,string> variables=6; repeated string tags=7; map<string,string> metadata=8;}`).root;
const Send = root.lookupType('Send'); const pobj = {...obj, variables:{firstName:'Ana',activationUrl:'https://colmena.cr/activate?t=abc123def456',expiresInHours:'24'}};
const M=200000; function bench(name, enc, dec){ let b=enc(); for(let i=0;i<20000;i++) dec(enc()); const t=process.hrtime.bigint(); for(let i=0;i<M;i++) dec(enc()); const us=Number(process.hrtime.bigint()-t)/1e3/M; console.log(name.padEnd(10), 'bytes', String(b.length).padStart(4), ' encode+decode µs', us.toFixed(2)); }
bench('JSON', ()=>Buffer.from(JSON.stringify(obj)), b=>JSON.parse(b.toString()));
bench('msgpack', ()=>pack(obj), b=>unpack(b));
bench('CBOR', ()=>cbor.encode(obj), b=>cbor.decode(b));
bench('protobuf', ()=>Send.encode(Send.fromObject(pobj)).finish(), b=>Send.toObject(Send.decode(b)));
const z=require('zlib'); const j=Buffer.from(JSON.stringify(obj)); console.log('JSON gzip bytes', z.gzipSync(j).length);

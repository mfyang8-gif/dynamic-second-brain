const DOCKER = 'http://192.168.88.130:2375';
const api = async (p, i) => { const r = await fetch(DOCKER+p, {...i, headers:{'Content-Type':'application/json',...(i?.headers||{})}}); const t = await r.text(); if(!r.ok) throw new Error(r.status+' '+t); return t?JSON.parse(t):null; };
const raw = async (p, i) => (await fetch(DOCKER+p, {...i, headers:{'Content-Type':'application/json',...(i?.headers||{})}})).arrayBuffer().then(Buffer.from);
const demux = (b) => { const parts=[]; let i=0; while(i+8<=b.length){const len=b.readUInt32BE(i+4); if(len<=0||i+8+len>b.length)break; parts.push(b.subarray(i+8,i+8+len)); i+=8+len;} return Buffer.concat(parts.length?parts:[b]).toString('utf8'); };
const ex = async (c, cmd) => { const cr = await api(`/containers/${encodeURIComponent(c)}/exec`, {method:'POST', body:JSON.stringify({AttachStdout:true,AttachStderr:true,Cmd:cmd})}); return demux(await raw(`/exec/${cr.Id}/start`, {method:'POST', body:JSON.stringify({Detach:false,Tty:false})})).replace(/\r/g,''); };
const main = async () => {
  for (const c of ['redis-stack','myredis']) {
    const info = await ex(c, ['sh','-c','redis-cli config get port; redis-cli dbsize; redis-cli info server | grep -m1 tcp_port']);
    console.log(`=== ${c} ===\n${info.trim()}\n`);
  }
};
main().catch(e=>{console.error('失败：',e.message);process.exit(1);});

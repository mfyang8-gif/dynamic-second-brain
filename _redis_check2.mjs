const DOCKER = 'http://192.168.88.130:2375';
const api = async (p, i) => { const r = await fetch(DOCKER+p, {...i, headers:{'Content-Type':'application/json',...(i?.headers||{})}}); const t = await r.text(); if(!r.ok) throw new Error(r.status+' '+t); return t?JSON.parse(t):null; };
const raw = async (p, i) => (await fetch(DOCKER+p, {...i, headers:{'Content-Type':'application/json',...(i?.headers||{})}})).arrayBuffer().then(Buffer.from);
const demux = (b) => { const parts=[]; let i=0; while(i+8<=b.length){const len=b.readUInt32BE(i+4); if(len<=0||i+8+len>b.length)break; parts.push(b.subarray(i+8,i+8+len)); i+=8+len;} return Buffer.concat(parts.length?parts:[b]).toString('utf8'); };
const ex = async (c, cmd) => { const cr = await api(`/containers/${encodeURIComponent(c)}/exec`, {method:'POST', body:JSON.stringify({AttachStdout:true,AttachStderr:true,Cmd:cmd})}); return demux(await raw(`/exec/${cr.Id}/start`, {method:'POST', body:JSON.stringify({Detach:false,Tty:false})})).replace(/\r/g,''); };
const main = async () => {
  const c = 'redis-stack';
  console.log('--- 各前缀分组计数 ---');
  console.log((await ex(c, ['sh','-c',`redis-cli --scan --count 1000 | awk -F: '{print $1}' | sort | uniq -c | sort -rn | head -12`])).trim());
  console.log('\n--- 找出会话记忆 key（32位hex且无前缀）并看内容与 TTL ---');
  console.log((await ex(c, ['sh','-c',`k=$(redis-cli --scan --count 1000 | grep -E '^[0-9a-f]{32}$' | head -1); echo "key=$k"; echo "type=$(redis-cli type $k)"; echo "ttl=$(redis-cli ttl $k)"; redis-cli get $k | head -c 600; echo`])).trim());
  console.log('\n--- 是否存在 chat-memory / chat:memory 前缀 ---');
  console.log((await ex(c, ['sh','-c',`redis-cli --scan --count 1000 | grep -iE 'memor|langchain4j' | head -10; echo "(空=无此前缀)"`])).trim());
};
main().catch(e=>{console.error('失败：',e.message);process.exit(1);});

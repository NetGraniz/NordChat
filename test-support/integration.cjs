'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const mineflayer = require('mineflayer')
const root = path.resolve(process.argv[2] || '')
const java = process.argv[3]
assert.equal(root, 'C:\\Users\\artyo\\Documents\\Codex\\nordchat-test-20261004')
assert(java)
const results = [], children = [], clients = new Set()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let paper, proxy, seq = 0
const source = path.resolve(__dirname, '..')
const preferences = path.join(root, 'paper', 'plugins', 'NordChat', 'players.yml')
async function until(fn, label, timeout = 15000) {
  const start = Date.now()
  while (!await fn()) { if (Date.now() - start > timeout) throw Error('Timeout: ' + label); await sleep(50) }
}
function pass(name) { results.push(name); console.log('PASS: ' + name) }
function fixture() {
  const previous = 'C:\\Users\\artyo\\Documents\\Codex\\nordqueue-test-20261003'
  const secret = require('node:crypto').randomBytes(32).toString('hex')
  for (const sub of ['paper/plugins/NordChat', 'paper/plugins/NordFilter', 'paper/config', 'proxy/plugins/nordqueue'])
    fs.mkdirSync(path.join(root, sub), { recursive: true })
  fs.copyFileSync(path.join(source, 'build/NordChat-0.1.3.jar'), path.join(root, 'paper/plugins/NordChat-0.1.3.jar'))
  fs.copyFileSync(path.join(__dirname, 'build/ChatTestProbe.jar'), path.join(root, 'paper/plugins/ChatTestProbe.jar'))
  fs.copyFileSync('Z:\\Minecraft server\\plugins\\NordFilter-1.0.2.jar', path.join(root, 'paper/plugins/NordFilter-1.0.2.jar'))
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordQueue\\releases\\1.1.2\\NordQueue-1.1.2.jar', path.join(root, 'proxy/plugins/NordQueue-1.1.2.jar'))
  fs.writeFileSync(path.join(root, 'paper/eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(root, 'paper/server.properties'), [
    'server-ip=127.0.0.1','server-port=25666','online-mode=false','enforce-secure-profile=false',
    'max-players=20','view-distance=2','simulation-distance=2','enable-rcon=false','enable-query=false',
    'level-name=nordchat-synthetic-world','level-type=minecraft:flat','generate-structures=false',
    'spawn-protection=0','pause-when-empty-seconds=-1','gamemode=creative','force-gamemode=true','difficulty=peaceful',''].join('\n'))
  const original = fs.readFileSync(path.join(previous,'paper/config/paper-global.yml'), 'utf8')
  const velocitySection = /  velocity:\r?\n    enabled: (?:true|false)\r?\n    online-mode: (?:true|false)\r?\n    secret: [^\r\n]*/
  assert(velocitySection.test(original))
  fs.writeFileSync(path.join(root, 'paper/config/paper-global.yml'), original.replace(velocitySection,
    '  velocity:\n    enabled: true\n    online-mode: false\n    secret: "'+secret+'"'))
  fs.writeFileSync(path.join(root, 'paper/plugins/NordChat/config.yml'),
    'temporary-ignore-days: 7\nprivate-message-cooldown-millis: 500\nclickable-chat-names: true\n')
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordFilter\\src\\main\\resources\\config.yml', path.join(root, 'paper/plugins/NordFilter/config.yml'))
  fs.writeFileSync(path.join(root, 'paper/plugins/NordFilter/banwords.yml'), 'words:\n  - syntheticforbidden\n')
  fs.writeFileSync(preferences, '{}\n') // Only the exact isolated synthetic store.
  fs.writeFileSync(path.join(root, 'proxy/local-test-forwarding.secret'), secret)
  let velocity = fs.readFileSync(path.join(previous,'proxy/velocity.toml'),'utf8')
    .replaceAll('25615','25665').replaceAll('25616','25666').replaceAll('25617','25667')
    .replace(/player-info-forwarding-mode = "[^"]+"/,'player-info-forwarding-mode = "modern"')
  fs.writeFileSync(path.join(root,'proxy/velocity.toml'),velocity)
  fs.writeFileSync(path.join(root, 'proxy/plugins/nordqueue/config.properties'),
    'main-capacity=20\nminimum-wait-seconds=1\ntransfer-interval-seconds=1\n')
  let limbo = fs.readFileSync(path.join(previous,'queue/settings.yml'),'utf8').replaceAll('25617','25667')
    .replace(/  type: (?:NONE|MODERN)/,'  type: MODERN').replace(/  secret: "[^"]+"/,'  secret: "'+secret+'"')
  assert(limbo.includes('type: MODERN'))
  fs.writeFileSync(path.join(root,'queue/settings.yml'),limbo)
}
function start(name, jar, heap) {
  const child = spawn(java, ['-Xms64M', '-Xmx' + heap, '-jar', jar, ...(name === 'paper' ? ['nogui'] : [])],
    { cwd: path.join(root, name), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const handle = { child, name, output: '', exited: false, index: children.filter(c => c.name === name).length }
  children.push(handle)
  child.stdout.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.stderr.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.on('exit', () => { handle.exited = true })
  child.on('error', e => { handle.output += String(e); handle.exited = true })
  return handle
}
async function startPaper() {
  paper = start('paper', 'server.jar', '2G')
  await until(() => /Done \(/.test(paper.output) || paper.exited, 'Paper startup', 120000)
  assert(!paper.exited, paper.output.slice(-3000))
  assert.match(paper.output, /Enabling NordChat v0\.1\.3/)
}
function connect(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25665, username: name, auth: 'offline',
    version: '26.2', hideErrors: true, checkTimeoutInterval: 30000 })
  const c = { bot, name, messages: [], errors: [], ended: false }
  clients.add(c)
  // Mineflayer retains the signed original in messagestr; a real client displays
  // the unsigned decorated component when Paper rewrites/renders a message.
  bot.on('message', m => c.messages.push(m.unsigned ? m.unsigned.toString() : m.toString()))
  bot.on('end', () => { c.ended = true })
  bot.on('error', e => c.errors.push(String(e)))
  bot.on('kicked', r => { c.kicked = JSON.stringify(r) })
  return c
}
async function joined(c) {
  const from = paper.output.length
  if (!paper.output.includes(c.name + ' joined the game'))
    await until(() => paper.output.slice(from).includes(c.name + ' joined the game') || c.ended, 'join ' + c.name, 20000)
  assert(!c.ended, JSON.stringify({kicked:c.kicked,errors:c.errors}))
  await sleep(500)
}
async function disconnected(c) { if (!c.ended) c.bot.quit(); await until(() => c.ended, 'disconnect'); await sleep(300) }
async function expect(c, regex, from = 0) {
  await until(() => c.messages.slice(from).some(m => regex.test(m)) || c.ended, c.name + ' receives ' + regex)
  assert(c.messages.slice(from).some(m => regex.test(m)), JSON.stringify({ messages:c.messages,kicked:c.kicked,errors:c.errors }))
}
async function cmd(c, text, regex) { const mark=c.messages.length; c.bot.chat(text); await expect(c,regex,mark) }
async function probe(command) {
  const from=paper.output.length; paper.child.stdin.write('ctest '+command+'\n')
  await until(()=>paper.output.slice(from).includes('CTEST_OK '+command)||paper.output.slice(from).includes('CTEST_FAILED'),'probe '+command)
  assert(!paper.output.slice(from).includes('CTEST_FAILED'),paper.output.slice(from))
}
async function state() {
  const id='s'+(++seq); await probe('state '+id)
  const match=paper.output.match(new RegExp('CSTATE '+id+' pending=(-?\\d+) writers=(\\d+) problem=([^\\r\\n]*)'))
  assert(match); return {pending:Number(match[1]),writers:Number(match[2]),problem:match[3]}
}
async function settled() { await until(async()=> (await state()).pending===0, 'preferences persisted', 20000) }
async function stopPaper() {
  if(paper&&!paper.exited){paper.child.stdin.write('stop\n');await until(()=>paper.exited,'Paper shutdown',45000)}
}
async function cleanup() {
  for(const c of clients)if(!c.ended)c.bot.quit()
  if(proxy&&!proxy.exited){proxy.child.stdin.write('shutdown\n');await until(()=>proxy.exited,'proxy shutdown',20000)}
  await stopPaper()
  for(const h of children.filter(c=>c.name==='queue'&&!c.exited)){
    h.child.kill();await until(()=>h.exited,'owned isolated limbo shutdown',10000)
  }
  for(const h of children)fs.writeFileSync(path.join(root,h.name+'-'+h.index+'-runtime.log'),h.output)
}
async function run() {
  fixture()
  await startPaper()
  const queue=start('queue','NanoLimbo.jar','256M')
  await until(()=>/NanoLimbo started|Listening|Server started/.test(queue.output)||queue.exited,'limbo startup',30000)
  assert(!queue.exited,queue.output)
  proxy=start('proxy','velocity.jar','512M')
  await until(()=>/Done \(/.test(proxy.output)||proxy.exited,'proxy startup',30000)
  assert(!proxy.exited,proxy.output)
  let a=connect('NCAlpha');await joined(a)
  let b=connect('NCBeta');await joined(b)
  const c=connect('NCGamma');await joined(c)
  a.bot.chat('CHAT_HELLO_1');await expect(b,/CHAT_HELLO_1/)
  pass('Normal chat after MODERN limbo-to-Paper transfer')
  for(const message of ['late-block-one','late-clear-one','late-console-only-one']){
    const marks=[a.messages.length,b.messages.length,c.messages.length]
    a.bot.chat(message);await sleep(400)
    for(const [i,client]of[a,b,c].entries())assert(!client.messages.slice(marks[i]).some(m=>m.includes(message)))
  }
  a.bot.chat('CHAT_AFTER_LATE_FILTER');await expect(b,/CHAT_AFTER_LATE_FILTER/)
  assert(!a.ended&&!b.ended)
  pass('Later cancellation/viewer clearing respected; next chat stays valid')
  const rewrite=b.messages.length;a.bot.chat('late-rewrite-original');await expect(b,/LATE_REWRITTEN/,rewrite)
  assert(!b.messages.slice(rewrite).some(m=>m.includes('late-rewrite-original')))
  pass('Later message rewrite is delivered instead of captured old content')
  const audience=c.messages.length;a.bot.chat('late-only-beta-one');await expect(b,/late-only-beta-one/)
  await sleep(250);assert(!c.messages.slice(audience).some(m=>m.includes('late-only-beta-one')))
  pass('Later audience restriction respected')
  await cmd(b,'/ignorehard NCAlpha',/NCAlpha ignored/)
  const ignored=b.messages.length;a.bot.chat('IGNORED_GLOBAL');await expect(c,/IGNORED_GLOBAL/)
  await sleep(250);assert(!b.messages.slice(ignored).some(m=>m.includes('IGNORED_GLOBAL')))
  await cmd(a,'/msg NCBeta IGNORED_PRIVATE',/not accepting messages/)
  await cmd(b,'/ignorehard NCAlpha',/NCAlpha unignored/)
  pass('Persistent ignore suppresses global and private messages')
  await cmd(b,'/togglechat',/Global chat is now hidden/)
  const hidden=b.messages.length;a.bot.chat('HIDDEN_GLOBAL');await expect(c,/HIDDEN_GLOBAL/)
  await sleep(250);assert(!b.messages.slice(hidden).some(m=>m.includes('HIDDEN_GLOBAL')))
  await cmd(b,'/togglechat',/Global chat is now visible/)
  await cmd(b,'/toggleprivatemsgs',/Private messages are now hidden/)
  await cmd(a,'/msg NCBeta HIDDEN_PM',/private messages disabled/)
  await cmd(b,'/toggleprivatemsgs',/Private messages are now visible/)
  pass('Chat and private visibility toggles work independently')
  await cmd(a,'/msg NCBeta PRIVATE_OK',/\[you -> NCBeta\].*PRIVATE_OK/)
  await expect(b,/\[NCAlpha -> you\].*PRIVATE_OK/)
  await cmd(a,'/msg NCBeta TOO_FAST',/Please wait/)
  await cmd(b,'/reply REPLY_OK',/\[you -> NCAlpha\].*REPLY_OK/)
  await expect(a,/REPLY_OK/)
  await disconnected(b)
  const joinMark=paper.output.length;b=connect('NCBeta')
  await until(()=>paper.output.slice(joinMark).includes('NCBeta joined the game')||b.ended,'Beta reconnect');assert(!b.ended);await sleep(400)
  await cmd(a,'/last SHOULD_NOT_REACH_RECONNECTED',/not messaged anyone yet/)
  pass('PM cooldown/reply work; disconnect clears stale reply targets')
  await cmd(a,'/ignorehard 33333333-3333-4333-8333-333333333333',/has not joined/)
  pass('Unknown UUID ignore cannot create arbitrary offline records')
  await probe('hide NCAlpha NCBeta')
  await cmd(a,'/msg NCBeta HIDDEN_TARGET',/Player is not online/)
  await probe('show NCAlpha NCBeta')
  await probe('deny NCAlpha nordchat.admin')
  const deniedAdmin=a.messages.length
  await cmd(a,'/nordchat reload',/permission|unknown|command/i)
  assert(!a.messages.slice(deniedAdmin).some(m=>m.includes('configuration reloaded')))
  const directDenied=a.messages.length
  await probe('direct NCAlpha nordchat reload')
  await expect(a,/do not have permission/,directDenied)
  await probe('deny NCAlpha nordchat.msg')
  const deniedPrivate=b.messages.length
  await cmd(a,'/nordchat:msg NCBeta NO_PERMISSION',/permission|unknown|command/i)
  assert(!b.messages.slice(deniedPrivate).some(m=>m.includes('NO_PERMISSION')))
  await probe('permission NCAlpha nordchat.msg true')
  pass('Hidden targets and revoked command permissions respected')
  await probe('filter NCAlpha false')
  const filterMark=b.messages.length
  await cmd(a,'/msg NCBeta syntheticforbidden',/muted/)
  a.bot.chat('FILTER_BLOCKED_GLOBAL');await sleep(300)
  assert(!b.messages.slice(filterMark).some(m=>m.includes('syntheticforbidden')||m.includes('FILTER_BLOCKED_GLOBAL')))
  await probe('filter NCAlpha true')
  a.bot.chat('FILTER_BYPASS_VALID');await expect(b,/FILTER_BYPASS_VALID/)
  pass('Existing NordFilter blocks private and public chat without NordChat resurrecting it')
  for(let i=0;i<3;i++){paper.child.stdin.write('nordchat reload\n');await sleep(100)}
  assert.equal((await state()).writers,1);await settled()
  pass('Configuration reload keeps exactly one preference writer')
  await probe('slow 3000')
  await cmd(b,'/togglechat',/Global chat is now hidden/)
  await until(async()=> (await state()).pending>0,'slow save pending')
  const begin=Date.now();await probe('state fast');assert(Date.now()-begin<1500)
  await probe('slow 0');await settled()
  pass('Slow persistence leaves main-thread diagnostics/commands responsive')
  const before=fs.readFileSync(preferences)
  await probe('fault true')
  await cmd(b,'/togglechat',/Global chat is now visible/)
  await until(async()=> (await state()).problem.includes('save failed'),'storage failure visible')
  assert(fs.readFileSync(preferences).equals(before))
  await cmd(b,'/togglechat',/storage is unavailable/)
  await probe('fault false');await settled();assert.equal((await state()).problem,'')
  pass('Failed write preserves file and dirty change; retry recovers')
  await cmd(b,'/toggleprivatemsgs',/Private messages are now hidden/)
  await settled();await disconnected(a);await disconnected(b);await disconnected(c)
  await stopPaper();await startPaper()
  a=connect('NCAlpha');await joined(a);b=connect('NCBeta');await joined(b)
  await cmd(a,'/msg NCBeta PERSISTED_BLOCK',/private messages disabled/)
  a.bot.chat('CHAT_AFTER_PAPER_RESTART');await expect(b,/CHAT_AFTER_PAPER_RESTART/)
  pass('Preferences survive graceful restart; reconnect chat remains valid')
  await disconnected(a);await disconnected(b);await stopPaper()
  fs.writeFileSync(preferences,'players: [MALFORMED_SYNTHETIC]\n')
  const corrupt=fs.readFileSync(preferences)
  await startPaper();a=connect('NCAlpha');await joined(a);b=connect('NCBeta');await joined(b)
  const absent=b.messages.length;a.bot.chat('FAIL_CLOSED_CHAT');await sleep(500)
  assert(!b.messages.slice(absent).some(m=>m.includes('FAIL_CLOSED_CHAT')))
  await cmd(a,'/msg NCBeta FAIL_CLOSED_PM',/storage is unavailable/)
  assert.match(paper.output,/Invalid\/unreadable players\.yml/)
  assert.equal((await state()).writers,0)
  assert(fs.readFileSync(preferences).equals(corrupt))
  pass('Corrupt initialization blocks chat/PM without overwriting data')
}
async function manual() {
  fixture()
  // Test probe would bypass NordFilter for every join. Retire it for the real-client smoke check.
  fs.renameSync(path.join(root,'paper/plugins/ChatTestProbe.jar'),path.join(root,'manual-retired-probe-'+Date.now()+'.jar'))
  await startPaper()
  const queue=start('queue','NanoLimbo.jar','256M')
  await until(()=>/NanoLimbo started|Listening|Server started/.test(queue.output)||queue.exited,'limbo startup',30000)
  assert(!queue.exited,queue.output)
  proxy=start('proxy','velocity.jar','512M')
  await until(()=>/Done \(/.test(proxy.output)||proxy.exited,'proxy startup',30000)
  assert(!proxy.exited,proxy.output)
  console.log('MANUAL_SMOKE_READY address=127.0.0.1:25665 probeRemoved=true noProductionChanges=true')
  console.log('Type stop on this test runner stdin to shut down the isolated fixture.')
  await new Promise(resolve=>{
    process.stdin.setEncoding('utf8')
    let pending=''
    process.stdin.on('data',chunk=>{
      pending+=chunk
      if(pending.split(/\r?\n/).some(line=>line.trim()==='stop')){process.stdin.pause();resolve()}
    })
  })
}
const manualMode=process.argv.includes('--manual');
(manualMode ? manual() : run()).then(()=>console.log(manualMode?'MANUAL_SMOKE_STOPPED':'ALL_CHAT_INTEGRATION_TESTS_PASSED count='+results.length))
  .catch(e=>{console.error(e.stack);if(paper)console.error(paper.output.slice(-7000));process.exitCode=1})
  .finally(async()=>{try{await cleanup()}catch(e){console.error(e.stack);process.exitCode=1}
    if(!manualMode)fs.writeFileSync(path.join(root,'results.json'),JSON.stringify({passed:process.exitCode!==1,results},null,2))})

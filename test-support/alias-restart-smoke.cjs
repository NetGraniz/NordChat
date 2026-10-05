'use strict'
// Isolated Paper runtime, synthetic players only. Never pass a production directory.
// node alias-restart-smoke.cjs <fresh-runtime-dir> <java.exe> <mineflayer-module-dir>
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const root = path.resolve(process.argv[2] || '')
const java = process.argv[3]
const mineflayer = require(path.resolve(process.argv[4]))
assert(java && fs.existsSync(path.join(root, 'server.jar')))
assert(!fs.existsSync(path.join(root, 'world')), 'Use a fresh isolated runtime')
assert(!fs.existsSync(path.join(root, 'plugins/NordChat/players.yml')), 'Never overwrite an existing store')
const store = path.join(root, 'plugins/NordChat/players.yml')
const clients = [], runs = [], results = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let paper
function offlineId(name) {
  const bytes = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128
  const hex = bytes.toString('hex')
  return [hex.slice(0,8), hex.slice(8,12), hex.slice(12,16), hex.slice(16,20), hex.slice(20)].join('-')
}
async function until(fn, label, timeout = 20000) {
  const start = Date.now()
  while (!fn()) { assert(Date.now() - start < timeout, 'Timeout: ' + label); await sleep(50) }
}
function pass(label) { results.push(label); console.log('PASS: ' + label) }
async function start() {
  const child = spawn(java, ['-Xms128M', '-Xmx2G', '-jar', 'server.jar', 'nogui'],
    { cwd: root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  paper = { child, output: '', exited: false }
  runs.push(paper)
  for (const stream of [child.stdout, child.stderr]) stream.on('data', b => { paperOutput(b) })
  // Capture this run, not the mutable current-run reference.
  const run = paper
  function paperOutput(b) { run.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') }
  child.on('exit', code => { run.exited = true; run.code = code })
  child.on('error', e => { run.output += String(e); run.exited = true })
  await until(() => run.output.includes('Done (') || run.exited, 'Paper startup', 180000)
  assert(!run.exited, run.output.slice(-4000))
  assert.match(run.output, /Enabling NordChat v0\.1\.5/)
  assert.doesNotMatch(run.output, /Invalid\/unreadable players.yml|Error occurred while enabling NordChat/)
}
async function stop() {
  if (paper && !paper.exited) {
    paper.child.stdin.write('stop\n')
    await until(() => paper.exited, 'graceful Paper stop', 60000)
    assert.equal(paper.code, 0)
  }
}
async function connect(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 31566, username: name,
    auth: 'offline', version: '26.2', hideErrors: true, checkTimeoutInterval: 30000 })
  const client = { bot, name, messages: [], ended: false }
  clients.push(client)
  bot.on('message', m => client.messages.push(m.unsigned ? m.unsigned.toString() : m.toString()))
  bot.on('end', () => { client.ended = true })
  bot.on('error', e => { client.error = String(e) })
  bot.on('kicked', r => { client.kicked = JSON.stringify(r) })
  await until(() => paper.output.includes(name + ' joined the game') || client.ended, 'join ' + name)
  assert(!client.ended, JSON.stringify(client.kicked || client.error))
  await sleep(500)
  return client
}
async function receive(client, marker, from = 0) {
  await until(() => client.messages.slice(from).some(m => m.includes(marker)) || client.ended, 'receive ' + marker)
  assert(client.messages.slice(from).some(m => m.includes(marker)), JSON.stringify(client.messages))
}
async function command(client, cmd, marker) {
  const from = client.messages.length; client.bot.chat(cmd); await receive(client, marker, from)
}
async function quitClients() {
  for (const c of clients) if (!c.ended) c.bot.quit()
  await until(() => clients.every(c => c.ended), 'disconnect clients')
}
async function main() {
  const names = ['NCAlpha', 'NCBeta', ...Array.from({ length: 181 }, (_, i) => 'NCSeed' + i)]
  const lines = ['names:']
  for (const name of names) lines.push('  ' + offlineId(name) + ': ' + name)
  lines.push('players:')
  for (const [index, name] of names.entries()) lines.push('  ' + offlineId(name) + ':',
    '    chat-visible: true', '    private-messages-visible: true', '    death-messages-visible: true',
    '    hard-ignored: ' + (index === 0 ? '&empty []' : '*empty'),
    '    ignored-death-messages: *empty', '    temporary-ignored: {}')
  fs.mkdirSync(path.dirname(store), { recursive: true })
  const legacy = Buffer.from(lines.join('\n') + '\n')
  fs.writeFileSync(store, legacy)
  fs.writeFileSync(path.join(root, 'server.properties'), [
    'server-ip=127.0.0.1', 'server-port=31566', 'online-mode=false', 'enforce-secure-profile=false',
    'max-players=5', 'view-distance=2', 'simulation-distance=2', 'enable-rcon=false', 'enable-query=false',
    'level-name=world', 'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0',
    'pause-when-empty-seconds=-1', 'gamemode=creative', 'force-gamemode=true', 'difficulty=peaceful', ''
  ].join('\n'))
  await start()
  assert.deepEqual(fs.readFileSync(store), legacy)
  pass('Legacy 183-player store loads without rewriting at startup')
  let a = await connect('NCAlpha'), b = await connect('NCBeta')
  a.bot.chat('ALIAS_PUBLIC_BEFORE_RESTART'); await receive(b, 'ALIAS_PUBLIC_BEFORE_RESTART')
  a.bot.chat('/msg NCBeta ALIAS_PRIVATE_BEFORE_RESTART'); await receive(b, 'ALIAS_PRIVATE_BEFORE_RESTART')
  pass('Public and private messages delivered to a real protocol client')
  await command(b, '/togglechat', 'Global chat is now hidden')
  const mark = b.messages.length
  a.bot.chat('ALIAS_HIDDEN_BEFORE_RESTART'); await receive(a, 'ALIAS_HIDDEN_BEFORE_RESTART')
  await sleep(600)
  assert(!b.messages.slice(mark).some(m => m.includes('ALIAS_HIDDEN_BEFORE_RESTART')))
  await quitClients(); await stop()
  const saved = fs.readFileSync(store, 'utf8')
  assert(!/\*(?:empty|id\d+)/.test(saved), 'Writer must not emit collection aliases')
  assert.equal((saved.match(/chat-visible:/g) || []).length, 183)
  pass('Preference change persisted; saved store contains 183 players and no aliases')
  await start()
  a = await connect('NCAlpha'); b = await connect('NCBeta')
  const hidden = b.messages.length
  a.bot.chat('ALIAS_HIDDEN_AFTER_RESTART'); await receive(a, 'ALIAS_HIDDEN_AFTER_RESTART')
  await sleep(600)
  assert(!b.messages.slice(hidden).some(m => m.includes('ALIAS_HIDDEN_AFTER_RESTART')))
  await command(b, '/togglechat', 'Global chat is now visible')
  a.bot.chat('ALIAS_PUBLIC_AFTER_RESTART'); await receive(b, 'ALIAS_PUBLIC_AFTER_RESTART')
  a.bot.chat('/msg NCBeta ALIAS_PRIVATE_AFTER_RESTART'); await receive(b, 'ALIAS_PRIVATE_AFTER_RESTART')
  pass('Cold restart preserves visibility setting and restores public/private delivery')
  await quitClients(); await stop()
}
main().then(() => { console.log('RUNTIME_SMOKE_OK checks=' + results.length) }).catch(e => {
  console.error(e.stack); process.exitCode = 1
}).finally(async () => {
  await quitClients(); await stop()
  runs.forEach((run, i) => fs.writeFileSync(path.join(root, 'runtime-' + i + '.log'), run.output))
  fs.writeFileSync(path.join(root, 'smoke-result.json'), JSON.stringify({ passed: !process.exitCode, checks: results }, null, 2))
})

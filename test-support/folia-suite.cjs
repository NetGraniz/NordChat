'use strict'
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),net=require('node:net')
const {spawn,spawnSync}=require('node:child_process'),mineflayer=require('mineflayer')
const root=path.resolve(process.argv[2]||''),java=process.argv[3],platform=process.argv[4]
assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nord-suite-test-20261007-'))
assert(['Paper','Folia'].includes(platform));assert(java);assert(!fs.existsSync(root),'Fresh fixture required')
const projects=path.resolve(__dirname,'../..'),server=path.join(root,'server'),clients=[],handles=[],passed=[]
const releases={NordChat:'NordChat-0.2.0.jar',NordFilter:'NordFilter-1.2.0.jar',NordCommandsPaper:'NordCommands-Paper-1.2.0.jar',NordDeaths:'NordDeaths-1.1.0.jar',NordHomes:'NordHomes-1.2.0.jar',NordPets:'NordPets-1.1.0.jar',NordPhantoms:'NordPhantoms-1.1.0.jar',NordStatus:'NordStatus-1.3.0.jar'}
let current
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
async function until(fn,label,timeout=30000){const start=Date.now();while(!await fn()){if(Date.now()-start>timeout)throw Error('Timeout: '+label);await sleep(100)}}
function pass(label){passed.push(label);console.log('PASS: '+label)}
function cmd(text){current.child.stdin.write(text+'\n')}
async function marker(command,pattern){const offset=current.output.length;cmd(command);await until(()=>pattern.test(current.output.slice(offset)),command);return current.output.slice(offset)}
function connect(name){
 const bot=mineflayer.createBot({host:'127.0.0.1',port:25646,username:name,version:'26.2',auth:'offline',hideErrors:true})
 const client={bot,name,ended:false,messages:[]};clients.push(client);bot.on('error',()=>{});bot.on('end',()=>{client.ended=true});bot.on('messagestr',m=>client.messages.push(m));bot.on('actionBar',m=>client.messages.push(m.toString()));
 // Mineflayer handles system-chat overlays but not the separate 26.2 action_bar packet.
 bot._client.on('action_bar',packet=>client.messages.push(require('prismarine-chat')(bot.registry).fromNotch(packet.text).toString()));
 bot.once('spawn',()=>{bot.physicsEnabled=false});return client
}
async function chat(client,text,expected){client.messages=[];client.bot.chat(text);await until(()=>client.messages.some(m=>m.includes(expected)),text)}
async function disconnect(client){if(!client.ended)client.bot.quit();await until(()=>client.ended,'disconnect '+client.name)}
async function stop(){if(current&&!current.exited){cmd('stop');await until(()=>current.exited,'stop',50000)}}
async function start(){
 const trust=path.join(server,'plugins/NordSuiteTest/local-tls.p12')
 const child=spawn(java,['-Dterminal.jline=false','-Dterminal.ansi=false','-Djavax.net.ssl.trustStore='+trust,'-Djavax.net.ssl.trustStorePassword=local-only-test','-Xms256M','-Xmx1400M','-jar','server.jar','nogui'],{cwd:server,windowsHide:true,stdio:['pipe','pipe','pipe']})
 const handle={child,output:'',exited:false};current=handle;handles.push(handle)
 for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{handle.output+=bytes.toString().replace(/\x1b\[[0-9;]*m/g,'')})
 child.on('exit',()=>{handle.exited=true});child.on('error',error=>{handle.output+=String(error);handle.exited=true})
 await until(()=>/Done \(/.test(handle.output)||handle.exited,'server bootstrap',120000)
 assert(!handle.exited,handle.output.slice(-5000));assert.match(handle.output,new RegExp(platform+' version'));assert.match(handle.output,/NORD_SUITE_READY/)
 for(const name of ['NordChat','NordFilter','NordCommands','NordDeaths','NordHomes','NordPets','NordPhantoms','NordStatus'])assert.match(handle.output,new RegExp('\\['+name+'\\] Enabling'))
 console.log('LOCAL '+platform+' suite ready, PID '+child.pid)
}
async function heartbeatCount(){const output=await marker('nsuite status',/NSSTATUS heartbeats=/);return Number(output.match(/NSSTATUS heartbeats=(\d+)/)[1])}
async function move(client,world,x,y,z){await marker(`nsuite move ${client.name} ${world} ${x} ${y} ${z}`,new RegExp('NSMOVE '+client.name+' true'));await sleep(300)}
async function main(){
 for(const port of [25646,25844])assert(!await new Promise(resolve=>{const socket=net.connect({host:'127.0.0.1',port});socket.on('connect',()=>{socket.destroy();resolve(true)});socket.on('error',()=>resolve(false))}),'Local port occupied')
 fs.mkdirSync(path.join(server,'plugins/NordSuiteTest'),{recursive:true})
 const seed='C:\\Users\\artyo\\Documents\\Codex\\nordauth-test-20261007-'+platform.toLowerCase()+'\\server'
 for(const name of ['server.jar','cache','libraries','eula.txt'])if(fs.existsSync(path.join(seed,name)))fs.cpSync(path.join(seed,name),path.join(server,name),{recursive:true})
 assert(fs.readFileSync(path.join(server,'eula.txt'),'utf8').includes('eula=true'))
 for(const [project,jar]of Object.entries(releases)){
   fs.copyFileSync(path.join(projects,project,'target',jar),path.join(server,'plugins',jar))
   const config=path.join(projects,project,'src/main/resources/config.yml')
   if(fs.existsSync(config)){const name=project==='NordCommandsPaper'?'NordCommands':project;fs.mkdirSync(path.join(server,'plugins',name),{recursive:true});fs.copyFileSync(config,path.join(server,'plugins',name,'config.yml'))}
 }
 fs.copyFileSync(path.join(projects,'NordChat/target/NordSuiteTest.jar'),path.join(server,'plugins/NordSuiteTest.jar'))
 const store=path.join(server,'plugins/NordSuiteTest/local-tls.p12'),keytool=path.join(path.dirname(java),'keytool.exe')
 const generated=spawnSync(keytool,['-genkeypair','-alias','localsuite','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1','-storetype','PKCS12','-keystore',store,'-storepass','local-only-test','-keypass','local-only-test'],{windowsHide:true,encoding:'utf8'})
 assert.equal(generated.status,0,generated.stderr)
 fs.writeFileSync(path.join(server,'server.properties'),'server-ip=127.0.0.1\nserver-port=25646\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=20\nview-distance=2\nsimulation-distance=2\nlevel-name=NordSuiteLocalTest\nspawn-protection=0\n')
 fs.writeFileSync(path.join(server,'plugins/NordStatus/config.yml'),'heartbeat-url: "https://localhost:25844/heartbeat"\ninterval-seconds: 30\ninitial-delay-seconds: 1\nrequest-timeout-seconds: 3\n')
 const commands=['msg','reply','last','ignore','ignorehard','ignorelist','togglechat','toggleprivatemsgs','toggledeathmsgs','toggledeathmsgshard','kill','sethome','home','back','nordchat','nordfilter','nordcommands','norddeaths','nordpets','nordphantoms','nordstatus']
 fs.writeFileSync(path.join(server,'plugins/NordCommands/config.yml'),'denied-message: "No such command."\nallowed-commands: ['+commands.join(', ')+']\n')
 const phantomConfig=path.join(server,'plugins/NordPhantoms/config.yml')
 fs.writeFileSync(phantomConfig,fs.readFileSync(phantomConfig,'utf8').replace('interval-ticks: 200','interval-ticks: 20').replace('interval-ticks: 100','interval-ticks: 20').replace('attempts: 16','attempts: 1'))
 await start();pass('All eight updated plugins enable together')
 await until(async()=>await heartbeatCount()>0,'scheduled TLS heartbeat')
 const count=await heartbeatCount();cmd('nordstatus test');await until(async()=>await heartbeatCount()>count,'explicit heartbeat')
 await marker('nsuite httpfail',/NSHTTPFAIL/);const beforeRetry=await heartbeatCount();cmd('nordstatus test');await until(async()=>await heartbeatCount()>=beforeRetry+2,'one bounded HTTP retry',15000)
 pass('NordStatus scheduled/manual HTTPS heartbeats and bounded retry reach local TLS endpoint')
 const alpha=connect('NSAlpha'),beta=connect('NSBeta');await until(()=>alpha.bot.entity&&beta.bot.entity,'two clients join',60000)
 const world=alpha.bot.game.dimension==='minecraft:overworld'?'minecraft:overworld':'NordSuiteLocalTest'
 // Server world names in 26.2 are dimension resource locations.
 await move(beta,world,2048,120,2048)
 beta.messages=[];alpha.bot.chat('hello distant region');await until(()=>beta.messages.some(m=>m.includes('hello distant region')),'cross-region public chat')
 await chat(beta,'/togglechat','Global chat is now hidden');beta.messages=[];alpha.bot.chat('hidden distant region');await sleep(700);assert(!beta.messages.some(m=>m.includes('hidden distant region')))
 await chat(beta,'/togglechat','Global chat is now visible');pass('Public chat and visibility preferences work across separated regions')
 beta.messages=[];await chat(alpha,'/msg NSBeta first private greeting','first private greeting');await until(()=>beta.messages.some(m=>m.includes('first private greeting')),'private recipient')
 await chat(beta,'/reply return private greeting','return private greeting');await until(()=>alpha.messages.some(m=>m.includes('return private greeting')),'reply recipient');pass('Private messages and replies return to both owning regions')
 await chat(beta,'/ignorehard NSAlpha','ignored.');await sleep(600);await chat(alpha,'/msg NSBeta rejected ignore greeting','not accepting messages')
 await chat(beta,'/ignorehard NSAlpha','unignored.');await chat(beta,'/toggleprivatemsgs','Private messages are now hidden');await sleep(600);await chat(alpha,'/msg NSBeta rejected disabled greeting','private messages disabled')
 await chat(beta,'/toggleprivatemsgs','Private messages are now visible');pass('Ignore and private-message visibility checks survive cross-region delivery')
 await chat(beta,'/version','No such command.');await chat(beta,'/nordcommands health','NordCommands: ready');beta.bot.chat('/nordcommands reload');await until(()=>/NordCommands configuration reloaded/.test(current.output),'policy reload');pass('Command allowlist blocks unlisted roots and reload refreshes player command views')
 await chat(alpha,'/sethome','Home saved.');const homeX=Math.floor(alpha.bot.entity.position.x),homeZ=Math.floor(alpha.bot.entity.position.z)
 await move(alpha,world,3000,120,3000);await chat(alpha,'/home','Do not move.');await marker('nsuite homefast NSAlpha',/NSHOMEFAST NSAlpha/)
 await until(()=>alpha.messages.some(m=>m.includes('Teleported home.')),'entity-owned teleport completion',30000)
 assert(Math.abs(alpha.bot.entity.position.x-homeX)<2&&Math.abs(alpha.bot.entity.position.z-homeZ)<2)
 await chat(alpha,'/home','Do not move.');await marker('nsuite step NSAlpha',/NSSTEP NSAlpha/)
 await until(()=>alpha.messages.some(m=>m.includes('cancelled because you moved')),'movement cancellation')
 pass('NordHomes stores a home, teleports between regions and cancels countdown on a region-owned move event')
 await marker('nsuite pets NSAlpha NSBeta',/NSPETS protected/);await until(()=>beta.messages.some(m=>m.includes('pet')),'pet attacker notification')
 pass('Foreign pet damage is blocked; attacker receives owning-region notification')
 await marker('nsuite phantoms NSAlpha',/NSPHANTOMS overworld-blocked/)
 await move(alpha,'minecraft:the_end',0,90,0)
 await marker('nsuite phantoms NSAlpha',/NSPHANTOMS end-passive-provoked/)
 await sleep(1500);pass('NordPhantoms blocks Overworld spawn and runs End passive/provoked movement tasks')
 await move(alpha,world,homeX,120,homeZ)
 await chat(beta,'/toggledeathmsgshard','Persistent death messages are now hidden');beta.messages=[]
 alpha.bot.chat('/kill');await until(()=>/NSDEATH NSAlpha/.test(current.output),'death processing',15000)
 await sleep(1000);assert(!beta.messages.some(m=>m.includes('NSAlpha')));pass('NordDeaths and NordChat process actual death with per-viewer visibility')
 await chat(beta,'/togglechat','Global chat is now hidden')
 await disconnect(alpha);await disconnect(beta);await stop();await start()
 const resumed=connect('NSBeta'),speaker=connect('NSAlpha');await until(()=>resumed.bot.entity&&speaker.bot.entity,'reconnect after restart',60000)
 resumed.messages=[];speaker.bot.chat('persistent hidden greeting');await sleep(800);assert(!resumed.messages.some(m=>m.includes('persistent hidden greeting')))
 await chat(resumed,'/togglechat','Global chat is now visible');pass('Chat preference state persists across a full server restart')
 speaker.messages=[];resumed.messages=[]
 speaker.bot.chat('repeat fixture greeting');await sleep(250);speaker.bot.chat('repeat fixture greeting');await sleep(250);speaker.bot.chat('repeat fixture greeting')
 await until(()=>speaker.messages.some(m=>m.includes('muted')),'spam mute')
 assert(resumed.messages.filter(m=>m.includes('repeat fixture greeting')).length<=2)
 cmd('nordfilter unmute NSAlpha');await sleep(1000);speaker.bot.chat('after unmute greeting');await until(()=>resumed.messages.some(m=>m.includes('after unmute greeting')),'persisted unmute')
 pass('NordFilter suppresses duplicate spam without breaking chat delivery; persisted unmute restores chat')
 if(platform==='Folia'){await marker('nsuite worldapi',/NSWORLDAPI unsupported/);pass('Folia build rejects dynamic world creation required by NordRegen')}
 for(const plugin of ['nordchat','norddeaths','nordpets','nordphantoms','nordstatus'])cmd(plugin+' reload')
 cmd('nordfilter reload');await until(()=>/Moderation configuration reloaded/.test(current.output),'moderation reload');await sleep(1500)
 assert(handles.every(h=>!/NSFAIL|Thread failed main thread check|Cannot read world asynchronously|Could not pass event|Exception executing task|UnsupportedOperationException|NoSuchMethodError|ConcurrentModificationException/.test(h.output)))
 pass('Reload and normal-operation logs contain no plugin ownership/compatibility errors')
 await disconnect(resumed);await disconnect(speaker);await stop()
 fs.writeFileSync(path.join(root,'results.json'),JSON.stringify({platform,total:passed.length,passed,notes:'Only synthetic loopback clients/data/TLS endpoint. No production data, external heartbeat or 1000-player load test.'},null,2))
 console.log('ALL '+passed.length+' '+platform+' SUITE SCENARIOS PASSED')
}
main().catch(error=>{console.error(error.stack);process.exitCode=1}).finally(async()=>{
 for(const client of clients)if(!client.ended)client.bot.quit();await stop().catch(()=>current.child.kill())
 for(const [index,handle]of handles.entries())fs.writeFileSync(path.join(root,'server-'+index+'-output.log'),handle.output)
})

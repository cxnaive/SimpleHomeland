const mc = require('minecraft-protocol');
const client = mc.createClient({
  host: '127.0.0.1', port: Number(process.env.HOMELAND_PROBE_PORT || 25649),
  username: 'MigrationOwner', auth: 'offline', version: '26.1'
});
let winScreens = 0;
client.on('login', () => console.log('PAPER_CLIENT_LOGIN'));
client.on('game_state_change', packet => {
  if (packet.reason !== 'win_game' || packet.gameMode !== 1) return;
  console.log('PAPER_WIN_SCREEN', ++winScreens);
  // Closing credits must leave the player at the selected homeland respawn point.
  setTimeout(() => client.write('client_command', {actionId: 'perform_respawn'}), 250);
});
client.on('error', error => { console.error(error); process.exitCode = 1; });
client.on('end', () => {
  console.log('PAPER_WIN_SCREEN_COUNT', winScreens);
  if (winScreens !== 1) process.exitCode = 1;
});
setTimeout(() => { client.end(); process.exitCode = 1; }, 90000).unref();

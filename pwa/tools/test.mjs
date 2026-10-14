// The tests of the Android app that apply to the web app, run with: node tools/test.mjs
import assert from 'node:assert/strict';

globalThis.localStorage = { getItem: () => null, setItem: () => {} };
Object.defineProperty(globalThis, 'navigator', { value: { language: 'it-IT' }, configurable: true });
globalThis.document = { documentElement: {} };
process.env.TZ = 'Europe/Rome';

const { setLanguage } = await import('../js/i18n.js');
setLanguage('it');
const { switchBotSign, remoteKind } = await import('../js/switchbot.js');
const { parseVoice, parseExtras } = await import('../js/voice.js');
const model = await import('../js/model.js');

let failures = 0;
async function test(name, body) {
  try {
    await body();
    console.log(`ok   ${name}`);
  } catch (e) {
    failures++;
    console.log(`FAIL ${name}\n     ${e.message.split('\n').join('\n     ')}`);
  }
}

await test('requests are signed as SwitchBot asks', async () => {
  assert.equal(await switchBotSign('token-abc', 'secret-key', '1700000000000', 'nonce-1'),
    'BDFQNBEJWKVP6RHVWLYOXD4M48GPWYEFZM1IASZBWIY=');
});

await test('remotes learnt by hand are told apart', () => {
  assert.equal(remoteKind({ type: 'TV' }), 'TV');
  assert.equal(remoteKind({ type: 'DIY TV' }), 'TV');
  assert.equal(remoteKind({ type: 'DIY Air Conditioner' }), 'AIR_CONDITIONER');
  assert.equal(remoteKind({ type: 'Others' }), 'CUSTOM');
  assert.equal(remoteKind({ type: 'Fan' }), 'OTHER');
});

const device = (id, name, room, hasPower = true) =>
  ({ applianceId: id, entityId: `e${id}`, name, category: 'LIGHT', room, hasPower, hasBrightness: false, isSensor: !hasPower });
const devices = [
  device('1', 'Luce cucina', 'Cucina'),
  device('2', 'Presa forno', 'Cucina'),
  device('3', 'Stufa', 'Salotto'),
  device('4', 'Lampada', 'Salotto'),
  device('5', 'Termometro', 'Salotto', false),
  device('6', 'Stufa bagno', 'Bagno'),
  device('7', 'Luce', 'Studio'),
];
const run = (...heard) => parseVoice(heard, devices);
const ran = (result) => {
  assert.equal(result.type, 'run');
  return result.command;
};
const asked = (result) => {
  assert.equal(result.type, 'ask');
  return result.options;
};

await test('exact name runs at once', () => {
  const command = ran(run('accendi la lampada'));
  assert.equal(command.devices[0].name, 'Lampada');
  assert.equal(command.on, true);
});
await test('switches off', () => {
  const command = ran(run('Spegni la presa del forno, per favore'));
  assert.equal(command.devices[0].name, 'Presa forno');
  assert.equal(command.on, false);
});
await test('accents and plurals do not matter', () => {
  assert.equal(ran(run('Accèndi le luci della cucina')).devices[0].name, 'Luce cucina');
});
await test('shorter name wins when said alone', () => assert.equal(ran(run('spegni la stufa')).devices[0].name, 'Stufa'));
await test('room tells apart devices with the same name', () => {
  assert.equal(ran(run('accendi la luce in studio')).devices[0].name, 'Luce');
});
await test('partial name asks', () => assert.equal(asked(run('accendi il forno'))[0].devices[0].name, 'Presa forno'));
await test('whole room always asks', () => {
  const options = asked(run('spegni tutto in salotto'));
  assert.equal(options[0].room, 'Salotto');
  assert.deepEqual(options[0].devices.map((d) => d.name), ['Stufa', 'Lampada']);
});
await test('sensors are never switched', () => assert.equal(run('accendi il termometro').type, 'unknown'));
await test('no verb is not a command', () => assert.equal(run('la lampada').type, 'unknown'));
await test('unknown device is not a command', () => assert.equal(run('accendi la lavatrice').type, 'unknown'));
await test('better guess of the recognizer wins', () => {
  assert.equal(ran(run('accendi la rampa', 'accendi la lampada')).devices[0].name, 'Lampada');
});
await test('custom devices and remotes are understood by their whole name', () => {
  const macros = [['m1', 'Postazione'], ['m2', 'Buonanotte casa']];
  const remotes = [['r1', 'TV Salotto']];
  assert.deepEqual(parseExtras(['esegui la postazione'], macros, remotes), { type: 'run', macroId: 'm1' });
  assert.deepEqual(parseExtras(['avvia buonanotte', 'avvia buonanotte casa'], macros, remotes), { type: 'run', macroId: 'm2' });
  assert.deepEqual(parseExtras(['Spegni la TV del salotto'], macros, remotes), { type: 'remote', remoteId: 'r1', on: false });
  assert.deepEqual(parseExtras(['turn on the tv salotto'], macros, remotes), { type: 'remote', remoteId: 'r1', on: true });
  assert.equal(parseExtras(['spegni postazione'], macros, remotes), null);
  assert.equal(parseExtras(['accendi la tv'], macros, remotes), null);
});
await test('english is understood too', () => {
  const command = ran(run('turn off the Lampada, please'));
  assert.equal(command.devices[0].name, 'Lampada');
  assert.equal(command.on, false);
  assert.equal(asked(run('turn on everything in the Salotto'))[0].room, 'Salotto');
});

// 14 October 2026 is a Wednesday.
const at = (day, hour, minute) => new Date(2026, 9, day, hour, minute).getTime();
const SATURDAY = 7;
const SUNDAY = 1;
const WEDNESDAY = 4;
await test('later today is today', () => assert.equal(model.nextTime(16, 0, [], at(14, 9, 30)), at(14, 16, 0)));
await test('a time already passed is tomorrow', () => {
  assert.equal(model.nextTime(8, 0, [], at(14, 9, 30)), at(15, 8, 0));
  assert.equal(model.nextTime(9, 30, [], at(14, 9, 30)), at(15, 9, 30));
});
await test('only the chosen days count', () => {
  assert.equal(model.nextTime(16, 0, [SATURDAY, SUNDAY], at(14, 9, 30)), at(17, 16, 0));
  assert.equal(model.nextTime(16, 0, [SATURDAY, SUNDAY], at(17, 16, 0)), at(18, 16, 0));
  assert.equal(model.nextTime(8, 0, [WEDNESDAY], at(14, 9, 30)), at(21, 8, 0));
});

const state = (values) => ({ ...model.emptyState(), ...values });
const automation = (trigger, threshold = 0) => ({ trigger, threshold });
await test('a threshold holds only past it', () => {
  const hot = automation(model.TRIGGER_TEMPERATURE_ABOVE, 25);
  assert.equal(model.holds(hot, state({ temperature: 25.1 })), true);
  assert.equal(model.holds(hot, state({ temperature: 25 })), false);
  const cold = automation(model.TRIGGER_TEMPERATURE_BELOW, 18);
  assert.equal(model.holds(cold, state({ temperature: 17.5 })), true);
  assert.equal(model.holds(cold, state({ temperature: 18.5 })), false);
  assert.equal(model.holds(automation(model.TRIGGER_HUMIDITY_ABOVE, 60), state({ humidity: 61 })), true);
});
await test('it is ready again only well clear of the threshold', () => {
  const hot = automation(model.TRIGGER_TEMPERATURE_ABOVE, 25);
  assert.equal(model.cleared(hot, state({ temperature: 24.9 })), false);
  assert.equal(model.cleared(hot, state({ temperature: 24.6 })), true);
});
await test('switching is waited for too', () => {
  assert.equal(model.holds(automation(model.TRIGGER_ON), state({ power: true })), true);
  assert.equal(model.cleared(automation(model.TRIGGER_ON), state({ power: false })), true);
  assert.equal(model.holds(automation(model.TRIGGER_OFF), state({ power: false })), true);
});
await test('what is not known says nothing', () => {
  const hot = automation(model.TRIGGER_TEMPERATURE_ABOVE, 25);
  assert.equal(model.holds(hot, state({})), null);
  assert.equal(model.holds(hot, state({ temperature: 30, reachable: false })), null);
  assert.equal(model.holds(automation(model.TRIGGER_ON), state({})), null);
});
await test('strings are formatted as on Android', async () => {
  const { str, plural } = await import('../js/i18n.js');
  assert.equal(str('on_brightness', 40), 'Acceso · 40%');
  assert.equal(plural('devices', 1), '1 dispositivo');
  assert.equal(plural('devices', 3), '3 dispositivi');
  assert.equal(model.countdown(26 * 3600_000), str('countdown_days', 1, 2));
  assert.equal(model.statusText({ isSensor: true }, state({ temperature: 21.46, humidity: 50 })), '21,5° · 50%');
});

if (failures) {
  console.log(`${failures} failed`);
  process.exit(1);
}
console.log('all passed');

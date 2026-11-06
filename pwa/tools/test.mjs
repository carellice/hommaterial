// The tests of the Android app that apply to the web app and its server, run with: node tools/test.mjs
import assert from 'node:assert/strict';

globalThis.localStorage = { getItem: () => null, setItem: () => {} };
Object.defineProperty(globalThis, 'navigator', { value: { language: 'it-IT' }, configurable: true });
globalThis.document = { documentElement: {} };
process.env.TZ = 'Europe/Rome';
// The server modules open their data folder on import: a throwaway one.
process.env.DATA_DIR = (await import('node:fs')).mkdtempSync((await import('node:path')).join((await import('node:os')).tmpdir(), 'hommaterial-test-'));

const { setLanguage } = await import('../shared/i18n.js');
setLanguage('it');
const { switchBotSign } = await import('../server/src/switchbot.ts');
const { parseVoice, parseExtras, parsePhrase } = await import('../web/js/voice.js');
const model = await import('../shared/model.js');
const { remoteKind } = model;

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
  const groups = [['g1', 'Gruppo sera']];
  assert.deepEqual(parseExtras(['accendi gruppo sera'], macros, remotes, groups), { type: 'group', groupId: 'g1', on: true });
  assert.deepEqual(parseExtras(['spegni il gruppo sera'], macros, remotes, groups), { type: 'group', groupId: 'g1', on: false });
  assert.equal(parseExtras(['esegui gruppo sera'], macros, remotes, groups), null);
});
await test('voice phrases are understood whole and alone', () => {
  const phrases = [['m1', ['Buonanotte', 'vado a letto']], ['m2', ['Esco di casa']]];
  assert.equal(parsePhrase(['buonanotte!'], phrases), 'm1');
  assert.equal(parsePhrase(['Alexa, vado a letto'], phrases), 'm1');
  assert.equal(parsePhrase(['esco casa', 'esco di casa'], phrases), 'm2');
  assert.equal(parsePhrase(['buonanotte a tutti quanti'], phrases), null);
  assert.equal(parsePhrase([''], phrases), null);
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
  const { str, plural } = await import('../shared/i18n.js');
  assert.equal(str('on_brightness', 40), 'Acceso · 40%');
  assert.equal(plural('devices', 1), '1 dispositivo');
  assert.equal(plural('devices', 3), '3 dispositivi');
  assert.equal(model.countdown(26 * 3600_000), str('countdown_days', 1, 2));
  assert.equal(model.statusText({ isSensor: true }, state({ temperature: 21.46, humidity: 50 })), '21,5° · 50%');
});

const { createBackup, parseBackup } = await import('../shared/backup.js');
await test('portable backup carries automations and schedules without account or PIN', () => {
  const action = { type: 'power', target: '1', value: 'off', custom: false };
  const s = { favorites: ['1'], schedules: [{ id: 's', action, at: at(20, 18, 0), hour: 18, minute: 0, days: [2, 4], enabled: true }],
    automations: [{ id: 'a', device: '5', trigger: 'temperatureAbove', threshold: 25, action, enabled: true }],
    monitor: { enabled: true, pin: 'not-portable', monitorPin: 'not-portable' }, refreshToken: 'not-portable' };
  const backup = parseBackup(JSON.stringify(createBackup(s, { token: 'test-token', secret: 'test-secret' })));
  assert.deepEqual(backup.schedules[0].days, [2, 4]);
  assert.equal(backup.automations[0].action.target, '1');
  assert.equal(backup.switchBot.secret, 'test-secret');
  assert.equal(backup.refreshToken, undefined);
  assert.equal(backup.monitor.pin, undefined);
  assert.equal(backup.monitor.monitorPin, undefined);
  assert.equal(s.monitor.pin, 'not-portable');
});
await test('old v1 backups remain readable and malformed schedules fail before restore', () => {
  assert.equal(parseBackup('{"app":"hommaterial","version":1,"favorites":["1"]}').favorites[0], '1');
  assert.throws(() => parseBackup('{"app":"hommaterial","version":2}'));
  assert.throws(() => parseBackup('{"app":"hommaterial","version":1,"schedules":[null]}'));
  assert.throws(() => parseBackup('{"app":"hommaterial","version":1,"macros":[{"steps":[{"type":"macro","target":"self","value":""}]}]}'));
});
await test('the LG TVs of an Android backup come back out of the web unchanged', () => {
  const tv = { id: 'lg:1', name: 'Sala', host: '192.168.1.20', key: 'k', pin: 'p', macs: ['a8:23:fe:01:02:03'], apps: { netflix: 'Netflix' } };
  const backup = parseBackup(JSON.stringify(createBackup({ lgTvs: [tv] })));
  assert.deepEqual(backup.lgTvs, [tv]);
  assert.equal(createBackup({}).lgTvs, undefined);
  assert.throws(() => parseBackup('{"app":"hommaterial","version":1,"lgTvs":{}}'));
  assert.throws(() => parseBackup('{"app":"hommaterial","version":1,"lgTvs":[{"id":"abc","host":"x"}]}'));
});

const { selectBackup, BACKUP_SECTIONS, tileSpan } = await import('../shared/backup.js');
await test('import custom devices and groups without changing favorites, credentials or runtime choices', () => {
  const local = createBackup({ favorites: ['local'], groups: [], macros: [], tileSpans: { local: 2 }, schedules: [], monitor: { enabled: false } }, { token: 'local' });
  const incoming = createBackup({ favorites: ['group:g'], groups: [{ id: 'g', name: 'Luci', devices: ['1'] }], macros: [{ id: 'm', steps: [] }], monitor: { enabled: true } }, { token: 'incoming' });
  const merged = selectBackup(local, incoming, ['macros', 'groups']);
  assert.equal(merged.groups[0].id, 'g'); assert.equal(merged.macros[0].id, 'm');
  for (const key of ['favorites', 'tileSpans', 'schedules', 'monitor', 'switchBot']) assert.deepEqual(merged[key], local[key]);
  merged.groups[0].name = 'Changed'; assert.equal(incoming.groups[0].name, 'Luci');
  assert.deepEqual(selectBackup(local, incoming, []), local);
  assert.deepEqual(selectBackup(local, { app: 'hommaterial', version: 1 }, BACKUP_SECTIONS.map(([id]) => id)), local);
});
await test('favorite groups appear at the top and on their own favorites page', () => {
  const s = { devices: [{ applianceId: '1', name: 'Lampada', hasPower: true, room: 'Sala' }], rooms: {}, roomList: [], hidden: [], hiddenRemotes: [],
    groups: [{ id: 'g', name: 'Luci', devices: ['1'] }], macros: [], remotes: [], favorites: ['group:g'], monitor: { enabled: false }, order: {} };
  assert(model.availablePages(s).includes(model.PAGE_FAVORITES));
  assert.equal(model.homeSections(model.PAGE_ALL, s)[0].entries[0].id, 'group:g');
  const favorites = model.homeSections(model.PAGE_FAVORITES, s);
  assert.equal(favorites.length, 1); assert.equal(favorites[0].entries[0].id, 'group:g');
  assert(model.homeSections(model.PAGE_ALL, s).some((section) => section.key === 'groups'));
  assert.deepEqual(model.pagePicks(s, model.PAGE_FAVORITES).map((p) => p.key), ['g']);
  s.monitor = { enabled: true, only: { favorites: [] } };
  assert.equal(model.homeSections(model.PAGE_FAVORITES, s).length, 0);
});
await test('portable tile widths default to one and shrink to fit a single column', () => {
  const backup = parseBackup(JSON.stringify(createBackup({ tileSpans: { 'group:g': 2, '1': 1 } })));
  assert.equal(tileSpan(backup.tileSpans, 'group:g', 4), 2);
  assert.equal(tileSpan(backup.tileSpans, 'group:g', 1), 1);
  assert.equal(tileSpan({}, 'missing', 4), 1);
  assert.equal(tileSpan({ bad: 999 }, 'bad', 4), 1);
});


const lg = await import('../server/src/lgtv.ts');

await test('the commands of the app become those of webOS', () => {
  assert.deepEqual(lg.webOsAction('turnOn'), { wake: true });
  assert.deepEqual(lg.webOsAction('volumeAdd'), { call: 'ssap://audio/volumeUp' });
  assert.deepEqual(lg.webOsAction('setMute'), { key: 'MUTE' });
  assert.deepEqual(lg.webOsAction('SetChannel', '12'), { call: 'ssap://tv/openChannel', payload: { channelNumber: '12' } });
  assert.equal(lg.webOsAction('SetChannel', 'x'), null);
  assert.equal(lg.webOsAction('setVolume', '101'), null);
  assert.deepEqual(lg.webOsAction('app:netflix'), { call: 'ssap://system.launcher/launch', payload: { id: 'netflix' } });
  assert.deepEqual(lg.webOsAction('input:HDMI_1'), { call: 'ssap://tv/switchInput', payload: { inputId: 'HDMI_1' } });
  assert.deepEqual(lg.webOsAction('key:RED'), { key: 'RED' });
  assert.equal(lg.webOsAction('key:red'), null);
  assert.equal(lg.webOsAction('bogus'), null);
});

await test('only the difference of the text goes to the TV', () => {
  assert.deepEqual(lg.webOsTyping('', 'ciao'), [{ call: 'ssap://com.webos.service.ime/insertText', payload: { text: 'ciao', replace: 0 } }]);
  assert.deepEqual(lg.webOsTyping('ciao', 'cia mondo').map((c) => c.payload), [{ count: 1 }, { text: ' mondo', replace: 0 }]);
  assert.deepEqual(lg.webOsTyping('a😀', 'a'), [{ call: 'ssap://com.webos.service.ime/deleteCharacters', payload: { count: 1 } }]);
  assert.deepEqual(lg.webOsTyping('same', 'same'), []);
});

await test('the search finds LG TVs and their names', () => {
  const answer = 'HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\nDLNADeviceName.lge.com: TV%20Sala\r\n\r\n';
  assert.deepEqual(lg.parseSsdp(answer, '192.168.1.5'), { host: '192.168.1.5', name: 'TV Sala' });
  assert.equal(lg.parseSsdp('HTTP/1.1 200 OK\r\nST: other\r\n\r\n', 'x'), null);
  assert.equal(lg.parseSsdp('NOTIFY * HTTP/1.1\r\n', 'x'), null);
});

await test('the magic packet wakes the right address', () => {
  const packet = lg.wakeOnLanPacket('aa:bb:cc:dd:ee:ff');
  assert.equal(packet.length, 102);
  assert.deepEqual([...packet.subarray(0, 6)], [255, 255, 255, 255, 255, 255]);
  assert.deepEqual([...packet.subarray(96)], [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);
  assert.throws(() => lg.wakeOnLanPacket('nope'));
});

const ai = await import('../server/src/assistant.ts');

await test('the answer of the model is found in its words', () => {
  assert.deepEqual(ai.extractJson('<think>hmm {"no":1}</think>Ecco: {"text":"accendi TV Flavio"} fine'), { text: 'accendi TV Flavio' });
  assert.equal(ai.extractJson('nothing'), null);
});

const { withUser } = await import('../server/src/db.ts');

await test('the assistant knows the home by short ids and asks only about what exists', () => {
  withUser(1, () => {
    const catalog = new ai.Catalog({
      devices: [device('1', 'TV Flavio', 'Camera'), device('2', 'Sensore', 'Salotto', false)],
      groups: [{ id: 'g', name: 'Tutto', devices: ['1'] }],
      macros: [{ key: 'macro:m', name: 'Postazione', room: null }],
      remotes: [{ id: 'lg:x', key: 'remote:lg:x', name: 'TV Sala', type: 'LG webOS', room: 'Sala', keys: [{ command: 'key:RED', label: 'Rosso' }] }],
    });
    assert.match(catalog.names(), /"TV Flavio" \(device, in "Camera"\)/);
    assert.doesNotMatch(catalog.names(), /Sensore/);
    assert.match(catalog.describe(false), /r1: remote "TV Sala" in "Sala", LG TV; keys: key:RED=Rosso \[NEW\]/);
    const questions = ai.parseQuestions({ questions: [{ target: 'd1', question: 'Chi?' }, { target: 'r1/F1', question: 'Tasto?' }, { target: 'z9', question: 'No' }] }, catalog);
    assert.deepEqual(questions.map((q) => q.target), ['1', 'remote:lg:x']);
  });
});

await test('the agent keeps only what names things of the home, by their key', () => {
  withUser(1, () => {
    const catalog = new ai.Catalog({
      devices: [
        { applianceId: 'lamp', name: 'Lampada', hasPower: true, category: 'LIGHT' },
        { applianceId: 'temp', name: 'Termometro', isSensor: true, category: 'SENSOR' },
      ],
      groups: [{ id: 'g', name: 'Sera', devices: ['lamp'] }],
      macros: [{ key: 'macro:m', name: 'Notte' }],
      remotes: [],
    });
    const plan = ai.parseAgent({ reply: 'Ok', actions: [
      { do: 'power', target: 'g1', on: false }, { do: 'power', target: 'd2' }, { do: 'later', target: 'd1', on: false, minutes: 20 },
      { do: 'automation', sensor: 'd2', when: 'temperatureAbove', threshold: 26, target: 'm1' }, { do: 'run', target: 'd1' },
      { do: 'list', list: 'Spesa', items: ['uova', ' '] }, { do: 'event', title: 'Dentista', start: '2026-11-12T15:00' },
    ] }, catalog);
    assert.deepEqual(plan.actions.map((a) => [a.do, a.key ?? a.list ?? a.title]), [
      ['power', 'group:g'], ['later', 'lamp'], ['automation', 'macro:m'], ['list', 'Spesa'], ['event', 'Dentista'],
    ]);
    assert.equal(ai.parseAgent({ reply: '', alexa: true, actions: [] }, catalog).alexa, true);
    assert.match(ai.agentPrompt(catalog, '', 'Italian', 'oggi', 'Lists: "Spesa"'), /Answer in Italian\. Now it is oggi\.[\s\S]*d1: device "Lampada"[\s\S]*Lists: "Spesa"$/);
  });
});

const football = await import('../shared/football.js');
const espnTeam = (id, name, extra = {}) => ({ id, displayName: name, shortDisplayName: name, logos: [{ href: `logo${id}` }], ...extra });
const espnMatch = (id, date, state, home, away, scores = [], extra = {}) => ({
  id, date, league: { abbreviation: 'Serie A' },
  competitions: [{ status: { type: { state, shortDetail: state === 'post' ? 'FT' : '' } }, venue: { fullName: 'Stadio' },
    competitors: [
      { homeAway: 'home', team: espnTeam(home[0], home[1]), score: scores[0] == null ? undefined : { displayValue: String(scores[0]) }, winner: extra.homeWins === true },
      { homeAway: 'away', team: espnTeam(away[0], away[1]), score: scores[1] == null ? undefined : { displayValue: String(scores[1]) } },
    ] }],
  ...(extra.timeValid === false ? { timeValid: false } : {}),
});

await test('the teams, the matches and the table of ESPN are read', () => {
  const teams = football.parseTeams({ sports: [{ leagues: [{ teams: [
    { team: espnTeam('2', 'Roma', { color: '990a2c' }) }, { team: espnTeam('1', 'Inter', { color: 'zz' }) },
  ] }] }] });
  assert.deepEqual(teams.map((t) => [t.id, t.name, t.color]), [['1', 'Inter', null], ['2', 'Roma', '#990a2c']]);
  const [m] = football.parseMatches({ events: [espnMatch('9', '2026-11-08T19:45Z', 'post', ['1', 'Inter'], ['2', 'Roma'], [2, 1])] });
  assert.equal(m.home.name, 'Inter');
  assert.equal(m.away.score, '1');
  assert.equal(m.competition, 'Serie A');
  assert.equal(m.venue, 'Stadio');
  const stats = (rank, points) => [{ name: 'rank', value: rank }, { name: 'points', value: points }, { name: 'wins', value: 3 }, { name: 'ties', value: 1 }, { name: 'losses', value: 0 }];
  const table = football.parseTable({ children: [{ standings: { entries: [
    { team: espnTeam('2', 'Roma'), stats: stats(2, 7) },
    { team: espnTeam('1', 'Inter'), stats: stats(1, 10), note: { description: 'Champions League', color: '#81D6AC' } },
  ] } }] });
  assert.deepEqual(table.map((r) => [r.rank, r.name, r.points, r.draws]), [[1, 'Inter', 10, 1], [2, 'Roma', 7, 1]]);
  assert.equal(table[0].zone, 'Champions League');
});

await test('the next match, the last five and how each went', () => {
  const now = Date.parse('2026-11-06T12:00Z');
  const fixtures = football.parseMatches({ events: [
    espnMatch('a', '2026-11-20T19:45Z', 'pre', ['1', 'Inter'], ['3', 'Lazio']),
    espnMatch('b', '2026-11-08T19:45Z', 'pre', ['2', 'Roma'], ['1', 'Inter'], [], { timeValid: false }),
    espnMatch('old', '2026-10-01T19:45Z', 'pre', ['2', 'Roma'], ['1', 'Inter']),
  ] });
  const results = football.parseMatches({ events: [
    espnMatch('r1', '2026-11-01T19:45Z', 'post', ['1', 'Inter'], ['2', 'Roma'], [2, 0]),
    espnMatch('r2', '2026-11-04T19:45Z', 'post', ['3', 'Lazio'], ['1', 'Inter'], [1, 1]),
    espnMatch('r3', '2026-10-20T19:45Z', 'post', ['3', 'Lazio'], ['1', 'Inter'], [1, 1], { homeWins: true }),
  ] });
  const info = football.teamSummary('1', { table: [{ id: '1', rank: 1 }], fixtures, results }, now);
  assert.equal(info.next.id, 'b');
  assert.equal(info.next.timeValid, false);
  assert.deepEqual(info.upcoming.map((m) => m.id), ['a']);
  assert.deepEqual(info.recent.map((m) => m.id), ['r2', 'r1', 'r3']);
  assert.equal(info.standing.rank, 1);
  assert.deepEqual(info.recent.map((m) => football.outcome(m, '1')), ['D', 'W', 'L']);
  assert.equal(football.outcome(fixtures[0], '1'), null);
});

const { matches } = await import('../shared/search.js');
await test('the settings are found by part of a word, a synonym or with a typo', () => {
  const monitor = 'Modalità monitor, tablet, pannello, salvaschermo, luminosità';
  assert.ok(matches('', monitor));
  assert.ok(matches('lumin', monitor));
  assert.ok(matches('luminosita', monitor));
  assert.ok(matches('pannelo', monitor));
  assert.ok(matches('schermo', monitor));
  assert.ok(matches('tablet di casa', 'tablet, casa'));
  assert.ok(!matches('calcio', monitor));
  assert.ok(!matches('tab calcio', monitor));
  assert.ok(matches('squdra', 'Squadra del cuore, calcio'));
});

const views = await import('../shared/lists.js');
await test('lists renamed and joined in the app only', () => {
  const lists = [{ id: 'a', name: 'Spesa', active: 2 }, { id: 'b', name: 'Casa', active: 1 }, { id: 'c', name: 'Lavoro', active: null }];
  let v = views.rename(views.EMPTY_VIEWS, 'c', '  Ufficio ');
  assert.deepEqual(views.applyViews(lists, v).map((l) => l.name), ['Spesa', 'Casa', 'Ufficio']);
  v = views.merge(v, ['b', 'a']);
  const shown = views.applyViews(lists, v);
  assert.deepEqual(shown.map((l) => [l.name, l.active]), [['Casa + Spesa', 3], ['Ufficio', null]]);
  assert.deepEqual(views.members(shown[0].id), ['b', 'a']);
  v = views.rename(v, shown[0].id, 'Tutto');
  assert.equal(views.applyViews(lists, v)[0].name, 'Tutto');
  // A list already joined leaves its old group, which parts once it has one list left.
  v = views.merge(v, ['a', 'c']);
  assert.deepEqual(v.merged, [['a', 'c']]);
  // A list that is gone leaves the group shown as the other list.
  assert.deepEqual(views.applyViews(lists.slice(1), v).map((l) => l.id), ['b', 'c']);
  v = views.split(v, views.viewId(['a', 'c']));
  assert.deepEqual(v.merged, []);
  assert.equal(views.rename(v, 'c', '').names.c, undefined);
});

if (failures) {
  console.log(`${failures} failed`);
  process.exit(1);
}
console.log('all passed');

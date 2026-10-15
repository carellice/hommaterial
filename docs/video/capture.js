const puppeteer = require('puppeteer-core');
const fps = +process.argv[2] || 60, only = process.argv[3], vert = process.env.VERT === '1';
const [w, h] = vert ? [1080, 1920] : [1920, 1080];
const dir = vert ? 'frames_v' : 'frames', sdir = vert ? 'stills_v' : 'stills';
(async () => {
  const b = await puppeteer.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless:'new', args:['--force-color-profile=srgb','--hide-scrollbars']});
  const pg = await b.newPage();
  await pg.setViewport({width:w, height:h, deviceScaleFactor:1});
  await pg.goto('file://' + __dirname + '/promo.html' + (vert ? '?v' : ''));
  await pg.waitForFunction('window.ready');
  if (only && only !== 'all') {
    for (const t of only.split(',')) { await pg.evaluate(t => seek(t), +t); await pg.screenshot({path:`${sdir}/t${t}.png`}); }
  } else {
    const n = Math.round(await pg.evaluate('DURATION') * fps);
    for (let i = 0; i < n; i++) { await pg.evaluate(t => seek(t), i / fps); await pg.screenshot({path:`${dir}/f${String(i).padStart(4,'0')}.png`}); }
  }
  await b.close();
})();

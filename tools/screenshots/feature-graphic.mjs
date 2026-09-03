import { chromium } from '/home/bschleifer/projects/personal/shotcraft/node_modules/.pnpm/playwright@1.59.1/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import os from 'node:os';
const SP = process.argv[2];
const b64 = n => fs.readFileSync(`${SP}/dial-${n}.png`).toString('base64');
const dials = { steel: b64('brushed-steel'), taupe: b64('knotwork-taupe') };
// the brand mark, minus its rounded-rect ground so it can sit on any colour
let svg = fs.readFileSync(os.homedir() + '/projects/personal/bfg-watchfaces/docs/brand/icon-light.svg', 'utf8');
svg = svg.replace(/<rect[^>]*\/>/, '');
const markOf = colour => 'data:image/svg+xml;base64,' +
  Buffer.from(svg.replace(/#80475C/g, colour)).toString('base64');

const browser = await chromium.launch();
const page = await browser.newPage();
await page.setContent(`<html><head><link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;700;800&display=swap"></head><body></body></html>`);
await page.evaluate(() => document.fonts.ready);
const out = await page.evaluate(async ({ d, markWhite, markPlum }) => {
  const load = async s => { const i = new Image(); i.src = s; await i.decode(); return i; };
  const steel = await load('data:image/png;base64,' + d.steel);
  const taupe = await load('data:image/png;base64,' + d.taupe);
  const mW = await load(markWhite), mP = await load(markPlum);
  const W = 1024, H = 500, FONT = "'Manrope', system-ui, sans-serif";
  const mk = draw => { const c = document.createElement('canvas'); c.width = W; c.height = H;
    const x = c.getContext('2d'); x.imageSmoothingQuality = 'high'; draw(x); return c.toDataURL('image/png'); };
  const disc = (x, s, cx, cy) => { x.save(); x.shadowColor='rgba(0,0,0,.45)'; x.shadowBlur=46; x.shadowOffsetY=14;
    x.fillStyle='#000'; x.beginPath(); x.arc(cx,cy,s/2,0,Math.PI*2); x.fill(); x.restore(); };
  const put = (x, img, s, cx, cy) => { x.save(); x.beginPath(); x.arc(cx,cy,s/2,0,Math.PI*2); x.clip();
    x.drawImage(img, cx-s/2, cy-s/2, s, s); x.restore(); };
  const art = (x, ground) => {
    disc(x,320,940,250); put(x,taupe,320,940,250);
    disc(x,400,762,250); put(x,steel,400,762,250);
    const g = x.createLinearGradient(0,0,600,0);
    g.addColorStop(0,ground); g.addColorStop(.8,ground); g.addColorStop(1,ground.replace(')',', 0)').replace('rgb','rgba'));
    x.fillStyle=g; x.fillRect(0,0,600,H);
  };
  const lockup = (x, mark, colour, y, markSize, nameSize) => {
    x.drawImage(mark, 64, y - markSize*0.78, markSize, markSize);
    x.fillStyle = colour; x.font = `800 ${nameSize}px ${FONT}`;
    x.fillText('BFG Watch Faces', 64 + markSize + 14, y);
  };

  // no name — the version already on the table
  const NONE = mk(x => { x.fillStyle='rgb(128,71,92)'; x.fillRect(0,0,W,H); art(x,'rgb(128,71,92)');
    x.fillStyle='#FFF'; x.font=`800 52px ${FONT}`;
    x.fillText('Design your own',64,210); x.fillText('watch face',64,272);
    x.fillStyle='#F4E6EB'; x.font=`500 25px ${FONT}`; x.fillText('Free. No ads, no account.',64,330); });

  // name as a small lockup, headline trimmed so "watch face" is said once
  const TOP = mk(x => { x.fillStyle='rgb(128,71,92)'; x.fillRect(0,0,W,H); art(x,'rgb(128,71,92)');
    lockup(x, mW, '#FFFFFF', 152, 40, 26);
    x.fillStyle='#FFF'; x.font=`800 47px ${FONT}`; x.fillText('Design your own',64,242);
    x.fillStyle='#F4E6EB'; x.font=`500 25px ${FONT}`; x.fillText('Free. No ads, no account.',64,300); });

  // name as the statement
  const HERO = mk(x => { x.fillStyle='rgb(128,71,92)'; x.fillRect(0,0,W,H); art(x,'rgb(128,71,92)');
    x.drawImage(mW, 64, 150, 64, 64);
    x.fillStyle='#FFF'; x.font=`800 46px ${FONT}`;
    x.fillText('BFG', 64, 268); x.fillText('Watch Faces', 64, 318);
    x.fillStyle='#F4E6EB'; x.font=`500 24px ${FONT}`;
    x.fillText('Design your own. Free, no ads.', 64, 366); });

  // same lockup, blush ground
  const BLUSH = mk(x => { x.fillStyle='rgb(244,230,235)'; x.fillRect(0,0,W,H); art(x,'rgb(244,230,235)');
    lockup(x, mP, '#80475C', 152, 40, 26);
    x.fillStyle='#80475C'; x.font=`800 47px ${FONT}`; x.fillText('Design your own',64,242);
    x.fillStyle='#5C4650'; x.font=`500 25px ${FONT}`; x.fillText('Free. No ads, no account.',64,300); });

  return { NONE, TOP, HERO, BLUSH };
}, { d: dials, markWhite: markOf('#FFFFFF'), markPlum: markOf('#80475C') });

for (const [k,url] of Object.entries(out))
  fs.writeFileSync(`${SP}/name-${k}.png`, Buffer.from(url.split(',')[1],'base64'));
await browser.close(); console.log('rendered', Object.keys(out).join(' '));

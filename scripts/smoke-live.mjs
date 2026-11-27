// Real-window smoke test. Requires a booted emulator, installed APKs and running Lab.
// This flag selects only the named emulator window; it never provides fake video.
import { chromium } from '../dashboard/node_modules/playwright/index.mjs';
import fs from 'node:fs/promises';

const option = (name, fallback) => {
  const at = process.argv.indexOf(name);
  return at >= 0 ? process.argv[at+1] : fallback;
};
const methodId = option('--method', 'dedup');
const caseId = option('--case', 'default');
const windowTitle = option('--window-title', 'Android Emulator - AdaptiveNewsLab:5554');
const baseURL = 'http://127.0.0.1:8765';
const ready = await fetch(baseURL+'/api/preflight').then(r=>r.json());
if (!ready.ready) throw new Error('Preflight chưa đạt: '+JSON.stringify(ready.checks.filter(c=>!c.ok)));
const catalog = await fetch(baseURL+'/api/methods').then(r=>r.json());
const method = catalog.methods.find(m=>m.id===methodId && m.enabled);
if (!method || !method.cases.some(c=>c.id===caseId)) throw new Error('Unknown method/case');
const browser = await chromium.launch({channel:'chrome',headless:false,args:[`--auto-select-window-capture-source-by-title=${windowTitle}`]});
try {
  const page = await browser.newPage({viewport:{width:1600,height:1100}});
  page.on('pageerror', error=>console.error('Browser:',error.message));
  await page.goto(baseURL);
  await page.getByRole('button',{name:method.title,exact:true}).click();
  await page.locator('.setup-controls select').first().selectOption(caseId);
  if (process.argv.includes('--slow')) await page.locator('.setup-controls select').nth(1).selectOption('slow');
  await page.getByRole('button',{name:'Kết nối evidence',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('video')?.readyState>=2,{}, {timeout:20000});
  await page.waitForFunction(()=>!document.querySelector('.setup-foot')?.textContent.includes('Hoàn tất'),{}, {timeout:20000});
  const video=page.locator('.video-surface video');
  await video.scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  const box=await video.boundingBox();
  if (!box) throw new Error('No video frame');
  // AVD window title is approximately 3.2% of the source height; no toolbar is captured.
  await page.mouse.click(box.x+box.width*.02,box.y+box.height*.04);
  await page.waitForTimeout(150);
  const secondBox = await video.boundingBox();
  await page.mouse.click(secondBox.x+secondBox.width*.98,secondBox.y+secondBox.height*.98);
  await page.waitForTimeout(150);
  console.log('Capture setup:', await page.locator('.setup-foot').innerText(), await page.locator('.calibration-tip').allTextContents());
  await page.screenshot({path:'.lab/live-ready.png',fullPage:true});
  const previous = new Set((await fetch(baseURL+'/api/runs').then(r=>r.json())).map(r=>r.id));
  await page.getByRole('button',{name:'Chạy demo A/B',exact:true}).click();
  let runId;
  for (let i=0; i<80; i++) {
    const runs=await fetch(baseURL+'/api/runs').then(r=>r.json());
    runId=runs.find(r=>!previous.has(r.id) && r.method_id===methodId)?.id;
    if (runId) break;
    await page.waitForTimeout(250);
  }
  if (!runId) throw new Error('Dashboard did not create a new run: '+await page.locator('main').innerText());
  console.log('Live smoke run:',runId);
  let run;
  const deadline=Date.now()+12*60*1000;
  while (Date.now()<deadline) {
    run=await fetch(`${baseURL}/api/runs/${runId}`).then(r=>r.json());
    if (['completed','failed','cancelled','incomplete'].includes(run.status)) break;
    console.log(run.status,run.trials.map(t=>`${t.arm}/${t.phase}:${t.status}`).join(' '));
    await page.waitForTimeout(3000);
  }
  await fs.mkdir('.lab',{recursive:true});
  await page.screenshot({path:`.lab/live-${methodId}.png`,fullPage:true});
  if (!run || run.status!=='completed') throw new Error(JSON.stringify({status:run?.status,error:run?.error}));
  console.log(JSON.stringify({id:run.id,status:run.status,summary:run.summary},null,2));
} finally { await browser.close(); }

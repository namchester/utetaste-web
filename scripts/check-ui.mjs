/** Run after Maven test/package: node scripts/check-ui.mjs [path-to-chrome]
 * Uses Node built-ins, Chromium, private H2 and a loopback SMTP test double.
 * Never connects to the normal application or reads .env.
 */
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';

const auditMode = process.argv.includes('--audit');
const output = path.resolve(process.argv.find(arg => arg.startsWith('--output='))?.slice(9) || 'target/ui-check');
fs.mkdirSync(output, {recursive: true});
const browserPath = process.argv.slice(2).find(arg => !arg.startsWith('--')) || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
assert(fs.existsSync(browserPath), 'Pass the path to an installed Chromium browser.');
const xml = fs.readFileSync('target/surefire-reports/TEST-vn.utetaste.UtetasteIntegrationTest.xml', 'utf8');
const classpath = xml.match(/<property name="java.class.path" value="([^"]+)"/)?.[1]
  .replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
assert(classpath, 'Run Maven test/package first to produce the test classpath.');
assert(classpath.includes('h2'), 'The private preview requires the test-only H2 driver.');
// Surefire can serialize workspace paths through the Windows legacy encoding.
// Use relative class directories to preserve Vietnamese workspace paths on Windows.
const entries = classpath.split(path.delimiter);
entries[0] = 'target/test-classes';
entries[1] = 'target/classes';
const port = await new Promise(resolve => {
  const probe = net.createServer();
  probe.listen(0, '127.0.0.1', () => {const port = probe.address().port; probe.close(() => resolve(port));});
});
const origin = 'http://127.0.0.1:' + port;
// Accept mail only on loopback, keep it in memory, never relay or log message bodies/OTPs.
const mailMessages = [];
const smtpClients = new Set();
const smtp = net.createServer(client => {
  smtpClients.add(client); client.on('close', () => smtpClients.delete(client));
  client.setEncoding('utf8'); client.write('220 localhost test SMTP\r\n');
  let buffer = '', data = false, body = [];
  client.on('data', chunk => {
    buffer += chunk;
    let end;
    while ((end = buffer.indexOf('\r\n')) >= 0) {
      const line = buffer.slice(0, end); buffer = buffer.slice(end + 2);
      if (data) {
        if (line === '.') {mailMessages.push(body.join('\r\n')); body = []; data = false; client.write('250 accepted\r\n');}
        else body.push(line);
      } else if (/^EHLO/i.test(line)) client.write('250-localhost\r\n250 8BITMIME\r\n');
      else if (/^DATA/i.test(line)) {data = true; client.write('354 send data\r\n');}
      else if (/^QUIT/i.test(line)) client.end('221 bye\r\n');
      else client.write('250 OK\r\n');
    }
  });
  client.on('error', () => client.destroy());
});
await new Promise(resolve => smtp.listen(0, '127.0.0.1', resolve));
function lastOtp() {
  const message = mailMessages.at(-1) || '';
  const headers = message.split('\r\n\r\n')[0];
  let body = message.split('\r\n\r\n').slice(1).join('\r\n\r\n');
  if (/Content-Transfer-Encoding:\s*base64/i.test(headers)) body = Buffer.from(body.replace(/\s/g,''),'base64').toString('utf8');
  else body = body.replace(/=\r\n/g,'');
  const match = body?.match(/(?<![0-9])[0-9]{8}(?![0-9])/);
  assert(match, 'OTP received by private SMTP test double'); return match[0];
}
const serverLog = fs.openSync(path.join(output, 'server.log'), 'w');
const server = spawn('java', ['-cp', entries.join(path.delimiter), 'vn.utetaste.UtetasteApplication',
  '--spring.profiles.active=test', '--server.address=127.0.0.1', '--server.port=' + port,
  '--spring.datasource.url=jdbc:h2:mem:ui-preview;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000',
  '--spring.datasource.driver-class-name=org.h2.Driver', '--spring.datasource.username=sa', '--spring.datasource.password=',
  '--spring.jpa.hibernate.ddl-auto=create-drop', '--spring.flyway.enabled=false',
  '--app.seed-demo=true', '--app.demo-password=UiPreview@Test2026', '--app.admin-password=UiPreview@Test2026',
  '--app.jwt-secret=ui-preview-only-secret-at-least-32-bytes-long', '--app.cookie-secure=false', '--app.base-url=' + origin,
  '--app.cloudinary.cloud-name=', '--app.cloudinary.api-key=', '--app.cloudinary.api-secret=',
  '--app.otp-secret=ui-only-otp-secret-at-least-32-bytes-long',
  '--app.mail.enabled=true', '--app.mail.from=no-reply@example.test',
  '--spring.mail.host=127.0.0.1', '--spring.mail.port=' + smtp.address().port,
  '--spring.mail.username=', '--spring.mail.password=', '--spring.mail.properties.mail.smtp.auth=false',
  '--spring.mail.properties.mail.smtp.starttls.enable=false', '--spring.mail.properties.mail.smtp.starttls.required=false',
  '--spring.thymeleaf.prefix=file:src/main/resources/templates/', '--spring.thymeleaf.cache=false',
  '--spring.web.resources.static-locations=file:src/main/resources/static/,classpath:/META-INF/resources/',
  '--logging.level.root=WARN', '--logging.level.org.hibernate.SQL=WARN', '--logging.level.org.springframework.web=WARN'
], {windowsHide: true, stdio: ['ignore', serverLog, serverLog]});

let browser, socket, sequence = 0, session;
const pending = new Map(), handlers = new Map(), errors = [], checks = [], posts = [], responses = [];
const requestPaths = new Map(), responseCookies = [];
const auditedPages = new Map(), responsiveFailures = [];
let intercepted, requestPattern;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const query = selector => 'document.querySelector(' + JSON.stringify(selector) + ')';
async function waitFor(check, description, timeout = 20000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    try {if (await check()) return;} catch (error) {if (server.exitCode !== null) throw error;}
    await pause(100);
  }
  throw new Error('Timed out: ' + description);
}
function command(method, params = {}, targetSession = session) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {pending.delete(id); reject(new Error('CDP timeout: ' + method));}, 20000);
    pending.set(id, {resolve, reject, timer});
    socket.send(JSON.stringify({id, method, params, ...(targetSession ? {sessionId: targetSession} : {})}));
  });
}
async function evaluate(expression) {
  const result = await command('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
}
function on(method, callback) {handlers.set(method, callback);}
async function navigate(url) {
  await command('Page.navigate', {url: url.startsWith('http') ? url : origin + url});
  await waitFor(() => evaluate('document.readyState === "complete" && location.href === ' + JSON.stringify(url.startsWith('http') ? url : origin + url)), 'navigation ' + url);
  await evaluate('document.fonts.ready.then(() => true)');
}
async function submit(expression) {
  const before = await evaluate('performance.timeOrigin');
  await evaluate(expression).catch(error => {if (!/context.*destroyed|Cannot find context/i.test(error.message)) throw error;});
  await waitFor(() => evaluate('performance.timeOrigin !== ' + before + ' && document.readyState === "complete"'), 'form navigation');
}
async function viewport(width, height) {
  await command('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: false});
}
async function check(name, expression) {
  assert(await evaluate(expression), name);
  checks.push(name); console.log('PASS ' + name);
}
async function layout(name, width, height) {
  if (auditMode && !name.includes('print')) await auditResponsive(name);
  await viewport(width, height);
  await evaluate('document.fonts.ready.then(() => true)');
  await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))).then(()=>Promise.all(document.getAnimations().filter(animation=>animation.effect.getComputedTiming().iterations!==Infinity).map(animation=>animation.finished.catch(()=>{})))).then(()=>true)');
  await check(name + ' has no horizontal page overflow', 'document.documentElement.scrollWidth <= innerWidth + 1');
  if (width <= 420 && await evaluate('location.pathname === "/"')) {
    assert(await evaluate('document.querySelector(".luxury-nav .brand-text").getBoundingClientRect().right + 4 <= document.querySelector(".nav-actions").getBoundingClientRect().left'), 'Mobile header branding and controls do not overlap');
  }
  const result = await command('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
  fs.writeFileSync(path.join(output, name + '.png'), Buffer.from(result.data, 'base64'));
}
async function auditResponsive(name) {
  const info = await evaluate('({path:location.pathname,title:document.title,role:document.querySelector(".user-role")?.textContent.trim() || (document.querySelector("#profile-summary")?.textContent || "public/guest/account"),editing:new URLSearchParams(location.search).has("edit")})');
  const key = JSON.stringify(info);
  if (auditedPages.has(key)) return;
  const widths = [320,375,390,768,1024,1440];
  const record = {name,...info,widths,failures:[]};auditedPages.set(key,record);
  for (const width of widths) {
    await viewport(width,900);
    await evaluate('document.fonts.ready.then(()=>true)');
    await pause(80);
    const metrics = await evaluate('({width:innerWidth,scroll:document.documentElement.scrollWidth})');
    if (metrics.scroll > metrics.width + 1) {record.failures.push(width);responsiveFailures.push({name,width,...metrics});}
    else checks.push('Audit '+name+' '+width+'px has no horizontal overflow');
    const spaced = await evaluate('(()=>[...document.querySelectorAll(".button-actions,.cart-links,.catalog-row-actions,.catalog-filter-actions")].every(group=>{const [a,b]=[...group.children].map(el=>el.getBoundingClientRect());return !a||!b||Math.max(b.left-a.right,b.top-a.bottom)>=9.9}))()');
    if (!spaced) {record.failures.push(width);responsiveFailures.push({name,width,reason:'Action buttons touch'});}
    const screenshot = await command('Page.captureScreenshot',{format:'png'});
    fs.writeFileSync(path.join(output,'audit-'+name+'-'+width+'.png'),Buffer.from(screenshot.data,'base64'));
  }
  fs.writeFileSync(path.join(output,'responsive-audit.json'),JSON.stringify({pages:[...auditedPages.values()],failures:responsiveFailures},null,2));
}
async function contrast(selector) {
  const ratio = await evaluate(`(() => {
    const node=document.querySelector(${JSON.stringify(selector)});
    const rgb=color=>color.match(/[\\d.]+/g).map(Number);
    const luminance=color=>rgb(color).slice(0,3).map(x=>{x/=255;return x<=.04045?x/12.92:((x+.055)/1.055)**2.4}).reduce((sum,x,i)=>sum+x*[.2126,.7152,.0722][i],0);
    let background=node;
    while(background.parentElement && rgb(getComputedStyle(background).backgroundColor)[3]===0)background=background.parentElement;
    const a=luminance(getComputedStyle(node).color),b=luminance(getComputedStyle(background).backgroundColor);
    return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
  })()`);
  assert(ratio >= 4.5, selector + ' contrast >= 4.5:1 (actual ' + ratio.toFixed(2) + ')');
  checks.push(selector + ' text contrast >= 4.5:1');
}

try {
  await waitFor(async () => {const response = await fetch(origin); return response.ok;}, 'private H2 application', 60000);
  let endpoint = '';
  browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', '--user-data-dir=' + path.join(output, 'browser-profile-' + Date.now()), 'about:blank'
  ], {windowsHide: true, stdio: ['ignore', 'ignore', 'pipe']});
  browser.stderr.on('data', data => {endpoint ||= data.toString().match(/DevTools listening on (ws:\/\/\S+)/)?.[1] || '';});
  await waitFor(() => endpoint, 'Chromium debugging endpoint');
  socket = new WebSocket(endpoint);
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const request = pending.get(message.id);
      if (request) {clearTimeout(request.timer); pending.delete(message.id); message.error ? request.reject(new Error(message.error.message)) : request.resolve(message.result);}
    } else {
      const result = handlers.get(message.method)?.(message.params);
      if (result?.catch) result.catch(error => errors.push(error.message));
    }
  });
  await new Promise((resolve, reject) => {socket.addEventListener('open', resolve, {once: true}); socket.addEventListener('error', reject, {once: true});});
  const target = await command('Target.createTarget', {url: 'about:blank'}, null);
  session = (await command('Target.attachToTarget', {targetId: target.targetId, flatten: true}, null)).sessionId;
  await command('Page.enable'); await command('Runtime.enable'); await command('Network.enable');
  on('Runtime.exceptionThrown', event => errors.push(event.exceptionDetails.exception?.description || event.exceptionDetails.text));
  on('Network.responseReceived', event => {if(event.response.url.startsWith(origin)) responses.push({path:new URL(event.response.url).pathname,status:event.response.status});});
  on('Page.javascriptDialogOpening', () => command('Page.handleJavaScriptDialog', {accept: true}));
  on('Network.requestWillBeSent', event => {
    requestPaths.set(event.requestId, new URL(event.request.url).pathname);
    if (event.request.method === 'POST' && event.request.url.startsWith(origin)) {
      posts.push({url: new URL(event.request.url).pathname, fields: [...new URLSearchParams(event.request.postData || '').keys()]});
    }
  });
  on('Network.responseReceivedExtraInfo', event => {
    const header = Object.entries(event.headers).find(([key]) => key.toLowerCase() === 'set-cookie')?.[1];
    if(header) responseCookies.push({path:requestPaths.get(event.requestId),cookies:header.split('\n').map(cookie=>({name:cookie.split('=')[0],deleted:/max-age=0/i.test(cookie),httpOnly:/httponly/i.test(cookie)}))});
  });

  await navigate('/'); await layout('home-desktop', 1440, 1000); await contrast('.btn-primary'); await layout('home-mobile', 390, 844);
  await evaluate('document.querySelector("[data-public-nav]").click()');
  await check('Public navigation opens on mobile', 'document.querySelector("[data-public-nav]").getAttribute("aria-expanded") === "true" && document.querySelector("#public-navigation").getClientRects().length > 0');
  await command('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape'});
  // Escape handling is attached to the header; put focus on the toggle as a real user would.
  await evaluate('document.querySelector("[data-public-nav]").focus()');
  await command('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape'});
  await check('Public navigation closes with Escape', 'document.querySelector("[data-public-nav]").getAttribute("aria-expanded") === "false"');

  await navigate('/login'); await layout('login-mobile', 390, 844);
  await evaluate('document.querySelector("form").requestSubmit()');
  await check('Invalid form has associated field errors and does not enter loading', 'document.querySelector("#username").getAttribute("aria-invalid") === "true" && document.querySelector(".field-error[role=alert]") !== null && !document.querySelector("form").hasAttribute("aria-busy")');
  await evaluate('document.querySelector("#username").value="admin";document.querySelector("#password").value="UiPreview@Test2026";document.querySelectorAll("input").forEach(x=>x.dispatchEvent(new Event("input",{bubbles:true})))');
  await submit('document.querySelector("form").requestSubmit()');
  await check('Login form keeps credentials and CSRF in POST', 'location.pathname === "/staff"');
  assert(posts.some(post => post.url === '/login' && ['username', 'password', '_csrf'].every(field => post.fields.includes(field))));
  await layout('dashboard-desktop', 1440, 1000); await layout('dashboard-tablet', 768, 1024);
  await contrast('.page-heading p');
  await evaluate('document.querySelector("[data-nav-toggle]").click()');
  await check('Mobile sidebar isolates background and exposes dialog state', 'document.querySelector(".workspace").inert && document.querySelector("#staff-navigation").getAttribute("aria-modal") === "true"');
  await evaluate('document.querySelector(".logout-form button").focus()');
  await command('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Tab', code: 'Tab'});
  await check('Sidebar keyboard focus wraps within navigation', 'document.activeElement === document.querySelector("#staff-navigation a")');
  await command('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape'});
  await check('Escape closes sidebar and restores focus', '!document.querySelector(".workspace").inert && document.querySelector("#staff-navigation").inert && document.activeElement.matches("[data-nav-toggle]")');
  await layout('dashboard-mobile', 390, 844);

  await navigate('/admin/users'); await layout('users-mobile', 390, 844);
  await evaluate('document.querySelector(".table-responsive").focus()');
  await command('Input.dispatchKeyEvent', {type:'keyDown',key:'ArrowRight',code:'ArrowRight'});
  await waitFor(() => evaluate('document.querySelector(".table-responsive").scrollLeft > 0'), 'keyboard table scrolling');
  checks.push('Responsive tables scroll with the keyboard');
  await check('Admin username pattern accepts existing allowed hyphens', 'document.querySelector("#user-name").value="staff-test";document.querySelector("#user-name").validity.valid');
  const staffName = 'browser-staff-' + Date.now();
  await evaluate('document.querySelector("#user-name").value='+JSON.stringify(staffName)+';document.querySelector("#fullName").value="Browser staff";document.querySelector("#role").value="WAITER";document.querySelector("#user-branch").selectedIndex=1;document.querySelector("#user-password").value="Browser@Test2026"');
  await submit(query('#user-name')+'.form.requestSubmit()');
  await navigate('/admin/users?q='+encodeURIComponent(staffName)+'&role=WAITER&state=active');
  await check('Staff creation, search and role filter persist', 'document.querySelectorAll("tbody tr").length===1 && document.querySelector("tbody").textContent.includes('+JSON.stringify(staffName)+')');
  await evaluate(query('form[action$="/state"]')+'.requestSubmit()');
  await waitFor(()=>evaluate('!!document.querySelector(".modal.show")'),'staff lock confirmation');
  await layout('admin-staff-lock-confirm',390,844);
  await evaluate(query('.modal.show [data-bs-dismiss]')+'.click()');
  await waitFor(()=>evaluate('!document.querySelector(".modal.show")'),'staff cancel confirmation');
  await check('Cancelling staff lock keeps active state', 'document.querySelector("tbody").textContent.includes("Đang hoạt động")');
  await evaluate(query('form[action$="/state"]')+'.requestSubmit()');
  await waitFor(()=>evaluate('!!document.querySelector(".modal.show")'),'staff lock confirmation again');
  await submit(query('[data-confirm-accept]')+'.click()');
  await navigate('/admin/users?q='+encodeURIComponent(staffName)+'&state=inactive');
  await check('Staff lock is visible after server filtering', 'document.querySelector("tbody").textContent.includes('+JSON.stringify(staffName)+') && document.querySelector("tbody").textContent.includes("Đã khóa")');
  await evaluate(query('form[action$="/state"]')+'.requestSubmit()');
  await waitFor(()=>evaluate('!!document.querySelector(".modal.show")'),'staff unlock confirmation');
  await submit(query('[data-confirm-accept]')+'.click()');
  await navigate('/admin/users?q='+encodeURIComponent(staffName)+'&state=active');
  await check('Staff unlock restores active state', 'document.querySelector("tbody").textContent.includes('+JSON.stringify(staffName)+')');
  await layout('admin-staff-filter-mobile',390,844);
  if (auditMode) {const edit=await evaluate('document.querySelector("tbody a[href*=edit]").getAttribute("href")');await navigate(edit);await layout('staff-edit',390,844);}
  await navigate('/admin/menu'); await layout('admin-menu-desktop', 1440, 1000);
  await check('Dish bulk action text fits without clipping', '(()=>{const s=document.querySelector("#bulk-active"),style=getComputedStyle(s),c=document.createElement("canvas").getContext("2d");c.font=style.font;return s.labels[0].textContent.includes("Với món đã chọn")&&c.measureText(s.selectedOptions[0].text).width+parseFloat(style.paddingLeft)+parseFloat(style.paddingRight)<=s.clientWidth})()');
  await check('Bulk update is disabled until a dish is selected', 'document.querySelector("[data-bulk-submit]").disabled && document.querySelector("[data-dish-selection-count]").textContent==="Đã chọn 0 món"');
  await layout('admin-menu-toolbar-mobile',320,844);
  await evaluate('document.querySelector("#categories").scrollIntoView({block:"start",behavior:"instant"})');
  await layout('admin-menu-categories-mobile',390,844);
  await check('Menu pagination and categories render below the dish list', '!!document.querySelector("nav[aria-label=\\"Phân trang món\\"]") && document.querySelector("#categories").getBoundingClientRect().width<=innerWidth');
  await evaluate('window.scrollTo(0,0)');
  const temporaryCategory = 'QA-category-' + Date.now();
  await evaluate(query('#new-category') + '.value=' + JSON.stringify(temporaryCategory));
  await submit(query('#new-category') + '.form.requestSubmit()');
  const categoryRow = '[...document.querySelectorAll(".category-row")].find(row=>row.querySelector("input[name=name]").value===' + JSON.stringify(temporaryCategory) + ')';
  await check('New category persists after form navigation', '!!(' + categoryRow + ')');
  await evaluate('(' + categoryRow + ').querySelector("form[action$=delete]").requestSubmit()');
  await waitFor(() => evaluate('!!document.querySelector(".modal.show")'), 'category delete confirmation');
  await layout('admin-category-confirm', 390, 844);
  await check('Category deletion opens an accessible confirmation modal', 'document.querySelector(".modal.show").getAttribute("aria-modal") === "true"');
  await evaluate('document.querySelector(".modal [data-bs-dismiss=modal]").click()');
  await waitFor(() => evaluate('!document.querySelector(".modal.show")'), 'cancel deletion');
  await check('Cancel category deletion preserves the row', '!!(' + categoryRow + ')');
  await evaluate('(' + categoryRow + ').querySelector("form[action$=delete]").requestSubmit()');
  await waitFor(() => evaluate('!!document.querySelector(".modal.show")'), 'second delete confirmation');
  await submit(query('[data-confirm-accept]') + '.click()');
  await check('Confirmed empty category deletion persists', '!(' + categoryRow + ')');
  await check('Linked categories expose counts and prevent deletion', '[...document.querySelectorAll(".category-row")].every(row=>row.querySelector("form[action$=delete] button").disabled)');
  await navigate('/admin/menu');
  const toggleId = await evaluate('document.querySelector("input[name=ids]").value');
  await evaluate('document.querySelector("#dish-bulk input[name=ids]").click()');
  await check('Dish selection updates the count, button and select-all state', '!document.querySelector("[data-bulk-submit]").disabled && document.querySelector("[data-dish-selection-count]").textContent==="Đã chọn 1 món" && document.querySelector("[data-select-dishes]").indeterminate');
  await evaluate(query('#dish-bulk') + '.requestSubmit()');
  await waitFor(() => evaluate('!!document.querySelector(".modal.show")'), 'bulk change confirmation');
  await submit(query('[data-confirm-accept]') + '.click()');
  await check('Bulk menu state change persists', query('input[name="ids"][value="'+toggleId+'"]') + '.closest("tr").textContent.includes("Ngừng bán")');
  await evaluate(query('#filter-state') + '.value="inactive"');
  await submit(query('form[aria-label="Lọc thực đơn"]') + '.requestSubmit()');
  await layout('admin-menu-filter-mobile', 390, 844);
  await check('Server menu state filter returns the disabled dish', '[...document.querySelectorAll("#dish-bulk tbody tr")].length===1 && document.querySelector("input[name=ids]").value===' + JSON.stringify(toggleId));
  await evaluate(query('#dish-state-' + toggleId) + '.requestSubmit()');
  await waitFor(() => evaluate('!!document.querySelector(".modal.show")'), 'restore selling confirmation');
  await submit(query('[data-confirm-accept]') + '.click()');
  await evaluate(query('#filter-sort') + '.value="price-desc"');
  await submit(query('form[aria-label="Lọc thực đơn"]') + '.requestSubmit()');
  await check('Menu prices sort descending on the server', '(()=>{const p=[...document.querySelectorAll("#dish-bulk tbody tr")].map(row=>Number(row.children[2].textContent.replace(/[^0-9]/g,"")));return p.every((v,i)=>i===0||p[i-1]>=v)})()');
  await navigate('/admin/menu');
  for (const [name, url] of [['reports','/staff/reports'], ['branches','/admin/branches'], ['availability','/staff/menu'], ['audit','/admin/audit'], ['password','/account/password'], ['kitchen','/staff/kitchen']]) {
    await navigate(url); await layout(name + '-mobile', 390, 844);
    if (auditMode && name==='branches') {const edit=await evaluate('document.querySelector("a[href*=edit]").getAttribute("href")');await navigate(edit);await layout('branch-edit',390,844);}
    if (auditMode && name==='password') await check('Account return link belongs to page content',query('main a[href="/account"]')+' !== null');
    await check(name + ' visible fields have labels', `[...document.querySelectorAll('input:not([type="hidden"]),select,textarea')].filter(x=>x.getClientRects().length).every(x=>x.labels.length || x.getAttribute('aria-label') || x.getAttribute('aria-labelledby'))`);
  }
  await navigate('/staff/tables'); await layout('tables-tablet', 768, 1024);
  await evaluate(query('[data-select-tables]')+'.click()');
  await check('Batch QR selection survives live refresh', 'UT.refresh().then(()=>document.querySelectorAll("input[form=qr-batch]:checked").length===8)');
  await submit(query('#qr-batch')+'.requestSubmit()');
  await check('Batch print renders QR for every selected table', 'document.querySelectorAll(".qr-print-item").length===8 && [...document.querySelectorAll(".qr-print-item img")].every(image=>image.naturalWidth===400)');
  await layout('admin-qr-batch-mobile',390,844);
  await command('Emulation.setEmulatedMedia',{media:'print'});
  await viewport(794,1123);
  const batchPdf=await command('Page.printToPDF',{preferCSSPageSize:true,printBackground:true});
  fs.writeFileSync(path.join(output,'admin-qr-batch-a4.pdf'),Buffer.from(batchPdf.data,'base64'));
  assert.equal((Buffer.from(batchPdf.data,'base64').toString('latin1').match(/\/Type\s*\/Page\b/g)||[]).length,2,'Eight QR codes fit exactly two A4 pages');
  checks.push('Eight QR codes generate exactly two A4 PDF pages');
  await command('Emulation.setEmulatedMedia',{media:''});
  await navigate('/staff/tables?state=free');
  await check('Table status filter shows only free tables', 'document.querySelectorAll(".dining-table").length===8 && !document.querySelector(".occupied")');
  const rotateQrUrl=await evaluate(query('.dining-table a[href$="/qr"]')+'.getAttribute("href")');
  await navigate(rotateQrUrl);
  if (auditMode) await layout('qr-single',390,844);
  const oldQrLink=await evaluate(query('.qr-link')+'.href');
  await evaluate(query('form[action$="/rotate"]')+'.requestSubmit()');
  await waitFor(()=>evaluate('!!document.querySelector(".modal.show")'),'QR rotation warning');
  await check('QR rotation warns that old printouts expire', 'document.querySelector(".modal.show").textContent.includes("hết hiệu lực")');
  await submit(query('[data-confirm-accept]')+'.click()');
  await navigate(rotateQrUrl);
  await check('QR rotation changes the table link', query('.qr-link')+'.href!=='+JSON.stringify(oldQrLink));
  await navigate('/staff/tables');
  await submit(query('form[action$="/open"]') + '.requestSubmit()');
  const qr = await evaluate(query('.occupied a[href$="/qr"]') + '.href');
  await navigate(qr);
  const join = await evaluate('document.querySelector(".qr-link").href');
  await navigate(join);
  if (auditMode) await layout('qr-join',390,844);
  const csrfState = await evaluate(`(() => {
    const token=document.querySelector('input[name="_csrf"]')?.value;
    const cookie=decodeURIComponent(document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=') || '');
    if(!token)return {token:false,cookie:!!cookie};
    const bytes=Uint8Array.from(atob(token.replaceAll('-','+').replaceAll('_','/')),x=>x.charCodeAt(0));
    const half=bytes.length/2;const plain=String.fromCharCode(...bytes.slice(0,half).map((x,i)=>x^bytes[half+i]));
    return {token:true,cookie:!!cookie,matches:plain===cookie};
  })()`);
  assert(csrfState.token && csrfState.cookie && csrfState.matches, 'QR form token remains valid after loading CSS and images');
  checks.push('QR form CSRF cookie survives authenticated resource loads');
  await submit('document.querySelector("form").requestSubmit()');
  await check('QR join preserves working guest menu', 'location.pathname === "/guest/menu" && document.querySelectorAll(".dish-card").length > 0');
  await evaluate('Promise.all([...document.querySelectorAll(".dish-image img")].map(image=>{image.loading="eager";return image.decode()})).then(()=>true)');
  await check('All ten menu images are loaded local JPEG photographs', '[...document.querySelectorAll(".dish-image img")].length === 10 && [...document.querySelectorAll(".dish-image img")].every(image=>image.naturalWidth>0 && new URL(image.src).origin===location.origin && new URL(image.src).pathname.startsWith("/images/food/") && image.src.endsWith(".jpg")) && new Set([...document.querySelectorAll(".dish-image img")].map(image=>image.src)).size===10');
  await check('Photo sources and licenses are linked from the menu', query('a[href="/images/food/credits.html"]') + ' !== null');
  await layout('menu-desktop', 1440, 1000);
  const menuSize = (await command('Page.getLayoutMetrics')).cssContentSize;
  const menuPhoto = await command('Page.captureScreenshot', {format:'png',captureBeyondViewport:true,clip:{x:0,y:0,width:menuSize.width,height:menuSize.height,scale:1}});
  fs.writeFileSync(path.join(output,'menu-all-photos.png'),Buffer.from(menuPhoto.data,'base64'));
  await layout('menu-tablet', 768, 1024);
  await evaluate('document.querySelector("#menu-search").value="no-such-dish-987";document.querySelector("#menu-search").dispatchEvent(new Event("input",{bubbles:true}))');
  await check('Search exposes empty result state', '!document.querySelector("#no-dishes").hidden && document.querySelector("#menu-result-count").textContent.startsWith("0 ")');
  await evaluate('document.querySelector("#menu-search").value="";document.querySelector("#menu-search").dispatchEvent(new Event("input",{bubbles:true}));document.querySelector("[data-add]").click()');
  await layout('menu-mobile', 390, 844);
  await evaluate('document.querySelector("[data-cart-action=increase]").focus()');
  await command('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', text: '\r'});
  await check('Quantity changes preserve keyboard focus and cart summary', 'document.activeElement.matches("[data-cart-action=increase]") && document.querySelector("#cart-preview-count").textContent === "2"');
  await evaluate('document.querySelector(".mobile-cart-link").click()');
  await waitFor(() => evaluate('document.querySelector("#cart-panel").getBoundingClientRect().top > 100 && document.querySelector("#cart-panel").getBoundingClientRect().top < 200'), 'mobile cart jump scroll');
  await check('Mobile cart link reaches a focusable visible cart', 'document.activeElement.id === "cart-panel"');
  await layout('cart-mobile', 390, 844);

  on('Fetch.requestPaused', event => {intercepted = event;});
  await command('Fetch.enable', {patterns: [{urlPattern: '*api/guest/orders', requestStage: 'Request'}]});
  await evaluate('document.querySelector("#submit-order").click()');
  await waitFor(() => intercepted, 'paused order request');
  requestPattern = JSON.parse(intercepted.request.postData);
  await check('Order shows loading and prevents edits while pending', 'document.querySelector("#submit-order").disabled && document.querySelector("#submit-order").getAttribute("aria-busy") === "true" && document.querySelector("#order-note").disabled');
  await command('Fetch.fulfillRequest', {requestId: intercepted.requestId, responseCode: 503,
    responseHeaders: [{name: 'Content-Type', value: 'application/json;charset=UTF-8'}],
    body: Buffer.from(JSON.stringify({message: 'Máy chủ đang bận. Vui lòng thử lại.'})).toString('base64')});
  await waitFor(() => evaluate('!document.querySelector("#submit-order").disabled'), 'failed order state restored');
  await check('Server failure is accessible and keeps the cart', 'document.querySelector(".app-toast.error[role=alert]") !== null && document.querySelector("#cart-preview-count").textContent === "2"');
  intercepted = undefined;
  await evaluate('Storage.prototype.removeItem=()=>{throw new DOMException("Browser storage cleanup unavailable","SecurityError")}');
  await evaluate('document.querySelector("#submit-order").click()');
  await waitFor(() => intercepted, 'retry order request');
  assert.equal(JSON.parse(intercepted.request.postData).requestKey, requestPattern.requestKey, 'Retry keeps idempotency key');
  await command('Fetch.continueRequest', {requestId: intercepted.requestId});
  await command('Fetch.disable');
  await waitFor(() => evaluate('location.pathname === "/guest/orders" && document.readyState === "complete"'), 'successful order navigation');
  checks.push('Accepted order navigates even when localStorage cleanup is unavailable');
  await layout('guest-orders-mobile', 390, 844);
  await navigate('/staff/orders');
  if (auditMode) await layout('staff-orders',390,844);
  for (const status of ['CONFIRMED', 'COOKING', 'READY', 'SERVED']) {
    await waitFor(() => evaluate('!!document.querySelector("[data-status=' + status + ']")'), 'status button ' + status);
    await evaluate('document.querySelector("[data-status=' + status + ']").click()');
    await waitFor(() => evaluate('!UT.pendingRequests && !UT.refreshing && !document.querySelector("[data-status=' + status + ']")'), 'status transition ' + status);
  }
  checks.push('Order status actions and live refresh remain functional');
  await navigate('/staff/tables');
  const checkout = await evaluate(query('.occupied a[href*="/sessions/"]') + '.href');
  await navigate(checkout); await layout('checkout-desktop', 1440, 1000);
  await submit(query('form[action$="/checkout"]') + '.requestSubmit()');
  await check('Checkout retains all POST fields', 'location.pathname.startsWith("/staff/receipt/")');
  assert(posts.some(post => post.url.endsWith('/checkout') && ['method', 'discountPercent', '_csrf'].every(field => post.fields.includes(field))));
  if (auditMode) await layout('receipt-screen',390,844);
  await command('Emulation.setEmulatedMedia', {media: 'print'});
  await layout('receipt-print', 800, 1000);
  await command('Emulation.setEmulatedMedia', {media: ''});
  await navigate('/admin/orders'); await layout('admin-orders-desktop', 1440, 1000); await layout('admin-orders-mobile', 390, 844);
  const paidOrderDetail = await evaluate(query('#admin-orders-live tbody a') + '.getAttribute("href")');
  await navigate(paidOrderDetail); await layout('admin-order-detail-mobile', 390, 844);
  await check('Paid order detail shows actual history and locks state actions', 'document.querySelectorAll(".audit-timeline li").length===5 && !document.querySelector("[data-status]")');
  await navigate('/admin/orders?q=missing-order-' + Date.now());
  await check('Admin order search exposes a genuine empty state', 'document.querySelector("#admin-orders-live tbody").children.length===0 && !!document.querySelector("#admin-orders-live .empty-state")');
  await navigate('/staff'); await layout('admin-dashboard-desktop', 1440, 1000);
  await evaluate('document.querySelector(".admin-panels").scrollIntoView()'); await layout('admin-dashboard-charts', 1440, 1000);
  await evaluate('window.scrollTo(0,0)'); await layout('admin-dashboard-mobile', 390, 844);
  await check('Admin dashboard exposes 7 and 30 day data and branch totals', 'document.querySelectorAll(".admin-chart").length===2 && document.querySelectorAll(".admin-chart .chart-day").length===37 && document.querySelector("#admin-dashboard-live").textContent.includes("Thực thu từng chi nhánh")');
  await navigate('/staff/reports');await layout('admin-report-desktop',1440,1000);await layout('admin-report-mobile',390,844);
  await check('Reports show paid invoice, average and payment methods after checkout','document.querySelectorAll(".admin-kpis .stat-card").length===4 && document.querySelector(".admin-kpis").textContent.includes("Trung bình") && document.querySelector("main").textContent.includes("So sánh chi nhánh") && document.querySelector("main").textContent.includes("Chuyển khoản") && !!document.querySelector("a[href*=receipt]")');
  await check('CSV download has BOM, branch column and paid receipt', 'fetch(document.querySelector("a[href*=export]").href).then(async r=>({ok:r.ok,bytes:new Uint8Array(await r.arrayBuffer())})).then(r=>r.ok&&r.bytes[0]===239&&r.bytes[1]===187&&r.bytes[2]===191&&new TextDecoder().decode(r.bytes).includes("Chi nhánh")&&new TextDecoder().decode(r.bytes).includes("CASH"))');
  if (auditMode) {
    for (const [username,urls] of [['manager2',['/staff','/staff/tables','/staff/menu','/staff/reports']],['waiter2',['/staff','/staff/tables','/staff/orders']],['chef2',['/staff/kitchen']]]) {
      await submit(query('.logout-form')+'.requestSubmit()');
      await submit('document.querySelector("#username").value='+JSON.stringify(username)+';document.querySelector("#password").value="UiPreview@Test2026";document.querySelector("form").requestSubmit()');
      for (const url of urls) {await navigate(url);await layout(username+'-'+url.split('/').at(-1),390,844);}
    }
    await submit(query('.logout-form')+'.requestSubmit()');
    await submit('document.querySelector("#username").value="admin";document.querySelector("#password").value="UiPreview@Test2026";document.querySelector("form").requestSubmit()');
    await navigate('/admin/menu?page=invalid');await layout('safe-error-page',390,844);
  }
  for (const width of [320, 390, 768, 1440]) {
    await navigate('/'); await layout('home-' + width, width, 900);
    await navigate('/login'); await layout('login-' + width, width, 900);
  }
  await navigate('/admin/menu');
  const editDish = await evaluate(query('a[href*="edit="]') + '.getAttribute("href")');
  await navigate(editDish);
  const doc = await command('DOM.getDocument');
  const imageInput = await command('DOM.querySelector', {nodeId: doc.root.nodeId, selector: '[data-dish-image]'});
  await command('DOM.setFileInputFiles', {nodeId: imageInput.nodeId, files: [path.resolve('src/main/resources/static/images/food/tea.jpg')]});
  await waitFor(() => evaluate('!document.querySelector("#dish-image-preview").hidden && document.querySelector("#dish-image-preview").naturalWidth > 0'), 'local dish preview');
  await layout('cloudinary-preview-mobile', 390, 844);
  checks.push('Local image preview works without contacting Cloudinary');
  // Dedicated open table and anonymous order: later USER login must never adopt it.
  await navigate('/staff/tables');
  await submit(query('form[action$="/open"]')+'.requestSubmit()');
  await navigate(await evaluate(query('.occupied a[href$="/qr"]')+'.href'));
  const memberJoin=await evaluate(query('.qr-link')+'.href');
  await navigate(memberJoin);await submit(query('form')+'.requestSubmit()');
  const anonymousMemberOrder=await evaluate('UT.request("/api/guest/orders",{requestKey:crypto.randomUUID(),items:[{dishId:Number(document.querySelector("[data-add]").dataset.add),quantity:1}],note:"Anonymous test order"})');
  await navigate('/account');
  const staffEmail = 'staff-ui-' + Date.now() + '@example.test';
  await evaluate('document.querySelector("#email").value=' + JSON.stringify(staffEmail) + ';document.querySelector("#currentPassword").value="UiPreview@Test2026"');
  const staffSentAt = Date.now();
  await submit(query('form[action="/account/email"]') + '.requestSubmit()');
  const staffCode = lastOtp();
  await evaluate('document.querySelector("#code").value=' + JSON.stringify(staffCode));
  await submit(query('form[action="/account/email/verify"]') + '.requestSubmit()');
  await check('Staff recovery email requires OTP and returns to login', 'location.pathname === "/login" && location.search.includes("linked")');
  await submit('document.querySelector("#username").value="admin";document.querySelector("#password").value="UiPreview@Test2026";document.querySelector("form").requestSubmit()');
  await navigate('/account'); await layout('account-staff-mobile', 390, 844);
  await submit(query('form[action="/logout"]') + '.requestSubmit()');
  const customerEmail = 'customer-ui-' + Date.now() + '@example.test';
  await navigate('/register'); await layout('register-mobile', 390, 844);
  await evaluate('document.querySelector("#fullName").value="Khách thử nghiệm";document.querySelector("#email").value=' + JSON.stringify(customerEmail) + ';document.querySelector("#password").value="Customer@Test2026";document.querySelector("#confirmPassword").value="Customer@Test2026"');
  await submit('document.querySelector("form").requestSubmit()');
  const registrationCode = lastOtp();
  await layout('verify-email-mobile', 390, 844);
  await evaluate('document.querySelector("#code").value=' + JSON.stringify(registrationCode));
  await submit(query('form[action="/verify-email"]') + '.requestSubmit()');
  await check('Email verification returns to login without automatic authentication', 'location.pathname === "/login" && location.search.includes("verified")');
  await submit('document.querySelector("#username").value=' + JSON.stringify(customerEmail) + ';document.querySelector("#password").value="Customer@Test2026";document.querySelector("form").requestSubmit()');
  await check('Verified USER logs into account page', 'location.pathname === "/account"');
  await layout('account-user-mobile', 390, 844);
  await navigate('/account/profile');await layout('member-profile',390,844);
  await evaluate('document.querySelector("#fullName").value="Khách thành viên";document.querySelector("#phone").value="0901234567"');
  await submit(query('form[action="/account/profile"]')+'.requestSubmit()');
  await check('Member profile saves own name and phone',query('#fullName')+'.value==="Khách thành viên" && '+query('#phone')+'.value==="0901234567"');
  await navigate('/account/orders');await layout('member-orders-empty',390,844);
  await check('Logging in never adopts anonymous table order', '!document.querySelector(".member-order") && !document.querySelector("main").textContent.includes('+JSON.stringify(anonymousMemberOrder.code)+')');
  await navigate('/account/favorites');await layout('member-favorites-empty',390,844);
  await navigate('/account/points');await layout('member-points-empty',390,844);
  await navigate('/guest/menu');await layout('member-qr-menu',390,844);
  const favoriteDish=await evaluate('Number(document.querySelector("[data-favorite]").dataset.favorite)');
  await evaluate(query('[data-favorite]')+'.click()');
  await waitFor(()=>evaluate(query('[data-favorite]')+'.getAttribute("aria-pressed")==="true" && !'+query('[data-favorite]')+'.disabled'),'favorite saved');
  await navigate('/account/favorites');await layout('member-favorites',390,844);
  await check('Saved favorite displays loaded dish photo', 'document.querySelectorAll(".member-dish").length===1 && document.querySelector(".member-dish img").naturalWidth>0');
  await navigate('/account/favorites/'+favoriteDish);await layout('member-favorite-detail',390,844);
  await evaluate(query('a[href$="/choose"]')+'.click()');
  await waitFor(()=>evaluate('location.pathname==="/guest/menu" && document.querySelector("#cart-preview-count")?.textContent==="1"'),'quick choose adds to local cart only');
  await check('Quick choose leaves explicit submit and removes URL replay', '!new URL(location.href).searchParams.has("pick") && !document.querySelector("#submit-order").disabled');
  await evaluate(query('#submit-order')+'.click()');
  await waitFor(()=>evaluate('location.pathname==="/guest/orders" && document.readyState==="complete"'),'member order accepted');
  await layout('member-qr-orders',390,844);
  await check('Member QR view contains only their new order', 'document.querySelectorAll(".order-card").length===1 && !document.querySelector("main").textContent.includes('+JSON.stringify(anonymousMemberOrder.code)+')');
  await navigate('/account/orders');await layout('member-orders',390,844);
  const memberOrderDetail=await evaluate(query('.member-order-code')+'.getAttribute("href")');
  await navigate(memberOrderDetail);await layout('member-order-detail-unpaid',390,844);
  await navigate('/account/security');await layout('member-security',390,844);
  await navigate('/account/password');await layout('password-user',390,844);
  await navigate('/account');await layout('member-dashboard-populated',1440,1000);
  await submit(query('form[action="/logout"]')+'.requestSubmit()');
  await submit('document.querySelector("#username").value="admin";document.querySelector("#password").value="UiPreview@Test2026";document.querySelector("form").requestSubmit()');
  await navigate('/staff/orders');
  for(const status of ['CONFIRMED','COOKING','READY','SERVED']){
    for(let i=0;i<2;i++){
      const orderId=await evaluate(query('[data-status="'+status+'"]')+'.dataset.order');
      await evaluate(query('[data-status="'+status+'"]')+'.click()');
      await waitFor(()=>evaluate('!UT.pendingRequests && !UT.refreshing && !document.querySelector('+JSON.stringify('[data-status="'+status+'"][data-order="'+orderId+'"]')+')'),'member regression status '+status);
    }
  }
  await navigate('/staff/tables');await navigate(await evaluate(query('.occupied a[href*="/sessions/"]')+'.href'));
  await submit(query('form[action$="/checkout"]')+'.requestSubmit()');
  await navigate('/account');await submit(query('form[action="/logout"]')+'.requestSubmit()');
  await submit('document.querySelector("#username").value='+JSON.stringify(customerEmail)+';document.querySelector("#password").value="Customer@Test2026";document.querySelector("form").requestSubmit()');
  await navigate(memberOrderDetail+'/receipt');await layout('member-order-receipt',390,844);
  await check('Member receipt is a private order extract after shared checkout', 'document.querySelector("main").textContent.includes("Phiên bàn đã thanh toán") && !document.querySelector("main").textContent.includes('+JSON.stringify(anonymousMemberOrder.code)+') && !document.querySelector("main").textContent.includes("Tổng thực thu")');
  await navigate('/account/points');await layout('member-points-after-payment',390,844);
  await check('No points awarded before verified payer rule', 'document.querySelector(".member-points-hero strong").textContent==="0" && !document.querySelector(".member-point-row")');
  await navigate('/account/favorites');await submit(query('.member-dish form')+'.requestSubmit()');
  await check('Remove favorite restores empty state', '!document.querySelector(".member-dish") && !!document.querySelector(".member-empty")');
  await navigate('/account');

  await submit(query('form[action="/logout"]') + '.requestSubmit()');
  // Use staff linked earlier so the registration cooldown does not add a blocking delay.
  await navigate('/forgot-password'); await layout('forgot-password-mobile', 390, 844);
  await waitFor(() => Date.now() - staffSentAt >= 61000, 'account email resend cooldown', 65000);
  const beforeResetMessages = mailMessages.length;
  await evaluate('document.querySelector("#request-email").value=' + JSON.stringify(staffEmail));
  await submit(query('form[action="/forgot-password"]') + '.requestSubmit()');
  const resetCode = lastOtp();
  assert.equal(mailMessages.length, beforeResetMessages + 1, 'Recovery OTP delivered to local SMTP double');
  await evaluate('document.querySelector("#code").value=' + JSON.stringify(resetCode));
  await submit(query('form[action="/forgot-password/verify"]') + '.requestSubmit()');
  await check('Recovery OTP grants access to reset form', 'location.pathname === "/reset-password"');
  await layout('reset-password-mobile', 390, 844);
  await evaluate('document.querySelector("#password").value="Reset@Test2026";document.querySelector("#confirmPassword").value="Reset@Test2026"');
  await submit('document.querySelector("form").requestSubmit()');
  await check('Reset returns to login and clears reset capability', 'location.pathname === "/login" && location.search.includes("changed")');
  await submit('document.querySelector("#username").value=' + JSON.stringify(staffEmail) + ';document.querySelector("#password").value="Reset@Test2026";document.querySelector("form").requestSubmit()');
  await check('New password logs in after browser reset flow', 'location.pathname === "/staff"');
  assert.deepEqual(errors, [], 'No JavaScript runtime errors');
  fs.writeFileSync(path.join(output, 'network-results.json'), JSON.stringify({responses},null,2));
  assert.deepEqual(responses.filter(r=>r.status>=500 && !(r.status===503 && r.path==='/api/guest/orders')),[],'No unexpected HTTP 5xx responses');
  assert.deepEqual(responsiveFailures, [], 'Audit pages must fit all six viewport widths');
  fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({status:'passed',checks, errors, auditedPages:[...auditedPages.values()], database: 'private in-memory H2', screenshots: fs.readdirSync(output).filter(file => file.endsWith('.png') && file !== 'failure.png')}, null, 2));
  fs.writeFileSync(path.join(output, 'failure.json'), JSON.stringify({status:'no failure in latest run'}));
  console.log('UI CHECK SUCCESS: ' + checks.length + ' checks; screenshots in ' + output + '.');
} catch (error) {
  const page = socket?.readyState === WebSocket.OPEN && session
    ? await evaluate('({path:location.pathname,title:document.title,message:document.querySelector("main")?.innerText.slice(0,1200),scrollWidth:document.documentElement.scrollWidth,chartParents:[...document.querySelectorAll(".admin-chart")].map(el=>({parentClass:el.parentElement.className,parentWidth:el.parentElement.getBoundingClientRect().width,parentOverflow:getComputedStyle(el.parentElement).overflowX,grandWidth:el.parentElement.parentElement.getBoundingClientRect().width})),overflow:[...document.querySelectorAll("body *")].filter(el=>el.getBoundingClientRect().right>innerWidth+1).filter(el=>{let p=el.parentElement;while(p){if(["auto","scroll","hidden","clip"].includes(getComputedStyle(p).overflowX))return false;p=p.parentElement;}return true;}).slice(0,20).map(el=>({tag:el.tagName,classes:el.className,width:el.getBoundingClientRect().width,right:el.getBoundingClientRect().right,overflow:getComputedStyle(el).overflowX}))})').catch(() => null) : null;
  fs.writeFileSync(path.join(output, 'failure.json'), JSON.stringify({message: error.message, page, checks, errors, posts, responseCookies:responseCookies.slice(-20), responses:responses.slice(-12)}, null, 2));
  if (session && socket?.readyState === WebSocket.OPEN) {
    const screenshot = await command('Page.captureScreenshot', {format: 'png'}).catch(() => null);
    if (screenshot) fs.writeFileSync(path.join(output, 'failure.png'), Buffer.from(screenshot.data, 'base64'));
  }
  console.error(error); process.exitCode = 1;
} finally {
  if (socket?.readyState === WebSocket.OPEN) {await command('Browser.close', {}, null).catch(() => {}); socket.close();}
  for (const request of pending.values()) clearTimeout(request.timer);
  browser?.kill(); server.kill(); fs.closeSync(serverLog);
  for (const client of smtpClients) client.destroy(); smtp.close();
}

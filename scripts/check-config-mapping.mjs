/** Test Compose bindings with invented values only. Never reads the real .env or starts containers. */
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';

const fixture = {
  DB_USER:'mapping_test',DB_PASSWORD:'mapping-test-only-password',JWT_SECRET:'mapping-test-jwt-secret-of-at-least-32-bytes',
  OTP_SECRET:'mapping-test-otp-independent-secret-of-32-bytes',MAIL_ENABLED:'true',MAIL_FROM:'sender@example.test',
  SMTP_HOST:'smtp.example.test',SMTP_PORT:'587',SMTP_USERNAME:'sender@example.test',SMTP_PASSWORD:'mapping-test-only-app-password',
  CLOUDINARY_CLOUD_NAME:'mapping-test-cloud',CLOUDINARY_API_KEY:'mapping-test-key',CLOUDINARY_API_SECRET:'mapping-test-secret',
  APP_BASE_URL:'http://localhost:8080',COOKIE_SECURE:'false',SEED_DEMO:'false',DEMO_PASSWORD:'Mapping@Test2026',ADMIN_PASSWORD:'Mapping@Test2026'
};
fs.mkdirSync('target',{recursive:true});
const fixturePath = path.resolve('target/config-mapping-test.env');
const checks=[];
function config(values) {
  fs.writeFileSync(fixturePath,Object.entries(values).map(([key,value])=>key+'='+value).join('\n')+'\n');
  const env={...process.env,...values};delete env.OTP_SECRET;
  if(values.OTP_SECRET) env.OTP_SECRET=values.OTP_SECRET;
  const result=spawnSync('docker',['compose','--env-file',fixturePath,'-f','compose.yml','config','--format','json'],
    {encoding:'utf8',windowsHide:true,env});
  assert.equal(result.status,0,'Compose configuration must parse (output suppressed)');
  return JSON.parse(result.stdout).services.app.environment;
}
const configured=config(fixture);
for(const key of ['JWT_SECRET','OTP_SECRET','MAIL_ENABLED','MAIL_FROM','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD',
  'CLOUDINARY_CLOUD_NAME','CLOUDINARY_API_KEY','CLOUDINARY_API_SECRET']) {
  assert.equal(String(configured[key]),fixture[key],key+' must reach the app environment');checks.push(key+' mapping PASS');
}
const {OTP_SECRET,...fallbackFixture}=fixture;
assert.equal(config(fallbackFixture).OTP_SECRET,fixture.JWT_SECRET,'Legacy OTP_SECRET fallback must match JWT_SECRET');
checks.push('Legacy OTP_SECRET fallback PASS');
const application=fs.readFileSync('src/main/resources/application.yml','utf8');
for(const key of ['MAIL_ENABLED','MAIL_FROM','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','CLOUDINARY_CLOUD_NAME','CLOUDINARY_API_KEY','CLOUDINARY_API_SECRET']) {
  assert(application.includes('${'+key+':'),key+' must bind to Spring properties');checks.push(key+' Spring binding PASS');
}
assert(application.includes('otp-secret: ${OTP_SECRET:${JWT_SECRET}}'),'OTP secret Spring fallback');checks.push('OTP_SECRET Spring binding PASS');
const report={status:'PASS',checks,realEnvRead:false,containersStarted:false};
fs.writeFileSync('target/config-mapping-results.json',JSON.stringify(report,null,2));
console.log('PASS: '+checks.length+' configuration mapping checks with invented fixture values.');

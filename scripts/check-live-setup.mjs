/** Read-only runtime audit. Never reads .env, prints credentials, sends mail or uploads images.
 * Run: node scripts/check-live-setup.mjs [app-container] [db-container]
 */
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {crc32} from 'node:zlib';

const app = process.argv[2] || 'utetaste-app-1';
const db = process.argv[3] || 'utetaste-db-1';
function docker(args, input) {
  const result = spawnSync('docker', args, {input, encoding:'utf8', windowsHide:true, maxBuffer:2*1024*1024});
  if (result.error || result.status !== 0) throw new Error('Docker audit failed; credentials are excluded. Check daemon access and container names.');
  return result.stdout.trim();
}
function identity(name) {
  const labels = JSON.parse(docker(['inspect','--format','{{json .Config.Labels}}',name]));
  return {container:name,project:labels['com.docker.compose.project'],service:labels['com.docker.compose.service'],
    workingDirectory:labels['com.docker.compose.project.working_dir'],image:docker(['inspect','--format','{{.Image}}',name])};
}
const identities = [identity(app),identity(db)];
if (identities[0].project !== identities[1].project || identities[0].service !== 'app' || identities[1].service !== 'db')
  throw new Error('Containers are not an app/db pair in one Compose project.');
if (!identities.every(i => i.workingDirectory && path.resolve(i.workingDirectory).toLowerCase() === process.cwd().toLowerCase()))
  throw new Error('Container Compose workspace does not match the current directory.');

const probe = [
  'for key in MAIL_ENABLED MAIL_FROM SMTP_HOST SMTP_PORT SMTP_USERNAME SMTP_PASSWORD OTP_SECRET CLOUDINARY_CLOUD_NAME CLOUDINARY_API_KEY CLOUDINARY_API_SECRET; do if printenv "$key" >/dev/null 2>&1; then if [ -n "$(printenv "$key")" ]; then printf "%s=SET\\n" "$key"; else printf "%s=EMPTY\\n" "$key"; fi; else printf "%s=ABSENT\\n" "$key"; fi; done',
  'if [ "$MAIL_ENABLED" = true ]; then echo MAIL_FEATURE=ENABLED; else echo MAIL_FEATURE=DISABLED; fi',
  'if [ -n "$OTP_SECRET" ] && [ ${#OTP_SECRET} -ge 32 ]; then case "$OTP_SECRET" in CHANGE_ME*) echo OTP_SECRET_CHECK=PLACEHOLDER;; *) echo OTP_SECRET_CHECK=LENGTH_OK_NOT_AUTHENTICATED;; esac; else echo OTP_SECRET_CHECK=MISSING_OR_TOO_SHORT; fi',
  'if [ -n "$OTP_SECRET" ] && [ "$OTP_SECRET" = "$JWT_SECRET" ]; then echo OTP_SECRET_SEPARATION=SHARED_WITH_JWT; elif [ -n "$OTP_SECRET" ]; then echo OTP_SECRET_SEPARATION=DIFFERENT_FROM_JWT; else echo OTP_SECRET_SEPARATION=NOT_SET; fi'
].join('\n')+'\n';
const environment = Object.fromEntries(docker(['exec','-i',app,'sh'],probe).split(/\r?\n/).map(line=>line.split('=')));
// Metadata and aggregate counts only; no account records or password hashes.
const sql = `
SELECT json_build_object(
 'readOnly', current_setting('default_transaction_read_only'),
 'databaseTimeZone', current_setting('TimeZone'),
 'migrations', (SELECT json_agg(json_build_object('version',version,'description',description,'checksum',checksum,'installedOn',installed_on,'success',success) ORDER BY installed_rank) FROM flyway_schema_history),
 'columns', (SELECT json_agg(json_build_object('table',table_name,'column',column_name,'type',data_type) ORDER BY table_name,ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND ((table_name='app_users' AND column_name IN ('email','email_verified','mail_window','last_mail_sent_at','mail_sends')) OR (table_name='dishes' AND column_name IN ('image_url','image_public_id')) OR table_name='email_otps')),
 'constraints', (SELECT json_agg(json_build_object('name',conname,'definition',pg_get_constraintdef(oid))) FROM pg_constraint WHERE conrelid IN ('app_users'::regclass,'dishes'::regclass,to_regclass('public.email_otps'))),
 'indexes', (SELECT json_agg(json_build_object('table',tablename,'name',indexname,'definition',indexdef)) FROM pg_indexes WHERE schemaname='public' AND tablename IN ('app_users','email_otps')),
 'counts', json_build_object('users',(SELECT count(*) FROM app_users),'branches',(SELECT count(*) FROM branches),'tables',(SELECT count(*) FROM restaurant_tables),'dishes',(SELECT count(*) FROM dishes),'sessions',(SELECT count(*) FROM dining_sessions),'orders',(SELECT count(*) FROM food_orders),'invoices',(SELECT count(*) FROM invoices)),
 'roles', (SELECT json_agg(json_build_object('role',role,'active',active,'count',n)) FROM (SELECT role,active,count(*) n FROM app_users GROUP BY role,active ORDER BY role,active) r)
);
`;
const database = JSON.parse(docker(['exec','-i',db,'sh','-c',
  'export PGOPTIONS="-c default_transaction_read_only=on"; exec psql -X -q -t -A -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1'],sql));
for (const migration of database.migrations || []) {
  const file = fs.readdirSync('src/main/resources/db/migration').find(name=>name.startsWith('V'+migration.version+'__'));
  if (file) {
    const text = fs.readFileSync(path.join('src/main/resources/db/migration',file),'utf8').replace(/^\uFEFF/,'').split(/\r\n|\r|\n/).join('');
    migration.matchesSourceChecksum = (crc32(Buffer.from(text,'utf8')) | 0) === migration.checksum;
  }
}
const http = [];
for (const route of ['/','/login','/register','/verify-email','/forgot-password']) {
  const response = await fetch('http://localhost:8080'+route,{redirect:'manual'});
  await response.arrayBuffer();http.push({path:route,status:response.status});
}
const report = {checkedAt:new Date().toISOString(),identities,environment,database,http,
  limitations:['No .env access; presence checks do not establish credential validity.','No writes, SMTP/API calls or migration applied.','Counts have no pre-migration baseline; preservation across earlier updates is not established.']};
fs.mkdirSync('target',{recursive:true});
fs.writeFileSync('target/live-setup-audit.json',JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));

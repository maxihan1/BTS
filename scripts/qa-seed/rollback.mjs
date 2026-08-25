// seed.mjs 가 남긴 매니페스트를 읽어 적재한 QA 데이터를 회수한다 (대시보드·스프린트 삭제 · 이슈 소프트삭제 · 프로젝트 아카이브)

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Api, parseArgs, requireEnv, loadLocalEnv, mapPool, splitOk, loadManifest, saveManifest } from './lib.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
loadLocalEnv(HERE);
const { flags, opts } = parseArgs(process.argv);
const CONCURRENCY = Number(opts.concurrency ?? 4);
const BASE_URL = opts['base-url'] ?? process.env.BTS_BASE_URL ?? 'https://bts.maxihan.com';
const MANIFEST = opts.manifest ?? path.join(HERE, `manifest.${new URL(BASE_URL).hostname}.json`);

const log = (...a) => console.log(...a);

async function main() {
  const manifest = await loadManifest(MANIFEST);
  if (!manifest.projects.length && !manifest.issues.length) {
    log(`매니페스트가 비었다: ${MANIFEST}`);
    return;
  }
  if (manifest.baseUrl && manifest.baseUrl !== BASE_URL) {
    throw new Error(`매니페스트는 ${manifest.baseUrl} 것인데 대상은 ${BASE_URL} 다. 엉뚱한 서버를 지울 뻔했다.`);
  }

  log(`대상: ${BASE_URL}`);
  log(`회수 예정 — 프로젝트 ${manifest.projects.length} · 이슈 ${manifest.issues.length} · 스프린트 ${manifest.sprints.length} · 대시보드 ${manifest.dashboards.length}`);
  log('보드는 삭제 API 가 없다 — 프로젝트 아카이브로 함께 가려진다.');

  if (!flags.has('yes')) {
    log('\n실제로 지우려면 --yes 를 붙여라.');
    process.exitCode = 2;
    return;
  }

  const pat = opts.pat ?? process.env.BTS_QA_PAT ?? null;
  const api = new Api({
    baseUrl: BASE_URL,
    provider: opts.provider ?? process.env.BTS_PROVIDER ?? 'local',
    username: pat ? null : (opts.username ?? requireEnv('BTS_QA_USERNAME')),
    password: pat ? null : (opts.password ?? requireEnv('BTS_QA_PASSWORD')),
    pat,
    totp: opts.totp ?? process.env.BTS_QA_TOTP ?? null,
  });
  await api.login();

  // 1. 대시보드 (하드 삭제)
  const dash = await mapPool(manifest.dashboards, CONCURRENCY,
    (d) => api.del(`/api/v1/dashboards/${d.id}`, { expect: [204, 404] }));
  log(`대시보드 ${splitOk(dash).ok.length}/${manifest.dashboards.length} 삭제`);

  // 2. 스프린트 — 이슈보다 먼저 지운다. 이슈가 없어진 뒤에는 할당 정리가 더 지저분해진다.
  //    id 가 비어 있는 항목은 건너뛴다 — `/sprints/undefined` 로 400 을 맞을 뿐이다.
  const sprints = manifest.sprints.filter((s) => s.id);
  const skipped = manifest.sprints.length - sprints.length;
  const sp = await mapPool(sprints, CONCURRENCY,
    (s) => api.del(`/api/v1/sprints/${s.id}`, { expect: [200, 204, 404, 409] }));
  log(`스프린트 ${splitOk(sp).ok.length}/${sprints.length} 삭제 시도${skipped ? ` (id 없음 ${skipped}건 건너뜀)` : ''}`);

  // 3. 이슈 (소프트 삭제)
  let last = 0;
  const iss = await mapPool(manifest.issues, CONCURRENCY,
    (i) => api.del(`/api/v1/issues/${i.key}`, { expect: [204, 404] }),
    (d, total) => { if (d - last >= 50 || d === total) { last = d; log(`  · 이슈 ${d}/${total}`); } });
  const issOk = splitOk(iss);
  log(`이슈 ${issOk.ok.length}/${manifest.issues.length} 삭제${issOk.failed.length ? ` (실패 ${issOk.failed.length} — 첫 사유: ${issOk.failed[0].message.slice(0, 160)})` : ''}`);

  // 4. 프로젝트 아카이브 — 하드 삭제 API 가 없다. 아카이브가 사실상의 회수 경계다.
  for (const p of manifest.projects) {
    try {
      await api.post(`/api/v1/projects/${p.key}/archive`, undefined, { expect: [200, 404, 409] });
      log(`프로젝트 ${p.key} 아카이브`);
    } catch (e) {
      log(`프로젝트 ${p.key} 아카이브 실패: ${e.message.slice(0, 200)}`);
    }
  }

  await saveManifest(`${MANIFEST}.rolledback`, { ...manifest, rolledBackAt: new Date().toISOString() });
  log(`\n완료. 요청 통계 ${JSON.stringify(api.stats)}`);
}

main().catch((e) => { console.error(`\n실패: ${e.message}`); process.exit(1); });

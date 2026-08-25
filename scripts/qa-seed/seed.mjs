// 프로덕션 QA 용 데이터를 REST API 로 적재한다 — 프로젝트 3개 · 이슈 450건 · 스프린트 · 보드 · 대시보드

import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  Api, SEED_LABEL, parseArgs, requireEnv, loadLocalEnv, mapPool, splitOk, rng, pick, pickMany,
  loadManifest, saveManifest,
} from './lib.mjs';
import {
  PROJECTS, DESCRIPTION_TEMPLATES, LABEL_POOL, COMMENT_POOL, SPRINT_PLAN, STATUS_MIX,
} from './content.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '../..');

loadLocalEnv(HERE);
const { flags, opts } = parseArgs(process.argv);
const DRY_RUN = flags.has('dry-run');
const CONCURRENCY = Number(opts.concurrency ?? 4);
const SEED = Number(opts.seed ?? 20260825);
const ISSUES_PER_PROJECT = Number(opts.issues ?? 150);

const BASE_URL = opts['base-url'] ?? process.env.BTS_BASE_URL ?? 'https://bts.maxihan.com';
const MANIFEST = opts.manifest ?? path.join(HERE, `manifest.${new URL(BASE_URL).hostname}.json`);

/** 첨부로 올릴 실제 스크린샷. 저장소에 있는 진짜 앱 캡처를 쓴다 — 합성 이미지보다 QA 가치가 높다. */
const SCREENSHOT_CANDIDATES = [
  'apps/web/e2e/visual/__screenshots__/issue-list-light.png',
  'apps/web/e2e/visual/__screenshots__/issue-list-dark.png',
  'apps/web/e2e/visual/__screenshots__/issue-detail-light.png',
  'apps/web/e2e/visual/__screenshots__/issue-detail-dark.png',
  '.local-run/after-login.png',
  '.local-run/real-02-after-login.png',
  '.local-run/real-login-result.png',
  '.local-run/step2-form.png',
  '.local-run/login-check.png',
];

/** 같은 제목이 여러 번 쓰일 때 붙이는 변형 — 실제 티켓처럼 서로 구분되게 만든다. */
const VARIANTS = [
  '', ' — Chrome 128', ' — Safari 17', ' — Firefox 129', ' — 모바일 390x844',
  ' — 다크 모드', ' — 대용량 데이터', ' — 느린 네트워크', ' — 재현 2차',
];

const log = (...a) => console.log(...a);
const unwrap = (r) => (Array.isArray(r) ? r : r?.data ?? r);

function buildIssuePlan(project, r) {
  const titles = project.themes.flatMap(([theme, list]) => list.map((t) => ({ theme, title: t })));
  const states = STATUS_MIX.flatMap((m) => Array.from({ length: m.count }, () => m));
  // 상태 분포를 이슈 수에 맞춘다 — 요청 개수가 기본값과 다를 때 비율을 유지한다.
  const scaled = [];
  for (let i = 0; i < ISSUES_PER_PROJECT; i += 1) scaled.push(states[Math.floor((i * states.length) / ISSUES_PER_PROJECT)]);

  return scaled.map((mix, i) => {
    const base = titles[i % titles.length];
    const variant = VARIANTS[Math.floor(i / titles.length) % VARIANTS.length];
    const summary = `[${base.theme}] ${base.title}${variant}`.slice(0, 200);
    return {
      index: i,
      summary,
      description: pick(r, DESCRIPTION_TEMPLATES)(base.title),
      priority: 1 + Math.floor(r() * 5),
      labels: [SEED_LABEL, ...pickMany(r, LABEL_POOL.filter((l) => l !== SEED_LABEL), Math.floor(r() * 3))],
      mix,
      wantsComments: r() < 0.4 ? 1 + Math.floor(r() * 3) : 0,
      wantsAttachment: r() < 0.15,
    };
  });
}

async function preflight(api) {
  await api.login();
  const [users, issueTypes, resolutions] = await Promise.all([
    api.get('/api/v1/users').then(unwrap),
    api.get('/api/v1/issue-types').then(unwrap),
    api.get('/api/v1/resolutions').then(unwrap),
  ]);
  if (!issueTypes?.length) throw new Error('이슈 타입이 하나도 없다 — 부팅 시드가 돌지 않은 서버다.');
  if (!resolutions?.length) throw new Error('resolution 이 하나도 없다 — DONE 전환을 만들 수 없다.');

  const screenshots = [];
  for (const rel of SCREENSHOT_CANDIDATES) {
    const abs = path.join(REPO, rel);
    if (existsSync(abs)) screenshots.push({ name: path.basename(rel), buf: await readFile(abs) });
  }
  return { users: users ?? [], issueTypes, resolutions, screenshots };
}

async function ensureProject(api, project, manifest) {
  const known = manifest.projects.find((p) => p.key === project.key);
  if (known) return known;
  try {
    await api.post('/api/v1/projects', { key: project.key, name: project.name });
  } catch (e) {
    // 이미 있으면 그대로 쓴다 — 재실행 가능해야 중단 후 이어받을 수 있다.
    if (e.status !== 409 && !/exist|중복|duplicate/i.test(e.bodyText ?? '')) throw e;
    log(`  · ${project.key} 는 이미 있다 — 재사용한다`);
  }
  // 워크플로우 스킴을 명시 배정한다. GET 의 자동 배정에 기대면 nil actor 경로로 500 이 날 수 있다.
  await api.put(`/api/v1/projects/${project.key}/workflow-scheme`, { schemeKey: 'software-scheme' })
    .catch((e) => log(`  · ${project.key} 워크플로우 스킴 배정 건너뜀: ${e.message.slice(0, 120)}`));
  const record = { key: project.key, name: project.name };
  manifest.projects.push(record);
  return record;
}

async function createIssues(api, project, plan, ctx, manifest) {
  const done = new Set(manifest.issues.filter((i) => i.projectKey === project.key).map((i) => i.index));
  const todo = plan.filter((p) => !done.has(p.index));
  if (!todo.length) return;

  let last = 0;
  const results = await mapPool(todo, CONCURRENCY, async (p, n) => {
    const assignee = ctx.users.length && n % 5 !== 0 ? ctx.users[n % ctx.users.length].id : null;
    const type = ctx.issueTypes[n % ctx.issueTypes.length];
    const body = {
      projectKey: project.key,
      summary: p.summary,
      description: p.description,
      typeId: type.id,
      priority: p.priority,
      labels: p.labels,
    };
    if (assignee) body.assigneeId = assignee;
    const created = unwrap(await api.post('/api/v1/issues', body));
    const rec = { index: p.index, projectKey: project.key, key: created.key, version: created.version, plan: p };
    manifest.issues.push({ index: p.index, projectKey: project.key, key: created.key });
    return rec;
  }, (d, total) => {
    if (d - last >= 25 || d === total) { last = d; log(`  · 이슈 ${d}/${total}`); }
  });

  const { ok, failed } = splitOk(results);
  if (failed.length) log(`  ! 이슈 생성 실패 ${failed.length}건 — 첫 사유: ${failed[0].message.slice(0, 200)}`);
  await saveManifest(MANIFEST, manifest);
  return ok;
}

async function applyTransitions(api, issues, ctx) {
  const needing = issues.filter((i) => i.plan.mix.path.length);
  let last = 0;
  const results = await mapPool(needing, CONCURRENCY, async (issue) => {
    let version = issue.version;
    for (const toStatusKey of issue.plan.mix.path) {
      const body = { toStatusKey, expectedVersion: version };
      // DONE 카테고리(done·closed) 진입 전환은 resolution 이 필수다 (FR-IS-07).
      if (toStatusKey === 'done' || toStatusKey === 'closed') {
        body.resolutionId = ctx.resolutions[Math.abs(hash(issue.key)) % ctx.resolutions.length].id;
      }
      const updated = unwrap(await api.post(`/api/v1/issues/${issue.key}/transition`, body));
      version = updated.version;
    }
    issue.version = version;
    return issue.key;
  }, (d, total) => {
    if (d - last >= 25 || d === total) { last = d; log(`  · 전환 ${d}/${total}`); }
  });
  const { failed } = splitOk(results);
  if (failed.length) log(`  ! 전환 실패 ${failed.length}건 — 첫 사유: ${failed[0].message.slice(0, 200)}`);
}

function hash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}

async function addComments(api, issues, r) {
  const jobs = [];
  for (const issue of issues) {
    for (let n = 0; n < issue.plan.wantsComments; n += 1) {
      jobs.push({ key: issue.key, body: pick(r, COMMENT_POOL) });
    }
  }
  if (!jobs.length) return;
  const results = await mapPool(jobs, CONCURRENCY, (j) => api.post(`/api/v1/issues/${j.key}/comments`, { body: j.body }));
  const { ok, failed } = splitOk(results);
  log(`  · 댓글 ${ok.length}/${jobs.length}${failed.length ? ` (실패 ${failed.length})` : ''}`);
}

async function addAttachments(api, issues, ctx) {
  if (!ctx.screenshots.length) { log('  · 첨부할 스크린샷 파일이 없어 건너뛴다'); return; }
  const targets = issues.filter((i) => i.plan.wantsAttachment);
  if (!targets.length) return;
  const results = await mapPool(targets, 2, async (issue, n) => {
    const shot = ctx.screenshots[n % ctx.screenshots.length];
    const fd = new FormData();
    fd.append('file', new File([shot.buf], shot.name, { type: 'image/png' }));
    return api.raw('POST', `/api/v1/issues/${issue.key}/attachments`, { formData: fd });
  });
  const { ok, failed } = splitOk(results);
  log(`  · 첨부 ${ok.length}/${targets.length}${failed.length ? ` (실패 ${failed.length} — 첫 사유: ${failed[0].message.slice(0, 160)})` : ''}`);
}

function isoDate(offsetDays) {
  const d = new Date(Date.UTC(2026, 7, 25));
  d.setUTCDate(d.getUTCDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

/**
 * 스프린트 응답의 식별자는 `id` 가 아니라 **`sprintId`** 다 (`SprintResponse:29`).
 * 보드는 `boardId`. 두 BC 가 서로 다른 명명을 쓰므로 한쪽을 보고 다른 쪽을 추측하면 안 된다.
 */
const sprintIdOf = (s) => s?.sprintId ?? s?.id;

async function createSprints(api, project, issues, manifest) {
  // 이미 만들어진 스프린트를 먼저 조회한다 — 중단 후 재실행이 사본을 만들지 않게 하고,
  // 응답 파싱이 어긋나 매니페스트에 id 가 비어 있던 것도 여기서 치유된다.
  const existing = unwrap(await api.get(`/api/v1/sprints?projectKey=${project.key}`).catch(() => ({ data: [] }))) ?? [];
  const byName = new Map(existing.map((s) => [s.name, s]));

  const created = [];
  for (const s of SPRINT_PLAN) {
    const name = `${project.key} ${s.suffix}`;
    let sprint = byName.get(name);
    if (sprint) {
      log(`  · ${name} 는 이미 있다 — 재사용한다`);
    } else {
      sprint = unwrap(await api.post('/api/v1/sprints', {
        projectKey: project.key,
        name,
        goal: s.goal,
        startDate: isoDate(s.offsetDays),
        endDate: isoDate(s.offsetDays + s.lengthDays),
      }));
    }
    const id = sprintIdOf(sprint);
    if (!id) { log(`  ! ${name} 의 id 를 못 읽었다: ${JSON.stringify(sprint).slice(0, 200)}`); continue; }
    created.push({ ...sprint, id, status: sprint.status, plan: s });
    if (!manifest.sprints.some((x) => x.id === id)) {
      manifest.sprints.push({ id, projectKey: project.key, name });
    }
  }

  // 완료 스프린트에는 done/closed 이슈를, 진행 스프린트에는 진행 중 이슈를 넣는다.
  const byState = (keys) => issues.filter((i) => keys.includes(i.plan.mix.state));
  const buckets = [
    { sprint: created[0], items: byState(['done', 'closed', 'cancelled']).slice(0, 30) },
    { sprint: created[1], items: byState(['in_progress', 'in_review']).slice(0, 30) },
    { sprint: created[2], items: byState(['open']).slice(0, 20) },
  ];
  for (const b of buckets) {
    const res = await mapPool(b.items, CONCURRENCY, (i) =>
      api.post(`/api/v1/sprints/${b.sprint.id}/issues`, { issueKey: i.key }, { expect: [200, 201, 204] }));
    const { ok, failed } = splitOk(res);
    log(`  · ${b.sprint.name} 이슈 ${ok.length}/${b.items.length}${failed.length ? ` (실패 ${failed.length})` : ''}`);
  }

  // 상태 전이는 할당 뒤에 한다 — COMPLETED 스프린트에는 이슈를 넣을 수 없다.
  // 409 를 허용하는 이유는 재실행 때 이미 그 상태이기 때문이다.
  for (const s of created) {
    if (s.plan.state === 'active' || s.plan.state === 'completed') {
      await api.post(`/api/v1/sprints/${s.id}/start`, undefined, { expect: [200, 409] });
    }
    if (s.plan.state === 'completed') {
      await api.post(`/api/v1/sprints/${s.id}/complete`, undefined, { expect: [200, 409] });
    }
  }
  log(`  · 스프린트 ${created.length}/${SPRINT_PLAN.length} 준비`);
  return created;
}

async function createBoard(api, project, manifest) {
  if (manifest.boards.some((b) => b.projectKey === project.key)) { log('  · 보드는 이미 있다'); return null; }
  const board = unwrap(await api.post('/api/v1/boards', { projectKey: project.key, name: project.boardName }));
  const id = board?.boardId ?? board?.id;
  manifest.boards.push({ id, projectKey: project.key, name: project.boardName });
  log(`  · 보드 "${project.boardName}" 생성`);
  return board;
}

function dashboardLayout(projectKeys) {
  const g = (i, x, y, w, h, gadgetType, config) => ({ i, x, y, w, h, gadgetType, config });
  return JSON.stringify([
    g('g1', 0, 0, 12, 2, 'text_widget', {
      markdown: `# QA 대시보드\n\n프로덕션 QA 용 시드 데이터다. 프로젝트 ${projectKeys.join(' · ')} 를 다룬다.\n\n라벨 \`${SEED_LABEL}\` 로 전량 식별·회수할 수 있다.`,
    }),
    g('g2', 0, 2, 4, 3, 'issue_count', { aql: `project = ${projectKeys[0]}` }),
    g('g3', 4, 2, 4, 3, 'issue_count', { aql: `project = ${projectKeys[1]}` }),
    g('g4', 8, 2, 4, 3, 'issue_count', { aql: `project = ${projectKeys[2]}` }),
    g('g5', 0, 5, 6, 5, 'assigned_to_me', { maxItems: 10 }),
    g('g6', 6, 5, 6, 5, 'recently_created', { maxItems: 10 }),
    g('g7', 0, 10, 6, 5, 'filter_result', { aql: 'status = open', maxItems: 15 }),
    g('g8', 6, 10, 6, 4, 'link_list', {
      links: [
        { label: '이슈 목록', url: 'https://bts.maxihan.com/issues' },
        { label: '보드', url: 'https://bts.maxihan.com/boards' },
        { label: '백로그', url: 'https://bts.maxihan.com/backlog' },
      ],
    }),
  ]);
}

async function createDashboards(api, projectKeys, manifest) {
  const specs = [
    { name: 'QA — 전체 현황', description: '3개 QA 프로젝트 통합 뷰', visibility: 'PRIVATE', layout: dashboardLayout(projectKeys) },
    {
      name: 'QA — 진행 중 작업',
      description: '열린 이슈와 내 담당 작업',
      visibility: 'PRIVATE',
      layout: JSON.stringify([
        { i: 'a1', x: 0, y: 0, w: 6, h: 5, gadgetType: 'assigned_to_me', config: { maxItems: 20 } },
        { i: 'a2', x: 6, y: 0, w: 6, h: 5, gadgetType: 'filter_result', config: { aql: 'status = in_progress', maxItems: 20 } },
        { i: 'a3', x: 0, y: 5, w: 12, h: 3, gadgetType: 'recently_created', config: { maxItems: 15 } },
      ]),
    },
  ];
  for (const spec of specs) {
    try {
      const d = unwrap(await api.post('/api/v1/dashboards', spec));
      manifest.dashboards.push({ id: d.id, name: spec.name });
      log(`  · 대시보드 "${spec.name}" 생성`);
    } catch (e) {
      log(`  ! 대시보드 "${spec.name}" 실패: ${e.message.slice(0, 240)}`);
    }
  }
}

async function main() {
  const pat = opts.pat ?? process.env.BTS_QA_PAT ?? null;
  const api = new Api({
    baseUrl: BASE_URL,
    provider: opts.provider ?? process.env.BTS_PROVIDER ?? 'local',
    // PAT 를 쓰면 비밀번호가 아예 필요 없다 — 없는 환경변수를 요구해 막지 않는다.
    username: pat ? null : (opts.username ?? requireEnv('BTS_QA_USERNAME')),
    password: pat ? null : (opts.password ?? requireEnv('BTS_QA_PASSWORD')),
    pat,
    totp: opts.totp ?? process.env.BTS_QA_TOTP ?? null,
  });

  log(`대상: ${BASE_URL}`);
  log(`매니페스트: ${MANIFEST}`);
  const ctx = await preflight(api);
  log(`사용자 ${ctx.users.length}명 · 이슈타입 ${ctx.issueTypes.length}종 · resolution ${ctx.resolutions.length}종 · 스크린샷 ${ctx.screenshots.length}개`);

  const total = PROJECTS.length * ISSUES_PER_PROJECT;
  log(`\n계획 — 프로젝트 ${PROJECTS.length}개 · 이슈 ${total}건 · 스프린트 ${PROJECTS.length * 3}개 · 보드 ${PROJECTS.length}개 · 대시보드 2개`);

  if (DRY_RUN) {
    log('\n--dry-run — 쓰기 없이 종료한다.');
    return;
  }
  if (!flags.has('yes')) {
    log('\n실제 적재하려면 --yes 를 붙여라. (되돌리려면 rollback.mjs)');
    process.exitCode = 2;
    return;
  }

  const manifest = await loadManifest(MANIFEST);
  manifest.baseUrl = BASE_URL;
  manifest.createdAt = manifest.createdAt ?? new Date().toISOString();

  const r = rng(SEED);
  for (const project of PROJECTS) {
    log(`\n[${project.key}] ${project.name}`);
    await ensureProject(api, project, manifest);
    const plan = buildIssuePlan(project, rng(SEED + hash(project.key)));
    const fresh = (await createIssues(api, project, plan, ctx, manifest)) ?? [];

    if (fresh.length) {
      await applyTransitions(api, fresh, ctx);
      await addComments(api, fresh, r);
      await addAttachments(api, fresh, ctx);
    } else {
      log('  · 이슈는 이미 적재돼 있다 — 스프린트·보드만 확인한다');
    }

    // 스프린트 배정은 이슈 전체를 봐야 한다. 재실행이면 매니페스트의 키에 결정적 plan 을
    // index 로 다시 붙여 복원한다 — buildIssuePlan 이 같은 seed 에서 같은 결과를 주기 때문에 가능하다.
    const planByIndex = new Map(plan.map((p) => [p.index, p]));
    const allIssues = manifest.issues
      .filter((i) => i.projectKey === project.key)
      .map((i) => ({ key: i.key, plan: planByIndex.get(i.index) }))
      .filter((i) => i.plan);

    await createSprints(api, project, allIssues, manifest);
    await createBoard(api, project, manifest);
    await saveManifest(MANIFEST, manifest);
  }

  log('\n[대시보드]');
  await createDashboards(api, PROJECTS.map((p) => p.key), manifest);
  await saveManifest(MANIFEST, manifest);

  log(`\n완료. 요청 통계 ${JSON.stringify(api.stats)}`);
  log(`매니페스트 ${MANIFEST} 에 기록했다. 회수는 node scripts/qa-seed/rollback.mjs --yes`);
}

main().catch((e) => { console.error(`\n실패: ${e.message}`); process.exit(1); });

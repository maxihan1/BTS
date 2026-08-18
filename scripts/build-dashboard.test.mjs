// 빌드 스크립트의 마크다운 파서 단위 테스트
// 실행. node --test scripts/build-dashboard.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parsePlanFile,
  parseFrIndex,
  parseGlossary,
  parseYongeo,
  parseTodos,
  renderTodos,
  TODO_STATUS_BY_MARKER,
} from './build-dashboard.mjs';

/**
 * 실제 `TODOS.md` 를 읽는다.
 *
 * 경로 조립을 테스트마다 다시 적지 않는다 — 아래 실파일 단언이 여럿이라 사본을 두면
 * 그것들이 서로 갈라진다. 기존 단건 테스트(`마커 수와 섹션 수가 일치한다`)는
 * 인라인 조립을 그대로 두었다 — 이 PR 이 건드리지 않은 코드다.
 */
async function readTodosFile() {
  const fs = await import('node:fs');
  const path = await import('node:path');
  const url = await import('node:url');
  const repoRoot = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), '..');
  const file = path.join(repoRoot, 'TODOS.md');
  return { content: fs.readFileSync(file, 'utf-8'), file };
}

test('parsePlanFile — 4단계 마커 4종(완료/진행중/차단/미진행) 정확히 인식', () => {
  const md = `# issue-tracking BC

#### §2.1.1 FR-IS-01 — 이슈 CRUD

- [x] D1. 도메인 (책임. backend-engineer)
- [~] D2. 명세 (책임. backend-engineer)
- [!] D3. 데이터 모델 (책임. db-engineer)
- [ ] D4. 백엔드 (책임. backend-engineer)
`;
  const { frs } = parsePlanFile(md, 'issue-tracking');
  assert.equal(frs.length, 1);
  assert.equal(frs[0].id, 'FR-IS-01');
  assert.equal(frs[0].steps.length, 4);
  assert.deepEqual(
    frs[0].steps.map(s => s.status),
    ['완료', '진행중', '차단', '미진행']
  );
});

test('parsePlanFile — 차단(!)이 하나라도 있으면 FR 상태 차단', () => {
  const md = `#### §1.1 FR-AT-01 — 트리거

- [x] D1.  도메인
- [!] D2. 명세
- [x] D3. 데이터 모델
`;
  const { frs } = parsePlanFile(md, 'automation');
  assert.equal(frs[0].status, '차단');
});

test('parsePlanFile — 모든 단계 완료 시 FR 상태 완료', () => {
  const md = `#### §1.1 FR-IS-01 — 이슈 CRUD

- [x] D1. 도메인
- [x] D2. 명세
`;
  const { frs } = parsePlanFile(md, 'issue-tracking');
  assert.equal(frs[0].status, '완료');
});

test('parsePlanFile — 진행중 마커(~)는 차단보다 낮은 우선순위', () => {
  const md = `#### §1.1 FR-IS-02 — 타입

- [~] D1. 도메인
- [x] D2. 명세
`;
  const { frs } = parsePlanFile(md, 'issue-tracking');
  assert.equal(frs[0].status, '진행중');
});

test('parsePlanFile — 일부 완료 + 일부 미진행은 진행중으로 집계', () => {
  const md = `#### §1.1 FR-AU-02 — LDAP

- [x] D1. 도메인
- [x] D2. 명세
- [x] D3. 데이터 모델
- [x] D4. 백엔드
- [x] D5. 백엔드 테스트
- [x] D6. 프론트 UI
- [ ] D7. E2E
`;
  const { frs } = parsePlanFile(md, 'identity-access');
  assert.equal(frs[0].status, '진행중');
});

test('parsePlanFile — ### 헤더(3 해시)도 FR 헤더로 인식', () => {
  const md = `## §6 이슈 이동

### §6.1.1 FR-MV-01 — 프로젝트 간 이슈 이동

- [ ] D1. 도메인
`;
  const { frs } = parsePlanFile(md, 'issue-tracking');
  assert.equal(frs.length, 1);
  assert.equal(frs[0].id, 'FR-MV-01');
});

test('parseFrIndex — fr-index.md 테이블 행에서 FR 117개 추출', () => {
  const md = `| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-IS-01 | 이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림 | 필수 | issue-tracking | §2.1.1 |
| FR-WF-01 | FSM 워크플로우 (상태/전이/조건/검증/후처리) | 필수 | project-workflow | §2.1 |
`;
  const idx = parseFrIndex(md);
  assert.equal(idx.size, 2);
  assert.equal(idx.get('FR-IS-01').bcSlug, 'issue-tracking');
  assert.equal(idx.get('FR-IS-01').oneLiner, '이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림');
  assert.equal(idx.get('FR-WF-01').priority, '필수');
});

test('parseGlossary — ## 섹션 + 3컬럼 표(용어/영문/정의) 파싱', () => {
  const md = `## 핵심 엔티티

| 용어 | 영문 | 정의 |
|---|---|---|
| 이슈 | Issue | 작업 단위. 고유 키는 영구 보존 |
| 스프린트 | Sprint | 애자일 작업 기간 |
`;
  const terms = parseGlossary(md);
  const term = terms.find(t => t.term === '이슈');
  assert.ok(term);
  assert.equal(term.definition, '작업 단위. 고유 키는 영구 보존');
  assert.ok(terms.find(t => t.term === 'Issue'));
});

test('parseYongeo — "A / B / C" 형식의 용어를 개별 분리', () => {
  const md = `# 협업

| **개발 용어** | **비개발자를 위한 쉬운 설명** |
|---|---|
| **Epic / Story / Task** | **"작업 쪼개기 단위"** 큰 프로젝트(에픽) → 사용자 기능(스토리) → 개발자 작업(태스크). |
`;
  const terms = parseYongeo(md);
  const names = terms.map(t => t.term).sort();
  assert.ok(names.includes('Epic'));
  assert.ok(names.includes('Story'));
  assert.ok(names.includes('Task'));
});

test('parseYongeo — "PR (Pull Request)" 형식에서 약어와 풀이를 함께 추출', () => {
  const md = `# 협업

| **개발 용어** | **비개발자를 위한 쉬운 설명** |
|---|---|
| **PR (Pull Request)** | **"코드 결재 요청서"** 동료에게 검토를 요청하는 것 |
`;
  const terms = parseYongeo(md);
  const names = terms.map(t => t.term);
  assert.ok(names.includes('PR'));
  assert.ok(names.includes('Pull Request'));
});

test('parseTodos — ✅/⬜ 마커로 해소/미착수를 가르고 본문을 섹션에 귀속시킨다', () => {
  const md = `<!-- 주석 -->

# TODOS

## ✅ 인프라 — 이미 고친 것 (2026-07-27 해소)

**해소.** 원인은 메시지 도둑질이었다.

## ⬜ apps/web — 아직 안 한 것 (미착수)

**무엇.** 접힘 레일 진입점 검토.
`;
  const todos = parseTodos(md);
  assert.equal(todos.length, 2);
  assert.equal(todos[0].status, '해소');
  assert.equal(todos[0].title, '인프라 — 이미 고친 것 (2026-07-27 해소)');
  assert.ok(todos[0].body.includes('메시지 도둑질'));
  assert.equal(todos[1].status, '미착수');
  assert.ok(todos[1].body.includes('접힘 레일'));
  // 다른 섹션 본문이 섞이지 않는다
  assert.ok(!todos[0].body.includes('접힘 레일'));
});

test('parseTodos — 실제 TODOS.md 를 파싱하면 마커 수와 섹션 수가 일치한다', async () => {
  const fs = await import('node:fs');
  const path = await import('node:path');
  const url = await import('node:url');
  const repoRoot = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), '..');
  const content = fs.readFileSync(path.join(repoRoot, 'TODOS.md'), 'utf-8');
  // ★마커 목록을 여기 다시 적지 않는다. 적으면 파서와 갈라지는 세 번째 사본이 된다 —
  //   실제로 이 줄이 `(✅|⬜)` 로 굳어 있어서 📌 누락을 못 잡고 있었다.
  const markers = Object.keys(TODO_STATUS_BY_MARKER).join('|');
  const headings = content.split('\n').filter(l => new RegExp(`^##\\s+(${markers})\\s+`).test(l));
  const todos = parseTodos(content);
  assert.equal(todos.length, headings.length);
  assert.ok(todos.length > 0, 'TODOS.md 에서 섹션을 하나도 못 읽었다');
  assert.ok(todos.every(t => t.title.length > 0));
  // 본문이 전부 비면 파서가 헤딩만 긁은 것이다 (공허 통과 차단)
  assert.ok(todos.filter(t => t.body.length > 0).length === todos.length);
});

// ─────────────────────────────────────────────────────────────────────────────
// 📌 보류 마커 — 파서·판별식·렌더가 같은 목록을 쓰는지
//
// 왜. `todos-resolved-section-purity.test.ts` 는 헤딩 마커로 ✅·📌·⬜ 셋을 허용하는데
// `build-dashboard.mjs` 의 파서는 ✅·⬜ **둘만** 인식했다. `📌 보류` 섹션을 하나라도
// 만들면 그 헤딩과 본문이 **앞 섹션 본문으로 흡수**돼 집계에서 통째로 사라진다.
// 게다가 앞 섹션이 ✅ 인데 흡수된 본문에 ⬜ 가 있으면 순수성 판별식이 엉뚱한 섹션을 지목한다.
// 현재 📌 섹션이 0건이라 **잠복** 중이었다 — 「보류」로 분류하고 싶은 항목이 생기는 순간 터진다.
// ─────────────────────────────────────────────────────────────────────────────

test('parseTodos — 📌 보류를 독립 섹션으로 인식한다 (앞 섹션에 흡수되지 않는다)', () => {
  const md = `# TODOS

## ✅ 인프라 — 고친 것

**해소.** 원인은 메시지 도둑질이었다.

## 📌 apps/web — 판단 보류

**무엇.** 실사용 신호가 모일 때까지 미룬다.

## ⬜ 인프라 — 아직 안 한 것

**무엇.** 접힘 레일 진입점.
`;
  const todos = parseTodos(md);
  assert.equal(todos.length, 3, '📌 섹션이 앞 섹션 본문으로 흡수됐다');
  assert.equal(todos[1].status, '보류');
  assert.equal(todos[1].title, 'apps/web — 판단 보류');
  assert.ok(todos[1].body.includes('실사용 신호'));
  // ★흡수의 서명 — 앞 ✅ 섹션이 📌 의 본문을 먹었는가.
  assert.ok(!todos[0].body.includes('실사용 신호'), '✅ 섹션이 📌 본문을 흡수했다');
  assert.ok(!todos[0].body.includes('판단 보류'), '✅ 섹션이 📌 헤딩을 흡수했다');
});

test('parseTodos — 선언된 상태 마커를 전부 인식한다 (단일 출처)', () => {
  // 목록을 여기 다시 적지 않는다. 상수에서 파생시켜야 마커가 늘어도 갈라지지 않는다.
  for (const [marker, status] of Object.entries(TODO_STATUS_BY_MARKER)) {
    const todos = parseTodos(`# TODOS\n\n## ${marker} 제목\n\n본문.\n`);
    assert.equal(todos.length, 1, `${marker} 를 섹션으로 인식하지 못했다`);
    assert.equal(todos[0].status, status, `${marker} 의 상태 이름이 상수와 다르다`);
  }
});

test('parseTodos — 선언되지 않은 마커는 섹션이 아니다 (음성 대조군)', () => {
  // 인식 범위가 넓어지면 「아무 헤딩이나 섹션」이 돼 순수성 판별식이 무의미해진다.
  const todos = parseTodos(`# TODOS\n\n## 🔴 마커 아님\n\n본문.\n`);
  assert.equal(todos.length, 0, '선언되지 않은 마커를 섹션으로 인식했다');
});

test('renderTodos — 선언된 모든 상태가 렌더 그룹을 갖는다 (파싱됐는데 화면에서 사라지지 않는다)', () => {
  // ★파서만 고치면 절반이다. 📌 를 파싱해 놓고 렌더에서 안 그리면 결과는 동일하게
  //   「대시보드에서 사라짐」이다 — 봉인 절반 양식.
  const md = Object.entries(TODO_STATUS_BY_MARKER)
    .map(([marker], i) => `## ${marker} 항목${i} 제목\n\n본문${i} 내용.\n`)
    .join('\n');
  const todos = parseTodos(`# TODOS\n\n${md}`);
  assert.equal(todos.length, Object.keys(TODO_STATUS_BY_MARKER).length);

  const html = renderTodos(todos);
  todos.forEach((t, i) => {
    assert.ok(html.includes(`항목${i} 제목`), `${t.status} 항목이 렌더 결과에 없다`);
    assert.ok(html.includes(`본문${i} 내용`), `${t.status} 본문이 렌더 결과에 없다`);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 코드펜스 안의 `# ` — 파서가 H1 로 오인해 항목 본문을 그 자리에서 자른다
//
// 왜. `parseTodos` 는 `line.startsWith('# ')` 를 만나면 항목을 닫는다. 코드펜스를
// 추적하지 않으므로 **코드블록 안의 bash·Kotlin 주석**이 H1 로 읽힌다.
// 2026-08-18 실측 — `TODOS.md:455`(`# 비-기본 권한 스킴을 만드는 프로덕션 쓰기 경로`) ·
// `:753`(`# 생성자 (L87-91) …`) 두 곳이 항목 본문을 절단하고 있었다. 화면에서는
// 「그 아래가 통째로 없다」로 보이고, 아무 판정도 그것을 세지 않았다.
//
// ★같은 파일 `mdToHtml` 은 fence 를 이미 안다(`if (line.startsWith('```'))`).
//   한 파일 안에서 fence 판정이 갈려 있던 것이 이 결함의 뿌리다.
// ─────────────────────────────────────────────────────────────────────────────

test('parseTodos — 코드펜스 안의 `# ` 는 항목을 자르지 않는다', () => {
  const md = [
    '# TODOS',
    '',
    '## ⬜ 인프라 — 펜스 안에 주석이 있는 항목',
    '',
    '**무엇.** 아래 코드블록에 `#` 주석이 있다.',
    '',
    '```',
    '# 이것은 bash 주석이지 문서 제목이 아니다',
    'grep -rn "foo" .',
    '```',
    '',
    '**펜스 뒤 본문.** 이 문장이 사라지면 절단된 것이다.',
    '',
  ].join('\n');

  const todos = parseTodos(md);
  assert.equal(todos.length, 1, '펜스 안 주석이 항목을 쪼갰다');
  assert.ok(
    todos[0].body.includes('펜스 뒤 본문'),
    '펜스 안 `# ` 에서 본문이 잘렸다 — 그 아래가 화면에서 사라진다',
  );
});

test('parseTodos — 펜스 **밖**의 `# ` 는 여전히 항목을 닫는다 (음성 대조군)', () => {
  // 과잉 수정 방지. fence 추적이 `# ` 판정을 통째로 없애 버리면 문서 구분자가
  // 항목 본문으로 흡수돼 반대 방향 사고가 난다.
  const md = [
    '# TODOS',
    '',
    '## ⬜ 인프라 — 앞 항목',
    '',
    '**무엇.** 본문.',
    '',
    '# 문서 구분자',
    '',
    '구분자 뒤 서문은 어느 항목에도 속하지 않는다.',
    '',
  ].join('\n');

  const todos = parseTodos(md);
  assert.equal(todos.length, 1);
  assert.ok(!todos[0].body.includes('구분자 뒤 서문'), '펜스 밖 `# ` 가 항목을 안 닫았다');
});

test('TODOS.md — 펜스 안 `# ` 로 절단된 항목이 0 이다 (실파일 회귀 고정)', async () => {
  const { content } = await readTodosFile();
  const lines = content.split('\n');

  // 펜스 안에 있는 `# ` 줄을 실측한다. 이 줄들은 항목을 자르면 안 된다.
  let inFence = false;
  const fencedHashLines = [];
  for (const [i, line] of lines.entries()) {
    if (line.startsWith('```')) { inFence = !inFence; continue; }
    if (inFence && line.startsWith('# ')) fencedHashLines.push(i + 1);
  }

  const todos = parseTodos(content);
  const joined = todos.map((t) => t.body).join('\n');
  const missing = fencedHashLines.filter((ln) => {
    const after = lines[ln]; // 그 주석 바로 다음 줄이 본문에 남아 있는가
    return after && after.trim().length > 0 && !joined.includes(after.trim());
  });

  assert.deepEqual(
    missing,
    [],
    `펜스 안 \`# \` 뒤 본문이 사라진 줄: ${missing.join(', ')} — 파서가 코드 주석을 H1 로 읽었다.`,
  );
});

test('TODOS.md — 코드펜스 열림/닫힘이 짝수다 (홀수면 문서가 통째로 사라진다)', async () => {
  // ★critical gap. 펜스가 홀수 개면 그 아래 전부가 코드로 읽혀 화면에서 조용히 없어진다.
  //   테스트도 오류 처리도 없고 사용자는 「원래 없었나」로 읽는다. 막는 비용이 이 한 줄이다.
  const { content } = await readTodosFile();
  const fences = content.split('\n').filter((l) => l.startsWith('```')).length;
  assert.equal(fences % 2, 0, `코드펜스가 ${fences}개(홀수)다 — 닫히지 않은 블록이 있다.`);
});

test('TODOS.md — 물결 펜스(~~~)를 쓰지 않는다 (파서 전제 고정)', async () => {
  // 파서는 백틱 펜스만 추적한다. 2026-08-18 실측 기준 물결 펜스는 0건이고, 그 전제가
  // 조용히 낡지 않도록 못 박는다. 물결을 쓰려면 파서를 먼저 고쳐라.
  const { content } = await readTodosFile();
  const tildes = content.split('\n').filter((l) => l.startsWith('~~~')).length;
  assert.equal(tildes, 0, `물결 펜스 ${tildes}줄 — 파서가 추적하지 않는 서식이다.`);
});

test('TODOS.md — 펜스 안에 `## ` 헤딩이 없다 (파서 전제 고정)', async () => {
  // `parseTodos` 는 `# ` 에만 fence 게이트를 달았다. 펜스 안 `## <마커>` 는 실측 0건이라
  // 지금 고치면 가설로 섹션 수를 바꾸는 것이 된다. 대신 그 전제를 여기서 못 박는다 —
  // 예시로 `## ⬜ …` 를 코드블록에 넣는 순간 유령 섹션이 생기고 이 단언이 먼저 죽는다.
  const { content } = await readTodosFile();
  const lines = content.split('\n');
  let inFence = false;
  const fenced = [];
  for (const [i, line] of lines.entries()) {
    if (line.startsWith('```')) { inFence = !inFence; continue; }
    if (inFence && /^##\s/.test(line)) fenced.push(i + 1);
  }
  assert.deepEqual(fenced, [], `펜스 안 \`## \` 헤딩 줄: ${fenced.join(', ')} — 유령 섹션이 생긴다.`);
});

test('TODOS.md — 두 파서가 버리는 텍스트에 판정 대상이 없다 (REGRESSION)', async () => {
  // ★두 파서가 이미 서로 다르게 읽는다.
  //   `parseTodos`(대시보드)는 `# ` 에서 항목을 닫고,
  //   `todos-resolved-section-purity.test.ts` 의 `parseSections` 는 `## ` 만 경계로 본다.
  //   즉 대시보드가 못 보는 본문을 순수성 판별식은 본다 — 두 목록이 서로를 검사하지 않는다.
  //   통합은 그 테스트의 계약을 바꾸므로 별건이고, 여기서는 **차이가 판정 대상을 삼키지
  //   않는다**만 강제한다. 삼키면 순수성 판별식과 두 줄 판별식이 동시에 눈이 먼다.
  const { content } = await readTodosFile();
  const lines = content.split('\n');

  // `## ` 만 경계로 본 본문 (purity test 의 시야)
  const bySection = [];
  let cur = null;
  for (const text of lines) {
    if (text.startsWith('## ')) { cur = { body: [] }; bySection.push(cur); continue; }
    if (cur) cur.body.push(text);
  }
  const purityView = bySection.map((s) => s.body.join('\n')).join('\n');
  const dashboardView = parseTodos(content).map((t) => t.body).join('\n');

  // 차이 = purity 는 보는데 dashboard 는 못 보는 줄
  const dropped = purityView
    .split('\n')
    .filter((l) => l.trim() && !dashboardView.includes(l));

  const loadBearing = dropped.filter((l) =>
    /^##\s*[⬜📌]|^\s*-\s*[⬜📌]|\*\*쉬운 말\.\*\*|\*\*방치하면\.\*\*/.test(l),
  );

  assert.deepEqual(
    loadBearing,
    [],
    '대시보드 파서가 버리는 텍스트에 판정 대상(미해결 마커 · 두 줄)이 들어 있다.\n' +
      `해당 줄: ${loadBearing.join(' | ')}`,
  );
});

test('renderTodos — 집계 합이 전체와 맞는다 (어느 상태도 셈에서 빠지지 않는다)', () => {
  const todos = parseTodos(
    `# TODOS\n\n## ✅ 가\n\n본문.\n\n## 📌 나\n\n본문.\n\n## ⬜ 다\n\n본문.\n`,
  );
  const html = renderTodos(todos);
  // 「전체 N건」과 상태별 건수의 합이 어긋나면 어떤 상태가 집계에서 빠진 것이다.
  const total = Number(html.match(/전체 (\d+)건/)?.[1]);
  const counts = [...html.matchAll(/— (\d+)건/g)].map((m) => Number(m[1]));
  assert.equal(total, todos.length, '전체 건수가 파싱 결과와 다르다');
  assert.equal(
    counts.reduce((a, b) => a + b, 0),
    total,
    `상태별 합(${counts.join('+')})이 전체(${total})와 다르다 — 집계에서 빠진 상태가 있다`,
  );
});

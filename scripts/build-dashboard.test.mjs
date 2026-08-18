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
  CATEGORIES,
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
| FR-WF-01 | FSM 워크플로우 (상태/전환/조건/검증/후처리) | 필수 | project-workflow | §2.1 |
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
  // ★펜스 안 예시(`등재 서식`)는 헤딩이 아니다 — 파서와 같은 게이트를 여기서도 건다.
  //   안 걸면 이 단언이 예시 1건만큼 어긋나 파서가 멀쩡한데 red 가 난다.
  const headingRe = new RegExp(`^##\\s+(${markers})\\s+`);
  let inFence = false;
  const headings = content.split('\n').filter((l) => {
    if (l.startsWith('```')) { inFence = !inFence; return false; }
    return !inFence && headingRe.test(l);
  });
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

test('parseTodos — 펜스 안의 `## <마커>` 는 섹션이 되지 않는다 (유령 섹션 차단)', () => {
  // `TODOS.md` 머리의 **등재 서식 예시**가 코드블록 안에서 `## ⬜ …` 모양을 보여 준다.
  // 게이트가 없으면 그 예시가 섹션으로 잡혀 집계·순수성 판정을 동시에 오염시킨다.
  const md = [
    '# TODOS',
    '',
    '**등재 서식.** 아래 모양으로 쓴다.',
    '',
    '```',
    '## ⬜ <영역> — <한 줄 증상> (<상태> · T?)',
    '```',
    '',
    '## ⬜ 도구 — 진짜 항목',
    '',
    '**무엇.** 본문.',
    '',
  ].join('\n');

  const todos = parseTodos(md);
  assert.equal(todos.length, 1, '펜스 안 예시가 유령 섹션이 됐다');
  assert.equal(todos[0].title, '도구 — 진짜 항목');
});

test('TODOS.md — 실파일에서도 펜스 안 예시가 섹션으로 잡히지 않는다', async () => {
  // 실파일 회귀 고정. 펜스 밖 마커 헤딩 수와 파싱된 섹션 수가 같아야 한다.
  const { content } = await readTodosFile();
  const markers = Object.keys(TODO_STATUS_BY_MARKER).join('|');
  const re = new RegExp(`^##\\s+(${markers})\\s+`);
  let inFence = false;
  let outsideCount = 0;
  for (const line of content.split('\n')) {
    if (line.startsWith('```')) { inFence = !inFence; continue; }
    if (!inFence && re.test(line)) outsideCount++;
  }
  assert.equal(
    parseTodos(content).length,
    outsideCount,
    '파싱된 섹션 수가 펜스 밖 마커 헤딩 수와 다르다 — 예시가 섹션으로 잡혔거나 항목이 사라졌다.',
  );
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
  // ★마커 섹션만 본다. 머리의 `## 등재 서식` 은 항목이 아니라 문서 설명이고,
  //   그 안의 예시가 두 줄 마커를 **일부러** 담고 있어 비교에 넣으면 오탐이 난다.
  const markers = Object.keys(TODO_STATUS_BY_MARKER).join('|');
  const sectionRe = new RegExp(`^##\\s+(${markers})\\s+`);
  const bySection = [];
  let cur = null;
  let inFence = false;
  for (const text of lines) {
    // ★펜스 안은 예시·코드다. 여기를 안 태우면 머리의 등재 서식 예시가 섹션으로 잡혀
    //   그 안의 두 줄 마커가 「버려진 판정 대상」으로 오탐된다 — 2026-08-18 실측.
    //   이 파일에서만 fence 를 잊어 네 번 틀렸다. `## `/`# ` 를 스캔하면 fence 부터 본다.
    if (text.startsWith('```')) { inFence = !inFence; continue; }
    if (inFence) { if (cur) cur.body.push(text); continue; }
    if (text.startsWith('## ')) { cur = sectionRe.test(text) ? { body: [] } : null; if (cur) bySection.push(cur); continue; }
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

// ─────────────────────────────────────────────────────────────────────────────
// 비개발자용 렌더 — 카테고리 소분류 + 두 줄 노출
//
// 목적. 접지 않고도 ① 어느 영역의 빚인지 ② 무슨 뜻인지 ③ 안 고치면 뭐가 생기는지를 읽는다.
// ★두 줄은 `<details>` **밖**에 나오고 **안에는 없어야** 한다 — 본문 전체를 그리는
//   `mdToHtml(t.body)` 를 그대로 두면 같은 줄이 두 번 나온다(2026-08-18 리뷰 P1).
// ─────────────────────────────────────────────────────────────────────────────

/** 카테고리 렌더 테스트용 최소 입력 — 영역 접두 + 두 줄을 갖춘 미착수 항목. */
function todoFixture(area, title, easy, risk) {
  return `## ⬜ ${area} — ${title}\n\n**쉬운 말.** ${easy}\n\n**방치하면.** ${risk}\n\n**무엇.** 기술 본문.\n`;
}

test('renderTodos — 미착수 항목이 카테고리별로 묶인다', () => {
  const md = [
    todoFixture('apps/web', '화면 문제', '화면에서 이런 게 보인다.', '사용자가 헷갈린다.'),
    todoFixture('도구', '안전장치 문제', '검사 장치가 이렇다.', '다른 고장을 못 잡는다.'),
    todoFixture('인프라', '환경 문제', '빌드가 이렇다.', '검사가 늦어진다.'),
  ].join('\n');
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));

  assert.ok(html.includes(CATEGORIES.screen.name), '「화면에서 보이는 것」 묶음이 없다');
  assert.ok(html.includes(CATEGORIES.guard.name), '「개발 안전장치」 묶음이 없다');
  assert.ok(html.includes(CATEGORIES.infra.name), '「빌드·배포 환경」 묶음이 없다');
  // 안 쓰인 카테고리는 빈 묶음으로 남지 않는다
  assert.ok(!html.includes(CATEGORIES.docs.name), '항목이 없는 카테고리가 빈 묶음으로 남았다');
});

test('renderTodos — 카테고리마다 한 줄 설명이 붙는다', () => {
  const md = todoFixture('도구', '안전장치 문제', '검사 장치가 이렇다.', '다른 고장을 못 잡는다.');
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));
  assert.ok(
    html.includes(CATEGORIES.guard.desc),
    '카테고리 설명이 없다 — 분류만 있고 그게 무엇인지는 없는 상태다',
  );
});

test('renderTodos — 두 줄이 `<details>` **밖**에 있다 (접지 않아도 읽힌다)', () => {
  const md = todoFixture('apps/web', '화면 문제', '화면에서 이런 게 보인다.', '사용자가 헷갈린다.');
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));

  const summaryEnd = html.indexOf('</summary>');
  const detailsEnd = html.indexOf('</details>');
  assert.ok(summaryEnd > 0 && detailsEnd > summaryEnd, '렌더 구조가 바뀌었다');

  assert.ok(html.includes('화면에서 이런 게 보인다'), '「쉬운 말」이 렌더 결과에 없다');
  assert.ok(html.includes('사용자가 헷갈린다'), '「방치하면」이 렌더 결과에 없다');
});

test('renderTodos — 두 줄이 `<details>` **안에는** 없다 (중복 렌더 차단)', () => {
  // ★본문 전체를 그리는 경로를 그대로 두면 같은 줄이 접기 안팎에 두 번 나온다.
  const md = todoFixture('apps/web', '화면 문제', '화면에서 이런 게 보인다.', '사용자가 헷갈린다.');
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));

  const body = html.slice(html.indexOf('todo-body'), html.indexOf('</details>'));
  assert.ok(!body.includes('화면에서 이런 게 보인다'), '「쉬운 말」이 접기 안에도 중복으로 나온다');
  assert.ok(!body.includes('사용자가 헷갈린다'), '「방치하면」이 접기 안에도 중복으로 나온다');
  // 기술 본문은 접기 안에 그대로 남아야 한다 (과잉 제거 방지)
  assert.ok(body.includes('기술 본문'), '두 줄을 걷어내면서 기술 본문까지 지웠다');
});

test('renderTodos — 보류(📌)도 카테고리 경로를 탄다 (실데이터 0건이라 공허한 축을 합성으로 태운다)', () => {
  // ★실데이터에 📌 가 0건이라 계약·렌더의 보류 축이 통째로 공허했다 —
  //   `CONTRACTED_STATUSES` 에서 '보류' 를 빼도 전량 초록이었다(2026-08-18 리뷰 실측).
  //   스펙 E6 이 「보류는 미착수와 같은 의무」라고 못 박았으므로 합성으로 그 경로를 태운다.
  const md = `## 📌 도구 — 판단 보류 항목\n\n**쉬운 말.** 지금은 고치지 않기로 한 것이다.\n\n**방치하면.** 판단을 다시 하게 되는 날까지 그대로 남는다.\n\n**무엇.** 기술 본문.\n`;
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));

  assert.ok(html.includes(CATEGORIES.guard.name), '보류 항목이 카테고리로 안 묶였다');
  assert.ok(html.includes(CATEGORIES.guard.desc), '보류 묶음에 카테고리 설명이 없다');
  assert.ok(html.includes('지금은 고치지 않기로 한 것이다'), '보류 항목의 두 줄이 안 나온다');
  const body = html.slice(html.indexOf('todo-body'), html.indexOf('</details>'));
  assert.ok(!body.includes('지금은 고치지 않기로 한 것이다'), '보류 항목도 두 줄이 접기 안에 중복된다');
});

test('TODOS.md — 펜스 안에 `## ` 헤딩이 없다 (다른 두 파서를 지키는 단언)', async () => {
  // `parseTodos` 는 펜스 안 `## ` 를 무시하지만 `debt-ledger-mapping.test.ts` 와
  // `todos-resolved-section-purity.test.ts` 는 **여전히 펜스를 안 본다**. 그 둘을 고치면
  // 순수성 판별식이 「펜스 안에 숨긴 미해결 마커」를 놓치므로, 파서를 합치는 대신
  // **입력 쪽에서** 그 모양을 금지한다. 등재 서식의 제목 줄을 코드블록 밖에 둔 이유다.
  const { content } = await readTodosFile();
  const lines = content.split('\n');
  let inFence = false;
  const fenced = [];
  for (const [i, line] of lines.entries()) {
    if (line.startsWith('```')) { inFence = !inFence; continue; }
    if (inFence && /^##\s/.test(line)) fenced.push(i + 1);
  }
  assert.deepEqual(
    fenced,
    [],
    `펜스 안 \`## \` 헤딩 줄: ${fenced.join(', ')} — 펜스를 안 보는 파서 2벌이 유령 항목으로 읽는다.`,
  );
});

test('renderTodos — 본문 코드블록 안의 두 줄 예시는 접기 밖으로 승격되지 않는다', () => {
  // ★B2 회귀 고정. `splitPlainLines` 가 펜스를 안 보면 코드블록 안의 예시가 뜯겨 나와
  //   그 항목의 진짜 두 줄인 척 렌더된다 — 이 PR 이 parseTodos 에서 고친 맹목을 새 코드에
  //   다시 심었던 자리다. 고쳐 놓고 지키는 테스트를 안 만들어 뮤테이션 ⑩ 이 GREEN 이었다.
  const md = [
    '## ⬜ 도구 — 서식을 설명하는 항목',
    '',
    '**쉬운 말.** 진짜 쉬운 말이다 충분히 길게 적은 문장이다.',
    '',
    '**방치하면.** 진짜 위험이다 충분히 길게 적은 문장이다.',
    '',
    '**무엇.** 서식은 아래와 같다.',
    '',
    '```',
    '**쉬운 말.** 이건 코드블록 안의 예시일 뿐이다.',
    '```',
    '',
  ].join('\n');
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));

  const plainStart = html.indexOf('todo-plain');
  const plainEnd = html.indexOf('<details');
  const plain = html.slice(plainStart, plainEnd);
  assert.ok(plain.includes('진짜 쉬운 말이다'), '진짜 두 줄이 접기 밖에 없다');
  assert.ok(
    !plain.includes('코드블록 안의 예시'),
    '코드블록 안 예시가 접기 밖으로 승격됐다 — 펜스 게이트가 없다',
  );
  const body = html.slice(html.indexOf('todo-body'), html.indexOf('</details>'));
  assert.ok(body.includes('코드블록 안의 예시'), '예시가 기술 상세에서 사라졌다');
});

test('renderTodos — 해소 항목은 카테고리로 묶지 않는다 (계약 대상 밖)', () => {
  // 해소 76건에 두 줄을 소급 작성하지 않기로 했으므로(NFR N2) 카테고리 분류도 안 한다.
  const md = `## ✅ apps/web — 이미 고친 것 (해소 2026-01-01 · #1)\n\n**무엇.** 본문.\n`;
  const html = renderTodos(parseTodos(`# TODOS\n\n${md}`));
  assert.ok(html.includes('이미 고친 것'), '해소 항목이 렌더에서 사라졌다');
  assert.ok(!html.includes(CATEGORIES.screen.name), '해소 항목에 카테고리 묶음이 붙었다');
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

/**
 * `parseTodos` 의 소속 절(H1) 추적 — PR #390 신설.
 *
 * `TODOS.md` 를 카테고리 절로 재배열하면 H1 의 의미가 **분류 경계**로 승격된다.
 * 그때 「이 항목이 어느 절에 있는가」를 알아야 파일 배치와 화면 배치의 일치를 강제할 수
 * 있는데, 종전 `parseTodos` 는 H1 을 경계로만 쓰고 **이름을 버렸다**.
 *
 * ★새 스캐너를 만들지 않고 이 함수를 확장한 이유. `# `·`## ` 를 읽는 코드가 늘어날 때마다
 * 코드펜스 판정을 잊는 것이 이 저장소의 반복 사고다(#389 한 세션에서 5회).
 * 판정을 한 벌로 유지하는 것이 처방이다 — `[[every-new-heading-scanner-forgets-code-fences]]`.
 */
test('parseTodos — 각 항목이 소속 H1 절 이름을 갖는다', () => {
  const md = [
    '# 화면에서 보이는 것',
    '',
    '## ⬜ apps/web — 첫 번째',
    '',
    '**무엇.** 본문 A.',
    '',
    '## ⬜ apps/web — 두 번째',
    '',
    '**무엇.** 본문 B.',
    '',
    '# 개발 안전장치',
    '',
    '## ⬜ 도구 — 세 번째',
    '',
    '**무엇.** 본문 C.',
    '',
  ].join('\n');

  const todos = parseTodos(md);
  assert.equal(todos.length, 3, '항목 3개를 수집해야 한다 (비-공허 짝)');
  assert.deepEqual(
    todos.map((t) => t.section),
    ['화면에서 보이는 것', '화면에서 보이는 것', '개발 안전장치'],
    '항목이 자기 앞의 H1 을 소속 절로 갖지 않는다 — 파일 배치를 검사할 수 없다',
  );
});

test('parseTodos — 펜스 안의 `# ` 는 절 이름을 바꾸지 않는다', () => {
  const md = [
    '# 개발 안전장치',
    '',
    '## ⬜ 도구 — 펜스 안에 주석이 있는 항목',
    '',
    '```bash',
    '# 이것은 bash 주석이지 절 제목이 아니다',
    'grep -rn "foo" .',
    '```',
    '',
    '## ⬜ 워크플로우 — 그 다음 항목',
    '',
    '**무엇.** 본문.',
    '',
  ].join('\n');

  const todos = parseTodos(md);
  assert.equal(todos.length, 2, '펜스 안 주석이 항목을 쪼갰다');
  assert.deepEqual(
    todos.map((t) => t.section),
    ['개발 안전장치', '개발 안전장치'],
    '펜스 안 `# ` 를 절 경계로 읽었다 — 항목이 엉뚱한 카테고리로 넘어간다',
  );
});

test('parseTodos — H1 앞의 항목은 section 이 null 이다 (음성 대조군)', () => {
  const md = [
    '## ⬜ apps/web — H1 없이 먼저 나온 항목',
    '',
    '**무엇.** 본문.',
    '',
    '# 화면에서 보이는 것',
    '',
    '## ⬜ apps/web — H1 뒤 항목',
    '',
    '**무엇.** 본문.',
    '',
  ].join('\n');

  const todos = parseTodos(md);
  assert.equal(todos.length, 2, '항목 2개를 수집해야 한다');
  assert.equal(
    todos[0].section,
    null,
    'H1 앞 항목의 section 이 null 이 아니다 — 「어느 절에도 안 속함」을 판별할 수 없다',
  );
  assert.equal(todos[1].section, '화면에서 보이는 것');
});

<!-- 에이전트 문서 인덱싱 체계 구현 계획 — 생성기·분류기·렌더러·판별식 9 태스크 TDD -->

# 에이전트 문서 인덱싱 체계 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

> slug: `doc-indexing-system` · type: `chore` · 논리 BC: 없음 (저장소 인프라)
> 생성: 2026-08-01 · 기저: `39b43317d` · 브랜치: `chore/doc-indexing-system`
> 스펙: [`docs/specs/2026-08-01-doc-indexing-system.md`](../specs/2026-08-01-doc-indexing-system.md)

**Goal.** 메모리 378개와 `docs/` 716개 문서에 자동 생성 인덱스를 달아, 에이전트의 매 세션 고정
비용을 40.7KB → ≈17KB로 줄이고 인덱스 미등록(현재 178건)을 0으로 만든다.

**Architecture.** 단일 생성기 `scripts/build-doc-index.mjs`가 4개 모듈(`parse-memory` ·
`classify` · `scan-docs` · `render`)을 조합해 인덱스 9개 파일을 만든다. 저장소 안(`docs/`)은
CI 판별식이, 저장소 밖(메모리)은 생성기 자가진단과 pre-commit 훅이 지킨다.

**Tech Stack.** Node 22 ESM (`.mjs`) · `node:test` + `node:assert/strict` · bash(husky 훅) ·
GitHub Actions self-hosted 러너. 신규 런타임 의존성 0.

---

## 배경 — 구현자가 먼저 알아야 할 것

이 저장소를 처음 보는 사람을 위한 사전 지식이다. 모르면 함정에 빠진다.

**① 메모리는 저장소 밖에 있다.**
`~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/` — `git`이 추적하지 않는다.
CI 러너는 이 경로를 체크아웃하지 않으므로 **CI로 검증할 수 없다**. 그래서 생성기가
스스로 검증한다 (Task 3).

**② 「판별식(discriminant)」은 이 저장소의 고유 용어다.**
"두 하드코딩 목록이 서로를 안 봐서 조용히 갈라지는" 결함을 막는 자동 검사를 뜻한다.
현재 6종이 `scripts/workflow/*.test.ts`에 있고 `pnpm test:workflow`로 돈다.
우리가 만드는 것이 **7번째**다. 새 bash 스크립트를 만들지 말 것 — 관례를 벗어난다.

**③ CI 트리거 경로 목록은 판별식 입력의 합집합이어야 한다.**
`.github/workflows/workflow-scripts-ci.yml`의 `paths:`에 입력 경로가 빠지면, 그 파일만
고치는 PR에서 판별식이 **0회 실행되고 초록으로 통과**한다. 가장 나쁜 사각이다.
`pull_request`와 `push` **양쪽 블록에 같은 목록**을 넣어야 한다 (한쪽만 걸면 절반 봉인).

**④ 테스트 개수는 grep이 아니라 실행 결과로 센다.**
`node --test`의 출력(`# pass N`)을 본다. 파일에서 `test(` 를 grep하면 실제 실행 여부를
알 수 없다.

**⑤ 뮤테이션(일부러 위반 주입) 검증은 커밋된 클린 상태에서만 한다.**
dirty 상태에서 주입하면 원복이 불완전해진다. 서명은 「원복 후 클린인데 red」 — 이러면
하네스가 고장난 것이다.

---

## File Structure

**생성기 (신규).**

| 파일 | 책임 |
|---|---|
| `scripts/build-doc-index.mjs` | 진입점. 모듈 조합 · 파일 쓰기 · 자가진단 후 종료 코드 |
| `scripts/doc-index/config.mjs` | 상수 단일 출처 — `SOURCES` · 카테고리 · 패턴 · 수동 오버라이드 |
| `scripts/doc-index/parse-memory.mjs` | 메모리 frontmatter 파싱 + 기존 `MEMORY.md` 승계 파싱 |
| `scripts/doc-index/classify.mjs` | 4단계 분류 (오버라이드→승계→패턴→uncategorized) |
| `scripts/doc-index/scan-docs.mjs` | `docs/` 스캔 — 파일명·H1·FR ID 추출 |
| `scripts/doc-index/render.mjs` | 마크다운 렌더 (라우터 · 카테고리 · FR축 · 시간축) |

**테스트 (신규).** `scripts/doc-index/{parse-memory,classify,scan-docs,render}.test.mjs`

**판별식 (신규).** `scripts/workflow/doc-index-coverage.test.ts`

**생성물 (신규, 자동).** `MEMORY.md`(덮어씀) · `memory/index/{workflow,backend,cross-bc,frontend,build,uncategorized}.md` · `docs/INDEX.md` · `docs/INDEX-fr.md` · `docs/INDEX-recent.md`

**수정.** `package.json`(스크립트 2개) · `.github/workflows/workflow-scripts-ci.yml`(paths+주석) · `CLAUDE.md`(슬림화+§인덱싱 규칙) · `DEVELOPMENT.md`·`DATA.md`(목차) · `.husky/pre-commit` · `docs/rules/`(분리본 신규)

분할 이유. 선례 `build-dashboard.mjs`는 919줄 단일 파일이지만, 파서·분류기·렌더러는 각각
단위 테스트 대상이 달라 함께 두면 테스트가 서로를 오염시킨다. 책임별로 나눈다.

---

## Task 1: 설정 상수 + 메모리 frontmatter 파서

**Files:**
- Create: `scripts/doc-index/config.mjs`
- Create: `scripts/doc-index/parse-memory.mjs`
- Test: `scripts/doc-index/parse-memory.test.mjs`
- Modify: `package.json` (scripts에 `test:doc-index` 추가)

- [ ] **Step 1: 설정 상수 파일 작성**

`scripts/doc-index/config.mjs`

```javascript
// 문서 인덱싱 생성기의 상수 단일 출처 — 입력 경로·카테고리·분류 패턴·수동 오버라이드
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(__dirname, '../..');
export const HOME = process.env.HOME || process.env.USERPROFILE || '';

// 메모리는 저장소 밖이다. CI 가 볼 수 없다 — 생성기 자가진단으로 지킨다.
export const MEMORY_DIR = path.join(
  HOME,
  '.claude/projects/-Users-maxi-moff-Projects-BTS/memory',
);

// ★ SOURCES 는 CI 트리거 경로(workflow-scripts-ci.yml)와 정합해야 한다 — 룰 L 이 강제한다.
//   저장소 안 경로만 CI 대상이다. repoRelative:false 는 검증에서 제외된다.
export const SOURCES = [
  { key: 'plans',     dir: 'docs/plans',     repoRelative: true },
  { key: 'specs',     dir: 'docs/specs',     repoRelative: true },
  { key: 'decisions', dir: 'docs/decisions', repoRelative: true },
  { key: 'adr',       dir: 'docs/adr',       repoRelative: true },
];

export const CATEGORIES = [
  { key: 'workflow',      label: '워크플로우·도구·소통' },
  { key: 'backend',       label: '백엔드 (Kotlin/jOOQ/트랜잭션/예외)' },
  { key: 'cross-bc',      label: 'cross-BC·권한·shared-kernel' },
  { key: 'frontend',      label: '프론트 (React/Zod/MSW/E2E/vitest)' },
  { key: 'build',         label: '빌드·린트·마이그레이션·SSO' },
  { key: 'uncategorized', label: '미분류 — 정리 필요' },
];

// 현재 MEMORY.md 의 `## ` 그룹 → 카테고리 키. 새 분류 체계를 발명하지 않는다.
export const LEGACY_GROUP_MAP = {
  '워크플로우': 'workflow',
  '프론트': 'frontend',
  '백엔드': 'backend',
  'cross-BC': 'cross-bc',
  '마이그레이션': 'build',
  'FR 스코프': 'fr-done',
};

// FR 완료 이력 판정. `\d{2}` 를 요구한다 — `/^fr-/` 로 넓히면
// fr-scope-change-full-sync-rule(워크플로우 규칙)을 완료 이력으로 오분류한다 (스펙 R2).
export const FR_ID_RE = /^fr-([a-z]{2,3})-(\d{2})/;

// FR ID 형식이 비표준이라 자동 추출이 안 되는 완료 이력 5건 (스펙 §2).
export const MANUAL_FR_OVERRIDE = {
  'fr-bl-d6-d7-backlog-sprint-done': 'FR-BL',
  'fr-pj-pr-2-r6-backfill-reseed-project-create-done': 'FR-PJ',
  'fr-pj-pr-3-list-query-settings-done': 'FR-PJ',
  'fr-pj-pr-4-archive-done': 'FR-PJ',
  'fr-pj-pr-5-project-crud-ui-done': 'FR-PJ',
};

export const CLASSIFY_PATTERNS = [
  [/(^|-)(e2e|msw|zod|react|vitest|playwright|jsx|jsdom|frontend|dialog|form-occ|mutation-setquerydata|tanstack|avatar|date-input|ui-pr)/, 'frontend'],
  [/(^|-)(jooq|transaction|exception|pg-|advisory|multipart|bearer|permitall|patch-merge|no-bump|audit|pgmq|interface-extension|profile-scoped|minio|enablewebmvc|mockk|negative-guard|best-effort|xss|issue-scope|hard-delete|auth-extraction|decorative-annotation|sibling-precedent|comment-backend|path-token|nginx|problemdetail|dev-seed|shared-dev-db|assembly-nonprod|authcontroller|issue-transition|workflow-validator|backend-)/, 'backend'],
  [/(^|-)(crossbc|cross-bc|shared-|resolver|permission|session-management|isomorphic|condition-eval|no-project|vite-preview|prod-assembly|ci-self-hosted|free-tier|use-time|custom-objectmapper|whoami|preseeded|new-bc|test-fake|nonprod-bean|no-backend-ci)/, 'cross-bc'],
  [/(^|-)(detekt|ktlint|migration|archunit|gradle|lint|kotlin-nested|module-first|identity-access-prod|concurrent-testcontainers|flaky|saml|oidc|flexmark|app-test|enum-add|plan-files|issue-tracking-transition|constructor-change|test-count|prod-dead)/, 'build'],
  [/(^|-)(bts-|worktree|parallel|subagent|multisession|memory-|checkpoint|dashboard|merge-|gh-pr|classify|verify-master|gstack|zsh|agent-tuning|harness|two-lists|discriminant|github-actions|runner-platform|debt-record|zero-measurement|feedback-|orchestrator|spec-stated|contrast-matrix|guard-handler|seal-|split-questions|mutation-|rule-reverse|verify-logic|jsx-comment|fr-scope-change|fr-sizing)/, 'workflow'],
];

export const AUTOGEN_HEADER =
  '<!-- 자동 생성 — 직접 수정 금지. 원본을 고치고 `node scripts/build-doc-index.mjs` 재실행 -->';
```

- [ ] **Step 2: 실패하는 테스트 작성**

`scripts/doc-index/parse-memory.test.mjs`

```javascript
// 메모리 frontmatter 파서 + 기존 MEMORY.md 승계 파서 단위 테스트
// 실행. node --test scripts/doc-index/parse-memory.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseFrontmatter, parseLegacyIndex } from './parse-memory.mjs';

test('parseFrontmatter — name·description·type 추출', () => {
  const md = `---
name: zod-v4-uuid-fixture-strictness
description: Zod 4.x uuid()는 RFC4122 니블까지 검증
metadata: 
  node_type: memory
  type: reference
---

본문이다.`;
  const fm = parseFrontmatter(md);
  assert.equal(fm.name, 'zod-v4-uuid-fixture-strictness');
  assert.equal(fm.description, 'Zod 4.x uuid()는 RFC4122 니블까지 검증');
  assert.equal(fm.type, 'reference');
});

test('parseFrontmatter — 따옴표로 감싼 description 의 따옴표를 벗긴다', () => {
  const md = `---
name: a
description: "FR-UX-09 B1 ✅#328. 정본 3건 전복 · G1 중복알림"
metadata: 
  type: project
---
본문`;
  assert.equal(
    parseFrontmatter(md).description,
    'FR-UX-09 B1 ✅#328. 정본 3건 전복 · G1 중복알림',
  );
});

test('parseFrontmatter — hook·priority·category 를 읽는다 (backfill 후 형태)', () => {
  const md = `---
name: two-lists-never-check-each-other
description: 긴 설명
metadata: 
  type: project
  hook: 지배 결함 양식. 처방=차집합 판별식+CI
  priority: critical
  category: workflow
---
본문`;
  const fm = parseFrontmatter(md);
  assert.equal(fm.hook, '지배 결함 양식. 처방=차집합 판별식+CI');
  assert.equal(fm.priority, 'critical');
  assert.equal(fm.category, 'workflow');
});

test('parseFrontmatter — frontmatter 가 없으면 null', () => {
  assert.equal(parseFrontmatter('# 그냥 마크다운'), null);
});

test('parseLegacyIndex — ## 그룹을 카테고리로 승계하고 ★ 를 잡아낸다', () => {
  const md = `## 워크플로우 / 도구 / 커뮤니케이션

- [**★두 목록이 서로를 안 본다**](two-lists-never-check-each-other.md) — 지배 결함 양식. 처방=차집합
- [BTS naming](bts-naming.md) · [BTS workflow](bts-workflow.md)

## 백엔드 (jOOQ / tx / 예외)

- [PG NULL 멱등성](pg-null-distinct-on-conflict-idempotency.md)
`;
  const idx = parseLegacyIndex(md);
  assert.equal(idx.get('two-lists-never-check-each-other').category, 'workflow');
  assert.equal(idx.get('two-lists-never-check-each-other').star, true);
  // 한 줄에 링크가 여러 개인 묶음 행도 전부 잡아야 한다
  assert.equal(idx.get('bts-naming').category, 'workflow');
  assert.equal(idx.get('bts-workflow').category, 'workflow');
  assert.equal(idx.get('bts-naming').star, false);
  assert.equal(idx.get('pg-null-distinct-on-conflict-idempotency').category, 'backend');
});

test('parseLegacyIndex — hook 문구(— 뒤)를 추출한다', () => {
  const md = `## 백엔드 (jOOQ / tx / 예외)

- [**★SYSTEM_ADMIN 시드**](dev-seed-system-admin-triggers-mfa-gate.md) — 로그인은 200, 그다음이 막힘
`;
  const e = parseLegacyIndex(md).get('dev-seed-system-admin-triggers-mfa-gate');
  assert.equal(e.hook, '로그인은 200, 그다음이 막힘');
});

test('parseLegacyIndex — 취소선(~~) 항목도 등록은 한다 (낡음 표시일 뿐)', () => {
  const md = `## cross-BC / 권한 / shared-kernel

- ~~[프로젝트 생성 미구현](no-project-creation-feature-issue-needs-5-layer-seed.md)~~ **낡음**
`;
  const idx = parseLegacyIndex(md);
  assert.ok(idx.has('no-project-creation-feature-issue-needs-5-layer-seed'));
});
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `node --test scripts/doc-index/parse-memory.test.mjs`
Expected: FAIL — `Cannot find module './parse-memory.mjs'`

- [ ] **Step 4: 파서 구현**

`scripts/doc-index/parse-memory.mjs`

```javascript
// 메모리 파일의 frontmatter 를 읽고, 기존 MEMORY.md 에서 손 큐레이션(그룹·★·hook)을 승계 추출한다
import { LEGACY_GROUP_MAP } from './config.mjs';

const FM_KEYS = ['hook', 'priority', 'category', 'type'];

/** `---` 로 감싼 frontmatter 를 평평한 객체로 읽는다. 없으면 null. */
export function parseFrontmatter(content) {
  const m = content.match(/^---\n([\s\S]*?)\n---/);
  if (!m) return null;
  const out = {};
  for (const line of m[1].split('\n')) {
    const kv = line.match(/^\s*([a-zA-Z_]+):\s*(.*)$/);
    if (!kv) continue;
    const key = kv[1];
    let val = kv[2].trim();
    // description 은 따옴표로 감싸는 경우가 있다
    if (val.startsWith('"') && val.endsWith('"') && val.length > 1) val = val.slice(1, -1);
    if (key === 'name' || key === 'description' || FM_KEYS.includes(key)) {
      if (out[key] === undefined) out[key] = val;
    }
  }
  return out;
}

/**
 * 기존 MEMORY.md 를 파싱해 slug → {category, star, hook} 를 만든다.
 * 손 큐레이션을 버리지 않고 frontmatter 로 이주시키기 위한 seed 다.
 */
export function parseLegacyIndex(content) {
  const out = new Map();
  let cur = null;
  for (const rawLine of content.split('\n')) {
    const h = rawLine.match(/^##\s+(.*)$/);
    if (h) {
      cur = null;
      for (const [k, v] of Object.entries(LEGACY_GROUP_MAP)) {
        if (h[1].startsWith(k)) { cur = v; break; }
      }
      continue;
    }
    if (!cur) continue;
    // 줄 전체의 hook 은 마지막 `—` 뒤. 링크가 여러 개인 묶음 행에서는 hook 을 쓰지 않는다.
    const links = [...rawLine.matchAll(/\[([^\]]*)\]\(([a-z0-9-]+)\.md\)/g)];
    const dashIdx = rawLine.indexOf(' — ');
    const lineHook =
      links.length === 1 && dashIdx > -1 ? rawLine.slice(dashIdx + 3).trim() : '';
    for (const m of links) {
      const label = m[1];
      const slug = m[2];
      if (out.has(slug)) continue;
      out.set(slug, {
        category: cur,
        star: label.includes('★') || (links.length === 1 && rawLine.includes('★')),
        hook: lineHook,
        label: label.replace(/\*\*/g, '').replace(/★/g, '').trim(),
      });
    }
  }
  return out;
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `node --test scripts/doc-index/parse-memory.test.mjs`
Expected: `# pass 7` · `# fail 0`

- [ ] **Step 6: package.json 에 테스트 스크립트 추가**

`package.json`의 `scripts`에 두 줄을 넣는다 (기존 `test:workflow` 아래).

```json
    "test:doc-index": "node --test scripts/doc-index/*.test.mjs",
    "build:doc-index": "node scripts/build-doc-index.mjs",
```

- [ ] **Step 7: 커밋**

```bash
git add scripts/doc-index/config.mjs scripts/doc-index/parse-memory.mjs \
        scripts/doc-index/parse-memory.test.mjs package.json
git commit -m "feat(doc-index): 메모리 frontmatter 파서 + MEMORY.md 승계 파서"
```

---

## Task 2: 4단계 분류기

**Files:**
- Create: `scripts/doc-index/classify.mjs`
- Test: `scripts/doc-index/classify.test.mjs`

- [ ] **Step 1: 실패하는 테스트 작성**

`scripts/doc-index/classify.test.mjs`

```javascript
// 메모리 4단계 분류기 단위 테스트 — 오버라이드 → 승계 → 패턴 → uncategorized
// 실행. node --test scripts/doc-index/classify.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classify, splitFrHistory } from './classify.mjs';

test('classify — 승계(legacy)가 패턴보다 우선한다', () => {
  // 파일명 패턴으로는 workflow 지만, 사람이 backend 로 분류해 두었다
  const legacy = new Map([['bts-naming', { category: 'backend', star: false, hook: '' }]]);
  assert.equal(classify('bts-naming', legacy), 'backend');
});

test('classify — 승계에 없으면 패턴 폴백', () => {
  assert.equal(classify('msw-dual-handler-e2e-shadow', new Map()), 'frontend');
  assert.equal(classify('jooq-init-codegen-mirror', new Map()), 'backend');
  assert.equal(classify('detekt-baseline-module-pattern', new Map()), 'build');
  assert.equal(classify('crossbc-resolver-nullable-fail-open', new Map()), 'cross-bc');
  assert.equal(classify('worktree-node-modules-partial-install', new Map()), 'workflow');
});

test('classify — 어디에도 안 걸리면 uncategorized (추측하지 않는다)', () => {
  assert.equal(classify('완전히-처음-보는-무언가', new Map()), 'uncategorized');
});

test('classify — 승계 category 가 fr-done 이면 카테고리로 인정하지 않는다', () => {
  // fr-done 은 FR 축 인덱스로 가므로 카테고리가 될 수 없다. 패턴으로 다시 판정한다.
  const legacy = new Map([['msw-x-y', { category: 'fr-done', star: false, hook: '' }]]);
  assert.equal(classify('msw-x-y', legacy), 'frontend');
});

test('splitFrHistory — FR 번호가 있고 승계가 없으면 FR 축으로 보낸다', () => {
  const { frHistory, categorized } = splitFrHistory(
    ['fr-co-01-comment-create-list-done', 'msw-dual-handler-e2e-shadow'],
    new Map(),
  );
  assert.deepEqual(frHistory.map(x => x.slug), ['fr-co-01-comment-create-list-done']);
  assert.equal(frHistory[0].frId, 'FR-CO-01');
  assert.deepEqual(categorized, ['msw-dual-handler-e2e-shadow']);
});

test('splitFrHistory — /^fr-/ 로 넓히면 안 되는 반례 2건은 카테고리에 남는다', () => {
  const { frHistory, categorized } = splitFrHistory(
    ['fr-scope-change-full-sync-rule', 'fr-sizing-d-stage-must-be-completion-unit'],
    new Map(),
  );
  assert.deepEqual(frHistory, []);
  assert.equal(categorized.length, 2);
});

test('splitFrHistory — 승계가 fr-done 이 아닌 그룹이면 카테고리 유지 (규칙 메모리 보호)', () => {
  const legacy = new Map([
    ['fr-admin-slack-route-guard-matrix-done', { category: 'workflow', star: false, hook: '' }],
  ]);
  const { frHistory, categorized } = splitFrHistory(
    ['fr-admin-slack-route-guard-matrix-done'],
    legacy,
  );
  assert.deepEqual(frHistory, []);
  assert.deepEqual(categorized, ['fr-admin-slack-route-guard-matrix-done']);
});

test('splitFrHistory — 수동 오버라이드 5건은 FR 축으로 간다', () => {
  const { frHistory } = splitFrHistory(['fr-pj-pr-4-archive-done'], new Map());
  assert.equal(frHistory.length, 1);
  assert.equal(frHistory[0].frId, 'FR-PJ');
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `node --test scripts/doc-index/classify.test.mjs`
Expected: FAIL — `Cannot find module './classify.mjs'`

- [ ] **Step 3: 분류기 구현**

`scripts/doc-index/classify.mjs`

```javascript
// 메모리를 FR 완료 이력과 카테고리로 가르고, 카테고리는 4단계 우선순위로 판정한다
import { CLASSIFY_PATTERNS, FR_ID_RE, MANUAL_FR_OVERRIDE, CATEGORIES } from './config.mjs';

const VALID = new Set(CATEGORIES.map(c => c.key));

/**
 * 카테고리 판정. 승계 → 패턴 → uncategorized.
 * 승계 값이 fr-done 이면 카테고리가 아니므로 패턴으로 내려간다.
 */
export function classify(slug, legacyIndex) {
  const legacy = legacyIndex.get(slug);
  if (legacy && VALID.has(legacy.category)) return legacy.category;
  for (const [re, cat] of CLASSIFY_PATTERNS) if (re.test(slug)) return cat;
  return 'uncategorized';
}

/**
 * FR 완료 이력(→ docs/INDEX-fr.md)과 카테고리 대상(→ memory/index/*.md)으로 가른다.
 * 사람이 fr-done 이 아닌 그룹에 명시 등록한 것은 규칙/허브 메모리이므로 카테고리에 남긴다.
 */
export function splitFrHistory(slugs, legacyIndex) {
  const frHistory = [];
  const categorized = [];
  for (const slug of slugs) {
    const override = MANUAL_FR_OVERRIDE[slug];
    const m = slug.match(FR_ID_RE);
    const legacy = legacyIndex.get(slug);
    const legacyClaimsCategory = legacy && VALID.has(legacy.category);
    if (!legacyClaimsCategory && (override || m)) {
      frHistory.push({
        slug,
        frId: override || `FR-${m[1].toUpperCase()}-${m[2]}`,
      });
    } else {
      categorized.push(slug);
    }
  }
  return { frHistory, categorized };
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `node --test scripts/doc-index/classify.test.mjs`
Expected: `# pass 8` · `# fail 0`

- [ ] **Step 5: 실데이터로 분포 확인 (스펙 수치 재현)**

Run:
```bash
node -e "
import('./scripts/doc-index/classify.mjs').then(async ({classify, splitFrHistory}) => {
  const {parseLegacyIndex} = await import('./scripts/doc-index/parse-memory.mjs');
  const {MEMORY_DIR} = await import('./scripts/doc-index/config.mjs');
  const fs = await import('node:fs');
  const legacy = parseLegacyIndex(fs.readFileSync(MEMORY_DIR + '/MEMORY.md', 'utf8'));
  const slugs = fs.readdirSync(MEMORY_DIR).filter(f => f.endsWith('.md') && f !== 'MEMORY.md').map(f => f.replace(/\.md\$/, ''));
  const {frHistory, categorized} = splitFrHistory(slugs, legacy);
  const c = {};
  for (const s of categorized) { const k = classify(s, legacy); c[k] = (c[k]||0)+1; }
  console.log('FR축:', frHistory.length, '| 카테고리:', categorized.length);
  console.log(c);
});
"
```
Expected: `FR축: 179 | 카테고리: 199` 그리고
`{ workflow: 55, backend: 52, 'cross-bc': 33, frontend: 32, build: 27 }` (uncategorized 없음)

수치가 다르면 **멈추고 원인을 찾는다.** 다만 **스펙 초안의 시뮬레이션 값(174/204/5)을 기대값으로
삼지 않는다** — 그 값은 수동 오버라이드 적용 전이라 실제와 다르다 (스펙 §검토 이력 R3).
스펙이 적은 개수는 눈가리개가 될 수 있다. 실측이 정본이다.

- [ ] **Step 6: 커밋**

```bash
git add scripts/doc-index/classify.mjs scripts/doc-index/classify.test.mjs
git commit -m "feat(doc-index): 4단계 분류기 — 오버라이드/승계/패턴/미분류"
```

---

## Task 3: 메모리 인덱스 렌더 + 생성기 자가진단

**Files:**
- Create: `scripts/doc-index/render.mjs`
- Create: `scripts/build-doc-index.mjs`
- Test: `scripts/doc-index/render.test.mjs`

- [ ] **Step 1: 실패하는 테스트 작성**

`scripts/doc-index/render.test.mjs`

```javascript
// 인덱스 마크다운 렌더러 단위 테스트
// 실행. node --test scripts/doc-index/render.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderRouter, renderCategoryIndex, auditOrphans } from './render.mjs';

const ENTRIES = [
  { slug: 'a-crit', hook: '항상 이것', priority: 'critical', category: 'workflow', description: '긴 설명' },
  { slug: 'b-norm', hook: '', priority: 'normal', category: 'backend', description: '설명 B' },
];

test('renderRouter — 자동 생성 경고를 첫 줄에 넣는다', () => {
  const out = renderRouter(ENTRIES, { workflow: 1, backend: 1 }, 0);
  assert.ok(out.startsWith('<!-- 자동 생성 — 직접 수정 금지'));
});

test('renderRouter — critical 만 ★ 구획에 넣는다', () => {
  const out = renderRouter(ENTRIES, { workflow: 1, backend: 1 }, 0);
  const starBlock = out.split('## 상황별 인덱스')[0];
  assert.ok(starBlock.includes('a-crit'));
  assert.ok(!starBlock.includes('b-norm'));
});

test('renderRouter — 건수 0 인 카테고리 행은 만들지 않는다', () => {
  const out = renderRouter(ENTRIES, { workflow: 1, backend: 0 }, 0);
  assert.ok(out.includes('memory/index/workflow.md'));
  assert.ok(!out.includes('memory/index/backend.md'));
});

test('renderRouter — uncategorized 가 0 이면 그 행도 사라진다', () => {
  const out = renderRouter(ENTRIES, { workflow: 1 }, 0);
  assert.ok(!out.includes('uncategorized'));
});

test('renderCategoryIndex — hook 이 있으면 hook, 없으면 description 을 쓴다', () => {
  const out = renderCategoryIndex('backend', [
    { slug: 'x', hook: '짧은 훅', description: '긴 설명' },
    { slug: 'y', hook: '', description: '설명만 있음' },
  ]);
  assert.ok(out.includes('짧은 훅'));
  assert.ok(!out.includes('긴 설명'));
  assert.ok(out.includes('설명만 있음'));
});

test('auditOrphans — 인덱스에 없는 실제 파일을 고아로 센다', () => {
  const r = auditOrphans(['a', 'b', 'c'], ['a', 'b']);
  assert.deepEqual(r.orphans, ['c']);
  assert.deepEqual(r.broken, []);
});

test('auditOrphans — 실제 없는데 인덱스에만 있으면 깨진 링크', () => {
  const r = auditOrphans(['a'], ['a', 'ghost']);
  assert.deepEqual(r.orphans, []);
  assert.deepEqual(r.broken, ['ghost']);
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `node --test scripts/doc-index/render.test.mjs`
Expected: FAIL — `Cannot find module './render.mjs'`

- [ ] **Step 3: 렌더러 구현**

`scripts/doc-index/render.mjs`

```javascript
// 인덱스 마크다운을 만든다 — 라우터(MEMORY.md) · 카테고리별 인덱스 · 고아/깨진링크 감사
import { AUTOGEN_HEADER, CATEGORIES } from './config.mjs';

/** hook 우선, 없으면 description. 인덱스 한 줄은 짧아야 한다. */
function oneLiner(e, max = 90) {
  const s = (e.hook && e.hook.trim()) || e.description || '';
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

export function renderRouter(entries, counts, frHistoryCount) {
  const crit = entries.filter(e => e.priority === 'critical');
  const lines = [AUTOGEN_HEADER, ''];

  lines.push(`## ★ 항상 지킬 것 (${crit.length}건)`, '');
  for (const e of crit) lines.push(`- ${oneLiner(e, 70)} [[${e.slug}]]`);
  lines.push('');

  lines.push('## 상황별 인덱스 — 필요한 것만 열어라', '');
  lines.push('| 지금 하는 일 | 열 파일 | 건수 |');
  lines.push('|---|---|---|');
  for (const c of CATEGORIES) {
    const n = counts[c.key] || 0;
    if (n === 0) continue;
    lines.push(`| ${c.label} | memory/index/${c.key}.md | ${n} |`);
  }
  if (frHistoryCount > 0) {
    lines.push(`| FR 완료 이력 | docs/INDEX-fr.md (memory 열) | ${frHistoryCount} |`);
  }
  lines.push('');
  return lines.join('\n');
}

export function renderCategoryIndex(categoryKey, entries) {
  const meta = CATEGORIES.find(c => c.key === categoryKey);
  const lines = [
    AUTOGEN_HEADER,
    '',
    `# ${meta ? meta.label : categoryKey} (${entries.length}건)`,
    '',
    '> 라우터. [MEMORY.md](../MEMORY.md)',
    '',
  ];
  for (const e of [...entries].sort((a, b) => a.slug.localeCompare(b.slug))) {
    const star = e.priority === 'critical' ? '★ ' : '';
    lines.push(`- ${star}[[${e.slug}]] — ${oneLiner(e)}`);
  }
  lines.push('');
  return lines.join('\n');
}

/** 양방향 차집합. 한 방향만 보면 봉인이 절반만 닫힌다. */
export function auditOrphans(actualSlugs, indexedSlugs) {
  const idx = new Set(indexedSlugs);
  const act = new Set(actualSlugs);
  return {
    orphans: actualSlugs.filter(s => !idx.has(s)),
    broken: indexedSlugs.filter(s => !act.has(s)),
  };
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `node --test scripts/doc-index/render.test.mjs`
Expected: `# pass 7` · `# fail 0`

- [ ] **Step 5: 진입점 구현 (자가진단 포함)**

`scripts/build-doc-index.mjs`

```javascript
// 메모리·docs 인덱스를 생성하는 단일 진입점. 저장소 밖(메모리)은 CI 가 못 지키므로 여기서 자가진단한다
// 실행. node scripts/build-doc-index.mjs

import fs from 'node:fs';
import path from 'node:path';
import { MEMORY_DIR, REPO_ROOT } from './doc-index/config.mjs';
import { parseFrontmatter, parseLegacyIndex } from './doc-index/parse-memory.mjs';
import { classify, splitFrHistory } from './doc-index/classify.mjs';
import { renderRouter, renderCategoryIndex, auditOrphans } from './doc-index/render.mjs';

function readMemory() {
  const files = fs.readdirSync(MEMORY_DIR).filter(f => f.endsWith('.md') && f !== 'MEMORY.md');
  return files.map(f => {
    const slug = f.replace(/\.md$/, '');
    const fm = parseFrontmatter(fs.readFileSync(path.join(MEMORY_DIR, f), 'utf8')) || {};
    return {
      slug,
      description: fm.description || '',
      hook: fm.hook || '',
      priority: fm.priority || 'normal',
      category: fm.category || '',
      type: fm.type || '',
    };
  });
}

function main() {
  const legacy = parseLegacyIndex(fs.readFileSync(path.join(MEMORY_DIR, 'MEMORY.md'), 'utf8'));
  const all = readMemory();
  const { frHistory, categorized } = splitFrHistory(all.map(e => e.slug), legacy);

  const bySlug = new Map(all.map(e => [e.slug, e]));
  const catOf = new Map();
  for (const slug of categorized) {
    const e = bySlug.get(slug);
    // frontmatter 가 이미 분류를 갖고 있으면 그것이 정본이다 (backfill 이후 경로)
    const cat = e.category || classify(slug, legacy);
    catOf.set(slug, cat);
    e.priority = e.priority === 'critical' || legacy.get(slug)?.star ? 'critical' : 'normal';
    if (!e.hook) e.hook = legacy.get(slug)?.hook || '';
  }

  const counts = {};
  for (const cat of catOf.values()) counts[cat] = (counts[cat] || 0) + 1;

  // --- 쓰기 ---
  const indexDir = path.join(MEMORY_DIR, 'index');
  fs.mkdirSync(indexDir, { recursive: true });
  const routerEntries = categorized.map(s => bySlug.get(s));
  fs.writeFileSync(
    path.join(MEMORY_DIR, 'MEMORY.md'),
    renderRouter(routerEntries, counts, frHistory.length),
  );
  for (const cat of Object.keys(counts)) {
    const entries = categorized.filter(s => catOf.get(s) === cat).map(s => bySlug.get(s));
    fs.writeFileSync(path.join(indexDir, `${cat}.md`), renderCategoryIndex(cat, entries));
  }

  // --- 자가진단. CI 가 못 보는 층이므로 여기서 막는다 ---
  const indexed = [...categorized, ...frHistory.map(f => f.slug)];
  const { orphans, broken } = auditOrphans(all.map(e => e.slug), indexed);
  const uncat = counts.uncategorized || 0;

  console.log(`→ 메모리 ${all.length}건 = FR축 ${frHistory.length} + 카테고리 ${categorized.length}`);
  console.log(`→ 분포. ${JSON.stringify(counts)}`);

  let bad = false;
  if (orphans.length) { console.error(`FAIL. 고아 ${orphans.length}건.`, orphans.slice(0, 10)); bad = true; }
  if (broken.length)  { console.error(`FAIL. 깨진 링크 ${broken.length}건.`, broken.slice(0, 10)); bad = true; }
  if (uncat > 0)      { console.warn(`WARN. uncategorized ${uncat}건 — 눈으로 확인해 분류할 것.`); }
  if (bad) process.exit(1);
  console.log('PASS. 고아 0 · 깨진 링크 0.');
}

main();
```

- [ ] **Step 6: 첫 실행 — 고아 0 확인**

Run: `node scripts/build-doc-index.mjs`
Expected:
```
→ 메모리 378건 = FR축 179 + 카테고리 199
→ 분포. {"workflow":55,"backend":52,"cross-bc":33,"frontend":32,"build":27}
PASS. 고아 0 · 깨진 링크 0.
```

`MEMORY.md`가 덮어써진다. **이전 내용은 `git`이 추적하지 않으므로**, 실행 전에 반드시 백업한다.

```bash
cp ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/MEMORY.md \
   /private/tmp/claude-501/-Users-maxi-moff-Projects-BTS/fe6d0c0a-8ff1-4f46-a617-591840c56a2d/scratchpad/MEMORY.md.bak
```

- [ ] **Step 7: 커밋**

```bash
git add scripts/doc-index/render.mjs scripts/doc-index/render.test.mjs scripts/build-doc-index.mjs
git commit -m "feat(doc-index): 메모리 라우터·카테고리 인덱스 렌더 + 자가진단"
```

---

## Task 4: frontmatter backfill (일회성) + 미분류 5건 손 정리

**Files:**
- Create: `scripts/doc-index/backfill.mjs`
- Modify: 메모리 378개 파일의 frontmatter (저장소 밖)

backfill(역채움)은 **한 번만 도는 마이그레이션**이다. 이후로는 frontmatter가 정본이 되어
기존 `MEMORY.md` 파싱에 더 이상 의존하지 않는다.

- [ ] **Step 1: backfill 스크립트 작성**

`scripts/doc-index/backfill.mjs`

```javascript
// 일회성 마이그레이션 — 기존 MEMORY.md 의 손 큐레이션(★·hook·그룹)을 각 메모리 frontmatter 로 이주
// 실행. node scripts/doc-index/backfill.mjs [--dry]

import fs from 'node:fs';
import path from 'node:path';
import { MEMORY_DIR } from './config.mjs';
import { parseLegacyIndex } from './parse-memory.mjs';
import { classify, splitFrHistory } from './classify.mjs';

const DRY = process.argv.includes('--dry');
const legacy = parseLegacyIndex(fs.readFileSync(path.join(MEMORY_DIR, 'MEMORY.md'), 'utf8'));
const files = fs.readdirSync(MEMORY_DIR).filter(f => f.endsWith('.md') && f !== 'MEMORY.md');
const slugs = files.map(f => f.replace(/\.md$/, ''));
const { frHistory } = splitFrHistory(slugs, legacy);
const frSet = new Set(frHistory.map(f => f.slug));

let changed = 0;
for (const f of files) {
  const slug = f.replace(/\.md$/, '');
  const p = path.join(MEMORY_DIR, f);
  const src = fs.readFileSync(p, 'utf8');
  const m = src.match(/^---\n([\s\S]*?)\n---/);
  if (!m) { console.warn('SKIP (frontmatter 없음).', slug); continue; }
  if (/^\s*(hook|priority|category):/m.test(m[1])) continue; // 이미 이주됨 — 멱등

  const leg = legacy.get(slug);
  const category = frSet.has(slug) ? 'fr-history' : classify(slug, legacy);
  const priority = leg?.star ? 'critical' : 'normal';
  const hook = (leg?.hook || '').replace(/\n/g, ' ').trim();

  // metadata: 블록 끝에 3줄을 덧붙인다. 들여쓰기 2칸은 기존 형식과 같다.
  const add = [
    hook ? `  hook: ${JSON.stringify(hook)}` : null,
    `  priority: ${priority}`,
    `  category: ${category}`,
  ].filter(Boolean).join('\n');
  const out = src.replace(/^---\n([\s\S]*?)\n---/, `---\n$1\n${add}\n---`);

  if (DRY) console.log(`${slug} → category=${category} priority=${priority} hook=${hook ? 'Y' : '-'}`);
  else fs.writeFileSync(p, out);
  changed++;
}
console.log(`${DRY ? '[dry] ' : ''}${changed}건 처리.`);
```

- [ ] **Step 2: dry-run 으로 전수 확인**

Run: `node scripts/doc-index/backfill.mjs --dry | head -40`
Expected: `category=` 값이 `workflow|backend|cross-bc|frontend|build|fr-history|uncategorized` 중 하나.
`SKIP` 이 나오면 그 파일을 눈으로 확인한다.

Run: `node scripts/doc-index/backfill.mjs --dry | grep -c 'priority=critical'`
Expected: `34` (실측. 스펙 초안의 45는 `★` 를 줄 전체로 오판정한 값이다 — §검토 이력 R3)

- [ ] **Step 3: 실제 실행**

Run: `node scripts/doc-index/backfill.mjs`
Expected: `378건 처리.` (SKIP 건수만큼 적을 수 있음)

- [ ] **Step 4: 멱등성 확인 — 두 번 돌려도 안 바뀐다**

Run: `node scripts/doc-index/backfill.mjs`
Expected: `0건 처리.`

이미 `hook|priority|category` 가 있으면 건너뛰므로 두 번째 실행은 아무것도 하지 않는다.
`0건`이 아니면 멱등 가드가 깨진 것이다 — 멈추고 원인을 찾는다.

- [ ] **Step 5: 미분류 5건 손 정리**

`uncategorized` 5건은 전부 FR ID 형식이 비표준인 완료 이력이다. `config.mjs`의
`MANUAL_FR_OVERRIDE`에 이미 등록되어 있으므로 backfill 후 `category: fr-history`가 되어야 한다.

Run: `grep -l 'category: uncategorized' ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/*.md`
Expected: **출력 없음**

출력이 있으면 그 파일을 열어 내용을 읽고 `category:` 를 손으로 알맞게 고친다. 추측하지 말고
본문을 읽는다.

- [ ] **Step 6: 재생성 후 자가진단 통과 확인**

Run: `node scripts/build-doc-index.mjs`
Expected: `PASS. 고아 0 · 깨진 링크 0.` 그리고 `WARN` 없음

- [ ] **Step 7: 커밋 (스크립트만 — 메모리는 저장소 밖이라 커밋 대상 아님)**

```bash
git add scripts/doc-index/backfill.mjs
git commit -m "chore(doc-index): 손 큐레이션 frontmatter 이주 일회성 스크립트"
```

---

## Task 5: docs 스캐너 + 인덱스 3종

**Files:**
- Create: `scripts/doc-index/scan-docs.mjs`
- Test: `scripts/doc-index/scan-docs.test.mjs`
- Modify: `scripts/doc-index/render.mjs` (FR축·시간축 렌더 추가)
- Modify: `scripts/build-doc-index.mjs` (docs 단계 추가)

- [ ] **Step 1: 실패하는 테스트 작성**

`scripts/doc-index/scan-docs.test.mjs`

```javascript
// docs 스캐너 단위 테스트 — 파일명·H1·FR ID 추출
// 실행. node --test scripts/doc-index/scan-docs.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseDocMeta, groupByFr, groupByDate } from './scan-docs.mjs';

test('parseDocMeta — 파일명에서 날짜와 slug 를 가른다', () => {
  const d = parseDocMeta('2026-07-31-fr-ux-09-b1-create-issue-fields.md', '# 제목\n\n본문');
  assert.equal(d.date, '2026-07-31');
  assert.equal(d.slug, 'fr-ux-09-b1-create-issue-fields');
});

test('parseDocMeta — H1 을 제목으로 쓴다', () => {
  const d = parseDocMeta('2026-01-01-x.md', '<!-- 주석 -->\n\n# 진짜 제목\n\n본문');
  assert.equal(d.title, '진짜 제목');
});

test('parseDocMeta — 본문의 FR ID 를 중복 없이 모은다', () => {
  const d = parseDocMeta('2026-01-01-x.md', '# T\n\nFR-UX-09 와 FR-CO-01, 그리고 FR-UX-09 재언급');
  assert.deepEqual(d.frIds, ['FR-CO-01', 'FR-UX-09']);
});

test('parseDocMeta — 날짜 접두가 없으면 date 는 빈 문자열', () => {
  assert.equal(parseDocMeta('README.md', '# R').date, '');
});

test('groupByFr — 같은 FR 의 spec·plan 을 한 행으로 묶는다', () => {
  const rows = groupByFr([
    { kind: 'specs', date: '2026-01-01', slug: 'a', frIds: ['FR-CO-01'], file: 'docs/specs/2026-01-01-a.md' },
    { kind: 'plans', date: '2026-01-01', slug: 'a', frIds: ['FR-CO-01'], file: 'docs/plans/2026-01-01-a.md' },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].frId, 'FR-CO-01');
  assert.equal(rows[0].specs.length, 1);
  assert.equal(rows[0].plans.length, 1);
});

test('groupByFr — FR 이 없는 문서는 어느 행에도 들어가지 않는다', () => {
  const rows = groupByFr([{ kind: 'plans', date: '2026-01-01', slug: 'x', frIds: [], file: 'f' }]);
  assert.deepEqual(rows, []);
});

test('groupByDate — 같은 slug 의 spec·plan 을 한 행으로, 날짜 내림차순', () => {
  const rows = groupByDate([
    { kind: 'specs', date: '2026-01-01', slug: 'a', frIds: [], file: 'f1' },
    { kind: 'plans', date: '2026-01-01', slug: 'a', frIds: [], file: 'f2' },
    { kind: 'plans', date: '2026-02-01', slug: 'b', frIds: [], file: 'f3' },
  ]);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].date, '2026-02-01'); // 최신이 위
  assert.equal(rows[1].kinds.specs, true);
  assert.equal(rows[1].kinds.plans, true);
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `node --test scripts/doc-index/scan-docs.test.mjs`
Expected: FAIL — `Cannot find module './scan-docs.mjs'`

- [ ] **Step 3: 스캐너 구현**

`scripts/doc-index/scan-docs.mjs`

```javascript
// docs/ 문서에서 파일명(날짜·slug) · H1 제목 · 본문 FR ID 를 뽑아 FR축/시간축으로 묶는다
const FILENAME_RE = /^(\d{4}-\d{2}-\d{2})-(.+)\.md$/;
const FR_IN_BODY_RE = /FR-[A-Z]{2,3}-\d{2}/g;

export function parseDocMeta(filename, content) {
  const m = filename.match(FILENAME_RE);
  const h1 = content.match(/^#\s+(.+)$/m);
  return {
    date: m ? m[1] : '',
    slug: m ? m[2] : filename.replace(/\.md$/, ''),
    title: h1 ? h1[1].trim() : '',
    frIds: [...new Set(content.match(FR_IN_BODY_RE) || [])].sort(),
  };
}

export function groupByFr(docs) {
  const byFr = new Map();
  for (const d of docs) {
    for (const fr of d.frIds) {
      if (!byFr.has(fr)) byFr.set(fr, { frId: fr, specs: [], plans: [], decisions: [], adr: [] });
      const row = byFr.get(fr);
      if (row[d.kind]) row[d.kind].push(d);
    }
  }
  return [...byFr.values()].sort((a, b) => a.frId.localeCompare(b.frId));
}

export function groupByDate(docs) {
  const bySlug = new Map();
  for (const d of docs) {
    const key = `${d.date}/${d.slug}`;
    if (!bySlug.has(key)) {
      bySlug.set(key, { date: d.date, slug: d.slug, title: d.title, kinds: {}, frIds: new Set() });
    }
    const row = bySlug.get(key);
    row.kinds[d.kind] = true;
    if (!row.title && d.title) row.title = d.title;
    for (const fr of d.frIds) row.frIds.add(fr);
  }
  return [...bySlug.values()].sort((a, b) =>
    a.date === b.date ? a.slug.localeCompare(b.slug) : b.date.localeCompare(a.date),
  );
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `node --test scripts/doc-index/scan-docs.test.mjs`
Expected: `# pass 7` · `# fail 0`

- [ ] **Step 5: FR축·시간축 렌더 추가**

`scripts/doc-index/render.mjs` 끝에 덧붙인다.

```javascript
function link(d) {
  return `[${d.date}](${'/' + d.file})`;
}

/** FR 축 인덱스. memory 열이 178개 고아 문제를 실제로 푸는 지점이다. */
export function renderFrIndex(rows, memoryByFr) {
  const lines = [
    AUTOGEN_HEADER, '',
    '# FR 축 인덱스', '',
    '> 이 FR 을 작업할 때 읽을 문서. 라우터. [INDEX.md](INDEX.md)', '',
    '| FR | spec | plan | decision/adr | memory |',
    '|---|---|---|---|---|',
  ];
  for (const r of rows) {
    const mem = (memoryByFr.get(r.frId) || []).map(s => `[[${s}]]`).join(' ') || '—';
    const cell = arr => (arr.length ? arr.map(link).join(' ') : '—');
    lines.push(
      `| ${r.frId} | ${cell(r.specs)} | ${cell(r.plans)} | ` +
      `${cell([...r.decisions, ...r.adr])} | ${mem} |`,
    );
  }
  lines.push('');
  return lines.join('\n');
}

/** 시간 축 인덱스. FR 없는 문서를 받아내는 그물이다. */
export function renderRecentIndex(rows) {
  const lines = [
    AUTOGEN_HEADER, '',
    '# 시간 축 인덱스 (최신순)', '',
    '> 라우터. [INDEX.md](INDEX.md)', '',
    '| 날짜 | slug | spec | plan | decision | adr | FR |',
    '|---|---|---|---|---|---|---|',
  ];
  for (const r of rows) {
    const k = n => (r.kinds[n] ? '✔' : '—');
    const fr = [...r.frIds].sort().join(' ') || '—';
    lines.push(`| ${r.date} | ${r.slug} | ${k('specs')} | ${k('plans')} | ${k('decisions')} | ${k('adr')} | ${fr} |`);
  }
  lines.push('');
  return lines.join('\n');
}

export function renderDocsRouter(stats) {
  return [
    AUTOGEN_HEADER, '',
    '# docs 인덱스 — 무엇을 찾느냐에 따라', '',
    '| 찾는 것 | 열 파일 |',
    '|---|---|',
    '| 이 FR 을 작업할 때 읽을 문서 | [INDEX-fr.md](INDEX-fr.md) |',
    '| 최근에 무슨 작업을 했나 | [INDEX-recent.md](INDEX-recent.md) |',
    '| FR 목록·진척 | [plan/README.md](plan/README.md) · [plan/fr-index.md](plan/fr-index.md) |',
    '| 설계 26개 챕터 | [sdd/README.md](sdd/README.md) |',
    '',
    `> 집계. spec ${stats.specs} · plan ${stats.plans} · decision ${stats.decisions} · adr ${stats.adr}`,
    '',
  ].join('\n');
}
```

- [ ] **Step 6: 진입점에 docs 단계 추가**

`scripts/build-doc-index.mjs`의 `main()` 안, 메모리 자가진단 **앞**에 넣는다.
`import` 문도 함께 갱신한다 (`SOURCES`, `REPO_ROOT`, `parseDocMeta`, `groupByFr`, `groupByDate`,
`renderFrIndex`, `renderRecentIndex`, `renderDocsRouter`).

```javascript
  // --- docs 인덱스 ---
  const docs = [];
  const stats = {};
  for (const src of SOURCES) {
    const dir = path.join(REPO_ROOT, src.dir);
    const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter(f => f.endsWith('.md')) : [];
    stats[src.key] = files.length;
    for (const f of files) {
      const meta = parseDocMeta(f, fs.readFileSync(path.join(dir, f), 'utf8'));
      docs.push({ ...meta, kind: src.key, file: `${src.dir}/${f}` });
    }
  }
  const memoryByFr = new Map();
  for (const { slug, frId } of frHistory) {
    if (!memoryByFr.has(frId)) memoryByFr.set(frId, []);
    memoryByFr.get(frId).push(slug);
  }
  const frBody = renderFrIndex(groupByFr(docs), memoryByFr);
  const recentBody = renderRecentIndex(groupByDate(docs));
  fs.writeFileSync(path.join(REPO_ROOT, 'docs/INDEX.md'), renderDocsRouter(stats));
  fs.writeFileSync(path.join(REPO_ROOT, 'docs/INDEX-fr.md'), frBody);
  fs.writeFileSync(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), recentBody);

  // docs 고아 — 렌더 **결과 문자열**에 실제로 나타나는지로 판정한다.
  //
  // ★ 여기서 `docs.map(d => d.file)` 같은 입력 배열로 검사하면 안 된다. 시간축은 입력 전량을
  //   받으므로 차집합이 정의상 항상 공집합이 되어, 렌더가 통째로 망가져도 "고아 0"이 뜬다.
  //   0 이 나오는 판별식은 먼저 판별식을 의심하라 — 렌더 산출물을 봐야 비-공허하다.
  const docOrphans = docs.filter(d => !recentBody.includes(`| ${d.slug} |`));
  console.log(`→ docs ${docs.length}건. 시간축 등록 ${docs.length - docOrphans.length} · 고아 ${docOrphans.length}`);
  if (docOrphans.length) {
    console.error('FAIL. docs 고아.', docOrphans.slice(0, 10).map(d => d.file));
    process.exit(1);
  }
```

**이 검사가 실제로 무언가를 잡는지 확인한다 (양성 대조군).** 검사를 짜고 나면
`renderRecentIndex` 안의 `for (const r of rows)` 를 잠깐 `rows.slice(1)` 로 바꿔 실행해 본다.
`고아 1` 이상이 나오고 `exit 1` 이면 비-공허하다. 확인 후 반드시 원복한다.

- [ ] **Step 7: 실행 — docs 인덱스 3종 생성 확인**

Run: `node scripts/build-doc-index.mjs`
Expected: `→ docs 716건. 시간축 등록 716 · 고아 0` 와 `PASS.`

Run: `wc -l docs/INDEX.md docs/INDEX-fr.md docs/INDEX-recent.md`
Expected: 세 파일 모두 존재하며 `INDEX-recent.md`가 720줄 내외

- [ ] **Step 8: 커밋**

```bash
git add scripts/doc-index/scan-docs.mjs scripts/doc-index/scan-docs.test.mjs \
        scripts/doc-index/render.mjs scripts/build-doc-index.mjs \
        docs/INDEX.md docs/INDEX-fr.md docs/INDEX-recent.md
git commit -m "feat(doc-index): docs 스캐너 + FR축·시간축 인덱스 3종"
```

---

## Task 6: 판별식 7번째 + CI 배선 (룰 I·J·K·L)

**Files:**
- Create: `scripts/workflow/doc-index-coverage.test.ts`
- Modify: `.github/workflows/workflow-scripts-ci.yml` (paths 양쪽 블록 + 헤더 표)

- [ ] **Step 1: 판별식 작성**

`scripts/workflow/doc-index-coverage.test.ts`

```typescript
// 문서 인덱스 판별식 — 재생성 diff(I) · 고아(J) · 깨진 링크(K) · SOURCES⟺CI paths(L)
// 실행. node --test scripts/workflow/doc-index-coverage.test.ts

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const CI_FILE = path.join(REPO_ROOT, '.github/workflows/workflow-scripts-ci.yml');
const INDEX_FILES = ['docs/INDEX.md', 'docs/INDEX-fr.md', 'docs/INDEX-recent.md'];

test('룰 I — 인덱스를 재생성해도 git diff 가 클린이다', () => {
  execFileSync('node', ['scripts/build-doc-index.mjs'], { cwd: REPO_ROOT, stdio: 'pipe' });
  const diff = execFileSync('git', ['diff', '--name-only', '--', ...INDEX_FILES], {
    cwd: REPO_ROOT, encoding: 'utf8',
  }).trim();
  assert.equal(
    diff, '',
    `인덱스 drift. 재생성 결과가 커밋본과 다르다:\n${diff}\n→ node scripts/build-doc-index.mjs 후 커밋할 것.`,
  );
});

test('룰 J — docs 문서 전량이 시간축 인덱스에 등록되어 있다', () => {
  const recent = fs.readFileSync(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), 'utf8');
  const orphans: string[] = [];
  for (const dir of ['docs/plans', 'docs/specs', 'docs/decisions', 'docs/adr']) {
    for (const f of fs.readdirSync(path.join(REPO_ROOT, dir)).filter(x => x.endsWith('.md'))) {
      const slug = f.replace(/^\d{4}-\d{2}-\d{2}-/, '').replace(/\.md$/, '');
      if (!recent.includes(`| ${slug} |`)) orphans.push(`${dir}/${f}`);
    }
  }
  assert.deepEqual(orphans, [], `인덱스 미등록 문서 ${orphans.length}건`);
});

test('룰 K — 인덱스가 가리키는 파일이 전부 실재한다', () => {
  const broken: string[] = [];
  for (const idx of INDEX_FILES) {
    const body = fs.readFileSync(path.join(REPO_ROOT, idx), 'utf8');
    for (const m of body.matchAll(/\]\(\/(docs\/[^)]+\.md)\)/g)) {
      if (!fs.existsSync(path.join(REPO_ROOT, m[1]))) broken.push(`${idx} → ${m[1]}`);
    }
  }
  assert.deepEqual(broken, [], `깨진 링크 ${broken.length}건`);
});

test('룰 L — 생성기 SOURCES 가 전부 CI 트리거 paths 에 있다 (pull_request·push 양쪽)', async () => {
  const { SOURCES } = await import('../doc-index/config.mjs');
  const ci = fs.readFileSync(CI_FILE, 'utf8');
  // pull_request 블록과 push 블록을 갈라 각각 검사한다. 한쪽만 걸면 절반 봉인이다.
  const prBlock = ci.slice(ci.indexOf('pull_request:'), ci.indexOf('push:'));
  const pushBlock = ci.slice(ci.indexOf('push:'), ci.indexOf('concurrency:'));
  const missing: string[] = [];
  for (const src of SOURCES.filter((s: { repoRelative: boolean }) => s.repoRelative)) {
    for (const [name, block] of [['pull_request', prBlock], ['push', pushBlock]] as const) {
      const covered =
        block.includes(`'${src.dir}/**'`) ||
        block.includes(`'docs/**'`);
      if (!covered) missing.push(`${src.dir} (${name})`);
    }
  }
  assert.deepEqual(
    missing, [],
    `CI 트리거 미포함 ${missing.length}건 — 이 입력만 바꾸는 PR 에서 판별식이 0회 실행된다`,
  );
});
```

- [ ] **Step 2: 판별식 실행 — 룰 L 실패 확인**

Run: `node --test scripts/workflow/doc-index-coverage.test.ts`
Expected: 룰 L **FAIL** — `docs/plans (pull_request)` 등 8건.
현재 CI `paths`에 `docs/**`가 없기 때문이다. 이것이 정상이다 — 판별식이 실제 갭을 잡았다.

- [ ] **Step 3: CI 트리거 경로 확장**

`.github/workflows/workflow-scripts-ci.yml`의 `pull_request`와 `push` **양쪽** `paths:` 블록에
같은 두 줄을 넣는다 (`TODOS.md` 위).

```yaml
      - 'docs/**'
      - 'scripts/doc-index/**'
```

같은 파일 헤더의 판별식 표에도 행을 추가한다.

```
# | doc-index-coverage             | docs/** · scripts/doc-index/**           | docs/** · scripts/doc-index/** |
```

- [ ] **Step 4: 판별식 재실행 — 4종 전부 통과**

Run: `node --test scripts/workflow/doc-index-coverage.test.ts`
Expected: `# pass 4` · `# fail 0`

- [ ] **Step 5: 기존 판별식 회귀 없음 확인**

Run: `pnpm test:workflow`
Expected: 기존 6종 + 신규 4개가 모두 통과. `# fail 0`

- [ ] **Step 6: 커밋**

```bash
git add scripts/workflow/doc-index-coverage.test.ts .github/workflows/workflow-scripts-ci.yml
git commit -m "test(doc-index): 판별식 7번째 — 재생성diff·고아·깨진링크·SOURCES⟺CI paths"
```

---

## Task 7: 비-공허 확인 (뮤테이션 5종)

판별식이 **실제로 무언가를 막는지** 확인한다. 위반을 주입하지 않은 판별식은 조용히 통과하는
장식일 수 있다 (메모리 `archunit-vacuous-rule-silent-pass`).

**전제.** 반드시 **커밋된 클린 상태**에서 시작한다. `git status --short`가 비어야 한다.
dirty 상태에서 주입하면 원복이 불완전해진다 (메모리 `mutation-test-requires-committed-baseline`).

- [ ] **Step 1: 클린 상태 확인**

Run: `git status --short`
Expected: 출력 없음

- [ ] **Step 2: 룰 I 주입 — 인덱스를 손으로 고친다**

```bash
echo "| 2099-12-31 | 가짜 | ✔ | — | — | — | — |" >> docs/INDEX-recent.md
node --test scripts/workflow/doc-index-coverage.test.ts 2>&1 | grep -E '^# (pass|fail)'
git checkout docs/INDEX-recent.md
```
Expected: 주입 시 `# fail 1` 이상 → 원복 후 재실행하면 `# fail 0`

- [ ] **Step 3: 룰 J 주입 — 인덱스에 없는 문서를 새로 만든다**

```bash
printf '# 판별식 주입 테스트\n' > docs/plans/2099-12-31-mutation-probe.md
node --test scripts/workflow/doc-index-coverage.test.ts 2>&1 | grep -E '^# (pass|fail)'
rm docs/plans/2099-12-31-mutation-probe.md
```
Expected: 주입 시 `# fail 1` 이상 (룰 I·J 둘 다 red) → 삭제 후 `# fail 0`

- [ ] **Step 4: 룰 J 역방향 주입 — 인덱스에서 한 줄을 지운다**

봉인은 기본적으로 절반만 닫힌다. 추가뿐 아니라 **삭제** 방향도 확인한다.

`INDEX-recent.md`는 1행 주석 · 3행 H1 · 5행 라우터링크 · 7행 표헤더 · 8행 구분선이므로
**첫 데이터 행은 9행**이다. 8행(구분선)을 지우면 표만 깨지고 룰 J 가 안 잡힐 수 있다.

```bash
sed -n '9p' docs/INDEX-recent.md          # 지울 행이 데이터 행인지 눈으로 먼저 확인
sed -i '' '9d' docs/INDEX-recent.md
node --test scripts/workflow/doc-index-coverage.test.ts 2>&1 | grep -E '^# (pass|fail)'
git checkout docs/INDEX-recent.md
```
Expected: `sed -n '9p'` 가 `| 2026-… | … |` 형태 → 주입 시 `# fail 1` 이상 → 원복 후 `# fail 0`

- [ ] **Step 5: 룰 K 주입 — 없는 파일을 가리키는 링크를 넣는다**

주입에 **가짜 FR ID 를 쓰지 않는다.** 이 계획 문서 자체가 스캔 대상이라, 정본에 없는 FR 을
적으면 생성기가 매번 "정본에 없는 FR" 경고를 뱉는다 (그 경고를 설명하는 문장조차 유발한다).
실재하는 FR 을 쓰고 **링크만** 깨뜨린다.

```bash
echo "| FR-CO-01 | [2099-12-31](/docs/specs/2099-12-31-ghost.md) | — | — | — |" >> docs/INDEX-fr.md
node --test scripts/workflow/doc-index-coverage.test.ts 2>&1 | grep -E '^# (pass|fail)'
git checkout docs/INDEX-fr.md
```
Expected: 주입 시 `# fail 1` 이상 → 원복 후 `# fail 0`

- [ ] **Step 6: 룰 L 주입 — SOURCES 에 CI 가 모르는 경로를 추가한다**

```bash
node -e "
const fs=require('fs'); const p='scripts/doc-index/config.mjs';
let s=fs.readFileSync(p,'utf8');
s=s.replace(\"{ key: 'adr',\", \"{ key: 'probe', dir: 'docs/probe', repoRelative: true },\n  { key: 'adr',\");
fs.writeFileSync(p,s);"
node --test scripts/workflow/doc-index-coverage.test.ts 2>&1 | grep -E '^# (pass|fail)'
git checkout scripts/doc-index/config.mjs
```
Expected: 주입 시 룰 L **fail** → 원복 후 `# fail 0`

**주의.** 이 주입은 `docs/**`가 이미 CI paths에 있으면 통과해 버린다 (`docs/probe`가 `docs/**`에
포함되므로). 그 경우 `dir: 'other/probe'`로 바꿔 다시 확인한다 — 저장소 밖 접두여야 red 가 뜬다.

- [ ] **Step 7: 원복 확인 — 클린인데 초록**

Run: `git status --short && node --test scripts/workflow/doc-index-coverage.test.ts 2>&1 | grep -E '^# (pass|fail)'`
Expected: `git status` 출력 없음 + `# fail 0`

원복 후에도 red 면 하네스가 고장난 것이다. 멈추고 원인을 찾는다.

- [ ] **Step 8: 결과를 계획에 기록**

이 파일(`docs/plans/2026-08-01-doc-indexing-system.md`) 끝의 「뮤테이션 확인 결과」 표에
5종의 red 확인 여부를 적고 커밋한다.

```bash
git add docs/plans/2026-08-01-doc-indexing-system.md
git commit -m "docs(doc-index): 판별식 비-공허 확인 5종 결과 기록"
```

---

## Task 8: CLAUDE.md 슬림화 + 인덱싱 규칙

**Files:**
- Create: `docs/rules/fr-sync-checklist.md`
- Create: `docs/rules/commands.md`
- Modify: `CLAUDE.md`
- Modify: `DEVELOPMENT.md`, `DATA.md` (목차만)

**절대 규칙 19개(`DEVELOPMENT.md §1`)는 건드리지 않는다.** 분리도 요약도 하지 않는다.

- [ ] **Step 1: 분리 대상을 새 파일로 옮긴다**

`CLAUDE.md`의 `## 명세/범위 변경 시 전수 동기화` 절 전체(체크리스트 9종 + 강제 문단)를
`docs/rules/fr-sync-checklist.md`로 옮긴다. 첫 줄에 한 줄 주석을 단다.

```markdown
<!-- FR/명세 변경 시 전수 동기화 체크리스트 9종 — CLAUDE.md 에서 분리 -->

# 명세/범위 변경 시 전수 동기화
```

같은 방식으로 `## 자주 쓰는 명령어` + `## 디렉토리 (한눈에)` 를 `docs/rules/commands.md`로 옮긴다.

- [ ] **Step 2: CLAUDE.md 에 링크만 남긴다**

```markdown
## 명세/범위 변경 시 전수 동기화

**초기 기획과 달라지는 모든 변경은 같은 PR 안에서 영향받는 모든 정본·미러·카운트를 전수 동기화한다.**
동기화 대상 9종 체크리스트와 강제 절차. [`docs/rules/fr-sync-checklist.md`](docs/rules/fr-sync-checklist.md)

**강제**. 머지 전 `bash scripts/verify-master-plan.sh` 통과 필수 (종료 4 로 차단).
```

- [ ] **Step 3: 인덱싱 규칙 절을 새로 넣는다**

`CLAUDE.md`의 `## 컨텍스트 효율` 절 바로 앞에 넣는다.

```markdown
## 문서 인덱싱 규칙

- **생성 파일 직접 수정 금지.** 상단에 `자동 생성` 주석이 있으면 원본을 고치고
  `node scripts/build-doc-index.mjs` 를 재실행한다. `MEMORY.md` · `memory/index/*.md` ·
  `docs/INDEX*.md` 가 대상.
- **새 메모리.** frontmatter 필수 — `name` · `description` · `metadata.type` ·
  `metadata.hook`(≤80자) · `metadata.priority`(`critical`|`normal`) · `metadata.category`
- **새 문서.** 파일명 `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID 명시.
  spec 과 plan 은 **같은 slug** 를 쓴다 (짝 매칭 키).
- **새 문서 디렉토리를 추가하면** `scripts/doc-index/config.mjs` 의 `SOURCES` 와
  `workflow-scripts-ci.yml` 의 `paths` 를 **같은 PR 에서 함께** 고친다 (판별식 룰 L 이 차단).
- **어디를 먼저 보나.** FR 작업 → [`docs/INDEX-fr.md`](docs/INDEX-fr.md) ·
  최근 작업 → [`docs/INDEX-recent.md`](docs/INDEX-recent.md)
```

- [ ] **Step 4: DEVELOPMENT.md · DATA.md 에 목차 추가**

각 파일 H1 바로 아래에 `## 목차` 를 넣는다. `##` 헤더를 그대로 나열한다.

Run: `grep -n '^## ' DEVELOPMENT.md` 로 헤더를 뽑아 링크 목록을 만든다.

- [ ] **Step 5: 크기 확인**

Run: `wc -c CLAUDE.md ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/MEMORY.md`
Expected: `CLAUDE.md` ≤6000 · `MEMORY.md` ≤6000 (합계 ≈12KB, 글로벌 8.3KB 포함 시 ≈20KB 이하)

- [ ] **Step 6: verify-master-plan 회귀 없음 확인**

`CLAUDE.md`를 고치면 룰 E(`N FR` 표기)가 영향을 받을 수 있다.

Run: `bash scripts/verify-master-plan.sh`
Expected: `PASS. FR ID 139/139 매핑 완료.` (종료 0)

- [ ] **Step 7: 커밋**

```bash
git add CLAUDE.md DEVELOPMENT.md DATA.md docs/rules/
git commit -m "docs: CLAUDE.md 슬림화 + 문서 인덱싱 규칙 신설"
```

---

## Task 9: Obsidian 인덱스 + pre-commit 훅 + 최종 검증

**Files:**
- Modify: `scripts/build-doc-index.mjs` (`--obsidian` 플래그)
- Modify: `.husky/pre-commit`

- [ ] **Step 1: Obsidian 인덱스 생성 추가**

`scripts/build-doc-index.mjs`의 `main()` 끝에 넣는다. **기본으로는 돌지 않는다** —
저장소 밖이고 CI가 검증할 수 없으므로 명시적 플래그를 요구한다.

```javascript
  if (process.argv.includes('--obsidian')) {
    const OBS = path.join(process.env.HOME || '', 'Maxi_wiki/BTS');
    if (fs.existsSync(OBS)) {
      const files = fs.readdirSync(OBS, { recursive: true })
        .filter(f => typeof f === 'string' && f.endsWith('.md') && f !== 'INDEX.md')
        .sort();
      // CI 가 못 지키는 층이다. 생성 시각을 박아 낡음이 눈에 보이게 한다.
      const stamp = new Date().toISOString().slice(0, 10);
      const body = [
        AUTOGEN_HEADER, '',
        `# Maxi_wiki/BTS 인덱스 (${files.length}건 · 생성 ${stamp})`, '',
        '> ⚠ 저장소 밖이라 CI 가 검증하지 않는다. 낡았을 수 있다 — 생성일을 확인할 것.', '',
        ...files.map(f => `- [[${f.replace(/\.md$/, '')}]]`),
        '',
      ].join('\n');
      fs.writeFileSync(path.join(OBS, 'INDEX.md'), body);
      console.log(`→ Obsidian ${files.length}건 인덱스 생성 (검증 없음).`);
    }
  }
```

`AUTOGEN_HEADER` import 를 추가한다.

- [ ] **Step 2: Obsidian 인덱스 생성 실행**

Run: `node scripts/build-doc-index.mjs --obsidian`
Expected: `→ Obsidian N건 인덱스 생성 (검증 없음).`

- [ ] **Step 3: pre-commit 훅에 메모리 검증 추가**

`.husky/pre-commit` 끝에 덧붙인다. 메모리는 저장소 밖이라 CI가 못 지키므로 여기서 잡는다.

```bash
# 문서 인덱스 — 메모리는 저장소 밖이라 CI 가 못 본다. 커밋 시점에 자가진단을 돌린다.
if ! node scripts/build-doc-index.mjs > /dev/null 2>&1; then
  echo "FAIL. 문서 인덱스 자가진단 실패. node scripts/build-doc-index.mjs 를 직접 돌려 확인할 것." >&2
  exit 1
fi
```

- [ ] **Step 4: 훅 동작 확인**

```bash
printf -- '---\nname: probe-hook\ndescription: 훅 확인용\nmetadata: \n  type: reference\n---\n본문\n' \
  > ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/probe-hook.md
git commit --allow-empty -m "chore: pre-commit 훅 확인" 2>&1 | tail -5
rm ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/probe-hook.md
node scripts/build-doc-index.mjs
```
Expected: 새 메모리가 인덱스에 없으므로 훅이 재생성해 통과하거나, 자가진단 FAIL 로 커밋이 막힌다.
어느 쪽이든 **조용히 통과하지 않아야** 한다.

- [ ] **Step 5: 전체 검증**

```bash
node --test scripts/doc-index/*.test.mjs
pnpm test:workflow
bash scripts/verify-master-plan.sh
node scripts/build-doc-index.mjs
git status --short
```
Expected. 단위 테스트 `# fail 0` · 판별식 `# fail 0` · verify `PASS` ·
생성기 `PASS. 고아 0 · 깨진 링크 0.` · `git status` 클린(재생성해도 diff 없음)

- [ ] **Step 6: 성공 기준 대조**

```bash
echo "상시 = 글로벌 8.3KB + CLAUDE $(wc -c < CLAUDE.md) + MEMORY $(wc -c < ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/MEMORY.md)"
grep -c 'category: uncategorized' ~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/*.md | grep -v ':0' | wc -l
wc -l docs/INDEX-fr.md docs/INDEX-recent.md
```
Expected. 상시 합계 ≤20KB · uncategorized 0 · 두 인덱스 모두 비어 있지 않음

- [ ] **Step 7: 커밋**

```bash
git add scripts/build-doc-index.mjs .husky/pre-commit
git commit -m "feat(doc-index): Obsidian 인덱스(검증 없음 명시) + pre-commit 자가진단"
```

---

## 뮤테이션 확인 결과 (Task 7에서 채운다)

| 룰 | 주입 | red 확인 | 원복 후 green |
|---|---|---|---|
| I | 인덱스 한 줄 수정 | ☐ | ☐ |
| J-추가 | 인덱스에 없는 문서 생성 | ☐ | ☐ |
| J-삭제 | 인덱스에서 한 줄 삭제 | ☐ | ☐ |
| K | 없는 파일 링크 추가 | ☐ | ☐ |
| L | SOURCES 에 CI 모르는 경로 추가 | ☐ | ☐ |

---

## Self-Review — 스펙 대조

| 스펙 요구 | 담당 태스크 |
|---|---|
| §설계1 단일 생성기 + AUTOGEN 마커 | Task 1(config·마커) · Task 3(진입점) |
| §설계2 손 큐레이션 이주 (hook·priority·category) | Task 1(파서) · Task 4(backfill) |
| §설계2 4단계 분류 + 수동 오버라이드 5건 | Task 2 |
| §설계2 `/^fr-/` 오분류 방지 (R2) | Task 2 Step 1 반례 테스트 2건 |
| §설계3 MEMORY.md 라우터 ≈3.4KB | Task 3(렌더) · Task 8 Step 5(크기 확인) |
| §설계3 카테고리 인덱스 5+1 | Task 3 |
| §설계4 FR축·시간축·라우터 3종 | Task 5 |
| §설계4 FR 없는 문서를 시간축이 받음 | Task 5 Step 1 `groupByFr` 빈 배열 테스트 |
| §설계5 헌법 3종 차등 처리 | Task 8 |
| §설계6 쓰기 규칙 | Task 8 Step 3 |
| §설계7 룰 I·J·K·L | Task 6 |
| §설계7 판별식은 `scripts/workflow/*.test.ts` 관례 | Task 6 Step 1 |
| §설계7 CI paths 양쪽 블록 | Task 6 Step 3 · 룰 L 테스트가 양쪽 검사 |
| §설계8 비-공허 확인 5종 | Task 7 |
| §한계① 메모리 자가진단 + pre-commit | Task 3 Step 5 · Task 9 Step 3 |
| §한계① Obsidian 생성만·시각 표기 | Task 9 Step 1 |
| §성공기준 전 항목 | Task 9 Step 6 |

**범위 밖 준수 확인.** 문서 이동·삭제·병합 없음 · 개별 문서 요약 없음 ·
`DEVELOPMENT.md §1` 절대 규칙 19개 무수정 · 글로벌 `~/.claude/CLAUDE.md` 무수정 ·
`docs/plan`·`docs/sdd` 기존 인덱스 무수정 · FR 카운트 표기 무변경.

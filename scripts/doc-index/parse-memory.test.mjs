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

test('parseLegacyIndex — 묶음 행의 ★ 는 직전 링크에만 귀속된다', () => {
  // 실제 MEMORY.md 19행 형태. ★ 는 verify-deps 것이고 나머지 3개는 아니다.
  const md = `## 워크플로우 / 도구 / 커뮤니케이션

- **worktree 5종** — [**verify-deps 심볼릭**](worktree-pnpm-verify-deps-symlink.md) ★\`pnpm exec\`가 main 오염 · [부분설치](worktree-node-modules-partial-install.md) · [orphan vite](e2e-orphan-vite-after-worktree-remove.md)
`;
  const idx = parseLegacyIndex(md);
  assert.equal(idx.get('worktree-pnpm-verify-deps-symlink').star, true);
  assert.equal(idx.get('worktree-node-modules-partial-install').star, false);
  assert.equal(idx.get('e2e-orphan-vite-after-worktree-remove').star, false);
});

test('parseLegacyIndex — 묶음 행에서 ★ 앞 링크는 ★ 가 아니다 (69행 형태)', () => {
  const md = `## 백엔드 (jOOQ / tx / 예외)

- [nginx 마스킹](nginx-access-log-token-masking-done.md) **길이 판별식** · [/error](authenticated-error-path-token-leak-done.md) ★"실측"은 표본 범위까지
`;
  const idx = parseLegacyIndex(md);
  assert.equal(idx.get('nginx-access-log-token-masking-done').star, false);
  assert.equal(idx.get('authenticated-error-path-token-leak-done').star, true);
});

test('parseLegacyIndex — 취소선(~~) 항목도 등록은 한다 (낡음 표시일 뿐)', () => {
  const md = `## cross-BC / 권한 / shared-kernel

- ~~[프로젝트 생성 미구현](no-project-creation-feature-issue-needs-5-layer-seed.md)~~ **낡음**
`;
  const idx = parseLegacyIndex(md);
  assert.ok(idx.has('no-project-creation-feature-issue-needs-5-layer-seed'));
});

// 인덱스 마크다운 렌더러 단위 테스트
// 실행. node --test scripts/doc-index/render.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderRouter, renderCategoryIndex, auditOrphans } from './render.mjs';

const ENTRIES = [
  {
    slug: 'a-crit',
    hook: '항상 이것',
    priority: 'critical',
    category: 'workflow',
    description: '긴 설명',
  },
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

test('renderRouter — FR 완료 이력 건수가 있으면 FR 축 행을 넣는다', () => {
  const out = renderRouter(ENTRIES, { workflow: 1 }, 179);
  assert.ok(out.includes('docs/INDEX-fr.md'));
  assert.ok(out.includes('179'));
});

test('renderRouter — FR 축으로 간 메모리의 ★ 도 라우터에 남는다', () => {
  // ★ 는 "항상 지킬 것"이다. 카테고리가 아니라 FR 축으로 분류됐다고 사라지면 안 된다.
  // 실제로 fr-ux-06/07/08 계열 ★ 5건이 이 이유로 라우터에서 누락됐다.
  const withFr = [
    ...ENTRIES,
    { slug: 'fr-ux-08-pr-b-sidebar-nav-done', hook: 'E8 반쪽봉합', priority: 'critical', category: 'fr-history', description: '' },
  ];
  const out = renderRouter(withFr, { workflow: 1, backend: 1 }, 179);
  const starBlock = out.split('## 상황별 인덱스')[0];
  assert.ok(starBlock.includes('fr-ux-08-pr-b-sidebar-nav-done'));
  assert.ok(starBlock.includes('(2건)')); // a-crit + fr 항목
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

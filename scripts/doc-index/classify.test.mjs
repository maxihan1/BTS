// 메모리 4단계 분류기 단위 테스트 — 오버라이드 → 승계 → 패턴 → uncategorized
// 실행. node --test scripts/doc-index/classify.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classify, splitFrHistory, frIdOf, partition } from './classify.mjs';

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
  assert.deepEqual(
    frHistory.map((x) => x.slug),
    ['fr-co-01-comment-create-list-done'],
  );
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

test('frIdOf — 표준 형식과 수동 오버라이드 둘 다 처리한다', () => {
  assert.equal(frIdOf('fr-co-01-comment-create-list-done'), 'FR-CO-01');
  assert.equal(frIdOf('fr-pj-pr-4-archive-done'), 'FR-PJ');
  assert.equal(frIdOf('msw-dual-handler-e2e-shadow'), null);
});

test('partition — frontmatter category 가 승계·패턴보다 우선한다 (멱등성의 근거)', () => {
  // backfill 이후에는 frontmatter 가 정본이다. 승계 원천(MEMORY.md)이 자동 생성본으로
  // 바뀌어 legacy 가 비어도 분류 결과가 흔들리면 안 된다 — 재생성 diff 가 매번 달라진다.
  const entries = [
    { slug: 'fr-workflow-scheme-contract-align-done', category: 'backend' },
    { slug: 'fr-co-01-comment-create-list-done', category: 'fr-history' },
  ];
  const { frHistory, categorized } = partition(entries, new Map());
  assert.deepEqual(categorized, ['fr-workflow-scheme-contract-align-done']);
  assert.deepEqual(
    frHistory.map((f) => f.slug),
    ['fr-co-01-comment-create-list-done'],
  );
});

test('partition — frontmatter category 가 없으면 승계+패턴으로 판정한다', () => {
  const legacy = new Map([['fr-admin-x-done', { category: 'workflow', star: false, hook: '' }]]);
  const entries = [
    { slug: 'fr-admin-x-done', category: '' },
    { slug: 'fr-co-02-x-done', category: '' },
  ];
  const { frHistory, categorized } = partition(entries, legacy);
  assert.deepEqual(categorized, ['fr-admin-x-done']);
  assert.deepEqual(
    frHistory.map((f) => f.slug),
    ['fr-co-02-x-done'],
  );
});

test('partition — 같은 입력이면 legacy 유무와 무관하게 같은 결과 (멱등)', () => {
  const entries = [
    { slug: 'fr-co-01-x-done', category: 'fr-history' },
    { slug: 'jooq-init-codegen-mirror', category: 'backend' },
  ];
  const withLegacy = partition(entries, new Map([['fr-co-01-x-done', { category: 'workflow' }]]));
  const without = partition(entries, new Map());
  assert.deepEqual(withLegacy.categorized, without.categorized);
  assert.deepEqual(
    withLegacy.frHistory.map((f) => f.slug),
    without.frHistory.map((f) => f.slug),
  );
});

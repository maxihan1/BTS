// docs 스캐너 단위 테스트 — 파일명·H1·FR ID 추출 및 FR축/시간축 묶음
// 실행. node --test scripts/doc-index/scan-docs.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseDocMeta, groupByFr, groupByDate, readCanonicalFrIds } from './scan-docs.mjs';

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

test('parseDocMeta — NFR-XX-NN 을 FR ID 로 오인하지 않는다', () => {
  // NFR = Non-Functional Requirement. `FR-[A-Z]{2,3}-\d{2}` 는 NFR-SEC-01 의 뒷부분을 잘라
  // FR-SEC-01 로 잡는다. 실제로 이 오탐 5건이 "정본에 없는 FR" 경고로 떴다 —
  // 문서가 틀린 게 아니라 판별식이 틀렸다.
  const d = parseDocMeta('2026-01-01-x.md', '# T\n\n- **NFR-SEC-01** Access JWT 는 sessionStorage\n- **NFR-SEC-02** Refresh 는 HttpOnly');
  assert.deepEqual(d.frIds, []);
});

test('parseDocMeta — 진짜 FR 과 NFR 이 섞여 있으면 FR 만 뽑는다', () => {
  const d = parseDocMeta('2026-01-01-x.md', '# T\n\nFR-CO-01 구현. NFR-SEC-03 은 별건.');
  assert.deepEqual(d.frIds, ['FR-CO-01']);
});

test('groupByFr — 같은 FR 의 spec·plan 을 한 행으로 묶는다', () => {
  const rows = groupByFr([
    {
      kind: 'specs',
      date: '2026-01-01',
      slug: 'a',
      frIds: ['FR-CO-01'],
      file: 'docs/specs/2026-01-01-a.md',
    },
    {
      kind: 'plans',
      date: '2026-01-01',
      slug: 'a',
      frIds: ['FR-CO-01'],
      file: 'docs/plans/2026-01-01-a.md',
    },
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

test('parseDocMeta — 파일명의 FR ID 를 따로 뽑는다 (주 문서 판별용)', () => {
  const d = parseDocMeta('2026-07-31-fr-ux-09-b1-create-issue-fields.md', '# T\n\nFR-IS-03 도 언급');
  assert.deepEqual(d.frIdsInName, ['FR-UX-09']);
  assert.deepEqual(d.frIds, ['FR-IS-03', 'FR-UX-09']);
});

test('groupByFr — 파일명 매치는 주 문서, 본문 언급만이면 mentioned 로 센다', () => {
  // FR-UX-06 한 행이 4236자가 된 원인. 스쳐 언급한 문서까지 전부 링크하면 읽을 수 없다.
  const rows = groupByFr([
    { kind: 'plans', date: '2026-01-01', slug: 'fr-co-01-x', frIds: ['FR-CO-01'], frIdsInName: ['FR-CO-01'], file: 'f1' },
    { kind: 'plans', date: '2026-01-02', slug: 'debt-zero', frIds: ['FR-CO-01'], frIdsInName: [], file: 'f2' },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].plans.length, 1); // 주 문서만
  assert.equal(rows[0].plans[0].file, 'f1');
  assert.equal(rows[0].mentioned, 1); // 언급은 개수로
});

test('groupByFr — 파일명 매치가 하나도 없으면 본문 언급을 주 문서로 승격한다', () => {
  // adr 는 파일명에 FR ID 가 1/34 뿐이다. 좁히기만 하면 adr 열이 통째로 비어버린다.
  const rows = groupByFr([
    { kind: 'adr', date: '2026-01-01', slug: 'workflow-yaml', frIds: ['FR-WF-01'], frIdsInName: [], file: 'a1' },
  ]);
  assert.equal(rows[0].adr.length, 1);
  assert.equal(rows[0].mentioned, 0);
});

test('groupByFr — 정본 FR 집합을 주면 그 밖의 FR 은 버리고 rejected 로 돌려준다', () => {
  const { rows, rejected } = groupByFr(
    [
      { kind: 'plans', date: '2026-01-01', slug: 'a', frIds: ['FR-CO-01', 'FR-ZZ-99'], frIdsInName: [], file: 'f' },
    ],
    new Set(['FR-CO-01']),
  );
  assert.deepEqual(rows.map((r) => r.frId), ['FR-CO-01']);
  assert.deepEqual(rejected, ['FR-ZZ-99']);
});

test('readCanonicalFrIds — fr-index 표에서 FR ID 집합을 읽는다', () => {
  const md = `| FR ID | 한 줄 | BC |
|---|---|---|
| FR-IS-01 | 이슈 CRUD | issue-tracking |
| FR-WF-01 | FSM | project-workflow |
`;
  const s = readCanonicalFrIds(md);
  assert.ok(s.has('FR-IS-01'));
  assert.ok(s.has('FR-WF-01'));
  assert.equal(s.size, 2);
});

test('groupByDate — 전량을 받는다 (FR 없는 문서도 반드시 한 행)', () => {
  // 시간축은 FR 축이 놓치는 문서를 받아내는 그물이다. 하나라도 빠지면 고아가 생긴다.
  const docs = [
    { kind: 'plans', date: '2026-01-01', slug: 'no-fr', frIds: [], file: 'f1' },
    { kind: 'adr', date: '2026-01-02', slug: 'has-fr', frIds: ['FR-XX-01'], file: 'f2' },
  ];
  assert.equal(groupByDate(docs).length, 2);
});

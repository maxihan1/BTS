// 빌드 스크립트의 마크다운 파서 단위 테스트
// 실행. node --test scripts/build-dashboard.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePlanFile, parseFrIndex, parseGlossary, parseYongeo } from './build-dashboard.mjs';

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

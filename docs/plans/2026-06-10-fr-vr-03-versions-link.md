# FR-VR-03 — Affects/Fix Version 연결

> slug: fr-vr-03-versions-link
> type: feature (backend + frontend + e2e 풀스택)
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> primary_bc: issue-tracking
> 생성: 2026-06-10

## Brief

이슈를 버전에 다대다로 연결한다 (Jira 정석).
- Affects Version (영향 버전) — 이 이슈/버그가 발견된·영향받는 버전
- Fix Version (수정 버전) — 이 이슈가 수정될·수정된 목표 버전
- 둘 다 다대다 — 조인 테이블 2개: issue_affects_versions, issue_fix_versions

product 정본: docs/plan/product/issue-tracking.md §3.2.3
선행: FR-VR-01 (버전 생성, PR #67), FR-VR-02 (버전 상태, PR #105)
Plan slug(정본): issue/versions-link

### D단계 (product 정본)
- [ ] D1. 도메인 — Affects vs Fix 의미 (backend-engineer)
- [ ] D2. 명세 (backend-engineer)
- [ ] D3. 데이터 모델 — issue_affects_versions, issue_fix_versions (db-engineer)
- [ ] D4. 백엔드 (backend-engineer)
- [ ] D5. 백엔드 테스트 (backend-engineer)
- [ ] D6. 프론트 UI — 버전 셀렉터 2종 (designer → frontend-engineer)
- [ ] D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

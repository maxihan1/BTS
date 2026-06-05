# FR-PM-05 — 이슈 접근 권한 (Browse, View)

> slug: fr-pm-05-browse-view
> type: auth
> agent: security-engineer
> 생성: 2026-06-05

## Brief

FR-PM-05 — 이슈 접근 권한(Browse/View 분리). 권한 없는 이슈를 조회 결과에서
자동 필터링하고 단건 조회 시 차단. identity-access BC 중심 + 이슈 쿼리 필터
자동 첨부(jOOQ Condition 빌더) + 프론트 권한 없는 이슈 404 처리.

- 선행: §4.2 FR-PM-02 권한 스킴 모델 활용
- 병렬 작업: FR-CM-03(issue-tracking, draft PR #84) 존재 — 공유 자원 충돌 주의
- classify: type=auth, agent=security-engineer, primary_bc=identity-access

plan 문서(docs/plan/product/identity-access.md §4.5) D1~D7:
- D1. 도메인 — BrowsePermission vs ViewPermission 분리 (security-engineer)
- D2. 명세 (security-engineer)
- D3. 데이터 모델 — (FR-PM-02 활용) (db-engineer)
- D4. 백엔드 — 이슈 쿼리에 필터 자동 첨부 (jOOQ Condition 빌더) (security + backend)
- D5. 백엔드 테스트 — 비공개 이슈 조회 차단 (security-engineer)
- D6. 프론트 UI — 권한 없는 이슈 404 처리 (frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

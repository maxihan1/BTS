# FR-IS-02 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀) — 백엔드

> slug: fr-is-02-issue-types-backend
> type: feature
> agent: backend-engineer
> 생성: 2026-05-29

## Brief

FR-IS-02 이슈 타입의 **백엔드 범위(D1~D5)** 구현. issue-tracking BC.

**범위 한정 (Maxi 결정)**. 백엔드만. 프론트(D6 타입 셀렉터)·E2E(D7)는 진행 중인 transition-e2e(PR #34) 정리 후 별 PR로 분리 — 이슈 상세 화면(`issues.$key.tsx`/`IssueMetaPanel.tsx`)에서 전이 UI와 충돌 회피.

**잔여 본업**.
- D1 도메인 — Issue 도메인에 type 연결 (IssueType 참조)
- D2 명세 — Epic-Subtask 계층 제약
- D3 데이터 모델 — `issues.type_id` FK 신규 Flyway 마이그레이션 (V005 예정). 책임. db-engineer
- D4 백엔드 — 커스텀 IssueType CRUD API (POST/PATCH/DELETE) + Epic-Subtask 계층 검증
- D5 백엔드 테스트 — MockK 단위 + Testcontainers 통합

**사전 도입분 재활용 (FR-WF-02 PR #31)**.
- `issue_types` 테이블 V003 + 5 표준 seed
- IssueType 엔티티 / IssueTypeRepository
- read-only GET API (IssueTypeController)
- 프론트 use-issue-types (이번 PR scope 외)

**분류**. classify 원결과 migration/db-engineer → Maxi 결정으로 feature/backend-engineer 교정 (plan FR-IS-02 D라인 책임자 일치).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

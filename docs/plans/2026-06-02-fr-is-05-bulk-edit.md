# FR-IS-05 — 이슈 일괄 편집 + 일괄 상태 전이 (백엔드 D1~D5)

> slug: fr-is-05-bulk-edit
> type: api
> agent: backend-engineer
> BC: issue-tracking
> 생성: 2026-06-02

## Brief

FR-IS-05 (docs/plan/product/issue-tracking.md §2.2.1) — 이슈 일괄 편집 + 일괄 상태 전이.
이번 작업 범위는 **백엔드만 (D1~D5)**. 프론트 UI(D6)·E2E(D7)는 후속 PR로 분리.

- D1. 도메인 — BulkOp
- D2. 명세 — 트랜잭션 정책, 부분 실패 처리
- D3. 데이터 모델 — (FR-IS-01 활용, 신규 테이블 없음 예상)
- D4. 백엔드 — `POST /api/v1/issues/bulk-update`, 청크 처리
- D5. 백엔드 테스트 — 부분 실패 시 트랜잭션 동작

선행 FR-IS-01~04 모두 구현 완료. 일괄 상태 전이는 FR-IS-01/02의 전이 검증 로직 활용.

classify 결과: type=api, agent=backend-engineer, primary_bc=issue-tracking
(classify 원본은 project-workflow 오판 → fr-index.md 근거로 issue-tracking 정정)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

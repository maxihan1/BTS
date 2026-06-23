<!-- FR-BL-01 백로그 우선순위 정렬(LexoRank) 구현 계획 -->
# FR-BL-01 — 백로그 우선순위 정렬 (LexoRank)

> slug: backlog-lexorank
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking (도메인 확정 — rank는 issue 스칼라 속성)
> 생성: 2026-06-23
> ⚠️ 진실출처: 이 plan 파일 (.bts-cache/classify.json은 멀티세션 충돌로 FR-SR-01에 덮어써짐)

## Brief

FR-BL-01 백로그 우선순위 정렬 (LexoRank) — agile-planning BC 백엔드.
- issues.rank 컬럼 (SDD §13.2.1 VARCHAR(50) 알파벳 키)
- LexoRank 알고리즘 (backend/shared/lexorank.kt 자체 구현, §1.1 선행)
- PATCH /api/v1/issues/{key}/rank 리랭크 API (위/아래 이웃 중간값)
- 자동 rebalance (주 1회 백그라운드 + 중간값 고갈 시 트리거)
- 1K 부하 테스트 (insert/move 평균 < 5ms)

프론트 UI(D6)는 FR-BL-02와 통합 예정 — 이번 PR 제외.

classify: { type: api, agent: backend-engineer, primary_bc: issue-tracking(검증필요) }
SDD §13.2.1 / product agile-planning.md §3.1 / fr-index §3.1

## 도메인 정리

- **구현 BC**: issue-tracking (product 분류는 agile-planning §3.1이나, rank는 issue 스칼라 속성 → issue-tracking 소유. FR-PL-01 일정 필드 선례 동일).
- **영향 엔티티**: Issue (issues.rank 컬럼 신규). shared-kernel에 LexoRank Rank VO 신규.
- **새 용어**:
  - LexoRank — 이미 glossary 존재 ("순서 보존 알고리즘, 보드/백로그 정렬"). 변경 없음.
  - Rank VO — LexoRank 키의 값 객체 (VARCHAR(50) 알파벳 문자열). glossary 추가 후보.
  - rebalance(재균형) — 중간값 고갈/누적 시 rank 키를 재배포. glossary 추가 후보.
- **BC 경계 결정** (Maxi 확정):
  1. rank 소유 = issue-tracking (issues.rank 컬럼 + IssueController `PATCH /api/v1/issues/{key}/rank`).
  2. LexoRank 알고리즘 = shared-kernel `com.bts.shared.lexorank` 순수 VO.
- **기존 결정 충돌**: 없음. FR-BD-01 ADR(결정 3)이 LexoRank를 FR-BL-01로 명시 이연 → 본 작업이 그 이연을 정식 도입.
- **product/SDD drift**: product §1.1 `backend/shared/lexorank.kt` 경로는 실제와 불일치 → shared-kernel로 정정 (전수 동기화 대상). product D3 `issues.rank`(TEXT) vs SDD §13.2.1 `VARCHAR(50)` → spec에서 컬럼 타입 확정.
- **관련 ADR**: [docs/decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md](../decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

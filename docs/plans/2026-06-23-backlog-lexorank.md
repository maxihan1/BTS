<!-- FR-BL-01 백로그 우선순위 정렬(LexoRank) 구현 계획 -->
# FR-BL-01 — 백로그 우선순위 정렬 (LexoRank)

> slug: backlog-lexorank
> type: api
> agent: backend-engineer
> primary_bc: (도메인 단계 확정 — issue-tracking vs agile-planning)
> 생성: 2026-06-23

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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

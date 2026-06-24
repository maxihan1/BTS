# FR-BL-01+FR-BL-02 D6/D7 — 백로그↔스프린트 프론트 UI + 백로그 조회 API + E2E

> slug: fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e
> type: ui
> agent: frontend-engineer (백엔드 조회 API task=backend-engineer, E2E task=qa-engineer plan 메타)
> primary_bc: agile-planning
> 생성: 2026-06-24

## Brief

FR-BL-01(백로그 우선순위 LexoRank, 백엔드 #179)·FR-BL-02(백로그→스프린트 이동, 백엔드 #182)의
D6(프론트 UI)/D7(E2E)를 통합 구현. 양쪽 PR Deviation이 D6/D7과 "백로그 조회 API"를 본 작업으로 이연.

선행 백엔드 완료 상태.
- FR-BL-01 D5(#179): `PATCH /api/v1/issues/{key}/rank` (LexoRank rank 변경, no-bump, on-demand rebalance)
- FR-BL-02 D5(#182): `SprintController` — 스프린트 CRUD + start/complete + 이슈 할당(POST /sprints/{id}/issues)/해제(DELETE /sprints/{id}/issues/{issueKey})

미구현(본 작업 포함).
- 백로그 조회 API — rank 정렬된 스프린트 미할당 이슈 목록 GET (양쪽 Deviation 이연 항목)
- D6 프론트: @dnd-kit 백로그 ↔ 스프린트 드래그앤드롭 보드
- D7 E2E: 드래그 재정렬·스프린트 할당/해제 시나리오

classify override: type=qa→ui (E2E 키워드 오판 선례 교정).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

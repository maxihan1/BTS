# FR-TL-01 D6/D7 타임라인/로드맵 뷰 (Gantt) — 프론트엔드 UI + E2E

> slug: fr-tl-01-d6-d7-gantt-ui-e2e
> type: ui
> agent: frontend-engineer
> BC: agile-planning (프론트), 백엔드 D1~D5 = #192 완료
> SDD: §4.1 / §13.3.1
> 생성: 2026-06-26

## Brief

사용자 원문: "fr-tl-01 d6, d7 진행해줘"

FR-TL-01 — 타임라인/로드맵 뷰 (Gantt). 필수 우선순위. agile-planning BC. SDD §4.1 / §13.3.1.
백엔드 D1~D5 는 PR #192 로 완료 — `GET /api/v1/timeline?project={key}` 존재 (타임라인 아이템 평면 목록 + epicKey + truncated 반환).
이번 작업 = D6(프론트 Gantt 렌더) + D7(E2E). 이슈의 Start/Due/Target Date 기반 타임라인을 Gantt 막대로 시각화.

classify: type=qa 오판 → ui/frontend-engineer 교정 (E2E 키워드 오판 함정).

범위 결정(Maxi 2026-06-26):
- 신규 worktree (FR-SR-03 PR2 와 격리)
- Gantt 라이브러리는 domain/spec 단계에서 후보 조사 → ADR trade-off 제시 → Maxi 승인

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

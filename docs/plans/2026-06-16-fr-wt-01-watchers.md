# FR-WT-01 — Watcher 추가/제거 + 자동 Watcher (백엔드 D1~D5)

> slug: fr-wt-01-watchers
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-16

## Brief

사용자 원문. "fr-wt-01 진행해줘"

명세 — `docs/plan/product/issue-tracking.md §4.3.1 FR-WT-01`.
이슈 Watcher(이슈를 지켜보며 알림 대상이 되는 사용자) 추가/제거 + Reporter/Assignee 자동 Watcher 등록.

이번 PR 범위 — **백엔드 D1~D5** (BTS 표준 관례에 따라 프론트 D6/D7은 후속 PR `fr-wt-01-watchers-ui-e2e`로 분리).

- D1. 도메인 (backend-engineer)
- D2. 명세 — Reporter/Assignee 자동 Watcher (backend-engineer)
- D3. 데이터 모델 — `issue_watchers` (db-engineer)
- D4. 백엔드 — `POST/DELETE /api/v1/issues/{key}/watchers` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)

classify 결과. type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

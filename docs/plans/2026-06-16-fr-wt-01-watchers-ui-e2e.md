# FR-WT-01 Watcher 프론트 D6/D7 — Watch 버튼 + 카운트 UI + E2E

> slug: fr-wt-01-watchers-ui-e2e
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-16

## Brief

사용자 원문. "fr-wt-01 프론트 watch 버튼+카운트 UI 및 E2E (백엔드 D1~D5는 PR #151로 머지 완료, 후속 PR slug fr-wt-01-watchers-ui-e2e)"

FR-WT-01(이슈 Watcher 추가/제거 + 자동 Watcher)의 백엔드 D1~D5는 PR #151(squash 6e954719)로 머지 완료. 이번 작업은 남은 프론트 D6/D7.

- **D6** — Watch 버튼(토글) + watcher 카운트 UI를 이슈 상세 페이지에 통합. `GET/POST/DELETE /api/v1/issues/{key}/watchers` 호출, `isWatching`으로 버튼 상태.
- **D7** — Playwright E2E(watch/unwatch/카운트/권한 게이팅).

classify 결과. type=qa로 오판정(E2E 키워드) → ui로 교정(본체는 프론트 UI, E2E 따라붙음). 메모리 `classify E2E→qa 오판정 ui 교정` 적용.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

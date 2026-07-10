# FR-SL-03 Slack Unfurl (Atlas URL 자동 카드)

> slug: fr-sl-03-slack-unfurl
> type: feature
> agent: backend-engineer
> 생성: 2026-07-11

## Brief

**사용자 원문**. "fr-sl-03 진행해줘"

FR-SL-03 — Slack Unfurl (Atlas URL 자동 카드). Slack 대화에 Atlas 이슈 URL을 붙이면
Slack이 `link_shared` 이벤트를 백엔드로 전송 → 백엔드가 열람 권한을 확인한 뒤
이슈 키·제목·상태·담당자를 담은 Block Kit 카드를 반환해 Slack이 링크를 카드로 펼침(unfurl).

**분류 결과**. classify-task가 `ui/frontend-engineer`로 오판 → 명세(`slack-integration.md §3.1`)
근거로 `feature/backend-engineer`로 교정. D6 프론트 UI = 해당 없음, D1~D5 backend-engineer, D7 qa-engineer.

**명세 위치**. `docs/plan/product/slack-integration.md §3.1`, `docs/sdd/09-notifications-slack.md §9.3.3`

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

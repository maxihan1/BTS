# FR-SL-04 — Slack Slash 명령어 (`/atlas ...`)

> slug: fr-sl-04-slash-command
> type: api
> agent: backend-engineer (+ security-engineer 권한 가드 검토)
> primary_bc: slack-integration
> 생성: 2026-07-11

## Brief

FR-SL-04 Slack Slash 명령어. Slack에서 `/atlas ...` 명령을 입력하면 BTS가 응답.
- 인바운드 slash command 엔드포인트 (`POST /slack/commands`)
- X-Slack-Signature 서명 검증 (FR-SL-03 패턴 재사용)
- Slack 사용자 → BTS 사용자 매핑 (user_slack_mapping 역매핑, V702 재사용)
- 권한 가드 (매핑된 BTS 사용자 권한으로 실행)
- 명령어. `/atlas search <aql>`, `/atlas create <title>` 등

classify: type=api, agent=backend-engineer, primary_bc=slack-integration

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

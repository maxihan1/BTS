# FR-SL-01 Slack App + Bot Token 방식

> slug: fr-sl-01-slack-bot-app
> type: auth
> agent: security-engineer
> primary_bc: slack-integration
> 생성: 2026-07-07

## Brief

slack-integration BC의 첫 구현. Slack App 설치 흐름(OAuth 2.0) + Bot Token 안전 보관.
- D1. 도메인 — SlackWorkspace + BotInstall
- D2. 명세 — OAuth 2.0 설치 흐름 + Token 보관
- D3. 데이터 모델 — slack_installs(workspace_id, bot_token_encrypted, ...)
- D4. 백엔드 — Slack 설치 콜백 (/slack/install/callback)
- D5. 백엔드 테스트
- D6. 프론트 UI — 관리자 "Slack 연결" 페이지
- D7. E2E

SDD 09장(알림/Slack). product: docs/plan/product/slack-integration.md §2.1

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

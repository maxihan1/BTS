# FR-SL-06 D6 UI (프로젝트 설정 → Slack 채널 관리 화면) + D7 E2E

> slug: fr-sl-06-d6-d7-slack-channel-mapping-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-13

## Brief

FR-SL-06(채널↔프로젝트 매핑)의 마지막 남은 D6 UI + D7 E2E를 구현한다.
백엔드는 완료됨:
- PR-A(#264): 매핑 CRUD API + 설정. V704, projectKey, 하드삭제, PROJECT_ADMIN 가드,
  403→404/malformed→400 정규화.
- PR-B(#266): 채널 라우팅. notification SlackChannelBroadcaster → q_slack_channel_broadcasts
  → 채널 워커, 보안게이트 IssueSecurityClassificationPort(fail-closed).

D6 = 프로젝트 설정 화면에 "Slack 채널 관리" 섹션 신설.
  채널↔프로젝트 매핑 CRUD(생성/목록/삭제) + event_filter 선택 UI.
D7 = Playwright E2E 시나리오.

완료 시 slack-integration BC = 6/6 · BC 완료 마킹 + 문서 전수 동기화(verify-master-plan).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

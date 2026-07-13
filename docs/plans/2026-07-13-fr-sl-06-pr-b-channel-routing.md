# FR-SL-06 PR-B — 채널 라우팅 (백엔드)

> slug: fr-sl-06-pr-b-channel-routing
> type: backend
> agent: backend-engineer
> 생성: 2026-07-13

## Brief

slack-integration BC 마지막 FR(FR-SL-06 채널↔프로젝트 매핑)의 후반부 PR-B(라우팅).
범위 = 백엔드 라우팅만 (D6 UI / D7 E2E는 후속).

PR-A(#264, 머지 완료)가 깔아둔 기반:
- `slack_channel_project_map` (V704, project_key, event_types text[], 하드삭제)
- CRUD API + `SlackChannelMappingPermissionResolver` 포트 + identity-access prod 어댑터
- `SlackChannelEventType` wire 미러 10종

PR-B가 채울 3덩어리:
- (a) notification 모듈: `q_slack_channel_broadcasts` 신규 pgmq 큐 + 이슈 이벤트당 1회
      fan-out 브로드캐스터 (q_issue_events는 competing-consumer라 재사용 불가)
- (b) slack 모듈: 채널 워커 — 매핑 조회 → event_filter(event_types) 매칭 →
      채널당 chat.postMessage 게시
- (c) 보안 게이트: 신규 cross-BC 포트 `IssueSecurityClassificationPort`로
      보안등급 이슈 fail-closed 제외 (issue-tracking prod 어댑터)

완료 시: slack BC 6/6 마킹 + 문서 전수 동기화(verify-master-plan).

관련 산출물(PR-A):
- spec `docs/specs/2026-07-13-fr-sl-06-channel-mapping.md`
- plan `docs/plans/2026-07-13-fr-sl-06-channel-mapping.md`
- ADR `docs/decisions/2026-07-13-fr-sl-06-channel-mapping.md`
- product `docs/plan/product/slack-integration.md §2.3`

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

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

## 도메인 정리

- **주 BC**: notification (producer) + slack-integration (consumer). cross-BC 어댑터: issue-tracking (보안게이트).
- **영향 엔티티/개념**:
  - notification: `NotificationWorker.dispatch()` (q_issue_events 소비자) — projectKey 있는 이벤트를 신규 큐로 fan-out. `SlackChannelSender`(FR-SL-02) JSON 큐 경계 패턴 재사용.
  - slack: `SlackNotificationChannel` (프로젝트→채널, slack 도메인 노트에 "FR-SL-06 예정" 명시) = `slack_channel_project_map`(V704, PR-A). 신규 채널 워커.
  - issue-tracking: 이슈 보안등급(`security_level_id`) — 신규 read 포트로 노출.
- **새 용어**: 없음. broadcast/fan-out은 표준 메시징 용어(glossary 추가 불요). "채널 브로드캐스트"(이벤트당 1회, 수신자 무관) vs "DM 딜리버리"(수신자 단위, FR-SL-02)의 granularity 구분만 명확히.
- **기존 결정 충돌**: 없음. PR-A ADR이 PR-B를 이미 확정(D1 라우팅 팬아웃·D5 보안 게이트). 이 PR은 그 결정의 구현.
- **관련 ADR**: [docs/decisions/2026-07-13-fr-sl-06-channel-mapping.md](../decisions/2026-07-13-fr-sl-06-channel-mapping.md) (PR-A, D1·D5가 PR-B 설계 확정 — **신규 ADR 불요**).
- **핵심 도메인 이슈 3건 검증 결과**:
  1. **fan-out 큐 분리 정당** — `q_issue_events`는 notification 전용 경쟁 소비 큐(companion KDoc). slack이 두 번째 consumer로 붙으면 이벤트를 나눠 먹어(competing-consumer) 일부만 게시됨. 채널 게시는 이벤트당 1회(수신자 무관)라 granularity 불일치 → 신규 큐 `q_slack_channel_broadcasts` 필수. (ADR D1, FR-SL-02 큐 경계 선례.)
  2. **BC 격리** — notification→slack는 도메인 타입 import 0, JSON wire 계약만. `SlackChannelEventType`(PR-A) 10종 미러가 `NotificationEventType`과 대응. producer는 wire 필드만 발행(eventType·issueKey·projectKey·title·occurredAt 등).
  3. **confidentiality oracle 회피** — 보안 게이트는 **뷰어별 가시성이 아닌 이슈의 절대 속성**(`security_level_id` non-null)으로 판정. `IssueSecurityClassificationPort.isSecurityRestricted(issueKey)`가 제한/판정불명이면 skip(fail-closed). 위조 가능 actor로 평가하지 않으므로 [[condition-eval-chosen-actor-read-oracle]] 계열 §12.4 오라클 문제 구조적 부재. issueKey 없는 이벤트는 이슈-스코프 아니라 게이트 우회(ADR D5).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

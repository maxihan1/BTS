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

## 스펙

전체 스펙 = **기존 FR-SL-06 정본**. [docs/specs/2026-07-13-fr-sl-06-channel-mapping.md](../specs/2026-07-13-fr-sl-06-channel-mapping.md)
(PR-A가 PR-B까지 FR4~FR9·EC1~EC12·완료기준 전부 명세. 별도 PR-B spec 미작성 — drift 방지.)

**PR-B 스코프 (이 PR이 구현하는 FR)**:
- FR4 — notification `SlackChannelBroadcaster` 컴포넌트: `dispatch()`의 **정책 early-return 이전**에서 projectKey 있으면 `q_slack_channel_broadcasts`로 이벤트당 1회 JSON emit(수신자/정책 독립). slack import 0.
- FR5 — slack 채널 워커: `q_slack_channel_broadcasts` 폴링(@Scheduled) → 매핑조회(`findByProjectKey`) → **보안게이트(FR9)** → 매칭 채널별(dedup확인 → 봇토큰해석 → `render(title,issueKey?)` → `chat.postMessage`) → 결과별 pgmq 생명주기(Sent=dedup기록후 delete / Permanent=delete / Retryable=retain, read_ct>MAX archive).
- FR6 — 채널 게시 dedup: 키=(projectKey,eventType,issueKey,occurredAt,channelId), **전송 성공 후에만** 기록(FR-SL-02 B4 exists→send→record).
- FR8 — prod 조립: 새 포트 adapter 2종(권한은 PR-A 완료, 신규=보안게이트) + 워커 @Scheduled 결선, `:modules:app:test` 부팅 검증.
- FR9 — cross-BC 포트 `IssueSecurityClassificationPort.isSecurityRestricted(issueKey)`: prod=issue-tracking(@Profile prod, `security_level_id` non-null), non-prod stub(항상 false). true/불명→해당 이벤트 채널 게시 전부 skip(메시지는 삭제, 유출차단), issueKey null→우회.

**PR-B 밖 (PR-A 완료)**: FR1(V704 테이블)·FR2(CRUD API)·FR3(eventTypes 검증)·FR7(권한 포트+어댑터).

**★ plan 판단 1건 — 채널 dedup 저장소** (spec line 135이 plan으로 위임):
- 옵션 A. 전용 로그 테이블 `slack_channel_broadcast_log(dedup_key PK, ...)` (V705에서 큐와 함께 생성). 의미 분리 명확, cleanup 독립. **권장.**
- 옵션 B. 기존 `slack_delivery_log`(dedup_key PK) 재사용. DM키(recipientUserId 포함)와 채널키(channelId 포함) 비충돌이라 가능하나, DM/채널 로그가 한 테이블에 섞임.
- → 게이트 1에서 Maxi 확인.

**★ 구현 refinement (brainstorming 발견)**: wire payload `dedupKey`는 **이벤트 레벨**(projectKey,eventType,issueKey,occurredAt 해시) — producer는 채널을 모름. 워커가 `dedupKey + channelId`로 per-channel dedup 키 완성.

**신규 마이그레이션 = slack V705**: 큐 `q_slack_channel_broadcasts` 생성(`CREATE EXTENSION pgmq` + `pgmq.create`, producer-creates 예외로 소비 모듈=slack에 배치, V701 선례) [+ 옵션 A 채택 시 dedup 테이블]. JdbcTemplate 모듈이라 init_codegen 미러 없음.

## Brainstorming Check

✅ 통과 (1회 iteration, 집중 자기검증).
- PR-A spec이 PR-B를 이미 comprehensive 하게 명세 — 재작성 대신 정본 참조(drift 방지).
- **발견 1 (구현 refinement)**: wire dedupKey는 이벤트 레벨, 워커가 channelId 덧붙임 → plan 명시.
- **발견 2 (plan 판단 위임)**: 채널 dedup 저장소(전용 vs 재사용) → 게이트 1 Maxi 확인.
- **검증**: producer는 projectKey 있는 이벤트만 emit(없으면 미발행, criterion 3) · issueKey nullable(sprint.*)는 보안게이트 우회하되 event_filter 적용 · 새 cross-BC 포트 소비(slack 워커→IssueSecurityClassificationPort)는 full-boot @MockBean 회귀 확인 필요([[new-crossbc-dep-openapi-mockbean-regression]]).
- 잔여 blocking gap 없음. plan 단계 진행 가능.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

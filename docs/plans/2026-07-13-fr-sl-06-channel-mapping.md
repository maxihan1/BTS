# FR-SL-06 채널 매핑

> slug: fr-sl-06-channel-mapping
> type: backend
> agent: backend-engineer
> 생성: 2026-07-13

## Brief

FR-SL-06 채널 매핑 구현. slack-integration BC의 마지막 남은 FR (현재 5/6 완료).
사용자 원문: "fr-sl-06 진행하자"
classify: type=backend, agent=backend-engineer, slug=fr-sl-06

## 도메인 정리

- **BC**. slack-integration (주). 라우팅 팬아웃 지점 결정에 따라 notification BC producer 소폭 변경 가능성.
- **FR**. FR-SL-06 — 채널 ↔ 프로젝트 매핑 (product/slack-integration §2.3, SDD 09 §9.1.1 "Slack 채널 = 프로젝트별 활동 피드").
- **핵심 엔티티 (신규)**. `ChannelProjectMapping` — Slack 채널 ↔ 프로젝트 매핑 + 이벤트 종류 필터. 테이블 `slack_channel_project_map`.
  - 다대다. 한 프로젝트 → 여러 채널, 한 채널 → 여러 프로젝트.
  - `event_filter`. 이 매핑으로 라우팅할 이벤트 종류 집합 (notification `NotificationEventType.wireValue` 문자열 기준, BC 격리로 enum 직접 import 금지).
- **유비쿼터스 언어**. "채널 매핑(channel mapping)", "프로젝트 활동 피드(project activity feed)", "이벤트 필터(event filter)". glossary에 신규 용어 추가 후보.
- **기존 코드 지형 (검증 완료)**.
  - 이벤트 소스 `q_issue_events` — **projectKey 보유**(`NotificationWorker.buildSourceEvent`). 단 **경쟁 소비 큐** — 두 번째 consumer 금지(companion KDoc 명시). fan-out은 용도별 큐 분리 필요.
  - 현행 Slack 경로 = 수신자 단위 DM (`notification.SlackChannelSender` → `q_slack_deliveries` → `slack.SlackDeliveryWorker`). projectId 없음, per-recipient granularity.
  - 채널 브로드캐스트는 **이벤트당 1회**(수신자 무관) 라 DM 경로 재사용 불가.
  - 재사용 자산. `SlackMessageClient.chat.postMessage`(채널 게시 가능), `SlackBlockKitRenderer`, `SlackBotTokenResolver`(teamId→봇토큰), `SystemPermissionResolver` 포트(관리자 가드), pgmq 워커 패턴(vt/재시도/dead-letter).
- **관련 ADR**. [[2026-07-10-fr-sl-02-slack-notification-delivery]](큐 경계 패턴), [[2026-07-07-fr-sl-01-slack-bot-app]](BC 신설 원칙). 충돌 없음. 본 FR로 신규 ADR 1건 예상(라우팅 팬아웃 결정).
- **핵심 결정 (Maxi 확정 2026-07-13)**.
  1. **라우팅 팬아웃 = notification 브로드캐스트**. notification `NotificationWorker.dispatch()`가 이벤트당 1회 프로젝트 브로드캐스트를 **새 큐 `q_slack_channel_broadcasts`**로 발행(projectKey 보유). slack BC 새 워커가 소비 → `slack_channel_project_map`(projectKey+eventType) 조회 → event_filter 적용 → 채널당 1회 `chat.postMessage`. JSON 큐 경계 = FR-SL-02 `SlackChannelSender` 패턴 재사용. 매핑 키 = **projectKey(String)** (라우팅 시점 cross-BC 조회 회피 — 스펙 `project_id` 컬럼은 projectKey deviation).
  2. **CRUD 권한 = 프로젝트 관리자**. `AutomationPermissionResolver` 패턴 미러 → **신규 cross-BC 포트 `SlackChannelMappingPermissionResolver`**(shared-kernel, Boolean fail-closed, actorId:UUID + projectKey:String, prod adapter=identity-access @Profile prod, non-prod=consumer stub). 소비자(slack 컨트롤러)가 거부 시 일반 403.
- **BC 경계**. 한 PR = slack BC(매핑/CRUD/워커/포트) + notification BC(브로드캐스트 producer 1개). notification은 이미 slack용 producer(`SlackChannelSender`) 보유 — 확립된 경계 내 확장. 큐/포트 JSON·인터페이스 경계만 공유, 도메인 타입 직접 import 금지.

## 스펙

전체 스펙. [docs/specs/2026-07-13-fr-sl-06-channel-mapping.md](../specs/2026-07-13-fr-sl-06-channel-mapping.md)

**스코프**. 이 PR = 백엔드 코어 D1~D5. D6 UI/D7 E2E는 후속 PR(FR-SL-01/02 관례).

핵심 시나리오 요약.
- 프로젝트 관리자가 프로젝트↔Slack 채널 매핑(+이벤트 필터) CRUD. 비관리자 403(fail-closed).
- 프로젝트 이벤트 발생 → notification이 `q_slack_channel_broadcasts`로 이벤트당 1회 브로드캐스트 → slack 워커가 event_filter 매칭 채널마다 1회 게시(채널별 effectively-once, 다대다 팬아웃).
- 보안등급 걸린 이슈는 채널 게시 제외(fail-closed, Maxi 확정).

신규 자산. `slack_channel_project_map`(V704) · CRUD API(`/api/v1/slack/channel-mappings`) · `SlackChannelBroadcaster`(notification) · slack 채널 워커 · cross-BC 포트 2종(`SlackChannelMappingPermissionResolver`·`IssueSecurityClassificationPort`).

## Brainstorming Check

✅ 통과 (1회). refinement 4건 반영 + team_id 단일설치 가정 문서화 + 보안 결정(보안등급 이슈 제외) Maxi 확정 → 신규 포트 FR9 추가. 잔여 gap 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

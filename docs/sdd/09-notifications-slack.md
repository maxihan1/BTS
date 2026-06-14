# 09. 알림 시스템 + Slack 통합

## 9.1 알림 시스템

### 9.1.1 채널

| 채널 | 용도 |
|---|---|
| 이메일 | 중요 알림, 일일 다이제스트 |
| 인앱 (Inbox) | 모든 알림, WebSocket 실시간 |
| Slack DM | 즉시성 높은 알림 (멘션, 할당) |
| Slack 채널 | 프로젝트별 활동 피드 |
| Webhook | 외부 시스템 통지 (FR-NT-05 — 전이 post-action 이벤트 발행 파이프라인 기반) |

### 9.1.2 이벤트별 알림 정책 (FR-NT-01)

| 이벤트 | 기본 수신자 |
|---|---|
| `issue.created` | Reporter, Watchers, ComponentLead |
| `issue.assigned` | Assignee (새), Assignee (이전) |
| `issue.transitioned` | Reporter, Assignee, Watchers |
| `issue.commented` | Reporter, Assignee, Watchers, Mentioned |
| `issue.due_soon` | Assignee |
| `issue.overdue` | Assignee, Reporter |
| `sprint.started/ended` | 프로젝트 멤버 |
| `automation.failed` | 규칙 소유자, ProjectAdmin |

### 9.1.3 발송 흐름

```
이벤트 발행 → pgmq notification_queue
↓
NotificationWorker.read()
  1. fanOut: 수신자 결정 (Reporter/Assignee/Watcher/Subscription)
  2. 각 수신자별:
     - NotificationSubscription 조회 (사용자 선호)
     - 채널 결정
     - 채널별 메시지 렌더링 (Handlebars 템플릿)
     - 채널별 발송 (이메일/Slack/Webhook)
  3. notification 테이블에 기록 (인앱 Inbox)
  4. WebSocket으로 인앱 실시간 푸시
```

## 9.2 인앱 Inbox (FR-UX-03)

| 필드 | 설명 |
|---|---|
| 수신함 / 읽음 / 보관함 탭 | 사용자별 필터 |
| 그룹화 | 같은 이슈의 여러 이벤트 묶음 |
| 일괄 읽음 처리 | 다중 선택 |
| 검색 | 텍스트 / 발신자 / 기간 |

## 9.3 Slack 통합 (FR-SL)

### 9.3.1 구성 방식: Slack App + Bot Token

Webhook만으로는 부족하므로 Slack App + Bot Token 방식 채택.

#### Bot Scopes
- `chat:write`, `chat:write.public` (메시지 발송)
- `links:read`, `links:write` (Unfurl)
- `commands` (Slash 명령)
- `app_mentions:read` (멘션 받기)
- `im:write` (DM 발송)

### 9.3.2 핵심 엔티티

```kotlin
data class SlackWorkspace(
    val id: Long,
    val teamId: String,           // Slack team ID
    val teamName: String,
    val botToken: String,         // 암호화 저장
    val installedBy: Long,        // Atlas user
    val installedAt: Instant,
)

data class SlackUserMapping(
    val atlasUserId: Long,
    val slackUserId: String,
    val workspaceId: Long,
    val linkedAt: Instant,
)

data class SlackChannelSubscription(
    val id: Long,
    val workspaceId: Long,
    val channelId: String,        // Slack channel ID
    val projectId: Long,          // Atlas project
    val events: List<String>,     // 구독 이벤트
)
```

### 9.3.3 Unfurl (FR-SL-03)

Slack에서 Atlas URL을 자동으로 카드 형태로 펼침.

```
사용자가 https://atlas.company.com/issues/PROJ-123 공유
↓
Slack → Atlas Webhook (link_shared event)
↓
Atlas: 사용자 권한 확인 (볼 수 있는 이슈만)
↓
Slack에 Block Kit 메시지 반환
  - 이슈 키 + 제목
  - 상태 + 우선순위 + 담당자
  - 빠른 액션 버튼 (상태 변경, 댓글)
```

### 9.3.4 Slash 명령 (FR-SL-04)

| 명령 | 동작 |
|---|---|
| `/atlas issue PROJ-123` | 이슈 정보 조회 |
| `/atlas create "제목"` | 이슈 생성 (채널 → 프로젝트 매핑) |
| `/atlas search "쿼리"` | AQL 검색 |
| `/atlas my` | 내 할당 이슈 |
| `/atlas help` | 도움말 |

### 9.3.5 인터랙티브 메시지 (FR-SL-05)

알림 메시지에 버튼 포함:

```
🔔 새 이슈 PROJ-123 할당됨
"로그인 페이지 버그 수정"
우선순위: Highest

[상세보기] [완료로 표시] [코멘트 추가]
```

## 9.4 멘션 (FR-MN)

- 이슈 본문/댓글에 `@username` 자동완성
- 멘션 시 즉시 알림 (이메일 + 인앱 + Slack DM)
- 그룹 멘션: `@team-name` (역할 기반)

## 9.5 알림 구독 (FR-NT-04)

사용자별 + 프로젝트별 알림 설정:

```yaml
NotificationSubscription:
  user_id: 100
  scope: global               # global / project:PROJ
  events:
    issue.assigned: [inapp, email, slack]
    issue.commented: [inapp]
    issue.transitioned: []    # 비활성
    digest.daily: [email]     # 일일 요약
  quiet_hours: "22:00-07:00"
  timezone: "Asia/Seoul"
```

## 9.6 Webhook 알림 채널 (FR-NT-05)

FR-NT-02에서 분리된 전용 FR. 워크플로우 전이 후처리(post-action)가 생성하는 이벤트를 외부 URL로 HTTP POST 하는 채널이다.

**구현 분할 (cross-BC).**
- **PR 1 (issue-tracking)**: `IssueApplicationService.transitionIssue()`가 `repo.applyTransition()` 성공 직후 `plan.emitEvents`를 pgmq 큐 `q_transition_events`에 발행(transaction outbox 정합). 신규 `TransitionEventPublisher`(@Component, `@Transactional(MANDATORY)`) 담당.
- **PR 2 (notification BC)**: `WebhookRequested` 이벤트 소비 + 외부 URL HTTP POST 디스패처.

**이벤트 계약.**

| 큐 | 메시지 형식 |
|---|---|
| `q_transition_events` | `{ "type": "<이벤트 type>", "payload": { ... } }` |

PR 2 디스패처는 `type == "WebhookRequested"`만 처리하고 나머지(WatcherAdded/NotificationRequested/AutomationRequested)는 미래 FR 소비처를 위해 무시한다.

**ADR**: [2026-06-14-fr-nt-05-transition-event-outbox.md](../decisions/2026-06-14-fr-nt-05-transition-event-outbox.md)

## 9.7 다음 챕터

- 검색 → [10. 검색/Export/Import](10-search-export-import.md)
- 권한 → [12. 권한 모델](12-permissions.md)
- API → [11. API 설계](11-api-design.md)

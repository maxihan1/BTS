# dedup 키에 commentId 를 싣는다 — 같은 순간의 두 댓글이 서로를 삼키는 문제 (FR-UX-03 · FR-CO-01)

> 티어: T2
> slug: notification-dedup-comment-id
> type: bugfix
> agent: backend-engineer
> 생성: 2026-09-05

## Brief

**Maxi 원문** — #458 후속 3건 중 2번. 「dedup 키에 commentId 없음 — 동일 occurredAt 댓글
2건이면 뒤엣것이 삼켜짐 (기존 동작)」

**티어 판정** — `backend/modules/notification/**/main` (BE_MAIN) → **T2**.
마이그레이션 없음(`notifications.dedup_key` 컬럼·UNIQUE 제약 그대로), shared-kernel 무관.

**BC** — notification 단일. `Notification.computeDedupKey` 와
`SlackChannelBroadcaster.computeDedupKey` 둘 다 `com.bts.notification` 안에 있다.
slack-integration 은 이 키를 **소비만** 한다(`SlackDeliveryWorker` 가
`deliveryLogRepository.exists(dedupKey)` 로 중복 차단) — 그쪽 코드는 건드리지 않는다.

## 결손

dedup 키의 구성 원소가 이벤트를 **유일하게 지목하지 못한다.**

| 계산기 | 현재 구성 원소 |
|---|---|
| `Notification.computeDedupKey` | eventType · issueKey · occurredAt · recipientUserId · channel |
| `SlackChannelBroadcaster.computeDedupKey` | projectKey · eventType · issueKey · occurredAt |

둘 다 「어느 댓글인가」를 모른다. 같은 이슈에 같은 순간(`occurredAt`) 댓글 2건이 달리면
두 알림의 키가 **바이트 단위로 같다**. `notifications.dedup_key` 는 UNIQUE 라 두 번째
`insertIfAbsent` 가 false 를 반환하고, 워커는 그것을 「재전달된 중복」으로 읽어
`notification_worker_duplicate_skipped` 로그만 남기고 발송을 건너뛴다.
슬랙 쪽도 같다 — `slack_delivery_skip_duplicate` 로 조용히 버린다.

**둘 다 조용하다.** 유실이 에러가 아니라 「멱등이 잘 동작했다」는 모습으로 나타난다.

## 이것이 이론이 아닌 이유

`occurredAt` 이 항상 `Instant.now(clock)` 이라면 충돌 확률은 사실상 0이다. 그런데
`CommentApplicationService` 에는 **호출자가 시각을 주는 경로**가 있다.

```kotlin
val effectiveCreatedAt = createdAt ?: Instant.now(clock)   // createdAt: Instant?
...
IssueCommented(..., occurredAt = effectiveCreatedAt)
```

import(`IssueImportAdapter`)로 들어오는 댓글은 원본 시스템의 타임스탬프를 그대로 가져온다.
Jira 등 외부 시스템의 댓글 시각은 **초 단위**인 경우가 흔하고, 같은 이슈에 같은 초에 달린
댓글 2건은 마이그레이션 데이터에서 드물지 않다. 그때 두 번째 댓글의 알림은 사라진다.

## Jira 대조

**대응 없음 — 준용할 ADS 패턴도 없다.**

이 변경에는 사용자 조작도 화면도 없다. dedup 키는 워커 내부의 멱등 판정용 해시이고,
바뀌는 것은 그 해시의 구성 원소뿐이다. Jira 에 대응하는 UI 개념이 존재하지 않으므로
조작 동작을 대조할 대상이 없고, ADS 는 화면 패턴 카탈로그라 준용할 항목도 없다.

사용자에게 노출되는 표면은 「같은 순간의 댓글 2건이면 알림이 1건만 온다」 하나이고,
그것을 「2건 온다」로 되돌리는 것이 이 PR 의 목적이다. 판정의 정본은 Jira 문서가 아니라
`notifications.dedup_key` UNIQUE 제약과 `slack_delivery_log.dedup_key` 다.

## 결정

### D1 — 원소를 더한다. 부재하면 **원소 자체를 뺀다**

```kotlin
val parts =
    listOfNotNull(
        eventType.wireValue,
        issueKey ?: "",
        occurredAt.toString(),
        recipientUserId.toString(),
        channel.name,
        commentId?.toString(),   // ← null 이면 리스트에서 사라진다
    )
```

`commentId?.toString() ?: ""` 로 **항상 붙이면** 구분자가 하나 더 생겨 댓글과 무관한
알림(담당자 지정·상태 전환·스프린트…)의 키까지 전부 바뀐다. `listOfNotNull` 은
commentId 가 없을 때 오늘과 **바이트 단위로 같은 문자열**을 만든다.

키가 바뀌면 배포 경계에 떠 있던 pgmq 메시지가 재전달될 때 새 키로 계산돼 UNIQUE 를
통과하고 중복 알림이 나간다. 그 위험을 **댓글 이벤트로만 가둔다.**

원소 개수가 5개인 경우와 6개인 경우는 구분자 수가 달라 서로 충돌하지 않는다.

### D2 — 파라미터는 기본값 `null`

`computeDedupKey` 호출 지점은 backend 전체에 27곳이고 그중 프로덕션은
`NotificationWorker.buildNotification` **한 곳**이다. 필수 파라미터로 만들면 26개 테스트
호출을 전부 고치게 되는데, 그 diff 는 이 변경의 내용과 무관한 잡음이다.

기본값의 위험 — 「미래의 호출자가 안 넘기고 조용히 옛 동작을 얻는다」 — 는 도메인
테스트로는 못 막는다. 그래서 **워커 레벨 테스트**를 둔다(아래 T3). 워커가 실제로
commentId 를 넘기지 않으면 파라미터는 죽은 코드고, 그 사실이 red 로 드러난다.

### D3 — 슬랙 브로드캐스터도 같이 고친다

같은 결손이 같은 BC 안에 두 벌 있다. 한쪽만 고치면 「인박스는 두 건, 슬랙은 한 건」이라는
새로운 불일치가 생긴다. 슬랙 쪽 키는 recipient 를 안 쓰는 **이벤트 레벨** 키라 구성 원소
목록이 다르지만, 결손과 처방은 동일하다.

### D4 — 마이그레이션 없음

`notifications.dedup_key`(TEXT · UNIQUE)와 `slack_delivery_log.dedup_key` 는 값의 **내용**만
바뀌고 길이(64자 hex)도 타입도 제약도 그대로다. 기존 행은 옛 키를 그대로 들고 있으면 된다 —
dedup 키는 조회 조건이 아니라 삽입 시점의 충돌 판정에만 쓰인다. T3 로 뛰지 않는다.

## Task

| # | 내용 | 파일 |
|---|---|---|
| T1 | red — commentId 가 다르면 키가 다르다 / commentId 가 없으면 옛 키와 동일하다(golden hex) | `NotificationTest.kt` |
| T2 | green — `computeDedupKey(commentId: UUID? = null)` + `listOfNotNull` | `Notification.kt` |
| T3 | red→green — 워커가 commentId 를 넘긴다: 같은 occurredAt · 다른 commentId 두 이벤트의 `dedupKey` 가 다르다 | `NotificationWorkerTest.kt` · `NotificationWorker.kt` |
| T4 | red→green — 슬랙 브로드캐스트 키도 commentId 를 반영한다 | `SlackChannelBroadcasterTest.kt` · `SlackChannelBroadcaster.kt` |

## 영향 범위

- `backend/modules/notification/src/main/kotlin/com/bts/notification/domain/Notification.kt`
- `backend/modules/notification/src/main/kotlin/com/bts/notification/worker/NotificationWorker.kt`
- `backend/modules/notification/src/main/kotlin/com/bts/notification/channel/SlackChannelBroadcaster.kt`
- 테스트 3파일. 마이그레이션·API 스키마·프론트 변경 없음.

## 배포 시 알아 둘 것

댓글에서 비롯된 알림(`issue.commented` · 댓글 멘션)의 dedup 키가 배포 시점에 바뀐다.
배포 순간 pgmq 에 떠 있던 **댓글 이벤트**가 재전달되면 옛 키 행이 있어도 새 키로 삽입돼
중복 알림 1건이 나갈 수 있다. 창은 큐 잔량만큼이고 한 번뿐이다.
댓글과 무관한 알림은 키가 그대로라 이 창이 없다(D1).

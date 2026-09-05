# 멘션 알림 + 멘션 자동 watcher (FR-MN-03)

> 티어: T2
> slug: mention-notify-autowatch
> type: backend
> agent: backend-engineer
> 생성: 2026-09-05

## Brief

**Maxi 원문** — 「댓글과 이슈 수정 시 멘션이 달리면 멘션 받은 사람에게 알림이 가고 이슈 지켜보기가 활성화 되어야해」

**classify 결과와 정정** — `classify-task.ts` 는 `slug=watcher · tier=T1` 을 냈으나 둘 다 정정했다.
제목 휴리스틱은 바뀔 **경로**를 못 보는데 실제 표면은 `backend/**/main`(BE_MAIN)+이벤트 계약이라
T2 가 정본이다. slug 도 작업 범위를 못 담아 `mention-notify-autowatch` 로 고쳤다.
`type=backend · agent=backend-engineer · primary_bc=issue-tracking` 은 채택.

**FR ID** — `docs/specs` 실측으로 FR-MN-01·FR-MN-02 만 선점되어 있어 **FR-MN-03** 을 쓴다
(fr-index grep 이 아니라 specs 디렉터리에서 확인 — learnings 2026-07-17).

### 범위 — 3경로 × 2기능

| 경로 | 멘션 알림 | 자동 watcher |
|---|---|---|
| 이슈 **수정** (description) | 이미 동작 (`publishMentions`) | **추가** |
| 이슈 **생성** (description) | **추가** | **추가** |
| **댓글** (작성·수정) | **추가** | **추가** |

댓글 수정은 설명과 같은 diff 규칙 — 새로 추가된 멘션만 발행한다.
자기 멘션 제외 · 미존재 username 드롭 · `MAX_MENTIONS_PER_EVENT` 캡은 기존 `publishMentions` 규칙을 승계한다.

### Jira 패리티 편차 (의도적)

멘션 → 자동 watcher 는 **Jira Cloud 에 없는 동작**이다. Jira 의 autowatch 는 「본인이 생성했거나
본인이 댓글을 단」이슈만 대상이고, 멘션은 알림만 보낸다. 멘션 자동 watcher 는
[JRASERVER-27430](https://jira.atlassian.com/browse/JRASERVER-27430) 으로 2012년부터 열려 있는
**미구현 기능 요청**이며 실무에선 Automation 룰로 우회한다(조회일 2026-09-05).

Maxi 가 2026-09-05 의도적 이탈로 확정 → `docs/design/jira-parity-contract.md` 에 편차로 명시해야 한다.

### 실측 현황 (착수 시점)

- `publishMentions` 는 `IssueApplicationService.kt:602`(updateIssue) **한 곳**에서만 호출되고 `sourceField="description"` 고정.
- `CommentApplicationService.insertComment` 는 `MentionParser` 를 호출하지 않고, `IssueCommented`(`IssueDomainEvent.kt:202`) 페이로드에 `mentionedUserIds` 필드가 없다.
- `EventRecipientResolver.kt:152` 는 `event.mentionedUserIds` 만 보므로 댓글 멘션 수신자는 0명이다.
- 자동 watcher 는 FR-WT-01 로 `createIssue`(reporter+assignee) · `changeAssignee` · 컴포넌트 자동재배정 **3진입점**만 존재. 멘션 기반 없음.
- 정책 시드 `V403` 에 `issue.mentioned × MENTIONED × IN_APP` 전역 기본이 있다.
- 프론트 자동완성·렌더링(TipTap `mention-extension.tsx`)은 설명·댓글·생성 폼 전부 이미 동작 — 프론트 변경 불요 예상.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

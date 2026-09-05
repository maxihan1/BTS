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

## Jira 대조

> 조회일 **2026-09-05**. 근거 도메인은 계약 §1 허용 5종 중 `support.atlassian.com`(Cloud 사용자 문서 · 1순위)과
> `community.atlassian.com`(Atlassian 공식 아티클)만 썼다. 기능 요청 트래커(`jira.atlassian.com`)는 허용 표에 없어
> **보조 언급**으로만 두고, 판단은 support 문서의 autowatch 정의로 내린다.

### 조작감 갭 (J)

| # | 조작 | Jira Cloud 실동작 (원문 인용) | 출처 · 구분 | BTS 현재 | 판정 |
|---|---|---|---|---|---|
| J1 | @멘션 → 알림 | *"Someone mentions you"* 개인 설정으로 켜지고, *"Important notifications will come through immediately, like mentions"* — 10분 묶음배치를 **우회해 즉시** 발송 | [personal settings](https://support.atlassian.com/jira-software-cloud/docs/manage-your-jira-personal-settings/) · Cloud | 이슈 **수정**만 발행(`publishMentions`) · 댓글·생성 0 | **채택** — 3경로로 확장 |
| J2 | 멘션 알림 조건 | *"If Emily does not have the Browse Users and Groups global permission, she won't be able to trigger email notifications for Robert when she mentions him."* 수신자의 이슈 열람 권한도 필요 | [mention 알림 KB](https://support.atlassian.com/jira/kb/troubleshooting-missing-email-notifications-for-user-mentions/) · Cloud | `UserLookupPort` 해석 실패분 자동 드롭 · 열람 권한 검사 미확인 | **채택** — D2 에서 수신자 열람 권한 확인 |
| J3 | autowatch 범위 | *"With this enabled, you become a watcher of any work item that you create or comment on."* — **생성·댓글 두 가지뿐** | [personal settings](https://support.atlassian.com/jira-software-cloud/docs/manage-your-jira-personal-settings/) · Cloud | 생성(reporter+assignee)·배정 시 자동 watcher(FR-WT-01) · **본인 댓글 autowatch 없음** | **부분 미채택** → X2 |
| J4 | @멘션 → watcher | Cloud 문서에 **대응 동작 없음**. autowatch 정의(J3)가 생성·댓글로 한정돼 멘션은 포함되지 않는다. 실무 표준은 *"create an automation rule triggered on Issue Commented to extract the mentioned users' accountId values and then add them as watchers"* | [Auto-Add Watchers When User Is Mentioned](https://community.atlassian.com/forums/Jira-articles/Auto-Add-Watchers-When-User-Is-Mentioned/ba-p/2556829) · Cloud · Atlassian 공식 아티클 | 없음 | **대응 없음** → X1 |

### 의도적 편차 (X)

| # | 편차 | 근거 |
|---|---|---|
| X1 | **멘션된 사용자를 watcher 로 자동 등록한다** — Jira Cloud 는 하지 않는다 | Maxi 확정 2026-09-05. **「Jira Cloud 가 그렇게 한다」고 주장하지 않는다** — J4 대로 Cloud 문서에 대응 동작이 없고, Atlassian 자신이 Automation 룰 우회를 안내한다(기능 요청 JRASERVER-27430 은 2012년부터 미구현 · 보조 언급). BTS 는 룰 엔진 없이 기본 동작으로 넣는다 — 멘션의 의도가 「이 사람을 대화에 끌어들인다」이므로 후속 변경 수신이 기본값이어야 한다는 판단 |
| X2 | **본인이 댓글을 달아도 자동 watcher 가 되지 않는다** (J3 의 절반 미채택) | 이번 FR 범위 밖. Jira 는 하지만 BTS 의 FR-WT-01 자동 watcher 진입점은 생성·배정 축이고, 댓글 작성자 autowatch 는 별도 결정이 필요하다. **조회했고 대응이 있으나 이번엔 채택하지 않는다**는 기록으로 남긴다 |

## 도메인 정리

**BC**. `issue-tracking` **단일**. `notification` 은 소비측이며 **코드 변경 0** 이다 —
`EventRecipientResolver.resolveMentioned` 가 이미 `event.mentionedUserIds` 를 읽는다.

**영향 엔티티**. `Issue`(description) · `Comment`(body) · `issue_watchers` · 도메인 이벤트 `IssueMentioned`.

**새 용어**. 없음. 「워처」는 **이미 등재돼 있다** — `glossary.md:53 | 워처 | Watcher. 이슈 변경 알림 수신자`. 이 FR 은 그 정의를 넓히지 않고 **등록 계기만** 추가하므로 glossary 갱신 불요다.
> ⚠ 착수 시 이 자리에 「헤딩이 없어 충돌 대상이 없다」고 적었는데 **거짓 근거였다** — `^## ` 헤딩만 grep 했고 glossary 는 표 행 형식이다. 결론은 같지만 근거가 틀렸으므로 리뷰에서 교정했다.

**관련 ADR**. 직접 무효화 **0건**. 인접 2건은 전제로만 쓴다 —
`docs/decisions/2026-07-27-fr-co-comment-feature.md`(댓글 도입) ·
`docs/decisions/2026-06-11-notification-policy-bc-bootstrap.md`(정책 매칭 구조).

**기존 결정 충돌**. 없음. 오히려 `IssueDomainEvent.kt:134` KDoc 이
*"현재 `"description"`, **향후 댓글 지원 시 `"comment"`**"* 로 이 확장을 **미리 예고**해 뒀다.

### 착수 전 실측으로 확정한 설계 쟁점 6건

| # | 쟁점 | 실측 | 결정 |
|---|---|---|---|
| 1 | 댓글 멘션 이벤트 형태 | `IssueCommented` 에 `mentionedUserIds` 없음 · `IssueMentioned` KDoc 이 `"comment"` 예고 | **`IssueMentioned` 재사용**. `notification` 무변경 |
| 2 | `sourceField` 확장 | main 코드 소비처 **0곳**(분기 없음 · write-only) | `"description"` \| `"comment"` 두 값. 상수로 승격 |
| 3 | watcher 등록 지점 | `IssueApplicationService` 에 `autoWatch(issueId, userIds)` private 헬퍼가 **이미 존재**(멱등) | 같은 BC 안에서 **repo 직접 호출**. cross-BC 쓰기 금지 유지 |
| 4 | unwatch 후 재멘션 | `IssueWatcherRepository.add` 는 `ON CONFLICT DO NOTHING` — 없는 행은 **새로 넣는다** | **재추가한다**. FR-WT-01 「해제 허용·재배정 시 재추가」와 대칭 |
| 5 | 보안 레벨 이슈 누출 | `EventRecipientResolver.applyVisibilityFilter` 가 **MENTIONED 포함 전 역할**에 fail-closed 로 걸린다(포트 예외 전파) | **알림은 이미 안전**. watcher 등록은 FR-WT-01 타인 추가 선례대로 대상 가시성 미검사 |
| 6 | 마이그레이션 | `issue_watchers` · `q_issue_events` 재사용 · V403 정책이 `sourceField` 무관하게 매칭 | **마이그레이션 0 · 시드 0 → T2 유지** |

## 스펙

### 사용자 시나리오 (Given-When-Then)

1. **댓글 멘션 알림**. Given B 가 이슈를 볼 수 있다, When A 가 댓글에 `@B` 를 써서 저장한다, Then B 의 인박스에 멘션 알림이 도착하고 B 가 그 이슈의 watcher 가 된다.
2. **생성 멘션**. Given 새 이슈, When A 가 description 에 `@B` 를 넣어 생성한다, Then B 에게 알림 + B 가 watcher.
3. **수정 멘션의 watcher**. Given 이미 알림은 가던 경로, When A 가 description 을 고쳐 `@B` 를 새로 넣는다, Then 기존대로 알림이 가고 **추가로** B 가 watcher 가 된다.
4. **댓글 수정 diff**. Given 댓글에 `@B` 가 이미 있다, When A 가 그 댓글에 `@C` 를 덧붙인다, Then **C 에게만** 알림이 가고 B 는 재알림받지 않는다. C 만 새로 watcher 가 된다.
5. **자기 멘션**. Given A 가 작성자다, When A 가 `@A` 를 쓴다, Then 알림 0건이고 A 는 watcher 로 추가되지 않는다(생성·배정 축으로 이미 watcher 일 수는 있다).
6. **보안 레벨**. Given B 가 볼 수 없는 보안 레벨 이슈, When A 가 `@B` 를 쓴다, Then B 에게 알림이 가지 않는다(가시성 필터 fail-closed).
7. **미존재 사용자**. Given `@nobody` 는 실재하지 않는다, When 저장한다, Then 이벤트 발행도 watcher 추가도 없고 저장은 성공한다.

### Jira 대조

이 문서 상단 `## Jira 대조` 절 참조 — J1~J4 + 의도적 편차 X1~X2. 조회일 2026-09-05.

### 기능 요구사항 (FR)

- **FR-1**. `createIssue` 가 description 의 멘션을 해석해 `IssueMentioned(sourceField="description")` 를 발행한다. 생성이므로 diff 대상이 없어 **전체 멘션**이 신규다.
- **FR-2**. `CommentApplicationService.insertComment` 가 body 의 멘션을 해석해 `IssueMentioned(sourceField="comment")` 를 발행한다.
- **FR-3**. `CommentApplicationService.update` 는 **diff** 로 발행한다 — `extract(new) - extract(existing.body)`. 기존 no-op 조기 반환(`existing.body == body`)은 그대로 유지되며 그 경로에서는 발행하지 않는다.
- **FR-4**. 위 3경로 + 기존 `updateIssue` 경로 **모두**에서, 발행 대상으로 확정된 `mentionedUserIds` 를 그 이슈의 watcher 로 자동 등록한다. 멱등(`ON CONFLICT DO NOTHING`).
- **FR-5**. 승계 규칙은 `publishMentions` 와 **동일**하다 — 자기 멘션 제외 · 미존재 username 드롭 · `MAX_MENTIONS_PER_EVENT` 캡(초과분 알파벳 오름차순 절단 + WARN 로그) · `mentionedUserIds` UUID 오름차순 정렬.
- **FR-6**. 발행 대상이 0명이면 이벤트를 발행하지 않고 watcher 도 추가하지 않는다.
- **FR-7**. `sourceField` 의 두 값을 리터럴이 아닌 **상수**로 승격한다. 값 집합이 둘 이상이 되는 순간 오타가 조용히 통과하기 때문이다.
- **FR-8**. 멘션 해석·발행·watcher 등록은 본체 변경과 **같은 트랜잭션** 안에서 일어난다(`IssueEventPublisher` 가 `Propagation.MANDATORY`).
- **FR-9**. `IssueMentioned` 에 `commentId: UUID? = null` 을 추가한다. `sourceField == "comment"` 일 때만 non-null 이다. **이번 PR 에서 읽는 소비처는 없다** — 이벤트 스키마 변경이 되돌리기 비싼 축이라 1회로 끝내려는 선반영이고(Maxi 확정 2026-09-05), 후속 인박스 딥링크 FR 이 이 필드를 쓴다. 기본값이 있으므로 기존 역직렬화는 깨지지 않는다.

### 비기능 요구사항 (NFR)

- 댓글 작성 p95 300ms 유지 — 멘션 경로가 추가하는 쿼리는 `findIdsByUsernames` 1회 + watcher `add` N회(N ≤ cap).
- 멘션 0건인 댓글(대다수)은 `MentionParser.extract` 가 빈 집합을 내는 즉시 조기 반환해 **추가 쿼리 0**.

### API 인터페이스 (REST)

**신규 엔드포인트 0.** 기존 4경로의 부수효과만 확장한다 —
`POST /api/v1/issues` · `PATCH /api/v1/issues/{key}` · `POST /api/v1/issues/{key}/comments` · `PATCH …/comments/{id}`.
응답 스키마 무변경. watcher 목록은 기존 `GET /api/v1/issues/{key}/watchers` 로 관측된다.

### 데이터 모델 변경

**DB 변경 없음.** `issue_watchers`(복합 PK 멱등) · `q_issue_events`(pgmq) 재사용. 마이그레이션 0 · 정책 시드 0 —
V403 의 `issue.mentioned × MENTIONED × IN_APP` 이 `sourceField` 와 무관하게 매칭된다.

**이벤트 스키마 변경 1건**. `IssueMentioned` 에 `commentId: UUID? = null` 추가(FR-9). 기본값이 있어 **기존 큐에 남은 메시지의 역직렬화가 깨지지 않는다** — 이 점을 D5 에서 라운드트립 테스트로 실증한다.

### 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| E1 | 댓글 본문이 코드블록뿐 (`` ```@b``` ``) | `MentionParser` 가 코드 제거 후 0건 → 발행·watcher 0 |
| E2 | 댓글 수정으로 멘션을 **삭제** | 발행 0 · 이미 붙은 watcher 는 **유지**(제거하지 않는다 — FR-WT-01 「자동 제거 안 함」과 대칭) |
| E3 | 같은 댓글에 `@b` 를 두 번 | dedup 되어 1건 |
| E4 | 멘션 대상이 이미 watcher | `ON CONFLICT DO NOTHING` 으로 무변경, 알림은 정상 발행 |
| E5 | 캡 초과(51명 멘션) | 알파벳 오름차순 50명만 · WARN 로그 · **watcher 도 그 50명만**(알림과 대상이 갈리면 안 된다) |
| E6 | 자기 멘션만 있는 댓글 | 대상 0명 → 발행 0 · watcher 0 |
| E7 | 아카이브된 프로젝트 | 기존 `archiveGuard` 가 본체에서 먼저 차단 — 멘션 경로 도달 안 함 |
| E8 | 소프트 삭제된 댓글의 멘션 | 삭제 이벤트는 `IssueCommentDeleted` 소관. 멘션 회수는 하지 않는다(E2 와 같은 논리) |
| **E9** | **`cloneIssue` 로 복제된 description 의 멘션** | **재발행하지 않는다.** `cloneIssue`(`IssueApplicationService.kt:382-405`)는 `createIssue` 를 경유하지 않는 별도 함수라 이 FR 의 변경이 자동으로 닿지 않는다. **의도적 제외다** — 멘션 대상은 원본에서 이미 알림을 받았고, 복제할 때마다 다시 알리면 대량 복제가 알림 폭탄이 된다. 코드 변경 0, 테스트는 「클론 시 `IssueMentioned` 발행 0건」 회귀 1건으로 이 판단을 고정한다 |

### 제약 조건

- **BC 격리**. `notification` 모듈 코드를 건드리지 않는다. watcher 쓰기는 `issue-tracking` 안에서만 한다.
- **즉사 계약**. `MentionParser.MENTION_PATTERN` 을 수정하지 않는다 — `MentionExtension` 의 `\G` 앵커 파생이 같은 패턴을 공유하며, 건드리면 렌더링이 함께 깨진다(`IssueDomainEvent`·`MentionExtension.kt:85` 가 명시 금지).
- **TDD red-first**. T2 이므로 `test:` 커밋이 `feat:` 앞에 온다.

### 측정 가능한 완료 기준

1. 4경로 × (알림 발행 · watcher 등록) **8조합** 각각에 red-first 단위 테스트가 있고 통과한다.
2. E1~E8 **8건** 회귀 테스트가 있고 통과한다.
3. Testcontainers 통합 테스트가 댓글 멘션 → `q_issue_events` enqueue 를 실증한다.
4. E2E `issue-mention-render.spec.ts` 계열에 「댓글 멘션 → Inbox 도착 + watcher 목록 반영」 시나리오가 추가되어 통과한다.
5. `./gradlew :modules:issue-tracking:test ktlintCheck detekt` 초록.
6. `commentId` 부재 JSON(기존 형식)이 `IssueMentioned` 로 역직렬화되는 하위호환 테스트가 통과한다.
7. **뮤테이션 짝** — 각 신규 판정에 대해 일부러 끊어 red 1회를 확인하고 GREEN 선커밋 뒤에 검증한다.

## Sanity Check

❓ **발견 3건** — 2건은 스펙에 반영했고, 1건은 Maxi 결정이 필요하다.

1. **(반영)** 캡 초과 시 알림 대상과 watcher 대상이 갈릴 수 있었다 → E5 로 「같은 50명」 못박음.
2. **(반영)** 멘션 삭제·댓글 삭제 시 watcher 회수 여부가 비어 있었다 → E2·E8 로 「회수 안 함」 명시.
3. **(해소 — Maxi 확정 2026-09-05)** `IssueMentioned.commentId` 를 **지금 넣는다**(nullable · FR-9).
   근거는 스키마 변경 횟수다 — 소비처가 없어 지금 비용은 거의 0 이고, 나중에 딥링크를 붙일 때
   넣으면 되돌리기 비싼 축을 두 번 건드리게 된다.

**범위 경계 (Maxi 확정 2026-09-05)**. 인박스 딥링크(알림 클릭 → 해당 댓글로 스크롤)는 **이번 PR 제외**다.
`notification` 의 제목/링크 빌더 + `apps/web` 인박스 + 댓글 앵커까지 걸려 **한 PR = 한 BC** 규칙을 깬다.
후속 FR 로 등재하고, 이번 PR 은 그 FR 이 쓸 `commentId` 만 이벤트에 남겨 둔다.

✅ 그 외 통과 — 6쟁점 전부 실측 근거로 확정, DB 마이그레이션 0 으로 T2 유지 확인.


## Plan

### Task 1. 이벤트 계약 확장 — `commentId` + 멘션 출처 상수

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/mention/MentionSource.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueDomainEventTest.kt`]
- depends-on: []
- jira: []

**RED**:
- 파일: `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueDomainEventTest.kt`
- 테스트 2건.
  ```kotlin
  @Test fun `commentId 가 없는 기존 JSON 도 역직렬화된다`()   // 하위호환 — 큐에 남은 메시지
  @Test fun `sourceField=comment + commentId 라운드트립`()
  ```
- 실패 메시지 (예상). `IssueMentioned` 에 `commentId` 파라미터 없음 / `MentionSource` 미해결

**GREEN**: `IssueMentioned` 에 `commentId: UUID? = null` 추가(**기본값 필수** — 없으면 기존 메시지 역직렬화가 깨진다). `MentionSource` object 에 `DESCRIPTION`·`COMMENT` 상수.

**REFACTOR**: `sourceField` KDoc 을 「현재/향후」 서술에서 **확정 값 집합**으로 갱신. `IssueApplicationService:1442` 의 리터럴 `"description"` 을 상수 참조로 교체.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*IssueDomainEventTest')`

---

### Task 2. `MentionTargetResolver` 추출 — 멘션 대상 산출을 순수 함수로

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/mention/MentionTargetResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/mention/MentionTargetResolverTest.kt`]
- depends-on: [1]
- jira: []

**RED**:
- 파일: `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/mention/MentionTargetResolverTest.kt` (신규)
- 테스트: 전체 모드(생성·댓글 작성)와 diff 모드(수정) 두 진입, 자기제외, 미존재 드롭, 캡 초과 절단(알파벳 오름차순), UUID 오름차순 정렬, 대상 0명.
  ```kotlin
  @Test fun `전체 모드는 본문의 모든 멘션을 대상으로 낸다`()
  @Test fun `diff 모드는 before 에 이미 있던 멘션을 뺀다`()
  @Test fun `캡 초과 시 알파벳 오름차순 앞부분만 남기고 드롭 수를 낸다`()
  ```
- 실패 메시지 (예상). `MentionTargetResolver` 클래스 없음

**GREEN**: `IssueApplicationService.publishMentions` 안에 인라인돼 있던 「추출 → diff → 캡 → `findIdsByUsernames` 해석 → 자기제외 → UUID 정렬」을 그대로 옮긴 순수 객체. `UserLookupPort` 는 **인자로 받는다**(빈 주입 아님 — `MentionParser` 와 동형).

**REFACTOR**: 드롭 수를 반환값에 실어 호출자가 WARN 로그를 남기게 한다(로깅을 순수 함수 안에 두지 않는다).

**⚠ 즉사 계약**: `MentionParser.MENTION_PATTERN` 을 **건드리지 않는다** — `MentionExtension.kt:85` 가 `\G` 앵커를 이 패턴에서 파생하므로 수정하면 **렌더링이 함께 깨진다**.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*MentionTargetResolverTest')`

---

### Task 3. `IssueApplicationService` 이관 — 행동 불변 + `updateIssue` 자동 watcher

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceMentionTest.kt`]
- depends-on: [2]
- jira: [J1]

**RED**:
- 기존 `IssueApplicationServiceMentionTest` S1~S5+cap 이 **그대로 통과**해야 한다(이관은 행동 불변).
- 신규: 수정으로 새 멘션이 생기면 그 대상이 `issue_watchers` 에 추가된다.
  ```kotlin
  @Test fun `updateIssue 신규 멘션 대상이 자동 watcher 가 된다`()
  @Test fun `대상 0명이면 watcher 도 추가하지 않는다`()
  ```
- 실패 메시지 (예상). `watcherRepository.add` 호출 0회 (mockk verify 실패)

**GREEN**: `publishMentions` 가 `MentionTargetResolver` 를 호출하도록 교체. 확정된 `targets` 를 기존 private `autoWatch(issueId, userIds)` 에 그대로 넘긴다.

★**리뷰 R2 반영 — `autoWatch` 를 배치 INSERT 로**. 현재 구현은 `userIds.distinct().forEach { repo.add(issueId, userId) }`(`:2193`) 라 **1인당 INSERT 1회**다. 기존 호출자는 최대 2명(reporter+assignee)이라 안 드러났지만 멘션은 `MAX_MENTIONS_PER_EVENT` 까지 가므로 한 트랜잭션에서 최대 50 왕복이 된다. `IssueWatcherRepository` 에 다중 VALUES `addAll(issueId, userIds)` 를 더하고 `autoWatch` 가 그것을 쓴다 — `ON CONFLICT DO NOTHING` 은 다중 VALUES 에도 그대로 걸려 멱등이 유지된다. 기존 2명 호출자도 같은 경로를 타므로 분기하지 않는다.

**REFACTOR**: 발행과 watcher 등록이 **같은 `targets` 리스트**를 쓰는 것을 한 곳에서 보이게 정리 — 캡 초과 시 알림 대상과 watcher 대상이 갈리는 E5 를 구조로 막는다.

★**리뷰 R4 반영 — 이관의 뮤테이션 짝(필수)**. 이 task 는 「행동 불변」을 기존 `S1~S5+cap` 통과로 주장하는데, **통과가 무엇을 덮는지 모르면 가짜 그린**이다. GREEN 선커밋 뒤 `MentionTargetResolver` 호출을 일부러 끊어 기존 S1~S5 가 **실제로 red 를 내는지 1회 확인**한다. red 가 안 나면 그 테스트는 이관을 감시하지 못하는 것이므로 감시하는 테스트를 먼저 추가한다. 뮤테이션 직후 `grep -c MUTATION` 으로 **적용 건수를 되잰다** — 「걸었다」와 「걸렸다」는 다르고, BSD sed 미매치로 뮤테이션이 파일에 안 들어갔는데 초록이던 전례가 있다(학습 `mutation-must-verify-it-actually-applied`).

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*IssueApplicationServiceMentionTest')`

---

### Task 4. `createIssue` 멘션 발행 + 자동 watcher

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceMentionTest.kt`]
- depends-on: [3]
- jira: [J1]

**RED**:
  ```kotlin
  @Test fun `createIssue description 멘션 전원이 대상이 된다`()   // diff 대상 없음 = 전체
  @Test fun `createIssue 멘션 대상이 자동 watcher 가 된다`()
  @Test fun `자기 자신만 멘션하면 이벤트도 watcher 도 없다`()
  ```
- 실패 메시지 (예상). `IssueMentioned` 발행 0회

**GREEN**: `createIssue` 에서 `MentionTargetResolver` 전체 모드 호출 → `IssueMentioned(sourceField = MentionSource.DESCRIPTION)` 발행 + `autoWatch`. reporter/assignee 자동 watcher(FR-WT-01)와 **같은 트랜잭션**.

**REFACTOR**: 생성·수정 두 경로의 발행 코드를 한 private 헬퍼로 합류.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*IssueApplicationServiceMentionTest')`

---

### Task 5. `insertComment` 멘션 발행 + 자동 watcher

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/application/CommentApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/application/CommentApplicationServiceTest.kt`]
- depends-on: [2, 1]
- jira: [J1]

**RED**:
  ```kotlin
  @Test fun `댓글 작성 시 body 멘션 대상에게 IssueMentioned 를 발행한다`()  // sourceField=comment, commentId=그 댓글
  @Test fun `댓글 멘션 대상이 자동 watcher 가 된다`()
  @Test fun `멘션 0건 댓글은 이벤트도 watcher 도 없다`()   // 조기 반환 — 추가 쿼리 0
  ```
- 실패 메시지 (예상). `IssueMentioned` 발행 0회 / `IssueWatcherRepository` 미주입

**GREEN**: `IssueWatcherRepository` 를 **nullable 생성자 주입**(`IssueApplicationService:169` 선례 — 기존 단위 테스트 호환 fallback). `insertComment` 에서 전체 모드 해석 후 발행 + watcher. `IssueCommented` 발행은 **그대로 둔다**(역할이 다르다).

**REFACTOR**: 댓글 쪽 `autoWatch` 헬퍼를 `insertComment`·`update` 가 공유하도록 뽑는다.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*CommentApplicationServiceTest')`

---

### Task 6. 댓글 수정 diff 발행 + no-op 경로 무발행

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/application/CommentApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/application/CommentApplicationServiceTest.kt`]
- depends-on: [5]
- jira: [J1]

**RED**:
  ```kotlin
  @Test fun `댓글 수정은 새로 추가된 멘션만 발행한다`()        // @b 있던 댓글에 @c 추가 → c 만
  @Test fun `멘션을 지우는 수정은 발행 0 이고 기존 watcher 를 유지한다`()  // E2
  @Test fun `본문이 같은 no-op 수정은 발행 0`()               // 기존 조기 반환 경로
  ```
- 실패 메시지 (예상). 수정 시 `IssueMentioned` 발행 0회

**GREEN**: `update` 에서 `MentionTargetResolver` **diff 모드**(`before = existing.body`, `after = body`) 호출. 기존 `existing.body == body` 조기 반환은 **손대지 않는다** — 그 경로가 무발행의 근거다.

**REFACTOR**: `historyRecorder.recordCommentEdited` 호출 순서와 나란히 두어 부수효과를 한 곳에 모은다.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*CommentApplicationServiceTest')`

---

### Task 7. 엣지 E1~E8 회귀 + 뮤테이션 짝

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/mention/MentionTargetResolverTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/application/CommentApplicationServiceTest.kt`]
- depends-on: [6]
- jira: [J2]

**RED**: 스펙 `## 엣지 케이스` E1~**E9** 각 1건. **E9(클론 시 `IssueMentioned` 발행 0건)** 는 「빠뜨린 것이 아니라 정한 것」을 코드로 고정하는 회귀다. **E5(캡 초과 시 알림 대상 == watcher 대상)** 와 **E4(이미 watcher 여도 알림은 정상)** 가 핵심이다 — 둘은 「두 목록이 서로를 검사하지 않는」 양식이라 판정이 없으면 조용히 갈린다.

**J2 회귀**: 보안 레벨 이슈의 멘션 알림이 `applyVisibilityFilter` 로 막히는지는 **notification BC 소관**이라 이 PR 에서 코드를 건드리지 않는다. 대신 「`issue-tracking` 은 가시성 필터를 스스로 하지 않는다」는 **경계 사실**을 테스트 이름으로 남겨 후속 리뷰가 착각하지 않게 한다.

**GREEN**: 필요한 최소 보강만. 대부분은 Task 2~6 구현으로 이미 통과해야 한다 — **통과하면 그 자체가 근거이고, 실패하면 설계 구멍이다.**

**REFACTOR**: 뮤테이션 짝 — 각 신규 판정을 **일부러 끊어 red 1회**를 확인한다. **GREEN 선커밋 뒤에** 돌린다(미커밋 원복은 소실).

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test ktlintCheck detekt)`

---

### Task 8. Testcontainers 통합 — 댓글 멘션 pgmq enqueue

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueMentionPublishIntegrationTest.kt`]
- depends-on: [6]
- jira: [J1]

**RED**: 댓글 작성/수정이 `q_issue_events` 에 `issue.mentioned` 를 **실제로 넣는지**, 그리고 같은 트랜잭션이 롤백되면 **큐에도 안 남는지**.

**GREEN**: 기존 `IssueMentionPublishIntegrationTest`(설명 경로) 형태를 댓글 경로로 확장.

**REFACTOR**: 설명·댓글 두 경로가 같은 assert 헬퍼를 쓰게 한다.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*IssueMentionPublishIntegrationTest')` — Docker 필요

---

### Task 9. E2E — 댓글 멘션 → Inbox 도착 + watcher 목록 반영

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-mention-render.spec.ts`]
- depends-on: [8]
- jira: [J1]

**RED**: 시나리오 2건 — ① 댓글에 `@b` 를 써서 저장하면 b 의 Inbox 에 멘션 알림이 도착한다 ② 저장 직후 그 이슈의 watcher 목록에 b 가 보인다.

**GREEN**: MSW 멘션 파생을 댓글 POST/PATCH 로 확장(기존 description PATCH 파생과 같은 형태). **ground-truth 는 Task 8 의 백엔드 통합 테스트**이고 이건 화면 배선 확인이다.

**REFACTOR**: 기존 S1~S6 과 시드가 겹치지 않는지 확인.

**검증**: `pnpm --filter web test:e2e -- issue-mention-render`

⚠ **실행 전 필수** — 5173 을 점유한 프로세스의 cwd 가 **이 worktree 인지 증명**한다. 다른 worktree 의 서버가 포트를 쥐고 있으면 `reuseExistingServer:false` 여도 내 spec 이 **남의 앱을 재서** 가짜 red/green 이 만들어진다(학습 `port-5173-shared-across-worktrees-measures-wrong-app`).

## Plan 메타

- **task 수**. 9 (각 TDD 사이클 1개)
- **예상 wave**. 5 — `1 → 2 → 3 → 4 → (5 → 6) → (7·8) → 9`. `IssueApplicationService.kt` 와 `CommentApplicationService.kt` 가 각각 여러 task 에 걸려 **파일 겹침으로 자동 직렬화**된다. 진짜 병렬은 Task 7·8 한 구간뿐이다.
- **구현 규율**. TDD red-first (T2). `test:` 커밋이 `feat:` 앞에 온다.
- **추가 검증**. `./gradlew :modules:issue-tracking:test ktlintCheck detekt` · `pnpm --filter web test:e2e` · 뮤테이션 짝(GREEN 선커밋 뒤 · 적용 건수 `grep -c` 로 되재기).
- **⚠ 커밋 규율**. 같은 worktree 에서 여러 task 가 돌면 **`git add` 로는 못 막는다** — git 인덱스가 프로세스 간 공유라 add 를 좁혀도 커밋 시점 인덱스에 옆 task 파일이 남아 있으면 함께 커밋된다(2026-09-04 실측). **`git commit --only <경로>`** 를 쓴다.
- **실패 모드 결정(게이트 1 · Maxi 확정 2026-09-05)**. 멘션 해석 포트가 throw 하면 **트랜잭션 전체를 롤백해 댓글 저장도 실패**시킨다. 기존 `publishMentions` 와 같은 성질이라 신규 결함이 아니고, 「댓글은 저장됐는데 멘션 알림만 조용히 증발」보다 낫다는 판단이다.
- **Jira 매핑**. `J1 → T3·T4·T5·T6·T8·T9` · `J2 → T7(경계 사실 기록 — 구현은 notification BC 기구현)` · `J3 → 부분 미채택 X2(본인 댓글 autowatch 는 범위 밖)` · `J4 → 대응 없음 X1(의도적 편차)`. **채택 2건 모두 task 에 물렸다 — 차집합 0.**


## 리뷰 결과

**렌즈**. `/plan-eng-review` 1종 (TYPE=backend · T2 · UI 미포함이라 design 렌즈 미추가).
**아웃사이드 보이스**. 건너뜀 — `codex_reviews=disabled` 설정. 계약이 폴백 금지를 명시해 Claude 서브에이전트도 띄우지 않았다.
**Step 0 복잡도 게이트**. 11파일 · 신규 클래스 2개로 임계값(8파일) 초과 → `AskUserQuestion` 발행 → **Maxi 확정 A: 그대로 간다**(파일 수를 민 것은 추상화가 아니라 실재 호출지점 4개 + 그 테스트).

> **절차 편차 1건 (의도적)**. `plan-eng-review` 는 「이슈 1건 = `AskUserQuestion` 1회」를 요구하지만,
> 이 체인의 바깥 계약인 `bts-review-plan` 은 「plan 에 append 후 **합산해 게이트 1 에서 한 번에** 판정」이다.
> 바깥 계약을 따랐다 — 발견을 삼킨 것이 아니라 묶은 것이고, 전량이 아래에 있으며 게이트 1 이 그 질문 자리다.

### 발견 5건 (실측 인용 있는 것만)

| # | 심각도 | 확신 | 위치 | 내용 |
|---|---|---|---|---|
| R1 | P1 | 9/10 | `IssueApplicationService.kt:382-405` | **`cloneIssue` 가 5번째 생성 경로인데 plan 이 「4경로」로 세었다** |
| R2 | P2 | 9/10 | `IssueApplicationService.kt:2193` | **`autoWatch` 가 개별 INSERT N회** — 캡 50 이면 50 라운드트립 |
| R3 | P2 | 9/10 | `CommentApplicationService.kt:56` | `@Suppress("LongParameterList")` 주석의 **「협력자 6」이 7이 되어 거짓이 된다** |
| R4 | P1 | 10/10 | Task 3 | 「행동 불변」의 회귀 가드가 **기존 테스트 재사용뿐** |
| R5 | P2 | 10/10 | 스펙 §도메인 정리 | glossary 근거가 거짓이었다 (**교정 완료**) |

#### R1 — `cloneIssue` 가 세어지지 않은 다섯 번째 경로 (P1)

```kotlin
// IssueApplicationService.kt:382-391 — clone 은 createIssue 를 경유하지 않는 별도 함수다
val saved = repo.insert(clone)
eventPublisher.publish(IssueCreated(issueKey = saved.key, ...))
```

`cloneIssue` 는 `createIssue` 를 **경유하지 않고** `IssueCreated` 만 발행하며 `autoWatch` 도 부르지 않는다
(`:294-295` 의 createIssue 와 대비 · TODOS 6105 가 이미 「클론 배정자는 인앱 알림을 단 1건도 못 받는다」로 등재).
따라서 **원본 description 의 `@멘션` 을 그대로 복사한 클론본은 이 FR 이 끝나도 멘션 알림이 안 간다.**

「원본에서 이미 알렸으니 재알림 안 하는 게 맞다」가 유력한 답이지만 **plan 에 그 판단이 없다**.
판단이 없으면 다음 사람이 「빠뜨렸나 일부러인가」를 다시 판정해야 한다 — TODOS 6105 가 정확히 그 상태다.

**처방**. 엣지 케이스에 **E9** 를 추가해 「클론은 멘션 재발행하지 않는다 + 사유」를 못박는다. 코드 변경 0.

#### R2 — 자동 watcher 등록이 개별 INSERT N회 (P2)

```kotlin
// IssueApplicationService.kt:2192-2193
val repo = watcherRepository ?: return
userIds.distinct().forEach { userId -> repo.add(issueId, userId) }
```

기존 호출자는 **최대 2명**(reporter + assignee)이라 이 형태가 문제가 안 됐다.
멘션은 `MAX_MENTIONS_PER_EVENT` 까지 가므로 **한 트랜잭션 안에서 최대 50회 왕복**이 된다.
치명적이진 않지만(수 ms 규모) 「기존 사용처의 전제가 바뀌는데 구현은 그대로」인 전형적 자리다.

**처방**. Task 3 GREEN 에서 `IssueWatcherRepository` 에 배치 add 를 더하고 `autoWatch` 가 그것을 쓰게 한다.
`ON CONFLICT DO NOTHING` 은 다중 VALUES 에도 그대로 걸리므로 멱등은 유지된다.

#### R3 — 억제 주석이 거짓이 된다 (P2)

```kotlin
// CommentApplicationService.kt:56
@Suppress("LongParameterList") // 협력자 6 + clock. IssueAttachmentService 와 동일 사유(모듈 선례)
```

Task 5 가 `IssueWatcherRepository` 를 주입하면 협력자가 **7**이 되어 이 주석은 그 자리에서 거짓이 된다.
**처방**. Task 5 REFACTOR 에서 같은 커밋으로 주석을 갱신한다. 「주석 유지보수는 변경의 일부」.

#### R4 — 이관 리팩터의 가드가 부족하다 (P1)

Task 3 은 「기존 `IssueApplicationServiceMentionTest` S1~S5+cap 이 그대로 통과」를 행동 불변의 근거로 삼는다.
그런데 Task 2 REFACTOR 가 **드롭 수를 WARN 로그에서 반환값으로 옮긴다** — 로그 동작 변화는 기존 테스트가 잡지 않는다.
「통과했으니 불변」은 **통과가 무엇을 덮는지 모를 때 가짜 그린**이다.

**처방**. Task 3 에 뮤테이션 짝을 명시 요구한다 — `MentionTargetResolver` 호출을 일부러 끊어 기존 S1~S5 가
**실제로 red 를 내는지** 1회 확인한다. 안 내면 그 테스트는 이관을 감시하지 못한다.

#### R5 — 교정 완료

스펙 §도메인 정리의 「glossary 에 헤딩이 없다」가 거짓이었다(표 행으로 실재). 결론은 불변, 근거만 교정했다.

### 학습 반영 3건 (이번 세션 preamble 로드)

| 학습 | 반영처 |
|---|---|
| `mutation-must-verify-it-actually-applied` (10/10) | Task 7 REFACTOR — 뮤테이션 직후 `grep -c MUTATION` 으로 **적용 건수를 되잰다**. sed 미매치로 「걸었다 ≠ 걸렸다」가 실측된 전례 |
| `shared-worktree-git-index-defeats-narrow-git-add` (10/10) | Plan 메타 — 병렬 구간(T7·T8)은 `git add` 가 아니라 **`git commit --only <경로>`** 로 커밋. git 인덱스가 프로세스 간 공유라 좁힌 add 로는 못 막는다 |
| `port-5173-shared-across-worktrees-measures-wrong-app` (10/10) | Task 9 — e2e 실행 전 **5173 점유 프로세스의 cwd 가 이 worktree 인지 증명**. 남의 앱을 재면 가짜 red/green |

### NOT in scope

| 항목 | 사유 |
|---|---|
| 인박스 딥링크(알림 → 해당 댓글) | Maxi 확정 2026-09-05. `notification` + `apps/web` 까지 걸려 「한 PR = 한 BC」를 깬다. `commentId` 만 남겨 후속 FR 이 쓰게 한다 |
| `cloneIssue` 멘션·watcher | R1 로 **판단만** 명시(E9). 코드는 안 건드린다 — TODOS 6105 의 별건이고 회귀 표면을 동시에 넓히지 않는다 |
| 본인 댓글 작성 시 autowatch | Jira 는 하지만(J3) 이번 범위 밖. 편차 X2 로 기록 |
| 그룹 멘션(`@team`) | FR-MN-01 이 이미 이연. `glossary.md:79` 이 사용자 그룹의 후속 소비처로 예고 |
| `sourceField` 를 enum 으로 | 두 값뿐이고 이벤트는 JSON 직렬화 계약이라 String 상수가 더 안전하다 |

### What already exists — 재사용 대 재건축

| 자산 | 위치 | 이 plan 의 처리 |
|---|---|---|
| `MentionParser` (코드블록·이메일·`@@` 제외) | `mention/MentionParser.kt` | **재사용**. 패턴 수정 금지(즉사 계약) |
| `autoWatch` private 헬퍼 (멱등) | `IssueApplicationService.kt:2188` | **재사용** + R2 로 배치화 |
| `IssueWatcherRepository.add` (`ON CONFLICT DO NOTHING`) | `watcher/repository/` | **재사용** |
| `IssueMentioned` + `sourceField` 확장 여지 | `event/IssueDomainEvent.kt:134` | **재사용**. KDoc 이 이 확장을 예고해 뒀다 |
| `EventRecipientResolver.resolveMentioned` | notification BC | **재사용 · 코드 변경 0** |
| `applyVisibilityFilter` (fail-closed) | `EventRecipientResolver.kt:109-118` | **재사용**. 보안 레벨 누출 방어가 이미 있다 |
| V403 정책 시드 | `notification/db/migration/V403` | **재사용 · 시드 0** |
| TipTap 멘션 자동완성·렌더링 | `apps/web/src/components/editor/` | **재사용 · 프론트 변경 0 예상** |
| `nullable 생성자 주입` fallback 패턴 | `IssueApplicationService.kt:169` | **선례 승계** — Task 5 가 같은 형태 |

**재건축 0건.** 이 plan 이 새로 만드는 것은 `MentionTargetResolver`(4경로 공유를 위한 추출)와 `MentionSource`(상수 2개)뿐이다.

### 실패 모드 — 신규 코드경로별 1건

| 경로 | 현실적 실패 | 테스트 | 에러 처리 | 사용자가 보는 것 | 판정 |
|---|---|---|---|---|---|
| 댓글 멘션 발행 | `findIdsByUsernames` 가 DB 장애로 throw | Task 5 | **없음 — 전파** | 댓글 저장 자체가 500 으로 실패 | ⚠ **논의 필요**. 알림 때문에 댓글을 못 쓰는 게 맞나 |
| 자동 watcher 등록 | `issue_watchers` INSERT 충돌 | Task 3·5 | `ON CONFLICT DO NOTHING` | 정상 | ✅ |
| 캡 초과 | 51명 멘션 | Task 7 (E5) | 절단 + WARN | 50명만 알림·watcher | ✅ |
| 댓글 수정 diff | `existing.body` 가 null/공백 | Task 6 | `MentionParser` 가 빈 집합 | 발행 0 | ✅ |
| 이벤트 역직렬화 | 큐에 남은 구 JSON 에 `commentId` 부재 | Task 1 | 기본값 `null` | 정상 | ✅ |

**critical gap 0건.** 다만 첫 행은 **설계 판단이 필요**하다 — 현재 `publishMentions` 도 같은 성질이라
(수정 경로에서 포트가 throw 하면 이슈 수정이 실패한다) **신규 결함은 아니고 기존 성질의 확산**이다.
댓글은 이슈 수정보다 훨씬 자주 일어나므로 노출 빈도가 올라간다. 게이트 1 판단 항목으로 올린다.

### 병렬화 전략

| 단계 | 모듈 | 의존 |
|---|---|---|
| T1 이벤트 계약 | `issue-tracking/event`, `/mention` | — |
| T2 Resolver | `issue-tracking/mention` | T1 |
| T3·T4 이슈 경로 | `issue-tracking/application` | T2 |
| T5·T6 댓글 경로 | `issue-tracking/comment` | T2 |
| T7 엣지 | `issue-tracking/mention`, `/comment` | T6 |
| T8 통합 | `issue-tracking/application`(test) | T6 |
| T9 E2E | `apps/web/e2e` | T8 |

- **Lane A**. T3 → T4 (순차 · `application/` 공유)
- **Lane B**. T5 → T6 (순차 · `comment/` 공유)
- **실행 순서**. T1 → T2 → **A ∥ B** → T7 ∥ T8 → T9.

⚠ **충돌 플래그**. Lane A 와 B 는 모듈이 다르지만 **같은 worktree 의 git 인덱스를 공유**한다.
학습 `shared-worktree-git-index-defeats-narrow-git-add` 대로 `git add` 로는 못 막는다 — **`git commit --only <경로>`** 를 쓴다.

**판정**. BLOCKER **0건** · P1 2건(R1·R4) · P2 3건(R2·R3·R5).

### 게이트 1 처리 (Maxi 확정 2026-09-05 · 승인)

| 발견 | 처리 |
|---|---|
| R1 클론 경로 | **E9 신설** — 「재발행하지 않는다 + 사유(대량 복제 알림 폭탄)」를 못박고 Task 7 에 발행 0건 회귀를 물렸다. 코드 변경 0 |
| R2 개별 INSERT | **Task 3 GREEN 에 배치 `addAll` 추가**. 기존 2명 호출자도 같은 경로 |
| R3 억제 주석 | Task 5 REFACTOR 에서 같은 커밋으로 갱신(기존 기재 유지) |
| R4 이관 가드 | **Task 3 REFACTOR 에 뮤테이션 짝 필수화** + 적용 건수 되재기 |
| R5 glossary 근거 | 교정 완료 |
| 실패 모드 1행 | **현행 유지** — 멘션 해석 실패 시 댓글 저장도 롤백. 기존 성질과 일관 |


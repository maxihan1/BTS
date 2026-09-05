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

**새 용어**. 없음 — `Maxi_wiki/BTS/glossary.md` 에 「멘션」·「watcher」 헤딩이 없어 충돌 대상이 없다. glossary 갱신 불요.

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


## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

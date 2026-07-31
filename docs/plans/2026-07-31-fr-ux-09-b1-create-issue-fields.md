# FR-UX-09 B1 — 이슈 생성 시 담당자·우선순위·라벨 1회 제출 확정

> slug: fr-ux-09-b1-create-issue-fields
> type: feature
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-07-31

## Brief

**사용자 원문.**
FR-UX-09 B1 — 이슈 생성 시 담당자·우선순위·라벨을 1회 제출로 확정한다. 지금은 create 후 PATCH 3회를
이어 붙여야 하고 **중간 실패 시 반쯤 만들어진 이슈가 남는다**(완제품 기준 위반).
`CreateIssueRequest.kt` 에 nullable 3필드 추가 + `IssueController` create 핸들러 +
`IssueApplicationService` create 경로. 전부 optional 추가라 기존 요청 무회귀.

**정본.** `docs/plan/product/personalization.md` §4.7 FR-UX-09 — **D4/D5**.
FR-UX-09 는 승계 PR 3건(F2 모달 · F3 진입점 3곳 · **B1 백엔드**)으로 구성되며 본 PR 은 그중 B1 이다.

**B1 의 지위.** 2026-07-29 정정 — 2026-07-28 Maxi 결정 #3 의 *"B1 = chore"* 를 승계·정정해
**이 FR 의 D4/D5** 로 승격. 조용한 변경이 아니라 원 결정의 명시적 승계다.

**classify 결과.**
| 항목 | 값 |
|---|---|
| type | `feature` |
| agent | `backend-engineer` |
| primary_bc | `issue-tracking` |
| slug (자동) | `fr-ux-09-b1-1-issue-tracking-createissuerequest-3` → 관례 맞춰 정정 |

### 착수 전 실측으로 확인된 사실

- `CreateIssueRequest.kt` 실경로 = `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`
  (정본이 적은 `com/atlas/bts/issuetracking/...` 아님)
- 정본 인용 `:27-39` **정확** — data class 범위가 실제로 27~39행
- 현재 DTO 보유 필드 = `projectKey` · `typeId` · `summary` · `description` · `componentIds` ·
  `securityLevelId` · `customFields` → **`assigneeId`·`priority`·`labels` 3필드 부재 확인**
- `IssueController.kt` · `IssueApplicationService.kt` 모두 `com/bts/issue/` 하위에 실재

### 선행 조건

- **선행 FR.** §4.5 FR-UX-07 (활성 프로젝트 컨텍스트) — **#320 로 완료**. 차단 없음
- 승계 관계상 F2(생성 모달)·F3(진입점 3곳)가 이 API 를 소비하므로 **B1 이 먼저**여야 한다.
  순서를 뒤집으면 프론트가 PATCH 3회 방식으로 만들어졌다가 재작업된다.

## 도메인 정리

- **BC.** `issue-tracking` 단일. cross-BC 0.
- **영향 엔티티.** `Issue` (애그리거트) — **신규 엔티티 0 · 신규 관계 0**
- **새 용어.** 없음
- **기존 결정 충돌.** 없음. `docs/decisions/2026-07-18-auto-assign-system-actor-permission-bypass.md` 는
  워크플로 **스킴 배정** 건으로 별건(BC 도 project-workflow)
- **glossary 갱신 대상 1건.** `담당자 | Assignee ... PATCH /issues/{key}/assignee로 변경 (FR-IS-03)` —
  생성 시 지정이 추가되면 이 서술이 낡는다 (Maxi 승인 후 갱신)

### ★실측이 뒤집은 것 3건

**① 정본의 「PATCH 3회」는 부정확 — 실제 2회.**
`priority`·`labels` 는 범용 `PATCH /{key}` 하나에 함께 들어간다
(`UpdateIssueRequest.kt:77,79` → `IssueController.kt:390-391`). 전용 엔드포인트 0개.
`assignee` 만 `PATCH /{key}/assignee` 로 분리돼 있다.
→ 현행 = `POST` + `PATCH /{key}`(priority+labels) + `PATCH /{key}/assignee` = **create + 2 PATCH**.
**문제 자체는 그대로 성립** — 2회여도 중간 실패 시 반제품 이슈가 남는다.

**② 도메인 애그리거트는 이미 3필드를 완비하고 있다.**
`Issue.create(...)` 가 `priority: Int = PRIORITY_DEFAULT`(`Issue.kt:188`) ·
`labels: List<String> = emptyList()`(`:189`) · `assigneeId: ActorId? = null`(`:192`) 를 이미 받고,
`validatePriority(priority)`(`:198`) · `validateAndNormalizeLabels(labels)`(`:199`) 로 **검증까지 내장**.
→ **D1(도메인)·D3(데이터 모델) 변경 0.** 실제 작업은 REST DTO + `IssueApplicationService.createIssue` 배선.

**③ 생성 경로에 이미 담당자 결정 로직이 있다.**
`resolveDefaultAssignee(projectId, normalizedComponentIds, current = null)`
(`IssueApplicationService.kt:260`) — 컴포넌트 리드 중 이름 오름차순 첫 번째(FR-IS-03 auto-assign).
명시 지정과 **우선순위 충돌**이 생긴다.

### ★선례 — 같은 문제를 이미 푼 필드가 있다 (`securityLevelId`)

`createIssue` 는 이미 optional 필드 하나를 받고, non-null 일 때만 추가 권한을 건다.

```kotlin
// IssueApplicationService.kt:239-247
if (request.securityLevelId != null) {
    assertSecurityLevelAssignable(
        actor = actor,
        scope = IssueScope.Project(request.projectKey),   // ← 생성은 Project
        projectKey = request.projectKey,
        levelId = request.securityLevelId,
    )
}
```

그리고 그 규칙이 KDoc 에 **명문화**돼 있다 —
`@param scope 권한 평가 범위. **생성은 Project, 수정은 Issue.**` (`:1523`).
이슈가 아직 없어 `IssueScope.Issue(key)` 로 평가할 수 없기 때문이다.

### ★권한 비대칭 — 이 PR 의 핵심 위험

| 경로 | 권한 | 범위 |
|---|---|---|
| `createIssue` | `IssuePermission.CREATE` | `IssueScope.Project` (`:225`) |
| `updateIssue` (priority·labels) | `IssuePermission.UPDATE` | `IssueScope.Issue` (`:519`) |
| `changeAssignee` | `IssuePermission.UPDATE` | `IssueScope.Issue` (`:800`) |

`CREATE` 와 `UPDATE` 는 **다른 권한**이다(별도 `ASSIGN` 권한은 없음 —
`shared-kernel/.../IssuePermission.kt:39,42`). 3필드를 무가드로 create 에 접으면
**CREATE 만 가진 actor 가 UPDATE 없이 값을 설정**하게 된다.

### ★`changeAssignee` 가 하고 `createIssue` 가 **안 하는** 것 — 전수 4종

무가드로 접으면 아래가 통째로 빠진다. (전수 열거 = 검증 범위. 개수 대신 목록으로 고정)

1. `assertEditableOrForbidden(actor, key, setOf(FieldRef(FieldKind.CORE, "assigneeId")))` (`:814`)
   — **FR-PM-07 필드 단위 편집 게이트**. `createIssue` 는 이 함수를 **한 번도 호출하지 않는다**
2. `userLookupPort.exists(assigneeId)` → `AssigneeNotFoundException` (`:818-820`)
   — auto-assign 은 컴포넌트 리드(이미 유효 사용자)에서 나와 불필요했으나, **명시 지정엔 필요**
3. `eventPublisher.publish(IssueAssigned(...))` (`:836`) — 알림 트리거.
   현행 create 는 `IssueCreated` 만 발행하고 auto-assign 이 담당자를 정해도 `IssueAssigned` 는 **미발행**
4. OCC `expectedVersion` 충돌 처리 (`:828-831`) — create 엔 무관(신규 행)

`autoWatch` 는 이미 `resolvedAssignee` 를 포함(`:290`)하므로 명시 지정으로 치환하면 자동 승계된다.

### ★결정 확정 (2026-07-31 Maxi) — 정본은 ADR

**ADR.** [`docs/decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md`](../decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md)

| # | 결정 | 컨트롤러 추천과 | 핵심 |
|---|---|---|---|
| **D-1** | 추가 권한 검사 **없음** — `CREATE` 만으로 충분 | **다름** (추천 A=선례 확장) | `securityLevelId` 만 게이트 보유 → optional 필드 규칙 **이원화** |
| **D-2** | `JsonNullable` **3단계** | 일치 | 생략=auto-assign / 명시 null=미할당 / 값=그 사용자 |
| **D-3** | FR-PM-07 편집 게이트 **미적용** | **다름** (추천 A=3필드 한정 적용) | 편집 잠긴 사용자가 **생성 시엔 설정 가능** |
| **D-4** | `IssueAssigned` **자동·명시 모두 발행** | **다름** (추천 A=미발행) | **알림 무회귀 전제 파기** |

**★결합 효과 2건 — 개별 답변으로는 안 보였던 것 (Maxi 에게 명시 후 진행).**

1. **D-1 + D-3 = 게이트 0개.** `CREATE` 만 보유한 actor 가 추가 권한 검사도, 필드 잠금도 없이
   3필드를 설정한다. FR-PM-07 로 `assigneeId` 가 잠긴 사용자가 **생성 경로로 그 잠금을 우회**한다.
2. **D-4 = 무회귀 파기.** 기존 생성 요청의 알림 동작이 바뀐다(지금까지 auto-assign 은
   `IssueAssigned` 미발행). PR 불변량에서 「알림 무회귀」를 내리고 회귀 테스트를 그 전제로 재작성.

**D-2 세부.** `priority`·`labels` 는 자동 결정 로직이 없어 `JsonNullable` 불필요 —
생략/null 이면 `Issue.create` 기본값(`PRIORITY_DEFAULT` / `emptyList()`)에 맡긴다.

**D-4 발행 판정식.** "최종 `assigneeId` 가 non-null 인가" **단일 술어**. 담당자가 확정되지
않으면(자동 배정 결과 null + 명시 생략) 미발행.



## 스펙

전체 스펙. [docs/specs/2026-07-31-fr-ux-09-b1-create-issue-fields.md](../specs/2026-07-31-fr-ux-09-b1-create-issue-fields.md)

핵심 시나리오 3줄.
- 담당자·우선순위·라벨을 `POST /issues` 1회 제출로 확정 (추가 PATCH 0회)
- `assigneeId` 3-state — 생략=자동배정 유지 / 명시 null=미할당 확정 / 값=그 사용자
- 담당자가 확정되면 `IssueAssigned` 발행. **단 REST 생성 경로만** (D-5)

**응답 계약 무변경** — `IssueResponse` 가 `priority`·`priorityName`·`labels`·`assigneeId` 를
이미 보유(`IssueResponse.kt:93-99`). OpenAPI 응답 스키마 diff **0**.

**실측 확정 수치.** priority `1..5`(기본 3) · label 길이 `≤50` · label 개수 `≤20` ·
미존재 담당자 → **422** `ASSIGNEE_NOT_FOUND`(`IssueExceptionHandler:452`) ·
범위 밖 priority → **400**(Jakarta). DTO 어노테이션은 `UpdateIssueRequest:75-82` 와 동일하게 맞춘다.

**아키텍처 제약 (명문 규칙).** 응용 계층은 `JsonNullable` 을 보지 않는다
(`IssueApplicationRequests.kt:41`). 컨트롤러가 전용 sealed `AssigneeIntent{Auto,None,User}` 로 변환 —
`toSecurityLevelPatch`(`IssueController.kt:957`) 선례.

## Brainstorming Check

✅ 통과 (1회 iteration). **공백 3건 발견, 1건이 ADR 개정(D-5)으로 이어짐.**
형식적 흔들기 대신 **생산자 전수 실측**으로 수행.

| # | 발견 | 처리 |
|---|---|---|
| **G1** | `createIssue` 생산자가 REST 하나가 아님 — `IssueImportAdapter.kt:534`. Import 는 생성 직후 `changeAssignee`(`:601`)로 담당자를 다시 지정하는데 그쪽이 이미 `IssueAssigned` 발행 → D-4 무제한 적용 시 **이슈당 2회**, 첫 번째는 곧 덮어쓰일 임시 담당자에 대한 **거짓 알림** | **★ADR D-5 신설** (Maxi 확정) — REST 만 발행. `notifyAssignment` **기본 false**(fail-safe) |
| **G2** | `cloneIssue` 는 담당자를 설정하며 `IssueCreated` 만 발행(`:365`). `createIssue` 미경유라 자동 포함 안 됨 | **별건 후속** (ADR D-5 에 기록) |
| **G3** | Import 가 담당자를 안 넘겨 `resolveDefaultAssignee` 가 컴포넌트 리드를 넣고, `applyAssigneeIfPresent` 는 원본 담당자 부재 시 조기 반환(`:599`) → **원본에 없던 담당자가 생김**(반입 충실도 위반). 이 PR 의 D-2 「명시 null」이 처방이나 FR-IM 수정 | **범위 밖 · 선재 결함 후보. TODOS 등재** |

## Plan

> 모든 task 는 `agent: backend-engineer` (헤더 기본값). 파일 경로는 repo 루트 기준.
> 축약. `IT/` = `backend/modules/issue-tracking/src/`

### Task 1. `AssigneeIntent` sealed 타입 + `AppCreateIssueRequest` 확장

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `IT/test/kotlin/com/bts/issue/application/IssueApplicationRequestsTest.kt`]
- depends-on: []

**RED**. `IssueApplicationRequestsTest`
- `AssigneeIntent` 3분기(`Auto`/`None`/`User(uuid)`)가 존재한다
- **`AppCreateIssueRequest` 를 기존 인자만으로 생성하면 `assignee == AssigneeIntent.Auto` · `priority == null` · `labels == null` · `notifyAssignment == false`**
- 실패 예상. `AssigneeIntent` 클래스 없음

**GREEN**. `IssueApplicationRequests.kt`
- `sealed interface AssigneeIntent { data object Auto; data object None; data class User(val userId: UUID) }`
- `AppCreateIssueRequest` 에 `assignee: AssigneeIntent = Auto` · `priority: Int? = null` · `labels: List<String>? = null` · `notifyAssignment: Boolean = false` 추가

**REFACTOR**. KDoc — `notifyAssignment` 기본 false 사유(fail-safe, D-5)를 주석으로 고정

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueApplicationRequestsTest'`

> ★이 task 의 기본값 단언이 **측정 기준 9**(신규 생산자가 알림을 조용히 켜지 못함)의 회귀 가드다.

---

### Task 2. `createIssue` — `priority`·`labels` 배선

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `IT/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [1]

**RED**.
- `priority=1`·`labels=["urgent"]` 전달 → 저장된 Issue 에 반영 (E9 대응)
- **생략 시** `priority == 3`(`PRIORITY_DEFAULT`) · `labels == []` — **무회귀 가드**

**GREEN**. `Issue.create(...)` 호출에
`priority = request.priority ?: IssuePriority.MEDIUM.number` · `labels = request.labels ?: emptyList()` 전달.
**`Issue.create` 시그니처 변경 금지**(C2)

> ★**R1 정정 (plan 리뷰).** 초안은 `PRIORITY_DEFAULT` 를 쓰려 했으나 그건
> `Issue.kt:23` 의 **파일 private `const`** 라 응용 계층에서 참조하면 **컴파일 실패**한다.
> `IssuePriority.MEDIUM.number`(`IssuePriority.kt:27`, public enum, 값 3)가 유일한 공개 출처다.
> 매직넘버 `3` 을 직접 쓰면 detekt `MagicNumber` 대상이 되고,
> `IssueResponse.kt:302` 가 자체 `DEFAULT_PRIORITY = 3` 을 또 선언한 중복을 3벌로 늘린다.

**REFACTOR**. `createIssue` 가 이미 `@Suppress("LongMethod")` — 길이 임계 재확인, 초과 시 헬퍼 분리

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueApplicationServiceTest'`

---

### Task 3. `createIssue` — `assigneeId` 3-state

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `IT/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [2]

**RED**. (E1·E2·E3)
- `Auto` → `resolveDefaultAssignee` 결과가 담당자
- `None` → 담당자 `null` **이고 `resolveDefaultAssignee` 가 호출되지 않는다** (mock verify — 「값이 null」만 보면 자동배정이 null 을 낸 경우와 구분 불가. **양성 대조군** = 컴포넌트 리드가 있어 Auto 면 담당자가 붙는 픽스처)
- `User(u)` → 담당자 `u`, `resolveDefaultAssignee` 미호출
- **★R3 추가 (plan 리뷰) — `autoWatch` 인자 단언.** `None` → 워처가 **reporter 1명** /
  `User(u)` → **reporter + u 2명**. `autoWatch(:290)` 가 `listOfNotNull(reporterId, resolvedAssignee)` 라
  담당자 분기가 워처에까지 전파되는데, 초안엔 이 단언이 없어 **FR7 이 무검증**이었다

**GREEN**. `when (request.assignee)` 분기로 `resolvedAssignee` 결정

**REFACTOR**. 분기를 `private fun resolveAssignee(...)` 로 추출

**검증**. 위와 동일

> ★`None` 단언에 **호출 여부 verify** 를 반드시 넣는다. 값만 보면 공허한 테스트가 된다
> (메모리 — `waitFor` t=0 즉시통과 / 뮤테이션 M4 계열).

---

### Task 4. `createIssue` — 명시 담당자 존재 검증 (422)

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `IT/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [3]

**RED**. (S4·E10)
- `User(미존재 UUID)` → `AssigneeNotFoundException`
- **이슈가 저장되지 않는다** (`repo.insert` 미호출 verify — 예외만 보면 롤백 여부를 모른다)
- `Auto` 경로는 `userLookupPort.exists` 를 **호출하지 않는다** (자동배정 결과는 이미 유효 사용자)

**GREEN**. `User` 분기에서만 `userLookupPort.exists(id)` → 미존재 시 `AssigneeNotFoundException`.
배치는 **`resolveDefaultAssignee` 자리(`:260`)** 로 **고정한다**.

> ★**R5 정정 (plan 리뷰).** 초안은 배치 지점을 "구현 시 판단"으로 열어 뒀는데 그건 리뷰가
> 검증할 수 없는 서술이다. **선례로 고정** — `assertSecurityLevelAssignable` 도 키 발급(`:228-229`)
> **이후**인 `:240` 에 있다. 키 소비 낭비 우려는 없다 — `incrementKeySequence` 가
> `pg_advisory_xact_lock` 아래 같은 트랜잭션이라 예외 시 롤백으로 되돌아간다.

**REFACTOR**. KDoc 의 `@throws` 에 `AssigneeNotFoundException` 추가

**검증**. 위와 동일

---

### Task 5. `createIssue` — `IssueAssigned` 발행 (`notifyAssignment` 게이트)

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `IT/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [4]

**RED**. (S6·S7·E14 — **2×2 행렬 전수**)

| `notifyAssignment` | 최종 assignee | 기대 |
|---|---|---|
| `true` | non-null | `IssueAssigned` **1회** |
| `true` | null | **0회** |
| `false` | non-null | **0회** ← Import 회귀 가드 |
| `false` | null | **0회** |

`IssueCreated` 는 **4케이스 전부 1회**(무회귀).

**GREEN**. `if (notifyAssignment && resolvedAssignee != null) publish(IssueAssigned(...))`.
발행 위치는 `IssueCreated` 직후, `recordHistory` 이전

**REFACTOR**. 판정식을 한 줄 주석으로 고정 — "최종 assigneeId non-null **AND** REST 경로(D-5)"

**★R4 확인 항목 (plan 리뷰, 테스트 아님)**. `recordHistory(before = null, after = saved)`(`:302`)가
초기 `assigneeId`·`priority`·`labels` 를 감사 이력에 남기는지 **실측**한다.
- 남긴다 → 그대로 두고 통합 테스트에 단언 1줄 추가
- 안 남긴다 → **이 PR 이 만든 결함이 아니다**(생성 이력 전반의 성질). 별건으로 보고

**검증**. 위와 동일

> ★행렬 4칸을 **전부** 단언한다. `true/non-null` 만 보면 게이트가 실제로 막는지 검증되지 않는다
> (메모리 — 가드×핸들러 행렬 눈가리개).

---

### Task 6. REST DTO `CreateIssueRequest` 3필드 + Jakarta 검증

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`, `IT/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTest.kt`]
- depends-on: []

**RED**. (S5·E8) — 전부 **400**
- `priority=0` · `priority=6` · `labels` 21개 · label 51자
- **경계 통과 확인** — `priority=1` · `priority=5` · labels 20개 · label 50자는 **400 이 아니다**
  (양성 대조군 — 상한만 막고 경계를 안 보면 off-by-one 을 놓친다)

**GREEN**. `assigneeId: JsonNullable<UUID> = JsonNullable.undefined()` ·
`@field:Min(1) @field:Max(5) priority: Int? = null` ·
`@field:Size(max=20) labels: List<@Size(max=50) String>? = null`
— **`UpdateIssueRequest:75-82` 와 문자 단위로 동일한 메시지 문구** 사용

**REFACTOR**. KDoc `@property` 3건 추가, 3-state 시맨틱 명시

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueControllerTest'`

---

### Task 7. 컨트롤러 배선 + `toAssigneeIntent`

**메타**.
- agent: `backend-engineer`
- files: [`IT/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `IT/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTest.kt`]
- depends-on: [1, 6]

**RED**. (FR3·FR6')
- 키 생략 → `AssigneeIntent.Auto` / 명시 `null` → `None` / 값 → `User(uuid)`
- **`notifyAssignment = true` 로 전달된다** (D-5 — REST 만 발행)
- `priority`·`labels` 가 그대로 전달된다

**GREEN**. `toAssigneeIntent(raw: JsonNullable<UUID>): AssigneeIntent` 를
`toSecurityLevelPatch`(`:957`) **바로 옆**에 배치. `AppCreateIssueRequest(... , notifyAssignment = true)`

**REFACTOR**. 헬퍼 KDoc — 3-state 매핑표

**검증**. 위와 동일

> ★C1 — 이 task 가 `JsonNullable` 누출 차단의 유일한 지점이다.
> `IssueApplicationRequests.kt` 에 `JsonNullable` import 가 **0건**임을 grep 으로 확인한다.

---

### Task 8. 통합 테스트 — S1~S8 + Import 회귀

**메타**.
- agent: `backend-engineer`
- files: [`IT/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerIntegrationTest.kt`, `IT/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [5, 7]

> ★**R2 정정 (plan 리뷰).** 초안은 S8(Import 회귀)까지 `IssueControllerIntegrationTest` 에 넣었는데,
> 그 테스트는 **REST 컨트롤러를 지나므로 `IssueImportAdapter` 를 아예 통과하지 않는다** —
> 초록이 나와도 Import 경로를 검증한 게 아니다(공허한 테스트). **S8 은 `IssueImportAdapterTest.kt`** 로.

**RED/GREEN**. (테스트만 추가 — 프로덕션 코드 변경 없음)
- **S1** 1회 제출로 3필드 확정, 추가 PATCH 0회
- **S2** 3필드 생략 시 기존 응답과 동일
- **S3** 명시 null → 미할당 (컴포넌트 리드 존재 픽스처)
- **S4** 미존재 담당자 → 422 `ASSIGNEE_NOT_FOUND` + 이슈 미생성
- **S6/S7** 알림 발행 유무
- **S8 (★Import 회귀)** — `IssueImportAdapter` 경유 반입 1건당 `IssueAssigned` 발행 횟수가
  **이 PR 전후 동일**. 자동배정이 발동하는 픽스처(컴포넌트 리드 존재)로 측정

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueControllerIntegrationTest'`

---

### Task 9. OpenAPI 계약 — 요청 3필드 · **응답 diff 0**

**메타**.
- agent: `backend-engineer`
- files: [`IT/test/kotlin/com/bts/issue/adapter/inbound/rest/OpenApiContractTest.kt`]
- depends-on: [7]

**RED/GREEN**.
- 요청 스키마에 `assigneeId`·`priority`·`labels` 존재 + 전부 **optional**(required 미포함)
- **응답 스키마 `IssueResponse` diff 0** (C3)

**검증**. `./gradlew :modules:issue-tracking:test --tests '*OpenApiContractTest'`

---

### 구현 후 전수 실측 (task 아님 — codereview 게이트 입력)

- 마이그레이션 **0** · 프론트 **0줄** · `package.json` diff **0** · cross-BC **0**
- `IssueApplicationRequests.kt` 의 `JsonNullable` import **0건** (C1)
- `assertEditableOrForbidden` 호출이 `createIssue` 에 **0건** (D-3 준수 확인)
- **E6 미해결 확인** — `labels=["   "]`(공백만) 요청의 실제 상태코드 실측.
  400 이 아니면 **선재 결함으로 보고**하고 범위 편입 여부 Maxi 확인

## Plan 메타

- **task 수. 9**
- **예상 wave. 6** — W1[T1,T6] → W2[T2,T7] → W3[T3,T9] → W4[T4] → W5[T5] → W6[T8]
- **★병렬 이득 제한적.** 9 task 전부 `issue-tracking` **단일 Gradle 모듈**이라 컴파일이 직렬화된다
  (메모리 `bts-plan-wave-gradle-module-compile`). wave 는 논리적 순서 보장용으로만 쓰고
  시간 단축은 기대하지 않는다
- **직렬 파일 3개.** `IssueApplicationService.kt`(T2→T3→T4→T5) ·
  `IssueController.kt`(T7) · `IssueControllerTest.kt`(T6→T7)
- TDD 강제. **yes** — `test:` → `feat:` 커밋 쌍 9세트 기계 검증
- 추가 검증. `ktlintCheck` · `detekt` (프론트 도구 해당 없음 — 백엔드 단일)

## 리뷰 결과

**eng 관점 1회. 결함 5건 발견 → 전량 plan 에 반영 완료.** BLOCKER 2건 포함.

| # | 등급 | 발견 | 처리 |
|---|---|---|---|
| **R1** | 🔴 **BLOCKER** | T2 GREEN 이 `PRIORITY_DEFAULT` 참조 — `Issue.kt:23` 의 **파일 private `const`** 라 응용 계층에서 **컴파일 실패** | `IssuePriority.MEDIUM.number`(public enum, `IssuePriority.kt:27`)로 정정. 매직넘버 `3` 은 detekt `MagicNumber` + 중복 3벌화 |
| **R2** | 🔴 **BLOCKER** | T8 이 S8(Import 회귀)을 `IssueControllerIntegrationTest` 에 배치 — 그 경로는 **`IssueImportAdapter` 를 통과하지 않아** 초록이어도 무의미(공허한 테스트) | `IssueImportAdapterTest.kt` 로 이동, T8 `files` 2개로 |
| **R3** | 🟡 공백 | `autoWatch` 단언 없음 → **FR7 무검증**. 담당자 분기가 워처 목록까지 전파되는데 안 봄 | T3 RED 에 워처 수 단언 추가(`None`=1명 / `User`=2명) |
| **R4** | 🟡 미확인 | `recordHistory(before=null)` 가 3필드 초기값을 남기는지 불명 | T5 에 **확인 항목**으로 등재. 안 남기면 선재 성질로 별건 보고 |
| **R5** | 🟢 경미 | T4 검증 배치를 "구현 시 판단"으로 열어 둠 — 리뷰가 검증 불가능한 서술 | 선례(`assertSecurityLevelAssignable:240`)로 **고정**. 키 소비 우려는 advisory lock 트랜잭션 롤백으로 해소 |

### ★리뷰가 뒤집은 것 — R1 은 "돌려보면 안다"가 아니었다

R1 은 **구현을 시작했으면 첫 컴파일에서 막혔을** 결함이다. 그런데 plan 만 읽으면
`PRIORITY_DEFAULT` 는 그럴듯한 이름이라 통과한다 — **가시성은 이름에 안 적혀 있다.**
`sed -n '8,26p' domain/Issue.kt` 로 `private const val` 을 눈으로 본 게 판별식이었다.

R2 는 더 나쁜 종류다. **컴파일도 되고 테스트도 초록인데 검증은 0**이다.
"어느 파일에 두는가"가 곧 "무엇을 지나는가"인데 초안은 파일명만 보고 배치했다.

### 한계 (명시)

- **독립·교차모델 리뷰 부재.** codex 미설치 + 에이전트 호출 금지로 컨트롤러가 자기 plan 을 자기가 리뷰했다.
  자기 초안의 사각지대는 구조적으로 안 보일 수 있다. #327 과 동일한 한계이며 개선되지 않았다
- **ceo/design/devex 관점 미수행.** 백엔드 DTO 확장이라 해당 없음으로 판단(디자인 산출물 0, 사용자 대면 문구 0)

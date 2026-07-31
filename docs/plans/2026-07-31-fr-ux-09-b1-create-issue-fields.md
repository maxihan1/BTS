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



## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

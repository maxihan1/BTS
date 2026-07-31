# FR-UX-09 B1 — 이슈 생성 시 담당자·우선순위·라벨 1회 제출 — 스펙

> slug: fr-ux-09-b1-create-issue-fields · BC: issue-tracking · PR #328
> ADR(결정 정본): [2026-07-31-fr-ux-09-b1-create-issue-fields](../decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md)
> 정본: `docs/plan/product/personalization.md` §4.7 FR-UX-09 D4/D5

## 배경 한 줄

`POST /api/v1/issues` 가 담당자·우선순위·라벨을 안 받아 프론트가 `PATCH` 2회를 이어 붙여야 하고,
중간 실패 시 **반제품 이슈**가 남는다.

## 사용자 시나리오 (Given-When-Then)

**S1. 담당자를 지정해 생성 (핵심 경로)**
- Given. `CREATE` 권한 보유 actor, 유효한 사용자 `U`
- When. `POST /issues` 에 `assigneeId=U` · `priority=1` · `labels=["urgent"]` 를 담아 1회 제출
- Then. 201 + 응답의 `assigneeId=U` · `priority=1` · `labels=["urgent"]`. **추가 PATCH 0회**

**S2. 3필드 전부 생략 — 기존 동작 보존**
- Given. 기존 프론트/외부 클라이언트가 보내던 그대로의 본문
- When. `POST /issues` (3필드 키 자체가 없음)
- Then. 201 + `resolveDefaultAssignee` 결과가 담당자 · `priority=3` · `labels=[]`
  — **요청/응답 계약 무회귀**

**S3. 자동 배정을 끄고 미할당으로 생성**
- Given. 컴포넌트 리드가 존재해 자동 배정이 발동할 프로젝트
- When. `POST /issues` 에 `assigneeId: null` **명시**
- Then. 201 + `assigneeId=null`. `resolveDefaultAssignee` **호출되지 않음**

**S4. 없는 사용자를 담당자로 지정**
- Given. `users` 에 없는 UUID `X`
- When. `POST /issues` 에 `assigneeId=X`
- Then. **422** + `ASSIGNEE_NOT_FOUND`. 이슈는 생성되지 않는다 (트랜잭션 롤백)

**S5. 범위 밖 우선순위**
- When. `priority=0` 또는 `priority=6`
- Then. **400** (Jakarta Validation). 도메인 `require` 에 도달하지 않는다

**S6. 담당자가 확정되면 알림이 간다 (D-4)**
- Given. 자동 배정이든 명시 지정이든 최종 `assigneeId` 가 non-null
- When. 이슈 생성
- Then. `IssueCreated` **+** `IssueAssigned` 둘 다 발행. 담당자는 auto-watch 에도 등록

**S7. 담당자가 없으면 알림이 안 간다 (D-4 경계)**
- Given. 자동 배정 결과 null 이고 명시 지정도 없음
- When. 이슈 생성
- Then. `IssueCreated` **만** 발행. `IssueAssigned` **미발행**

## 기능 요구사항 (FR)

| ID | 요구사항 | 근거 |
|---|---|---|
| **FR1** | `CreateIssueRequest` 에 `assigneeId`(3-state) · `priority` · `labels` 를 optional 로 추가 | 정본 §4.7 B1 |
| **FR2** | `assigneeId` 는 **3-state** — 생략=자동배정 유지 / 명시 null=미할당 확정 / 값=그 사용자 | ADR D-2 |
| **FR3** | 응용 계층은 `JsonNullable` 을 **보지 않는다**. 컨트롤러가 전용 sealed 타입으로 변환 | `IssueApplicationRequests.kt:41` 명문 규칙 |
| **FR4** | `priority`·`labels` 는 3-state 불필요. 생략/null → `Issue.create` 기본값 | ADR D-2 |
| **FR5** | 명시 `assigneeId` 는 `userLookupPort.exists()` 검증 → 미존재 시 `AssigneeNotFoundException` | `changeAssignee:818-820` 대칭 |
| **FR6** | 최종 `assigneeId` 가 non-null 이면 `IssueAssigned` 발행 (**단일 술어**) | ADR D-4 |
| **FR7** | 최종 담당자는 기존 `autoWatch`(`:290`) 경로로 워처 등록 | 기존 동작 승계 |
| **FR8** | 추가 권한 검사 **없음** — `CREATE` 만 | ADR D-1 |
| **FR9** | `assertEditableOrForbidden` **미호출** | ADR D-3 |
| **FR10** | `IssueResponse` **무변경** — `priority`·`priorityName`·`labels`·`assigneeId` 이미 보유 | `IssueResponse.kt:93-99` |

## 비기능 요구사항 (NFR)

| ID | 요구사항 | 측정 |
|---|---|---|
| **NFR1** | 생성 왕복 횟수 3 → **1** | S1 이 PATCH 0회로 성립 |
| **NFR2** | 3필드 생략 시 기존 요청과 **바이트 단위 동일한 응답** | S2 회귀 테스트 |
| **NFR3** | DB 쿼리 증가 ≤ 1 (명시 지정 시 `userLookupPort.exists` 1회) | 생략 경로는 **증가 0** |
| **NFR4** | 마이그레이션 0 · 신규 의존성 0 · 프론트 0줄 · cross-BC 0 | diff 실측 |

## API 인터페이스 (REST)

### 요청 — `POST /api/v1/issues`

`CreateIssueRequest` 에 추가되는 3필드. **어노테이션은 `UpdateIssueRequest`(`:75-82`) 와 동일하게 맞춘다.**

```kotlin
// 신규 3필드 (기존 7필드 뒤에 추가)
val assigneeId: JsonNullable<UUID> = JsonNullable.undefined(),

@field:Min(value = 1, message = "priority는 1 이상이어야 합니다.")
@field:Max(value = 5, message = "priority는 5 이하여야 합니다.")
val priority: Int? = null,

@field:Size(max = 20, message = "라벨은 최대 20개까지 허용합니다.")
val labels: List<
    @Size(max = 50, message = "라벨 하나는 50자 이하여야 합니다.")
    String,
    >? = null,
```

### 응답

**무변경.** `IssueResponse` 가 `priority`(`:93`) · `priorityName`(`:94`) · `labels`(`:95`) ·
`assigneeId`(`:99`) 를 이미 보유. **OpenAPI 응답 스키마 diff 0.**

### 상태 코드

| 상황 | 코드 | 코드값 |
|---|---|---|
| 정상 | 201 | — |
| `priority` 범위 밖 · `labels` 개수/길이 초과 | **400** | Jakarta Validation |
| 명시 `assigneeId` 미존재 | **422** | `ASSIGNEE_NOT_FOUND` (`IssueExceptionHandler:452`) |
| `CREATE` 권한 없음 | 403 | 기존 |

### 응용 계층 계약

`AppCreateIssueRequest`(`IssueApplicationRequests.kt:25-34`) 에 3필드 추가.
`assigneeId` 는 **전용 sealed 타입**으로 — `SecurityLevelPatch`·`DatePatch`·`EstimatePatch` 선례를 따른다.

```kotlin
sealed interface AssigneeIntent {
    /** 키 생략 — resolveDefaultAssignee 유지 (기존 동작). */
    data object Auto : AssigneeIntent
    /** 명시 null — 자동 배정 비활성, 미할당 확정. */
    data object None : AssigneeIntent
    /** 값 지정 — 자동 배정 비활성, 해당 사용자. */
    data class User(val userId: UUID) : AssigneeIntent
}
```

컨트롤러 변환 헬퍼는 `toSecurityLevelPatch`(`IssueController.kt:957`) 형태를 따라
`toAssigneeIntent(raw: JsonNullable<UUID>): AssigneeIntent` 를 같은 위치에 둔다.

## 데이터 모델 변경

**없음.** `issues` 테이블의 `assignee_id` · `priority` · `labels` 컬럼은 이미 존재하고
`Issue.create`(`Issue.kt:188-192`) 가 이미 3필드를 받는다. **마이그레이션 0 · jOOQ 재생성 0.**

## 엣지 케이스

| # | 케이스 | 기대 동작 | 근거 |
|---|---|---|---|
| **E1** | `assigneeId` 생략 + 컴포넌트 리드 존재 | 자동 배정 발동 (기존) | FR2 |
| **E2** | `assigneeId: null` + 컴포넌트 리드 존재 | **미할당**. `resolveDefaultAssignee` 미호출 | FR2 · S3 |
| **E3** | `assigneeId` 값 + 컴포넌트 리드 존재 | 명시 값이 이김 | FR2 |
| **E4** | `assigneeId` 값이 프로젝트 **비멤버** | **통과시킨다** — `changeAssignee` 도 멤버십을 안 본다(`:818` 은 존재만 확인). 대칭 유지 | FR5 |
| **E5** | `labels` 에 빈 문자열 포함 | 도메인이 필터(`Issue.kt:371`) | 기존 |
| **E6** | `labels` 에 공백만 있는 문자열 | 도메인 `require` 위반 → DTO 통과 후 500 위험 | **★아래 미해결 참조** |
| **E7** | `labels` 중복 | 도메인이 exact-match 로 dedup | 기존 |
| **E8** | `labels` 21개 (중 2개가 빈 문자열) | **400** — DTO `@Size(max=20)` 가 raw 길이로 먼저 걸림. `UpdateIssueRequest` 와 동일 동작 | 대칭 |
| **E9** | `priority` 생략 | `PRIORITY_DEFAULT=3` | FR4 |
| **E10** | 명시 `assigneeId` 가 미존재 사용자 | 422, 이슈 생성 **안 됨** | S4 |
| **E11** | 자동 배정 결과 null + 명시 생략 | `IssueAssigned` **미발행** | S7 · ADR D-4 |
| **E12** | FR-PM-07 로 `assigneeId` 편집이 잠긴 사용자 | **설정된다** (의도된 대가) | ADR D-3 |
| **E13** | `CREATE` 만 보유, `UPDATE` 없음 | **3필드 전부 설정된다** (의도된 대가) | ADR D-1+D-3 결합 |

### ★미해결 1건 — E6 (구현 단계에서 확정 필요)

공백만으로 된 라벨(`"   "`)은 DTO `@Size(max=50)` 를 통과하지만 도메인
`require(label.isNotBlank())`(`Issue.kt:373`) 에서 `IllegalArgumentException` 이 된다.
그런데 `IssueExceptionHandler` 에 **`IllegalArgumentException` 핸들러가 없다**(실측 — grep 0건).

- **선재 성질이다.** `PATCH /{key}` 도 동일 입력에서 같은 경로를 탄다 → 이 PR 이 만든 결함이 아니다
- **구현 시 확인할 것.** 전역 advice 가 400 으로 잡는지 실측. 500 이면 **선재 결함으로 보고**하고
  이 PR 범위에 넣을지 Maxi 확인 (봉합은 `UpdateIssueRequest` 도 함께 고쳐야 대칭)

## 제약 조건

- **C1.** 응용 계층에 `JsonNullable` 누출 금지 (`IssueApplicationRequests.kt:41`)
- **C2.** `Issue.create` 시그니처 변경 금지 — 이미 3필드를 받는다
- **C3.** `IssueResponse` 변경 금지 — 응답 계약 무회귀
- **C4.** 마이그레이션 금지 — 컬럼 이미 존재
- **C5.** issue-tracking 단일 BC. notification 은 **이벤트 발행만** (직접 import 금지)
- **C6.** ADR D-1~D-4 재논의 금지 — 확정 사항

## 측정 가능한 완료 기준

1. **S1~S7 전 시나리오**가 통합 테스트로 통과
2. **E1~E13** 중 E6 제외 전부 테스트로 고정 (E6 은 실측 후 판단)
3. `OpenApiContractTest` — 요청 스키마에 3필드 추가 확인, **응답 스키마 diff 0**
4. **회귀** — 3필드 미지정 기존 요청이 `IssueControllerIntegrationTest` 에서 무변경 통과
   (단 **알림은 예외** — D-4 로 `IssueAssigned` 가 새로 발행되므로 그 전제로 재작성)
5. `IssueAssigned` **팬아웃 검증** — 발행 1회, 구독자 동작 확인
   (메모리 `preseeded-event-producer-activates-notifications` 회귀 방지)
6. TDD `test:` → `feat:` 커밋 순서 기계 검증
7. 마이그레이션 0 · 프론트 0줄 · `package.json` diff 0 실측

## Brainstorming Check

**✅ 통과 (1회 iteration). 공백 3건 발견, 그중 1건이 ADR 개정으로 이어짐.**

형식적 흔들기 대신 **생산자 전수 실측**으로 수행했다 (메모리 — 생산자 수가 곧 검증 범위).

### G1 (★ADR 개정) — `createIssue` 의 생산자는 REST 하나가 아니다

`IssueImportAdapter.kt:534` 가 `createIssue` 를 **직접 호출**한다. 그리고 곧바로
`applyAssigneeIfPresent`(`:593-607`)가 `changeAssignee` 로 원본 담당자를 다시 지정하는데,
`changeAssignee` 는 이미 `IssueAssigned` 를 발행한다(`:836`).

→ D-4 를 무제한 적용하면 **이슈 1건당 `IssueAssigned` 2회**, 그중 첫 번째는
곧 덮어쓰일 임시 담당자에 대한 **거짓 알림**. 억제 장치 0건(grep).

**→ ADR D-5 신설 (Maxi 확정). REST 생성만 발행, Import 제외, Clone 별건.**
`AppCreateIssueRequest.notifyAssignment: Boolean = false` — **기본값 미발행**(fail-safe).

### G2 — `cloneIssue` 의 비대칭

`cloneIssue` 는 `assigneeId = if (includeAssignee) source.assigneeId else null` 로
담당자를 설정하면서 `IssueCreated` 만 발행한다(`:365`). `createIssue` 를 경유하지 않는
**별도 함수**라 이 PR 의 변경이 자동으로 미치지 않는다. → **별건 후속** (ADR D-5 에 기록).

### G3 (범위 밖 · ★선재 결함 후보) — Import 가 원본에 없던 담당자를 만든다

Import 는 `createIssue` 에 담당자를 **안 넘긴다**(`:537-544`). 그러면
`resolveDefaultAssignee` 가 컴포넌트 리드를 담당자로 넣는다. 이후
`applyAssigneeIfPresent` 는 `resolution.assigneeId ?: return currentVersion`(`:599`) 이라
**원본에 담당자가 없으면 그냥 반환**한다.

→ **원본엔 담당자가 없었는데 반입된 이슈엔 담당자가 생긴다.** 반입 충실도 위반.
공교롭게 이 PR 의 **D-2 「명시 null」이 처방**이다 — Import 가 `AssigneeIntent.None` 을
넘기면 자동 배정이 꺼진다. 단 FR-IM 수정이라 **이 PR 범위 밖**. TODOS 등재 대상.

### 반영 — 위 발견으로 스펙에서 바뀐 것

- **FR6 개정** — 발행 조건에 「REST 생성 경로일 것」이 추가됨 (아래 FR6' 참조)
- **S8 신설** — Import 경로 무발행 시나리오
- **E14 신설** — Import 경유 시 `IssueAssigned` 0회

## 스펙 개정분 (Brainstorming 반영)

### FR6' (FR6 대체)

최종 `assigneeId` 가 non-null **이고** `notifyAssignment == true` 일 때만 `IssueAssigned` 발행.
`notifyAssignment` 기본값 **false**. `IssueController.create` 만 `true` 를 전달한다.

### S8. Import 반입은 알림을 새로 만들지 않는다

- Given. 컴포넌트 리드가 존재해 자동 배정이 발동할 프로젝트
- When. `IssueImportAdapter` 가 이슈를 반입
- Then. `createIssue` 단계의 `IssueAssigned` **0회**. 기존 `changeAssignee` 경로 발행만 유지
  (= 반입 전후 알림 건수 **무변경**)

### E14. `notifyAssignment=false` + 담당자 non-null

→ `IssueAssigned` **미발행**. `IssueCreated` 만.

### 측정 기준 추가

8. **Import 회귀 실측** — 반입 1건당 `IssueAssigned` 발행 횟수가 이 PR 전후로 동일
9. **fail-safe 기본값 검증** — `AppCreateIssueRequest` 를 기본값으로 생성하면
   `notifyAssignment == false` (신규 생산자가 알림을 조용히 켜지 못함)

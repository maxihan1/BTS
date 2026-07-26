# WorkflowScheme 읽기 API 권한 게이트 봉합 (N4)

> slug: workflowscheme-api-n4-list-get-manage-scheme
> type: api (classify 판정) — **브랜치/PR 접두사는 `auth`** (실질이 권한 가드, N1~N3 형제 PR #311·#312 선례)
> agent: backend-engineer (주) + security-engineer 검토 (권한 가드, CLAUDE.md §sub-agent)
> primary_bc: project-workflow
> 생성: 2026-07-26

## Brief

### 사용자 원문

WorkflowSchemeController 읽기 API 권한 갭(N4) 봉합. `list`(L105)·`get`(L121)이 `requirePermission` 0건이라
"인증만 되면 누구나" 전 스킴 이름·매핑·사용처 카운트를 읽는다.

처방은 **Maxi 결정 B안**으로 확정 (결정 ID `6bdd1944`, 2026-07-26).

1. `WorkflowSchemeController.list`·`get` 에 `MANAGE_SCHEME` / `WorkflowSchemeScope.Global` 게이트 추가
2. 배정 화면용으로 `ProjectWorkflowSchemeController` 에
   `ASSIGN_SCHEME` / `WorkflowSchemeScope.Project(projectKey)` 게이트 목록 엔드포인트 신설
3. 프론트 `apps/web/src/hooks/use-workflow-schemes.ts` 를 신설 창구로 전환해
   `projects.$projectKey.settings.workflow-scheme` 화면이 403 으로 깨지지 않게 한다

**마이그레이션 0 · 신규 enum 0 · FR 129 불변 예정.**

### classify 결과

```json
{
  "type": "api",
  "agent": "backend-engineer",
  "primary_bc": "project-workflow",
  "slug": "workflowscheme-api-n4-list-get-manage-scheme"
}
```

**접두사 편차 기록.** classify 는 `type=api` 로 판정했으나 브랜치/PR 접두사는 `auth` 를 쓴다.
실질이 권한 가드 추가이고, 같은 경로토큰 유출 트랙의 형제 PR(#311 `[auth]`·#312 `[auth]`)과
정렬하기 위함. 에이전트 배정은 classify 대로 backend-engineer 유지(주 BC 가 project-workflow 이고
security-engineer 의 주 작업 영역은 `identity-access/**` 이므로) + 권한 가드 부분 security-engineer 검토.

### 배경 — 경로토큰 유출 트랙의 마지막 항목

2026-07-26 병렬 조사로 확정된 표면 4건 중 **N4 가 유일한 잔여**.

| 항목 | 상태 |
|---|---|
| N1 nginx 접속로그 토큰 마스킹 | ✅ #311 `17043db71` |
| N2 인증요청 `/error` 경로토큰 유출 | ✅ #312 `6cc7184d9` |
| N3 잠복 전역 advice `instance` 자동채움 | ✅ #313 `2b5d4e951` |
| **N4 WorkflowScheme 읽기 권한 갭** | ⬜ **본 작업** |

진실출처. 메모리 `path-token-leak-surface-four-findings-2026-07-26`.

### 착수 전 반증 결과 (2026-07-26, /context-restore 단계에서 수행)

근거 5건 **전부 유효**.

| 주장 | 실측 |
|---|---|
| 쓰기 5개만 `requirePermission` (L82·146·177·203·241) | ✅ 일치 |
| `list`(L105)·`get`(L121) 권한 0건 | ✅ 본문에 가드 없음 |
| enum 은 `MANAGE_SCHEME`·`ASSIGN_SCHEME` 2종뿐 | ✅ `WorkflowSchemePermission.kt:20-36` |
| prod resolver Global=`isSystemAdmin` | ✅ `IdentityAccessWorkflowSchemePermissionResolver.kt:54` |
| 배정 화면이 `useWorkflowSchemes` 소비 | ✅ `projects.$projectKey.settings.workflow-scheme.tsx:61` |

**정정 2건.**

- 라우트 가드는 "`requireAuth` 뿐"이 아니라 **`requireAuthAndPasswordChanged`**(이름과 달리 MFA 포함).
  systemAdmin 이 아니라는 결론은 불변 → 프로젝트 관리자 도달 성립.
- **★형제 컨트롤러가 이미 정답 패턴이다.** `ProjectWorkflowSchemeController` 는 GET(L107)·PUT(L78)
  **둘 다** `ASSIGN_SCHEME` + `WorkflowSchemeScope.Project(projectKey)` 로 게이트돼 있고,
  문제의 배정 화면이 그 GET 을 `useGetAssignment` 로 **이미 호출해 정상 동작 중**이다(L60).
  ⇒ "프로젝트 관리자가 `ASSIGN_SCHEME`/Project 게이트를 통과한다"가 그 화면에서 **이미 실증**됐다.
  최초 조사는 이 대칭을 놓쳐 B안 비용을 실제보다 비싸게 봤다.

### 기각된 대안

- **A안 — `get` 단건만 게이트.** `list` 가 전 스킴 이름·사용처 카운트를 계속 노출. 문을 반만 닫는다.
- **C안 — `VIEW_SCHEME` enum 신설.** shared-kernel enum + prod resolver 분기 + 권한 시드 마이그레이션 +
  `PermissionSchemaMigrationTest` 커플링. 폭발 반경이 셋 중 가장 크다.

## 도메인 정리

- **BC**: project-workflow (단일). identity-access 의 prod resolver 는 **읽기만 하고 수정하지 않음** → cross-BC 변경 0
- **영향 엔티티**: `WorkflowScheme` · `WorkflowSchemeMapping` (둘 다 기존). **신규 엔티티 0**
- **새 용어**: **0건.** `MANAGE_SCHEME` · `ASSIGN_SCHEME` · `WorkflowSchemeScope.Global/Project` 전부 기존 등재.
  glossary L71 "시스템 관리자 — 전역 자원(워크플로우 스킴 등) 관리 주체" 가 이미 본 작업의 판정 모델을 서술한다.
  → **glossary 갱신 불요**
- **기존 결정 충돌**: **없음. 오히려 정합 복원.**

### ★ 핵심 발견 — 이것은 신규 정책이 아니라 스펙 준수 복원이다

`docs/specs/2026-05-28-fr-wf-02-d6-ui-crud.md:57`.

> base `/api/v1`. **모든 endpoint 권한 검증** — `WorkflowSchemePermission.MANAGE_SCHEME` (**4.1**, 4.2, 4.4)
> / `ASSIGN_SCHEME` (4.3).

§4.1 = 스킴 CRUD 5개이고 그 안에 `list`·`get` 이 포함된다. **스펙은 처음부터 읽기에도 `MANAGE_SCHEME` 을
요구했고 구현이 이행하지 않았다.** 같은 spec L119 가 "권한 없는 사용자 — FR-PM-04 후속 정식 RBAC 도입 시
401/403 분기 추가" 로 이연했으나, FR-PM-04(#73)가 쓰기 5개만 결선하고 읽기 2개를 남겼다. **문서↔구현 drift.**

BC 관례도 반대 방향 — `PostActionController.kt:74` 는 GET 에도 `MANAGE_SCHEME` 요구.

### 기존 ADR 과의 정합

[2026-06-04-workflow-scheme-permission-prod-resolver](../decisions/2026-06-04-workflow-scheme-permission-prod-resolver.md)
§spec 단계 확정(2026-06-05).

| ADR 확정 사항 | 본 작업 |
|---|---|
| D3 Global — 스킴 CRUD(`MANAGE_SCHEME`/Global) = **시스템 관리자 전용** (Jira Cloud 모델, 스킴은 전역 자원) | D1 이 읽기까지 일관 적용 |
| D3 Project — 스킴 배정(`ASSIGN_SCHEME`/Project) = **프로젝트 관리자** | D2 가 배정용 읽기 창구에 그대로 적용 |
| D4 시드 — `MANAGE_WORKFLOW` × `PROJECT_ADMIN` 1행(V013), Global 은 매트릭스 미경유 | **시드 변경 0** — 기존 시드로 판정됨 |

[2026-06-05-workflow-scheme-controller-actor-wiring](../decisions/2026-06-05-workflow-scheme-controller-actor-wiring.md)
이 확립한 `CurrentActor.current()` → `actor.toUuid()` 결선 패턴을 읽기 2개에도 동일 적용한다.

### 신규 ADR

[docs/decisions/2026-07-26-workflow-scheme-read-permission-gate.md](../decisions/2026-07-26-workflow-scheme-read-permission-gate.md) **생성됨**.
D1(읽기 게이트) · D2(배정용 프로젝트 스코프 창구) · D3(배정용 응답은 부분집합) · D4(enum·마이그레이션 0).
엔드포인트 경로·응답 DTO 형태는 **spec 단계로 이연**(2026-06-04 ADR 이 판정 모델을 spec 으로 이연한 선례와 동형).

### 워크플로우 편차 기록 — `grill-with-docs` 생략

**사유.** 이 단계의 목적은 (a) 새 용어 발굴 (b) 도메인 모델 검증 (c) 기존 결정 충돌 탐지인데,
읽기 전 조사에서 셋 다 결론이 났다 — 새 용어 0 · 신규 엔티티 0 · 충돌 0(오히려 기존 ADR·spec 과 정합).
대화형 grilling 이 추가로 밝힐 미지가 없고 세션 예산만 소모한다.
대신 이 단계의 실질 산출물인 **ADR 1건은 정상 생성**했다.
(선례 — 2026-07-26 N1 작업도 같은 사유로 생략하고 기록함.)

## 스펙

전체 스펙. [docs/specs/2026-07-26-workflowscheme-api-n4-list-get-manage-scheme.md](../specs/2026-07-26-workflowscheme-api-n4-list-get-manage-scheme.md)

### 핵심 3줄

- `GET /workflow-schemes` · `GET /workflow-schemes/{key}` 를 `MANAGE_SCHEME`/Global 로 잠근다 (= 시스템 관리자 전용)
- 배정 화면용 읽기는 `GET /api/v1/projects/{projectKey}/assignable-workflow-schemes` 로 분리하고
  `ASSIGN_SCHEME`/Project(projectKey) 로 게이트한다
- 프론트는 배정 전용 훅 `useAssignableWorkflowSchemes(projectKey)` 를 신설해 그 화면만 갈아끼운다.
  관리용 `useWorkflowSchemes` 는 그대로 둔다 (소비자가 admin 4-가드 라우트 2개뿐임을 실측 확인)

### ★ 재사용으로 신규 코드를 최소화한 지점 (실측)

| 필요한 것 | 이미 있음 | 위치 |
|---|---|---|
| 배정용 최소 응답 DTO | `SchemeResponse` = `{id, key, name, description, isDefault}` | `ProjectWorkflowSchemeController.kt:160` |
| 카운트 없는 스킴 목록 조회 | `WorkflowSchemeApplicationService.list()` — **프로덕션 소비처 0건인 채 존재** | `WorkflowSchemeApplicationService.kt:246` |
| 403 매핑 | `@ExceptionHandler(WorkflowSchemeAccessDeniedException)` + 본문 미노출 계약 | `WorkflowSchemeExceptionHandler.kt:202` |
| 프로젝트 스코프 권한 경로 | `ASSIGN_SCHEME`/`Project(key)` — 같은 화면에서 이미 동작 중 | `ProjectWorkflowSchemeController.kt:117` |

⇒ **신규 DTO 0 · 신규 서비스 메서드 0 · 신규 예외 0 · 마이그레이션 0 · enum 0.**

### ★ 이 작업의 가장 큰 함정 — 기존 테스트가 회귀를 못 잡는다

`WorkflowSchemeControllerTest.kt:84` 의 `permissionResolver` 는 **`mockk(relaxed = true)`** 다.
`requirePermission` 이 no-op 이므로 **게이트를 추가해도 기존 GET 테스트 6개가 전부 green 을 유지한다.**
메모리 `seal-blinds-existing-guard` 의 정확한 재현.

⇒ RED 를 만들려면 resolver mock 이 **던지도록** 세워야 하고, 판별자는 상태코드가 아니라
**응답 본문에 스킴 정보가 0건인지**여야 한다 (메모리 `negative-guard-needs-body-discriminator`).

## Brainstorming Check

✅ **통과 (1회 iteration).** 검증 안 된 단언 5건 실측 → 4건 해소 · **1건 진짜 gap 발견 후 스펙 보강**.

- **G4 (gap→해소).** E2E/MSW 파급을 "있으면 확인" 으로 미뤄 뒀던 것을 **실측 목록으로 승격**.
  배정 E2E `workflow-scheme-assignment.spec.ts` + MSW `scheme-handlers.ts:83` 이 확정 파급 대상.
  안 잡았으면 impl 에서 배정 E2E 가 **빈 Select / timeout** 으로 깨지고, 그 증상이 권한 버그처럼 보였을 것.
- G1·G2·G3·G5 해소 — 403 매핑 실재(basePackages 가 두 컨트롤러 다 커버) · OpenAPI 스냅샷 0건 ·
  사이드바 사용처 admin 2라우트뿐 · `verify-master-plan` 신규 3파일 포함 상태에서 **PASS(EXIT=0)**.

**편차.** `office-hours`·`superpowers:brainstorming` 미호출 (사유는 spec §9·§10).
**남은 편향** — 작성자=리뷰어. outside voice(`codex`) 미설치로 #308~#311 과 동형 편향 잔존.

## Plan

### 착수 전 확인된 테스트 하네스 제약 (RED 설계를 좌우함)

| 제약 | 근거 | 처치 |
|---|---|---|
| `WorkflowSchemeControllerTest` 의 resolver 는 `mockk(relaxed = true)` — `requirePermission` no-op | `WorkflowSchemeControllerTest.kt:84` | T1 RED 는 `every { … } throws` 로 **던지게** 세워야 성립 |
| `ProjectWorkflowSchemeControllerTest` 는 MockK 대신 **손수 짠 stub** 사용 (MockK 1.13.x 가 `@JvmInline value class` 파라미터 서명 생성 실패) | 같은 파일 L51-55, L65-88 | T2 는 mockk 못 씀. 기존 `CapturingPermissionResolverStub` 을 **확장**해야 함 |
| 그 stub 의 `requirePermission` 은 **캡처만 하고 절대 안 던진다** | L71-80 | T2 RED 를 위해 `var denyWith: RuntimeException? = null` 추가 필요 |
| `StubWorkflowSchemeApplicationService` 는 `assignToProject`·`findAssignedScheme` 만 override. `list()` 는 부모 구현 → `schemeRepo`(비-relaxed `mockk()`)를 타 예외 | L96-130 | T2 는 stub 에 `list()` override + `listResponse` 필드 추가 필요 |

---

### Task 1. `WorkflowSchemeController.list`·`get` 에 MANAGE_SCHEME/Global 게이트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeControllerTest.kt`]
- depends-on: []

**RED**. `WorkflowSchemeControllerTest.kt` 에 2개 추가.

```kotlin
@Test
@WithMockUser(username = AUTH_ACTOR_UUID_STRING)
fun `GET 스킴 목록 — 권한 거부 시 403 + 본문에 스킴 정보 0건`() {
    every { applicationService.listWithCounts() } returns listOf(/* key = "software-scheme" 포함 */)
    every { permissionResolver.requirePermission(any(), any(), any()) } throws
        WorkflowSchemeAccessDeniedException(authActorUuid, MANAGE_SCHEME, WorkflowSchemeScope.Global)

    mockMvc.perform(get("/api/v1/workflow-schemes").accept(APPLICATION_JSON))
        .andExpect(status().isForbidden)
        // ★ 판별자 — 상태코드가 아니라 본문. 게이트 이전에는 200 + 목록이 나온다.
        .andExpect(content().string(not(containsString("software-scheme"))))
}
```

`get` 도 동형(판별자 = 매핑의 `workflowKey` 문자열 미출현).

**실패 메시지 (예상)**. `Status expected:<403> but was:<200>` — `requirePermission` 이 호출되지 않아
예외 자체가 발생하지 않는다. **본문 판별자도 동시에 실패**(목록이 그대로 실림).

⚠️ 선행 확인. 이 테스트 클래스의 `TestMvcConfig` 에 `WorkflowSchemeExceptionHandler` 빈이 등록돼
있는지 확인. 없으면 등록해야 403 매핑이 슬라이스에서 동작한다
(`ProjectWorkflowSchemeControllerTest.kt:155` 가 선례).

**GREEN**. 두 핸들러 진입 직후에 기존 쓰기 5개와 **완전히 동일한 3줄**.

```kotlin
val actor = CurrentActor.current()
permissionResolver.requirePermission(actor.toUuid(), WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
```

**REFACTOR**. 클래스 KDoc L49-53 "모든 mutating endpoint 는 …" → "모든 endpoint 는 …" 으로 정정
(현재 문구가 읽기 제외를 정당화하는 것처럼 읽힌다). 각 핸들러 KDoc 에 `@throws … 403` 추가.

**검증**. `cd backend && ./gradlew :modules:project-workflow:test --tests '*WorkflowSchemeControllerTest'`

**주의**. 기존 GET 테스트 6개는 relaxed mock 이라 **그대로 green 을 유지한다.** 이는 정상이며,
"기존 테스트가 안 깨졌으니 안전하다" 로 해석하면 안 된다(spec EC-2).

---

### Task 2. `GET /projects/{projectKey}/assignable-workflow-schemes` 신설

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeControllerTest.kt`]
- depends-on: []

**RED**. 먼저 stub 2개를 확장한 뒤(테스트 하네스 변경도 RED 커밋에 포함) 테스트 4개 추가.

1. `CapturingPermissionResolverStub` 에 `var denyWith: RuntimeException? = null` 추가.
   `requirePermission` 본문 끝에 `denyWith?.let { throw it }`. `reset()` 에도 `denyWith = null`.
2. `StubWorkflowSchemeApplicationService` 에 `var listResponse: List<WorkflowScheme> = emptyList()` +
   `override fun list(): List<WorkflowScheme> = listResponse`.

```kotlin
@Test @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
fun `GET assignable — 200 + 스킴 배열`()                      // T3-a
@Test @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
fun `GET assignable — ASSIGN_SCHEME + Project(projectKey) 스코프로 호출`()  // T3-b ★
@Test @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
fun `GET assignable — 권한 거부 시 403 + 본문에 스킴 key 0건`()  // T4-a
@Test @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
fun `GET assignable — 미해석 projectKey 404`()                 // T4-b
```

**T3-b 가 가장 중요하다.** `capturedPermission == ASSIGN_SCHEME` **그리고**
`capturedScope == WorkflowSchemeScope.Project("ATLAS")` 를 둘 다 단언한다.
Global 로 잘못 쓰면 프로젝트 관리자가 403 을 맞아 S4 가 다시 깨지는데, **이 단언이 그 회귀의 유일한 방어선**이다.

**실패 메시지 (예상)**. `Status expected:<200> but was:<404>` — 핸들러 미존재.

**GREEN**. 기존 `getAssignedScheme`(L107-124)와 **동일한 4단계 순서**로 핸들러 추가.

```kotlin
@GetMapping("/{projectKey}/assignable-workflow-schemes")
fun listAssignableSchemes(@PathVariable projectKey: String): ResponseEntity<DataResponse<List<SchemeResponse>>> {
    val actor = CurrentActor.current()                                    // 1. 인증 먼저 (KDoc L50-52 계약)
    val projectId = projectLookupPort.findIdByKey(ProjectKey(projectKey)) // 2. 404
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
    permissionResolver.requirePermission(                                  // 3. 403
        actor.toUuid(), WorkflowSchemePermission.ASSIGN_SCHEME, WorkflowSchemeScope.Project(projectKey))
    return ResponseEntity.ok(DataResponse(data = appService.list().map { it.toResponse() }))  // 4.
}
```

- `SchemeResponse` · `toResponse()` (L160·L178) **재사용**. 신규 DTO 0.
- `appService.list()` (L246) **재사용**. 신규 서비스 메서드 0.
- `projectId` 는 404 판정에만 쓰이고 조회에는 안 쓴다(전역 목록). 미사용 경고 시 `_` 대신
  기존 핸들러와 동일하게 변수로 두되 KDoc 에 "존재 검증 전용" 명시.

**REFACTOR**. 클래스 KDoc 의 endpoint 목록(L33-35)에 3번째 줄 추가. `@RequestMapping` 주석 정합.

**검증**. `cd backend && ./gradlew :modules:project-workflow:test --tests '*ProjectWorkflowSchemeControllerTest'`

---

### Task 3. 프론트 계약 + 훅 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/workflow-schemes.types.ts`, `apps/web/src/api/workflow-schemes.ts`, `apps/web/src/hooks/use-workflow-schemes.ts`, `apps/web/src/mocks/scheme-handlers.ts`, `apps/web/src/api/__tests__/workflow-schemes.test.ts`, `apps/web/src/hooks/__tests__/use-workflow-schemes.test.tsx`]
- depends-on: [2]

> `depends-on: [2]` 는 **계약 의존**이다. 응답 형태를 Task 2 의 실제 Kotlin `SchemeResponse` 에서
> 확정해야 한다(메모리 `frontend-zod-backend-dto-contract-gap` — DTO invent 금지).

**RED**.

```ts
// api/__tests__/workflow-schemes.test.ts
it('assignableSchemeResponseSchema — id/description null 을 허용한다', () => { ... })
it('fetchAssignableWorkflowSchemes — /projects/:key/assignable-workflow-schemes 를 호출한다', () => { ... })
// hooks/__tests__/use-workflow-schemes.test.tsx  (기존 케이스는 유지 — 관리용은 살아 있다)
it('useAssignableWorkflowSchemes — 프로젝트 스코프 목록을 반환한다', () => { ... })
```

**실패 메시지 (예상)**. `assignableSchemeResponseSchema is not exported` / 훅 미존재.

**GREEN**.

```ts
// workflow-schemes.types.ts — ★ 기존 schemeResponseSchema 재사용 금지 (필수 필드 3개가 응답에 없다, EC-4)
export const assignableSchemeResponseSchema = z.object({
  id: z.number().int().nullable(),        // EC-5 — backend SchemeResponse.id: Long?
  key: z.string().min(1),                 // EC-7 — 관리용의 schemeKey 와 필드명이 다르다
  name: z.string().min(1),
  description: z.string().nullable(),     // EC-6 — 관리용은 non-null, 여기는 nullable
  isDefault: z.boolean(),
})
export type AssignableSchemeResponse = z.infer<typeof assignableSchemeResponseSchema>
```

- `workflow-schemes.ts` — `fetchAssignableWorkflowSchemes(projectKey)`,
  `apiGet('/api/v1/projects/${projectKey}/assignable-workflow-schemes', dataOf(z.array(...)))`
- `use-workflow-schemes.ts` — `useAssignableWorkflowSchemes(projectKey)`,
  queryKey `['projects', projectKey, 'assignable-workflow-schemes']` (EC-8 충돌 없음), `staleTime: 30_000`
- `scheme-handlers.ts` — **10번째 핸들러 추가.** 기존 9개 무변경.
  파일 상단 KDoc 의 endpoint 목록(L60-68)에도 한 줄 추가

**REFACTOR**. `SCHEME_KEYS` 에 `assignable: (projectKey) => [...]` 상수 추가(매직 문자열 방지, 기존 관례).

**검증**. `pnpm test -- workflow-schemes`

**주의**. `useWorkflowSchemes`·`fetchWorkflowSchemes`·기존 9개 MSW 핸들러는 **건드리지 않는다**(R7).

---

### Task 4. 배정 화면을 신규 훅으로 교체

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.workflow-scheme.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.workflow-scheme.test.tsx`]
- depends-on: [3]

**RED**. 기존 테스트 파일에서 관리용 목록 엔드포인트 의존을 신규 엔드포인트 의존으로 바꾼다.
바꾸는 순간 화면이 아직 옛 훅을 쓰므로 **Select 옵션 0개**로 실패한다.

```tsx
it('배정 Select 가 assignable 엔드포인트의 스킴으로 채워진다', () => { ... })
```

**실패 메시지 (예상)**. Select 옵션 미발견 (화면이 여전히 `useWorkflowSchemes` 호출).

**GREEN**.
- L21 import → `useAssignableWorkflowSchemes`
- L61 `useWorkflowSchemes()` → `useAssignableWorkflowSchemes(projectKey)`
- **EC-7** — 옵션 렌더/선택 로직의 `scheme.schemeKey` 참조를 `scheme.key` 로 전수 교체
- L49-50 KDoc 주석의 "전체 스킴 목록" 설명 갱신

**REFACTOR**. 없음(교체만).

**검증**. `pnpm test -- projects.\$projectKey.settings.workflow-scheme`

---

### Task 5. E2E 확인 + 뮤테이션으로 봉인 실효 검증

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/workflow-scheme-assignment.spec.ts`]
- depends-on: [1, 2, 4]

**절차** (TDD 사이클 아님 — 검증 task).

1. **배정 E2E 통과 확인.** `pnpm test:e2e -- workflow-scheme-assignment`.
   깨지면 MSW 핸들러 경로/응답 형태 불일치 — Task 3 으로 되돌린다.
   ⚠️ 필터 인자가 삼켜지는 함정 있음(메모리 `e2e-playwright-filter-arg-drop`) — **실행 개수를 눈으로 확인**.
2. **관리 E2E 4개 무변경 확인.** `workflow-scheme-crud` · `-mappings` · `-standard-protect` · `-in-use-modal`.
3. **★ 뮤테이션 (spec §8 T6).** **반드시 커밋 후**에 수행(메모리 `mutation-test-requires-committed-baseline` —
   미커밋 상태에서 `git checkout --` 원복은 작업 소실).
   - 기준선. `./gradlew :modules:project-workflow:test` **EXIT=0 선확인**
   - M1. `list` 의 `requirePermission` 한 줄 삭제 → T1 의 list 테스트가 **red** 여야 함
   - M2. `get` 의 한 줄 삭제 → T1 의 get 테스트가 **red**
   - M3. Task 2 의 스코프를 `Project(projectKey)` → `Global` 로 변경 → **T3-b 가 red**
   - **`BUILD SUCCESSFUL` 은 나쁜 소식이다**(메모리 `seal-blinds-existing-guard`). 하나라도 green 이면
     그 테스트는 봉인이 아니라 장식이다
   - 각 뮤테이션 후 `git checkout -- <파일>` 로 원복
4. **전체 통과.** `cd backend && ./gradlew :modules:project-workflow:test ktlintCheck detekt` ·
   `pnpm verify` · `bash scripts/verify-master-plan.sh`

**검증**. 위 4단계 전부 기록으로 남긴다(뮤테이션은 M1~M3 각각의 red 확인 로그 포함).

## Plan 메타

- **task 수**: 5 (T1·T2 backend / T3·T4 frontend / T5 qa)
- **의존 그래프**: `T1 ⟂ T2` → `T3(계약: T2)` → `T4` → `T5(T1,T2,T4)`
- **예상 wave**: 4 (wave1 = T1+T2, wave2 = T3, wave3 = T4, wave4 = T5)
  - ⚠️ T1·T2 는 같은 Gradle 모듈이라 **컴파일이 직렬화**된다(메모리 `bts-plan-wave-gradle-module-compile`).
    병렬 dispatch 해도 벽시계 이득은 제한적이며, 두 task 가 **서로 다른 파일**이므로 충돌은 없다.
- **TDD 강제**: yes (T5 제외 — 검증 task)
- **추가 검증**: ktlint · detekt · vitest · playwright · verify-master-plan · **뮤테이션 3종**
- **신규 코드 총량**: 백엔드 핸들러 1 + 게이트 6줄 · 프론트 스키마 1 + fetch 1 + 훅 1 + MSW 핸들러 1
- **변경 없음 보장**: 마이그레이션 · enum · 서비스 메서드 · DTO · 관리 화면 · 관리 E2E 4개

## 리뷰 결과 (← /bts-review-plan 채움)

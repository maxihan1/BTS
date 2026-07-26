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

> **2026-07-26 plan-eng-review 에서 이 표가 2행 정정됐다.** 초안은 MockK 제약을 resolver 에
> 귀속했으나 실제로는 **application service** 쪽이고, 더 위험한 제약 하나(`clearMocks` 부재)를
> 놓치고 있었다. 정정본이 아래다.

| 제약 | 근거 (실측) | 처치 |
|---|---|---|
| `WorkflowSchemeControllerTest` 의 resolver 는 `mockk(relaxed = true)` — `requirePermission` no-op | `WorkflowSchemeControllerTest.kt:84` | T1 RED 는 `every { … } throws` 로 **던지게** 세워야 성립 |
| **★그 mock 은 Spring 싱글턴이고 `@BeforeEach` 에 `clearMocks` 가 없다** — stubbing 이 클래스 전체로 샌다 | `:114-117` (MockMvc 만 재생성) · `clearMocks`/`@DirtiesContext` **0건** | **T1 이 `clearMocks(permissionResolver)` 를 같이 넣어야 한다.** 없으면 T1 이 자기 클래스의 나머지 테스트를 순서 의존적으로 깨뜨린다 |
| MockK 1.13.x 의 `@JvmInline value class` 제약은 **application service** 쪽이다 (`ActorId`·`WorkflowSchemeKey` 파라미터). `requirePermission(UUID, enum, sealed)` 은 **mockk 가능** | `ProjectWorkflowSchemeControllerTest.kt:52-55` KDoc | T2 가 손수 stub 을 쓰는 이유는 service 제약. T1 의 `every … throws` 는 정상 |
| `CapturingPermissionResolverStub.requirePermission` 은 **캡처만 하고 절대 안 던진다** | `ProjectWorkflowSchemeControllerTest.kt:71-80` | T2 RED 를 위해 `var denyWith: RuntimeException? = null` 추가 필요 |
| `StubWorkflowSchemeApplicationService` 는 `assignToProject`·`findAssignedScheme` 만 override. `list()` 는 부모 구현 → `schemeRepo`(비-relaxed `mockk()`)를 타 예외 | 같은 파일 L96-130 | T2 는 stub 에 `list()` override + `listResponse` 필드 추가. kotlin-allopen 이 `@Transactional` 멤버를 열어 주므로 가능(기존 `override fun assignToProject` 가 증명) |
| `WorkflowSchemeExceptionHandler` 빈은 **이미 등록돼 있다** | `WorkflowSchemeControllerTest.kt:96` | 초안의 "없으면 추가" 사전확인 **불요** |
| worktree 에 `node_modules` 가 **없다**(루트·`apps/web` 둘 다) | 실측 | **T3 의 0번 단계 = 레포 루트에서 `pnpm install`**(워크스페이스 설치, `apps/web` 안에서 아님) |

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

**★필수 동반 변경 — `clearMocks`.** 같은 커밋에서 `@BeforeEach` 를 다음으로 바꾼다.

```kotlin
@BeforeEach
fun setUp() {
    clearMocks(permissionResolver)   // ← 신규. 없으면 위 throws 가 클래스 전체로 샌다
    mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
}
```

resolver 는 `TestMvcConfig` 의 Spring 싱글턴이라 `every … throws` 가 테스트 종료 후에도 남는다.
`clearMocks` 없이 RED 를 넣으면 C1·C4~C7·P1 이 순서 의존적으로 403 을 받아 **T1 이 자기 클래스를 깨뜨린다.**
`clearMocks` 는 relaxed 설정을 유지하므로 기존 테스트는 그대로 통과한다.

✅ `WorkflowSchemeExceptionHandler` 빈은 `WorkflowSchemeControllerTest.kt:96` 에 **이미 등록**돼 있다.

**GREEN**. 두 핸들러 진입 직후에 기존 쓰기 5개와 **완전히 동일한 3줄**.

```kotlin
val actor = CurrentActor.current()
permissionResolver.requirePermission(actor.toUuid(), WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
```

**REFACTOR**. 클래스 KDoc L49-53 "모든 mutating endpoint 는 …" → "모든 endpoint 는 …" 으로 정정
(현재 문구가 읽기 제외를 정당화하는 것처럼 읽힌다). 각 핸들러 KDoc 에 `@throws … 403` 추가.

**검증**. `cd backend && ./gradlew :modules:project-workflow:test --tests '*WorkflowSchemeControllerTest'`

**주의**. 기존 테스트(이 클래스 `@Test` **20개** · GET 요청 **5개**)는 relaxed mock 이라
`clearMocks` 를 넣으면 **그대로 green 을 유지한다.** 이는 정상이며,
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

**0단계 (TDD 이전, 필수).** **레포 루트**에서 `pnpm install`.
worktree 에는 루트·`apps/web` 어느 쪽에도 `node_modules` 가 없다(실측). 이 단계 없이는 T3~T5 의
모든 프론트 명령이 "모듈 없음" 으로 터지고, 그 증상이 코드 결함으로 오인된다
(메모리 `worktree-node-modules-partial-install` · `worktree-pnpm-verify-deps-symlink`).
**`apps/web` 안에서 실행하지 말 것** — pnpm 워크스페이스라 루트 설치여야 한다.

**GREEN**.

```ts
// workflow-schemes.types.ts — ★ 기존 schemeResponseSchema 재사용 금지 (필수 필드 3개가 응답에 없다, EC-4)
// ★ Maxi 결정 D3=1A — 경계에서 프론트 어휘로 정규화한다. 프론트에 어휘 두 벌을 만들지 않는다.
export const assignableSchemeResponseSchema = z
  .object({
    id: z.number().int().nullable(),      // EC-5 — backend SchemeResponse.id: Long?
    key: z.string().min(1),               // 백엔드 이름
    name: z.string().min(1),
    description: z.string().nullable(),   // EC-6 — 관리용은 non-null, 여기는 nullable
    isDefault: z.boolean(),               // 백엔드 이름
  })
  .transform((s) => ({
    id: s.id,
    schemeKey: s.key,                     // → 프론트 어휘 (관리용 schemeResponseSchema 와 일치)
    name: s.name,
    description: s.description,
    isStandard: s.isDefault,              // → 프론트 어휘
  }))
export type AssignableSchemeResponse = z.infer<typeof assignableSchemeResponseSchema>
```

⚠️ **초안의 EC-7 은 반대로 적혀 있었다** — "화면의 `scheme.schemeKey` 를 `scheme.key` 로 전수 교체".
D3=1A 로 **철회**됐다. 화면 코드의 `schemeKey` 참조는 **그대로 둔다**(T4 참조).

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

**RED**. 테스트 2개.

```tsx
// R1 — 창구 전환
it('배정 Select 가 assignable 엔드포인트의 스킴으로 채워진다', () => { ... })

// R2 — ★CRITICAL 회귀 테스트 (REGRESSION RULE, 질문 없이 필수)
it('403 이면 권한 안내를 보여주고 빈 Select 로 두지 않는다', () => { ... })
```

**실패 메시지 (예상)**. R1 = Select 옵션 미발견(화면이 여전히 `useWorkflowSchemes` 호출).
R2 = 안내 문구 미발견 — 현재 화면에 **error 분기 자체가 없다**.

**왜 R2 가 회귀인가.** 오늘은 무권한 엔드포인트라 200 이 오지만 이 PR 이후 403 이다. 그런데
`const schemeOptions = schemes ?? []`(`settings.workflow-scheme.tsx:78`)가 `undefined` 를 빈 배열로
삼켜 **빈 드롭다운 + 설명 0** 이 된다. 기존 동작 변경 · 기존 테스트 미커버 · 기존 호출자에 새 실패 모드
⇒ 세 조건을 모두 충족하는 회귀다.

**GREEN**.
- L21 import → `useAssignableWorkflowSchemes`
- L61 `useWorkflowSchemes()` → `useAssignableWorkflowSchemes(projectKey)` (`error` 도 함께 구조분해)
- **★D6=4A** — `isLoading` 분기 다음에 403 전용 분기 추가.
  ```tsx
  if (error instanceof ApiError && error.status === 403) return <NoWorkflowPermissionCard />
  ```
  문구는 `i18n/workflow-scheme-labels.ts` 에 신규 키로 추가(현재 forbidden/error 키 **0건**).
  **빈 목록(200 + `[]`)과 반드시 구분** — 같은 증상(빈 Select)에 원인이 둘이다.
- **EC-7 철회** — `scheme.schemeKey` 참조는 **건드리지 않는다**(D3=1A 로 Zod 가 정규화).
- L49-50 KDoc 주석의 "전체 스킴 목록" 설명 갱신

**REFACTOR**. 403 카드를 기존 `UnassignedSchemeCard` 와 같은 파일/패턴으로 정렬.

**검증**. `pnpm test -- projects.\$projectKey.settings.workflow-scheme`

---

### Task 6. 핸들러 행렬 봉인 (ArchUnit) — ★Maxi 결정 D5=3B

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/archunit/SchemeHandlerPermissionMatrixTest.kt`]
- depends-on: [1, 2]

**왜.** T1·T2 는 **손댄 3핸들러만** 봉인한다. N4 의 결함 클래스는 "새 핸들러가 가드를 빠뜨려도
아무도 모른다" 이고, 그 상태가 **14개월** 지속됐다. 스펙(`2026-05-28-…-d6-ui-crud.md:57`)이
"모든 endpoint 권한 검증" 을 이미 요구했는데도 못 잡았다. #309 가 라우트에 대해 쓴 처방
(**미분류 = 실패**)을 컨트롤러 핸들러에 적용한다.

**RED**. 봉인 2축.

1. **호출 강제** — `com.bts.workflow.scheme.web` 의 모든 요청 매핑 메서드가
   `WorkflowSchemePermissionResolver.requirePermission` 을 호출해야 한다.
2. **분류 강제** — 핸들러 → `(permission, scopeKind)` 분류맵을 테스트가 들고 있고,
   **매핑 메서드 중 맵에 없는 것이 하나라도 있으면 실패**.

```
현재 분류 (10 핸들러)
  WorkflowSchemeController        create·update·delete·addMapping·deleteMapping  → (MANAGE_SCHEME, Global)
                                  list·get                                        → (MANAGE_SCHEME, Global)  ← T1 신규
  ProjectWorkflowSchemeController assignScheme·getAssignedScheme                  → (ASSIGN_SCHEME, Project)
                                  listAssignableSchemes                           → (ASSIGN_SCHEME, Project) ← T2 신규
```

**실패 메시지 (예상)**. 축 1·2 모두 T1·T2 완료 후엔 통과하므로, **RED 는 의도적 위반 주입으로 만든다** —
`list` 의 `requirePermission` 을 임시 제거해 축 1 이 red, 분류맵에서 한 행을 빼 축 2 가 red 임을 확인한 뒤 복원.
(메모리 `archunit-vacuous-rule-silent-pass` — 룰이 vacuous 하게 통과하는지 반드시 확인.)

**GREEN / 구현 주의 (실현 가능성 실측 완료)**.
- ✅ `archunit-junit5:1.3.0` 이 이미 `build.gradle.kts:138` 에 있다. **신규 의존성 0.**
- ✅ `ProjectWorkflowArchitectureTest.kt:34-40` 이 `com.bts.workflow` 를 `DoNotIncludeTests()` 로
  이미 임포트한다 — 그 `JavaClasses` 를 재사용.
- ✅ 레포에 `archunit.properties` 가 **0건**이라 `failOnEmptyShould` 가 1.x 기본값(fail)이다.
  vacuous 통과 위험은 낮지만 위 위반 주입은 그대로 수행한다.
- ⚠️ **`ArchConditions.callMethodWhere(...)` 는 `ArchCondition<JavaClass>` 라
  `methods().should(...)` 와 타입이 맞지 않는다.** 한 줄로 안 끝난다 —
  `JavaCodeUnit.getMethodCallsFromSelf()` 를 도는 **수제 `ArchCondition<JavaMethod>`** 로 작성한다.
  (outside voice 지적, 컴파일 미검증이므로 착수 시 먼저 확인할 것.)
- ⚠️ **동일 클래스 내 private 가드 헬퍼 1단계는 허용**하도록 조건을 쓴다.
  `PostActionController.kt:74·147` 이 이미 private `requireManageScheme()` 경유 패턴이라,
  직접 호출만 인정하면 BC 관례와 어긋나고 향후 DRY 리팩토링을 금지하게 된다.

**REFACTOR**. 분류맵 상단 KDoc 에 "행을 추가하지 않으면 테스트가 실패한다" 를 명시.

**검증**. `cd backend && ./gradlew :modules:project-workflow:test --tests '*SchemeHandlerPermissionMatrix*'`
+ 위반 주입 2회로 판별력 확인.

---

### Task 5. E2E 확인 + 뮤테이션으로 봉인 실효 검증

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/workflow-scheme-assignment.spec.ts`]
- depends-on: [1, 2, 4, 6]

**절차** (TDD 사이클 아님 — 검증 task).

1. **배정 E2E 통과 확인.** `pnpm test:e2e -- workflow-scheme-assignment`.
   깨지면 MSW 핸들러 경로/응답 형태 불일치 — Task 3 으로 되돌린다.
   ⚠️ 필터 인자가 삼켜지는 함정 있음(메모리 `e2e-playwright-filter-arg-drop`) — **실행 개수를 눈으로 확인**.
2. **관리 E2E 4개 무변경 확인.** `workflow-scheme-crud` · `-mappings` · `-standard-protect` · `-in-use-modal`.
3. **★ 뮤테이션 (spec §8 T6).** **반드시 커밋 후**에 수행(메모리 `mutation-test-requires-committed-baseline` —
   미커밋 상태에서 `git checkout --` 원복은 작업 소실).
   - 기준선. `./gradlew :modules:project-workflow:test` **EXIT=0 선확인**
   - M1. `list` 의 `requirePermission` 한 줄 삭제 → T1 의 list 테스트 **red** + **T6 축 1 도 red**
   - M2. `get` 의 한 줄 삭제 → T1 의 get 테스트 **red** + T6 축 1 red
   - M3. Task 2 의 스코프를 `Project(projectKey)` → `Global` 로 변경 → **T3-b red** + **T6 축 2 red**
   - M4. **분류맵에서 `listAssignableSchemes` 행 삭제** → T6 축 2 가 "미분류" 로 **red**
   - M5. **`@BeforeEach` 의 `clearMocks` 삭제** → 같은 클래스의 기존 테스트가 **순서 의존적으로 red**
     (T1 이 자기 클래스를 오염시키지 않는다는 것의 역검증)
   - **`BUILD SUCCESSFUL` 은 나쁜 소식이다**(메모리 `seal-blinds-existing-guard`). 하나라도 green 이면
     그 테스트는 봉인이 아니라 장식이다
   - 각 뮤테이션 후 `git checkout -- <파일>` 로 원복
4. **전체 통과.** `cd backend && ./gradlew :modules:project-workflow:test ktlintCheck detekt` ·
   **`./gradlew :modules:app:test`**(조립 부팅 — 백엔드 CI 부재라 신규 요청 매핑이 조립 컨텍스트에서
   부팅되는지 확인할 다른 수단이 없다. dev postgres 5433 필요.
   메모리 `no-backend-ci-and-assembly-merge-verification-traps` · `prod-assembly-boot-verification-required`) ·
   `pnpm verify` · `bash scripts/verify-master-plan.sh`

**검증**. 위 4단계 전부 기록으로 남긴다(뮤테이션은 M1~M3 각각의 red 확인 로그 포함).

## Plan 메타

- **task 수**: **6** (T1·T2·T6 backend / T3·T4 frontend / T5 qa) — 리뷰에서 T6 신설
- **의존 그래프**: `T1 ⟂ T2` → `T3(계약: T2)` → `T4` → `T5(T1,T2,T4,T6)`, `T6(T1,T2)`
- **예상 wave**: 4 (wave1 = T1+T2, wave2 = T3+T6, wave3 = T4, wave4 = T5)
  - ⚠️ T1·T2·T6 는 같은 Gradle 모듈이라 **컴파일이 직렬화**된다(메모리 `bts-plan-wave-gradle-module-compile`).
    병렬 dispatch 해도 벽시계 이득은 제한적이며, task 들이 **서로 다른 파일**이므로 충돌은 없다.
- **TDD 강제**: yes (T5 제외 — 검증 task)
- **추가 검증**: ktlint · detekt · vitest · playwright · verify-master-plan · **`:app:test` 조립 부팅** ·
  **뮤테이션 5종** · **ArchUnit 위반 주입 2회**
- **신규 코드 총량**: 백엔드 핸들러 1 + 게이트 6줄 + ArchUnit 테스트 1 ·
  프론트 스키마 1 + fetch 1 + 훅 1 + MSW 핸들러 1 + 403 카드 1
- **변경 없음 보장**: 마이그레이션 · enum · 서비스 메서드 · 백엔드 DTO · 관리 화면 · 관리 E2E 4개 ·
  `useWorkflowSchemes`·`fetchWorkflowSchemes`·기존 MSW 9핸들러

## 리뷰 결과

### plan-eng-review (2026-07-26, FULL_REVIEW, outside voice 포함)

**Step 0 — 범위**: 복잡도 체크 트리거(13파일 > 8) → **그대로 진행**(D2=A).
13 = 프로덕션 5 + 테스트/목 8, 신규 클래스·서비스 0. 설계 복잡도가 아니라 테스트 밀도.
[Layer 1] `@PreAuthorize` 미사용은 ADR `2026-06-05` §제약 1(모듈에 `oauth2-resource-server` 부재)로 정당.

| 섹션 | 결과 |
|---|---|
| Architecture | 2건 — Issue 1(어휘 두 벌) → **1A 정규화** · Issue 2(404/403 probe) → **2A 범위 밖+TODOS** |
| Code Quality | 1건 — Issue 3(**행렬 봉인 부재**) → **3B Task 6 신설**. DRY 가드 중복은 3B 가 감시하므로 #309 처방과 동일하게 해소 |
| Tests | 다이어그램 산출, GAP 3 + **CRITICAL 1** — Issue 4(403 무음 실패) → **4A 안내+회귀테스트** · Issue 5(`pnpm install` 무소유) → **5A T3 0단계** |
| Performance | **0건.** 신규 경로는 `findAll()` 단일 쿼리로 대체 대상(`listWithCounts()` 스칼라 서브쿼리 2개)보다 가볍다 |

**Outside voice (Claude 서브에이전트 — `codex` 미설치, D8=A)**. 사실 오류 **4건 적발**, 전부 실측 확인.

| # | 지적 | 처리 |
|---|---|---|
| 1 | **ADR 의 결정적 근거가 거짓** — 배정 화면은 실서버와 통신한 적 없음(Zod 필드 교집합 0) | ADR 에 **철회 박스** 추가. D9=B |
| 2 | 워크플로우 스킴 프론트 계약 3종 파손 → 모든 프론트 초록이 MSW끼리의 일치 | 범위 밖 + **TODOS 등재**(차단 사안 명시). D9=B |
| 3 | `clearMocks` 부재로 T1 RED 가 **자기 클래스를 오염** | T1 에 `clearMocks` **필수 동반**으로 반영 |
| 5·6 | ArchUnit 조건 타입 불일치 · private 헬퍼 경유 관례 | T6 구현 주의에 반영 |
| 7 | MockK 제약을 resolver 에 잘못 귀속 | 제약표 정정(실제는 app service) |
| 8 | `useWorkflowSchemeDetail` 소비자 미열거 | ADR 잔여위험 1 에 전수 결과 등재 |
| 9·10 | prod 에서만 거부되는 주체 2종(비멤버 SYSTEM_ADMIN · 비-기본 권한스킴) | **신규 손실 아님**(쓰기 게이트가 이미 동일) 확인 후 D10=A 로 문서화 |
| 11·12·13·14 | `instance` 자동채움 · `:app:test` 누락 · 루트 설치 · 개수 오기 | 각각 ADR 잔여위험 7 · T5 · T3 0단계 · 실측치 고정 |

**CROSS-MODEL TENSION.** 리뷰(작성자)는 "배정 경로는 실증돼 안전"이라 했고 outside voice 는
"그 실증은 존재하지 않는다" 고 했다. **실측 결과 outside voice 가 옳았다.** 초안의 주장은 철회됐다.

**BLOCKER**: 없음. **미해결 결정**: 없음(D2~D11 전부 응답).

## NOT in scope

| 항목 | 사유 |
|---|---|
| 프론트↔백엔드 Zod 계약 파손 3종 | 선재 결함. 보안 봉합 PR 을 계약 리팩토링으로 번지게 하면 리뷰 단위가 무너진다 (D9=B). **TODOS 등재** |
| `ProjectWorkflowSchemeController` 404/403 순서 정렬 | 한계 노출량 0(기존 2핸들러로 같은 정보 획득 가능) + 3핸들러 동시 수정 (D4=2A). **TODOS 등재** |
| `MANAGE_WORKFLOW` 비-기본 권한스킴 시드 | 신규 손실 아님(쓰기 게이트가 이미 동일) + 마이그레이션 0 전제를 깬다 (D10=A). **TODOS 등재** |
| 시스템 관리자 fallback 추가 | 읽기 게이트를 쓰기보다 넓히면 N4 결함이 모양만 바꿔 돌아온다 (D10=A) |
| `VIEW_SCHEME` 권한 enum 신설 | 폭발 반경 과다(시드 마이그레이션 + `PermissionSchemaMigrationTest` 커플링). 제3의 읽기 주체가 생기면 재검토 (ADR 대안 C) |
| 컨트롤러 권한 가드 3줄 블록 DRY 추출 | #309 처방과 동일 — **중복은 제거하지 않고 행렬 테스트(T6)로 감시한다** |
| `WorkflowSchemeExceptionHandler` 의 `instance` 자동채움 | 경로에 비밀값 없어 유출 아님. N3 봉인은 선택자 없는 전역 advice 만 덮으므로 이 핸들러는 영구 봉인 밖 — 같은 트랙 후속 판단 |
| 배포/유통 파이프라인 | 신규 아티팩트 없음 (엔드포인트 1개 추가) |

## What already exists (재사용 vs 재건축)

| 필요한 것 | 이미 있는 것 | 위치 | 판정 |
|---|---|---|---|
| 배정용 최소 응답 DTO | `SchemeResponse` | `ProjectWorkflowSchemeController.kt:160` | **재사용** — 신규 DTO 0 |
| 카운트 없는 스킴 목록 조회 | `WorkflowSchemeApplicationService.list()` — **프로덕션 소비처 0건인 채 존재** | `:246` | **재사용(활성화)** — 신규 서비스 메서드 0 |
| soft-delete 필터 | `findAll()` 의 `WHERE deleted_at IS NULL` | `WorkflowSchemeRepository.kt:150` | **재사용** — 별도 필터 불요 |
| 403 매핑 + 본문 미노출 계약 | `@ExceptionHandler(WorkflowSchemeAccessDeniedException)` | `WorkflowSchemeExceptionHandler.kt:202` | **재사용** — 신규 예외 0 |
| 프로젝트 스코프 권한 판정 | `ASSIGN_SCHEME` + `WorkflowSchemeScope.Project` | `ProjectWorkflowSchemeController.kt:117` | **재사용** — enum·시드 0 |
| actor 결선 패턴 | `CurrentActor.current()` → `actor.toUuid()` | ADR `2026-06-05` | **재사용** |
| ArchUnit 인프라 | `archunit-junit5:1.3.0` + `ProjectWorkflowArchitectureTest` | `build.gradle.kts:138` | **재사용** — 신규 의존성 0 |
| `{data:T}` 래퍼 파싱 | `dataOf()` | `workflow-schemes.types.ts:12` | **재사용** |

**불필요하게 재건축한 것: 없음.**

## 실패 모드 (신규 코드경로별)

| 코드경로 | 실서비스 실패 시나리오 | 테스트 | 에러처리 | 사용자 인지 |
|---|---|---|---|---|
| `list`/`get` 게이트 | 권한 판정기가 예외를 던짐 | ✅ T1 | ✅ 403 매핑 | ✅ 관리 화면은 4-가드라 도달 자체가 없음 |
| 신규 `listAssignableSchemes` | 프로젝트 비멤버가 호출 → 403 | ✅ T2 | ✅ 403 | ✅ **T4 의 403 안내** |
| 신규 엔드포인트 | `projectKey` 미해석 → 404 | ✅ T2 | ✅ `ResponseStatusException` | ⚠️ 화면은 404 전용 표시 없음 — 라우트 도달 자체가 드묾 |
| 프론트 Zod 정규화 | 백엔드가 필드명을 바꾸면 parse 실패 | ✅ T3 | ✅ throw → error 분기 | ✅ T4 안내(403 외 에러도 무음 아님) |
| 배정 화면 목록 | **403 → 빈 드롭다운 무음** | ✅ **T4 회귀테스트** | ✅ **T4 403 카드** | ✅ |
| ArchUnit 행렬 | 룰이 vacuous 하게 통과 | ✅ T6 위반 주입 2회 | — | — |

**critical gap: 0건.** (리뷰 진입 시점엔 1건 — 배정 화면 403 무음 실패. D6=4A 로 해소.)

## 병렬화 전략 (worktree)

| Lane | task | 모듈 | 의존 |
|---|---|---|---|
| A | T1 ⟂ T2 → T6 | `backend/modules/project-workflow/` | T6 는 T1·T2 |
| B | T3 → T4 | `apps/web/` | T3 는 T2(계약) |
| C | T5 | `apps/web/e2e/` + backend 검증 | A·B 전부 |

**실행 순서.** A 와 B 를 병렬로 띄우되 **B 는 T2 완료 후 출발**(계약 확정). 둘 다 끝나면 C.
**충돌 플래그.** Lane A 의 세 task 는 같은 Gradle 모듈이라 **컴파일이 직렬화**된다
(메모리 `bts-plan-wave-gradle-module-compile`) — 파일은 안 겹치므로 머지 충돌은 없고 벽시계 이득만 제한적.
Lane A ↔ B 는 디렉토리가 완전히 분리돼 충돌 없음. **단일 worktree 로 충분** — 프론트 구현을 병렬로 쪼갤 때는
구현자가 커밋하지 말고 controller 가 순차 커밋할 것(learning `parallel-frontend-edit-only-controller-commit`).

## Implementation Tasks

리뷰 발견을 빌드 가능한 단위로 종합. 각 항목은 특정 발견에서 유래한다.

- [ ] **T1 (P1, human: ~1h / CC: ~10min)** — project-workflow — `list`/`get` 에 MANAGE_SCHEME/Global 게이트 + `clearMocks` 동반
  - Surfaced by: Outside voice #3 — `@BeforeEach` 에 `clearMocks` 부재로 RED 가 자기 클래스를 오염
  - Files: `WorkflowSchemeController.kt`, `WorkflowSchemeControllerTest.kt`
  - Verify: `./gradlew :modules:project-workflow:test --tests '*WorkflowSchemeControllerTest'`
- [ ] **T2 (P1, human: ~2h / CC: ~15min)** — project-workflow — `assignable-workflow-schemes` 엔드포인트 신설
  - Surfaced by: Architecture — 배정 화면 403 회귀 차단이 B안의 전제
  - Files: `ProjectWorkflowSchemeController.kt`, `ProjectWorkflowSchemeControllerTest.kt`
  - Verify: `./gradlew :modules:project-workflow:test --tests '*ProjectWorkflowSchemeControllerTest'`
- [ ] **T6 (P1, human: ~3h / CC: ~20min)** — project-workflow — ArchUnit 핸들러 행렬 봉인(미분류=실패)
  - Surfaced by: Code Quality Issue 3 (D5=3B) — 손댄 핸들러만 봉인하면 결함 클래스가 남는다
  - Files: `SchemeHandlerPermissionMatrixTest.kt` (신규)
  - Verify: 위반 주입 2회로 판별력 확인 후 `--tests '*SchemeHandlerPermissionMatrix*'`
- [ ] **T3 (P1, human: ~2h / CC: ~15min)** — apps/web — assignable Zod(어휘 정규화)+fetch+훅+MSW, 0단계 루트 `pnpm install`
  - Surfaced by: Architecture Issue 1 (D3=1A) + Test Issue 5 (D7=5A)
  - Files: `workflow-schemes.types.ts`, `workflow-schemes.ts`, `use-workflow-schemes.ts`, `scheme-handlers.ts`
  - Verify: `pnpm test -- workflow-schemes`
- [ ] **T4 (P1, human: ~1.5h / CC: ~12min)** — apps/web — 창구 교체 + **403 안내 카드 + 회귀 테스트**
  - Surfaced by: Test Issue 4 (D6=4A) CRITICAL — 403 무음 실패, REGRESSION RULE 발동
  - Files: `projects.$projectKey.settings.workflow-scheme.tsx`, `i18n/workflow-scheme-labels.ts`
  - Verify: `pnpm test -- projects.\$projectKey.settings.workflow-scheme`
- [ ] **T5 (P2, human: ~2h / CC: ~20min)** — qa — E2E + 뮤테이션 5종 + `:app:test` 조립 부팅
  - Surfaced by: Outside voice #12 — 백엔드 CI 부재라 조립 검증 수단이 이것뿐
  - Files: `e2e/workflow-scheme-assignment.spec.ts`
  - Verify: 4단계 전부 기록(뮤테이션 M1~M5 각각의 red 로그 포함)

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR (PLAN) | 5 issues, 0 critical gaps (1 해소) |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |
| Outside Voice | 서브에이전트 | 독립 제2의견 | 1 | issues_found | 14 findings, 4 factual corrections absorbed |

**CROSS-MODEL:** Outside voice 가 리뷰의 핵심 전제를 반증했고 실측으로 확인됐다 — 배정 화면이 실서버와
통신한 적이 없어 "게이트 통과가 실증됐다" 는 ADR 근거는 성립하지 않는다. 초안 주장은 철회, 근거는 코드
읽기로 재구성. 나머지 13건 중 6건은 계획 정정으로, 3건은 TODOS 등재로, 4건은 task 보강으로 흡수.

**VERDICT:** ENG CLEARED — ready to implement (6 tasks, 4 waves).

NO UNRESOLVED DECISIONS

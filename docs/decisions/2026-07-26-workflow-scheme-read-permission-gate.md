<!-- 워크플로우 스킴 읽기 API 의 권한 게이트 부재를 봉합하고 배정용 읽기 창구를 프로젝트 스코프로 분리하는 결정 -->
# 워크플로우 스킴 읽기 권한 게이트 — 관리용 Global / 배정용 Project 창구 분리

> 상태: 채택(Accepted)
> 날짜: 2026-07-26
> BC: project-workflow (단일) — cross-BC 0
> 맥락: 경로토큰 유출 표면 4건 중 N4, slug `workflowscheme-api-n4-list-get-manage-scheme`
> 관련: [2026-06-04-workflow-scheme-permission-prod-resolver](2026-06-04-workflow-scheme-permission-prod-resolver.md) ·
> [2026-06-05-workflow-scheme-controller-actor-wiring](2026-06-05-workflow-scheme-controller-actor-wiring.md)
> 선행 트랙: #311(N1) · #312(N2) · #313(N3)

## 배경

`WorkflowSchemeController` 의 핸들러 7개 중 쓰기 5개(`create` L82 · `update` L146 · `delete` L177 ·
`addMapping` L203 · `deleteMapping` L241)만 `permissionResolver.requirePermission` 을 호출한다.
읽기 2개 — `list`(L105) · `get`(L121) — 은 호출이 **0건**이다.

정확히는 "무인증 노출"이 아니라 **"인증만 되면 누구나 읽음"** 이다. 사내 1,000명 전원이 전체
워크플로우 스킴의 이름·설명·이슈타입↔워크플로우 매핑·사용 프로젝트 수를 조회할 수 있다.

반증 6경로(서비스 계층 · `SecurityConfig` · `@PreAuthorize` · 전역 `@Aspect` · 리포지토리 술어 ·
상위타입 default)를 전부 소진해 "다른 곳에서 막고 있지 않다"를 확정했다.

### 이것은 신규 정책이 아니라 스펙 준수 복원이다

`docs/specs/2026-05-28-fr-wf-02-d6-ui-crud.md:57` 이 이미 명시하고 있다.

> base `/api/v1`. **모든 endpoint 권한 검증** — `WorkflowSchemePermission.MANAGE_SCHEME` (**4.1**, 4.2, 4.4)
> / `ASSIGN_SCHEME` (4.3).

§4.1 은 스킴 CRUD 5개이고 그 안에 `GET /workflow-schemes`(list)·`GET /workflow-schemes/{schemeKey}`(get)
가 포함된다. 즉 **스펙은 처음부터 읽기에도 `MANAGE_SCHEME` 을 요구했고, 구현이 이를 이행하지 않았다.**
같은 spec L119 가 "권한 없는 사용자 — FR-PM-04 후속 정식 RBAC 도입 시 401/403 분기 추가"로 이연을
명시했으나, FR-PM-04(#73)가 쓰기 5개만 결선하고 읽기 2개를 남겼다. **문서↔구현 drift.**

BC 관례도 반대 방향이다 — `PostActionController.kt:74` 는 GET 에도 `MANAGE_SCHEME` 을 요구한다.

## 결정

### D1 — `list` · `get` 에 `MANAGE_SCHEME` / `WorkflowSchemeScope.Global` 게이트 추가

쓰기 5개와 동일한 Guard 패턴(컨트롤러 진입 직후 `CurrentActor.current()` → `requirePermission`)을 적용한다.
[2026-06-04 ADR](2026-06-04-workflow-scheme-permission-prod-resolver.md) §spec 단계 확정 D3 의
"스킴 CRUD(`MANAGE_SCHEME`/`Global`)는 시스템 관리자 전용 — Jira Cloud 모델, 스킴은 전역 자원"을
읽기까지 일관 적용하는 것이다.

### D2 — 배정 화면용 읽기 창구를 `ProjectWorkflowSchemeController` 에 프로젝트 스코프로 신설

D1 만 적용하면 프로젝트 관리자의 스킴 배정 화면이 깨진다. 그 화면
(`apps/web/src/routes/projects.$projectKey.settings.workflow-scheme.tsx:61`)이
`useWorkflowSchemes` → `GET /api/v1/workflow-schemes` 를 Select 옵션 소스로 소비하는데,
prod resolver 의 Global 판정은 `isSystemAdmin`(`IdentityAccessWorkflowSchemePermissionResolver.kt:54`)
이므로 프로젝트 관리자는 403 을 받는다.

따라서 배정에 필요한 읽기는 `ProjectWorkflowSchemeController` 에
`ASSIGN_SCHEME` / `WorkflowSchemeScope.Project(projectKey)` 게이트로 **별도 엔드포인트를 신설**하고,
프론트를 그쪽으로 전환한다.

**엔드포인트 경로·응답 DTO 형태는 spec 단계에서 확정한다.** 본 ADR 은 권한 모델과 배치만 정한다.
(선례 — 2026-06-04 ADR 이 D3/D4 판정 모델을 spec 으로 이연한 방식과 동형.)

### D3 — 배정용 응답은 관리용 응답의 부분집합으로 한다

관리용 `WorkflowSchemeDetailResponse` 는 `usedByProjectsCount`(다른 프로젝트가 몇 개나 쓰는지)와
전체 `mappings` 를 동봉한다. 배정 Select 는 이 중 어느 것도 필요하지 않다.
프로젝트 관리자에게 조직 전체의 스킴 사용 분포를 줄 이유가 없으므로, 배정용 응답은 최소 필드로 한정한다.

구체 필드 목록은 spec 에서 확정한다.

### D4 — 권한 enum 신설 없음, 마이그레이션 없음

`WorkflowSchemePermission` 은 `MANAGE_SCHEME` · `ASSIGN_SCHEME` 2종을 유지한다.
`role_permissions` 시드도 변경하지 않는다 — 배정용 창구가 소비하는 `ASSIGN_SCHEME`/Project 는
2026-06-04 ADR D4 가 이미 시드한 `MANAGE_WORKFLOW`(기본 권한 스킴 × `PROJECT_ADMIN`, V013)로 판정된다.

## 고려한 대안

**A — `get` 단건만 게이트.** `list` 를 그대로 열어둔다. 변경 한 줄, 회귀 위험 최소.
**기각** — `list` 가 전 스킴 이름·매핑 수·사용 프로젝트 수를 계속 노출한다. 가장 민감한 매핑 상세는
막지만 운영 구조 자체는 그대로 새어나간다. 문을 반만 닫는다.

**C — 읽기 전용 권한 `VIEW_SCHEME` 신설.** shared-kernel enum 추가 + prod resolver 분기 +
`role_permissions` 시드 마이그레이션 + `PermissionSchemaMigrationTest` 정확 카운트 단언 갱신
(메모리 `fr-pm-permission-seed-migration-test-coupling`).
**기각** — 개념적으로는 가장 정합하나 폭발 반경이 셋 중 가장 크다. 그리고 지금 필요한 읽기 주체는
"시스템 관리자(관리용)" 와 "프로젝트 관리자(배정용)" 둘뿐이고, 둘 다 기존 권한으로 정확히 표현된다.
제3의 읽기 주체가 생기는 시점에 재검토한다.

**B (채택)** — 기존 두 권한을 스코프로 나눠 재사용. 마이그레이션 0 · enum 0.

### B 를 택한 근거 — 게이트 자체가 기존 배정 동작과 동일하다

`ProjectWorkflowSchemeController` 는 GET(L107) · PUT(L78) **둘 다**
`ASSIGN_SCHEME` + `WorkflowSchemeScope.Project(projectKey)` 로 게이트돼 있다.
D2 는 새 권한 경로를 여는 것이 아니라 **이미 존재하는 게이트에 엔드포인트 하나를 더 붙이는 것**이다.
읽기 게이트가 쓰기(배정) 게이트와 정확히 같으므로 "보이는데 안 되는" 상태가 생기지 않는다.

프로젝트 관리자가 이 게이트를 통과한다는 근거는 **코드 읽기**다 —
`IdentityAccessWorkflowSchemePermissionResolver.hasProjectPermission`(L71-79)이
`projectDirectory.resolveKeyToId` → 멤버십 → `roleHasPermission(projectId, role, "MANAGE_WORKFLOW")`
순으로 평가하고, V013 이 기본 권한 스킴의 `PROJECT_ADMIN` 에 그 행을 시드한다.

> ### ⚠️ 초안의 오류 — 철회 (2026-07-26, plan-eng-review outside voice 가 반증)
>
> 초안은 여기에 **"배정 화면이 그 GET 을 `useGetAssignment` 로 이미 호출해 정상 동작 중이므로
> 실증된 사실"** 이라고 적었다. **사실이 아니다. 철회한다.**
>
> `fetchProjectAssignment`(`apps/web/src/api/workflow-schemes.ts:242`)는
> `dataOf(assignmentResponseSchema).parse(raw)` 를 태우는데,
>
> | 프론트가 요구 (`workflow-schemes.types.ts:48-52`) | 백엔드가 반환 (`ProjectWorkflowSchemeController.kt:160-166`) |
> |---|---|
> | `projectKey` · `schemeKey` · `schemeName` | `id` · `key` · `name` · `description` · `isDefault` |
>
> **필드 교집합이 0개다.** 실서버 응답에는 Zod 가 던진다. 그 화면이 동작해 온 것은
> MSW(`scheme-handlers.ts:212-221`)가 프론트 형태를 돌려주기 때문이다.
> ⇒ **아무도 프로젝트 관리자가 이 게이트를 통과하는 것을 관측한 적이 없다.**
>
> **교훈.** "이미 동작 중이다" 를 근거로 쓸 때는 **그 동작이 실서버 경로였는지**까지 확인해야 한다.
> 프론트 테스트의 초록은 MSW 가 MSW 와 맞는다는 뜻일 수 있다.
> [[frontend-zod-backend-dto-contract-gap]] · [[authenticated-error-path-token-leak-done]]("실측했다"는
> 단언은 표본 범위까지 확인) 의 재발.

## 결과

- 워크플로우 스킴의 운영 구조가 시스템 관리자에게만 보인다(스펙 L57 이행).
- 프로젝트 관리자는 자기 프로젝트 배정에 필요한 최소 정보만 프로젝트 스코프 게이트로 받는다.
- 마이그레이션 0 · 신규 enum 0 · FR 129 불변.
- 경로토큰 유출 표면 4건(N1~N4) 전량 봉합 완료.

## 잔여 위험

1. **소비자 전수 (확정).** outside voice 가 레포 전체를 훑어 확정했다 —
   `GET /workflow-schemes` → `WorkflowSchemeSidebar.tsx:193` · `settings.workflow-scheme.tsx:61`,
   `GET /workflow-schemes/{key}` → `useWorkflowSchemeDetail` → `admin.workflow-schemes.$schemeKey.tsx:93`.
   그 외 백엔드 모듈·시드 SQL·스크립트·docs 에서 호출 **0건**.
   `WorkflowSchemeControllerIntegrationTest` 는 컨트롤러를 우회해 서비스를 직접 호출하므로 무영향.
2. **음성 테스트의 판별자.** "여전히 403" 류의 vacuous 단언을 피한다
   (메모리 `negative-guard-needs-body-discriminator`). 판별자는 **본문의 스킴 key 문자열 부재**다.
3. **봉합이 기존 가드의 눈을 가린다**(메모리 `seal-blinds-existing-guard`).
   `WorkflowSchemeControllerTest.kt:84` 의 resolver 가 `mockk(relaxed = true)` 라
   게이트를 추가해도 기존 GET 테스트가 전부 green 을 유지한다. 이것이 D1 을 필요하게 만든 조건 자체다.
4. **`AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`) 때문에 non-prod 는 전부 통과한다.**
   게이트 실효는 resolver 가 **던지도록 세운** 테스트로만 확인된다.
5. **★prod 에서만 거부되는 주체 2종 (Maxi 결정 D10 — 게이트 폭을 넓히지 않고 문서화).**
   - **프로젝트 비멤버인 SYSTEM_ADMIN.** `WorkflowSchemeScope.Project` 판정에 시스템 관리자 fallback 이
     없다(`IdentityAccessWorkflowSchemePermissionResolver.kt:52-56, 76-78`). 멤버십 조회가 null → 거부.
   - **비-기본 권한 스킴에 매핑된 프로젝트의 관리자.** `MANAGE_WORKFLOW` 는 기본 스킴
     (`00000000-…-001`)의 `PROJECT_ADMIN` 에만 시드돼 있다(V013). 따라서 D4 의 "시드 변경 0" 은
     **기본 스킴 프로젝트에 한해** 참이다.

   **두 경우 모두 신규 기능 손실이 아니다** — 배정 실행(PUT L78-93)과 현재 배정 조회(GET L107-121)가
   이미 같은 게이트라 오늘도 적용 단계에서 403 을 맞는다. 신규 엔드포인트는 그 거부를
   **적용 시점에서 목록 시점으로 앞당길 뿐**이고, 명시적 403 안내와 합치면 진단성이 개선된다.
   TODOS.md 에 등재.
6. **★선재 계약 파손 (범위 밖, Maxi 결정 D9).** 워크플로우 스킴 프론트의 Zod 3종이 백엔드 DTO 와
   불일치한다(위 철회 박스 + `schemeResponseSchema` 의 `schemeKey`/`isStandard` 부재,
   `mappingResponseSchema` 의 `isDefault` 부재). **이 부채가 남는 한 스킴 기능의 어떤 PR 도
   프론트 테스트로 "회귀 없음" 을 증명할 수 없다** — 초록은 MSW 가 MSW 와 맞는다는 뜻이다.
   TODOS.md 에 등재. 착수 시 조립 부팅 실측부터.
7. **`instance` 자동채움.** 이 BC 의 `WorkflowSchemeExceptionHandler.problem()` 은 `pd.instance` 를
   설정하지 않아 Spring 이 요청 URI 로 채운다. 경로에 비밀값이 없어 유출은 아니나, N3(#313)의 봉인은
   **선택자 없는 전역 advice 만** 덮으므로 `basePackages` 를 가진 이 핸들러는 영구히 봉인 밖이다.
   같은 트랙의 후속 판단 대상으로 명시해 둔다.

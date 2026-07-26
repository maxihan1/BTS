<!-- 워크플로우 스킴 읽기 API 권한 게이트(N4) 의 시나리오·FR·API·엣지케이스·완료기준 스펙 -->
# WorkflowScheme 읽기 API 권한 게이트 봉합 (N4) — 스펙

> 날짜: 2026-07-26
> BC: project-workflow (단일)
> plan: [2026-07-26-workflowscheme-api-n4-list-get-manage-scheme](../plans/2026-07-26-workflowscheme-api-n4-list-get-manage-scheme.md)
> ADR: [2026-07-26-workflow-scheme-read-permission-gate](../decisions/2026-07-26-workflow-scheme-read-permission-gate.md)
> 선행 스펙: [2026-05-28-fr-wf-02-d6-ui-crud](2026-05-28-fr-wf-02-d6-ui-crud.md) (L57 이 본 작업의 근거)
> FR 영향: **없음 (129 불변).** 신규 FR 아님 — 기존 FR-WF-02 의 스펙 L57 미이행분 이행.

## 0. 한 줄

`GET /api/v1/workflow-schemes` 와 `GET /api/v1/workflow-schemes/{schemeKey}` 를 시스템 관리자 전용으로
잠그고, 프로젝트 관리자가 스킴 배정에 쓰던 읽기는 프로젝트 스코프 전용 엔드포인트로 분리한다.

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 시스템 관리자 — 관리 화면 정상 동작 (회귀 없음)

Given `SYSTEM_ADMIN` 전역 역할 보유자로 로그인
When `/admin/workflow-schemes` 진입 → `GET /api/v1/workflow-schemes` 발생
Then 200 + 전체 스킴 목록(카운트 동봉). **현행과 동일.**

### S2. 일반 사원 — 관리용 목록 차단 (본 작업의 핵심)

Given 시스템 관리자가 **아닌** 인증 사용자
When `GET /api/v1/workflow-schemes` 직접 호출
Then **403** + 본문에 스킴 정보 **0건**. (현행. 200 + 전체 목록)

### S3. 일반 사원 — 관리용 단건 차단

Given 시스템 관리자가 아닌 인증 사용자
When `GET /api/v1/workflow-schemes/software-scheme` 직접 호출
Then **403** + 본문에 매핑 정보 **0건**. (현행. 200 + 매핑 상세 전량)

### S4. 프로젝트 관리자 — 배정 화면 정상 동작 (회귀 방지의 핵심)

Given `MANAGE_WORKFLOW` 권한을 가진 프로젝트 ATLAS 의 멤버(시스템 관리자 아님)
When `/projects/ATLAS/settings/workflow-scheme` 진입
Then 배정 Select 에 선택 가능한 스킴 목록이 표시되고 배정이 성공한다.
**S2 로 관리용 목록이 막혀도 이 화면은 깨지지 않아야 한다.**

### S5. 타 프로젝트 관리자 — 남의 프로젝트 배정 후보 차단

Given 프로젝트 BETA 의 관리자 (ATLAS 비멤버)
When `GET /api/v1/projects/ATLAS/assignable-workflow-schemes` 호출
Then **403**. (`WorkflowSchemeScope.Project("ATLAS")` 판정에서 멤버 게이트 탈락)

### S6. 미인증 요청

Given 인증 없음
When 위 3개 읽기 엔드포인트 중 아무거나 호출
Then **401**. `CurrentActor.current()` 가 권한 판정 이전에 던진다. (현행 유지)

## 2. 기능 요구사항 (FR)

> 신규 FR ID 를 발급하지 않는다. 기존 FR-WF-02 스펙(L57)의 미이행분 이행 + FR-PM-04 판정 모델 적용.

- **R1.** `WorkflowSchemeController.list` 진입 직후 `CurrentActor.current()` →
  `requirePermission(actor, MANAGE_SCHEME, WorkflowSchemeScope.Global)` 호출.
- **R2.** `WorkflowSchemeController.get` 동일 처리.
- **R3.** `ProjectWorkflowSchemeController` 에 신규 핸들러
  `GET /api/v1/projects/{projectKey}/assignable-workflow-schemes` 추가.
  진입 직후 `CurrentActor.current()` → `projectLookupPort.findIdByKey`(404) →
  `requirePermission(actor, ASSIGN_SCHEME, WorkflowSchemeScope.Project(projectKey))`.
  **기존 두 핸들러(L78 `assignScheme` · L107 `getAssignedScheme`)의 순서를 그대로 따른다.**
- **R4.** R3 의 응답은 기존 `SchemeResponse`(`ProjectWorkflowSchemeController.kt:160`) 리스트.
  = `{ id, key, name, description, isDefault }`. **신규 DTO 정의 없음.**
- **R5.** R3 은 `WorkflowSchemeApplicationService.list()`(L246, `@Transactional(readOnly=true)`,
  `List<WorkflowScheme>` 반환)를 소비한다. **신규 서비스 메서드 없음.**
- **R6.** 프론트 — 배정 화면 전용 훅 `useAssignableWorkflowSchemes(projectKey)` 신설.
  `projects.$projectKey.settings.workflow-scheme.tsx:61` 을 이 훅으로 교체.
- **R7.** `useWorkflowSchemes`(관리용)는 **그대로 유지**한다. 소비자는 `WorkflowSchemeSidebar.tsx:193`
  하나만 남고, 그 화면은 `/admin/workflow-schemes` 4-가드(requireAuth+systemAdmin+passwordChanged+MFA)
  아래 있어 정합하다.
- **R8.** MSW(Mock Service Worker — 브라우저/테스트에서 API 응답을 가로채 흉내내는 라이브러리)
  핸들러에 R3 엔드포인트 추가.

## 3. 비기능 요구사항 (NFR)

- **N1.** 마이그레이션 0 · 신규 enum 0 · `role_permissions` 시드 변경 0.
- **N2.** cross-BC 변경 0. identity-access 는 읽기만 한다(`IdentityAccessWorkflowSchemePermissionResolver`
  무수정).
- **N3.** FR 총수 129 불변. `bash scripts/verify-master-plan.sh` 통과.
- **N4.** 응답 시간 회귀 없음 — R3 은 카운트 서브쿼리가 없는 `findAll()` 경로라 기존
  `listWithCounts()`(카티전 위험 경로, 메모리 `cartesian-product-jooq-leftjoin-count`)보다 가볍다.

## 4. API 인터페이스 (REST)

### 4.1 변경 — 관리용 (권한만 추가, 계약 무변경)

| 메서드 | 경로 | 권한 (신규) | 응답 |
|---|---|---|---|
| GET | `/api/v1/workflow-schemes` | `MANAGE_SCHEME` / `Global` | 200 `{data: WorkflowSchemeDetailResponse[]}` (무변경) |
| GET | `/api/v1/workflow-schemes/{schemeKey}` | `MANAGE_SCHEME` / `Global` | 200 `{data: WorkflowSchemeDetailResponse}` (무변경) |

거부 시 `WorkflowSchemeAccessDeniedException` → 기존 `WorkflowSchemeExceptionHandler` 가 **403** 매핑.
**응답 본문 계약은 바뀌지 않는다** — 기존 프론트/테스트의 200 경로 파싱은 그대로.

### 4.2 신규 — 배정용

```
GET /api/v1/projects/{projectKey}/assignable-workflow-schemes
권한: ASSIGN_SCHEME / WorkflowSchemeScope.Project(projectKey)
200 → { "data": [ { "id": 1, "key": "software-scheme", "name": "소프트웨어",
                    "description": "...", "isDefault": true } ] }
401 → 미인증 (CurrentActor)
404 → projectKey 미해석 (ProjectLookupPort)
403 → 비멤버 / MANAGE_WORKFLOW 미보유
```

**경로 명명 근거.** 기존 `/{projectKey}/workflow-scheme`(단수)는 "지금 배정된 그 하나"를 뜻한다.
후보 목록에 복수형 `/workflow-schemes` 를 쓰면 한 글자 차이로 의미가 갈려 오독 위험이 크다.
`assignable-` 접두로 "배정 후보"임을 경로에 박는다.
(대안 `/{projectKey}/workflow-scheme/options` 은 단수 리소스의 하위로 읽혀 계층이 어긋난다.)

## 5. 데이터 모델 변경

**없음.** 테이블·컬럼·시드·인덱스 전부 무변경. Flyway 마이그레이션 파일 추가 0건.

## 6. 엣지 케이스

- **EC-1. `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`) 가 전부 통과시킨다.**
  non-prod 부팅 테스트로는 게이트가 걸리는지 확인 불가. → 컨트롤러 슬라이스에서 resolver mock 이
  **던지도록** 세워야 실제 검증이 된다. (§8 T1 판별자)
- **EC-2. 기존 `WorkflowSchemeControllerTest` 의 resolver 는 `mockk(relaxed = true)`**
  (`WorkflowSchemeControllerTest.kt:84`) — `requirePermission` 이 no-op 이라
  **게이트를 추가해도 기존 테스트가 전부 green 을 유지한다.**
  메모리 `seal-blinds-existing-guard` 의 정확한 재현. 기존 테스트는 회귀 감지에 무력하다.
  (실측 — 이 클래스는 `@Test` **20개** · GET 요청 **5개**. 초안의 "GET 테스트 6개" 는 오기였다.
  메모리 `spec-stated-count-becomes-blindfold` 재발이라 실측치로 고정한다.)

- **EC-12. ★그 mock 은 Spring 싱글턴이고 `clearMocks` 가 없다 — 신규 (outside voice 발견).**
  `@BeforeEach setUp()`(`WorkflowSchemeControllerTest.kt:114-117`)은 MockMvc 만 재생성한다.
  `clearMocks` · `@DirtiesContext` **0건**(실측). 따라서 EC-2 를 뚫으려고 세우는
  `every { … } throws` 가 **클래스의 나머지 테스트로 새어 나가** 순서 의존적으로 403 을 만든다.
  ⇒ RED 를 넣는 task 가 `@BeforeEach` 에 `clearMocks(permissionResolver)` 를 **함께** 넣어야 한다.
  이것 없이는 게이트 테스트가 자기 클래스를 깨뜨린다.

- **EC-13. MockK 제약의 귀속 정정 — `requirePermission` 은 mockk 가능하다.**
  `WorkflowSchemePermissionResolver.requirePermission(UUID, enum, sealed)` 에는
  `@JvmInline value class` 파라미터가 **없다**. MockK 1.13.x 제약은
  **application service**(`ActorId` · `WorkflowSchemeKey` 파라미터) 쪽이다
  (`ProjectWorkflowSchemeControllerTest.kt:52-55` KDoc). 따라서
  `WorkflowSchemeControllerTest` 의 `every … throws` 는 정상 동작하고,
  `ProjectWorkflowSchemeControllerTest` 가 손수 stub 을 쓰는 이유는 **service 쪽 제약** 때문이다.

- **EC-14. `WorkflowSchemeExceptionHandler` 빈은 이미 등록돼 있다.**
  `WorkflowSchemeControllerTest.kt:96` 에 `@Bean` 존재. 초안의 "없으면 추가" 사전확인은 불요.
- **EC-3. 404 가 403 보다 먼저 나온다** (R3). 인증됐지만 권한 없는 사용자가 projectKey 존재 여부를
  probe 할 수 있다. **기존 두 핸들러가 이미 이 순서**(`ProjectWorkflowSchemeController.kt:86-93`)이며
  KDoc L50-52 가 "인증을 가장 앞에 둔다"까지만 정당화한다. 본 작업은 **기존 순서를 그대로 따르고**
  순서 변경은 범위 밖으로 둔다(3핸들러 동시 변경 = 별도 결정 사안).
- **EC-4. 프론트 Zod 스키마 불일치.** 현행 `schemeResponseSchema`
  (`workflow-schemes.types.ts:21`)는 `usedByProjectsCount`·`mappingsCount`·`isStandard` 를 **필수**로
  요구한다. R3 응답에는 이 셋이 없다 → **신규 Zod 스키마 필요**
  (`assignableSchemeResponseSchema` = `{ id, key, name, description, isDefault }`).
  기존 스키마 재사용 시 parse 실패로 배정 화면이 깨진다.
- **EC-5. `id` 는 nullable.** `SchemeResponse.id: Long?` (미영속 스킴 대비). Zod 는 `.nullable()` 필요.
- **EC-6. `description` 은 nullable** (`SchemeResponse.description: String?`). 현행 관리용 스키마는
  `z.string()`(non-null)이라 **같은 이름의 필드가 두 창구에서 다른 nullability** 를 갖는다.
  신규 스키마에서 `.nullable()` 로 정확히 반영한다.
- **EC-7. 배정 화면의 Select 옵션 키 — ★정정됨 (Maxi 결정 D3=1A).**
  ~~신규 응답의 필드명은 `key` 이므로 화면 코드의 `scheme.schemeKey` 참조를 `scheme.key` 로 전수 교체~~
  → **반대로 한다.** 신규 Zod 스키마가 경계에서 백엔드 이름(`key`·`isDefault`)을
  **프론트 어휘(`schemeKey`·`isStandard`)로 정규화**한다. 화면 코드의 `scheme.schemeKey` 참조는
  **그대로 둔다.** 프론트에 어휘 두 벌이 생기는 것을 막기 위함.
  (초안대로 교체했다면 같은 레이어에 `isStandard` 와 `isDefault` 가 공존했다.)

- **EC-11. 권한 없는 사용자의 배정 화면 — ★신규 (Maxi 결정 D6=4A, REGRESSION).**
  배정 화면 라우트 가드는 `requireAuthAndPasswordChanged`(인증+비번+MFA)뿐이라
  `MANAGE_WORKFLOW` 없는 사원도 도달한다. 오늘은 무권한 엔드포인트라 200 이지만
  **이 PR 이후 403** 이다. 그런데 화면에 error 분기가 없다 —
  `const schemeOptions = schemes ?? []`(`settings.workflow-scheme.tsx:78`)가 `undefined` 를 빈 배열로
  삼켜 **빈 드롭다운 + 설명 0** 이 된다. 무음 실패.
  ⇒ 403 전용 안내(`이 프로젝트의 워크플로우 설정 권한이 없습니다`)를 렌더하고,
  **빈 목록(200 + [])과 구분**한다. 회귀 테스트 필수.
- **EC-8. `useWorkflowSchemes` 캐시 키 충돌 없음.** 신규 훅은
  `['projects', projectKey, 'assignable-workflow-schemes']` 로 별도 네임스페이스.
- **EC-9. 배정 성공 후 무효화 대상.** 신규 훅 캐시는 배정 mutation 과 무관(후보 목록은 배정으로
  안 바뀜) → `useUpdateAssignment` 의 invalidate 목록에 추가하지 않는다.
- **EC-10. MSW / E2E 파급 (Phase B 에서 구체화 — 실측 목록).**
  배정 화면은 MSW 핸들러 `http.get('/api/v1/workflow-schemes')`(`scheme-handlers.ts:83`)를
  Select 옵션 소스로 소비한다. 신규 엔드포인트로 전환하면 다음이 함께 움직인다.

  | 대상 | 파일 | 처치 |
  |---|---|---|
  | MSW 핸들러 | `apps/web/src/mocks/scheme-handlers.ts` | **10번째 핸들러 추가** (`/api/v1/projects/:projectKey/assignable-workflow-schemes`). 기존 9개는 무변경 |
  | 배정 화면 단위 테스트 | `apps/web/src/routes/__tests__/` 의 배정 화면 테스트 | 신규 훅 경로로 갱신 |
  | 배정 E2E | `apps/web/e2e/workflow-scheme-assignment.spec.ts` | 신규 경로 확인 |
  | 훅 테스트 | `apps/web/src/hooks/__tests__/use-workflow-schemes.test.tsx` | 신규 훅 케이스 **추가**(기존 케이스 유지 — 관리용은 살아 있다) |

  **무변경 확인.** 관리 화면 E2E 4개(`workflow-scheme-crud` · `-mappings` · `-standard-protect` ·
  `-in-use-modal`)와 `WorkflowSchemeSidebar.test.tsx` 는 `useWorkflowSchemes`(관리용)를 계속 쓰므로
  손대지 않는다.

  **경로 shadowing 없음.** 신규 경로 `/projects/:projectKey/assignable-workflow-schemes` 는
  기존 `/projects/:projectKey/workflow-scheme`(L67-68)과 접두가 달라 MSW 핸들러 순서에 무관하다
  (메모리 `msw-dual-handler-e2e-shadow` 회피). 복수형 `/workflow-schemes` 를 골랐다면
  한 글자 차이로 순서 의존이 생겼을 지점이다.

## 7. 제약 조건

- **C1.** worktree(`.worktrees/workflowscheme-api-n4-list-get-manage-scheme`) 내부에서만 편집.
- **C2.** BC 격리 — project-workflow 모듈 + `apps/web` 만. identity-access·shared-kernel 무수정.
- **C3.** TDD red→green→refactor 강제. `test:` 커밋이 `feat:` 보다 앞서야 한다.
- **C4.** `rm`·`mv` 는 프로젝트 `.claude/settings.json` deny — `git clean -f` / `git mv` 사용
  (메모리 `global-advice-instance-token-leak-done`).
- **C5.** Gradle 은 `backend/` 에서 실행(메모리 `gradlew-at-backend-and-assembly-boot-needs-dev-postgres`).
- **C6.** worktree 프론트 작업은 `node_modules` 부분 설치 함정 있음
  (메모리 `worktree-node-modules-partial-install`) — impl 단계에서 `pnpm install` 선행.

## 8. 측정 가능한 완료 기준

### T1 — 게이트 실효 (음성, 본문 판별자)

resolver mock 이 `WorkflowSchemeAccessDeniedException` 을 던지도록 세운 상태에서
`GET /api/v1/workflow-schemes` → **403** 이고 **응답 본문에 스킴 key 문자열이 0건**.
`get` 도 동일하게 **매핑 정보 0건**.

> "여전히 403" 이 아니라 **"본문에 스킴 정보가 없다"** 가 판별자다
> (메모리 `negative-guard-needs-body-discriminator`). 게이트 이전에는 같은 mock 세팅에서
> **200 + 목록**이 나오므로 이 테스트는 진짜 RED 로 출발한다.

### T2 — 결선 검증 (양성)

`verify { permissionResolver.requirePermission(any(), MANAGE_SCHEME, WorkflowSchemeScope.Global) }`
가 `list`·`get` 각각에서 1회 호출됨. (기존 `create` 의 P1 패턴과 동형)

### T3 — 배정 경로 생존

신규 엔드포인트가 `ASSIGN_SCHEME` + `Project(projectKey)` 로 호출되고 200 + 스킴 배열 반환.
`verify` 로 **Global 이 아니라 Project 스코프**로 호출됐음을 못 박는다
(Global 로 잘못 쓰면 S4 가 다시 깨진다 — 이 테스트가 그 회귀의 유일한 방어선).

### T4 — 404 / 403 분기

미해석 projectKey → 404. 권한 거부 → 403.

### T5 — 프론트

배정 화면이 신규 훅을 호출하고 Select 옵션이 렌더된다. 신규 Zod 스키마가 R4 응답을 parse 한다
(`usedByProjectsCount` 부재로 실패하지 않는다).

### T6 — 뮤테이션 검증 (봉인 실효 확인)

`list`·`get` 의 `requirePermission` 호출을 **한 줄씩 지웠을 때 T1 이 각각 red** 가 되어야 한다.
기준선(EXIT=0)을 먼저 확인한 뒤 수행한다 (메모리 `verify-logic-vs-verify-guard` ·
`mutation-test-requires-committed-baseline` — **커밋 후에만** 뮤테이션).

### T7 — 전체 통과

`cd backend && ./gradlew :modules:project-workflow:test ktlintCheck detekt` 통과.
`pnpm verify` 통과. `bash scripts/verify-master-plan.sh` 통과(FR 129 불변).

## 9. Brainstorming Check — Spec Self-Review 결과

스펙의 **검증 안 된 단언 5건을 실측**했다. 4건 해소 · **1건이 진짜 gap 이라 스펙을 보강**했다.

| # | 검증한 단언 | 결과 |
|---|---|---|
| G1 | "`WorkflowSchemeAccessDeniedException` → 403 매핑이 이미 있다" | ✅ **확인.** `WorkflowSchemeExceptionHandler.kt:202`, errorCode `WORKFLOW_SCHEME_ACCESS_DENIED`. 그리고 `@RestControllerAdvice(basePackages=["com.bts.workflow.scheme"])` 가 **두 컨트롤러 모두** 커버(둘 다 `…scheme.web`). L204 가 "actor/permission/scope 는 로그만, 응답 body 미노출" 을 계약으로 명시 — 메모리 `fr-pm-04-guard-exception-message-http-leak` 재발 없음 |
| G2 | "엔드포인트 추가로 깨질 API 계약 스냅샷이 없다" | ✅ **확인.** project-workflow 에 OpenAPI(springdoc) 스냅샷 테스트 0건 |
| G3 | "`useWorkflowSchemes` 를 관리용으로 남겨도 정합하다"(R7) | ✅ **확인.** `WorkflowSchemeSidebar` 사용처는 `admin.workflow-schemes.tsx` · `admin.workflow-schemes.$schemeKey.tsx` **둘뿐**이고 둘 다 4-가드 admin 라우트 |
| G4 | "E2E 파급은 plan 에서 확인하면 된다" | ❌ **gap.** 배정 E2E(`workflow-scheme-assignment.spec.ts`)와 MSW 핸들러(`scheme-handlers.ts:83`)가 **확정 파급 대상**인데 스펙이 "있으면 확인" 수준으로 미뤄 뒀다 → **EC-10 을 실측 목록으로 승격** |
| G5 | "신규 문서가 `verify-master-plan` 을 깨지 않는다" | ✅ **확인.** spec·ADR·plan 3파일이 워킹트리에 있는 상태로 실행 → **PASS, EXIT=0.** 산문의 `FR-WF-02`·`FR-PM-04` 언급이 헤더 스캐너에 false-match 되지 않았다(메모리 `verify-master-plan-header-scanner-false-match` 회피 확인) |

### 이번 self-review 가 실제로 막은 것

G4 를 안 잡았으면 impl 단계에서 배정 E2E 가 원인 불명으로 깨졌을 것이다.
MSW 핸들러가 없으면 E2E 는 **timeout 이나 빈 Select** 로 나타나고, 그 증상은 권한 게이트 버그처럼 보인다
(메모리 `e2e-flaky-timeout-masks-transient-url-race` 의 오진 패턴).

### 편차 — `superpowers:brainstorming` 미호출

메모리 · 2026-07-26 N1 선례와 동일. 해당 스킬은 **설계를 생성**하는 스킬이라 완성된 스펙을 비평하는
용도와 형식이 맞지 않고, 종착점이 `writing-plans` 라 다음 단계 `bts-plan` 과 충돌한다.
그 스킬의 `Spec Self-Review` 절만 적용해 위 표를 만들었다.

**남은 편향.** 스펙 작성자와 리뷰어가 동일하다. outside voice(`codex`)가 미설치라
#308·#309·#310·#311 과 같은 편향이 이번에도 남는다. 붙이려면 `npm install -g @openai/codex`.

## 10. 워크플로우 편차 기록

**`office-hours` 미호출.** 메모리 `bts-spec-office-hours-mismatch` — office-hours 는 YC 식
"이 아이디어를 만들 가치가 있나" 진단 도구다. 본 작업은 (a) 기존 FR-WF-02 스펙 L57 의 미이행분이고
(b) 처방이 Maxi 결정(`6bdd1944`)으로 확정됐으며 (c) ADR 로 판정 모델까지 고정된 상태라 프레임이 맞지 않는다.
기존 `docs/specs/*.md` 형식으로 직접 작성했다. (2026-05-29 FR-IS-02 에서 Maxi 가 택한 경로)

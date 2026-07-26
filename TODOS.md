<!-- 후속 기술부채 추적 — 리팩토링이 필요하나 현재 PR 범위 밖이라 보류한 항목 기록 -->

# TODOS

## identity-access — 컨트롤러 권한 게이트 DRY 부채 (D19, PR #277)

**결정 (Maxi 확정, plan-eng-review D19)**. `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 · `resolveActorId` ·
`requireSystemAdmin` 3종 복제는 **선재 부채**다. `global_permission_grants`(FR-PM-10, PR-1)가 신설한
`GlobalPermissionGrantController`가 기존 패턴을 한 벌 더 복제했다. **공통화는 지금 하지 않는다** — 공통화하면
PR-1이 N파일 리팩토링이 되어 글로벌 CLAUDE.md §3(surgical, 변경은 요청받은 것만)과 충돌하고 권한 PR의 리뷰
단위가 무너진다. 대신 복사 + 이 기록으로 후속 작업을 명시한다.

**중복 3종 (2026-07-17 grep 재확인 — PR #277 T4/T5/T6 반영 후 실측치, 추측 아님)**.

| 패턴 | 파일 수 (src/main) | 재확인 명령 |
|---|---|---|
| `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 | 14 | `grep -rlE "FORBIDDEN_RESPONSE\|UNAUTHORIZED_RESPONSE" backend/modules/identity-access/src/main --include="*.kt"` |
| `resolveActorId` 함수 | 7 | `grep -rl "resolveActorId" backend/modules/identity-access/src/main --include="*.kt"` |
| `requireSystemAdmin` 함수 | 3 | `grep -rl "fun requireSystemAdmin" backend/modules/identity-access/src/main --include="*.kt"` |

> ⚠️ **`requireSystemAdmin`은 plan D19가 인계한 "5파일"과 다르다.** plan-eng-review 원안(`docs/plans/2026-07-17-project-management-crud.md:1312`)은 pre-T6 기준 "4파일"로 적었고, T6이 `GlobalPermissionGrantController`에 1벌을 더 복제해 "5파일"이 될 것으로 예상했다(구두 인계). **grep 재확인 결과 실제로는 3파일**뿐이다 — `GlobalPermissionGrantController` · `IssueSecuritySchemeController` · `UserGroupController`. plan의 "4파일" 원안 자체가 애초 과다 계상이었던 것으로 보인다([[spec-stated-count-becomes-blindfold]] 재발 — 숫자를 그대로 물려받지 말 것). 후속 리팩토링 착수 시 이 grep 명령으로 다시 재검증할 것.
>
> `resolveActorId`는 검색 결과 8개 파일에서 매치되나, 1개(`GlobalPermissionGrantControllerTest.kt`)는 KDoc/주석에서 개념을 언급할 뿐 실제 중복 구현이 아니라 제외했다(`src/main`만 집계).

**후속 작업**. 공통 베이스 클래스 또는 shared-kernel 유틸로 추출 — 별도 PR로 분리해 리팩토링 리뷰 단위를 권한 변경과 섞지 않는다.

## identity-access — ADR D-1 이중 방어의 "두 겹이 같은 집합" 정합 무가드 (PR #277 codereview)

**결정 (Maxi 확정, 2026-07-17 게이트 2)**. **후속으로 미룬다.** ADR이 이미 **잔여 위험 4**로 의식적으로 수용한
항목이고, 두 집합이 **현재 일치하므로 잠복 부채이지 현행 결함이 아니다**. 가드를 넣으려면 private companion
상수에 리플렉션을 걸거나 상수를 `internal`로 승격해야 하는데(prod 코드 변경), 권한 PR의 리뷰 단위를 흐린다.

**무엇이 안 잠겨 있나**. ADR D-1의 이중 방어는 **두 겹이 같은 집합**이어야 성립한다.

| 겹 | 위치 | 값 |
|---|---|---|
| 앱 | `GlobalPermissionGrantService.kt:129` | `val ALLOWED_GLOBAL_PERMISSIONS = setOf("CREATE_PROJECT")` |
| DB | `V036__global_permission_grants.sql:26` | `CHECK (permission IN ('CREATE_PROJECT'))` |

**두 값을 함께 읽는 테스트가 0건이다.** 서비스 KDoc `:34-36`이 스스로 *"🛑 권한코드 추가 시 3곳을 동시에
갱신한다(ADR 잔여 위험 4) … 하나만 놓치면 fail-closed로 조용히 막힌다"*라고 **수동** 동기화를 지시하는데,
그 지시를 강제하는 자동 가드가 없다. BTS가 FR 카운트 drift에 `verify-master-plan.sh`를 붙인 것과 같은 종류의 부채다.

**드리프트 방향별 결과**.
- 화이트리스트만 확장 → 서비스 통과 후 DB CHECK 위반 → `DataIntegrityViolationException` → **400이어야 할 것이 500으로 변질** (서비스 KDoc `:31-32`가 예고한 바로 그 변질)
- CHECK만 확장 → 부여가 **400으로 조용히 거부**

**후속 작업**. `GlobalPermissionGrantSchemaMigrationTest`에 "화이트리스트 전량이 V036 CHECK를 통과하는지"
확인하는 테스트 추가(`@JdbcTest` 자동 롤백에 기댄다). 상수가 private companion이라 리플렉션이 필요하며,
꺼려지면 `internal`로 승격해 직접 참조하는 편이 깔끔하다. **빈 집합이면 루프가 vacuous하게 통과하므로
`assertThat(allowed).isNotEmpty()` 선단언 필수**([[verify-logic-vs-verify-guard]]).

## CREATE_PROJECT 상수 — identity 화이트리스트를 shared-kernel 상수로 이전 (FR-PJ PR-2 plan-eng-review)

**결정 (Maxi 확정, 2026-07-18 plan-eng-review)**. **후속으로 미룬다.** FR-PJ PR-2 가 issue-tracking 게이트에서
쓸 `CREATE_PROJECT` 문자열을 **shared-kernel `com.bts.shared.permission`에 `const val` 로 신설**한다(그 패키지의
권한 enum `toPermissionCode()` 관례와 정합). 하지만 identity-access 의 기존 `ALLOWED_GLOBAL_PERMISSIONS`
(`GlobalPermissionGrantService.kt:129`)를 그 상수 참조로 바꾸는 것은 **PR-1(#277) 파일 변경**이라 이번 PR 밖이다
(글로벌 CLAUDE.md §3 surgical + BC 경계 모호 — 권한 PR 리뷰 단위를 흐림).

**무엇이 중복인가 (2026-07-18 실측)**.

| 겹 | 위치 | 값 |
|---|---|---|
| shared-kernel (FR-PJ PR-2 신설) | `com.bts.shared.permission` const val | `"CREATE_PROJECT"` |
| identity 앱 | `GlobalPermissionGrantService.kt:129` | `setOf("CREATE_PROJECT")` |
| DB | `V036__global_permission_grants.sql:26` | `CHECK (permission IN ('CREATE_PROJECT'))` |

**후속 작업**. identity 화이트리스트를 shared-kernel 상수 참조로 교체. 위 [[ADR D-1 이중 방어]] 항목과 **같이 처리**하면
(화이트리스트↔CHECK 정합 테스트 + 상수 단일화) 한 번에 drift 근원 3겹을 2겹으로 줄인다. **의존**. FR-PJ PR-2 머지
후(shared-kernel 상수 실재해야 함). shared-kernel 은 9 모듈이 의존하므로 변경 시 광범위 재컴파일 — 리뷰 단위를 권한
변경과 섞지 않게 별도 PR.

## project-workflow — 워크플로우 스킴 프론트↔백엔드 계약 파손 (PR #314 plan-eng-review outside voice)

**결정 (Maxi 확정, 2026-07-26 D9=B)**. **이번 PR 범위 밖.** 선재 결함이고, 보안 봉합 PR 을 프론트 계약
리팩토링으로 번지게 하면 리뷰 단위가 무너진다. 대신 여기 등재한다.

**★차단 사안**. 이 부채가 남는 한 **스킴 기능의 어떤 PR 도 프론트 테스트로 "회귀 없음" 을 증명할 수 없다.**
초록은 MSW 가 MSW 와 맞는다는 뜻이다.

**불일치 3종 (2026-07-26 실측)**.

| Zod 스키마 | 요구 필드 | 백엔드 실제 반환 | 상태 |
|---|---|---|---|
| `assignmentResponseSchema` (`workflow-schemes.types.ts:48-52`) | `projectKey`·`schemeKey`·`schemeName` | `SchemeResponse{id,key,name,description,isDefault}` (`ProjectWorkflowSchemeController.kt:160-166`) | **교집합 0** |
| `schemeResponseSchema` (`:21-28`) | `schemeKey`·`isStandard`·`description`(non-null) | `WorkflowSchemeDetailResponse{key,isDefault,description:String?}` (`WorkflowSchemeDto.kt:116-126`) | `schemeKey`·`isStandard` 부재, nullability 불일치 |
| `mappingResponseSchema` (`:31-39`) | `isDefault` | `MappingResponseDetail` 에 해당 필드 없음 | 필드 부재 |

`workflow-schemes.types.ts:25` 주석이 "backend 의 isDefault 와 동일 의미, 후속 PR 에서 backend 계약 정렬
예정" 이라고 적혀 있어 **일부는 의도된 부채**로 보인다. 그러나 `assignmentResponseSchema` 의 교집합 0 은
의도로 설명되지 않는다.

**착수 시 첫 단계**. 추측하지 말고 **조립 부팅 실측부터** — dev postgres 5433 + `:app:test` 또는
`ProdAssemblyHttpTestBase` 로 실제 응답을 받아 어느 쪽이 정본인지 확정한다. 화면이 실서버에서
동작한 적이 없는 것인지, 내가 못 본 변환 계층이 있는 것인지가 먼저 확정돼야 한다.

**Depends on / blocked by**. 없음. 단 이 작업 전에는 스킴 관련 PR 의 프론트 회귀 주장을 신뢰하지 말 것.

## project-workflow — ProjectWorkflowSchemeController 의 404/403 순서 (PR #314 plan-eng-review)

**결정 (Maxi 확정, 2026-07-26 D4=2A)**. **이번 PR 범위 밖.** 한계 노출량이 0(같은 정보를 기존 2핸들러로
이미 얻을 수 있음)이고, 순서 변경은 3핸들러 동시 수정이라 보안 봉합 PR 의 리뷰 단위를 흐린다.

**무엇이 문제인가**. 세 핸들러 모두 `projectLookupPort.findIdByKey`(404) 를 `requirePermission`(403)
**보다 먼저** 호출한다(`ProjectWorkflowSchemeController.kt:86-93` 외 2곳). 인증됐지만 권한 없는 사용자가
응답 코드 차이(404 vs 403)로 프로젝트 키의 실재를 열거할 수 있다.

**착수 시 함정**. 순서를 바꾸면 `fetchProjectAssignment`(`workflow-schemes.ts:233-243`)의
**404 → null = "미할당"** 로직이 깨진다. 미할당 상태를 404 가 아닌 다른 신호로 표현하도록 계약을 먼저
정해야 한다. 세 핸들러를 한꺼번에 정렬할 것 — 한 개만 바꾸면 같은 컨트롤러 안에서 순서가 갈린다.

**Depends on / blocked by**. 없음. 우선순위 낮음(한계 노출량 0).

## identity-access — MANAGE_WORKFLOW 시드가 기본 권한 스킴에만 존재 (PR #314 plan-eng-review outside voice)

**결정 (Maxi 확정, 2026-07-26 D10=A)**. **이번 PR 범위 밖.** 신규 기능 손실이 아니다 — 배정 실행(PUT)이
이미 같은 게이트라 해당 사용자는 오늘도 적용 단계에서 403 을 맞는다. 마이그레이션 0 이라는 PR 전제를
깨면서까지 지금 할 일이 아니다.

**무엇이 안 잠겨 있나**. `V013__manage_workflow_permission.sql:14-16` 이 `MANAGE_WORKFLOW` 를
**기본 권한 스킴(`00000000-0000-0000-0000-000000000001`)의 `PROJECT_ADMIN`** 에만 1행 시드한다.
프로젝트가 어느 스킴을 쓰는지는 `COALESCE(project_permission_scheme, permission_schemes WHERE is_default)`
로 결정된다(`JdbcPermissionSchemeRepository.kt:65-72`).

⇒ **비-기본 권한 스킴에 명시 매핑된 프로젝트**의 관리자는 `roleHasPermission` 이 false 가 되어
워크플로우 스킴 배정이 불가능하다. ADR `2026-07-26-workflow-scheme-read-permission-gate` D4 의
"시드 변경 0" 은 **기본 스킴 프로젝트에 한해** 참이다.

**함께 등재 — 프로젝트 비멤버인 SYSTEM_ADMIN**. `WorkflowSchemeScope.Project` 판정에 시스템 관리자
fallback 이 없다(`IdentityAccessWorkflowSchemePermissionResolver.kt:52-56, 76-78`). 멤버십 조회가 null 이면
곧바로 거부다. 사내 전체 관리자가 자기가 멤버가 아닌 프로젝트의 워크플로우를 배정할 수 없다.
이것이 의도된 정책인지 누락인지는 **미확정** — 착수 시 먼저 정할 것.

**동반 필요**. 시드를 넓히면 `PermissionSchemaMigrationTest` 의 정확 카운트 단언을 함께 갱신해야 한다
(메모리 `fr-pm-permission-seed-migration-test-coupling`).

**Depends on / blocked by**. 권한 스킴을 실제로 둘 이상 운용하기 시작하는 시점. 그전까지는 잠복.

## project-workflow — SchemeHandlerPermissionMatrix 봉인의 3가지 한계 (PR #314 뮤테이션 M3 + 코드리뷰 주입)

**결정 (Maxi 확정, 2026-07-26 D12=B)**. **이번 PR 범위 밖.** N4 의 결함 클래스(가드 자체가 없음)는
축1(호출 강제)이 이미 완전히 막고 있고, 맵 값 drift 는 더 좁은 클래스다. 대신 테스트 KDoc 에 한계를
명시해 과신을 막고 여기 등재한다.

**무엇이 안 잠겨 있나**. 축2 는 `HANDLER_CLASSIFICATION.containsKey(HandlerKey(owner, name))` 로
**등록 여부만** 본다(`SchemeHandlerPermissionMatrixTest.kt:78-80`). 맵에 적힌 `(permission, scopeKind)` 가
코드가 실제로 넘기는 인자와 같은지는 대조하지 않는다.

**실증 (뮤테이션 M3, 2026-07-26)**. `listAssignableSchemes` 의 스코프를 `Project(projectKey)` → `Global`
로 바꿨을 때 **축2 는 green 을 유지**했다. 그 뮤테이션을 잡은 것은 `ProjectWorkflowSchemeControllerTest`
의 `capturedScope` 단언(개별 단위 테스트)이었다. ⇒ 맵은 **등록부**이지 계약이 아니다.

**비대칭 위험**. 신규 2핸들러(`listAssignableSchemes`)와 `create` 는 스코프 단언을 가진 단위 테스트가
있으나, 나머지 기존 핸들러 전부가 그런 단언을 갖는지는 미확인이다. 착수 시 **먼저 전수 확인**할 것.

**처방 후보**. MockMvc + 기존 `CapturingPermissionResolverStub` 으로 10 핸들러를 전수 호출해
캡처된 `(permission, scope)` 가 `HANDLER_CLASSIFICATION` 과 일치하는지 대조한다. 스텁이 이미 있으므로
신규 인프라는 불요. 비용은 10 핸들러의 요청 URL·바디 구성.
⚠️ ArchUnit 만으로는 못 한다 — 바이트코드에서 상수 인자 값을 읽어야 해서 `JavaMethodCall` 로는 부족하다.

**Depends on / blocked by**. 없음. 이 갭이 남는 동안에는 **축2 의 green 을 "권한/스코프가 맞다"로
읽지 말 것** — "핸들러가 등록은 돼 있다" 까지만 참이다.

**추가 한계 2종 (2026-07-26 코드리뷰가 직접 주입해 실증)**.

- **축1 도 "호출이 존재하는가" 만 본다.** 가드가 `if (schemes.isEmpty()) { requirePermission(…) }` 처럼
  **정상 응답 경로 밖**에 있어도 양 축을 통과한다. 즉 N4 와 실질이 같은 핸들러가 봉인을 빠져나간다.
  대조군(호출 아예 제거)에서는 FAILED — 이 축이 잡는 것은 정확히 "호출 0건" 이다.
- **판별 범위가 `com.bts.workflow.scheme.web` 패키지 한정이다.**
  `WorkflowSchemeApplicationService.list()`·`listWithCounts()` 자체는 권한 호출 0건이므로,
  다른 패키지 컨트롤러가 그 서비스를 소비하면 봉인이 그 클래스를 임포트조차 하지 않아 무음 통과한다.

⇒ **런타임 대조(MockMvc + capturing stub 전수 호출)로 강화하면 축1·축2 한계가 동시에 해소된다** —
실제로 핸들러를 태워 캡처값을 보므로 "안 타는 분기" 도 잡힌다. 범위는 스킴 서비스를 소비하는
핸들러 전체로 잡을 것(패키지가 아니라 **의존 관계**를 판별식으로).

## apps/web — SCHEME_KEYS.assignable 캐시가 스킴 뮤테이션으로 무효화되지 않는다 (PR #314 코드리뷰 CONCERNS 5)

**결정 (Maxi 확정, 2026-07-26 게이트 2 = A)**. **범위 밖.** 영향이 작고, 성급한 처방이 더 위험하다.

**증상**. `useAssignableWorkflowSchemes` 는 `staleTime: 30_000`(`use-workflow-schemes.ts:78-81`)인데
스킴 생성·수정·삭제 훅(`:100`·`:171`·`:189`)은 `SCHEME_KEYS.list`/`detail` 만 invalidate 한다.
⇒ SYSTEM_ADMIN 겸 PROJECT_ADMIN 이 `/admin/workflow-schemes` 에서 스킴을 만든 뒤 **30초 안에**
배정 화면으로 이동하면 새 스킴이 드롭다운에 없다.

**★처방 함정 (착수 시 반드시 고려)**. 단순히 `invalidateQueries({ queryKey: ['projects'] })` 를 넣으면
**프로젝트 관련 전 쿼리를 무효화**해 관계없는 화면까지 재요청이 폭발한다. 관리자 뮤테이션은
`projectKey` 를 모르므로(전역 자원 조작) 정확한 키를 만들 수 없다.
⇒ predicate 기반 부분 매칭(`predicate: q => q.queryKey[0]==='projects' && q.queryKey[2]==='assignable-workflow-schemes'`)
이 맞는 방향이다. 그게 이 레포의 다른 훅 관례와 맞는지 먼저 확인할 것.

**Depends on / blocked by**. 없음. 우선순위 낮음(30초 staleness, 두 역할 겸임자 한정).

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

## ✅ project-workflow — 워크플로우 스킴 프론트↔백엔드 계약 파손 (해소 #317)

**해소 (2026-07-27, PR #317).** 계약 스냅샷 기전 + 뷰 어휘 정렬 + Zod 형태별 분리로 봉합했다.
정본 어휘는 **`key` + `isStandard`**(스킴), **`isDefault`**(기본 매핑)이다. 마이그레이션 0.

**★이 항목의 기록이 두 군데 틀렸다 — 정정.**
1. **규모.** "불일치 3종" 이 아니라 **응답 7종 + 요청 1종**이었다. 8 endpoint 중 정합은 1건뿐이었다
   (이미 `.transform()` 정규화를 하던 assignable 목록). 실측 근거는
   `docs/plans/2026-07-27-workflow-scheme-contract-align.md` §Task 2 A9-② 증거표.
2. **지목 DTO 2건이 오기.** `assignmentResponseSchema` 의 대응 백엔드는 `ProjectWorkflowSchemeController.kt`
   의 `SchemeResponse`(GET)와 `AssignmentResponse`(PUT) **두 개**다 — 한 스키마가 서로 다른 DTO 2개를
   덮고 있던 것이 교집합 0 의 실제 원인이다. 근본 원인은 endpoint 수가 아니라 **형태 수**만큼 스키마를
   나누지 않은 것이었다(스키마 3장이 각각 백엔드 DTO 2개씩을 겸했다).

**봉합 방식.** `docs/contracts/workflow-schemes.snapshot.json` 이 유일 계약 정본이다. 백엔드는
prod 조립 부팅(`WorkflowSchemeContractSnapshotTest`)에서 8 endpoint 실응답과 문자열 동등을 단정하고,
프론트는 같은 파일을 `.strict()` 로 파싱한다(`workflow-schemes.contract.test.ts`). 한쪽이 어긋나면
그 지점에서 즉시 빨간불이 켜진다 — 「MSW 가 MSW 와 맞는다」 상태가 끝났다.

**신규 이연 8건.** (아래 3건 + 독립 리뷰가 추가로 잡은 5건 — 상세는 plan §독립 리뷰 결과)
- 계약 스냅샷의 **숫자 타입 붕괴** — 정규화가 모든 숫자를 `1` 로 만들어 `Long`→`Double` 변경을 못 잡는다
- `description` 의 **`null`↔`''` 왕복** — 이름만 고쳐도 DB `NULL` 이 `''` 가 된다(왕복 테스트 없음)
- **낙관적 배정의 key↔name 불일치** — 재조회 전까지 카드가 옛 스킴 이름을 보여준다
- **롤백 가드 비대칭** — 캐시 쓰기는 무조건, 롤백은 조건부
- **`fetchProjectAssignment` 404 해석(선재)** — 백엔드는 미배정에 404 를 안 낸다(자동 배정). 실제 404 는 「프로젝트 없음」

**기존 이연 3건.**
- **도메인·DB 어휘 이연** — 도메인 `WorkflowScheme.isDefault` 와 DB 컬럼 `is_default` 는 그대로다
  (ADR D2 — 이번 변경은 뷰 레이어 한정, 마이그레이션 0). 이름이 「표준 스킴」 의미인데 `default` 라
  DB 주석(`V201__workflow_schemes.sql:37`)과도 어긋나 있다. rename 하려면 마이그레이션 + jOOQ 재생성이
  필요하다.
- **cross-BC 이슈타입 조회 실패가 무음** — `issueTypeKey`/`issueTypeName` 이 null 로만 표현돼, 화면은
  「조회 실패」와 「기본 매핑」을 구분해 보여줄 수 없다. `isDefault` 신설로 **오분류는 막았으나**
  실패 자체를 사용자에게 알리는 신호는 아직 없다(ADR 잔여위험 2).
- **`classify-task.ts:45` 의 `'스키마'` 키워드가 Zod·GraphQL·JSON schema 작업을 전부 `migration` 으로
  오분류한다.** 같은 작업 제목 3종이 `migration`/`qa`/`backend` 3개 결과를 냈다. 기존 항목
  「`bts-review-plan` 분기 표에 `type=backend` 가 없다」의 형제 — 하드코딩 키워드가 아니라 **판별식**이 필요하다.

**별도 작업으로 남은 것.** `/v3/api-docs` 가 미인증 노출이다(`OpenApiSecurityConfig.kt` +
`OpenApiConfig.kt` **두 곳**, 한 곳만 고치면 효과 0). 2 BC + security-engineer 소관이라 이 PR 에
넣지 않았다(결정 3A'). 이번 정렬로 **응답 필드명이 문서화된 공개 계약**임이 확정됐으므로 우선순위가 올랐다.

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

## issue-tracking — 핵심 엔티티 3종이 glossary 미등재 (PR #315 FR-CO-01 grill-with-docs 발견)

**결정 (Maxi 확정, 2026-07-27 D6)**. **이번 PR 범위 밖.** 댓글만 등재하고 동질 부채는 여기 등재한다.
기존 FR 소관이고, 기능 PR 을 용어사전 정리로 번지게 하면 리뷰 단위가 무너진다(#314 가 프론트 계약
부채에 쓴 것과 같은 잣대).

**미등재 3종 (2026-07-27 실측)**.

| 엔티티 | 소관 FR | 실재 여부 | `domain/issue-tracking.md` 핵심 엔티티 목록 |
|---|---|---|---|
| Worklog (작업로그) | FR-TT-01 | 있음 — POST/GET/PATCH/DELETE 전량 + 작성자 한정 수정·삭제 | **FR-CO-01 에서 추가함**(Comment 선례라 같이 넣음) |
| Attachment (어테처) | FR-AC-01/02 | 있음 — MinIO 업로드 + 미리보기 | 이미 있음 |
| Watcher (워처) | FR-WT-01 | 있음 | 이미 있음 |

**★판정 기준 자체가 흔들린다는 게 진짜 문제.** glossary 에는 `버전 상태`·`CFD`·`LexoRank`·`공유 토큰`
처럼 **설명 없이는 모를 것**이 들어가 있고, `Worklog`·`Attachment`·`Watcher`·(이전의)`Comment` 처럼
**이름만 들으면 아는 것**이 빠져 있다. 즉 암묵 기준은 "설명 필요도" 로 보인다. 그런데
`domain/issue-tracking.md` 핵심 엔티티 목록엔 Attachment·Watcher 가 **이미 있어** 두 문서의 수록
기준이 서로 다르다.

**착수 시 첫 단계**. 등재 기준을 명문화한다 — glossary 는 "도메인 전문가에게 의미가 모호한 용어"만인지,
"핵심 엔티티 전량"인지. 기준을 정한 뒤 누락분을 일괄 채운다. 기준 없이 개별 추가하면 같은 누락이 반복된다.
`glossary.md` §변경 규칙에 기준 한 줄을 추가하는 것이 산출물.

## 워크플로우 — `bts-review-plan` 분기 표에 `type=backend` 가 없다 (PR #315 발견)

**증상**. `.claude/skills/bts-review-plan/SKILL.md` Step 2 의 타입별 리뷰 체인 표는
`auth`·`migration`·`ui`·`api`·`feature`·`design` + `{bugfix,chore,qa}` skip **7종만** 다룬다.
그런데 `scripts/workflow/classify-task.ts` 는 `backend` 를 내보낸다(PR #315 실측). **매칭되는 행이
없어 분기가 미정의**다.

**임시 대응 (PR #315)**. 메모리 `bts-review-plan-autoplan-overkill`(Maxi 피드백)에 따라
**eng 집중 + UI 포함이면 design 추가**로 수동 선택했다. CEO·DevEx 는 제외.

**★같은 형태의 누락이 더 있을 수 있다.** classify 가 낼 수 있는 타입 집합과 스킬 분기 표의 행 집합을
**대조**해야 한다 — 표에 없는 타입이 조용히 미정의로 떨어지는 구조다. 하드코딩 목록끼리 어긋나는
전형적 형태(메모리 `guard-handler-matrix-blindfold` 와 동질).

**착수 시 첫 단계**. `classify-task.ts` 의 type 유니온을 열거하고 `bts-review-plan`·`bts-spec`·`bts-plan`·
`bts-impl` 각 스킬의 분기 표와 **집합 차이**를 낸다. 차집합이 0 이 되게 채우고, 앞으로 어긋나면 깨지는
검증을 하나 둔다(스킬 문서 대조 스크립트 또는 classify 출력 화이트리스트).

**산출물**. 분기 표 보강 + 타입 집합 정합 검증. 개별 타입 추가만으로 끝내지 말 것 — 판별식이 없으면 재발한다.

## apps/web mocks — auth-fixtures 와 user-fixtures 의 사용자 id 교집합이 0 이다 (PR #315 브라우저 눈확인 발견)

**증상**. 모든 "작성자 이름" 표시가 mock/E2E/로컬 dev 에서 **원시 UUID 로 나온다.** 헤더는 `김앨리스`
를 정상 표시하고 담당자 드롭다운도 정상인데, 목록 항목의 작성자만 UUID 다.

**근본 원인 (2026-07-27 실측)**. 같은 `username: 'alice'` 가 두 픽스처 파일에서 **다른 id** 를 갖는다.

| 파일 | 필드 | 값 |
|---|---|---|
| `src/mocks/auth-fixtures.ts` | `aliceUser.userId` | `00000000-0000-4000-8000-000000000001` |
| `src/mocks/user-fixtures.ts` | `userListFixture[0].id` | `c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f` |

`grep -c "00000000-0000-4000-8000" src/mocks/user-fixtures.ts` → **0**. 교집합이 공집합이다.

**왜 드롭다운은 되고 목록은 안 되나.** `GET /api/v1/users` 의 두 모드가 다른 픽스처를 탄다 —
`?query=` 모드는 `userListFixture` 를 그대로 반환하므로 `김앨리스` 가 보이고, `?ids=` 모드는
`userListFixture.filter(u => ids.includes(u.id))` 이므로 **auth UUID 로는 아무것도 안 걸린다.**
그러면 `useUsersByIds` 가 빈 배열을 받고 컴포넌트가 UUID 폴백을 탄다.

**범위 — 이 PR 만의 문제가 아니다.** `useUsersByIds` 를 쓰는 모든 화면이 같다.
`WorklogSection`(작성자) · `CommentSection`(작성자) · 그 외 authorId → displayName 을 해석하는 곳 전부.
`worklog-handlers`·`comment-handlers` 가 Bearer 토큰(=auth-fixtures UUID)에서 저작자를 도출하기 때문이다.

**프로덕션은 정상이다.** 실 `/api/v1/users?ids=` 는 실제 사용자를 돌려주므로 이름이 해석된다.
**mock 전용 결함**이며, UUID 폴백 자체는 탈퇴·삭제 사용자를 위한 **의도된 동작**이다(고장 아님).

**★왜 아무도 몰랐나.** 컴포넌트 테스트가 `useUsersByIds` 를 `vi.mock` 으로 대체해 실제 조회 경로를
타지 않는다. E2E 도 작성자 이름을 단정하지 않았다. **브라우저 눈확인에서만 드러났다** —
FR-UX-06 이 22 PR 을 끝내고도 미실시로 남긴 그 절차다.

**PR #315 에서 고치지 않은 이유**. `user-fixtures.ts` 는 공유 mock 데이터이고 프론트 8,044 테스트가
의존한다(항목 수·드롭다운 내용을 단정하는 테스트가 있을 수 있다). mock 전용 표시 문제를 위해
기능 PR 에서 공유 픽스처를 바꾸는 것은 폭발 반경이 맞지 않는다.

**착수 시 첫 단계**. 어느 쪽을 정본으로 삼을지 먼저 정한다 — `auth-fixtures` UUID 를 정본으로 두고
`user-fixtures` 를 맞추는 편이 자연스럽다(토큰에서 도출되는 값이 곧 실사용 id 이므로). 바꾼 뒤
**`useUsersByIds` 를 mock 하지 않는 통합 테스트 1건**을 남겨 같은 회귀가 다시 숨지 못하게 한다.


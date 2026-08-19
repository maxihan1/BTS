# [backend] project-workflow — 워크플로우·상태 CRUD API

> 티어: T3
> slug: backend-project-workflow-crud-api
> type: api
> agent: backend-engineer
> 생성: 2026-08-19

## Brief

워크플로우 편집기 로드맵(`~/.claude/plans/cozy-hatching-otter.md`) 10 PR 중 **PR 3**.
FR-WF-04(워크플로우 CRUD). 선행 PR 2 = #392(squash `213e8a317`) 머지 완료.

classify 결과 — `type=api` · `agent=backend-engineer` · `primary_bc=project-workflow` ·
`tier=T3`(Maxi 지정. `classify-task.ts:517` 은 티어를 추론하지 않고 `input.tier ?? DEFAULT_TIER` 다).
실측 표면도 T3 — `backend/**/main`(BE_MAIN·T2) + `shared-kernel` 신규 enum·resolver(SHARED_KERNEL·T3) 혼합 → 최고 티어 지배.

### 범위 7건 (사용자 원문)

1. **선행 — 읽기 경로 2단 join 전환.** PR 2 에서 D1 로 이관된 분. `statuses` + `workflow_statuses`
   2단 join 으로 읽기를 바꾸고, 원시 SQL 로 워크플로우 상태를 심는 **32파일**(issue-tracking 21 ·
   project-workflow 11)을 픽스처 헬퍼 1개 도입으로 일괄 이주한다.
2. `POST/PUT/DELETE /api/v1/workflows` · `POST /api/v1/workflows/{key}/duplicate`
3. `GET/POST/PUT/DELETE /api/v1/statuses` (전역 상태 카탈로그)
4. `POST/DELETE /api/v1/workflows/{key}/statuses` · `PUT .../statuses/order`
5. **권한** — shared-kernel 에 `WorkflowDefinitionPermission`(CREATE/UPDATE/DELETE/PUBLISH) +
   `WorkflowDefinitionPermissionResolver` 신설. `VersionPermission`·`ComponentPermission`·
   `TemplatePermission` 의 「도메인당 enum 1개 + resolver 1개」 관례 그대로. prod 어댑터는
   identity-access 의 `MANAGE_WORKFLOW` 코드 매핑, non-prod 는 `AlwaysAllow` stub.
   **`WorkflowSchemePermission` 에 끼워 넣지 않는다** — 이름이 스킴을 뜻하는데 워크플로우 정의를
   담게 되어 다음 사람이 오해한다.
6. **키 불변 강제** — `PUT /statuses/{id}` 는 `name`·`description`·`category` 만 수용하고
   `key` 는 받지 않는다. 테스트로 못박는다.
7. **캐시 무효화** — 모든 쓰기 경로 끝에 `WorkflowCache.invalidate(workflowKey)`.
   누락 감지 테스트를 별도로 둔다.

### red-first 4건

- 이름 수정 후 GET 이 새 이름을 주는가
- `key` 변경 시도가 400 인가
- 사용 중 워크플로우 삭제가 409 인가
- 편집 후 캐시가 갱신되는가

### 체인에 물린 learnings 2건

- **2026-05-23 fixture 옵션 B 패턴** — mirror data(fixture/seed/mock)는 정적 문자열 대신 helper
  호출로 drift 를 **본질 차단**한다. 회귀 가드는 보조. 32파일 픽스처 헬퍼 설계에 직접 적용.
- **2026-07-17 파일 존재 ≠ 기능 존재** — 「백엔드 완비」는 컨트롤러 HTTP 매핑을 세어서 판정한다.
  기존 `WorkflowController` 의 실제 `@*Mapping` 개수를 먼저 실측할 것.

## 도메인 정리

**BC** — `project-workflow` 단일. shared-kernel 에 권한 계약 2파일 신설(enum + resolver 포트),
identity-access 에 prod 어댑터 추가. issue-tracking 은 **테스트 픽스처만** 바뀌고 프로덕션 0줄.

**영향 엔티티** — Workflow · Status(전역 카탈로그, PR 2 신설) · WorkflowStatus(N:M 편성) ·
WorkflowTransition(읽기 경로만, CRUD 는 PR 4).

**새 용어** — 없음. `glossary.md` §워크플로우/자동화 의 「전환」·「워크플로우 스킴」·「표준 스킴」·
「기본 매핑」이 이미 정본이고 이 PR 은 새 개념을 도입하지 않는다. 「전역 상태 카탈로그」는 PR 2 가
ADR 로 등재했다. **`grill-with-docs` 미호출** — T3 이지만 신규 도메인 개념이 없다.

**기존 결정 충돌** — 없음. 이 PR 은 PR 1(#391)이 세운 ADR 2건을 **이행**한다.
- `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` — 정본이 DB. 이 PR 이 그 DB 를 쓰는 API 를 연다
- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — 키 불변 원칙. 이 PR 의 F5 가 그것을 강제한다

**관련 ADR** — 위 2건 + `docs/decisions/2026-07-26-workflow-scheme-read-permission-gate.md`(권한 게이트 선례) ·
`docs/decisions/2026-07-27-workflow-scheme-canonical-vocabulary.md`(스킴 용어 정본).

### ★ 실측 — 「지금 있는 것 / 이 PR 이 만드는 것」

learnings `2026-07-17 파일 존재 ≠ 기능 존재` 를 적용해 `@*Mapping` 을 **세었다**.

`WorkflowController.kt` 의 매핑은 4개뿐이고 **쓰기 CRUD 는 0개**다 —
`GET /`(`:52`) · `GET /{key}`(`:66`) · `POST /{key}/transitions`(`:84`, 전환 *계획 계산*) ·
`POST /cache/invalidate`(`:128`). `/api/v1/statuses` 는 컨트롤러 자체가 없다.
전수 표는 스펙 §API 인터페이스.

## 스펙

정본 — [`docs/specs/2026-08-19-backend-project-workflow-crud-api.md`](../specs/2026-08-19-backend-project-workflow-crud-api.md)

핵심 시나리오 3줄.
- 워크플로우 이름을 고치면 GET 이 즉시 새 이름을 주고 캐시도 갱신된다 (S1)
- 상태 `key` 는 어떤 경로로도 바뀌지 않는다 — 이슈·자동화·검색이 문자열로 참조하기 때문 (S2)
- 스킴이 참조 중인 워크플로우는 409 로 삭제를 막는다 (S3)

## Sanity Check

✅ 통과 — 보강 1회로 gap 9건 해소(삭제 방식·복제 범위·초기값·엣지 4건·「사용 중」 3축 분리·낙관적 락 경계·좌표 컬럼 경계),
Maxi 결정 2건은 아래에서 확정.

### ★ Maxi 결정 2건 (2026-08-19)

**D1 — 죽은 권한 게이트를 이 PR 에서 고친다.**
`POST /api/v1/workflows/cache/invalidate` 가 요구하는 `WORKFLOW_MANAGE` authority 는 **발급 경로가 없다**.
실측 3건 — ①`WorkflowController.kt:129` 가 요구 ②그 문자열은 그 컨트롤러와 그 테스트에만 존재하고
정본 코드는 철자가 뒤집힌 `MANAGE_WORKFLOW`(`V013` 시드) ③authority 생성처는 저장소에 2곳뿐이고
둘 다 `ROLE_` 접두어(`SidRevokeJwtConverter:115` · `PatAuthenticationFilter:48`).
테스트는 `@WithMockUser(authorities=["WORKFLOW_MANAGE"])` 로 손수 심어 초록이었다
(MEMORY `unreachable-state-fixture-is-fake-green`).
→ resolver 게이트로 교체. 범위 추가분 = F11 · E16 · M11.

**D2 — issue-tracking 테스트 21파일을 이 PR 에 포함한다.**
읽기 경로를 바꾸는 순간 즉시 red 가 되므로 원자적으로 같이 간다. 프로덕션 0줄.
게이트 2 요약에 BC 격리 의도적 편차로 명시한다.

### 실측 정정 1건

지시문의 「32파일」을 다시 세어 **31파일**(issue-tracking 21 · project-workflow 10)로 정정했다.
별도로 `workflow_states` 를 언급만 하는 4파일은 **구형 테이블 자체를 검증하는 것이 목적**이라
이주 대상이 아니다 — 헬퍼로 감싸면 검증이 사라진다.

## Plan

> 규율 — **TDD red-first**. T3 이므로 `test:` 커밋이 `feat:` 보다 선행하고 CI 판별식이 대조한다.
> 이 세션은 서브에이전트 dispatch 를 쓰지 않으므로(Maxi 정책) wave 는 **순서 근거**로만 쓰고 인라인 순차 구현한다.

### 판별식 배치 결정 (전 task 공통 전제)

판별식 3종(M4·M5·M11)은 **backend 테스트 소스셋**에 둔다. `scripts/workflow/` 가 아니다.

| 후보 | 판정 |
|---|---|
| `scripts/workflow/` + `workflow-scripts-ci` | ❌ 그 워크플로우의 `paths` 는 **판별식 전량의 입력 합집합**이라는 계약이고(파일 머리 주석), 여기에 `backend/**` 를 넣으면 백엔드 변경마다 끌려온다. 주석이 그 배치를 명시적으로 반대한다 |
| backend 테스트 소스셋 | ✅ `backend-ci.yml` 이 `backend/**` 를 이미 걸어 **반드시 돈다**. ArchUnit 룰 선례도 있다(learnings 2026-05-21) |

부채 15 의 A안과 같은 논리다 — 「그 CI 가 반드시 도는 자리에 둔다」.

### Task 1. 워크플로우 상태 픽스처 헬퍼 신설 + 원시 SQL 재유입 금지 판별식

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/testsupport/WorkflowStatusFixture.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/testsupport/WorkflowStatusFixture.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/guard/RawWorkflowStateInsertGuardTest.kt`]
- depends-on: []

**RED**:
- 파일: `.../guard/RawWorkflowStateInsertGuardTest.kt`
- 테스트: 테스트 소스 트리를 스캔해 `INSERT INTO workflow_states` 를 하는 파일이 **허용목록 4파일 외에 0개**임을 단언
- 실패 메시지 (예상): 위반 31파일 열거 — 이 시점에는 아직 아무것도 이주하지 않았으므로 **31건 전부 red**
- ★ **탐지 문자열을 런타임 조립**한다 — `val NEEDLE = "INSERT INTO " + "workflow_states"`. 리터럴로 적으면 판별식 파일 자신이 위반으로 잡히고, 자기를 허용목록에 넣으면 「판별식은 검사에서 빠진다」 구멍이 열린다(#356 선례)
- 허용목록 4파일 — `V200MigrationTest` · `SeedStatusCatalogIntegrationTest` · `StatusCatalogParityTest` · `YamlSeedServiceTest`. **구형 테이블 자체를 검증하는 것이 목적**이라 헬퍼로 감싸면 검증이 사라진다
- ★ 허용목록의 **썩은 항목 0도 함께 강제**한다 — 목록에 있는데 실제로는 더 이상 위반하지 않는 파일이 남으면, 그 줄이 미래의 신규 위반을 조용히 통과시킨다(#356 의 `stale` 단언과 같은 형태)

**GREEN**:
- 파일: 두 BC 의 `testsupport/WorkflowStatusFixture.kt`
- 헬퍼가 **신형 2단 삽입**을 한다 — `statuses` upsert(키 기준) → `workflow_statuses` insert(`display_order` 포함). 반환값은 `statusId`
- learnings `2026-05-23 fixture 옵션 B 패턴` — 픽스처가 **헬퍼를 호출**하게 만들어 drift 를 본질 차단한다. 회귀 가드는 보조다

**REFACTOR**:
- KDoc 에 「원시 SQL 로 상태를 심지 말 것 · 이유는 2단 카탈로그」를 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*RawWorkflowStateInsertGuardTest*'`

**❓ 남는 위험 (리뷰 렌즈 판단 요청).** `java-test-fixtures` 관례가 이 저장소에 **없어서**(실측) 헬퍼가
두 BC 에 **2벌**이 된다. 두 벌이 갈라질 여지가 있다. 대안은 `shared-kernel` 에 testFixtures 소스셋을
도입하는 것이나 **모듈 토폴로지 변경**이라 이 PR 범위를 넘는다. 헬퍼를 「신형 테이블 2개 삽입」으로
얇게 유지해 drift 여지를 줄이는 선에서 막는다.

### Task 2. `WorkflowDefinitionPermission` enum + resolver 포트 신설

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowDefinitionPermission.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowDefinitionPermissionResolver.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/permission/WorkflowDefinitionPermissionTest.kt`]
- depends-on: []

**RED**:
- 파일: `.../shared/permission/WorkflowDefinitionPermissionTest.kt`
- 테스트: enum 이 `CREATE`·`UPDATE`·`DELETE`·`PUBLISH` 4값을 갖고, resolver 포트가 **Guard 패턴**(권한 없으면 예외)임을 단언
- 실패 메시지 (예상): `WorkflowDefinitionPermission` 클래스 없음

**GREEN**:
- enum 4값 + `WorkflowDefinitionPermissionResolver` 인터페이스
- ★ **`WorkflowSchemePermission` 에 값을 끼워 넣지 않는다** — 이름이 스킴을 뜻하는데 워크플로우 정의를 담게 되어 다음 사람이 오해한다
- 반환 규약은 **Guard 패턴**(예외). 근거 — `WorkflowSchemePermissionResolver` 가 전역 자원 + 같은 BC 소비라는 두 조건에서 동형이다. `VersionPermissionResolver` 의 `Boolean` 규약은 프로젝트 스코프 자원의 것이다

**REFACTOR**:
- KDoc 에 권한↔엔드포인트 대응표(`VersionPermission` 관례) + 「전역 자원이라 `isSystemAdmin` 으로 판정」 근거

**검증**: `./gradlew :modules:shared-kernel:test --tests '*WorkflowDefinitionPermission*'`

### Task 3. 읽기 경로 2단 join 전환 + 픽스처 31파일 일괄 이주

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/DefaultWorkflowDefinitionRepository.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionTransitionResolver.kt`, `backend/modules/project-workflow/src/test/**`, `backend/modules/issue-tracking/src/test/**`]
- depends-on: [1]

**RED**:
- 파일: `.../repository/WorkflowRepositoryStatusCatalogTest.kt` (신규)
- 테스트: 헬퍼로 심은 상태(= `statuses` + `workflow_statuses` 에만 있고 `workflow_states` 에는 **없는** 상태)가 `WorkflowRepository.findByKey` 결과의 `states[]` 에 나타나는가
- 실패 메시지 (예상): `states[]` 가 비어 있음 — 읽기가 아직 구형 테이블을 본다
- ★ 이 RED 가 D1 이관의 본질이다. 지금 main 은 write 는 신형, read 는 구형인 상태다

**GREEN**:
- main 3파일의 join 을 `statuses` + `workflow_statuses` 2단으로 교체
- 31파일을 Task 1 의 헬퍼 호출로 이주 (issue-tracking 21 · project-workflow 10)
- ★ **N1 하위호환** — `GET /api/v1/workflows/{key}` 응답 형태는 `{key,name,description,states[],transitions[]}` 그대로다. 내부만 바뀐다

**REFACTOR**:
- 2단 join SQL 을 리포지토리 상수로 추출

**검증**:
- `./gradlew :modules:project-workflow:test :modules:issue-tracking:test`
- Task 1 의 `RawWorkflowStateInsertGuardTest` 가 **green 으로 전환**(31 → 0 위반)
- `apps/web` **0파일 변경** 확인 — `git diff --name-only main -- apps/web | wc -l` 이 0

### Task 4. 권한 어댑터 3종 배선

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessWorkflowDefinitionPermissionResolver.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/DevAllowWorkflowDefinitionPermissionResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/AlwaysAllowWorkflowDefinitionPermissionResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessWorkflowDefinitionPermissionResolverIntegrationTest.kt`]
- depends-on: [2]

**RED**:
- 테스트: 시스템 관리자는 통과, 아닌 사용자는 `WorkflowDefinitionAccessDeniedException`
- 실패 메시지 (예상): prod resolver 빈 없음

**GREEN**:
- prod 어댑터가 `SystemPermissionResolver.isSystemAdmin` 으로 판정한다. ★ **권한 매트릭스를 거치지 않는다** — `IdentityAccessWorkflowSchemePermissionResolver` 주석이 「Global 스코프는 매트릭스를 거치지 않고 isSystemAdmin 로 판정한다」를 명시했고 워크플로우 정의도 같은 전역 자원이다
- `DevAllow*`(비-prod) + BC 쪽 `AlwaysAllow*` stub

**REFACTOR**:
- prod 어댑터 KDoc 에 판정 경로표

**검증**: `./gradlew :modules:identity-access:test --tests '*WorkflowDefinitionPermissionResolver*'`

### Task 5. 워크플로우 CRUD API — 생성·수정·삭제·복제

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowDto.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowApplicationService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowCrudIntegrationTest.kt`]
- depends-on: [3, 4]

**RED** (red-first 3건):
- ① 이름 수정 후 `GET` 이 새 이름을 준다 (S1)
- ② 사용 중 워크플로우 삭제가 409 (S3 · E3 — 스킴 매핑 참조 기준)
- ③ 편집 후 캐시가 갱신된다 (E1)
- 실패 메시지 (예상): `POST/PUT/DELETE /api/v1/workflows` 매핑 없음 → 405/404

**GREEN**:
- `POST /` (201) · `PUT /{key}` (200) · `DELETE /{key}` (200, **소프트 삭제** = `deleted_at`) · `POST /{key}/duplicate` (201)
- 복제는 워크플로우 행 + `workflow_statuses` 편성 + 전환을 함께 복사하고 `origin='CUSTOM'` (F9)
- 신규 생성 기본값 `origin='CUSTOM'` · `version=0` · `is_locked=false` (F10)
- 쓰기 경로 끝에서 `WorkflowCache.invalidate(key)` + ★ `WorkflowCache.withWriteLock` 을 **실제로 호출**한다(현재 호출부 0곳 — 로드맵이 지목)
- 권한 게이트는 Task 2 의 resolver **메서드 호출**. `@PreAuthorize` SpEL 을 쓰지 않는다(N5)
- ★ E7 검사 순서 — **존재 확인을 권한 판정보다 먼저** 한다. 없는 워크플로우는 404, 있는데 권한이 없으면 403. MEMORY `permission-assert-before-existence-makes-403-lie` — 순서로 의미가 뒤집히고 로컬은 `AlwaysAllow` stub 이라 보이지 않는다

**REFACTOR**:
- 예외 → 상태코드 매핑을 `WorkflowExceptionHandler` 로 모은다

**검증**: `./gradlew :modules:project-workflow:test --tests '*WorkflowCrudIntegrationTest*'`

### Task 6. 전역 상태 카탈로그 CRUD API + 키 불변 강제

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/web/StatusController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/web/dto/StatusDto.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/application/StatusApplicationService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/repository/StatusRepository.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/web/StatusExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/status/web/StatusCrudIntegrationTest.kt`]
- depends-on: [3, 4]

**RED** (red-first 1건 + 엣지 3건):
- ① **`key` 변경 시도가 400** (S2 · F5) — 요청 DTO 가 `key` 를 아예 받지 않으므로 알 수 없는 필드로 거부된다
- E12 워크플로우가 참조 중인 상태 삭제 → 409 (`workflow_statuses.status_id` FK **RESTRICT** 를 409 로 번역)
- E13 이름이 대소문자만 다른 상태 생성 → 409 (`UNIQUE INDEX on lower(name) WHERE deleted_at IS NULL`)
- E15 소프트 삭제된 상태 조회 → 404
- 실패 메시지 (예상): `/api/v1/statuses` 컨트롤러 자체가 없음 → 404

**GREEN**:
- `GET /` · `POST /` (201) · `PUT /{id}` (200, `name`·`description`·`category` **만**) · `DELETE /{id}` (200, 소프트 삭제)
- 권한 게이트 + 캐시 무효화 — 상태 변경은 그 상태를 쓰는 **모든 워크플로우**의 캐시를 무효화한다

**REFACTOR**:
- `category` CHECK 값(TODO·IN_PROGRESS·DONE)을 enum 으로

**검증**: `./gradlew :modules:project-workflow:test --tests '*StatusCrudIntegrationTest*'`

### Task 7. 워크플로우↔상태 편성 API — 추가·제거·순서변경

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowApplicationService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowStatusRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowStatusCompositionIntegrationTest.kt`]
- depends-on: [5, 6]

**RED**:
- S5 상태를 넣으면 `GET` 의 `states[]` 에 나타나고, 순서를 바꾸면 순서가 바뀐다
- E5 이슈가 실제로 그 상태에 있는데 워크플로우에서 제거 → 409 (이관 마법사는 PR 10)
- E10 그 워크플로우에 없는 상태 id 가 순서 요청에 포함 → 400
- E11 순서 요청이 일부 상태를 누락 → 400 (전체 집합 일치 강제)

**GREEN**:
- `POST /{key}/statuses` (201) · `DELETE /{key}/statuses/{statusId}` (200) · `PUT /{key}/statuses/order` (200)
- `display_order` 만 다룬다. ★ `layout_x`·`layout_y` 는 **PR 9 범위라 건드리지 않는다**(C7)

**REFACTOR**:
- 순서 일치 검증을 도메인 불변식으로 끌어올린다

**검증**: `./gradlew :modules:project-workflow:test --tests '*WorkflowStatusCompositionIntegrationTest*'`

### Task 8. 죽은 권한 게이트 교체 (Maxi 결정 D1)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowControllerMvcTest.kt`]
- depends-on: [4, 5]

**RED** (red-first 2건 — E16):
- ⑤ **실제로 발급되는 신원**(시스템 관리자)으로 `POST /api/v1/workflows/cache/invalidate` → 200
- ⑥ 같은 경로를 비-관리자로 → 403
- ★ `@WithMockUser(authorities = ["WORKFLOW_MANAGE"])` 로 authority 를 **손수 심지 않는다**. 그 방식이 지금까지 이 결함을 가려 왔다(MEMORY `unreachable-state-fixture-is-fake-green`)
- 실패 메시지 (예상): ⑤가 **403** — 시스템 관리자에게도 `WORKFLOW_MANAGE` authority 가 발급되지 않기 때문. **이 red 가 결함의 증인이다**

**GREEN**:
- `@PreAuthorize("hasAuthority('WORKFLOW_MANAGE')")` 제거 → `WorkflowDefinitionPermissionResolver` 메서드 호출로 교체
- 기존 `@WithMockUser(authorities=[...])` 테스트 2건을 도달 가능한 신원 기반으로 **반전**한다(삭제가 아니라 반전 — 새 계약의 증인으로 남긴다)

**REFACTOR**:
- 컨트롤러 머리 주석의 엔드포인트 목록과 권한 표기를 실제와 맞춘다(`:30` 이 `WORKFLOW_MANAGE` 라고 적혀 있다)

**검증**: `./gradlew :modules:project-workflow:test --tests '*WorkflowControllerMvcTest*'`

### Task 9. 판별식 2종 — 캐시 무효화 차집합 · `@PreAuthorize` 재유입 금지

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/guard/CacheInvalidationCoverageTest.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/guard/NoPreAuthorizeSpelGuardTest.kt`]
- depends-on: [5, 6, 7, 8]

**RED**:
- **M5 차집합** — 「쓰기 엔드포인트 집합」 ⊖ 「`WorkflowCache.invalidate` 호출부 집합」 = ∅ 을 단언. MEMORY `two-lists-never-check-each-other` 의 처방 그대로다. 한쪽만 읽으면 안 읽는 쪽이 조용히 썩는다
- **M11** — `backend/**/main` 전체에서 `hasAuthority(` SpEL 권한 검사 **0건**
- ★ 두 판별식 모두 탐지 문자열을 **런타임 조립**한다
- ★ M5 는 「쓰기 엔드포인트」를 **손으로 다시 적지 않는다** — 컨트롤러 소스에서 `@PostMapping`·`@PutMapping`·`@DeleteMapping` 을 스캔해 집합을 만든다. 손으로 적으면 그 목록이 두 번째 리스트가 되어 같은 결함을 재생산한다(#390 의 C1 선례 — 판별식이 CLI 판정을 import 하지 않고 손으로 다시 적었다)

**GREEN**:
- 두 판별식 통과

**REFACTOR**:
- 스캐너 공통부를 테스트 헬퍼로

**검증**: `./gradlew :modules:project-workflow:test :modules:shared-kernel:test`

### Task 10. 뮤테이션 검증 3회 + 문서 전수 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-19-backend-project-workflow-crud-api.md`, `docs/specs/2026-08-19-backend-project-workflow-crud-api.md`, `docs/plan/product/project-workflow.md`, `TODOS.md`]
- depends-on: [9]

**RED** — 해당 없음(검증 task).

**GREEN**:
- ★ **뮤테이션은 GREEN 선커밋 뒤에** 넣는다. 미커밋 원복은 소실이다(함정)
- ① `invalidate` 호출 1개 삭제 → `CacheInvalidationCoverageTest` red 확인 → 원복
- ② 이주한 테스트 1개에 원시 SQL 재삽입 → `RawWorkflowStateInsertGuardTest` red 확인 → 원복
- ③ 아무 컨트롤러에 `@PreAuthorize("hasAuthority('X')")` 1줄 추가 → `NoPreAuthorizeSpelGuardTest` red 확인 → 원복
- 각 원복 후 `git status` 를 **눈으로 확인**한다
- FR 동기화 — FR-WF-04 의 D 단계 상태를 `docs/plan/product/project-workflow.md` 에 반영. **FR 총수는 143 불변**(신규 FR 없음)

**검증**:
- `bash scripts/verify-master-plan.sh` EXIT 0
- `node scripts/build-doc-index.mjs --check` EXIT 0
- `./gradlew :modules:project-workflow:test :modules:issue-tracking:test :modules:app:test ktlintCheck detekt` EXIT 0 (**단일 실행** — 같은 프로젝트 gradle 2개 동시 실행 금지)
- `./gradlew :modules:project-workflow:generateJooq` 후 **diff 0** (스키마 무변경이므로 diff 가 나오면 이상 신호)

## Plan 메타

- **task 수** — 10
- **예상 wave** — 5. `WorkflowController.kt` 를 Task 5·7·8 이 공유해 자동 직렬화된다
  - wave 1 — Task 1 · 2 (독립)
  - wave 2 — Task 3 (←1) · Task 4 (←2)
  - wave 3 — Task 5 (←3,4) → Task 6 (←3,4)
  - wave 4 — Task 7 (←5,6) → Task 8 (←4,5)
  - wave 5 — Task 9 (←5,6,7,8) → Task 10 (←9)
- **구현 규율** — TDD red-first. `test:` 커밋이 `feat:` 보다 선행
- **추가 검증** — ktlint · detekt · `:modules:app:test`(5433 실제 Postgres 필요 — `bts-postgres-dev` 유지) · `generateJooq` diff 0 · `verify-master-plan.sh` · `build-doc-index.mjs --check`
- **프론트** — `apps/web` **0파일**(N2). 프론트 검증은 회귀 확인 목적으로만 `pnpm --filter web test` 1회
- **의도적 편차 (게이트 2 요약에 싣는다)** — ① BC 격리 — issue-tracking 테스트 21파일 포함, 프로덕션 0줄(D2) ② 서브에이전트 dispatch 미사용 — Maxi 정책, 인라인 구현으로 대체하되 TDD·순서·검증은 그대로

## 리뷰 결과 (← /bts-review-plan 채움)

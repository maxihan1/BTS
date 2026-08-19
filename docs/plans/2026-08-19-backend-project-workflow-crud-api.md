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
- 허용목록 **5파일** (구현 중 실측으로 1건 추가) — `V200MigrationTest` · `SeedStatusCatalogIntegrationTest` · `StatusCatalogParityTest` · `YamlSeedServiceTest` · ★`V203ToV206MigrationTest`. 앞 4건은 언급만 하고, 마지막 1건은 **INSERT 를 하지만 그 INSERT 가 `V204` 백필의 원본**이라 헬퍼로 감싸면 백필 검증이 사라진다. 전부 **구형 테이블 자체를 검증하는 것이 목적**이다
- 따라서 이주 대상은 31 → **30** (issue-tracking 21 · project-workflow 9)
- ★ 허용목록의 **썩은 항목 0도 함께 강제**한다 — 목록에 있는데 실제로는 더 이상 위반하지 않는 파일이 남으면, 그 줄이 미래의 신규 위반을 조용히 통과시킨다(#356 의 `stale` 단언과 같은 형태)

**GREEN**:
- 파일: 두 BC 의 `testsupport/WorkflowStatusFixture.kt`
- 헬퍼가 **신형 2단 삽입**을 한다 — `statuses` upsert(키 기준) → `workflow_statuses` insert(`display_order` 포함). 반환값은 `statusId`
- learnings `2026-05-23 fixture 옵션 B 패턴` — 픽스처가 **헬퍼를 호출**하게 만들어 drift 를 본질 차단한다. 회귀 가드는 보조다

**REFACTOR**:
- KDoc 에 「원시 SQL 로 상태를 심지 말 것 · 이유는 2단 카탈로그」를 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*RawWorkflowStateInsertGuardTest*'`

**★ 리뷰 C2 반영 — 헬퍼 2벌에 대조 판별식을 짝으로 붙인다.**
`java-test-fixtures` 관례가 이 저장소에 **없어서**(실측) 헬퍼가 두 BC 에 **2벌**이 된다.
`shared-kernel` testFixtures 도입은 **모듈 토폴로지 변경**이라 이 PR 범위를 넘는다.

「얇게 유지한다」만으로는 부족하다 — 그것은 사람의 규율이지 기계의 강제가 아니고, 정확히
`two-lists-never-check-each-other` 의 양식이다. 따라서 **두 헬퍼가 같은 계약을 지키는지 대조하는
판별식**을 함께 둔다. 두 헬퍼가 삽입하는 **컬럼 집합**(`statuses` 측 · `workflow_statuses` 측)과
**함수 시그니처**를 각각 뽑아 **차집합 0** 을 단언한다. 한쪽만 컬럼이 늘면 red 가 된다.

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
- Task 1 의 `RawWorkflowStateInsertGuardTest` 가 **green 으로 전환**(30 → 0 위반)
- ★ **무손실 대조 (리뷰 T2)** — 31파일 대량 이동은 diff 로 사람이 판정할 수 없다(#390 실증). 이주 **전** 커밋에서 `:modules:project-workflow:test :modules:issue-tracking:test` 의 **테스트 이름 전량을 파일로 뽑아 두고**, 이주 후 같은 목록을 뽑아 **차집합 0** 을 확인한다. 테스트가 조용히 사라지거나 `@Disabled` 로 바뀌는 것을 사람 눈에 맡기지 않는다
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
- depends-on: [3, 4, 11]

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
- ★ E7 검사 순서 — **권한 판정을 존재 확인보다 먼저** 한다(리뷰 A2 로 반전). 비-관리자는 워크플로우 존재 여부와 무관하게 **403**, 권한 보유자가 없는 key 를 치면 404
  - 근거 실측 — `VersionApplicationService.kt:136-138` 이 `assertPermission` → `findActiveVersion` 순이다. 저장소 관례가 권한 먼저다
  - MEMORY `permission-assert-before-existence-makes-403-lie` 는 **순서를 뒤집으라는 처방이 아니다**. 그 메모리 본문이 「이 순서는 버그가 아니라 **의도된 정책**이다 — 존재 probe 방지」라고 명시한다. 처방은 **사용자 문구**를 그 순서에 맞게 쓰라는 것이다
  - 워크플로우 정의는 전역 + 시스템 관리자 전용이라 존재 숨김이 더 맞다. 409/403/404 문구는 원인을 뭉뚱그리지 않는다

**REFACTOR**:
- 예외 → 상태코드 매핑을 `WorkflowExceptionHandler` 로 모은다

**검증**: `./gradlew :modules:project-workflow:test --tests '*WorkflowCrudIntegrationTest*'`

### Task 6. 전역 상태 카탈로그 CRUD API + 키 불변 강제

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/web/StatusController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/web/dto/StatusDto.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/application/StatusApplicationService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/repository/StatusRepository.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/status/web/StatusExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/status/web/StatusCrudIntegrationTest.kt`]
- depends-on: [3, 4, 11]

**RED** (red-first 1건 + 엣지 3건):
- ① **`key` 변경 시도가 400** (S2 · F5) — 요청 DTO 가 `key` 를 아예 받지 않으므로 알 수 없는 필드로 거부된다
- E12 워크플로우가 참조 중인 상태 삭제 → 409 (`workflow_statuses.status_id` FK **RESTRICT** 를 409 로 번역)
- E13 이름이 대소문자만 다른 상태 생성 → 409 (`UNIQUE INDEX on lower(name) WHERE deleted_at IS NULL`)
- E15 소프트 삭제된 상태 조회 → 404
- ★ **E17 (리뷰 T1 신설)** 상태를 소프트 삭제한 뒤 **같은 `key` 로 재생성** → 201. Task 11 의 부분 유니크 인덱스가 없으면 이 케이스가 409 로 영구 차단된다
- 실패 메시지 (예상): `/api/v1/statuses` 컨트롤러 자체가 없음 → 404

**GREEN**:
- `GET /` · `POST /` (201) · `PUT /{id}` (200, `name`·`description`·`category` **만**) · `DELETE /{id}` (200, 소프트 삭제)
- 권한 게이트 + 캐시 무효화 — 상태 변경은 그 상태를 쓰는 **모든 워크플로우**의 캐시를 무효화한다
- ★ **무효화 방법 (리뷰 A3)** — `WorkflowCache` 는 `findByKey`(`:52`)·`invalidate(key)`(`:74`) 두 메서드뿐이고 **상태→워크플로우 역인덱스도 `invalidateAll` 도 없다**. 따라서 `workflow_statuses ⋈ workflows` 로 **DB 역조회해 key 목록을 얻어** `invalidate` 를 반복 호출한다. 캐시에 역인덱스를 새로 심지 않는다 — 두 번째 리스트가 되어 워크플로우 편성이 바뀔 때마다 갈라진다

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
- **M11 ★★ (구현 중 재설계)** — `backend/**/main` 의 `hasAuthority('X')` 에서 **X 가 `ROLE_` 로 시작하지 않으면 red**.

  초안은 「`@PreAuthorize` 애노테이션 자체 금지」였는데 **실측이 그것을 뒤집었다** — 저장소에 `@PreAuthorize` 가 **49건** 있고 `isAuthenticated()` · `hasRole('SYSTEM_ADMIN')` 은 **정당한 사용**이다. 애노테이션을 금지하면 무관한 49건이 red 가 되고 판별식이 곧 꺼진다.

  진짜 결함은 **발급되지 않는 authority 를 요구하는 것**이었다. 이 저장소가 만드는 authority 는 전부 `ROLE_` 접두어를 갖는다(`SidRevokeJwtConverter:115` 가 `setAuthorityPrefix("ROLE_")` · `PatAuthenticationFilter:48` 이 `ROLE_PAT`). 접두어 없는 문자열을 요구하면 그 게이트는 **어떤 요청으로도 통과할 수 없다**.

  `hasRole(` 은 검사하지 않는다 — Spring 이 `ROLE_` 을 자동으로 붙여 주므로 같은 결함이 생기지 않는다. 리뷰 T3 의 「`hasRole` 로 재유입된다」는 우려는 이 접두어 규칙으로 해소된다.

  실측 현재값 — `hasAuthority(` 사용처 **0건**(Task 8 이 마지막 1건을 제거).
- ★ 두 판별식 모두 탐지 문자열을 **런타임 조립**한다
- ★ M5 는 「쓰기 엔드포인트」를 **손으로 다시 적지 않는다** — 컨트롤러 소스에서 `@PostMapping`·`@PutMapping`·`@DeleteMapping` 을 스캔해 집합을 만든다. 손으로 적으면 그 목록이 두 번째 리스트가 되어 같은 결함을 재생산한다(#390 의 C1 선례 — 판별식이 CLI 판정을 import 하지 않고 손으로 다시 적었다)
- ★ **스캐너의 사각을 막는다 (리뷰 Q3)** — 스캔 대상 **파일 목록을 하드코딩하지 않는다**. `backend/modules/project-workflow/src/main/**` 를 **디렉터리 전수 순회**해 `@RestController` 를 가진 파일을 찾는다. 파일 목록을 적으면 새 컨트롤러가 목록 밖에서 태어나 조용히 빠진다 — 그것이 세 번째 리스트다
- ★ 비-공허 짝 — 컨트롤러가 **0개로 스캔되면 실패**한다. 스캐너가 경로를 잘못 짚어 빈 집합을 만들면 차집합이 자동으로 ∅ 이 되어 통과하기 때문이다

**GREEN**:
- 두 판별식 통과

**REFACTOR**:
- 스캐너 공통부를 테스트 헬퍼로

**검증**: `./gradlew :modules:project-workflow:test :modules:shared-kernel:test`

### Task 10. 뮤테이션 검증 3회 + 문서 전수 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-19-backend-project-workflow-crud-api.md`, `docs/specs/2026-08-19-backend-project-workflow-crud-api.md`, `docs/plan/product/project-workflow.md`, `TODOS.md`]  # 로드맵 정본은 저장소 밖(`~/.claude/plans/`)이라 files 에 넣지 않으나 Task 10 이 함께 갱신한다
- depends-on: [9]

**RED** — 해당 없음(검증 task).

**GREEN**:
- ★ **뮤테이션은 GREEN 선커밋 뒤에** 넣는다. 미커밋 원복은 소실이다(함정)
- ① `invalidate` 호출 1개 삭제 → `CacheInvalidationCoverageTest` red 확인 → 원복
- ② 이주한 테스트 1개에 원시 SQL 재삽입 → `RawWorkflowStateInsertGuardTest` red 확인 → 원복
- ③ 아무 컨트롤러에 `@PreAuthorize("hasAuthority('X')")` 1줄 추가 → `NoPreAuthorizeSpelGuardTest` red 확인 → 원복
- 각 원복 후 `git status` 를 **눈으로 확인**한다
- FR 동기화 — FR-WF-04 의 D 단계 상태를 `docs/plan/product/project-workflow.md` 에 반영. **FR 총수는 143 불변**(신규 FR 없음)
- ★ **로드맵 번호 재정렬 (게이트 1 채택 TODO ③)** — 이 PR 이 `V206` 을 쓰므로 로드맵 정본
  `~/.claude/plans/cozy-hatching-otter.md` 의 §DB 스키마(`V203 ~ V208` → `V203 ~ V209`) ·
  §PR 분해의 PR 4 `V206__transitions_multi_and_global.sql` → `V207` · PR 6 `V207__workflow_drafts…` → `V208`
  을 갱신한다. 안 하면 다음 PR 이 이미 쓴 번호를 다시 쓴다
- ★ **TODOS.md 등재 2건 (게이트 1 채택 TODO ①②)** — `TODOS.md` 머리의 등재 서식(#389 가 세운 「쉬운 말」·
  「방치하면」 두 줄 포함)을 따른다
  - ① `shared-kernel` 에 `java-test-fixtures` 소스셋 도입 — 픽스처 헬퍼가 BC 마다 복제되는 **구조적 원인**.
    이 PR 은 대조 판별식으로 증상만 막는다. 근본 해소는 모듈 토폴로지 변경(T3)이라 별도 PR
  - ② `workflow_states` DROP — `DATA.md` §4 add→backfill→drop 3단 분할의 마지막. 선행 = 로드맵 PR 4~10 완료

**검증**:
- `bash scripts/verify-master-plan.sh` EXIT 0
- `node scripts/build-doc-index.mjs --check` EXIT 0
- `./gradlew :modules:project-workflow:test :modules:issue-tracking:test :modules:app:test ktlintCheck detekt` EXIT 0 (**단일 실행** — 같은 프로젝트 gradle 2개 동시 실행 금지)
- `./gradlew :modules:project-workflow:generateJooq` 후 **diff 0** (스키마 무변경이므로 diff 가 나오면 이상 신호)

### Task 11. ★ 최선행 — `V206` 소프트 삭제 부분 유니크 인덱스 (리뷰 A1 / Maxi 결정 D3)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V206__soft_delete_partial_unique_keys.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V206MigrationTest.kt`, `backend/modules/project-workflow/src/generated/jooq/**`]
- depends-on: []

> **번호 주의.** 로드맵은 `V206` 을 PR 4(전환 다중화)에 예약했다. 이 PR 이 `V206` 을 쓰므로
> **PR 4 는 `V207` 로 밀린다.** 로드맵 정본 `~/.claude/plans/cozy-hatching-otter.md` §DB 스키마 ·
> §PR 분해의 번호를 Task 10 의 문서 동기화에서 함께 갱신한다.

**무엇이 문제였나.** PR 2 가 만든 DDL 을 읽어 확인한 **비대칭**이다.

```
statuses
  key   VARCHAR(50) NOT NULL UNIQUE                          (V203:13)  ← 무조건 유니크
  name  UNIQUE INDEX ... WHERE deleted_at IS NULL            (V203:32)  ← 부분 유니크
workflows
  key   TEXT NOT NULL UNIQUE                                 (V200:7)   ← 무조건 유니크
  deleted_at TIMESTAMPTZ                                     (V205:8)   ← 소프트 삭제 도입
```

이름은 소프트 삭제 후 재사용되는데 **key 는 죽은 행이 영원히 점유**한다. 편집기 UI(PR 8~10)에서
운영자가 상태를 지웠다 다시 만드는 것은 일상 조작인데, 그때마다 409 가 나고 원인인 죽은 행은
보이지도 지워지지도 않는다.

**RED**:
- 파일: `.../db/V206MigrationTest.kt` (`V200MigrationTest.kt` 패턴 · 이미지 `quay.io/tembo/pg16-pgmq:latest`)
- 테스트: ① `statuses` 행을 소프트 삭제한 뒤 **같은 `key`** 로 INSERT → 성공 ② 살아 있는 두 행이 같은 `key` → 여전히 위반 ③ `workflows` 도 ①②와 동일
- 실패 메시지 (예상): ① 이 `duplicate key value violates unique constraint` 로 실패

**GREEN**:
- `V206__soft_delete_partial_unique_keys.sql`
  - `statuses` 의 컬럼 레벨 `UNIQUE` 를 DROP 하고 `CREATE UNIQUE INDEX ... (key) WHERE deleted_at IS NULL` 로 교체
  - `workflows` 도 동일
  - ★ **DROP 전에 위반 데이터 가드**를 둔다. 부분 유니크로 바꾸는 것은 제약을 **완화**하는 방향이라 기존 데이터가 깨지지 않지만, 인덱스 생성이 실패하면 원인을 알 수 있게 `RAISE EXCEPTION` 메시지를 남긴다 (V204 의 유일성 가드 관례)
- `./gradlew :modules:project-workflow:generateJooq` 재실행 — ★★ **생성물은 커밋하지 않는다**. `.gitignore:21` 이 `**/src/generated/jooq/` 를 무시한다(구현 중 실측). 로드맵 §제약의 「jOOQ 생성물은 git 커밋 대상」은 **거짓**이다
- ★★ **진짜 검증은 코드젠 미러다.** `project-workflow` 의 jOOQ 입력은 손 유지 사본 `db/codegen/init_codegen.sql` 이라 마이그레이션만 고치면 생성물이 안 바뀐다. 미러를 함께 고쳐야 한다
- ★★ **PR 2 의 미러 판별식에 갭이 있었다** — 테이블→(컬럼명→타입) 한 축만 대조해 `key` 의 UNIQUE 소멸을 못 잡았다(`partial-column-parser-lets-unread-column-rot`). 이 Task 가 **제약·인덱스 축을 추가**해 막는다

**REFACTOR**:
- 마이그레이션 머리에 위 비대칭 표를 주석으로 남긴다 — 다음 사람이 「왜 컬럼 UNIQUE 를 인덱스로 바꿨나」를 묻지 않게

**검증**: `./gradlew :modules:project-workflow:test --tests '*V206MigrationTest*'`

## 구현 중 발견 (wave 1~2)

계획에 없던 것들이다. 게이트 2 요약에 그대로 싣는다.

### F1. `V206` 의 파급 — `ON CONFLICT (key)` 33곳

컬럼 UNIQUE 를 DROP 하고 **부분** 인덱스로 바꾸면 PostgreSQL 이 `ON CONFLICT (key)` 만으로는
그 인덱스를 추론하지 못한다. 실측 오류 문구.

```
ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification
```

| 대상 | 건수 | 처방 |
|---|---|---|
| 테스트 픽스처 `INSERT INTO workflows ... ON CONFLICT (key)` | 31 | `ON CONFLICT (key) WHERE deleted_at IS NULL` |
| 프로덕션 `YamlSeedService` jOOQ `.onConflict(STATUSES.KEY)` | 2 | `.where(STATUSES.DELETED_AT.isNull)` 추가 |

★ **예외 1건.** `V203ToV206MigrationTest` 는 **V205 이전 시점**(`deleted_at` 컬럼 부재)에 픽스처를
심으므로 술어를 붙이면 `column "deleted_at" does not exist` 로 죽는다. 마이그레이션 중간 상태를
다루는 픽스처는 그 시점의 스키마를 따라야 한다.

이 파급은 게이트 1 리뷰가 예측하지 못했다. D3 의 A안을 유지한 이유 — 전부 기계적 치환이고,
대안(tombstone)은 key 를 변형해 「키 불변」 원칙과 충돌한다.

### F2. 존재 판정에 aggregate 복원을 쓰면 중간 상태를 못 견딘다

`YamlSeedService.insertIfAbsent` 가 `workflowRepository.findByKey()` 로 존재를 판정했다.
읽기가 2단으로 바뀐 뒤 **`workflow_statuses` 가 빈 중간 상태**에서 그 복원이
`Workflow.of()` invariant(「상태가 하나 이상」)에 걸려 죽는다.

```
Workflow.of(Workflow.kt:45) ← toWorkflows(:192) ← findByKey(:43) ← insertIfAbsent(:378) ← seedAll(:226)
```

그러면 **바로 그 중간 상태를 되채우려던 보정 경로에 닿기도 전에** 시드가 실패한다.
처방 — 존재 판정을 `fetchExists(selectOne from workflows where key = ?)` 로 낮춘다.
invariant 는 옳으므로 완화하지 않는다.

### F3. 자동 치환이 상태 2개를 삼켰다 — 대조가 잡았다

29파일 이주를 스크립트로 돌렸더니 3파일에서 **멀티행 `VALUES` 를 1행으로 축소**하고
`wfId` 정의 블록까지 삼켰다. `git diff` 로는 「헬퍼 호출로 바뀌었다」로만 보인다.

파일별 **상태 삽입 건수 대조**(원본 `VALUES` 튜플 수 vs 현재 `insertWorkflowStatus(` 호출 수)가
`원본 3 → 현재 0` 으로 잡아냈다. 원복 후 수동 이주했다.
#390 의 「대량 이동은 diff 로 사람이 판정할 수 없다」가 그대로 재현됐다.

### F4. 권한 어댑터를 3종 → 2종으로 줄였다

plan 은 prod · DevAllow · AlwaysAllow 3종이었으나 **스킴 관례는 2종**(prod + BC stub)이다.
`@Profile("!prod")` 빈을 identity-access 와 BC 양쪽에 두면 비-prod 에서 둘 다 살아 충돌한다.
`WorkflowSchemePermissionResolver` 와 동형으로 맞췄다.

### F5. 이주 대상은 29파일 (허용목록 2항목)

허용목록은 파일명 기준 2항목이다 — `V203ToV206MigrationTest.kt`(V204 백필 원본) ·
`WorkflowStatusFixture.kt`(헬퍼 자신, 두 BC 에 각 1개). 계획의 31/30 은 헬퍼를 세지 않은 값이었다.
「썩은 항목」 단언이 처음 적은 허용목록 5개 중 4개를 즉시 잡아냈다 — 그 4개는 `workflow_states` 를
**언급만** 하고 INSERT 는 하지 않는다.

### F6. `insertIfAbsent` 변경이 만든 orphan — 생성자 파라미터 1개

`YamlSeedService` 가 존재 판정에서 `workflowRepository` 를 쓰지 않게 되자 그 생성자 파라미터가
**미사용**이 됐다(detekt `UnusedPrivateProperty`). 내 변경이 만든 orphan 이므로 제거했다 —
생성자 1곳 + 호출부 **8곳**(named argument 1곳 포함). 「내 변경이 만든 orphan 은 제거한다」 규칙 그대로다.

### F7. ktlint 와 detekt 의 줄 길이 기준이 다르다

`ktlintCheck` 는 **140자**, detekt `MaxLineLength` 는 **120자**(detekt 기본값)다.
그 사이 길이의 줄은 **ktlint 를 통과하고 detekt 에서 걸린다.** 두 도구를 모두 돌려야 안다.

`ktlintFormat` 은 이 PR 이 만진 **31파일만** 바꿨다(부수 변경 0). 「모듈 전체 format 이 PRE_EXISTING
파일을 부수 변경한다」는 함정은 **커밋을 먼저 하고 `git diff --name-only main...HEAD` 와 대조**해
확인했다. 그 절차 없이 format 을 돌리면 함정에 그대로 걸린다.

detekt 처방 2건은 억제가 아니라 근거를 남겼다.
- `LongParameterList`(픽스처 6개 파라미터) — spec data class 로 묶으면 **두 BC 에 또 하나의 복제**가
  생긴다. 호출부 40여 곳의 가독성도 떨어진다. KDoc 에 근거를 적고 `@Suppress`
- `NestedBlockDepth`(JDBC try-with-resources 3단 + 행 루프) — 더 쪼개면 자원 해제 경계가 흐려진다.
  공통 헬퍼 `forEachRow` 로 중첩을 한 곳에 모으고 그 함수에만 `@Suppress`

## 뮤테이션 검증 실측 (Task 10)

**GREEN 선커밋 뒤**에 넣고, 각 원복 후 `git status` 를 눈으로 확인했다(미커밋 원복은 소실이다).

| # | 넣은 결함 | 판별식 | 결과 | 잡은 것 |
|---|---|---|---|---|
| ① | `WorkflowCommandService.update` 의 `invalidate` 호출 1개 삭제 | `CacheInvalidationCoverageTest` | **red** | `["WorkflowCommandService.update"]` |
| ② | 이주한 테스트에 원시 SQL `INSERT INTO workflow_states` 1건 재삽입 | `RawWorkflowStateInsertGuardTest` | **red** | `["…/cache/WorkflowCacheTest.kt"]` |
| ③ | `StatusController.list` 에 `hasAuthority('STATUS_READ')` 1줄 추가 | `GrantedAuthorityPrefixGuardTest` | **red** | `["…/StatusController.kt → hasAuthority('STATUS_READ')"]` |
| ④ | issue-tracking 헬퍼만 `display_order + 1` 로 변경 | `WorkflowStatusFixtureParityTest` | **red** | 본문 불일치 |

네 판별식 모두 **실제로 값을 본다.** 원복 후 `git status` 는 매번 0건이었다.

## Plan 메타

- **task 수** — 11 (리뷰에서 Task 11 신설)
- **예상 wave** — 5. `WorkflowController.kt` 를 Task 5·7·8 이 공유해 자동 직렬화된다
  - wave 1 — Task 1 · 2 · **11** (전부 독립 · 11 은 마이그레이션이라 실행 순서상 최선행)
  - wave 2 — Task 3 (←1) · Task 4 (←2)
  - wave 3 — Task 5 (←3,4,11) → Task 6 (←3,4,11)
  - wave 4 — Task 7 (←5,6) → Task 8 (←4,5)
  - wave 5 — Task 9 (←5,6,7,8) → Task 10 (←9)
- **구현 규율** — TDD red-first. `test:` 커밋이 `feat:` 보다 선행
- **추가 검증** — ktlint · detekt · `:modules:app:test`(5433 실제 Postgres 필요 — `bts-postgres-dev` 유지) · `verify-master-plan.sh` · `build-doc-index.mjs --check`
- **★★ `generateJooq` 기대 재정정 (구현 중 실측)** — 생성물은 `.gitignore:21` 대상이라 **git diff 가 원리적으로 항상 0** 이다. 커밋하지 않는다. 검증은 `generateJooq` EXIT=0 + 컴파일 통과 + **코드젠 미러 정합 판별식**이다
- **프론트** — `apps/web` **0파일**(N2). 프론트 검증은 회귀 확인 목적으로만 `pnpm --filter web test` 1회
- **의도적 편차 (게이트 2 요약에 싣는다)** — ① BC 격리 — issue-tracking 테스트 21파일 포함, 프로덕션 0줄(D2) ② 서브에이전트 dispatch 미사용 — Maxi 정책, 인라인 구현으로 대체하되 TDD·순서·검증은 그대로

## 리뷰 결과

렌즈 — `/plan-eng-review` **1종**(분기 표 `TYPE == "api"`). Outside voice 는 `codex_reviews=disabled`
이고 `codex` 가 PATH 에 없어 **미실행**(조용히 0종으로 넘기지 않고 여기 명시한다).
서브에이전트 dispatch 미사용(Maxi 정책) — 렌즈를 인라인 적용했다.

### 판정 요약

| # | 심각도 | 신뢰도 | 발견 | 처리 |
|---|---|---|---|---|
| A1 | **P1 BLOCKER** | 9/10 | 소프트 삭제 vs `key` 무조건 UNIQUE 충돌 — 같은 key 재생성 영구 차단 | **Maxi 결정 D3 = A** → Task 11 신설(`V206`) |
| A2 | **P1** | 9/10 | E7 검사 순서가 저장소 관례와 **정반대** | Task 5 에서 **권한 먼저**로 반전 |
| A3 | P2 | 8/10 | `WorkflowCache` 에 상태→워크플로우 역인덱스도 `invalidateAll` 도 없다 | Task 6 에 DB 역조회 방식 명시 |
| C1 | P2 | 7/10 | `WorkflowController` 가 4 → 11 엔드포인트로 비대해진다 | **미채택** — 아래 근거 |
| C2 | P2 | 8/10 | 픽스처 헬퍼 2벌을 「얇게 유지」로만 막는 것은 사람의 규율이지 기계의 강제가 아니다 | Task 1 에 **대조 판별식** 추가 |
| T1 | **P1** | 9/10 | red-first 에 소프트 삭제 후 재생성 케이스 부재 (A1 이 드러냄) | E17 신설 · red-first 6 → **7건** |
| T2 | P2 | 8/10 | 31파일 대량 이주에 **회귀 증인**이 없다 | Task 3 에 테스트 이름 **무손실 대조** 추가 |
| T3 | P2 | 7/10 | M11 이 `hasAuthority(` 만 잡으면 `hasRole(` 로 재유입된다 | `@PreAuthorize` 애노테이션 자체를 금지 대상으로 |
| Q3 | P2 | 8/10 | M5 스캐너가 파일 목록을 하드코딩하면 **세 번째 리스트**가 된다 | 디렉터리 전수 순회 + **비-공허 짝**(0개 스캔 시 실패) |
| P1 | — | 9/10 | N+1 **없음** | `fetchJoinedRows`(`WorkflowRepository.kt:114`)가 단일 join 후 메모리 grouping. 2단 전환도 join 1개 추가일 뿐 |

**BLOCKER 0 (A1 은 Maxi 결정 D3 으로 해소).** 게이트 1 진입 가능.

### 1. Architecture review

**A1 [P1] (9/10) `V203:13` · `V200:7` — 소프트 삭제와 `key` 무조건 UNIQUE 의 충돌.**
근거 라인.
```
V203:13   key         VARCHAR(50)  NOT NULL UNIQUE
V203:32   CREATE UNIQUE INDEX uq_statuses_lower_name ... WHERE deleted_at IS NULL
V200:7    key         TEXT        NOT NULL UNIQUE
V205:8    ADD COLUMN deleted_at TIMESTAMPTZ
```
같은 테이블에서 **이름은 부분 유니크, key 는 무조건 유니크**다. F8(소프트 삭제)과 짝이 맞지 않는다.
→ Maxi 결정 D3 = A. Task 11(`V206`) 신설.

**A2 [P1] (9/10) Task 5 E7 — 검사 순서가 관례와 정반대였다.**
근거 라인 — `VersionApplicationService.kt:136-138`.
```
136   assertPermission(actorId, VersionPermission.UPDATE, projectId)
137   archiveGuard.check(projectId)
138   val existing = findActiveVersion(versionId, projectId)
```
권한이 **먼저**다. MEMORY `permission-assert-before-existence-makes-403-lie` 본문도
「이 순서는 버그가 아니라 **의도된 정책**이다 — 존재 probe 방지」라고 못박는다.
plan 초안은 그 메모리를 **「순서를 뒤집으라」로 오독**했다. 메모리의 처방은 순서가 아니라
**사용자 문구**(원인을 뭉뚱그리지 말 것)다. → Task 5 에서 반전.

**A3 [P2] (8/10) `WorkflowCache.kt:52,74` — 무효화 경로가 미정이었다.**
공개 메서드는 `findByKey(key)`·`invalidate(key)` 둘뿐이다. `invalidateAll` 도, 상태→워크플로우
역인덱스도 없다. Task 6 이 「그 상태를 쓰는 모든 워크플로우 무효화」를 하려면 방법이 필요하다.
→ DB 역조회(`workflow_statuses ⋈ workflows`)로 key 목록을 얻어 반복 호출. **캐시에 역인덱스를
새로 심지 않는다** — 편성이 바뀔 때마다 갈라지는 두 번째 리스트가 된다.

**C1 [P2] (7/10) `WorkflowController` 비대화 — 미채택.**
엔드포인트가 4 → 11 로 는다. 분리를 검토했으나 **채택하지 않는다**. 근거 — 상태 카탈로그는 이미
Task 6 에서 `status/web/StatusController` 로 분리돼 별 경로(`/api/v1/statuses`)를 갖고, 남는 것은
`/api/v1/workflows` **한 경로의 자원 CRUD** 라 한 컨트롤러가 맞다. 지금 나누면 경로가 같은데 클래스만
둘이 되어 다음 사람이 어느 쪽에 추가할지 모른다. 로드맵 PR 4~6 이 전환·규칙·초안을 각각 하위 경로로
붙이므로, **분리는 그때 하위 경로 단위로** 하는 것이 자연스럽다.

**데이터 흐름 (2단 전환 전후).**
```
[전]  workflows ─┬─ workflow_states ────────── (워크플로우 종속 상태)
                 └─ workflow_transitions

[후]  workflows ─┬─ workflow_statuses ── statuses   (전역 카탈로그 · N:M)
                 │        │ display_order
                 │        └ (layout_x/y — PR 9 범위, 무변경)
                 └─ workflow_transitions

  쓰기 경로 ──► WorkflowCache.withWriteLock ──► DB ──► invalidate(key)
                 (현재 호출부 0곳 — 이 PR 이 처음 씀)
  상태 변경 ──► workflow_statuses ⋈ workflows 역조회 ──► invalidate(key) × N
```

### 2. Code quality review

**C2 [P2] (8/10) Task 1 픽스처 헬퍼 2벌.**
`java-test-fixtures` 관례 부재는 실측으로 확인했다(`backend/**/build.gradle.kts` 에 0건).
plan 초안의 처방은 「헬퍼를 얇게 유지한다」였는데 그것은 **사람의 규율이지 기계의 강제가 아니다** —
정확히 `two-lists-never-check-each-other` 의 양식이다. → 두 헬퍼의 **삽입 컬럼 집합 + 시그니처**를
뽑아 차집합 0 을 단언하는 판별식을 짝으로 붙인다.

**stale 주석 1건.** `WorkflowRepository.kt:111-112` 의 「현재 스키마는 states 필수이나 향후 확장성을
위해 LEFT JOIN 유지」는 2단 전환 후 문맥이 달라진다. Task 3 REFACTOR 에서 함께 갱신한다.

**DRY** — 위반 없음. 세 리포지토리가 같은 join 을 각자 쓰게 되므로 Task 3 REFACTOR 의
「2단 join SQL 을 리포지토리 상수로 추출」이 그 자리를 막는다.

### 3. Test review

```
CODE PATHS                                           USER FLOWS
[+] V206 부분 유니크 인덱스                          [+] 상태 편집
  ├── [★★★ 계획됨] 소프트삭제→재생성 (E17·T1 신설)     ├── [★★★ 계획됨] 이름 수정 → GET 반영 (S1)
  ├── [★★  계획됨] 살아있는 중복 key → 여전히 위반      ├── [★★★ 계획됨] key 변경 거부 (S2)
  └── [★★  계획됨] workflows 도 동일                   ├── [★★  계획됨] 소프트삭제 후 재생성 (E17)
[+] 읽기 경로 2단 join                                └── [★★  계획됨] 대소문자 중복 (E13)
  ├── [★★★ 계획됨] 신형에만 있는 상태가 states[] 에
  ├── [★★★ 계획됨] 31파일 무손실 대조 (T2 신설)      [+] 권한
  └── [★★  계획됨] 응답 형태 무변경 (N1)               ├── [★★★ 계획됨] 관리자 200 / 비관리자 403 (E16)
[+] 쓰기 CRUD 11종                                    └── [★★★ 계획됨] 존재 숨김 순서 (E7·A2 반전)
  ├── [★★★ 계획됨] 생성·수정·삭제·복제
  ├── [★★★ 계획됨] 409 3종 (사용중·중복·잠금)
  └── [★★  계획됨] 순서 전체집합 일치 (E10·E11)
[+] 판별식 3종
  ├── [★★★ 계획됨] 뮤테이션 3회로 비-공허 확인
  └── [★★★ 계획됨] M5 스캐너 0개 스캔 시 실패 (Q3 신설)

COVERAGE: 계획 단계 — 아직 구현하지 않아 실측이 아니다. red-first 7건 + 엣지 17건이 task 에 배치됨
GAPS: 0 (리뷰에서 T1·T2·Q3 3건을 메워 닫음)
```

**REGRESSION RULE 적용 1건.** Task 3 의 31파일 이주는 **기존 동작을 바꾸는 변경**이고 기존 테스트가
「이주 자체」를 덮지 않는다. 따라서 무손실 대조(T2)는 **AskUserQuestion 없이 필수로 추가**했다.

**T3** — M11 탐지 범위를 `@PreAuthorize` 애노테이션 자체로 넓혔다. `hasAuthority(` 만 막으면
`hasRole(` 로 같은 결함이 다시 들어온다.

### 4. Performance review

**N+1 없음 (9/10).** 근거 — `WorkflowRepository.kt:100-103`.
```
100   fun findAll(): List<Workflow> {
101       val rows = fetchJoinedRows(DSL_TRUE)
102       return rows.toWorkflows()
```
`fetchJoinedRows`(`:114`)가 단일 join 쿼리로 전부 가져와 `:154` 에서 메모리 grouping 한다.
2단 join 전환은 **join 1개 추가**일 뿐 쿼리 수가 늘지 않는다.

**캐시** — `ConcurrentHashMap<String, Workflow>`(`:42`) 전량 적재. 워크플로우 수가 수십 단위라
메모리 우려 없음. 상태 변경 시 N개 무효화도 N 이 작다.

**주의 1건 (5/10 · 확인 권장).** `withWriteLock` 이 `pg_try_advisory_xact_lock`(`:135`)을 쓴다.
이 PR 이 그 첫 호출부가 되므로, 쓰기가 잦아지면 락 경합이 처음으로 관측될 수 있다.
현 사용 규모(관리자 1인 편집)에서는 문제되지 않는다.

### NOT in scope

| 항목 | 왜 미룸 |
|---|---|
| 전환(transition) CRUD | 로드맵 PR 4. 이 PR 은 읽기 경로만 |
| 전환 규칙(validator) CRUD | 로드맵 PR 5 |
| Draft/Publish · 기본값 복원 · `base_version` 낙관적 락 | 로드맵 PR 6. `workflows.version` 은 증가만 (C6) |
| 상태 이관 마법사 · 이슈 일괄 이관 | 로드맵 PR 7·10. E5 는 지금 409 로 막는다 |
| `layout_x` · `layout_y` | 로드맵 PR 9 (C7) |
| 편집기 UI 전량 | 로드맵 PR 8~10. `apps/web` 0파일 (N2) |
| `workflow_states` DROP | 로드맵 마지막 PR. `DATA.md` §4 add→backfill→drop 3단 |
| `shared-kernel` testFixtures 소스셋 도입 | 모듈 토폴로지 변경. C2 는 대조 판별식으로 대신 막는다 |

### What already exists

| 이미 있는 것 | 이 PR 의 태도 |
|---|---|
| `statuses` · `workflow_statuses` 테이블 (V203·V204) | **재사용.** 새로 만들지 않는다 |
| `workflows.version`·`origin`·`deleted_at`·`is_locked` (V205) | **재사용.** 컬럼 추가 없음 |
| `WorkflowCache.withWriteLock`(`:94`) | **재사용.** 호출부 0곳이던 자산을 처음 쓴다 |
| `WorkflowExceptionHandler` | **재사용.** 새 예외를 여기 얹는다 |
| 권한 3벌 관례 (`VersionPermission` 외 5종) | **모방.** 새 패턴을 만들지 않는다 |
| `IdentityAccessWorkflowSchemePermissionResolver` 의 Global=isSystemAdmin 판정 | **모방** |
| `V200MigrationTest` 패턴 | **모방.** Task 11 이 그대로 따른다 |
| `GET /workflows`·`GET /{key}`·`POST /{key}/transitions` | **무변경.** 내부 join 만 바뀐다 (N1) |

### 실패 모드 (신규 코드경로별 1건씩)

| 경로 | 현실적 실패 | 테스트 | 에러 처리 | 사용자가 보는 것 |
|---|---|---|---|---|
| 2단 join 읽기 | 상태가 카탈로그에서 소프트 삭제됐는데 편성은 남음 → `states[]` 결손 | E15 + 무손실 대조 | 카탈로그 삭제를 FK RESTRICT 가 막음(E12) | 발생 불가 경로 |
| 워크플로우 삭제 | 스킴 매핑이 참조 중 | S3·E3 | 409 | 「사용 중이라 지울 수 없습니다」 |
| 상태 제거 | 이슈가 실제로 그 상태에 있음 | E5 | 409 | 「이 상태를 쓰는 이슈가 있습니다」 |
| 캐시 무효화 | 상태 변경 후 일부 워크플로우 캐시가 남음 | M5 차집합 + 뮤테이션 | — | **조용한 실패** → 그래서 판별식이 필수 |
| 권한 게이트 | prod resolver 빈 부재로 부팅 실패 | Task 4 RED | `BeanCreationException` (의도) | 부팅 차단 — 임시 stub 금지 관례 |
| `V206` | 인덱스 생성 실패 | `V206MigrationTest` | `RAISE EXCEPTION` 메시지 | 마이그레이션 중단 |
| `withWriteLock` | advisory lock 타임아웃 | 기존 `WorkflowCacheLockTimeoutException`(`:151`) | 예외 | 「잠시 후 다시 시도」 |

### 인라인 ASCII 다이어그램을 넣을 파일

- `WorkflowRepository.kt` — 2단 join 구조 (위 §1 데이터 흐름 도해). `:111-112` stale 주석과 함께 갱신
- `WorkflowCache.kt` — 무효화 트리거 경로 (쓰기 CRUD → invalidate, 상태 변경 → 역조회 → invalidate × N)
- `V206__soft_delete_partial_unique_keys.sql` — 비대칭 표 (Task 11 REFACTOR 에 이미 배치)

### TODO 후보 (게이트 1 에서 Maxi 판정)

| # | 무엇 | 왜 | 얻는 것 / 비용 | 선행 |
|---|---|---|---|---|
| ① | `shared-kernel` 에 `java-test-fixtures` 소스셋 도입 | 픽스처 헬퍼가 BC 마다 복제되는 구조적 원인. 이번엔 대조 판별식으로 증상만 막는다 | 얻음 — 헬퍼 한 벌 · 비용 — 모듈 토폴로지 변경(T3) · 기존 테스트 의존 재배선 | 없음 |
| ② | `workflow_states` DROP | 3단 분할의 마지막 단계. 남겨 두면 「어느 쪽이 정본인가」가 계속 모호 | 얻음 — 모호성 제거 · 비용 — 되돌릴 수 없음 | 로드맵 PR 4~10 완료 |
| ③ | 로드맵 마이그레이션 번호 재정렬 | 이 PR 이 `V206` 을 쓰면서 PR 4~6 의 예약 번호가 한 칸씩 밀린다 | 얻음 — 정본 일치 · 비용 — 문서 3줄 | 이 PR (Task 10 에서 처리) |

## GSTACK REVIEW REPORT

| Runs | Status | Findings |
|---|---|---|
| `/plan-eng-review` (인라인 · 서브에이전트 미사용) | 완료 | 10 (P1 3 · P2 6 · 확인 1) |
| Outside voice (`codex`) | **미실행** | `codex_reviews=disabled` + `codex` PATH 부재 |
| Scope gate | 사용자 지정 경로 (Exception 2) | — |
| Design doc | `DESIGN.md` 는 UI 디자인 시스템 문서라 **무관** — 입력으로 쓰지 않음 | — |

| # | 심각도 | 신뢰도 | 상태 |
|---|---|---|---|
| A1 소프트삭제 vs key UNIQUE | P1 BLOCKER | 9/10 | 해소 — Maxi 결정 D3=A · Task 11 신설 |
| A2 E7 검사 순서 반대 | P1 | 9/10 | 해소 — Task 5 반전 |
| T1 소프트삭제 재생성 red 부재 | P1 | 9/10 | 해소 — E17 신설 · red-first 7건 |
| A3 캐시 무효화 경로 미정 | P2 | 8/10 | 해소 — Task 6 에 DB 역조회 명시 |
| C2 픽스처 헬퍼 2벌 | P2 | 8/10 | 해소 — Task 1 에 대조 판별식 |
| T2 31파일 이주 회귀 증인 부재 | P2 | 8/10 | 해소 — 무손실 대조 (REGRESSION RULE) |
| Q3 스캐너 파일목록 하드코딩 | P2 | 8/10 | 해소 — 전수 순회 + 비-공허 짝 |
| T3 M11 탐지 범위 | P2 | 7/10 | 해소 — `@PreAuthorize` 자체 금지 |
| C1 컨트롤러 비대화 | P2 | 7/10 | **미채택** — 경로 단위 분리는 PR 4~6 이 자연스러움 |
| P1 N+1 | — | 9/10 | 없음 확인 (`WorkflowRepository.kt:100-114`) |

**Q1~Q4 판정** — Q1(헬퍼 2벌) 불충분 → 대조 판별식 추가 · Q2(판별식 배치) **타당**, backend-ci 가
`backend/**` 를 걸어 반드시 돈다 · Q3(소스 스캔) 사각 있음 → 전수 순회 + 비-공허 짝 · Q4(E7 순서)
**plan 이 틀렸음** → 관례대로 권한 먼저로 반전.

VERDICT: **PASS with changes applied.** BLOCKER 0 · 발견 10건 중 9건 plan 반영 · 1건 근거와 함께 미채택.
CODEX: not run (disabled). CROSS-MODEL: none.

게이트 1 (2026-08-19) — **승인.** TODO ①②③ 전부 채택 → Task 10 이 처리한다
(① `TODOS.md` 등재 · ② `TODOS.md` 등재 · ③ 로드맵 정본 번호 재정렬).

NO UNRESOLVED DECISIONS

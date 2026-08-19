# 워크플로우·상태 CRUD API — 스펙

> slug: backend-project-workflow-crud-api · type: api · agent: backend-engineer · 티어: T3
> plan: [`docs/plans/2026-08-19-backend-project-workflow-crud-api.md`](../plans/2026-08-19-backend-project-workflow-crud-api.md)
> PR: #393 · 작성 2026-08-19 · 기준 HEAD `213e8a317`
> FR: FR-WF-04 (워크플로우 CRUD) · 선행 PR 2 = #392 · 선행 ADR `docs/adr/2026-08-18-workflow-global-status-catalog.md` · `-db-as-source-of-truth.md`

## 요약

워크플로우 편집기 로드맵 10 PR 중 **3번**. PR 2 가 만든 전역 상태 카탈로그(`statuses` ·
`workflow_statuses`)를 **읽기 경로가 실제로 쓰게** 만들고, 그 위에 워크플로우·상태 **쓰기 API** 를 연다.
지금 이 BC 의 쓰기 REST 노출은 **0개**다(§도메인 정리 실측).

## Jira 대조

비-UI 타입이므로 생략한다. 편집기 화면의 Jira 대조는 로드맵 PR 8~10 이 담당한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 워크플로우 이름 수정이 살아남는다
```
Given 시스템 관리자가 로그인했고 워크플로우 `software-workflow` 가 있다
When  PUT /api/v1/workflows/software-workflow 로 name 을 "개발 워크플로우" 로 바꾼다
Then  200 을 받고, 이어지는 GET /api/v1/workflows/software-workflow 가 새 이름을 준다
And   캐시가 무효화되어 전환 계산도 새 정의를 쓴다
```

### S2. 상태 키는 바꿀 수 없다
```
Given 전역 상태 `in_progress` 가 카탈로그에 있다
When  PUT /api/v1/statuses/{id} 바디에 key 를 넣어 보낸다
Then  400 을 받고 카탈로그의 key 는 그대로다
```
키는 이슈·자동화·검색이 문자열로 참조하는 식별자다(FK 없음). 바뀌면 조용히 끊긴다.

### 「사용 중」의 정의 ❓

두 축이 서로 다르므로 스펙에서 가른다.

| 대상 | 「사용 중」 판정 | 위반 시 |
|---|---|---|
| 워크플로우 | 스킴 매핑(`workflow_scheme_issue_type_mappings`)이 참조 | 409 (S3·E3) |
| 전역 상태 — 카탈로그에서 삭제 | 어떤 워크플로우든 `workflow_statuses` 로 참조 (FK RESTRICT) | 409 (E12) |
| 전역 상태 — 특정 워크플로우에서 제거 | 그 워크플로우를 쓰는 이슈가 실제로 그 상태에 있음 | 409 (E5) |

### S3. 사용 중인 워크플로우는 지울 수 없다
```
Given 워크플로우 `software-workflow` 가 어떤 스킴 매핑에 쓰이고 있다
When  DELETE /api/v1/workflows/software-workflow
Then  409 를 받고 워크플로우는 남는다
```

### S4. 권한 없는 사용자는 편집할 수 없다
```
Given 시스템 관리자가 아닌 사용자로 로그인했다
When  POST /api/v1/workflows 로 새 워크플로우를 만들려 한다
Then  403 을 받는다 (존재 여부를 묻는 경로에서는 404 보다 뒤에 판정한다 — §엣지 케이스 E7)
```

### S5. 워크플로우에 상태를 넣고 순서를 바꾼다
```
Given 전역 카탈로그에 상태 `blocked` 가 있고 워크플로우 `software-workflow` 는 아직 안 쓴다
When  POST /api/v1/workflows/software-workflow/statuses 로 `blocked` 를 넣는다
Then  201 을 받고 GET 응답의 states[] 에 blocked 가 나타난다
When  PUT /api/v1/workflows/software-workflow/statuses/order 로 순서를 바꾼다
Then  200 을 받고 GET 응답의 states[] 순서가 바뀐다
```

## 기능 요구사항 (FR)

FR-WF-04 의 D 단계. 하위 항목.

| # | 요구사항 |
|---|---|
| F1 | 읽기 경로가 `statuses` + `workflow_statuses` **2단 join** 으로 워크플로우를 읽는다 |
| F2 | 워크플로우 CRUD — 생성 · 수정 · 삭제 · 복제 |
| F3 | 전역 상태 카탈로그 CRUD — 목록 · 생성 · 수정 · 삭제 |
| F4 | 워크플로우 ↔ 상태 편성 — 추가 · 제거 · 순서 변경 |
| F5 | 상태 `key` 불변 — 수정 API 가 `key` 를 수용하지 않는다 |
| F6 | 모든 쓰기 경로가 끝에서 `WorkflowCache.invalidate(workflowKey)` 를 호출한다 |
| F7 | 쓰기 경로가 `WorkflowDefinitionPermission` 게이트를 통과해야 한다 |
| F8 ❓ | **삭제는 소프트 삭제**다. 워크플로우·상태 모두 `deleted_at` 을 채우고 물리 삭제하지 않는다 |
| F9 ❓ | **복제**는 새 `key` 를 받아 워크플로우 행 + 상태 편성(`workflow_statuses`) + 전환을 함께 복사하고 `origin='CUSTOM'` 으로 만든다 |
| F10 ❓ | 신규 생성 워크플로우는 `origin='CUSTOM'` · `version=0` · `is_locked=false` |
| F11 ★ | `POST /api/v1/workflows/cache/invalidate` 의 `@PreAuthorize` 를 제거하고 `WorkflowDefinitionPermission` resolver 게이트로 교체한다 (C1 결정) |

## 비기능 요구사항 (NFR)

| # | 요구사항 | 근거 |
|---|---|---|
| N1 | **`GET /api/v1/workflows/{key}` 응답 형태 무변경.** `{ key, name, description, states[], transitions[] }` 유지. 새 필드는 **추가만** | PR 2 가 PR 본문·스펙 §제약에 실은 하위호환 약속. `apps/web` 의 `api/workflows.ts` Zod 스키마 · `mocks/workflow-fixtures.ts` · `hooks/use-workflows.ts`(`extractStatusOptions`) · e2e 4건이 이 약속으로 무수정 생존한다 |
| N2 | **`apps/web` 0파일.** 이 PR 은 백엔드 전용 | 한 PR = 한 BC. UI 는 로드맵 PR 8~10 |
| N3 | 픽스처 헬퍼가 **유일한 상태 삽입 경로**가 되고, 원시 SQL 재유입을 판별식이 막는다 | learnings `2026-05-23 fixture 옵션 B 패턴` — mirror data 는 helper 호출로 drift 를 본질 차단. 회귀 가드는 보조 |
| N4 | 캐시 무효화는 **차집합 판별식**으로 강제한다. 「쓰기 엔드포인트 집합」과 「`invalidate` 호출부 집합」이 서로를 검사한다 | MEMORY `two-lists-never-check-each-other` — 두 목록이 서로를 검사하지 않는 지배 결함. 처방 = 차집합 판별식 + 비-공허 짝 + CI |
| N5 | 권한 판정은 **명시적 메서드 호출**로 한다. `@PreAuthorize` SpEL 을 쓰지 않는다 | `VersionPermissionResolver.kt` 가 관례를 명문화 — 「`@PreAuthorize` SpEL 표현식 대신 명시적 메서드 호출로 권한을 검증한다」. 실측상 저장소 전체 `hasAuthority(` 사용처는 1곳뿐이며 그 1곳이 죽은 게이트다(§제약 C1) |
| N6 | jOOQ 생성물 재생성 후 커밋 | 로드맵 §제약 — `./gradlew :modules:project-workflow:generateJooq` |

## API 인터페이스 (REST)

### 지금 있는 것 / 이 PR 이 만드는 것

`WorkflowController.kt` 의 `@*Mapping` 을 **세어서** 판정했다(learnings `2026-07-17 파일 존재 ≠ 기능 존재`).

| 메서드 · 경로 | 현재 | 이 PR |
|---|---|---|
| `GET /api/v1/workflows` | ✅ `:52` | 유지 |
| `GET /api/v1/workflows/{key}` | ✅ `:66` | 유지 (내부만 2단 join) |
| `POST /api/v1/workflows/{key}/transitions` | ✅ `:84` (전환 **계획 계산** — CRUD 아님) | 유지 |
| `POST /api/v1/workflows/cache/invalidate` | ✅ `:128` — **죽은 게이트**(§제약 C1) | §제약 C1 결정에 따름 |
| `POST /api/v1/workflows` | ❌ 없음 | **신설** |
| `PUT /api/v1/workflows/{key}` | ❌ 없음 | **신설** |
| `DELETE /api/v1/workflows/{key}` | ❌ 없음 | **신설** |
| `POST /api/v1/workflows/{key}/duplicate` | ❌ 없음 | **신설** |
| `GET /api/v1/statuses` | ❌ 컨트롤러 자체 없음 | **신설** |
| `POST /api/v1/statuses` | ❌ | **신설** |
| `PUT /api/v1/statuses/{id}` | ❌ | **신설** (`key` 미수용) |
| `DELETE /api/v1/statuses/{id}` | ❌ | **신설** |
| `POST /api/v1/workflows/{key}/statuses` | ❌ | **신설** |
| `DELETE /api/v1/workflows/{key}/statuses/{statusId}` | ❌ | **신설** |
| `PUT /api/v1/workflows/{key}/statuses/order` | ❌ | **신설** |

**쓰기 CRUD 노출은 현재 0개다.** 도메인·서비스·리포지토리 파일이 있어도 REST 가 없으면 기능이 없다.

### 상태 코드 계약

| 상황 | 코드 |
|---|---|
| 생성 성공 | 201 |
| 수정·순서변경 성공 | 200 |
| 삭제 성공 | **204** ★ — `WorkflowSchemeController.delete` 관례(`@ResponseStatus(NO_CONTENT)`). 초안의 200 은 관례 확인 전 값이었다 |
| `key` 형식 위반 · 알 수 없는 필드(`key` 수정 시도) | 400 |
| 권한 없음 | 403 |
| 대상 없음 | 404 |
| 사용 중 워크플로우·상태 삭제 · 키 중복 | 409 |

## 데이터 모델 변경

**신규 마이그레이션 1건 — `V206` (리뷰 A1 / Maxi 결정 D3).** 당초 「신규 마이그레이션 없음」이었으나,
소프트 삭제(F8)와 `key` 의 **무조건 UNIQUE** 가 충돌하는 것이 리뷰에서 실측으로 드러났다.
`statuses.name` 은 이미 부분 유니크(`WHERE deleted_at IS NULL`)인데 `statuses.key`(V203:13)와
`workflows.key`(V200:7)만 무조건 유니크라, 소프트 삭제된 행이 key 를 영원히 점유해 **같은 key 재생성이
영구 차단**된다. `V206` 이 두 key 를 부분 유니크 인덱스로 바꿔 규칙을 한 벌로 맞춘다.
★ 로드맵이 `V206` 을 PR 4 에 예약했으므로 **PR 4 는 `V207` 로 밀린다** — 로드맵 정본을 함께 갱신한다.

나머지는 PR 2 의 `V203`(카탈로그) · `V204`(백필) · `V205`(version·origin·deleted_at·is_locked)가
이미 만든 자리를 **읽고 쓰기 시작**하는 것이다.

읽기 경로 실측 — main 에서 아직 구형 `workflow_states` 를 읽는 파일.

| 파일 | 이 PR |
|---|---|
| `repository/WorkflowRepository.kt` | 2단 join 으로 교체 |
| `repository/DefaultWorkflowDefinitionRepository.kt` | 2단 join 으로 교체 |
| `postaction/PostActionTransitionResolver.kt` | 2단 join 으로 교체 |
| `seed/YamlSeedService.kt` | 이미 신형 (PR 2 가 전환) — 무변경 |

### 픽스처 이주 — 실측 수치

지시문의 「32파일」을 **다시 세었다**(함정 「지시문에 개수를 쓰지 마라」).

| 기준 | issue-tracking | project-workflow | 계 |
|---|---|---|---|
| `INSERT INTO workflow_states` 를 실제로 하는 테스트 | 21 | 10 | 31 |
| 그중 **이주 대상** (아래 제외 1건 뺀 값) | 21 | 9 | **30** |
| `workflow_states` 를 언급만 하는 테스트 = **이주 대상 아님** | 0 | 4 | 4 |
| 이미 `workflow_statuses` 를 쓰는 테스트 (PR 2 산출) | 0 | 4 | 4 |

이주 제외는 **5파일**이다(구현 중 실측으로 1건 추가).
- 언급만 하는 4건 — `V200MigrationTest` · `SeedStatusCatalogIntegrationTest` · `StatusCatalogParityTest` · `YamlSeedServiceTest`
- ★ INSERT 를 하지만 **그 INSERT 자체가 검증 대상**인 1건 — `V203ToV206MigrationTest`. `V204` 백필의 **원본**을 심는 것이 목적이라 헬퍼로 감싸면 백필 검증이 통째로 사라진다

전부 **구형 테이블 자체를 검증하는 것이 목적**이라 판별식 허용목록에 넣는다.

**BC 격리.** issue-tracking 21파일은 그 BC 의 테스트다. 한 PR = 한 BC 규칙과 충돌하는지 여부는
§제약 C2 에서 다룬다.

## 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| E1 | 이름만 바꾸고 상태는 그대로 | 200 · 캐시 무효화 · GET 이 새 이름 |
| E2 | `key` 를 바꾸려 시도 | 400 · 카탈로그 불변 |
| E3 | 스킴 매핑이 참조 중인 워크플로우 삭제 | 409 |
| E4 | 어떤 워크플로우도 안 쓰는 상태 삭제 | 200 |
| E5 | **이슈가 실제로 그 상태에 있는** 상태를 워크플로우에서 제거 | 409 (이관 마법사는 로드맵 PR 10) |
| E6 | 중복 `key` 로 상태 생성 | 409 |
| E7 | **없는 워크플로우를 권한 없이 수정** | **404 가 아니라 403 인지, 그 반대인지 순서를 고정한다.** MEMORY `permission-assert-before-existence-makes-403-lie` — 검사 순서로 의미가 뒤집히고 로컬은 `AlwaysAllow` stub 이라 보이지 않는다 |
| E8 | `origin='SEED'` 워크플로우 수정 | 허용 (DB 가 정본 — ADR `-db-as-source-of-truth`). 「기본값 복원」은 PR 6 |
| E9 | `is_locked=true` 워크플로우 수정 | 409 (V205 가 컬럼을 만들어 뒀다) |
| E10 | 순서 변경 요청에 그 워크플로우에 없는 상태 id 포함 | 400 |
| E11 | 순서 변경 요청이 일부 상태를 누락 | 400 (전체 집합 일치 강제) |
| E12 ❓ | **워크플로우가 참조 중인 전역 상태 삭제** | 409. `workflow_statuses.status_id` 가 **FK RESTRICT** 라 DB 가 먼저 막는다 — 그 예외를 409 로 번역한다 |
| E13 ❓ | 이름이 대소문자만 다른 상태 생성 (`Blocked` vs `blocked`) | 409. `UNIQUE INDEX on lower(name) WHERE deleted_at IS NULL` (Jira Cloud 동일 정책) |
| E14 ❓ | 이미 있는 key 로 워크플로우 복제 | 409 |
| E15 ❓ | 소프트 삭제된 워크플로우·상태를 조회·수정 | 404. `deleted_at IS NOT NULL` 은 없는 것으로 취급 |
| E17 ★ | 상태를 소프트 삭제한 뒤 **같은 `key` 로 재생성** | 201. `V206` 부분 유니크 인덱스가 없으면 409 로 영구 차단된다 (리뷰 A1) |
| E16 ★ | **실제로 발급되는 신원**으로 `cache/invalidate` 호출 | 시스템 관리자는 200, 아닌 사용자는 403. `@WithMockUser(authorities=[...])` 로 authority 를 손수 심지 않고 **도달 가능한 경로**로 검증한다 (C1 결정 · MEMORY `unreachable-state-fixture-is-fake-green`) |

## 제약 조건

### C1. ★ 죽은 권한 게이트를 발견했다 — Maxi 결정 필요

`POST /api/v1/workflows/cache/invalidate` 는 **어떤 실제 요청으로도 통과할 수 없다.**

실측 근거 3건.
1. `WorkflowController.kt:129` 가 `@PreAuthorize("hasAuthority('WORKFLOW_MANAGE')")` 를 건다.
2. `WORKFLOW_MANAGE` 라는 문자열은 저장소 전체에서 **그 컨트롤러와 그 테스트에만** 있다.
   부여하는 코드도, 시드하는 마이그레이션도 없다. SDD 12.3 정본 권한 코드는 `MANAGE_WORKFLOW`
   (철자가 뒤집혀 있다)이고 `V013__manage_workflow_permission.sql` 이 그것을 시드한다.
3. authority 를 만드는 곳은 저장소 전체에 2곳뿐이고 **둘 다 `ROLE_` 접두어**다 —
   `SidRevokeJwtConverter.kt:115`(`roles` claim → `ROLE_*`) · `PatAuthenticationFilter.kt:48`(`ROLE_PAT`).
   접두어 없는 `WORKFLOW_MANAGE` authority 는 생성 경로가 없다.

테스트가 초록인 이유는 `WorkflowControllerMvcTest.kt:184` 가 `@WithMockUser(authorities = ["WORKFLOW_MANAGE"])`
로 authority 를 **손수 심기** 때문이다. MEMORY `unreachable-state-fixture-is-fake-green` 의 양식이다.

> 이 PR 이 같은 컨트롤러에 쓰기 엔드포인트를 여럿 붙이므로, 죽은 게이트를 남기면 **한 컨트롤러에
> 권한 방식이 두 벌** 공존한다.

**★ 결정 (Maxi · 2026-08-19) — 이 PR 에서 고친다.** `@PreAuthorize("hasAuthority('WORKFLOW_MANAGE')")`
를 제거하고 `WorkflowDefinitionPermission` resolver 게이트로 교체한다. 근거 — 어차피 이 컨트롤러를
재작성 수준으로 만지므로 새 엔드포인트의 권한 방식과 한 벌로 맞출 수 있는 유일한 시점이다.
「부채 등재」·「엔드포인트 제거」는 기각. 제거안은 운영 중 캐시가 꼬였을 때 쓸 비상 수단을 없앤다.

이 결정이 범위에 더하는 것 — F11 · E16 · M11.

### C2. issue-tracking 21파일 이주와 「한 PR = 한 BC」

이주 대상 31파일 중 21이 issue-tracking 의 **테스트**다. 규칙은 「한 PR = 한 BC」이고
`/bts` 판정 5문 ④ 는 「테스트만 바뀌면 티어를 올리지 않는다」이나 **BC 격리는 티어와 별개 축**이다.

**★ 결정 (Maxi · 2026-08-19) — 이 PR 에 포함한다.** 근거 — 읽기 경로를 바꾸는 순간 그 21파일이
**즉시 red** 가 되므로 원자적으로 같이 가야 한다. 떼어 놓으면 두 PR 사이 구간에서 main 이 빨간불이
되어 로드맵의 「어느 시점에 멈춰도 main 이 초록」 원칙이 깨진다. 바뀌는 것은 **테스트 픽스처뿐이고
issue-tracking 의 프로덕션 코드는 0줄**이라 그 BC 의 동작 계약은 무변경이다.

게이트 2 요약에 「BC 격리 의도적 편차 — issue-tracking 테스트 21파일, 프로덕션 0줄」을 명시한다.

### C3. 운영 고지 유지

PR 2 가 세운 고지 — 이 PR 이후에도 **시드 YAML 편집은 「빈 DB 최초 부팅」에만 효력**이다.
기존 DB 반영 수단은 로드맵 PR 8~10 의 편집 UI 이며 그 전에는 직접 SQL 뿐이다.
이 PR 이 CRUD API 를 열지만 **UI 는 아직 없다**.

### C4. 권한 스코프 — 전역 자원 관례

워크플로우 정의는 스킴과 같은 **전역 자원**이다. `IdentityAccessWorkflowSchemePermissionResolver` 는
`Global` 스코프를 권한 매트릭스가 아니라 `SystemPermissionResolver.isSystemAdmin` 으로 판정하고,
`Project` 스코프에서만 `MANAGE_WORKFLOW` 코드를 쓴다. `WorkflowDefinitionPermission` 도 이 관례를 따른다.

또한 resolver 반환 규약이 두 갈래다 — `VersionPermissionResolver` 는 `Boolean`,
`WorkflowSchemePermissionResolver` 는 **예외를 던지는 Guard 패턴**. 워크플로우 정의는 스킴과 동형인
전역 자원이고 같은 BC 가 소비하므로 **Guard 패턴**을 따른다.

### C6 ❓. `workflows.version` 낙관적 락은 이 PR 에서 **강제하지 않는다**

`V205` 가 만든 `version BIGINT` 는 편집 낙관적 락용이다. 다만 로드맵은 낙관적 락 충돌(409)을
**PR 6**(Draft/Publish · `base_version`)의 범위로 명시한다. 따라서 이 PR 의 쓰기 경로는
`version` 을 **증가시키되 요청에서 요구하지 않는다**. PR 6 이 그 값을 근거로 409 를 낸다.

경계를 여기 적어 두는 이유 — 적지 않으면 다음 사람이 「version 컬럼이 있는데 왜 안 쓰지」로 읽고
PR 6 과 중복 구현한다.

### C7 ❓. `layout_x` · `layout_y` 는 건드리지 않는다

`workflow_statuses` 의 좌표 2컬럼은 로드맵 **PR 9**(xyflow 다이어그램)의 것이다.
이 PR 의 상태 추가·순서변경 API 는 `display_order` 만 다룬다.

### C5. 러너 단일성

CI 러너가 개발 머신과 같은 컴퓨터다. 로컬 gradle 과 CI 를 동시에 돌리지 않는다.
같은 프로젝트에서 gradle 2개 동시 실행도 금지(`build/test-results` XML 쓰기 충돌).

## 측정 가능한 완료 기준

| # | 기준 | 확인 방법 |
|---|---|---|
| M1 | red-first **7건**(원 4건 + C1 결정분 E16 2케이스 + 리뷰 T1 의 E17 소프트삭제 재생성)이 구현 전 red, 구현 후 green | `test:` 커밋이 `feat:` 보다 선행 (CI 판별식이 대조) |
| M2 | 신규 엔드포인트 11종이 실제로 매핑된다 | `grep -cE '@(Get\|Post\|Put\|Delete)Mapping'` 로 세어 대조 |
| M3 | 읽기 응답 형태 무변경 | `apps/web` **0파일 변경** · 프론트 vitest 전건 green · e2e 4건 green |
| M4 | 픽스처 이주 31파일 완료 · 원시 SQL 재유입 0 | 판별식이 `INSERT INTO workflow_states` 를 테스트에서 금지(허용목록 4파일) |
| M5 | 캐시 무효화 누락 0 | 차집합 판별식 — 쓰기 엔드포인트 집합 ⊖ `invalidate` 호출부 집합 = ∅ |
| M6 | 판별식이 **비어 있지 않다** | 뮤테이션 2회 — ①`invalidate` 호출 1개 삭제 → M5 red ②테스트 1개에 원시 SQL 재삽입 → M4 red. **GREEN 선커밋 뒤** 실행 |
| M7 | 백엔드 전량 green | `./gradlew :modules:project-workflow:test :modules:issue-tracking:test :modules:app:test ktlintCheck detekt` EXIT=0 |
| M8 ★★ | jOOQ 생성물 재생성 | `generateJooq` **EXIT=0** + 컴파일 통과. **커밋하지 않는다** — `.gitignore:21` 이 `**/src/generated/jooq/` 를 무시하고 주석이 「SQL 이 source of truth, 매 빌드시 재생성」이라 명시한다. 로드맵 §제약의 「생성물은 git 커밋 대상」과 이 항목의 이전 두 기대(「diff 0」·「diff 가 나온다」)는 **둘 다 틀렸다**(구현 중 실측). 대신 **미러 정합**이 진짜 검증이다 |
| M9 | 문서 정합 | `verify-master-plan.sh` EXIT 0 · `build-doc-index.mjs --check` EXIT 0 |
| M11 ★ | **`hasAuthority(` 재유입 0.** 판별식이 `backend/**/main` 에서 `@PreAuthorize` SpEL 권한 검사를 금지한다 | `VersionPermissionResolver` 가 명문화한 관례(N5)를 기계가 강제. 뮤테이션 — 아무 컨트롤러에 `@PreAuthorize` 1줄 추가 → red |
| M10 | CI 전건 green | 머지 전 실측 |

## Sanity Check

스펙을 스스로 한 번 흔들었다(4항목 — 누락 요구사항 · 모호한 표현 · 가정 누락 · 엣지 케이스 미커버).

**보강 1회 수행 (❓ 라벨).** 로드맵 §DB 스키마로 해소된 gap.

| gap | 처리 |
|---|---|
| 삭제가 hard 인지 soft 인지 미기재 | F8 — `deleted_at` 소프트 삭제로 확정 (로드맵 스키마 명시) |
| 복제의 의미(무엇까지 복사) 미기재 | F9 — 워크플로우 행 + 상태 편성 + 전환, `origin='CUSTOM'` |
| 신규 생성 시 `origin`·`version`·`is_locked` 초기값 미기재 | F10 |
| 참조 중 상태 삭제 미커버 | E12 — FK RESTRICT 를 409 로 번역 |
| 이름 대소문자 중복 미커버 | E13 — `lower(name)` 유일 인덱스 |
| 복제 key 충돌 · 소프트 삭제분 재조회 미커버 | E14 · E15 |
| 「사용 중」이 세 축을 뭉뚱그림 | §S2 아래 판정표로 분리 |
| `version` 낙관적 락을 이 PR 이 쓰는지 모호 | C6 — 증가만 시키고 강제는 PR 6 |
| `layout_x/y` 를 이 PR 이 건드리는지 모호 | C7 — PR 9 범위, 무변경 |

**Maxi 결정 필요 2건 — 게이트 1 로 올린다.**

- **C1 죽은 권한 게이트.** `POST /api/v1/workflows/cache/invalidate` 가 발급 경로 없는
  `WORKFLOW_MANAGE` authority 를 요구해 통과 불가. 이 PR 에서 고칠지, 부채로 등재할지.
- **C2 BC 격리.** 이주 대상 31파일 중 21이 issue-tracking 테스트다. 「한 PR = 한 BC」와의 관계.

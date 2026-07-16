<!-- 프로젝트 관리 FR 신설 스펙 — FR-PJ-01~04(생성/목록/설정/아카이브) + FR-PM-10(전역 권한 부여) + R6/R6-B 선재결함. plan D1~D11 상세화 -->

# 프로젝트 관리 기능 신설 — 스펙

> 날짜. 2026-07-17 | PR. #277 (Draft, 마스터) | 상위. [plan](../plans/2026-07-17-project-management-crud.md) | 브랜치. `backend/project-management-crud`
>
> **범위.** FR 5개 신설 (123 → 128) — `FR-PJ-01~04` (issue-tracking) + `FR-PM-10` (identity-access).
> 여기에 D10 결정으로 **R6/R6-B 선재 결함**(project-workflow)이 합류한다. **3개 BC.**

**office-hours 스킵 사유.** 요구사항이 plan D1~D11 (Maxi 확정 11건) + `/bts-domain` 도메인 정리로 이미 확정적이다.
office-hours는 "만들 가치가 있는가"를 묻는 단계인데, 그 질문은 **D11("계속 진행")로 2026-07-17 답이 나왔다**.
이미 잠긴 결정을 다시 흔드는 셈이라 부적합 (선례 — 2026-05-29 FR-IS-02에서 Maxi가 "직접 기술 스펙 작성" 선택).

**design-shotgun 스킵 사유.** UI는 BTS D단계 구조상 **D6**이고, 이 작업의 D6은 3번째 이후 PR이다(§9).
지금 목업 4종을 뽑으면 백엔드 계약(§4)이 확정되기 전에 낡는다. **UI PR의 spec에서 돌린다.**

---

## 0. 이 작업이 푸는 문제

**BTS에는 프로젝트를 만드는 방법이 없다.** `POST /api/v1/projects`도, `GET /api/v1/projects`도, 생성 화면도 없다.
모든 `/api/v1/projects/{key}/...` 엔드포인트와 `/projects/$projectKey/...` 라우트는 **이미 존재하는 프로젝트를 전제**한다.
지금 프로젝트를 만드는 유일한 방법은 `infra/local/seed-project.sql`을 손으로 실행하는 것이다.

Maxi 원문 — *"계획에 지라의 스페이스 개념이 빠져 있는데 추가 해줄래?"*.
조사 결과 **지라 Space = BTS `projects`**로 개념은 이미 있었고, 빠진 것은 **관리 기능과 그것을 추적할 FR**이었다(plan §Brief).

그리고 그 결핍이 **두 번째 결함을 숨겨왔다**. 프로젝트를 만들 수 없으니 아무도 새 프로젝트에 이슈를 넣어본 적이 없고,
그래서 **새 prod DB에서 이슈 생성이 422로 깨져 있다는 사실이 드러날 일이 없었다**(R6, §2.5).

---

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 프로젝트 생성 (해피 패스)

```
Given  actor 가 로그인했고 CREATE_PROJECT 전역 권한을 (직접 또는 그룹으로) 보유한다
When   POST /api/v1/projects { key: "ATLAS", name: "Atlas Issues" }
Then   201 Created + { id, key: "ATLAS", name, leadUserId: null, archivedAt: null }
And    projects 1행 + project_memberships 1행(actor=PROJECT_ADMIN) 이 같은 트랜잭션에서 생성된다
And    actor 는 즉시 그 프로젝트에서 이슈를 만들 수 있다
```

> **`project_memberships` 는 선택이 아니라 필수다.** `IdentityAccessIssuePermissionResolver` 는 비멤버를 즉시 거부하고
> **SYSTEM_ADMIN 자동 우회가 없다**(FR-PM-08 ADR §결정5). 멤버십 없이 생성하면 **생성자조차 못 들어가는 프로젝트**가 된다.
> → 두 행은 **같은 트랜잭션**. 이 경계는 PR 분할선이 가로지를 수 없다(§9 불변식 I1).

### S2. 권한 없는 actor 의 생성 시도

```
Given  actor 가 로그인했으나 CREATE_PROJECT 를 보유하지 않는다
When   POST /api/v1/projects { key: "ATLAS", name: "Atlas Issues" }
Then   403 Forbidden
And    projects / project_memberships 어느 것도 생성되지 않는다
And    응답 message 에 권한 내부 구조가 노출되지 않는다 (일반 메시지)
```

### S3. 미인증 요청

```
Given  actor 가 로그인하지 않았다
When   POST /api/v1/projects
Then   401 Unauthorized
And    프로젝트 존재 여부를 묻기 전에 거부된다 (actor 추출이 조회보다 먼저)
```

### S4. 중복 키

```
Given  key "ATLAS" 인 프로젝트가 이미 있다 (아카이브·소프트삭제 여부 무관)
When   POST /api/v1/projects { key: "ATLAS", ... }
Then   409 Conflict
And    key 는 영구 점유다 — 소프트 삭제된 프로젝트의 key 도 재발급되지 않는다 (DATA.md §1.1)
```

### S5. 프로젝트 목록 — 아카이브 기본 제외

```
Given  actor 가 프로젝트 A(활성) · B(아카이브) 의 멤버다
When   GET /api/v1/projects
Then   200 + A 만 반환된다
When   GET /api/v1/projects?archived=true
Then   200 + B 만 반환된다
And    두 경우 모두 actor 가 멤버가 아닌 프로젝트는 반환되지 않는다
```

### S6. 아카이브 — 읽기는 살고 쓰기만 죽는다

```
Given  프로젝트 P 가 아카이브됐다 (archived_at NOT NULL)
When   GET /api/v1/issues?projectKey=P  또는  GET /api/v1/issues/P-1
Then   200 — 이슈는 계속 읽힌다 (키가 외부 인용 중이다)
When   POST /api/v1/issues { projectKey: "P", ... }  또는  PATCH /api/v1/issues/P-1
Then   409 Conflict — 프로젝트가 읽기 전용이다
```

### S7. 아카이브 잠금은 백그라운드에도 적용된다 (D9)

```
Given  프로젝트 P 가 아카이브됐다
When   automation 룰이 P-1 을 수정하려 한다 (IssueMutationPort)
 또는  Import 작업이 P 에 이슈를 넣으려 한다 (IssueImportPort)
Then   두 경로 모두 거부된다 — 어댑터가 전부 IssueApplicationService 를 경유하므로 초크포인트 1곳이 덮는다
And    알림/Slack 은 이슈 변경 이벤트에 반응하므로 변경이 막히면 자동으로 멈춘다
```

### S8. 아카이브 해제

```
Given  프로젝트 P 가 아카이브됐다
When   POST /api/v1/projects/P/unarchive  (권한 = PROJECT_ADMIN)
Then   200 + archivedAt: null
And    P 는 다시 쓰기 가능해진다
```

### S9. 아카이브 프로젝트는 설정도 못 바꾼다

```
Given  프로젝트 P 가 아카이브됐다
When   PATCH /api/v1/projects/P { name: "새 이름" }
Then   409 Conflict
And    단 unarchive 는 허용된다 (Version ADR D4 동형 — 아카이브는 mutation 을 막지만 해제는 예외)
```

### S10. ★ 새 prod DB 에서 이슈가 만들어진다 (R6 회귀 가드)

```
Given  마이그레이션만 적용된 빈 DB 로 앱을 부팅했다 (시드 SQL 손수 실행 없음)
When   S1 로 프로젝트를 만들고 그 프로젝트에 이슈를 만든다
Then   201 — 422 workflow_not_configured 가 아니다
```

> **이 시나리오가 지금 실패한다.** R6 이 고쳐지지 않으면 FR-PJ-01 은 "프로젝트는 만들어지는데 이슈는 안 만들어지는" 반쪽으로 머지된다.

### S11. ★ 워크플로우 YAML 을 수정해도 앱이 부팅된다 (R6-B 회귀 가드)

```
Given  R6 백필로 workflow_scheme_issue_type_mappings 에 매핑이 존재한다
When   표준 워크플로우 YAML 을 구조적으로 수정하고(state 추가) 앱을 재부팅한다
Then   부팅 성공 — FK RESTRICT 위반으로 시드가 터지지 않는다
And    기본 매핑은 재적재 후에도 유효한 workflow 를 가리킨다
```

> **이 시나리오는 R6 을 고치는 순간 실패하기 시작한다.** §2.5-B 참조.

### S12. 전역 권한 부여 (FR-PM-10)

```
Given  actor 가 SYSTEM_ADMIN 이다
When   POST /api/v1/admin/global-permissions { permission: "CREATE_PROJECT", granteeType: "GROUP", granteeId: <groupId> }
Then   201 — 그 그룹의 모든 사용자가 CREATE_PROJECT 를 획득한다
When   SYSTEM_ADMIN 이 아닌 actor 가 같은 요청을 보낸다
Then   403
```

---

## 2. 기능 요구사항 (FR)

### 2.1. FR-PJ-01 — 프로젝트 생성

| # | 요구사항 |
|---|---|
| PJ1-1 | `POST /api/v1/projects` 신설. 201 + 생성된 프로젝트 |
| PJ1-2 | actor 는 **`CurrentActor.current()` 로 실제 인증 주체를 추출**한다. placeholder 금지 (§2.2 근거) |
| PJ1-3 | actor 추출이 **key 중복 조회보다 먼저** 실행된다 ([[auth-extraction-before-resource-lookup]]) |
| PJ1-4 | `CREATE_PROJECT` 전역 권한을 명시 호출로 게이트한다. `@PreAuthorize hasRole` 금지 (§2.4) |
| PJ1-5 | `projects` + `project_memberships`(actor=PROJECT_ADMIN) 를 **한 트랜잭션**에서 생성 |
| PJ1-6 | key 검증 — `^[A-Z][A-Z0-9]{1,9}$` (DB CHECK 와 동일). 위반 시 400 |
| PJ1-7 | key 중복 시 409. 아카이브·소프트삭제 프로젝트의 key 도 점유로 본다 |
| PJ1-8 | 권한 거부 message 에 내부 구조를 노출하지 않는다 ([[fr-pm-04-guard-exception-message-http-leak]]) |

#### 2.2. PJ1-2 의 근거 — placeholder actor 를 쓰면 아무도 못 들어가는 프로젝트가 생긴다 ★

`ProjectLeadController.kt:19-20` 은 actor 를 **하드코딩**한다.

```kotlin
/** actorId placeholder — FR-PM-03 실 추출 이연. SecurityConfig 가 401 을 보장한다. */
private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
```

이 패턴을 쓰는 컨트롤러가 **7개** 있다 — `ProjectLead` · `Component` · `Version` · `CustomField` · `IssueTemplate` · `Bulk` · `ReleaseNotes`.
기존 기능에서는 "누가 했는지"를 감사에만 쓰므로 placeholder 가 견뎠다.

**FR-PJ-01 은 다르다.** 생성자가 곧 `project_memberships` 의 PROJECT_ADMIN 이 된다(PJ1-5).
placeholder 를 멤버로 넣으면 **실재하지 않는 사용자가 유일한 관리자인 프로젝트**가 만들어지고,
`IdentityAccessIssuePermissionResolver` 는 비멤버를 즉시 거부하므로 **생성자 본인도 못 들어간다**.
placeholder 는 여기서 "감사 정확도" 문제가 아니라 **기능 자체를 무효화**한다.

**대안은 이미 있다.** `CurrentActor.current()`(`issue-tracking/.../rest/CurrentActor.kt`) 가 FR-PM-06 PR-B 에서 만들어졌고
`Comment` · `Watcher` · `Attachment` · `Worklog` 컨트롤러가 이미 쓴다. 계약 —

- `SecurityContextHolder` 의 `Authentication` 만 사용 → **인증 방식 중립**(Local/LDAP/SAML/OIDC 무관)
- `authentication.name` 이 UUID 형식이 아니거나 **nil-UUID 면 401**
- `ActorId` 가 nil-UUID 를 `require` 가드로 거부

> **범위 경계.** 기존 7개 컨트롤러의 placeholder 를 걷어내는 것은 **이 작업의 범위가 아니다**(글로벌 CLAUDE.md §3 surgical).
> FR-PJ 신규 코드만 `CurrentActor` 를 쓴다. 기존 7개는 별도 후속.

### 2.3. FR-PJ-02 — 목록 / 조회

| # | 요구사항 |
|---|---|
| PJ2-1 | `GET /api/v1/projects` 신설 — actor 가 접근 가능한 프로젝트만 |
| PJ2-2 | **아카이브 기본 제외.** `?archived=true` 로 아카이브만 조회 (D8 — 지라 관례) |
| PJ2-3 | `GET /api/v1/projects/{projectIdOrKey}` 신설 — 단건. 아카이브 프로젝트도 **조회된다** |
| PJ2-4 | 접근 불가 프로젝트는 목록에서 제외된다 (존재를 누설하지 않는다) |

> **D8 은 Version 선례와 의도적으로 다르다.** `VersionRepository.kt:84·100·129` 는 STATUS 필터 없이 `DELETED_AT` 만 검사한다(실측 확인).
> 프로젝트는 지라 관례를 따라 아카이브를 기본 제외한다.
>
> **⚠️ 정정 (Phase B B3).** plan 은 *"`ProjectMembershipAdapter.kt:63-73` 술어 변경 → identity-access cross-BC 영향"* 이라 적었으나 **두 가지가 틀렸다**.
> (1) 실제 심볼명은 `projectKeysOf(userId)`(`:54`) 이고 술어 SQL 은 `:67-73` 이다 — `"내 프로젝트 목록"` 이라는 문자열은 파일에 없다.
> (2) **영향이 identity-access 에서 끝나지 않는다.** 그 어댑터는 `ProjectMembershipPort` 구현이고 **실소비자는 search-export-import**(FR-SR-03 필터 공유 가시성)다.
> → **`projectKeysOf` 를 바꾸면 안 된다.** 아카이브 필터는 `projects` 소유 BC(issue-tracking)가 포트 결과 **위에** 얹는다.

### 2.4. FR-PJ-03 — 설정 변경 / FR-PJ-04 — 아카이브

| # | 요구사항 |
|---|---|
| PJ3-1 | `PATCH /api/v1/projects/{projectIdOrKey}` — `name` 변경. 권한 = PROJECT_ADMIN, 명시 호출 게이트 |
| PJ3-2 | 아카이브된 프로젝트의 설정 변경은 **409** (S9) |
| PJ4-1 | `POST /api/v1/projects/{projectIdOrKey}/archive` → `archived_at = now()`. 권한 = PROJECT_ADMIN |
| PJ4-2 | `POST /api/v1/projects/{projectIdOrKey}/unarchive` → `archived_at = NULL` |
| PJ4-3 | 아카이브 프로젝트는 **모든 쓰기를 거부**(409)하되 **읽기는 허용**한다 (S6) |
| PJ4-4 | 잠금은 `IssueApplicationService` **초크포인트 1곳**에 건다 — automation·Import·REST 가 전부 경유한다 (D9) |
| PJ4-5 | `Clock` 주입 필수. `Instant.now()` 직접 호출 금지 (Version ADR D3 선례) |
| PJ4-6 | `buildActiveSecureWhere`(`IssueRepository.kt:945-959`) 는 **읽기 술어 — 건드리지 않는다** |

**guard 는 `@PreAuthorize hasRole` 이 아니라 명시 호출이다.** `UserGroupController.kt:53-58` 이 근거를 남겼다 —
*"JWT claim 이 stale 일 수 있고 **PAT 경로에는 role claim 이 없어**, 두 인증 경로에서 일관된 전역 관리자 판정을 보장하기 위해 DB 진실원천을 직접 조회한다"*.
선언적 경로는 PAT 에서 **아예 동작하지 않는다**.

### 2.5. R6 / R6-B — 선재 결함 (D10 — 이번 PR 통합)

#### R6 (확정 — 3중 실측)

| # | 요구사항 |
|---|---|
| R6-1 | 빈 DB 부팅 후 **워크플로우 스킴 기본 매핑이 존재**해야 한다 |
| R6-2 | 백필은 `workflows` 가 채워진 **후**에 실행된다 (`YamlSeedService` 이후) |
| R6-3 | 백필은 **멱등**이다 — 재부팅해도 중복 행을 만들지 않는다 |
| R6-4 | S10 이 통과한다 |

근거 3중.
1. `V201__workflow_schemes.sql:132-134` 주석 자인 — *"Flyway migrate 는 Spring Boot 기동 전에 실행되므로 workflows 가 비어 있으면 0건 삽입. 이 경우 default mapping 은 후속 ApplicationRunner 에서 보완 (**Wave-2 범위**)"*
2. **그 Wave-2 는 미구현.** project-workflow 의 `ApplicationReadyEvent` 소비자는 `YamlSeedService` 하나뿐이고, 이 클래스는 `workflows`/`workflow_states`/`workflow_transitions`/`workflow_validators`/`workflow_post_actions` 만 삽입한다(`:483-533`). 전 모듈 `ApplicationRunner`/`CommandLineRunner` grep 에도 백필 없음(MinIO config · `SystemAdminBootstrapRunner` 뿐)
3. `infra/local/seed-project.sql:54-56` 주석이 증상 명시 — *"없으면 resolveStart(null) 가 기본 매핑을 못 찾아 422 workflow_not_configured"*

#### R6-B (신규 발견 — R6 을 고치면 활성화된다) ★

| # | 요구사항 |
|---|---|
| R6B-1 | 매핑이 존재하는 상태에서 워크플로우 YAML 을 구조 변경해도 **부팅이 성공**한다 |
| R6B-2 | 재적재 후 기본 매핑이 **유효한 workflow 를 가리킨다** (dangling 금지) |
| R6B-3 | S11 이 통과한다 |

`V201:84` — `workflow_id UUID NOT NULL REFERENCES workflows(id) **ON DELETE RESTRICT**`.
`YamlSeedService.applyIfChanged`(`:289-305`) 는 YAML structural 변경 감지 시 `deleteWorkflow(key)` → `insertWorkflow(dto)` 로 **통째 재적재**하는데,
`deleteWorkflow`(`:467-472`) 는 `DELETE FROM workflows WHERE key = ?` 평문이다.

> **매핑이 존재하는 순간부터, 표준 워크플로우 YAML 을 구조적으로 한 번만 수정해도 FK RESTRICT 위반 → 시드 예외 → 부팅 실패.**

- **두 결함이 서로를 가려왔다.** R6 때문에 매핑이 항상 0행이라 RESTRICT 가 발동할 일이 없었다
- `YamlSeedService` KDoc `:143-145` 는 *"CASCADE 로 런타임 post-action 이 소실된다 — 알려진 한계"* 라며 CASCADE 를 전제하는데, **매핑만 RESTRICT** 라 이 전제가 틀렸다
- **로컬은 이미 이 지뢰 위에 있다** — `seed-project.sql:58-67` 이 매핑을 심으므로, YAML 구조 수정 시 부팅 실패. 아직 아무도 안 밟았을 뿐
- 패턴 동형 — [[permitall-opens-preexisting-body-buffer-dos]]("경로를 열면 선재 결함이 신규 노출")

**재시드 생존 전략 — 3안 (plan 단계에서 1안 확정 필요).**

| 안 | 내용 | trade-off |
|---|---|---|
| (a) 매핑 정리 후 재연결 | `deleteWorkflow` 전에 매핑을 DELETE, `insertWorkflow` 후 key 기준 재INSERT | 재적재 창 동안 매핑 부재. 같은 트랜잭션이면 무해 |
| (b) FK 를 CASCADE 전환 | `V203` 으로 RESTRICT → CASCADE, 백필 러너가 매번 보정 | 재적재 시 매핑이 **조용히 소멸** — 백필이 반드시 뒤따라야 함. 실패 시 무증상 422 |
| (c) workflows 재적재를 UPSERT 로 | `deleteWorkflow` 자체를 없애고 UUID 보존 | 폭발 반경 최대 — `YamlSeedService` 핵심 로직 재설계. states/transitions cascade 도 재검토 |

> **(b) 는 무증상 실패로 되돌아간다는 점에서 위험하다** — R6 의 재발이다. **(a) 권장**, 단 plan 단계에서 카운트 가드 · `init_codegen.sql` 미러 영향과 함께 확정한다.

### 2.6. FR-PM-10 — 전역 권한 부여

| # | 요구사항 |
|---|---|
| PM10-1 | `global_permission_grants` 테이블 신설 — `(permission, grantee_type, grantee_id)` |
| PM10-2 | `grantee_type ∈ {USER, GROUP}`. GROUP 은 `user_groups`(V015, FR-PM-09) 재사용 |
| PM10-3 | `SystemPermissionResolver` 를 **default 메서드**로 확장 — `hasGlobalPermission(actorId, permission): Boolean` |
| PM10-4 | 부여/회수 API — 권한 = **SYSTEM_ADMIN**, 명시 호출 게이트 |
| PM10-5 | 권한 코드 시드가 카운트 가드를 깨는지 **실측 확인**한다. 실명은 `SchemaMigrationTest` 가 아니라 **`PermissionSchemaMigrationTest.kt:79`** (`role_permissions` 행 수 17 하드코딩). 단 `CREATE_PROJECT` 는 `global_permission_grants` 로 가므로 **깨지는지 UNKNOWN** — 전 모듈 grep 으로 판정 (Phase B B4) |
| PM10-6 | 판정은 fail-closed — grant 가 없으면 `false` |

#### 2.7. PM10-3 이 default 메서드여야 하는 이유 — 구현체는 3곳이 아니라 6곳이다 ★

plan 은 *"기존 구현 3곳"* 이라 적었으나 **실측 결과 6곳**이다. ([[spec-stated-count-becomes-blindfold]] — 스펙이 적은 개수를 그대로 물려받지 말 것)

| # | 구현체 | 위치 |
|---|---|---|
| 1 | `IdentityAccessSystemPermissionResolver` | identity-access **prod** |
| 2 | `NonProdAllowSystemAdminResolver` | issue-tracking **prod (`@Profile("!prod")`)** |
| 3 | `WebhookIntegrationConfig.StubSystemPermissionResolver` | search-export-import test |
| 4 | `TestPermissionConfig` 익명 object | notification test |
| 5 | `Require2faTestPermissionConfig` 익명 object | issue-tracking test |
| 6 | `StubSystemPermissionResolver` | slack-integration test |

abstract 메서드로 추가하면 **6곳 전부 컴파일 실패**한다. default 메서드면 6곳 모두 무변경으로 산다([[interface-extension-default-method]]).

> **mockk 은 default 메서드로 구제되지 않지만 이번엔 안전하다.** `mockk()`(non-relaxed) 은 default 메서드도 인터셉트하므로
> 스텁 없이 호출되면 실패한다. 다만 기존 소비자는 새 메서드를 **호출하지 않으므로** 기존 mockk 테스트는 영향받지 않는다.
> 새 메서드를 호출하는 것은 FR-PJ-01 신규 코드뿐이고, 그 테스트는 이번에 새로 쓴다.

#### 2.8. FR-PM-10 이 FR-PM 인 이유 (D6 논리의 역)

D6 은 *"프로젝트 CRUD 는 권한이 아니니 FR-PJ"* 라 판단했다. 전역 권한 부여는 그 역 — **권한 그 자체**이므로 §2.2.10 권한 관리에 속한다. BC 도 identity-access 로 일치한다.

#### 2.9. D7 이 FR-PM-08 ADR D3 위반이 아닌 이유 (원문 확인)

`docs/decisions/2026-06-04-system-admin-role.md:57` 원문 —

> 미래에 전역 권한이 세분화되면(예: 감사자는 `VIEW_AUDIT_LOG`만) **그때 전역 매트릭스를 도입한다.** 단일 역할 단계에서 매트릭스는 과설계.

`CREATE_PROJECT` 도입이 곧 **"전역 권한 세분화"** 다. D3 이 예고한 트리거가 발동한 것이므로 **위반이 아니라 예정된 확장**이다.
같은 ADR `:56` 은 *"전역 판정기 포트는 shared-kernel(`com.bts.shared.permission`)에 둔다"* 고 못 박았으므로 PM10-3 의 배치도 ADR 준수다.

**기존 테이블로는 안 되는 이유 (확정).**
- `role_permissions` — `CHECK(role IN ('PROJECT_ADMIN','MEMBER'))`(`V008:25`). 전역 축이 없다
- `project_permission_scheme` — `project_id` 가 PK. **생성 시점엔 project_id 가 없어 평가 자체가 불가능**

---

## 3. 비기능 요구사항 (NFR)

| # | 요구사항 |
|---|---|
| NFR-1 | 권한 테스트는 **`@ActiveProfiles("prod")`** 로 작성한다 (§7 C1) |
| NFR-2 | 생성 트랜잭션은 부분 성공을 남기지 않는다 — `projects` 만 있고 멤버십 없는 상태 금지 |
| NFR-3 | 아카이브 잠금 게이트는 **모든** 쓰기 경로를 덮는다. 미강제 지점 0 |
| NFR-4 | R6 백필은 부팅 시간을 유의미하게 늘리지 않는다 (표준 4 워크플로우 한정) |
| NFR-5 | 신규 엔드포인트는 인증 없이 접근 불가 (`DEVELOPMENT.md §1` 절대규칙 4) |

---

## 4. 인터페이스

### 4.1. REST — 신규

| 메서드 | 경로 | 권한 | FR |
|---|---|---|---|
| POST | `/api/v1/projects` | `CREATE_PROJECT` (전역) | FR-PJ-01 |
| GET | `/api/v1/projects` | 인증 (멤버십 필터) | FR-PJ-02 |
| GET | `/api/v1/projects/{projectIdOrKey}` | BROWSE | FR-PJ-02 |
| PATCH | `/api/v1/projects/{projectIdOrKey}` | PROJECT_ADMIN | FR-PJ-03 |
| POST | `/api/v1/projects/{projectIdOrKey}/archive` | PROJECT_ADMIN | FR-PJ-04 |
| POST | `/api/v1/projects/{projectIdOrKey}/unarchive` | PROJECT_ADMIN | FR-PJ-04 |
| POST | `/api/v1/admin/global-permissions` | SYSTEM_ADMIN | FR-PM-10 |
| DELETE | `/api/v1/admin/global-permissions/{id}` | SYSTEM_ADMIN | FR-PM-10 |
| GET | `/api/v1/admin/global-permissions` | SYSTEM_ADMIN | FR-PM-10 |

**`projectIdOrKey` 는 기존 관례다** — ADR `2026-06-01-project-member-projectidorkey`. `ProjectLookup` 재사용.

### 4.2. ★ 미해결 — FR-PJ-03 과 기존 `PATCH /lead` 의 충돌

plan 은 FR-PJ-03 을 *"설정 변경 (name·lead_user_id)"* 으로 정의했다. **그런데 `lead_user_id` 는 이미 `PATCH /api/v1/projects/{projectIdOrKey}/lead` 가 담당한다**(FR-CM-04, `ProjectLeadController.kt:75`).

기존 `project` 패키지는 **설정마다 독립 수직 슬라이스**를 두는 관례다 — 설정 하나당 Controller + ApplicationService + Repository + DTO + ExceptionHandler 5종.

| 슬라이스 | 엔드포인트 | 출처 |
|---|---|---|
| `ProjectLead*` | `PATCH /lead` | FR-CM-04 |
| `ProjectRequire2fa*` | `PATCH /require-2fa` | (프로젝트 2FA) |

또한 **plan 의 FR-PJ-03 정의는 `require_2fa`(V020) 를 누락**했다 — 이미 존재하는 프로젝트 설정이다.

→ **§4.2 는 plan 단계 결정 사항이다.** 3안 —
- (a) `PATCH /projects/{k}` 는 `name` 만. lead·require_2fa 는 기존 슬라이스 유지 → **기존 관례 일관, 변경 최소**
- (b) `PATCH /projects/{k}` 가 name+lead 통합, `PATCH /lead` deprecate → 통합 UX, 그러나 FR-CM-04 회귀 위험
- (c) `ProjectName*` 슬라이스 신설 → 관례 완벽 일치, 그러나 "설정 변경"이 엔드포인트 3개로 흩어짐

> **(a) 권장** — 글로벌 CLAUDE.md §3(surgical) + 기존 관례 존중. FR-PJ-03 정의를 "`name` 변경"으로 좁히고 plan/FR 표를 정정한다.

### 4.3. 포트 — shared-kernel 확장

```kotlin
interface SystemPermissionResolver {
    fun isSystemAdmin(actorId: UUID): Boolean

    /** 신규 — default 로 추가해 기존 6개 구현체를 fail-safe 하게 유지한다 (§2.7). */
    fun hasGlobalPermission(actorId: UUID, permission: String): Boolean = isSystemAdmin(actorId)
}
```

> **default 본문이 `isSystemAdmin` 위임인 것은 의도적이다** — 기존 구현체(특히 test stub)가 새 메서드를 물려받아도
> "SYSTEM_ADMIN 이면 모든 전역 권한 보유"라는 안전한 상위집합 의미를 갖는다. prod 어댑터는 이를 **override** 해 grant 를 조회한다.
> **단 `NonProdAllowSystemAdminResolver`(항상 `true`) 가 이 default 를 물려받으면 non-prod 에서 CREATE_PROJECT 가 항상 통과**한다 — 의도된 동작이나 NFR-1 이 반드시 필요한 이유다.

---

## 5. 데이터 모델 변경

### 5.1. 현재 `projects` 실측 (2026-07-17)

| 컬럼 | 출처 |
|---|---|
| `id` UUID PK / `key` VARCHAR(10) UNIQUE CHECK `^[A-Z][A-Z0-9]{1,9}$` / `name` VARCHAR(255) / `key_sequence` BIGINT / `created_at` / `updated_at` / `deleted_at` | V001 |
| `lead_user_id` UUID NULL | V013 (FR-CM-04) |
| `require_2fa` BOOLEAN NOT NULL DEFAULT false | V020 |

> **`version`(낙관적 잠금) 컬럼이 없다.** 이슈에는 있으나 프로젝트엔 없다. §6 EC-7 참조.

### 5.2. 신규 마이그레이션

**Flyway V번호는 BC 별 독립 네임스페이스다** — `FlywayAssemblyConfig.kt:23-68` 이 BC 마다 별도 이력 테이블(`flyway_history_<bc>`)에 자기 location 만 마이그레이션한다. identity-access 와 issue-tracking 이 V030~V035 를 **중복 보유해도 충돌하지 않는다**.

| BC | 다음 번호 | 내용 |
|---|---|---|
| issue-tracking | **V037** (최신 V036) | `ALTER TABLE projects ADD COLUMN archived_at TIMESTAMPTZ NULL` |
| identity-access | **V036** (최신 V035) | `global_permission_grants` + `CREATE_PROJECT` 권한 코드 시드 |
| project-workflow | **V203** (최신 V202) | R6-B 대응 (전략 확정 후) |

**실행 순서 — identity → issue → workflow** (`FlywayAssemblyConfig.kt:28-38`). KDoc `:20-21` 명시 — *"project-workflow(V202)가 issue-tracking 의 projects 를 FK 참조하므로 issue 를 workflow 보다 먼저 실행한다"*. **`projects` 는 cross-BC FK 피참조 대상**이므로 컬럼 추가 시 이 순서 전제를 깨지 않는지 확인한다.

### 5.3. 아카이브 = `archived_at`, `deleted_at` 과 직교

| 축 | 컬럼 | 의미 |
|---|---|---|
| 존재 여부 | `deleted_at` (기존) | NULL=활성. **범위 밖 — 손대지 않는다** |
| 잠금 여부 | `archived_at` (신규) | NULL=활성, NOT NULL=읽기 전용 |

- **`status` enum 기각** — 2값은 boolean 의 enum 위장. Version 의 enum 은 릴리스 축(UNRELEASED/RELEASED)이 **선재했기에** 정당했고 프로젝트엔 그런 축이 없다. **아카이브 축을 timestamp 단독으로 모델링한 선례 실재** — `V407__notifications_inbox.sql:6` (`ALTER TABLE notifications ADD COLUMN archived_at TIMESTAMPTZ`)
  > **정정(Phase B B5).** `notifications` 테이블에 `status` 컬럼은 **실재한다**(`V402:17`, TEXT NOT NULL, PENDING/SENT/FAILED). 다만 그것은 **발송 축**이지 아카이브 축이 아니다. 선례의 요점은 "이 테이블에 status 가 없다"가 아니라 **"아카이브를 status 에 얹지 않고 별도 timestamp 로 뽑았다"** 이다
- **`deleted_at` 재사용 기각** — 의미가 정반대. `projects.deleted_at` 읽기 술어 10곳이 전부 "NOT NULL = 존재하지 않음(404/제외)"을 전제하는데, 아카이브 프로젝트는 **여전히 조회돼야** 한다. 한 곳만 놓치면 아카이브가 곧 **이슈 소실**로 나타난다
- **cascade 없음** — 아카이브는 잠금이지 소멸이 아니다. 물리적으로도 불가에 가깝다: boards/sprints 는 `project_key` **문자열**만 갖고 BC 격리로 `projects` 직접 참조가 차단돼 있다(`V500__boards.sql:11`, `V503__sprints.sql:11` — 둘 다 `project_key VARCHAR(64) NOT NULL, -- BC 격리: FK 아님`)
- **`key` 는 아카이브와 무관하게 UNIQUE 를 계속 점유** (DATA.md §1.1)

---

## 6. 엣지 케이스

| # | 케이스 | 기대 |
|---|---|---|
| EC-1 | 소프트 삭제된 프로젝트의 key 로 생성 | 409 — key 는 영구 점유 (DATA.md §1.1) |
| EC-2 | 아카이브된 프로젝트를 다시 아카이브 | 멱등 200 (또는 409 — plan 확정) |
| EC-3 | 아카이브 프로젝트의 이슈 **읽기** | 200 — 반드시 살아야 한다 |
| EC-4 | 아카이브 프로젝트에 automation 시간 트리거 발화 | 조기 skip. 409 실패 노이즈 금지 (D9 잔여) |
| EC-5 | `lead_user_id` 가 가리키는 사용자 삭제 후 아카이브 | 아카이브는 성공 (lead 는 nullable) |
| EC-6 | 동시에 같은 key 로 2건 생성 | 1건 201 + 1건 409. DB UNIQUE 가 진실원천 ([[jooq-exception-translator-409-dependency]]) |
| EC-7 | 같은 프로젝트를 두 사람이 동시에 설정 변경 | **`version` 컬럼 부재 — plan 확정 필요.** OCC 도입 vs last-write-wins |
| EC-8 | R6 백필 중 앱이 죽고 재부팅 | 멱등 — 중복 매핑 없음 (R6-3) |
| EC-9 | 매핑이 이미 있는 DB 에 백필 러너 최초 배포 | 무변경 (로컬 seed-project.sql 로 이미 심긴 환경) |
| EC-10 | 비-멤버가 아카이브 프로젝트 단건 조회 | 403/404 — 아카이브가 권한을 완화하지 않는다 |

---

## 7. 제약

| # | 제약 |
|---|---|
| C1 | **`NonProdAllowSystemAdminResolver` 는 `@Profile("!prod")` + 항상 `true`.** KDoc 이 직접 *"운영 환경 사용 시 권한 우회가 발생한다"* 고 명시. 권한 테스트를 기본 프로파일로 짜면 **"권한 있는 사용자는 생성 가능" 테스트가 무의미하게 통과**한다(누구나 관리자니까). **`@ActiveProfiles("prod")` + 실제 grant 시드** 필수 |
| C2 | **음성 가드는 본문 판별자가 필요하다.** "여전히 403"은 vacuous 하다 — 컨트롤러가 없어도 403 이다. **위반을 주입해 fail 을 확인**할 것 ([[negative-guard-needs-body-discriminator]]) |
| C3 | `init_codegen.sql` 미러 필수 (issue-tracking = jOOQ 모듈) ([[jooq-init-codegen-mirror]]) |
| C4 | 권한 시드 카운트 가드의 실명은 **`PermissionSchemaMigrationTest.kt:79`** — `SchemaMigrationTest` 는 automation 모듈의 다른 테스트다(Phase B B4 오기 정정). `role_permissions` 를 참조하는 테스트는 `PermissionSchemaMigrationTest` · `IssueVisibilityAdapterIntegrationTest` 2개. **전 모듈 grep 으로 실측** ([[fr-pm-permission-seed-migration-test-coupling]] — 이 메모리의 표현도 부정확) |
| C5 | `SystemPermissionResolver` 확장은 **default 메서드** (§2.7 — 구현체 6곳) |
| C6 | 새 cross-BC 의존을 소비하면 **OpenApi 테스트에 `@MockBean` 동반** 필요 ([[new-crossbc-dep-openapi-mockbean-regression]]) |
| C7 | prod 조립 부팅 재검증 필수 — 머지 전 rebase + `:modules:app:test` ([[prod-assembly-boot-verification-required]]) |
| C8 | 같은 모듈 안에서는 Flyway V번호 동시 브랜치 충돌이 여전 — 머지 직전 재확인 ([[migration-vnumber-concurrent-branch-collision]]) |
| C9 | 백엔드 CI 부재 — main 머지 전 로컬 검증 필수 ([[no-backend-ci-and-assembly-merge-verification-traps]]) |

---

## 8. 측정 가능한 완료 기준

| # | 기준 | 검증 |
|---|---|---|
| DoD-1 | S1~S12 전 시나리오 통과 | 통합 테스트 |
| DoD-2 | **빈 DB 부팅 → 프로젝트 생성 → 이슈 생성 201** | S10 — Testcontainers |
| DoD-3 | **YAML 구조 변경 후 재부팅 성공** | S11 — Testcontainers |
| DoD-4 | 권한 테스트가 `@ActiveProfiles("prod")` 에서 통과 | C1 |
| DoD-5 | 아카이브 잠금 미강제 지점 **0** — 패턴 grep 으로 재검증 | NFR-3 |
| DoD-6 | `./gradlew :modules:app:test` 통과 (9 BC prod 조립) | C7 |
| DoD-7 | `bash scripts/verify-master-plan.sh` 통과 | 전수 동기화 |
| DoD-8 | FR 카운트 **실측** 128 (기존 숫자 복사 금지) | §10 drift |

> **DoD-5 의 "0" 은 스펙이 세어준 숫자가 아니다.** [[spec-stated-count-becomes-blindfold]] — 개수는 패턴 grep 으로 직접 재검증한다.

---

## 9. PR 분할 설계

### 9.1. 분할 불변식 (넘으면 안 되는 선)

| # | 불변식 | 근거 |
|---|---|---|
| **I1** | `projects` 삽입과 `project_memberships` 삽입은 **같은 트랜잭션 · 같은 PR** | 분리하면 생성자조차 못 들어가는 프로젝트 (§2.2) |
| **I2** | **방어가 경로보다 먼저다** — `CREATE_PROJECT` 게이트 없이 `POST /projects` 를 열지 않는다 | FR-AT-07 C-1 이 *"전략 오조준 — 방어 없이 경로를 먼저 열고 방어는 2 PR 뒤"* 로 감점된 실수 |
| **I3** | R6 백필과 R6-B 방어는 **같은 PR** | 백필만 넣으면 부팅 실패를 심는 것 (§2.5-B) |
| **I4** | R6 는 FR-PJ-01 과 같은 PR (D10 — Maxi 확정) | 별도 선행 PR 기각 |

### 9.2. 제안 분할

| PR | 내용 | BC | 마이그레이션 |
|---|---|---|---|
| **PR-1** | **FR-PM-10 백엔드** — `global_permission_grants` + `CREATE_PROJECT` 코드 + `SystemPermissionResolver` default 확장 + grant/revoke API | identity-access **(1개)** | V036 |
| **PR-2** | **R6 + R6-B + FR-PJ-01 백엔드** — 백필 러너 + 재시드 생존 + 프로젝트 생성 | project-workflow + issue-tracking **(2개 — D10)** | V203, V037 |
| **PR-3** | **FR-PJ-02/03 백엔드** — 목록·조회·설정 변경 | issue-tracking | — |
| **PR-4** | **FR-PJ-04 백엔드** — 아카이브 + 잠금 초크포인트 + cross-BC 술어 | issue-tracking (+ identity-access 술어) | V038 |
| **PR-5** | **D6 프론트 UI** — 생성·목록·설정·아카이브 화면 (design-shotgun 은 여기서) | apps/web | — |
| **PR-6** | **FR-PM-10 관리 화면 + D7 E2E** | apps/web | — |

**I2 가 PR-1 을 앞에 세운다.** FR-PM-10 을 먼저 넣으면 **방어만 들어가고 경로는 아직 안 열린다** — C-1 실수의 정반대다.
그리고 PR-1 은 **BC 1개**라 `DEVELOPMENT.md §4`("한 PR = 한 BC + 한 plan") 관례도 지킨다.

**PR-2 만 BC 2개다.** D10 이 명시 고지 후 확정한 유일한 예외이므로 PR 본문·`/bts-codereview` 에 D10 근거를 첨부한다.
`bc:<context>` 라벨이 단수 전제라 표기 방식은 plan 단계에서 정한다.

> **PR-4 를 PR-3 에서 분리한 이유** — 아카이브는 폭발 반경이 가장 크다. 목록/설정과 섞으면 리뷰 단위가 무너진다.

> 🛑 **§9 는 Phase B B1/B2 로 확정 불가 상태다.** 아래 두 가지가 미해결이다.
> - **PR-2 는 B2 로 재설계 필요** — `project_memberships` 가 identity-access 소유라 신규 cross-BC 쓰기 포트가 선행돼야 한다. 그 포트를 PR-1(identity-access)에 넣으면 PR-2 의 BC 가 2개 → 유지, 별도 PR 로 빼면 5→7 PR
> - **PR-4 의 비용 추정이 B1 로 무너졌다** — D9 의 *"비용 거의 없음"* 이 근거였으나 실측 29개 쓰기 / 5 BC. **아카이브 잠금 범위가 Maxi 결정 사항**이며, 그 결정 없이는 PR-4 가 1개인지 3개인지 정할 수 없다

**각 PR 은 자기 plan 파일을 갖는다** (`DEVELOPMENT.md §4`). 이 문서는 **마스터 스펙**이고, 각 PR 착수 시 `/bts` 로 상세화한다 (FR-AT-07 마스터 스펙 §B 선례 동형).

---

## 10. spec 단계 실측 발견 — plan 정정 5건

| # | plan 기술 | 실측 | 처리 |
|---|---|---|---|
| 1 | *"project-workflow 는 범위 밖"* | **D10 으로 범위 복귀** — 3 BC | plan §도메인 정리 정정 완료 |
| 2 | *"현 최신 V028"* | **BC 별 독립 네임스페이스.** issue V036 / identity V035 / workflow V202 | plan §구현 시 필수 주의 정정 완료 |
| 3 | *"SystemPermissionResolver 기존 구현 3곳"* | **6곳** (prod 2 + test 4) | §2.7 |
| 4 | FR-PJ-03 = *"설정 변경 (name·lead_user_id)"* | `lead_user_id` 는 이미 `PATCH /lead`(FR-CM-04) 담당. `require_2fa`(V020) 누락 | **§4.2 — plan 결정 필요** |
| 5 | (미기술) | `projects` 에 `version`(OCC) 컬럼 **없음** | **EC-7 — plan 결정 필요** |
| 6 | (미기술) | **R6-B** — R6 수정이 FK RESTRICT 부팅 실패를 활성화 | §2.5-B |

### 함께 고칠 drift 3건 (plan §도메인 정리)

| drift | 실제 | 처리 |
|---|---|---|
| `docs/plan/fr-index.md:5` `(122개 전수)` | **123** | 이번 PR 통합 (FR 추가로 어차피 건드림) |
| `DATA.md:45` *"`SoftDeleteFilter` 래퍼"* | **허구** — `.kt` grep 히트 0 | 이번 PR 통합 (아카이브 술어 설계가 오독하면 보안 버그) |
| `docs/plan/product/issue-tracking.md:171` `(7개)` | **8** | 분리 (무관한 섹션 — 글로벌 CLAUDE.md §3) |

> `verify-master-plan.sh` **는 위 drift 2건을 안고도 종료 0 으로 통과한다.** 통과는 카운트 정합의 충분조건이 아니다.

---

## Brainstorming Check

### 1회차 (2026-07-17) — 적대적 검토. **BLOCKER 3건 · 정정 4건 · 주의 3건**

> **교훈. 이 스펙의 BLOCKER 3건은 전부 "plan 이 이미 조사해서 안전하다고 결론낸 지점"에서 나왔다.**
> D9 는 *"비용 거의 없음 — 초크포인트 한 곳"*, R2 는 *"2행 한 트랜잭션"*, D8 은 *"술어 변경"* 이라 적었고
> 나는 그 결론을 spec 으로 옮겨 적었다. **셋 다 증거는 참인데 결론이 과일반화였다.**
> [[spec-stated-count-becomes-blindfold]] 의 재현 — 앞 단계가 "세어준" 범위를 물려받으면 그 밖을 안 센다.

#### 🛑 B1 — D9 의 "초크포인트 한 곳" 이 프로젝트 스코프 쓰기 29개를 안 덮는다 (**Maxi 결정 필요**)

D9 원문 — *"cross-BC 쓰기 포트 2종이 전부 `IssueApplicationService` 를 통과하므로 초크포인트 한 곳 게이트로 **API·automation·Import 가 동시에 막힌다**"*.

**증거는 참이다.** `IssueMutationPort` · `IssueImportPort` 는 실제로 `IssueApplicationService` 소비자다 → **S7(automation·Import 차단)은 그대로 성립**.
**결론이 과일반화다.** "API" 는 **이슈 API 에만** 참이다.

`/api/v1/projects/{...}` 하위 쓰기 엔드포인트 **29개 / 5 BC** 실측 — **`IssueApplicationService` 참조 0건**.

| BC | 컨트롤러 | 쓰기 수 |
|---|---|---|
| issue-tracking | `Version`(5) · `Component`(4) · `CustomField`(3) · `IssueTemplate`(3) · `ProjectLead`(1) · `ProjectRequire2fa`(1) | **17** |
| identity-access | `ProjectMember`(3) · `ProjectSecurityScheme`(2) · `FieldPermission`(2) | **7** |
| automation | `AutomationRule`(4) | **4** |
| project-workflow | `ProjectWorkflowScheme`(1) | **1** |

→ **NFR-3("미강제 지점 0")은 초크포인트 1곳으로 달성 불가.**
→ D9 의 ***"`ProjectLifecyclePort` 신설 불필요, BC별 어댑터 불필요"* 는 반증됐다** — identity-access · automation · project-workflow 가 "이 프로젝트가 아카이브인가"를 물을 창구가 없다.
→ **§9 PR-4 의 비용 추정이 무너진다.** D9 의 *"비용 거의 없음"* 이 이 추정의 근거였다.

#### 🛑 B2 — I1(`projects` + `project_memberships` 같은 트랜잭션)이 BC 경계를 횡단한다

| 테이블 | 소유 BC | 근거 |
|---|---|---|
| `projects` | **issue-tracking** | `V001__issues_initial.sql:10` |
| `project_memberships` | **identity-access** | `V007__project_memberships.sql` — 코드 접근도 identity-access 단독 |

plan R2 는 *"생성 = 2행 한 트랜잭션"* 이라 정확히 세었으나 **그 2행이 서로 다른 BC 에 산다는 것을 놓쳤다.**
I1 을 그대로 구현하면 issue-tracking 이 identity-access 테이블에 직접 쓴다 = **BC 격리 위반**(`CLAUDE.md §핵심 패턴`).

**기존 포트로는 안 된다.** `shared-kernel/membership/ProjectMembershipPort.kt` 는 **읽기 전용**이다 — `projectKeysOf(userId): Set<String>` 하나뿐.
KDoc 이 *"**default 구현 금지 (fail-closed)**"* · *"이 포트 외부에서 별도의 프로젝트 멤버십 조회 경로를 만드는 것을 금지한다"* 로 설계 의도를 못 박았다.

→ **신규 cross-BC 쓰기 포트 필요** (issue-tracking → identity-access). 선례는 `IssueMutationPort`(automation → issue-tracking, 포트+어댑터로 남의 BC 에 쓰기).
→ **default 메서드 금지** — 이 포트는 fail-closed 를 명시 설계했다. abstract 로 추가하고 구현체(identity-access 1곳 + test stub)를 갱신한다.
  ([[interface-extension-default-method]] 의 "공유 인터페이스는 default" 원칙과 **충돌** — 이 포트는 명시적 예외다)

#### 🛑 B3 — D8 의 술어 변경이 search-export-import FR-SR-03 을 오염시킨다

D8 은 *"`ProjectMembershipAdapter.kt` 술어 변경 → identity-access cross-BC 영향"* 이라 적었다. **영향 범위가 identity-access 에서 끝나지 않는다.**

그 어댑터는 `ProjectMembershipPort.projectKeysOf` 의 구현이고, **이 포트의 실소비자는 search-export-import**(FR-SR-03 필터 공유 가시성)다.
`projectKeysOf` 에 아카이브 제외를 넣으면 **프로젝트를 아카이브하는 순간 그 프로젝트의 공유 필터가 조용히 사라진다** — 아카이브는 **잠금이지 소멸이 아니다**(§5.3)는 이 스펙의 기본 전제와 정면 충돌.

→ **FR-PJ-02 의 아카이브 필터는 `projectKeysOf` 를 바꾸지 않고 그 위에 얹어야 한다.** 포트는 "멤버인 프로젝트"만 답하고, 아카이브 축은 `projects` 소유 BC(issue-tracking)가 건다.

### 정정 4건 (실측 검증)

| # | spec 기술 | 실측 | 조치 |
|---|---|---|---|
| B4 | C4 *"`SchemaMigrationTest` 카운트 가드"* | **오기.** `SchemaMigrationTest.kt` 는 **automation 모듈 1개뿐**이고 그 count 는 룰 액션/조건용이다. 권한 시드 가드의 실명은 **`PermissionSchemaMigrationTest.kt:79`** (`assertThat(count).isEqualTo(17)` — `role_permissions` 행 수) | C4 이름 정정. **단 `CREATE_PROJECT` 는 P3 CHECK 상 `role_permissions` 에 못 들어가고 `global_permission_grants` 로 가므로, 이 가드가 실제로 깨지는지는 UNKNOWN** — plan 단계에서 실측 |
| B5 | *"`V407:6` = `archived_at` 단독 선례 (status enum 없음)"* | **`notifications.status` 는 실재**한다 (`V402:17` TEXT NOT NULL, PENDING/SENT/FAILED) | 논지(아카이브 축을 timestamp 단독으로 모델링한 선례)는 **생존**. "status enum 없음" 표현만 거짓 → §5.3 문구 정정 |
| B6 | `seed-project.sql:57-70` | 실제 **58-67** (파일 EOF=67). `:57` 은 주석 | 라인 정정 |
| B7 | `V500:6-9` / `V503:7-9` | `project_key` 컬럼은 **양쪽 다 `:11`**. 인용 범위가 주석+`CREATE TABLE (` 만 덮고 컬럼을 놓침 | 라인 정정 |

### 주의 4건 (BLOCKER 아님, plan 단계 입력)

| # | 내용 |
|---|---|
| B8 | **`hasGlobalPermission` default = `isSystemAdmin` 은 prod 어댑터가 override 를 잊으면 D7 을 조용히 뒤집는다.** `CREATE_PROJECT` 가 SYSTEM_ADMIN 전용으로 되돌아간다 — **Maxi 가 기각한 바로 그 안**이다. fail-closed 라 사고는 안 나지만 **아무도 모르게 기능이 사라진다.** → DoD 필수. *"`CREATE_PROJECT` grant 를 가진 **비-SYSTEM_ADMIN** 이 프로젝트를 만들 수 있다"* (`@ActiveProfiles("prod")`) |
| B9 | **`ProjectLookup` 은 `archived_at` 을 보지 않는다** (`ProjectLookupRepository.kt:34-42`·`:51-59` 는 `DELETED_AT.isNull` 만). PJ2-3("아카이브도 조회된다")과는 정합하나, **S6/S9 잠금을 `ProjectLookup` 이 해줄 거라 가정하면 안 된다** — 호출자가 건다 |
| B10 | **`BROWSE` 는 의미 재사용이다.** `IssuePermission.BROWSE` → `"BROWSE_PROJECT"` 매핑(`IdentityAccessIssuePermissionResolver:174`)이나 계약 KDoc 은 **이슈 목록**용이다. §4.1 이 `GET /projects/{k}` 에 쓰려면 `IssueScope.Project` 경유 제약이 따라온다. enum 8종 = `BROWSE`·`VIEW`·`CREATE`·`UPDATE`·`TRANSITION`·`SOFT_DELETE`·`SET_SECURITY`·`HARD_DELETE` |
| B11 | **`project_permission_scheme` 는 시드 행이 0개** — 현재 모든 프로젝트가 `is_default` fallback 경로로만 판정된다(`JdbcPermissionSchemeRepository.kt:65-80` `COALESCE`). FR-PJ-01 이 스킴을 배정하지 않아도 되는 근거가 코드로 확인됨 |

### 확증된 것 (반증 시도 후 살아남음)

- **phantom 0건.** spec 이 인용한 21개 citation · 10개 심볼 전부 실존. 지어낸 이름 없음
- **R6-B 논지 코드 확증.** `workflows(id)` 를 참조하는 FK 전수 — **RESTRICT 는 `V201:84` 매핑 하나뿐**, `V200:22`·`:45`(states/transitions)는 CASCADE. 즉 `YamlSeedService` KDoc 의 CASCADE 전제는 states/transitions 엔 참이고 **매핑에만 거짓** — §2.5-B 그대로
- **P1 확증.** `IdentityAccessIssuePermissionResolver:77-81` 이 비멤버를 `return false` 로 즉시 거부. `SystemPermissionResolver` 가 **생성자에 주입조차 안 돼 있어** 우회 분기를 만들 수단이 없다 (KDoc 보다 강한 구조적 근거). **단 `@Profile("prod")` 한정** — non-prod 는 `AlwaysAllowIssuePermissionResolver` → **C1 이 필수인 이유가 이중으로 확인됨**
- **P3/P4 확증.** `V008:25` `CHECK (role IN ('PROJECT_ADMIN', 'MEMBER'))` 라인까지 정확. `project_permission_scheme.project_id` PK 확인 → "생성 시점 평가 불가" 성립
- **D7 근거 확증.** `CREATE_PROJECT` 는 Kotlin·SQL 통틀어 **0건** (문서에만 존재) — 신설 충돌 없음

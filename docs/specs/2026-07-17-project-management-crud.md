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
> **단 두 행은 서로 다른 BC 에 산다** — `projects`=issue-tracking, `project_memberships`=identity-access. `ProjectMembershipWritePort` 경유(§4.4).

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
| PJ4-3 | 아카이브 프로젝트는 **이번 범위의 쓰기를 거부**(409)하되 **읽기는 허용**한다 (S6). 범위는 D12 |
| PJ4-4 | **이슈 쓰기(생성·수정·전이)** 잠금은 `IssueApplicationService` **초크포인트 1곳**에 건다 — automation·Import·이슈 REST 가 전부 경유한다 (D9 확증). **단 이슈 하위리소스 쓰기 8종**(Attachment·Worklog·Link·Parent·Epic·Watcher·Move·Comment)**은 이 초크포인트를 경유하지 않는다**(PR-4 리뷰 실측 BLOCKER-1 — `IssueAttachmentService`·`WorklogService`·`LinkApplicationService`·`IssueParentService`·`IssueEpicService`·`IssueWatcherService`·`IssueMoveService`·`CommentApplicationService` 모두 `IssueApplicationService` 참조 0건). issue-tracking **자기 소유**라 D12 cross-BC 이연 근거가 성립하지 않아, PR-4 에서 각 서비스 진입점에 `ProjectArchiveGuard.checkByIssue` 를 **개별** 적용해 잠갔다(범위 확대, D-SUBRESOURCE) |
| PJ4-7 | **issue-tracking 자체 프로젝트 스코프 쓰기 17곳**에 각각 잠금을 건다 — `Version`(5) · `Component`(4) · `CustomField`(3) · `IssueTemplate`(3) · `ProjectLead`(1) · `ProjectRequire2fa`(1). 이들은 `IssueApplicationService` 를 **경유하지 않는다**(참조 0건 실측) |
| PJ4-8 | **cross-BC 쓰기는 이번 범위 밖** (D12 — 후속 FR). 경로 기준 12곳(identity-access `ProjectMember` 3 · `ProjectSecurityScheme` 2 · `FieldPermission` 2 / automation `AutomationRule` 4 / project-workflow `ProjectWorkflowScheme` 1) **+ 경로 밖 ~18곳**(agile-planning `Board`·`Sprint`·`BoardQuickFilter` / slack `ChannelMapping` / automation `replay`) = **30곳 안팎**. **이 목록은 완전하지 않다**(§2.4-B·§2.4-C) — 후속 FR 이 **5중 교차** 열거로 다시 만든다. **PR 본문·KDoc 에 "미잠금"과 "목록 불완전"을 함께 명시**한다 |
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
| NFR-3 | 아카이브 잠금 게이트는 **D12 범위(이슈 쓰기 + issue-tracking 17곳 + 이슈 하위리소스 쓰기 8종)의** 쓰기 경로를 덮는다. 그 범위 안에서 미강제 지점 **0**. **범위 밖 12곳은 "아직 안 막힘"이 의도된 상태**이며 §2.4 PJ4-8 이 명시한다 — 이 구분을 흐리면 후속 FR 이 구멍을 못 찾는다 |
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
>
> 🛑 **Phase B B8 — 이 default 는 D7 을 조용히 뒤집을 수 있다.** prod 어댑터가 override 를 **잊으면** `hasGlobalPermission` = `isSystemAdmin` 이 되어
> `CREATE_PROJECT` 가 **SYSTEM_ADMIN 전용**으로 되돌아간다 — **Maxi 가 D7 에서 기각한 바로 그 안**이다. fail-closed 라 사고는 안 나지만 **아무도 모르게 기능이 사라진다.**
> → **DoD-9 필수** — *"`CREATE_PROJECT` grant 를 가진 **비-SYSTEM_ADMIN** 이 프로젝트를 만들 수 있다"* (`@ActiveProfiles("prod")`).
> 이 테스트가 없으면 override 누락이 **초록불로 통과**한다.

### 4.4. 포트 — cross-BC 멤버십 쓰기 (**신규 · Phase B B2**)

**I1(생성 트랜잭션 2행)이 BC 경계를 넘는다.** `projects` = issue-tracking(`V001:10`), `project_memberships` = identity-access(`V007`).
issue-tracking 이 identity-access 테이블에 직접 쓰면 BC 격리 위반이다(`CLAUDE.md §핵심 패턴`).

**기존 포트로는 안 된다.** `shared-kernel/membership/ProjectMembershipPort.kt` 는 `projectKeysOf(userId): Set<String>` **하나뿐인 읽기 포트**다.

→ **신규 포트 필요.** 선례는 `IssueMutationPort`(automation → issue-tracking) — 포트를 shared-kernel 에 두고 소유 BC 가 어댑터를 제공해 남의 BC 에 쓴다.

```kotlin
// shared-kernel/membership — 신규 인터페이스
interface ProjectMembershipWritePort {
    /**
     * 프로젝트 생성 시 생성자를 멤버로 등록한다. 호출자 트랜잭션에 참여한다.
     *
     * @param role 'PROJECT_ADMIN' 또는 'MEMBER'. 원시 String — 아래 §BC 경계 참조.
     */
    fun addMember(projectId: UUID, userId: UUID, role: String)
}
```

| 설계 결정 | 내용 |
|---|---|
| **별도 포트 (기존 확장 아님)** | 기존 `ProjectMembershipPort` 에 쓰기를 얹으면 **읽기 전용 소비자(search-export-import)가 쓰기 메서드를 물려받는다**. 읽기/쓰기 분리가 그 포트의 fail-closed 설계와 정합 |
| **신규 인터페이스이므로 default/abstract 논쟁이 없다** | [[interface-extension-default-method]] 는 **기존 공유 인터페이스에 메서드를 추가할 때** fail-safe default 를 요구하는 규칙이다. **여기엔 적용되지 않는다** — 신규 인터페이스라 깨질 기존 구현체가 0개다. 구현은 identity-access 어댑터 1개를 새로 쓴다 |
| **Bean 미등록 = 부팅 실패 (의도)** | `ProjectMembershipPort` KDoc 의 *"default 구현 금지 (fail-closed) — Bean 이 등록되지 않으면 부팅 자체가 실패하도록 설계된 안전망"* 정신을 신규 포트도 따른다. issue-tracking 이 이 포트를 주입받는 순간 **prod 조립에서 어댑터 부재가 즉시 드러난다** ([[new-crossbc-dep-openapi-mockbean-regression]] — 소비 BC 의 full-boot 테스트에 `@MockBean` 동반 필요) |
| **`role: String` 은 취향이 아니라 강제 — 단 차단자는 2단이다 (2회차 N4 정정)** | `ProjectRole` 은 **identity-access 도메인 타입**(`identity/project/ProjectRole.kt:3` `package com.atlas.bts.identity.project`)이다. **1차 차단 = Gradle 클래스패스** — `shared-kernel/build.gradle.kts` 에 `project(":modules:identity-access")` 의존이 **없다**(project 의존 0건). 시그니처에 쓰면 **Kotlin 컴파일이 unresolved reference 로 먼저 죽는다**. **2차 차단 = ArchUnit** — `SharedKernelBoundaryArchTest.kt:131-146` 이 `com.bts.shared.membership..` 의 `com.atlas.bts.identity..` 의존을 금지한다(룰 실존, `ProjectRole` 이 정확히 걸림). 단 ArchUnit 은 **이미 컴파일된 클래스**를 읽으므로(`:34-37` `ClassFileImporter`) 1차가 먼저 죽으면 실행 지점에 도달조차 못 한다. → **누군가 "의존만 추가하면 되네"로 1차를 우회하면 그때 2차가 잡는다.** 이 2단 구조를 알아야 우회 시도를 막을 수 있다 |
| **트랜잭션 — 어댑터에 트랜잭션 애노테이션을 붙이지 않는다 (2회차 N5 정정)** | 9 BC 가 **단일 DataSource** 로 조립되므로(`FlywayAssemblyConfig.kt:47` `assemblyFlywayMigrator(dataSource: DataSource)`) 어댑터가 **트랜잭션 경계를 선언하지 않으면** Spring 이 바인딩한 커넥션을 그대로 써서 **호출자 트랜잭션에 자동 참여**한다. I1(원자성) 성립. **필수 조건** — 어댑터는 tx-aware 빈(주입받은 `DSLContext`/`JdbcTemplate`)만 쓴다. 손수 만든 `DSLContext` 는 트랜잭션을 우회해 **가짜 커밋**을 낸다 ([[transaction-aware-dslcontext-rollback-test-gap]]) |
| **PR 배치** | 포트+어댑터는 **identity-access 산출물**이므로 **PR-1**(FR-PM-10)에 넣는다 → PR-2 는 소비만 한다 (§9) |

> 🛑 **2회차 N5 — 개정 전 이 자리에 인용했던 "선례 `AutomationIssueMutationAdapter`" 는 정반대 선례였다. 삭제한다.**
>
> 그 어댑터에는 **`@Transactional` 이 0건**이다. `TransactionTemplate` 을 써서 **일부러 호출자 트랜잭션에 참여하지 않도록** 설계됐다.
> KDoc `:49` — *"### 트랜잭션 경계 — `@Transactional` 대신 `TransactionTemplate` (**OCC 재시도 격리**)"*, `:60` — *"호출 시점에 **앙비언트(ambient) Spring 트랜잭션이 없다는 전제** 하에"*.
>
> **I1 은 정의상 앙비언트 트랜잭션이 있는 자리다** — 선례의 전제가 성립하지 않는 곳에 선례를 인용했다.
> **그리고 그 패턴을 복사하면 사고가 난다.** `TransactionTemplate` 기본 propagation 은 `REQUIRED` 라 호출자 트랜잭션에 **참여해버리고**,
> 그 어댑터 KDoc `:52-57` 이 경고한 바로 그 트랩이 되살아난다 — *"`isGlobalRollbackOnParticipationFailure=true` 에 의해 첫 시도의 OCC 예외가 물리 트랜잭션 전체를 rollback-only 로 오염 → 재시도가 성공해도 `UnexpectedRollbackException`"*.
> 관련 [[transaction-self-invocation-requires-new]] · [[workflowstatecatalog-mandatory-rollback-poison]].
>
> **"cross-BC 쓰기를 호출자 트랜잭션 안에서 하는" 기존 선례는 이 저장소에 없다.** 따라서 위 표의 트랜잭션 규칙은 **선례 인용이 아니라 신규 요구사항**이며, plan 단계에서 **롤백 통합 테스트로 실증**해야 한다 — 프로젝트 생성 도중 예외를 주입해 `projects` · `project_memberships` **둘 다** 롤백되는지 확인한다(둘 중 하나만 남으면 I1 위반).

> **기존 설계가 이미 cross-BC 를 인정하고 있다.** `identity V007:5` 주석 — *"`project_id` UUID NOT NULL, **cross-BC(issue-tracking projects) 참조, FK 없음** (ADR D2)"*.
> 즉 `project_memberships` 는 처음부터 남의 BC 프로젝트를 UUID 로 느슨하게 가리키도록 설계됐다. 이 포트는 그 설계와 정합하며, **B2 는 "설계 위반"이 아니라 "plan 이 경계의 존재를 못 본 것"** 이다.
> 부수 확인 — `identity V007:3-11` DDL 본문에 `deleted_at` **부재**(hard delete, ADR D6) → 아카이브가 멤버십에 미치는 영향 없음.
> **인용 표기 주의** — `V007` 은 **두 BC 에 각각 있다**(`issue-tracking/V007__issue_assignee.sql` · `identity-access/V007__project_memberships.sql`). BC 별 독립 V 네임스페이스(§5.2)이므로 **반드시 BC 접두사와 함께** 인용한다.

> **⚠️ 이 포트는 새 쓰기 경로다.** `ProjectMembershipPort` KDoc 의 *"이 포트 외부에서 별도의 프로젝트 멤버십 **조회** 경로를 만드는 것을 금지한다"* 는 **읽기** 규칙이라 위반이 아니다.
> 다만 같은 정신에서 **쓰기도 이 포트로만** 한다는 규칙을 신규 포트 KDoc 에 명시한다.

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
| EC-4 | 아카이브 프로젝트에 automation 시간 트리거 발화 | 조기 skip. 409 실패 노이즈 금지 (D9 잔여). **automation 은 cross-BC → D12 후속 FR 범위.** `next_fire_at` 전진 여부는 §2.4-C UNKNOWN — 후속에서 확정 |
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
| **C10** | 🛑 **I1 롤백 테스트를 issue-tracking 표준 테스트 베이스 위에 얹으면 거짓으로 실패한다 (3회차 신설).** `IssueTestcontainersBase.kt:112` 가 `DSL.using(dataSource, SQLDialect.POSTGRES)` — **tx-aware 가 아니다**. 동형 10곳 이상(`CycleTimeIntegrationTest:223` · `BulkOperationIntegrationTest:138` · `CustomFieldIntegrationTest:143` …). tx-aware 로 감싼 것은 **`IssueImportAdapterTest.kt:135` 단 1곳**이고, `:126-130` 이 함정을 자인한다 — *"createIssue 의 INSERT 가 즉시 커밋돼버려 이후 `setRollbackOnly()` 가 무력화된다 … **다른 기존 통합 테스트는 실패 지점이 항상 insert 이전(validate)이라 이 gap 을 드러낸 적이 없었다**"*. → **DoD-11 컨텍스트를 `DSL.using(TransactionAwareDataSourceProxy(dataSource), ...)` 로 명시 고정**한다(선례 `IssueImportAdapterTest.kt:135` · `AgilePlanningTestcontainersConfig.kt:121`). 안 하면 `projects` INSERT 가 tx 밖에서 자동 커밋돼 *"멤버십만 롤백, projects 잔존"* 이라는 **거짓 red** 가 뜨고, plan 이 원인을 포트 설계로 오진한다. ([[transaction-aware-dslcontext-rollback-test-gap]] — **main 코드는 무관**하다. `DSL.using` main 히트 **0건**으로 전부 주입된 tx-aware 빈이다) |

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
| **DoD-9** | **`CREATE_PROJECT` grant 를 가진 비-SYSTEM_ADMIN 이 프로젝트를 만들 수 있다** (`@ActiveProfiles("prod")`) | **B8 — 이게 없으면 prod 어댑터 override 누락이 초록불로 통과하고 D7 이 조용히 뒤집힌다** |
| **DoD-10** | 아카이브된 프로젝트에서 **issue-tracking 프로젝트 스코프 쓰기 각각**이 409 를 낸다 | D12 범위. **개수(17)를 물려받지 말고 §2.4-B 5중 교차 열거로 직접 만든 목록**에 대해 개별 테스트 |
| **DoD-11** | **프로젝트 생성 도중 예외 주입 시 `projects` · `project_memberships` 가 둘 다 롤백된다** | I1 원자성 실증 (§4.4). cross-BC 쓰기가 호출자 tx 에 참여함을 증명하는 유일한 방법 — 선례가 없으므로(§4.4 N5) 반드시 실증한다. **C10 필수** — tx-aware 컨텍스트로 고정하지 않으면 거짓 red |

> **DoD-5 의 "0" 은 스펙이 세어준 숫자가 아니다.** [[spec-stated-count-becomes-blindfold]] — 개수는 패턴 grep 으로 직접 재검증한다.
>
> **DoD-4 deviation (PR-4, D-TESTPROFILE, CONCERN-1 Maxi 확정).** `@ActiveProfiles("prod")` 원칙은 `CREATE_PROJECT`/`SystemPermission` 경로(PR-1/2)에는 그대로 적용된다. 그러나 PR-4 의 PROJECT_ADMIN 게이트(`ComponentPermissionResolver`)는 실 구현체가 identity-access 소유라 issue-tracking 테스트 클래스패스에 prod 실 grant 가 없어 `@ActiveProfiles("prod")` 가 부팅 실패 또는 vacuous 통과로 이어진다 — 선례 `Require2faTestPermissionConfig` 동형으로 `@ActiveProfiles("test")` + 제어형 fake `ComponentPermissionResolver`(`ArchiveTestPermissionConfig`)로 대체했다.

---

## 9. PR 분할 설계

### 9.1. 분할 불변식 (넘으면 안 되는 선)

| # | 불변식 | 근거 |
|---|---|---|
| **I1** | `projects` 삽입과 `project_memberships` 삽입은 **같은 트랜잭션 · 같은 PR**. 단 **BC 경계를 넘으므로 `ProjectMembershipWritePort` 경유**(§4.4 — Phase B B2) | 분리하면 생성자조차 못 들어가는 프로젝트 (§2.2). 직접 쓰면 BC 격리 위반 |
| **I2** | **방어가 경로보다 먼저다** — `CREATE_PROJECT` 게이트 없이 `POST /projects` 를 열지 않는다 | FR-AT-07 C-1 이 *"전략 오조준 — 방어 없이 경로를 먼저 열고 방어는 2 PR 뒤"* 로 감점된 실수 |
| **I3** | R6 백필과 R6-B 방어는 **같은 PR** | 백필만 넣으면 부팅 실패를 심는 것 (§2.5-B) |
| **I4** | R6 는 FR-PJ-01 과 같은 PR (D10 — Maxi 확정) | 별도 선행 PR 기각 |

### 9.2. 제안 분할 (**Phase B 개정** — D12 + B2 반영)

| PR | 내용 | BC | 마이그레이션 |
|---|---|---|---|
| **PR-1** | **FR-PM-10 백엔드 + 멤버십 쓰기 포트** — `global_permission_grants` + `CREATE_PROJECT` 코드 + `SystemPermissionResolver` default 확장(§4.3) + grant/revoke API + **`ProjectMembershipWritePort` 포트·어댑터(§4.4)** | identity-access **(1개)** | identity **V036** |
| **PR-2** | **R6 + R6-B + FR-PJ-01 백엔드** — 백필 러너 + 재시드 생존 + 프로젝트 생성(PR-1 의 포트를 **소비만**) | project-workflow + issue-tracking **(2개 — D10 예외)** | workflow **V203** (전략 (b) 선택 시) |
| **PR-3** | **FR-PJ-02/03 백엔드** — 목록·조회·설정(`name`) | issue-tracking | 없음 |
| **PR-4** | **FR-PJ-04 백엔드** — `archived_at` + 이슈 초크포인트 + **issue-tracking 17곳 잠금**(D12) | issue-tracking | issue **V037** |
| **PR-5** | **D6 프론트 UI** — 생성·목록·설정·아카이브 화면 (design-shotgun 은 여기서) | apps/web | 없음 |
| **PR-6** | **FR-PM-10 관리 화면 + D7 E2E** | apps/web | 없음 |

**I2 가 PR-1 을 앞에 세운다.** FR-PM-10 을 먼저 넣으면 **방어만 들어가고 경로는 아직 안 열린다** — C-1 실수의 정반대다.
그리고 PR-1 은 **BC 1개**라 `DEVELOPMENT.md §4`("한 PR = 한 BC + 한 plan") 관례도 지킨다.

**B2 가 포트를 PR-1 로 밀어 넣는다.** `ProjectMembershipWritePort` 의 **어댑터는 identity-access 산출물**이다(그 BC 가 `project_memberships` 를 소유).
PR-1 에 두면 PR-2 는 **소비만** 하므로 PR-2 의 BC 수가 **2개로 유지**된다(D10 예외 범위 안). 포트를 PR-2 에 두면 **3 BC** 가 되어 D10 이 고지한 범위를 넘는다.

**PR-2 만 BC 2개다.** D10 이 명시 고지 후 확정한 유일한 예외이므로 PR 본문·`/bts-codereview` 에 D10 근거를 첨부한다.
`bc:<context>` 라벨이 단수 전제라 표기 방식은 plan 단계에서 정한다.

**마이그레이션 배치 정정 (Phase B).** 개정 전 §9 는 PR-2 에 `V037`, PR-4 에 `V038` 을 적었으나 **둘 다 틀렸다**.
`projects` 는 이미 존재하므로 **FR-PJ-01 에는 issue-tracking 마이그레이션이 필요 없고**, `archived_at` 은 FR-PJ-04(PR-4) 것이므로 **issue-tracking 첫 마이그레이션은 PR-4 의 V037** 이다. §5.2 와 이제 일치한다.

> **PR-4 를 PR-3 에서 분리한 이유** — 아카이브는 D12 범위 안에서도 **18곳**(이슈 초크포인트 1 + issue-tracking 17)을 건드린다. 목록/설정과 섞으면 리뷰 단위가 무너진다.

### 9.3. 이번 범위 밖 (후속 FR — ID 미부여)

**아카이브 잠금 cross-BC 확장** (D12). `ProjectLifecyclePort` 신설 + **어댑터 6종** — identity-access · automation · project-workflow · **agile-planning · slack-integration**.

| BC | 알려진 쓰기 | 스코프 획득 |
|---|---|---|
| identity-access | `ProjectMember` 3 · `ProjectSecurityScheme` 2 · `FieldPermission` 2 | (a) 경로 |
| automation | `AutomationRule` 4 · **`AutomationExecution.replay` 1** · **`AutomationScheduleWorker.updateNextFireAt`** · **`AutomationEventWorker` → `rule_executions`** | (a) 경로 / **클래스 레벨 `@RequestMapping` 없음** / **(5) HTTP 밖 `@Scheduled`·pgmq — 초크포인트도 미경유** |
| project-workflow | `ProjectWorkflowScheme` 1 | (a) 경로 |
| **agile-planning** | `Board` 4 · `Sprint` 7 · `BoardQuickFilter` 3 | **(b) 본문 `projectKey` / (c) 파생** |
| **slack-integration** | `SlackChannelMapping` 3 | **(b) 본문 `projectKey`** |

> 🛑 **이 표는 완전하지 않다 — 그게 이 절의 요점이다.** 개정 전 §9.3 은 *"12곳"* 이라 적었고 그 숫자는 **경로 grep 이 볼 수 있는 것만** 센 값이었다(2회차 N3).
> 위 표는 §2.4-B 교차 중 일부만 돌린 **중간 결과**이며 **~30곳** 규모다. **후속 FR 은 이 표를 물려받지 말고 §2.4-B 5중 교차를 처음부터 다시 돌린다.**
> 숫자를 물려받는 순간 그 숫자가 눈가리개가 된다 — 이 스펙이 그 실수를 **두 번** 했다(1회차 B1, 2회차 N3).

**전역(non-project-scoped) issue-tracking 쓰기도 이번 범위 밖** (PR-4 DoD-5 전수 열거에서 발견, Maxi 확정 이연). `IssueTypeApplicationService.delete(reassignTo)` → `IssueTypeRepository.reassignIssues(from, to)` 는 `UPDATE issues SET type_id=? WHERE type_id=?` 를 **프로젝트 필터 없이** 실행해 여러 프로젝트(아카이브 포함)의 이슈를 동시에 건드린다. 전역 이슈타입 삭제라는 **관리 작업**이라 단일 `projectId`/`issueKey` 게이트 지점이 없어 `ProjectArchiveGuard.check`/`checkByIssue` 패턴이 부적합하다. 아카이브 freeze(S6)를 전역 op 에 어떻게 적용할지는 cross-BC 확장과 같은 후속 FR 결정 사항(옵션: 아카이브 이슈 포함 시 삭제 차단 vs 아카이브 이슈 스킵). PR-4 는 프로젝트 스코프 쓰기만 잠근다.

> **FR ID 를 지금 부여하지 않는 이유** — 전수 동기화 8종(`CLAUDE.md:29-36`)이 이번 PR 로 딸려온다. 착수 시점에 `/bts` 로 신설한다. **이번 작업 FR 총수 128 유지.**

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
| 7 | D9 *"비용 거의 없음 · 초크포인트 1곳 · `ProjectLifecyclePort` 불필요"* | 경로 기준 쓰기 **29개 / 4 BC**, `IssueApplicationService` 참조 0건. **경로 밖 ~18곳 추가** | **D12 로 범위 한정** (B1) + **§2.4-B 5중 교차 열거** (N3·N-1) |
| 8 | R2 *"생성 = 2행 한 트랜잭션"* | 2행이 **서로 다른 BC** (`projects`=issue-tracking / `project_memberships`=identity-access) | **§4.4 신규 포트** (Phase B B2) |
| 9 | D8 *"술어 변경 → identity-access cross-BC 영향"* | 그 어댑터는 **공유 포트 구현**, 실소비자는 **search-export-import**(FR-SR-03) | **`projectKeysOf` 불변** (Phase B B3) |

### 함께 고칠 drift 3건 (plan §도메인 정리)

| drift | 실제 | 처리 |
|---|---|---|
| `docs/plan/fr-index.md:5` `(122개 전수)` | **123** | 이번 PR 통합 (FR 추가로 어차피 건드림) |
| `DATA.md:45` *"`SoftDeleteFilter` 래퍼"* | **허구** — `.kt` grep 히트 0 | 이번 PR 통합 (아카이브 술어 설계가 오독하면 보안 버그) |
| `docs/plan/product/issue-tracking.md:171` `(7개)` | **8** | 분리 (무관한 섹션 — 글로벌 CLAUDE.md §3) |

> `verify-master-plan.sh` **는 위 drift 2건을 안고도 종료 0 으로 통과한다.** 통과는 카운트 정합의 충분조건이 아니다.

---

## Brainstorming Check

✅ **3라운드 종료 (2026-07-17).** 라운드마다 BLOCKER 가 나왔고 **3라운드 모두 "직전 개정이 새로 넣은 결함"** 을 잡았다.
`/bts-spec` 은 A↔B 를 최대 3회로 제한한다. **4회차에 갈 게 아니라 남은 미확정을 plan 단계 입력으로 넘긴다** — 아래 §미확정 이월.

### 3회차 (2026-07-17) — 2회차 개정본 적대적 검토. **반박 성공 1 · 반박 실패 3 · 신규 사각지대 1**

> **★ 실패 양식이 세 번째로 반복됐다. 이번엔 한 단계 더 깊다.**
> 1회차 — *plan 이 세어준 범위*가 눈가리개.
> 2회차 — *내가 고른 grep 패턴*이 눈가리개. 처방 = **4중 교차 열거**.
> 3회차 — **그 처방 자체가 "컨트롤러가 있고 타입 있는 DTO 를 받는다"를 전제**했다. 진단은 맞았는데 **처방이 진단을 못 따라잡았다.**
> → *"개수를 재검증"*(1회차) → *"개수를 만든 정의를 재검증"*(2회차) → **"정의가 전제한 코드 모양을 재검증"**(3회차).

| # | 지적 | 조치 |
|---|---|---|
| 🛑 **N-1** | **`@Scheduled` 워커를 4중 교차가 구조적으로 못 본다.** `AutomationScheduleWorker.kt:83-84·101-102` `@Scheduled` → `repository.updateNextFireAt(rule.id, next)` 는 `project_key` 스코프 테이블 쓰기인데 (1)경로 ✗ (2)DTO ✗ (3)`IssueScope` ✗ (4)파생 ✗ **초크포인트도 미경유** ✗. 동형 `AutomationEventWorker.kt:68-69`(pgmq) → `:140` → `rule_executions`. **결정적 증거 — §6 EC-4 가 이 대상을 이미 요구하는데 스펙의 열거 방법론이 산출하지 못했다** | **§2.4-C 신설 + 5번째 축**("HTTP 밖 — `@Scheduled`·pgmq consumer·raw-body"). §9.3 automation 행에 워커 2종 추가 |
| 🛑 **N-2** | **request DTO 가 구조적으로 존재할 수 없는 쓰기.** `SlackCommandsController.kt:74-80` — KDoc `:24` *"원문 바디 보존 — 서명 대상이므로 `@RequestParam` 병용 금지"*. `projectKey` 는 `:101` 수동 form-decode 문자열. (완화 — `IssueImportPort` → 초크포인트라 **열거 구멍이지 NFR-3 구멍은 아니다**) | 5번째 축에 포함 |
| 🛑 **N-3** | **(3) 이 `IssueScope.Project` 만 봐서 권한 타입 8종을 놓친다.** `shared-kernel/permission/` 의 프로젝트 스코프 포트 8종은 어느 것도 `IssueScope` 를 안 쓴다 — `AutomationPermissionResolver:62 hasManageAutomation(actorId, projectKey)` 등. `Version`·`Component`·`CustomField` 는 **우연히** (1) 경로가 구제했을 뿐 | §2.4-B (3) 을 **8종 포트 전수 grep** 으로 확장 |
| ⚠️ **신규 사각지대** | **§4.4 가 요구한 롤백 테스트를 표준 테스트 베이스로 짜면 거짓 red 가 뜬다.** `IssueTestcontainersBase.kt:112` `DSL.using(dataSource, ...)` — tx-aware 아님(동형 10곳+). tx-aware 는 `IssueImportAdapterTest.kt:135` **단 1곳**이고 `:126-130` 이 자인 — *"다른 기존 통합 테스트는 실패 지점이 항상 insert 이전(validate)이라 **이 gap 을 드러낸 적이 없었다**"* | **C10 신설** + DoD-11 에 C10 필수 명시 |

**반박 실패 — 주장 유지 3건.**
- 🟢 **§4.4 트랜잭션 규칙 성립.** identity-access = `NamedParameterJdbcTemplate`(`JdbcProjectMembershipRepository.kt:150-155`, jOOQ 의존 없음) / issue-tracking = jOOQ `DSLContext`. **서로 다르지만 같은 커넥션에 바인딩된다** — main 코드 `DSL.using` **0건**(전부 주입 = `JooqAutoConfiguration` 의 `TransactionAwareDataSourceProxy` 빈), `fun dataSource`/`DataSource(` **0건**(Boot 오토컨피그 단일 빈), `NamedParameterJdbcTemplate` → `DataSourceUtils.getConnection()`. **I1 성립.** `@Transactional` 선례는 **클래스 레벨**(`IssueApplicationService.kt:125-127`) — Version·Component·ProjectLead·Require2fa 전부 동형
- 🟢 **§9.3 카운트 5/5 정확** — `Board` 4 · `Sprint` 7 · `BoardQuickFilter` 3 · `SlackChannelMapping` 3 · `replay` 1
- 🟢 **shared-kernel `project` 의존 0건 확증** — `build.gradle.kts:45-87` 전문에 `project(":modules:...")` 없음. N4 정정("1차 차단 = Gradle 클래스패스")은 참
- 🟢 **`WorkflowSchemeScope` 는 반례 아님** — `WorkflowSchemeController` 쓰기 5건은 전부 `WorkflowSchemeScope.Global`. §9.3 `ProjectWorkflowScheme 1` 배정 정확

### 미확정 이월 (plan 단계 입력 — 4회차 대신)

| # | 미확정 | 성격 |
|---|---|---|
| 1 | **프로젝트 스코프 쓰기 전수 목록** | 스펙이 확정하지 못함을 **산출물로 명시**(§2.4-B). plan 이 5중 교차로 직접 열거 |
| 2 | **§4.2** — FR-PJ-03 이 `lead`(FR-CM-04) · `require_2fa` 와 겹침 | 3안 제시, (a) 권장 |
| 3 | **EC-7** — `projects` 에 OCC `version` 컬럼 부재 | OCC 도입 vs last-write-wins |
| 4 | **EC-2** — 아카이브 멱등 200 vs 409 | |
| 5 | **R6-B 재시드 생존 전략 3안** | (a) 권장 (§2.5-B) |
| 6 | **B10** — `BROWSE` 의미 재사용 | `IssueScope.Project` 경유 제약 |
| 7 | **§2.4-C UNKNOWN** — `updateNextFireAt` 이 아카이브 시 막혀야 하나 | **D12 후속 FR 범위** |

### 2회차 (2026-07-17) — 1회차 개정본 적대적 검토. **N1~N5 · 반박 실패 3건**

> **★ 이 스펙의 핵심 교훈. 1회차와 2회차의 실패 양식이 같다 — "숫자는 맞는데 숫자를 만든 정의가 틀렸다."**
> 1회차는 *plan 이 세어준 범위*(D9 "초크포인트 1곳")를 물려받아 그 밖을 안 셌고,
> 2회차는 **내가 고른 grep 패턴**(`/api/v1/projects` 경로 prefix)이 눈가리개였다.
> 29·17·12 는 독립 재계수에서 **완벽히 재현**됐다 — 그래서 더 위험했다. **맞는 숫자가 틀린 정의를 가려준다.**
> 그리고 **BLOCKER 를 고치며 새로 넣은 §4.4 의 근거 2개가 둘 다 틀렸다**(N4·N5) — *"방어를 추가하는 행위 자체가 새 사각지대를 만든다"* 의 재현.

| # | 지적 | 조치 |
|---|---|---|
| 🛑 **N1** | *"29개 / **5 BC**"* — **표 자신이 4행**이다. agile-planning 이 5번째 후보이나 `BacklogController:56` · `SprintVelocityController:61` 둘 다 `@GetMapping` 단독으로 **쓰기 0** | **4 BC** 로 정정 |
| 🛑 **N2** | **클래스 레벨 `@RequestMapping` 이 없는 컨트롤러를 grep 이 구조적으로 못 본다.** `AutomationExecutionController:39` 가 이유를 명시 — *"prefix 가 서로 달라 class-level `@RequestMapping` 없이 메서드마다 전체 경로 명시"*. `:117` `POST /api/v1/automation/executions/{id}/replay` 는 KDoc `:110` 이 *"실제 이슈 변경 유발"* 이라 밝히는 프로젝트 스코프 쓰기. **29 가 살아남은 건 이 컨트롤러의 프로젝트 경로 엔드포인트가 우연히 GET 이라서지 방법론이 옳아서가 아니다** | §2.4-B 신설 |
| 🛑 **N3** | **"프로젝트 스코프 쓰기"를 경로로 정의해 스코프 쓰기를 대량 누락.** `BoardController:91-100` 은 `IssueScope.Project(request.projectKey)` 로 권한받는 프로젝트 스코프 쓰기인데 경로가 `/api/v1/boards` 라 0으로 세어졌다. 동류 — `Sprint` 7 · `BoardQuickFilter` 3 · `SlackChannelMapping` 3. **`SprintController:68` 은 권한 판정이 service 내부라 `IssueScope.Project` grep 으로도 안 잡힌다** | §2.4-B **4중 교차 열거** 규정. §9.3 후속 FR 을 12 → **~30곳**. PJ4-8 에 "목록 불완전" 명시 |
| 🛑 **N4** | §4.4 *"ArchUnit 이 빌드를 차단"* — **룰은 실존**(`SharedKernelBoundaryArchTest:131-146`, `ProjectRole` 정확히 걸림)하나 **1차 차단은 Gradle 클래스패스**다. `shared-kernel/build.gradle.kts` 에 identity-access 의존이 없어 **Kotlin 컴파일이 먼저 죽고**, ArchUnit 은 컴파일된 클래스를 읽으므로(`:34-37`) 실행 지점에 도달조차 못 한다. **FR-AT-07 PR-B 가 겪은 "ArchUnit 이 막는다는 주장이 거짓" 전례의 재현** | **2단 차단 구조**로 정정 — 1차 Gradle / 2차 ArchUnit(우회 시 발동) |
| 🛑 **N5** | §4.4 *"선례 — `AutomationIssueMutationAdapter`"* — **정반대 선례다.** 그 어댑터엔 `@Transactional` **0건**이고 `TransactionTemplate` 으로 **일부러 참여를 피한다**(KDoc `:49` "OCC 재시도 격리", `:60` "**앙비언트 트랜잭션이 없다는 전제**"). I1 은 정의상 앙비언트 트랜잭션이 **있는** 자리다. **더 나쁘게** — `TransactionTemplate` 기본 propagation 은 `REQUIRED` 라 복사하면 참여해버리고, `:52-57` 이 경고한 *"OCC 예외 → rollback-only 오염 → `UnexpectedRollbackException`"* 트랩이 되살아난다 | **선례 인용 삭제.** "cross-BC 쓰기를 호출자 트랜잭션 안에서" 하는 선례는 **이 저장소에 없음**을 명시. 규칙을 신규 요구사항으로 재기술(어댑터에 트랜잭션 애노테이션 금지 + tx-aware 빈 강제) + **롤백 통합 테스트로 실증** 요구 |
| ✗ | `V007` 이 **두 BC 에 각각 존재** (`issue-tracking/V007__issue_assignee.sql` · `identity-access/V007__project_memberships.sql`). BC 접두사 없이 인용 | `identity V007:5` 로 한정 |

**반박 실패 — 주장 유지 3건.**
- **마이그레이션 번호 3/3 정확** — issue-tracking 최신 `V036__pgmq_queue_automation_events.sql` → **V037** / identity-access 최신 `V035__manage_automation_permission.sql` → **V036** / project-workflow 최신 `V202__assignment_project_id_to_uuid.sql` → **V203**
- **`project_memberships` 에 `deleted_at` 부재** — `identity V007:3-11` DDL 본문으로 확인(주석 근거 아님)
- **29·17·12 및 BC 배정** — 독립 재계수에서 정확히 재현, 배정 오류 0건. **숫자는 맞다. 정의가 틀렸을 뿐이다**(N3)

### 1회차 (2026-07-17) — 적대적 검토. **BLOCKER 3건 · 정정 4건 · 주의 3건**

> **교훈. 이 스펙의 BLOCKER 3건은 전부 "plan 이 이미 조사해서 안전하다고 결론낸 지점"에서 나왔다.**
> D9 는 *"비용 거의 없음 — 초크포인트 한 곳"*, R2 는 *"2행 한 트랜잭션"*, D8 은 *"술어 변경"* 이라 적었고
> 나는 그 결론을 spec 으로 옮겨 적었다. **셋 다 증거는 참인데 결론이 과일반화였다.**
> [[spec-stated-count-becomes-blindfold]] 의 재현 — 앞 단계가 "세어준" 범위를 물려받으면 그 밖을 안 센다.

#### 🛑 B1 — D9 의 "초크포인트 한 곳" 이 프로젝트 스코프 쓰기 29개를 안 덮는다 (**Maxi 결정 필요**)

D9 원문 — *"cross-BC 쓰기 포트 2종이 전부 `IssueApplicationService` 를 통과하므로 초크포인트 한 곳 게이트로 **API·automation·Import 가 동시에 막힌다**"*.

**증거는 참이다.** `IssueMutationPort` · `IssueImportPort` 는 실제로 `IssueApplicationService` 소비자다 → **S7(automation·Import 차단)은 그대로 성립**.
**결론이 과일반화다.** "API" 는 **이슈 API 에만** 참이다.

`/api/v1/projects/{...}` 하위 쓰기 엔드포인트 **29개 / 4 BC** 실측 — **`IssueApplicationService` 참조 0건**.

| BC | 컨트롤러 | 쓰기 수 |
|---|---|---|
| issue-tracking | `Version`(5) · `Component`(4) · `CustomField`(3) · `IssueTemplate`(3) · `ProjectLead`(1) · `ProjectRequire2fa`(1) | **17** |
| identity-access | `ProjectMember`(3) · `ProjectSecurityScheme`(2) · `FieldPermission`(2) | **7** |
| automation | `AutomationRule`(4) | **4** |
| project-workflow | `ProjectWorkflowScheme`(1) | **1** |

→ **NFR-3("미강제 지점 0")은 초크포인트 1곳으로 달성 불가.**
→ D9 의 ***"`ProjectLifecyclePort` 신설 불필요, BC별 어댑터 불필요"* 는 반증됐다** — identity-access · automation · project-workflow 가 "이 프로젝트가 아카이브인가"를 물을 창구가 없다.
→ **§9 PR-4 의 비용 추정이 무너진다.** D9 의 *"비용 거의 없음"* 이 이 추정의 근거였다.

> 🛑 **2회차 정정 (N1·N2·N3) — 위 표의 숫자는 맞지만 그 숫자를 만든 정의가 틀렸다.**
> 29·17·12 는 독립 재계수에서 정확히 재현됐고 BC 배정 오류도 0건이다. **그러나 "5 BC" 는 틀렸다 — 표 자신이 4행이다(N1).** 그리고 더 중요한 것은 §2.4-B 다.

#### 2.4-B. ★ "프로젝트 스코프 쓰기"를 열거하는 단일 grep 은 존재하지 않는다 (2회차 N2·N3)

위 29 는 *"**경로**가 `/api/v1/projects` 로 시작하는 쓰기"* 다. NFR-3 이 요구하는 *"프로젝트 스코프 쓰기"* 와 **다른 집합**이다.
프로젝트 스코프를 얻는 경로가 **셋으로 갈리기 때문**이다.

| 방식 | 예 | 경로 grep 이 보나 |
|---|---|---|
| (a) 경로 `/api/v1/projects/{k}` | `Version` · `Component` · `ProjectMember` … | ✅ 보인다 (=29) |
| (b) **본문/파라미터 `projectKey`** | `SprintController`(`/api/v1/sprints`, `:79·85`) · `SlackChannelMappingController`(`/api/v1/slack/channel-mappings`, `:77`) · `BoardController`(`/api/v1/boards`, `:91-100`) | ❌ **안 보인다** |
| (c) **부모 리소스에서 파생** | `BoardQuickFilterController`(boardId → board → projectKey) | ❌ **안 보인다** |

**두 번째 grep(`IssueScope.Project` 를 쓰는 컨트롤러)도 실패한다.** `SprintController` KDoc `:68` — *"actor 추출 후 service 에 위임한다. **service 내부에서 projectKey 로 권한을 판정한다**"*. 컨트롤러에 `IssueScope.Project` 문자열이 없다.

**클래스 레벨 `@RequestMapping` 이 아예 없는 컨트롤러도 있다.** `AutomationExecutionController:39` 가 이유를 명시한다 — *"세 경로의 prefix 가 서로 달라(프로젝트 스코프/전역 혼재) class-level `@RequestMapping` 없이 **메서드마다 전체 경로를 명시**한다"*. `:117` `@PostMapping("/api/v1/automation/executions/{id}/replay")` 는 KDoc `:110` 이 *"**실제 이슈 변경 유발**"* 이라 밝히는 프로젝트 스코프 쓰기다. (완화 — replay 의 이슈 변경분은 `IssueMutationPort` → 초크포인트를 타므로 S7 이 덮는다. 다만 `rule_executions` 행 생성 자체는 안 막힌다.)

> **교훈. [[spec-stated-count-becomes-blindfold]] 가 이 스펙 안에서 재현됐다.** 1회차에서는 *"plan 이 세어준 범위"* 가 눈가리개였고,
> 2회차에서는 **내가 고른 grep 패턴 자체**가 눈가리개였다. 개수를 재검증하라는 교훈만으로는 부족하다 — **개수를 만든 정의를 재검증**해야 한다.

**→ 이 스펙은 프로젝트 스코프 쓰기의 전수 목록을 확정하지 못한다. 확정하지 못한다는 사실 자체를 산출물로 넘긴다.**
plan 단계는 **숫자를 물려받지 말고** 아래 **5중 교차 열거**로 직접 만든다.

1. 경로 — `RequestMapping`/메서드 매핑에 `/api/v1/projects` (=29). **메서드 레벨 전체 경로 명시 컨트롤러 포함** (`AutomationExecutionController:39` — 클래스 레벨 `@RequestMapping` 없음)
2. 본문 — 쓰기 매핑 함수의 request DTO 에 `projectKey`/`projectId` 필드
3. 권한 — **`IssueScope.Project(...)` 만으로는 부족하다.** `shared-kernel/permission/` 의 **프로젝트 스코프 권한 포트 8종 전부**를 grep 한다 — `AutomationPermissionResolver`(`:62` `hasManageAutomation(actorId, projectKey)`) · `SlackChannelMappingPermissionResolver:67` · `VersionPermissionResolver:47` · `ComponentPermissionResolver:46` · `CustomFieldPermissionResolver:50` · `TemplatePermissionResolver:50` · `FieldPermissionResolver:74·91` · `WorkflowSchemePermissionResolver:54`. **컨트롤러와 서비스 양쪽**에서 (`SprintController:68` 은 판정이 service 내부)
4. 파생 — 부모 리소스(board/sprint 등)를 통해 프로젝트에 귀속되는 쓰기
5. **HTTP 밖 (3회차 N-1·N-2 신설)** — `@Scheduled` 워커 · pgmq consumer · **서명 검증형 raw-body 엔드포인트**. 컨트롤러도 DTO 도 없어 1~4 가 **구조적으로** 못 본다

#### 2.4-C. ★ 5번째 축의 근거 — 열거 방법론이 스펙 자신의 요구를 산출하지 못했다 (3회차 N-1)

**§6 EC-4 는 *"아카이브 프로젝트에 automation 시간 트리거 발화 → 조기 skip"* 을 이미 요구한다.** 그런데 4중 교차는 그 대상을 **산출하지 못했다**.

`automation/worker/AutomationScheduleWorker.kt:83-84·101-102`
```kotlin
@Scheduled(fixedDelayString = "...")
fun pollAndFire() {
    enqueuer.enqueue(rule.id, rule.triggerType, ...)
    repository.updateNextFireAt(rule.id, next)   // ← automation_rules 는 project_key 로 스코프된 테이블
}
```
(1)경로 ✗ HTTP 없음 / (2)DTO ✗ 없음 / (3)`IssueScope` ✗ 없음 / (4)파생 ✗ 리소스 자체가 없음 / **초크포인트 ✗ 경유 안 함**.
동형 — `AutomationEventWorker.kt:68-69`(pgmq consumer) → `:140` `findEnabledByProjectAndTriggerType(matched.projectKey, ...)` → `rule_executions` 행 생성.

`slack/web/SlackCommandsController.kt:74-80` 은 **DTO 가 구조적으로 존재할 수 없다**. KDoc `:24` — *"원문 바디 보존 — 서명 대상이므로 `@RequestParam` 병용 금지 (★핵심 함정)"*. `projectKey` 는 `:101` 수동 form-decode 문자열에서 나온다. (완화 — `IssueImportPort` → 초크포인트를 타므로 이슈 생성은 S7 이 덮는다. **열거 방법론의 구멍이지 NFR-3 구멍은 아니다.** 반면 워커는 완화가 없다.)

> **교훈 — 2회차의 실패 양식이 3회차에도 반복됐다.** 2회차는 *"경로 grep 이 눈가리개"* 라 진단하고 4중 교차를 처방했는데,
> **그 처방이 "컨트롤러가 있고 타입 있는 DTO 를 받는다"를 전제**했다. 진단은 맞았고 처방이 진단을 못 따라잡았다.
> **"개수를 만든 정의를 재검증"(2회차)의 다음 단계는 "정의가 전제한 코드 모양을 재검증"** 이다.

> **UNKNOWN — plan/후속 FR 결정 사항.** `updateNextFireAt` 이 아카이브 시 막혀야 하는가? 룰 발화 자체는 EC-4 가 skip 을 요구하나,
> `next_fire_at` 전진은 **스케줄러 부기**라 막으면 워커가 같은 룰을 계속 재조회한다. **"룰 실행은 skip 하되 `next_fire_at` 은 전진"** 이 유력하나 코드로 결정 불가.
> **automation 은 cross-BC 라 D12 가 후속 FR 로 미룬 버킷**이므로 이번 범위 결정이 아니다 — 후속 FR 착수 시 확정한다.

**D12 의 결정 구조는 이 정정으로 흔들리지 않는다** — (b)(c) 로 새로 드러난 것들(agile-planning `Board`+`Sprint` 11 · `BoardQuickFilter` 3 · slack `ChannelMapping` 3 · automation `replay` 1)은 **전부 cross-BC** 라 D12 가 후속으로 미룬 버킷에 들어간다.
**흔들리는 것은 §9.3 후속 FR 의 크기다** — "12곳"이 아니라 **30곳 안팎**이며, 그 목록은 위 **5중 교차**로 다시 만들어야 한다.

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

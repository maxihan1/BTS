# 프로젝트 관리 기능 신설 — 생성 / 목록 / 설정 / 아카이브 + FR 추가

> slug: project-management-crud
> type: backend
> agent: backend-engineer
> 생성: 2026-07-17
> 브랜치: `backend/project-management-crud`

## Brief

### 사용자 원문

> "계획에 지라의 스페이스 개념이 빠져 있는데 추가 해줄래? 지라와 동일한 스페이스 스펙으로 잡아주고 현재 개발된 것과 조립 해줘"

### 조사로 확인된 것 — "스페이스"의 정체

지라 Cloud는 2025년에 **Project를 Space로 개명**했다. 데이터·API·JQL은 그대로다.

- Atlassian 공식 문서가 `"A Jira space is a collection of related work items"` 라고 정의하는데, 그 문서 URL은 여전히 `support.atlassian.com/jira-software-cloud/docs/what-is-a-jira-software-**project**/` 다 — 주소는 project, 내용은 space. 개명의 증거.
- 파트너사 분석: `"This is just a name change. Functionally, nothing about your data, workflows (JQLs), or permissions changes. Only the labels in the UI are the ones changing."` 2025년 12월까지 순차 롤아웃.
- 지라 Space 속성: space key / name / type(software·business) / template / management style(team·company-managed).
- **Space 위에 컨테이너는 없다.** 별개 개념인 `Atlassian Projects`(Atlassian Home의 cross-tool 상위 그릇)가 존재하나 Space와 다른 축이다.

**따라서 지라의 Space = BTS의 `projects`.** 개념은 이미 있다. 빠진 것은 **관리 기능과 그것을 추적할 FR**이다.

### 진짜 결핍 (조사 결과)

BTS는 지금 **스페이스가 하나(ATLAS)뿐이고 그마저 손으로 심은** 상태다.

| 결핍 | 근거 |
|---|---|
| 생성 API 0건 | `projects` 행 삽입은 dev 전용 시드 `data-dev.sql:16-18`의 ATLAS 1건뿐. prod는 `data-locations` 미설정이라 실행조차 안 됨 |
| 목록 API 0건 | `GET /api/v1/projects` 가 백엔드·프론트 양쪽에 없음 |
| 목록 화면 0건 | `apps/web/src/router.ts` 52개 라우트에 `/projects` 없음. 프로젝트 스코프 라우트 19개는 전부 `$projectKey`를 이미 안다는 전제 |
| 네비 진입점 0건 | `apps/web/src/routes/__root.tsx:21-30` = Header + main뿐. `Header.tsx:91-118` 메인 nav는 `/dashboards`·`/calendar` 둘뿐 |
| 이슈 생성 폼이 자유 텍스트 | `apps/web/src/routes/issues.new.tsx:39` `z.string().min(1)` — 목록 API가 없어 드롭다운 소스가 없음 |
| 추적할 FR 0개 | 123개 FR 중 프로젝트 CRUD 항목 없음. `FR-PM-01`(`02-requirements.md:192`)은 "프로젝트 행정"이나 내용은 **멤버 관리** |

**이 공백은 줄곧 인지돼 있었다.** ADR 3건이 명시적으로 이연을 기록했고, 받아줄 FR이 없어 갈 곳을 잃은 상태다.

- `docs/adr/2026-05-22-issue-key-prefix-policy.md:72` — "FR-IS-01 본 PR은 이슈 CRUD 코어에 집중하므로 프로젝트 생성 API를 제공하지 않는다."
- `docs/adr/2026-05-22-issue-key-prefix-policy.md:78` — "프로젝트 생성/수정/삭제 API는 별도 PR (Project Management) 에서 사용자 입력 검증 + 예약어 차단 + UI 도입."
- `docs/decisions/2026-06-01-project-membership-model.md:14` — "**프로젝트 생성 기능(API/서비스)은 미구현**이다."
- `docs/decisions/2026-06-01-project-membership-model.md:66` — "프로젝트 생성 API(생성자=자동 ADMIN)가 들어오면 이 부트스트랩은 '생성 시 생성자 멤버십 1행 삽입'으로 대체되어 창문이 닫힌다."
- `docs/decisions/2026-06-01-project-member-projectidorkey.md:60` — "프로젝트 생성 FR 도입 시 생성자=자동 admin으로 연결."
- `docs/plan/product/identity-access.md:272` — "프로젝트 생성 FR으로 이연" ← **그 대상 FR이 인덱스에 없음**

## Maxi 확정 결정 (질문-응답으로 잠김, 2026-07-16)

| # | 결정 | 이유 |
|---|---|---|
| D1 | **명칭은 프로젝트 유지** — 개명 없음. UI·DB·API 전부 `project` | "워크스페이스"가 이미 5가지 의미로 과적재(제품 자체 / Slack 워크스페이스 / pnpm workspace / Google Workspace / git worktree 기준 디렉터리). Atlas Wiki v0.5에 Confluence식 Space가 들어올 예정이라 용어 충돌 원천 회피. 지라도 DB·API는 project를 그대로 뒀다 |
| D2 | **범위는 코어만** — 생성 · 목록/조회 · 설정 변경 · 아카이브 | 지라의 type / template / management style 제외. management style(team·company-managed)은 권한·워크플로우 스킴 3종이 `project_id` PK 1:1 구조라 도입 시 동시 재설계 필요 → 폭발 반경 과대 |
| D3 | **상위 컨테이너 제외** — Atlassian Projects 미도입 | Space와 별개 축. 도입 시 `projects.key` 전역 UNIQUE 완화가 필요해지고 이는 `issues.key` 전역 UNIQUE · `issue_key_redirects.old_key` PK 전제 · DATA.md §1.1(이슈키 영구 보존)과 정면 충돌 |
| D4 | **SDD §5.3 괴리는 SDD를 코드에 맞춰 정정** | 코드는 prod 기출시. 이슈 키가 거기 매달려 있어 반대 방향 불가 |
| D5 | **#276(FR-AT-07 PR-B)과 병렬 진행** | 별도 worktree. 단 같은 issue-tracking BC라 충돌 관리 필요 (아래 §리스크) |

### /bts-domain 단계 추가 확정 (2026-07-17)

| # | 결정 | 이유 |
|---|---|---|
| D6 | **FR 프리픽스 = 신규 `FR-PJ` 신설** (§2.2.16) | §2.2.10 제목이 "권한 관리 (FR-PM)"이고 9개 중 8개가 순수 권한. SDD §2.1 추적 매트릭스가 FR-PM을 12장(권한)에 매핑하므로 프로젝트 CRUD를 넣으면 섹션 제목과 챕터 매핑이 둘 다 거짓이 된다. `identity-access.md:272`가 예약해 둔 "프로젝트 생성 FR"과 일치. `PJ` 프리픽스 미충돌 확인(현존 33종 전수 스캔). **전례 없는 첫 프리픽스 신설** |
| D7 | **생성 권한 = 전역 권한코드 `CREATE_PROJECT` 완전형** (그룹/사용자 부여 + 관리 화면) | Maxi 확정. SYSTEM_ADMIN 전용은 사내 1,000명 규모에서 생성이 소수 관리자에 병목. **FR-PM-08 ADR D3 위반이 아니라 그 ADR이 예고한 확장 트리거를 당기는 것** — D3 원문 "미래에 전역 권한이 세분화되면 그때 전역 매트릭스를 도입한다". `role_permissions`는 `CHECK(role IN ('PROJECT_ADMIN','MEMBER'))`(`V008:25`)이라 못 쓰고, `project_permission_scheme.project_id` PK 판정은 생성 시점에 project_id가 없어 평가 불가 → **신규 `global_permission_grants` 테이블 필요**. `user_groups`(FR-PM-09) 재사용 |
| D8 | **아카이브 목록 = 기본 제외 + 별도 탭** (지라 관례) | Maxi 확정. Version 선례(`VersionRepository.kt:84·100·129`가 STATUS 필터 없이 DELETED_AT만 검사)와는 **의도적으로 다름**. `ProjectMembershipAdapter.kt:63-73`("내 프로젝트 목록") 술어 변경 필요 → identity-access cross-BC 영향 |
| D9 | **아카이브 잠금 범위 = 백그라운드 포함 (전면 읽기 전용)** | Maxi 확정. **비용은 당초 우려보다 훨씬 작다** — cross-BC 쓰기 포트 2종이 **전부 `IssueApplicationService`를 통과**하므로(`AutomationIssueMutationAdapter` KDoc:30-31 "도메인 repository 를 직접 호출하지 않으므로", `IssueImportAdapter`) 초크포인트 한 곳 게이트로 API·automation·Import가 동시에 막힌다. **`ProjectLifecyclePort` 신설 불필요, BC별 어댑터 불필요.** 알림/Slack은 이슈 변경 이벤트에 반응하므로 변경이 막히면 자동으로 멈춘다. 잔여 = automation 시간 기반 트리거의 409 실패 노이즈 → automation 조기 skip으로 처리 |

### /context-restore 세션 확정 (2026-07-17, 저장본 §Remaining 대기 2건 해소)

| # | 결정 | 이유 |
|---|---|---|
| D10 | **R6 = 이번 PR 통합** — FR-PJ-01 PR 안에서 R6 + R6-B를 함께 고친다. 별도 선행 PR 아님 | Maxi 확정. 추천안(별도 선행 PR)을 기각. **BC 혼재를 명시 고지한 상태에서의 선택** — R6 수정은 project-workflow BC, FR-PJ-01은 issue-tracking BC라 한 PR이 2개 BC를 담는다. `DEVELOPMENT.md §1` 절대 규칙 19개에는 BC 격리가 **없다**(보안 6 / 데이터 무결성 4 / 코드 품질 6 / 외부 의존성 3). "한 PR = 한 BC + 한 plan"은 `DEVELOPMENT.md §4 PR 규칙`의 **관례**이므로 절대 규칙 충돌이 아니다. `CLAUDE.md §컨텍스트 효율`의 "여러 BC 동시 수정은 Maxi 확인" 요건 = **본 결정으로 충족**. → PR 본문·`/bts-codereview`에 이 근거를 첨부해야 하며, 라벨 `bc:<context>`가 단수 전제라 spec 단계에서 표기 방식 결정 필요 |
| D11 | **계속 진행 — D7 완전형 유지** | Maxi 확정. D2("코어만")의 공식 확대를 유지. 되돌리기 가장 싼 지점(코드 0줄)에서 재확인한 결과이므로 이후 범위 축소 재논의는 새 근거 없이는 하지 않는다. 범위 = FR 5개(FR-PJ-01~04 + FR-PM-10), 123 → 128 |

### /bts-spec Phase B 확정 (2026-07-17, D9 전제 반증에 따른 재결정)

| # | 결정 | 이유 |
|---|---|---|
| D12 | **아카이브 잠금 = 단계적.** 이번 범위 = 이슈 쓰기(초크포인트 1곳) + **issue-tracking 자체 17곳**. cross-BC 12곳(identity-access 7 · automation 4 · project-workflow 1)과 `ProjectLifecyclePort` 는 **후속 FR** | Maxi 확정. **D9 의 근거였던 *"비용 거의 없음"* 이 Phase B B1 로 반증됨** — `/api/v1/projects/{...}` 하위 쓰기 실측 **29개 / 5 BC**, 전부 `IssueApplicationService` 참조 0건. D9 의 증거(cross-BC 쓰기 포트 2종이 초크포인트 경유)는 **참**이므로 automation·Import 차단(S7)은 성립하나, *"API 가 막힌다"* 는 **이슈 API 에만** 참이었다. → **D9 를 폐기하지 않고 범위를 명시**한다. 29곳 중 **17곳이 issue-tracking(=`projects` 소유 BC) 안**이라 이번 범위에서는 **D9 의 "`ProjectLifecyclePort` 신설 불필요"가 그대로 성립**한다. cross-BC 12곳에서만 포트가 필요하며 그건 후속 FR 로 분리 |

> **D9 의 지위.** 폐기가 아니라 **범위 한정**이다. "백그라운드 포함(automation·Import)" 이라는 D9 의 핵심 주장은 실측으로 확증됐다(S7). 무너진 것은 *"초크포인트 한 곳이면 전면 읽기 전용이 된다"* 는 **비용 추정**뿐이다.

> **후속 FR 후보 (이번에 ID 부여 안 함).** "아카이브 잠금 cross-BC 확장" — `ProjectLifecyclePort` 신설 + identity-access(`ProjectMember` 3 · `ProjectSecurityScheme` 2 · `FieldPermission` 2) · automation(`AutomationRule` 4) · project-workflow(`ProjectWorkflowScheme` 1) 어댑터. **FR ID 를 지금 부여하면 전수 동기화 8종(CLAUDE.md:29-36)이 이번 PR 로 딸려오므로**, 착수 시점에 `/bts` 로 신설한다. FR 총수는 이번 작업에서 **128 유지**.

### R6-B — R6 을 고치면 깨어나는 2차 결함 (2026-07-17 실측 확인, 신규 발견)

**R6 확정.** 3중 실측.
1. `V201__workflow_schemes.sql:132-134` 주석이 자인 — "Flyway migrate 는 Spring Boot 기동 전에 실행되므로 workflows 가 비어 있으면 0건 삽입 … 후속 ApplicationRunner 에서 보완 (Wave-2 범위)".
2. **그 Wave-2 는 미구현.** project-workflow 의 `ApplicationReadyEvent` 소비자는 `YamlSeedService` 단 하나이고, 이 클래스는 workflows / workflow_states / workflow_transitions / workflow_validators / workflow_post_actions 만 삽입한다(`:483-533`). 스킴↔워크플로우 매핑 삽입 코드 없음. 전 모듈 `ApplicationRunner` / `CommandLineRunner` grep 결과에도 백필 러너 없음(MinIO config · `SystemAdminBootstrapRunner` 뿐).
3. `infra/local/seed-project.sql:54-56` 주석이 증상 명시 — "없으면 resolveStart(null) 가 기본 매핑을 못 찾아 422 workflow_not_configured".

**R6-B (신규).** `V201:84` — `workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE RESTRICT`.
`YamlSeedService.applyIfChanged`(`:289-305`)는 YAML structural 변경 감지 시 `deleteWorkflow(key)` → `insertWorkflow(dto)` 로 **통째 재적재**하는데, `deleteWorkflow`(`:467-472`)는 `DELETE FROM workflows WHERE key = ?` 평문이다. **매핑이 존재하는 순간부터 표준 워크플로우 YAML 을 구조적으로 한 번만 수정해도 FK RESTRICT 위반 → `ApplicationReadyEvent` 시드 예외 → 부팅 실패.**

- **두 결함이 서로를 가리고 있다.** R6 때문에 매핑이 항상 0행이라 RESTRICT 가 발동할 일이 없다. R6 을 고치는 순간 R6-B 가 활성화된다.
- `YamlSeedService` KDoc `:143-145` 는 "CASCADE 로 런타임 post-action 이 소실된다 — 알려진 한계"라며 CASCADE 를 전제하는데, **매핑만 RESTRICT** 라 이 전제가 틀렸다.
- 로컬은 `seed-project.sql:57-70` 이 매핑을 심으므로 **이미 이 지뢰 위**에 있다(YAML 구조 수정 시 부팅 실패). 아직 아무도 안 밟았을 뿐.
- 패턴 동형 — 메모리 `permitall-opens-preexisting-body-buffer-dos`("경로를 열면 선재 결함이 신규 노출"). D10 이 선행 PR 분리를 기각했으므로 **같은 PR 안에서 R6 백필과 R6-B 방어를 동시에** 넣어야 한다. 백필만 넣고 R6-B 를 남기면 부팅 실패를 심는 것이다.
- **spec 단계 필수 설계.** 백필 러너의 재시드 생존 전략 — 후보 (a) `deleteWorkflow` 전 매핑 정리 후 재연결 (b) FK 를 CASCADE 로 바꾸고 백필 러너가 매번 보정 (c) workflows 재적재를 UPSERT 로 전환해 UUID 보존. 각각 `SchemaMigrationTest` 카운트 가드 · `init_codegen.sql` 미러 영향 확인 필요.

### FR 배치 (D6 + D7 귀결)

| FR | BC | 내용 |
|---|---|---|
| FR-PJ-01 | issue-tracking | 프로젝트 생성 (키 검증·예약어 차단·생성자 자동 PROJECT_ADMIN 멤버십) |
| FR-PJ-02 | issue-tracking | 프로젝트 목록/조회 (권한 필터링·아카이브 기본 제외) |
| FR-PJ-03 | issue-tracking | 프로젝트 설정 변경 (name·lead_user_id) |
| FR-PJ-04 | issue-tracking | 프로젝트 아카이브/해제 (읽기 전용 잠금) |
| FR-PM-10 | identity-access | 전역 권한 부여 (`global_permission_grants` — 그룹/사용자 grant + 관리 화면) |

**FR 총수 123 → 128.** D2("코어만")는 D7로 명시 확대됐다.

> **전역 권한 부여를 FR-PM에 두는 이유** — D6에서 "프로젝트 CRUD는 권한이 아니니 FR-PJ"라 판단한 논리의 역이다. 전역 권한 부여는 권한 그 자체이므로 §2.2.10 권한 관리에 속한다. BC도 identity-access로 일치한다.

## 확인된 현황 — SDD vs 실제 코드 괴리 (D4 대상)

`docs/sdd/05-data-model.md:52-67` §5.3 Project는 필드 12개를 설계해 뒀으나 실제와 어긋난다.

| 축 | SDD 표기 | 실제 코드 | 근거 |
|---|---|---|---|
| `id` 타입 | BIGINT | **UUID** | `docs/decisions/2026-06-01-project-membership-model.md:17` |
| 리드 컬럼명 | `lead_id` | **`lead_user_id`** | `docs/adr/2026-06-06-project-lead-default-assignee-fallback.md` |
| 이슈 발번 | `next_issue_number` | **`key_sequence`** | `V001__issues_initial.sql:10` |
| 소속 BC | Project & Workflow (`04-architecture.md:19`) | **issue-tracking** | `project-membership-model.md:13` |

실제 `projects` 컬럼 9개 — `id`(UUID) / `key`(VARCHAR(10) UNIQUE, `^[A-Z][A-Z0-9]{1,9}$`) / `name`(VARCHAR(255)) / `key_sequence`(BIGINT) / `created_at` / `updated_at` / `deleted_at` / `lead_user_id`(V013) / `require_2fa`(V020).

생명주기 미설계 — SDD Project 표에 `status`·`archived_at` 없음. 대조군으로 Version은 `status`(`05-data-model.md:88` UNRELEASED/RELEASED/ARCHIVED)가, Issue는 `deleted_at`(`:35`)이 있다. **프로젝트만 둘 다 없다.** 실제 테이블엔 `deleted_at`만 있다.

API 미설계 — `docs/sdd/11-api-design.md:32-36`의 프로젝트 API는 **GET 4개가 전부**. 이슈(`:19-21`)는 POST/PATCH/DELETE가 다 있는 것과 대조된다.

## 함께 고칠 drift

- `docs/plan/fr-index.md:5` — `## §A.1 FR 역인덱스 (122개 전수)` 가 stale. 같은 파일 `:1`·`:8`·`:231` 및 실측은 모두 **123**. (`:253`에 `2026-06-14. FR-NT-05 신설 … 합계 122→123` 이력 있음 — 그때 `:5`만 놓침)

## 리스크

### R1. #276과 같은 BC 동시 수정 (Maxi 승인된 병렬)

Draft PR #276 `backend/fr-at-07-pr-b-fix-version`이 같은 issue-tracking BC의 `IssueMutationPort`를 수정 중.

- **Flyway V번호 충돌** — 아카이브 컬럼 마이그레이션 추가 시. 머지 직전 재확인 필수
- **머지 순서 의존** — 먼저 머지되는 쪽에 맞춰 rebase 필요

### R2. 프로젝트 생성 = 2행 한 트랜잭션 (2026-07-17 정정 — 최초 "5계층" 기술은 오류였다)

**정정 이력.** 이 문단은 최초에 "프로젝트 · 권한 스킴 매핑 · 멤버십 · 워크플로우 스킴 · 기본 매핑 5계층이 한 트랜잭션에 필요"라고 적었으나 **사실이 아니었다**. `data-dev.sql`이 5개를 명시적으로 심는다는 사실을 "런타임 생성에 5개가 필수"로 잘못 옮긴 것이다. /bts-domain 조사에서 반증됐고 직접 재확인했다.

| 계층 | 생성 시 행이 필요한가 | 근거 |
|---|---|---|
| `projects` | **필요** | `IssueApplicationService.kt:212-214` (`findProjectIdByKey ?: throw`) |
| `project_memberships` | **필요** (우회 불가) | `IdentityAccessIssuePermissionResolver.kt:78-81` 비멤버 즉시 거부 · `:49-50` "관리자 우회 없음" |
| 권한 스킴 매핑 | **불필요** — `is_default` fallback | `V008__permission_schemes_and_role_permissions.sql:37` "미매핑 프로젝트는 is_default = TRUE 스킴을 fallback 으로 사용한다" |
| 워크플로우 스킴 배정 | **불필요** (단 R5 확인 필요) — auto-assign | `WorkflowKeyResolverImpl.kt:58-59` "EC-1 auto-assign: assignment 없는 신규 프로젝트는 software-scheme 을 자동 배정한 뒤 default mapping workflow 를 반환한다" |
| 스킴 기본 매핑 | **프로젝트 단위 아님** — 스킴 단위(`scheme_id`)라 생성 트랜잭션의 일이 아니다. **단 전역으로 비어 있다 → R6** | `V201__workflow_schemes.sql:137-145` |
| 보안 스킴 | **조건부** — `securityLevelId != null` 일 때만 | `IssueApplicationService.kt:221-229` |

**프로젝트 생성 트랜잭션에 필요한 건 2행이다** — `projects` INSERT + `project_memberships` INSERT(생성자 = PROJECT_ADMIN).

**영향.** project-workflow BC는 이 작업 범위에서 **완전히 빠진다**. cross-BC 쓰기는 issue-tracking → identity-access 한 방향뿐이다.

**단 이 2행을 쪼개면 안 된다.** `project-membership-model.md:66`이 예고한 대로, 멤버십 삽입 없이 생성 API만 머지하면 ADR이 막아온 권한 상승 창문(멤버 0명 프로젝트에 자신을 첫 PROJECT_ADMIN으로 꽂기)을 prod에 처음으로 여는 셈이 된다. FR-AT-07 C-1이 "**전략 오조준** — 방어 없이 경로를 먼저 열고 방어는 2 PR 뒤"로 감점된 바로 그 실수다. **분할선은 보안 불변식을 가로지르지 않는다.**

### R5. auto-assign 이 nil actor 로 500 을 던진다는 실증 기록 (검증 필요)

자동 메모리 `no-project-creation-feature-issue-needs-5-layer-seed`(2026-07-11, **실증 기반**)가 기록한다.

> `project_workflow_scheme_assignments` → software-scheme. **미배정 시** 첫 이슈 생성의 `resolveStart` auto-assign 경로가 `assignToProject` 에서 ASSIGN_SCHEME 권한을 **nil actor(0000...0)로 검사**해 `WorkflowSchemeAccessDeniedException` **500**. 미리 배정해 auto-assign 회피.

`WorkflowKeyResolverImpl.kt:58-59` KDoc 은 auto-assign 이 정상 동작한다고 서술하나, 메모리는 실제로 돌려본 결과다. **KDoc 과 실측이 충돌한다 — spec/impl 단계에서 실증 필수.**

- 사실이면 FR-PJ-01 생성 트랜잭션이 `project_workflow_scheme_assignments` 를 **명시 배정**해야 한다(= 3행). 그러면 project-workflow BC 가 범위로 돌아온다.
- 이 종류의 결함은 KDoc·주석으로 확인 불가하다. **실제 신규 프로젝트에 이슈를 만들어 보는 것**만이 판별한다.

### R6. 스킴 기본 매핑이 새 DB 에서 전역으로 비어 있다 — 선재 결함 (FR-PJ-01 이 정면으로 만난다)

`V201__workflow_schemes.sql:137-145` 는 `workflow_scheme_issue_type_mappings` default mapping 을 `INSERT ... SELECT ... JOIN workflows` 로 심는데, **새 DB 에서 항상 0행**이다. 같은 파일 `:132-134` 주석이 자인한다.

> 실행 순서 의존성: workflows 테이블은 YamlSeedService(ApplicationReadyEvent) 가 채운다. Flyway migrate 는 **Spring Boot 기동 전에 실행**되므로 workflows 가 비어 있으면 **0건 삽입**. 이 경우 default mapping 은 후속 ApplicationRunner 에서 보완 (**Wave-2 범위 — EC-2 참조**).

**그 Wave-2 보완은 구현되지 않았다** (직접 확인 — project-workflow 의 `ApplicationReadyEvent` 소비자는 `YamlSeedService` 하나뿐, default mapping 백필 코드 0건).

결과. **새 prod DB 에서는 어떤 프로젝트를 만들어도 이슈 생성이 422 `workflow_not_configured` 로 실패한다.** `WorkflowKeyResolverImpl.kt:60` EC-2 경로다. 현재는 `infra/local/seed-project.sql:57-70` 이 로컬에서 손수 심어 가려져 있고, 그 파일 `:53` 주석이 문제를 명시한다 — "부팅 시드는 스킴/워크플로우만 만들고 이 매핑은 안 만든다(원래 워크플로우 스킴 설정 UI 의 몫)".

**프로젝트 생성 기능과 별개의 선재 결함이나, FR-PJ-01 이 이걸 만나지 않고는 동작을 증명할 수 없다.**

**→ 처리 방침 확정 = D10 (이번 PR 통합).** 선택지 (b) 별도 선행 PR · (c) 별도 FR 이연은 Maxi 기각. 단 **백필만 넣으면 안 된다** — R6-B(§R6-B) 가 동시에 활성화되므로 같은 PR 에서 함께 방어한다.

### R3. 이슈 키 영구 보존과 충돌 (learnings.md 사전등록 함정)

> "이슈 키는 절대 재사용 금지 — 이슈 키(`PROJ-123`)는 외부 시스템(Slack, 이메일, 다른 문서)에 영구 인용된다."

아카이브/삭제 설계가 정면으로 만난다. **아카이브 vs 소프트삭제(`deleted_at`) 의미 구분**이 spec 과제.

### R4. 프로젝트 키 입력 검증

`issue-key-prefix-policy.md:19,24,78` — 예약어 차단 + 영문 대문자 검증 + `"Atlas Issues" → ATLAS` 자동 제안 UX 구상이 기록돼 있음.

## FR 추가 지점 (조사 확인됨)

- **정본**: `docs/sdd/02-requirements.md` §2.2. 표 헤더 `| ID | 요구사항 | 우선순위 |`. 섹션은 BC가 아니라 **FR 프리픽스**로 묶임 (`### 2.2.N <영역명> (FR-XX)`). 새 프리픽스면 `:260`(FR-CA-02) 뒤 ~ `:262`(§2.3 NFR) 앞에 §2.2.16 신설
- **BC 매핑**은 `docs/plan/fr-index.md:12-13`에만 존재 (`| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |`)
- **프리픽스 선택** (FR-PM 확장 vs 신규 프리픽스) — domain/spec 단계 과제
- **전수 동기화 8종** (CLAUDE.md:29-36) + `bash scripts/verify-master-plan.sh` 통과 필수 (종료코드 4로 자동 차단)

## 도메인 정리

- **BC**: issue-tracking (주 — `projects` 소유) + identity-access (FR-PM-10 전역 권한 부여) + **project-workflow (R6/R6-B — D10 으로 범위 복귀)** + apps/web (UI)
- ~~**project-workflow는 범위 밖** (R2 정정 결과)~~ → **2026-07-17 D10 으로 뒤집힘.** R2 정정("생성 트랜잭션은 2행, 스킴 배정 없음")은 여전히 유효하나, **R6/R6-B 수정이 project-workflow BC 를 이 PR 로 끌어들인다.** 따라서 이 PR 은 3개 BC 를 담는다 — 근거와 관례 판정은 D10 참조
- **영향 엔티티**: Project(보강) · ProjectMembership(기존) · GlobalPermissionGrant(신규) · **WorkflowSchemeIssueTypeMapping(R6 백필) · Workflow(R6-B 재적재 전략)**
- **기존 결정 충돌**: 없음. FR-PM-08 ADR D3은 위반이 아니라 **예고된 확장 트리거를 당기는 것**(D7 참조) → 신규 ADR로 확장 선언 필요

### 아카이브 의미론 — 2축 직교

BTS에서 **보관은 삭제의 전단계가 아니라 삭제를 금지하는 상위 잠금**이다. Version ADR D4 원문(`docs/adr/2026-06-10-version-status-and-transitions.md:58-65`).

```
ARCHIVED 상태 버전은 다른 mutation을 거부한다.
- rename / changeDescription / changeDates → 도메인이 거부, 409.
- delete(soft delete) → ApplicationService에서 거부, 409.   ← 아카이브가 소프트삭제를 막는다
- 단 unarchive(→UNRELEASED)는 허용.
```

| 축 | 컬럼 | 의미 |
|---|---|---|
| 존재 여부 | `deleted_at` (기존) | NULL=활성. **이번 범위 밖 — 손대지 않는다** |
| 잠금 여부 | `archived_at` (신규) | NULL=활성, NOT NULL=읽기 전용 |

- **`status` enum 기각** — 2값은 boolean의 enum 위장(글로벌 CLAUDE.md §2). Version의 enum은 릴리스 축(UNRELEASED/RELEASED)이 **선재했기에** 정당했고 프로젝트엔 그런 축이 없다. `archived_at` 단독 선례 실재 — `V407__notifications_inbox.sql:6`(status enum 없음)
- **`deleted_at` 재사용 기각** — 의미가 정반대. 게다가 `projects.deleted_at` 읽기 술어 10곳이 전부 "NOT NULL = 존재하지 않음(404/제외)"을 전제하는데, 아카이브 프로젝트는 **여전히 조회돼야** 한다(이슈가 살아 있고 키가 외부 인용 중). 한 곳만 놓치면 아카이브가 곧 이슈 소실로 나타난다. `DATA.md:44`와 DDL 주석(`V001:26`)도 동시에 거짓이 된다
- **`key`는 아카이브와 무관하게 UNIQUE를 계속 점유** (`DATA.md §1.1` 이슈키 영구 보존)
- **cascade 없음** — 아카이브는 잠금이지 소멸이 아니다. 물리적으로도 불가에 가깝다: boards/sprints는 `project_key` **문자열**만 갖고 BC 격리로 `projects` 직접 참조가 차단돼 있다(`V500__boards.sql:6-9`, `V503__sprints.sql:7-9`)
- **`buildActiveSecureWhere`(`IssueRepository.kt:945-959`)는 읽기 술어 — 건드리지 않는다.** 아카이브 프로젝트의 이슈는 계속 읽혀야 한다
- **Clock 주입 필수** (Version ADR D3 선례). `Instant.now()` 직접 호출 금지

### 잠금 초크포인트 (D9 근거)

cross-BC 쓰기 포트가 **전부 `IssueApplicationService`를 경유**하므로 게이트 한 곳이 전 경로를 덮는다.

| 포트 | prod 어댑터 | 경유 |
|---|---|---|
| `IssueMutationPort` (automation) | `AutomationIssueMutationAdapter` | → `IssueApplicationService` / `CommentApplicationService` (KDoc:30-31 "도메인 repository 를 직접 호출하지 않으므로") |
| `IssueImportPort` (FR-IM-01) | `IssueImportAdapter` | → `IssueApplicationService` 외 3종 ApplicationService |

### 신규 ADR (작성 필요)

1. **프로젝트 생명주기 — 아카이브 2축 직교 모델** (Version ADR D1~D7 형식). `DATA.md:12`가 생명주기 정책에 ADR + Maxi 확인을 요구
2. **전역 권한 부여 — FR-PM-08 D3 확장 선언** (`global_permission_grants` 도입 근거 + D3의 "미래 세분화" 트리거 충족 명시)

### glossary 갱신 대기 (Maxi 승인 필요 — 수동 영역)

| # | 용어 | 상태 |
|---|---|---|
| 1 | 프로젝트 (Project) | **보강** — 현재 `:14` "이슈를 담는 컨테이너. 프로젝트 키는 영문 대문자 + 숫자" 한 줄뿐. 소유 BC·id 타입·생명주기 2축 추가 |
| 2 | 프로젝트 보관 (Project Archive) | **신규** — 버전 상태(`:18`) 항목을 모델로 |
| 3 | 소프트 삭제 (soft delete) | **신규** — 놀랍게도 없다. `데이터 무결성 키워드`(`:101-107`)에 미등재 |
| 4 | 전역 권한 부여 (Global Permission Grant) | **신규** — FR-PM-10 |
| 5 | 프로젝트 키 (Project Key) | **신규 후보** — 이슈 키(`:12`)는 독립 항목인데 프로젝트 키는 묻혀 있음. 예약어 정책 확정 후 등재 |

### 조사 중 발견한 drift 3건

| drift | 실제 | 처리 |
|---|---|---|
| `docs/plan/fr-index.md:5` `(122개 전수)` | **123** | **이번 PR 통합** — FR 추가로 어차피 이 숫자를 건드린다 |
| `DATA.md:45` "jOOQ 기본 쿼리는 `deleted_at IS NULL` 필터 자동 첨부 (`SoftDeleteFilter` 래퍼)" | **허구** — `.kt` grep 히트 0. 실제로는 repository마다 수동 | **이번 PR 통합** — 아카이브 술어 설계가 이 문장을 근거로 오독되면 곧 보안 버그 |
| `docs/plan/product/issue-tracking.md:171` `## §3 컴포넌트 / 버전 (7개)` | **8** (FR-CM-01~04 + FR-VR-01~04) | **분리** — 무관한 섹션 (글로벌 CLAUDE.md §3 surgical) |

> **`verify-master-plan.sh`는 위 drift 2건을 안고도 종료 0으로 통과한다.** 통과는 카운트 정합의 충분조건이 아니다. FR 추가 시 기존 숫자를 복사하지 말고 **실측**할 것.

### 구현 시 필수 주의

- **`NonProdAllowSystemAdminResolver`(`issue-tracking/.../project/adapter/`)는 `@Profile("!prod")` + 항상 `true`.** KDoc이 직접 "운영 환경 사용 시 권한 우회가 발생한다"고 명시. 권한 테스트를 기본 프로파일로 짜면 **"관리자는 생성 가능" 테스트가 무의미하게 통과**한다(누구나 관리자니까). `@ActiveProfiles("prod")` + 실제 grant 시드로 검증하거나 위반을 주입해 fail을 확인할 것
- **guard는 `@PreAuthorize hasRole`이 아니라 명시 호출.** `UserGroupController.kt:53-58` — "JWT claim 이 stale 일 수 있고 **PAT 경로에는 role claim 이 없어**, 두 인증 경로에서 일관된 전역 관리자 판정을 보장하기 위해 DB 진실원천을 직접 조회한다"
- **`SystemPermissionResolver` 확장은 default 메서드로** (공유 인터페이스 — 기존 구현 3곳 fail-safe)
- **신규 권한 코드 시드는 `SchemaMigrationTest` 카운트 가드를 깬다** — 전 모듈 grep 필요
- **`init_codegen.sql` 미러 필수** (issue-tracking = jOOQ 모듈)
- **Flyway V번호는 BC 별 독립 네임스페이스다** (2026-07-17 실측 정정 — 이전 기술 "현 최신 V028"은 오류). `FlywayAssemblyConfig.kt:23-68` 이 BC 마다 별도 이력 테이블(`flyway_history_<bc>`)에 자기 location 만 마이그레이션하므로 **identity-access 와 issue-tracking 이 V030~V035 를 중복 보유해도 충돌하지 않는다**. 모듈별 다음 번호 — **issue-tracking V037** (최신 V036) · **identity-access V036** (최신 V035) · **project-workflow V203** (최신 V202). 단 **같은 모듈** 안에서는 동시 브랜치 충돌이 여전하므로 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]])
- **`FlywayAssemblyConfig` 실행 순서 = identity → issue → workflow → …** (`:28-38`). KDoc `:20-21` 명시 — "project-workflow(V202)가 issue-tracking 의 projects 를 FK 참조하므로 issue 를 workflow 보다 먼저 실행한다". **`projects` 는 cross-BC FK 피참조 대상**이므로 아카이브 컬럼 추가 시 이 순서 전제를 깨지 않는지 확인할 것
- **(무해 drift, 이번 범위 밖)** `FlywayAssemblyConfig.kt:13` KDoc 이 "identity-access(V001~V033)와 issue-tracking(V001~V035)"라 적었으나 실제는 V035 / V036. 주석만의 drift라 동작 무영향 — 글로벌 CLAUDE.md §3(surgical) 따라 이번 PR 에서 건드리지 않는다

## 스펙

전체 스펙. [docs/specs/2026-07-17-project-management-crud.md](../specs/2026-07-17-project-management-crud.md) — **마스터 스펙**. 각 PR 착수 시 `/bts` 로 상세화한다(FR-AT-07 마스터 스펙 §B 선례 동형).

**핵심 시나리오 3줄 요약.**
- `CREATE_PROJECT` 전역 권한을 가진 사용자가 프로젝트를 만들면 `projects` + `project_memberships`(생성자=PROJECT_ADMIN)가 **한 트랜잭션**에 생기고 **즉시 이슈를 만들 수 있다**(S1·S10)
- 프로젝트를 아카이브하면 **읽기는 살고 쓰기만 죽는다** — 이슈 키가 외부 인용 중이므로 소멸이 아니라 잠금이다(S6·S8·S9)
- 전역 권한은 SYSTEM_ADMIN 이 그룹/사용자에게 부여한다(S12 — D7 완전형)

**office-hours / design-shotgun 스킵.** 사유는 스펙 헤더에 기록. 요약 — 요구사항이 D1~D12 로 확정적이라 "만들 가치가 있나"는 이미 답이 나왔고([[bts-spec-office-hours-mismatch]] · 2026-05-29 FR-IS-02 선례), UI 는 D6 이라 3번째 이후 PR 이다.

**PR 6분할 (스펙 §9).**

| PR | 내용 | BC | 마이그레이션 |
|---|---|---|---|
| PR-1 | FR-PM-10 + `ProjectMembershipWritePort` 포트·어댑터 | identity-access (1개) | identity **V036** |
| PR-2 | R6 + R6-B + FR-PJ-01 (포트 소비만) | workflow + issue (2개 — D10 예외) | workflow **V203** (전략 (b) 시) |
| PR-3 | FR-PJ-02/03 목록·조회·설정(`name`) | issue-tracking | 없음 |
| PR-4 | FR-PJ-04 아카이브 + 이슈 초크포인트 + issue-tracking 스코프 쓰기 잠금 | issue-tracking | issue **V037** |
| PR-5 | D6 프론트 UI (design-shotgun 은 여기서) | apps/web | 없음 |
| PR-6 | FR-PM-10 관리 화면 + D7 E2E | apps/web | 없음 |

**분할 불변식 4종.** I1 `projects`+`project_memberships` 원자성(단 BC 경계를 넘으므로 포트 경유) / I2 방어가 경로보다 먼저(FR-AT-07 C-1 의 정반대) / I3 R6 백필과 R6-B 방어는 같은 PR / I4 R6 는 FR-PJ-01 과 같은 PR(D10).

## Brainstorming Check

✅ **통과 (3회차 iteration).** 라운드마다 BLOCKER 가 나왔고 **3라운드 모두 "직전 개정이 새로 넣은 결함"** 을 잡았다. 상세는 스펙 §Brainstorming Check.

**★ 이 작업의 핵심 교훈 — 실패 양식이 세 번 반복됐고 매번 한 단계 깊어졌다.**

| 라운드 | 눈가리개 | 처방 |
|---|---|---|
| 1회차 | **plan 이 세어준 범위** (D9 "초크포인트 1곳" · R2 "2행" · D8 "술어 변경") | 실측으로 덮어쓰기 → D12 |
| 2회차 | **내가 고른 grep 패턴** (`/api/v1/projects` 경로 prefix) | 4중 교차 열거 |
| 3회차 | **그 처방 자체의 전제** ("컨트롤러가 있고 타입 있는 DTO 를 받는다") | **5중 교차** — HTTP 밖 축 신설 |

> *"개수를 재검증"*(1) → *"개수를 만든 정의를 재검증"*(2) → **"정의가 전제한 코드 모양을 재검증"**(3).
> **2·3회차의 결함은 전부 "BLOCKER 를 고치며 새로 넣은 것"** 이다 — 방어를 추가하는 행위 자체가 새 사각지대를 만든다.

**1회차 BLOCKER 3건 (전부 plan 이 "안전하다"고 결론낸 자리).**
- **B1** D9 초크포인트가 프로젝트 스코프 쓰기 29개(4 BC)를 안 덮음 → **D12 로 범위 한정**. D9 증거(cross-BC 포트 2종이 초크포인트 경유)는 **참**이라 automation·Import 차단은 성립, 무너진 건 비용 추정뿐
- **B2** I1 이 BC 경계 횡단(`projects`=issue-tracking / `project_memberships`=identity-access). R2 는 2행을 정확히 셌으나 **다른 BC 라는 걸 못 봄** → `ProjectMembershipWritePort` 신설
- **B3** D8 술어 변경이 search-export-import FR-SR-03 오염(`ProjectMembershipAdapter` = 공유 포트 구현) → **`projectKeysOf` 불변**, 아카이브 필터는 issue-tracking 이 위에 얹음

**2·3회차가 잡은 "내가 넣은 결함".**
- **N4** *"ArchUnit 이 차단"* — 룰은 실존하나 **1차 차단은 Gradle 클래스패스**(FR-AT-07 PR-B 의 동일 전례 재현)
- **N5** *"선례 `AutomationIssueMutationAdapter`"* — **정반대 선례**. `@Transactional` 0건, `TransactionTemplate` 으로 일부러 참여 회피. 복사하면 그 어댑터가 회피한 rollback-only 오염 트랩 부활 → **cross-BC 쓰기를 호출자 tx 안에서 하는 선례는 저장소에 없음**을 명시, 롤백 실증(DoD-11) 요구
- **C10** 그 롤백 테스트를 **표준 테스트 베이스로 짜면 거짓 red** — `IssueTestcontainersBase:112` 가 tx-aware 가 아님

**phantom 0건.** citation 21 · 심볼 10 전수 실존. 지어낸 이름 없음.

**plan 단계로 이월된 미확정 7건.** 전수 목록(5중 교차) / §4.2 FR-PJ-03↔`lead`·`require_2fa` 중복 / EC-7 OCC 부재 / EC-2 아카이브 멱등 / R6-B 전략 3안 / B10 `BROWSE` 재사용 / §2.4-C `updateNextFireAt`(D12 후속 범위).

## Plan (PR-1 — 스펙 §9.2 첫 행. FR-PM-10 백엔드 + 멤버십 쓰기 포트)

> **이 절은 PR-1 만 담는다.** 스펙은 마스터이고 §9.2 가 6개 PR 로 쪼갰다. 마스터 plan 의 `## Plan` 이 첫 PR 만 담는 것은
> FR-AT-07 선례와 동형이다 (`docs/plans/2026-07-15-fr-at-07-pr-merge.md` → `## Plan (PR-A — spec §A …)`, task 4개).
> **PR-2~6 은 각자 자기 plan 파일을 갖는다** (`DEVELOPMENT.md §4`). 착수 시 `/bts` 로 신설.

### /bts-plan 단계 확정 (2026-07-17)

| # | 결정 | 이유 |
|---|---|---|
| **D13** | **FR-PJ-03 = `name` 변경만.** `lead_user_id` 는 기존 `PATCH /lead`(FR-CM-04), `require_2fa` 는 기존 `PATCH /require-2fa` 가 계속 담당 | Maxi 확정 (스펙 §4.2 3안 중 (a)). **두 엔드포인트 실존 확인** — `ProjectLeadController.kt:75` · `ProjectRequire2faController.kt:53`, 둘 다 클래스 레벨 `@RequestMapping("/api/v1/projects/{projectIdOrKey}")`. 기존 "설정 하나당 수직 슬라이스" 관례 유지 + FR-CM-04 회귀 위험 0 (글로벌 CLAUDE.md §3 surgical). **UI(PR-5)는 엔드포인트 3개를 호출한다.** → 스펙 §10 정정 4번 해소. FR-PJ-03 FR 문안을 "설정 변경(name)"으로 확정 |
| **D14** | **prod `hasGlobalPermission` = `grant OR isSystemAdmin`.** SYSTEM_ADMIN 은 grant 없이도 모든 전역 권한 보유 | Maxi 확정. 3중 근거 — (1) **기존 ADR 연속성**. `2026-06-04-system-admin-role.md` D3 원문이 *"전역 판정 = SYSTEM_ADMIN 보유 여부"* 로 **현재 모델 자체가 그것**이고, FR-PM-10 은 D3 이 예고한 *"세분화되면 그때 전역 매트릭스"* 를 **확장**하는 것이지 대체하는 게 아니다. (2) **부트스트랩**. grant-only 면 빈 DB 에서 CREATE_PROJECT 보유자가 0명이고, grant API 자체가 SYSTEM_ADMIN 게이트라 수동 자기부여 1회가 강제된다. (3) **스펙 §4.3 자체 정합**. §4.3 은 default(`= isSystemAdmin`)를 *"안전한 상위집합"* 이라 부르는데, prod 가 grant-only 면 default ⊅ prod (grant 보유 비-admin 이 prod 통과·default 탈락) 라 그 표현이 성립하지 않는다. `grant OR isSystemAdmin` 이라야 **prod ⊇ default** 가 되어 fail-safe 방향이 맞는다 |
| **D15** | **FR 5개를 PR-1 이 한 번에 등록** (123 → 128). D단계 체크박스는 PR 별로 틱 | **PR-1 이 이미 마스터 spec·plan 문서를 싣고 있다.** 그 문서들이 `FR-PJ-01~04` 를 본문 전반에서 참조하므로, `fr-index.md` 가 그 ID 를 모르는 채 머지되면 **CLAUDE.md §전수 동기화가 금지한 바로 그 drift**("일부만 고쳐 문서 간 drift 를 남기지 않는다")가 된다. BTS 관례상 FR 은 **기획 시점에 등록되고 D 체크박스로 진척을 추적**한다(현 123 FR 이 그렇게 산다). D11 도 범위를 *"FR 5개, 123 → 128"* 단일 결정으로 잠갔다 |
| **D16** | **DoD-9 · DoD-11 은 PR-1 판과 PR-2 판으로 쪼갠다** | 스펙 DoD-9(*"CREATE_PROJECT grant 보유 비-SYSTEM_ADMIN 이 **프로젝트를 만들 수 있다**"*)·DoD-11(*"생성 도중 예외 → 2행 롤백"*)은 **`POST /projects` 를 전제**하는데 그건 PR-2 다. PR-1 은 같은 결함을 **판정기·어댑터 층에서** 잡는 등가 테스트를 갖는다(T5-RED · T7-RED). PR-2 가 end-to-end 판을 추가한다 |

### ★ 스펙이 PR-1 에 대해 틀린 것 (실측 정정 4건)

**스펙의 제약 목록을 그대로 물려받으면 안 된다** ([[spec-stated-count-becomes-blindfold]]). PR-1 범위로 좁혀 재검증한 결과.

| # | 스펙 기술 | PR-1 실측 | 근거 |
|---|---|---|---|
| 1 | **C3** *"`init_codegen.sql` 미러 필수"* | **PR-1 무관.** identity-access 엔 `init_codegen.sql` 이 **없다** | 이 파일은 jOOQ 모듈 4곳에만 존재(`issue-tracking`·`search-export-import`·`notification`·`agile-planning`). identity-access 는 jOOQ 미사용 — `DSL.using` 히트 **0건**. V035 주석도 자인 — *"identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님"*. **C3 은 PR-4 것**(`projects` 가 `init_codegen.sql:21` 에 있다) |
| 2 | **C10** *"롤백 테스트가 `DSL.using` 때문에 거짓 red"* | **PR-1 무관.** identity-access 에 `DSL.using`·`TransactionAwareDataSourceProxy` **둘 다 0건** | 주입된 `NamedParameterJdbcTemplate` 은 `DataSourceUtils.getConnection()` 경유라 자동 tx-aware. **C10 은 PR-2/PR-4 것** |
| 3 | **C1/NFR-1** *"권한 테스트는 `@ActiveProfiles("prod")` 필수"* | **PR-1 은 불필요** (달아도 무해하나 부팅 비용만) | C1 의 근거인 **항상-`true` 스텁 `NonProdAllowSystemAdminResolver` 는 issue-tracking 소속**이라 identity-access 스캔 경로에 없다. 그리고 **`IdentityAccessSystemPermissionResolver` 는 `@Profile` 이 아예 없어** 모든 프로파일에서 실제 판정한다(KDoc *"AlwaysAllow stub 을 두지 않는다"*, ADR D4). **C1 은 PR-2 것** |
| 4 | **PM10-5** *"카운트 가드가 깨지는지 **UNKNOWN**"* | **안 깨진다 (확정)** | `PermissionSchemaMigrationTest.kt:79` 는 `role_permissions JOIN permission_schemes WHERE is_default=TRUE` **행 수**를 센다. `CREATE_PROJECT` 는 신규 테이블 `global_permission_grants` 로만 가고 `role_permissions` 엔 **들어갈 수 없다**(`V008:25` `CHECK(role IN ('PROJECT_ADMIN','MEMBER'))` — 전역 축 없음). **단 T2 가 실제로 돌려 확인한다** — "안 깨진다"는 전제가 아니라 검증 대상이다 |

> **정정 1·2·3 의 공통 원인 — 스펙 제약이 전체 작업 기준으로 쓰였는데 PR-1 은 identity-access 단일 BC 다.**
> C1·C3·C10 은 전부 **jOOQ 또는 issue-tracking 을 전제**한다. 그대로 물려받으면 PR-1 에 없는 함정을 막느라 실제 함정(§T7 트랜잭션 비대칭)을 놓친다.

### ★ 권한 코드 신설의 실제 파급 — 스펙이 명명하지 않은 정본 1곳

`MANAGE_AUTOMATION`(V035) · `MANAGE_WORKFLOW`(V013) 두 기존 권한 코드의 전 저장소 발자국을 추적한 결과, **`docs/sdd/12-permissions.md` 가 권한 코드 정본**이다(둘 다 등재). V035 주석도 명시 — *"권한 코드 MANAGE_AUTOMATION 은 SDD 12.3(12-permissions.md) 정본"*.

스펙 §전수 동기화는 *"docs/sdd/ — 해당 챕터"* 로 **포괄만** 했고 이 파일을 지목하지 않았다. **T9 가 명시 대상으로 잡는다.**

### ★ 모든 implementer 에게 인계 (dispatch prompt 필수 포함)

1. **`git stash` 금지.** 타 세션 휴면 stash 를 오작동 pop 한다 ([[subagent-git-stash-worktree-shared-collision]]).
2. **`ktlintFormat` 금지.** `ktlintCheck` 로 확인하고 손으로 고친다 ([[bts-ktlintformat-docs-commit-traps]]).
3. **자기 task 의 `files` 만 `git add`.** 병렬 dispatch pre-commit race ([[parallel-dispatch-precommit-hook-race]]).
4. **lint 통과 보고를 믿지 않는다** — controller 가 `./gradlew :modules:identity-access:ktlintCheck detekt --rerun-tasks` 로 직접 검증한다 ([[subagent-ktlint-false-green-controller-verify]]·[[backend-detekt-lint-debt-unmasked]]).
5. **KDoc 에 중괄호/백틱 금지** — ktlint parse 실패 ([[ktlint-kdoc-brace-parse-failure]]).
6. **머지 직전 identity-access 최신 V번호 재확인** — 동시 브랜치 충돌 ([[migration-vnumber-concurrent-branch-collision]]). V035 자신이 그 사고 기록을 주석에 남겼다.

---

### Task 1. ADR — 전역 권한 부여 (FR-PM-08 D3 확장 선언)

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-17-global-permission-grants.md`]
- depends-on: []

코드 없음. **설계를 먼저 잠근다** — T2 의 CHECK 제약과 T5 의 판정 의미가 이 문서에서 나온다.

**작성 내용 (필수 절)**.
- **맥락**. `2026-06-04-system-admin-role.md` D3 원문 인용 — *"미래에 전역 권한이 세분화되면(예: 감사자는 `VIEW_AUDIT_LOG`만) 그때 전역 매트릭스를 도입한다. 단일 역할 단계에서 매트릭스는 과설계."* → `CREATE_PROJECT` 도입이 그 트리거. **위반이 아니라 예정된 확장**임을 명시.
- **D-1. 신규 테이블 `global_permission_grants`.** 기존 2개로 안 되는 이유를 실측 근거와 함께 — `role_permissions` 는 `V008:25` `CHECK (role IN ('PROJECT_ADMIN','MEMBER'))` 라 전역 축이 없고, `project_permission_scheme` 은 `project_id` 가 PK 라 **생성 시점엔 project_id 가 없어 평가 자체가 불가능**.
- **D-2. 판정 = `grant OR isSystemAdmin`** (plan D14). 위 D14 행의 3중 근거를 그대로 옮긴다.
- **D-3. 포트 확장은 default 메서드** — 구현체 6곳 fail-safe (스펙 §2.7).
- **D-4. `grantee_id` 에 FK 없음.** `USER`→`users` / `GROUP`→`user_groups` 다형 참조라 PostgreSQL 이 단일 FK 로 표현 불가. `project_memberships.project_id` 가 이미 같은 선례(`V007:5` 주석 *"cross-BC 참조, FK 없음 (ADR D2)"*). **대신 서비스 층이 존재 검증**하고 고아 행은 판정에서 자연 탈락(fail-closed).
- **잔여 위험**. `users`/`user_groups` 삭제 시 grant 고아 행 잔존 → 판정엔 무해(JOIN 탈락)하나 목록 API 에 노출. 후속 정리 대상으로 명시.

**검증**. `bash scripts/verify-master-plan.sh` (ADR 링크 정합).

---

### Task 2. V036 — `global_permission_grants` + 스키마 가드

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/identity-access/V036__global_permission_grants.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantSchemaMigrationTest.kt`]
- depends-on: [1]

**RED**. `GlobalPermissionGrantSchemaMigrationTest` — `PermissionSchemaMigrationTest.kt:27-48` 의 `@JdbcTest` + Testcontainers 패턴을 그대로 복제(같은 모듈·같은 목적).

```kotlin
// V036 마이그레이션 검증 — global_permission_grants 테이블·제약·멱등 UNIQUE 확인

package com.atlas.bts.identity.permission

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class GlobalPermissionGrantSchemaMigrationTest {
    // companion object = PermissionSchemaMigrationTest.kt:31-48 와 동일 (postgres:16-alpine + @DynamicPropertySource)

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `grantee_type 은 USER 와 GROUP 만 허용한다`() {
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id) " +
                    "VALUES ('CREATE_PROJECT', 'ROLE', :id)",
                mapOf("id" to UUID.randomUUID()),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 (permission, grantee) 중복 부여는 UNIQUE 로 차단된다`() {
        val granteeId = UUID.randomUUID()
        val sql = "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id) " +
            "VALUES ('CREATE_PROJECT', 'USER', :id)"
        jdbc.update(sql, mapOf("id" to granteeId))

        assertThatThrownBy { jdbc.update(sql, mapOf("id" to granteeId)) }
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun `기존 role_permissions 카운트 가드는 영향받지 않는다`() {
        // PM10-5 — "안 깨진다"를 전제가 아니라 검증으로 확정한다 (스펙 §2.6 UNKNOWN 해소).
        val count = jdbc.queryForObject(
            """
            SELECT count(*) FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
            """,
            mapOf<String, Any>(),
            Int::class.java,
        )
        assertThat(count).isEqualTo(17)
    }
}
```

**실패 메시지 (예상)**. `relation "global_permission_grants" does not exist`.

**GREEN**. `V036__global_permission_grants.sql`.

```sql
-- 전역 권한 부여 매트릭스 — 사용자/그룹에게 전역 권한코드를 부여한다 (FR-PM-10)
--
-- 권한 코드 CREATE_PROJECT 는 SDD 12(12-permissions.md) 정본 — 프로젝트 생성 권한.
-- 근거: FR-PM-10 · ADR docs/decisions/2026-07-17-global-permission-grants.md
--       (FR-PM-08 ADR D3 "미래에 전역 권한이 세분화되면 그때 전역 매트릭스를 도입한다" 트리거 발동).
-- 선례: V015__user_groups.sql (grantee GROUP 대상), V007__project_memberships.sql (FK 없는 참조).
--
-- role_permissions 를 못 쓰는 이유: V008:25 CHECK (role IN ('PROJECT_ADMIN','MEMBER')) — 전역 축 없음.
-- init_codegen.sql 미러 불요 — identity-access 는 jOOQ 미사용(JdbcTemplate), 해당 파일 자체가 없다.
--
-- grantee_id 에 FK 없음: USER->users / GROUP->user_groups 다형 참조라 단일 FK 로 표현 불가.
-- project_memberships.project_id 와 같은 선례. 고아 행은 판정 JOIN 에서 탈락한다(fail-closed).
--
-- 주의(Flyway V번호): 머지 직전 identity-access 최신 번호를 재확인할 것
-- (V035 가 같은 사고로 V034->V035 재번호된 이력).
CREATE TABLE global_permission_grants (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    permission   VARCHAR(64) NOT NULL,
    grantee_type VARCHAR(16) NOT NULL CHECK (grantee_type IN ('USER','GROUP')),
    grantee_id   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (permission, grantee_type, grantee_id)
);

CREATE INDEX ix_global_permission_grants_lookup ON global_permission_grants(permission, grantee_type, grantee_id);

COMMENT ON TABLE  global_permission_grants              IS '전역 권한 부여 — 사용자/그룹에게 프로젝트와 무관한 전역 권한코드를 부여한다 (FR-PM-10)';
COMMENT ON COLUMN global_permission_grants.permission   IS '전역 권한코드. SDD 12(12-permissions.md) 정본. 현재 CREATE_PROJECT 1종';
COMMENT ON COLUMN global_permission_grants.grantee_type IS '부여 대상 종류. USER(users.id) 또는 GROUP(user_groups.id)';
COMMENT ON COLUMN global_permission_grants.grantee_id   IS 'grantee_type 에 따라 users.id 또는 user_groups.id. 다형 참조라 DB FK 없음 (ADR D-4)';
```

> **세 컬럼 모두 `NOT NULL` 인 것이 UNIQUE 멱등성의 전제다.** PostgreSQL UNIQUE 는 기본 `NULLS DISTINCT` 라
> nullable 컬럼이 섞이면 같은 값이 중복 삽입된다 ([[pg-null-distinct-on-conflict-idempotency]]). 여기선 NULL 이 없어 안전.

**REFACTOR**. 없음 (SQL + COMMENT 는 GREEN 에 포함).

**검증**. `./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantSchemaMigrationTest*'` → 3/3 PASS.
이어서 **회귀** `./gradlew :modules:identity-access:test --tests '*PermissionSchemaMigrationTest*'` → PASS (PM10-5 확정).

---

### Task 3. shared-kernel — `hasGlobalPermission` default 메서드 확장

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/SystemPermissionResolver.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/permission/SystemPermissionResolverDefaultTest.kt`]
- depends-on: []

**★ 이 task 의 성패는 "구현체 6곳이 무변경으로 컴파일되는가" 다.** abstract 로 추가하면 6곳 전부 깨진다 (스펙 §2.7 — plan 이 적었던 "3곳"은 오류, 실측 6곳).

**RED**. `SystemPermissionResolverDefaultTest`.

```kotlin
// SystemPermissionResolver.hasGlobalPermission default 구현 계약 검증 — 미override 구현체의 fail-safe 위임

package com.bts.shared.permission

class SystemPermissionResolverDefaultTest {
    /** override 하지 않은 구현체 — 기존 6개 구현체가 이 상태다. */
    private class OnlyIsSystemAdmin(private val admin: Boolean) : SystemPermissionResolver {
        override fun isSystemAdmin(actorId: UUID): Boolean = admin
    }

    @Test
    fun `default 는 isSystemAdmin 에 위임한다 - 관리자면 전역권한 보유`() {
        assertThat(OnlyIsSystemAdmin(admin = true).hasGlobalPermission(UUID.randomUUID(), "CREATE_PROJECT")).isTrue()
    }

    @Test
    fun `default 는 isSystemAdmin 에 위임한다 - 비관리자면 미보유 (fail-closed)`() {
        assertThat(OnlyIsSystemAdmin(admin = false).hasGlobalPermission(UUID.randomUUID(), "CREATE_PROJECT")).isFalse()
    }
}
```

**실패 메시지 (예상)**. `Unresolved reference: hasGlobalPermission`.

**GREEN**. `SystemPermissionResolver.kt` 에 default 메서드 추가. **`isSystemAdmin` 시그니처는 건드리지 않는다.**

```kotlin
    /**
     * 행위자가 전역 권한 [permission] 을 보유하는지 판정한다 (FR-PM-10).
     *
     * ## default 가 isSystemAdmin 위임인 이유 (fail-safe)
     * 이 메서드는 기존 인터페이스에 **나중에 추가**됐다. 구현체 6곳(prod 2 + test 4)이 override 없이
     * 살아남아야 하므로 default 를 둔다. 의미는 "SYSTEM_ADMIN 이면 모든 전역 권한 보유" —
     * 실제 prod 판정(grant OR isSystemAdmin)의 **부분집합**이라 fail-safe 방향이다.
     *
     * ## prod 어댑터는 반드시 override 한다
     * override 를 잊으면 판정이 SYSTEM_ADMIN 전용으로 되돌아가 FR-PM-10 이 조용히 무력화된다.
     * IdentityAccessSystemPermissionResolver 가 override 하며, 그 회귀는
     * "grant 보유 비-SYSTEM_ADMIN 이 true 를 받는다" 테스트가 잡는다.
     *
     * @param actorId 판정 대상 사용자 UUID
     * @param permission 전역 권한코드. SDD 12 정본 (예 CREATE_PROJECT)
     * @return 보유 시 true. 판정 불가/미부여는 false (fail-closed)
     */
    fun hasGlobalPermission(
        actorId: UUID,
        permission: String,
    ): Boolean = isSystemAdmin(actorId)
```

**REFACTOR**. 인터페이스 KDoc 상단에 FR-PM-10 확장 사실 1줄 추가.

**검증**.
```bash
./gradlew :modules:shared-kernel:test --tests '*SystemPermissionResolverDefaultTest*'
# ★ 6개 구현체 무변경 컴파일 확인 — 이게 이 task 의 본 목적
./gradlew :modules:identity-access:compileKotlin :modules:issue-tracking:compileKotlin \
          :modules:search-export-import:compileTestKotlin :modules:notification:compileTestKotlin \
          :modules:issue-tracking:compileTestKotlin :modules:slack-integration:compileTestKotlin
```

---

### Task 4. `GlobalPermissionGrantRepository` — JdbcTemplate 리포지토리

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrant.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantRepositoryIntegrationTest.kt`]
- depends-on: [2]

**RED**. `GlobalPermissionGrantRepositoryIntegrationTest` — `ProjectMembershipRepositoryIntegrationTest` 패턴 복제.

```kotlin
@Test
fun `USER grant 를 부여하면 hasGrant 가 true 를 반환한다`() {
    val userId = seedUser()
    repo.grant("CREATE_PROJECT", GranteeType.USER, userId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `GROUP grant 는 그룹 멤버에게 전파된다`() {
    val userId = seedUser()
    val groupId = seedGroup()
    seedGroupMembership(groupId, userId)
    repo.grant("CREATE_PROJECT", GranteeType.GROUP, groupId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `grant 가 없으면 false (fail-closed, PM10-6)`() {
    assertThat(repo.hasGrant(seedUser(), "CREATE_PROJECT")).isFalse()
}

@Test
fun `다른 권한코드의 grant 는 전파되지 않는다`() {
    val userId = seedUser()
    repo.grant("SOME_OTHER_PERMISSION", GranteeType.USER, userId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isFalse()
}

@Test
fun `그룹에서 탈퇴하면 grant 가 사라진다`() {
    val userId = seedUser(); val groupId = seedGroup()
    seedGroupMembership(groupId, userId)
    repo.grant("CREATE_PROJECT", GranteeType.GROUP, groupId)
    removeGroupMembership(groupId, userId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isFalse()
}
```

**실패 메시지 (예상)**. `Unresolved reference: GlobalPermissionGrantRepository`.

**GREEN**. 도메인 + 리포지토리. **SQL 은 전부 `NamedParameterJdbcTemplate` 바인딩** (DEVELOPMENT.md §1.3 — 문자열 결합 금지).

```kotlin
// 전역 권한 부여 행 — global_permission_grants 매핑 (FR-PM-10)
enum class GranteeType { USER, GROUP }

data class GlobalPermissionGrant(
    val id: UUID,
    val permission: String,
    val granteeType: GranteeType,
    val granteeId: UUID,
    val createdAt: Instant,
)
```

`hasGrant` 판정 SQL — USER 직접 부여 ∪ GROUP 경유 부여.

```sql
SELECT EXISTS (
    SELECT 1
    FROM global_permission_grants g
    WHERE g.permission = :permission
      AND (
            (g.grantee_type = 'USER'  AND g.grantee_id = :actorId)
         OR (g.grantee_type = 'GROUP' AND g.grantee_id IN (
                SELECT gm.group_id FROM group_memberships gm WHERE gm.user_id = :actorId
            ))
      )
)
```

메서드 4종 — `grant(permission, granteeType, granteeId): GlobalPermissionGrant` (`ON CONFLICT DO NOTHING` 아님 — 중복은 409 로 올린다) / `revoke(id): Boolean` / `list(): List<GlobalPermissionGrant>` / `hasGrant(actorId, permission): Boolean`.

**REFACTOR**. SQL 상수를 `private companion object` 로 추출 + KDoc (`ProjectMembershipAdapter.kt:57-70` 동형).

**검증**. `./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantRepositoryIntegrationTest*'` → 5/5 PASS.

---

### Task 5. prod 판정기 override — `hasGlobalPermission` (D14)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolverGlobalPermissionTest.kt`]
- depends-on: [3, 4]

**★ 이 task 가 스펙 B8 을 막는다.** override 를 잊으면 default(`= isSystemAdmin`)가 남아 `CREATE_PROJECT` 가 **SYSTEM_ADMIN 전용**으로 되돌아간다 — **Maxi 가 D7 에서 기각한 바로 그 안**이다. fail-closed 라 사고는 안 나지만 **아무도 모르게 기능이 사라진다**.

**RED**. **판별자는 "비-SYSTEM_ADMIN + grant"** 다 — 이것만이 override 유무를 가른다 (C2 — 판별력 없는 단언 금지).

```kotlin
@Test
fun `grant 보유 비-SYSTEM_ADMIN 이 전역권한을 획득한다 (B8 회귀 가드 · DoD-9 PR-1 판)`() {
    val userId = seedUser()
    grantRepo.grant("CREATE_PROJECT", GranteeType.USER, userId)

    // ★ 판별자 — 이 사람이 SYSTEM_ADMIN 이 아님을 먼저 못박는다.
    //   override 가 없으면 default 가 isSystemAdmin 에 위임해 false 가 되고 이 테스트가 red 로 잡는다.
    assertThat(resolver.isSystemAdmin(userId)).isFalse()
    assertThat(resolver.hasGlobalPermission(userId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `SYSTEM_ADMIN 은 grant 없이도 전역권한을 보유한다 (D14)`() {
    val adminId = seedUser()
    seedSystemAdmin(adminId)
    assertThat(resolver.hasGlobalPermission(adminId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `grant 도 SYSTEM_ADMIN 도 아니면 false (PM10-6 fail-closed)`() {
    assertThat(resolver.hasGlobalPermission(seedUser(), "CREATE_PROJECT")).isFalse()
}

@Test
fun `GROUP grant 보유 비-SYSTEM_ADMIN 이 전역권한을 획득한다`() {
    val userId = seedUser(); val groupId = seedGroup()
    seedGroupMembership(groupId, userId)
    grantRepo.grant("CREATE_PROJECT", GranteeType.GROUP, groupId)
    assertThat(resolver.isSystemAdmin(userId)).isFalse()
    assertThat(resolver.hasGlobalPermission(userId, "CREATE_PROJECT")).isTrue()
}
```

> **`@ActiveProfiles("prod")` 를 달지 않는다.** C1 의 근거인 항상-`true` 스텁(`NonProdAllowSystemAdminResolver`)은
> **issue-tracking 소속**이라 identity-access 스캔 경로에 없고, `IdentityAccessSystemPermissionResolver` 는
> `@Profile` 이 아예 없어(KDoc *"AlwaysAllow stub 을 두지 않는다"*, ADR D4) 모든 프로파일에서 실제 판정한다.
> **C1 은 PR-2 제약이다** (위 §스펙 정정 3).

**실패 메시지 (예상)**. `expected: true but was: false` (default 위임이 살아 있음).

**GREEN**. override 추가. **생성자에 `GlobalPermissionGrantRepository` 주입** — 기존 `repo: SystemRoleAssignmentRepository` 는 유지.

```kotlin
    /**
     * 전역 권한 [permission] 보유 여부 (FR-PM-10, ADR D-2).
     *
     * 판정 = grant 보유 **또는** SYSTEM_ADMIN (plan D14). SYSTEM_ADMIN 은 전역 권한의 상위집합이므로
     * 별도 grant 없이 통과한다 — FR-PM-08 ADR D3 "전역 판정 = SYSTEM_ADMIN 보유 여부" 와의 연속성이며,
     * 빈 DB 에서 CREATE_PROJECT 보유자가 0명이 되는 부트스트랩 공백을 막는다.
     *
     * ## 이 override 를 지우면 안 된다
     * 지우면 인터페이스 default 가 살아나 판정이 isSystemAdmin 과 같아지고, FR-PM-10 이
     * 조용히 SYSTEM_ADMIN 전용으로 되돌아간다(D7 에서 기각된 안). 회귀 가드는
     * IdentityAccessSystemPermissionResolverGlobalPermissionTest 의 "grant 보유 비-SYSTEM_ADMIN" 케이스다.
     */
    override fun hasGlobalPermission(
        actorId: UUID,
        permission: String,
    ): Boolean = grantRepo.hasGrant(actorId, permission) || isSystemAdmin(actorId)
```

**REFACTOR**. 클래스 KDoc 에 FR-PM-10 확장 1줄 + `@see` 추가.

**검증**. `./gradlew :modules:identity-access:test --tests '*IdentityAccessSystemPermissionResolverGlobalPermissionTest*'` → 4/4 PASS.
**★ mutation 확인 (필수)** — override 를 주석 처리하면 1·4번이 **fail** 해야 한다. 안 하면 vacuous ([[verify-logic-vs-verify-guard]]·[[archunit-vacuous-rule-silent-pass]]).

---

### Task 6. `GlobalPermissionGrantController` — grant/revoke/list API

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/GlobalPermissionGrantController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/GlobalPermissionGrantControllerTest.kt`]
- depends-on: [4]

**선례를 그대로 따른다 — `UserGroupController`** (같은 모듈·같은 SYSTEM_ADMIN 게이트·같은 PAT 대응).

**RED**. `GlobalPermissionGrantControllerTest` (`@WebMvcTest` + `SystemPermissionResolver` mock).

```kotlin
@Test fun `SYSTEM_ADMIN 이 grant 를 부여하면 201`()
@Test fun `비-SYSTEM_ADMIN 의 grant 부여는 403`()
@Test fun `비-SYSTEM_ADMIN 의 목록 조회는 403`()
@Test fun `403 응답 본문에 권한 내부 구조가 없다`()   // PJ1-8 · [[fr-pm-04-guard-exception-message-http-leak]]
@Test fun `존재하지 않는 grantee 부여는 404`()
@Test fun `중복 부여는 409`()
@Test fun `revoke 는 204`()
```

**GREEN**. `UserGroupController.kt` 의 5요소를 동형 이식한다 (**복사가 아니라 동형** — 실측한 선례 구조).

- 클래스 레벨 `@RestController @RequestMapping("/api/v1/admin/global-permissions") @PreAuthorize("isAuthenticated()")`
- 핸들러마다 `requireSystemAdmin(jwt)?.let { return it }` — **이중 가드** (DEVELOPMENT.md §1.1 #4)
- `resolveActorId(jwt)` — jwt subject → UUID, 없으면 `SecurityContextHolder` principal(String) → UUID (**PAT 경로**), 둘 다 실패 시 401
- `UNAUTHORIZED_RESPONSE`/`FORBIDDEN_RESPONSE` = `mapOf("error" to "unauthorized"/"forbidden")` — **일반 메시지, 내부 구조 0**
- `runHandler {}` + `mapDomainException` — snake_case error 코드 인라인 매핑 (RestControllerAdvice 없음)
- DTO(`GrantGlobalPermissionRequest`·`GlobalPermissionGrantResponse`)는 **컨트롤러 파일 안**에 둔다 (`UserGroupController.kt:316·342` 동형)

> **`@PreAuthorize hasRole` 을 쓰지 않는다.** `UserGroupController` KDoc 이 이유를 남겼다 —
> *"JWT claim 이 stale 일 수 있고 **PAT 경로에는 role claim 이 없어**, 두 인증 경로에서 일관된 전역 관리자
> 판정을 보장하기 위해 DB 진실원천을 직접 조회한다"*. 선언적 경로는 **PAT 에서 아예 동작하지 않는다**.
>
> **SecurityConfig 무변경.** `/api` 하위 authenticated 규칙이 이미 덮는다 (`UserGroupController` KDoc §보안).

**REFACTOR**. `@Suppress("TooManyFunctions")` 필요 시 사유 주석 동반 (선례 `UserGroupController:79`).

**검증**. `./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantControllerTest*'` → 7/7 PASS.

---

### Task 7. `ProjectMembershipWritePort` — 포트 + 어댑터 + 트랜잭션 참여 실증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/membership/ProjectMembershipWritePort.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipWriteAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipWriteAdapterTest.kt`]
- depends-on: []

**★ 이 저장소에 선례가 없는 유일한 설계다.** 스펙 §4.4 N5 — *"cross-BC 쓰기를 호출자 트랜잭션 안에서 하는 선례는 이 저장소에 없다"*. 그래서 **실증이 필수**다.

**RED**. **트랜잭션 참여를 직접 증명한다** (DoD-11 의 PR-1 판 — D16).

```kotlin
// ProjectMembershipWritePort 어댑터 — 호출자 트랜잭션 참여 실증 (스펙 §4.4 · DoD-11 PR-1 판)

@Test
fun `호출자 트랜잭션이 롤백되면 addMember 도 롤백된다`() {
    val userId = seedUser()
    val projectId = UUID.randomUUID()   // project_id 는 FK 가 없다 (V007:5 cross-BC) — 실 projects 행 불요

    assertThatThrownBy {
        txTemplate.execute {
            adapter.addMember(projectId, userId, "PROJECT_ADMIN")
            throw IllegalStateException("의도적 실패 — 롤백 유발")
        }
    }.isInstanceOf(IllegalStateException::class.java)

    // ★ 어댑터가 자기 트랜잭션을 열었다면(REQUIRES_NEW / TransactionTemplate) 이 행이 살아남아 red 가 된다.
    assertThat(countMemberships(projectId, userId)).isZero()
}

@Test
fun `호출자 트랜잭션이 커밋되면 addMember 가 영속된다`() {
    val userId = seedUser()
    val projectId = UUID.randomUUID()
    txTemplate.execute { adapter.addMember(projectId, userId, "PROJECT_ADMIN") }
    assertThat(countMemberships(projectId, userId)).isOne()
}
```

> **왜 이 red 가 진짜인가.** identity-access 는 jOOQ 를 안 쓴다(`DSL.using` **0건**). 주입된
> `NamedParameterJdbcTemplate` 은 `DataSourceUtils.getConnection()` 을 타므로 자동으로 tx-aware 다.
> **C10 의 거짓 red 함정은 여기 없다** (위 §스펙 정정 2). 반대로 issue-tracking 에서 같은 테스트를 짜면
> `IssueTestcontainersBase.kt:112` 때문에 거짓 red 가 뜬다 — **PR-2 는 C10 을 반드시 적용한다**.

**GREEN**. 포트 (shared-kernel).

```kotlin
// 프로젝트 멤버십 쓰기 포트 — 프로젝트 생성 시 생성자를 멤버로 등록하는 cross-BC 통로

package com.bts.shared.membership

import java.util.UUID

/**
 * 프로젝트 멤버십을 등록하는 cross-BC 쓰기 포트 (FR-PM-10 / FR-PJ-01).
 *
 * project_memberships 는 identity-access BC 소유이고 projects 는 issue-tracking BC 소유다.
 * 프로젝트 생성은 두 테이블에 각각 1행씩 **같은 트랜잭션**으로 써야 하므로(불변식 I1),
 * issue-tracking 이 이 포트를 통해 identity-access 에 쓴다. 직접 테이블 접근은 BC 격리 위반이다.
 *
 * ### default 구현 금지 (fail-closed)
 * ProjectMembershipPort 와 같은 정신이다. Bean 이 없으면 부팅이 실패하도록 둔다 — no-op default 를
 * 두면 멤버십 없는 프로젝트가 조용히 만들어지고, 생성자조차 못 들어가는 프로젝트가 된다.
 *
 * ### 새 쓰기 경로 금지
 * 프로젝트 멤버십 쓰기는 이 포트로만 한다.
 *
 * ### BC 경계 규칙
 * 파라미터는 원시 타입(UUID/String)만 쓴다. role 을 identity-access 의 ProjectRole enum 으로 받으면
 * 두 단계로 막힌다 — 1차는 Gradle 클래스패스(shared-kernel 에 modules 의존이 0건이라 unresolved
 * reference 로 컴파일이 먼저 죽는다), 2차는 SharedKernelBoundaryArchTest.
 *
 * ### 트랜잭션 계약
 * 구현체는 트랜잭션 경계를 **선언하지 않는다**. 호출자 트랜잭션에 참여하는 것이 이 포트의 존재 이유다.
 *
 * @see ProjectMembershipPort 같은 fail-closed 원칙을 따르는 읽기 짝
 */
interface ProjectMembershipWritePort {
    /**
     * [projectId] 프로젝트에 [userId] 를 [role] 로 등록한다. 호출자 트랜잭션에 참여한다.
     *
     * @param role 'PROJECT_ADMIN' 또는 'MEMBER' (project_memberships CHECK 제약과 동일)
     * @throws IllegalArgumentException role 이 두 값이 아닐 때
     */
    fun addMember(
        projectId: UUID,
        userId: UUID,
        role: String,
    )
}
```

어댑터 (identity-access). **`ProjectMembershipRepository.kt:294-296` 의 INSERT 를 재사용**한다.

```kotlin
@Component
class ProjectMembershipWriteAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectMembershipWritePort {
```

**★ KDoc 에 트랜잭션 비대칭의 이유를 반드시 남긴다.** 형제 파일 `ProjectMembershipAdapter` 는 클래스 레벨
`@Transactional(readOnly = true)` 를 다는데 이 어댑터는 **아무 애노테이션도 안 단다**. 이유를 안 적으면
리뷰어나 다음 사람이 "빠뜨렸네" 하고 채워 넣는다.

```
## 트랜잭션 애노테이션이 없는 것은 의도다
호출자(issue-tracking 프로젝트 생성)의 트랜잭션에 참여해야 I1(2행 원자성)이 성립한다.
애노테이션이 없으면 Spring 이 바인딩한 커넥션을 그대로 써서 자동 참여한다.
- readOnly = true 는 쓰기라 애초에 틀렸다.
- TransactionTemplate 을 쓰면 안 된다. AutomationIssueMutationAdapter 가 그걸 쓰지만 그건
  "앙비언트 트랜잭션이 없다"를 전제한 OCC 재시도 격리 설계이고, 앙비언트 트랜잭션이 있는 여기에
  복사하면 그 어댑터 KDoc 이 경고한 rollback-only 오염 -> UnexpectedRollbackException 이 부활한다.
회귀 가드 — ProjectMembershipWriteAdapterTest 의 롤백 케이스.
```

**REFACTOR**. SQL 상수를 `private companion object` 로 추출.

**검증**. `./gradlew :modules:identity-access:test --tests '*ProjectMembershipWriteAdapterTest*'` → 2/2 PASS.
**★ mutation 확인 (필수)** — 어댑터에 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 를 임시로 달면
롤백 테스트가 **fail** 해야 한다. 통과하면 그 테스트는 vacuous 다.

---

### Task 8. `:modules:app` — prod 조립 빈 결선 가드

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt`]
- depends-on: [5, 7]

**★ 신규 cross-BC 포트가 prod 조립에서 실제로 결선되는지는 두 모듈 어느 쪽 테스트도 못 본다.** 9 BC 조립은 `:modules:app` 뿐이다 ([[prod-assembly-boot-verification-required]]·[[no-cross-bc-deployment-assembly]]).

**RED**. 기존 fail-closed 회귀 가드 2건(`BtsApplicationContextTest:41` `IssueSnapshotPort` · `:54` `IssueSecurityClassificationPort`)과 **동형**으로 추가.

> **★ `getBean(Type)` 이 아니라 `containsBean(FQN)` 이다.** 이 클래스는
> `FullyQualifiedAnnotationBeanNameGenerator` 를 써서 **빈 이름 = FQN 클래스명**이다(`:35` 주석).
> 기존 가드 4건이 전부 이 형태이고, 타입이 아니라 **이름으로 고정**해야 *"우연한 다른 빈"* 이
> 주입을 충족시키는 걸 막는다(`:46-47` 주석이 그 의도를 명시).

```kotlin
@Test
fun `ProjectMembershipWritePort prod 어댑터가 조립 컨텍스트에 결선된다(fail-closed 회귀 가드, FR-PM-10)`() {
    // issue-tracking 프로젝트 생성(PR-2)이 이 포트를 non-null 로 요구한다. identity-access 의
    // ProjectMembershipWriteAdapter 가 빠지면 주입이 NoSuchBeanDefinitionException 으로
    // 부팅 자체를 막아야 한다 — no-op default 금지(포트 KDoc §default 구현 금지).
    assertThat(
        context.containsBean("com.atlas.bts.identity.project.ProjectMembershipWriteAdapter"),
    ).isTrue()
}

@Test
fun `SystemPermissionResolver prod 구현이 조립 컨텍스트에 결선된다(FR-PM-10)`() {
    // 이 조립에 사는 판정기가 IdentityAccessSystemPermissionResolver 임을 이름으로 고정한다.
    // issue-tracking 의 항상-true 스텁 NonProdAllowSystemAdminResolver 는 @Profile("!prod") 라
    // 이 prod 컨텍스트에 등록되지 않는다.
    assertThat(
        context.containsBean("com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver"),
    ).isTrue()
}
```

> **B8(override 누락)은 여기서 안 잡는다 — T5 가 잡는다.** 리플렉션으로 `declaringClass` 를 보는 방법은
> 빈이 CGLIB 프록시면 프록시 클래스를 반환해 **헛fail** 한다. 역할 분담이 맞다 —
> **T5** 가 *"그 클래스가 override 를 갖는가"* 를 행위로 증명하고, **T8** 이 *"그 클래스가 prod 조립에 있는가"* 를
> 이름으로 고정한다. 둘을 합치면 B8 이 덮인다.

**실패 메시지 (예상)**. `expected: true but was: false` (빈 미등록).

**GREEN**. T7 의 `@Component` 어댑터가 identity-access 스캔에 잡히면 통과. **안 잡히면 중앙 스캔 확인** ([[shared-kernel-component-extraction-scan-regression]]).

**REFACTOR**. 없음.

**검증**.
```bash
docker compose -f infra/docker-compose.dev.yml up -d postgres   # 5433 — app 테스트 전제
./gradlew :modules:app:test
```

> **`:modules:app:test` 는 5433 영속 DB 를 쓴다** ([[app-test-persistent-db-migration-checksum-trap]]).
> V036 은 **신규** 마이그레이션이라 체크섬 드리프트가 없다. 단 **V036 을 한 번 적용한 뒤 편집하면 드리프트**가 나므로,
> 편집이 필요하면 `docker compose ... down -v` 로 볼륨을 지우고 재적용한다.

---

### Task 9. 전수 동기화 8종 + FR 5개 등록 + drift 2건 (D15)

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/sdd/02-requirements.md`, `docs/sdd/12-permissions.md`, `docs/plan/product/identity-access.md`, `docs/plan/product/issue-tracking.md`, `docs/plan/README.md`, `CLAUDE.md`, `DATA.md`, `docs/progress.html`]
- depends-on: [1, 2, 3, 4, 5, 6, 7, 8]

**★ 숫자를 복사하지 않는다. 전부 실측한다** (DoD-8 · [[spec-stated-count-becomes-blindfold]]).

**재계수 명령 (프리픽스 길이 무관 — `FR-[A-Z]{2}` 는 `FR-API-01` 을 놓친다)**.
```bash
grep -oE '^\| FR-[A-Z]+-[0-9]+ ' docs/plan/fr-index.md | sort -u | wc -l   # 현재 123 → 작업 후 128
```

**등록 대상 5개** (D15 — PR-1 이 한 번에. D 체크박스는 PR 별로 틱).

| FR | BC | 문안 |
|---|---|---|
| FR-PJ-01 | issue-tracking | 프로젝트 생성 (키 검증 · 생성자 자동 PROJECT_ADMIN 멤버십) |
| FR-PJ-02 | issue-tracking | 프로젝트 목록/조회 (권한 필터링 · 아카이브 기본 제외) |
| FR-PJ-03 | issue-tracking | **프로젝트 설정 변경 (`name`)** ← **D13.** `lead_user_id`·`require_2fa` 는 기존 엔드포인트 담당 |
| FR-PJ-04 | issue-tracking | 프로젝트 아카이브/해제 (읽기 전용 잠금) |
| FR-PM-10 | identity-access | 전역 권한 부여 (`global_permission_grants` — 그룹/사용자 grant + 관리 화면) |

**동기화 8종 (CLAUDE.md:29-36)**.
1. `docs/plan/fr-index.md` — FR ID 행 5개 · §A.2 BC 카운트(issue-tracking +4 / identity-access +1) · 합계 · 상단 주석. **+ drift 정정** `:5` `(122개 전수)` → **123 기준으로 재계산해 128**
2. `docs/sdd/02-requirements.md` — **§2.2.16 `프로젝트 관리 (FR-PJ)` 신설** (`:260` FR-CA-02 뒤 ~ `:262` §2.3 NFR 앞. **전례 없는 첫 프리픽스 신설**) + §2.2.10 에 FR-PM-10 행
3. **`docs/sdd/12-permissions.md` — `CREATE_PROJECT` 권한 코드 등재 (★ 스펙 미명명 정본).** `MANAGE_AUTOMATION`·`MANAGE_WORKFLOW` 선례 형식
4. `docs/plan/product/identity-access.md` — FR-PM-10 § 본문 · D 체크박스(**D1~D5 틱, D6·D7 미틱** — PR-6) · §N 헤더 `(FR-PM, N개)` · L1 주석 · `소속 FR. N개` · BC 완료 게이트
5. `docs/plan/product/issue-tracking.md` — FR-PJ § 신설 · D 체크박스 **전부 미틱**(PR-2~5) · 같은 5개 카운트 표기
6. `docs/plan/README.md` — §1 BC 테이블 행 · 합계
7. `CLAUDE.md` — `123 FR` → `128 FR` (**worktree 판과 main 판 둘 다**)
8. `DATA.md:45` **drift 정정** — *"jOOQ 기본 쿼리는 `deleted_at IS NULL` 필터 자동 첨부 (`SoftDeleteFilter` 래퍼)"* 는 **허구**(`.kt` grep 히트 0). 실제는 repository 마다 수동. **아카이브 술어(PR-4)가 이 문장을 근거로 오독하면 곧 보안 버그**라 지금 고친다
9. `docs/progress.html` — `node scripts/build-dashboard.mjs` 재생성

> **범위 밖 (건드리지 않는다).** `docs/plan/product/issue-tracking.md:171` `(7개)` → 실제 8 drift 는 **무관한 섹션**이라 분리한다 (글로벌 CLAUDE.md §3 surgical). `FlywayAssemblyConfig.kt:13` KDoc V번호 drift 도 동일.
>
> **Obsidian 미러 (`Maxi_wiki/BTS/`)** 는 Maxi 승인 수동 영역 — glossary 5건(§plan 참조)은 **보고만** 하고 손대지 않는다.

**검증**.
```bash
bash scripts/verify-master-plan.sh          # 종료 0 필수 (드리프트 시 종료 4로 자동 차단)
grep -oE '^\| FR-[A-Z]+-[0-9]+ ' docs/plan/fr-index.md | sort -u | wc -l   # → 128
node scripts/build-dashboard.mjs
```

> **`verify-master-plan.sh` 통과는 충분조건이 아니다.** 위 drift 2건(fr-index:5 · DATA.md:45)을 **안고도 종료 0 으로 통과**한다.
> 새 카운트 표기가 verify 가 못 잡는 형식이면 **verify 스크립트도 같은 PR 에서 확장**한다 (CLAUDE.md §전수 동기화 — 룰 추가 시 일부러 위반 넣어 fail 확인).
>
> **dashboard 재생성은 post-merge 훅이 상시 고장**이라 수동 `--no-verify` 커밋이 정규 절차다 ([[dashboard-regen-after-fr-marking]]).

---

## Plan 메타

- **task 수**. 9 (PR-1 한정)
- **예상 시간**. 직렬 약 30분. wave 병렬 시 약 20분 (**wave 수 6** — 손으로 세지 않고 `depends-on` 그래프에서 계산. cycle 없음)
- **TDD 강제**. yes — 전 task RED→GREEN→REFACTOR. `test:` 커밋이 `feat:` 보다 먼저인지 `/bts-impl` 이 git log 로 자동 검증
- **병렬 dispatch**. `/bts-impl` 이 `depends-on` + `files` 교집합으로 wave 계산
- **추가 검증**. `ktlintCheck` · `detekt` (`--rerun-tasks` — 캐시 false-green [[backend-detekt-lint-debt-unmasked]])
- **agent 배분**. `security-engineer` 6 (권한·포트 — identity-access 가 주 작업영역) / `db-engineer` 1 (V036) / `backend-engineer` 1 (문서 동기화) / ADR 1

**예상 wave** — 손으로 세지 않고 `depends-on` 그래프에서 위상정렬로 계산했다. **cycle 0**.

```
depends-on: T1[] T2[1] T3[] T4[2] T5[3,4] T6[4] T7[] T8[5,7] T9[1..8]
```

| wave | task | 근거 |
|---|---|---|
| 1 | **T1**(ADR) · **T3**(shared-kernel default) · **T7**(WritePort+어댑터) | depends-on 없음 |
| 2 | **T2**(V036) | [1] — ADR 이 CHECK 제약을 정한다 |
| 3 | **T4**(Repository) | [2] — 테이블이 있어야 통합테스트가 돈다 |
| 4 | **T5**(resolver override) · **T6**(Controller) | [3,4] / [4] |
| 5 | **T8**(app 결선 가드) | [5,7] |
| 6 | **T9**(전수 동기화) | [1..8] — 전 task 결과를 문서에 반영 |

> **초안에 "wave 4" 라 적었다가 표를 세니 5, 그래프로 계산하니 6 이었다.** 손으로 센 숫자와 표와 그래프가
> 셋 다 달랐다 — 자기모순이 가장 값싼 탐지기다([[spec-stated-count-becomes-blindfold]]).
> **`/bts-impl` 이 계산하는 실측 wave 가 최종**이다. 위 표는 그 예상치이며, `files` 교집합으로 더 직렬화될 수 있다.

> **T7 이 wave 1 인 것은 의도다.** 포트·어댑터는 FR-PM-10 코드에 의존하지 않는다 —
> 스펙 §9 가 이걸 PR-1 에 넣은 이유는 "어댑터가 identity-access 산출물"이기 때문이지 결합 때문이 아니다.
>
> **wave 는 Gradle 모듈 컴파일도 직렬화한다** ([[bts-plan-wave-gradle-module-compile]]) — 같은 wave 의
> 두 task 가 같은 모듈을 건드리면 실제로는 순차 실행된다. T1·T3·T7 은 각각 docs / shared-kernel /
> (shared-kernel + identity-access) 라 T3↔T7 이 shared-kernel 에서 겹친다. **실측 wave 는 `/bts-impl` 이 확정한다.**

## PR-2~6 로 이월 (이 plan 의 범위 밖)

**스펙 §Brainstorming Check 미확정 7건 중 6건은 PR-1 소관이 아니다.** 각 PR 의 자기 plan 이 받는다.

| # | 미확정 | 받는 PR |
|---|---|---|
| 1 | 프로젝트 스코프 쓰기 **전수 목록** — 숫자(17)를 물려받지 말고 **§2.4-B 5중 교차**로 직접 열거 | **PR-4** |
| 2 | ~~§4.2 FR-PJ-03 범위~~ | ✅ **D13 으로 해소** |
| 3 | **EC-7** — `projects` 에 OCC `version` 컬럼 부재. 도입 vs last-write-wins | **PR-3** |
| 4 | **EC-2** — 아카이브 멱등 200 vs 409 | **PR-4** |
| 5 | **R6-B 재시드 생존 전략 3안** — (a) 매핑 정리 후 재연결 권장 | **PR-2** |
| 6 | **B10** — `BROWSE` 의미 재사용 | **PR-3** |
| 7 | **§2.4-C UNKNOWN** — `updateNextFireAt` 아카이브 시 차단 여부 | **후속 FR** (D12 — ID 미부여) |

**ADR 2종 중 1종만 PR-1 이다.** plan §신규 ADR 은 2건을 요구했다.

| ADR | PR | 이유 |
|---|---|---|
| 전역 권한 부여 — FR-PM-08 D3 확장 선언 | **PR-1** (T1) | `global_permission_grants` 설계를 T2 가 곧바로 구현한다 |
| **프로젝트 생명주기 — 아카이브 2축 직교 모델** | **PR-4** | `archived_at` 을 도입하는 PR 것이다. 지금 쓰면 구현 없는 설계 선언이 되고, EC-2(멱등 200 vs 409)·EC-7(OCC) 미확정이라 **결정할 내용 자체가 아직 없다**. `DATA.md:12` 가 생명주기 정책에 ADR + Maxi 확인을 요구하므로 PR-4 에서 반드시 쓴다 |

**PR-2 가 반드시 물려받아야 할 제약 (PR-1 에선 무해했던 것들).**
- **C1** — `@ActiveProfiles("prod")` 필수. issue-tracking 엔 항상-`true` 스텁 `NonProdAllowSystemAdminResolver` 가 산다
- **C10** — 롤백 테스트를 `IssueTestcontainersBase` 위에 얹으면 **거짓 red**. `DSL.using(TransactionAwareDataSourceProxy(dataSource), ...)` 로 명시 고정
- **C6** — 새 cross-BC 의존 소비 → OpenApi 테스트에 `@MockBean` 동반 ([[new-crossbc-dep-openapi-mockbean-regression]])
- **DoD-9/DoD-11 end-to-end 판** (D16) — `POST /projects` 가 생긴 뒤라야 가능
- **C3** — `init_codegen.sql` 미러는 **PR-4**(issue-tracking V037 `archived_at`. `projects` 가 `init_codegen.sql:21` 에 있다)

## 리뷰 결과 (← /bts-review-plan 채움)

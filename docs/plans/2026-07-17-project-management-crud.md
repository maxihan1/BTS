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
| **D15** | **FR 5개를 PR-1 이 한 번에 등록** (123 → 128). D단계 체크박스는 PR 별로 틱 | **PR-1 이 이미 마스터 spec·plan 문서를 싣고 있다.** 그 문서들이 `FR-PJ-01~04` 를 본문 전반에서 참조하므로, `fr-index.md` 가 그 ID 를 모르는 채 머지되면 **CLAUDE.md §전수 동기화가 금지한 바로 그 drift**("일부만 고쳐 문서 간 drift 를 남기지 않는다")가 된다. BTS 관례상 FR 은 **기획 시점에 등록되고 D 체크박스로 진척을 추적**한다(현 123 FR 이 그렇게 산다). D11 도 범위를 *"FR 5개, 123 → 128"* 단일 결정으로 잠갔다. **★ spec §9.3(후속 FR 은 ID 미부여)과의 구분선** (plan-eng-review P3 — 초안이 미명시) — 둘 다 미착수 미래 작업인데 정반대 논리다. 기준은 **"참조하는 정본 문서가 같은 PR 에 들어가는가"** — FR-PJ 는 spec/plan 이 이번 PR 에 실려 그 ID 를 본문 전반에서 참조하므로 등록, §9.3 후속 FR 은 참조 문서가 아직 없으므로 착수 시 신설 |
| **D16** | **DoD-9 · DoD-11 은 PR-1 판과 PR-2 판으로 쪼갠다** | 스펙 DoD-9(*"CREATE_PROJECT grant 보유 비-SYSTEM_ADMIN 이 **프로젝트를 만들 수 있다**"*)·DoD-11(*"생성 도중 예외 → 2행 롤백"*)은 **`POST /projects` 를 전제**하는데 그건 PR-2 다. PR-1 은 같은 결함을 **판정기·어댑터 층에서** 잡는 등가 테스트를 갖는다(T5-RED · T7-RED). PR-2 가 end-to-end 판을 추가한다 |

### /bts-review-plan 확정 (2026-07-17, plan-eng-review)

| # | 결정 | 이유 |
|---|---|---|
| **D17** | **전역 권한코드는 DB CHECK + 서비스 400 이중 검증** | Maxi 확정(D3). `global_permission_grants` 는 BTS 에서 **처음으로 권한코드를 사용자 입력(REST 바디)으로 받는다** — `role_permissions` 의 무제약 관례(`V008:26`)는 그 테이블이 **마이그레이션만 시드**하기에 성립했다. 오타는 fail-closed 라 사고는 안 나지만 아무도 원인을 모르는 쓰레기 grant 를 남긴다 |
| **D18** | **SYSTEM_ADMIN 게이트 = 명시 호출 (PAT 지원)** | Maxi 확정(D4). **근거를 정정한다** — spec §2.4 와 초안은 *"명시 호출이 옳다"* 를 **보편 규칙**처럼 적었으나, 실측 결과 BTS 엔 **두 패턴이 공존**한다. `UserGroupController`(명시 호출) vs `AuthAuditLogAdminController:65`(`@PreAuthorize("hasRole('SYSTEM_ADMIN')")`, 그 테스트 `:113-115` 가 `ROLE_PAT` 403 을 **명시 테스트** — 버그가 아니라 의도). 실제 규칙은 **"PAT 를 지원할 것인가"** 다(`PatAuthenticationFilter.kt:48` 이 `ROLE_PAT` 만 부여). → 지원한다. **그러므로 PAT 양성 테스트가 필수**다(T6) — 없으면 결정의 근거 자체가 미검증이다 |
| **D19** | **DRY 부채는 복사 + 후속 TODO** | Maxi 확정(D5). `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` **13파일** · `resolveActorId` 6파일 · `requireSystemAdmin` 4파일 복제는 **선재 부채**다. 공통화하면 PR-1 이 13파일 리팩토링이 되어 글로벌 CLAUDE.md §3(surgical)과 충돌하고 권한 PR 의 리뷰 단위가 무너진다. T9 가 `TODOS.md` 에 기록 |

### ★ 스펙이 PR-1 에 대해 틀린 것 (실측 정정 4건)

**스펙의 제약 목록을 그대로 물려받으면 안 된다** ([[spec-stated-count-becomes-blindfold]]). PR-1 범위로 좁혀 재검증한 결과.

| # | 스펙 기술 | PR-1 실측 | 근거 |
|---|---|---|---|
| 1 | **C3** *"`init_codegen.sql` 미러 필수"* | **PR-1 무관.** identity-access 엔 `init_codegen.sql` 이 **없다** | 이 파일은 jOOQ 모듈 4곳에만 존재(`issue-tracking`·`search-export-import`·`notification`·`agile-planning`). identity-access 는 jOOQ 미사용 — `DSL.using` 히트 **0건**. V035 주석도 자인 — *"identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님"*. **C3 은 PR-4 것**(`projects` 가 `init_codegen.sql:21` 에 있다) |
| 2 | **C10** *"롤백 테스트가 `DSL.using` 때문에 거짓 red"* | 🛑 **이 정정이 틀렸다 (plan-eng-review 가 반증).** jOOQ 기전은 실제로 없으나 **C10 의 기전은 둘**이고 2번째가 PR-1 에 정통으로 적용된다 | 아래 §C10-2 |
| 3 | **C1/NFR-1** *"권한 테스트는 `@ActiveProfiles("prod")` 필수"* | **PR-1 은 불필요** (달아도 무해하나 부팅 비용만) | C1 의 근거인 **항상-`true` 스텁 `NonProdAllowSystemAdminResolver` 는 issue-tracking 소속**이라 identity-access 스캔 경로에 없다. 그리고 **`IdentityAccessSystemPermissionResolver` 는 `@Profile` 이 아예 없어** 모든 프로파일에서 실제 판정한다(KDoc *"AlwaysAllow stub 을 두지 않는다"*, ADR D4). **C1 은 PR-2 것** |
| 4 | **PM10-5** *"카운트 가드가 깨지는지 **UNKNOWN**"* | **안 깨진다 (확정)** | `PermissionSchemaMigrationTest.kt:79` 는 `role_permissions JOIN permission_schemes WHERE is_default=TRUE` **행 수**를 센다. `CREATE_PROJECT` 는 신규 테이블 `global_permission_grants` 로만 가고 `role_permissions` 엔 **들어갈 수 없다**(`V008:25` `CHECK(role IN ('PROJECT_ADMIN','MEMBER'))` — 전역 축 없음). **단 T2 가 실제로 돌려 확인한다** — "안 깨진다"는 전제가 아니라 검증 대상이다 |

> **정정 1·3 의 공통 원인 — 스펙 제약이 전체 작업 기준으로 쓰였는데 PR-1 은 identity-access 단일 BC 다.**
> C1·C3 은 **jOOQ 또는 issue-tracking 을 전제**한다. 그대로 물려받으면 PR-1 에 없는 함정을 막게 된다.
> **단 정정 2 는 그 논리를 한 칸 더 밀었다가 틀렸다 — 아래.**

#### 🛑 C10-2. 거짓 red 의 두 번째 기전 — 테스트 클래스 자신의 트랜잭션 (plan-eng-review 신설)

**정정 2 는 참인 근거로 거짓 결론에 도달했다.** *"identity-access 에 `DSL.using` 0건"* 은 **사실**이다(실측). 그러나 그것은
C10 의 **기전 하나**(tx-aware 가 아닌 손수 배선 `DSLContext`)만 덮는다. 거짓 red 로 가는 길은 둘이다.

| # | 기전 | PR-1 |
|---|---|---|
| 1 | 손수 배선 `DSLContext` 가 tx 를 우회 → tx **밖**에서 자동 커밋 | **없음** (`DSL.using` 0건) — 정정 2 의 주장은 여기까지 참 |
| 2 | **테스트 클래스 자체가 `@Transactional`** → 검증 SELECT 가 **같은 tx** 안에서 미커밋 INSERT 를 그대로 읽음 | 🛑 **정통으로 적용** |

**3중 실측.**
1. `@JdbcTest` 는 **`@Transactional` 메타 애노테이션**이다 — `spring-boot-test-autoconfigure-3.3.4.jar` 의 `JdbcTest.class` 바이트코드에 `Lorg/springframework/transaction/annotation/Transactional;` 실재(추측 아님, jar 직접 확인).
2. **identity-access 테스트 41개가 `@JdbcTest`** 다. T4 가 복제하라고 지목한 `ProjectMembershipRepositoryIntegrationTest.kt:44` 가 바로 그것이다.
3. 탈출구 **선례 실재** — `RefreshTokenRepositoryTest.kt:230` `@Transactional(propagation = Propagation.NOT_SUPPORTED)`, KDoc `:225` *"`@Transactional(NOT_SUPPORTED)` — @JdbcTest 기본 테스트 트랜잭션을 비활성화한다"*.

**왜 P0 인가 — 이 테스트는 판별력이 0 이고, 그 사실을 mutation 확인이 가려준다.**

```
@JdbcTest (= @Transactional) 안에서 txTemplate.execute { addCreatorAsAdmin(); throw } 를 돌리면

  올바른 어댑터 (무애노테이션)          잘못된 어댑터 (REQUIRES_NEW)
  → 테스트 tx 에 참여                   → 별도 tx 로 진짜 커밋
  → throw 시 rollback-only 마킹만       → READ COMMITTED 로 조회 가능
  → 실제 롤백은 테스트 종료 시점
  → countMemberships 가 같은 tx 를
     읽어 미커밋 INSERT 를 본다  → 1        → 1
                          isZero() FAIL      isZero() FAIL
                                    ↑ 두 경우가 똑같이 fail = 판별력 0
```

- **I1(2행 원자성)은 이 plan 의 1번 불변식**이고 T7 이 유일한 실증이다.
- 구현자는 거짓 red 를 보고 어댑터를 "고치려" 든다. 가장 그럴듯한 수정이 `REQUIRES_NEW`/`TransactionTemplate` — **I1 을 정확히 파괴하는 방향**이고 spec §4.4 N5 가 경고한 그 함정이다.
- 그리고 *"REQUIRES_NEW 를 달면 fail 해야 한다"* 는 mutation 확인이 **vacuous 하게 통과**한다 — 이미 fail 이니까. 깨진 테스트에 초록불을 준다([[negative-guard-needs-body-discriminator]]).

> **★ 이게 눈가리개의 4층이다.** spec Phase B 는 *개수 → 정의 → 정의가 전제한 코드 모양* 으로 3층을 벗겼다.
> 이 정정표는 그 교훈을 적용해 만든 **개정본인데, 개정본이 새 결함을 넣었다** — 나는 개수도 정의도 코드 모양도 재검증했지만
> **"이 함정에 경로가 하나뿐인가"** 를 묻지 않았다. [[spec-stated-count-becomes-blindfold]] 의 *"개정본을 원본보다 의심하라"* 가
> 이번엔 **내 정정표**를 겨눴다. → **5층 질문. "내가 반증한 것이 그 제약의 유일한 기전인가?"**

### ★ 권한 코드 신설의 실제 파급 — 스펙이 명명하지 않은 정본 1곳

`MANAGE_AUTOMATION`(V035) · `MANAGE_WORKFLOW`(V013) 두 기존 권한 코드의 전 저장소 발자국을 추적한 결과, **`docs/sdd/12-permissions.md` 가 권한 코드 정본**이다(둘 다 등재). V035 주석도 명시 — *"권한 코드 MANAGE_AUTOMATION 은 SDD 12.3(12-permissions.md) 정본"*.

스펙 §전수 동기화는 *"docs/sdd/ — 해당 챕터"* 로 **포괄만** 했고 이 파일을 지목하지 않았다. **T9 가 명시 대상으로 잡는다.**

### ★ 모든 implementer 에게 인계 (dispatch prompt 필수 포함)

1. **`git stash` 금지.** 타 세션 휴면 stash 를 오작동 pop 한다 ([[subagent-git-stash-worktree-shared-collision]]).
2. **`ktlintFormat` 금지.** `ktlintCheck` 로 확인하고 손으로 고친다 ([[bts-ktlintformat-docs-commit-traps]]).
3. **자기 task 의 `files` 만 `git add`.** 병렬 dispatch pre-commit race ([[parallel-dispatch-precommit-hook-race]]).
4. **lint 통과 보고를 믿지 않는다** — controller 가 `cd backend && ./gradlew :modules:identity-access:ktlintCheck detekt --rerun-tasks` 로 직접 검증한다 ([[subagent-ktlint-false-green-controller-verify]]·[[backend-detekt-lint-debt-unmasked]]).
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
- **D-4. `grantee_id` 에 FK 없음.** `USER`→`users` / `GROUP`→`user_groups` 다형 참조라 PostgreSQL 이 단일 FK 로 표현 불가. `project_memberships.project_id` 가 이미 같은 선례(`V007:5` 주석 *"cross-BC 참조, FK 없음 (ADR D2)"*). **대신 서비스 층이 존재 검증한다** — `UserRepository`(USER) / `UserGroupRepository`(GROUP) 조회, 미존재 시 404.
  > 🛑 **"고아 행은 판정에서 자연 탈락한다"고 쓰지 말 것 — USER 경로에서 거짓이다 (plan-eng-review P3).**
  > `hasGrant` SQL 의 GROUP 가지는 `group_memberships` 를 JOIN 하고 `V015` 가 양 FK 를 `ON DELETE CASCADE` 로 걸어서 그룹 삭제 시 **실제로 탈락**한다.
  > 그러나 **USER 가지는 `users` JOIN 이 아예 없다**(`g.grantee_type = 'USER' AND g.grantee_id = :actorId`) — 사용자 삭제 시 grant 행은 **영구 잔존**한다.
  > 실제 피해는 제한적이다(UUID 재사용이 없어 권한 상승은 안 나고, 삭제된 사용자는 인증 자체가 불가). **문제는 ADR 이 앞으로 인용될 정본이라는 점**이다 —
  > 거짓 안전 성질을 FK 생략의 근거로 박으면 다음 사람이 그걸 믿는다. **정확히 쓴다** — *"GROUP 은 CASCADE 로 탈락. USER 는 탈락 기전이 없어 고아 행이 잔존하며, 서비스 층 존재 검증과 목록 API 노출이 유일한 방어"*.
- **D-5. `granted_by` 로 감사 흔적을 남긴다** (plan-eng-review P2). 권한 부여 시스템에 *"누가 줬나"* 가 없으면 사후 추적이 불가능하다. GROUP grant 는 **그룹 멤버십 변경만으로 권한이 전파**되므로 부여 체인 전체가 무기록이 된다. 지금은 컬럼 1개지만 나중에 붙이면 **기존 행이 전부 NULL** 이라 초기 grant(가장 민감한 것)의 출처가 영구 소실된다. FK 는 D-4 정신대로 생략.
- **잔여 위험**. (1) `users`/`user_groups` 삭제 시 USER grant 고아 행 잔존 (위 D-4) — 후속 정리 대상. (2) `revoke` 는 hard DELETE 라 **회수 이력이 남지 않는다** — `revoked_at`/`revoked_by` soft delete 는 후속. 이번엔 `granted_by` 로 부여 측만 덮는다.

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

    // 🛑 T2 정정 — 세 INSERT 모두 granted_by 를 채운다. granted_by 는 NOT NULL(ADR D-5)이라
    // 생략하면 CHECK 위반이 아니라 NOT NULL 위반이 나서, 테스트 2 는 setup 에서 터지고
    // 테스트 1 은 grantee_type CHECK 를 지워도 통과하는 vacuous 가드가 된다.
    // 각 INSERT 가 겨냥한 제약 하나만 위반시키는 것이 판별자다.
    @Test
    fun `grantee_type 은 USER 와 GROUP 만 허용한다`() {
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by) " +
                    "VALUES ('CREATE_PROJECT', 'ROLE', :id, :by)",
                mapOf("id" to UUID.randomUUID(), "by" to UUID.randomUUID()),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 (permission, grantee) 중복 부여는 UNIQUE 로 차단된다`() {
        val granteeId = UUID.randomUUID()
        val sql = "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by) " +
            "VALUES ('CREATE_PROJECT', 'USER', :id, :by)"
        jdbc.update(sql, mapOf("id" to granteeId, "by" to UUID.randomUUID()))

        // 두 번째는 granted_by 만 다르다 — UNIQUE 가 3컬럼이라는 게 판별자다.
        assertThatThrownBy { jdbc.update(sql, mapOf("id" to granteeId, "by" to UUID.randomUUID())) }
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun `permission 은 CREATE_PROJECT 만 허용한다`() {
        // ADR D-1 — 이 테이블은 권한코드를 사용자 입력으로 받는 유일한 곳이다. 오타를 DB 가 막는다.
        // ※ 아래 "D3" 은 plan-eng-review 번호다. ADR 실번호는 D-1 (인계 사실 #12 표 참조).
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by) " +
                    "VALUES ('CREATE_PROJET', 'USER', :id, :by)",
                mapOf("id" to UUID.randomUUID(), "by" to UUID.randomUUID()),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
```

> 🛑 **`assertThat(count).isEqualTo(17)` 을 여기에 복제하지 않는다 (plan-eng-review P3).**
> `PermissionSchemaMigrationTest.kt:79` 가 **글자 그대로 같은 단언**을 이미 갖는다. 복제하면 (1) V036 을 아무것도 검증하지 않으면서
> V008~V035 의 성질만 재단언하고, (2) **하드코딩 카운트 가드가 2곳**이 되어 다음 권한코드 추가 때 `GlobalPermissionGrant*` 라는
> 무관한 이름 뒤에 숨는다 — [[enum-add-breaks-crossmodule-count-guard]] 가 경고한 함정을 이 PR 이 **새로 심는** 꼴이다.
> **PM10-5 확정은 아래 검증절의 회귀 실행이 이미 달성한다.**

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
-- project_memberships.project_id 와 같은 선례 (ADR D-4).
-- 고아 행: GROUP 은 group_memberships 양 FK CASCADE(V015)로 탈락하나, USER 는 술어에 users JOIN 이
-- 없어 영구 잔존한다. 서비스 층 존재 검증과 목록 API 노출이 유일한 방어다.
--
-- 주의(Flyway V번호): 머지 직전 identity-access 최신 번호를 재확인할 것
-- (V035 가 같은 사고로 V034->V035 재번호된 이력).
CREATE TABLE global_permission_grants (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    permission   VARCHAR(64) NOT NULL CHECK (permission IN ('CREATE_PROJECT')),
    grantee_type VARCHAR(16) NOT NULL CHECK (grantee_type IN ('USER','GROUP')),
    grantee_id   UUID        NOT NULL,
    granted_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (permission, grantee_type, grantee_id)
);

-- 인덱스 추가 없음 (plan-eng-review P2).
-- 위 UNIQUE(permission, grantee_type, grantee_id) 가 정확히 같은 3컬럼·같은 순서의 btree 를 이미 만든다.
-- hasGrant 술어가 그 인덱스를 그대로 탄다. 별도 인덱스는 100% 중복이라 쓰기 증폭만 낳는다.
-- V015__user_groups.sql:19 가 세운 기준("복합 PK 선두라 인덱스 자동 생성 — 별도 불요")을 따른다.
-- group_memberships(user_id) 조회는 ix_group_memberships_user(V015:20)가 이미 커버한다.

COMMENT ON TABLE  global_permission_grants              IS '전역 권한 부여 — 사용자/그룹에게 프로젝트와 무관한 전역 권한코드를 부여한다 (FR-PM-10)';
COMMENT ON COLUMN global_permission_grants.permission   IS '전역 권한코드. SDD 12(12-permissions.md) 정본. 현재 CREATE_PROJECT 1종. 코드 추가 시 이 CHECK 도 확장';
COMMENT ON COLUMN global_permission_grants.grantee_type IS '부여 대상 종류. USER(users.id) 또는 GROUP(user_groups.id)';
COMMENT ON COLUMN global_permission_grants.grantee_id   IS 'grantee_type 에 따라 users.id 또는 user_groups.id. 다형 참조라 DB FK 없음 (ADR D-4)';
COMMENT ON COLUMN global_permission_grants.granted_by   IS '이 grant 를 부여한 SYSTEM_ADMIN 의 users.id. 감사 흔적 (ADR D-5)';
```

> **`permission` CHECK 은 D3 (plan-eng-review).** 이 테이블은 BTS 에서 **처음으로 권한코드를 사용자 입력(REST 바디)으로 받는다** —
> `role_permissions` 의 무제약 관례는 그 테이블이 마이그레이션만 시드하기에 성립했다. 오타(`CREATE_PROJET`)는 fail-closed 라
> 사고는 안 나지만 **아무도 원인을 모르는** 쓰레기 grant 를 남긴다. DB CHECK + 서비스 400 **두 겹**으로 막는다.
>
> **`granted_by` 는 D7 (plan-eng-review).** 지금은 컬럼 1개지만 나중에 붙이면 **기존 행이 전부 NULL** 이라
> 초기 grant(가장 민감한 것)의 출처가 영구 소실된다. GROUP grant 는 그룹 멤버십 변경만으로 권한이 전파되므로
> 부여 체인 전체가 무기록이 된다.

> **세 컬럼 모두 `NOT NULL` 인 것이 UNIQUE 멱등성의 전제다.** PostgreSQL UNIQUE 는 기본 `NULLS DISTINCT` 라
> nullable 컬럼이 섞이면 같은 값이 중복 삽입된다 ([[pg-null-distinct-on-conflict-idempotency]]). 여기선 NULL 이 없어 안전.

**REFACTOR**. 없음 (SQL + COMMENT 는 GREEN 에 포함).

**검증**. `cd backend && ./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantSchemaMigrationTest*'` → 3/3 PASS.
이어서 **회귀** `cd backend && ./gradlew :modules:identity-access:test --tests '*PermissionSchemaMigrationTest*'` → PASS (PM10-5 확정).

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
cd backend && ./gradlew :modules:shared-kernel:test --tests '*SystemPermissionResolverDefaultTest*'
# ★ 6개 구현체 무변경 컴파일 확인 — 이게 이 task 의 본 목적
cd backend && ./gradlew :modules:identity-access:compileKotlin :modules:issue-tracking:compileKotlin \
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

> 🛑 **T4 가 잡은 plan 초안 결함 2건 — 아래 코드는 정정본이다** (controller 가 착수 전 지적, T4 가 처리).
> - **결함 A.** 초안의 테스트 4 는 `repo.grant("SOME_OTHER_PERMISSION", ...)` 로 시드했으나 V036 은
>   `CHECK (permission IN ('CREATE_PROJECT'))` (T2 커밋 `2ab46ee30`, D17 로 의도된 설계)라 **INSERT 자체가 불가능**하다 —
>   `false` 가 아니라 `DataIntegrityViolationException` 을 만나 단언에 도달하지 못한다. CHECK 를 푸는 대신
>   **방향을 뒤집어** 같은 술어(`g.permission = :permission`)를 겨냥했다 — `CREATE_PROJECT` 를 부여한 뒤
>   **다른 코드로 조회**해 false 를 본다. 조회 인자는 CHECK 대상이 아니라(읽기 경로) 불법 INSERT 없이 성립한다.
> - **결함 B.** 초안의 테스트 1~4 가 `grantedBy` 를 빠뜨렸다(테스트 5~7 은 넘긴다 — 초안 자기모순).
>   `grant()` 는 4-파라미터이며 **`grantedBy` 에 기본값을 두지 않는다** — 기본값은 ADR D-5 의 감사 흔적을
>   조용히 위조하는 통로다. 테스트가 `adminId` 를 명시로 넘기게 정정했다. (T2 가 RED 에서 잡은 `granted_by` 누락과 같은 계열)
> - **테스트 1건 신설 (7 → 8).** 초안의 7건 중 어느 것도 아래 `grant` 계약(`ON CONFLICT DO NOTHING` **아님**)을
>   가드하지 않는다 — `ON CONFLICT DO NOTHING` 을 붙여도 7건이 전부 통과한다. 중복 부여 → `DuplicateKeyException` 단언을 추가했다.

```kotlin
@Test
fun `USER grant 를 부여하면 hasGrant 가 true 를 반환한다`() {
    val userId = seedUser()
    repo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = adminId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `GROUP grant 는 그룹 멤버에게 전파된다`() {
    val userId = seedUser()
    val groupId = seedGroup()
    seedGroupMembership(groupId, userId)
    repo.grant("CREATE_PROJECT", GranteeType.GROUP, groupId, grantedBy = adminId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `grant 가 없으면 false 를 반환한다 (fail-closed, PM10-6)`() {
    assertThat(repo.hasGrant(seedUser(), "CREATE_PROJECT")).isFalse()
}

// 결함 A 정정 — 부여는 CHECK 가 허용하는 CREATE_PROJECT 로, 조회를 다른 코드로 뒤집는다.
// 판별자. hasGrant 에서 `g.permission = :permission` 을 지우면 이 조회가 true 가 되어 fail 한다.
@Test
fun `다른 권한코드로 조회하면 false 를 반환한다`() {
    val userId = seedUser()
    repo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = adminId)
    assertThat(repo.hasGrant(userId, "SOME_OTHER_PERMISSION")).isFalse()
}

@Test
fun `그룹에서 탈퇴하면 grant 가 사라진다`() {
    val userId = seedUser(); val groupId = seedGroup()
    seedGroupMembership(groupId, userId)
    repo.grant("CREATE_PROJECT", GranteeType.GROUP, groupId, grantedBy = adminId)
    // 탈퇴 전 선단언 — 없으면 아래 isFalse 가 "원래부터 false" 여도 통과하는 vacuous 가드가 된다
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isTrue()
    removeGroupMembership(groupId, userId)
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isFalse()
}

// ↓ D6 신설 2건 (plan-eng-review) — 회수는 보안 기능이다. 조용히 안 되면 뗐다고 믿은 권한이 살아있다.
@Test
fun `revoke 하면 hasGrant 가 false 로 돌아가고 true 를 반환한다`() {
    val userId = seedUser()
    val grant = repo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = adminId)
    assertThat(repo.revoke(grant.id)).isTrue()
    assertThat(repo.hasGrant(userId, "CREATE_PROJECT")).isFalse()
    assertThat(repo.revoke(grant.id)).isFalse()   // 없는 id → false (서비스가 404)
}

@Test
fun `list 는 부여한 grant 를 granted_by 와 함께 반환한다`() {
    val userId = seedUser()
    repo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = adminId)
    assertThat(repo.list()).singleElement().satisfies({
        assertThat(it.granteeId).isEqualTo(userId)
        assertThat(it.grantedBy).isEqualTo(adminId)   // ADR D-5 감사 흔적이 실제로 읽힌다
    })
}

// ↓ T4 신설 (8번째) — 아래 grant 계약("ON CONFLICT DO NOTHING 아님")의 유일한 가드.
// 없으면 ON CONFLICT DO NOTHING 을 붙여도 위 7건이 전부 초록이다(RETURNING 0행 → 500 으로 변질).
// 스키마 UNIQUE 단언(GlobalPermissionGrantSchemaMigrationTest)과 별개의 성질 —
// 그쪽은 "DB 가 막는다", 이쪽은 "리포지토리가 그 예외를 삼키지 않는다".
@Test
fun `같은 grantee 에 중복 부여하면 DuplicateKeyException 이 전파된다`() {
    val userId = seedUser()
    repo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = adminId)
    assertThatThrownBy {
        repo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = seedUser())
    }.isInstanceOf(DuplicateKeyException::class.java)
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
    val grantedBy: UUID,      // 감사 — ADR D-5
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

메서드 4종. **`grantedBy` 에 기본값을 두지 않는다** — 기본값은 ADR D-5 감사 흔적을 조용히 위조하는 통로다(결함 B).
- `grant(permission, granteeType, granteeId, grantedBy): GlobalPermissionGrant` — `ON CONFLICT DO NOTHING` **아님**. 중복은 `DuplicateKeyException` 으로 올라가 서비스가 409 로 매핑한다(멱등 200 이 아니라 409 인 이유 — 관리자가 "이미 있다"를 알아야 한다). 가드는 위 8번째 테스트
- `revoke(id): Boolean` — hard DELETE. 삭제 행 수 > 0 이면 true (없으면 404)
- `list(): List<GlobalPermissionGrant>`
- `hasGrant(actorId, permission): Boolean`

**REFACTOR**. SQL 상수를 `private companion object` 로 추출 + KDoc (`ProjectMembershipAdapter.kt:57-70` 동형).

**검증**. `cd backend && ./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantRepositoryIntegrationTest*'` → **8/8 PASS** (D6 로 `revoke()` · `list()` 2건 추가 + T4 가 중복부여 가드 1건 신설). **콘솔 합계가 아니라 `build/test-results/test/*.xml` 의 `tests="8"` 로 확인할 것** — `--tests` 필터를 걸어도 ArchUnit 2종(3건)이 같이 돈다(인계 사실 9).

---

### Task 5. prod 판정기 override — `hasGlobalPermission` (D14)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolverGlobalPermissionTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolverTest.kt`]
- depends-on: [3, 4]

> 🛑 **3번째 파일은 이 task 가 **깨뜨리는** 기존 테스트다 (plan-eng-review P1 — 초안이 누락).**
> `IdentityAccessSystemPermissionResolverTest.kt:26` — `private val resolver = IdentityAccessSystemPermissionResolver(repo = repo)`.
> **명명 인자**라 `grantRepo` 파라미터를 추가하는 순간 `No value passed for parameter 'grantRepo'` 로 **컴파일이 깨진다**.
> 고치는 법 — `grantRepo = mockk()` 추가. 기존 2개 테스트는 `isSystemAdmin` 만 호출하므로 스텁 불요.
> ([[plan-files-constructor-injection-existing-tests]] — *"생성자 주입은 기존 mock 테스트도 plan files"*. 이 메모리가 기록한 사고를 초안이 그대로 재현했다.)

**★ 이 task 가 스펙 B8 을 막는다.** override 를 잊으면 default(`= isSystemAdmin`)가 남아 `CREATE_PROJECT` 가 **SYSTEM_ADMIN 전용**으로 되돌아간다 — **Maxi 가 D7 에서 기각한 바로 그 안**이다. fail-closed 라 사고는 안 나지만 **아무도 모르게 기능이 사라진다**.

**RED**. **판별자는 "비-SYSTEM_ADMIN + grant"** 다 — 이것만이 override 유무를 가른다 (C2 — 판별력 없는 단언 금지).

> 🛑 **정정 (T5 실측) — 결함 B 계열이 여기서도 반복됐다 (3번째).**
> 아래 테스트 1·4 의 초안은 `grantRepo.grant("CREATE_PROJECT", GranteeType.USER, userId)` 로 **`grantedBy` 를 빠뜨렸다**.
> T4 가 확정한 실제 시그니처는 `grant(permission, granteeType, granteeId, grantedBy)` 4-파라미터이고 **기본값이 없다**
> (ADR D-5 감사 흔적을 조용히 위조하지 않기 위한 의도된 설계) — 그대로 쓰면 **컴파일이 깨진다**.
> **기본값을 추가하지 않고** 테스트가 `adminId` 를 명시로 넘긴다 (T2·T4 와 동일한 처리).

```kotlin
@Test
fun `grant 보유 비-SYSTEM_ADMIN 이 전역권한을 획득한다 (B8 회귀 가드 · DoD-9 PR-1 판)`() {
    val userId = seedUser()
    grantRepo.grant("CREATE_PROJECT", GranteeType.USER, userId, grantedBy = adminId)

    // ★ 판별자 — 이 사람이 SYSTEM_ADMIN 이 아님을 먼저 못박는다.
    //   override 가 없으면 default 가 isSystemAdmin 에 위임해 false 가 되고 이 테스트가 red 로 잡는다.
    assertThat(resolver.isSystemAdmin(userId)).isFalse()
    assertThat(resolver.hasGlobalPermission(userId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `SYSTEM_ADMIN 은 grant 없이도 전역권한을 보유한다`() {
    val adminUserId = seedUser()
    seedSystemAdmin(adminUserId)
    assertThat(resolver.hasGlobalPermission(adminUserId, "CREATE_PROJECT")).isTrue()
}

@Test
fun `grant 도 SYSTEM_ADMIN 도 아니면 false (PM10-6 fail-closed)`() {
    assertThat(resolver.hasGlobalPermission(seedUser(), "CREATE_PROJECT")).isFalse()
}

@Test
fun `GROUP grant 보유 비-SYSTEM_ADMIN 이 전역권한을 획득한다`() {
    val userId = seedUser(); val groupId = seedGroup()
    seedGroupMembership(groupId, userId)
    grantRepo.grant("CREATE_PROJECT", GranteeType.GROUP, groupId, grantedBy = adminId)
    assertThat(resolver.isSystemAdmin(userId)).isFalse()
    assertThat(resolver.hasGlobalPermission(userId, "CREATE_PROJECT")).isTrue()
}
```

**부팅 방식 (T5 실측).** `@JdbcTest` + Testcontainers (T4 패턴 복제). `@JdbcTest` 는 `@Component`/`@Repository` 를
스캔하지 않으므로(인계 사실 #3) **판정기 + 협력자 2종을 명시 `@Import`** 한다 —
`@Import(IdentityAccessSystemPermissionResolver::class, GlobalPermissionGrantRepository::class, JdbcSystemRoleAssignmentRepository::class)`.
`seedSystemAdmin` 은 `system_role_assignments`(V012) 직접 INSERT 이며, 같은 테이블의 `user_id` FK 때문에 실 users 행이 필요하다.

> **`@ActiveProfiles("prod")` 를 달지 않는다.** C1 의 근거인 항상-`true` 스텁(`NonProdAllowSystemAdminResolver`)은
> **issue-tracking 소속**이라 identity-access 스캔 경로에 없고, `IdentityAccessSystemPermissionResolver` 는
> `@Profile` 이 아예 없어(KDoc *"AlwaysAllow stub 을 두지 않는다"*, ADR D4) 모든 프로파일에서 실제 판정한다.
> **C1 은 PR-2 제약이다** (위 §스펙 정정 3).

**실패 메시지 (예상)**. `expected: true but was: false` (default 위임이 살아 있음).

**GREEN**. override 추가. **생성자에 `GlobalPermissionGrantRepository` 주입** — 기존 `repo: SystemRoleAssignmentRepository` 는 유지.

> **ADR 번호 정정 (T5).** 판정식의 정본은 **ADR D-2** 다. 산문의 "D14"·"D7" 은 plan/review 번호이지 ADR 번호가
> **아니다** (인계 사실 #12 — T2 가 같은 계열을 이미 정정했다). 아래 KDoc 은 정정된 번호를 반영한 최종본이다.

```kotlin
    /**
     * 전역 권한 [permission] 보유 여부 (FR-PM-10, ADR D-2).
     *
     * 판정 = grant 보유 **또는** SYSTEM_ADMIN. SYSTEM_ADMIN 은 전역 권한의 상위집합이므로
     * 별도 grant 없이 통과한다 — FR-PM-08 ADR D3 "전역 판정 = SYSTEM_ADMIN 보유 여부" 와의 연속성이며,
     * 빈 DB 에서 CREATE_PROJECT 보유자가 0명이 되는 부트스트랩 공백을 막는다.
     *
     * ## 이 override 를 지우면 안 된다
     * 지우면 인터페이스 default 가 살아나 판정이 isSystemAdmin 과 같아지고, FR-PM-10 이
     * 조용히 SYSTEM_ADMIN 전용으로 되돌아간다(기각된 안). fail-closed 라 장애로 드러나지도 않는다.
     * 회귀 가드는 IdentityAccessSystemPermissionResolverGlobalPermissionTest 의
     * "grant 보유 비-SYSTEM_ADMIN" 케이스이며, 그 테스트의 판별자는 행위자가 SYSTEM_ADMIN 이
     * 아님을 먼저 못박는 선단언이다.
     */
    override fun hasGlobalPermission(
        actorId: UUID,
        permission: String,
    ): Boolean = grantRepo.hasGrant(actorId, permission) || isSystemAdmin(actorId)
```

**REFACTOR**. 클래스 KDoc 에 FR-PM-10 확장 1줄 + `@see` 추가.

**검증**. `cd backend && ./gradlew :modules:identity-access:test --tests '*IdentityAccessSystemPermissionResolverGlobalPermissionTest*'` → 4/4 PASS.
**★ mutation 확인 (필수)** — override 를 주석 처리하면 1·4번이 **fail** 해야 한다. 안 하면 vacuous ([[verify-logic-vs-verify-guard]]·[[archunit-vacuous-rule-silent-pass]]).

---

### Task 6. `GlobalPermissionGrantController` — grant/revoke/list API

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantException.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/GlobalPermissionGrantController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/GlobalPermissionGrantControllerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/GlobalPermissionGrantServiceTest.kt`]
- depends-on: [4]

> **🛑 files 확장 1건 — `GlobalPermissionGrantServiceTest.kt` (T6 발견 · 결함 D).** 초안은 테스트 파일을
> `GlobalPermissionGrantControllerTest` 하나만 뒀는데, 그 테스트는 **슬라이스라 서비스를 mock 한다**
> (선례 `UserGroupControllerTest.kt:138` = `mockk()`). 즉 예외는 mock 이 던지고 **서비스의 실제 로직은
> 한 줄도 실행되지 않는다** — grantee 존재 검증(ADR D-4 = FK 생략의 근거) · permission 선검증(ADR D-1
> 이중 방어의 앱 겹) · `DuplicateKeyException`→409 변환이 **전부 무가드**가 된다. 존재 검증을 통째로
> 지워도 컨트롤러 테스트는 전부 초록이다. 모듈 관례가 이미 답을 정해 뒀다 —
> `UserGroupService`/`UserGroupServiceTest`(순수 mockk)/`UserGroupControllerTest`(슬라이스) **3종 세트**.
> 같은 형태로 서비스 단위 테스트를 신설한다. 설계 판단이 아니라 선례 적용이다.

**선례를 그대로 따른다 — `UserGroupController`** (같은 모듈·같은 SYSTEM_ADMIN 게이트·같은 PAT 대응).

> **grantee 존재 검증의 협력자를 명시한다 (plan-eng-review P2 — 초안이 누락).** ADR D-4 가 *"서비스 층이 존재 검증"* 을
> **FK 생략의 근거**로 삼았으므로 이 검증은 선택이 아니다. `GlobalPermissionGrantService` 는 `granteeType` 에 따라
> **`UserRepository`(USER) 와 `UserGroupRepository`(GROUP) 두 곳을 조회**한다 — 선례 `UserGroupController.kt:186`
> (`userRepository.findByIds(...)`) · `:286-287`(404 매핑). 예외는 `GlobalPermissionGrantException` sealed 아래
> `GranteeNotFoundException` · `DuplicateGrantException` · `UnknownPermissionException` 3종.
> 이걸 안 적으면 구현자가 (a) 임의로 리포지토리를 주입해 [[plan-files-constructor-injection-existing-tests]] 를 밟거나
> (b) 검증을 생략해 **ADR D-4 를 거짓으로 만든다**.

**RED**. `GlobalPermissionGrantControllerTest` (`@WebMvcTest` + `SystemPermissionResolver` mock. 선례 `UserGroupControllerTest.kt:59`)
**+ `GlobalPermissionGrantServiceTest`** (순수 mockk. 선례 `UserGroupServiceTest.kt:44`).

> **🛑 결함 A — 개수 자기모순 (controller 선발견).** 아래 목록은 기존 7 + D6 신설 3 = **10** 인데 검증 줄이
> `7/7` 에 멈춰 있었다. **"7" 은 눈가리개다** — T4 가 정확히 같은 함정에서 무가드 계약 1건을 찾아냈다
> ([[spec-stated-count-becomes-blindfold]]). 개수를 목표로 삼지 않고 **계약을 전수 열거**한 결과가 아래이며,
> 최종 실측은 **컨트롤러 13 + 서비스 10 = 23** 이다.

```kotlin
// ── GlobalPermissionGrantControllerTest (13) ──
@Test fun `SYSTEM_ADMIN 이 grant 를 부여하면 201`()                  // + grantedBy = 인증 actorId 인자 고정
@Test fun `비-SYSTEM_ADMIN 의 grant 부여는 403`()
@Test fun `비-SYSTEM_ADMIN 의 목록 조회는 403`()
@Test fun `403 응답 본문에 권한 내부 구조가 없다`()   // PJ1-8 · [[fr-pm-04-guard-exception-message-http-leak]]
@Test fun `존재하지 않는 grantee 부여는 404`()
@Test fun `중복 부여는 409`()
@Test fun `revoke 는 204`()

// ↓ D6 신설 3건 (plan-eng-review)
@Test fun `PAT 로 인증한 SYSTEM_ADMIN 이 grant 를 부여하면 201`()   // ★ D4 의 근거 자체를 잠근다
@Test fun `미인증 요청은 401`()                                      // 필터 체인 (/api/** authenticated)
@Test fun `미지 permission 부여는 400`()                             // D3 — 서비스 겹

// ↓ T6 신설 3건 — 무가드 계약 전수 점검 결과
@Test fun `존재하지 않는 grant 회수는 404`()          // 🛑 결함 C. ADR D-5 + 리포지토리 Boolean 계약
@Test fun `비-UUID subject 는 401`()                  // resolveActorId 실패 경로 (미인증 401 과 다른 코드 경로)
@Test fun `SYSTEM_ADMIN 의 목록 조회는 200`()         // list 양성 — 403 만 있으면 GET 이 깨져도 초록
```

> **🛑 결함 C — `revoke 0행 → 404` 가 무가드였다 (T6 발견).** ADR **D-5** 가 *"삭제 행 수가 0 이면 404 로
> 거부한다 — 지웠다고 믿었는데 대상이 없었다를 조용히 성공으로 만들지 않는다"* 를 못박았고 T4 리포지토리는
> 그 목적 하나로 `Boolean` 을 반환한다(`GlobalPermissionGrantRepository.kt:87` KDoc *"호출 측이 404 로
> 거부한다"*). 그런데 10건 중 `revoke 는 204` 만 있어 **반환값을 버려도 전부 초록**이다. 게다가 초안의
> **예외 3종으로는 이 상태를 표현할 수조차 없다**(`GranteeNotFoundException` 은 grantee 축이지 grant 행 축이
> 아니다) → sealed 하위를 **4종**으로 늘린다: `GranteeNotFoundException`·`DuplicateGrantException`·
> `UnknownPermissionException`·**`GrantNotFoundException`**.

```kotlin
// ── GlobalPermissionGrantServiceTest (10) — 결함 D 로 신설 ──
@Test fun `USER 부여는 users 존재검증 후 위임하고 grantedBy 를 그대로 넘긴다`()
@Test fun `존재하지 않는 USER grantee 는 GranteeNotFoundException — 부여 미호출`()   // ★ ADR D-4 의 가드
@Test fun `GROUP 부여는 user_groups 존재검증 후 위임한다`()
@Test fun `존재하지 않는 GROUP grantee 는 GranteeNotFoundException — 부여 미호출`()  // ★ ADR D-4 의 가드
@Test fun `미지 permission 은 UnknownPermissionException — 리포지토리 도달 전 차단`() // ADR D-1 앱 겹
@Test fun `중복 부여 DuplicateKeyException 은 DuplicateGrantException 으로 변환`()
@Test fun `존재하지 않는 grant 회수는 GrantNotFoundException`()
@Test fun `grant 회수 성공은 예외 없음`()
@Test fun `list 는 리포지토리 결과를 그대로 위임`()
@Test fun `Annotation 회귀 가드 — Service + Transactional`()                          // 절대 규칙 9
```

> ★ **PAT 양성 테스트가 이 task 에서 가장 중요하다.** D4 는 *"PAT 를 지원하려고 명시 호출을 쓴다"* 로 패턴을 정했다.
> 그런데 JWT 테스트만 있으면 **누군가 `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` 로 바꿔도 전부 초록**이다 —
> `PatAuthenticationFilter.kt:48` 이 `ROLE_PAT` 만 주므로 PAT 는 그때부터 403 인데 아무도 모른다.
> 즉 **결정의 근거가 미검증으로 남는다.** 선례 — `AuthAuditLogAdminControllerTest.kt:113-115` 가
> `ROLE_PAT` authority 로 정확히 이 축을 테스트한다(그쪽은 403 을 기대하는 음성 테스트, 여기는 201 을 기대하는 양성).

**GREEN**. `UserGroupController.kt` 의 5요소를 동형 이식한다 (**복사가 아니라 동형** — 실측한 선례 구조).

> **🛑 결함 B — `grant(...)` 시그니처 (T2·T4·T5 에 이어 4연속 재발 계열).** 실제 시그니처는
> **`grant(permission, granteeType, granteeId, grantedBy)` 4-파라미터·기본값 없음**이다
> (`GlobalPermissionGrantRepository.kt:62-67` 실측). ADR **D-5** 가 *"기본값을 두지 않는다 — 기본값은 감사
> 흔적을 조용히 위조하는 통로"* 라고 의도를 명시했으므로 **기본값을 추가하지 않고**, 서비스가
> `grantedBy = 인증된 actorId` 를 **명시로** 넘긴다. 컨트롤러 테스트 1번이 mock 인자를 `ADMIN_ID` 로
> 고정해 이 배선을 잠근다(느슨한 `any()` 를 쓰면 grantee_id 를 넘겨도 초록이 된다).

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

**검증** (**결함 A 정정 — `7/7` 은 D6 3건 추가 시 안 고친 자기모순이었다**).
```bash
cd backend
./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantControllerTest*'   # 13/13
./gradlew :modules:identity-access:test --tests '*GlobalPermissionGrantServiceTest*'      # 10/10
./gradlew :modules:identity-access:test          # 모듈 전체 — 슬라이스 회귀 전수 (약 8분)
./gradlew :modules:identity-access:ktlintCheck :modules:identity-access:detekt --rerun-tasks
```
XML `tests="N"` 을 직접 확인한다 — `--tests` 필터에도 ArchUnit 2종이 동반 실행돼 콘솔 합계가 부풀려진다(인계 사실 #8).

**★ mutation 확인 (필수)** — `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` 로 게이트를 바꾸면 **PAT 양성 테스트가
fail** 해야 한다. `PatAuthenticationFilter.kt:48` 이 `ROLE_PAT` 만 주므로 PAT 는 그 순간 403 이 된다 — 이게
D4 의 근거(*"PAT 지원 때문에 명시 호출"*)를 실증으로 바꾸는 유일한 가드다. 기준선 PASS 를 **먼저** 확인할 것.

---

### Task 7. `ProjectMembershipWritePort` — 포트 + 어댑터 + 트랜잭션 참여 실증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/membership/ProjectMembershipWritePort.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipWriteAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipWriteAdapterTest.kt`]
- depends-on: []

**★ 이 저장소에 선례가 없는 유일한 설계다.** 스펙 §4.4 N5 — *"cross-BC 쓰기를 호출자 트랜잭션 안에서 하는 선례는 이 저장소에 없다"*. 그래서 **실증이 필수**다.

> 🛑 **무애노테이션이 절대 규칙 9 위반이 아닌 근거 (bts-impl 선행읽기에서 실측 · `/bts-codereview` 인계).**
> `DEVELOPMENT.md §1.2` 규칙 9(*"트랜잭션 경계 명시. `@Transactional` 누락 시 코드리뷰 BLOCKER"*)와
> 도메인 노트 `identity-access.md:39`(*"`TransactionalServiceArchTest` ArchUnit 룰이 자동 검증"*)를 보면
> 이 어댑터가 걸릴 것 같지만, **룰의 방향이 반대다**.
>
> - `TransactionalServiceArchTest.kt` 룰 원문 — *"`@Transactional` 이 붙은 메서드를 1개 이상 보유한 클래스는
>   `@Service`/`@Component`/`@Repository`/`@Configuration` 중 하나로 선언되어야 한다"*.
>   즉 **`@Transactional` → 빈이어야 함**이지 **빈 → `@Transactional` 이어야 함이 아니다**.
>   PR #6 의 `LocalCredentialService` 사고(빈 아닌 클래스의 `@Transactional` 이 무음 무력화)를 막는 룰이다.
>   **`@Transactional` 없는 `@Component` 는 이 룰이 발화조차 하지 않는다.**
> - `DATA.md §6` 이 예고한 예외 애노테이션 **`@TransactionalAware` 는 미구현**(전 모듈 히트 0). 쓰지 말 것.
> - `DATA.md §6` 의 *"Detekt 커스텀 룰로 빌드 차단"* 도 **미구현**("(Phase 1)" 예고).
>
> **규칙 9 의 정신도 위반이 아니다** — 이 어댑터는 경계의 **소유자가 아니라 참여자**이고, 경계 명시는
> 호출자(PR-2 의 프로젝트 생성 서비스)가 한다. 경계를 여기서 또 선언하면 I1 이 깨진다.
> **이 근거를 어댑터 KDoc 에 남긴다** — 안 남기면 `/bts-codereview` 가 규칙 9 를 문자 그대로 읽고 BLOCKER 를 낸다.

**RED**. **트랜잭션 참여를 직접 증명한다** (DoD-11 의 PR-1 판 — D16).

> 🛑 **테스트 클래스를 반드시 비트랜잭션으로 고정한다 (§C10-2 — plan-eng-review P0).**
> `@JdbcTest` 는 `@Transactional` 메타라 기본값대로 두면 **올바른 구현에서도 fail** 하고 두 경우가 구분되지 않는다.
> 선례 `RefreshTokenRepositoryTest.kt:230`.

```kotlin
// ProjectMembershipWritePort 어댑터 — 호출자 트랜잭션 참여 실증 (스펙 §4.4 · DoD-11 PR-1 판)

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // ★ §C10-2 필수. 없으면 판별력 0
class ProjectMembershipWriteAdapterTest {
    // companion object = ProjectMembershipRepositoryIntegrationTest.kt:44-60 동형

    @AfterEach                                             // ★ 테스트 tx 가 없으니 수동 정리
    fun cleanup() = jdbc.update("DELETE FROM project_memberships WHERE project_id = :p", mapOf("p" to projectId))

    @Test
    fun `호출자 트랜잭션이 롤백되면 addCreatorAsAdmin 도 롤백된다`() {
        val userId = seedUser()
        val projectId = UUID.randomUUID()   // project_id 는 FK 가 없다 (V007:5 cross-BC) — 실 projects 행 불요

        assertThatThrownBy {
            txTemplate.execute {
                adapter.addCreatorAsAdmin(projectId, userId)
                throw IllegalStateException("의도적 실패 — 롤백 유발")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        // 어댑터가 자기 트랜잭션을 열었다면(REQUIRES_NEW / TransactionTemplate) 이 행이 커밋돼 살아남는다.
        // NOT_SUPPORTED 라 이 SELECT 는 tx 밖에서 실행되므로 "미커밋을 내가 읽는" 오염이 없다.
        assertThat(countMemberships(projectId, userId)).isZero()
    }

    @Test
    fun `호출자 트랜잭션이 커밋되면 addCreatorAsAdmin 이 영속된다`() {
        val userId = seedUser()
        val projectId = UUID.randomUUID()
        txTemplate.execute { adapter.addCreatorAsAdmin(projectId, userId) }
        assertThat(countMemberships(projectId, userId)).isOne()   // ★ 진짜 커밋을 확인한다
    }
}
```

**★ mutation 순서를 뒤집지 말 것 ([[verify-logic-vs-verify-guard]] — 기준선 EXIT=0 선확인).**
1. **먼저** 무애노테이션 어댑터로 **2/2 PASS** 를 확인한다. ← 이게 기준선. 여기가 red 면 테스트가 틀린 것이지 어댑터가 틀린 게 아니다
2. **그 다음** 어댑터에 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 를 임시로 달아 1번이 **fail** 하는지 본다
3. 1번을 건너뛰고 2번만 하면 §C10-2 의 vacuous 통과를 그대로 재현한다

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
 * 파라미터는 원시 타입(UUID)만 쓴다. identity-access 도메인 타입(ProjectRole 등)을 쓰면 두 단계로
 * 막힌다 — 1차는 Gradle 클래스패스(shared-kernel 에 modules 의존이 0건이라 unresolved reference 로
 * 컴파일이 먼저 죽는다), 2차는 SharedKernelBoundaryArchTest.
 *
 * ### 트랜잭션 계약
 * 구현체는 트랜잭션 경계를 **선언하지 않는다**. 호출자 트랜잭션에 참여하는 것이 이 포트의 존재 이유다.
 *
 * @see ProjectMembershipPort 같은 fail-closed 원칙을 따르는 읽기 짝
 */
interface ProjectMembershipWritePort {
    /**
     * 프로젝트 생성자를 그 프로젝트의 PROJECT_ADMIN 으로 등록한다. 호출자 트랜잭션에 참여한다.
     *
     * 역할은 파라미터가 아니다 — 이 포트의 유일한 용도가 FR-PJ-01 의 생성자 등록이고 역할은 항상
     * PROJECT_ADMIN 이다. 일반 멤버 관리는 identity-access 내부(ProjectMemberController)에 있고
     * 이 포트를 타지 않는다. 역할을 열어두면 "임의 사용자를 임의 프로젝트의 관리자로" 만드는
     * 무가드 프리미티브의 표면만 넓어진다.
     *
     * 멱등이 아니다 — project_memberships 의 UNIQUE(project_id, user_id) 로 2회 호출은 실패한다.
     * 신규 프로젝트에서만 호출되므로 충돌이 성립하지 않는다.
     */
    fun addCreatorAsAdmin(
        projectId: UUID,
        userId: UUID,
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

**검증**. `cd backend && ./gradlew :modules:identity-access:test --tests '*ProjectMembershipWriteAdapterTest*'` → 2/2 PASS.
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
    // ⚠️ 이 단언은 PR-1 코드 0줄에서도 이미 초록이다 — RED 아님(아래 참조). 이름 고정 회귀 가드일 뿐.
    assertThat(
        context.containsBean("com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver"),
    ).isTrue()
}
```

> **B8(override 누락)은 여기서 안 잡는다 — T5 가 잡는다.** 리플렉션으로 `declaringClass` 를 보는 방법은
> 빈이 CGLIB 프록시면 프록시 클래스를 반환해 **헛fail** 한다. 역할 분담이 맞다 —
> **T5** 가 *"그 클래스가 override 를 갖는가"* 를 행위로 증명하고, **T8** 이 *"그 클래스가 prod 조립에 있는가"* 를
> 이름으로 고정한다. 둘을 합치면 B8 이 덮인다.

> 🛑 **T8-2 는 RED 가 될 수 없다 — TDD 라 주장하지 말 것 (plan-eng-review P3, 초안이 거짓 실패를 예고했다).**
> `IdentityAccessSystemPermissionResolver.kt:30-31` 은 `@Component` 이고 **`@Profile` 이 없다**(KDoc `:20-25` 가 그 사실을 명시).
> `BtsApplicationContextTest.kt:24` 는 `ProdAssemblyHttpTestBase` 상속으로 **지금도** prod 조립을 띄운다. → 이 빈은 **main 에서 이미 존재**한다.
> `/bts-impl` 이 `test:` → `feat:` 순서를 git log 로 강제하므로 이 테스트의 RED 커밋은 **형식만 남고 실질이 없다**.
> 가치는 인정한다(다음 사람이 `@Profile("prod")` 를 붙이면 이 가드가 잡는다) — 다만 **"RED 를 봤다"고 보고하지 않는다**.

**실패 메시지 (예상)**.
- **T8-1** (`ProjectMembershipWriteAdapter`) — `expected: true but was: false` (빈 미등록). **진짜 RED.**
- **T8-2** (`IdentityAccessSystemPermissionResolver`) — **RED 없음.** 처음부터 초록이다(위 참조).

**GREEN**. T7 의 `@Component` 어댑터가 identity-access 스캔에 잡히면 통과. **안 잡히면 중앙 스캔 확인** ([[shared-kernel-component-extraction-scan-regression]]).

**REFACTOR**. 없음.

**검증**.
```bash
docker compose -f infra/docker-compose.dev.yml up -d postgres   # 5433 — app 테스트 전제
cd backend && ./gradlew :modules:app:test
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
5. `docs/plan/product/issue-tracking.md` — FR-PJ § 신설 · D 체크박스 **전부 미틱**(PR-2~5) · 같은 5개 카운트 표기 · **★ BC 완료 게이트 `:495`** (아래)
6. `docs/plan/README.md` — §1 BC 테이블 행 · 합계
7. `CLAUDE.md` — `123 FR` → `128 FR`. **워크트리 판만** (아래)
8. `DATA.md:45` **drift 정정** — *"jOOQ 기본 쿼리는 `deleted_at IS NULL` 필터 자동 첨부 (`SoftDeleteFilter` 래퍼)"* 는 **허구**(`.kt` grep 히트 0). 실제는 repository 마다 수동. **아카이브 술어(PR-4)가 이 문장을 근거로 오독하면 곧 보안 버그**라 지금 고친다
9. `docs/progress.html` — `node scripts/build-dashboard.mjs` 재생성

> 🛑 **`CLAUDE.md` 는 워크트리 판만 고친다 (plan-eng-review P2 — 초안이 *"main 판도"* 라 적었다).**
> `verify-master-plan.sh:18` `REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"` + `:82` `CLAUDE_MD="${REPO_ROOT}/CLAUDE.md"`
> → 워크트리에서 실행하면 **워크트리 판만** 검사한다. main 판 편집은 **무효과**이고, `CLAUDE.md §핵심 패턴`
> (*"`.worktrees/<slug>` 안에서만 Edit/Write. **main 트리 오염 차단**"*)을 **이 plan 이 스스로 위반**하는 것이다.
> main 판은 머지가 가져간다. #276 이 병렬 중이라 실제 위험이다([[multisession-git-branch-checkout-hijack]]·[[worktree-lint-staged-shared-git-stash-collision]]).

> 🛑 **issue-tracking BC 완료 게이트 + verify 룰 확장 (plan-eng-review P2 — 초안이 누락).**
> 실측 `docs/plan/product/issue-tracking.md:495` — `- [ ] §2~§6 (30 FR) 모두 [x] 마킹`.
> **FR-PJ 는 §7 이 되어 이 범위 밖**이다 → 고치지 않으면 issue-tracking BC 가 **FR-PJ-01~04 미구현 상태로 "완료" 선언 가능**해진다.
> → `§2~§7 (35 FR)` 로 갱신. (참고 — `:495` 의 `30` 은 이미 stale 이다. 같은 파일 `:5` 는 `소속 FR. 31개`.)
>
> **그리고 `verify-master-plan.sh` 가 이걸 구조적으로 못 잡는다.** 룰 D 정규식은 `\(FR-[A-Z]+,? *[0-9]+개\)` 인데
> 이 줄은 `(30 FR)` 형식이라 **매칭 0 → 종료 0 으로 통과**한다. `CLAUDE.md §전수 동기화` 가 못박은 대로
> **verify 도 같은 PR 에서 확장**한다 — 정규식을 `\((?:FR-[A-Z]+,? *)?[0-9]+ ?(?:개|FR)\)` 로 넓히고
> **일부러 위반을 넣어 fail 을 확인**한다([[archunit-vacuous-rule-silent-pass]] — 룰은 오타 하나로 vacuous PASS 가 된다).
> 초안이 *"통과는 충분조건이 아니다"* 라 적어놓고 정작 그 사각지대의 실례를 놓쳤다.

> **범위 밖 (건드리지 않는다).** `docs/plan/product/issue-tracking.md:171` `(7개)` → 실제 8 drift 는 **무관한 섹션**이라 분리한다 (글로벌 CLAUDE.md §3 surgical). `FlywayAssemblyConfig.kt:13` KDoc V번호 drift 도 동일.

> **후속 TODO (D5 — 이번 PR 범위 밖, 기록만).** identity-access 컨트롤러의 `resolveActorId`(6파일) ·
> `requireSystemAdmin`(4파일) · `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE`(**13파일**) 복제. PR-1 이 14번째 사본을 만든다.
> 공통화는 13파일 리팩토링이라 권한 PR 에 섞으면 둘 다 제대로 못 본다 — 별도 작업으로 분리한다. `TODOS.md` 에 기록.
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

## 구현 진행 (/bts-impl, 2026-07-17)

### wave 1 ✅ 졸업 (3/9) — controller 가 git log 직접 수집해 검증

| task | TDD 커밋 (실측 순서) | 판정 |
|---|---|---|
| **T1** ADR | `1cd02d407 docs:` — 문서 전용, TDD 사이클 불요 | ✅ PASS |
| **T3** shared-kernel default | `a4122bfd4 test:` → `7fec9f176 feat:` → `ce6d95362 refactor:` | ✅ PASS |
| **T7** 포트+어댑터 | `6b14e0c3f test:` → `6953cf8fe feat:` → `8f1c71ab5 refactor:` | ✅ PASS |

**선언 외 파일 0건.** 브랜치 전체 변경 6파일 = T1(1) + T3(2) + T7(3).

**T3 가 §2.7 의 "구현체 6곳"을 확증했다** — 무변경 컴파일 성공. prod 2(`IdentityAccessSystemPermissionResolver` · `NonProdAllowSystemAdminResolver`) + test 4(search `WebhookIntegrationConfig` · notification `TestPermissionConfig` · issue `Require2faTestPermissionConfig` · slack `StubSystemPermissionResolver`).

**T7 이 §C10-2(P0 수정)가 실제로 작동함을 실증했다.**
```
① 기준선(무애노테이션)     → 2/2 PASS
② REQUIRES_NEW 임시 주입   → 롤백 테스트만 FAIL (expected: 0 but was: 1)
③ 되돌림                   → diff --stat 빈 출력, 재실행 2/2 PASS
```
`NOT_SUPPORTED` 가 없었으면 ①도 fail 이라 ②가 vacuous 했을 것이다. 코드 실측 — 어댑터에 `@Transactional`/`TransactionTemplate` **import 0건**(매칭은 KDoc 산문뿐), 테스트 `:60` 에 `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 실재.

### wave 2 ✅ 졸업 (4/9) — controller 가 재실행·XML 로 직접 검증

| task | TDD 커밋 (실측 순서) | 판정 |
|---|---|---|
| **T2** V036 | `18e8b1c32 test:` → `2ab46ee30 feat:` (refactor 없음 — plan 대로 생략) | ✅ PASS |

**선언 외 파일 0건.** `git diff --stat` = 2 files, 152 insertions (V036 SQL 47 + 테스트 105).

**controller 재검증 실측** (에이전트 보고를 믿지 않고 직접 실행 — [[subagent-ktlint-false-green-controller-verify]]).
- `GlobalPermissionGrantSchemaMigrationTest` XML = `tests="3" failures="0"`, testcase 이름 3개 실재
- 회귀 `PermissionSchemaMigrationTest` XML = `tests="10" failures="0"` → **PM10-5 확정**(§리스크 4행의 UNKNOWN 해소). V036 은 새 테이블만 만들어 `role_permissions` 를 안 건드리므로 `:79` 의 `isEqualTo(17)` 가드와 접점이 없다 — **전제가 아니라 실행으로 확정됐다**
- `ktlintCheck` + `detekt --rerun-tasks` = BUILD SUCCESSFUL, `8 executed`(up-to-date 0 = 진짜 재실행)

**🛑 T2 가 잡은 plan 결함 1건 (에이전트가 고쳐서 진행, controller 승인).**
plan 의 RED 테스트 1·2 가 `granted_by` 를 INSERT 에서 누락했다. 스키마는 `granted_by NOT NULL`(ADR D-5)이라 —
- 테스트 2 는 **깨진다** (setup INSERT 가 NOT NULL 로 터져 `DuplicateKeyException` 단언에 도달 못 함)
- 테스트 1 은 **틀린 이유로 통과한다** — NOT NULL 위반도 `DataIntegrityViolationException` 이라 `grantee_type` CHECK 를 통째로 지워도 초록. **vacuous 가드**

ADR 이 `granted_by NOT NULL` 을 명시하고 plan 의 테스트 3 은 이미 넘기고 있어 해석이 하나로 결정된다(설계 판단 아님) → 세 INSERT 모두 `granted_by` 를 채워 **각 INSERT 가 겨냥한 제약 하나만** 위반하게 했다. **mutation 으로 판별력 실증** — 기준선 PASS 선확인 → 두 CHECK + UNIQUE 제거 → 3/3 fail → 복원 → 3/3 PASS ([[verify-logic-vs-verify-guard]]). 테스트 2 의 두 번째 INSERT 는 `granted_by` 만 다르다 — UNIQUE 가 3컬럼이라는 게 판별자다.

**ADR 대조 결과 (T2 에 명시 인계된 필수 작업).**
- **고아 행 문구 — 의미 일치**, 정정 불요. ADR 표(GROUP=CASCADE 탈락 / USER=영구 잔존)를 정본으로 SQL 주석에 반영. `V015:12-13` CASCADE 실측 확인
- **🛑 D 번호 인용 정정** — plan 500행이 `permission` CHECK 근거를 `// D3` 로 인용하나 그건 **plan-eng-review 번호**다. **ADR 실제 D-3 은 default 메서드 포트(Task 3)** 이고 permission CHECK 근거는 **ADR D-1** 안에 있다 (controller 가 ADR 헤더 실측으로 확인 — D-1 신규테이블 / D-2 판정식 / D-3 default메서드 / D-4 FK없음 / D-5 granted_by). 테스트 주석을 `ADR D-1` 로 정정했다. **plan 산문의 "D3"·"D7" 은 plan-eng-review 번호이므로 ADR 번호로 읽지 말 것**
- 부수 — plan 의 `V015:19/:20` 은 off-by-one(실제 18-19 / 19). ADR 표기가 맞아 그쪽을 따랐다. `V008:25/26/39`·`V007:5` 는 실측 정확
- `init_codegen.sql` 미러 불요 **실측 확인** — identity-access 는 `build.gradle.kts` 에 jOOQ 없고 해당 파일도 없다(issue-tracking·notification·agile-planning·search-export-import 4개 모듈에만 존재)

### wave 3 ✅ 졸업 (5/9) — controller 가 재실행·XML 로 직접 검증

| task | TDD 커밋 (실측 순서) | 판정 |
|---|---|---|
| **T4** Repository | `291e31add test:` → `389bebf9e feat:` → `b6c220658 refactor:` (선행 `d24592c4f docs:` = plan 정정) | ✅ PASS |

**선언 외 파일 0건.** 4 files, 538 insertions = files 3 + plan 정정.

**controller 재검증 실측.** XML `tests="8" failures="0"`, testcase 이름 8개 실재. `ktlintCheck`+`detekt --rerun-tasks` = `8 executed`(up-to-date 0).
> **콘솔은 11 이라고 나온다** — 인계 사실 #9 (ArchUnit 2종 동반실행). XML 이 판단 근거다.

**🛑 T4 가 처리한 plan 결함 3건.**
1. **결함 A — 테스트 4 가 깨진다** (controller 선발견). plan 이 `repo.grant("SOME_OTHER_PERMISSION", ...)` 로 시드하나 V036 의 `CHECK (permission IN ('CREATE_PROJECT'))`(D17 확정 설계)가 **INSERT 자체를 막는다** → `false` 가 아니라 예외. **CHECK 를 풀지 않고 방향을 뒤집어** 의도를 살렸다 — `CREATE_PROJECT` 부여 후 `hasGrant(userId, "SOME_OTHER_PERMISSION")` 이 false 인지 본다(조회 인자는 읽기 경로라 CHECK 대상 아님). mutation 2 로 판별력 실증.
2. **결함 B — 테스트 1~4 가 `grantedBy` 누락** (controller 선발견, plan 자기모순 — 5~7 은 넘긴다). **기본값을 주지 않았다** — ADR D-5 감사 흔적을 조용히 위조하는 통로가 된다. 테스트가 `adminId` 를 명시로 넘긴다.
3. **🛑 무가드 계약 1건 → 8건으로 신설** (T4 자체 발견). plan 764행이 `grant` 는 **`ON CONFLICT DO NOTHING` 아님**(중복 → 409)을 계약으로 못박았으나 **7건 중 어느 것도 이를 가드하지 않았다**. 중복부여 → `DuplicateKeyException` 단언 신설. **"7건"이라는 개수가 눈가리개였다** ([[spec-stated-count-becomes-blindfold]]).

**mutation 판별력 실증** (기준선 8/8 PASS 선확인 → 주입 → 복원 `git diff --stat` 빈 출력).

| 주입 | 결과 |
|---|---|
| `hasGrant` GROUP 가지 제거 | `failures="2"` — `GROUP 전파` + `그룹 탈퇴` 정확히 2건 |
| permission 필터 무력화(`:permission IS NOT NULL`) | `failures="1"` — 재설계한 `다른 권한코드` 1건 |
| `ON CONFLICT DO NOTHING` 주입 | `failures="1"` — 신설한 중복부여 가드 1건 |

- **mutation 1 이 `그룹 탈퇴` 도 죽인 건 T4 가 넣은 탈퇴 전 선단언(`isTrue()`) 덕이다.** 없었으면 "원래부터 false"여도 통과하는 vacuous 가드였다.
- **mutation 3 은 예측을 관측으로 바꿨다** — 실패 메시지가 `IllegalArgumentException: INSERT ... RETURNING 이 행을 반환하지 않았습니다`. 즉 `ON CONFLICT` 를 붙이면 409 가 아니라 **500 으로 변질**된다. 주장이 아니라 실측이다.

### wave 4 ✅ 졸업 (7/9) — T5 prod 판정기 override · T6 API 표면

| task | TDD 커밋 (실측 순서) | 판정 |
|---|---|---|
| **T5** override | `5d2ac497a test:` → `da4e7d082 feat:` → `abe5c86d1 refactor:` | ✅ PASS |
| **T6** API | `591e14ef4 test:` → `37dddde0a feat:` → `54b86fa2b refactor:` (선행 `741bd11f1 docs:`) | ✅ PASS |

> T5·T6 은 같은 wave 이나 **controller 가 순차 dispatch** 했다 — 같은 worktree·같은 Gradle 모듈이라 동시 실행 시
> build 디렉토리와 git index 가 경합한다 ([[parallel-dispatch-precommit-hook-race]]). 아래는 T5 절, 그 다음이 T6 절.

**선언 외 파일 0건.** files 3개(신규 테스트 · 판정기 · 기존 테스트 복구) + plan 정정.

**XML 실측.** 대상 `IdentityAccessSystemPermissionResolverGlobalPermissionTest` = `tests="4" failures="0"`,
기존 `IdentityAccessSystemPermissionResolverTest` = `tests="2" failures="0"`.
**모듈 전체 = 257 클래스 · `tests=2386 failures=0 errors=0`** (#15 회귀 전수 확인 — 8분 18초).
`ktlintCheck`+`detekt --rerun-tasks` = BUILD SUCCESSFUL, `8 executed`(up-to-date 0).

**🛑 T5 가 처리한 plan 결함 1건 — 결함 B 계열 3번째 재발.**
RED 테스트 1·4 가 `grantedBy` 를 누락했다(T2·T4 와 같은 계열). T4 가 확정한 시그니처는 4-파라미터·기본값 없음
(ADR D-5)이라 **컴파일이 깨진다**. 기본값을 추가하지 않고 테스트가 `adminId` 를 명시로 넘겼다. plan 본문도 정정.
**같은 결함이 3개 task 에 연속 재발했다 — 남은 task(T6·T8·T9)의 `grant(...)` 호출도 착수 시 시그니처를 먼저 대조할 것.**

**mutation 판별력 실증** (기준선 4/4 PASS **선확인** → 주입 → 복원 `git diff --stat`·`git status --porcelain` 둘 다 빈 출력).

| 주입 | 결과 |
|---|---|
| `hasGlobalPermission` override 주석 처리 | `failures="2"` — `grant 보유 비-SYSTEM_ADMIN`(USER) + `GROUP grant 보유 비-SYSTEM_ADMIN` **정확히 2건** |

- **살아남은 2건이 정상이다.** `SYSTEM_ADMIN 은 grant 없이도` · `fail-closed` 는 override 유무와 무관하게 통과한다
  (default 가 `= isSystemAdmin` 이므로 두 경로의 답이 같다). **override 를 가르는 것은 "비-SYSTEM_ADMIN + grant" 뿐**이라는
  ADR D-2 말미의 주장이 관측으로 확인됐다 — 그 2건만으로 구성했다면 가드가 **vacuous** 였다.
- RED 실패 메시지 실측 = `Expecting value to be true but was false` (default 위임 생존). 예측과 일치.

#### T6 — API 표면 (controller 재검증 완료)

**선언 외 파일 0건.** 6 files, 1188 insertions = **files 5**(아래 D 로 확장) + plan.

**controller 재검증 실측.** `GlobalPermissionGrantControllerTest` = `tests="13" failures="0"` · `GlobalPermissionGrantServiceTest` = `tests="10" failures="0"`. T4 회귀 동반 확인(스키마 3 · 리포지토리 8) — **PR-1 신규 4클래스 합계 34건 전량 PASS**. `hasRole` grep = **실제 게이트 0건**(KDoc 경고문에만 등장 — PAT 경로 보존 확인). **모듈 전체 = 259 클래스 · `tests=2409 failures=0`** (T5 의 2386 + 신규 23 = 정확히 일치).

**🛑 T6 이 처리한 결함 4건** (controller 지시 2 + T6 자체 발견 2).

- **A 개수 자기모순** (controller 선발견) — 목록은 7 + "D6 신설 3건" = **10** 인데 검증 줄이 `7/7` 이었다. **그런데 10 도 최소치가 아니었다** — T6 이 개수를 버리고 **계약을 전수 열거하니 13(컨트롤러) + 10(서비스) = 23**. [[spec-stated-count-becomes-blindfold]] 가 또 맞았다.
- **B `grant(...)` 시그니처** (controller 선발견) — 4연속 재발 계열. 착수 시 실측해 `grantedBy = actorId` 명시 전달. 기본값 추가 안 함.
- **🛑 C 무가드 계약 신설** (T6 발견) — **ADR D-5 가 *"삭제 행 0 이면 404 — 조용히 성공으로 만들지 않는다"* 를 못박았고 T4 리포지토리는 그 목적 하나로 `Boolean` 을 반환하는데, 10건 중 `revoke 는 204` 만 있어 반환값을 버려도 전부 초록이었다.** 게다가 지정된 예외 3종으로는 이 상태를 **표현할 수조차 없었다**(`GranteeNotFoundException` 은 grantee 축이지 grant 행 축이 아니다) → `GrantNotFoundException` 신설, sealed **4종**.
- **🛑 D files 확장 1건** (T6 발견, 보고 필수 사항) — **컨트롤러 슬라이스는 서비스를 mock 한다**(선례 `UserGroupControllerTest:138`). 즉 예외를 mock 이 던져 **서비스 로직이 한 줄도 안 돈다** — **ADR D-4 의 grantee 존재 검증(= FK 생략의 근거 그 자체)** · D-1 permission 선검증 · DuplicateKey→409 변환이 **전부 무가드**였다. 존재 검증을 통째로 지워도 13건 전부 초록. 모듈 관례가 답을 정해 뒀다(`UserGroupService`/`ServiceTest`/`ControllerTest` 3종 세트) → `GlobalPermissionGrantServiceTest.kt` 신설, files 5개로 정정.

**mutation 실증** (기준선 13/13 PASS 선확인 → 복원 `git diff --stat` 빈 출력).

| 주입 | 결과 |
|---|---|
| `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` 로 교체 | `failures="12"` — PAT 양성 `201→403` 포함 |
| `resolveActorId` 의 PAT 가지 제거 | **`failures="1"` — PAT 양성 단독 사망(`201→401`)** |

> **⚠️ plan 941-945행의 근거 서술이 부정확했다.** *"JWT 테스트만 있으면 전부 초록"* 은 **JWT 테스트가 `ROLE_SYSTEM_ADMIN` authority 를 달고 있을 때만** 참이다(`AuthAuditLogAdminControllerTest:107-108` 이 그 형태). T6 의 JWT 테스트는 authority 없는 맨 `jwt()` 라 같이 죽는다. 그래서 **mutation 2 로 격리**해, PAT 양성이 **유일하게** 잠그는 축(`SecurityContextHolder` principal(String) 가지 = 실 `PatAuthenticationFilter` 경로)을 `failures="1"` 로 실증했다. **D4 의 근거는 주장이 아니라 관측이 됐다.**

### wave 5 ✅ 졸업 (8/9) — T8 prod 조립 빈 결선 가드

| task | 커밋 | 판정 |
|---|---|---|
| **T8** 조립 가드 | `366152435 test:` — **`feat:` 커밋 없음이 정상**(GREEN 은 T7 어댑터가 이미 제공. plan 명시) | ✅ PASS |

**선언 외 파일 0건.** 1 file(`BtsApplicationContextTest.kt`), 가드 2건 추가.
**XML 실측** — `BtsApplicationContextTest` = `tests="8" failures="0"` (기존 6 + 신규 2). `:modules:app:ktlintCheck`+`detekt --rerun-tasks` = `8 executed`.

> ⚠️ **T8 서브에이전트가 세션 한도로 중단됐다** — 가드 2건을 작성한 시점에서 끊겼고, **미커밋 변경은 테스트 파일 1개뿐**(prod 코드 무변경, 뮤테이션 잔재 0건)이었다. controller 가 검증·판단·커밋을 직접 마무리했다.

**🛑 plan 결함 1건 — 쌍둥이를 놓쳤다 (controller 실측 발견).**
plan 1232-1236행은 **T8-2 가 RED 가 될 수 없다**고 정확히 경고했다. 그런데 **T8-1 도 똑같이 RED 가 불가능하다** — `depends-on: [5,7]` 이 T8 을 T7(wave 1) **뒤에** 놓으므로, 어댑터 `@Component` 는 T8 작성 시점에 **이미 존재**한다. 그럼에도 plan 1239행은 T8-1 을 *"`expected: true but was: false` (빈 미등록). **진짜 RED**"* 라 예고했다 — **plan 자기모순**이다(1242행은 *"T7 의 `@Component` 어댑터가 잡히면 통과"* 라고 옳게 적었다).
**실측 — 기준선 `:modules:app:test` 는 처음부터 `tests="8" failures="0"`.** 두 가드 모두 green-on-arrival 이다.
> **한 인스턴스를 잡고 쌍둥이를 놓치는 것이 눈가리개의 전형이다** ([[spec-stated-count-becomes-blindfold]]). "T8-2 는 RED 아님"을 특칭으로 적은 순간 T8-1 은 검토 대상에서 빠졌다. → **RED 가 없으므로 판별력은 mutation 으로만 증명된다.**

**mutation 판별력 실증** (기준선 8/8 PASS 선확인 → 주입 → 복원 `git status` 확인 + `MUTATION-TEMP` 전수 grep 0건).

| 주입 | 결과 | 해석 |
|---|---|---|
| 어댑터 `@Component` 제거 | **`failures="1"`** — `ProjectMembershipWritePort` 가드 **단독 사망**. 메시지 = `Expecting value to be true but was false` | **부팅은 성공했다**(나머지 7건 통과). 소비자가 PR-2 라 아직 없어, **이 가드가 유일한 탐지기**임이 관측됐다 |
| 리포지토리 `@Repository` 제거 | **`failures="8"`** — 전량 사망. `NoSuchBeanDefinitionException: No qualifying bean of type 'GlobalPermissionGrantRepository'` | **부팅 자체가 실패**한다 |

**★ 3번째 가드 판단 = 불필요 (실측 결정).**
인계 사실 #14 가 `GlobalPermissionGrantRepository` 도 T8 이 고정할 빈이라 제안했으나, **위 mutation 2 가 전이성을 증명했다** — 그 빈이 없으면 `IdentityAccessSystemPermissionResolver` 생성자 주입이 터져 **부팅 실패**로 8건이 전량 죽는다. **부팅 실패가 이미 더 큰 탐지기**이므로 이름 고정을 하나 더 두면 중복 가드가 되어 유지비만 늘고 [[enum-add-breaks-crossmodule-count-guard]] 계열 부채가 된다.
**두 빈이 갈리는 지점이 정확히 "소비자가 있는가"다** — 어댑터는 소비자(PR-2)가 없어 부팅이 안 깨지므로 이름 고정이 유일한 탐지기, 리포지토리는 소비자(판정기)가 있어 부팅이 깨진다. **이 근거를 테스트 주석에 남겼다** — 안 남기면 다음 사람이 #14 를 읽고 중복 가드를 추가한다.

### wave 6 ✅ 졸업 (9/9) — T9 전수 동기화 + FR 5개 등록 · **PR-1 전 task 완료**

| task | 커밋 | 판정 |
|---|---|---|
| **T9** 동기화 | `7c2fdc45d docs:` — 문서 전용이라 TDD 사이클 없음(T1 `1cd02d407` 선례 동형) | ✅ PASS |

**11 files** = 선언 9 + `scripts/verify-master-plan.sh`(plan 이 확장 명시) + `TODOS.md`(plan 354행이 T9 몫으로 지정). **선언 외 0건.**

**controller 재검증 실측.**
- `bash scripts/verify-master-plan.sh` → **EXIT=0**, `PASS. FR ID 128/128 매핑 완료. 체크박스 마커 정상. 카운트 정합(fr-index/README/헤더/CLAUDE)`
- 재계수 `grep -oE '^\| FR-[A-Z]+-[0-9]+ ' docs/plan/fr-index.md | sort -u | wc -l` → **128**. BC 증분 실측 일치 — identity-access 24→25(PM 9→10) · issue-tracking 31→35(+PJ 4)
- **main 트리 오염 0** — `/Users/maxi.moff/Projects/BTS/CLAUDE.md` 는 `123 FR` 그대로(머지가 가져간다), 워크트리 판만 `128 FR`. 함정 1 준수 확인
- dashboard 재생성 완료 — `전체 122/128 완료 (95%)`. `[skip ci]` 미포함

**★ verify 룰 D 확장 — controller 가 직접 mutation 으로 비-vacuous 확인** (에이전트 보고를 믿지 않는다. 룰은 오타 하나로 vacuous PASS 가 된다 — [[archunit-vacuous-rule-silent-pass]]).

| 단계 | 결과 |
|---|---|
| 기준선 **선확인** | `EXIT=0` (이걸 안 하면 아래가 vacuous) |
| mutation A — BC 게이트 `35 FR`→`34 FR` (**새 룰의 대상**) | `FAIL. 카운트 drift — issue-tracking.md BC 완료 게이트 '34 FR' (fr-index §A.1 실측 35)` · **EXIT=4** |
| mutation B — `(FR-PM, 10개)`→`11개` (**기존 분기 회귀**) | `FAIL. 카운트 drift — identity-access.md 헤더 'FR-PM 11개' (실제 10)` · **EXIT=4** |
| 복원 | `EXIT=0` · `git status` 빈 출력 |

**🛑 T9 가 잡은 것 3건.**
1. **issue-tracking BC 완료 게이트를 실제로 막았다** — `:495` 원문 `§2~§6 (30 FR)`(이미 stale — 31 이 맞았다) → **`§2~§7 (35 FR)`**. 안 고쳤으면 issue-tracking BC 가 **FR-PJ-01~04 미구현 상태로 "완료" 선언 가능**했다.
2. **`verify-master-plan.sh` 의 진짜 버그 발견·해소** (plan 에 없던 것) — `hprefix` 추출 파이프라인이 `set -e`+`pipefail` 조합에서, 접두사 없는 매치 라인을 만나면 `grep -oE 'FR-[A-Z]+'` 가 0건 매치 → exit 1 → **스크립트 전체가 FAIL 메시지 없이 조용히 abort**(EXIT=1)했다. `|| true` 가드 2곳으로 해소. **"통과"가 아니라 "중단"이었을 뻔했다** — [[zsh-pipestatus-1-based-false-green]] 계열의 거짓 그린.
3. **🛑 인계 숫자를 grep 으로 재확인해 하나를 뒤집었다** — `requireSystemAdmin` 은 인계받은 **5 가 아니라 실측 3**(`GlobalPermissionGrantController`·`IssueSecuritySchemeController`·`UserGroupController`). plan-eng-review 원안의 pre-T6 "4파일"부터 이미 과다 계상이었다. **추측으로 5 를 정정하지 않고 실측 3 을 캐비엇과 함께 기록**했다. `FORBIDDEN|UNAUTHORIZED_RESPONSE` 14 · `resolveActorId` 7 은 일치.

**Obsidian** — `Maxi_wiki/BTS/` 미변경 확인(이 worktree 에 존재하지 않는다). glossary 5건(프로젝트 보강 / 프로젝트 보관 · 소프트 삭제 · 전역 권한 부여 · 프로젝트 키 신규)은 **Maxi 승인 수동 영역이라 보고만** 한다.

### ★ wave 2~6 이 물려받을 실측 사실 (다시 발견하지 말 것)

| # | 사실 | 출처 |
|---|---|---|
| 1 | **Gradle wrapper 는 `backend/gradlew`** 다. 저장소 루트에 없다 — 루트에서 `./gradlew` 하면 `no such file or directory`. 전 검증 명령을 `cd backend && ./gradlew` 로 정정 완료 | T3 |
| 2 | **`@JdbcTest` 슬라이스에 `TransactionAutoConfiguration` 이 포함**된다(`AutoConfigureJdbc.imports` 실측). `@EnableTransactionManagement` 가 활성이라 mutation 의 `REQUIRES_NEW` 가 AOP 로 실제 적용된다 — 이게 없었으면 애노테이션이 무음 무시돼 ②가 "fail 안 남"으로 나왔다. `TransactionTemplate` 빈 주입도 가능 | T7 |
| 3 | **`@JdbcTest` 는 `@Transactional` 메타** — jar 바이트코드 독립 재확인(`Lorg/springframework/transaction/annotation/Transactional;`) | T7 |
| 4 | **`@JdbcTest` 는 `@Component` 를 스캔하지 않는다** → 어댑터 테스트에 `@Import(ProjectMembershipWriteAdapter::class)` 필요 | T7 |
| 5 | **T8 이 고정할 빈 이름 = `com.atlas.bts.identity.project.ProjectMembershipWriteAdapter`** (확정) | T7 |
| 6 | **detekt `UseCheckOrError`** — 테스트에서 `throw IllegalStateException(...)` 대신 `error(...)`. ktlint `Class body should not start with blank line` — 형제 파일들은 baseline 동결이라 통과 중이나 신규 파일은 걸린다 | T7 |
| 7 | **`BUILD SUCCESSFUL` 을 믿지 말 것** — 결과 XML(`build/test-results/test/*.xml`)에서 `tests="N"` 을 직접 확인해 0개 실행 가짜 그린을 배제한다. wave 1 두 에이전트 모두 이걸 했다 ([[gradle-batched-task-partial-test-run]]) | T3·T7 |
| 8 | 🛑 **zsh 는 unquoted 변수를 단어분할하지 않는다.** controller 의 2-D 수집에서 `git log -- $FILES` 가 여러 경로를 한 덩어리로 넘겨 **빈 출력**을 냈다 — 그대로 믿었으면 TDD_VIOLATION 오판이었다. **배열 `"${ARR[@]}"` 을 쓸 것** ([[zsh-pipestatus-1-based-false-green]] 과 같은 zsh 함정 계열) | controller |
| 9 | 🛑 **`--tests` 필터를 걸어도 ArchUnit 2종이 항상 같이 돈다** — `SpiBoundaryArchTest`(2) + `TransactionalServiceArchTest`(1). 그래서 콘솔의 `6 tests completed` 는 내 클래스 3 + 이 3 이다. **콘솔 합계로 판단하면 오독**하니 클래스별 XML 을 볼 것 | T2 |
| 10 | **Flyway `locations: classpath:db/migration`**(`application.yml:25`)이 하위 `identity-access/` 를 재귀 스캔한다. 테스트용 별도 flyway 설정 없이 `@DynamicPropertySource` 로 `spring.flyway.enabled=true` 만 켜면 V001~V036 전량 적용된다 | T2 |
| 11 | **pre-commit 훅을 `-c core.hooksPath=/dev/null` 로 우회하는 것이 wave 표준** — worktree 공유 + lint-staged 가 내부적으로 `git stash` 를 써서 타 세션/병렬 task 산출물을 흡수할 위험이 있다([[worktree-lint-staged-shared-git-stash-collision]]). **대신 `ktlintCheck`/`detekt --rerun-tasks` 를 명시 실행해 검증을 유지**할 것 | T2 |
| 12 | **plan 산문의 "D3"·"D7" 은 plan-eng-review 번호**이지 ADR 번호가 아니다. ADR 실번호 = D-1 신규테이블 / D-2 판정식(`grant OR isSystemAdmin`) / D-3 default메서드 / D-4 FK없음·다형참조 / D-5 granted_by·회수 hard delete. **T5 KDoc 이 ADR 을 인용할 때 이 표를 볼 것** | T2·controller |
| 13 | **`GlobalPermissionGrantRepository` 는 인터페이스 없는 concrete `@Repository`** 다. T5 는 `grantRepo = mockk()` 로 그대로 목킹하면 된다(mockk 는 final 클래스 인터셉트). 모듈에 두 관례가 공존하나(`ProjectMembershipRepository`=인터페이스+impl / `CalendarFeedTokenRepository`=concrete 단독) plan 이 파일 1개만 선언해 후자를 따랐다 | T4 |
| 14 | **T8 이 고정할 빈 이름 2번째 = `com.atlas.bts.identity.permission.GlobalPermissionGrantRepository`** (T7 의 사실 #5 와 같은 계열) | T4 |
| 15 | ✅ **해소됨 (T5 실측)** — `grantRepo` 주입으로 깨진 곳은 **`IdentityAccessSystemPermissionResolverTest:26` 단 1곳**(생성자 명명인자)이고 `grantRepo = mockk()` 로 복구했다. **`@WebMvcTest` 4곳은 무영향** — 슬라이스가 `SystemPermissionResolver` **인터페이스**를 mockk `@Bean` 으로 공급하고 concrete 판정기를 스캔하지 않는다. full-boot(`SystemAdminInfraIntegrationTest` 등)는 같은 모듈 스캔 + `NamedParameterJdbcTemplate` 자동설정으로 자동 해결. **모듈 전체 2386 tests / 0 failures 로 확증** | T4→T5 |
| 15b | **`IdentityAccessSystemPermissionResolver` 직접 참조 전수 결과 (T5 grep)** — 코드 참조는 `IdentityAccessSystemPermissionResolverTest`(생성자) + `SystemAdminInfraIntegrationTest`(`isInstanceOf` 단언, full-boot prod) **2곳뿐**이고, 나머지 7곳은 **KDoc 산문 언급**이다. **판정기에 협력자를 더 추가해도 컴파일이 깨지는 곳은 이 2곳뿐**이다 | T5 |
| 16 | **`@JdbcTest` 롤백만으로 격리 충분** — `@BeforeEach` DELETE 불요. `list()` 의 `singleElement()` 가 롤백에 기대지만 격리가 깨지면 **fail 하는 방향**이라 안전하다. 픽스처는 매번 랜덤 UUID | T4 |
| 17 | **`granted_by`·`grantee_id` 엔 FK 가 없어 users 행 없이도 INSERT 된다**(ADR D-4). 단 `group_memberships.user_id` 엔 V015 FK 가 있어 **GROUP 시나리오엔 실 users 행이 필수** | T4 |
| 18 | **detekt/ktlint 커스텀 설정 없음** — 루트 `.editorconfig` 도 detekt.yml 도 없다(기본값). 신규 파일은 라인 ≤120 으로 쓰면 안전 | T4 |
| 19 | 🛑 **슬라이스 mock `@Bean` 은 테스트 메서드 간 공유돼 호출 기록이 누적된다.** `@BeforeEach clearMocks` 없이는 `verify(exactly=0)` 이 **타 테스트 호출로 실패**하고, 더 위험하게 `verify(exactly=1)` 이 **누적 호출로 가짜 통과**해 판별력을 잃는다. T6 의 GREEN 1차가 정확히 이걸로 2건 실패했다(선례 `PersonalAccessTokenControllerTest:106`). `UserGroupControllerTest` 엔 이게 없어 `verify` 를 못 쓴다 | T6 |
| 20 | **PAT 양성 테스트의 정본 형태** — `jwt().authorities(ROLE_PAT)`(`AuthAuditLogAdminControllerTest:112`)는 principal 이 `Jwt` 라 **PAT 가지를 안 탄다**. 실 경로는 `personalAccessTokenService.verify(RAW_PAT)` mock + `Authorization: Bearer pat_...` 헤더(`UserGroupControllerTest:605` 형태). **부수 효과** — `Bearer pat_` 는 `patBearerMatcher` 로 **CSRF-ignore** 라 POST 에도 `csrf()` 불요 | T6 |
| 21 | ⚠️ **모듈 전체 suite 는 flaky** — `MfaBackupCodeLoginIntegrationTest` 3건이 `UnknownContentTypeException(application/octet-stream)` 로 실패했으나 **단독 3/3 PASS + 풀 suite 재실행 BUILD SUCCESSFUL** 로 비결정성 확정([[concurrent-testcontainers-suite-flaky]]). T6 변경과 무관. **1회 실패로 BLOCKED 판단하지 말 것 — 단독 재실행으로 확정** | T6 |
| 22 | **ktlint 는 KDoc 뒤 standalone `//` 를 거부**한다("an EOL comment may not be preceded by a KDoc"). `@Suppress` 사유는 KDoc 본문 + annotation EOL 요약으로 | T6 |
| 23 | **권한코드 추가 시 3곳 동시 갱신** — SDD 12 · V036 CHECK · `GlobalPermissionGrantService.ALLOWED_GLOBAL_PERMISSIONS`(ADR 잔여 위험 4). 서비스 KDoc 에 명시됨 | T6 |
| 24 | **PR-2 인계** — `POST /projects` 의 `CREATE_PROJECT` 판정은 **`SystemPermissionResolver.hasGlobalPermission` 으로만** 하라. `GlobalPermissionGrantRepository.hasGrant` 직접 호출은 **SYSTEM_ADMIN 이 탈락**한다(ADR D-2 뒷항 소실) | T6 |

### ★ wave 2~6 에 인계된 주의 (서브에이전트 보고)

- **T5 (mockk) → ✅ 확인 완료, T6 에 이월.** T5 의 전 모듈 grep 결과 기존 `SystemPermissionResolver` mockk 4곳(Whoami/UserGroup/IssueSecurityScheme/WhoamiOoo)은 **스텁 추가 없이 통과**했다 — T5 는 판정기만 바꾸고 컨트롤러를 늘리지 않아 그 슬라이스들의 호출 경로가 변하지 않았기 때문이다(모듈 전체 2386 tests / 0 failures 로 확증). **경고 자체는 T6 에 유효하다** — T6 이 `hasGlobalPermission` 을 호출하는 컨트롤러를 추가하면 `mockk()`(non-relaxed)이 default 메서드도 인터셉트하므로 그 4곳에 스텁이 필요해질 수 있다. **T6 은 자기 컨트롤러 슬라이스뿐 아니라 이 4곳도 함께 돌릴 것**(모듈 전체 실행이 확실하다).
- **T2 (db-engineer).** V036 SQL 주석의 "고아 행" 문구는 **ADR D-4 문안과 일치해야 한다**(`140cf17fe` 로 plan 정정 완료 — GROUP 은 CASCADE 로 탈락 / USER 는 영구 잔존). ADR 을 Read 해서 대조할 것.
- **🛑 T9 — `TODOS.md` DRY 부채 기록은 T9 몫이다 (T6 이 실측 인계).** T6 프롬프트가 `TODOS.md` 기록을 지시했으나 T6 은 **기록하지 않았고 그 판단이 옳다** — plan 354행이 `T9 가 TODOS.md 에 기록` 으로 명시하고, 파일이 아직 없으며 T6 files 에도 없다(선언 외 파일 생성 회피). 대신 **실측을 남겼다** — D19 의 13/6/4 는 **pre-T6 기준으로 정확했고**, T6 의 복사 3건으로 각각 **`FORBIDDEN|UNAUTHORIZED_RESPONSE` 14파일 · `resolveActorId` 7 · `requireSystemAdmin` 5** 가 됐다. **T9 는 이 숫자로 기록하라.**
- **T9 (문서 동기화) 판단 대상 2건.** (a) `DATA.md §3` 하드삭제 허용 목록에 `global_permission_grants` 추가가 필요해 보인다 — 다만 hard delete ADR 이 있는 `project_memberships` 도 그 목록에 없어 목록이 exhaustive 가 아닐 수 있다. (b) **무관 drift, 고치지 않는다** — `DATA.md:87` §4.1 표가 identity-access 를 `사용 중 V001~V006` 이라 적었으나 실제는 V035 (글로벌 CLAUDE.md §3 surgical).
- **T1 산출물 형식 결정 2건.** (a) revoke hard delete 정당화를 D-6 독립이 아니라 **D-5 안에 부여/회수 비대칭으로 편입**했다 — plan 이 지정한 D-1~D-5 번호를 T2 SQL·T5 KDoc 이 인용하므로 번호를 깨지 않기 위함. (b) **변경이력 절을 넣지 않았다** — `docs/decisions/` 106개 · `docs/adr/` 전체에 변경이력 절이 **0건**이라 실제 관례를 따랐다.

### 남은 wave (재개 지점)

```
wave 2  T2 (db-engineer)        V036 마이그레이션 + 스키마 가드      ✅ 졸업
wave 3  T4 (security-engineer)  GlobalPermissionGrantRepository      ✅ 졸업
wave 4  T5 · T6 (security)      prod override · 컨트롤러             ✅ 졸업 (순차 dispatch)
wave 5  T8 (security)           :modules:app 조립 가드               ✅ 졸업
wave 6  T9 (backend-engineer)   전수 동기화 8종 + FR 5개 등록        ✅ 졸업

★ 9/9 전 task 완료.
E2E     ⛔ 대상 없음 (실측 결정 — 아래 §E2E 판단)
검증     ✅ verification-before-completion 통과 (아래 §최종 검증)
다음     /bts-codereview [7/8] → 🛑 게이트 2 (Maxi) → /bts-merge [8/8]   ← 여기서 재개
```

### E2E 판단 — ⛔ 대상 없음 (실측 결정)

plan 은 *"그 후 qa-engineer E2E (타입 auth)"* 를 예고했으나 **PR-1 에 E2E 대상이 없다**. E2E(Playwright — 실제 브라우저로 화면을 구동하는 테스트)는 UI 표면이 있어야 성립한다.

**실측** — `git diff --name-only origin/main...HEAD -- apps/web` = **빈 출력**. PR-1 변경 33파일은 **backend(identity-access 10 · app 1) + docs/스크립트뿐**이다. UI 는 스펙 §9.2 의 **PR-5(UI) · PR-6(관리 화면)** 몫이다.

**대신 API 계약을 커버하는 것** — `GlobalPermissionGrantControllerTest` 13건이 `@WebMvcTest` 로 실제 HTTP 표면(201/400/401/403/404/409/204 · PAT 경로 · 본문 누출 0)을 검증한다. **UI 가 붙는 PR-5·PR-6 이 E2E 를 진다.**

> **"타입 auth 라서 E2E" 는 조건이 아니라 기본값이었다** — [[ui-pr-defer-e2e-regression-latent]] 가 경고하는 것은 *"UI PR 이 E2E 를 미루면 회귀가 잠복한다"* 이지, UI 가 없는 PR 에 E2E 를 만들라는 게 아니다. **PR-5·PR-6 이 이 경고의 실제 대상이다.**

### 최종 검증 (verification-before-completion) — controller 직접 실행

| 검증 | 결과 |
|---|---|
| `:modules:identity-access:test` **모듈 전체** | **259 클래스 · `tests=2409 failures=0 errors=0`** (6분 31초) |
| `:modules:app:test` (9 BC prod 조립) | `tests="8" failures="0"` |
| `ktlintCheck` + `detekt --rerun-tasks` (identity-access + app) | BUILD SUCCESSFUL · **`16 executed`**(up-to-date 0 = 진짜 재실행, 캐시 false-green 아님) |
| `bash scripts/verify-master-plan.sh` | **EXIT=0** · `PASS. FR ID 128/128` |
| FR 재계수 | **128** |
| main 트리 오염 | **0** (`/Users/maxi.moff/Projects/BTS/CLAUDE.md` = `123 FR` 그대로) |

**PR-1 신규 테스트 42건** = 스키마 3 + 리포지토리 8 + 서비스 10 + 컨트롤러 13 + 판정기 4 + 포트 2(T7) + 조립 가드 2.

## [7/8] /bts-codereview 결과 + 🛑 게이트 2 (Maxi 승인 2026-07-17)

**리뷰 5종 병행** — `superpowers:code-reviewer`(절대규칙 19개+DATA 5원칙) + `/review` 전문가 4종(security · data-migration · api-contract · testing/maintainability).

> 🛑 **`/review` 의 scope 자동판정이 4/5 를 틀렸다** ([[gstack-diff-scope-blind-to-kotlin]] 재확인). BACKEND=false(실제 Kotlin 10파일) · MIGRATIONS=false(실제 V036) · API=false(실제 신규 REST 컨트롤러) · FRONTEND=true(실제 apps/web 무변경). AUTH 만 맞았다. **실측으로 덮어쓰지 않았으면 security·data-migration 전문가를 통째로 건너뛰었다.**

**결론 — CONCERNS, BLOCKER 0.** prod 코드에 살아 있는 보안 구멍 없음. 절대 규칙 19개 위반 0 · 데이터 무결성 5원칙 충족 · learnings.md 회귀 0 · plan D1~D19 위반 0 · 8개 축 중 6 PASS.

### 🛑 발견 1 (3중 확인 · mutation 2회 실증) — DELETE 가드가 테스트로 안 잠겼다 → **수정 완료** `20f704c76`

security(9/10) · 절대규칙 · testing(10/10) **셋이 독립적으로** 찾았고 **둘이 각자 mutation 으로 실증**했다.

**가드 자체는 3/3 핸들러에 실재**한다(`grantPermission:91` · `listGrants:115` · `revokeGrant:134`) — prod 무결점. 문제는 **테스트가 그 가드를 구분하지 못한 것**이다. `revokeGrant` 의 가드 한 줄을 지우고 돌리면 **`tests=13 failures=0` 전량 초록**이었다. 그 엔드포인트를 치는 테스트 파일이 이것 하나뿐이라 다른 커버리지도 없었다.

원인 — 403 음성 테스트가 POST·GET 만 있었다. **테스트 KDoc `:53` 이 스스로 `(부여·목록 양쪽)` 이라 적어 누락을 문서화**하고 있었다. DELETE 2건(`관리자 204` · `404`)은 **둘 다 `grantAdmin()` 전제**라 판별력 0. `confirmVerified` 가 없어 미사용 스텁을 mockk 가 실패시키지도 않아 **커버리지 착시**만 만들었다.

> **이 PR 자신의 잣대가 적용되지 않은 자리다.** 같은 파일 `:61-63` 이 *"JWT 테스트만 있으면 누군가 hasRole 로 바꿔도 전부 초록 — 결정의 근거 자체가 미검증으로 남는다"* 라고 적고 PAT 양성을 넣었고, T5 KDoc 은 *"판별자를 지우면 vacuous"* 라 못박았다. **DELETE 에만 그 논리를 안 썼다.** 셋 중 가장 파괴적인 것이 회수인데도(hard delete · 복구 불가).
> **"13건"이 또 눈가리개였다** — **가드×핸들러 행렬**을 전수 열거하지 않아 POST✓ GET✓ DELETE✗ 가 숨었다([[spec-stated-count-becomes-blindfold]] 5연속 재발).

**controller 수정 + 실증** (prod 코드 무변경, 테스트만).

| 단계 | 결과 |
|---|---|
| 신규 기준선 **선확인** | `tests="14" failures="0"` |
| 가드 제거 mutation | **`failures="1"` — 새 테스트 단독 사망** (이전엔 같은 mutation 이 13/0 통과) |
| 복원 | `tests="14" failures="0"` · 가드 3/3 · `MUTATION-TEMP` grep 0 |

### 발견 2 (2중 확인) — 하드 삭제 거버넌스 → **🛑 게이트 2 Maxi 승인 후 수정 완료**

data-migration(7/10) · 절대규칙이 각각 찾았다. **`DATA.md §1` 2번이 *"하드 삭제는 ADR + Maxi 확인 필수"*** 인데 —
- ADR D-5 가 그 규칙을 **`§1 7번`** 이라 인용했다(§1 은 **5원칙**이라 7번이 없다)
- **`ADR 필수` 로 축약해 "Maxi 확인"을 떨어뜨렸다** — 하필 그 지운 부분이 당시 실제로 기록 없던 항목
- D-1(`Maxi 확정 — plan D7`) · D-2(`plan D14`)는 마커를 달았으나 **D-5 만 없었다**
- `DATA.md §3` 하드삭제 허용 목록에 `global_permission_grants` **미등재**

> **T9 가 등재를 건너뛴 근거를 data-migration 이 실측으로 무너뜨렸다.** plan 은 *"`project_memberships` 도 목록에 없으니 exhaustive 가 아닐 수 있다"* 를 방어로 삼았으나, **날짜순으로 보면 그 방어는 가장 오래된 선례 하나에만 기댄다** — `project_memberships`(2026-06-01)만 미등재이고 **이후 하드삭제 ADR 5건은 전부 등재**돼 있다(issue_attachments 06-15 · favorites 06-24 · saved_filters 06-26 · dashboard_share_tokens 07-02 · user_keymap 07-08). 목록이 non-exhaustive 한 게 아니라 **2026-06-15 부로 등재가 관례로 굳었고** `project_memberships` 가 그 이전 건이다. V036(07-17)은 관례 확립 이후이며 등재 형식도 5건이 일치한다.

**🛑 controller 는 이걸 대신 승인할 수 없었다** — 규칙이 "Maxi 확인 필수"라 못박은 항목이다. **게이트 2 에서 Maxi 승인 (2026-07-17)** → ADR D-5 에 확정 마커 + 인용 정정(§1 2번 · "Maxi 확인" 복원), `DATA.md §3` 등재(선례 5건과 동형).

### 발견 3 (api-contract 6/10) — `createdAt` wire format 미고정 → **수정 완료** `20f704c76`

응답 6필드 중 `createdAt` 만 어떤 테스트도 형식을 안 잠갔다. 실측 — identity-access 엔 커스텀 `ObjectMapper` 빈도 `@EnableWebMvc` 도 없어 Boot 기본(ISO-8601 문자열)이 적용되고, 같은 BC 선례 `TrustedDeviceControllerTest:128` 이 그 형식을 verbatim 고정한다. **PR-5/6 이 이 응답에 Zod 를 쓴다** — DTO 는 nullable 0개라 FR-AU-10 의 `.nullish()` 패턴을 복사하면 **필드 소실 회귀를 조용히 통과**시킨다.

### 발견 4 (testing 8/10) — ADR D-1 이중방어 정합 무가드 → **🛑 게이트 2 결정. 후속으로 미룸**

앱 겹(`ALLOWED_GLOBAL_PERMISSIONS`)과 DB 겹(V036 CHECK)이 **같은 집합이어야** 하는데 두 값을 함께 읽는 테스트가 0건이다. **ADR 이 이미 잔여 위험 4 로 의식적 수용**했고 두 집합이 현재 일치하므로 **잠복 부채이지 현행 결함이 아니다**. 가드에 리플렉션/`internal` 승격(prod 변경)이 필요해 권한 PR 리뷰 단위를 흐린다 → **`TODOS.md` 기록 후속**.

### 범위 밖 (건드리지 않음)

`FlywayAssemblyConfig.kt:13` KDoc `V001~V033`(실제 V036) · `DATA.md:87 §4.1` 표 `V001~V006` — **선재 drift**. V035 를 넣은 선행 PR 도 안 고쳤다. 글로벌 CLAUDE.md §3 surgical.

### 리뷰가 확인한 깨끗한 축 (실측, 가정 아님)

`clearMocks` 실재(`:167-170`)라 mock 누적 함정 해당 없음 · `resolveActorId`/`requireSystemAdmin` 신규 사본은 선례와 **바이트 동일** · 스키마 테스트의 넓은 `DataIntegrityViolationException` 은 **vacuous 아님**(각 setup 이 겨냥한 제약 **하나만** 위반) · `ProjectMembershipWriteAdapterTest` 의 `NOT_SUPPORTED` 정확 · 빈 catch 0건 · `!!` 0건 · `println` 0건 · cross-BC 직접 import 0건 · `init_codegen.sql` 부재 주장 사실(jOOQ 의존 0) · V036 번호 free 재확인 · 고아행 분석 정확(V015:12-13 CASCADE verbatim) · 인덱스 주장 성립 · CHECK 3곳 정확(4번째 없음).

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

## 리뷰 결과

### plan-eng-review (2026-07-17) — PR-1 (T1~T9). 타입 auth → BLOCKER 무시 옵션 없음

**Step 0 범위 도전.** 복잡도 트리거 발동(26파일 / 신규클래스 6 · 기준 8/2) → Maxi 확정 **그대로 진행**.
분해하면 **문서 동기화 9**(CLAUDE.md §전수동기화 강제) + **테스트 6**(TDD) + **코드 9** + ADR 1 + app 테스트 1.
가장 의심한 **T7(소비자 없는 포트)** 은 git 이력으로 검증한 결과 **선례 동형**이다 — PR **#75**
*"FR-PM-08 — 전역 시스템 관리자 역할 + 전역 권한 인프라 (**FR-PM-04 선행**)"* 가 shared-kernel 포트 +
identity-access 어댑터만 싣고 소비자를 뺐고, `--reverse` 순서상 소비자 PR(#73)보다 **먼저** 머지됐다.

| 섹션 | 결과 |
|---|---|
| 1. 아키텍처 | ⚠️ 2건 — 권한코드 무검증(D17) · 게이트 패턴 근거 오류(D18) |
| 2. 코드 품질 | 🛑 1건 — T5 `files` 누락(컴파일 파손) · ⚠️ DRY 13파일 복제(D19) |
| 3. 테스트 | ⚠️ 커버리지 67% (14/21) · 갭 7건 → **6건 추가, 1건 소멸**(role 제거) |
| 4. 성능 | ⚠️ 1건 — 중복 인덱스. N+1 없음, grant 테이블 수십 행 규모 |
| Outside voice | 🛑 **P0 1건** + P2 4건 + P3 4건. codex 미설치 → Claude 서브에이전트 |

**🛑 BLOCKER 1건 (Outside voice 발견, 반영 완료) — §C10-2.**
T7 롤백 테스트를 `@JdbcTest`(= `@Transactional` 메타) 위에 얹으면 **올바른 구현에서도 fail** 하고
잘못된 구현과 **구분되지 않는다**(테스트 tx 가 미커밋 INSERT 를 그대로 읽음). I1(2행 원자성)의 유일한 실증이
구현자를 **I1 파괴 방향(REQUIRES_NEW)으로 유도**하고, 필수 지정한 mutation 확인이 그 사실을 **가려준다**.
→ `@Transactional(propagation = NOT_SUPPORTED)` + `@AfterEach` 수동 정리 + **기준선 PASS 선확인**. 선례 `RefreshTokenRepositoryTest.kt:230`.

**★ 이 리뷰의 핵심 — 눈가리개의 4층.**

| 라운드 | 눈가리개 | 처방 |
|---|---|---|
| spec 1~3회차 | 개수 → 정의 → **정의가 전제한 코드 모양** | 5중 교차 열거 |
| **plan-eng-review** | **내가 반증한 것이 그 제약의 유일한 기전이라는 전제** | **"경로가 하나뿐인가"를 묻는다** |

plan 의 §스펙 정정표는 spec 의 교훈을 적용해 만든 **개정본인데, 그 개정본이 새 결함을 넣었다**.
정정 2 는 *"identity-access 에 `DSL.using` 0건"* 이라는 **참인 근거**로 C10 전체를 기각했다 —
그러나 거짓 red 의 기전은 **둘**이고 2번째(테스트 클래스 자체 `@Transactional`, 41파일)를 안 봤다.
[[spec-stated-count-becomes-blindfold]] 의 *"개정본을 원본보다 의심하라"* 가 이번엔 **내 정정표**를 겨눴다.

**정정된 것 (초안이 틀렸던 지점 6).**
1. §스펙 정정 2 — C10 기각은 **오류**. §C10-2 신설
2. T5 `files` — `IdentityAccessSystemPermissionResolverTest.kt:26` 누락 → 명명 인자라 컴파일 파손 ([[plan-files-constructor-injection-existing-tests]] 재현)
3. T9 — *"main 판 CLAUDE.md 도"* → `verify-master-plan.sh:18·82` 가 워크트리 판만 검사. **CLAUDE.md §핵심 패턴을 plan 이 스스로 위반**
4. T9 — issue-tracking BC 완료 게이트 `:495` `§2~§6 (30 FR)` 누락 → FR-PJ(§7) 4개가 게이트 밖. verify 룰 D 도 못 잡아 **종료 0 통과** → 룰 확장 동반
5. T8-2 — *"실패 메시지 예상: 빈 미등록"* 은 **거짓**. `@Profile` 없어 main 에서 이미 초록 → RED 아님 명기
6. T6 — grantee 존재 검증(ADR D-4 의 FK 생략 근거) 협력자가 `files`·`depends-on` 어디에도 없었음

**설계 개선 3건 (Maxi 확정).** 포트 `role: String` 제거 → `addCreatorAsAdmin` (MEMBER 는 영원히 죽은 값 · 무가드 프리미티브 표면 축소 · 테스트 1건 소멸) / `granted_by` 감사 컬럼 (지금은 컬럼 1개, 나중엔 기존 행 전부 NULL) / V036 중복 인덱스 삭제 (`UNIQUE` 가 같은 3컬럼 btree 를 이미 생성 — `V015:19` 가 세운 기준).

**확증된 것 (반증 시도 후 살아남음).**
- 🟢 `AuthAuditLogAdminController` 의 PAT 403 은 **버그 아님** — `AuthAuditLogAdminControllerTest:113-115` 가 `ROLE_PAT` 케이스를 명시 테스트. 확인 게이트가 내 오탐을 잡았다
- 🟢 T9 의 "SecurityConfig 무변경" — `SecurityConfig.kt:209` `auth.requestMatchers("/api/**").authenticated()` 실재
- 🟢 §스펙 정정 1·3·4 (C3 무관 · C1 무관 · PM10-5 안 깨짐) — 전부 재검증 통과
- 🟢 T7 을 PR-1 에 두는 것 — PR #75 선례 동형

**Prior learnings applied.** [[plan-files-constructor-injection-existing-tests]](9/10) · [[spec-stated-count-becomes-blindfold]](10/10) · [[negative-guard-needs-body-discriminator]](9/10) · [[verify-logic-vs-verify-guard]](9/10) · [[enum-add-breaks-crossmodule-count-guard]](8/10) · [[archunit-vacuous-rule-silent-pass]](8/10).

**BLOCKER 잔여. 없음** (P0 반영 완료).

### plan-ceo-review — 스킵

`/bts-review-plan` 표는 auth → eng → ceo 를 지정하나, ceo 리뷰의 질문(*"이걸 만들 가치가 있나 · 범위가 맞나"*)은
**D7·D11·D15 로 이미 잠겼다**. D11 은 되돌리기 가장 싼 지점(코드 0줄)에서 재확인한 결과이고
*"이후 범위 축소 재논의는 새 근거 없이는 하지 않는다"* 를 명시했다. 새 근거는 나오지 않았다 —
오히려 Step 0 이 범위를 독립 도전했고 Maxi 가 **그대로 진행**으로 확정했다.
([[bts-spec-office-hours-mismatch]] 동형 — 확정된 결정을 다시 흔드는 단계는 부적합.)

## GSTACK REVIEW REPORT

| Run | Status | Findings |
|---|---|---|
| Step 0 scope challenge | ✅ 통과 | 트리거 발동(26파일/6클래스) → Maxi "그대로 진행". T7 은 PR #75 선례 동형으로 검증 |
| 1. Architecture | ⚠️ 2 findings | P1 권한코드 무검증 → D17 · P2 게이트 근거 오류 → D18 |
| 2. Code quality | 🛑 1 blocker-class + 1 | P1 T5 files 누락(적용) · P3 DRY 13파일 → D19(복사+TODO) |
| 3. Tests | ⚠️ 7 gaps | 67% → 6건 추가 + 1건 소멸. PAT 양성이 D18 근거를 잠금 |
| 4. Performance | ⚠️ 1 finding | 중복 인덱스 삭제(적용). N+1 없음 |
| Outside voice (Claude subagent) | 🛑 P0 + 8 | codex 미설치. **4/4 실측 검증 통과**, 오탐 0 |
| Cross-model tension | ✅ 해소 | C10 적용 여부 — outside voice 가 옳음. 내 논거는 참이나 기전 하나만 덮음 |

**적용 완료.** P0(§C10-2 · T7 테스트 베이스) · P1×2(T5 files · 권한코드 CHECK) · P2×4(게이트 근거 · main CLAUDE.md · BC 게이트+verify 룰 · T6 협력자) · P3×3(중복 인덱스 · T2 복제 가드 · T8-2 red 아님) · 설계 3(role 제거 · granted_by · ADR D-4 문안).

**VERDICT: APPROVED WITH CHANGES — 전부 반영 완료.** BLOCKER 잔여 0. task 9 · 메타 9/9/9 무결. auth 타입 BLOCKER 무시 옵션은 사용하지 않았다.

NO UNRESOLVED DECISIONS

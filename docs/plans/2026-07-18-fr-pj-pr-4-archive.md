# FR-PJ-04 프로젝트 아카이브 구현 (PR-4)

> slug: fr-pj-pr-4-archive
> type: backend (classify 원본 migration → Maxi 확인 후 정정)
> agent: backend-engineer (리드) + db-engineer(V037) + security-engineer(PROJECT_ADMIN 게이트 검토)
> primary_bc: issue-tracking
> 생성: 2026-07-18

## Brief

**원문**: FR-PJ-04 프로젝트 아카이브 구현 (project-management-crud 스펙 §9.2 6분할 중 PR-4).

**범위**:
- `projects.archived_at` 컬럼 마이그레이션 **V037** (issue-tracking 최신 V036 확인, V037 free)
- `POST /archive` · `POST /unarchive` 엔드포인트 (PROJECT_ADMIN 게이트 = `ComponentPermission.UPDATE` 재사용, PR-3 선례)
- PR-3에서 이관받은 **PJ2-2** (아카이브 프로젝트 목록 기본 제외 + `?archived` 필터)
- PR-3에서 이관받은 **PJ3-2** (아카이브된 프로젝트 설정변경 시 409)
- issue-tracking 프로젝트 스코프 쓰기 **17곳 잠금** (Version 5·Component 4·CustomField 3·IssueTemplate 3·ProjectLead 1·ProjectRequire2fa 1)
- 이슈 쓰기 초크포인트 1곳
- `Clock` 주입 필수 (archived_at 타임스탬프)

**정본 스펙**: `docs/specs/2026-07-17-project-management-crud.md` §9.2
**직전 PR-3 plan**: `docs/plans/2026-07-18-fr-pj-pr-3.md`

**함정 (체크포인트/메모리)**:
- 아카이브 예외 동명충돌 주의 — PR-3에서 `ProjectQueryNotFoundException` 별도명명 선례
- 리포지토리 신설 시 **IssueBcArchTest 검증 포함** (--tests 스코프 좁히다 놓친 회귀)
- cross-BC 빈 부재 시 `CrossBcPortTestConfig` mock 추가
- V번호는 머지 직전 재확인 (동시 브랜치 충돌)
- FR 총수 129 불변 (FR-PJ-01~04 완료마킹은 PR-5 몫)

## 도메인 정리 (← /bts-domain 채움)

**스킵.** 정본 마스터 스펙 `docs/specs/2026-07-17-project-management-crud.md`가 FR-PJ 6분할 전체의 도메인·용어·ADR을 이미 확정. PR-1~PR-3가 동일 스펙으로 domain 단계를 거쳤음. PR-4는 그 스펙 §2.4·§5·§9.2를 상속.

## 스펙 (← /bts-spec Phase A 채움)

**스킵.** 정본 스펙 §2.4(PJ4-1~8)·§5.2(V037)·§5.3(archived_at ⊥ deleted_at)·§6 EC·§7 C1~C10·§8 DoD-5/10/11·§9.2 PR-4 행 참조.

## Brainstorming Check (← /bts-spec Phase B 채움)

**스킵.** 스펙이 3회차 적대적 검토(§부록 B1·N1~N3·2.4-B·2.4-C)까지 박제 완료.

## Plan

> **PR-4 범위 = issue-tracking BC 단일.** cross-BC 아카이브 잠금(identity-access·automation·project-workflow·agile-planning·slack — `ProjectLifecyclePort` + 어댑터 6종)은 **D12 후속 FR로 범위 밖**(§9.3). PR 본문·KDoc에 "cross-BC 미잠금 + 목록 불완전"을 PJ4-8대로 명시한다.

### 확정 결정 (게이트1에서 Maxi 재확인 대상)

- **D-EC2 = 멱등 200.** 이미 아카이브된 프로젝트 재아카이브 → 200 no-op. 아카이브 해제도 대칭(이미 활성 → 200). 근거: 아카이브는 잠금 토글, UI 더블클릭 스푸리어스 에러 방지, 스펙 EC-2가 200을 우선 표기.
- **D-GUARD = issue-tracking 자체 술어(포트 불필요).** PR-4 잠금 대상이 전부 issue-tracking 소유(=`projects` 소유 BC)이므로 `projects.archived_at`를 **직접 조회**하는 `ProjectArchiveGuard`(신규, issue-tracking 내부)로 충분. **issueKey→projectKey 해석도 이 guard가 제공**(하위리소스 쓰기용). cross-BC 창구(`ProjectLifecyclePort`)는 후속 FR. → 이번 PR은 **새 cross-BC 의존 0** → C6(OpenApi @MockBean) 회귀 위험 없음.
- **D-UNARCHIVE 예외.** 아카이브 잠금 게이트는 `unarchive`를 **막지 않는다**(S9 — 아카이브는 mutation을 막지만 해제는 예외). archive/unarchive 엔드포인트 자체는 스코프 쓰기 잠금의 대상이 아니라 라이프사이클 op.
- **D-OCC = last-write-wins.** `projects`에 `version`(OCC) 컬럼 부재(§5.1). 아카이브 토글은 idempotent라 동시성 충돌이 무해 → OCC 미도입.
- **D-READ 불변.** `buildActiveSecureWhere`(`IssueRepository.kt:945-959`, 실측 archived 무참조)는 **읽기 술어 — 손대지 않는다**(PJ4-6). `deleted_at` 읽기 술어 10곳도 무변경(§5.3).
- **D-ORDER = permission 먼저, state-lock 나중** (리뷰 반영). 미인가 actor가 409(아카이브 상태)로 프로젝트 상태를 알아내지 못하도록 모든 쓰기에서 `assertPermission` → `ProjectArchiveGuard.check` 순서. 선례 `VersionApplicationService.kt:88`(assertPermission) → `:204`(assertNotArchived).
- **D-TESTPROFILE = test 프로파일 + 제어형 fake `ComponentPermissionResolver`** (CONCERN-1, Maxi 확정). 스펙 C1/DoD-4의 `@ActiveProfiles("prod")`는 SystemPermission/CREATE_PROJECT 경로(PR-1/2) 기준. PR-4의 PROJECT_ADMIN 게이트는 `ComponentPermissionResolver`이고 실 구현체가 identity-access 소유라 issue-tracking에서 prod 프로파일 부팅불가/vacuous. → 선례 `Require2faTestPermissionConfig` 동형으로 `@ActiveProfiles("test")` + 제어형 fake(admin→true/비admin→false) 신설. **spec DoD-4 deviation → Task 10에서 문서화**.
- **D-SUBRESOURCE = 이슈 하위리소스 쓰기 8종도 이번 PR 잠금** (BLOCKER-1, Maxi 확정, 범위 확대). issue-tracking 자기 소유라 D12 이연 근거 불성립. NFR-3/PJ4-4 스펙 문구 전수 동기화(Task 10).

### Task 1. V037 마이그레이션 — `projects.archived_at` + init_codegen 미러

**메타.**
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V037__projects_archived_at.sql`, `backend/modules/issue-tracking/src/main/resources/db/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/**/SchemaMigrationTest 계열`]
- depends-on: []

**RED**: `projects` 스키마에 `archived_at` 부재를 확인하는 테스트(또는 SchemaMigrationTest에 컬럼 존재 단언 추가) → 실패.
**GREEN**: `ALTER TABLE projects ADD COLUMN archived_at TIMESTAMPTZ NULL` (V037). `init_codegen.sql` 미러 필수(C3, [[jooq-init-codegen-mirror]]).
**REFACTOR**: 마이그레이션 L1 한글 역할 주석 + FK 피참조 순서(identity→issue→workflow, §5.2) 무영향 확인.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*SchemaMigration*'` + `:compileKotlin`(jOOQ 재생성). **V번호 머지 직전 재확인**(C8, [[migration-vnumber-concurrent-branch-collision]]).

### Task 2. Project 도메인 `archivedAt` 필드 **신설** + 리포지토리 배선

**메타.**
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/domain/Project.kt`, `.../project/repository/ProjectLookupRepository.kt`, `.../project/repository/ProjectQueryRepository.kt`, 대응 test]
- depends-on: [1]

**RED**: 아카이브된 프로젝트 조회 시 `archivedAt` non-null 매핑 리포지토리 통합 테스트 → 실패.
**GREEN**: `Project.kt`는 현재 id/key/name 3필드뿐 — **`archivedAt: Instant?` 필드 신설**(매핑이 아니라 신설). 조회 SELECT에 `archived_at` 포함. **읽기는 아카이브 프로젝트도 반환**(EC-3, PJ4-6).
**REFACTOR**: 매핑 중복 제거.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*ProjectQuery*' --tests '*ProjectLookup*'`.

### Task 3. `ProjectArchiveGuard` — 중앙 잠금 술어 (security-engineer)

**메타.**
- agent: `security-engineer`
- files: [`.../project/archive/ProjectArchiveGuard.kt`, `.../project/archive/ProjectArchivedException.kt`, 대응 test]
- depends-on: [1]

**RED**: 아카이브 프로젝트 → `ProjectArchivedException`(409) / 활성 → 통과 / **삭제(deleted_at) 프로젝트를 아카이브로 오판 안 함**(판별자, C2 — 위반 주입해 fail 확인). guard는 두 진입 제공 — `check(projectKey)` + `checkByIssue(issueKey)`(issueKey→projectKey 해석, 하위리소스용).
**GREEN**: `projects.archived_at IS NOT NULL` **단독** 판정. `deleted_at`·`buildActiveSecureWhere` 미참조. 409 매핑(동명충돌 회피 `ProjectArchivedException` 신규명, [[duplicate-exception-name-cross-package-status]]).
**REFACTOR**: 예외 핸들러 스코프 격리(assignableTypes-scoped + HIGHEST_PRECEDENCE, PR-3 선례 [[domain-exception-http-handler-basepackage-scope]]).
**검증**: `--tests '*ProjectArchiveGuard*'`. **★security 검토: deleted_at 오독=이슈 소실 금지**.

### Task 4. 테스트 권한 인프라 — 제어형 fake `ComponentPermissionResolver` config

**메타.**
- agent: `security-engineer`
- files: [`.../src/test/kotlin/com/bts/issue/project/archive/ArchiveTestPermissionConfig.kt`(신규 @TestConfiguration)]
- depends-on: []

**RED/GREEN**: `Require2faTestPermissionConfig` 동형 — `@ActiveProfiles("test")`에서 제어형 fake `ComponentPermissionResolver`(admin actor→true, 비admin→false) 빈 제공. C1 vacuous 회피(D-TESTPROFILE). Task 5/7/9의 권한 테스트가 이 config 소비.
**검증**: 컴파일 + Task 5에서 첫 소비 검증.

### Task 5. archive / unarchive 엔드포인트 — PROJECT_ADMIN 명시 게이트 + Clock

**메타.**
- agent: `backend-engineer`
- files: [`.../project/web/ProjectArchiveController.kt`, `.../project/archive/ProjectArchiveService.kt`, `.../project/repository/ProjectArchiveRepository.kt`, 대응 test]
- depends-on: [1, 2, 4]

**RED**: S6/S8 — `POST /{idOrKey}/archive` → `archived_at=now()`(Clock), `/unarchive` → `NULL`. 권한 PROJECT_ADMIN(`ComponentPermission.UPDATE`, **명시 호출**, PR-3 선례). EC-2 멱등 200. `@ActiveProfiles("test")` + fake resolver(D-TESTPROFILE) — 비admin 403 **비-vacuous**(위반 주입 확인, C2).
**GREEN**: `Clock` 주입(직접 `Instant.now()` 금지, PJ4-5). D-ORDER: permission→(archive/unarchive는 guard 우회, D-UNARCHIVE).
**REFACTOR**: KDoc "명시 게이트 이유(PAT)".
**검증**: `--tests '*ProjectArchive*'`.

### Task 6. PJ2-2 — 목록 아카이브 기본 제외 + `?archived` 필터 (FR-PJ-02 이관분)

**메타.**
- agent: `backend-engineer`
- files: [`.../project/query/ProjectQueryService.kt`, `.../project/web/ProjectQueryController.kt`, `.../project/repository/ProjectQueryRepository.kt`, 대응 test]
- depends-on: [2]

**RED**: S5 — `GET /api/v1/projects`는 아카이브 **기본 제외**, `?archived=true`면 아카이브만(D8 지라 관례). 단건 조회 `GET /{idOrKey}`는 아카이브도 반환(PJ2-3, 무변경).
**GREEN**: 목록 쿼리에 `archived_at` 필터 축 추가. `projectKeysOf` **불변**(§B3 — 술어 변경 금지, search-export-import 오염 방지).
**REFACTOR**: 필터 파라미터 검증.
**검증**: `--tests '*ProjectQuery*'`.

### Task 7. PJ3-2 — 아카이브 프로젝트 설정 변경 409 (name·lead·require2fa)

**메타.**
- agent: `backend-engineer`
- files: [`.../project/settings/ProjectSettingsService.kt`, `.../project/application/ProjectLeadApplicationService.kt`, `.../project/application/ProjectRequire2faApplicationService.kt`, 대응 test]
- depends-on: [3, 4, 5]

**RED**: S9 — 아카이브 프로젝트 `PATCH /{idOrKey}`(name)·lead·require2fa → **각각 409**(단 unarchive 허용). 각 409에 "활성 프로젝트 같은 쓰기→2xx" 양성 baseline 짝(C2 판별자, [[guard-handler-matrix-blindfold]]). `@ActiveProfiles("test")` + fake.
**GREEN**: 세 서비스 쓰기 진입에 D-ORDER(permission→`ProjectArchiveGuard.check`).
**검증**: `--tests '*ProjectSettings*' --tests '*ProjectLead*' --tests '*ProjectRequire2fa*'`.

### Task 8. 이슈 쓰기 초크포인트 — IssueApplicationService **쓰기 경로에만** 잠금

**메타.**
- agent: `backend-engineer`
- files: [`.../application/IssueApplicationService.kt`, 대응 test]
- depends-on: [3]

**RED**: S6/EC-3 — 아카이브 프로젝트 이슈 **쓰기 409**(생성·수정·전이), **읽기 200**. ★`assertPermission(:1477)`은 쓰기 9종 + `listIssues`/`listIssuesByCursor`(BROWSE) 공유 → guard를 **쓰기 진입에만** 정밀 배치(목록조회 409 금지, EC-3 파괴 방지). automation·Import·REST 3경로 동시 차단 단언.
**GREEN**: 쓰기 메서드에만 `ProjectArchiveGuard.check`. 읽기·목록 경로 무변경.
**검증**: `--tests '*IssueApplication*'` + 목록조회 200 회귀 테스트.

### Task 9. ★ 프로젝트 스코프 쓰기 전수 잠금 — 5중 교차 열거 (config 15 + 이슈 하위리소스 8)

**메타.**
- agent: `backend-engineer`
- files: [`.../version/application/VersionApplicationService.kt`, `.../component/application/ComponentApplicationService.kt`, `.../customfield/application/CustomFieldApplicationService.kt`, `.../template/application/IssueTemplateApplicationService.kt`, `.../attachment/application/IssueAttachmentService.kt`, `.../worklog/application/WorklogService.kt`, `.../link/application/LinkApplicationService.kt`, `.../link/application/IssueParentService.kt`, `.../epic/application/IssueEpicService.kt`, `.../watcher/application/IssueWatcherService.kt`, `.../application/IssueMoveService.kt`, `.../comment/application/CommentApplicationService.kt`, 대응 per-write test(it.each)]
- depends-on: [3]

**RED**: **"17"을 물려받지 않는다**(DoD-10·§2.4-B, [[spec-stated-count-becomes-blindfold]]). issue-tracking BC로 스코프한 5중 교차를 직접 grep해 **확정 목록** 산출:
1. 경로 `/api/v1/projects/{k}` 쓰기(class-level @RequestMapping 없는 컨트롤러 포함, N2)
2. DTO `projectKey`/`projectId` 필드
3. 프로젝트 스코프 권한 resolver 4종 소비처(컨트롤러+서비스, N3)
4. **부모 리소스 파생 — ★이슈 하위리소스 쓰기(경로 `/api/v1/issues/{key}/...`, issueKey 기반)**: Attachment·Worklog·Link·Parent·Epic·Watcher·Move·Comment. **리뷰 실측: IssueApplicationService 미경유(Task 8 초크포인트 밖)** → 개별 잠금 필수(BLOCKER-1)
5. HTTP 밖(@Scheduled·pgmq) issue-tracking 워커 유무 실측
→ 확정 목록의 **각 쓰기마다** 아카이브 시 409 + "활성→2xx" 양성 baseline 짝(C2 판별자).
**GREEN**: 각 서비스 쓰기 진입에 D-ORDER(permission→guard). config계열은 `check(projectKey)`, 이슈 하위리소스는 `checkByIssue(issueKey)`. ProjectLead·Require2fa는 Task 7에서 처리(중복 배제). Comment는 REST 미노출이나 automation/Import 어댑터 경유분까지 잠금.
**REFACTOR**: **확정 목록을 §부록에 기록**(개수 아닌 목록).
**검증**: `--tests '*Version*' '*Component*' '*CustomField*' '*Template*' '*Attachment*' '*Worklog*' '*Link*' '*Epic*' '*Watcher*' '*Move*' '*Comment*'` + **DoD-5 grep 재검증(미강제 지점 0)**.

### Task 10. 통합 검증 + 전수 동기화 + PR 본문

**메타.**
- agent: `backend-engineer`
- files: [`docs/specs/2026-07-17-project-management-crud.md`, `docs/plans/2026-07-18-fr-pj-pr-4-archive.md`, PR 본문]
- depends-on: [5, 6, 7, 8, 9]

**GREEN**:
- **스펙 전수 동기화**(범위 확대 반영, CLAUDE.md §전수 동기화) — NFR-3/PJ4-4 문구 갱신("초크포인트 1곳"이 전 이슈 쓰기를 안 덮음·이슈 하위리소스 8종 개별 잠금 추가), D-TESTPROFILE의 DoD-4 deviation 기록.
- DoD-5 미강제 지점 0 패턴 grep 재검증([[negative-guard-needs-body-discriminator]]).
- **prod 조립 부팅** `./gradlew :modules:app:test`(9 BC, C7·DoD-6, [[prod-assembly-boot-verification-required]]) — 머지 직전 rebase 후 재실행.
- `bash scripts/verify-master-plan.sh`(DoD-7) — **FR 총수 129 불변**(완료마킹 PR-5 몫).
- IssueBcArchTest 포함(신규 패키지 `project/archive` ArchUnit 룰2, [[gradle-batched-task-partial-test-run]]).
- **PR 본문·KDoc PJ4-8 명시**: cross-BC 미잠금 + "목록 불완전"(§9.3 후속 FR 5중 교차 재열거).
**검증**: 전 항목 XML 실측(`BUILD SUCCESSFUL` 불신, [[subagent-ktlint-false-green-controller-verify]]).

## Plan 메타

- task 수: 10 (리뷰 BLOCKER 반영으로 8→10: 이슈 하위리소스 8종 잠금 + PJ2-2 + 테스트 권한 config)
- 예상 wave: 6 (W1: T1·T3·T4 / W2: T2 / W3: T5·T6·T8·T9 / W4: T7 / W5: T10). depends 그래프는 bts-impl이 재계산.
- TDD 강제: yes (test 커밋 선행)
- 리드 agent: backend-engineer. T1=db-engineer. T3·T4=security-engineer.
- 추가 검증: ktlint·detekt·:modules:app:test(prod 조립)·verify-master-plan·IssueBcArchTest·DoD-5 grep
- 신규 cross-BC 의존: **0**(D-GUARD 자체 술어) → C6 회귀 위험 없음
- 범위 확대: 이슈 하위리소스 쓰기 8종(리뷰 BLOCKER-1) → 스펙 NFR-3/PJ4-4 전수 동기화(T10)

## 리뷰 결과

### 집중 리뷰 (2026-07-18) — security-engineer + backend-engineer 병렬, 실측 grep 기반

리뷰 깊이 = "집중 리뷰"(Maxi 선택). gstack 정식 plan-eng-review 대신 독립 관점 2개로 5개 초점 검증. 근거 원문: `.bts-cache/review-security.md` · `.bts-cache/review-backend.md`.

**🛑 BLOCKER-1 (backend, controller 실측 확인됨) — 이슈 하위리소스 쓰기 8종이 잠금 사각.**
issue-tracking **자기 소유** 프로젝트 스코프 쓰기인데 경로가 `/api/v1/issues/{key}/...`(projectKey 아닌 issueKey 기반)라 §2.4-B 5중 교차가 **구조적으로 놓침** — 스펙조차 §2.4-B/2.4-C/§9.3 어디서도 언급 안 함(눈가리개 4회차):
- `IssueAttachmentService`(upload/delete) · `WorklogService`(create/update/delete) · `LinkApplicationService`·`IssueParentService` · `IssueEpicService` · `IssueWatcherService`(watch/unwatch) · `IssueMoveService`(move) · `CommentApplicationService`(create — REST 미노출이나 automation `AutomationIssueMutationAdapter:140`·Import `IssueImportAdapter:729`가 직접 호출).
- **실측 확인**: 위 서비스 3종(Attachment/Worklog/Watcher) 모두 `IssueApplicationService` 참조 **0건** → Task 6 초크포인트가 못 덮음. PJ4-4 "이슈 REST가 전부 IssueApplicationService 경유(D9 확증)"의 전제가 반증됨.
- **영향**: 아카이브된 프로젝트에 워크로그·첨부·댓글·링크·이동이 여전히 가능 → S6("아카이브는 쓰기만 죽는다")·NFR-3("미강제 지점 0") 위반. cross-BC 아님 → D12 이연 근거 불성립.
- **결정 필요(Maxi)**: (a) 이번 PR에서 8종도 잠근다(범위 확대) / (b) 명시 근거와 함께 후속 FR 이연(porousness 문서화).

**⚠️ CONCERN-1 (security) — C1 테스트 전략이 issue-tracking ComponentPermission 경로에 오적용.**
스펙 C1·DoD-4는 `@ActiveProfiles("prod")` + 실 grant를 요구하나, 이는 **SystemPermission/CREATE_PROJECT 경로(PR-1/2)** 기준이다. PR-4의 PROJECT_ADMIN 게이트는 `ComponentPermissionResolver`이고 실 구현체는 **identity-access 모듈**이라 issue-tracking test 클래스패스에 없음(@ActiveProfiles("prod") 테스트 0건). literal 적용 시 부팅 실패 또는 `AlwaysAllowComponentPermissionResolver` 기본값으로 "비관리자→403"이 vacuous 통과. **선례**: `Require2faTestPermissionConfig`(@ActiveProfiles("test") + 제어형 ground-truth fake). → **결정 필요(Maxi)**: PR-4 권한 테스트는 test 프로파일 + 제어형 fake `ComponentPermissionResolver` 신설로.

**plan 결함/정정 (자동 반영 예정, BLOCKER 아님):**
- ✏️ **PJ2-2 미배정** — Brief가 PR-4 범위로 선언한 "목록 기본 제외 + `?archived` 필터"가 Task 어디에도 없음. Task 2 "읽기는 아카이브도 반환"이 LIST에 적용되면 PJ2-2와 모순. → 전용 Task 신설 필요.
- ✏️ **가드 vs 권한 순서** — permission 먼저·state-lock 나중(선례 `VersionApplicationService:88` assertPermission → `:204` assertNotArchived). 미인가 actor가 409로 아카이브 상태 노출 방지. Task 4/5/6/7에 순서 명시.
- ✏️ **가드 배치 정밀** — `IssueApplicationService.assertPermission(:1477)`은 쓰기 9종 + `listIssues`/`listIssuesByCursor`(BROWSE) 2곳 공유. Task 6 GREEN "쓰기 진입 1곳"이 이걸 가리키면 목록조회까지 409 → EC-3 파괴. 가드는 **쓰기 경로에만** 정밀 배치.
- ✏️ **Task 2 표현** — `Project.kt`는 id/key/name 3필드뿐, `archivedAt` 필드 부재. "매핑"이 아니라 **필드 신설**.
- ✏️ **C2 판별자 확장** — Task 5/6/7 각 409 테스트에 "활성 프로젝트 같은 쓰기→2xx" 양성 baseline 짝지어 per-write 판별자화([[guard-handler-matrix-blindfold]]).

**OK (모순 없음):** guard 술어 정합(archived_at 단독·`buildActiveSecureWhere:945` archived 무참조·읽기 생존) · PROJECT_ADMIN 명시게이트(PR-3 선례 일치) · cross-BC 범위경계(D12 이연+PJ4-8 명시) · 확정결정 5건(D-EC2/GUARD/UNARCHIVE/OCC/READ 스펙 정합).

**종합**: BLOCKER 1 · CONCERN 1 · 자동정정 5. auth/migration BLOCKER는 무시 옵션 없음 → Maxi 결정 후 plan 갱신 → 게이트1.

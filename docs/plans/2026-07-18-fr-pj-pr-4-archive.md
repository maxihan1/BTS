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
- **D-GUARD = issue-tracking 자체 술어(포트 불필요).** PR-4 잠금 대상이 전부 issue-tracking 소유(=`projects` 소유 BC)이므로 `projects.archived_at`를 **직접 조회**하는 `ProjectArchiveGuard`(신규, issue-tracking 내부)로 충분. cross-BC 창구(`ProjectLifecyclePort`)는 후속 FR. → 이번 PR은 **새 cross-BC 의존 0** → C6(OpenApi @MockBean) 회귀 위험 없음.
- **D-UNARCHIVE 예외.** 아카이브 잠금 게이트는 `unarchive`를 **막지 않는다**(S9 — 아카이브는 mutation을 막지만 해제는 예외). archive/unarchive 엔드포인트 자체는 스코프 쓰기 잠금의 대상이 아니라 라이프사이클 op.
- **D-OCC = last-write-wins.** `projects`에 `version`(OCC) 컬럼 부재(§5.1). 아카이브 토글은 idempotent라 동시성 충돌이 무해 → OCC 미도입.
- **D-READ 불변.** `buildActiveSecureWhere`(`IssueRepository.kt:945-959`)는 **읽기 술어 — 손대지 않는다**(PJ4-6). `deleted_at` 읽기 술어 10곳도 무변경(§5.3).

### Task 1. V037 마이그레이션 — `projects.archived_at` + init_codegen 미러

**메타.**
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V037__projects_archived_at.sql`, `backend/modules/issue-tracking/src/main/resources/db/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/**/SchemaMigrationTest 계열`]
- depends-on: []

**RED**: `projects` 스키마에 `archived_at` 부재를 확인하는 테스트(또는 SchemaMigrationTest에 컬럼 존재 단언 추가) → 실패.
**GREEN**: `ALTER TABLE projects ADD COLUMN archived_at TIMESTAMPTZ NULL` (V037). `init_codegen.sql` 미러 필수(C3 — jOOQ 코드생성 동기화, [[jooq-init-codegen-mirror]]).
**REFACTOR**: 마이그레이션 주석(L1 한글 역할 주석) + FK 피참조 순서(identity→issue→workflow, §5.2) 무영향 확인.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*SchemaMigration*'` + `./gradlew :backend:issue-tracking:compileKotlin`(jOOQ 재생성). **V번호는 머지 직전 재확인**(C8, [[migration-vnumber-concurrent-branch-collision]]).

### Task 2. Project 도메인/리포지토리 — 실제 `archived_at` 배선

**메타.**
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/domain/Project.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/repository/ProjectLookupRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/repository/ProjectQueryRepository.kt`, 대응 test]
- depends-on: [1]

**RED**: 아카이브된 프로젝트 조회 시 `archivedAt`이 non-null로 매핑되는지 리포지토리 통합 테스트 → 현재 하드코딩 null이라 실패.
**GREEN**: `Project.archivedAt`(현 placeholder null)을 실제 컬럼에서 매핑. 목록/단건 조회 SELECT에 `archived_at` 포함. **읽기는 아카이브 프로젝트도 반환**(EC-3, PJ4-6).
**REFACTOR**: 매핑 중복 제거.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*ProjectQuery*' --tests '*ProjectLookup*'`.

### Task 3. `ProjectArchiveGuard` — 중앙 잠금 술어 (security-engineer 검토 필수)

**메타.**
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/archive/ProjectArchiveGuard.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/archive/ProjectArchivedException.kt`, 대응 test]
- depends-on: [1]

**RED**: 아카이브된 프로젝트 → `ProjectArchivedException`(409) throw / 활성 → 통과 / **삭제된(deleted_at) 프로젝트를 아카이브로 오판하지 않음**(판별자 테스트, C2 — 위반 주입해 fail 확인). `@ActiveProfiles("prod")`(C1).
**GREEN**: `projects.archived_at IS NOT NULL` 단독 판정. `deleted_at`·`buildActiveSecureWhere` 미참조. throw 시 409 매핑(ExceptionTranslator — 동명충돌 회피 위해 `ProjectArchivedException` 신규명, [[duplicate-exception-name-cross-package-status]]).
**REFACTOR**: 예외 → HTTP 핸들러 스코프 격리(assignableTypes-scoped + HIGHEST_PRECEDENCE, PR-3 선례 [[domain-exception-http-handler-basepackage-scope]]).
**검증**: `./gradlew :backend:issue-tracking:test --tests '*ProjectArchiveGuard*'`. **★security-engineer 검토: 아카이브 술어가 이슈 소실로 이어지지 않는지(deleted_at 오독 금지)**.

### Task 4. archive / unarchive 엔드포인트 — PROJECT_ADMIN 명시 게이트 + Clock

**메타.**
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/ProjectArchiveController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/archive/ProjectArchiveService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/repository/ProjectArchiveRepository.kt`, 대응 test]
- depends-on: [1, 2]

**RED**: S6/S8 — `POST /{idOrKey}/archive` → `archived_at=now()`(Clock 주입), `POST /{idOrKey}/unarchive` → `archived_at=NULL`. 권한 = PROJECT_ADMIN(`ComponentPermission.UPDATE` 재사용, **명시 호출** — @PreAuthorize 금지, PAT 경로 role claim 부재, PR-3 선례). EC-2 멱등 200. `@ActiveProfiles("prod")` + 실제 grant 시드(C1·DoD-4).
**GREEN**: 서비스에 `Clock` 주입(`Instant.now()` 직접호출 금지, PJ4-5 [[condition-eval-chosen-actor-read-oracle]] 계열). unarchive는 아카이브 잠금 우회(D-UNARCHIVE).
**REFACTOR**: 컨트롤러 KDoc에 "명시 게이트 이유"(PAT) 주석.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*ProjectArchive*'`.

### Task 5. PJ3-2 — 아카이브 프로젝트 설정 변경 409 (name·lead·require2fa)

**메타.**
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/settings/ProjectSettingsService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/application/ProjectLeadApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/application/ProjectRequire2faApplicationService.kt`, 대응 test]
- depends-on: [3, 4]

**RED**: S9 — 아카이브된 프로젝트에 `PATCH /{idOrKey}`(name)·lead 변경·require2fa 변경 → **각각 409**. 단 unarchive는 허용. `@ActiveProfiles("prod")`.
**GREEN**: 세 서비스의 쓰기 진입에 `ProjectArchiveGuard.check(projectKey)` 호출.
**REFACTOR**: 중복 호출 패턴 정리.
**검증**: `./gradlew :backend:issue-tracking:test --tests '*ProjectSettings*' --tests '*ProjectLead*' --tests '*ProjectRequire2fa*'`.

### Task 6. 이슈 쓰기 초크포인트 — IssueApplicationService 잠금 (읽기는 생존)

**메타.**
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, 대응 test]
- depends-on: [3]

**RED**: S6/EC-3 — 아카이브 프로젝트 이슈 **쓰기 409**(생성·수정·전이), **읽기 200**. automation·Import·REST가 전부 이 초크포인트 경유(PJ4-4·D9 확증) → 세 경로 동시 차단 단언.
**GREEN**: `IssueApplicationService` 쓰기 진입 1곳에 `ProjectArchiveGuard.check`. 읽기 경로 무변경(`buildActiveSecureWhere` 무관).
**REFACTOR**: guard 호출 위치 KDoc(초크포인트 명시).
**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueApplication*'`.

### Task 7. ★ issue-tracking 프로젝트 스코프 쓰기 전수 잠금 — 5중 교차 열거 (DoD-10)

**메타.**
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/application/VersionApplicationService.kt`, `.../component/application/ComponentApplicationService.kt`, `.../customfield/application/CustomFieldApplicationService.kt`, `.../template/application/IssueTemplateApplicationService.kt`, 대응 per-write test(it.each)]
- depends-on: [3]

**RED**: **스펙의 "17"을 물려받지 않는다**(DoD-10·§2.4-B·[[spec-stated-count-becomes-blindfold]]). 5중 교차 열거를 **issue-tracking BC로 스코프**해 직접 grep:
1. 경로 `/api/v1/projects/{k}` 쓰기 매핑 (class-level @RequestMapping 없는 컨트롤러 포함 — N2)
2. request DTO에 `projectKey`/`projectId` 필드
3. issue-tracking 소유 프로젝트 스코프 권한 resolver 4종(`Version`·`Component`·`CustomField`·`Template`PermissionResolver) 소비처 — **컨트롤러+서비스 양쪽**(N3, 권한 판정이 service 내부일 수 있음)
4. 부모 리소스 파생
5. HTTP 밖(@Scheduled·pgmq) — issue-tracking엔 해당 워커 유무 실측
→ 확정 목록의 **각 쓰기마다** 아카이브 시 409 개별 테스트(it.each, C2 판별자).
**GREEN**: 열거된 각 application service 쓰기 진입에 `ProjectArchiveGuard.check`. Version(5)·Component(4)·CustomField(3)·IssueTemplate(3)은 자기 서비스, ProjectLead(1)·Require2fa(1)은 Task 5에서 처리 → 중복 배제.
**REFACTOR**: 확정 목록을 plan §부록에 기록(개수 아닌 목록).
**검증**: `./gradlew :backend:issue-tracking:test --tests '*Version*' --tests '*Component*' --tests '*CustomField*' --tests '*Template*'` + **DoD-5 grep 재검증(미강제 지점 0)**.

### Task 8. 통합 검증 + 전수 동기화 + PR 본문 명시

**메타.**
- agent: `backend-engineer`
- files: [`docs/plans/2026-07-18-fr-pj-pr-4-archive.md`, PR 본문]
- depends-on: [4, 5, 6, 7]

**RED**: n/a(검증 task).
**GREEN**:
- DoD-5 미강제 지점 0 패턴 grep 재검증(§8, [[negative-guard-needs-body-discriminator]]).
- **prod 조립 부팅**: `./gradlew :modules:app:test`(9 BC, C7·DoD-6, [[prod-assembly-boot-verification-required]]) — 머지 직전 rebase 후 재실행.
- `bash scripts/verify-master-plan.sh`(DoD-7) — **FR 총수 129 불변**(FR-PJ-04 완료마킹은 PR-5 몫, DoD-8과 별개로 이번 PR은 카운트 무변).
- IssueBcArchTest 포함 검증(리포지토리 신규 패키지 `project/archive` — ArchUnit 룰2 jOOQ 화이트리스트, [[gradle-batched-task-partial-test-run]]).
- **PR 본문·KDoc에 PJ4-8 명시**: cross-BC 쓰기 미잠금 + "목록 불완전"(§9.3 ~30곳은 후속 FR가 5중 교차로 재열거).
**검증**: 전 항목 XML 실측(`BUILD SUCCESSFUL` 불신, [[subagent-ktlint-false-green-controller-verify]]).

## Plan 메타

- task 수: 8
- 예상 wave: 5 (W1: T1 / W2: T2·T3 / W3: T4·T6·T7 / W4: T5 / W5: T8)
- TDD 강제: yes (test 커밋 선행)
- 리드 agent: backend-engineer. T1=db-engineer, T3=security-engineer.
- 추가 검증: ktlint·detekt·:modules:app:test(prod 조립)·verify-master-plan·IssueBcArchTest·DoD-5 grep
- 신규 cross-BC 의존: **0**(D-GUARD — issue-tracking 자체 술어) → C6 회귀 위험 없음

## 리뷰 결과 (← /bts-review-plan 채움)

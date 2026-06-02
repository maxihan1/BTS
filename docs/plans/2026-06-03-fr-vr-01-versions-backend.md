# FR-VR-01 — 버전 생성 + 시작일/릴리즈 예정일 (백엔드 D1~D5)

> slug: fr-vr-01-versions-backend
> type: api (backend)
> agent: backend-engineer (+ security-engineer 권한 가드 검토)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-VR-01 버전(Version) CRUD 구현 — issue-tracking BC. 컴포넌트(FR-CM-01, PR #59) 선례를 따라 버전 등록/조회/수정/삭제 API.
권한 판정은 `VersionPermissionResolver` 포트로 추상화하고 prod 실판정은 후속 FR-PM-03에 이연(FR-CM-01과 동일 구조).

- 범위: 이번 PR은 **백엔드 D1~D5**만. 프론트 UI(D6)/E2E(D7)는 후속 PR (FR-CM-01: PR #59 백엔드 → PR #64 프론트 패턴).
- plan 정의 출처: `docs/plan/product/issue-tracking.md` §3.2.1 (Plan slug `issue/versions`, 선행 §2.1.1).
- 선행 관계: FR-VR-01은 FR-PM-03(버전/컴포넌트 등록 권한)의 기능 선행 FR. `docs/plan/product/identity-access.md` §4.3 메모 참조.
- 권한 이연 ADR 선례: `docs/adr/2026-06-02-component-model-and-permission-deferral.md`.

D1. 도메인 — Version Aggregate (backend-engineer)
D2. 명세 (backend-engineer)
D3. 데이터 모델 — `versions` (db-engineer)
D4. 백엔드 — CRUD API (backend-engineer + security-engineer)
D5. 백엔드 테스트 (backend-engineer)

## 도메인 정리

- BC: issue-tracking
- 신규 엔티티: **Version Aggregate** (`com.bts.issue.version`, 컴포넌트 `com.bts.issue.component` 동형)
- 새 용어: 없음 (glossary에 "버전 Version — 릴리스 단위. fix/affects 관계로 이슈에 연결" 이미 존재)
- 핵심 차이(vs Component): 컴포넌트의 `lead_user_id`(users 참조) 자리에 버전은 날짜 두 필드(`start_date`, `release_date`). 따라서 cross-BC 사용자 참조 없음 → UserLookupPort 불필요
- 데이터 모델: `versions`(id UUID PK, project_id FK→projects, name, description nullable, start_date DATE nullable, release_date DATE nullable, created_at/updated_at, deleted_at soft delete). 활성 행 `(project_id, name)` 부분 유니크. → V010 마이그레이션
- 날짜 결정(Maxi 2026-06-03): 둘 다 선택값(nullable), **순서 미강제**(startDate ≤ releaseDate 강제 안 함, Jira 기본 동작)
- status(Unreleased/Released/Archived): **FR-VR-02 이연** (versions.status는 FR-VR-02 D3 소관)
- 권한: `VersionPermissionResolver` 포트 추상화, 비prod AlwaysAllow + !prod fallback/부팅가드, prod 실판정은 FR-PM-03 이연 (ComponentPermissionResolver 동형)
- 기존 결정 충돌: 없음
- 관련 ADR: [docs/adr/2026-06-03-version-model-and-permission-deferral.md](../adr/2026-06-03-version-model-and-permission-deferral.md) (생성됨), 선례 [docs/adr/2026-06-02-component-model-and-permission-deferral.md](../adr/2026-06-02-component-model-and-permission-deferral.md)

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-vr-01-versions-backend.md](../specs/2026-06-03-fr-vr-01-versions-backend.md)

핵심 시나리오 요약.
- 버전 생성/조회/수정/소프트삭제 CRUD (`/api/v1/projects/{projectIdOrKey}/versions`). 컴포넌트(FR-CM-01) 동형.
- name 필수(1~255, trim), description/startDate/releaseDate 선택값. 날짜는 ISO date, **순서 미강제**(역순 허용).
- 이름/설명은 결합 PATCH(문자열 sentinel), **날짜는 전용 `/dates` 서브리소스**(두 키 항상 존재, null=해제) — 컴포넌트 `/lead` 선례.
- 권한은 `VersionPermissionResolver` 포트(비prod AlwaysAllow + !prod fallback/부팅가드), prod 실판정 FR-PM-03 이연.
- 에러: 401 / 404 PROJECT_NOT_FOUND·VERSION_NOT_FOUND / 409 VERSION_NAME_DUPLICATE. status는 FR-VR-02 이연.

## Brainstorming Check

✅ 통과 (직접 적대적 sanity check, office-hours 스킵 — 정의된 백엔드 FR + 도메인 grill 완료, FR-CM-01 동형).
주요 발견: 날짜 PATCH 3-state 모호성→전용 `/dates`(Maxi 결정), 날짜 순서 미강제(Maxi 결정), 사용자 참조 부재로 UserLookupPort 제거, status FR-VR-02 이연. 미해소 결정 없음.

## Plan

> 검증 경로(메모리): gradlew는 `backend/`, 모듈 경로 `:modules:issue-tracking`. 즉 `cd backend && ./gradlew :modules:issue-tracking:test`.
> 전부 issue-tracking 단일 모듈(+shared-kernel 포트) → test 컴파일 단위 공유 → **직렬 dispatch**(메모리 bts-plan-wave-gradle-module-compile, FR-CM-01 선례).
> TDD red→green→refactor 강제 — 각 task `test:` 커밋이 `feat:` 커밋보다 먼저.

### Task 1. Version 도메인 Aggregate

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/domain/Version.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/domain/VersionTest.kt`]
- depends-on: []

**RED**: `VersionTest` — 빈/공백 이름 거부, 이름 trim 정규화, name 길이(≤255) 불변식, `rename`/`changeDescription`/`changeDates`/`softDelete` 도메인 메서드, **역순 날짜(startDate>releaseDate) 허용**(순서 미강제, Maxi 결정). 실패: `Version` 없음.
**GREEN**: `Version` Aggregate(id:UUID?, projectId:UUID, name, description?, startDate:LocalDate?, releaseDate:LocalDate?, deletedAt:Instant?). 정규화·불변식·도메인 메서드. `changeDates(startDate, releaseDate)`는 두 값 통째 치환(전용 /dates 서브리소스 대응). 날짜 순서 검증 없음 — **C2 반영: changeDates는 불변식 없는 순수 setter임을 KDoc에 명시**(미래에 순서 강제 추가 시 진입점 단일화 위해 도메인 메서드 경유 유지).
**REFACTOR**: 상수(MAX_NAME)·KDoc 정리(컴포넌트 Component.kt 동형).
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*VersionTest"`

### Task 2. versions 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V010__versions.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/migration/VersionsMigrationTest.kt`]
- depends-on: []

**RED**(C1 반영): 전용 `VersionsMigrationTest` — Flyway 직접 적용 후 `versions` 테이블/부분 유니크 인덱스/FK 인덱스/TIMESTAMPTZ/동명충돌/소프트삭제 재생성 검증(컴포넌트 `ComponentsMigrationTest.kt` 7-test 동형). 테이블 부재로 실패. (Task 4 표면화 대안 표현 제거 — TDD 강제상 T2 자체 RED 산출물 필요.)
**GREEN**: `versions(id UUID PK, project_id UUID NOT NULL REFERENCES projects(id), name VARCHAR(255) NOT NULL, description TEXT NULL, start_date DATE NULL, release_date DATE NULL, created_at, updated_at, deleted_at)` + 부분 유니크 인덱스 `(project_id, name) WHERE deleted_at IS NULL` + FK 인덱스 `idx_versions_project_id`. **init_codegen.sql 동일 미러**(메모리 jooq-init-codegen-mirror). status 컬럼 없음(FR-VR-02 이연).
**REFACTOR**: COMMENT ON + BC prefix 정책(ADR) 정합. V009(components) 다음 번호 확인.
**검증**: jOOQ codegen → 상수 생성 확인. `cd backend && ./gradlew :modules:issue-tracking:generateJooq` 컴파일.

### Task 3. ProjectLookup 공용 패키지 이동 (component → project)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/ProjectLookup.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/repository/ProjectLookupRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/ProjectLookupTest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/application/ComponentApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/web/ComponentControllerIntegrationTest.kt`]
- depends-on: []

**RED 없음(순수 이동 리팩토링)**: 기존 `ProjectLookupTest`가 회귀 가드(메모리: 이동 리팩토링은 RED 없는 refactor). 패키지 선언/import만 변경, 로직·시그니처 불변.
**REFACTOR(이동)**(B1·B2 반영):
- `com.bts.issue.component.application.ProjectLookup` → **`com.bts.issue.project.ProjectLookup`** (application 성격).
- `com.bts.issue.component.repository.ProjectLookupRepository` → **`com.bts.issue.project.repository.ProjectLookupRepository`** — **반드시 끝이 `.repository`** 여야 ArchUnit 룰2(`..repository..`만 jOOQ 접촉 허용, IssueBcArchTest.kt:172-188) 매칭 유지. 끝이 `.project`면 빌드 RED(B1).
- import 경로 수정 대상(B2): `ComponentApplicationService.kt`(ProjectLookup) + **`ComponentControllerIntegrationTest.kt`**(import 2건 ProjectLookup·ProjectLookupRepository + `@Bean` 팩토리 참조 3곳 L131·134·155) + `ProjectLookupTest.kt`(ProjectLookupRepository import). Maxi 결정 — 공용 공유.
- 기존 `ProjectLookupTest`도 `com.bts.issue.project`로 이동. **컴포넌트 동작 0 변경**(기존 컴포넌트 통합테스트가 회귀 가드).
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ProjectLookupTest" --tests "*ComponentControllerIntegrationTest" --tests "*ComponentApplicationServiceTest"` + ArchUnit `--tests "*IssueBcArchTest"` (룰2 그린 + 컴포넌트 회귀 0 확인)

### Task 4. VersionRepository (jOOQ) + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/repository/VersionRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/repository/VersionRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: Testcontainers 통합 — insert(날짜 有/無/역순)/findById(소속+활성)/findByProject(활성, name 정렬)/update/softDelete/활성 동명 유일·삭제후 재생성. 실패: repository 없음.
**GREEN**: jOOQ repository — CRUD + soft delete + 부분 유니크 활용. 도메인↔레코드 매핑(start_date/release_date ↔ LocalDate, null 안전). ComponentRepository 동형.
**REFACTOR**: 쿼리 추출·readOnly 트랜잭션 표기.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*VersionRepositoryTest"`

### Task 5. VersionPermissionResolver 포트 + AlwaysAllow + 부팅 가드

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/VersionPermissionResolver.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/VersionPermission.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/adapter/AlwaysAllowVersionPermissionResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/VersionPermissionResolverBootTest.kt`]
- depends-on: []

**RED**: 부팅 가드 통합테스트 — 비prod 컨텍스트에서 resolver 빈 1개 주입 성공. 의도는 "비prod 빈 존재 + prod-impl 부재 시 prod 부팅 차단"(BeanCreationException). 실패: 포트/빈 없음.
**GREEN**: `VersionPermissionResolver` 포트(shared-kernel, ComponentPermissionResolver `hasPermission(actorId, permission, scope)` 동형 시그니처) + `VersionPermission` enum(CREATE/UPDATE/DELETE) + `AlwaysAllowVersionPermissionResolver`(@Profile !prod). prod impl은 FR-PM-03.
**REFACTOR**: 포트 KDoc(FR-PM-03 prod impl 이연 명시).
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*VersionPermissionResolverBootTest"`

### Task 6. CRUD ApplicationService (도메인 경유)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/application/VersionApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/domain/VersionExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/application/VersionApplicationServiceTest.kt`]
- depends-on: [1, 3, 4, 5]

**RED**: MockK 단위 — 생성(날짜有/無)/수정(name·description)/날짜변경(지정·해제, 별 메서드)/삭제, 프로젝트 미존재→VersionProjectNotFound, 버전 미존재→VersionNotFound, 이름 중복→DuplicateVersionName(활성). 도메인 정규화 메서드 호출 검증(우회 금지, patch-merge-domain-bypass). **사용자 참조 없음 → 리드 검증/UserLookupPort 없음**. 실패: service 없음.
**GREEN**: `@Transactional` service — ProjectLookup(이동된 com.bts.issue.project)·VersionRepository·resolver 조율. `update(name,description)`와 `changeDates(startDate,releaseDate)` **별 메서드**(전용 /dates 서브리소스 대응). 도메인 메서드 경유(rename/changeDescription/changeDates/softDelete). 23505 catch→409(DataIntegrityViolation + jOOQ IntegrityConstraintViolation 이중 catch, 컴포넌트 tryInsert 동형). sealed 예외. ComponentApplicationService 동형(단 validateLead/UserLookupPort 제거). **C3 반영: `VersionExceptions.kt`에 4개만**(VersionProjectNotFound/VersionNotFound/DuplicateVersionName/VersionAccessDenied) — LeadNotFound 미포함.
**REFACTOR**: 예외 파일 분리·메서드 추출.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*VersionApplicationServiceTest"`

### Task 7. Controller + DTO + ExceptionHandler + ErrorCodes + 통합테스트

**메타**.
- agent: `backend-engineer` (권한 가드 부분 security-engineer 검토)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/web/VersionController.kt`, `.../web/dto/CreateVersionRequest.kt`, `.../web/dto/UpdateVersionRequest.kt`, `.../web/dto/ChangeVersionDatesRequest.kt`, `.../web/dto/VersionResponse.kt`, `.../web/VersionExceptionHandler.kt`, `.../web/VersionErrorCodes.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/web/VersionControllerIntegrationTest.kt`]
- depends-on: [6]

**RED**: Testcontainers 통합 — S1~S9: POST 201(날짜有/無/역순)/GET 목록(삭제 제외)/GET 단건/PATCH name·description 200/**PATCH /dates 200(지정·해제)**/DELETE 204/404 PROJECT·VERSION/409 중복/401 미인증. 401 검증은 기존 Issue 통합테스트 Security 셋업 재사용. 실패: 컨트롤러 없음.
**GREEN**: `VersionController`(`/api/v1/projects/{projectIdOrKey}/versions`, DataResponse 래퍼) — POST/GET목록/GET단건/PATCH(name·description, 문자열 sentinel)/**PATCH `/{id}/dates`(ChangeVersionDatesRequest{startDate:LocalDate?, releaseDate:LocalDate?}, 2-state each)**/DELETE. DTO(Jakarta Validation, toAppRequest/from) + `@RestControllerAdvice(basePackages=["com.bts.issue.version.web"])` ExceptionHandler(ProblemDetail+errorCode, BC격리) + ErrorCodes 상수. 동시 생성 race → 유니크 제약(23505) catch→409. ComponentController 동형(단 /lead → /dates). **N1: ErrorCodes는 컴포넌트 비일관 답습** — ACCESS_DENIED만 `VERSION_ACCESS_DENIED`, NOT_FOUND/DUPLICATE는 `VERSION_NOT_FOUND`/`VERSION_NAME_DUPLICATE`, PROJECT_NOT_FOUND/VALIDATION_FAILED/INTERNAL_ERROR는 접두사 없음(ComponentErrorCodes 동형).
**REFACTOR**: KDoc·엔드포인트 주석·import 정렬(ktlint 파일단위, 모듈 ktlintFormat 금지).
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*VersionControllerIntegrationTest" :modules:issue-tracking:ktlintMainSourceSetCheck :modules:issue-tracking:detekt`

## Plan 메타

- task 수: 7
- dispatch: **직렬**(전부 issue-tracking 단일 모듈 + shared-kernel 포트, test 컴파일 공유 — 메모리 bts-plan-wave-gradle-module-compile). 의존: T1·T2·T3·T5 독립 → T4[1,2] → T6[1,3,4,5] → T7[6].
- TDD 강제: yes (test→feat 커밋 순서, bts-impl 자동 검증). T3은 순수 이동 리팩토링(기존 테스트가 회귀 가드).
- 추가 검증: jOOQ codegen, ktlint(파일단위)·detekt, Testcontainers 통합. 머지 전 controller가 `:test :ktlintMainSourceSetCheck :ktlintTestSourceSetCheck :detekt` 직접 실행(메모리 subagent-ktlint-false-green).
- 핵심 차이(vs FR-CM-01): UserLookupPort/리드검증/422 제거, /lead→/dates(날짜 2필드), status 없음(FR-VR-02 이연), ProjectLookup 공용 이동(T3 신규).

## 리뷰 결과

### plan-eng-review (2026-06-03, code-reviewer 독립 dispatch, ground-truth 대조)

메모리 bts-review-plan-autoplan-overkill대로 autoplan 4종 대신 code-reviewer를 실제 컴포넌트 코드/shared-kernel/ArchUnit과 1:1 대조 dispatch.

- **BLOCKER 2건 → 해소(plan 수정 완료)**:
  - **B1** — T3 `ProjectLookupRepository`를 `com.bts.issue.project`로 옮기면 ArchUnit 룰2(jOOQ 접촉은 `..repository..`만 허용, IssueBcArchTest.kt:172-188, PR #63 정정 룰) 위반 → 빌드 RED. **해소: 목적지를 `com.bts.issue.project.repository.ProjectLookupRepository`로(끝이 `.repository` 유지), ProjectLookup(application)만 `com.bts.issue.project`. T3 본문·files·검증에 ArchUnit 그린 확인 추가.**
  - **B2** — T3 import 수정 대상이 ComponentApplicationService 하나만 명시됐으나, 실제로 `ComponentControllerIntegrationTest.kt`(import 2 + @Bean 팩토리 참조 3곳 L131·134·155) + `ProjectLookupTest.kt` import도 고쳐야 컴파일. **해소: T3 files에 ComponentControllerIntegrationTest.kt 추가, import 수정 대상 전부 명시.**
- **CONCERN 3건 → 반영**:
  - **C1** — T2 마이그레이션 RED가 "Task 4에서 표면화" 대안으로 모호 → TDD 강제상 전용 RED 필요. **해소: `VersionsMigrationTest.kt`(컴포넌트 ComponentsMigrationTest 7-test 동형) T2 files에 명시.**
  - **C2** — `changeDates`가 불변식 없는 순수 setter → 도메인 경유 방어가치가 rename보다 약함. **반영: changeDates no-invariant를 KDoc 명시(미래 순서강제 시 진입점 단일화).**
  - **C3** — `VersionExceptions.kt`에 LeadNotFound 미포함(4개: ProjectNotFound/NotFound/Duplicate/AccessDenied) 명시. **반영.**
- **NIT**: N1 ErrorCodes 접두사 비일관(ACCESS_DENIED만 접두사) 컴포넌트 동형 답습 명시(반영). N2 부팅테스트 패키지 위치 PASS.
- **PASS (ground-truth 확인)**: shared-kernel 포트 위치(`com.bts.shared.permission`)·`hasPermission(actorId, permission, projectId)` 시그니처, VersionPermission enum(CREATE/UPDATE/DELETE), V010 마이그레이션 번호, init_codegen 미러 패턴, @Profile("!prod")+부팅가드, 23505 도메인 우회 금지(이중 catch), DTO/DataResponse/ExceptionHandler basePackages 규약, status FR-VR-02 범위 밖, UserLookupPort/422 제거 일관성, depends-on 그래프(순환·누락 0), 단일모듈 직렬 dispatch, subagent-ktlint-false-green 머지전 직접검증. 스펙↔plan 누락 0.

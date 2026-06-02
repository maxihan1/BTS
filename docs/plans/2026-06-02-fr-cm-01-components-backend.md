# FR-CM-01 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드 (백엔드 D1~D5)

> slug: fr-cm-01-components-backend
> type: api
> agent: backend-engineer (+ security-engineer: D4 권한 가드)
> primary_bc: issue-tracking
> 생성: 2026-06-02

## Brief

FR-PM-03(버전/컴포넌트 등록 권한)의 **기능 선행**. 권한을 얹을 컴포넌트 CRUD 기능 자체가
미구현이라, 먼저 이 기능을 만든다. plan §3.1.1 FR-CM-01 — 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드.

- 범위: 백엔드 D1~D5 (D1 도메인 Component Aggregate / D2 명세 / D3 데이터모델 components(lead_user_id) / D4 CRUD API + 권한 가드 / D5 백엔드 테스트). D6 프론트 UI·D7 E2E는 후속 PR.
- 선행 충족: §2.1.1 FR-IS-01(이슈 CRUD) ✅ 완료. 권한 인프라 FR-PM-02 ✅ 완료.
- classify 정정: identity-access 오판 → issue-tracking(컴포넌트는 issue-tracking BC).

## 도메인 정리

- **BC**: issue-tracking (classify의 identity-access 오판 정정)
- **영향 엔티티**: Component (신규 Aggregate Root). Issue 연결은 FR-CM-02(다중 컴포넌트 할당) 범위, FR-CM-01 아님.
- **새 용어**: 없음 — "컴포넌트(Component)"/"버전(Version)" glossary 기등록. "컴포넌트 리드(Component Lead)"는 glossary 한 줄 추가 후보(Maxi 승인 대기).
- **데이터 모델**: `components(id, project_id FK→projects, name, description?, lead_user_id?, created_at, updated_at, deleted_at)`. 같은 BC라 projects 실 FK. 활성 기준 (project_id, name) 유일. soft delete(DATA.md §3).
- **권한 가드 결정(Maxi, ①)**: 리졸버 포트 패턴 — `ComponentPermissionResolver`(비prod AlwaysAllow + !prod fallback 빈 + 부팅 가드), prod 실판정은 **FR-PM-03 이연**. 이슈 권한(FR-IS-01→FR-PM-02)과 동형. 인증 필수 + 프로젝트 존재 + 리드 검증은 FR-CM-01에서.
- **리드 검증**: `UserLookupPort`(shared-kernel, FR-IS-03 선례)로 존재 검증 → 없으면 422 `COMPONENT_LEAD_NOT_FOUND`. lead nullable.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: [docs/adr/2026-06-02-component-model-and-permission-deferral.md](../adr/2026-06-02-component-model-and-permission-deferral.md) (생성됨)
- **선행 충족**: FR-IS-01(이슈 CRUD) ✅, FR-PM-02(권한 인프라) ✅.

## 스펙

전체 스펙. [docs/specs/2026-06-02-fr-cm-01-components-backend.md](../specs/2026-06-02-fr-cm-01-components-backend.md)

핵심 시나리오 요약.
- `POST/GET/PATCH/DELETE /api/v1/projects/{projectIdOrKey}/components` — 컴포넌트 CRUD, 리드(lead) 선택값.
- 검증: 프로젝트 존재(404 PROJECT_NOT_FOUND)·컴포넌트 소속(404 COMPONENT_NOT_FOUND)·이름 중복(409, 활성 기준)·리드 실재(422 COMPONENT_LEAD_NOT_FOUND, UserLookupPort).
- 권한: 인증(401)은 Security 필터, 실 판정은 ComponentPermissionResolver(비prod AlwaysAllow)로 FR-PM-03 이연. soft delete. 3-state PATCH.

## Brainstorming Check

✅ 통과 (직접 적대적 sanity check, office-hours 스킵). 발견 2건 반영 — (1) issue-tracking 프로젝트 조회 컴포넌트 신규 필요(FR-4b), (2) actor 추출 cross-BC 경계 → FR-PM-03 이연. EC/메모리 교훈(3-state, 활성 유니크, 도메인 우회, init_codegen, profile bean) 선반영. 미해소 결정 0.

## Plan

> 검증 경로 정정(메모리): gradlew는 `backend/`, 모듈 경로 `:modules:issue-tracking`. 즉 `cd backend && ./gradlew :modules:issue-tracking:test`.
> 전부 issue-tracking 단일 모듈 → test 컴파일 단위 공유 → **직렬 dispatch**(메모리 bts-plan-wave-gradle-module-compile, FR-IS-03 선례).
> TDD red→green→refactor 강제 — 각 task `test:` 커밋이 `feat:` 커밋보다 먼저.

### Task 1. Component 도메인 Aggregate

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/domain/Component.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/domain/ComponentTest.kt`]
- depends-on: []

**RED**: `ComponentTest` — 빈/공백 이름 거부, 이름 trim 정규화, `rename`/`changeLead`/`changeDescription`/`softDelete` 도메인 메서드, name 길이(≤255) 불변식. 실패: `Component` 없음.
**GREEN**: `Component` Aggregate(id:UUID?, projectId:UUID, name, description?, leadUserId?:UUID, deletedAt?). 정규화·불변식·도메인 메서드.
**REFACTOR**: 상수(MAX_NAME)·KDoc·VO 정리.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ComponentTest"`

### Task 2. components 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/Vxxx__components.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`]
- depends-on: []

**RED**: 마이그레이션 적용 통합테스트(또는 Task 3 repo 통합테스트에서 표면화) — `components` 테이블·부분 유니크 인덱스 부재로 실패.
**GREEN**: `components(id UUID PK, project_id UUID NOT NULL REFERENCES projects(id), name VARCHAR(255) NOT NULL, description TEXT NULL, lead_user_id UUID NULL, created_at, updated_at, deleted_at)` + 부분 유니크 인덱스 `(project_id, name) WHERE deleted_at IS NULL`. **init_codegen.sql 동일 미러**(메모리 jooq-init-codegen-mirror).
**REFACTOR**: COMMENT ON + BC prefix 정책(ADR) 정합.
**검증**: jOOQ codegen → 상수 생성 확인. `cd backend && ./gradlew :modules:issue-tracking:generateJooq` 컴파일.

### Task 3. ComponentRepository (jOOQ) + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/repository/ComponentRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/repository/ComponentRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: Testcontainers 통합 — insert/findById(소속+활성)/findByProject(활성, name 정렬)/softDelete/활성 동명 유일·삭제후 재생성. 실패: repository 없음.
**GREEN**: jOOQ repository — CRUD + soft delete + 부분 유니크 활용. 도메인↔레코드 매핑.
**REFACTOR**: 쿼리 추출·readOnly 트랜잭션 표기.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ComponentRepositoryTest"`

### Task 4. 프로젝트 조회 (in-BC, 존재 + key→id) + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/application/ProjectLookup.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/application/ProjectLookupTest.kt`]
- depends-on: [2]

**RED**: 통합 — projectKey→id 해석, UUID 직접 수용, 소프트 삭제·미존재 프로젝트는 미해석(404 신호). 실패: lookup 없음.
**GREEN**: issue-tracking 소유 projects 테이블 자체 조회(in-BC). `resolve(projectIdOrKey): UUID?`(활성만). **C1 반영: key→id 쿼리는 기존 `IssueRepository.findProjectIdByKey`(soft-delete 제외) 패턴 재사용/추출, UUID 직접 분기만 신규.** (참고: ProjectMemberController.resolveProjectId는 identity-access BC라 import 불가 — 경로 규약만 참고.)
**REFACTOR**: 정규식(UUID 판별)·쿼리 정리.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ProjectLookupTest"`

### Task 5. ComponentPermissionResolver 포트 + AlwaysAllow + 부팅 가드

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/ComponentPermissionResolver.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/adapter/AlwaysAllowComponentPermissionResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/ComponentPermissionResolverBootTest.kt`]
- depends-on: []

**RED**: 부팅 가드 통합테스트 — 비prod 컨텍스트에서 resolver 빈 1개 주입 성공. **C2 반영: 소비자(ComponentController)·구현(AlwaysAllow) 모두 issue-tracking 同모듈이라 FR-PM-02의 cross-BC 스캔 누락은 구조적으로 발생 안 함. 이 가드의 의도는 "비prod 빈 존재 + prod-impl 부재 시 부팅 차단"(prod에서 BeanCreationException)** — FR-PM-02 복붙 아님. 실패: 포트/빈 없음.
**GREEN**: `ComponentPermissionResolver` 포트(shared-kernel, IssuePermissionResolver `hasPermission(actorId, permission, scope)` 동형 시그니처) + `AlwaysAllowComponentPermissionResolver`(@Profile !prod). prod impl은 FR-PM-03.
**REFACTOR**: 포트 KDoc(FR-PM-03 prod impl 이연 명시).
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ComponentPermissionResolverBootTest"`

### Task 6. CRUD ApplicationService (리드 검증 + 도메인 경유)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/application/ComponentApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/domain/ComponentExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/application/ComponentApplicationServiceTest.kt`]
- depends-on: [1, 3, 4, 5]

**RED**: MockK 단위 — 생성(리드有/無)/수정(name·description)/리드변경(지정·해제, 별 메서드)/삭제, 프로젝트 미존재→ProjectNotFound, 컴포넌트 미존재→ComponentNotFound, 이름 중복→DuplicateName(활성), 리드 미실재→ComponentLeadNotFound(UserLookupPort.exists=false). 도메인 정규화 메서드 호출 검증(우회 금지, patch-merge-domain-bypass). 실패: service 없음.
**GREEN**: `@Transactional` service — ProjectLookup·ComponentRepository·UserLookupPort·resolver 조율. `update(name,description)`와 `changeLead(leadUserId?)` **별 메서드**(B1: 리드 전용 서브리소스 대응). 도메인 메서드 경유(changeLead/rename/changeDescription/softDelete). sealed 예외.
**REFACTOR**: 예외 파일 분리·메서드 추출.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ComponentApplicationServiceTest"`

### Task 7. Controller + DTO + ExceptionHandler + ErrorCodes + 통합테스트

**메타**.
- agent: `backend-engineer` (권한 가드 부분 security-engineer 검토)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/web/ComponentController.kt`, `.../web/dto/CreateComponentRequest.kt`, `.../web/dto/UpdateComponentRequest.kt`, `.../web/dto/ChangeComponentLeadRequest.kt`, `.../web/dto/ComponentResponse.kt`, `.../web/ComponentExceptionHandler.kt`, `.../web/ComponentErrorCodes.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/web/ComponentControllerIntegrationTest.kt`]
- depends-on: [6]

**RED**: Testcontainers 통합 — S1~S10: POST 201(리드有/無)/GET 목록(삭제 제외)/GET 단건/PATCH name·description 200/**PATCH /lead 200(지정·해제)**/DELETE 204/404 PROJECT·COMPONENT/409 중복/422 리드/401 미인증. 401 검증은 기존 Issue 통합테스트 Security 셋업 재사용(C1 NIT). 실패: 컨트롤러 없음.
**GREEN**: `ComponentController`(`/api/v1/projects/{projectIdOrKey}/components`, DataResponse 래퍼) — POST/GET목록/GET단건/PATCH(name·description, 문자열 sentinel)/**PATCH `/{id}/lead`(ChangeComponentLeadRequest{leadUserId:UUID?}, 2-state, B1 반영)**/DELETE. DTO(Jakarta Validation, toAppRequest/from) + `@RestControllerAdvice` ExceptionHandler(ProblemDetail+errorCode) + ErrorCodes 상수. 동시 생성 race → 유니크 제약(SQLState 23505) 위반 catch→409.
**REFACTOR**: KDoc·엔드포인트 주석·import 정렬(ktlint 파일단위, 모듈 ktlintFormat 금지).
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*ComponentControllerIntegrationTest" :modules:issue-tracking:ktlintMainSourceSetCheck :modules:issue-tracking:detekt`

## Plan 메타

- task 수: 7
- dispatch: **직렬**(전부 issue-tracking 단일 모듈, test 컴파일 공유 — 메모리 bts-plan-wave-gradle-module-compile). 의존: T1·T2·T5 독립 → T3[1,2]·T4[2] → T6[1,3,4,5] → T7[6].
- TDD 강제: yes (test→feat 커밋 순서, bts-impl 자동 검증)
- 추가 검증: jOOQ codegen, ktlint(파일단위)·detekt, Testcontainers 통합. 머지 전 controller가 `:test :ktlintMainSourceSetCheck :ktlintTestSourceSetCheck :detekt` 직접 실행(메모리 subagent-ktlint-false-green).

## 리뷰 결과

### plan-eng-review (2026-06-02, code-reviewer 독립 dispatch, ground-truth 대조)

- **BLOCKER 1건 → 해소(plan 수정 완료)**:
  - **B1** — leadUserId 결합 PATCH 3-state가 "FR-IS-03 동형" 오기. 실제 FR-IS-03(ChangeAssigneeRequest)은 모호성 때문에 전용 `/assignee` 서브리소스로 분리했고, 코드베이스에 presence-detection(JsonNullable) 없음 + UUID는 빈문자열 sentinel 불가 → 결합 PATCH로 해제/무변경 구분 불가. **해소: 전용 `PATCH .../components/{id}/lead` 서브리소스 도입(2-state), 결합 PATCH는 name/description만(문자열 sentinel). S4/S4b·API·FR-3·EC-1·Task6·Task7 전부 정정.**
- **CONCERN 2건 → 반영**:
  - **C1** — Task 4 프로젝트 조회: 기존 `IssueRepository.findProjectIdByKey`(soft-delete 제외) 부분 존재 → 쿼리 패턴 재사용/추출, UUID 직접 분기만 신규로 정정. 경로 선례 출처(ProjectMember=identity-access, in-BC 아님) 주석 정정.
  - **C2** — Task 5 부팅가드: 소비자·구현 모두 issue-tracking 同모듈이라 FR-PM-02 cross-BC 스캔 누락은 미발생. 가드 의도를 "비prod 빈 존재 + prod-impl 부재 부팅차단"으로 재명시.
- **PASS (ground-truth 확인)**: 포트 위치(shared-kernel `com.bts.shared.permission`)·시그니처, UserLookupPort.exists 422 동형, init_codegen V009 미러, DataResponse·ExceptionHandler·ErrorCodes 규약, 도메인 우회 차단, 직렬 dispatch + depends-on 그래프(순환·누락 0), 부분 유니크 23505 catch→409, TDD 형식, BC 격리(ArchUnit 1b 준수). 스펙↔plan 누락 0.
- **NIT**: SQLState 23505 명시(반영), 401 검증은 기존 Issue 통합테스트 Security 셋업 재사용(반영).

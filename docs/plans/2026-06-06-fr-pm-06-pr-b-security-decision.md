# FR-PM-06 PR-B — 이슈 보안 수준 판정 결선

> slug: fr-pm-06-pr-b-security-decision
> type: api
> agent: backend-engineer (+ security-engineer 검토: 권한 판정/resolver)
> primary_bc: issue-tracking (+ identity-access resolver 확장 — cross-BC 포트)
> 생성: 2026-06-06
> 선행: PR-A(#86, 관리 인프라) 머지 완료. 최신 main(FR-CM-04 #89) 기준.

## Brief

FR-PM-06(이슈 보안 수준)의 후속 PR-B. PR-A에서 Jira Cloud 방식 스킴→등급→멤버(5타입) **관리 인프라**까지 머지됐고, 이번 PR-B는 실제 **이슈 차단 판정 결선**을 구현한다.

범위:
- `issues.security_level_id` 컬럼 추가 (issue-tracking 마이그레이션 + init_codegen.sql 미러 필수 — jOOQ 상수 생성, jooq-init-codegen-mirror 교훈).
- 이슈 생성/편집 시 등급 지정 API (SET_ISSUE_SECURITY 가드). 적용 스킴 미소속 등급이면 422.
- cross-BC `IssueSecurityLookup` 포트 (identity-access가 issues의 security_level_id/reporter_id/assignee_id read, ProjectDirectory 패턴).
- `IdentityAccessIssuePermissionResolver` 판정 확장: VIEW 매트릭스 통과 AND 등급 멤버(5타입) 충족, 미통과 시 404 (FR-PM-05 assertViewIssueOrNotFound 일관).
- listIssues 목록 필터 (등급 멤버 아닌 이슈 제외, N+1 회피).
- prod Testcontainers 통합으로 판정 ground-truth (S10~S13, non-prod AlwaysAllow 마스킹 주의).

(선택 후속) D6/D7 — 이슈 생성/편집 등급 선택 UI + E2E.

## 도메인 정리

- **BC**: issue-tracking(주) + identity-access(판정 확장, cross-BC read). 단일 PR-B로 두 BC를 가로지르며, 이는 ADR `2026-06-06-issue-security-level-scheme-model.md` §결과의 "2 PR 분할(PR-A=관리 인프라 / PR-B=결선)"로 이미 확정된 의도. cross-BC 읽기는 `ProjectDirectory` 선례(아래)를 따르므로 BC 격리 위반 아님.
- **도메인 언어**: PR-A에서 확정·머지됨(신규 용어 0). 이슈 보안 스킴(IssueSecurityScheme) → 보안 등급(IssueSecurityLevel) → 등급 멤버(SecurityLevelMember, 5타입). 본 PR은 판정/지정 동작만 추가.
- **영향 엔티티/컬럼**:
  - (issue-tracking) `issues.security_level_id UUID NULL` 신규 — 등급 미지정 = NULL(모든 VIEW 통과자에게 공개). FK 미적용(BC 격리, 등급은 identity-access 소유). 마이그레이션 + `init_codegen.sql` 미러 필수(jooq-init-codegen-mirror 교훈, V006/V012 선례).
  - (identity-access) 신규 테이블 없음 — PR-A 테이블 4종 read.
- **판정 규칙(ADR §결정 4 — 본 PR이 코드로 구현)**:
  - `VIEW_ISSUE` 매트릭스 통과 **AND** (등급 NULL **OR** actor가 등급 멤버 5타입 중 하나 충족) → 통과. 아니면 `false` → 컨트롤러가 404(`assertViewIssueOrNotFound` 일관, FR-PM-05).
  - 멤버 5타입 충족: REPORTER=actor==issue.reporter_id / ASSIGNEE=actor==issue.assignee_id / USER=actor==member_value / GROUP=actor가 그룹(FR-PM-09) 소속 / PROJECT_ROLE=actor의 ProjectMembership.role==member_value.
  - **관리자 우회 없음**(ADR §결정 5).
- **cross-BC 포트 — `IssueSecurityLookup`(신규, identity-access)**: `ProjectDirectory` 동형. 인터페이스 + `Jdbc*` 구현. issue-tracking `issues` 테이블의 `security_level_id`/`reporter_id`/`assignee_id`를 raw SQL `NamedParameterJdbcTemplate` + `@Transactional(readOnly=true)`로만 read(직접 import 금지, ADR D2 deployment invariant). 의존 컬럼 KDoc 명시 + DDL drift 경고.
- **resolver 확장 지점**: `IdentityAccessIssuePermissionResolver.hasPermission` — VIEW/BROWSE 매트릭스 통과 직후 보안등급 게이트 추가. 멤버 충족 판정은 별도 순수 함수/도메인 서비스(`IssueSecurityDecider`류)로 분리. **issue-tracking `AlwaysAllowIssuePermissionResolver`(@Profile("!prod"))가 non-prod에서 마스킹** → ground-truth는 prod Testcontainers 통합만(issue-scope-global-prod-hard-deny / best-effort-loop-permission-exception-nonprod-mask 교훈).
- **이슈 등급 지정(issue-tracking)**: 생성/편집 시 `security_level_id` 설정. `SET_ISSUE_SECURITY` 가드(PR-A 시드 완료, count=13). 지정 등급이 프로젝트 **적용 스킴 소속** 아니면 422.
- **기존 결정 충돌**: 없음. ADR 2026-06-06 정본, 본 PR은 §결과 PR-B 항목 구현. 신규 ADR 불요.
- **관련 ADR**: [docs/decisions/2026-06-06-issue-security-level-scheme-model.md](../decisions/2026-06-06-issue-security-level-scheme-model.md) (PR-A).
- **미해결 설계 갈림길(→ /bts-spec 옵션 제시)**:
  1. **목록 필터 N+1 전략** — listIssues 등급 멤버 아닌 이슈 제외. 후처리 배치 필터 vs 쿼리 술어 푸시다운. cross-BC·성능 stakes.
  2. **등급 지정 "적용 스킴 소속" 422 검증 경로** — issue-tracking cross-BC read 포트 직접 검증 vs identity-access 위임.

## 스펙

전체 스펙. [docs/specs/2026-06-06-fr-pm-06-pr-b-security-decision.md](../specs/2026-06-06-fr-pm-06-pr-b-security-decision.md)

핵심 시나리오 요약.
- 등급 멤버는 단건 200, 비멤버는 **404**(403 아님, 존재 숨김). 등급 NULL = 공개.
- 멤버 5타입 OR 충족(REPORTER=reporter_id / ASSIGNEE=assignee_id / USER / GROUP / PROJECT_ROLE). 관리자 우회 없음.
- 목록은 비멤버 이슈 제외(총개수·content 정합). 등급 지정은 SET_ISSUE_SECURITY 가드 + 적용 스킴 미소속 422.

**설계 갈림길 결정(게이트1 전 Maxi 확정, 2026-06-06)**.
- **갈림길 3 = 옵션 A** — PR-B가 `IssueController` actor 결선 포함(`CurrentActor` 패턴, PR #82 WorkflowSchemeController 선례 복제). 고정 `SYSTEM_ACTOR_UUID`(FR-PM-04 C2 부채)를 실제 인증 주체로 치환 → 보안 수준이 prod에서 실작동. 미인증/비-UUID → 401. **범위 확대**: 컨트롤러 actor 치환 + 401 회귀테스트 포함.
- **갈림길 1 = 옵션 A** — 목록 필터 SQL 술어 푸시다운. identity-access가 actor 기준 접근 가능 등급 집합(+REPORTER/ASSIGNEE 적용 등급 집합)을 계산 → issue-tracking 목록 쿼리 WHERE로 푸시(신규 cross-BC 포트 1개, shared-kernel 정의·identity-access 구현, `IssuePermissionResolver` 동형 방향). 페이지네이션 정합 + N+1 0.
- **갈림길 2 = 옵션 A**(권장 채택) — `IssueSecurityLookup`(또는 인접 포트)에 `levelBelongsToProjectScheme(levelId, projectId): Boolean` 추가, issue-tracking이 등급 지정 전 호출해 422 판정.

## Brainstorming Check

✅ 통과 (office-hours 스킵·정의된 FR. Explore 코드 정초로 갈림길 3건 발굴 → Maxi 확정. brainstorming 재흔들기 불요 — 갈림길이 곧 gap, 전부 해소).

## Plan

> 모듈 3종 관여: shared-kernel(포트/enum 정의) · identity-access(판정/포트구현) · issue-tracking(컬럼/컨트롤러/목록/지정). 같은 Gradle 모듈 test는 컴파일 단위 공유 → 동일 모듈 task는 사실상 직렬(bts-plan-wave 교훈). 거부 경로는 non-prod AlwaysAllow가 마스킹 → **prod Testcontainers 통합만 ground-truth**.
> 아키텍처: cross-BC 포트 2종 분리 — ① `IssueSecurityLookup`(identity-access 내부, issues raw SQL read, ProjectDirectory 동형, resolver 단건용) ② `IssueSecurityDirectory`(shared-kernel 포트, identity-access prod 구현 + issue-tracking non-prod stub, IssuePermissionResolver 동형, 목록필터·422 검증용).

### Task 1. shared-kernel — IssuePermission.SET_SECURITY 추가 + 전 모듈 consumer 동기화

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/IssuePermission.kt`, `backend/modules/shared-kernel/src/test/.../IssuePermissionTest.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessIssuePermissionResolver.kt`, `backend/modules/identity-access/src/test/.../IdentityAccessIssuePermissionResolverTest.kt`]
- depends-on: []

**RED**: shared-kernel `IssuePermissionTest` — `IssuePermission.SET_SECURITY` 존재 + `entries` 카운트 갱신(enum-add 교훈: entries.hasSize(N) 가드 깨짐 → 전 모듈 grep `IssuePermission.entries`/`when (this`/`hasSize`). identity-access resolver test — `SET_SECURITY` → `"SET_ISSUE_SECURITY"` 매트릭스 위임 단언.

**GREEN**: `IssuePermission`에 `SET_SECURITY` 추가. `toCodeOrNull` exhaustive `when`에 `SET_SECURITY -> "SET_ISSUE_SECURITY"` 추가(누락 시 컴파일 RED). 깨진 카운트 가드 갱신.

**REFACTOR**: KDoc 매핑 표에 SET_SECURITY 행 추가.

**검증**: `./gradlew :modules:shared-kernel:test :modules:identity-access:test --tests *IssuePermission* --tests *IssuePermissionResolver*` + 전 모듈 grep로 잔여 exhaustive when/count 0 확인.

### Task 2. shared-kernel — IssueSecurityDirectory 포트 + IssueSecurityAccess + issue-tracking non-prod stub

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/IssueSecurityDirectory.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/IssueSecurityAccess.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/AlwaysAllowIssueSecurityDirectory.kt`, `backend/modules/issue-tracking/src/test/.../AlwaysAllowIssueSecurityDirectoryTest.kt`]
- depends-on: []

**RED**: stub test — non-prod stub의 `levelBelongsToProjectScheme(any,any)==true`, `accessibleLevels(any,any)`==무제한(필터 미적용 신호).

**GREEN**: 포트 인터페이스 `IssueSecurityDirectory`(`levelBelongsToProjectScheme(levelId: UUID, projectKey: String): Boolean` + `accessibleLevels(actorId: UUID, projectKey: String): IssueSecurityAccess`). `IssueSecurityAccess`(접근가능 등급집합 + REPORTER/ASSIGNEE 적용 등급집합 + `unrestricted: Boolean` 빠른경로). issue-tracking `@Profile("!prod")` stub.

**REFACTOR**: KDoc — IssuePermissionResolver 동형 패턴·BC 격리 명시.

**검증**: `./gradlew :modules:shared-kernel:test :modules:issue-tracking:test --tests *AlwaysAllowIssueSecurityDirectory*`.

### Task 3. issue-tracking — V014 마이그레이션 + init_codegen 미러 (jOOQ 재생성)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V014__issue_security_level.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`]
- depends-on: []

**RED**: jOOQ 상수 `ISSUES.SECURITY_LEVEL_ID`를 참조하는 컴파일 테스트(또는 스키마 검증 테스트) — 상수 미생성 시 컴파일 RED.

**GREEN**: V014 `ALTER TABLE issues ADD COLUMN security_level_id UUID NULL`(FK 미적용, BC 격리 — 등급은 identity-access 소유). **init_codegen.sql에도 동일 컬럼 미러**(Flyway PG16 우회, jOOQ codegen source of truth — jooq-init-codegen-mirror 교훈, V006/V012 선례). jOOQ 재생성.

**REFACTOR**: 마이그레이션 주석으로 등급 소유 BC·FK 미적용 사유 명시.

**검증**: `./gradlew :modules:issue-tracking:generateJooq :modules:issue-tracking:compileKotlin` — ISSUES.SECURITY_LEVEL_ID 생성 확인. **머지 직전 V번호 재확인**(현재 V013 최신, 동시 브랜치 V014 소비 여부 fetch 점검).

### Task 4. issue-tracking — Issue 도메인 + repository security_level_id read/write

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/.../IssueTest.kt`, `backend/modules/issue-tracking/src/test/.../IssueRepository*Test.kt`]
- depends-on: [3]

**RED**: 도메인 — `Issue.securityLevelId` 보유 + 지정/해제 함수(예 `assignSecurityLevel(levelId?)`)가 version bump(명시 편집이므로 OCC bump 적절, no-bump 아님 — no-bump-sidecar 교훈은 자동/부수변경 한정). 생성 시 `securityLevelId` 수용(no-bump insert, version=1). repo — security_level_id 영속/조회.

**GREEN**: 도메인 필드 + 함수, repo insert/update에 컬럼 반영(기존 OCC setter 재사용해 version bump, 생성은 no-bump insert).

**REFACTOR**: KDoc — 등급 NULL=공개 의미 명시.

**검증**: `./gradlew :modules:issue-tracking:test --tests *IssueTest* --tests *IssueRepository*`.

### Task 5. issue-tracking — CurrentActor + IssueController actor 결선 (401 회귀)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CurrentActor.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/test/.../IssueController*Test.kt`]
- depends-on: []

**RED**: 컨트롤러 테스트 — 인증 주체가 SecurityContext에 있으면 그 UUID가 actor로 전달, 미인증/익명/비-UUID → **401**(회귀). 기존 `SYSTEM_ACTOR_UUID` 가정 테스트 갱신.

**GREEN**: issue-tracking `CurrentActor`(project-workflow 선례 복제, issue-tracking `ActorId` 반환, `Authentication.name`→UUID, 미인증 401). `IssueController` 전 엔드포인트(create/list/get/update/transition 등 actor 사용처)의 `ActorId(SYSTEM_ACTOR_UUID)`를 `CurrentActor.current()`로 치환. 인증 추출을 findByKey(404)보다 앞에(auth-extraction-before-resource-lookup 교훈 — 미인증자 404 vs 401 probe 차단).

**REFACTOR**: SYSTEM_ACTOR_UUID 상수 잔여 사용 제거 확인.

**검증**: `./gradlew :modules:issue-tracking:test --tests *IssueController*` + 미인증 401 회귀 그린.

### Task 6. issue-tracking — securityLevelId 지정 API + SET_ISSUE_SECURITY 가드 + 422

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/dto/*.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/.../IssueApplicationService*Test.kt`]
- depends-on: [1, 2, 4, 5]

**RED**: 서비스 단위(stub Directory) — securityLevelId 지정 시 `assertPermission(actor, SET_SECURITY, scope)` 호출(미보유 403 — S10), 지정 등급이 `IssueSecurityDirectory.levelBelongsToProjectScheme==false`면 **422**(S11), 해제(null) merge-patch 3-state(부재=무변경 vs 명시 null=클리어 — S12, FR-IS-04 description 선례).

**GREEN**: `CreateIssueRequest`/`UpdateIssueRequest`에 `securityLevelId: UUID?`(+merge-patch 표현). 서비스 — 지정/변경/해제 분기에 SET_SECURITY 가드 + Directory 422 검증 + 도메인 `assignSecurityLevel` 경유(우회 금지, patch-merge-domain-bypass 교훈).

**REFACTOR**: 422 예외 메시지 일반화(내부식별자 누출 금지, guard-exception 교훈).

**검증**: `./gradlew :modules:issue-tracking:test --tests *IssueApplicationService*`.

### Task 7. identity-access — IssueSecurityLookup(issues raw SQL read) + repo 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecurityLookup.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecuritySchemeRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/UserGroupRepository.kt`, `backend/modules/identity-access/src/test/.../IssueSecurityLookup*IntegrationTest.kt`]
- depends-on: [3]

**RED**: Testcontainers 통합 — `IssueSecurityLookup.lookup(issueKey)`가 issues의 (security_level_id, reporter_id, assignee_id) read. repo 확장: `levelBelongsToProjectScheme(levelId, projectId)`(project_issue_security_schemes ⨝ levels), `listLevelIdsByMemberType(schemeId, memberType)`(REPORTER/ASSIGNEE 적용 등급), `UserGroupRepository.isMemberOf(groupId, userId)` 단건.

**GREEN**: `IssueSecurityLookup` 인터페이스 + `JdbcIssueSecurityLookup`(ProjectDirectory 동형, raw SQL `@Transactional(readOnly=true)`, issues 직접 import 금지, 의존 컬럼 KDoc+DDL drift 경고). repo 메서드 추가.

**REFACTOR**: SQL 상수화 + KDoc.

**검증**: `./gradlew :modules:identity-access:test --tests *IssueSecurityLookup*` (Testcontainers).

### Task 8. identity-access — IssueSecurityDecider 5타입 순수 판정함수

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecurityDecider.kt`, `backend/modules/identity-access/src/test/.../IssueSecurityDeciderTest.kt`]
- depends-on: []

**RED**: 순수 함수 단위 — 입력(actor, levelId?, reporterId, assigneeId, members:List<SecurityLevelMember>, actorRole?, isGroupMemberFn) → Boolean. 케이스: 등급 NULL=true, REPORTER(=reporterId), ASSIGNEE(=assigneeId), USER(=member_value), GROUP(소속), PROJECT_ROLE(role==member_value), 비멤버=false, 고아 등급(멤버 0)=false(보수적 차단), 다중 충족 OR.

**GREEN**: 순수 함수 구현(I/O 없음 — 멤버/그룹/역할은 인자 주입).

**REFACTOR**: when 분기 exhaustive(MemberType 5종 명시, else 금지 → 타입 추가 시 컴파일 RED).

**검증**: `./gradlew :modules:identity-access:test --tests *IssueSecurityDecider*`.

### Task 9. identity-access — resolver VIEW 보안 게이트 확장 + IssueSecurityDirectory prod 구현

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessIssuePermissionResolver.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IdentityAccessIssueSecurityDirectory.kt`, `backend/modules/identity-access/src/test/.../IdentityAccessIssuePermissionResolver*IntegrationTest.kt`, `backend/modules/identity-access/src/test/.../IdentityAccessIssueSecurityDirectory*IntegrationTest.kt`]
- depends-on: [1, 7, 8]

**RED**: prod Testcontainers — resolver `hasPermission(actor, VIEW, Issue(key))`가 VIEW_ISSUE 매트릭스 통과 후 보안게이트 적용: 멤버 통과(S1/S4~S6), 비멤버 false(S2), SYSTEM_ADMIN도 비멤버면 false(S7 — 우회 없음). Directory prod — `levelBelongsToProjectScheme`/`accessibleLevels`(REPORTER/ASSIGNEE 적용 등급집합 + 정적 등급집합 + unrestricted 빠른경로).

**GREEN**: resolver VIEW 경로에 게이트 추가(IssueSecurityLookup + IssueSecurityDecider + repos 조합). `IdentityAccessIssueSecurityDirectory`(@Profile("prod")) 구현.

**REFACTOR**: resolver 비대화 방지 — 게이트 로직 private 분리. KDoc 임시정책 메모 갱신.

**검증**: `./gradlew :modules:identity-access:test --tests *IssuePermissionResolver* --tests *IssueSecurityDirectory*` (prod 프로파일 Testcontainers).

### Task 10. issue-tracking — listWithType 술어 푸시다운 + prod Testcontainers 통합 S1~S12

**메타**.
- agent: `backend-engineer` (통합은 `qa-engineer` 보강)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/.../IssueSecurityLevel*IntegrationTest.kt`]
- depends-on: [2, 3, 6, 9]

**RED**: 목록 단위/통합 — `listIssues`가 `IssueSecurityDirectory.accessibleLevels`로 WHERE 푸시다운: `security_level_id IS NULL OR IN(:static) OR (IN(:reporterLv) AND reporter_id=:actor) OR (IN(:assigneeLv) AND assignee_id=:actor)`. 비멤버 이슈가 content·총개수 모두 제외(S8), 페이지네이션 정합. unrestricted면 필터 생략(빠른경로). **prod 통합 캡스톤 S1~S12** end-to-end(실제 actor·프로파일).

**GREEN**: `listWithType` 시그니처에 `IssueSecurityAccess` 수용 → count·content 쿼리 동일 WHERE. 서비스가 Directory 호출 후 전달.

**REFACTOR**: WHERE 빌더 공통화(count/content 중복 제거), N+1 0 단언.

**검증**: `./gradlew :modules:issue-tracking:test --tests *IssueSecurityLevel*` + 목록 쿼리 카운트 단언(N+1 0).

## Plan 메타

- task 수: 10
- 모듈 분포: shared-kernel(T1,T2) · issue-tracking(T2 stub,T3,T4,T5,T6,T10) · identity-access(T1 consumer,T7,T8,T9)
- 의존 그래프: T1·T2·T3·T5·T8 선두(무의존) → T4(←3) → T7(←3) → T6(←1,2,4,5) → T9(←1,7,8) → T10(←2,3,6,9)
- 동일 모듈 직렬 요인: issue-tracking task들은 test 컴파일 단위 공유로 사실상 직렬, identity-access 동일. wave는 bts-impl이 files 겹침+depends-on로 계산
- 예상 wave 수: 약 4~5 (모듈 경계가 병렬 상한)
- TDD 강제: yes (test→feat 커밋 순서 bts-impl 검증)
- 거부 경로 ground-truth: prod Testcontainers 통합만(non-prod AlwaysAllow 마스킹)
- 추가 검증: ktlintMainSourceSetCheck + ktlintTestSourceSetCheck + detekt + ArchUnit BC 격리 + verify-master-plan(FR 카운트 121 불변)

## 리뷰 결과 (← /bts-review-plan 채움)

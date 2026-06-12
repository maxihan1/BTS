# FR-MF-04 — MFA 강제 정책 (관리자 + 민감 프로젝트)

> slug: fr-mf-04-mfa-require-2fa
> type: auth
> agent: security-engineer
> 생성: 2026-06-12

## Brief

FR-MF-04 — MFA(Multi-Factor Authentication) 강제 정책. SDD §19.7.2 기준.
- 모든 관리자: 강제
- 민감 프로젝트 멤버 (`project.require_2fa = true`): 강제
- 일반 사용자: 권장
- 외부 협력사: 강제 검토

BC: identity-access. 선행 FR-MF-01(TOTP)/FR-MF-02(백업 코드) 완료 위에 enforcement 레이어 추가.

## 도메인 정리

### 결정 사항 (Maxi 확인 — 2026-06-12)

| # | 갈림길 | 선택 | 이유 |
|---|---|---|---|
| D1 | 강제 대상 범위 | **관리자(SYSTEM_ADMIN) + 민감 프로젝트 멤버** | SDD §19.7.2 '필수' 정의. 외부 협력사('강제 검토'=미확정)는 후속 |
| D2 | `require_2fa` 위치 + cross-BC | **issue-tracking `projects` 테이블 + shared-kernel `SensitiveProjectResolver` 포트** | 프로젝트 설정의 자연스러운 자리. 기존 `SystemPermissionResolver` 선례와 동일 |
| D3 | enforcement 차단 방식 | **whoami `mfaEnrollmentRequired` 플래그 + 백엔드 게이트** | FR-AU-05 `mustChangePassword` 선례. 단, FR-AU-05가 미룬 백엔드 전역 차단도 이번엔 포함(보안 정합) |
| D4 | PR 범위 | **백엔드 먼저** | FR-MF-01/02 선례. 프론트 게이팅 UI + E2E는 D6/D7 후속 PR |

### BC 경계 (이 PR이 3개 모듈을 걸침 — Maxi 확인 필요)

CLAUDE.md '한 PR = 한 BC' 원칙의 예외. cross-BC resolver 패턴(FR-PM-06/07 선례)으로 처리.

- **issue-tracking** — `projects.require_2fa` 컬럼 추가(마이그레이션 + jOOQ `init_codegen.sql` 미러 필수) + 프로젝트 관리자용 토글 엔드포인트 + `SensitiveProjectResolver` 구현(adapter).
- **shared-kernel** — `SensitiveProjectResolver` 포트(`com.bts.shared.permission`, UUID 시그니처, identity 도메인 타입 미참조 — 역의존 차단).
- **identity-access** — `MfaEnforcementPolicy` 평가 + whoami `mfaEnrollmentRequired` 노출 + 백엔드 게이트(미등록 강제 대상 차단). **새 마이그레이션 불필요**(상태는 `mfaService.isEnabled` + 정책 계산으로 도출, 영속 상태 0).

### 영향 엔티티 / 포트

- `Project` (issue-tracking) — `require_2fa: Boolean` 신규 필드.
- `ProjectMembership` (identity-access, V007) — 기존. 멤버십 조회로 '민감 프로젝트 소속' 판정.
- `SystemRole`/`SystemPermissionResolver` (identity-access) — 기존. 관리자 판정 재사용.
- `MfaService.isEnabled(userId)` (identity-access, FR-MF-01) — 기존. '등록 여부' 판정.
- `SensitiveProjectResolver` (shared-kernel, **신규 포트**) — issue-tracking이 impl 제공.
- `WhoamiResponse` (identity-access) — `mfaEnrollmentRequired: Boolean` 필드 추가.

### 정책 평가 규칙

```
mfaRequired(user)        = isSystemAdmin(user) OR memberOfSensitiveProject(user)
mfaEnrolled(user)        = mfaService.isEnabled(user)           // FR-MF-01 TOTP 활성
mfaEnrollmentRequired    = mfaRequired(user) AND NOT mfaEnrolled(user)
```

게이트: `mfaEnrollmentRequired=true`인 요청은 enrollment 관련 API + whoami + logout 외 전부 차단.

### 새 용어 (glossary 갱신 대기 — Maxi 승인 필요)

- **MFA 강제 정책 (MFA enforcement policy)** — 특정 사용자군(관리자·민감 프로젝트 멤버)에게 2FA 설정을 의무화하는 규칙.
- **민감 프로젝트 (sensitive project)** — `require_2fa=true`로 표시된 프로젝트. 멤버는 MFA 강제 대상.
- **MFA enrollment 강제 (mfaEnrollmentRequired)** — 강제 대상이지만 아직 MFA 미설정 → 등록 완료 전까지 다른 기능 접근 차단되는 상태.

### 기존 결정 충돌

- 없음. FR-MF-01/02(opt-in MFA) 위에 강제 레이어를 더하는 확장. 기존 로그인 흐름 회귀 0 목표.
- BC 격리 원칙은 resolver 패턴으로 준수(직접 import 0, shared-kernel 포트 경유).

### 관련 ADR

- [docs/decisions/2026-06-12-mfa-enforcement-policy.md](../decisions/2026-06-12-mfa-enforcement-policy.md) (이 PR에서 생성)

## 스펙

전체 스펙. [docs/specs/2026-06-12-fr-mf-04-mfa-require-2fa.md](../specs/2026-06-12-fr-mf-04-mfa-require-2fa.md)

핵심 시나리오 3줄 요약.
- 관리자/민감프로젝트 멤버가 MFA 미설정으로 로그인하면 access JWT 클레임 `mfa_enrollment_required=true` → 게이트가 MFA등록/whoami/logout/refresh 외 전부 403 차단.
- MFA 활성화 후 `/auth/refresh`로 토큰 갱신하면 클레임 false → 게이트 해제 (등록→refresh 흐름).
- require_2fa는 SYSTEM_ADMIN만 토글, issue-tracking projects 컬럼, SensitiveProjectResolver 포트로 cross-BC 평가.

추가 결정 (Maxi 2026-06-12).
- 게이트 평가 = JWT 클레임 + 짧은 TTL (매 요청 DB 0, require_2fa 변경은 다음 refresh까지 지연).
- 토글 권한 = SYSTEM_ADMIN 전용.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 2건 발견 후 Maxi 결정 반영 — 게이트 평가 방식 + 토글 권한).

## Plan

> 모듈 컴파일 의존: shared-kernel(포트) → issue-tracking(impl·column·toggle) → identity-access(policy·claim·gate·whoami). 모듈 경계 넘는 task는 test 컴파일도 직렬화(learnings: wave Gradle 모듈).

### Task 1. issue-tracking `projects.require_2fa` 마이그레이션 (V020) + jOOQ init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V020__project_require_2fa.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/repository/ProjectRequire2faColumnTest.kt`]
- depends-on: []

**RED**: 통합 테스트가 시드 프로젝트의 `require_2fa` 기본값 false 를 읽음 → 컬럼 부재로 실패.
**GREEN**: `ALTER TABLE projects ADD COLUMN require_2fa BOOLEAN NOT NULL DEFAULT false;` + COMMENT. **init_codegen.sql 의 projects 정의에도 동일 컬럼 미러**(미러 누락 시 jOOQ 코드 생성 drift — learnings jooq-init-codegen-mirror).
**REFACTOR**: 컬럼 COMMENT 한글 1줄.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*ProjectRequire2faColumn*'` + 코드 생성(`generateJooq`) 통과.

### Task 2. `SensitiveProjectResolver` 포트(shared-kernel) + issue-tracking adapter impl

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/SensitiveProjectResolver.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/adapter/IssueTrackingSensitiveProjectResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/adapter/IssueTrackingSensitiveProjectResolverTest.kt`]
- depends-on: [1]

**RED**: resolver 통합 테스트 — projects 시드(require_2fa=true / false / soft-deleted) 후 `anyRequiresMfa(setOf(id))`: true 프로젝트→true, false/미존재→false, soft-deleted(`deleted_at NOT NULL`)→제외(false). 포트 미존재로 컴파일 실패.
**GREEN**: shared-kernel 포트 `interface SensitiveProjectResolver { fun anyRequiresMfa(projectIds: Set<UUID>): Boolean }`(UUID 시그니처, identity 도메인 타입 미참조 — 역의존 차단, SystemPermissionResolver 선례). issue-tracking adapter — jOOQ `SELECT EXISTS(... WHERE id = ANY(:ids) AND require_2fa AND deleted_at IS NULL)`. 빈 집합은 즉시 false(쿼리 생략).
**REFACTOR**: KDoc(소비처 identity-access 명시) + 빈 집합 가드.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*SensitiveProjectResolver*'`.

### Task 3. issue-tracking SYSTEM_ADMIN require_2fa 토글 엔드포인트

> **리뷰 B3 반영.** issue-tracking 은 `SystemPermissionResolver` 소비처 0 + 실 actor 추출/SecurityContext 인프라가 컨트롤러 슬라이스 테스트에 없음(`ProjectLeadController` 는 `SYSTEM_ACTOR_UUID` placeholder). 따라서 (1) prod-assembled 는 identity-access 의 `IdentityAccessSystemPermissionResolver` 빈을 받지만, (2) issue-tracking **단독 테스트 컨텍스트**엔 그 빈이 없으므로 notification 선례(`TestPermissionConfig` fake `SystemPermissionResolver`) + `@SpringBootTest` 풀부팅 테스트가 필요. non-prod 단독 부팅 가용성을 위해 `@Profile("!prod")` fallback 빈도 추가(기존 `AlwaysAllow*Resolver` 선례).

**메타**.
- agent: `backend-engineer`  (SYSTEM_ADMIN 게이트는 security-engineer 검토)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/ProjectRequire2faController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/dto/ChangeRequire2faRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/application/ProjectRequire2faApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/repository/ProjectRequire2faRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/adapter/NonProdAllowSystemAdminResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/web/ProjectRequire2faControllerIntegrationTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/web/Require2faTestPermissionConfig.kt`]
- depends-on: [1]

**RED**: `@SpringBootTest`(풀부팅, 슬라이스 아님 — SecurityContext 필요) HTTP 통합 — `PATCH /api/v1/projects/{key}/require-2fa {requireTwoFactor:true}`: SYSTEM_ADMIN(fake resolver true) → 200 + 영속 확인, 비관리자(fake false) → 403, 미인증 → 401. fake `SystemPermissionResolver` 는 `Require2faTestPermissionConfig` 로 주입(admin/비admin 토글 가능 — notification `TestPermissionConfig` 선례).
**GREEN**: `CurrentActor.current()` 로 actorId 추출 → `SystemPermissionResolver.isSystemAdmin(actor.value)` false 면 403(`@Transactional` ApplicationService) → repository UPDATE require_2fa. 에러 메시지 내부 상세 누출 금지(learnings guard-exception-message-http-leak). non-prod 단독 부팅용 `@Profile("!prod")` `NonProdAllowSystemAdminResolver`(기존 AlwaysAllow 선례) — prod 는 assembled identity-access 실 빈 사용.
**REFACTOR**: ExceptionHandler 정합(403 코드 `require_2fa_forbidden` 등) + KDoc.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*ProjectRequire2fa*'`.

### Task 4. `MfaEnforcementPolicy` 평가 서비스 + 멤버십 조회 + non-prod fallback resolver

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaEnforcementPolicy.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/NonProdSensitiveProjectResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaEnforcementPolicyTest.kt`]
- depends-on: [2]

**RED**: `MfaEnforcementPolicy` 단위 테스트(fake: SystemPermissionResolver / SensitiveProjectResolver / MfaService / 멤버십). 분기 — (a)관리자+미설정→required, (b)민감멤버+미설정→required, (c)일반+미설정→false, (d)관리자+이미설정→false(EC9), (e)멤버십0→false(EC3), (f)다중프로젝트 하나만 민감→required(EC4).
**GREEN**: `mfaRequired = isSystemAdmin(uid) OR sensitiveResolver.anyRequiresMfa(membershipRepo.listProjectIdsByUser(uid))`; `mfaEnrollmentRequired = mfaRequired && !mfaService.isEnabled(uid)`. `ProjectMembershipRepository.listProjectIdsByUser(userId): List<UUID>` 신규(in-BC 조회). identity-access 단독 부팅용 `NonProdSensitiveProjectResolver`(`@Profile("!prod")`, 안전 기본 false). **fail-open 금지 — 단일 명확 가드(CEO-3)**: prod 는 issue-tracking 실 impl 을 assembled 로 받고, prod 부팅 시 실 빈 부재면 Spring DI 가 loud 하게 실패(silent 우회 금지). 별도 3중 방어 대신 "non-prod fallback(false) + prod 실빈 필수"로 단순화(learnings crossbc-resolver-nullable-fail-open / profile-scoped-bean-boot-failure).
**REFACTOR**: `@Service` 부착 확인(ArchUnit) + KDoc.
**검증**: `./gradlew :backend:modules:identity-access:test --tests '*MfaEnforcementPolicy*'`.

### Task 5. access JWT 클레임 `mfa_enrollment_required` — JwtIssuer 내부 계산 (전 발급 경로 단일 chokepoint)

> **리뷰 B1/B2 반영.** `jwtIssuer.issue` 호출은 4곳(AuthController.issueTokens, RefreshTokenService.rotate, OidcAuthenticationSuccessHandler, Saml2AuthenticationSuccessHandler). 호출처마다 클레임을 넣으면 누락 위험(SSO 관리자 우회=B1). **`JwtIssuer.issue` 가 첫 파라미터 `userId: UUID` 를 이미 받으므로**, JwtIssuer 내부에서 `MfaEnforcementPolicy.evaluate(userId)` 를 호출해 클레임을 박는다 → 4 경로 전부 시그니처 변경 0으로 자동 포괄. 호출처/그 테스트 무변경(B2 축소 — JwtIssuer 자체 테스트 2개만 보정).

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/JwtIssuer.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtIssuerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtIssuerSignatureRegressionTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MfaEnforcementClaimIntegrationTest.kt`]
- depends-on: [4]

**RED**: (a) JwtIssuer 단위 — fake `MfaEnforcementPolicy` 가 true 반환하도록 두면 발급 토큰에 클레임 `mfa_enrollment_required=true`, false면 false 명시. (b) 로컬 로그인 통합 — 강제대상 미설정 login → 토큰 클레임 true, 일반 → false. (c) **refresh 통합** — rotate 후 토큰도 클레임 반영. (d) **SSO 통합(B1)** — OIDC/SAML 성공 핸들러 발급 토큰도 클레임 포함(SSO 관리자 미설정 → true). 기존 JwtIssuer 직접 생성 테스트 2개(`JwtIssuerTest`, `JwtIssuerSignatureRegressionTest`)는 생성자 인자 추가로 컴파일 실패(보정 대상).
**GREEN**: `JwtIssuer` 생성자에 `MfaEnforcementPolicy` 주입. `issue()` 내부에서 `.claim(CLAIM_MFA_ENROLLMENT_REQUIRED, mfaEnforcementPolicy.evaluate(userId))`(false 여도 명시 — 부재=false 해석 일관). **issue() 시그니처·호출처 무변경** → AuthController/RefreshTokenService/Oidc·Saml 핸들러 및 그 테스트 모두 무변경(B1 전 경로 자동 포괄, B2 회귀 차단). 정책 평가가 발급 경로를 throw 로 막지 않도록 주의(resolver prod 실 impl, non-prod fallback false — login 가용성).
**REFACTOR**: 상수 `CLAIM_MFA_ENROLLMENT_REQUIRED` companion + KDoc claims 표 갱신(발급 chokepoint 설계 명시).
**검증**: `./gradlew :backend:modules:identity-access:test --tests '*JwtIssuer*' --tests '*MfaEnforcementClaim*'`.

### Task 6. whoami `mfaEnrollmentRequired` 노출 (클레임 출처)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/WhoamiResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/WhoamiControllerTest.kt`]
- depends-on: [5]

**RED**: whoami 통합 — 클레임 true 토큰 → `mfaEnrollmentRequired=true`, 클레임 false/부재 → false, PAT → false 고정.
**GREEN**: `WhoamiResponse` 에 `mfaEnrollmentRequired: Boolean` 추가(JSON additive — 프론트 D6 에서 소비). WhoamiController JWT 분기가 `jwt.getClaim(CLAIM_MFA_ENROLLMENT_REQUIRED) ?: false` 읽어 채움(라이브 재계산 아님 — 게이트와 단일 출처 일치, EC7). PAT 분기 false.
**REFACTOR**: KDoc(`@property mfaEnrollmentRequired`) + PAT 정책 주석.
**검증**: `./gradlew :backend:modules:identity-access:test --tests '*WhoamiController*'`.

### Task 7. 백엔드 게이트 필터 (`MfaEnrollmentGateFilter` + SecurityConfig 등록)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/MfaEnrollmentGateFilter.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MfaEnrollmentGateIntegrationTest.kt`]
- depends-on: [5]

**RED**(리뷰 C3/C4 반영): 게이트 통합(양방향) — 클레임 true 토큰: (a) **identity-access 컨텍스트 내 가용한 보호 경로**(예 `GET /api/v1/auth/sessions` — authenticated, allow-list 외) → 403 `mfa_enrollment_required`. (`/api/v1/issues`는 identity-access 단독 컨텍스트에 부재하므로 RED 대상으로 쓰지 않음 — C4.) (b) allow-list 통과를 **명시적 경로별로** 검증(C3 영구 락 방지): `POST /api/v1/auth/mfa/totp/setup`·`/totp/enable`, `GET /api/v1/auth/mfa/totp`, `DELETE /api/v1/auth/mfa/totp`, `POST/GET /api/v1/auth/mfa/backup-codes`, `GET /api/v1/users/me/whoami`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/refresh` → 전부 통과. 클레임 false 토큰 → 모든 API 통과(회귀 0). PAT(클레임 부재) → 통과.
**GREEN**: `OncePerRequestFilter` — 인증 후(JWT 필터 뒤) 실행, principal 의 클레임 `mfa_enrollment_required=true` && 요청 경로 ∉ allow-list → 403 + 바디 `{error:"mfa_enrollment_required"}`. SecurityConfig `addFilterAfter` 로 등록(미인증은 기존 401 유지). allow-list 상수화.
**REFACTOR**: allow-list AntPathMatcher 상수 + KDoc(영구 락 방지 — allow-list 누락 위험 명시).
**검증**: `./gradlew :backend:modules:identity-access:test --tests '*MfaEnrollmentGate*'`.

### Task 8. 종합 enforcement 통합 테스트 (S1~S7 end-to-end)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MfaEnforcementEndToEndTest.kt`]
- depends-on: [3, 6, 7]

**RED→GREEN**: end-to-end — (S1/S2) 관리자 미설정 login → whoami required → 보호 API 403 → MFA enable → refresh → 보호 API 200 + whoami false. (S3) 민감 프로젝트 멤버(test fake resolver true) 미설정 → required. (S4) 일반 사용자 → 영향 0(회귀). (S5) 이미 설정 관리자 → 무영향. **(B1) SSO(OIDC/SAML) 로그인 관리자 미설정 → 토큰 클레임 true → 게이트 403**(발급 chokepoint 검증). test @TestConfiguration 으로 SensitiveProjectResolver fake 주입(identity-access 단독 컨텍스트).
**REFACTOR**: 시나리오 헬퍼 추출.
**검증**(리뷰 CEO-1 — 게이트 필터는 공유 SecurityFilterChain 변경, blast radius 큼): 모듈 표적 + **전체 백엔드 회귀** `./gradlew test`(기존 MFA/인증 통합테스트 그린 확인). 기존 E2E 회귀는 bts-codereview/merge 게이트에서 확인.

### Task 9. FR-MF-04 백엔드 D단계 마킹 + 전수 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/plan/product/identity-access.md`, `docs/plan/README.md`, `docs/sdd/19-authentication.md`, `CLAUDE.md`]
- depends-on: [8]

**작업**(TDD 비대상 — 문서 동기화): FR-MF-04 백엔드 D1~D5 체크박스 마킹(D6/D7 프론트/E2E 후속 미체크), §A.2 BC 카운트·합계·`(FR-MF, N개)` 헤더·`소속 FR` 정합, SDD §19.7.2 강제 정책 구현 반영, ADR 링크. **`bash scripts/verify-master-plan.sh` 통과 필수**(카운트 drift 차단, exit 4). dashboard 재생성은 bts-merge 가 수행.
**검증**: `bash scripts/verify-master-plan.sh` exit 0.

## Plan 메타

- task 수: 9
- 모듈: issue-tracking(T1·T2·T3) + shared-kernel(T2 포트) + identity-access(T4~T8) + docs(T9)
- 예상 wave: 의존 그래프상 임계 경로 T1→T2→T4→T5→{T6,T7}→T8→T9 (약 7 wave). T3 는 T2 와 병렬(둘 다 T1 후), T6·T7 병렬.
- TDD 강제: yes (T9 docs 제외)
- 추가 검증: ktlint/detekt(모듈 baseline), 모듈 전체 회귀, init_codegen 미러 정합, verify-master-plan
- 회귀 표면: whoami JSON additive(프론트 D6 소비) / 게이트 allow-list 정밀 / fail-open 금지 / **JWT 발급 chokepoint(T5)=전 경로(SSO 포함) 자동 포괄, 호출처 무변경**
- 리뷰 반영: B1(SSO우회)→JwtIssuer 내부계산 / B2(생성자주입)→시그니처 무변경 / B3(토글 인프라)→fake config+풀부팅+fallback / C3·C4(게이트 테스트) / CEO-1(회귀 sweep 확대)

## 리뷰 결과

### 독립 eng/보안 리뷰 (security-engineer agent, 2026-06-12) — 코드 대조

**BLOCKER 3건 (모두 plan 반영 완료, auth라 무시 불가)**.
- **B1 (보안 치명)** — JWT 발급 4경로(local·refresh·OIDC·SAML) 중 plan이 2곳만 다뤄 SSO 관리자 토큰에 클레임 부재 → 게이트 우회. **해결**: JwtIssuer 내부에서 `MfaEnforcementPolicy.evaluate(userId)` 계산(발급 chokepoint) → 4경로 자동 포괄(T5 재작성). T8에 SSO 관리자 게이트 케이스 추가.
- **B2** — T5 생성자 주입이 `RefreshTokenServiceTest`(직접 생성) 컴파일 깸. **해결**: JwtIssuer 내부 계산으로 issue() 시그니처 무변경 → 호출처 테스트 무변경. JwtIssuer 자체 테스트 2개(`JwtIssuerTest`/`JwtIssuerSignatureRegressionTest`)만 보정, T5 files 포함.
- **B3** — issue-tracking 토글(T3)의 SYSTEM_ADMIN 게이트 인프라(actor 추출·resolver 소비·fake config) 부재. **해결(Maxi: 이번 PR 완성)**: notification `TestPermissionConfig` 선례 fake + `@SpringBootTest` 풀부팅 + non-prod fallback 빈 추가(T3 files 확장).

**CONCERN**.
- C1 — "SystemPermissionResolver 선례와 동일" 표현 부정확(impl은 identity-access). 방향 자체(impl=데이터 소유 BC)는 타당. prod assembled 조립 가정은 기존 전 resolver 공통(아래 한계 참조).
- C2 — staleness(권한 추가 ≤15분 지연)는 fail-safe 방향(강제가 늦게 켜짐, 꺼지지 않음) → 수용. EC10 revoke 경로 실재.
- C3 — allow-list 경로별 명시 테스트로 영구 락 방지(T7 반영).
- C4 — T7 RED 대상은 identity-access 컨텍스트 내 보호 경로(`/api/v1/auth/sessions`)로 확정(`/api/v1/issues`는 단독 컨텍스트 부재)(T7 반영).

### CEO/제품 리뷰 (Plan agent, 2026-06-12)

- 종합: **진행 가능** — 범위 절제됨, 위험한 단순화는 이미 옳게 됨. 분할 불요.
- 자동결정 4: 단일 PR 유지(선형 의존, 게이트 전엔 dead code) / T8 e2e 풀 시나리오 유지(영구 락 안전망) / fallback+가드 유지 / T3 in-PR.
- taste 3 → 처리: ① 회귀 sweep 확대(T8 전체 `./gradlew test` 반영) ② T3 in-PR(**Maxi 확정**) ③ fail-open 가드 단일화(T4 반영).

### 알려진 한계 (Maxi 인지 — 기존 패턴 답습)

- **prod cross-BC 조립**: identity-access 가 prod 에서 issue-tracking 의 `SensitiveProjectResolver` 실 빈을 받으려면 단일 assembled 부팅이 전제. 현재 `no-cross-bc-deployment-assembly`(test-assembled 표준)로, 이는 기존 모든 cross-BC resolver(System/Field/Component 등)와 동일 조건 — 본 FR가 새로 만든 문제 아님. 동일 패턴(non-prod fallback + test-assembled)으로 진행.

### BLOCKER 상태: 없음 (3건 전부 plan 반영 완료)

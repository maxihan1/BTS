# 조립 앱 비-prod dev 시드 — 로그인 가능한 최소 데이터

> slug: assembly-nonprod-dev-seed
> type: auth
> agent: security-engineer
> 생성: 2026-07-29

## Brief

### 사용자 원문 (Maxi)

> 조립 앱(`:modules:app`)을 비-prod(기본) 프로파일로 띄웠을 때 **로그인이 가능하도록** dev 시드 데이터를 넣는다.
> 현재 계정·프로젝트 0건이라 부팅은 되지만 손검증이 불가능하다.
> D5 결정(`SystemPermissionResolver` 가 비-prod 에서도 실제 판정)에 따라 권한 판정이 실제로 돌기 때문에 시드가 선행 조건이다.
> 주입 방식(Flyway `R__` repeatable / app 전용 `ApplicationRunner` / 수동 SQL)과
> 시드 깊이(관리자 계정만 vs 프로젝트·워크플로 스킴·권한 스킴까지 5계층)는 **스펙 단계에서 옵션으로 제시**할 것.
> **prod 프로파일에는 절대 시드가 들어가면 안 된다.**

### classify 결과 + 수동 정정

| 항목 | 값 | 비고 |
|---|---|---|
| type | `auth` | 알려진 비밀번호를 가진 계정을 만드는 작업이라 보안 등급이 지배적. 그대로 채택 |
| agent | `security-engineer` | 채택. 단 시드 대상이 3개 BC 에 걸쳐 backend-engineer 공동 태스크가 나올 수 있음 (plan 태스크 메타에서 지정) |
| primary_bc | `identity-access` | **주의 — 데이터의 소속 BC 일 뿐, 코드가 놓일 자리가 아니다.** 아래 「선행 제약」 참조 |
| slug | ~~`prod-dev-prod`~~ → `assembly-nonprod-dev-seed` | classify 산출값이 `prod`/`dev` 토큰만 남은 무의미 문자열이라 수동 교정. #321 선례 `assembly-nonprod-bean-wiring` 과 정렬 |
| task_count | 0 | classify 가 태스크 수를 못 냄. `/bts-plan` 에서 실제 분해 |

### 선행 제약 (착수 전 확정 사항)

1. **코드 위치는 조립 모듈 우선 검토.** 시드 대상 데이터가 identity-access(계정) · issue-tracking(프로젝트) ·
   project-workflow(스킴) **3개 BC** 에 걸친다. BC 격리 규칙(한 PR = 한 BC, 다른 BC 는 이벤트 발행만)과 정면으로 만난다.
   #321 이 확립한 A안 — *"조립 계층의 문제는 조립 모듈에서 봉합한다"* — 이 여기에도 적용되는지가 **도메인 단계의 첫 질문**이다.
2. **prod 격리는 타입/구조로 닫는다.** `@Profile("!prod")` 문자열 하나에 의존하는 형태는 오타·프로파일 추가 시 조용히 뚫린다.
   #321 D2 교훈(`FilterType.REGEX` 금지, 개명 시 컴파일 에러화)과 같은 강도의 판별식이 필요하다.
3. **스텁 금지.** #321 D7 — 비-prod 전용이라도 **prod 실구현 경로**로 만든다.
   시드가 도메인 불변식을 우회해 SQL 로 직접 꽂히면, 실제 가입/생성 경로가 요구하는 상태를 못 갖춰
   "로그인은 되는데 그다음이 깨지는" 상태가 된다.
4. **D5 파급.** 비-prod 도 실제 권한 판정이므로 계정만 넣으면 로그인 후 화면이 전부 403 이 될 수 있다.
   「로그인 가능」의 성공 기준을 **어느 화면까지 도달**로 정의할지가 시드 깊이 결정과 같은 질문이다.

### 성공 기준 (초안 — 스펙에서 확정)

- 비-prod 프로파일로 `:modules:app` 부팅 후 **브라우저에서 실제 로그인 성공**
- prod 프로파일 부팅 시 시드 데이터 **0건** (음성 대조군으로 실증, 뮤테이션으로 판별력 확인)
- 다른 9개 BC 의 프로덕션 코드 변경 최소화 (#321 은 0줄 달성)

## 도메인 정리

> 전부 **코드/마이그레이션 실측**이다. 착수 전 전제를 그대로 믿지 않고 다시 쟀다 (부채 기록 재실측 원칙).

### 결론 — 「5계층 시드」 전제가 낡았다

메모리 `no-project-creation-feature-issue-needs-5-layer-seed` 는 *"프로젝트를 쓰려면 5계층을 전부 시드해야 한다"* 고
기록돼 있으나, **5계층 중 3계층은 이미 versioned 마이그레이션이 시드하고 있고 1계층은 실 UI 경로가 생겼다.**
실제로 비어 있는 건 **2계층**뿐이다.

| # | 계층 | 테이블 | 현재 상태 | 근거 (실측) |
|---|---|---|---|---|
| 1 | **사용자 계정** | `users` | **비어 있음** | `V001__users.sql` — 시드 INSERT 0건 |
| 2 | **로그인 비밀번호** | `local_credentials` | **비어 있음** | `V003__local_credentials.sql` — Argon2id 해시 저장, 시드 0건 |
| 3 | 인증 공급자 | `authn_providers` | **불필요** | `AuthnProviderConfigRepository.isEnabled():40-44` = *"disabled 행이 없으면 enabled"* → LOCAL 은 **행 부재 = 활성**. 시드 불필요 |
| 4 | 권한 스킴 | `permission_schemes` · `role_permissions` | **시드 완료** | `V008:51,62` 기본 스킴 INSERT + *"미매핑 프로젝트는 `is_default=TRUE` 스킴을 fallback"* (`V008:37`) |
| 5 | 워크플로 스킴 | `workflow_schemes` · 매핑 | **시드 완료** | `V201:118,137` 표준 4개 스킴 + 이슈타입 매핑 INSERT |
| 6 | 프로젝트 | `projects` · `project_memberships` | 비어 있음 — **단 실 UI 경로 존재** | `ProjectCreateApplicationService:56-66` (insert → `addCreatorAsAdmin` 단일 트랜잭션), 화면은 `/projects/new` (#300) |

### 로그인 최소 요건 — 실측된 판별식

`AuthController:147` → `CompositeAuthenticationManager.authenticate(provider, username, password)`.

1. `provider` 문자열 필수 (`"local"`). blank/누락 = **400 `provider_required`** — 자동 fallback 없음 (계정 열거 방지)
2. `parseUsernamePasswordType()` 가 `LOCAL`/`LDAP` 로만 좁힘
3. `isEnabled(LOCAL)` — **행 부재면 true** (위 표 #3)
4. `LocalAuthenticationProvider` 가 `local_credentials.password_hash` (Argon2id) 대조

⇒ **로그인 성립 최소 집합 = `users` 1행 + `local_credentials` 1행.** 그 외 아무것도 필요 없다.

### 로그인 이후 — D5 파급

#321 D5 로 비-prod 도 `SystemPermissionResolver` 가 **실제 DB 판정**을 한다. 따라서 계정만 넣으면
로그인은 되지만 화면이 권한 부족으로 막힐 수 있다. 해소 경로는 `system_role_assignments` 1행(`SYSTEM_ADMIN`, `V012`).
`whoami.canCreateProject` 가 `hasGlobalPermission(CREATE_PROJECT, grant OR isSystemAdmin)` 이므로 (#300 BE-2),
**SYSTEM_ADMIN 한 줄이 프로젝트 생성 화면까지 열어준다** — 즉 계층 6 을 시드가 아니라 **실 제품 경로**로 채울 수 있다.

### BC 격리 판정

- 최소안(계정 1 + 비밀번호 1 + 시스템역할 1)은 **전부 `identity-access` 테이블 단일 BC** — BC 격리 규칙과 충돌하지 않는다.
- 프로젝트까지 시드하면 `issue-tracking`(projects) + `identity-access`(memberships) **2 BC 동시 기록**이 되어
  #321 A안(조립 계층 봉합)을 꺼내야 한다. 시드 깊이 결정이 곧 BC 격리 결정이다.

### 새 용어 / 기존 결정 충돌

- **새 용어 0건.** 「dev 시드」는 기존 마이그레이션이 이미 하고 있는 행위(V008·V201·V003 이슈타입 등)의 연장이며
  glossary 신규 등재 대상이 아니다.
- **기존 결정 충돌 0건.** ADR `2026-06-04-system-admin-role` L65 *"모든 프로파일에서 실제 판정"* 및
  #321 ADR(조립 A안·D7 스텁 금지)과 **정합**한다.
- **관련 ADR** — 본 작업의 결정(주입 방식·시드 깊이·prod 격리 판별식)은 신규 ADR 로 발행 예정.


## 스펙

전체 스펙. [docs/specs/2026-07-29-assembly-nonprod-dev-seed.md](../specs/2026-07-29-assembly-nonprod-dev-seed.md)

핵심 시나리오 3줄 요약.
- 조립 앱을 비-prod 로 띄우면 `alice` 계정 1개 + 비밀번호 + 시스템관리자 역할 3행이 자동으로 생긴다
- 브라우저에서 `alice` / `password` 로 로그인해 `/projects/new` 까지 도달, 프로젝트를 **실 제품 화면으로** 만든다
- prod 프로파일에서는 시드 빈이 컨텍스트에 0개이고 `users` 는 0행을 유지한다 (양방향 봉인으로 실증)

Maxi 확정 결정 4건 (D4·D5·D6·D8) + 스펙 단계 파생 결정 2건 (D7 정정 · D9 신설).
가장 중요한 설계는 **§6 prod 격리 3층** — `@Profile` 문자열 하나에 의존하지 않고 런타임 단언 + 양방향 봉인으로 닫는다.

### ⚠️ 스펙 단계에서 뒤집힌 전제 2건

1. **「시드가 없다」가 아니라 「있는데 조립에서 꺼져 있다」** — `data-dev.sql` 2개(alice 계정 · ATLAS 프로젝트)가
   이미 존재하며, 두 모듈이 같은 리소스 이름을 써 조립 클래스패스에서 충돌하기 때문에 비활성화돼 있었다
   (`app/application.yml:47` 주석에 사유 명시). Maxi 재결정(D6) — 기존 파일은 손대지 않고 조립 러너를 별도로 둔다.
2. **「5계층 시드 필요」가 낡았다** — 권한 스킴(V008)·워크플로 스킴(V201)은 이미 시드돼 있고,
   `authn_providers` 는 행 부재가 곧 활성이다. 실제로 비어 있는 건 2계층뿐.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 5건 발견 후 전량 반영)

| ID | gap | 처리 |
|---|---|---|
| G1 | 결정적 UUID 는 실 생산 경로로 달성 불가 (`UserRepository.create` 가 `UUID.randomUUID()`) | D7 정정 — UUID 결정성 철회, `username` 으로만 신원 고정 |
| G2 | `CreateLocalAccountService` 는 무작위 임시 비밀번호 + `mustChange=true` 라 **목적을 깬다** | `UserRepository.create` + `LocalCredentialService.store(mustChange=false)` 조합으로 대체 |
| G3 | `store` 가 평문 `CharArray` 를 wipe → 시드가 평문 수명 책임을 진다 | FR-8 신설 |
| G4 | 실행 순서는 이미 구조적으로 참 (`FlywayAssemblyConfig` 가 `InitializingBean`) | 가정 확인. 회귀 방지용 순서 뮤테이션은 유지 |
| G5 | 시드 실패 시 부팅을 죽일지 미정 | D9 신설 — prod 격리 위반만 기동 실패, 시드 실패는 ERROR 로그 + 부팅 계속 |

## Plan

> 전 task 가 `:modules:app` 단일 모듈. 다른 9개 BC 의 `src/**` 는 **0줄**. 마이그레이션 0건. 신규 의존성 0건.
> 패키지는 기존 관례를 따라 `com.bts.app` 평면 배치 (`NonProdAssemblyPortConfig.kt`·`FlywayAssemblyConfig.kt` 동형).

### 선행 실측 — CI 배선은 이미 존재한다 (추가 작업 0)

제약 4(「CI 배선까지가 범위」)를 착수 전에 확인했다. `backend-ci.yml:139-141` 에
`Test — :modules:app (비-prod 조립 부팅 가드)` 스텝이 이미 있고 `:modules:app:nonProdAssemblyTest` 를 실행한다.
`build.gradle.kts:114-118` 이 그 태스크에 `includeTags("nonprod-assembly")` 를 걸고 기본 `test` 는 같은 태그를
`excludeTags` 한다. ⇒ **신규 테스트에 `@Tag("nonprod-assembly")` 를 붙이면 CI 에서 자동 실행된다.**
prod 음성 테스트는 태그 없이 두면 기본 `test` 잡(`backend-ci.yml:133`)에서 돈다. **새 CI 배선 불필요.**
단, 이 사실을 **CI 로그로 실제 확인**하는 것은 완료 기준에 남긴다 (#321 의 「0회 실행」 사고 방지).

---

> **리뷰 반영 (2026-07-29 `/plan-eng-review`).** 아래 3건이 계획에 이미 적용돼 있다 —
> **D8 축소**(설정 전용 클래스 폐기, 생성자 `@Value` 흡수 → 7 task→6, 11파일→8, 신규 클래스 3→2) ·
> **이슈 1A**(`SYSTEM_ADMIN` → `CREATE_PROJECT` 전역 부여) · **이슈 2A**(`@Profile` 부정 → 허용목록).

### Task 1. 사용자 + 로컬 자격증명 시드 (FR-1 · FR-2 · FR-8)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/NonProdDevSeeder.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/NonProdDevSeederTest.kt`, `backend/modules/app/src/main/resources/application.yml`]
- depends-on: []

**설정 표면 (D8 축소 반영).** 전용 프로퍼티 클래스를 만들지 않고 생성자 `@Value` 로 흡수한다 —
`bts.dev-seed.username:alice` · `bts.dev-seed.password:${BTS_DEV_SEED_PASSWORD:password}` ·
`bts.dev-seed.email:alice@bts.local` · `bts.dev-seed.display-name`.
기본 비밀번호가 기존 `data-dev.sql` 이 이미 문서화한 값과 같으므로 **새 비밀이 도입되지 않는다**(D8 근거).

**RED**. 시드 실행 후 (a) `UserRepository.findByUsername("alice")` 가 non-null,
(b) **`LocalCredentialService.verify` 가 시드한 평문으로 통과** — 해시가 실제로 유효함을 행 존재가 아니라 **검증 성공**으로 단언한다.
실패 예상. `NonProdDevSeeder` 없음.

**GREEN**. `@Component @Transactional class NonProdDevSeeder(userRepository, localCredentialService, ...)`.
`userRepository.create(username, email, displayName)` → `localCredentialService.store(user.id, plain, mustChange = false)`.

**REFACTOR**. 평문 수명 — `store` 가 인자 `CharArray` 를 wipe 하므로 시드는 `String` → `CharArray` 변환 직후 넘기고
별도 사본을 만들지 않는다. KDoc 에 **G2 함정**(`CreateLocalAccountService` 는 무작위 비밀번호 + `mustChange=true` 라 부적합)을 기록.

**검증**. `./gradlew :modules:app:test --tests '*NonProdDevSeederTest'`

---

### Task 2. 프로젝트 생성 권한 부여 (FR-3 — **이슈 1A 로 재정의**)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/NonProdDevSeeder.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/NonProdDevSeederTest.kt`]
- depends-on: [1]

> **⚠️ 원안 폐기 — `SYSTEM_ADMIN` 을 주면 안 된다.** `MfaEnforcementPolicy.kt:24` *"관리자(SYSTEM_ADMIN)는
> 무조건 강제 대상이다"* · `:71` `systemPermissionResolver.isSystemAdmin(userId) ||` → `JwtIssuer.kt:109` 가
> `mfa_enrollment_required=true` 를 토큰에 박고 → `MfaEnrollmentGateFilter.kt:54` 가 허용목록 밖 **전 경로 403**.
> 즉 원안대로면 **로그인은 되지만 어떤 화면에도 못 간다** — 이 작업의 유일한 목적이 파괴된다.

**RED**. 3 단언.
- (a) `IdentityAccessSystemPermissionResolver.hasGlobalPermission(alice.id, CREATE_PROJECT)` = **true**
- (b) `isSystemAdmin(alice.id)` = **false** (관리자가 아님을 명시적으로 고정)
- (c) **`MfaEnforcementPolicy.evaluate(alice.id)` = false** ← 이슈 1 회귀 가드. 훗날 누가 `SYSTEM_ADMIN` 을
  되살리면 이 단언이 즉시 깨진다. **행 존재가 아니라 게이트 통과 여부가 계약이다.**

**GREEN**. 전역 부여 1행 삽입 (`grantRepo` 경로 — `IdentityAccessSystemPermissionResolver.kt:66`
`hasGrant(actorId, permission) || isSystemAdmin(actorId)` 의 앞항).

**REFACTOR**. 두 쓰기가 하나의 트랜잭션임을 KDoc 에 명시. **왜 관리자가 아닌지**(MFA 게이트 연쇄)를
KDoc 에 인용과 함께 남긴다 — 근거 없이 보면 "관리자로 올리면 편한데" 로 되돌리기 쉽다.

**검증**. `./gradlew :modules:app:test --tests '*NonProdDevSeederTest'`

---

### Task 3. 멱등 + 부분 상태 보정 (FR-4 · E1 · E2 · E3 · E6)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/NonProdDevSeeder.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/NonProdDevSeederTest.kt`]
- depends-on: [2]

**RED**. 4 시나리오 (D8 축소로 시더 테스트 파일에 합침).
- E1 2회 연속 시드 → `users` 행 수 1 불변, `created_at` 불변
- E2 사용자만 있고 자격증명 없음 → 자격증명만 생성
- E3 부여 이미 존재 → 제약 위반 없이 통과
- **E6 기존 비밀번호가 다름 → 덮어쓰지 않는다** (기존 해시 문자열 불변을 직접 단언)

**GREEN**. 각 쓰기 앞에 존재 조회(`findByUsername` / 자격증명 조회 / `hasGrant`)를 두고 없을 때만 삽입.

**REFACTOR**. E6 의 「덮어쓰지 않음」이 **의도된 선택**임을 KDoc 에 남긴다 — 멱등 원칙이 편의보다 우선.

**검증**. `./gradlew :modules:app:test --tests '*NonProdDevSeederTest'`

---

### Task 4. ApplicationRunner 배선 + prod 격리 L1/L2 + 실패 비대칭 (FR-5 · FR-6 · FR-7 · FR-9 · D9 · **이슈 2A**)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/NonProdDevSeedRunner.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/NonProdDevSeedRunnerTest.kt`]
- depends-on: [3]

**RED**. 4 시나리오.
- 허용 프로파일(무프로파일=`default`) → `seeder.seed()` 1회 호출 + INFO 로그
- **활성 프로파일에 `prod` 포함 → 예외 전파(기동 실패)**. 조용한 return 이면 실패해야 한다
  (음성 판별자 확보 — 메모리 `negative-guard-needs-body-discriminator`)
- **모르는 프로파일(`staging`) → 시드 미실행** ← 이슈 2A 허용목록 가드. 부정(`!prod`)이면 통과해버린다
- **`seeder.seed()` 가 예외 → ERROR 로그 후 정상 반환**(부팅 계속). D9 비대칭

**GREEN**. `@Component @Profile("default | dev | local") class NonProdDevSeedRunner(...) : ApplicationRunner`.
`run()` 진입부에서 `environment.activeProfiles` 를 **같은 허용목록**과 대조 → 불일치면 `IllegalStateException`.
`seeder.seed()` 는 try/catch (트랜잭션 경계 **밖**이라 롤백 후 로그만 남는다).

> **이슈 2A 근거.** 부정(`!prod`)은 앞으로 생기는 모든 프로파일에 대해 **fail-open** 이다.
> 허용목록은 fail-closed. 조립 앱의 실제 기본 부팅은 **무프로파일**이므로 `default` 를 반드시 포함해야 한다
> (`application-dev.yml` 은 `dev` 활성 시에만 병합된다 — 실측).
> L1(빈 등록)과 L2(런타임 단언)가 **같은 목록 상수**를 공유해야 한 쪽만 고치는 drift 가 안 생긴다.

**REFACTOR**. KDoc 에 **순서 근거**(G4) 기록 — `FlywayAssemblyConfig:45-65` 가 `InitializingBean` 이라
refresh 중 마이그레이션이 끝나고 `ApplicationRunner` 는 refresh 후 실행. **`@PostConstruct`/`InitializingBean` 으로
옮기면 조용히 깨진다**고 경고문을 남긴다. 비밀번호 미로깅(NFR-3)도 단언.

**검증**. `./gradlew :modules:app:test --tests '*NonProdDevSeedRunnerTest'`

---

### Task 5. L3 양방향 봉인 — 실부팅 대조 (§6 L3)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/NonProdDevSeedBootTest.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/ProdDevSeedAbsenceBootTest.kt`]
- depends-on: [4]

**RED**. 두 방향을 **각각 다른 태스크에서** 돈다.
- **양성** `NonProdDevSeedBootTest` — `@Tag("nonprod-assembly")` + `RANDOM_PORT`. 실부팅 후
  `users`/`local_credentials`/전역 부여 각 1행 + **실 HTTP `POST /login` 200**
  (행 존재가 아니라 로그인 성공이 계약) + **로그인 토큰으로 `GET /whoami` 200 · `canCreateProject=true` ·
  `mfaEnrollmentRequired=false`** ← 이슈 1 을 실서버 응답으로 못 박는다
- **음성** `ProdDevSeedAbsenceBootTest` — 기존 `ProdAssemblyHttpTestBase` 상속(prod 고정, 태그 없음).
  `NonProdDevSeedRunner` 빈 **0개** + `users` **0행**

**GREEN**. 테스트만 추가. 프로덕션 코드 변경 0.

**REFACTOR**. 두 테스트가 **서로의 대조군**임을 KDoc 에 상호 링크. 메모리 `seal-closes-only-half-by-default` 인용.

**검증**. `./gradlew :modules:app:test --tests '*ProdDevSeedAbsenceBootTest'` +
`./gradlew :modules:app:nonProdAssemblyTest` (dev postgres 5433 필요)

---

### Task 6. 뮤테이션 실증 + ADR + 문서 동기화

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-29-assembly-nonprod-dev-seed.md`, `docs/plans/2026-07-29-assembly-nonprod-dev-seed.md`]
- depends-on: [5]

**RED/GREEN 해당 없음** (검증 + 문서 task).

**뮤테이션 6종** — 각각 주입 후 **실컨텍스트 부팅**으로 RED 확인, 원복. 커밋 후에만 수행
(메모리 `mutation-test-requires-committed-baseline`).

| # | 주입 | 기대 RED |
|---|---|---|
| M1 | `@Profile` 허용목록 제거 | 음성 부팅 테스트 (빈 0개 단언) |
| M2 | L2 런타임 단언을 조용한 `return` 으로 교체 | 러너 단위 테스트 prod 시나리오 |
| M3 | 멱등 존재 조회 제거 | 멱등 시나리오 (중복/제약 위반) |
| M4 | 전역 부여 삽입 제거 | `hasGlobalPermission` 단언 + 양성 부팅 `canCreateProject` |
| M5 | 러너를 `InitializingBean` 으로 이동 (순서 파괴) | 양성 부팅 (테이블 부재로 실패) |
| **M6** | 전역 부여를 **`SYSTEM_ADMIN` 부여로 되돌림** | `MfaEnforcementPolicy.evaluate=false` 단언 + 양성 부팅 `mfaEnrollmentRequired` |

**★M2 는 필수** — 조용한 스킵으로 바꿔도 음성 테스트가 통과하면 그 테스트는 **공허**하다.
**★M6 은 이슈 1 전용 회귀 가드** — "관리자로 올리면 편한데" 로 되돌리는 순간 red 여야 한다.
**★허용목록 drift** — L1 과 L2 가 같은 상수를 공유하는지 M1 이 실제로 판별하는지 확인할 것.

**ADR**. `docs/decisions/2026-07-29-assembly-nonprod-dev-seed.md` — D4~D9 결정과 기각안(Flyway·통합) 근거 기록.

**문서 동기화**. FR 변경 0건이므로 `fr-index`·`README`·`CHANGELOG` 카운트는 **불변**.
`verify-master-plan.sh` EXIT 0 · 139/139 확인만 수행.

**검증**. `./gradlew :modules:app:test :modules:app:nonProdAssemblyTest` ·
`./gradlew ktlintCheck detekt --rerun-tasks` · `bash scripts/verify-master-plan.sh`

## Plan 메타

- task 수: **6** (리뷰 D8 축소로 7→6)
- 파일 수: **8** · 신규 클래스 **2** (`NonProdDevSeeder` · `NonProdDevSeedRunner`)
- 예상 wave: **6 (전량 직렬)** — T1·T2·T3 이 `NonProdDevSeeder.kt` 를 공유하고 T4→T5→T6 이 선행 산출물에 의존.
  파일 겹침 자동 직렬화 규칙에 걸린다. 메모리 `bts-plan-wave-gradle-module-compile`(같은 Gradle 모듈 동시 컴파일 충돌)과도 정합.
- TDD 강제: yes (`test:` 커밋이 `feat:` 보다 선행)
- 병렬 dispatch: **없음** (전 task 가 `:modules:app` 단일 모듈 + 파일 공유)
- 추가 검증: ktlint · detekt `--rerun-tasks` · **뮤테이션 6종** · 브라우저 눈확인
- CI: 신규 배선 **불필요** (실측 확인 — `backend-ci.yml:139-141` 기존 스텝이 태그로 자동 수집)

## 리뷰 결과

### plan-eng-review (2026-07-29)

**Step 0 범위 도전 → 축소 채택 (D8).** 복잡도 체크 트리거(11파일/3클래스 > 8파일/2클래스 기준).
`DevSeedProperties` 전용 클래스를 생성자 `@Value` 로 흡수해 **6 task · 8파일 · 2클래스**로 축소.
`NonProdDevSeeder`↔`NonProdDevSeedRunner` 분리는 **축소 불가** — D9 가 트랜잭션 경계 **밖** catch 를 요구하고,
합치면 자기 호출(self-invocation)로 `@Transactional` 프록시가 우회된다(메모리 `transaction-self-invocation-requires-new`).

**이슈 1 — [P1] (confidence 9/10) 계획이 자기 목적을 파괴했다. → 1A 채택.**
`MfaEnforcementPolicy.kt:24` *"관리자(SYSTEM_ADMIN)는 무조건 강제 대상이다"* · `:71`
`systemPermissionResolver.isSystemAdmin(userId) ||` → `JwtIssuer.kt:109` 가 `mfa_enrollment_required=true` 를
토큰에 박고 → `MfaEnrollmentGateFilter.kt:54` 가 허용목록 밖 **전 경로 403**.
원안(FR-3 `SYSTEM_ADMIN` 부여)대로면 **로그인은 성공하나 어떤 화면에도 도달 못 한다** — 유일한 목적이 파괴된다.
처방 = `IdentityAccessSystemPermissionResolver.kt:66` `hasGrant(actorId, permission) || isSystemAdmin(actorId)` 의
**앞항**을 쓴다. `CREATE_PROJECT` 전역 부여 1행이면 프로젝트 생성이 열리고 MFA 강제는 켜지지 않는다.
> **Prior learning applied**: `bts-assembly-test-pat-bearer-and-httpclient` (confidence 9/10, 2026-07-27)
> — *"SYSTEM_ADMIN 은 MFA 미등록 시 그 게이트에서 403"*. 이 기록이 없었으면 구현 완료 후 브라우저에서야 발견했다.

**이슈 2 — [P2] (confidence 8/10) `@Profile("!prod")` 는 fail-open. → 2A 채택.**
부정은 앞으로 생기는 모든 프로파일을 자동 포함한다(`staging`·`demo` 등). 허용목록으로 뒤집어 fail-closed.
조립 앱 기본 부팅이 **무프로파일**이므로 `default` 를 반드시 포함(실측 — `application-dev.yml` 은 `dev` 활성 시에만 병합).
L1(빈 등록)과 L2(런타임 단언)가 **같은 상수**를 공유해 drift 를 막는다.

**Code Quality — 이슈 0건(주 보고).** 부록 1건 — 멱등 「조회 후 삽입」은 원리상 TOCTOU(검사·사용 시점 차)를 갖는다
(confidence 6/10). 동시 부팅 2 프로세스에서만 발생하고 D9(시드 실패=ERROR 로그+부팅 계속)가 이미 흡수하므로 미조치.

**Performance — 이슈 0건.** 부팅당 조회 3 + 삽입 최대 3. N+1 없음, 캐시 대상 없음.

#### 테스트 커버리지 다이어그램

```
CODE PATHS                                        USER FLOWS
[+] NonProdDevSeeder                              [+] dev 손검증 여정
  ├── seed()                                        ├── [GAP→T5] 로그인 alice/password → 200
  │   ├── [GAP→T1] 사용자 부재 → 생성                ├── [GAP→T5] whoami canCreateProject=true
  │   ├── [GAP→T3] 사용자 존재 → 무변경               ├── [GAP→T5] whoami mfaEnrollmentRequired=false ★이슈1
  │   ├── [GAP→T1] 자격증명 생성(실 Argon2 verify)    └── [수동]   /projects/new 에서 프로젝트 생성 (눈확인)
  │   ├── [GAP→T3] 자격증명 존재 → 해시 불변(E6)
  │   ├── [GAP→T2] 전역 부여 삽입 → hasGlobalPermission=true
  │   ├── [GAP→T2] isSystemAdmin=false ★이슈1 회귀가드
  │   └── [GAP→T3] 부여 존재 → 제약위반 0
[+] NonProdDevSeedRunner
  ├── run()
  │   ├── [GAP→T4] 허용 프로파일 → seed() 1회 + INFO
  │   ├── [GAP→T4] prod 포함 → 예외(기동 실패)  ★음성 판별자
  │   ├── [GAP→T4] staging(미허용) → 미실행     ★이슈2 가드
  │   └── [GAP→T4] seed() 예외 → ERROR 로그 + 정상 반환 (D9)
[+] 봉인 (실부팅)
  ├── [GAP→T5] 양성: 비-prod 조립 → 3행 + 로그인 200   [→E2E]
  └── [GAP→T5] 음성: prod 조립 → 빈 0개 + users 0행    [→E2E]

COVERAGE: 계획 반영 후 16/16 (100%)  |  현재 구현 0/16 (구현 전이므로 전량 GAP 이 정상)
QUALITY(계획 기준): ★★★:16  |  미할당 GAP: 0
```

**모든 GAP 이 특정 task 에 배정돼 있다** — 「완료기준만 추가하고 소유자를 안 정하는」 실패
(learning `completion-criterion-without-task-or-feasibility`)를 피했다.

#### 실패 모드 (신규 코드경로별)

| 코드경로 | 현실적 프로덕션 실패 | 테스트 | 에러 처리 | 사용자가 보는 것 |
|---|---|---|---|---|
| `seed()` 삽입 | DB 제약 위반 / 연결 끊김 | T3·T4 | D9 ERROR 로그 + 부팅 계속 | 로그인 실패(계정 없음) — 로그에 원인 명시 |
| 러너 프로파일 판정 | 새 프로파일 추가로 fail-open | T4(staging 시나리오) | 허용목록 fail-closed | 시드 미실행(안전측) |
| 순서(Flyway 이전 실행) | 리팩터로 `InitializingBean` 이동 | T6 M5 뮤테이션 | 없음(구조로 보장) | 부팅 실패 — 즉시 관측 |
| 전역 부여 누락 | 리팩터로 삭제 | T2·T5·M4 | 없음 | `/projects/new` 403 |
| **SYSTEM_ADMIN 복귀** | "편하니까" 되돌림 | **T2(c)·T5·M6** | 없음 | 전 화면 403 — **이슈 1 재발** |

**critical gap(테스트 0 + 에러처리 0 + 무음) = 0건.**

#### NOT in scope (검토 후 명시적 이연)

| 항목 | 사유 |
|---|---|
| 프로젝트·이슈·추가 사용자 시드 | D4 최소 결정. 프로젝트는 실 UI 경로로 만드는 것이 손검증 가치가 더 크다 |
| 기존 `data-dev.sql` 2개 통합/삭제 | D6. 다른 BC 파일을 건드리면 #321 의 「다른 BC 0줄」이 깨진다 |
| `classpath*` 로 모듈 시드 되살리기 | D6 에서 기각 — Flyway 대비 실행 순서 미검증 |
| `/admin/*` 관리자 화면 손검증 | 이슈 1A 로 SYSTEM_ADMIN 을 안 주므로 도달 불가. 별도 과제 |
| MFA 등록 상태 시드 | 1B 기각 — 로그인마다 TOTP 코드가 필요해 손검증이 더 번거로워진다 |
| prod 시드 전략 | 해당 없음 — 영구 제외 |

#### What already exists (재사용 vs 재구축)

| 기존 자산 | 이 계획의 처리 |
|---|---|
| `UserRepository.create` · `findByUsername` | **재사용** (신규 도메인 로직 0) |
| `LocalCredentialService.store/verify` (실 Argon2id) | **재사용** — 해시 리터럴을 저장소에 남기지 않는 근거 |
| `IdentityAccessSystemPermissionResolver.hasGlobalPermission` | **재사용** — 1A 처방의 근거 |
| `permission_schemes`(V008) · `workflow_schemes`(V201) 시드 | **재사용** — 5계층 전제가 낡았음을 실증 |
| `data-dev.sql` 2개 | **의도적 미사용** (D6). 조립에서는 비활성 유지, 단독 모듈 실행용으로 존속 |
| `nonProdAssemblyTest` 태스크 + `backend-ci.yml:139-141` | **재사용** — CI 신규 배선 0 |
| `ProdAssemblyHttpTestBase` | **재사용** — 음성 봉인 테스트의 기반 |
| `CreateLocalAccountService` | **의도적 미사용** (G2). 무작위 비밀번호 + `mustChange=true` 라 목적 불일치 |

#### Worktree 병렬화

**Sequential implementation, no parallelization opportunity.** 6 task 전량이 `:modules:app` 단일 모듈이고
T1·T2·T3 이 `NonProdDevSeeder.kt` 를 공유한다.

#### Outside voice

**미실행.** `codex` CLI 미설치(`codex: NOT installed`)이고, 대체 경로인 Claude sub-agent dispatch 는
**Maxi 의 세션 지시(에이전트 호출 금지)** 로 사용하지 않았다. ⇒ 이번 리뷰는 **단일 모델**이다.
교차모델 검증 부재는 #308·#309·#310 과 동형의 잔여 위험이며 게이트 2 에서 재고 대상.

#### Implementation Tasks

이 리뷰의 findings 에서 파생된 것만. 계획 본문 Task 1~6 에 이미 흡수돼 있으므로 중복 나열하지 않는다.

- [x] **T-R1 (P1, human: ~2h / CC: ~10min)** — `NonProdDevSeeder` — `SYSTEM_ADMIN` 대신 `CREATE_PROJECT` 전역 부여
  - Surfaced by: Architecture — 이슈 1 (`MfaEnforcementPolicy.kt:24,71` → `MfaEnrollmentGateFilter.kt:54`)
  - Files: `backend/modules/app/src/main/kotlin/com/bts/app/NonProdDevSeeder.kt`
  - Verify: `MfaEnforcementPolicy.evaluate(alice.id) == false` + 양성 부팅 `whoami.mfaEnrollmentRequired == false`
  - → **계획 Task 2 에 반영 완료**
- [x] **T-R2 (P2, human: ~1h / CC: ~10min)** — `NonProdDevSeedRunner` — `@Profile` 부정 → 허용목록
  - Surfaced by: Architecture — 이슈 2 (fail-open 프로파일 판정)
  - Files: `backend/modules/app/src/main/kotlin/com/bts/app/NonProdDevSeedRunner.kt`
  - Verify: `staging` 프로파일 시나리오에서 시드 미실행 + M1 뮤테이션 RED
  - → **계획 Task 4 에 반영 완료**
- [x] **T-R3 (P2, human: ~30min / CC: ~5min)** — 뮤테이션 — M6(SYSTEM_ADMIN 복귀) 추가
  - Surfaced by: Failure modes — 「SYSTEM_ADMIN 복귀」가 이슈 1 재발 경로
  - Files: `docs/plans/2026-07-29-assembly-nonprod-dev-seed.md` Task 6
  - Verify: M6 주입 시 `evaluate=false` 단언 RED
  - → **계획 Task 6 에 반영 완료**

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | codex CLI 미설치 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR (PLAN) | 2 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | UI 변경 0 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** ENG CLEARED — 구현 착수 가능. 이슈 2건(P1 1 · P2 1) 전량 계획에 반영 완료, 미해결 0.
**단일 모델 리뷰** — outside voice 미실행(codex 미설치 + 에이전트 호출 금지 지시)이므로 교차모델 확인은 없다.

NO UNRESOLVED DECISIONS

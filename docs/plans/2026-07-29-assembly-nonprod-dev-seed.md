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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

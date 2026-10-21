# 영역별 코드 체크리스트

구현 · 리뷰 전에 **이번 일에 걸리는 절만** 연다 — `grep -n "^## " docs/rules/code-checklists.md` 로 범위를 찾는다.
절대 규칙 정본은 `DEVELOPMENT.md` §1, 데이터는 `DATA.md`. 2026-10-20 하네스 다이어트 때 역할 에이전트 5종의 본문을 옮겼다.

## 백엔드 (Kotlin/Spring)
### 담당 BC

| BC | 주 책임 |
|---|---|
| issue-tracking | 이슈 CRUD, 이슈 키 발급, 코멘트, 첨부, 라벨 |
| project-workflow | FSM, 전환 검증, YAML 워크플로우 정의 |
| agile-planning | 스프린트, 백로그, 보드, LexoRank |
| automation | 룰 평가, AQL 파서 (ANTLR 4) |
| notification | 인앱/이메일 알림, 그룹화 |
| slack-integration | Unfurl, Slash, Interactive |

### 필수 체크리스트

1. **모듈 경계 존중** — 다른 BC의 내부 클래스 직접 import 금지. 공개 API (`api/` 패키지) 또는 이벤트 발행만
2. **`@Transactional` 명시** — public service 메서드 전체. 누락 = BLOCKER
3. **jOOQ DSL** — SQL 문자열 결합 금지, 동적 조건은 `Condition` 빌더. **예외는 `DATA.md` §5(2026-05-26) 가 정본** — parameter binding + jOOQ 미지원 PG 함수(`pg_advisory_lock`·`pgmq.send` 등). **예외 해당 여부를 먼저 확인하고 BLOCKER 를 매긴다.**
4. **Jakarta Validation** — `@field:NotBlank`, `@field:Email` 등 (Kotlin 어노테이션 prefix)
5. **응답 포맷 통일** — 성공 `data: T`, 에러 `error: { code, message }`
6. **에러 코드 prefix 고정** — `ISSUE_`, `WORKFLOW_`, `AUTOMATION_`, `NOTIF_`, `SLACK_`
7. **로깅** — Logback 구조화. `private val log = LoggerFactory.getLogger(javaClass)`
8. **페이지네이션** — 커서 기반이 기본(`?cursor=…&limit=…`, `page`/`size` 폴백 병존). 파라미터명은 같은 BC 컨트롤러를 grep 후 따른다

### 절차

1. **기존 패턴 조사** — 같은 BC의 가까운 컨트롤러/서비스 2-3개 Read
2. **테스트** — 비즈니스 로직은 MockK 단위, 트랜잭션/DB 경계는 Testcontainers 통합. 돌릴 때는 대상만(`--tests '<클래스>'`)
3. **새 이벤트 발행** — 같은 트랜잭션에서 pgmq enqueue. 다른 BC 직접 호출 금지 · 핸들러는 별도 워커 프로세스
4. **모듈 경계 위반 감지** — Detekt 커스텀 룰 미도입. 다른 BC의 `internal` import 는 코드 리뷰에서 수동 차단

### 회귀 방지 (실제 사고 교훈 — 같은 실수 재발 금지)

- **jOOQ 다중 LEFT JOIN + count = cartesian product** — 여러 LEFT JOIN 뒤 count/집계는 행이 곱으로 폭증해 값이 틀어진다. 연관 카운트는 스칼라 서브쿼리로 분리 (PR #31 BLOCKER)
- **advisory lock 시그니처 + TOCTOU** — `pg_advisory_xact_lock`은 `(bigint, bigint)` 시그니처가 없다. 단일 `bigint` 또는 `(int4, int4)`만 존재하므로 `dsl.execute("select pg_advisory_xact_lock(?)", key)` 형태로 호출. 그리고 lock을 잡았어도 lock 밖에서 미리 읽은 count로 판단하면 TOCTOU(검사-사용 사이 변경)가 무력화된다 → **lock 획득 후 반드시 재조회**. 단위 mock은 못 잡고 Testcontainers 통합만 표면화 (PR #48)
- **PATCH 도메인 우회** — PATCH 핸들러가 service→repository 직행하면 도메인 Aggregate 불변식 검증이 dead code가 된다. service가 도메인 정규화 함수를 호출해야 함. DTO 검증(@field:*)은 1차 방어일 뿐 (PR #43 BLOCKER)
- **detekt false-green** — 빌드 캐시가 위반을 가린다. 실검증은 바뀐 모듈의 `:modules:<bc>:detekt --rerun-tasks`. PRE_EXISTING 위반은 모듈 `detekt-baseline.xml` 동결(현재 8모듈 보유 — shared-kernel·agile-planning 없음), 신규 위반은 코드 수정 또는 `@Suppress`. 전역 임계값 완화 금지 (PR #37/#40)
- **트랜잭션 self-invocation 무력화** — `@Transactional` 메서드를 같은 클래스 안에서 호출하면 Spring 프록시를 타지 않아 트랜잭션 속성이 무시된다. `REQUIRES_NEW`가 필요한 로직은 별도 Bean으로 분리해 주입받아 호출 (rollback 오염 사고)
- **catch-all 핸들러가 ResponseStatusException 삼킴** — `@ExceptionHandler(Exception::class)` catch-all이 프레임워크 예외까지 잡으면 401이 500으로 변질된다. 구체 예외 핸들러를 분리하고 advice의 basePackage 스코프를 좁게. 도메인 예외는 타 컨트롤러 경로에서의 HTTP 통합테스트로 확인
- **cross-BC 권한은 resolver 창구만** — 타 BC의 권한 확인은 권한코드 + 권한 resolver 경유만. 멤버십 role 직접 조회는 권한 모델 우회 (FR-PM-07)
- **enum/권한코드 추가는 타 모듈 카운트 가드도 깬다** — enum 값·권한코드 시드 추가 시 다른 모듈의 카운트 검증 테스트가 깨진다. 추가 전 전 모듈 grep으로 카운트 가드 동반 수정

그 외 사고 이력 전체는 `Maxi_wiki/BTS/learnings.md` 참조 (inline 주입 대상 아님 — 필요 시 직접 Read 가능).

### 절대 금지

- 다른 BC의 내부 클래스(`internal`) 직접 import · `@Transactional` 없는 public service 메서드
- jOOQ 없이 raw SQL(`Connection.createStatement`) — 예외는 위 체크리스트 3
- `println`/`System.out`(Logback 사용) · `!!` non-null assertion · `process.env.*` 직접(`@Value`/`@ConfigurationProperties`)
- 그 외 공통 금지(빈 catch 등)의 정본은 `DEVELOPMENT.md` §1 절대 규칙 19개

### 참조 파일

- 먼저 볼 것 — `DEVELOPMENT.md` §1 · §2 · `DATA.md` §5 · §6 · §7 · `Maxi_wiki/BTS/domain/<bc>.md`
- 필요 시 Read — 관련 SDD `docs/sdd/0{5,7,8,9,10,11}.md`

## 프론트 (React/TS)
### 담당

- 컴포넌트 · 라우팅(TanStack Router) · 상태(TanStack Query, Zustand) · 폼(RHF + Zod) · 데이터 페칭(`apps/web/src/api`, ky + Zod 응답 검증)
- 새 UI 의 디자인 스펙 · HTML 목업 · `DESIGN.md` 토큰 확장 (§새 UI 스펙 모드)
- 단일 SPA 구조다 — `packages/**` · `features/**` 는 존재하지 않으니 그 경로로 import/생성하지 않는다

### 필수 체크리스트

1. **`strict: true` + `noUncheckedIndexedAccess: true`** — `arr[0]` 은 `T | undefined`. `any` 금지(모르면 `unknown`) · `!` null assertion 금지
2. **`interface` 우선** — `type` 은 union/intersection 전용. Zod 스키마는 `z.infer` 로 도출하고 `interface` 중복 정의 금지
3. **컴포넌트 named export** — default export 금지 (IDE 자동 import 충돌)
4. **shadcn 위에 build** — 래퍼 부재 컴포넌트는 radix-ui 직접 import 가 정상. 래퍼 신설은 Maxi 확인
5. **i18n** — 사용자 노출 문자열 전부 `t('namespace.key')` (한국어 ↔ 영어 길이 차 고려)
6. **세션 토큰은 `sessionStorage` 또는 HttpOnly Cookie** — `localStorage` 절대 금지
7. **성능** — TanStack Query `staleTime` 명시(미지정 0 이 요청 폭증). 100행 이상 렌더 예상 시 가상화 여부를 plan 에 명시(기본은 페이지네이션/필터 · 가상화는 Maxi 확인)

### Jira 패리티 즉사 계약 (UI 작업 필수)

BTS 의 UI/UX 기준은 **Jira Cloud (2025)** 다. 정본은 `docs/design/jira-parity-contract.md`.

- **새 UI 는 Jira 대조가 먼저** — 스펙의 `## Jira 대조` 섹션(계약 §1 절차 산출물)을 확인하고 구현. 스펙에 없는 UI 를 자체 발명하지 않는다
- **plan 「할 일」에 `jira: [J1, J3]` 이 있으면 그 번호의 근거 행을 먼저 읽는다** — 원문 인용과 출처가 그 행에 있다. 번호가 가리키는 조작을 빠뜨리면 plan 의 `Jira 매핑` 줄과 어긋난다. **web 도구가 없으므로 직접 조회하지 말고**, 근거가 부족하면 구현을 멈추고 보고한다
- **즉사 계약 요지 (본문 암기 대상)** — ① nav `aria-label` 4종(`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·`검색`)과 `<h1>` 이름은 **글자 단위 verbatim 보존** ② `프로젝트 뷰 전환`을 Radix Tabs 로 바꾸면 `role="navigation"` 소멸로 e2e 즉사 ③ 신규 다이얼로그는 고유 `aria-label` ④ 기존 단축키 레지스트리(`SHORTCUTS` 5종)는 동결 — 나머지 4행은 계약 §2
- **재사용 자산 먼저** — `command.tsx` · `popover.tsx` · `IssueTypeIcon` · `meta/` 8종 · `FilterBar` 슬롯 · `EmptyState` 등 계약 §4 레지스트리를 grep 후 소비. 새로 만들면 병렬 FR 간 add/add 충돌
- **사전 grep** — 수정 대상이 노출된 e2e/유닛 어서션을 먼저 잰다(계약 §5). 결과가 0이 아니면 해당 스펙 갱신을 같은 task 범위로 산정

### 상태 3종 — 에러/로딩/빈 (필수)

데이터를 그리는 모든 화면은 세 상태를 전부 처리한다. **빈 `<div/>` 반환 금지** — 백로그 에러가 빈 화면으로 침묵한 실사고가 로드맵 결함 목록에 있다.

- 로딩. Skeleton 계열 (기존 `*Skeleton` 컴포넌트 패턴 복제)
- 에러. 안내 + 재시도 액션 (toast 단독 금지 — 화면에 남는 상태 표시)
- 빈. `EmptyState` 프리미티브 재사용 (`DESIGN.md` §4)

### 브라우저 눈확인 (시각 변화 시 생략 금지)

시각/조작 변화가 있는 task 는 `jira-parity-contract.md` §6 절차로 실제 브라우저에서 라이트/다크 양쪽을 확인하고,
관찰 요지를 DONE 보고에 포함한다. 코드·테스트 초록만으로 시각 변화를 닫지 않는다
(FR-UX-06 22 PR 이 눈확인 0회로 끝난 것이 기록된 프로세스 결함).
스냅샷 파일럿이 덮는 4화면은 `visual` 잡이 대체하되, 조작·인터랙션 변화는 여전히 눈확인 대상이다.

### 새 UI 스펙 모드 (스펙이 없는 새 UI 일 때)

기존 스펙(`docs/design/<slug>.md` · `apps/web/public/mockups/<slug>.html`)이 있으면 그대로 구현한다. 없으면 **구현 전에 스펙을 먼저 쓴다**.

1. **`DESIGN.md` 먼저** — 기존 토큰 위에 짓는다(통째 Read 금지 — 헤딩 grep 후 부분 Read). 팔레트 정본은 §2 뿐이고 🔒 동결 토큰(§2·§5 `--font-mono`)은 건드리지 않는다. elevation 은 그림자 대신 `ring-1 ring-foreground/10` 관례(§8)
2. **Jira 대조 → 대응 화면이 없으면 ADS(Atlassian Design System) v2 준용**을 스펙에 명시. 근거 없는 자체 발명 금지. 앱 셸/사이드바는 `fr-ux-06-jira-redesign.md` §3 을 전제로 한다
3. **프리미티브 24종 레지스트리(`DESIGN.md` §4) 위에서 정의** — 재사용인지 신설인지 명시하고, 신설이면 `DESIGN.md` 패치를 동반
4. **상태 7종 전부** — default / hover / active / disabled / loading / error / empty
5. **반응형 4종 전부** — sm(~640) / md(~768) / lg(~1024) / xl(~1280) 각각 어떻게 변하는지
6. **아이콘은 Lucide React 만**(이모지 금지, 이름 + 24x24 기본 명시) · 본문 ≥14px · 모바일 터치 타깃 ≥44px · 색 대비 4.5:1
7. **핸드오프 완결 조건** — 색상 토큰명 · 타이포/간격 Tailwind 토큰 · i18n 키 위치 · aria/키보드 · shadcn 매핑이 전부 적혀야 스펙이 끝난 것이다

산출물은 `docs/design/<slug>.md` 또는 `apps/web/public/mockups/<slug>.html`, 토큰 신설 시 `DESIGN.md` 패치를 함께 낸다.

### 회귀 방지 (실제 사고 교훈 — 같은 실수 재발 금지)

- **Zod 응답 스키마는 backend DTO에서 도출** — 응답 스키마를 backend와 분리해 invent하면 MSW mock 위에서만 통과하고 실제 API에선 깨진다. 새 응답 타입은 spec/구현 전에 backend 컨트롤러를 grep해 실제 필드와 맞출 것 (PR #31)
- **Zod 스키마 강화 시 인라인 mock 전수 점검** — 응답 스키마에 required 필드를 추가하면 산재한 인라인 mock들이 `z.parse` 실패로 깨진다. vitest는 타입을 검증 안 하니 grep 전수검색 + `pnpm typecheck`(tsc) 동반 필수 (PR #46)
- **mutation은 invalidate-only 또는 캐시 머지** — `setQueryData(응답)`로 캐시를 통째 덮으면, 응답에 없는 파생 필드(예: 단건 GET만 채우는 `descriptionHtml`)가 null로 덮여 화면이 플리커한다. invalidateQueries로 refetch하거나 기존 캐시와 머지 (PR #46)
- **props 식별값 useState는 key prop으로 재마운트** — props의 id로 useState를 초기화하는 컴포넌트는 id가 바뀌어도 state가 stale하게 남는다. 부모에서 `key={id}`를 줘 재마운트 강제 (PR #31)
- **공유 자원은 spec 단계 grep 선점** — `api/users.ts` 같은 공유 파일을 새로 만들면 병렬 FR끼리 add/add 머지 충돌이 난다. 공유 자원을 소비하는 기능은 spec 단계에 grep로 선점 FR을 확인하고, 먼저 머지된 정본(fetchUsers/useUsers 등)으로 통합 (PR #50/#51)
- **api 관례는 같은 BC 선례를 따른다** — api 모듈 관례(에러 처리, CSRF 헤더 수동 부착 여부 등)가 BC별로 다르다. 새 api 파일은 같은 BC의 기존 모듈을 grep해 복제 후 수정 — 다른 BC 패턴 이식 금지
- **error-key 매핑은 공유 util 경유** — 에러 코드→i18n 키 매핑을 화면마다 인라인으로 만들면 키 drift로 raw 코드가 노출되는 가짜 그린이 난다. 공유 util을 만들거나 기존 것을 재사용 (PR #106)

그 외 사고 이력 전체는 `Maxi_wiki/BTS/learnings.md` 참조 (inline 주입 대상 아님 — 필요 시 직접 Read 가능).

### 절대 금지

- `localStorage` 에 토큰(XSS) · CDN 임의 스크립트(`<script src="https://…">`)
- 인라인 스타일 · `DESIGN.md` 토큰 밖 임의 색상/간격 (확장은 `DESIGN.md` 패치로)
- `npm install <new-pkg>` 임의 도입 (Maxi 확인 필수 · pnpm 외 패키지 매니저 금지)
- 그 외 공통 금지(`any` · `!` · 빈 catch 등)의 정본은 `DEVELOPMENT.md` §1 절대 규칙 19개

### 참조 파일

- 먼저 볼 것 — `DEVELOPMENT.md` §1 · §2.2 · `Maxi_wiki/BTS/domain/<bc>.md`
- 필요 시 Read — `DESIGN.md`(헤딩 grep 후 부분 Read) · `docs/design/jira-parity-contract.md` · `docs/design/fr-ux-06-jira-redesign.md`
- 관련 SDD — `docs/sdd/21-frontend.md` · `docs/sdd/20-personalization.md`

## 보안 (인증·권한)
### 담당

- 인증 (LDAP / SAML / OIDC / Local / OAuth Pluggable Provider)
- 2FA (TOTP + 백업 코드, WebAuthn) · 세션/토큰 발급 (PAT, Refresh)
- 권한 평가 (프로젝트 행정 + 이슈 데이터 접근)
- CSRF, CORS, Spring Security 필터 체인

### 필수 체크리스트 (모든 변경)

1. **패스워드는 Argon2id** — `Argon2PasswordEncoder` (memory 65536, iterations 3, parallelism 1)
2. **토큰은 해시 저장** — `sha256(rawToken)`. raw token은 발급 응답에 한 번만
3. **외부 비밀값은 KMS 암호화** — Slack/OAuth client secret 등
4. **세션 토큰은 `sessionStorage` 또는 HttpOnly Cookie** — `localStorage` 절대 금지
5. **CSRF 검증 항상 활성화** — `@PostMapping`/`@PutMapping`/`@DeleteMapping`에서 우회 금지
6. **인증 가드 이중** — Spring Security 필터 체인 등록 + `@PreAuthorize`. 새 엔드포인트는 `SecurityConfig.kt` 의 `authorizeHttpRequests` 누락 여부를 **수동 확인**(Detekt 룰 미도입)
7. **2FA 강제 영역** — 관리자 + 민감 프로젝트 (FR-IS-19). 우회 코드 절대 금지
8. **로그에 PII/비밀값 마스킹** — Logback `${field:?MASK}` 패턴

### 절차

1. **기존 패턴 조사** — `backend/modules/identity-access/`에서 가까운 유사 코드 2-3개 Read. 프로젝트 규약 우선
2. **타입 우선** — Kotlin data class + 유효성 검증 (`@field:Valid`, `@field:Email`)
3. **테스트** — 인증 흐름은 통합 테스트 필수 (Testcontainers + 실제 PostgreSQL)
4. **마이그레이션** — 사용자/토큰 스키마 변경 시 Flyway + 아래 DB 절 체크리스트 동반
5. **감사 로그** — 로그인/로그아웃/권한 변경은 `audit_logs`에 append-only 기록

### 회귀 방지 (실제 사고 교훈 — 같은 실수 재발 금지)

- **시각 의존 로직은 Clock 주입** — 세션 만료·토큰 TTL 등 시각 비교를 핸들러에서 `Instant.now()`로 하드코딩하면 특정 날짜에 깨지는 time-bomb이 된다(AuthControllerTest 세션삭제 2건이 6/1에 실패). `Clock`을 주입(기본값 `Clock.systemUTC()`)하고 테스트는 `Clock.fixed`로 고정 (PR #52)
- **세션 self-service는 JWT 전용** — 사용자 본인 세션 관리(목록/폐기)는 JWT 인증만 허용하고 PAT(Personal Access Token)는 403으로 차단(Jira 방식). admin 세션관리·audit emit은 FR-AU-10 후속 (PR #37)
- **advisory lock TOCTOU** — 권한/멤버십 동시성 제어에 advisory lock을 쓸 때, lock 밖에서 읽은 값으로 판단하면 무력화된다. lock 후 재조회 필수. `pg_advisory_xact_lock`은 `(bigint,bigint)` 시그니처 없음 (FR-PM-01 PR #48, backend와 공유)
- **fail-open 금지 — 불명은 거부** — cross-BC 권한 resolver를 nullable 의존성 + `?: return`으로 처리하면 prod에서 빈 부재 시 전부 허용으로 떨어진다. 권한 판정 경로의 기본값·미주입·예외는 모두 "거부"로 수렴해야 한다 (FR-PM-07 PR #97/#100)
- **Guard 예외 message HTTP 누출** — 권한 Guard가 던지는 예외의 message가 그대로 HTTP 응답 detail로 노출되면 내부 사정(존재 여부/정책)이 샌다. 응답에는 일반 메시지로 치환 (FR-PM-04)
- **전역 스코프는 prod 하드 거부** — `IssueScope.Global`류 전체-노출 스코프는 prod 프로파일에서 무조건 거부. non-prod에서만 마스킹 허용. 코드 한 줄 실수의 폭발 반경이 전체 데이터
- **민감 작업은 step-up 재인증** — 계정 연결/해제 같은 민감 작업은 기존 세션 인증 위에 재인증(비밀번호 또는 SSO 재인증) 계층을 강제 (FR-AU-08 PR #103/#104)

그 외 사고 이력 전체는 `Maxi_wiki/BTS/learnings.md` 참조 (inline 주입 대상 아님 — 필요 시 직접 Read 가능).

### 절대 금지

- 검증되지 않은 입력으로 SQL/외부 명령 실행 (RCE) · `Connection.createStatement` 직접 사용
- 임시 백도어 / 디버그용 인증 우회 코드 커밋
- `try-catch` 후 `null` 반환으로 인증 실패 은폐 · 빈 catch (보안 이슈 silently swallow)
- 그 외 공통 금지(`@Transactional` 누락 · `println` 등)의 정본은 `DEVELOPMENT.md` §1.1 보안 6개 절대 규칙 + §1

### 참조 파일

- 먼저 볼 것 — `DEVELOPMENT.md` §1.1 · `DATA.md` §8(토큰/비밀값 저장) · `Maxi_wiki/BTS/domain/identity-access.md`
- 필요 시 Read — `docs/sdd/19-authentication.md` · `docs/sdd/12-permissions.md`

## DB (스키마·마이그레이션)
### 담당

- Flyway 마이그레이션 (`backend/modules/<bc>/src/main/resources/db/migration/V*__*.sql`)
- jOOQ 코드 생성 (`./gradlew generateJooq`) + `init_codegen.sql` 미러
- 인덱스 설계 (FK, FTS GIN, 복합 인덱스) · pgmq 큐 생성/삭제
- 데이터 이관 (Jira Import) · 백업/복구 절차

### 필수 체크리스트 (마이그레이션마다)

1. **Flyway 명명 + BC 번호 범위** — `V<번호>__<설명>.sql` (snake_case, 명령형). BC 별 번호 범위 정본은 `DATA.md` §4.1
2. **`TIMESTAMPTZ` 강제** — `TIMESTAMP` (without tz) 금지
3. **인덱스는 `CONCURRENTLY`** — 대형 테이블 락 회피
4. **`NOT NULL` 컬럼 추가** — default 또는 backfill 마이그레이션 동반 (V<N+1>). 큰 변경은 add → backfill → drop 3단계로 롤백 가능하게 설계
5. **소프트 삭제 컬럼** — 새 엔티티 테이블에 `deleted_at TIMESTAMPTZ NULL` 추가 (감사/세션/알림 제외)
6. **외래 키 인덱스** — PostgreSQL은 자동 안 함. 모든 FK에 명시적 인덱스
7. **`init_codegen.sql` 동반 수정** — 컬럼/테이블 추가는 여기에도 미러해야 jOOQ 상수가 생성된다 (§회귀 방지 1)
8. **마이그레이션 테스트** — Testcontainers로 전체 체인 실행 → 데이터 보존 검증. 테스트 없는 마이그레이션 커밋 금지
9. **이슈 키 영속성** — `issue_key_redirect (old_key, new_key)`는 절대 손대지 않음 (append-only 트리거로도 차단 · `DATA.md` §1.1). 큰 스키마 변경은 `docs/adr/` 에 ADR 동반

### 회귀 방지 (실제 사고 교훈 — 같은 실수 재발 금지)

- **init_codegen.sql 미러 필수** — BTS는 Flyway(PG16 런타임)와 jOOQ 코드젠을 분리한 구조라, 컬럼/테이블 추가 마이그레이션은 `init_codegen.sql`에도 **똑같이 미러**해야 jOOQ 상수가 생성된다. 빠뜨리면 repository가 컴파일조차 안 됨. V005가 선례 (PR #43 B3)
- **advisory lock 시그니처** — 동시성 제어에 advisory lock을 권할 때 `pg_advisory_xact_lock`은 `(bigint,bigint)` 시그니처가 없음(단일 bigint 또는 (int4,int4)만). lock 후 재조회(TOCTOU 방어) 필수 (PR #48)
- **V번호 동시 브랜치 충돌** — 병렬 PR이 각자 다음 V번호를 선점하면 머지 순서에 따라 Flyway checksum 충돌로 머지가 깨진다. 머지 직전 `origin/main`의 최신 V번호를 재확인하고 필요 시 리넘버 (사고 이력)
- **NULL 멱등성은 NULLS NOT DISTINCT** — PostgreSQL UNIQUE 제약은 NULL을 서로 다른 값으로 본다. NULL 포함 컬럼의 멱등 INSERT(ON CONFLICT)는 `UNIQUE NULLS NOT DISTINCT`로 선언해야 재실행 안전 (사고 이력)
- **조인 테이블 FK는 ON DELETE CASCADE 검토** — 다대다 조인 테이블의 FK에 CASCADE 누락 시 부모 삭제 후 고아 행이 남는다. Testcontainers cleanup도 깨짐. pre-existing 테이블은 main 실측 후 판단 (사고 이력)
- **권한코드 시드 ↔ 마이그레이션 테스트 카운트 결합** — 권한코드 시드 추가는 `PermissionSchemaMigrationTest` 같은 카운트 검증 테스트를 깬다. 시드 변경 시 카운트 가드 동반 수정 (FR-PM)

그 외 사고 이력 전체는 `Maxi_wiki/BTS/learnings.md` 참조 (inline 주입 대상 아님 — 필요 시 직접 Read 가능).

### 절대 금지

- `DELETE FROM ... WHERE 1=1` 또는 WHERE 없는 DELETE · `DROP TABLE` (ADR + Maxi 명시 승인 후만)
- 수동 `ALTER TABLE` (반드시 Flyway) · 같은 V<번호> 재사용 (checksum 충돌)
- `issue_key_redirect` 수정/삭제 · 마이그레이션에 비즈니스 로직 SQL 포함 (스키마 변경만)
- 그 외 공통 금지의 정본은 `DEVELOPMENT.md` §1.2 데이터 무결성 + §1 절대 규칙 19개

### 참조 파일

- 먼저 볼 것 — `DATA.md` 전체(특히 §1 · §2 · §4 · §7) · `DEVELOPMENT.md` §1.2
- 필요 시 Read — `docs/sdd/05-data-model.md` · `docs/sdd/15-migration.md`

## 테스트 인프라

- **E2E(Playwright)는 2026-10-20 부터 쓰지도 돌리지도 않는다.** `apps/web/e2e/` 는 동결이다. UI 는 브라우저 눈확인.
- 작업 중에는 대상 테스트만 돌린다. 범위는 `CLAUDE.md` §테스트.

### 회귀 방지 (실제 사고 교훈 — 같은 실수 재발 금지)

- **MSW mutation은 stateful 영속** — PATCH/POST 핸들러가 결과를 stateful 오버라이드에 영속하지 않으면, `invalidateQueries` refetch 후 화면이 옛 값으로 롤백된다(setQueryData 위에서만 통과하는 가짜 그린). 핸들러가 변경을 메모리에 보존해야 함 (FR-IS-02 D7)
- **MSW 파생동작은 공유 store 경유** — mutation의 파생 응답(다른 엔드포인트가 반환할 값)이 핸들러별 지역 상태에 분산되면 시나리오 간 drift가 난다. 브라우저에서 시드 가능한 공유 store에서 읽도록 통일 (FR-CM)

### 절대 금지

- 테스트 안에서 production DB 접근(모두 Testcontainers) · 인증 정보 하드코딩
- `Thread.sleep()` · 테스트 격리 깨기(다른 테스트 결과 의존)

# FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정) · 마스터 스펙

> plan. [docs/plans/2026-07-15-fr-at-07-pr-merge.md](../plans/2026-07-15-fr-at-07-pr-merge.md) — 도메인 사실 F1~F9 / 결정 D1~D14
> **2회차** (1회차 스펙은 Phase B 적대적 검토에서 BLOCKER 9건 → 재작성. 검토 원문은 §부록 A)
> **이 문서는 PR-A/B/C 3개를 관통하는 마스터 스펙이다.** 각 PR은 자기 §만 구현한다.

## PR 분할 (DEC-11, Maxi 확정)

Phase B 검토로 범위가 BC 4개 + 프론트 + 마이그레이션 4개 + 신규 포트 + enum 2종(파급 6파일 13지점) + 보안설정으로 불어남 → **3분할**. 직렬 진행(병렬 PR 충돌 이력 회피).

| PR | 범위 | 모듈 | 선행 | 이 문서의 § |
|---|---|---|---|---|
| **PR-A** | **slack** 인바운드 permitAll 중앙등록 + slack 동작 변수·암호화 키 배포 + prod 조립 HTTP 테스트 인프라 | identity-access · infra · app(test) | — | **§A** |
| **PR-B** | `IssueMutationPort.setFixVersions` + issue-tracking 어댑터 + `ActionType.SET_FIX_VERSIONS` + 프론트 계약 | shared-kernel · issue-tracking · automation · apps/web | — | §B |
| **PR-C** | `TriggerType.PR_MERGED` + Git webhook 엔드포인트 + 서명검증 + 이슈키 추출 + 등록 API | automation · apps/web | A, B | §C |

**PR-A를 먼저 두는 이유**. (1) 기존 부채라 FR-AT-07과 독립 — 지금 FR-AT-01·FR-SL이 **prod에서 사문화**돼 있다 (2) PR-C의 엔드포인트가 동작하려면 필수 (3) "prod에서 401이 아님"을 검증할 **테스트 인프라 자체가 없어서**(부록 A C-j) 그 인프라 구축이 PR-A의 산출물이며 PR-C가 재사용
**PR-B가 C보다 먼저인 이유**. C의 룰이 붙일 액션이 B의 산출물. B는 단독으로도 유효(스케줄·이슈이벤트 룰로 Fix Version 설정 가능).

---

# §A. PR-A — 인바운드 웹훅 prod 도달 가능화 (이번 PR)

## A-0. 문제 (사실)

중앙 `SecurityConfig`(identity-access)가 `anyRequest().authenticated()`(`:186`)로 닫혀 있고, 인바운드 웹훅 경로가 permitAll 목록에 **없다**. POST는 CSRF 필터가 먼저 403. 각 BC는 **테스트 전용** 필터체인으로 자기 경계를 검증해 **초록불**이라 여태 안 드러남.

- **알려진 부채**. `docs/plans/2026-07-11-automation-prod-assembly.md:53` — *"`AutomationWebhookController`가 조립되나 중앙 SecurityConfig 화이트리스트 미포함 → prod 401(FR-AT-01 WEBHOOK 트리거 **사문화**). slack `/slack/events`도 동일 미등록·후속 추적 중."* / `:165` — *"인바운드 permitAll 중앙등록은 BTS의 알려진 BC별 배포-시점 후속 패턴"*
- **확장 포인트 없음**. 경로는 `SecurityConfig` companion object 리터럴 하드코딩(`:209-260`). `PathContributor` 류 grep 0건 → **이 구조가 부채의 원인**

## A-1. 사용자 시나리오

```
S-A1. Given prod 프로파일로 앱이 조립 부팅됐다
      When 외부 Git/Slack 서버가 인바운드 경로로 POST 한다
      Then 401/403이 아니라 각 컨트롤러에 도달한다 (서명 검증이 실제로 실행된다)

S-A2. Given 잘못된 서명의 요청이다
      When 인바운드 경로로 POST 한다
      Then 컨트롤러의 서명 검증이 401을 준다 (permitAll이 인증을 없앤 게 아니라 검증 주체를 옮긴 것)

S-A3. Given /slack/install (관리자 전용, authenticated + admin 이중가드)
      When 익명으로 요청한다
      Then 여전히 401 (permitAll 범위가 새지 않았다)

S-A4. Given 암호화 키 환경변수가 배포 매니페스트에 선언돼 있다
      When 운영자가 .env.prod.example 대로 설정한다
      Then slack·MFA·OIDC·automation 기능이 첫 호출에서 500이 나지 않는다
```

## A-2. 기능 요구사항

| ID | 요구사항 |
|---|---|
| **FR-A1** | 중앙 `SecurityConfig`에 인바운드 경로를 **경로 × 메서드 표대로** permitAll 등록 (A-3) |
| **FR-A2** | **동일 경로를 CSRF ignore에도 등록**. ★ permitAll(`:151-184`)과 CSRF-ignore(`:133-146`)는 **각각 다른 블록** — FR-MF-01에서 한쪽만 등록해 실제 BLOCKER 발생 이력(KDoc `:86,139,228`) |
| **FR-A3** | 매처는 **최소 범위**. 메서드 고정 + **단일 세그먼트/정확 경로**. `/**` 하위 와일드카드 **금지** (notification 선례 `:175-180` — GET 고정 + `/*` 2겹 방어) |
| **FR-A4** | `infra/prod/.env.prod.example`에 **slack 동작 변수 + 암호화 키 3종** 선언 (DEC-14 + B-1) |
| **FR-A5** | **prod 조립 HTTP 테스트 인프라** 신규 — permitAll 경로가 조립 컨텍스트에서 실제로 필터를 통과함을 **양성 단언**으로 검증 (A-5) |
| ~~FR-A6~~ | ~~회귀 가드~~ → **DEC-16으로 대체**. 열거식 가드는 미래 경로를 원리적으로 못 잡음 → **공유 리스트 구조로 이중등록을 컴파일 단위에서 불가능화** (A-3) |

## A-3. 경로 × 메서드 표 (FR-A1 — BLOCKER B5 해소 · **DEC-15 반영**)

> 1회차 스펙은 "slack 인바운드"라고만 적어 **실체 4개**를 열거하지 않았다. D2의 "메서드 고정(POST)" 원칙만 적용하면 `GET /slack/install/callback`이 누락돼 **slack 설치 플로우가 계속 401**이고, `/slack/**`로 열면 `/slack/install`(admin 이중가드)이 **익명 노출**된다.
>
> **DEC-15 — PR-A는 slack만.** automation 웹훅은 미방어 `issueKey`(`AutomationWebhookController:97`) 때문에 방어심층(FR-7)이 함께 들어오는 **PR-C**로 이관. ★ 부수 효과로 **C-4 divergence도 소멸** — slack test config는 이미 정확 경로라 중앙 등록과 일치(automation만 `/**` 와일드카드였다).

| 경로 | 메서드 | 매처 형태 | 출처 (test 전용 → 중앙 이관) |
|---|---|---|---|
| `/slack/events` | **POST** | 정확 경로 | `SlackTestSecurityConfig.kt:65` |
| `/slack/commands` | **POST** | 정확 경로 | `:66` |
| `/slack/interactions` | **POST** | 정확 경로 | `:67` |
| `/slack/install/callback` | **GET** | 정확 경로 | `:64` |

**★ 공유 리스트로 등록 (DEC-16)**. 위 4행을 `List<Pair<HttpMethod, String>>` **하나**로 두고 CSRF-ignore(`:133-146`)와 authorize(`:151-184`)를 **같은 리스트에서 구동** → 한쪽만 등록이 **컴파일 단위에서 불가능**. 열거식 가드 테스트는 미래 경로를 원리적으로 못 잡고 `private companion object`(`:209`) 때문에 테스트가 경로 리터럴을 복제해 drift한다.

**PR-C에서 추가**. `/api/v1/automation/webhooks/*` (POST, 단일 세그먼트 — test 원본 `/**`에서 좁힘) · `/api/v1/webhooks/git/*` (POST).

**열지 않는 것 (명시)**. `/slack/install` — `authenticated()` + admin fail-closed 이중가드 유지(`SlackTestSecurityConfig.kt:44-45`).

## A-4. 배포 변수 (FR-A4 — BLOCKER B4 + **B-1** 해소)

`grep -rn "SLACK" infra/` → `nginx.conf:40` **한 줄뿐**. 암호화 키 3종도, **slack 동작 변수도 전부 없다**. [[use-time-validated-env-passes-boot-fails-on-use]]에 기록된 **실사고 그 자체**(health 통과 후 기능 첫 호출 500).

> **★ B-1 (2회차 리뷰).** 1회차 §A-4는 **암호화 키 3종만** 적었다. 그러나 **`BTS_SLACK_SIGNING_SECRET`이 없으면** `SlackSignatureVerifier.kt:64-66`(`if (signingSecret.isBlank()) return false`)이 fail-closed로 전부 막아 **permitAll을 열어도 "필터의 401"이 "컨트롤러의 401"로 바뀔 뿐 기능 변화 0**이다. slack **암호화** 키(봇 토큰)는 서명 통과 **후** 하류라 순서가 뒤집혀 있었다. → **PR-A의 "FR-SL 되살아남" 주장이 거짓이 될 뻔했다.**

| 환경변수 | 프로퍼티 | 없으면 |
|---|---|---|
| `BTS_SLACK_SIGNING_SECRET` | `bts.slack.signing-secret` | **서명 검증 전부 401** ← B-1 핵심 |
| `BTS_SLACK_CLIENT_ID` / `_SECRET` / `_REDIRECT_URI` | `bts.slack.{client-id,client-secret,redirect-uri}` | `/slack/install/callback` 동작 불가 |
| `BTS_SLACK_STATE_KEY` | `bts.slack.state-key` | OAuth state 서명 불가 (`SlackOAuthStateSigner.kt:58` — **impl에서 실재 확인 후 반영**) |
| `BTS_SLACK_ENCRYPTION_KEY` / `_SALT` | `bts.slack-encryption.{key,salt}` | 봇 토큰 복호화 500 |
| `BTS_MFA_ENCRYPTION_KEY` / `_SALT` | (MfaEncryptionConfig) | MFA 첫 호출 500 — **실사고 재발분** |
| `BTS_OIDC_ENCRYPTION_KEY` / `_SALT` | (OidcEncryptionConfig) | OIDC 첫 호출 500 — 동일 |
| `BTS_AUTOMATION_ENCRYPTION_KEY` / `_SALT` | `bts.automation-encryption.{key,salt}` | **PR-C에서 추가** (그 때 빈도 신설) |

- **salt는 hex** (C9 — `SecretEncryptor` 계약)
- 생성 방법 주석 병기 (`openssl rand -hex 32` 등)
- **DEC-14 — surgical changes 예외**. 본 FR 범위 밖이나 (a) 같은 종류의 사고가 이미 2번 터졌고 (b) 문서 몇 줄이라 비용 ≈ 0 (c) PR-A 자체가 "인바운드가 prod에서 실제로 도는가"를 다루는 PR이라 주제 정합. Maxi 확정

## A-5. 테스트 인프라 (FR-A5 — CONCERN C-j 해소)

**기존 인프라로는 이 PR의 완료 기준을 검증할 수 없다.** 유일한 9-BC prod 조립 테스트 `BtsApplicationContextTest`는 `@SpringBootTest` **기본(MOCK) 웹환경** → 실 HTTP 불가, MockMvc autowire 안 됨, KDoc상 **"dev postgres 수동 기동"** 전제(Testcontainers 미관리).

- **실현 가능성 확인**(N-1). `BtsApplicationContextTest`가 **prod 프로파일로 지금 통과 중**이고 `@DynamicPropertySource props`가 주입하는 건 issuer-uri + PEM **2개뿐**(`:96-97`). **암호화 키 미설정으로 prod 부팅이 된다는 걸 이 테스트의 존재가 이미 증명**한다(= [[use-time-validated-env-passes-boot-fails-on-use]]의 요지). MOCK→`RANDOM_PORT`는 **실 Tomcat 바인딩만 추가**
- → **별도 태스크(T2)**. 이 인프라는 PR-C가 그대로 재사용
- **★ C-5 — 기존 `BtsApplicationContextTest`를 이 베이스 상속으로 전환한다.** 안 하면 `webEnvironment` 차이로 `MergedContextConfiguration` 키가 달라 **컨텍스트 캐시 미공유** → 같은 JVM에 9-BC prod 컨텍스트 **2벌**(부팅 2회 + `@Scheduled` 워커 2벌이 동일 5433 pgmq 큐 동시 폴링)

### ★ 검증 방식 = 양성 단언 (B-2 — 1회차의 "401이 아님" 폐기)

`SlackSignatureVerifier`는 **결정론적** HMAC-SHA256(`v0:{ts}:{rawBody}`)이고 secret은 프로퍼티 주입이다 → **테스트가 유효 서명을 직접 계산**할 수 있다.

```
T2 베이스가 bts.slack.signing-secret 을 알려진 테스트 값으로 주입
  → 테스트가 v0:{ts}:{body} 로 유효 서명 계산
  → POST /slack/events  {"type":"url_verification","challenge":"abc123"}
  → 200 + challenge "abc123" 에코 단언   (SlackEventsController:88-91)
```
- **필터 통과 + 서명 검증 동작이 한 번에 양성 증명**된다. 응답 포렌식(`WWW-Authenticate` 유무 — 저장소 선례 **0건**) 불요
- **우리 코드에만 의존** — Spring Security 내부(엔트리포인트 등록·content negotiation)에 의존하지 않음
- → **R5(필터 401 ↔ 컨트롤러 401 구분 곤란) 소멸**

## A-6. 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| EC-A1 | **유효 서명** `url_verification` | **200 + challenge 에코** ← 필터 통과 양성 증명 (S-A1) |
| EC-A2 | **무효 서명** 동일 요청 | **컨트롤러가** 401 (`SlackEventsController:74-77`) — 검증 주체 이동 실증 (S-A2) |
| EC-A3 | `POST /slack/install` 익명 | **401 유지** (범위 누출 0, admin 이중가드) (S-A3) |
| EC-A4 | permitAll 등록했으나 CSRF ignore 누락 | POST가 403 → **DEC-16 공유 리스트로 구조적 불가능**(가드 테스트 불요) |
| EC-A5 | `/slack/commands` · `/slack/interactions` · `/slack/install/callback` | **각각 개별 단언** (단수로 뭉뚱그리면 매처 오타를 못 잡음) |

## A-7. 제약

| # | 제약 |
|---|---|
| **C-A1** | **BC 격리 예외** — identity-access(`SecurityConfig`)를 slack 사유로 수정. plan §리스크 명시 + **security-engineer 공동 검토 필수**. ★ FR-SL-01 ADR **§D7이 이 후속을 예고**해 둠(*"중앙 SecurityConfig에 콜백 permitAll 추가는 **DEVELOPMENT.md §1.4 예외로 ADR/게이트 승인이 필요한 후속 작업**"*) |
| ~~C-A2~~ | ~~prod 신규 노출 리스크~~ → **DEC-15로 해소**. automation 웹훅(무검증 `issueKey` enqueue `AutomationWebhookController:97` · actor 임의 지정)은 방어심층이 동반되는 **PR-C**로 이관. **slack은 방어 온전**(서명+replay+상수시간+fail-closed) |
| **C-A3** | rate limit 부재 — permitAll 경로에 미인증 요청 강제 가능. **기존 부채**(신규 아님)이나 경로를 여는 PR이 **명시는 해야 함** → §후속 |
| **C-A6** | **★ 절대 규칙 §1.4 정면 대상** — "인증 없는 엔드포인트 추가 금지". 정식 예외 절차 = **ADR + 게이트1 승인**(2026-07-15 Maxi 승인 완료). 표기는 저장소 관례 `§1.<규칙번호>` = **`§1.4`**(9파일 14곳과 일관, DEC-17) |
| **C-A4** | 매처 문법 주의 — Spring Security의 `/*`는 단일 세그먼트, `/**`는 하위 전체. notification 선례(`:248` `PUBLIC_DASHBOARDS_PATH = "/api/v1/public/dashboards/*"`) 대조 |
| **C-A5** | 신규 의존성 0 |

## A-8. 측정 가능한 완료 기준 (PR-A)

- [ ] **S-A1 양성 단언** — 유효 서명 `url_verification` → **200 + challenge 에코** (필터 통과 + 서명 검증 동작 동시 증명)
- [ ] **S-A2** — 무효 서명 → **컨트롤러가** 401 (검증 주체가 필터에서 컨트롤러로 옮겨졌음을 실증)
- [ ] **S-A3 / EC-A3** — `GET /slack/install` 익명 **401 유지** (범위 누출 0). ★ 일부러 위반을 넣어 fail을 잡는지 확인([[archunit-vacuous-rule-silent-pass]] — 통과가 검증을 의미하지 않음)
  > **⚠️ 2026-07-15 구현 중 2건 정정 (T3).**
  > 1. **메서드는 GET이다** — 위 원문의 `POST`는 틀렸다. 실제 매핑은 `@GetMapping("/slack/install")`(`SlackInstallController.kt:60`). POST로 검증하면 CSRF가 permitAll 여부와 **무관하게** 항상 거부해 누출을 원리적으로 못 잡는다.
  > 2. **상태코드 단언만으로는 vacuous** — `/slack/install`을 목록에 **일부러 넣어보니** permitAll이 새어도 컨트롤러의 `SlackActorExtractor`가 401을 던져 **상태는 그대로 401**이었다. 판별자는 **응답 본문**뿐이다(필터 401=빈 본문 vs 컨트롤러=`SLACK_UNAUTHENTICATED` ProblemDetail). 본문 단언으로 교체함.
- [ ] **slack 4경로 각각** 개별 검증 (부록 A C-9 — 단수로 뭉뚱그리면 매처 오타를 못 잡음)
- [ ] FR-A5 prod 조립 HTTP 테스트 인프라 신규 구축 + **기존 `BtsApplicationContextTest` 상속 전환**(C-5 — prod 컨텍스트 1벌)
- [ ] `.env.prod.example` — slack 동작 변수 + 암호화 키 3종(+PR-C에서 automation) + 생성법 주석
- [ ] **DEC-16 구조 확인** — permitAll·CSRF-ignore가 **단일 공유 리스트**에서 구동되는가(한쪽만 등록이 컴파일 단위에서 불가능한가)
- [ ] `:modules:app:test` 통과 (9 BC prod 조립)
- [ ] 기존 slack·identity-access·automation 테스트 회귀 0
- [ ] ktlint + detekt 0
- [ ] `bash scripts/verify-master-plan.sh` 통과
- [ ] **FR 카운트 불변 123** (PR-A는 FR 자체를 완료시키지 않음 — 부채 청산)

## A-9. 후속 (PR-A 범위 밖)

- **permitAll 확장 포인트 도입** — BC별 경로 등록 인터페이스. 부재가 이 부채의 **구조적 원인**(§A-0). 도입 시 조립만으로 자동 반영
- 인바운드 경로 rate limit (C-A3)
- `AutomationWebhookController`의 무검증 `issueKey` enqueue (C-A2) → PR-C에서 방어심층
- actor 임의 지정 (부록 A C-e) → 별도 FR 후보

---

# §B. PR-B — Fix Version 설정 통로 (후속 PR)

> 요약만. 착수 시 `/bts`로 별도 plan/spec 상세화.

## B-1. 범위

| ID | 요구사항 |
|---|---|
| FR-B1 | `IssueMutationPort.setFixVersions(SetFixVersionsCommand)` — default 없음(fail-closed) |
| FR-B2 | issue-tracking 어댑터 구현 — `changeFixVersions` 유스케이스 위임 |
| FR-B3 | `ActionType.SET_FIX_VERSIONS` + config `{versionIds:[UUID...]}` (빈 배열 = 전체 해제) |
| FR-B4 | 프론트 계약 동기화 — `actionTypeSchema` z.enum + 라벨 맵 |

## B-2. ★ `expectedVersion` 없음 (BLOCKER B6 해소)

```kotlin
data class SetFixVersionsCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val versionIds: List<UUID>,   // 전체교체 시맨틱
    val dryRun: Boolean,
)
```

**1회차 스펙의 `expectedVersion: Long?`는 삭제.** plan D3의 근거(*"setField에 리스트를 숨기면 OCC 파라미터가 사라진다"*)가 **코드로 반증됨** — `SetFieldCommand`/`AssignCommand`(`IssueMutationCommands.kt:34-60`)는 **애초에 OCC 파라미터가 없고**, 어댑터가 매 시도마다 자기 트랜잭션 안에서 `findByKey().version`을 재조회해 채운다(`AutomationIssueMutationAdapter.kt:100-105,118-124`) + `runWithOccRetry`(`:162-172`) 1회 재시도. **호출자는 OCC를 알 필요가 없다.**
게다가 `ActionExecutor`는 `IssueSnapshot`(9필드, `version` 없음)에서 값을 얻어 **유효한 expectedVersion을 조달할 경로가 없다** → 항상 null인 죽은 분기 → 훗날 "값 있으니 재조회 생략" 구현 시 **진짜 TOCTOU**.

**D3의 결론(전용 포트 메서드)은 유지.** 정당한 근거는 **전체교체 시맨틱 + 복수 versionId를 타입으로 드러냄**(`value: String?` JSON 인코딩에 리스트를 숨기면 "필드 하나에 값 하나"가 깨짐) + fixVersions가 `updateIssue`가 아닌 **별도 서비스 메서드** 경로라는 구조적 사실(F3).

## B-3. ★ `ActionType` 추가 파급 — 6파일 ~13지점 (BLOCKER B7 해소)

`Action`은 **sealed class** → exhaustive `when` 전수 갱신 필요.

| 파일 | 지점 |
|---|---|
| `Action.kt` | sealed subclass 신설 + `fromJson`의 `when(actionType)` |
| `AutomationActionRepository.kt` | `:113-116`, `:131-143` — DB 저장/복원 매핑 2곳 |
| `AutomationRuleResponses.kt` | `:190-193`, `:199-202` — REST 응답 DTO 매핑 2곳 |
| `RuleConflictAnalyzer.kt` | `:103-106`(CYCLE) · `:414-417`(권한요구) · `:426-429`(라벨) · **`:317-319` `hasObservableSideEffect`** |
| `ActionExecutor.kt` | `:199-213`(디스패치) · `:289-292`(타입 매핑) |
| `AutomationYamlCodec.kt` | `:245-248`, `:258-265` — YAML GitOps(FR-AT-06 승계) |

**★ `hasObservableSideEffect:317-319`는 `it is X || it is Y` boolean 체인 — 컴파일러가 강제하지 않는다.** 누락해도 **컴파일 통과**하고 PRIORITY_AMBIGUITY 충돌 탐지가 **조용히 SET_FIX_VERSIONS를 무시**한다 → 회귀 테스트 필수([[archunit-vacuous-rule-silent-pass]] 동종 — 통과가 검증을 의미하지 않음).

## B-4. 기타 반영

- 프론트 `actionTypeSchema`(`automation-rules.types.ts:24`) + 테스트 목록(`:273`) + 라벨 맵. **백엔드만 추가하면 해당 룰 조회 시 Zod parse 실패로 룰 목록 화면 전체가 깨짐**
- `ActionTest.kt:18,22` `entries.size shouldBe 4` → 5
- `automation_actions.action_type` CHECK 갱신 — **V302 편집 금지**(체크섬 드리프트), 신규 파일
- EC — 타 프로젝트/삭제 버전 → `validateVersions`가 차단, **예외 타입 그대로 전파 → `rule_executions` FAILED**. ★ **"422" 언급 금지**(부록 A C-i) — 422는 `IssueController` 동기 REST 매핑 전용이고 automation은 pgmq 워커 비동기라 HTTP 응답 자체가 없음

---

# §C. PR-C — Git webhook + PR_MERGED 트리거 (후속 PR)

> 요약만. 착수 시 `/bts`로 별도 plan/spec 상세화. **선행 = PR-A, PR-B.**

## C-1. ★ 신뢰 경계 (BLOCKER B2 전제 — 이 문단이 없으면 아래 상한들이 과잉방어로 오해됨)

> **PR 제목·본문은 신뢰할 수 없는 외부 입력이다.** HMAC 서명이 증명하는 것은 **"GitHub이 보냈다"** 이지 **"내용이 믿을 만하다"** 가 아니다. PR은 BTS 계정이 없는 외부 기여자도 열 수 있고, 그 사람이 제목·본문을 자유롭게 쓴다. 따라서 **추출 결과는 전부 상한·검증 대상**이다.

## C-2. 핵심 요구사항

| ID | 요구사항 |
|---|---|
| FR-C1 | `POST /api/v1/webhooks/git/{token}` — permitAll(PR-A 인프라 재사용) + CSRF ignore |
| FR-C2 | 토큰 SHA-256 조회 → 등록행(provider·projectKey·secret) |
| FR-C3 | **서명 검증은 등록행 `provider`로만 분기** (C-3) |
| FR-C4 | 머지 이벤트만 처리 (GITHUB `pull_request`+`action=closed`+`merged=true` / GITLAB `Merge Request Hook`+`action=merge`) |
| FR-C5 | **targetBranch 필터** (C-5) |
| FR-C6 | 이슈 키 추출 — PR 제목+본문, `Closes/Fixes/Resolves` 계열 키워드 필수, 이슈키 대문자 고정 |
| FR-C7 | 프로젝트 스코프 필터 — prefix ≠ 등록 project_key인 키 무시 |
| FR-C8 | **팬아웃 상한** (C-4) |
| FR-C9 | 배달 dedup — **replay 방어 아님** (C-6) |
| FR-C10 | 등록 API `POST/GET/DELETE /api/v1/projects/{projectKey}/automation/git-webhooks`, 권한 `MANAGE_AUTOMATION` |
| FR-C11 | `TriggerType.PR_MERGED` + 프론트 `triggerTypeSchema` 동기화 |
| FR-C12 | `automationSecretEncryptor` 빈 + `.env.prod.example` 4번째 키 |

## C-3. ★ raw body — `@RequestBody String` 금지 (BLOCKER B1 해소)

**1회차 스펙의 C1(`@RequestBody String`만 사용)과 NFR-2(크기 상한이 서명 검증 이전)는 양립 불가능했다.** `@RequestBody String`이면 Spring이 **컨트롤러 진입 전 본문 전체를 힙에 버퍼링**한다 → 메서드 안 검사는 전부 버퍼링 이후. `infra/prod/nginx.conf:16` `client_max_body_size 110m` + `mem_limit: 1536m` → **미인증 permitAll 경로로 110MB 힙 적재**.

**1회차가 인용한 FR-AT-01 선례는 정반대였다** — `AutomationWebhookController.kt:110-116`은 `@RequestBody`를 **의도적으로 쓰지 않고**:
```kotlin
private fun readBoundedBody(request: HttpServletRequest): ByteArray {
    val bytes = request.inputStream.readNBytes(MAX_PAYLOAD_BYTES + 1)
    if (bytes.size > MAX_PAYLOAD_BYTES) throw AutomationWebhookPayloadTooLargeException()
    return bytes
}
```
1회차는 **값(256KB)만 FR-AT-01에서, 형태는 slack에서** 가져와 slack의 약한 가드를 상속했다(slack도 같은 문제 보유 — `SlackCommandsController.kt:73-78`).

**확정**.
- `HttpServletRequest.inputStream.readNBytes(MAX+1)` → 초과 시 **413** (`AutomationWebhookController:110` 동형)
- **HMAC은 raw 바이트에 직접** — String 왕복 없음. `@RequestBody String`은 `server.servlet.encoding.charset` 의존이고 잘못된 UTF-8 바이트의 decode→re-encode가 **손실적이라 서명이 깨짐**
- **`@RequestParam`/`@ModelAttribute` 병용 절대 금지** (원 함정 유지 — form 파싱이 스트림 소비)
- `consumes = APPLICATION_JSON_VALUE` — GitHub UI의 form-urlencoded 옵션을 **415로 명시 거부**(부록 A NIT). 미지정 시 `readTree` 실패 → 202 조용히 무시 → 운영자가 "202인데 아무 일 없음"을 디버깅
- **완료 기준 재작성** — 1회차의 "413이 서명검증 이전(실서블릿)"은 `@RequestBody String`으로도 **통과하는 가짜 그린**. → **"컨트롤러가 `@RequestBody`를 쓰지 않는다"를 구조적으로 단언** + 대용량 요청 시 힙 미증가 검증

## C-4. ★ 팬아웃 상한 (BLOCKER B2 해소)

```
256KB ÷ ~12B("Closes P-1 ") ≈ 20,000 distinct 이슈키 × 룰 N개
→ 동기 enqueue 20,000·N
→ 워커 배수량 10 msg/s (BATCH_SIZE=5 / poll 500ms) = 약 33분 전 프로젝트 automation 정지
→ 억제창 60초는 키가 전부 distinct라 무력
→ rule_executions.trigger_event JSONB가 실행마다 PR body 영속 = 요청 1건 ≈ 5GB
```

**확정**.
- **추출 이슈키 distinct 상한 = 20**. 초과 시 **202 + WARN, 처리 0건**(fail-closed — 일부만 처리하면 어느 게 처리됐는지 비결정적)
- **triggerEvent의 `title`/`body` 길이 절단** (각 2KB). 팬아웃 시 N배 복제되므로 필수
- 룰 수 × 키 수 곱의 상한도 명시

## C-5. ★ targetBranch 필터 (BLOCKER B8 해소 — DEC-12)

`Condition.FIELD_WHITELIST`(issue.* 9종)에 PR 메타가 없고 `findEnabledByProjectAndTriggerType(projectKey, PR_MERGED)`에 브랜치 축이 없어, `release/1.2→1.2.0`·`release/2.0→2.0.0` 두 룰이 있으면 **어느 브랜치로 머지되든 둘 다 발화**해 같은 이슈에 두 버전이 동시에 박힌다.

**확정**. `trigger_config`에 `targetBranch` 필터 추가 — **기존 `ISSUE_UPDATED`의 `fields` 필터와 동형**(`TriggerConfig.kt` 타입별 파싱이 이미 존재, `AutomationEventWorker:142-145`가 교집합 필터 선례). 구조 추가 없음(JSONB).
- 미지정 = 전 브랜치 (하위호환)
- 매칭은 정확 일치 (glob/정규식은 범위 밖 — 필요 시 후속)

## C-6. ★ dedup은 replay 방어가 아니다 (BLOCKER B3 해소)

**GitHub HMAC은 본문만 서명한다** — `X-GitHub-Delivery`는 **서명 대상 밖**. 유효 (body, signature) 1쌍을 캡처하면 **delivery UUID만 갈아끼워 무한 재전송**해도 서명은 통과하고 dedup 키는 매번 신규다. **GITLAB은 평문 토큰이 본문과 무관해 본문 무결성이 0** → rawBody SHA fallback도 무의미.

**확정**.
- S6/EC11 문구를 **"정직한 재시도에 대한 배달 dedup(at-most-once)"** 으로 정정. **"replay 방어" 주장 삭제**
- GitHub 웹훅은 **구조적으로 replay 방어가 불가능**(서명에 timestamp 없음 — slack의 ±300초 윈도우에 대응하는 게 없음). **잔여 위험으로 ADR 명시**
- 완화는 (a) 멱등 처리(Fix Version 전체교체) (b) **팬아웃 상한**(C-4) (c) rate limit(후속)에 의존
- **DEC-13 — GitLab 유지 + 잔여위험 ADR 명시**. GitLab 웹훅은 원래 `X-Gitlab-Token` 평문이고 GitLab이 HMAC 서명을 제공하지 않아 **우리가 더 강하게 만들 수 없다**. product doc D2 준수. 단 **GITHUB/GITLAB을 동급으로 서술하지 않는다** — 보안등급 차이를 ADR·KDoc에 명시

## C-7. 그 외 확정 (부록 A 반영)

| 항목 | 확정 |
|---|---|
| provider 분기 (C-a) | **등록행 `provider`로만** 분기. 헤더로 추론 금지. 기대 헤더 없으면 즉시 401, **다른 provider 방식 폴백 금지**. GitHub 레거시 `X-Hub-Signature`(SHA-1) 미사용. 교차 헤더 EC 2건 추가 |
| 404/401 (C-b) | **401로 통일**. 서명 실패 401 분기가 생기면 "404=미존재 / 401=존재+서명틀림" 오라클이 됨. FR-AT-01은 **서명이 없어 401 분기 자체가 없었으므로 선례가 아님** |
| FR-7 하류 (C-d) | `ActionExecutor.extractIssueKey:264-268`이 `triggerEvent.issueKey`를 **무검증 신뢰** → 워커에 **방어심층** 추가(룰 projectKey ≠ 이슈키 prefix → SKIPPED) |
| 복호화 실패 (부록 A 8) | EC 분리 — **등록 시 키 미설정 = 500** / **검증 시 복호화 실패 = 401 + ERROR 로그**(등록 id·projectKey만, 평문/키 금지) + 메트릭. **서명 불일치와 반드시 구분** — 아니면 키 유실이 조용한 401로 은폐돼 운영자가 "GitHub 설정이 틀렸나"를 몇 시간 뒤짐 |
| 부분 팬아웃 실패 (C-f) | dedup 커밋 후 N건 중 일부 enqueue 실패 시 **영구 유실**(재전송도 dedup에 막힘). EC 명시 + 트레이드오프 기술 |
| 마이그레이션 (C-g) | **V306~V309 분할** — 모듈 V300~V305가 예외 없이 **1파일=1스키마 변경**. `DATA.md §4`("큰 변경 분할")과도 정합. ★ 1회차는 **V306을 4번 표기**(Flyway 버전 중복 = 부팅 실패) |
| 음성 테스트 (C-h) | `SchemaMigrationTest.kt:559-562`가 `insertRule("PR_MERGED")`를 **CHECK 위반 프로브**로 사용 중 → 프로브 값을 다른 무효값으로 교체 |
| NFR-1 검증 (B9) | 완료 기준에 **명시 항목 추가** — 동기 경로 DB 왕복 상한 + p95 측정. 1회차는 200ms를 하드 넘버로 적고 검증 항목 **0건** |
| buildContext (C-k) | PR_MERGED + ADD_COMMENT 조합 시 `{{issue.title}}`이 **PR 제목**으로 렌더(`:271-284`가 중첩 `issue` 없으면 triggerEvent 전체를 issue로 취급). **후속 한계로 명시** |
| init_codegen (NIT) | automation은 JdbcTemplate → **면제**(jOOQ 4모듈에 automation 없음, 확인 완료). 면제 사실 명시 |
| 식별자 (NIT) | 결정은 **`DEC-n`**, product doc 단계는 **`D1~D7`** 로 분리. 1회차는 D7이 두 뜻이었음 |

---

## 부록 A. Phase B 적대적 검토 (1회차, BLOCKER 9 / CONCERN 11)

security-engineer + backend-engineer 병렬, **실제 코드 대조**. 인용 파일:줄 10여 개 전부 실재·정확 확인 — 문제는 인용이 아니라 **설계**.

> 검토 방식 deviation. 스킬 Phase B는 `superpowers:brainstorming` 호출을 지시하나 그 스킬은 "구현 전 사용자 의도 대화 탐색" 도구로 "작성된 스펙의 gap 발견"과 목적이 다름([[bts-spec-office-hours-mismatch]]와 동종). Phase B의 **명시된 목적**을 적대적 에이전트 검토로 달성.

| # | BLOCKER | 해소 |
|---|---|---|
| B1 | `@RequestBody String`이 크기검사 전 버퍼링 → 미인증 110MB 힙. 완료기준이 통과시킴(가짜 그린) | §C-3 |
| B2 | 팬아웃 무제한 → 20k×N enqueue, ~33분 정지, ~5GB 저장 | §C-1, C-4 |
| B3 | dedup 키가 서명 밖 헤더 → "replay 방어" 주장이 거짓 | §C-6 |
| B4 | `.env.prod.example`에 암호화 키 3종 부재 → prod 미동작 확정(실사고 재발) | §A-4 |
| B5 | FR-14 경로 미열거 → `/slack/**`면 admin 익명노출, POST만이면 설치 401 | §A-3 |
| B6 | `expectedVersion` — plan D3 근거가 코드로 반증. 죽은 분기 + 미래 TOCTOU | §B-2 |
| B7 | `SET_FIX_VERSIONS` 파급 6파일 13지점 누락, 특히 컴파일러 미강제 `hasObservableSideEffect` | §B-3 |
| B8 | targetBranch 스코프 부재 → 다중 릴리스에서 제품의미 붕괴 | §C-5 |
| B9 | NFR-1(200ms) 검증 방법 0 | §C-7 |

**CONCERN 11 / NIT 7** — C-a~C-k는 §C-7·§A-7에 개별 반영. 상세 원문은 커밋 `490514186` 참조.

### 검토가 확인한 "맞는 부분"

provider를 등록행에서 읽는 설계(혼동 공격 차단) · FR-7 프로젝트 스코프 필터의 존재 · `setFixVersions` 전용 포트 + default 없음(fail-closed) · actor 권한 fail-closed(`IssueApplicationService.kt:949` `assertPermission` 선행 — 코드 확인) · `validateVersions`(`:952`)의 타 프로젝트 버전 차단 · raw body 함정 인지 · at-most-once 판단.

## Brainstorming Check

✅ **통과 (2회차)** — 1회차 BLOCKER 9 / CONCERN 11 전건 반영. 주요 변경. (1) **PR 3분할**(DEC-11) (2) `@RequestBody String` → `HttpServletRequest.readNBytes`(B1) (3) 팬아웃 상한 20 + body 절단 + 신뢰경계 문단(B2) (4) "replay 방어" 주장 삭제·잔여위험 ADR(B3) (5) `.env` 키 4종(B4) (6) 경로×메서드 표(B5) (7) `expectedVersion` 삭제 + D3 근거 정정(B6) (8) 6파일 파급 명시(B7) (9) targetBranch 필터(B8) (10) NFR-1 검증 항목(B9).

<!-- git·automation 인바운드 웹훅 2경로군 중앙 SecurityConfig permitAll — DEVELOPMENT.md §1.4 정식 예외·automation 프레이밍 차이·잔여위험 5종·롤백 런북 -->

# ADR — git·automation 인바운드 permitAll (DEVELOPMENT.md §1.4 정식 예외)

- 날짜: 2026-07-17
- 상태: 채택 (Accepted) — **2026-07-17 Maxi 게이트1 승인** (절대 규칙 §1.4 정식 예외 승인 포함)
- 관련 FR: FR-AT-07 (automation BC) — PR-C. 본 ADR은 FR-C15(인바운드 permitAll)와 FR-C13(방어심층)의 결정 기록
- 관련 slug: fr-at-07-pr-c-git-webhook
- Plan: `docs/plans/2026-07-17-fr-at-07-pr-c-git-webhook.md` / Spec: `docs/specs/2026-07-17-fr-at-07-pr-c-git-webhook.md`
- 선행: PR-A(`2026-07-15-slack-inbound-permitall-central.md` — **§후속 "automation 웹훅 permitAll"이 예고한 후속이 본 ADR이다**) · PR-B(`2026-07-16-fr-at-07-pr-b-fix-version-port.md`)
- 선례: PR-A(`2026-07-15-slack-inbound-permitall-central.md`) / FR-DB-03(`2026-07-02-fr-db-03-dashboard-share.md`) / FR-CA-02(`2026-07-09-fr-ca-02-ical-export.md`)

## 맥락 (Context)

PR-A(#274)가 slack 인바운드 4경로를 중앙 `SecurityConfig`에 permitAll 등록하면서 **automation 웹훅을 일부러
제외했다**(PR-A D5 = DEC-15). 범위 축소가 목적이 아니라 **경로마다 방어 상태가 달랐기 때문**이다.

automation을 뺀 구체적 사유는 `AutomationWebhookController.kt:97`이다 (실측 재확인 — 현행 코드 그대로).

```kotlin
enqueuer.enqueue(rule.id, TriggerType.WEBHOOK, triggerEvent)   // :97 — triggerEvent.issueKey 검증 없음
```

하류 `ActionExecutor.extractIssueKey`(`:271`)가 이 값을 **그대로 신뢰**한다. 웹훅 토큰 보유자가
`{"issueKey":"OTHER-1"}`을 보내면 룰을 **자기와 무관한 임의 이슈로 유도**할 수 있고, **폭발 반경은 토큰
보유자 본인 권한이 아니라 룰 actor의 권한**이다. PR-A는 "방어 없이 먼저 열면 순 효과는 *안전하게 죽은 상태* →
*알려진 미방어 결함을 달고 살아 있는 상태*이며, 이는 부채 청산이 아니라 부채 실현"이라 판단하고
**방어심층(FR-C13)이 같은 PR에 들어오는 PR-C로 미뤘다**(PR-A `:149`, §후속 `:224`).

**본 PR이 그 PR-C다.** FR-C13(룰 projectKey ≠ 이슈키 prefix → SKIPPED)을 `ActionExecutor.execute` 내부에
넣으면서 **git 신규 경로 + automation 기존 경로를 함께 연다**.

현재 상태 실측. `SecurityConfig.kt`에 문자열 `automation`은 **0회 등장**한다 → automation 웹훅은
`:209 requestMatchers("/api/**").authenticated()`에서 401이다. **FR-AT-01의 WEBHOOK 트리거가 prod에서
사문화돼 있다.** git 경로는 아직 존재하지 않는다(본 PR 신규).

## 결정 (Decision)

### D1. 인바운드 웹훅 2경로군을 중앙 `SecurityConfig`에 등록 — permitAll · CSRF-ignore · bearer skip **3곳**

| 경로 | 메서드 | 매처 형태 | 상태 |
|---|---|---|---|
| `/api/v1/webhooks/git/*` | POST | 정확 경로 + 단일 세그먼트 | **신규**(본 PR) |
| `/api/v1/automation/webhooks/*` | POST | 정확 경로 + 단일 세그먼트 | **기존 컨트롤러의 결선**(PR-A가 미룬 것) |

`/**` 하위 와일드카드는 **금지**한다. 메서드 고정 + `/*` 단일 세그먼트 2겹으로 폭발 반경을 봉인한다
(`PUBLIC_DASHBOARDS_PATH:272`·`ICAL_FEED_PATH:283`과 동일 원칙).

**★ 두 경로군은 `/api/**` 하위라 `:209 authenticated()`보다 위에 등록해야 한다**(`:205` 주석이 계약 명시).

**★ 등록처는 2곳이 아니라 3곳이다**(`SecurityConfig.kt:290` KDoc이 경고). 이 결합은 자명하지 않고,
**셋의 누락 증상이 전부 같은 401**이라 오진하기 쉽다.

| 구동처 | 줄 | 누락 시 |
|---|---|---|
| bearer token resolver **skip** | `:115-116` | **form POST에서 Tomcat 파싱이 본문 소진 → 컨트롤러가 빈 바디로 401** |
| CSRF-ignore | `:152-154` | POST 403 |
| permitAll | `:206-208` | 401 |

**★ bearer skip이 git에 특히 중요하다.** GitHub 웹훅은 설정에서 `application/x-www-form-urlencoded`를
고를 수 있다 → PR-A D7이 실측 확정한 `DefaultBearerTokenResolver`의 본문 소진 결함이 **PR-C에 그대로
적용**된다. `allowFormEncodedBodyParameter=false`로는 막지 못한다(파라미터 접근이 이미 일어난 뒤에 검사됨).

### D2. §1.4 예외 정당화

`DEVELOPMENT.md:16` **규칙 4 — "인증 없는 엔드포인트 추가 금지. Spring Security 필터 우회 금지."** 를
정면으로 건드리는 변경이다. 네 가지 근거로 정식 예외를 신청하며, **Maxi 게이트1에서 승인됐다**.

**(a) 외부 시스템(GitHub/GitLab)이 호출하므로 BTS 자격증명을 가질 수 없다.**
GitHub 서버는 우리 사용자가 아니다. JWT도 세션도 PAT도 발급할 대상이 없다. 인증을 요구하는 것 자체가
성립하지 않는다(FR-DB-03 익명 대시보드·FR-CA-02 iCal 피드·PR-A slack이 밟은 동일 논리).

**(b) git 경로의 인증은 컨트롤러의 HMAC 서명 검증이 담당한다 — `GitWebhookSignatureVerifier`.**
필터가 비키는 자리에 Git 프로토콜에 정확히 맞는 검증이 선다(§D3에서 **automation에는 이 논거가 성립하지
않음**을 별도로 기술한다).

**(c) 필터가 막으면 서명 검증 코드가 실행조차 되지 않는다.**
automation의 지금 401은 보안 강화가 아니라 **기능 정지**다. FR-AT-01 WEBHOOK 트리거는 구현돼 있고 테스트도
통과하지만 prod에서 **한 번도 호출되지 않는다**. 필터를 유지하는 것이 우리를 더 안전하게 만들지 않는다.

**(d) 폭발 반경은 메서드 고정 + `/*` 단일 세그먼트로 봉인한다.** (D1)

### D3. ★ automation의 프레이밍 차이 — PR-A의 논거를 그대로 쓸 수 없다

**이 절이 본 ADR에서 가장 정직해야 하는 부분이다.**

PR-A D2의 핵심 프레이밍은 *"permitAll은 인증을 **없애는** 게 아니라 **검증 주체를 필터 → 컨트롤러로 옮기는**
것"* 이었고, 그 정당성의 근거는 *"필터가 비키는 자리에 **더 강한** 검증이 이미 서 있다"*(HMAC-SHA256 +
replay ±5분 + 상수시간 비교 + fail-closed)였다.

**automation 웹훅에는 서명 검증이 없다.** 불투명 토큰 소지 자체가 인증이다(`AutomationWebhookController:95`가
`findByWebhookTokenHash(sha256Hex(token))`로 조회). 따라서 **PR-A의 (b) 논거는 automation 경로에
성립하지 않으며, 본 ADR은 그것을 성립하는 척하지 않는다.**

| 경로군 | 인증 수단 | PR-A (b) 논거 |
|---|---|---|
| `/api/v1/webhooks/git/*` | **HMAC 서명 검증**(GITHUB) / 평문 토큰 비교(GITLAB — §잔여위험 R3) | **성립** |
| `/api/v1/automation/webhooks/*` | **불투명 토큰 소지 = 인증** (서명 없음) | **성립하지 않음** |

**automation의 실제 등급.** 인증 *메커니즘*은 `PublicDashboardController` 직교 토큰 선례와 같은 등급이다
(불투명 토큰 소지 = 인증, SHA-256 해시만 저장, 토큰 원문·해시 로그 미출력).

**★ 그러나 폭발 반경은 같은 등급이 아니다.** `PublicDashboardController:62`는 **read-only GET**이고
(`:25` — "정화된 공개 스냅샷 조회"), automation 웹훅은 **부수효과를 내는 POST**다. 토큰이 새면
전자는 스냅샷 열람이지만 후자는 **룰 actor 권한으로의 이슈 변경**이다. 두 선례를 "같은 등급"으로 뭉뚱그리면
automation이 실제로 더 위험하다는 사실이 가려진다 → **토큰 유출의 영향은 R1에 등재한다.**

**그럼에도 여는 근거.** (i) FR-C13 방어심층이 **같은 PR에** 들어와 PR-A가 지목한 cross-project 유도를
차단한다(PR-A `:149`가 요구한 전제 충족). (ii) `MANAGE_AUTOMATION` 권한자만 토큰을 발급받고 토큰은
**발급 응답에 1회만** 노출된다. (iii) 대안은 FR-AT-01의 무기한 사문화다(§대안 (B)).

### D4. 공유 리스트 확장 — `SLACK_INBOUND_PATHS` → `INBOUND_WEBHOOK_PATHS`

PR-A D4(DEC-16)가 만든 **단일 `List<Pair<HttpMethod, String>>`를 3곳이 함께 순회하는 구조**를 그대로
확장한다. 경로군이 2개 늘면 slack 전용 이름이 거짓이 되므로 리네이밍한다.

★ **효력 범위는 PR-A D4와 동일** — 이 구조가 막는 건 **경로·메서드 divergence**(한쪽에만 추가)이지
`forEach` 블록 삭제가 아니다. 그건 T15 prod 조립 HTTP 테스트가 잡는다. **구조와 테스트가 함께 가드이며
어느 한쪽도 단독으로 충분하지 않다.**

★ **csrf 쪽만 `antMatcher(method, path)`인 것은 API 강제다.** `ignoringRequestMatchers`에는
`(HttpMethod, String)` 오버로드가 **없다** → 문자열 오버로드를 쓰면 **메서드 고정이 조용히 사라지고
컴파일·테스트 모두 통과**한다.

### D5. 표기 — 저장소 지역 관례 `§1.4`를 따른다 (PR-A DEC-17 승계)

신규 KDoc도 **`§1.4 정식 예외(ADR 2026-07-17-git-webhook-inbound-permitall · 게이트1 승인)`** 로 쓴다.
저장소는 규칙 4를 `§1.4`로 참조하는 `§1.<규칙번호>` 방언을 일관 사용한다(`SecurityConfig.kt:202` 등).

- **기각.** `§1.1 #4` 표기 — 한 파일에 두 표기가 나란히 서는 **세 번째 방언**이 된다.
- **기각.** 기존 14곳 일괄 정정 — 본 FR 무관 정리로 보안 PR의 리뷰 초점을 흐린다(surgical changes).
  PR-A가 이미 별건 후속으로 등재했다.

## 결과 (Consequences)

- **FR-AT-01 WEBHOOK 트리거가 prod에서 되살아난다.** PR-A가 남긴 마지막 인바운드 부채가 청산된다.
- **PR 머지 → 자동화 규칙 발화(S1) 경로가 완성된다** — PR-A(도달 가능화) · PR-B(설정 통로) 위에 얹힌다.
- **BC 격리 예외 2건.** automation 사유로 identity-access(`SecurityConfig`) · app(prod 조립 테스트)를
  수정한다. 중앙 필터체인이 identity-access에만 존재하는 구조상 불가피하며 security-engineer 검토로 갈음한다.
- **네 번째·다섯 번째 비인증 경로군**이 된다(FR-DB-03 `/api/v1/public/*` · FR-CA-02 `/ical/feed/*` ·
  PR-A slack 4경로 다음).
- **permitAll만으로는 부족하다** — git secret 미설정 시 검증기 fail-closed가 전부 막아 "필터의 401"이
  "컨트롤러의 401"로 바뀔 뿐이다. `.env.prod.example` 배포 변수 선언이 **같은 PR에 동반**되어야 본 ADR의
  주장이 참이 된다(PR-A와 동일 구조).
- **`AutomationTestSecurityConfig.kt:49`의 `/**` divergence가 되살아난다** — PR-A `:151`이 "automation을
  빼면 C-4 divergence도 소멸"이라 했으나 본 PR이 되살리므로 `/*`로 정합화한다. 같은 파일 `:47`이
  `csrf { it.disable() }`라 **BC 테스트는 중앙 CSRF-ignore 누락을 원리적으로 못 잡는다** → app 모듈 조립
  테스트(T15)가 **유일한 관문**이다.
- **되돌림 난이도 2/5** — 마이그레이션 3개 + enum 추가라 코드 롤백만으로 안전하지 않다(**R5 + 롤백 런북**).
- FR 카운트 **불변 123**(D6/D7·FR-AT-07 완료 마킹은 PR-D). automation BC **6/7 유지**.

### 잔여 위험 (수용) — 5종

| # | 위험 | 판단 |
|---|---|---|
| **R1** | **★ FR-C13은 cross-project만 막는다.** 룰 projectKey ≠ 이슈키 prefix는 SKIPPED이지만 **같은 프로젝트 내 임의 이슈 조작은 여전히 가능**하다. `ActionExecutor.kt:176`이 조건 없는 룰을 `?: return@runCatching true`로 게이트 없이 통과시키므로 조건 미설정 룰은 작성자 가시성 검사조차 받지 않는다. **PR-A DEC-15의 우려는 완전히 해소되지 않는다** — 토큰 보유자는 룰 actor 권한으로 **같은 프로젝트 내** 임의 이슈를 유도할 수 있다 | **수용.** 근본 해소는 전 트리거의 동작 변경(조건 없는 룰의 기본 게이트 신설)이라 폭발 반경이 FR-AT-07을 넘는다 → **별도 PR**(§후속). 본 PR은 PR-A가 지목한 **cross-project 유도**를 차단해 최대 폭발 반경을 프로젝트 경계로 봉인한다 |
| **R2** | **★ GitHub replay 방어가 구조적으로 불가능하다.** `X-Hub-Signature-256`은 `sha256=hex(HMAC(secret, rawBody))` — **base string에 timestamp가 없다** → slack의 ±300초 윈도우(`REPLAY_WINDOW_SECONDS=300L`)에 **대응물이 없다**. `X-GitHub-Delivery`는 **서명 대상 밖**이라 위조 가능 | **수용 — 우리가 만들 수 없다.** GitHub 프로토콜의 성질이지 우리 구현의 누락이 아니다. **dedup은 "정직한 재시도 방어"일 뿐 replay 방어가 아니다** — ADR·KDoc·스펙 어디에도 replay 방어를 주장하지 않는다. 캡처된 유효 요청은 재전송 시 dedup에 걸리지 않는 한 재발화한다 |
| **R3** | **★ GitLab은 GITHUB과 보안 등급이 다르다.** `X-Gitlab-Token`은 **평문 토큰 비교**다. HMAC 서명이 아니므로 본문 무결성을 보장하지 않고 전송 경로 노출에 취약하다 | **수용 — GitLab이 HMAC을 제공하지 않아 우리가 더 강하게 만들 수 없다.** ★ **GITHUB과 동급으로 서술 금지**(DEC-13) — 검증기 KDoc·ADR·스펙 모두 등급 차이를 명시한다. 완화책으로 등록 API가 secret **최소 16자**를 강제한다(빈 secret이면 토큰만으로 우회) |
| **R4** | **rate limit 부재 — 본 PR로 등급이 상승한다.** 미인증 요청을 강제할 수 있고, 서명 검증이 요청당 HMAC 계산을 한다. slack(R1)에서는 **CPU 소모**였으나 git 경로는 `git_webhook_deliveries`에 **dedup 행을 쓴다** → **디스크 고갈**로 등급 상승 | **수용 + 완화.** 서명 미검증 요청은 **DB 쓰기 0**(T15가 실증 — 서명 틀린 요청 N회 후 행 수 불변)이라 무자격 트래픽은 디스크에 닿지 않는다. 정직한 트래픽의 무한 증식은 **T18 7일 정리 배치**가 막는다. rate limit 자체는 모든 비인증 경로 공통의 **기존 부채** → §후속 |
| **R5** | **★ 롤백 안전성 — 코드를 롤백하면 automation 화면이 통째로 500이 된다** (아래 상세) | **수용 — 코드로 막을 수 없다. 절차가 유일한 방어** → **롤백 런북 필수** |

### R5 상세 — 롤백 시 automation 읽기 경로 전면 500

```
V309(trigger_type CHECK 6종)는 Flyway 자동 롤백이 없어 DB에 남는다
  └─▶ PR_MERGED 룰이 1건이라도 존재 + 코드만 롤백
        └─▶ AutomationRuleRepository.kt:314   TriggerType.valueOf(rs.getString("trigger_type"))  ← runCatching 없음
            RuleExecutionRepository.kt:183    동일                                               ← runCatching 없음
              └─▶ IllegalArgumentException → 룰 목록·실행 이력 조회 500
```

**실측 근거** (패턴 grep으로 직접 확인 — plan의 개수를 물려받지 않았다.
[[spec-stated-count-becomes-blindfold]] 3회 재현 이력). `grep -rn "TriggerType\.valueOf"
backend/modules/automation/src/main` → **3건**. `AutomationExecutionWorker.kt:437`만
`runCatching { TriggerType.valueOf(text) }.getOrNull()`로 방어돼 있고 **읽기 경로 2곳은 무방비**다.

**★ 코드로 막을 수 없다.** 지금 그 두 줄을 `runCatching`으로 고쳐도 **롤백이 그 수정을 함께 되돌린다.**
이번 롤백은 코드로 구할 수 없고 **절차가 유일한 방어**다.

#### 롤백 런북 (순서 엄수)

1. **코드 롤백 *전에* PR_MERGED 룰을 비활성화한다.**
   ```sql
   UPDATE automation_rules SET enabled = false WHERE trigger_type = 'PR_MERGED';
   ```
   (또는 소프트 삭제.) **먼저 하지 않으면 롤백 직후 automation 화면 전체가 사망한다.**
2. **그 다음 코드를 롤백한다.**
3. **V307~V309는 남긴다 — 되돌리지 말 것.** 신규 테이블 추가 + CHECK 확대는 옛 코드와 호환된다.
   **★ V309를 되돌리면 남아 있는 PR_MERGED 행이 CHECK를 위반해 오히려 깨진다.**

→ **후속 후보**(§후속). 읽기 경로 2곳을 `runCatching`으로 방어해 **다음번** enum 추가 PR의 롤백을 안전하게
만든다. 이번 롤백은 못 구하지만 구조적 개선이다.

## 대안 (Rejected)

CEO 리뷰 0C-bis에서 구현 대안 3종을 검토했고 **Maxi가 A를 확정**했다.

- **(A) 중앙 공유 리스트 확장 — 채택.** PR-A DEC-16이 만든 구조를 그대로 확장한다. 신규 의존성 0,
  혁신 토큰 0, 인증 우회 지점이 **한 파일에서 전부 보인다**.
- **(B) git 경로만 열고 automation은 계속 닫아 둔다.** 기각 — **FR-AT-01 사문화가 지속**된다.
  이미 FR-SL-01 D7(2026-07-07) · automation-prod-assembly(2026-07-11) · PR-A DEC-15(2026-07-15)로
  **세 번 미뤄졌다**. FR-C13이 같은 PR에 들어와 PR-A가 요구한 전제가 충족된 지금이 그 조건이다.
  범위 축소는 이미 2회 확정된 사안의 번복이기도 하다.
- **(C) permitAll 확장 포인트(`PathContributor` 류)를 먼저 도입한다.** 기각 —
  **★ 보안 설정을 9개 BC에 분산시켜 "인증 우회 지점이 한 파일에서 전부 보인다"는 중앙 감사 성질을 잃는다.**
  부재가 이 부채의 구조적 원인임은 맞으나(PR-A §맥락), 9개 BC의 필터체인 등록 방식을 바꾸는 범위 폭증이며
  보안 PR의 리뷰 초점을 흐린다. **별도 후속으로 등재**한다(§후속). PR-A가 이미 같은 근거로 기각했고,
  본 PR도 같은 결론에 도달했다.
- **(D) automation 웹훅에 서명 검증을 신설해 git과 등급을 맞춘다.** 기각 — 서명 검증은 **양쪽 합의**가
  필요하다. automation 웹훅의 호출자는 임의의 외부 시스템이며 우리가 그들의 서명 구현을 강제할 수 없다.
  불투명 토큰이 이 경로에 맞는 인증 수단이고, 부족분은 FR-C13 방어심층 + R1 등재로 다룬다.

## 승인 (Approval)

- **`DEVELOPMENT.md §1.4`**(규칙 4 — 인증 없는 엔드포인트 추가 금지 / Spring Security 필터 우회 금지)의
  **정식 예외 — 2026-07-17 Maxi 게이트1 승인** (DEC-22).
- **`DATA.md:15` §1.5** — *"인증/CSRF 우회 불가 — Spring Security 필터 체인 변경은 plan-eng-review +
  plan-ceo-review 필수"*(데이터 무결성 5원칙, 위반 시 즉시 PR BLOCKER). T12가 정확히 필터 체인을 바꾸므로
  **양 리뷰를 모두 실행했고 둘 다 CLEAR**다.

| 리뷰 | 결과 | 발견 |
|---|---|---|
| `plan-eng-review` (2026-07-17) | **CLEAR** | 3건 (T18 dedup 정리 배치 신설 · NFR-1 측정 T15 배정 · 팬아웃 상한 100 실측) / critical gap 0 |
| `plan-ceo-review` (2026-07-17) | **CLEAR** (mode: HOLD_SCOPE) | 3건 (T7 복호화 boolean 수렴 · 로그 이벤트명 분리 · **본 ADR의 R5 + 롤백 런북**) / critical gap 0 |

> **★ 1회차는 ceo-review를 "배선 PR이라 해당 없음"으로 스킵했다.** gstack 스킬의 일반 기준
> ("제품 변경엔 추천, 인프라는 스킵")을 따르다 **프로젝트 헌법(`DATA.md §1.5`)을 놓쳤다**.
> **프로젝트 규칙이 도구 기본값을 이긴다.** 2회차에 실행했고, 그 리뷰가 R5(롤백 시 automation 전면 500)를
> 발견했다 — 스킵했다면 놓쳤을 최대 발견이다.

## 후속 (본 ADR 범위 밖)

- **R1 근본 해소** — 조건 없는 룰의 기본 가시성 게이트(`ActionExecutor.kt:176`). 전 트리거 동작 변경이라 별도 PR
- **R5 구조적 개선** — `AutomationRuleRepository:314`·`RuleExecutionRepository:183`을 `runCatching`으로 방어해
  **다음번** enum 추가 PR의 롤백을 안전하게 (이번 롤백은 못 구함)
- **permitAll 확장 포인트**(`PathContributor` 류) — 이 부채의 구조적 원인 해소(대안 C). `SecurityConfig`
  300줄 한계와 함께 검토
- **인바운드 경로 rate limit** (R4) — 모든 비인증 경로 공통 기존 부채
- **`§1.4` 표기 ↔ `DEVELOPMENT.md` 절번호 충돌** — `§1.<규칙번호>` 방언의 별건 일괄 정리 (PR-A DEC-17 승계)
- **`AutomationWebhookController`의 actor 임의 지정** (PR-A §후속 승계 — 별도 FR 후보)
- **메트릭 인프라 부재** — 저장소에 micrometer·MeterRegistry 0건. 관측성은 구조화 로그 단일 수단.
  도입은 `§1.17` 신규 의존성 = 별건 (CEO 리뷰 2A)

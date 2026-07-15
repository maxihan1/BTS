<!-- slack 인바운드 4경로 중앙 SecurityConfig permitAll — DEVELOPMENT.md §1.4 정식 예외·검증 주체 이관·공유 리스트 구조 결정 -->

# ADR — slack 인바운드 permitAll 중앙 등록 (DEVELOPMENT.md §1.4 정식 예외)

- 날짜: 2026-07-15
- 상태: 채택 (Accepted) — **2026-07-15 Maxi 게이트1 승인** (절대 규칙 §1.4 정식 예외 승인 포함)
- 관련 FR: FR-SL-01~06 (slack-integration BC) — 본 ADR은 FR을 완료시키지 않는 **선행 부채 청산**
- 관련 slug: fr-at-07-pr-merge (PR-A)
- Plan: `docs/plans/2026-07-15-fr-at-07-pr-merge.md` / Spec: `docs/specs/2026-07-15-fr-at-07-pr-merge.md` §A
- 선행: FR-SL-01(`2026-07-07-fr-sl-01-slack-bot-app.md` **§D7이 예고한 후속이 본 ADR이다**)
- 선례: FR-DB-03(`2026-07-02-fr-db-03-dashboard-share.md`) / FR-CA-02(`2026-07-09-fr-ca-02-ical-export.md`)

## 맥락 (Context)

중앙 `SecurityConfig`(identity-access)는 `auth.anyRequest().authenticated()`(`SecurityConfig.kt:186`)로 닫혀 있고,
slack 인바운드 경로가 permitAll 목록에 **없다**. 따라서 Slack이 보내는 요청은 컨트롤러에 도달하기 전에 401이고,
POST는 CSRF 필터가 먼저 403을 준다. **FR-SL 전체가 prod에서 사문화돼 있다.**

이 상태가 여태 드러나지 않은 이유가 부채의 본질이다. 각 BC가 **테스트 전용** 필터체인으로 자기 경계를 검증하기
때문에 CI는 초록불이다. slack은 `SlackTestSecurityConfig.kt:64-67`에 4경로 permitAll을 갖고 있으나 이는
`@TestConfiguration`이라 prod 조립에 존재하지 않는다. **"test 초록불이 prod를 대변하지 않는다"** 의 실례다.

이는 신규 발견이 아니라 **문서화된 scope-out + 후속 추적 항목**이다.

- `docs/decisions/2026-07-07-fr-sl-01-slack-bot-app.md:57-59` **D7** — *"프로덕션 `SecurityFilterChain`은 identity-access
  중앙 `SecurityConfig`에만 있다. (…) 배포 조립 시점에 중앙 `SecurityConfig`에 콜백 permitAll을 추가하는 것은
  **DEVELOPMENT.md §1.4 예외로 ADR/게이트 승인이 필요한 후속 작업**(public dashboards 경로 선례와 동일 취급)."*
  → **본 ADR이 그 후속이며, D7이 요구한 ADR + 게이트 승인 절차를 이행한다.**
- `docs/plans/2026-07-11-automation-prod-assembly.md:53` — *"`AutomationWebhookController`가 조립되나 중앙 SecurityConfig
  화이트리스트 미포함 → prod 401(FR-AT-01 WEBHOOK 트리거 사문화). slack `/slack/events`도 동일 미등록·후속 추적 중."*
  / `:143` D3=scope-out / `:165` *"인바운드 permitAll 중앙등록은 BTS의 알려진 BC별 배포-시점 후속 패턴."*

**구조적 원인.** permitAll 경로는 `SecurityConfig`의 `private companion object`(`:209`)에 리터럴로 하드코딩된다.
BC가 자기 경로를 등록하는 확장 포인트(`PathContributor` 류)가 없어(grep 0건), 새 BC가 인바운드 경로를 추가할 때마다
같은 부채가 재생산된다. 본 ADR은 이 구조적 원인을 해결하지 않는다(§후속).

## 결정 (Decision)

### D1. slack 인바운드 4경로를 중앙 `SecurityConfig`에 permitAll + CSRF-ignore 등록

| 경로 | 메서드 | 매처 형태 | test 원본 |
|---|---|---|---|
| `/slack/events` | POST | 정확 경로 | `SlackTestSecurityConfig.kt:65` |
| `/slack/commands` | POST | 정확 경로 | `:66` |
| `/slack/interactions` | POST | 정확 경로 | `:67` |
| `/slack/install/callback` | GET | 정확 경로 | `:64` |

`/slack/**` 같은 하위 와일드카드는 **금지**한다. 정확 경로 + 메서드 고정으로 폭발 반경을 봉인한다
(`PUBLIC_DASHBOARDS_PATH:248`·`ICAL_FEED_PATH:259`의 GET 고정·단일 세그먼트 2겹 방어와 동일 원칙).

### D2. ★ 정확한 프레이밍 — permitAll은 인증을 없애는 게 아니라 **검증 주체를 옮기는** 것

이 구분이 본 ADR의 핵심이며, §1.4 예외가 정당한 이유 그 자체다.

**필터가 하던 인증을 컨트롤러의 서명 검증이 대신한다.** 요청은 여전히 자기가 진짜 Slack임을 증명해야 하고,
증명하지 못하면 거부된다. 달라지는 것은 **거부하는 주체**(필터 → 컨트롤러)이지 **거부 여부**가 아니다.

이 주장은 **테스트로 실증한다**(spec S-A1/S-A2). 유효한 서명을 계산해 보낸 `url_verification` 요청이
**200 + challenge 에코**를 받고(필터 통과 + 서명 검증 통과의 양성 증명), 서명만 틀린 동일 요청이 **401**을 받는다
(검증 주체가 컨트롤러임의 실증). "401이 아니다"라는 음성 단언에 기대지 않는다.

### D3. §1.4 예외 정당화

`DEVELOPMENT.md §1.1`의 **규칙 4 — "인증 없는 엔드포인트 추가 금지. Spring Security 필터 우회 금지."** 를
정면으로 건드리는 변경이다. 네 가지 근거로 정식 예외를 신청하며, Maxi 게이트1에서 승인됐다.

**(a) 외부 시스템이 호출하므로 BTS 자격증명을 가질 수 없다.**
Slack 서버는 우리 사용자가 아니다. JWT도 세션도 PAT도 발급할 대상이 없다. 인증을 요구하는 것 자체가
성립하지 않는다(FR-DB-03 익명 대시보드·FR-CA-02 iCal 피드가 외부 캘린더 앱에 대해 밟은 동일 논리).

**(b) 인증은 컨트롤러의 서명 검증이 담당한다 — `SlackSignatureVerifier`.**
필터가 비키는 자리에 더 강한(그리고 Slack 프로토콜에 정확히 맞는) 검증이 이미 서 있다.

| 방어 | 구현 |
|---|---|
| HMAC-SHA256 서명 | base string `v0:{timestamp}:{rawBody}`, `X-Slack-Signature` = `v0=`+hex (`:90-100`) |
| **replay 방어 ±5분** | `abs(clock.instant().epochSecond - timestamp) <= 300` (`:71-73` guard · `:85-87` · `REPLAY_WINDOW_SECONDS=300L :113`) |
| **상수시간 비교** | `MessageDigest.isEqual` (`:78-81`) — 조기 반환 비교의 타이밍 공격 차단 |
| **fail-closed** | signing secret 미설정도 `false` (`:64-66`). 헤더 누락·`v0=` 접두 없음·비숫자 timestamp·윈도우 초과·불일치 **전부 `false`** |
| time-bomb 회피 | `Clock` 주입(`:48` 기본값 `Clock.systemUTC()`) — 테스트는 `Clock.fixed` |

> **인용 정정.** plan·spec이 인용한 줄번호(`:78-80` fail-closed · `:104-107` 상수시간 · `:110-112` replay)는
> 파일 이전 리비전 기준이라 **어긋난다**. 위 표가 현행 코드(115줄) 기준 검증값이다. 주장 자체는 전부 사실이다.

**(c) 필터가 막으면 서명 검증 코드가 실행조차 되지 않는다.**
지금의 401은 보안 강화가 아니라 **기능 정지**다. `SlackSignatureVerifier`는 작성돼 있고 테스트도 통과하지만
prod에서 **한 번도 호출되지 않는다**. 필터를 유지하는 것이 우리를 더 안전하게 만들지 않는다 — 단지 FR-SL을
죽여둘 뿐이다. 열어야 비로소 우리가 설계한 방어가 작동한다.

**(d) 폭발 반경은 메서드 고정 + 정확 경로로 봉인한다.** (D1)

### D4. ★ 공유 리스트 구조 — 이중 등록 누락을 구조적으로 불가능화 (DEC-16)

permitAll(`:151-184`)과 CSRF-ignore(`:133-146`)는 **서로 다른 블록**이다. 한쪽만 등록하면 POST가 403으로 죽는다.
FR-MF-01에서 실제로 발생한 BLOCKER다.

**4경로를 `List<Pair<HttpMethod, String>>` 하나로 두고 csrf 블록과 authorize 블록이 같은 리스트를 순회한다.**

★ **정확한 효력 범위** — 이 구조가 막는 건 **경로·메서드 divergence**다(한쪽에 경로를 추가하고 다른 쪽에
빠뜨리는 것). **`forEach` 블록 자체를 지우는 것은 여전히 컴파일된다** — 그건 구조가 아니라
`SlackInboundPermitAllTest`가 잡는다. 구조와 테스트가 **함께** 가드이며, 어느 한쪽도 단독으로 충분하지 않다.

### D4-a. `SLACK_INBOUND_PATHS` 편집 시 지킬 것 (코드 KDoc이 이 절을 가리킨다)

- **이 목록 하나가 3곳을 구동한다** — permitAll · CSRF-ignore · bearer resolver skip(§D7). 셋의 결합은
  자명하지 않다. 특히 CSRF-ignore·bearer skip을 빠뜨리면 **증상이 "permitAll 미등록"과 구분되지 않는
  401**이라 오진하기 쉽다(FR-MF-01 BLOCKER-1 실사고).
- **csrf 쪽만 `antMatcher`인 건 API 강제**다. `CsrfConfigurer.ignoringRequestMatchers`에는
  `(HttpMethod, String)` 오버로드가 **없다**(`(String...)`·`(RequestMatcher...)`뿐). 문자열 오버로드로
  바꾸면 **메서드 고정이 조용히 사라지고 컴파일·테스트 모두 통과**한다.
- **메서드 고정 + 정확 경로만.** 와일드카드 금지(`PUBLIC_DASHBOARDS_PATH`·`ICAL_FEED_PATH`와 동일 원칙).
- **`/slack/install`은 이 목록에 없다** — 관리자 설치 개시 경로이며 `authenticated()` + admin fail-closed
  이중 가드를 유지한다. 회귀 가드는 `SlackInboundPermitAllTest`의 EC-A1이되, **판별자는 상태코드가 아니라
  응답 본문**이다(`SLACK_UNAUTHENTICATED` 부재) — permitAll이 새어도 컨트롤러가 같은 401을 주므로
  상태코드 단언은 vacuous임이 위반 주입으로 실증됐다.

**열거식 회귀 가드 테스트를 기각한 근거.**
1. 열거식 테스트는 **자기가 아는 경로만** 단언한다. 미래에 추가될 경로는 존재를 모르므로 **원리적으로 못 잡는다**.
2. `SecurityConfig.kt:209`가 `private companion object`라 `com.bts.app` 테스트가 상수에 접근할 수 없다
   → 테스트가 **경로 리터럴을 복제**하게 되고, 그 복제본이 drift한다. **가드가 막으려는 결함(등록 누락·drift)을
   가드 자신이 재생산**한다.
3. 회귀 가드(사람이 계속 맞춰야 함)보다 **본질 차단**(구조상 불가능)이 우선이다 — fixture가 helper를 호출해
   drift를 차단한 선례(learnings 2026-05-23 "fixture 옵션 B")와 같은 결.

이는 `PathContributor` 대공사 ↔ 아무것도 안 함이라는 **거짓 이분법** 사이의 약 20줄 지역 리팩터링이다.

### D5. ★ automation 웹훅은 이번에 열지 않는다 — 위험도로 가른 분할 (DEC-15)

원래 D2("3종 일괄 중앙 등록")를 **부분 철회**한다. **경로마다 방어 상태가 다르기 때문**이지 범위 축소가 목적이 아니다.

| 경로 | 방어 상태 | PR |
|---|---|---|
| slack 인바운드 4경로 | **온전** — HMAC-SHA256 + replay ±5분 + 상수시간 + fail-closed (D3-b) | **PR-A (본 ADR)** |
| `/api/v1/automation/webhooks/*` | **미방어** | **PR-C** |

**automation 웹훅의 구체적 결함.** `AutomationWebhookController.kt:97`이 payload에서 뽑은 issueKey를
**무검증으로 enqueue**한다.

```kotlin
enqueuer.enqueue(rule.id, TriggerType.WEBHOOK, triggerEvent)   // :97 — triggerEvent.issueKey 검증 없음
```

하류 `ActionExecutor.extractIssueKey`가 이 값을 **그대로 신뢰**한다. 따라서 웹훅 토큰 보유자가
`{"issueKey":"OTHER-1"}`을 보내면 **룰을 자기와 무관한 임의 이슈로 유도**할 수 있고, **폭발 반경은 룰 actor의
권한까지**다(토큰 보유자 본인 권한이 아니라).

**지금은 prod에서 죽어 있어서 문제가 되지 않던 결함이다.** 방어 없이 먼저 열면 순 효과는
"안전하게 죽은 상태" → **"알려진 미방어 결함을 달고 살아 있는 상태"** 이며, 이는 부채 청산이 아니라 부채 실현이다.
→ 방어심층(FR-7 — 룰 projectKey ≠ 이슈키 prefix면 SKIPPED)이 **같은 PR에 들어오는 PR-C**와 함께 연다.

**부수 효과.** automation을 빼면 test config `/**` ↔ 중앙 `/*` divergence(C-4)도 소멸한다.
slack test config는 이미 정확 경로라 중앙 등록과 일치한다.

### D6. 표기 — 저장소 지역 관례 `§1.4`를 따른다 (DEC-17)

신규 KDoc도 **`§1.4 정식 예외(ADR … · 게이트1 승인)`** 로 쓴다. 저장소는 규칙 4를 `§1.4`로 참조하는
`§1.<규칙번호>` 방언을 **9파일 14곳에서 일관 사용**한다(`SecurityConfig.kt`에만 `:59,:71,:176,:183,:246,:257` 6곳.
`:117`은 CSRF 규칙 5를 `§1.5`로 참조). `DEVELOPMENT.md`의 절 번호(`§1.4 외부 의존성`)와 충돌하지만
**기존 부채이며 본 PR이 만든 것이 아니다** → 별건 후속(§후속).

- **기각.** `§1.1 #4` 신규 표기 — 한 파일 안에 두 표기가 나란히 서는 **세 번째 방언**이 된다.
- **기각.** 14곳 일괄 정정 — 본 FR 무관 메모 정리로 보안 PR의 리뷰 초점을 흐린다(surgical changes).

### D7. ★ slack 인바운드는 `DefaultBearerTokenResolver`를 태우지 않는다 — 본문 스트림 보호

**증상.** PR-A를 `origin/main`(PR #275 머지 후)에 rebase 하니 form-urlencoded 2경로(`/slack/commands`,
`/slack/interactions`)만 401. JSON 경로(`/slack/events`)는 통과. permitAll·CSRF-ignore는 정상 등록돼 있었다.

**근본 원인 (스택 트레이스로 실측 확정 — 가설 4개를 먼저 반증).**
`SecurityConfig`의 bearer resolver 람다가 `DefaultBearerTokenResolver.resolve()`를 호출 →
`isParameterTokenSupportedForRequest()`가 `POST` + `application/x-www-form-urlencoded`이면 **true** →
`resolveFromRequestParameters()` → `getParameterValues("access_token")` → **Tomcat이 form 본문을 파싱**해
요청 입력 스트림을 소진. 원문 바이트를 직접 읽는 slack 컨트롤러(`readBoundedSlackBody`)는 **빈 바디**를
받아 HMAC 서명 검증이 전부 실패한다.

★ **`allowFormEncodedBodyParameter = false`는 이걸 막지 못한다.** 그 플래그는 그 **뒤**의
`isParameterTokenEnabledForRequest()`에서야 검사된다 — 파라미터 접근이 **이미 일어난 후**다.

**왜 PR #275 전에는 안 보였나.** 그때 컨트롤러는 `@RequestBody String`이었고,
`ServletServerHttpRequest.getBody()`의 `isFormPost()` 분기가 **`getParameterMap()`에서 본문을 재구성**해
스트림 소진을 가려줬다. #275가 DoS 가드를 위해 원문 스트림 읽기로 바꾸면서 잠복 결함이 표면화됐다.

**결정.** `SLACK_INBOUND_PATHS`를 `OrRequestMatcher`로 묶어, 해당 요청이면 delegate 호출 **자체를 건너뛰고**
`null`을 반환한다. slack은 Bearer 토큰을 보내지 않으므로 skip이 **의미상으로도 정확**하다(기능 손실 0).

- **기각.** `allowFormEncodedBodyParameter=false` 명시 — 위 이유로 무효. 고쳤다는 **착각만** 준다.
- **기각.** 컨트롤러를 `@RequestBody`로 되돌리기 — #275가 막은 미인증 힙 DoS가 그대로 부활한다.

**회귀 가드.** `SlackInboundPermitAllTest`의 slash command·interactions 케이스가 실 HTTP로 200을 단언한다
(prod 조립 + `RANDOM_PORT`). 이 skip이 사라지면 두 테스트가 401로 즉시 깨진다.

## 결과 (Consequences)

- **FR-SL이 prod에서 되살아난다.** 단, permitAll만으로는 부족하다 — `BTS_SLACK_SIGNING_SECRET`이 없으면
  `SlackSignatureVerifier.kt:64-66`의 fail-closed가 전부 막아 **"필터의 401"이 "컨트롤러의 401"로 바뀔 뿐
  기능 변화가 0**이다. 배포 변수 선언(`.env.prod.example`)이 **같은 PR에 반드시 동반**되어야 본 ADR의 주장이 참이 된다.
- **BC 격리 예외.** slack 사유로 identity-access `SecurityConfig`를 수정한다. 중앙 필터체인이 identity-access에만
  존재하는 구조상 불가피하며(FR-SL-01 D7이 이미 이 경로를 지정), security-engineer 공동 검토로 갈음한다.
- 신규 의존성 0. DB 변경 0. FR 카운트 **불변 123**(부채 청산이라 FR을 완료시키지 않음). automation BC **6/7 유지**.
- **세 번째 비인증 경로군**(FR-DB-03 `/api/v1/public/*` · FR-CA-02 `/ical/feed/*` 다음)이 된다.
- prod 조립 HTTP 테스트 인프라가 신규로 생기며 PR-C가 그대로 재사용한다("401이 아님"을 검증할 수단이
  현재 저장소에 없다 — 기존 조립 테스트는 MOCK 웹환경).

### 잔여 위험 (수용)

| # | 위험 | 판단 |
|---|---|---|
| **R1** | **slack 경로 rate limit 부재** — permitAll 경로에 미인증 요청을 강제할 수 있다. 서명 검증이 요청당 HMAC 계산을 하므로 미인증 트래픽이 CPU를 소비한다 | **기존 부채**(신규 아님 — 모든 비인증 경로가 동일). 경로를 여는 PR이므로 **명시는 한다**. → §후속 |
| **R2** | `/slack/install`(관리자 설치 진입점)은 **열지 않는다** | 의도적. `authenticated()` + 컨트롤러 뒤 `SlackInstallService`의 admin fail-closed **이중 가드 유지**. permitAll 범위가 새지 않았음을 EC-A1(익명 → 401 유지)이 단언 |

## 대안 (Rejected)

- **(i) permitAll 확장 포인트(`PathContributor` 류)를 먼저 도입한다.** 기각 — 부재가 이 부채의 **구조적 원인**임은
  맞으나(§맥락), 9개 BC의 필터체인 등록 방식을 바꾸는 범위 폭증이며 보안 PR의 리뷰 초점을 흐린다.
  **별도 후속으로 등재**한다(§후속). DEC-16의 공유 리스트는 `SecurityConfig` **내부** 해결이라 BC별 등록은 여전히 수동이다.
- **(ii) 계속 미룬다.** 기각 — FR-SL 사문화가 지속된다. 이미 FR-SL-01 D7(2026-07-07)과
  automation-prod-assembly(2026-07-11)에서 두 번 미뤄졌고, 그 사이 FR-SL-01~06 6개가 전부 "완료"로 표기된 채
  prod에서 동작하지 않는 상태가 됐다. 세 번째 연기는 부채를 키울 뿐이다.
- **(iii) 열거식 회귀 가드 테스트로 이중 등록을 보장한다.** 기각 — D4 참조(미래 경로 원리적 미포착 + 리터럴 복제 drift).
- **(iv) automation 웹훅까지 함께 연다(원 D2).** 기각 — D5 참조(미방어 issueKey → 방어심층 동반 PR-C로).

## 후속 (본 ADR 범위 밖)

- **automation 웹훅 permitAll** — PR-C에서 방어심층(FR-7)과 함께 (DEC-15)
- **permitAll 확장 포인트**(`PathContributor` 류) — 이 부채의 구조적 원인 해소. 도입 시 조립만으로 자동 반영
- **`§1.4` 표기 ↔ `DEVELOPMENT.md` 절번호 충돌** — 9파일 14곳 `§1.<규칙번호>` 방언의 별건 일괄 정리 (DEC-17)
- **slack 인바운드 경로 rate limit** (R1)
- `AutomationWebhookController`의 actor 임의 지정 (별도 FR 후보)
</content>
</invoke>

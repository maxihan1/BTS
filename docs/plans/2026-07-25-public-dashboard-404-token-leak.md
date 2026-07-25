# 공개 대시보드 404 응답 토큰 노출 검증 및 봉합

> slug: public-dashboard-404-token-leak
> type: auth (classify 원출력 `qa` → 상향 조정. 사유는 §Brief)
> agent: security-engineer (BC = notification, 대상 = permitAll 익명 경로)
> 생성: 2026-07-25

## Brief

**사용자 원문.**
> PublicDashboardController의 404 응답 본문에 공유 대시보드 토큰이 노출되는지 실제 응답으로 검증하고,
> 갭이 확인되면 봉합 + 회귀 테스트 추가 (notification-dashboard BC)

**출처.** 2026-07-25 세션 체크포인트(`#309` 머지 직후)의 보안 트랙 잔여 2건 중 PR B.
원 지목은 FR-UX-06 PR22(#308) 시각 확인 과정에서 파생된 `PRE_EXISTING` 후속 목록.

**classify 정정.** `classify-task.ts` 원출력은 `type=qa · agent=qa-engineer`.
`"검증"`·`"회귀 테스트"` 키워드가 끌어당긴 오분류로 판단해 `type=auth`로 상향했다.
근거 2가지.
1. `qa-engineer`는 정의상 **`src/` 구현 코드 수정 금지**(`.claude/agents/qa-engineer.md`)인데
   본 작업은 "갭이 확인되면 봉합"을 포함해 프로덕션 Kotlin 수정 가능성이 있다.
2. 대상이 **인증 없이 열린(permitAll) 익명 엔드포인트의 정보 노출**이라 보안 영역이다.
   선례 — #309도 같은 성격(가드 누락)으로 `auth/` 브랜치를 썼다.
상향은 검토 강도를 **높이는** 방향이라 단독 판단으로 진행했다(Maxi 보고 완료).

**사전 조사(착수 전 read-only, 미확정 가설 포함).**
- `PublicDashboardController.kt:80-92` — 404 핸들러가 `pd.instance`를 **설정하지 않는다**
  (`type`·`title`·`detail`·`errorCode`·`timestamp`만 설정).
- 경로 = `GET /api/v1/public/dashboards/{token}` — **원문 토큰이 URI 경로 세그먼트에 있다.**
- Spring Boot **3.3.5** (Spring Framework 6.1.x).
- 기존 슬라이스 테스트 `PublicDashboardControllerTest.kt`는 `$.errorCode`·`$.detail`만 단언하고
  **`$.instance`를 검사하지 않는다** → 새더라도 현 테스트는 초록이다.
- ⚠️ **가설 (미검증)** — Spring MVC가 `ProblemDetail.instance == null`일 때 요청 URI로 자동 채우면
  404 본문에 원문 토큰이 실린다. **소스 리딩으로 단정 금지. red 테스트로 실증한다.**
- ⚠️ **체크포인트 전제 정정 후보** — 원 메모는 "백엔드 기동 필요(dev postgres 5433)"라 했으나,
  `instance` 자동 채움은 Spring MVC 메시지 컨버터 반환값 처리 단계라 **MockMvc 슬라이스가 같은 경로를
  탄다**는 것이 현재 판단. 이 역시 가설이므로 슬라이스 결과와 상위 레벨(부팅) 결과의 일치를 별도 확인한다.

**성공 기준.**
1. 404 응답 본문의 실제 형태를 **증거로** 확정한다(추정 금지).
2. 토큰이 실린다면 봉합하고, 실리지 않는다면 그 상태를 **회귀 테스트로 못박는다**.
   두 경우 모두 산출물이 있다 — "문제 없었음"으로 끝내지 않는다.
3. 판정 오라클(슬라이스 vs 실기동)이 서로 어긋나지 않음을 확인한다.

## 도메인 정리

- **BC**: notification (단일). 대시보드 영역 `com.bts.notification.dashboard`.
- **영향 엔티티**: DashboardShareToken (기존, 변경 없음). 신규 엔티티 0.
- **새 용어**: 0건. 기존 용어 `공유 토큰(Dashboard Share Token)`만 사용.
- **기존 결정 충돌**: 없음. 오히려 **기존 결정을 복원하는 작업**이다.

### 근거가 된 기존 결정

`docs/decisions/2026-07-02-fr-db-03-dashboard-share.md`
- **D1** — 공유 토큰은 불투명(opaque) 랜덤 토큰. **원문은 발급 응답에서 1회만 노출**, DB엔 SHA-256 해시만
  (유출 시 원문 복원 불가). 즉 *"원문 토큰이 나가는 응답은 발급 응답 하나뿐"* 이 확립된 불변식이다.
- **Consequences** — "BTS 첫 비인증 읽기 경로 → 계정 열거/토큰 probe 방어를 spec에서 명시".

`DEVELOPMENT.md §1.1-1 / §1.1-2` — 평문 비밀값 저장·로깅 금지.

### 실증 결과 (RED 확정, 2026-07-25)

MockMvc 슬라이스 프로브로 404 응답 본문을 실측했다. **postgres 기동 불요** — `instance` 자동 채움은
Spring MVC 반환값 처리 단계라 슬라이스가 같은 경로를 탄다(체크포인트의 "dev postgres 5433 필요" 전제 정정).

```json
{"type":"...","title":"Dashboard Not Found","status":404,"detail":"...",
 "instance":"/api/v1/public/dashboards/share_SUPERSECRETTOKEN_0123456789abcdef",
 "errorCode":"NOTIF_DASHBOARD_NOT_FOUND","timestamp":"..."}
```
→ `TOKEN_IN_BODY=true`. **원문 공유 토큰이 404 본문에 실려 나간다.**

### ★정본 수정 패턴이 이미 레포에 존재한다

같은 결함 클래스가 **automation BC 에서는 이미 발견·봉합**돼 있었다.

| 컨트롤러 | BC | `instance` 명시 | 상태 |
|---|---|---|---|
| `AutomationWebhookController:212` | automation | `URI.create("/api/v1/automation/webhooks")` | ✅ 봉합 |
| `GitWebhookController:416` | automation | `URI.create("/api/v1/webhooks/git")` | ✅ 봉합 |
| `PublicDashboardController` | notification | **없음** | ❌ **유출 확정** |
| `IcalFeedController` | identity-access | (ProblemDetail 미사용, `ResponseStatusException`) | ⚠️ **미실증** |

`AutomationWebhookController` KDoc(L187-195)이 기전·근거를 이미 문서화해 놓았다 — 인용.
> `instance` 를 반드시 명시한다 — 비우면 Spring 이 원문 토큰을 응답에 싣는다.
> `RequestResponseBodyMethodProcessor` 는 `instance` 가 null 이면 요청 URI 로 자동 채운다. (…)
> 보낸 사람이야 이미 아는 값이지만, 그 본문이 **응답 로그·프록시 캐시·에러 트래커에 적재되는 순간
> 그것이 평문 토큰 저장/로깅**이다(DEVELOPMENT.md §1.1-1·§1.1-2).

→ 본 작업은 **새 설계가 아니라 확립된 패턴의 미적용 구멍을 메우는 것**이다. 수정 형태는 이미 정해져 있다.

### 전수 열거 — `instance` 를 명시 설정하는 곳

main 소스 전수 grep 결과 **정확히 2곳**(위 automation 2건)뿐이고, 나머지 **44개 ProblemDetail 생산자는
전부 Spring 자동 채움에 맡긴다**. 대부분은 경로에 비밀값이 없어 무해하다(이슈키·프로젝트키는 비밀이 아니다).
**경로에 비밀값이 있는데 `instance` 를 안 채우는 곳 = 2곳**(PublicDashboard 확정 · IcalFeed 미실증).

### 구조적 재발 위험 (설계 결정 필요)

automation 2건은 2026-07 중순에 고쳤는데 `PublicDashboardController`(2026-07-02 도입)는 **3주 넘게
같은 결함으로 남아 있었다**. 개별 봉합만으로는 재발한다는 증거다
(memory `guard-handler-matrix-blindfold` — "개수 말고 행렬 전수열거 + 판별자").
신규 비밀-경로 엔드포인트가 `instance` 를 비우면 **자동으로 실패하는 장치**가 필요한지 게이트 1에서 판정.

### grill-with-docs

**미실행.** 신규 엔티티·용어·도메인 결정이 0건이고, 기존 ADR(D1)이 이미 불변식을 확정해 놓은
"기존 결정 복원" 작업이라 도메인 문답의 산출물이 없다. #309 선례(D4 Maxi 승인) 동형.
→ **Maxi 확인 대상** (아래 게이트 질의에 포함).

- **관련 ADR**: `docs/decisions/2026-07-02-fr-db-03-dashboard-share.md` (신규 ADR 생성 없음 — 신규 결정 0.
  단, 재발 방지 장치를 도입하기로 하면 그건 신규 결정이라 ADR 필요)

## 스펙

전체 스펙. [docs/specs/2026-07-25-public-dashboard-404-token-leak.md](../specs/2026-07-25-public-dashboard-404-token-leak.md)

핵심 3줄 요약.
- `/api/v1/public/dashboards/{token}` 의 **오류 응답 본문에 원문 공유 토큰이 실려 나간다** —
  404(컨트롤러-로컬)와 500(advice catch-all) **두 통로 모두 실측 확인**.
- 수정은 `instance` 를 **토큰 세그먼트를 뺀 고정 경로**로 설정하는 것. automation BC 2건에 **이미 있는 정본 패턴**.
- 404 만 막으면 500 이 그대로 새므로 **경로 단위로 흡수**한다(설계 옵션 (b), 게이트 1 판정 대상 + ADR).

범위. notification BC 단일 · FR 129 불변 · 마이그레이션 0 · 프론트 0 · 기능 변경 0.
심각도. **P1 정보노출(위생)** — 체크포인트의 "P0" 표기를 증거 기준으로 하향(요청자는 이미 토큰을 알고 있고,
실질 위험은 에러 트래커·프록시 캐시·버그리포트로의 **평문 2차 적재**다).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 8건 — 3건 즉시 실증 해소 · 4건 스펙 반영 · 1건 별건 등재). 상세는 스펙 §Brainstorming Check.

즉시 해소한 3건.
- **G3** 프론트 `instance` 소비처 → 전수 grep 으로 **0 확정**(추정이었던 것을 사실로 승격).
- **G4** 도달 가능 오류 통로 → 서비스 본문 판독으로 **6통로 중 M1·M2 2개만 도달 가능** 확정.
- **G7 일부** `PublicDashboardNotFoundException` 은 고정 메시지라 **토큰을 품지 않음** 확인(로그 안전).

스펙에 반영한 4건.
- **G1** 재발방지 장치의 판별식 명시 + `it.each` 무음통과 방지용 **개수 하한 단언**.
- **G2** "컨트롤러-로컬이 advice 보다 우선" 단정을 **RED 실증 태스크로 전환**(틀리면 설계가 무너지는 지점).
- **G5** 토큰 검사를 문자열이 아니라 **raw 바이트**로 — 인코딩 설정에 좌우되지 않게.
- **G6** 검사 대상에 **응답 헤더** 추가.

별건 등재 1건.
- **G8** 프로브 출력의 한글 `detail` 깨짐 — MockMvc 읽기 인코딩 아티팩트로 **추정(미확정)**. G5 로 본 작업엔 무해.

## Plan

**Goal.** `/api/v1/public/dashboards/{token}` 의 모든 오류 응답에서 원문 공유 토큰을 제거하고,
같은 결함이 이 경로에 다시 생기면 테스트가 실패하도록 봉인한다.

**Architecture.** 컨트롤러-로컬 `@ExceptionHandler` 가 `@RestControllerAdvice` 보다 우선 적용되는 성질을
이용해, 공개 경로의 오류를 **컨트롤러 안에서 전부 흡수**하고 각 핸들러가 `instance` 를 토큰 세그먼트가 없는
고정 경로로 설정한다(automation BC 정본 패턴 동형). 인증 대시보드 경로는 기존 advice 를 그대로 쓰므로
진단용 `instance` 를 잃지 않는다.

**Tech Stack.** Kotlin 2.0 / Spring Boot 3.3.5 / MockMvc 슬라이스 / MockK / JUnit5 / Gradle.

**기준선 (실측).** notification 모듈 `@Test` **429개** · `PublicDashboardControllerTest` **5개** ·
main HEAD `011d3df9b` · 브랜치 `auth/public-dashboard-404-token-leak`.

**전 task 공통.** 작업 디렉토리는 **절대 경로**로 지정한다
(memory — `cd ..` 가 `apps/` 로 떨어져 커밋이 조용히 안 된 사고). Gradle 은 `backend/` 에서 실행한다.
파이프 뒤 `$?` 금지, `set -o pipefail` 사용 (memory `zsh-pipestatus-1-based-false-green`).

---

### Task 1. RED — 오류 응답 행렬 회귀 테스트 신설

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboardErrorTokenLeakTest.kt`]
- depends-on: []

기존 `PublicDashboardControllerTest` 를 건드리지 않고 **별도 파일**로 만든다. 이유 2가지 —
(1) 기존 파일은 advice 를 **일부러 등록 안 하는** 컨텍스트라 목적이 다르고, (2) 그 의도를 훼손하면
"advice 없이도 컨트롤러-로컬이 404 를 낸다" 는 기존 증명이 사라진다.

- [ ] **Step 1: 실패 테스트 작성**

파일 `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboardErrorTokenLeakTest.kt`

```kotlin
// 공개 대시보드 오류응답 토큰 유출 회귀 가드 — 전 오류통로 × 본문/헤더 바이트 검사

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.application.PublicDashboardNotFoundException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * 공개 대시보드 오류 응답 토큰 유출 회귀 가드.
 *
 * ## 왜 별도 파일인가
 * [PublicDashboardControllerTest] 는 `DashboardExceptionHandler` 를 **일부러 등록하지 않는다**
 * (컨트롤러-로컬 404 매핑만으로 성립함을 증명하는 것이 그 파일의 목적). 이 파일은 반대로
 * **프로덕션과 동일하게 advice 를 함께 등록**해, advice 경로로 새는 유출을 관측한다.
 * `@RestControllerAdvice(basePackages = ["com.bts.notification.dashboard.web"])` 이고
 * [PublicDashboardController] 가 바로 그 패키지에 있으므로 프로덕션에서는 advice 가 적용된다.
 *
 * ## 왜 바이트로 검사하는가
 * `contentAsString` 은 응답 문자 인코딩 설정에 좌우된다. 실제로 **회선에 나가는 것**을 재기 위해
 * `contentAsByteArray` 를 UTF-8 로 읽어 검사한다. 헤더도 함께 본다 — 본문만 보면 헤더 유출을 놓친다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [PublicDashboardErrorTokenLeakTest.TestMvcConfig::class])
@WebAppConfiguration
class PublicDashboardErrorTokenLeakTest {
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun dashboardService(): DashboardService = mockk(relaxed = true)

        @Bean
        open fun publicDashboardController(service: DashboardService) = PublicDashboardController(service)

        /** 프로덕션과 동일하게 advice 도 등록한다 — 이 등록이 이 파일의 존재 이유다. */
        @Bean
        open fun dashboardExceptionHandler() = DashboardExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: DashboardService

    private lateinit var mockMvc: MockMvc

    private val secretToken = "share_LEAKCANARY_0123456789abcdef"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    private fun call(): MvcResult = mockMvc.perform(get("/api/v1/public/dashboards/{token}", secretToken)).andReturn()

    /** 본문(바이트) + 전 헤더값에 토큰이 없어야 한다. */
    private fun assertNoTokenAnywhere(result: MvcResult) {
        val bodyBytes = String(result.response.contentAsByteArray, Charsets.UTF_8)
        assertThat(bodyBytes)
            .describedAs("응답 본문(raw bytes)에 원문 토큰이 실렸다")
            .doesNotContain(secretToken)

        val headerDump =
            result.response.headerNames.joinToString("\n") { name ->
                "$name: ${result.response.getHeaders(name).joinToString(",")}"
            }
        assertThat(headerDump)
            .describedAs("응답 헤더에 원문 토큰이 실렸다")
            .doesNotContain(secretToken)
    }

    /** M1. 컨트롤러-로컬 404 — 무효/만료/부모삭제가 모두 수렴하는 통로. */
    @Test
    fun `M1 — 404 응답에 원문 토큰이 없다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()

        val result = call()

        assertThat(result.response.status).isEqualTo(404)
        assertNoTokenAnywhere(result)
    }

    /** M2. 분류되지 않은 예외 → 500. advice catch-all 이 잡던 통로. */
    @Test
    fun `M2 — 500 응답에 원문 토큰이 없다`() {
        every { service.getPublicByToken(secretToken) } throws IllegalStateException("boom")

        val result = call()

        assertThat(result.response.status).isEqualTo(500)
        assertNoTokenAnywhere(result)
    }

    /**
     * G2 실증 — 컨트롤러-로컬 핸들러가 advice 보다 **우선 적용**된다.
     *
     * 이 성질이 설계의 토대다. 틀리면 (b) 안 전체가 무너지므로 단정하지 않고 관측한다.
     * advice 가 등록된 상태에서도 404 의 errorCode 가 컨트롤러-로컬 값이면 우선 적용이 확정된다.
     */
    @Test
    fun `G2 — advice 가 등록돼 있어도 컨트롤러 로컬 핸들러가 우선 적용된다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()

        val body = String(call().response.contentAsByteArray, Charsets.UTF_8)

        assertThat(body).contains("NOTIF_DASHBOARD_NOT_FOUND")
    }

    /** R3 회귀가드 — 기능은 한 글자도 바뀌지 않는다(상태·errorCode·detail 불변). */
    @Test
    fun `R3 — 404 의 상태코드 errorCode detail 이 기존과 동일하다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()

        val result = call()
        val body = String(result.response.contentAsByteArray, Charsets.UTF_8)

        assertThat(result.response.status).isEqualTo(404)
        assertThat(body).contains("NOTIF_DASHBOARD_NOT_FOUND")
        assertThat(body).contains("공유된 대시보드를 찾을 수 없습니다.")
    }

    /** R3 회귀가드 — 500 의 상태·errorCode·detail 도 advice 시절과 동일해야 한다(drift 금지). */
    @Test
    fun `R3 — 500 의 상태코드 errorCode detail 이 기존과 동일하다`() {
        every { service.getPublicByToken(secretToken) } throws IllegalStateException("boom")

        val result = call()
        val body = String(result.response.contentAsByteArray, Charsets.UTF_8)

        assertThat(result.response.status).isEqualTo(500)
        assertThat(body).contains("NOTIF_DASHBOARD_INTERNAL_ERROR")
        assertThat(body).contains("서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")
    }

    /**
     * E4 — 퍼센트 인코딩된 토큰도 디코딩된 원문이 응답에 실리지 않는다.
     *
     * `instance` 를 고정값으로 덮으면 인코딩 여부와 무관해지지만, **그렇다는 것을 테스트가 말해야 한다.**
     * 이 케이스가 없으면 나중에 누가 `instance` 를 다시 요청 URI 로 되돌렸을 때
     * "인코딩된 형태라 안전하다" 는 잘못된 안심이 가능해진다.
     */
    @Test
    fun `E4 — 퍼센트 인코딩된 토큰도 응답에 실리지 않는다`() {
        val rawToken = "share_ENC ODED+CANARY/0123"
        every { service.getPublicByToken(rawToken) } throws PublicDashboardNotFoundException()

        val result = mockMvc.perform(get("/api/v1/public/dashboards/{token}", rawToken)).andReturn()
        val body = String(result.response.contentAsByteArray, Charsets.UTF_8)

        assertThat(result.response.status).isEqualTo(404)
        assertThat(body).doesNotContain(rawToken)
        assertThat(body).doesNotContain("ENC")
    }

    /**
     * R2 고정 — `instance` 는 **토큰 세그먼트를 뺀 정확한 경로**여야 한다 (eng-review 이슈 3).
     *
     * 다른 테스트는 전부 "토큰이 없다" 만 본다. 그것만으로는 값이 엉뚱하게 바뀌어도 통과하므로
     * 스펙 R2 가 검증되지 않은 채 남는다. 404·500 두 통로 모두에서 값을 고정한다.
     */
    @Test
    fun `R2 — instance 는 토큰 세그먼트를 뺀 고정 경로다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()
        assertThat(String(call().response.contentAsByteArray, Charsets.UTF_8))
            .contains("\"instance\":\"/api/v1/public/dashboards\"")

        every { service.getPublicByToken(secretToken) } throws IllegalStateException("boom")
        assertThat(String(call().response.contentAsByteArray, Charsets.UTF_8))
            .contains("\"instance\":\"/api/v1/public/dashboards\"")
    }
}
```

- [ ] **Step 2: 실패 확인 (RED)**

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:test --tests "com.bts.notification.dashboard.web.PublicDashboardErrorTokenLeakTest" 2>&1 | tail -25
echo "EXIT=$?"
```

기대. 이 파일의 테스트는 **7건**(M1 · M2 · G2 · R3-404 · R3-500 · E4 · R2).
**FAIL 4건** — `M1`·`M2`·`E4` 는 "응답 본문(raw bytes)에 원문 토큰이 실렸다", `R2` 는 instance 가
고정 경로가 아니라 토큰이 붙은 요청 URI 라서 실패한다.
**PASS 3건** — `G2`·`R3-404`·`R3-500`(현재 동작을 기록하는 회귀가드). **EXIT != 0** 이어야 한다.

⚠️ FAIL 이 4건이 아니면 멈추고 원인을 본다. 특히 **M1·M2 가 PASS 로 나오면 오라클이 고장 난 것**이다
(바이트 검사가 실제로 본문을 못 읽고 있을 수 있다) — 프로브에서 이미 유출을 실측했으므로 PASS 는 불가능하다.

⚠️ **G2 가 이 시점에 FAIL 하면 설계 (b) 의 전제가 깨진 것이다.** 즉시 중단하고 Maxi 에게 보고한다.

- [ ] **Step 3: 커밋 (test: 선행 — TDD 강제)**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" add backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboardErrorTokenLeakTest.kt
git -C "$WT" commit -m "test: 공개 대시보드 오류응답 토큰 유출 회귀 가드 (RED — M1/M2 실패)"
```

**검증**. `EXIT != 0` + FAIL 2건이 M1·M2 인지 이름으로 확인.

---

### Task 2. GREEN — 컨트롤러-로컬 흡수 + `instance` 고정

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/PublicDashboardController.kt`]
- depends-on: [1]

- [ ] **Step 1: 최소 구현**

`PublicDashboardController.kt` 를 다음과 같이 바꾼다.

> **리뷰 반영.** eng-review 이슈 1·2·4 를 A 안으로 채택해 원안에서 3곳이 바뀌었다 —
> ① `ResponseStatusException` 분기 **삭제**(항상 500), ② `problem()` **헬퍼 추출**(automation 정본 동형),
> ③ 클래스 KDoc 에 **예외 해소 순서 ASCII 다이어그램** 추가. import 로 `ResponseStatusException` 을
> 추가할 필요가 없어졌다(원안 (1) 폐기).

(1) 클래스 KDoc 에 예외 해소 순서 다이어그램을 추가한다 — 이 파일이 앞으로 오해받을 자리를 그림으로 막는다.

```kotlin
 * ## 예외 해소 순서 — 왜 이 컨트롤러가 500 핸들러를 직접 갖는가
 * Spring 은 컨트롤러 클래스의 @ExceptionHandler 를 **먼저** 찾고, 없을 때만 @ControllerAdvice 로 간다.
 * 이 컨트롤러가 Exception catch-all 을 가지므로 **모든 예외가 여기서 끝난다** — 의도된 설계다.
 * 공개 경로는 정화가 기본값이어야 하고, advice 의 일반 응답이 흘러들면 instance 로 토큰이 샌다.
 *
 *   요청 GET /api/v1/public/dashboards/{token}
 *          │
 *          ├─ 정상 ─────────────────────────────▶ 200 DataResponse (ProblemDetail 아님, instance 없음)
 *          │
 *          └─ 예외 발생
 *               │
 *               ▼
 *      ┌────────────────────────────────┐
 *      │ ① 컨트롤러-로컬 @ExceptionHandler │ ◀── 항상 여기서 매치된다
 *      ├────────────────────────────────┤
 *      │ PublicDashboardNotFound → 404  │──┐
 *      │ Exception (catch-all)   → 500  │──┤   둘 다 problem() 을 거친다
 *      └────────────────────────────────┘  │   → instance = INSTANCE_PATH (토큰 세그먼트 없음)
 *               ╎ (도달하지 않음)            │
 *               ▼                          ▼
 *      ┌────────────────────────────────┐  응답 본문·헤더에 원문 토큰 0
 *      │ ② DashboardExceptionHandler    │
 *      │    advice — instance 미설정     │  ◀── 인증 경로(/api/v1/dashboards/**)는 계속 여기를 쓴다.
 *      └────────────────────────────────┘      비밀값이 없어 요청 URI 를 남기는 편이 진단에 유리하다.
 *
 * ⚠️ 이 컨트롤러의 catch-all 을 "advice 와 중복" 이라며 지우면 즉시 토큰 유출로 회귀한다.
 *    `PublicDashboardErrorTokenLeakTest` 가 그 회귀를 잡는다.
```

(2) 두 핸들러를 `problem()` 헬퍼 위로 올린다 — `instance` 설정을 **단일 지점**으로 만들어,
새 핸들러를 추가하는 사람이 그 줄을 빠뜨리는 것을 구조적으로 불가능하게 한다(이슈 2).

```kotlin
    @ExceptionHandler(PublicDashboardNotFoundException::class)
    fun handleNotFound(
        @Suppress("UnusedParameter") ex: PublicDashboardNotFoundException,
    ): ProblemDetail {
        log.debug("NOTIF_DASHBOARD_404 public_not_found")
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "dashboard-not-found",
            title = "Dashboard Not Found",
            errorCode = "NOTIF_DASHBOARD_NOT_FOUND",
            detail = "공유된 대시보드를 찾을 수 없습니다.",
        )
    }

    /**
     * 분류되지 않은 모든 예외 — 500. **공개 경로 전용 catch-all.**
     *
     * `DashboardExceptionHandler` advice 도 같은 매핑을 갖지만 그 `problem()` 은 `instance` 를 비워 두므로
     * 이 경로로 흘러가면 **원문 토큰이 응답에 실린다**. 컨트롤러-로컬이 advice 보다 먼저 매치되는 성질로
     * 공개 경로의 오류를 여기서 흡수한다(위 KDoc 다이어그램 ①).
     * 상태·errorCode·detail 은 advice 와 **한 글자도 다르지 않게** 유지한다(응답 drift 금지).
     *
     * ## 상태 코드를 분기하지 않는 이유
     * `ResponseStatusException` 의 상태를 보존하려 `HttpStatus.valueOf(...)` 를 쓰면 **비표준 코드에서
     * 그 호출이 예외를 던져 핸들러 자체가 실패**하고, Spring 기본 오류 처리(`/error`)로 넘어가 응답
     * `path` 에 **다시 원문 토큰이 실린다** — 막으려던 것을 되살리는 경로다. 이 경로의 서비스는
     * `ResponseStatusException` 을 던지지 않아(=도달 불가) 상태 보존의 실익이 없으므로, 분기를 두지 않고
     * 전부 500 으로 수렴시킨다. `SEAL` 테스트가 새 통로 추가를 감시한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnclassified(ex: Exception): ProblemDetail {
        log.error("NOTIF_DASHBOARD_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "dashboard-internal-error",
            title = "Dashboard Internal Server Error",
            errorCode = "NOTIF_DASHBOARD_INTERNAL_ERROR",
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    /**
     * ProblemDetail 조립 헬퍼 (`AutomationWebhookController.problem` 동형).
     *
     * ## ★ `instance` 를 반드시 명시한다 — 비우면 Spring 이 원문 토큰을 응답에 싣는다
     * `RequestResponseBodyMethodProcessor` 는 `instance` 가 `null` 이면 요청 URI 로 자동 채운다.
     * 이 엔드포인트의 요청 URI 에는 **경로 세그먼트에 원문 공유 토큰**이 있으므로, 비워 두면 404·500
     * **모든** 오류 응답 본문에 평문 토큰이 실려 나간다. 보낸 사람이야 아는 값이지만 그 본문이
     * 응답 로그·프록시 캐시·에러 트래커에 적재되는 순간 그것이 **평문 토큰 저장/로깅**이다
     * (DEVELOPMENT.md §1.1-1·§1.1-2 · ADR 2026-07-02 D1 "원문은 발급 응답에서 1회만").
     *
     * **모든 오류 응답이 이 한 함수를 지나게 두는 것이 설계의 핵심**이다 — 핸들러마다 `instance` 를
     * 기억해서 넣는 구조였다면 언젠가 빠뜨린다(실제로 이 컨트롤러가 3주간 그 상태였다).
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.instance = URI.create(INSTANCE_PATH)
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    private companion object {
        /**
         * ProblemDetail `instance` 고정값 — **토큰 세그먼트를 뺀** 엔드포인트 경로.
         * 비워 두면 Spring(`RequestResponseBodyMethodProcessor`)이 원문 토큰이 든 요청 URI 로 채운다.
         * automation BC 의 `AutomationWebhookController.INSTANCE_PATH` · `GitWebhookController.INSTANCE_PATH` 동형.
         */
        const val INSTANCE_PATH = "/api/v1/public/dashboards"
    }
```

- [ ] **Step 2: 통과 확인 (GREEN)**

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:test --tests "com.bts.notification.dashboard.web.PublicDashboard*" 2>&1 | tail -20
echo "EXIT=$?"
```

기대. `PublicDashboardErrorTokenLeakTest` **7건** + `PublicDashboardControllerTest` **5건** 전량 PASS, `EXIT=0`.

⚠️ 기존 5건이 깨지면 **기능 변경이 일어난 것**이다(R3 위반). 즉시 원인 규명.

- [ ] **Step 3: 커밋**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" add backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/PublicDashboardController.kt
git -C "$WT" commit -m "fix: 공개 대시보드 오류응답 instance 고정 — 원문 공유 토큰 노출 봉합"
```

**검증**. `EXIT=0` + 10건 PASS.

---

### Task 3. 재발 방지 — `@ExceptionHandler` 파생 열거 + 미분류 실패

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboardErrorTokenLeakTest.kt`]
- depends-on: [2]

핵심 — **개수를 세지 않는다. 열거하고, 목록에 없으면 실패시킨다** (#309 "미분류 = 실패" 동형).

- [ ] **Step 1: 판별식 테스트 추가**

같은 파일 하단에 추가한다. import 에 `org.springframework.web.bind.annotation.ExceptionHandler` 를 더한다.

```kotlin
    /**
     * ★ 재발 방지 봉인 — 공개 컨트롤러의 오류 통로를 **파생 열거**해 미분류를 실패시킨다.
     *
     * `PublicDashboardController` 에 새 `@ExceptionHandler` 를 추가하면 이 기대 맵도 함께 갱신해야만
     * 초록이 된다. "핸들러를 하나 더 만들었는데 아무도 토큰 유출을 확인하지 않는" 상태를 구조적으로 막는다.
     *
     * 각 항목의 값은 그 예외 타입을 실제로 발생시키기 위한 **표본 예외**다. 표본으로 요청을 태워
     * 본문·헤더에 토큰이 없음을 확인한다 — 선언만 세는 vacuous 검사가 아니다.
     */
    @Test
    fun `SEAL — 선언된 모든 오류 통로가 분류돼 있고 각각 토큰을 흘리지 않는다`() {
        val samples: Map<Class<out Throwable>, Throwable> =
            mapOf(
                PublicDashboardNotFoundException::class.java to PublicDashboardNotFoundException(),
                Exception::class.java to IllegalStateException("boom"),
            )

        val declared: Set<Class<out Throwable>> =
            PublicDashboardController::class.java.declaredMethods
                .filter { it.isAnnotationPresent(ExceptionHandler::class.java) }
                .flatMap { it.getAnnotation(ExceptionHandler::class.java).value.toList() }
                .map { it.java }
                .toSet()

        // (1) 무음 통과 방지 — 파생 목록이 비면 아래 루프가 0회 돌고도 초록이 된다.
        //     (memory: guard-handler-matrix-blindfold — it.each(파생목록) 무음통과 2차 재발)
        assertThat(declared)
            .describedAs("파생 열거가 비었다 — 리플렉션 판별식 자체가 고장 났다")
            .hasSizeGreaterThanOrEqualTo(2)

        // (2) 미분류 = 실패. 새 핸들러를 추가했다면 위 samples 에 표본을 등재해야 한다.
        assertThat(declared)
            .describedAs("분류되지 않은 @ExceptionHandler 가 있다 — samples 에 표본을 등재하라")
            .containsExactlyInAnyOrderElementsOf(samples.keys)

        // (3) 표본마다 실제 HTTP 응답을 받아 토큰 부재를 확인한다.
        samples.forEach { (type, sample) ->
            every { service.getPublicByToken(secretToken) } throws sample
            val result = call()
            val body = String(result.response.contentAsByteArray, Charsets.UTF_8)
            assertThat(body)
                .describedAs("통로 ${type.simpleName} 의 응답 본문에 원문 토큰이 실렸다")
                .doesNotContain(secretToken)
        }
    }
```

- [ ] **Step 2: 통과 확인**

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:test --tests "com.bts.notification.dashboard.web.PublicDashboardErrorTokenLeakTest" 2>&1 | tail -15
echo "EXIT=$?"
```

기대. 이 파일 **8건**(기존 7 + `SEAL`) 전량 PASS, `EXIT=0`.

- [ ] **Step 3: 봉인이 진짜 작동하는지 확인 (일부러 위반)**

`PublicDashboardController` 에 더미 핸들러를 **임시로** 추가한다.

```kotlin
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleTemp(ex: IllegalArgumentException): ProblemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)
```

테스트를 돌려 `SEAL` 이 **FAIL** 하는지 본다("분류되지 않은 @ExceptionHandler 가 있다").
확인 후 **더미를 반드시 되돌린다** (`git checkout --` 이 아니라 손으로 삭제 — 미커밋 작업 소실 방지,
memory `mutation-test-requires-committed-baseline`). 되돌린 뒤 다시 초록임을 재확인.

- [ ] **Step 5: 커밋**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" add backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboardErrorTokenLeakTest.kt
git -C "$WT" commit -m "test: 오류통로 파생열거 봉인 — 미분류 @ExceptionHandler 실패 + 개수 하한"
```

**검증**. 위반 주입 시 FAIL · 원복 시 PASS 둘 다 관측 (vacuous 아님 증명).

---

### Task 4. R4 회귀가드 — 인증 경로 `instance` 불변

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardExceptionHandlerInstanceTest.kt`]
- depends-on: [2]

공개 경로만 고정값으로 덮었고 **인증 경로의 진단 정보는 그대로**임을 못박는다.
이 가드가 없으면 나중에 누가 advice 의 `problem()` 에 고정 instance 를 박아도 아무도 모른다.

- [ ] **Step 1: 테스트 작성**

`DashboardExceptionHandler` 를 advice 로 등록한 최소 컨텍스트에 **인증 경로를 흉내내는 테스트 전용 컨트롤러**를
두고, 오류 응답의 `instance` 가 **요청 URI 그대로**인지 단언한다.

```kotlin
// advice 의 instance 동작 회귀 가드 — 인증(비밀값 없는) 경로는 요청 URI 를 유지해야 한다

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * `DashboardExceptionHandler` 의 `instance` 동작 회귀 가드.
 *
 * 공개 경로(`/api/v1/public/dashboards/{token}`)는 토큰 노출 때문에 `instance` 를 고정값으로 덮지만,
 * **비밀값이 경로에 없는 인증 대시보드 경로는 요청 URI 를 유지**해야 한다(진단 가치 보존).
 * 이 가드가 advice 에 고정 instance 를 박는 회귀를 막는다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DashboardExceptionHandlerInstanceTest.TestMvcConfig::class])
@WebAppConfiguration
class DashboardExceptionHandlerInstanceTest {
    @RestController
    class ProbeController {
        @GetMapping("/api/v1/dashboards/probe-not-found")
        fun notFound(): Nothing = throw DashboardNotFoundException("probe")
    }

    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun probeController() = ProbeController()

        @Bean
        open fun dashboardExceptionHandler() = DashboardExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    /** R4 — 인증 경로 오류 응답의 instance 는 요청 URI 그대로여야 한다. */
    @Test
    fun `인증 경로 오류 응답의 instance 는 요청 URI 를 유지한다`() {
        val result = mockMvc.perform(get("/api/v1/dashboards/probe-not-found")).andReturn()
        val body = String(result.response.contentAsByteArray, Charsets.UTF_8)

        assertThat(body).contains("\"instance\":\"/api/v1/dashboards/probe-not-found\"")
    }
}
```

⚠️ `DashboardNotFoundException` 의 생성자 시그니처를 **먼저 실물로 확인**하고 맞춘다
(`DashboardExceptions.kt` 를 열어 확인. 추정 금지 — memory `frontend-zod-backend-dto-contract-gap` 의 결).
advice 의 `ProbeController` 는 `com.bts.notification.dashboard.web` 패키지 안이라 basePackages 스코프에 든다.

- [ ] **Step 2: 통과 확인 + 커밋**

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:test --tests "com.bts.notification.dashboard.web.DashboardExceptionHandlerInstanceTest" 2>&1 | tail -12
echo "EXIT=$?"
```

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" add backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardExceptionHandlerInstanceTest.kt
git -C "$WT" commit -m "test: R4 회귀가드 — 인증 경로 instance 는 요청 URI 유지"
```

**검증**. PASS + `EXIT=0`.

---

### Task 5. 뮤테이션 검증 — 가드가 vacuous 하지 않음을 증명

**메타**.
- agent: `security-engineer`
- files: []  (일시 수정 후 전량 원복 — 최종 diff 0)
- depends-on: [3, 4]

**전제.** 반드시 **커밋된 상태**에서 수행한다 (memory `mutation-test-requires-committed-baseline` —
미커밋 상태에서 원복하면 그 파일의 작업이 소실된다). 먼저 기준선이 EXIT=0 인지 확인한다
(memory `verify-logic-vs-verify-guard`).

- [ ] **Step 1: 기준선 확인**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" status --porcelain    # 비어 있어야 한다(프로브 파일 제외)
cd "$WT/backend"; set -o pipefail
./gradlew :modules:notification:test 2>&1 | tail -5; echo "BASELINE_EXIT=$?"
```

- [ ] **Step 2: 뮤테이션 4종**

> **리뷰 반영 (이슈 3).** `problem()` 헬퍼 추출로 `instance` 설정이 **단일 지점**이 됐고,
> `R2` 테스트가 값을 고정하게 됐다. 뮤테이션 목록을 그에 맞게 정정한다 — 특히 **D 의 기대값이
> green → red 로 뒤집힌다**(값을 고정했으므로 대조군의 의미가 달라졌다).

| # | 뮤테이션 | 기대 |
|---|---|---|
| A | `problem()` 의 `pd.instance = …` 줄 삭제 | M1 · M2 · R2 · SEAL **red** (단일 지점이라 전 통로가 함께 무너진다) |
| B | `handleUnclassified` 전체 삭제 (advice 로 되돌림) | M2 · R2 · SEAL **red** |
| C | `handleNotFound` 를 `problem()` 미경유 직접 조립으로 되돌림 | M1 · R2 **red** (헬퍼 우회 회귀 탐지) |
| D | `INSTANCE_PATH` 를 `"/api/v1/public/dashboards/x"` 로 변경 | **R2 red · M1/M2 green** — 값 고정은 R2 만, 토큰 부재 단언은 값에 무관해야 정상 |

각 뮤테이션마다 테스트를 돌려 red/green 을 기록하고 **손으로 원복**한다.
**D 가 판별력의 핵심 대조군**이다 — R2 만 red 이고 M1/M2 가 green 이어야 한다.
D 에서 M1/M2 까지 red 가 되면 토큰 부재 단언이 경로 문자열에 과잉 결합된 것이고,
R2 가 green 이면 값 고정이 실제로는 작동하지 않는 것이다. **양쪽 다 실패 신호다.**

- [ ] **Step 3: 원복 확인**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" diff --stat     # 비어 있어야 한다
cd "$WT/backend"; set -o pipefail
./gradlew :modules:notification:test 2>&1 | tail -5; echo "RESTORED_EXIT=$?"
```

**검증**. A·B·C 가 실제로 red · D 가 green · 원복 후 diff 0 + EXIT=0.

---

### Task 6. ADR + 후속 등재 + 임시 파일 정리

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-25-public-dashboard-error-instance-sanitization.md`, `docs/specs/2026-07-25-public-dashboard-404-token-leak.md`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboard404BodyProbeTest.kt`]
- depends-on: [5]

- [ ] **Step 1: ADR 작성**

`docs/decisions/2026-07-25-public-dashboard-error-instance-sanitization.md` 신설. 담을 것 —
맥락(실측 유출 2통로) · 결정(옵션 (b) 채택, (a)/(c) 기각 사유) · `ResponseStatusException` 을 rethrow 하지
않기로 한 판단과 근거 · 결과(인증 경로 불변, 재발 방지 봉인) · 잔여 위험(경로 기반 토큰 자체는 접근로그·
리퍼러에 남는다 — ADR D1 이 택한 설계라 본 결정의 범위 밖).

- [ ] **Step 2: E7 확인 — 로그 스택에 토큰이 실릴 여지**

M2(500) 경로는 `log.error("NOTIF_DASHBOARD_500 internal_error", ex)` 로 **예외 스택을 통째로 찍는다.**
`PublicDashboardNotFoundException` 은 고정 메시지라 안전함을 이미 확인했으나, catch-all 로 흘러드는
하위 컴포넌트가 토큰을 예외 메시지에 넣으면 **평문 토큰이 로그에 남는다**(§2 가 지적한 바로 그 위험).

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
cd "$WT/backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard"
# getPublicByToken 이 부르는 협력자들이 예외 메시지에 원문 토큰을 넣는지 본다
grep -rn "plaintextToken\|plaintext\b" application/ domain/ | grep -i "throw\|require\|check\|Exception\|message"
```

기대. 히트 0 (협력자가 토큰을 예외에 싣지 않음). 히트가 있으면 **본 PR 범위로 끌어와 봉합**한다 —
로그 유출은 응답 유출과 같은 결함 클래스이고 §2 의 근거(`DEVELOPMENT.md §1.1-2`)가 동일하다.
결과는 히트 유무와 무관하게 스펙 §10 E7 에 **확정 문구로** 기록한다("미확인" 으로 남기지 않는다).

- [ ] **Step 3: 후속 항목 등재**

스펙 §13 에 조사 결과를 확정 기록한다 — `IcalFeedController`(identity-access, `ResponseStatusException`
경로라 Boot `/error` 의 `path` 필드로 샐 가능성, **미실증**) · E3 비-GET 응답 · G8 인코딩 관측.

- [ ] **Step 4: 임시 프로브 삭제**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
rm "$WT/backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboard404BodyProbeTest.kt"
git -C "$WT" status --porcelain     # untracked 프로브가 사라졌는지 확인
```

- [ ] **Step 5: 커밋**

```bash
WT=/Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak
git -C "$WT" add docs/
git -C "$WT" commit -m "docs: ADR — 공개 경로 오류응답 instance 정화 + 후속 항목 등재"
```

**검증**. `git status --porcelain` 이 완전히 비어 있다.

---

### Task 7. 최종 검증 — 전량 테스트 · 개수 대조 · 린트

**메타**.
- agent: `security-engineer`
- files: []
- depends-on: [6]

- [ ] **Step 1: 전량 테스트 + 개수 실측 대조**

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:test --rerun-tasks 2>&1 | tail -10; echo "EXIT=$?"
```

```bash
# 실행된 테스트 수를 산출물로 실측한다 (콘솔 마지막 줄은 증거가 약하다)
# memory: gradle-batched-task-partial-test-run — 6/56 클래스만 돌고 SUCCESSFUL 이 난 전례가 있다
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend/modules/notification
grep -ho 'tests="[0-9]*"' build/test-results/test/TEST-*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print "실행 테스트 =", s}'
```

기대. **기준선 429 + 신규 9건 = 438 이상.**
신규 9건 = `PublicDashboardErrorTokenLeakTest` 8(M1·M2·G2·R3-404·R3-500·E4·R2·SEAL)
+ `DashboardExceptionHandlerInstanceTest` 1(R4). 감소하면 조사한다.

- [ ] **Step 2: 린트 + 정적 분석**

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:ktlintCheck 2>&1 | tail -10; echo "KTLINT_EXIT=$?"
./gradlew :modules:notification:detekt --rerun-tasks 2>&1 | tail -10; echo "DETEKT_EXIT=$?"
```

⚠️ `ktlintFormat` 은 실행하지 않는다 (memory `bts-ktlintformat-docs-commit-traps` — 다른 파일까지 포맷).
위반이 나오면 **해당 줄만 손으로** 고친다. detekt 는 `--rerun-tasks` 필수(캐시 false-green).
`MaxLineLength` 위험은 리뷰 반영으로 사라졌다(원안의 긴 `status` 대입 줄이 제거됨).
남은 후보는 KDoc 다이어그램 줄이므로 **박스 폭을 120자 안에** 그린다. detekt `TooManyFunctions` 는
`DashboardExceptionHandler` 처럼 `@Suppress` 가 필요할 수 있으나, 이 컨트롤러는 함수 4개(1 매핑 + 2 핸들러
+ 1 헬퍼)라 임계 미만이다 — **실측으로 확인하고 추측으로 `@Suppress` 를 붙이지 않는다.**

- [ ] **Step 3: 조립 부팅 영향 확인**

cross-BC `@Component` 추가가 없으므로 `:app:test` 는 불요.
그래도 **컨트롤러 시그니처가 바뀌었으므로** 컴파일 회귀만 확인한다.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/public-dashboard-404-token-leak/backend
set -o pipefail
./gradlew :modules:notification:compileKotlin :modules:notification:compileTestKotlin 2>&1 | tail -5; echo "EXIT=$?"
```

**검증**. 테스트 436 이상 · ktlint EXIT=0 · detekt EXIT=0 · baseline 신규 등재 0.

---

## Plan 메타

- task 수: **7**
- 예상 시간: 직렬 기준 약 35분 (Gradle 실행이 대부분). 병렬 wave 적용 시에도 큰 이득 없음 —
  T1→T2→T3/T4→T5→T6→T7 이 **본질적으로 직렬**(TDD red→green 순서 + 뮤테이션은 커밋 후에만).
  유일한 병렬 후보는 T3·T4 이나 둘 다 T2 산출물에 의존하고 Gradle 이 모듈 단위로 직렬화되므로 이득 없음
  (memory `bts-plan-wave-gradle-module-compile`).
- TDD 강제: **yes** — T1(`test:`) 이 T2(`fix:`) 보다 먼저 커밋된다.
- 병렬 dispatch: **없음.** 세션 지시에 따라 메인이 직접 순차 수행.
- 추가 검증: ktlintCheck · detekt(--rerun-tasks) · 뮤테이션 4종 · 봉인 위반 주입 1종 · 테스트 개수 실측 대조.
- 프론트/마이그레이션/cross-BC: **0**. `verify-master-plan.sh` 대상 파일 무변경(FR 129 불변).

## 리뷰 결과

### plan-eng-review (2026-07-25)

**Step 0 스코프 챌린지 — 축소 없음.** 프로덕션 코드 **1파일 수정**, 신규 서비스/클래스 **0개**,
신규 테스트 2파일. 8파일·2클래스 임계 미만이라 복잡도 STOP 게이트 미발동.
`instance` 를 명시하는 곳이 레포 전체에 **automation 2곳뿐**이고 나머지 44개 ProblemDetail 생산자는
경로에 비밀값이 없어 무해 — 범위가 이미 최소다.

**Architecture — 1건 (P1).** 리뷰 전 원안의 `handleUnclassified` 가
`HttpStatus.valueOf(ex.statusCode.value())` 로 상태를 분기했다. 비표준 코드에서 이 호출이 **예외를 던져
핸들러 자체를 실패**시키고, Spring 기본 `/error` 로 넘어가 응답 `path` 에 **원문 토큰이 다시 실린다** —
막으려던 유출을 되살리는 경로. 덤으로 상태 404 + 제목/errorCode "Internal Server Error" 라는 비정합도 생긴다.
→ **채택 A: 분기 삭제, 항상 500.** 이 경로의 서비스는 `ResponseStatusException` 을 던지지 않아 도달 불가라
상태 보존의 실익이 없다.

**Code Quality — 1건 (P2).** ProblemDetail 조립 6~7줄이 두 핸들러에 복붙되고, 같은 문제를 이미 푼
`AutomationWebhookController:203-218` · `GitWebhookController:405-420` 의 `problem()` 헬퍼 정본을 따르지 않았다.
→ **채택 A: `problem()` 헬퍼 추출.** 부수 효과가 본질 — `instance` 설정이 **단일 지점**이 되어
새 핸들러가 그 줄을 빠뜨리는 것이 구조적으로 불가능해진다. **재발 방지가 테스트에서 구조로 올라갔다.**

**Test — 커버리지 13/15(87%), gap 2건 → 1건 해소.**
스펙 R2("instance 는 토큰 뺀 고정 경로")를 **어떤 테스트도 고정하지 않아** 값이 바뀌어도 초록이었다
(원안 뮤테이션 D 가 "그래도 green 이 정상"이라고 적어 둔 것이 그 증거).
→ **채택 A: `R2` 테스트 신설**(404·500 양쪽 값 고정) + **뮤테이션 D 기대값을 green → red 로 정정.**
남은 gap 1건(`ResponseStatusException` 분기 무테스트)은 이슈 1 채택으로 **분기 자체가 사라져 소멸**.

**Performance — 0건.** DB 접근·N+1 없음. 오류 경로에서 `URI.create` 상수 1회. 영향 없음.

**문서 — 1건 (P3).** 이 PR 의 토대인 "컨트롤러-로컬이 advice 보다 먼저 매치된다" 는 비직관적 규칙이
표로만 설명돼 있었다. 모르는 사람이 catch-all 을 "중복"으로 지우면 즉시 유출 회귀.
→ **채택 A: 클래스 KDoc 에 예외 해소 순서 ASCII 다이어그램 + 삭제 경고.**

**Critical gap — 1건 발생 → 0건 (해소).** 이슈 1 의 `/error` 재유출 경로가 유일한 critical gap 이었고
A 채택으로 제거됐다.

**Outside voice — 미실행.** `codex` CLI 미설치(`CODEX_MODE: not_installed`)이고, 대체 경로인 Claude
서브에이전트는 **이 세션이 에이전트 dispatch 를 금지**하므로 실행 불가. 교차모델 검증 없음.
→ **잔여 위험으로 등재.** #309·#308 과 동일하게 **구현자가 자기 계획을 리뷰한 편향**이 남는다.
필요하면 `npm install -g @openai/codex` 후 재실행하거나, 게이트 2 에서 Maxi 가 별도 판단.

**BLOCKER: 없음.** (auth 타입이라 BLOCKER 는 무시 옵션이 없으나, 발생 0건.)

### plan-ceo-review — 생략 (사유 등재)

제품 범위 결정 **0건**(기능 변경 0 · FR 129 불변 · 사용자에게 보이는 변화 0 — 오류 응답의 진단용 필드
값 하나뿐). 메모리 `bts-review-plan-autoplan-overkill` + #309 선례 동형.

### 워크플로우 편차 (리뷰 단계)

- **`plan-eng-review` 의 scope gate 질의 생략** — `/bts` 체인이 리뷰 대상 plan 파일 경로를 **인자로 명시
  전달**해 확인 질문이 순수 중복. 리뷰 본문 4섹션은 생략 없이 전부 수행.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | 생략 | 제품 범위 결정 0건 (사유 등재) |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 4 issues, 1 critical gap 발생→해소 |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | 프론트 변경 0 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | 공개 API 변경 0 |

**VERDICT:** ENG CLEARED — 4건 전부 A안 채택 후 반영 완료. BLOCKER 0 · critical gap 0 · 구현 착수 가능.

**UNRESOLVED DECISIONS:**
- Outside voice(교차모델 독립 검증) 미실행 — `codex` CLI 미설치이고 Claude 서브에이전트 대체 경로는
  이 세션의 에이전트 dispatch 금지에 막힌다. 결과적으로 **구현자가 자기 계획을 리뷰한 편향**이 남는다.
  선택지 — (a) 이대로 진행(#308·#309 선례 동형), (b) `npm install -g @openai/codex` 후 재실행,
  (c) 게이트 2 에서 별도 판단. Maxi 결정 필요.

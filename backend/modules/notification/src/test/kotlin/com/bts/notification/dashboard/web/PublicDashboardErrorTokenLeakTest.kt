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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * 공개 대시보드 오류 응답 토큰 유출 회귀 가드 (FR-DB-03 봉합).
 *
 * ## 왜 별도 파일인가
 * [PublicDashboardControllerTest] 는 [DashboardExceptionHandler] 를 **일부러 등록하지 않는다**
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
        SecurityContextHolder.clearContext()
    }

    private fun call(): MvcResult = mockMvc.perform(get("/api/v1/public/dashboards/{token}", secretToken)).andReturn()

    private fun bodyOf(result: MvcResult): String = String(result.response.contentAsByteArray, Charsets.UTF_8)

    /** 본문(바이트) + 전 헤더값에 토큰이 없어야 한다. */
    private fun assertNoTokenAnywhere(result: MvcResult) {
        assertThat(bodyOf(result))
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
     * 이 성질이 설계의 토대다. 틀리면 봉합 방식 전체가 무너지므로 단정하지 않고 관측한다.
     * advice 가 등록된 상태에서도 404 의 errorCode 가 컨트롤러-로컬 값이면 우선 적용이 확정된다.
     */
    @Test
    fun `G2 — advice 가 등록돼 있어도 컨트롤러 로컬 핸들러가 우선 적용된다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()

        assertThat(bodyOf(call())).contains("NOTIF_DASHBOARD_NOT_FOUND")
    }

    /** R3 회귀가드 — 기능은 한 글자도 바뀌지 않는다(상태·errorCode·detail 불변). */
    @Test
    fun `R3 — 404 의 상태코드 errorCode detail 이 기존과 동일하다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()

        val result = call()

        assertThat(result.response.status).isEqualTo(404)
        assertThat(bodyOf(result)).contains("NOTIF_DASHBOARD_NOT_FOUND")
        assertThat(bodyOf(result)).contains("공유된 대시보드를 찾을 수 없습니다.")
    }

    /** R3 회귀가드 — 500 의 상태·errorCode·detail 도 advice 시절과 동일해야 한다(drift 금지). */
    @Test
    fun `R3 — 500 의 상태코드 errorCode detail 이 기존과 동일하다`() {
        every { service.getPublicByToken(secretToken) } throws IllegalStateException("boom")

        val result = call()

        assertThat(result.response.status).isEqualTo(500)
        assertThat(bodyOf(result)).contains("NOTIF_DASHBOARD_INTERNAL_ERROR")
        assertThat(bodyOf(result)).contains("서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")
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
        val rawToken = "share_ENCODED+CANARY"
        every { service.getPublicByToken(rawToken) } throws PublicDashboardNotFoundException()

        val result = mockMvc.perform(get("/api/v1/public/dashboards/{token}", rawToken)).andReturn()
        val body = String(result.response.contentAsByteArray, Charsets.UTF_8)

        assertThat(result.response.status).isEqualTo(404)
        assertThat(body).doesNotContain(rawToken)
        assertThat(body).doesNotContain("ENCODED")
    }

    /**
     * R2 고정 — `instance` 는 **토큰 세그먼트를 뺀 정확한 경로**여야 한다.
     *
     * 다른 테스트는 전부 "토큰이 없다" 만 본다. 그것만으로는 값이 엉뚱하게 바뀌어도 통과하므로
     * 스펙 R2 가 검증되지 않은 채 남는다. 404·500 두 통로 모두에서 값을 고정한다.
     */
    @Test
    fun `R2 — instance 는 토큰 세그먼트를 뺀 고정 경로다`() {
        every { service.getPublicByToken(secretToken) } throws PublicDashboardNotFoundException()
        assertThat(bodyOf(call())).contains("\"instance\":\"/api/v1/public/dashboards\"")

        every { service.getPublicByToken(secretToken) } throws IllegalStateException("boom")
        assertThat(bodyOf(call())).contains("\"instance\":\"/api/v1/public/dashboards\"")
    }

    /**
     * ★ 재발 방지 봉인 — 공개 컨트롤러의 오류 통로를 **파생 열거**해 미분류를 실패시킨다.
     *
     * [PublicDashboardController] 에 새 `@ExceptionHandler` 를 추가하면 이 기대 맵도 함께 갱신해야만
     * 초록이 된다. "핸들러를 하나 더 만들었는데 아무도 토큰 유출을 확인하지 않는" 상태를 구조적으로 막는다.
     *
     * 각 항목의 값은 그 예외 타입을 실제로 발생시키기 위한 **표본 예외**다. 표본으로 요청을 태워
     * 본문에 토큰이 없음을 확인한다 — 선언만 세는 vacuous 검사가 아니다.
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
            assertThat(bodyOf(call()))
                .describedAs("통로 ${type.simpleName} 의 응답 본문에 원문 토큰이 실렸다")
                .doesNotContain(secretToken)
        }
    }
}

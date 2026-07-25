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
import java.util.UUID

/**
 * [DashboardExceptionHandler] 의 `instance` 동작 회귀 가드 (R4).
 *
 * 공개 경로(`/api/v1/public/dashboards/{token}`)는 경로에 원문 토큰이 있어 `instance` 를 고정값으로
 * 덮지만, **비밀값이 경로에 없는 인증 대시보드 경로는 요청 URI 를 그대로 유지**해야 한다 —
 * 어느 요청에서 터졌는지가 진단에 필요하기 때문이다.
 *
 * 이 가드가 없으면 "공개 경로를 고쳤으니 advice 에도 같이 박자" 는 그럴듯한 회귀가 아무 저항 없이 통과한다.
 * 공개 경로 쪽 봉합(`PublicDashboardErrorTokenLeakTest`)과 **정확히 반대 방향**을 못 박는 짝이다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DashboardExceptionHandlerInstanceTest.TestMvcConfig::class])
@WebAppConfiguration
class DashboardExceptionHandlerInstanceTest {
    /**
     * 인증 대시보드 경로를 흉내내는 테스트 전용 컨트롤러.
     *
     * `com.bts.notification.dashboard.web` 패키지에 있으므로
     * `@RestControllerAdvice(basePackages = [...])` 스코프에 들어간다.
     * 컨트롤러-로컬 `@ExceptionHandler` 를 두지 않아 예외가 advice 로 흘러간다.
     */
    @RestController
    class ProbeController {
        @GetMapping("/api/v1/dashboards/probe-not-found")
        fun notFound(): Nothing = throw DashboardNotFoundException(PROBE_ID)

        companion object {
            val PROBE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        }
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

    /** R4 — 인증 경로 오류 응답의 instance 는 요청 URI 그대로여야 한다(진단 가치 보존). */
    @Test
    fun `인증 경로 오류 응답의 instance 는 요청 URI 를 유지한다`() {
        val result = mockMvc.perform(get("/api/v1/dashboards/probe-not-found")).andReturn()
        val body = String(result.response.contentAsByteArray, Charsets.UTF_8)

        assertThat(result.response.status).isEqualTo(404)
        assertThat(body)
            .describedAs("advice 에 고정 instance 가 박혔다 — 인증 경로의 진단 정보가 사라진다")
            .contains("\"instance\":\"/api/v1/dashboards/probe-not-found\"")
    }
}

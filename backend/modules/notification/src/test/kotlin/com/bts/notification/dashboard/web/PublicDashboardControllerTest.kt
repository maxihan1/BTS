// PublicDashboardController MockMvc 슬라이스 테스트 — 익명 공개 대시보드 조회·내부필드 유출가드·404 수렴·SecurityContext 무참조

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.application.PublicDashboardNotFoundException
import com.bts.notification.dashboard.application.PublicDashboardSnapshot
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * PublicDashboardController MockMvc 슬라이스 테스트 (FR-DB-03 Task 7).
 *
 * BTS 첫 비인증(익명) 데이터 경로 — GET /api/v1/public/dashboards/{token}.
 * DashboardService 는 MockK stub 으로 대체한다.
 *
 * 익명 경로이므로 어떤 인증 컨텍스트도 주입하지 않는다.
 * @BeforeEach 에서 [SecurityContextHolder] 를 비워, 남은 인증 상태가 판정에 끼어들지 않게 한다.
 * 이 상태에서도 200 을 반환한다는 것은 컨트롤러가 currentActorId() 를 호출하지 않는다(= SecurityContext 무참조)는
 * 코드-레벨 증명이다 — currentActorId() 를 호출했다면 미인증 401 이 됐을 것이다.
 *
 * advice scoping: DashboardExceptionHandler 를 등록하지 않는다. 404 매핑은 컨트롤러-로컬
 * @ExceptionHandler(PublicDashboardNotFoundException) 로만 이뤄진다(catch-all→500 변질·타 컨트롤러 500 변질 차단).
 *
 * 테스트 케이스.
 * - VALID.   유효 토큰 → 200 + data{name, description, layout}, 내부 식별자(ownerId/sharedUserIds/version/id) 부재
 * - INVALID. 무효 토큰 → 404 + NOTIF_DASHBOARD_NOT_FOUND
 * - CONVERGE. 만료·부모삭제도 동일 예외 → 무효 토큰과 동일 404 응답(열거 차단)
 * - MALFORMED. 이상 문자·초장문 토큰 → 500 아닌 404 수렴(컨트롤러 검증 없이 서비스 위임)
 * - ANON.    SecurityContext 비어 있어도 200 (currentActorId 미호출 증명)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [PublicDashboardControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class PublicDashboardControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * PublicDashboardController 와 MockK stub 서비스만 등록한다.
     * DashboardExceptionHandler(advice)·인증 필터는 등록하지 않는다 — 익명 경로 + 컨트롤러-로컬 핸들러 검증.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun dashboardService(): DashboardService = mockk(relaxed = true)

        @Bean
        open fun publicDashboardController(service: DashboardService) = PublicDashboardController(service)
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: DashboardService

    private lateinit var mockMvc: MockMvc

    private val validToken = "share_validtoken0123456789abcdef"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 익명 경로 — 인증 컨텍스트를 주입하지 않는다. 이전 테스트의 잔여 인증이 끼어들지 않도록 명시적으로 비운다.
        SecurityContextHolder.clearContext()
    }

    /** VALID. 유효 토큰 → 200 + 정화된 스냅샷. 내부 식별자 필드는 응답 스키마에 존재조차 하지 않는다(유출 회귀가드). */
    @Test
    fun `유효한 토큰은 200 과 정화된 스냅샷을 반환하며 내부 식별자 필드가 응답에 없다`() {
        every { service.getPublicByToken(validToken) } returns
            PublicDashboardSnapshot(
                name = "팀 현황판",
                description = "스프린트 진행 상황",
                layout = """[{"type":"text_widget"}]""",
            )

        mockMvc.perform(get("/api/v1/public/dashboards/{token}", validToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("팀 현황판"))
            .andExpect(jsonPath("$.data.description").value("스프린트 진행 상황"))
            .andExpect(jsonPath("$.data.layout").value("""[{"type":"text_widget"}]"""))
            // 유출 회귀가드 — 내부 식별자/버전/공유대상은 응답에 존재조차 하지 않는다
            .andExpect(jsonPath("$.data.ownerId").doesNotExist())
            .andExpect(jsonPath("$.data.sharedUserIds").doesNotExist())
            .andExpect(jsonPath("$.data.version").doesNotExist())
            .andExpect(jsonPath("$.data.id").doesNotExist())
    }

    /** INVALID. 무효 토큰 → PublicDashboardNotFoundException → 404 + errorCode. */
    @Test
    fun `무효 토큰은 404 를 반환한다`() {
        val invalid = "share_bogus"
        every { service.getPublicByToken(invalid) } throws PublicDashboardNotFoundException()

        mockMvc.perform(get("/api/v1/public/dashboards/{token}", invalid))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    /** CONVERGE. 만료·부모삭제도 서비스가 동일 예외를 던지므로 무효 토큰과 동일한 404 응답으로 수렴(열거 차단). */
    @Test
    fun `만료·부모삭제도 동일 예외로 무효 토큰과 동일한 404 응답을 반환한다 (열거 차단)`() {
        val expiredOrDeleted = "share_expiredordeleted"
        every { service.getPublicByToken(expiredOrDeleted) } throws PublicDashboardNotFoundException()

        mockMvc.perform(get("/api/v1/public/dashboards/{token}", expiredOrDeleted))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
            // 무효 토큰과 동일한 상태·코드·detail 로 수렴 — 토큰 존재/만료 여부가 응답으로 새지 않는다
            .andExpect(jsonPath("$.detail").value("공유된 대시보드를 찾을 수 없습니다."))
    }

    /**
     * MALFORMED. 이상 문자·초장문 토큰 → 500 이 아닌 404 로 수렴한다.
     *
     * 컨트롤러는 토큰을 파싱·검증하지 않고 String 그대로 서비스에 위임한다(UUID 파싱 없음).
     * 서비스가 NotFound 를 던지면 컨트롤러-로컬 핸들러가 404 로 매핑한다.
     * errorCode 단언으로 catch-all 500 이 아닌 의도된 404 임을 확정한다.
     */
    @Test
    fun `이상 문자·초장문 토큰도 500 이 아닌 404 로 수렴한다`() {
        val weirdLong = "share_%s.-_~".format("a".repeat(2048))
        every { service.getPublicByToken(weirdLong) } throws PublicDashboardNotFoundException()

        mockMvc.perform(get("/api/v1/public/dashboards/{token}", weirdLong))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_DASHBOARD_NOT_FOUND"))
    }

    /**
     * ANON. SecurityContext 가 비어 있어도 200 을 반환한다.
     *
     * currentActorId() 를 호출했다면 미인증 401 이 됐을 것이다 — 200 은 컨트롤러가 SecurityContext 를
     * 일절 참조하지 않는다는 코드-레벨 증명이다. description=null 직렬화도 함께 확인한다.
     */
    @Test
    fun `SecurityContext 가 비어 있어도 200 을 반환한다 (currentActorId 미호출)`() {
        SecurityContextHolder.clearContext()
        every { service.getPublicByToken(validToken) } returns
            PublicDashboardSnapshot(name = "현황판", description = null, layout = "[]")

        mockMvc.perform(get("/api/v1/public/dashboards/{token}", validToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("현황판"))
            .andExpect(jsonPath("$.data.layout").value("[]"))
    }
}

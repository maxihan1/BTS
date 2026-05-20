// CorsConfigurationSource Bean 동작 검증 — dev/prod origins, methods, headers, credentials

package com.atlas.bts.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * CorsConfig Bean 단위 검증 (FR-09-24).
 *
 * @SpringBootTest 없이 CorsConfig 인스턴스를 직접 생성해 Bean 로직을 검증한다.
 * SecurityConfig 가 JwtDecoder Bean 을 요구하여 전체 컨텍스트 로드 비용을 피한다.
 *
 * 검증 항목.
 * 1. allowed origins — dev 기본값 `http://localhost:5173`
 * 2. allowed origins — prod override `https://bts.example.com`
 * 3. allowed methods — GET / POST / PUT / DELETE / OPTIONS
 * 4. allowed headers — Authorization / X-XSRF-TOKEN / Content-Type
 * 5. allowCredentials — true (Cookie 전달 필요)
 * 6. /api/v1/** 경로에만 CORS 설정이 등록됨
 */
class CorsConfigTest {

    private fun buildSource(origins: List<String>): UrlBasedCorsConfigurationSource {
        val config = CorsConfig()
        val source = config.corsConfigurationSource(origins)
        return source as UrlBasedCorsConfigurationSource
    }

    private fun resolveConfig(source: UrlBasedCorsConfigurationSource, path: String): CorsConfiguration? {
        val request = MockHttpServletRequest("GET", path)
        return source.getCorsConfiguration(request)
    }

    // --- origins ---

    @Test
    fun `dev origin localhost 5173 이 allowed origins 에 포함된다`() {
        val source = buildSource(listOf("http://localhost:5173"))
        val cfg = resolveConfig(source, "/api/v1/whoami")

        assertThat(cfg).isNotNull
        assertThat(cfg!!.allowedOrigins).contains("http://localhost:5173")
    }

    @Test
    fun `prod origin bts example com 이 allowed origins 에 포함된다`() {
        val source = buildSource(listOf("https://bts.example.com"))
        val cfg = resolveConfig(source, "/api/v1/whoami")

        assertThat(cfg).isNotNull
        assertThat(cfg!!.allowedOrigins).contains("https://bts.example.com")
    }

    @Test
    fun `BTS_FRONTEND_ORIGIN override 로 여러 origins 를 동시에 허용한다`() {
        val origins = listOf("http://localhost:5173", "https://bts.example.com")
        val source = buildSource(origins)
        val cfg = resolveConfig(source, "/api/v1/whoami")

        assertThat(cfg!!.allowedOrigins).containsExactlyInAnyOrder(
            "http://localhost:5173",
            "https://bts.example.com",
        )
    }

    // --- methods ---

    @Test
    fun `allowed methods 에 GET POST PUT DELETE OPTIONS 가 포함된다`() {
        val source = buildSource(listOf("http://localhost:5173"))
        val cfg = resolveConfig(source, "/api/v1/issues")

        assertThat(cfg!!.allowedMethods).containsExactlyInAnyOrder(
            "GET", "POST", "PUT", "DELETE", "OPTIONS",
        )
    }

    // --- headers ---

    @Test
    fun `allowed headers 에 Authorization X-XSRF-TOKEN Content-Type 이 포함된다`() {
        val source = buildSource(listOf("http://localhost:5173"))
        val cfg = resolveConfig(source, "/api/v1/issues")

        assertThat(cfg!!.allowedHeaders).containsExactlyInAnyOrder(
            "Authorization", "X-XSRF-TOKEN", "Content-Type",
        )
    }

    // --- credentials ---

    @Test
    fun `allowCredentials 가 true 여야 한다 (Cookie 전달 필수)`() {
        val source = buildSource(listOf("http://localhost:5173"))
        val cfg = resolveConfig(source, "/api/v1/auth/token")

        assertThat(cfg!!.allowCredentials).isTrue()
    }

    // --- path scope ---

    @Test
    fun `api v1 경로에는 CORS 설정이 적용된다`() {
        val source = buildSource(listOf("http://localhost:5173"))
        val cfg = resolveConfig(source, "/api/v1/projects")

        assertThat(cfg).isNotNull
    }

    @Test
    fun `api v1 외 경로에는 CORS 설정이 적용되지 않는다`() {
        val source = buildSource(listOf("http://localhost:5173"))
        // /actuator 같은 경로는 CORS 설정 대상 외
        val cfg = resolveConfig(source, "/actuator/health")

        assertThat(cfg).isNull()
    }
}

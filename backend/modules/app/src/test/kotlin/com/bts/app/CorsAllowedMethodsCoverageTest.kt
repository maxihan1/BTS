// CORS allowedMethods 가 조립 전역에 실제로 등록된 HTTP 메서드 집합을 덮는지 강제하는 봉인

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping

/**
 * **CORS 허용 메서드 커버리지 봉인.**
 *
 * ## 위험 모델
 * `CorsConfig.allowedMethods` 는 **하드코딩 목록**이고, 컨트롤러가 쓰는 HTTP 메서드 집합은
 * 애너테이션에서 자라난다. 둘의 정합을 아무도 보고 있지 않았다.
 *
 * 실제로 `PATCH` 가 빠진 채로 **프로덕션 `@PatchMapping` 40개**가 쌓였다. 지금 무해한 이유는
 * `infra/prod/nginx.conf` 가 SPA 와 백엔드를 **같은 오리진**으로 서빙해 브라우저가 preflight 를
 * 아예 보내지 않기 때문이다. 그러나 `application.yml` 에 `BTS_CORS_ALLOWED_ORIGINS` 오버라이드가
 * 이미 배선돼 있어, 프론트를 별 도메인으로 분리하는 순간 **PATCH 40개가 동시에 차단**된다.
 *
 * ## ★ 왜 어떤 기존 테스트도 못 잡았나
 * - MSW 는 네트워크 계층 **이전**에서 가로챈다
 * - Vite dev 프록시는 요청을 동일 오리진으로 만든다
 * - MockMvc 는 `CorsFilter` 를 타지 않는다
 * - `CorsConfigTest` 는 **하드코딩 목록끼리** 대조해 결함을 정답으로 박제하고 있었다
 *   (`containsExactlyInAnyOrder` 에 PATCH 가 없는 집합)
 *
 * ⇒ **전 계층 초록 + 실배포만 빨강**. 이 저장소에서 가장 늦게 발견되는 유형이다.
 *
 * ## 판별식 — 목록 대조가 아니라 실제 등록 매핑을 읽는다
 * 조립 컨텍스트의 [RequestMappingInfoHandlerMapping] 에서 **실제로 등록된** 핸들러의 메서드 집합을
 * 뽑아 `allowedMethods` 와 차집합을 낸다. 새 컨트롤러가 새 메서드를 쓰면 이 테스트가 먼저 깨진다 —
 * 하드코딩 목록끼리 대조하면 같은 눈가리개가 재생산된다.
 *
 * ## ★ 왜 조립(:modules:app)인가
 * `CorsConfig` 는 identity-access 에 있지만 그 설정은 **9 BC 전체 요청**에 적용된다. 봉인을 한 BC 안에
 * 두면 그 BC 의 컨트롤러만 보게 되어 위험 모델과 가드 범위가 어긋난다. 조립 모듈만 9 BC 를 전부
 * 물고 있어 여기서만 전수 스캔이 가능하다(형제 [GlobalControllerAdviceSealTest] 와 같은 근거).
 */
class CorsAllowedMethodsCoverageTest : ProdAssemblyHttpTestBase() {
    @Autowired
    private lateinit var handlerMappings: List<RequestMappingInfoHandlerMapping>

    // ★ 빈 이름을 명시해야 한다. 조립 컨텍스트에는 CorsConfigurationSource 타입 빈이 둘이다 —
    // 우리 CorsConfig 의 `corsConfigurationSource` 와 Spring MVC 가 내부적으로 등록하는
    // `mvcHandlerMappingIntrospector`. 타입만으로 주입하면 NoUniqueBeanDefinition 으로 부팅이 실패한다.
    @Autowired
    @Qualifier("corsConfigurationSource")
    private lateinit var corsSource: CorsConfigurationSource

    /**
     * CORS 가 적용되는 경로(`/api/v1` 하위)의 허용 메서드 집합.
     *
     * `UrlBasedCorsConfigurationSource` 는 요청 경로로 설정을 고르므로, 실제 요청 하나를 만들어
     * 해석시킨다 — 등록 패턴을 테스트가 다시 하드코딩하면 그 자체가 또 하나의
     * 어긋날 수 있는 목록이 된다.
     */
    private fun allowedMethods(): Set<String> {
        val request = MockHttpServletRequest("OPTIONS", "/api/v1/issues")
        val cfg = corsSource.getCorsConfiguration(request)
        requireNotNull(cfg) { "/api/v1/** 에 CORS 설정이 없다 — 등록 패턴이 바뀌었는지 확인하라." }
        return cfg.allowedMethods.orEmpty().toSet()
    }

    /** 조립 전역에 실제로 등록된 HTTP 메서드 집합 (`/api/v1` 하위 매핑 한정) */
    private fun registeredApiMethods(): Set<String> =
        handlerMappings
            .flatMap { it.handlerMethods.keys }
            .filter { info ->
                // CORS 설정이 걸린 경로만 대상 — 그 밖(actuator·slack 인바운드 등)은 preflight 대상이 아니다.
                // Spring 6 은 PathPattern(신) 과 AntPathMatcher(구) 두 조건을 함께 노출하며
                // 활성화된 쪽만 채워진다. 한쪽만 읽으면 매핑을 통째로 놓치므로 둘 다 본다.
                val antPatterns = info.patternsCondition?.patterns.orEmpty()
                val pathPatterns = info.pathPatternsCondition?.patterns.orEmpty().map { it.patternString }
                (antPatterns + pathPatterns).any { it.startsWith("/api/v1/") }
            }
            .flatMap { it.methodsCondition.methods }
            .map { it.name }
            .toSet()

    @Test
    fun `판별식이 비어 있지 않다 - 등록 매핑을 실제로 수집한다`() {
        val registered = registeredApiMethods()

        // 하한이 없으면 수집이 0건이어도 아래 차집합이 공허하게 통과한다
        // ([[archunit-vacuous-rule-silent-pass]] 와 같은 실패 양식).
        assertThat(registered)
            .describedAs("조립 컨텍스트에서 /api/v1/** 핸들러 매핑을 하나도 수집하지 못했다 — 수집 로직이 고장났다")
            .isNotEmpty()
        assertThat(registered).contains("GET", "POST")
    }

    @Test
    fun `allowedMethods 가 실제 등록된 메서드 집합을 전부 덮는다`() {
        val registered = registeredApiMethods()
        val allowed = allowedMethods()
        val missing = registered - allowed

        assertThat(missing)
            .describedAs(
                "CorsConfig.allowedMethods 가 덮지 못하는 HTTP 메서드가 있다: %s\n" +
                    "등록됨=%s / 허용됨=%s\n" +
                    "프론트를 별 도메인으로 분리하는 순간 그 메서드의 전 엔드포인트가 preflight 에서 차단된다.",
                missing,
                registered.sorted(),
                allowed.sorted(),
            )
            .isEmpty()
    }

    @Test
    fun `PATCH 가 실제로 등록돼 있다 - 이 봉인이 지키는 대상의 존재 확인`() {
        // PATCH 매핑이 0건이면 위 차집합 단언이 PATCH 에 대해 공허해진다.
        // 이 저장소에는 @PatchMapping 이 40개 있으므로 그 사실을 판별자로 고정한다.
        assertThat(registeredApiMethods())
            .describedAs("등록된 PATCH 매핑이 0건이다 — 위 커버리지 단언이 PATCH 축에서 공허해진다")
            .contains("PATCH")
    }
}

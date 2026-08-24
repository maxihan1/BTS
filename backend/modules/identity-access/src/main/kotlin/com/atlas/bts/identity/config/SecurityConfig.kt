// Spring Security 필터 체인 — SAML(1)+OIDC(2)+STATELESS(3) 3체인, JWT/PAT/CSRF/CORS (FR-09-26/27/30, FR-AU-03/04)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.web.PatAuthenticationFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfigurationSource

/**
 * BTS 단일 SecurityFilterChain 설정 (FR-09-26 / FR-09-27 / FR-09-30).
 *
 * ## BLOCKER #4 해소 — Spring Authorization Server 미도입
 * RegisteredClient / OAuth2AuthorizationServerConfiguration 사용 금지.
 * BTS는 JWT를 자체 발급(JwtIssuer, Task 12)하며 Nimbus-JOSE-JWT 9.40 +
 * spring-security-oauth2-jose 를 직접 사용한다.
 * 이전 plan의 Order(1) AuthServer + Order(2) BTS API 분리 구조 폐기.
 * 단일 SecurityFilterChain Bean 으로 통합한다.
 *
 * ## BLOCKER #5 해소 — 표준 OAuth2 endpoint 비활성
 * /oauth2/token, /oauth2/authorize 등 Spring Authorization Server 표준 endpoint 가
 * 미등록 상태로 404 를 반환하므로 공격 표면이 없다.
 * 사용자 로그인은 /api/v1/auth/login (Custom) 만 제공한다.
 *
 * ## permitAll 경로 (FR-09-30 · FR-DB-03 · FR-CA-02 · FR-SL · FR-AT-07)
 * 아래는 **대표 경로**이며 정본은 [securityFilterChain] 의 authorizeHttpRequests 블록이다
 * (refresh·SAML/OIDC 목록·MFA verify 등은 아래에 적지 않았다).
 * 총 개수는 표기하지 않는다 — 목록이 부분집합이라 개수를 적으면 곧 stale 이 되어 오히려 오독을 부른다.
 * - /api/v1/auth/login      — 로그인 요청 (credentials 수신, CSRF skip)
 * - /api/v1/auth/providers  — 활성 Provider 목록 조회 (인증 전 필요)
 * - /.well-known/jwks.json  — 공개키 제공 (외부 검증용, CSRF skip)
 * - /actuator/health        — 헬스체크 (로드밸런서, CSRF skip)
 * - /api/v1/public/dashboards/{token} — 익명 공개 대시보드 조회 (FR-DB-03, GET 메서드 고정 read-only, 단일 세그먼트 토큰)
 * - /ical/feed/{token}.ics — 익명 iCal 구독 피드 (FR-CA-02, GET 메서드 고정 read-only, 단일 세그먼트 토큰)
 * - 인바운드 웹훅 6경로 — 외부 서버-투-서버 호출 ([INBOUND_WEBHOOK_PATHS], 메서드 고정 + 정확 경로/단일 세그먼트).
 *   slack 4 · git 1 은 컨트롤러의 서명 검증이, automation 1 은 불투명 토큰 소지가 인증을 담당한다
 *   (★ 셋의 방어 등급이 균일하지 않다 — [INBOUND_WEBHOOK_PATHS] KDoc 참조)
 *
 * ## CSRF Cookie 모드 (ADR docs/decisions/2026-05-20-csrf-cookie-mode.md)
 * CookieCsrfTokenRepository.withHttpOnlyFalse() — SPA가 Cookie를 읽어 X-XSRF-TOKEN 헤더로 전송.
 * SameSite=Strict + secure=true 설정 (DEVELOPMENT.md §1.5).
 *
 * ## JWT sid revoke 회로 (FR-09-11 / Task 34)
 * [SidRevokeJwtConverter] 를 jwtAuthenticationConverter 로 등록.
 * 모든 API 요청의 JWT sid 클레임으로 세션 revoke 여부를 Caffeine 캐시(5s TTL)와 함께 확인한다.
 *
 * ## @EnableMethodSecurity
 * 엔드포인트별 @PreAuthorize 이중 가드 (DEVELOPMENT.md §1.4 Spring Security 주의사항).
 * SecurityConfig 필터 체인 + @PreAuthorize 두 레이어로 우회를 방지한다.
 *
 * ## SAML 전용 체인 분리 (FR-AU-03 / 게이트1 D1 — BLOCKER 해소)
 * SAML SP-initiated 로그인은 AuthnRequest 상관관계/replay 방어 상태를 HttpSession 에 저장하므로
 * STATELESS 정책과 충돌한다. 따라서 SAML 경로만 [SamlSecurityConfig] 의 별도 체인(Order=1)이
 * 잡아 IF_REQUIRED 세션을 허용하고, 그 외 모든 경로는 이 [securityFilterChain] (Order=2)이
 * STATELESS 로 처리한다. SAML 체인의 세션 허용은 일반 API 의 STATELESS/JWT/CSRF 동작에 영향을 주지 않는다.
 * 로그인 전 호출되는 [SAML_IDPS_PATH] 만 이 체인에서 permitAll 로 추가 노출한다.
 *
 * ## 익명 공개 대시보드 경로 (FR-DB-03 — BTS 첫 비인증 데이터 경로)
 * [PUBLIC_DASHBOARDS_PATH] 는 로그인 없이 불투명 공유 토큰만으로 정화된 대시보드 스냅샷을 조회하는
 * GET 전용 read-only 경로다. DEVELOPMENT.md §1.4 "인증 없는 엔드포인트 추가 금지"의 정식 예외로,
 * 근거는 ADR 2026-07-02-fr-db-03-dashboard-share(직교 토큰·정화 fail-closed)와 게이트1 승인이다.
 * 매처는 전역 하위경로 와일드카드가 아니라 단일 경로 세그먼트 하나(`/api/v1/public/dashboards/{token}`)만 노출한다(폭발 반경 최소화).
 * 토큰 해싱·layout 정화·미존재/만료/삭제의 404 수렴은 모두 notification BC(DashboardService) 책임이다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val sidRevokeJwtConverter: SidRevokeJwtConverter,
    private val corsConfigurationSource: CorsConfigurationSource,
    private val personalAccessTokenService: PersonalAccessTokenService,
) {
    // LongMethod 억제 — 단일 SecurityFilterChain DSL 빌더는 필터 등록 순서 의존성 때문에 한 메서드에
    // 응집돼야 하며, csrf/authorizeHttpRequests 설정을 임의 헬퍼로 분해하면 가독성과 순서 보장이 깨진다.
    // FR-MF-01 에서 verify 를 CSRF-ignore + permitAll 양쪽에 등록(BLOCKER-1)하며 임계(60)를 1줄 넘었다.
    @Suppress("LongMethod")
    @Bean
    @Order(API_CHAIN_ORDER)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // CookieCsrfTokenRepository 설정
        // withHttpOnlyFalse(): SPA가 Cookie 토큰 읽어 X-XSRF-TOKEN 헤더로 전송하므로 JS 접근 허용.
        // setCookieCustomizer: SameSite=Strict (DEVELOPMENT.md §1.5, ADR csrf-cookie-mode).
        // secure(true): HTTPS 환경에서 Cookie 전송 제한.
        val csrfRepo =
            CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieCustomizer { cookie ->
                    cookie.sameSite("Strict")
                    cookie.secure(true)
                }
            }

        // EC-26: pat_ prefix 토큰은 JWT 파싱 대상에서 제외.
        // DefaultBearerTokenResolver 가 Authorization 헤더에서 Bearer 토큰을 추출하되,
        // pat_ prefix 인 경우 null 을 반환하여 JWT 필터가 처리하지 않도록 한다.
        // PAT 요청은 PatAuthenticationFilter 가 JWT 필터보다 먼저 처리하여 SecurityContext 에 인증 정보를 설정한다.
        //
        // ★ 인바운드 웹훅은 delegate 를 태우지 않는다 — form POST 의 `access_token` 조회가 Tomcat form
        // 파싱 → 본문 스트림 소진을 일으켜 컨트롤러가 빈 바디로 401. 상세·기각안은 slack ADR §D7.
        // ★ git 도 동일 노출이다 — GitHub 웹훅은 설정에서 application/x-www-form-urlencoded 를 고를 수 있다
        // (FR-AT-07 PR-C ADR §D1). allowFormEncodedBodyParameter=false 로는 막지 못한다 — 파라미터 접근이
        // 이미 일어난 뒤에 플래그를 검사하므로 그 시점엔 본문이 소진돼 있다.
        val delegate = DefaultBearerTokenResolver()
        val inboundWebhookMatcher: RequestMatcher =
            OrRequestMatcher(INBOUND_WEBHOOK_PATHS.map { (method, path) -> antMatcher(method, path) })
        val patSkippingBearerTokenResolver =
            BearerTokenResolver { req: HttpServletRequest ->
                if (inboundWebhookMatcher.matches(req)) {
                    null
                } else {
                    val token = delegate.resolve(req)
                    if (token != null && token.startsWith(PersonalAccessToken.TOKEN_PREFIX)) null else token
                }
            }

        return http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(corsConfigurationSource) }
            // DEVELOPMENT.md §1.5 — CSRF 비활성화 금지.
            // login: credentials 수신 전이므로 CSRF skip. jwks.json / actuator: 공개 리소스.
            // refresh: Cookie 기반 엔드포인트. SameSite=Strict refresh_token Cookie 로
            //   CSRF 위험을 동등하게 방어한다 (AuthController KDoc §CSRF 처리 참조).
            //   Bearer 토큰 없이 Cookie 만으로 동작하므로 CSRF skip 추가 (FR-09-21 회귀 방지).
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepo)
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                // PAT Bearer 요청: Authorization 헤더에 pat_ prefix 토큰이 있으면 CSRF skip.
                // PAT는 stateless 자격증명이며 CSRF 공격 벡터(쿠키 기반 세션)가 없다.
                // JWT Bearer 요청은 oauth2ResourceServer가 자동으로 CSRF를 skip한다.
                val patBearerMatcher =
                    RequestMatcher { req: HttpServletRequest ->
                        val header = req.getHeader("Authorization") ?: return@RequestMatcher false
                        header.startsWith("Bearer ${PersonalAccessToken.TOKEN_PREFIX}")
                    }
                csrf.ignoringRequestMatchers(
                    patBearerMatcher,
                )
                // FR-AT-07: 인바운드 웹훅 — permitAll·bearer skip 과 **같은** [INBOUND_WEBHOOK_PATHS] 목록에서
                // 구동한다(PR-A DEC-16 · PR-C ADR §D4). 외부 시스템은 브라우저가 아니라 서버가 POST 하므로 CSRF
                // 토큰을 가질 수 없다 — permitAll 만 열고 여기를 빠뜨리면 CsrfFilter 가 먼저 거부해 경로가 계속
                // 죽어 있다(FR-MF-01 BLOCKER-1 과 동일 사고).
                // ★ antMatcher(method, path) 필수 — ignoringRequestMatchers 에는 (HttpMethod, String) 오버로드가
                // 없다. 문자열 오버로드를 쓰면 메서드 고정이 조용히 사라지고 컴파일·테스트 모두 통과한다(PR-C ADR §D4).
                INBOUND_WEBHOOK_PATHS.forEach { (method, path) ->
                    csrf.ignoringRequestMatchers(antMatcher(method, path))
                }
                csrf.ignoringRequestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    // FR-MF-01: 로그인 2단계 검증 — 정식 세션 전(JWT 없음)이라 login 처럼 명시적 CSRF skip (BLOCKER-1).
                    // method=webauthn 도 동일 경로(MFA_VERIFY_PATH)를 쓰므로 별도 등록 불요 (FR-MF-03).
                    MFA_VERIFY_PATH,
                    // FR-MF-03: 보안키 인증(assertion) 시작 — 정식 세션 전(챌린지 토큰만)이라 verify 와 동일하게 CSRF skip.
                    WEBAUTHN_AUTHENTICATE_START_PATH,
                    "/.well-known/jwks.json",
                    "/actuator/**",
                )
            }
            .authorizeHttpRequests { auth ->
                // permitAll — FR-09-30 계열(로그인/providers/jwks/actuator 등, 메서드 무관) + refresh(쿠키 기반, 인증 토큰 불요).
                // FR-DB-03 익명 대시보드 경로만 아래에서 GET 메서드로 한정해 별도 등록한다(C1 defense-in-depth).
                auth.requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/providers",
                    // FR-AU-03: 활성 SAML IdP 목록은 로그인 전 호출되므로 permitAll
                    // (민감정보 미노출 — registrationId/displayName 만, SamlIdpController KDoc 참조).
                    SAML_IDPS_PATH,
                    // FR-AU-04: 활성 OIDC Provider 목록도 로그인 전 호출되므로 permitAll
                    // (민감정보 미노출 — registrationId/displayName 만, OidcProviderController KDoc 참조).
                    OIDC_PROVIDERS_PATH,
                    // FR-AU-07: 도메인 기반 SSO 라우트 조회도 로그인 전 호출되므로 permitAll.
                    // SAML 체인(Order=1)·OIDC 체인(Order=2)은 /saml2/**·/oauth2/** 만 매칭하므로
                    // /api/v1/auth/route 는 이 STATELESS API 체인(Order=3)에 안전히 떨어진다(체인 충돌 없음).
                    // 도메인만으로 라우트 존재 여부만 판단하며 자격증명을 취급하지 않는다 (DomainRouteController KDoc 참조).
                    ROUTE_PATH,
                    // FR-MF-01: 2단계 검증은 정식 세션 전 호출이라 permitAll (totp/** 는 permitAll 아님 — authenticated).
                    // method=webauthn 도 동일 경로를 쓰므로 별도 등록 불요 (FR-MF-03).
                    MFA_VERIFY_PATH,
                    // FR-MF-03: 보안키 인증 시작도 정식 세션 전(챌린지 토큰만) 호출이라 permitAll
                    // (webauthn/register/** 와 GET/DELETE /webauthn 은 permitAll 아님 — /api/** authenticated).
                    WEBAUTHN_AUTHENTICATE_START_PATH,
                    "/.well-known/jwks.json",
                    "/actuator/health",
                ).permitAll()
                // FR-DB-03 (C1): 익명 공개 대시보드 조회 — 로그인 없이 불투명 공유 토큰만으로 정화된 스냅샷 조회.
                // DEVELOPMENT.md §1.4 정식 예외(ADR 2026-07-02-fr-db-03-dashboard-share·게이트1 승인).
                // BTS 유일의 익명 데이터 경로이므로 defense-in-depth 로 GET 메서드에만 permitAll 을 고정한다.
                // 향후 같은 prefix 에 POST/PUT/DELETE 매핑이 추가돼도 익명 노출되지 않는다(비-GET 은 authenticated 로 떨어짐).
                // 단일 세그먼트 `/*` 매처로 토큰 1개 path 만 노출. 정화·404 수렴은 notification BC(DashboardService) 책임.
                auth.requestMatchers(HttpMethod.GET, PUBLIC_DASHBOARDS_PATH).permitAll()
                // FR-CA-02: 익명 iCal 구독 피드 — 외부 앱이 Authorization 없이 폴링하는 read-only 경로.
                // PUBLIC_DASHBOARDS_PATH 와 동일 defense-in-depth(GET 고정·단일 세그먼트). 404 수렴은 IcalFeedController.
                // DEVELOPMENT.md §1.4 정식 예외(ADR 2026-07-09-fr-ca-02·게이트1 승인). 상세는 ICAL_FEED_PATH KDoc.
                auth.requestMatchers(HttpMethod.GET, ICAL_FEED_PATH).permitAll()
                // FR-NT-02: STOMP over WebSocket 핸드셰이크 — **HTTP 계층에서만** 연다.
                //
                // ★ 왜 여는가. BTS 는 STATELESS + JWT Bearer 라 세션 쿠키가 없고, 브라우저 WebSocket API 는
                //   업그레이드 요청에 임의 헤더를 실을 수 없다. 그래서 인증 지점을 한 단계 뒤인 STOMP
                //   `CONNECT` frame 의 native header `Authorization: Bearer <accessToken>` 로 미룬다.
                //   그 검증은 `notification` BC 의 StompAuthChannelInterceptor 가 **단독으로** 진다 —
                //   헤더 부재·형식 오류·decode 실패·subject 부재·PAT 사용은 전부 CONNECT 를 거부하고,
                //   클라이언트발 SEND 도 거부한다(push-only). 즉 소켓은 CONNECT 성공 전까지 아무 데이터도
                //   나르지 못한다. 핸드셰이크만 열리고 인증은 그대로다.
                //
                // ★ 이 줄이 없으면 어떻게 되는가(실측). 아래 anyRequest().authenticated() 가 먼저 잡아
                //   업그레이드 요청이 401 로 끊기고, 브라우저는 CONNECT 를 보내볼 기회조차 없이
                //   `WebSocket connection to 'wss://…/ws' failed: HTTP Authentication failed` 만 남긴다.
                //   프로덕션 전 화면에서 실시간 인앱 알림이 죽어 있었고, 두 모듈 어느 테스트도 상대
                //   설정을 읽지 않아 유닛은 전부 초록이었다(저장소 지배 결함 양식).
                //
                // 🛑 하위 전체를 덮는 와일드카드로 넓히지 마라. 정확히 이 한 경로만 연다 — 훗날 이 아래
                //    매핑이 생기면 조용히 익명 노출된다. 짝 판별식 =
                //    WebSocketHandshakePermitAllIntegrationTest (열림 1건 + 인접 경로 401 1건).
                //
                // 🛑 문자열 매처(`requestMatchers("/ws")`) 를 쓰지 마라 — Spring MVC 가 있으면
                //    MvcRequestMatcher 로 해석되는데 `/ws` 는 **MVC 핸들러가 아니라** WebSocket
                //    핸들러라 매칭되지 않고, permitAll 을 적어도 401 이 그대로 남는다(실측).
                //    같은 이유로 SamlSecurityConfig 도 MVC 비의존 AntPathRequestMatcher 를 쓴다.
                auth.requestMatchers(antMatcher(WS_HANDSHAKE_PATH)).permitAll()
                // FR-AT-07: 인바운드 웹훅 6경로 — CSRF-ignore·bearer skip 과 **같은** [INBOUND_WEBHOOK_PATHS]
                // 목록을 순회한다(PR-A DEC-16 · PR-C ADR §D4).
                // ★ 아래 /api/** · anyRequest() 보다 반드시 위 — Spring Security 매처는 선언 순서대로 첫 매치가 이긴다.
                //   git(/api/v1/webhooks/git/*) · automation(/api/v1/automation/webhooks/*) 두 경로군은 /api/**
                //   하위라 이 순서가 곧 계약이다 — 아래로 내리면 즉시 401 로 죽는다(PR-C ADR §D1).
                INBOUND_WEBHOOK_PATHS.forEach { (method, path) ->
                    auth.requestMatchers(method, path).permitAll()
                }
                auth.requestMatchers("/api/**").authenticated()
                // ★★ /error 를 permitAll 로 열지 말 것 — 위 permitAll 3경로군의 토큰이 동시에 샌다 (FR-AT-07 PR-C T15)
                // 서블릿은 컨트롤러 @ExceptionHandler 가 잡지 않는 에러(415·405 등)를 sendError → **/error 로 ERROR
                // 디스패치**한다. /error 가 이 anyRequest() 에 걸려 authenticated 이면 **익명 요청**에 한해
                // BasicErrorController 가 실행되지 못하고 필터가 빈 401 을 준다.
                // ★ 정정(N2) — 이 한 줄은 **익명만** 막는다. 인증(JWT) 요청은 BearerTokenAuthenticationFilter 가
                //   SecurityContext 를 RequestAttributeSecurityContextRepository(STATELESS 기본 저장소 = 요청
                //   attribute)에 저장하므로 **같은 요청의 ERROR 디스패치에서 복원**되고, 여기 authenticated 를
                //   그대로 통과해 BasicErrorController 가 살아난다. 아래 T15 실측의 표본이 전부 익명이라
                //   여태 관측되지 않았을 뿐이다(AuthenticatedErrorPathTokenLeakTest 가 405·404 두 축으로 실증).
                //   그래서 토큰 비노출의 실제 근거는 이 줄이 아니라 `server.error.include-path: never`(조립
                //   application.yml)다. 이 줄은 그 위의 defense-in-depth 로 유지한다.
                // permitAll 로 바꾸면 BasicErrorController 가 살아나고, Spring Boot 기본 에러 본문의 `path` 필드는
                // **요청 URI 원문**을 담는다(opt-in 인 message/trace 와 달리 path 는 항상 포함). 위 permitAll 경로군은
                // 셋 다 **경로 세그먼트에 비밀 토큰**을 싣는다 — 공개 대시보드 공유 토큰·iCal 피드 토큰·웹훅 토큰.
                // 그 순간 세 종류가 한꺼번에 에러 응답으로 나가고, 응답 로그·프록시 캐시·GitHub 웹훅 delivery 기록에
                // 적재되면 그것이 곧 평문 토큰 저장이다(DEVELOPMENT.md §1.1-1·§1.1-2).
                // 실측(T15): /error 를 permitAll 로 뒤집으면 form-urlencoded 요청의 415 본문에
                // `"path":"/api/v1/webhooks/git/<원문토큰>"` 이 그대로 실렸다.
                // ★ 정정(N2) — 이 회귀를 잡는 것은 T15-6 이 **아니다**. 컨트롤러가 415 를 자기 핸들러 안에서
                //   응답하게 된 뒤로 T15-6 의 요청은 /error 를 아예 타지 않는다(그 테스트 자신의 주석이 명시).
                //   더구나 `server.error.include-path: never` 이후로는 본문에서 토큰이 사라져 **토큰 문자열을
                //   판별자로 쓰던 축이 전부**(T15-6·T15-7 포함) permitAll 로 뒤집어도 초록으로 남는다
                //   (PR #312 에서 뮤테이션으로 재현 확인).
                //   현재 이 회귀를 잡는 유일한 가드는 AuthenticatedErrorPathTokenLeakTest 의 **N2-D** 다 —
                //   판별자가 되려면 요청이 REQUEST 디스패치에서 **인가를 통과**하고(entry point 가 아니라
                //   sendError 로 가야 한다) 컨트롤러 밖에서 오류가 나야 하므로, **익명 + permitAll 경로 + 404**
                //   조합(익명 iCal 피드)만이 /error 의 권한 설정에 반응한다.
                // ★ 익명 경로가 제대로 된 에러 JSON 을 못 받는다는 이유로 열고 싶다면, **먼저 ErrorAttributes 에서
                //   path 를 제거**하고 나서 열 것. 순서를 바꾸면 그 사이에 토큰이 샌다.
                //   (N2 로 그 선행조건은 충족됐다 — `server.error.include-path: never`. 그래도 **열지 말 것**:
                //   path 제거는 본문 한 필드를 없앤 것일 뿐, permitAll 로 열면 익명에게 상태코드 오라클이
                //   생기고 N3(잠복 전역 advice) 같은 다른 통로가 익명에게도 열린다.)
                auth.anyRequest().authenticated()
            }
            // PAT Bearer 필터: JWT 필터보다 먼저 실행하여 pat_ prefix 토큰을 SecurityContext 에 설정
            .addFilterBefore(
                PatAuthenticationFilter(personalAccessTokenService),
                BearerTokenAuthenticationFilter::class.java,
            )
            // FR-MF-04: MFA 등록 게이트 — 인증 필터(BearerToken·PAT) 뒤에 등록해 principal 이 가용할 때 동작.
            // 클레임 mfa_enrollment_required=true 인 강제대상 미등록 요청을 allow-list 외 경로에서 403 차단(DB 0).
            // 미인증은 손대지 않고 통과 → 기존 401 흐름 유지(게이트가 401 을 403 으로 바꾸지 않음).
            .addFilterAfter(
                MfaEnrollmentGateFilter(),
                BearerTokenAuthenticationFilter::class.java,
            )
            // FR-09-11: SidRevokeJwtConverter — sid claim 으로 세션 revoke 여부 확인 후 인증 토큰 발급
            // bearerTokenResolver: pat_ prefix 토큰은 null 반환하여 JWT 필터가 처리하지 않도록 한다
            .oauth2ResourceServer { rs ->
                rs.bearerTokenResolver(patSkippingBearerTokenResolver)
                rs.jwt { jwt -> jwt.jwtAuthenticationConverter(sidRevokeJwtConverter) }
            }
            .build()
    }

    private companion object {
        /**
         * 기존 STATELESS API 체인 우선순위 — SAML(1)/OIDC(2) 경로 외 모든 요청을 처리한다.
         * SAML 체인(Order=1)·OIDC 체인(Order=2)보다 후순위로, 세 체인 모두 distinct order 를 갖도록
         * 2→3 으로 1칸 밀었다 (FR-AU-04 C1 — @Order 동률 회피, OidcSecurityConfig KDoc 참조).
         */
        const val API_CHAIN_ORDER = 3

        /** 로그인 전 호출되는 활성 SAML IdP 목록 엔드포인트 (permitAll, [com.atlas.bts.identity.web.SamlIdpController]). */
        const val SAML_IDPS_PATH = "/api/v1/auth/saml/idps"

        /** 로그인 전 호출되는 활성 OIDC Provider 목록 엔드포인트 (permitAll, [com.atlas.bts.identity.web.OidcProviderController]). */
        const val OIDC_PROVIDERS_PATH = "/api/v1/auth/oidc/providers"

        /** 로그인 전 호출되는 도메인 기반 SSO 라우트 조회 엔드포인트 (permitAll, [com.atlas.bts.identity.web.DomainRouteController]). */
        const val ROUTE_PATH = "/api/v1/auth/route"

        /**
         * 로그인 2단계 검증 엔드포인트 (FR-MF-01 TOTP/백업코드, FR-MF-03 보안키 — method 로 분기).
         * 1단계(pw) 통과 후 정식 세션 발급 전에 호출되므로 permitAll + CSRF-ignore 양쪽에 등록한다(BLOCKER-1).
         */
        const val MFA_VERIFY_PATH = "/api/v1/auth/mfa/verify"

        /**
         * 로그인 2단계 보안키 인증(assertion) 시작 엔드포인트 (FR-MF-03).
         * 1단계(pw) 통과 후 정식 세션 발급 전(챌린지 토큰만)에 호출되므로 [MFA_VERIFY_PATH] 선례대로
         * permitAll + CSRF-ignore 양쪽에 등록한다. allowCredentials 옵션 발급만 하며 자격증명을 취급하지 않는다.
         */
        const val WEBAUTHN_AUTHENTICATE_START_PATH = "/api/v1/auth/mfa/webauthn/authenticate/start"

        /**
         * 익명 공개 대시보드 조회 엔드포인트 (FR-DB-03, [com.bts.notification.dashboard.web.PublicDashboardController]).
         *
         * 로그인 없이 불투명 공유 토큰만으로 접근하는 GET 전용 read-only 경로다.
         * permitAll 은 `HttpMethod.GET` 으로 고정 등록한다(C1) — BTS 유일 익명 데이터 경로이므로,
         * 같은 prefix 에 비-GET 매핑이 추가돼도 익명 노출되지 않도록 폭발 반경을 메서드 차원에서 봉인한다.
         * 단일 세그먼트 매처 — 전역 하위경로 와일드카드가 아니라 토큰 1개 path (`/api/v1/public/dashboards/{token}`)만 노출한다.
         * DEVELOPMENT.md §1.4 정식 예외(ADR 2026-07-02-fr-db-03-dashboard-share·게이트1 승인).
         */
        const val PUBLIC_DASHBOARDS_PATH = "/api/v1/public/dashboards/*"

        /**
         * 익명 iCal 구독 피드 엔드포인트 (FR-CA-02, [com.atlas.bts.identity.calendar.IcalFeedController]).
         *
         * 외부 캘린더 앱이 Authorization 헤더 없이 폴링하는 GET 전용 read-only 경로다.
         * permitAll 은 `HttpMethod.GET` 으로 고정 등록한다 — 같은 prefix 에 비-GET 매핑이 추가돼도
         * 익명 노출되지 않도록 폭발 반경을 메서드 차원에서 봉인한다(PUBLIC_DASHBOARDS_PATH 와 동일 원칙).
         * 단일 세그먼트 매처 — 토큰 1개 path (`/ical/feed/{token}.ics`)만 노출한다(하위경로 와일드카드 아님).
         * DEVELOPMENT.md §1.4 정식 예외(ADR 2026-07-09-fr-ca-02-ical-export·게이트1 승인).
         */
        const val ICAL_FEED_PATH = "/ical/feed/*"

        /**
         * STOMP over WebSocket 핸드셰이크 경로 (FR-NT-02,
         * [com.bts.notification.config.WebSocketConfig] 가 등록한다).
         *
         * **HTTP 계층만** 여는 permitAll 이다. 실제 인증은 한 단계 뒤인 STOMP `CONNECT` frame 의
         * native header `Authorization: Bearer <accessToken>` 에서 이뤄지고, 그 검증은
         * [com.bts.notification.config.StompAuthChannelInterceptor] 가 단독으로 진다 —
         * 헤더 부재·형식 오류·decode 실패·subject 부재·PAT 사용을 모두 거부하며 클라이언트발 SEND 도
         * 막는다(push-only). 따라서 CONNECT 성공 전의 소켓은 어떤 데이터도 나르지 못한다.
         *
         * 브라우저 WebSocket API 가 업그레이드 요청에 임의 헤더를 실을 수 없어 생긴 구조적 제약이며,
         * Spring 의 STOMP + JWT 표준 배치다. 단일 경로 — 하위 와일드카드로 넓히지 않는다.
         */
        const val WS_HANDSHAKE_PATH = "/ws"

        /**
         * 외부 시스템이 자격증명 없이 직접 호출하는 인바운드 웹훅 6경로 — slack 4 (FR-SL-01/03/04/05) ·
         * git 1 (FR-AT-07 PR-C) · automation 1 (FR-AT-01).
         *
         * DEVELOPMENT.md §1.4 정식 예외 — **근거 정본은 두 ADR**이다.
         * - slack 4 — `docs/decisions/2026-07-15-slack-inbound-permitall-central.md` (게이트1 승인)
         * - git 1 · automation 1 — `docs/decisions/2026-07-17-git-webhook-inbound-permitall.md`
         *   (**2026-07-17 Maxi 게이트1 승인**, `DATA.md §1.5` 요구대로 plan-eng-review·plan-ceo-review 양쪽 CLEAR)
         *
         * ★ **편집 전 PR-C ADR §D4-a 필독. 이 목록이 permitAll · CSRF-ignore · bearer skip(§D7) 3곳을 구동한다.**
         * 하나만 빠뜨려도 증상이 전부 401/403 이라 어느 곳이 빠졌는지 구분되지 않는다. 이 단일 목록 구조가 막는 것은
         * **경로·메서드 divergence**(한쪽에만 추가)이지 `forEach` 블록 삭제가 아니다 — 그건 app 모듈 prod 조립
         * HTTP 테스트가 잡는다. **구조와 테스트가 함께 가드이며 어느 한쪽도 단독으로 충분하지 않다.**
         *
         * ## 검증 주체 이관 — 다만 등급이 균일하지 않다
         * permitAll 은 인증 제거가 아니라 **검증 주체 이관**이다(slack ADR §D2) — 외부 시스템에는 발급할
         * JWT·세션·PAT 가 없다. 그러나 필터가 비킨 자리를 대신하는 검증의 등급이 경로군마다 다르다.
         * - **slack 4** — [com.bts.slack.security.SlackSignatureVerifier] HMAC 서명 검증 + replay 윈도우.
         * - **git 1** — `GitWebhookSignatureVerifier` HMAC 서명 검증(GITHUB) / 평문 토큰 비교(GITLAB —
         *   GitLab 이 HMAC 을 제공하지 않아 **GITHUB 과 동급이 아니다**, PR-C ADR §잔여위험 R3).
         * - **automation 1** — **서명 검증이 없다.** 불투명 토큰 소지 자체가 인증이다. 따라서 slack ADR 의
         *   *"필터가 비키는 자리에 더 강한 검증이 선다"* 논거는 **이 경로에 성립하지 않는다**(PR-C ADR §D3).
         *   부수효과를 내는 POST 라 토큰이 새면 룰 actor 권한으로 이슈가 변경된다(§잔여위험 R1).
         *
         * ## 매처 형태 — 메서드 고정 + 단일 세그먼트 2겹
         * 전역 하위경로 와일드카드가 아니라 토큰 1개 path 만 노출한다 — [PUBLIC_DASHBOARDS_PATH]·[ICAL_FEED_PATH]
         * 와 동일 원칙이며, 같은 prefix 에 매핑이 추가돼도 폭발 반경이 넓어지지 않는다.
         * git·automation 은 `/api` 하위 경로이므로 permitAll 등록이 아래 API 전역 `authenticated()` 매처
         * **보다 위**여야 한다(선언 순서 = 계약).
         *
         * (KDoc 본문에 매처 리터럴을 그대로 적지 않는다 — Kotlin 은 블록 주석이 중첩되므로 슬래시+별표가
         * 나타나면 주석이 조기 종료되지 않고 아래 선언까지 삼킨다. 실제 매처는 아래 목록이 정본이다.)
         */
        val INBOUND_WEBHOOK_PATHS =
            listOf(
                // slack (FR-SL-01/03/04/05) — 정확 경로
                HttpMethod.POST to "/slack/events",
                HttpMethod.POST to "/slack/commands",
                HttpMethod.POST to "/slack/interactions",
                HttpMethod.GET to "/slack/install/callback",
                // git (FR-AT-07 PR-C) — /api/v1/webhooks/git/{token} 단일 세그먼트
                HttpMethod.POST to "/api/v1/webhooks/git/*",
                // automation (FR-AT-01) — /api/v1/automation/webhooks/{token} 단일 세그먼트
                HttpMethod.POST to "/api/v1/automation/webhooks/*",
            )
    }
}

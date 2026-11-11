// 「QA Hub AI」 계정의 쓰기를 QA Hub 가 부르는 곳으로만 좁히는 게이트 필터 (BTS-F2-02 · F10-02 · FR-API-04)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.web.support.resolveActorId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * QA Hub(test_platform)가 BTS 를 부르는 전용 계정 「QA Hub AI」 의 쓰기 게이트 (BTS-F2-02).
 *
 * 계정은 Auth 통합계정이고 열쇠는 그 계정의 Auth PAT 다. BTS 에서는 프로젝트 MEMBER 라 이슈 필드 고치기 · 링크 ·
 * 작업 기록 · 자기 댓글 지우기도 되는데, 열쇠가 새도 그 일은 못 하게 쓰기를 [ALLOWED_WRITES] 로만 연다.
 *
 * - 대상은 `bts.qahub-ai.user-id`(env `BTS_QAHUB_AI_USER_ID`)의 BTS 사용자 하나. null 이면 아무것도 안 막는다.
 * - 인증 방식을 가리지 않는다(PAT · JWT) — 그 계정으로 브라우저에 로그인해도 같다.
 * - GET · HEAD · OPTIONS 는 손대지 않는다. 읽기 범위는 프로젝트 멤버십이 정한다.
 * - 막으면 403 `{"error":"forbidden"}`. 경로는 응답에 담지 않는다(웹훅 · iCal 경로 세그먼트에 비밀 토큰이 있다).
 * - 미인증은 손대지 않아 401 흐름이 그대로다.
 *
 * QA Hub 가 새 쓰기 통로를 쓰면 [ALLOWED_WRITES] 에 더한다 — 안 그러면 QA Hub 의 그 호출이 403 으로 깨진다.
 */
class QaHubAiWriteGateFilter(
    private val qaHubAiUserId: UUID?,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(QaHubAiWriteGateFilter::class.java)

    init {
        // 비면 조용히 꺼지므로 기동 로그로 켜짐 · 꺼짐을 남긴다 — 운영 .env 에 값이 들어갔는지 여기서 본다
        log.info("QA Hub AI 쓰기 게이트 {}", qaHubAiUserId?.let { "켜짐 userId=$it" } ?: "꺼짐(BTS_QAHUB_AI_USER_ID 없음)")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isQaHubAi() && request.method !in READ_METHODS && ALLOWED_WRITES.none { it.matches(request) }) {
            log.warn("QA Hub AI 쓰기 차단 method={}", request.method) // 경로는 남기지 않는다(클래스 KDoc)
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(FORBIDDEN_BODY)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isQaHubAi(): Boolean =
        qaHubAiUserId != null &&
            resolveActorId(SecurityContextHolder.getContext().authentication?.principal as? Jwt) == qaHubAiUserId

    private companion object {
        val READ_METHODS = setOf(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name())

        const val FORBIDDEN_BODY = """{"error":"forbidden"}"""

        /**
         * QA Hub 가 이 열쇠로 부르는 쓰기 + 읽기지만 POST 인 AQL 검색 (test_platform `apps/admin/src/bts`).
         * 사용자 정의 필드는 값만 고치는 좁은 길 `/custom-fields` 만 연다 — 이슈 필드 「테스트 수정 PR」에 PR 주소를 적는다
         * (BTS-F10-02). 제목 · 설명까지 고치는 `PATCH /api/v1/issues/{key}` 는 계속 막는다.
         * 로그아웃은 권한을 줄이기만 해서 연다 — 그 계정으로 브라우저에 들어왔을 때 세션을 끊을 수 있게.
         */
        val ALLOWED_WRITES =
            listOf(
                antMatcher(HttpMethod.POST, "/api/v1/auth/logout"),
                antMatcher(HttpMethod.POST, "/api/v1/issues"),
                antMatcher(HttpMethod.POST, "/api/v1/issues/*/comments"),
                antMatcher(HttpMethod.POST, "/api/v1/issues/*/transition"),
                antMatcher(HttpMethod.PATCH, "/api/v1/issues/*/assignee"),
                antMatcher(HttpMethod.POST, "/api/v1/issues/*/attachments"),
                antMatcher(HttpMethod.PATCH, "/api/v1/issues/*/custom-fields"),
                antMatcher(HttpMethod.POST, "/api/v1/search/aql"),
            )
    }
}

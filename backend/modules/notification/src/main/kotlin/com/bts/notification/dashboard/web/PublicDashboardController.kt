// 익명 공개 대시보드 조회 REST 컨트롤러 — 비인증 GET /{token} read-only (FR-DB-03, ADR 2026-07-02)

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.application.PublicDashboardNotFoundException
import com.bts.notification.dashboard.web.dto.PublicDashboardResponse
import com.bts.notification.web.DataResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant

/**
 * 익명(비로그인) 공개 대시보드 조회 컨트롤러 (FR-DB-03 / BTS 첫 비인증 데이터 경로).
 *
 * 엔드포인트.
 * - GET /api/v1/public/dashboards/{token} — 불투명 공유 토큰으로 정화된 공개 스냅샷 조회 (200 / 404)
 *
 * ## 인증 정책 — SecurityContext 무참조
 * 이 컨트롤러는 currentActorId() 등 [org.springframework.security.core.context.SecurityContextHolder] 를
 * 일절 참조하지 않는다. 접근 제어는 오직 불투명 공유 토큰의 소지(possession)로만 이뤄진다
 * (직교 토큰 — ADR 2026-07-02-fr-db-03-dashboard-share). SecurityConfig 는 이 경로를
 * permitAll(GET read-only)로 노출한다(DEVELOPMENT.md §1.4 "인증 없는 엔드포인트" 정식 예외).
 *
 * ## 404 수렴 — 컨트롤러-로컬 핸들러
 * 미존재·만료·부모 삭제는 서비스가 모두 [PublicDashboardNotFoundException] 으로 수렴시킨다(존재 숨김).
 * 이 예외는 컨트롤러-로컬 @ExceptionHandler 로 명시 매핑해 404 를 보장한다 — catch-all 로 흘러 500 으로
 * 변질되거나 타 컨트롤러 advice 에 잘못 잡히는 것을 차단한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception /
 * domain-exception-http-handler-basepackage-scope).
 *
 * ## 로그
 * 토큰 원문·해시는 절대 출력하지 않는다(DEVELOPMENT.md §1.2). 결과 상태만 debug 로 남긴다.
 *
 * @param service 대시보드 서비스 (getPublicByToken — 해싱·정화·404 수렴 담당)
 */
@RestController
@RequestMapping("/api/v1/public/dashboards")
class PublicDashboardController(
    private val service: DashboardService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 불투명 공유 토큰으로 익명 공개 대시보드 스냅샷을 조회한다.
     *
     * 토큰은 파싱·검증하지 않고 String 그대로 서비스에 위임한다(SHA-256 해싱·정화·404 수렴은 서비스 책임).
     * SecurityContext 를 참조하지 않으므로 미인증 요청도 그대로 처리한다.
     *
     * @param token 원문 공유 토큰 (단일 경로 세그먼트)
     * @return 200 OK + PublicDashboardResponse (정화된 스냅샷)
     * @throws PublicDashboardNotFoundException 미존재·만료·부모 삭제 → 404 (컨트롤러-로컬 핸들러)
     */
    @GetMapping("/{token}")
    fun getPublic(
        @PathVariable token: String,
    ): ResponseEntity<DataResponse<PublicDashboardResponse>> {
        val snapshot = service.getPublicByToken(token)
        log.debug("PublicDashboardController.getPublic — 공개 대시보드 조회 성공")
        return ResponseEntity.ok(DataResponse(data = PublicDashboardResponse.from(snapshot)))
    }

    /**
     * 익명 공개 조회 실패(미존재·만료·부모 삭제) → 404.
     *
     * 컨트롤러-로컬 매핑으로 catch-all→500 변질·타 컨트롤러 advice 오포착을 차단한다.
     * 토큰 존재/만료 여부를 노출하지 않는 일반 메시지만 담는다(열거 차단). 로그에 토큰 원문·해시 미출력.
     *
     * @param ex 익명 조회 실패 예외 (내부 상태 미참조)
     * @return 404 ProblemDetail (errorCode=NOTIF_DASHBOARD_NOT_FOUND)
     */
    @ExceptionHandler(PublicDashboardNotFoundException::class)
    fun handleNotFound(
        @Suppress("UnusedParameter") ex: PublicDashboardNotFoundException,
    ): ProblemDetail {
        log.debug("NOTIF_DASHBOARD_404 public_not_found")
        val pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND)
        pd.type = URI.create("https://bts.example.com/problems/dashboard-not-found")
        pd.title = "Dashboard Not Found"
        pd.detail = "공유된 대시보드를 찾을 수 없습니다."
        pd.setProperty("errorCode", "NOTIF_DASHBOARD_NOT_FOUND")
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }
}

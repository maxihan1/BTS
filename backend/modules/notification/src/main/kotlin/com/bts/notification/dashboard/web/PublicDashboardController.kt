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
 * ## 예외 해소 순서 — 왜 이 컨트롤러가 500 핸들러를 직접 갖는가
 * Spring 은 컨트롤러 클래스의 @ExceptionHandler 를 **먼저** 찾고, 없을 때만 @ControllerAdvice 로 간다.
 * 이 컨트롤러가 Exception catch-all 을 가지므로 **모든 예외가 여기서 끝난다** — 의도된 설계다.
 * 공개 경로는 정화가 기본값이어야 하고, advice 의 일반 응답이 흘러들면 instance 로 토큰이 샌다.
 *
 *   요청 GET /api/v1/public/dashboards/{token}
 *          |
 *          +- 정상 ------------------------------> 200 DataResponse (ProblemDetail 아님, instance 없음)
 *          |
 *          +- 예외 발생
 *               |
 *               v
 *      +--------------------------------+
 *      | (1) 컨트롤러-로컬 @ExceptionHandler|  <-- 항상 여기서 매치된다
 *      +--------------------------------+
 *      | PublicDashboardNotFound -> 404 |--+
 *      | Exception (catch-all)   -> 500 |--+   둘 다 problem() 을 거친다
 *      +--------------------------------+  |   -> instance = INSTANCE_PATH (토큰 세그먼트 없음)
 *               : (도달하지 않음)            |
 *               v                          v
 *      +--------------------------------+  응답 본문/헤더에 원문 토큰 0
 *      | (2) DashboardExceptionHandler  |
 *      |     advice — instance 미설정    |  <-- 인증 경로(`/api/v1/dashboards` 하위)는 계속 여기를 쓴다.
 *      +--------------------------------+      비밀값이 없어 요청 URI 를 남기는 편이 진단에 유리하다.
 *
 * (주의. KDoc 안에 `/` 다음에 `*` 를 연달아 쓰면 Kotlin 이 중첩 블록 주석으로 읽어 컴파일이 깨진다.
 *  경로 와일드카드를 표기할 때는 위처럼 산문으로 풀어 쓴다.)
 *
 * 이 컨트롤러의 catch-all 을 "advice 와 중복" 이라며 지우면 즉시 토큰 유출로 회귀한다.
 * `PublicDashboardErrorTokenLeakTest` 가 그 회귀를 잡는다.
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
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "dashboard-not-found",
            title = "Dashboard Not Found",
            errorCode = "NOTIF_DASHBOARD_NOT_FOUND",
            detail = "공유된 대시보드를 찾을 수 없습니다.",
        )
    }

    /**
     * 분류되지 않은 모든 예외 → 500. **공개 경로 전용 catch-all.**
     *
     * [DashboardExceptionHandler] advice 도 같은 매핑을 갖지만 그 `problem()` 은 `instance` 를 비워 두므로
     * 이 경로로 흘러가면 **원문 토큰이 응답에 실린다**. 컨트롤러-로컬이 advice 보다 먼저 매치되는 성질로
     * 공개 경로의 오류를 여기서 흡수한다(클래스 KDoc 다이어그램 (1)).
     * 상태·errorCode·detail 은 advice 와 **한 글자도 다르지 않게** 유지한다(응답 drift 금지).
     *
     * ## 상태 코드를 분기하지 않는 이유
     * `ResponseStatusException` 의 상태를 보존하려 `HttpStatus.valueOf(...)` 를 쓰면 **비표준 코드에서
     * 그 호출이 예외를 던져 핸들러 자체가 실패**하고, Spring 기본 오류 처리(`/error`)로 넘어가 응답
     * `path` 에 **다시 원문 토큰이 실린다** — 막으려던 것을 되살리는 경로다. 이 경로의
     * [DashboardService.getPublicByToken] 은 `ResponseStatusException` 을 던지지 않아(도달 불가)
     * 상태 보존의 실익이 없으므로, 분기를 두지 않고 전부 500 으로 수렴시킨다.
     * 새 오류 통로가 생기면 `PublicDashboardErrorTokenLeakTest` 의 `SEAL` 이 미분류로 실패시킨다.
     *
     * @param ex 분류되지 않은 예외 (스택은 로그에만, 응답 본문에는 내부 사정을 싣지 않는다)
     * @return 500 ProblemDetail (errorCode=NOTIF_DASHBOARD_INTERNAL_ERROR)
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
     * ProblemDetail(RFC 7807) 조립 헬퍼 (`AutomationWebhookController.problem` 동형).
     *
     * ## ★ `instance` 를 반드시 명시한다 — 비우면 Spring 이 원문 토큰을 응답에 싣는다
     * `RequestResponseBodyMethodProcessor` 는 반환된 [ProblemDetail] 의 `instance` 가 `null` 이면 요청
     * URI 로 자동 채운다. 이 엔드포인트의 요청 URI 에는 **경로 세그먼트에 원문 공유 토큰**이 들어 있으므로,
     * 비워 두면 404·500 **모든** 오류 응답 본문에 평문 토큰이 실려 나간다 — 보낸 사람이야 아는 값이지만
     * 그 본문이 응답 로그·프록시 캐시·에러 트래커에 적재되는 순간 그것이 **평문 토큰 저장/로깅**이다
     * (DEVELOPMENT.md §1.1-1·§1.1-2 · ADR 2026-07-02 D1 "원문은 발급 응답에서 1회만 노출").
     *
     * **모든 오류 응답이 이 한 함수를 지나게 두는 것이 설계의 핵심**이다 — 핸들러마다 `instance` 를
     * 기억해서 넣는 구조였다면 언젠가 빠뜨린다(실제로 이 컨트롤러가 도입 후 3주간 그 상태였다).
     *
     * @param status HTTP 응답 상태 코드.
     * @param type problems URI 의 suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드(`NOTIF_DASHBOARD_` prefix).
     * @param detail 상세 설명(요청 값·내부 사정을 싣지 않는 고정 문구).
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
         * 비워 두면 Spring 이 원문 토큰이 든 요청 URI 로 채운다([problem] KDoc ★ 참조).
         * automation BC 의 `AutomationWebhookController.INSTANCE_PATH` ·
         * `GitWebhookController.INSTANCE_PATH` 동형.
         */
        const val INSTANCE_PATH = "/api/v1/public/dashboards"
    }
}

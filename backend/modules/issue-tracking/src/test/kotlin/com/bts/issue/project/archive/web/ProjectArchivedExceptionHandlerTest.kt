// ProjectArchivedExceptionHandler MockMvc 테스트 — 409 매핑·내부 누출 차단·비 catch-all (FR-PJ-04 PR-4 Task 3)

package com.bts.issue.project.archive.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.issue.project.archive.ProjectArchivedException
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * [ProjectArchivedExceptionHandler] standalone MockMvc 테스트.
 *
 * 컨트롤러 부팅 없이 throwaway 컨트롤러 + advice 만 조립해 매핑 계약을 검증한다.
 * - [ProjectArchivedException] → 409
 * - 응답에 예외 message 의 내부 식별자(projectId/projectKey)가 새지 않음
 * - 다른 예외([ResponseStatusException] 401)는 삼키지 않고 통과(비 catch-all 판별자)
 */
class ProjectArchivedExceptionHandlerTest {
    /** 예외를 던지기만 하는 테스트 전용 컨트롤러. */
    @RestController
    class ThrowingController {
        @GetMapping("/archive-test/archived")
        fun archived(): Nothing = throw ProjectArchivedException("SECRET-PROJECT-42")

        @GetMapping("/archive-test/unauthorized")
        fun unauthorized(): Nothing = throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "no")

        /**
         * **경로 세그먼트에 비밀 토큰이 실린** 요청을 재현한다 — 이 advice 는 선택자가 없어
         * 레포의 어느 컨트롤러에도 붙을 수 있고, 그중 4개 경로가 경로 세그먼트에 원문 토큰을 싣는다.
         */
        @GetMapping("/archive-test/archived/{token}")
        fun archivedWithPathToken(): Nothing = throw ProjectArchivedException("SECRET-PROJECT-42")
    }

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(ThrowingController())
            .setControllerAdvice(ProjectArchivedExceptionHandler())
            .build()

    @Test
    fun `ProjectArchivedException 은 409 로 매핑된다`() {
        mockMvc.perform(get("/archive-test/archived"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_ARCHIVED"))
    }

    @Test
    fun `409 응답은 내부 식별자를 노출하지 않고 일반 메시지로 치환한다`() {
        mockMvc.perform(get("/archive-test/archived"))
            .andExpect(jsonPath("$.detail").value("아카이브된 프로젝트에는 쓰기를 할 수 없습니다."))
            .andExpect(content().string(not(containsString("SECRET-PROJECT-42"))))
    }

    @Test
    fun `다른 예외(401)는 삼키지 않고 통과시킨다 (비 catch-all)`() {
        mockMvc.perform(get("/archive-test/unauthorized"))
            .andExpect(status().isUnauthorized)
    }

    /**
     * **경로 토큰 유출 봉인 (N3).**
     *
     * Spring MVC 의 `RequestResponseBodyMethodProcessor` 는 반환된 [org.springframework.http.ProblemDetail]
     * 의 `instance` 가 `null` 이면 **요청 URI 로 자동으로 채운다**(#310 이 실측 확정한 기전).
     * 이 advice 는 **선택자가 없어 레포의 어느 컨트롤러에도 붙으며**, 그중 4개 경로가
     * 경로 세그먼트에 원문 비밀 토큰을 싣는다(공유 대시보드·iCal 피드·git 웹훅·automation 웹훅).
     *
     * 그래서 `instance` 를 **고정 경로로 명시**해 자동채움 통로 자체를 없앤다 —
     * automation 두 웹훅 컨트롤러와 `PublicDashboardController` 가 이미 같은 처방을 쓴다.
     *
     * ## 판별자 주의
     * 상태코드·`errorCode`·`detail` 은 이 축의 판별자가 **못 된다** — `instance` 를 지워도 전부 그대로다.
     * 유일한 판별자는 응답 본문에 요청 경로 문자열이 실리는가이다.
     */
    @Test
    fun `409 응답의 instance 가 요청 URI 로 자동 채워지지 않는다 (N3 경로토큰 봉인)`() {
        mockMvc.perform(get("/archive-test/archived/$PATH_TOKEN"))
            .andExpect(status().isConflict)
            // 양성 증거 — 이 advice 가 실제로 응답했다. 없으면 아래 부재 단언이 공허해진다
            // (다른 핸들러가 잡아 토큰 없는 응답을 내도 통과해 버린다).
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_ARCHIVED"))
            .andExpect(content().string(not(containsString(PATH_TOKEN))))
    }

    @Test
    fun `instance 는 요청 정보를 담지 않는 발생 UUID 다 (RFC 9457 시맨틱)`() {
        mockMvc.perform(get("/archive-test/archived/$PATH_TOKEN"))
            .andExpect(jsonPath("$.instance").value(matchesPattern(INSTANCE_URN_PATTERN)))
    }

    /**
     * **응답↔로그 상관관계가 계약이다.**
     *
     * `instance` 를 요청 URI 에서 떼어낸 대가로 "이 오류가 서버 어디서 났는가"를 잃는다.
     * 발생 UUID 를 **응답과 로그 양쪽에 같은 값으로** 실어 그 진단성을 되찾는데, 한쪽만 바꾸면
     * 값어치가 조용히 사라진다(로그의 UUID 와 응답의 UUID 가 달라도 둘 다 형식은 맞다).
     * 이 테스트만이 그 회귀를 잡는다.
     */
    @Test
    fun `응답 instance 의 UUID 와 서버 로그의 occurrenceId 가 일치한다 (진단 상관관계)`() {
        val logger = LoggerFactory.getLogger(ProjectArchivedExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            val body =
                mockMvc.perform(get("/archive-test/archived/$PATH_TOKEN"))
                    .andReturn()
                    .response
                    .contentAsString

            val instanceUuid =
                INSTANCE_UUID_REGEX.find(body)?.groupValues?.get(1)
                    ?: error("응답에서 instance UUID 를 찾지 못했다: $body")

            val loggedLine =
                appender.list
                    .map { it.formattedMessage }
                    .single { it.contains(ProjectArchivedExceptionHandler.LOG_OCCURRENCE_KEY) }

            assertThat(loggedLine)
                .withFailMessage(
                    "로그의 occurrenceId 가 응답 instance 의 UUID(%s)와 다르다. 로그=%s",
                    instanceUuid,
                    loggedLine,
                ).contains("${ProjectArchivedExceptionHandler.LOG_OCCURRENCE_KEY}$instanceUuid")
        } finally {
            logger.detachAppender(appender)
        }
    }

    private companion object {
        /** 경로 세그먼트에 실리는 원문 토큰을 흉내 낸 값(테스트 전용). 누출 판정의 검색어다. */
        const val PATH_TOKEN = "n3-path-token-abcdef0123456789"

        /** `urn:uuid:<RFC 4122 UUID>` — 요청 정보가 한 글자도 들어갈 수 없는 형식이다. */
        const val INSTANCE_URN_PATTERN =
            "^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

        val INSTANCE_UUID_REGEX =
            Regex(""""instance"\s*:\s*"urn:uuid:([0-9a-f-]{36})"""")
    }
}

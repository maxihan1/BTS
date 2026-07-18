// ProjectArchivedExceptionHandler MockMvc 테스트 — 409 매핑·내부 누출 차단·비 catch-all (FR-PJ-04 PR-4 Task 3)

package com.bts.issue.project.archive.web

import com.bts.issue.project.archive.ProjectArchivedException
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
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
}

// ProjectResponse.from() archived 매핑 단위 테스트 (FR-PJ PR-5 Task 1)

package com.bts.issue.project.web.dto

import com.bts.issue.project.domain.Project
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [ProjectResponse.from] 이 [Project.archivedAt] 을 `archived: Boolean` 으로 파생 매핑하는지 검증한다.
 *
 * PR-4 가 도메인에 `archivedAt` 을 추가했으나 HTTP 응답 DTO 는 아직 노출하지 않아
 * 프론트가 아카이브 상태를 알 수 없던 갭을 메운다.
 */
class ProjectResponseTest {
    private val projectId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    @Test
    fun `archivedAt 이 non-null 이면 archived 는 true 이다`() {
        val project =
            Project(
                id = projectId,
                key = "BTS",
                name = "Project Atlas",
                archivedAt = Instant.parse("2026-07-01T00:00:00Z"),
            )

        val response = ProjectResponse.from(project)

        assertTrue(response.archived)
    }

    @Test
    fun `archivedAt 이 null 이면 archived 는 false 이다`() {
        val project =
            Project(
                id = projectId,
                key = "BTS",
                name = "Project Atlas",
                archivedAt = null,
            )

        val response = ProjectResponse.from(project)

        assertFalse(response.archived)
    }
}

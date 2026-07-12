// 자동화 조건 평가용 이슈 스냅샷 cross-BC 읽기 포트 계약 검증 — IssueSnapshotPort fail-closed + VO 필드
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IssueSnapshotPort] cross-BC 읽기 포트 계약 테스트 (FR-AT-03 Task 3).
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [IssueSnapshotPort] 는 default 구현이 없음 — `fetch` 가 추상(fail-closed).
 * - [IssueSnapshotPort.fetch] 는 actor 가시성 제한/이슈 부재 시 null 을 반환할 수 있다.
 * - [IssueSnapshot] 필드 계약(key/projectKey/type/status/priority/assigneeId/reporterId/labels/summary).
 * - [IssueSnapshot] 의 nullable 필드(type/priority/assigneeId/reporterId)는 실제로 null 을 담을 수 있다.
 */
class IssueSnapshotContractTest {
    // ── IssueSnapshotPort fail-closed ────────────────────────────────────────

    @Test
    fun `IssueSnapshotPort 는 default 구현 없이 fetch 가 추상으로 선언된다`() {
        // fetch 를 override 하지 않으면 익명 객체 생성이 컴파일되지 않는다.
        // 이 테스트는 컴파일 타임에 override 강제를 검증한다.
        val actorUserId = UUID.randomUUID()
        val port =
            object : IssueSnapshotPort {
                override fun fetch(
                    actorUserId: UUID,
                    issueKey: String,
                ): IssueSnapshot? =
                    IssueSnapshot(
                        key = issueKey,
                        projectKey = "PROJ",
                        type = "Bug",
                        status = "in_progress",
                        priority = 3,
                        assigneeId = actorUserId,
                        reporterId = actorUserId,
                        labels = listOf("urgent"),
                        summary = "테스트 이슈",
                    )
            }

        val result = port.fetch(actorUserId, "PROJ-1")

        assertThat(result).isNotNull
        assertThat(result?.key).isEqualTo("PROJ-1")
    }

    @Test
    fun `fetch 는 actor 가 못 보는 이슈 또는 이슈 부재 시 null 을 반환할 수 있다`() {
        // 가시성 강제(SDD §12.4 관리자 우회 없음)를 표현하는 계약 — null 이 유효한 반환값임을 검증한다.
        val port =
            object : IssueSnapshotPort {
                override fun fetch(
                    actorUserId: UUID,
                    issueKey: String,
                ): IssueSnapshot? = null
            }

        val result = port.fetch(UUID.randomUUID(), "SECRET-1")

        assertThat(result).isNull()
    }

    // ── IssueSnapshot 필드 계약 ───────────────────────────────────────────────

    @Test
    fun `IssueSnapshot 은 key projectKey type status priority assigneeId reporterId labels summary 를 보존한다`() {
        val assigneeId = UUID.randomUUID()
        val reporterId = UUID.randomUUID()

        val snapshot =
            IssueSnapshot(
                key = "PROJ-1",
                projectKey = "PROJ",
                type = "Bug",
                status = "done",
                priority = 5,
                assigneeId = assigneeId,
                reporterId = reporterId,
                labels = listOf("urgent", "backend"),
                summary = "로그인 실패",
            )

        assertThat(snapshot.key).isEqualTo("PROJ-1")
        assertThat(snapshot.projectKey).isEqualTo("PROJ")
        assertThat(snapshot.type).isEqualTo("Bug")
        assertThat(snapshot.status).isEqualTo("done")
        assertThat(snapshot.priority).isEqualTo(5)
        assertThat(snapshot.assigneeId).isEqualTo(assigneeId)
        assertThat(snapshot.reporterId).isEqualTo(reporterId)
        assertThat(snapshot.labels).containsExactly("urgent", "backend")
        assertThat(snapshot.summary).isEqualTo("로그인 실패")
    }

    @Test
    fun `IssueSnapshot 은 type priority assigneeId reporterId 가 nullable 이다`() {
        val snapshot =
            IssueSnapshot(
                key = "PROJ-2",
                projectKey = "PROJ",
                type = null,
                status = "open",
                priority = null,
                assigneeId = null,
                reporterId = null,
                labels = emptyList(),
                summary = "타입 미지정 이슈",
            )

        assertThat(snapshot.type).isNull()
        assertThat(snapshot.priority).isNull()
        assertThat(snapshot.assigneeId).isNull()
        assertThat(snapshot.reporterId).isNull()
        assertThat(snapshot.labels).isEmpty()
    }
}

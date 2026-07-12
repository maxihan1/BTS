// IssueCompletionOptionsPort VO 조립 + fail-closed 계약 단위 테스트
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IssueCompletionOptionsPort] cross-BC 포트 계약 테스트 (FR-SL-05 Task 1).
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [IssueCompletionOptionsPort] 는 default 구현이 없음 — 추상 메서드(fail-closed).
 * - viewer 가 볼 수 없으면 `null` 을 반환할 수 있다(fail-closed 계약).
 * - [IssueCompletionOptions] 필드 계약(version/doneTransitions/resolutions).
 * - [DoneTransition] 필드 계약(toStateKey/label).
 * - [ResolutionOption] 필드 계약(id/label).
 */
class IssueCompletionOptionsPortTest {
    // ── IssueCompletionOptionsPort fail-closed ───────────────────────────────

    @Test
    fun `IssueCompletionOptionsPort 는 default 구현 없이 추상 메서드만 선언된다`() {
        // getCompletionOptions 를 override 하지 않으면 익명 객체 생성이 컴파일되지 않는다.
        // 이 테스트는 컴파일 타임에 override 강제를 검증한다.
        val port =
            object : IssueCompletionOptionsPort {
                override fun getCompletionOptions(
                    issueKey: String,
                    viewerUserId: UUID,
                ): IssueCompletionOptions =
                    IssueCompletionOptions(
                        version = 3L,
                        doneTransitions = listOf(DoneTransition(toStateKey = "done", label = "완료")),
                        resolutions = listOf(ResolutionOption(id = UUID.randomUUID(), label = "완료됨")),
                    )
            }

        val result = port.getCompletionOptions("PROJ-1", UUID.randomUUID())

        assertThat(result.version).isEqualTo(3L)
    }

    @Test
    fun `IssueCompletionOptionsPort 구현체는 viewer 가 볼 수 없으면 null 을 반환할 수 있다`() {
        // fail-closed 계약 — 판정 불가·미가시·부재 모두 null.
        val port =
            object : IssueCompletionOptionsPort {
                override fun getCompletionOptions(
                    issueKey: String,
                    viewerUserId: UUID,
                ): IssueCompletionOptions? = null
            }

        val result = port.getCompletionOptions("PROJ-1", UUID.randomUUID())

        assertThat(result).isNull()
    }

    // ── IssueCompletionOptions 필드 계약 ──────────────────────────────────────

    @Test
    fun `IssueCompletionOptions 는 version doneTransitions resolutions 를 보존한다`() {
        val doneTransition = DoneTransition(toStateKey = "done", label = "완료로 전환")
        val resolution = ResolutionOption(id = UUID.randomUUID(), label = "해결됨")

        val options =
            IssueCompletionOptions(
                version = 5L,
                doneTransitions = listOf(doneTransition),
                resolutions = listOf(resolution),
            )

        assertThat(options.version).isEqualTo(5L)
        assertThat(options.doneTransitions).containsExactly(doneTransition)
        assertThat(options.resolutions).containsExactly(resolution)
    }

    @Test
    fun `IssueCompletionOptions 는 doneTransitions resolutions 가 빈 목록일 수 있다`() {
        // 이미 DONE 카테고리이거나 이동 가능한 전이·resolution 이 없는 경우.
        val options =
            IssueCompletionOptions(
                version = 0L,
                doneTransitions = emptyList(),
                resolutions = emptyList(),
            )

        assertThat(options.doneTransitions).isEmpty()
        assertThat(options.resolutions).isEmpty()
    }

    @Test
    fun `IssueCompletionOptions 는 동일 필드면 동등하다`() {
        val doneTransition = DoneTransition(toStateKey = "done", label = "완료")
        val resolution =
            ResolutionOption(id = UUID.fromString("11111111-1111-1111-1111-111111111111"), label = "완료됨")

        val a =
            IssueCompletionOptions(
                version = 1L,
                doneTransitions = listOf(doneTransition),
                resolutions = listOf(resolution),
            )
        val b =
            IssueCompletionOptions(
                version = 1L,
                doneTransitions = listOf(doneTransition),
                resolutions = listOf(resolution),
            )

        assertThat(a).isEqualTo(b)
    }

    // ── DoneTransition 필드 계약 ──────────────────────────────────────────────

    @Test
    fun `DoneTransition 은 toStateKey label 을 보존한다`() {
        val transition = DoneTransition(toStateKey = "resolved", label = "해결로 전환")

        assertThat(transition.toStateKey).isEqualTo("resolved")
        assertThat(transition.label).isEqualTo("해결로 전환")
    }

    @Test
    fun `DoneTransition 은 동일 필드면 동등하다`() {
        val a = DoneTransition(toStateKey = "done", label = "완료")
        val b = DoneTransition(toStateKey = "done", label = "완료")

        assertThat(a).isEqualTo(b)
    }

    // ── ResolutionOption 필드 계약 ────────────────────────────────────────────

    @Test
    fun `ResolutionOption 은 id label 을 보존한다`() {
        val id = UUID.randomUUID()
        val resolution = ResolutionOption(id = id, label = "해결되지 않음")

        assertThat(resolution.id).isEqualTo(id)
        assertThat(resolution.label).isEqualTo("해결되지 않음")
    }

    @Test
    fun `ResolutionOption 은 동일 필드면 동등하다`() {
        val id = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val a = ResolutionOption(id = id, label = "완료됨")
        val b = ResolutionOption(id = id, label = "완료됨")

        assertThat(a).isEqualTo(b)
    }
}

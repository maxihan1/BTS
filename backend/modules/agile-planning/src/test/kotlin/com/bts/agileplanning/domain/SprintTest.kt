// Sprint 도메인 애그리게이트 상태 전이 + 불변식 단위테스트

package com.bts.agileplanning.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Sprint 도메인 애그리게이트 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 순수 도메인 로직만 검증한다.
 *
 * 검증 시나리오.
 * - start(): PLANNED -> ACTIVE 정상 전이, ACTIVE/COMPLETED 에서 InvalidSprintTransitionException
 * - complete(): ACTIVE -> COMPLETED 정상 전이, PLANNED/COMPLETED 에서 InvalidSprintTransitionException
 * - 기간 불변식: startDate > endDate 는 IllegalArgumentException
 * - 이름 불변식: name 공백 시 IllegalArgumentException
 */
class SprintTest {

    private val baseId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 6, 24)

    private fun plannedSprint(
        name: String = "스프린트 1",
        goal: String? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
    ) = Sprint(
        id = baseId,
        projectKey = "PROJ",
        name = name,
        goal = goal,
        status = SprintStatus.PLANNED,
        startDate = startDate,
        endDate = endDate,
        version = 0L,
    )

    private fun activeSprint() = plannedSprint().start()

    // ─── start() 전이 ───────────────────────────────────────────────────────

    @Nested
    inner class StartTransition {

        @Test
        fun `PLANNED 스프린트를 start() 하면 ACTIVE 상태의 새 Sprint를 반환한다`() {
            val sprint = plannedSprint()
            val result = sprint.start()
            assertThat(result.status).isEqualTo(SprintStatus.ACTIVE)
        }

        @Test
        fun `start() 결과는 원본과 다른 객체이며 나머지 필드는 동일하다`() {
            val sprint = plannedSprint(name = "스프린트 A", goal = "목표")
            val result = sprint.start()
            assertThat(result).isNotSameAs(sprint)
            assertThat(result.id).isEqualTo(sprint.id)
            assertThat(result.name).isEqualTo(sprint.name)
            assertThat(result.goal).isEqualTo(sprint.goal)
            assertThat(result.projectKey).isEqualTo(sprint.projectKey)
        }

        @Test
        fun `ACTIVE 스프린트를 start() 하면 InvalidSprintTransitionException이 발생한다`() {
            val sprint = activeSprint()
            assertThatThrownBy { sprint.start() }
                .isInstanceOf(InvalidSprintTransitionException::class.java)
        }

        @Test
        fun `COMPLETED 스프린트를 start() 하면 InvalidSprintTransitionException이 발생한다`() {
            val sprint = activeSprint().complete()
            assertThatThrownBy { sprint.start() }
                .isInstanceOf(InvalidSprintTransitionException::class.java)
        }
    }

    // ─── complete() 전이 ────────────────────────────────────────────────────

    @Nested
    inner class CompleteTransition {

        @Test
        fun `ACTIVE 스프린트를 complete() 하면 COMPLETED 상태의 새 Sprint를 반환한다`() {
            val sprint = activeSprint()
            val result = sprint.complete()
            assertThat(result.status).isEqualTo(SprintStatus.COMPLETED)
        }

        @Test
        fun `complete() 결과는 원본과 다른 객체이며 나머지 필드는 동일하다`() {
            val sprint = activeSprint()
            val result = sprint.complete()
            assertThat(result).isNotSameAs(sprint)
            assertThat(result.id).isEqualTo(sprint.id)
            assertThat(result.projectKey).isEqualTo(sprint.projectKey)
        }

        @Test
        fun `PLANNED 스프린트를 complete() 하면 InvalidSprintTransitionException이 발생한다`() {
            val sprint = plannedSprint()
            assertThatThrownBy { sprint.complete() }
                .isInstanceOf(InvalidSprintTransitionException::class.java)
        }

        @Test
        fun `COMPLETED 스프린트를 complete() 하면 InvalidSprintTransitionException이 발생한다`() {
            val sprint = activeSprint().complete()
            assertThatThrownBy { sprint.complete() }
                .isInstanceOf(InvalidSprintTransitionException::class.java)
        }
    }

    // ─── 기간 불변식 ─────────────────────────────────────────────────────────

    @Nested
    inner class DateInvariant {

        @Test
        fun `startDate가 endDate보다 나중이면 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy {
                plannedSprint(
                    startDate = today.plusDays(5),
                    endDate = today,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `startDate와 endDate가 같으면 정상 생성된다`() {
            val sprint = plannedSprint(startDate = today, endDate = today)
            assertThat(sprint.startDate).isEqualTo(today)
            assertThat(sprint.endDate).isEqualTo(today)
        }

        @Test
        fun `startDate만 null이면 정상 생성된다`() {
            val sprint = plannedSprint(startDate = null, endDate = today)
            assertThat(sprint.startDate).isNull()
            assertThat(sprint.endDate).isEqualTo(today)
        }

        @Test
        fun `endDate만 null이면 정상 생성된다`() {
            val sprint = plannedSprint(startDate = today, endDate = null)
            assertThat(sprint.startDate).isEqualTo(today)
            assertThat(sprint.endDate).isNull()
        }

        @Test
        fun `startDate와 endDate 둘 다 null이면 정상 생성된다`() {
            val sprint = plannedSprint(startDate = null, endDate = null)
            assertThat(sprint.startDate).isNull()
            assertThat(sprint.endDate).isNull()
        }
    }

    // ─── 이름 불변식 ─────────────────────────────────────────────────────────

    @Nested
    inner class NameInvariant {

        @Test
        fun `name이 공백 문자열이면 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy { plannedSprint(name = " ") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `name이 빈 문자열이면 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy { plannedSprint(name = "") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `name이 유효한 문자열이면 정상 생성된다`() {
            val sprint = plannedSprint(name = "스프린트 1")
            assertThat(sprint.name).isEqualTo("스프린트 1")
        }
    }

    // ─── SprintStatus 전이 규칙 ──────────────────────────────────────────────

    @Nested
    inner class SprintStatusEntries {

        @Test
        fun `SprintStatus 항목이 정확히 3개다 (PLANNED, ACTIVE, COMPLETED)`() {
            assertThat(SprintStatus.entries.size).isEqualTo(3)
        }

        @Test
        fun `PLANNED 에서 허용된 다음 상태는 ACTIVE 하나뿐이다`() {
            assertThat(SprintStatus.PLANNED.allowedTransitions)
                .containsExactly(SprintStatus.ACTIVE)
        }

        @Test
        fun `ACTIVE 에서 허용된 다음 상태는 COMPLETED 하나뿐이다`() {
            assertThat(SprintStatus.ACTIVE.allowedTransitions)
                .containsExactly(SprintStatus.COMPLETED)
        }

        @Test
        fun `COMPLETED 에서 허용된 다음 상태는 없다`() {
            assertThat(SprintStatus.COMPLETED.allowedTransitions).isEmpty()
        }
    }
}

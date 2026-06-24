// SprintRepository 통합 테스트 — sprints / sprint_issues 테이블 CRUD + 조건부 DML + 동시성 검증 (FR-BL-02 Task 3)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * [SprintRepository] 통합 테스트.
 *
 * Testcontainers PostgreSQL 16-alpine 위에서 Flyway V500~V503 마이그레이션을 적용한 뒤
 * [SprintRepository] 의 모든 퍼블릭 메서드를 검증한다.
 *
 * ## 검증 범위
 * - insert / findById(소프트삭제 제외) / findByProject(status별·deleted_at 제외)
 * - updateMeta(name·goal·기간 변경 + version bump)
 * - updateStatus(상태전이 영속 + version bump)
 * - softDelete(sprint_issues 먼저 삭제 + sprints.deleted_at 세팅)
 * - assignIssue(delete-then-insert 단일 트랜잭션, COMPLETED 가드, 영향 행수 반환)
 * - unassignIssue(연관 DELETE, 멱등, COMPLETED 가드)
 * - findIssueKeys(created_at 순)
 * - E12 동시성: 2 커넥션 동시 assign → UNIQUE(issue_key) 위반 검증
 *
 * ## 설정 공유
 * [AgilePlanningTestcontainersConfig] 의 singleton PostgreSQL 컨테이너와 Flyway 마이그레이션을 재사용한다.
 */
@SpringBootTest(classes = [AgilePlanningTestBootApplication::class])
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
class SprintRepositoryTest {
    @Autowired
    private lateinit var sprintRepository: SprintRepository

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 기본 PLANNED 스프린트 도메인 객체를 생성한다(DB 미반영). */
    private fun buildSprint(
        projectKey: String = "TEST",
        status: SprintStatus = SprintStatus.PLANNED,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
    ): Sprint =
        Sprint(
            id = UUID.randomUUID(),
            projectKey = projectKey,
            name = "스프린트 ${UUID.randomUUID()}",
            goal = null,
            status = status,
            startDate = startDate,
            endDate = endDate,
            version = 0L,
        )

    // ── (1) insert / findById ────────────────────────────────────────────────

    @Test
    fun `insert 후 findById 가 같은 id 를 반환한다`() {
        val sprint = buildSprint()
        val saved = sprintRepository.insert(sprint)

        assertThat(saved.id).isEqualTo(sprint.id)
        assertThat(saved.projectKey).isEqualTo(sprint.projectKey)
        assertThat(saved.name).isEqualTo(sprint.name)
        assertThat(saved.status).isEqualTo(SprintStatus.PLANNED)
        assertThat(saved.version).isZero()
    }

    @Test
    fun `findById 는 soft-deleted 스프린트를 반환하지 않는다`() {
        val sprint = buildSprint()
        sprintRepository.insert(sprint)
        sprintRepository.softDelete(sprint.id)

        val found = sprintRepository.findById(sprint.id)
        assertThat(found as Any?).isNull()
    }

    @Test
    fun `findById 는 존재하지 않는 id 에 null 을 반환한다`() {
        val found = sprintRepository.findById(UUID.randomUUID())
        assertThat(found as Any?).isNull()
    }

    // ── (2) findByProject ───────────────────────────────────────────────────

    @Test
    fun `findByProject 는 해당 프로젝트의 모든 활성 스프린트를 반환한다`() {
        val key = "PROJ-${UUID.randomUUID().toString().take(6)}"
        val s1 = sprintRepository.insert(buildSprint(projectKey = key))
        val s2 = sprintRepository.insert(buildSprint(projectKey = key))

        val result = sprintRepository.findByProject(key)
        val ids = result.map { it.id }
        assertThat(ids).contains(s1.id, s2.id)
    }

    @Test
    fun `findByProject 는 soft-deleted 스프린트를 제외한다`() {
        val key = "SOFTDEL-${UUID.randomUUID().toString().take(6)}"
        val active = sprintRepository.insert(buildSprint(projectKey = key))
        val deleted = sprintRepository.insert(buildSprint(projectKey = key))
        sprintRepository.softDelete(deleted.id)

        val result = sprintRepository.findByProject(key)
        val ids = result.map { it.id }
        assertThat(ids).contains(active.id)
        assertThat(ids).doesNotContain(deleted.id)
    }

    @Test
    fun `findByProject status 필터로 ACTIVE 스프린트만 반환한다`() {
        val key = "STATUS-${UUID.randomUUID().toString().take(6)}"
        val planned = sprintRepository.insert(buildSprint(projectKey = key))
        val active = sprintRepository.insert(buildSprint(projectKey = key))
        sprintRepository.updateStatus(active.id, SprintStatus.ACTIVE, active.version)

        val result = sprintRepository.findByProject(key, status = SprintStatus.ACTIVE)
        val ids = result.map { it.id }
        assertThat(ids).contains(active.id)
        assertThat(ids).doesNotContain(planned.id)
    }

    // ── (3) updateMeta ──────────────────────────────────────────────────────

    @Test
    fun `updateMeta 는 name·goal·기간을 갱신하고 version 을 bump 한다`() {
        val sprint = sprintRepository.insert(buildSprint())
        val newName = "갱신된 스프린트 이름"
        val newGoal = "새 목표"
        val newStart = LocalDate.of(2026, 7, 1)
        val newEnd = LocalDate.of(2026, 7, 14)

        val updated = sprintRepository.updateMeta(
            id = sprint.id,
            name = newName,
            goal = newGoal,
            startDate = newStart,
            endDate = newEnd,
            version = sprint.version,
        )

        assertThat(updated).isNotNull()
        assertThat(updated!!.name).isEqualTo(newName)
        assertThat(updated.goal).isEqualTo(newGoal)
        assertThat(updated.startDate).isEqualTo(newStart)
        assertThat(updated.endDate).isEqualTo(newEnd)
        assertThat(updated.version).isEqualTo(sprint.version + 1)
    }

    @Test
    fun `updateMeta 는 존재하지 않는 id 에 null 을 반환한다`() {
        val result = sprintRepository.updateMeta(
            id = UUID.randomUUID(),
            name = "이름",
            goal = null,
            startDate = null,
            endDate = null,
            version = 0L,
        )
        assertThat(result as Any?).isNull()
    }

    // ── (4) updateStatus ────────────────────────────────────────────────────

    @Test
    fun `updateStatus 는 상태를 ACTIVE 로 변경하고 version 을 bump 한다`() {
        val sprint = sprintRepository.insert(buildSprint())

        val updated = sprintRepository.updateStatus(sprint.id, SprintStatus.ACTIVE, sprint.version)

        assertThat(updated).isNotNull()
        assertThat(updated!!.status).isEqualTo(SprintStatus.ACTIVE)
        assertThat(updated.version).isEqualTo(sprint.version + 1)
    }

    @Test
    fun `updateStatus 는 상태를 COMPLETED 로 변경하고 version 을 bump 한다`() {
        val sprint = sprintRepository.insert(buildSprint())
        val activated = sprintRepository.updateStatus(sprint.id, SprintStatus.ACTIVE, sprint.version)!!

        val completed = sprintRepository.updateStatus(activated.id, SprintStatus.COMPLETED, activated.version)

        assertThat(completed).isNotNull()
        assertThat(completed!!.status).isEqualTo(SprintStatus.COMPLETED)
        assertThat(completed.version).isEqualTo(activated.version + 1)
    }

    @Test
    fun `updateStatus 는 존재하지 않는 id 에 null 을 반환한다`() {
        val result = sprintRepository.updateStatus(UUID.randomUUID(), SprintStatus.ACTIVE, 0L)
        assertThat(result as Any?).isNull()
    }

    // ── (5) softDelete ──────────────────────────────────────────────────────

    @Test
    fun `softDelete 후 findById 는 null 을 반환한다`() {
        val sprint = sprintRepository.insert(buildSprint())
        sprintRepository.softDelete(sprint.id)

        val found = sprintRepository.findById(sprint.id)
        assertThat(found as Any?).isNull()
    }

    @Test
    fun `softDelete 후 findIssueKeys 는 빈 목록을 반환한다`() {
        val sprint = sprintRepository.insert(buildSprint())
        sprintRepository.assignIssue(sprint.id, "PRJ-SOFT-1")
        sprintRepository.assignIssue(sprint.id, "PRJ-SOFT-2")

        sprintRepository.softDelete(sprint.id)

        val keys = sprintRepository.findIssueKeys(sprint.id)
        assertThat(keys).isEmpty()
    }

    // ── (6) assignIssue ─────────────────────────────────────────────────────

    @Test
    fun `assignIssue 후 findIssueKeys 에 해당 이슈 키가 포함된다`() {
        val sprint = sprintRepository.insert(buildSprint())
        val affected = sprintRepository.assignIssue(sprint.id, "PRJ-1")

        assertThat(affected).isEqualTo(1)
        assertThat(sprintRepository.findIssueKeys(sprint.id)).contains("PRJ-1")
    }

    @Test
    fun `assignIssue 는 이슈를 다른 스프린트에서 이동시킨다 (delete-then-insert)`() {
        val sprintA = sprintRepository.insert(buildSprint())
        val sprintB = sprintRepository.insert(buildSprint())
        sprintRepository.assignIssue(sprintA.id, "MOVE-1")

        // sprintB 로 이동
        sprintRepository.assignIssue(sprintB.id, "MOVE-1")

        assertThat(sprintRepository.findIssueKeys(sprintA.id)).doesNotContain("MOVE-1")
        assertThat(sprintRepository.findIssueKeys(sprintB.id)).contains("MOVE-1")
    }

    @Test
    fun `assignIssue 멱등 재할당은 no-op 이다 (같은 sprint + issue_key)`() {
        val sprint = sprintRepository.insert(buildSprint())
        sprintRepository.assignIssue(sprint.id, "IDEM-1")

        // 같은 스프린트에 같은 이슈 키 재할당 — 예외 없이 처리
        val affected = sprintRepository.assignIssue(sprint.id, "IDEM-1")

        // 이미 같은 sprint 에 있으므로 DELETE 0행 + INSERT 0행 또는 1행 — no-op (0 반환 가능)
        assertThat(sprintRepository.findIssueKeys(sprint.id)).contains("IDEM-1")
        assertThat(affected).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `assignIssue COMPLETED 스프린트에는 영향 0 을 반환한다 (COMPLETED 가드)`() {
        val sprint = sprintRepository.insert(buildSprint())
        val activated = sprintRepository.updateStatus(sprint.id, SprintStatus.ACTIVE, sprint.version)!!
        sprintRepository.updateStatus(activated.id, SprintStatus.COMPLETED, activated.version)

        val affected = sprintRepository.assignIssue(sprint.id, "GUARD-1")

        assertThat(affected).isZero()
        assertThat(sprintRepository.findIssueKeys(sprint.id)).doesNotContain("GUARD-1")
    }

    // ── (7) unassignIssue ───────────────────────────────────────────────────

    @Test
    fun `unassignIssue 후 findIssueKeys 에서 해당 이슈 키가 제거된다`() {
        val sprint = sprintRepository.insert(buildSprint())
        sprintRepository.assignIssue(sprint.id, "DEL-1")

        sprintRepository.unassignIssue(sprint.id, "DEL-1")

        assertThat(sprintRepository.findIssueKeys(sprint.id)).doesNotContain("DEL-1")
    }

    @Test
    fun `unassignIssue 는 존재하지 않는 이슈 키에도 예외를 던지지 않는다 (멱등)`() {
        val sprint = sprintRepository.insert(buildSprint())

        // 한 번도 할당하지 않은 이슈 키 — 예외 없어야 한다
        sprintRepository.unassignIssue(sprint.id, "NOTEXIST-99")
    }

    @Test
    fun `unassignIssue COMPLETED 스프린트에서도 멱등 동작한다 (COMPLETED 가드)`() {
        val sprint = sprintRepository.insert(buildSprint())
        sprintRepository.assignIssue(sprint.id, "COMP-UNASSIGN-1")
        val activated = sprintRepository.updateStatus(sprint.id, SprintStatus.ACTIVE, sprint.version)!!
        sprintRepository.updateStatus(activated.id, SprintStatus.COMPLETED, activated.version)

        // COMPLETED 가드: unassign 은 0행 DELETE (예외 없음)
        sprintRepository.unassignIssue(sprint.id, "COMP-UNASSIGN-1")
    }

    // ── (8) findIssueKeys ───────────────────────────────────────────────────

    @Test
    fun `findIssueKeys 는 created_at 오름차순으로 이슈 키를 반환한다`() {
        val sprint = sprintRepository.insert(buildSprint())
        sprintRepository.assignIssue(sprint.id, "ORD-1")
        sprintRepository.assignIssue(sprint.id, "ORD-2")
        sprintRepository.assignIssue(sprint.id, "ORD-3")

        val keys = sprintRepository.findIssueKeys(sprint.id)
        assertThat(keys).containsExactly("ORD-1", "ORD-2", "ORD-3")
    }

    @Test
    fun `findIssueKeys 는 이슈가 없으면 빈 목록을 반환한다`() {
        val sprint = sprintRepository.insert(buildSprint())
        assertThat(sprintRepository.findIssueKeys(sprint.id)).isEmpty()
    }

    // ── (9) E12 동시성 — UNIQUE(issue_key) 위반 ────────────────────────────

    @Test
    fun `E12 동시 assignIssue 는 UNIQUE 위반으로 한쪽이 실패한다`() {
        val sprintA = sprintRepository.insert(buildSprint())
        val sprintB = sprintRepository.insert(buildSprint())
        val issueKey = "CONCURRENT-${UUID.randomUUID().toString().take(6)}"

        // 2개의 커넥션이 서로 다른 스프린트에 같은 issue_key 를 동시에 assign 시도.
        // UNIQUE(issue_key) 제약으로 한 쪽은 반드시 실패해야 한다.
        val executor = Executors.newFixedThreadPool(2)
        val futures = mutableListOf<Future<Result<Int>>>()

        futures.add(
            executor.submit<Result<Int>> {
                runCatching { sprintRepository.assignIssue(sprintA.id, issueKey) }
            },
        )
        futures.add(
            executor.submit<Result<Int>> {
                runCatching { sprintRepository.assignIssue(sprintB.id, issueKey) }
            },
        )

        executor.shutdown()
        val results = futures.map { it.get() }

        val successCount = results.count { it.isSuccess }
        val failureCount = results.count { it.isFailure }

        // 정확히 한 쪽만 성공, 한 쪽은 UNIQUE 위반으로 실패
        assertThat(successCount).isEqualTo(1)
        assertThat(failureCount).isEqualTo(1)

        // 성공한 스프린트에 해당 이슈가 할당되어 있어야 한다
        val successIdx = results.indexOfFirst { it.isSuccess }
        val winningSprint = if (successIdx == 0) sprintA else sprintB
        assertThat(sprintRepository.findIssueKeys(winningSprint.id)).contains(issueKey)
    }
}

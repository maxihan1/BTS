// SprintRepository 통합 테스트 — sprints / sprint_issues 테이블 CRUD + 조건부 DML + 동시성 검증 (FR-BL-02 Task 3)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
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
 * - updateStatus(상태전환 영속 + version bump)
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

    @Autowired
    private lateinit var boardRepository: BoardRepository

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 프로젝트별 FK 대상 보드 캐시. V506 이후 `sprints.board_id` 는 NOT NULL + FK 라 실제 행이 필요하다. */
    private val boardIdCache = mutableMapOf<String, UUID>()

    /**
     * 그 프로젝트의 보드를 만들어 id 를 돌려준다(같은 테스트 안에서는 재사용).
     *
     * 스프린트는 보드에 매달리므로 도메인 객체만으로는 INSERT 가 FK 위반으로 죽는다.
     */
    private fun boardIdFor(projectKey: String): UUID =
        boardIdCache.getOrPut(projectKey) {
            boardRepository
                .insert(
                    Board(
                        id = UUID.randomUUID(),
                        projectKey = projectKey,
                        name = "$projectKey 스크럼 보드",
                        columns = emptyList(),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                        boardType = BoardType.SCRUM,
                    ),
                ).id
        }

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
            boardId = boardIdFor(projectKey),
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

        val updated =
            sprintRepository.updateMeta(
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
        val result =
            sprintRepository.updateMeta(
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

    // ── (9) E12 동시성 — 한 이슈는 한 스프린트에만 ──────────────────────────

    /**
     * ★2026-07-28 — 단언을 「한쪽이 예외를 던진다」에서 **「행이 정확히 1개 남는다」**로 바꿨다.
     *
     * ## 왜 옛 단언이 틀렸나
     * [SprintRepository.assignIssue] 는 **삭제 후 삽입**(이동 의미)이다 —
     * 먼저 그 `issue_key` 를 전역에서 지우고 새 스프린트에 넣는다.
     * 그래서 두 스레드의 인터리빙에 따라 **둘 다 성공할 수 있다.**
     *
     * | 인터리빙 | 결과 |
     * |---|---|
     * | A삭제 · B삭제 · A삽입 · B삽입 | B 가 UNIQUE 위반 → 1 성공 1 실패 |
     * | A삭제 · A삽입 · B삭제 · B삽입 | **둘 다 성공** (B 의 삭제가 A 의 행을 치운다) |
     *
     * 옛 단언은 두 번째 인터리빙에서 `expected: 1 but was: 2` 로 깨졌다.
     * 전체 스위트 부하에서만 재현되고 단독 6/6 은 통과해 오래 숨어 있었다
     * (main 에도 같은 단언이 있는 **선재 결함**이다).
     *
     * ## 무엇이 진짜 불변식인가
     * 프로덕션이 지켜야 하는 것은 「예외가 난다」가 아니라
     * **「한 이슈는 어느 시점에도 한 스프린트에만 속한다」** 이다.
     * 위 두 인터리빙 **모두**에서 최종 행은 정확히 1개다.
     *
     * 구현 세부(UNIQUE 위반이 표면화되는지)를 단언하면, 구현이 `ON CONFLICT` 로
     * 바뀌기만 해도 의미 없이 깨진다. 불변식을 단언하면 그렇지 않다.
     *
     * ## 두 단언이 각각 무엇을 잡는지 (과장하지 않기 위해 명시)
     * - **「최소 한쪽 성공」** — 동시 호출에서 **둘 다 실패**하는 것을 잡는다.
     *   과도한 잠금이나 교착으로 이슈가 어디에도 안 붙는 회귀가 여기 걸린다.
     * - **「보유 스프린트 정확히 1개」** — `V503__sprints.sql:46`
     *   `sprint_issues_issue_key_unique UNIQUE (issue_key)` 의 **거울**이다.
     *   제약이 살아 있는 한 「A삭제·B삭제·A삽입·B삽입」 인터리빙에서 B 가 튕겨 1개가 되지만,
     *   **제약이 사라지면 그 인터리빙이 2개를 만든다** — 그때 이 단언이 유일한 탐지자다.
     *
     * ⚠️ 반대로, `assignIssue` 의 **전역 삭제를 지우는** 뮤테이션은 이 테스트가 **못 잡는다**
     * (그 경우에도 보유 스프린트는 1개다). 그 축은 형제 두 테스트
     * (`멱등 재할당은 no-op` · `다른 스프린트에서 이동시킨다`)가 덮는다 — 실측 확인함.
     */
    @Test
    fun `E12 동시 assignIssue 후에도 이슈는 한 스프린트에만 속한다`() {
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

        // 최소 한쪽은 성공해야 한다 — 둘 다 실패하면 이슈가 어디에도 안 붙는다.
        assertThat(results.count { it.isSuccess })
            .`as`("둘 다 실패했다 — 동시 요청에서 이슈가 어느 스프린트에도 배정되지 않았다")
            .isGreaterThanOrEqualTo(1)

        // ★핵심 불변식 — 이 issue_key 를 가진 스프린트는 **정확히 하나**다.
        val holders = listOf(sprintA, sprintB).filter { issueKey in sprintRepository.findIssueKeys(it.id) }
        assertThat(holders)
            .`as`(
                "동시 assignIssue 후 이슈를 가진 스프린트가 %d 개다 — 한 이슈는 한 스프린트에만 속해야 한다",
                holders.size,
            )
            .hasSize(1)
    }
}

// SprintApplicationService MockK 단위 테스트 — 권한 판정 + 도메인 전환 + 이슈 할당/해제 RED 명세 (FR-BL-02 Task 4)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.InvalidSprintTransitionException
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.openapitools.jackson.nullable.JsonNullable
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * SprintApplicationService 단위 테스트.
 *
 * cross-BC 포트(IssuePermissionResolver, BoardIssueLookupPort)와 SprintRepository 를
 * MockK 로 교체해 독립적으로 동작을 검증한다.
 * 권한 판정 순서(actor -> sprint 조회 -> 권한 -> 동작)와 fail-closed 를 집중 검증한다.
 */
@Suppress("LargeClass") // 서비스 전 시나리오(권한·전환·할당·partial PATCH)를 단일 테스트 클래스로 커버한다
class SprintApplicationServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val sprintId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"
    private val boardId: UUID = UUID.randomUUID()

    private val plannedSprint =
        Sprint(
            id = sprintId,
            projectKey = projectKey,
            boardId = boardId,
            name = "Sprint 1",
            goal = null,
            status = SprintStatus.PLANNED,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 14),
            version = 0L,
        )

    private val activeSprint = plannedSprint.copy(status = SprintStatus.ACTIVE, version = 1L)
    private val completedSprint = plannedSprint.copy(status = SprintStatus.COMPLETED, version = 2L)

    /** 기본 allow-all resolver — 원하는 곳에서만 false 세팅 */
    private fun allowAllResolver(): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
        }

    /** 지정 권한에 대해 false 반환, 나머지 true */
    private fun denyResolver(
        actor: UUID = actorId,
        permission: IssuePermission,
        scope: IssueScope,
    ): IssuePermissionResolver =
        mockk<IssuePermissionResolver>().also {
            every { it.hasPermission(any(), any(), any()) } returns true
            every { it.hasPermission(actor, permission, scope) } returns false
        }

    /**
     * 활성 보드 fixture — 소속 검증에 쓰는 것은 id · projectKey · boardType 셋이다.
     *
     * ★기본값이 **SCRUM** 인 것은 도메인 기본값([BoardType.KANBAN])과 **일부러 다르다.**
     *  스프린트가 붙을 수 있는 보드는 스크럼뿐이므로(ADR 편차 X4 — 칸반은 백로그 없이 간다),
     *  「정상 경로」 fixture 는 스크럼이어야 한다. 도메인 기본값을 그대로 쓰면 이 파일의 create·start
     *  테스트 전량이 「칸반 보드에 스프린트를 매다는」 도달 불가 조합을 고정하게 된다
     *  (memory `unreachable-state-fixture-is-fake-green`).
     */
    private fun activeBoard(
        id: UUID,
        project: String = projectKey,
        boardType: BoardType = BoardType.SCRUM,
    ): Board =
        Board(
            id = id,
            projectKey = project,
            name = "$project 보드",
            columns = emptyList(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            boardType = boardType,
        )

    /**
     * 보드 소속 검증용 stub repository.
     *
     * 등록한 보드만 활성으로 취급하고 나머지 UUID 는 null 을 돌려준다 —
     * 실제 [BoardRepository.findById] 가 `deleted_at IS NULL` 로 거르는 동작(미존재·소프트 삭제 모두 null)과 같다.
     */
    private fun boardRepoOf(vararg boards: Board): BoardRepository =
        mockk<BoardRepository>().also { repo ->
            every { repo.findById(any()) } returns null
            boards.forEach { board -> every { repo.findById(board.id) } returns board }
        }

    private fun makeService(
        resolver: IssuePermissionResolver = allowAllResolver(),
        repo: SprintRepository = mockk(relaxed = true),
        lookupPort: BoardIssueLookupPort = mockk(relaxed = true),
        boardService: BoardApplicationService =
            mockk(relaxed = true) {
                // 보드를 안 준 create 요청이 붙을 자리. 실제 해소는 BoardApplicationService 의 책임이다.
                every { ensureScrumBoard(any()) } returns boardId
            },
        boardRepository: BoardRepository = boardRepoOf(activeBoard(boardId)),
    ): SprintApplicationService =
        SprintApplicationService(resolver, repo, lookupPort, boardService, boardRepository)

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create CREATE 권한이 있으면 repo에 sprint를 삽입하고 반환한다`() {
        val repo = mockk<SprintRepository>()
        every { repo.insert(any()) } answers { firstArg() }

        val result =
            makeService(repo = repo).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = boardId,
                name = "Sprint 1",
                goal = null,
                startDate = null,
                endDate = null,
            )

        assertThat(result.projectKey).isEqualTo(projectKey)
        assertThat(result.name).isEqualTo("Sprint 1")
        assertThat(result.status).isEqualTo(SprintStatus.PLANNED)
        verify(exactly = 1) { repo.insert(any()) }
    }

    @Test
    fun `create CREATE 권한 없으면 403을 던지고 repo를 호출하지 않는다`() {
        val repo = mockk<SprintRepository>()
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = boardId,
                name = "Sprint X",
                goal = null,
                startDate = null,
                endDate = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)

        verify(exactly = 0) { repo.insert(any()) }
    }

    // ── create 의 하위 호환 경로 (FR-BD-04) ────────────────────────────────────
    //
    // ★ 2026-09-02 갱신 — 위 두 문장이 이제 사실이 아니다. PR ③ 이 `CreateSprintRequest.boardId` 를
    // 넣고 `SprintController` 를 결선해 **백로그 화면은 항상 boardId 를 명시**한다.
    // 그래도 이 테스트는 남는다 — `boardId` 가 **선택 필드**라 미지정 요청이 여전히 유효하고,
    // 그 경로가 `ensureScrumBoard` 폴백을 탄다. 이 분기를 지우면 하위 호환이 조용히 깨진다.
    //
    // 원래 사유(보존) — 위 create 테스트들이 전부 boardId 를 명시하는 바람에 이 분기가 한 번도
    // 실행되지 않았고, `ensureScrumBoard` 를 `error("x")` 로 바꿔도 전 스위트가 초록이었다(리뷰 지적 C1).

    @Test
    fun `create boardId 를 안 주면 그 프로젝트의 스크럼 보드에 붙는다`() {
        val repo = mockk<SprintRepository>()
        every { repo.insert(any()) } answers { firstArg() }
        val boardService =
            mockk<BoardApplicationService>(relaxed = true).also {
                every { it.ensureScrumBoard(projectKey) } returns boardId
            }

        val result =
            makeService(repo = repo, boardService = boardService).create(
                actorId = actorId,
                projectKey = projectKey,
                name = "Sprint 1",
                goal = null,
                startDate = null,
                endDate = null,
            )

        assertThat(result.boardId).isEqualTo(boardId)
        verify(exactly = 1) { boardService.ensureScrumBoard(projectKey) }
    }

    @Test
    fun `create 같은 프로젝트의 활성 보드를 주면 그 보드에 붙고 스크럼 보드를 찾지 않는다`() {
        val explicitBoardId = UUID.randomUUID()
        val repo = mockk<SprintRepository>()
        every { repo.insert(any()) } answers { firstArg() }
        val boardService = mockk<BoardApplicationService>(relaxed = true)

        val result =
            makeService(
                repo = repo,
                boardService = boardService,
                // ★ 폴백을 안 타는 조건은 「boardId 가 있다」가 아니라 「그 보드가 이 프로젝트의 활성 보드다」이다.
                //   전자로 두면 타 프로젝트 보드도 그대로 통과하는 동작을 이 테스트가 고정해 버린다(BLOCKER-1).
                boardRepository = boardRepoOf(activeBoard(explicitBoardId, projectKey)),
            ).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = explicitBoardId,
                name = "Sprint 1",
                goal = null,
                startDate = null,
                endDate = null,
            )

        assertThat(result.boardId).isEqualTo(explicitBoardId)
        // PR ③ 이 백로그에서 boardId 를 명시로 넘기기 시작하면 이 경로가 기본이 된다.
        verify(exactly = 0) { boardService.ensureScrumBoard(any()) }
    }

    // ── create 의 보드 소속 검증 (BLOCKER-1 · IDOR) ────────────────────────────
    //
    // `boardId` 는 HTTP 입력이다(`CreateSprintRequest.boardId`). 권한은 body 의 `projectKey` 로만
    // 판정되므로, 보드 소속을 검증하지 않으면 A 에 CREATE 만 가진 행위자가 B 의 보드 UUID 를 실어
    // **B 의 보드에 스프린트를 매달 수 있다**(B 의 보드 헤더 오염 · 카드 0건 · B 의 스프린트 시작 영구 차단).
    // `sprints` FK 는 `boards(id)` 만 걸려 있어 프로젝트 일치를 DB 가 막지 못한다.
    //
    // 상태 코드는 **404** 다 — 읽기 경로 `BacklogApplicationService.resolveBoardScope`(스펙 E8)와 같은 규약.
    // 403 은 「그 UUID 는 존재한다」를 흘린다(memory `permission-assert-before-existence-makes-403-lie`).

    @Test
    fun `create 다른 프로젝트의 보드 UUID 를 주면 404 를 던지고 삽입하지 않는다`() {
        val otherProjectBoardId = UUID.randomUUID()
        val repo = mockk<SprintRepository>()
        val boardService = mockk<BoardApplicationService>(relaxed = true)

        assertThatThrownBy {
            makeService(
                repo = repo,
                boardService = boardService,
                boardRepository = boardRepoOf(activeBoard(otherProjectBoardId, "OTHER")),
            ).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = otherProjectBoardId,
                name = "Sprint X",
                goal = null,
                startDate = null,
                endDate = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)

        verify(exactly = 0) { repo.insert(any()) }
        // 잘못된 지정을 기본 보드로 조용히 대체하지 않는다 — 읽기 경로 편차 E7 과 같은 규약.
        verify(exactly = 0) { boardService.ensureScrumBoard(any()) }
    }

    /**
     * 칸반 보드에는 스프린트를 매달 수 없다 (FR-BD-04 PR ⑤).
     *
     * 막지 않으면 `POST /api/v1/sprints {boardId: <칸반>}` 이 201 이고 `start` 도 200 인데,
     * `BoardApplicationService.getBoard` 는 `boardType == SCRUM` 일 때만 활성 스프린트를 조회하므로
     * **그 스프린트는 어느 보드 화면에도 영원히 안 나타난다** — 사용자에게는 「시작했는데 아무 일도
     * 안 일어남」이다. 오늘 이것을 가리는 것은 백로그 스위처가 스크럼만 노출하는 것
     * (`projects.$projectKey.backlog.tsx` 의 `scrumBoards`) **하나뿐**이고, 그 KDoc 이 이 실패
     * 양식을 그대로 적고 있다 — 프론트 필터가 유일한 방어선이라는 뜻이다.
     *
     * 상태 코드는 위 두 테스트와 같은 **404** 다. 403 이면 「그 UUID 는 존재한다」가 샌다.
     */
    @Test
    fun `create 칸반 보드 UUID 를 주면 404 를 던지고 삽입하지 않는다`() {
        val kanbanBoardId = UUID.randomUUID()
        val repo = mockk<SprintRepository>()
        val boardService = mockk<BoardApplicationService>(relaxed = true)

        assertThatThrownBy {
            makeService(
                repo = repo,
                boardService = boardService,
                boardRepository =
                    boardRepoOf(activeBoard(kanbanBoardId, boardType = BoardType.KANBAN)),
            ).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = kanbanBoardId,
                name = "Sprint X",
                goal = null,
                startDate = null,
                endDate = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)

        verify(exactly = 0) { repo.insert(any()) }
        // 잘못된 지정을 스크럼 보드로 조용히 대체하지 않는다 — 편차 E7 과 같은 규약.
        verify(exactly = 0) { boardService.ensureScrumBoard(any()) }
    }

    @Test
    fun `create 소프트 삭제된 보드 UUID 를 주면 404 를 던지고 삽입하지 않는다`() {
        val deletedBoardId = UUID.randomUUID()
        val repo = mockk<SprintRepository>()
        val boardService = mockk<BoardApplicationService>(relaxed = true)

        assertThatThrownBy {
            makeService(
                repo = repo,
                boardService = boardService,
                // BoardRepository.findById 는 deleted_at IS NULL 로 거르므로 소프트 삭제 보드는 null 이다.
                boardRepository = boardRepoOf(),
            ).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = deletedBoardId,
                name = "Sprint X",
                goal = null,
                startDate = null,
                endDate = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)

        verify(exactly = 0) { repo.insert(any()) }
        verify(exactly = 0) { boardService.ensureScrumBoard(any()) }
    }

    // 위 두 테스트가 「권한이 있을 때」의 404 를 고정한다면, 이 테스트는 **검사 순서**를 고정한다.
    // 권한 판정이 보드 조회보다 **뒤로** 밀리면, 이 프로젝트에 아무 권한도 없는 행위자가 404(그 UUID 는
    // 이 프로젝트 보드가 아니다)와 403(권한만 없다)의 차이로 **어느 UUID 가 이 프로젝트의 보드인지
    // 탐침**할 수 있다. 권한이 먼저라 두 경우가 모두 403 으로 합쳐지는 것이 그 구분을 없앤다.
    // 바로 위 `create CREATE 권한 없으면 403…` 테스트는 **유효한 보드**를 주므로 순서를 판별하지 못한다 —
    // 순서를 뒤집어도 초록이다. 판별에는 「권한 없음 × 무효한 보드」 조합이 필요하다.
    @Test
    fun `create 권한이 없으면 보드가 무효해도 404 가 아니라 403 이다`() {
        val repo = mockk<SprintRepository>()
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(
                resolver = resolver,
                repo = repo,
                // 등록된 보드가 없으므로 findById 는 어떤 UUID 에도 null 이다 = 보드 조회는 404 감이다.
                boardRepository = boardRepoOf(),
            ).create(
                actorId = actorId,
                projectKey = projectKey,
                boardId = UUID.randomUUID(),
                name = "Sprint X",
                goal = null,
                startDate = null,
                endDate = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)

        verify(exactly = 0) { repo.insert(any()) }
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update sprint 조회로 projectKey를 확보한 뒤 CREATE 권한을 검증한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.updateMeta(any(), any(), any(), any(), any(), any()) } returns
                    plannedSprint.copy(name = "새 이름")
            }

        val result =
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("새 이름"),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 0L,
            )

        assertThat(result.name).isEqualTo("새 이름")
    }

    @Test
    fun `update sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("이름"),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 0L,
            )
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `update CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("이름"),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 0L,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `update repo가 null 반환하면 스프린트 존재 시 OCC 충돌 409를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                // findById 는 두 번 호출된다: loadSprintWithPermission + resolveOccNull 재조회
                every { it.findById(sprintId) } returns plannedSprint
                every { it.updateMeta(any(), any(), any(), any(), any(), any()) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("이름"),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 99L,
            )
        }.isInstanceOf(SprintVersionConflictException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(409)
    }

    @Test
    fun `update repo가 null 반환하고 스프린트도 없으면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                // 첫 번째 findById(loadSprintWithPermission): 존재
                // 두 번째 findById(resolveOccNull 재조회): 삭제됨
                every { it.findById(sprintId) } returnsMany listOf(plannedSprint, null)
                every { it.updateMeta(any(), any(), any(), any(), any(), any()) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("이름"),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 99L,
            )
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    // ── update partial 시맨틱 ─────────────────────────────────────────────────

    @Test
    fun `update name만 present면 goal과 dates는 기존 값을 유지한다 (핵심 partial 검증)`() {
        val existingStartDate = LocalDate.of(2026, 7, 1)
        val existingEndDate = LocalDate.of(2026, 7, 14)
        val existingGoal = "기존 목표"
        val existing =
            plannedSprint.copy(
                goal = existingGoal,
                startDate = existingStartDate,
                endDate = existingEndDate,
            )
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns existing
                every {
                    it.updateMeta(
                        id = sprintId,
                        name = "새 이름만",
                        goal = existingGoal,
                        startDate = existingStartDate,
                        endDate = existingEndDate,
                        version = 0L,
                    )
                } returns existing.copy(name = "새 이름만", version = 1L)
            }

        val result =
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("새 이름만"),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 0L,
            )

        assertThat(result.name).isEqualTo("새 이름만")
        // repo.updateMeta 는 기존 goal/dates 로 호출되어야 한다 (미전송 = 무변경)
        verify(exactly = 1) {
            repo.updateMeta(
                id = sprintId,
                name = "새 이름만",
                goal = existingGoal,
                startDate = existingStartDate,
                endDate = existingEndDate,
                version = 0L,
            )
        }
    }

    @Test
    fun `update goal이 present null이면 goal을 클리어한다`() {
        val existing = plannedSprint.copy(goal = "기존 목표")
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns existing
                every {
                    it.updateMeta(
                        id = sprintId,
                        name = existing.name,
                        goal = null,
                        startDate = existing.startDate,
                        endDate = existing.endDate,
                        version = 0L,
                    )
                } returns existing.copy(goal = null, version = 1L)
            }

        val result =
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.undefined(),
                goal = JsonNullable.of(null),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 0L,
            )

        // repo.updateMeta 가 null goal 로 호출됐는지 검증
        verify(exactly = 1) {
            repo.updateMeta(
                id = sprintId,
                name = existing.name,
                goal = null,
                startDate = existing.startDate,
                endDate = existing.endDate,
                version = 0L,
            )
        }
        assertThat(result.goal).isNull()
    }

    @Test
    fun `update 모든 필드가 absent면 기존 값을 그대로 유지해 repo에 전달한다`() {
        val existing =
            plannedSprint.copy(
                name = "기존 이름",
                goal = "기존 목표",
                startDate = LocalDate.of(2026, 7, 1),
                endDate = LocalDate.of(2026, 7, 14),
            )
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns existing
                every {
                    it.updateMeta(
                        id = sprintId,
                        name = "기존 이름",
                        goal = "기존 목표",
                        startDate = LocalDate.of(2026, 7, 1),
                        endDate = LocalDate.of(2026, 7, 14),
                        version = 0L,
                    )
                } returns existing.copy(version = 1L)
            }

        makeService(repo = repo).update(
            actorId = actorId,
            sprintId = sprintId,
            name = JsonNullable.undefined(),
            goal = JsonNullable.undefined(),
            startDate = JsonNullable.undefined(),
            endDate = JsonNullable.undefined(),
            version = 0L,
        )

        verify(exactly = 1) {
            repo.updateMeta(
                id = sprintId,
                name = "기존 이름",
                goal = "기존 목표",
                startDate = LocalDate.of(2026, 7, 1),
                endDate = LocalDate.of(2026, 7, 14),
                version = 0L,
            )
        }
    }

    @Test
    fun `update startDate가 present null이면 날짜를 해제한다`() {
        val existing = plannedSprint.copy(startDate = LocalDate.of(2026, 7, 1), endDate = null)
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns existing
                every {
                    it.updateMeta(
                        id = sprintId,
                        name = existing.name,
                        goal = existing.goal,
                        startDate = null,
                        endDate = null,
                        version = 0L,
                    )
                } returns existing.copy(startDate = null, version = 1L)
            }

        makeService(repo = repo).update(
            actorId = actorId,
            sprintId = sprintId,
            name = JsonNullable.undefined(),
            goal = JsonNullable.undefined(),
            startDate = JsonNullable.of(null),
            endDate = JsonNullable.undefined(),
            version = 0L,
        )

        verify(exactly = 1) {
            repo.updateMeta(
                id = sprintId,
                name = existing.name,
                goal = existing.goal,
                startDate = null,
                endDate = null,
                version = 0L,
            )
        }
    }

    // ── update 날짜 잠금 (COMPLETED · FR-2 · J1) ──────────────────────────────

    /**
     * COMPLETED 스프린트 조회 + updateMeta 성공 stub.
     *
     * 잠금이 없으면 전달된 날짜가 그대로 저장되는 상태를 재현한다 —
     * 잠금 판정이 사라지면 이 stub 때문에 저장이 성공해 테스트가 red 가 된다.
     */
    private fun completedSprintRepo(): SprintRepository =
        mockk<SprintRepository>().also {
            every { it.findById(sprintId) } returns completedSprint
            every { it.updateMeta(any(), any(), any(), any(), any(), any()) } answers {
                completedSprint.copy(startDate = arg(3), endDate = arg(4), version = 3L)
            }
        }

    @Test
    fun `update COMPLETED 스프린트에 endDate 를 실으면 400 을 던진다`() {
        val repo = completedSprintRepo()

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.undefined(),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.of(LocalDate.of(2026, 8, 31)),
                version = 2L,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)
    }

    @Test
    fun `update COMPLETED 스프린트에 실은 endDate 는 저장되지 않는다`() {
        val repo = completedSprintRepo()

        catchThrowable {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.undefined(),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.of(LocalDate.of(2026, 8, 31)),
                version = 2L,
            )
        }

        // 거부된 요청은 어떤 값도 영속되지 않아야 한다 (응답 코드와 별개의 축).
        verify(exactly = 0) { repo.updateMeta(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update COMPLETED 스프린트에 startDate 를 present null 로 보내도 400 이고 저장되지 않는다`() {
        val repo = completedSprintRepo()

        assertThatThrownBy {
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.undefined(),
                goal = JsonNullable.undefined(),
                startDate = JsonNullable.of(null),
                endDate = JsonNullable.undefined(),
                version = 2L,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)
        verify(exactly = 0) { repo.updateMeta(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update COMPLETED 스프린트도 날짜가 absent 면 이름과 목표는 저장된다`() {
        val repo = completedSprintRepo()

        val result =
            makeService(repo = repo).update(
                actorId = actorId,
                sprintId = sprintId,
                name = JsonNullable.of("회고 반영 이름"),
                goal = JsonNullable.of("회고 반영 목표"),
                startDate = JsonNullable.undefined(),
                endDate = JsonNullable.undefined(),
                version = 2L,
            )

        assertThat(result.version).isEqualTo(3L)
        verify(exactly = 1) {
            repo.updateMeta(
                id = sprintId,
                name = "회고 반영 이름",
                goal = "회고 반영 목표",
                startDate = completedSprint.startDate,
                endDate = completedSprint.endDate,
                version = 2L,
            )
        }
    }

    @Test
    fun `update ACTIVE 스프린트의 endDate 는 잠기지 않고 저장된다`() {
        val newEndDate = LocalDate.of(2026, 7, 21)
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
                every { it.updateMeta(any(), any(), any(), any(), any(), any()) } returns
                    activeSprint.copy(endDate = newEndDate, version = 2L)
            }

        makeService(repo = repo).update(
            actorId = actorId,
            sprintId = sprintId,
            name = JsonNullable.undefined(),
            goal = JsonNullable.undefined(),
            startDate = JsonNullable.undefined(),
            endDate = JsonNullable.of(newEndDate),
            version = 1L,
        )

        verify(exactly = 1) {
            repo.updateMeta(
                id = sprintId,
                name = activeSprint.name,
                goal = activeSprint.goal,
                startDate = activeSprint.startDate,
                endDate = newEndDate,
                version = 1L,
            )
        }
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    fun `softDelete sprint 조회 후 CREATE 권한 확인하고 repo softDelete 호출한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.softDelete(sprintId) } returns Unit
            }

        makeService(repo = repo).softDelete(actorId, sprintId)

        verify(exactly = 1) { repo.softDelete(sprintId) }
    }

    @Test
    fun `softDelete sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).softDelete(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `softDelete CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).softDelete(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list BROWSE 권한이 있으면 프로젝트의 스프린트 목록을 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, null) } returns listOf(plannedSprint, activeSprint)
            }

        val result = makeService(repo = repo).list(actorId, projectKey, statusFilter = null)

        assertThat(result).hasSize(2)
    }

    @Test
    fun `list BROWSE 권한 없으면 403을 던진다`() {
        val resolver = denyResolver(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver).list(actorId, projectKey, statusFilter = null)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `list statusFilter가 있으면 해당 상태만 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findByProject(projectKey, SprintStatus.ACTIVE) } returns listOf(activeSprint)
            }

        val result = makeService(repo = repo).list(actorId, projectKey, SprintStatus.ACTIVE)

        assertThat(result).hasSize(1)
        assertThat(result.first().status).isEqualTo(SprintStatus.ACTIVE)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun `get BROWSE 권한이 있으면 sprint 단건을 반환한다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }

        val result = makeService(repo = repo).get(actorId, sprintId)

        assertThat(result.id).isEqualTo(sprintId)
    }

    @Test
    fun `get sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).get(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `get BROWSE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).get(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    // ── start ─────────────────────────────────────────────────────────────────

    @Test
    fun `start PLANNED 스프린트는 ACTIVE로 전환되고 영속된다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                // FR-BD-04 보드당 활성 1개 가드 — 이 보드에 활성이 없는 정상 경로다.
                every { it.findActiveByBoard(boardId) } returns null
                every { it.updateStatus(sprintId, SprintStatus.ACTIVE, 0L) } returns activeSprint
            }

        val result = makeService(repo = repo).start(actorId, sprintId)

        assertThat(result.status).isEqualTo(SprintStatus.ACTIVE)
        verify(exactly = 1) { repo.updateStatus(sprintId, SprintStatus.ACTIVE, 0L) }
    }

    @Test
    fun `start sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `start CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).start(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `start ACTIVE 스프린트 start 시도는 InvalidSprintTransitionException을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)
    }

    @Test
    fun `start COMPLETED 스프린트 start 시도는 InvalidSprintTransitionException을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns completedSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)
    }

    // ── start 활성 1개 가드 (FR-BD-04 D4) ────────────────────────────────────
    //
    // 선행 결정 무효화. agile-planning.md §3.2 Deviation(PR #182) ⑤ 「동시 ACTIVE 다중 허용」을
    // 뒤집는다. Jira Cloud 기본이 보드당 1개다. 기존 다중 활성 행은 깨지 않는다 — 가드는 start 시점만.

    @Test
    fun `start 같은 보드에 이미 ACTIVE 스프린트가 있으면 409를 던지고 상태를 바꾸지 않는다`() {
        val otherActive = plannedSprint.copy(id = UUID.randomUUID(), status = SprintStatus.ACTIVE)
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.findActiveByBoard(boardId) } returns otherActive
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(409)

        verify(exactly = 0) { repo.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `start 는 그 스프린트가 속한 보드로만 활성 여부를 묻는다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.findActiveByBoard(boardId) } returns null
                every { it.updateStatus(sprintId, SprintStatus.ACTIVE, 0L) } returns activeSprint
            }

        makeService(repo = repo).start(actorId, sprintId)

        // 프로젝트 전역이 아니라 보드 스코프다 — 다른 보드의 활성 스프린트는 막지 않는다.
        verify(exactly = 1) { repo.findActiveByBoard(boardId) }
    }

    @Test
    fun `start FSM 위반은 보드 가드보다 먼저 판정된다`() {
        // ACTIVE 스프린트를 다시 start 하면 「이미 활성이 있다」가 아니라 「전환 불가」여야 한다.
        // 가드를 sprint.start() 앞에 두면 이 구분이 사라진다.
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).start(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)

        verify(exactly = 0) { repo.findActiveByBoard(any()) }
    }

    // ── complete ──────────────────────────────────────────────────────────────

    @Test
    fun `complete ACTIVE 스프린트는 COMPLETED로 전환되고 영속된다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
                every { it.updateStatus(sprintId, SprintStatus.COMPLETED, 1L) } returns completedSprint
            }

        val result = makeService(repo = repo).complete(actorId, sprintId)

        assertThat(result.status).isEqualTo(SprintStatus.COMPLETED)
        verify(exactly = 1) { repo.updateStatus(sprintId, SprintStatus.COMPLETED, 1L) }
    }

    @Test
    fun `complete sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).complete(actorId, sprintId)
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `complete CREATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns activeSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).complete(actorId, sprintId)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `complete PLANNED 스프린트 complete 시도는 InvalidSprintTransitionException을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }

        assertThatThrownBy {
            makeService(repo = repo).complete(actorId, sprintId)
        }.isInstanceOf(InvalidSprintTransitionException::class.java)
    }

    // ── assignIssue ───────────────────────────────────────────────────────────

    @Test
    fun `assignIssue UPDATE 권한이 있고 이슈가 가시적이면 할당에 성공한다`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.assignIssue(sprintId, issueKey) } returns 1
            }
        val lookupPort =
            mockk<BoardIssueLookupPort>().also {
                every { it.isVisibleIssue(projectKey, issueKey, actorId) } returns true
            }

        makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)

        verify(exactly = 1) { repo.assignIssue(sprintId, issueKey) }
    }

    @Test
    fun `assignIssue sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).assignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `assignIssue UPDATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).assignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    @Test
    fun `assignIssue 이슈가 가시적이지 않으면 404를 던진다 (probe 차단)`() {
        val issueKey = "ATLAS-99"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val lookupPort =
            mockk<BoardIssueLookupPort>().also {
                every { it.isVisibleIssue(projectKey, issueKey, actorId) } returns false
            }

        assertThatThrownBy {
            makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
    }

    @Test
    fun `assignIssue isVisibleIssue가 false(포트 미등록 기본값)이면 할당을 거부한다 (fail-closed)`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        // default impl returns false (fail-closed)
        val lookupPort = object : BoardIssueLookupPort {}

        assertThatThrownBy {
            makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(404)
    }

    @Test
    fun `assignIssue COMPLETED 스프린트에 할당하면 409를 던진다 (E5)`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns completedSprint
                every { it.assignIssue(sprintId, issueKey) } returns 0
            }
        val lookupPort =
            mockk<BoardIssueLookupPort>().also {
                every { it.isVisibleIssue(projectKey, issueKey, actorId) } returns true
            }

        assertThatThrownBy {
            makeService(repo = repo, lookupPort = lookupPort).assignIssue(actorId, sprintId, issueKey)
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(409)
    }

    // ── unassignIssue ─────────────────────────────────────────────────────────

    @Test
    fun `unassignIssue UPDATE 권한이 있으면 repo unassignIssue를 호출한다`() {
        val issueKey = "ATLAS-1"
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
                every { it.unassignIssue(sprintId, issueKey) } returns Unit
            }

        makeService(repo = repo).unassignIssue(actorId, sprintId, issueKey)

        verify(exactly = 1) { repo.unassignIssue(sprintId, issueKey) }
    }

    @Test
    fun `unassignIssue sprint 미존재면 404를 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns null
            }

        assertThatThrownBy {
            makeService(repo = repo).unassignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(SprintNotFoundException::class.java)
    }

    @Test
    fun `unassignIssue UPDATE 권한 없으면 403을 던진다`() {
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns plannedSprint
            }
        val resolver = denyResolver(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))

        assertThatThrownBy {
            makeService(resolver = resolver, repo = repo).unassignIssue(actorId, sprintId, "ATLAS-1")
        }.isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(403)
    }

    // ── 권한 판정 순서 보장 ────────────────────────────────────────────────────

    @Test
    fun `get 권한 판정은 sprint조회로 얻은 projectKey로 수행한다 (요청 파라미터 신뢰 금지)`() {
        val differentProjectKey = "OTHER"
        val sprintWithDifferentProject = plannedSprint.copy(projectKey = differentProjectKey)
        val repo =
            mockk<SprintRepository>().also {
                every { it.findById(sprintId) } returns sprintWithDifferentProject
            }
        val resolver =
            mockk<IssuePermissionResolver>().also {
                every {
                    it.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(differentProjectKey))
                } returns true
                every {
                    it.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
                } returns false
            }

        // sprint.projectKey = OTHER 기준으로 권한 판정해야 통과
        val result = makeService(resolver = resolver, repo = repo).get(actorId, sprintId)
        assertThat(result.projectKey).isEqualTo(differentProjectKey)
    }

    @Test
    fun `create만 body projectKey로 권한을 판정한다 (리소스 부재 상황)`() {
        val repo = mockk<SprintRepository>()
        every { repo.insert(any()) } answers { firstArg() }
        val resolver = allowAllResolver()

        makeService(resolver = resolver, repo = repo).create(
            actorId = actorId,
            projectKey = projectKey,
            boardId = boardId,
            name = "Sprint 1",
            goal = null,
            startDate = null,
            endDate = null,
        )

        // create는 사전에 sprint가 없어 body projectKey 로 권한 판정
        verify { resolver.hasPermission(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey)) }
    }

    // ── 응답 statusCode 세부 검증 ─────────────────────────────────────────────

    @Test
    fun `SprintNotFoundException은 HttpStatus 404를 반환한다`() {
        val ex = SprintNotFoundException()
        assertThat((ex as ResponseStatusException).statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }
}

// IssueChangelogService 단위 테스트 — 이슈 변경 이력 조회 서비스 (MockK)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import com.bts.shared.user.UserLookupPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID

class IssueChangelogServiceTest : DescribeSpec({

    val issueApplicationService = mockk<IssueApplicationService>()
    val changeHistoryRepository = mockk<IssueChangeHistoryRepository>()
    val userLookupPort = mockk<UserLookupPort>()
    val issueRepository = mockk<IssueRepository>()

    // strict mock — 스텁하지 않은 호출은 예외다. 댓글 항목이 없는 케이스에서 이 저장소를
    // 건드리면(불필요한 DB 왕복) 해당 테스트가 죽는다.
    val commentRepository = mockk<CommentRepository>()

    // 기본은 allow-all — 마스킹을 검증하는 케이스에서만 제한 resolver 로 override 한다.
    val sut =
        IssueChangelogService(
            issueApplicationService = issueApplicationService,
            changeHistoryRepository = changeHistoryRepository,
            userLookupPort = userLookupPort,
            issueRepository = issueRepository,
            fieldPermissionResolver = AlwaysAllowFieldPermissionResolver(),
            commentRepository = commentRepository,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val issueId = UUID.randomUUID()
    val projectId = UUID.randomUUID()
    val actorId1 = UUID.randomUUID()
    val actorId2 = UUID.randomUUID()

    fun makeIssueResponse(id: UUID = issueId): IssueResponse =
        IssueResponse(
            key = issueKey.value,
            id = id,
            projectKey = "BTS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.randomUUID(),
            version = 1L,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
        )

    fun makeGroup(
        actorId: UUID? = actorId1,
        createdAt: Instant = Instant.parse("2026-06-01T10:00:00Z"),
    ): IssueChangeGroup =
        IssueChangeGroup(
            issueId = issueId,
            issueKey = issueKey.value,
            actorId = actorId,
            items =
                listOf(
                    IssueChangeItem(field = "priority", fromValue = "2", toValue = "3"),
                ),
            createdAt = createdAt,
        )

    beforeEach {
        clearMocks(
            issueApplicationService,
            changeHistoryRepository,
            userLookupPort,
            issueRepository,
            commentRepository,
        )
        // projectId 조회는 마스킹 candidate 구성의 전제 — 모든 정상 경로에서 동일하게 stub.
        every { issueRepository.findProjectIdByKey("BTS") } returns projectId
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (a) VIEW 권한 없음 / 미존재 / 소프트 삭제 → IssueNotFoundException 전파
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — VIEW 가드 위반") {

        context("issueApplicationService.findByKey 가 IssueNotFoundException 을 던짐") {

            beforeEach {
                every {
                    issueApplicationService.findByKey(actor, issueKey)
                } throws IssueNotFoundException(issueKey)
            }

            it("IssueNotFoundException 을 그대로 전파한다") {
                shouldThrow<IssueNotFoundException> {
                    sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                }
            }

            it("changeHistoryRepository 를 호출하지 않는다") {
                runCatching { sut.findChangelog(actor, issueKey, PageRequest.of(0, 20)) }
                verify(exactly = 0) { changeHistoryRepository.findByIssuePaged(any(), any(), any()) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (b) actorId 있는 그룹 → UserLookupPort 로 actorName 채워짐
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — actorName 해석") {

        context("두 그룹, actorId 모두 존재") {

            val groups =
                listOf(
                    makeGroup(actorId = actorId1),
                    makeGroup(actorId = actorId2),
                )

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 2L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1, actorId2))
                } returns mapOf(actorId1 to "Alice", actorId2 to "Bob")
            }

            it("각 그룹의 actorName 이 표시명으로 채워진다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].actorName shouldBe "Alice"
                page.content[1].actorName shouldBe "Bob"
            }

            it("actorId 도 그대로 포함된다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].actorId shouldBe actorId1
                page.content[1].actorId shouldBe actorId2
            }

            it("items 가 도메인 IssueChangeItem 그대로 포함된다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].items shouldHaveSize 1
                page.content[0].items[0].field shouldBe "priority"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (c) actorId=null(시스템) 또는 lookup 결과 없음 / 포트 예외 → actorName=null graceful degrade
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — graceful degrade") {

        context("actorId=null 인 시스템 그룹") {

            val groups = listOf(makeGroup(actorId = null))

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                // actorId 없으면 lookup 호출 불필요 — 호출 자체가 일어나지 않아도 됨
                every {
                    userLookupPort.findDisplayNamesByIds(emptySet())
                } returns emptyMap()
            }

            it("actorName=null 로 이력을 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content shouldHaveSize 1
                page.content[0].actorName.shouldBeNull()
            }
        }

        context("lookup 결과에 actorId 없음 (미존재 사용자)") {

            val groups = listOf(makeGroup(actorId = actorId1))

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns emptyMap()
            }

            it("actorName=null 로 이력을 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].actorName.shouldBeNull()
            }
        }

        context("UserLookupPort 가 예외를 던짐") {

            val groups = listOf(makeGroup(actorId = actorId1))

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } throws RuntimeException("identity-access unavailable")
            }

            it("예외를 전파하지 않고 actorName=null 로 이력을 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content shouldHaveSize 1
                page.content[0].actorName.shouldBeNull()
            }

            it("이력의 items 도 그대로 포함된다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].items shouldHaveSize 1
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (d) 페이징 위임 — pageable → limit/offset 변환 + PageImpl 구성
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — 페이징") {

        context("2페이지(0-indexed), pageSize=10, 총 25건 — limit/offset 위임 검증") {
            // Spring PageImpl 은 offset+pageSize > total 이면 total=offset+content.size 로 조정한다.
            // page=2, size=10 → offset=20, 20+10=30 > 25 이면 total 이 변환되어 totalElements 검증이 깨진다.
            // limit/offset 위임만 검증하고 totalElements 는 별도 context 에서 검증한다.

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 10, 20) // page=2, size=10 → offset=20
                } returns listOf(makeGroup())
                every { changeHistoryRepository.countByIssue(issueId) } returns 25L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns mapOf(actorId1 to "Alice")
            }

            it("limit=10, offset=20 으로 repository 를 호출한다") {
                sut.findChangelog(actor, issueKey, PageRequest.of(2, 10))
                verify(exactly = 1) { changeHistoryRepository.findByIssuePaged(issueId, 10, 20) }
            }
        }

        context("첫 페이지(page=0), pageSize=10, 총 25건 — totalElements/totalPages 검증") {
            // offset=0, pageSize=10, 0+10=10 <= 25 → Spring PageImpl 이 total=25 를 그대로 보존.
            val groups = (1..10).map { makeGroup(actorId = actorId1) }

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 10, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 25L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns mapOf(actorId1 to "Alice")
            }

            it("totalElements=25 를 포함한 Page 를 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 10))
                page.totalElements shouldBe 25L
            }

            it("totalPages=3 이다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 10))
                page.totalPages shouldBe 3
            }
        }

        context("첫 페이지(page=0), pageSize=20, 총 5건") {

            val groups =
                (1..5).map { i ->
                    makeGroup(
                        actorId = actorId1,
                        createdAt = Instant.parse("2026-06-0${i}T10:00:00Z"),
                    )
                }

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 5L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns mapOf(actorId1 to "Alice")
            }

            it("5건 반환 + isLast=true") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content shouldHaveSize 5
                page.isLast shouldBe true
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (e) 필드 수준 마스킹 (FR-PM-07, 코드리뷰 P1) — 단건 maskInvisible 와 동일 정책
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — 필드 수준 마스킹") {

        // description 만 안 보이는 actor — visibleFields 가 description CORE 를 제외한 candidate 만 반환.
        val descriptionInvisibleResolver =
            object : FieldPermissionResolver {
                override fun visibleFields(
                    actorId: UUID,
                    projectId: UUID,
                    candidates: Set<FieldRef>,
                ): Set<FieldRef> = candidates.filterNot { it.key == "description" }.toSet()

                override fun editableFields(
                    actorId: UUID,
                    projectId: UUID,
                    candidates: Set<FieldRef>,
                ): Set<FieldRef> = candidates
            }

        val maskingSut =
            IssueChangelogService(
                issueApplicationService = issueApplicationService,
                changeHistoryRepository = changeHistoryRepository,
                userLookupPort = userLookupPort,
                issueRepository = issueRepository,
                fieldPermissionResolver = descriptionInvisibleResolver,
                commentRepository = commentRepository,
            )

        fun groupWith(vararg items: IssueChangeItem): IssueChangeGroup =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = issueKey.value,
                actorId = actorId1,
                items = items.toList(),
                createdAt = Instant.parse("2026-06-01T10:00:00Z"),
            )

        context("description(안 보임) + priority(보임) item 이 같은 그룹에 존재") {

            val descriptionItem =
                IssueChangeItem(
                    field = "description",
                    fromValue = "민감한 이전 설명",
                    toValue = "민감한 새 설명",
                    fromLabel = "이전 라벨",
                    toLabel = "새 라벨",
                )
            val priorityItem = IssueChangeItem(field = "priority", fromValue = "2", toValue = "3")

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns listOf(groupWith(descriptionItem, priorityItem))
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every { userLookupPort.findDisplayNamesByIds(setOf(actorId1)) } returns mapOf(actorId1 to "Alice")
            }

            it("description item 의 from/to 값과 라벨이 모두 null 로 마스킹된다") {
                val page = maskingSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                val masked = page.content[0].items.first { it.field == "description" }
                masked.fromValue.shouldBeNull()
                masked.toValue.shouldBeNull()
                masked.fromLabel.shouldBeNull()
                masked.toLabel.shouldBeNull()
            }

            it("마스킹돼도 item 자체는 남아 변경 사실(field=description)을 보존한다") {
                val page = maskingSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].items.map { it.field } shouldContainExactlyInAnyOrder
                    listOf("description", "priority")
            }

            it("보이는 필드(priority) 의 값은 그대로 노출된다") {
                val page = maskingSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                val visible = page.content[0].items.first { it.field == "priority" }
                visible.fromValue shouldBe "2"
                visible.toValue shouldBe "3"
            }
        }

        context("assignee item — field 키 assignee 가 CORE assigneeId 로 매핑돼 마스킹된다") {

            // assigneeId(CORE) 만 안 보이는 actor.
            val assigneeInvisibleResolver =
                object : FieldPermissionResolver {
                    override fun visibleFields(
                        actorId: UUID,
                        projectId: UUID,
                        candidates: Set<FieldRef>,
                    ): Set<FieldRef> = candidates.filterNot { it.key == "assigneeId" }.toSet()

                    override fun editableFields(
                        actorId: UUID,
                        projectId: UUID,
                        candidates: Set<FieldRef>,
                    ): Set<FieldRef> = candidates
                }

            val assigneeSut =
                IssueChangelogService(
                    issueApplicationService = issueApplicationService,
                    changeHistoryRepository = changeHistoryRepository,
                    userLookupPort = userLookupPort,
                    issueRepository = issueRepository,
                    fieldPermissionResolver = assigneeInvisibleResolver,
                    commentRepository = commentRepository,
                )

            val assigneeItem =
                IssueChangeItem(
                    field = "assignee",
                    fromValue = UUID.randomUUID().toString(),
                    toValue = UUID.randomUUID().toString(),
                    fromLabel = "이전 담당자",
                    toLabel = "새 담당자",
                )

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns listOf(groupWith(assigneeItem))
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every { userLookupPort.findDisplayNamesByIds(setOf(actorId1)) } returns mapOf(actorId1 to "Alice")
            }

            it("assignee item 의 값·라벨이 마스킹된다 (assignee→assigneeId 매핑 검증)") {
                val page = assigneeSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                val masked = page.content[0].items.first { it.field == "assignee" }
                masked.fromValue.shouldBeNull()
                masked.toValue.shouldBeNull()
                masked.fromLabel.shouldBeNull()
                masked.toLabel.shouldBeNull()
            }
        }

        context("customField item — customField:secret 가 CUSTOM secret 으로 매핑돼 마스킹된다") {

            // CUSTOM secret 만 안 보이는 actor.
            val customInvisibleResolver =
                object : FieldPermissionResolver {
                    override fun visibleFields(
                        actorId: UUID,
                        projectId: UUID,
                        candidates: Set<FieldRef>,
                    ): Set<FieldRef> = candidates.filterNot { it.key == "secret" }.toSet()

                    override fun editableFields(
                        actorId: UUID,
                        projectId: UUID,
                        candidates: Set<FieldRef>,
                    ): Set<FieldRef> = candidates
                }

            val customSut =
                IssueChangelogService(
                    issueApplicationService = issueApplicationService,
                    changeHistoryRepository = changeHistoryRepository,
                    userLookupPort = userLookupPort,
                    issueRepository = issueRepository,
                    fieldPermissionResolver = customInvisibleResolver,
                    commentRepository = commentRepository,
                )

            val secretItem =
                IssueChangeItem(field = "customField:secret", fromValue = "old-secret", toValue = "new-secret")
            val publicItem =
                IssueChangeItem(field = "customField:public", fromValue = "old-pub", toValue = "new-pub")

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns listOf(groupWith(secretItem, publicItem))
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every { userLookupPort.findDisplayNamesByIds(setOf(actorId1)) } returns mapOf(actorId1 to "Alice")
            }

            it("customField:secret 의 값이 마스킹되고 customField:public 은 노출된다") {
                val page = customSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                val secret = page.content[0].items.first { it.field == "customField:secret" }
                val public = page.content[0].items.first { it.field == "customField:public" }
                secret.fromValue.shouldBeNull()
                secret.toValue.shouldBeNull()
                public.fromValue shouldBe "old-pub"
                public.toValue shouldBe "new-pub"
            }
        }

        context("마스킹 대상이 아닌 필드(status) 는 안 보임 actor 라도 노출된다") {

            // 모든 candidate 를 다 가려도(아무것도 안 보이는 극단 actor) status 는 candidate 가 아니므로 영향 없음.
            val denyAllResolver =
                object : FieldPermissionResolver {
                    override fun visibleFields(
                        actorId: UUID,
                        projectId: UUID,
                        candidates: Set<FieldRef>,
                    ): Set<FieldRef> = emptySet()

                    override fun editableFields(
                        actorId: UUID,
                        projectId: UUID,
                        candidates: Set<FieldRef>,
                    ): Set<FieldRef> = candidates
                }

            val denySut =
                IssueChangelogService(
                    issueApplicationService = issueApplicationService,
                    changeHistoryRepository = changeHistoryRepository,
                    userLookupPort = userLookupPort,
                    issueRepository = issueRepository,
                    fieldPermissionResolver = denyAllResolver,
                    commentRepository = commentRepository,
                )

            val statusItem = IssueChangeItem(field = "status", fromValue = "open", toValue = "closed")

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns listOf(groupWith(statusItem))
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every { userLookupPort.findDisplayNamesByIds(setOf(actorId1)) } returns mapOf(actorId1 to "Alice")
            }

            it("status item 값은 마스킹되지 않고 그대로 노출된다") {
                val page = denySut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                val status = page.content[0].items.first { it.field == "status" }
                status.fromValue shouldBe "open"
                status.toValue shouldBe "closed"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (f) page 오버플로 (코드리뷰 P2) — Long 곱으로 Int 오버플로 음수 OFFSET 을 방지하고 Int 범위로 클램프
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — 큰 pageNumber 오버플로 가드") {

        // pageNumber * pageSize 를 Int 로 곱하면 오버플로로 음수 OFFSET 이 되어 Postgres 가 500 을 던진다.
        // Long 으로 곱한 뒤 repository offset(Int) 범위(0..Int.MAX_VALUE)로 안전하게 클램프해야 한다.
        // 예: pageNumber=200_000_000, pageSize=20 → Long 곱 4_000_000_000 → Int.MAX_VALUE 로 클램프.
        context("pageNumber*pageSize 가 Int.MAX_VALUE 를 초과") {

            val bigPage = 200_000_000
            val size = 20

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, size, Int.MAX_VALUE)
                } returns emptyList()
                every { changeHistoryRepository.countByIssue(issueId) } returns 0L
            }

            it("음수 OFFSET 없이 Int.MAX_VALUE 로 클램프된 offset 으로 repository 를 호출한다") {
                sut.findChangelog(actor, issueKey, PageRequest.of(bigPage, size))
                verify(exactly = 1) { changeHistoryRepository.findByIssuePaged(issueId, size, Int.MAX_VALUE) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (g) 삭제된 댓글의 이력 본문 마스킹 (FR-CO-02 Task 11 — 모더레이션 우회 차단)
    //
    // 악의적 사용자가 무해한 댓글을 쓴 뒤 부적절한 내용으로 수정하면 그 본문이
    // issue_change_item.to_value 에 남는다. 모더레이터가 댓글을 소프트 삭제해도 이력 탭에서
    // 계속 읽히면 삭제가 무력화된다. 이력은 append-only 라 행을 지울 수 없으므로 조회 시점에 가린다.
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — 삭제된 댓글 본문 마스킹") {

        val commentSut =
            IssueChangelogService(
                issueApplicationService = issueApplicationService,
                changeHistoryRepository = changeHistoryRepository,
                userLookupPort = userLookupPort,
                issueRepository = issueRepository,
                // 필드 권한 마스킹과 직교시킨다 — 여기서 가려지면 원인이 댓글 삭제인지 필드 권한인지 구분 못 한다.
                fieldPermissionResolver = AlwaysAllowFieldPermissionResolver(),
                commentRepository = commentRepository,
            )

        val activeCommentId = UUID.randomUUID()
        val deletedCommentId = UUID.randomUUID()
        val activeField = "${IssueHistoryRecorder.COMMENT_FIELD_PREFIX}$activeCommentId"
        val deletedField = "${IssueHistoryRecorder.COMMENT_FIELD_PREFIX}$deletedCommentId"

        fun commentItem(
            field: String,
            before: String,
            after: String,
        ): IssueChangeItem =
            IssueChangeItem(
                field = field,
                fromValue = before,
                toValue = after,
                fromLabel = "이전 라벨",
                toLabel = "새 라벨",
            )

        fun groupWith(vararg items: IssueChangeItem): IssueChangeGroup =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = issueKey.value,
                actorId = actorId1,
                items = items.toList(),
                createdAt = Instant.parse("2026-06-01T10:00:00Z"),
            )

        beforeEach {
            every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
            every { changeHistoryRepository.countByIssue(issueId) } returns 1L
            every { userLookupPort.findDisplayNamesByIds(setOf(actorId1)) } returns mapOf(actorId1 to "Alice")
        }

        context("활성 댓글 1건 + 삭제된 댓글 1건의 수정 이력이 같은 그룹에 존재") {

            val activeItem = commentItem(activeField, "활성 댓글 원본", "활성 댓글 수정본")
            val deletedItem = commentItem(deletedField, "무해한 위장 본문", "삭제 사유가 된 부적절한 본문")

            beforeEach {
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns listOf(groupWith(activeItem, deletedItem))
                every { commentRepository.findActiveIds(any(), issueId) } returns setOf(activeCommentId)
            }

            it("삭제된 댓글의 이력 항목은 fromValue·toValue 가 마스킹된다") {
                val page = commentSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))

                val masked = page.content[0].items.first { it.field == deletedField }
                masked.fromValue.shouldBeNull()
                masked.toValue.shouldBeNull()
                masked.fromLabel.shouldBeNull()
                masked.toLabel.shouldBeNull()
                // N+1 금지 — 페이지 내 댓글 id 를 모아 단 한 번만 배치 조회해야 한다.
                verify(exactly = 1) {
                    commentRepository.findActiveIds(setOf(activeCommentId, deletedCommentId), issueId)
                }
            }

            it("활성 댓글의 이력 항목은 본문이 그대로 보인다") {
                val page = commentSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))

                val visible = page.content[0].items.first { it.field == activeField }
                visible.fromValue shouldBe "활성 댓글 원본"
                visible.toValue shouldBe "활성 댓글 수정본"
                visible.fromLabel shouldBe "이전 라벨"
                visible.toLabel shouldBe "새 라벨"
            }

            it("마스킹돼도 항목 자체는 목록에서 사라지지 않는다") {
                val page = commentSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))

                // "삭제된 댓글이 수정된 적 있다" 는 사실 자체는 감사 추적을 위해 남아야 한다.
                page.content[0].items shouldHaveSize 2
                page.content[0].items.map { it.field } shouldContainExactlyInAnyOrder
                    listOf(activeField, deletedField)
            }
        }

        context("comment 접두사가 아닌 기존 필드가 같은 그룹에 섞여 있음") {

            val statusItem = IssueChangeItem(field = "status", fromValue = "open", toValue = "closed")
            val assigneeItem =
                IssueChangeItem(
                    field = "assignee",
                    fromValue = "11111111-1111-4111-8111-111111111111",
                    toValue = "22222222-2222-4222-8222-222222222222",
                    fromLabel = "이전 담당자",
                    toLabel = "새 담당자",
                )
            val deletedItem = commentItem(deletedField, "이전 본문", "이후 본문")

            beforeEach {
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns listOf(groupWith(statusItem, assigneeItem, deletedItem))
                // 모든 댓글이 삭제된 극단 — 그래도 댓글이 아닌 필드는 손대면 안 된다.
                every { commentRepository.findActiveIds(any(), issueId) } returns emptySet()
            }

            it("comment: 접두사가 아닌 기존 필드(status·assignee 등)는 영향받지 않는다") {
                val page = commentSut.findChangelog(actor, issueKey, PageRequest.of(0, 20))

                val status = page.content[0].items.first { it.field == "status" }
                status.fromValue shouldBe "open"
                status.toValue shouldBe "closed"
                val assignee = page.content[0].items.first { it.field == "assignee" }
                assignee.fromValue shouldBe "11111111-1111-4111-8111-111111111111"
                assignee.toValue shouldBe "22222222-2222-4222-8222-222222222222"
                assignee.fromLabel shouldBe "이전 담당자"
                assignee.toLabel shouldBe "새 담당자"
                // 대조군 — 같은 그룹의 삭제된 댓글 항목은 실제로 가려진다.
                // 없으면 "아무것도 마스킹하지 않는" 구현도 위 단언을 전부 통과한다.
                page.content[0].items.first { it.field == deletedField }.toValue.shouldBeNull()
            }
        }
    }
})

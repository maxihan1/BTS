// updateIssue 멘션 추출 → IssueMentioned 발행 단위 테스트 (FR-MN-01 Task-4)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueDomainEvent
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueMentioned
import com.bts.issue.event.IssueUpdated
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [IssueApplicationService.updateIssue] 에서 description 변경 시 IssueMentioned 발행 여부를 검증한다.
 *
 * 단위 테스트(MockK). UserLookupPort 는 inline fake 로 username→UUID 매핑을 시뮬레이션한다.
 * 기존 IssueUpdated 발행 회귀 확인을 모든 케이스에서 함께 수행한다.
 *
 * 스펙 S1~S5:
 * - S1: null → "@bob" 변경 → bob ID 가 mentionedUserIds 에 포함된 IssueMentioned 1회 발행.
 * - S2: "@bob" → "@bob 추가" (멘션 동일) → IssueMentioned 미발행.
 * - S3: "@bob" → "@bob @carol" → 추가된 carol ID 만 포함.
 * - S4: actor=alice 가 자기 멘션 추가 → 자기 제외 후 빈집합 → 미발행.
 * - S5: "@ghost" (해석 불가) → 미발행.
 */
class IssueApplicationServiceMentionTest : DescribeSpec({

    val bobId = UUID.fromString("bb000000-0000-0000-0000-000000000001")
    val carolId = UUID.fromString("cc000000-0000-0000-0000-000000000002")
    val aliceId = UUID.fromString("aa000000-0000-0000-0000-000000000003")

    val aliceActor = ActorId(aliceId)
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L
    val fixedClock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC)
    val anyProjectId: UUID = UUID.fromString("11111111-0000-0000-0000-000000000001")

    // UserLookupPort inline fake — exists 는 항상 true, findIdsByUsernames 는 bob/carol/alice 만 해석, ghost 는 드롭.
    val userLookupFake =
        object : UserLookupPort {
            override fun exists(userId: UUID): Boolean = true

            override fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> =
                mapOf("bob" to bobId, "carol" to carolId, "alice" to aliceId)
                    .filterKeys { it in usernames }
        }

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            resolutionRepository = resolutionRepository,
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = userLookupFake,
            componentRepository = mockk(relaxed = true),
            projectLeadRepository = mockk(relaxed = true),
            versionRepository = mockk(relaxed = true),
            clock = fixedClock,
            historyRecorder = mockk(relaxed = true),
        )

    /**
     * 테스트용 Issue 도메인 객체를 생성한다.
     *
     * @param description 이슈 본문. null 이면 미작성 상태.
     */
    fun makeIssue(description: String? = null) =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = issueKey,
            projectId = UUID.randomUUID(),
            summary = "제목",
            reporterId = aliceActor,
            currentStateKey = "open",
            version = existingVersion,
            deletedAt = null,
            createdAt = Instant.parse("2026-06-11T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-11T00:00:00Z"),
            typeId = IssueTypeId(3L),
            description = description,
        )

    /**
     * 테스트용 IssueResponse DTO 를 생성한다.
     */
    fun makeResponse() =
        IssueResponse(
            key = issueKey.value,
            id = UUID.randomUUID(),
            projectKey = issueKey.projectPrefix,
            summary = "제목",
            currentStateKey = "open",
            reporterId = aliceActor.value,
            version = existingVersion + 1,
            createdAt = Instant.parse("2026-06-11T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-11T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    // 각 테스트 전 공통 mock 초기화
    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
        every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
        every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findProjectIdByKey(issueKey.projectPrefix) } returns anyProjectId
        every {
            permissionResolver.hasPermission(
                aliceActor.value,
                IssuePermission.UPDATE,
                IssueScope.Issue(issueKey.value),
            )
        } returns true
        every { eventPublisher.publish(any()) } returns Unit
    }

    describe("updateIssue — 멘션 상한 (FR-MN-01 H1)") {

        // H1: distinct 멘션 51개 → 발행된 mentionedUserIds.size 가 50 이하
        context("H1 — distinct 멘션 51개: 발행 이벤트 대상이 상한(50) 이하로 잘린다") {
            // user0..user50 — 51개. actor(aliceActor) 는 목록 외이므로 자기 제외 없이 전원 대상.
            val manyUsernames = (0..50).map { "user$it" }
            val manyIds = manyUsernames.associateWith { UUID.nameUUIDFromBytes(it.toByteArray()) }

            // 51개 모두 해석하는 fake — 기존 userLookupFake 와 별도로 구성
            val bulkUserLookupFake =
                object : UserLookupPort {
                    override fun exists(userId: UUID): Boolean = true

                    override fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> {
                        return manyIds.filterKeys { it in usernames }
                    }
                }

            val bulkRepo = mockk<IssueRepository>()
            val bulkEventPublisher = mockk<IssueEventPublisher>()
            val bulkPermissionResolver = mockk<IssuePermissionResolver>()

            val bulkSut =
                IssueApplicationService(
                    repo = bulkRepo,
                    issueTypeRepository = mockk(relaxed = true),
                    resolutionRepository = mockk(relaxed = true),
                    eventPublisher = bulkEventPublisher,
                    permissionResolver = bulkPermissionResolver,
                    workflowPort = mockk(relaxed = true),
                    workflowKeyResolver = mockk(relaxed = true),
                    userLookupPort = bulkUserLookupFake,
                    componentRepository = mockk(relaxed = true),
                    projectLeadRepository = mockk(relaxed = true),
                    versionRepository = mockk(relaxed = true),
                    clock = fixedClock,
                    historyRecorder = mockk(relaxed = true),
                )

            // 51개 멘션 본문 구성 — 기존 description null
            val bulkDescription = manyUsernames.joinToString(" ") { "@$it" }
            val existingIssue = makeIssue(description = null)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    description = bulkDescription,
                )

            beforeEach {
                every { bulkRepo.findActiveComponentIdsByIssue(any()) } returns emptyList()
                every { bulkRepo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
                every { bulkRepo.findFixVersionIdsByIssue(any()) } returns emptyList()
                every { bulkRepo.findProjectIdByKey(issueKey.projectPrefix) } returns anyProjectId
                every {
                    bulkPermissionResolver.hasPermission(
                        aliceActor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { bulkRepo.findByKey(issueKey) } returns existingIssue
                every {
                    bulkRepo.updateFields(issueKey, any(), existingVersion)
                } returns 1
                every { bulkRepo.findByKeyWithType(issueKey) } returns makeResponse()
                every { bulkEventPublisher.publish(any()) } returns Unit
            }

            it("IssueMentioned 의 mentionedUserIds.size 가 50 이하다") {
                val capturedEvents = mutableListOf<IssueDomainEvent>()
                every { bulkEventPublisher.publish(capture(capturedEvents)) } returns Unit

                bulkSut.updateIssue(aliceActor, issueKey, request)

                val mentioned = capturedEvents.filterIsInstance<IssueMentioned>()
                // 현재 구현은 상한이 없어 51개가 모두 포함될 것이므로 이 단언이 실패해야 한다
                mentioned.single().mentionedUserIds.size shouldBe 50
            }

            // ★E5 캡 경계 — 캡이 걸리는 순간이 알림 대상과 watcher 대상이 갈릴 수 있는 유일한 자리다.
            //   기존 판정은 캡이 안 걸린 경우만 봤으므로 여기서 경계를 직접 잰다.
            it("캡이 걸려도 알림 대상과 watcher 등록 목록이 정확히 같다") {
                val capWatcherRepo = mockk<IssueWatcherRepository>(relaxed = true)
                val capSut =
                    IssueApplicationService(
                        repo = bulkRepo,
                        issueTypeRepository = mockk(relaxed = true),
                        resolutionRepository = mockk(relaxed = true),
                        eventPublisher = bulkEventPublisher,
                        permissionResolver = bulkPermissionResolver,
                        workflowPort = mockk(relaxed = true),
                        workflowKeyResolver = mockk(relaxed = true),
                        userLookupPort = bulkUserLookupFake,
                        componentRepository = mockk(relaxed = true),
                        projectLeadRepository = mockk(relaxed = true),
                        versionRepository = mockk(relaxed = true),
                        clock = fixedClock,
                        historyRecorder = mockk(relaxed = true),
                        watcherRepository = capWatcherRepo,
                    )
                val captured = mutableListOf<IssueDomainEvent>()
                every { bulkEventPublisher.publish(capture(captured)) } returns Unit
                val watched = mutableListOf<List<UUID>>()
                every { capWatcherRepo.addAll(any(), capture(watched)) } returns Unit

                capSut.updateIssue(aliceActor, issueKey, request)

                val mentioned = captured.filterIsInstance<IssueMentioned>().single()
                mentioned.mentionedUserIds.size shouldBe 50
                watched.single() shouldBe mentioned.mentionedUserIds
            }
        }
    }

    describe("updateIssue — 멘션 발행 (FR-MN-01)") {

        // S1: null → "@bob" 변경 → bob ID 포함 IssueMentioned 1회 발행
        context("S1 — description null→\"@bob\" 변경: 신규 멘션 1개") {
            val existingIssue = makeIssue(description = null)
            val request = UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "@bob")

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(issueKey, any(), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns makeResponse()
            }

            it("IssueMentioned 1회 발행: mentionedUserIds=[bobId], sourceField=description, actorId=aliceActor") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { event ->
                            event is IssueMentioned &&
                                event.mentionedUserIds == listOf(bobId) &&
                                event.sourceField == "description" &&
                                event.actorId == aliceActor
                        },
                    )
                }
            }

            it("IssueUpdated(description) 도 함께 발행된다 (회귀)") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { event ->
                            event is IssueUpdated && "description" in event.fields
                        },
                    )
                }
            }
        }

        // S2: "@bob" → "@bob 추가설명" — 멘션 집합 동일 → IssueMentioned 미발행
        context("S2 — description \"@bob\"→\"@bob 추가설명\": 멘션 동일, 신규 없음") {
            val existingIssue = makeIssue(description = "@bob")
            val newDescription = "@bob 추가설명"
            val request =
                UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = newDescription)

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(issueKey, any(), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns makeResponse()
            }

            it("IssueMentioned 가 발행되지 않는다") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 0) {
                    eventPublisher.publish(match { it is IssueMentioned })
                }
            }

            it("IssueUpdated(description) 은 발행된다 (회귀)") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { event ->
                            event is IssueUpdated && "description" in event.fields
                        },
                    )
                }
            }
        }

        // S3: "@bob" → "@bob @carol" — carol 만 추가분 → mentionedUserIds=[carolId]
        context("S3 — description \"@bob\"→\"@bob @carol\": carol 이 신규 추가") {
            val existingIssue = makeIssue(description = "@bob")
            val newDescription = "@bob @carol"
            val request =
                UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = newDescription)

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(issueKey, any(), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns makeResponse()
            }

            it("IssueMentioned 가 1회 발행되고 mentionedUserIds=[carolId] (추가분만)") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { event ->
                            event is IssueMentioned &&
                                event.mentionedUserIds == listOf(carolId)
                        },
                    )
                }
            }
        }

        // S4: actor=alice 가 본문에 "@alice" 추가 → 자기 제외 후 빈집합 → 미발행
        context("S4 — actor=alice 가 \"@alice\" 추가: 자기 멘션 제외 후 빈집합") {
            val existingIssue = makeIssue(description = null)
            val request = UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "@alice")

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(issueKey, any(), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns makeResponse()
            }

            it("IssueMentioned 가 발행되지 않는다 (자기 제외)") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 0) {
                    eventPublisher.publish(match { it is IssueMentioned })
                }
            }
        }

        // S5: "@ghost" — findIdsByUsernames 빈맵 반환 → 미발행
        context("S5 — \"@ghost\": 존재하지 않는 username, 해석 결과 빈맵") {
            val existingIssue = makeIssue(description = null)
            val request = UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "@ghost")

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(issueKey, any(), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns makeResponse()
            }

            it("IssueMentioned 가 발행되지 않는다 (미존재 username 드롭)") {
                sut.updateIssue(aliceActor, issueKey, request)
                verify(exactly = 0) {
                    eventPublisher.publish(match { it is IssueMentioned })
                }
            }
        }
    }

    // ── FR-MN-03 — 멘션 대상 자동 watcher ────────────────────────────────
    describe("updateIssue — 멘션 대상이 자동 watcher 가 된다 (FR-MN-03)") {

        val watcherRepo = mockk<IssueWatcherRepository>(relaxed = true)
        val wRepo = mockk<IssueRepository>()
        val wEventPublisher = mockk<IssueEventPublisher>()
        val wPermissionResolver = mockk<IssuePermissionResolver>()
        val existingIssue = makeIssue(description = null)

        val wSut =
            IssueApplicationService(
                repo = wRepo,
                issueTypeRepository = mockk(relaxed = true),
                resolutionRepository = mockk(relaxed = true),
                eventPublisher = wEventPublisher,
                permissionResolver = wPermissionResolver,
                workflowPort = mockk(relaxed = true),
                workflowKeyResolver = mockk(relaxed = true),
                userLookupPort = userLookupFake,
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = fixedClock,
                historyRecorder = mockk(relaxed = true),
                watcherRepository = watcherRepo,
            )

        beforeEach {
            clearMocks(watcherRepo, answers = false)
            every { wRepo.findActiveComponentIdsByIssue(any()) } returns emptyList()
            every { wRepo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
            every { wRepo.findFixVersionIdsByIssue(any()) } returns emptyList()
            every { wRepo.findProjectIdByKey(issueKey.projectPrefix) } returns anyProjectId
            every {
                wPermissionResolver.hasPermission(
                    aliceActor.value,
                    IssuePermission.UPDATE,
                    IssueScope.Issue(issueKey.value),
                )
            } returns true
            every { wRepo.findByKey(issueKey) } returns existingIssue
            every { wRepo.updateFields(issueKey, any(), existingVersion) } returns 1
            every { wRepo.findByKeyWithType(issueKey) } returns makeResponse()
            every { wEventPublisher.publish(any()) } returns Unit
        }

        it("신규 멘션 대상이 watcher 로 등록된다") {
            wSut.updateIssue(
                aliceActor,
                issueKey,
                UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "@bob @carol"),
            )

            verify(exactly = 1) { watcherRepo.addAll(existingIssue.id.value, listOf(bobId, carolId).sorted()) }
        }

        // ★E5 — 알림 대상과 watcher 대상은 반드시 같은 목록이다.
        //   두 곳이 각자 계산하면 캡이 걸렸을 때 조용히 갈린다. 같은 리스트인지 직접 대조한다.
        it("발행된 mentionedUserIds 와 watcher 등록 목록이 정확히 같다") {
            val captured = mutableListOf<IssueDomainEvent>()
            every { wEventPublisher.publish(capture(captured)) } returns Unit
            val watched = mutableListOf<List<UUID>>()
            every { watcherRepo.addAll(any(), capture(watched)) } returns Unit

            wSut.updateIssue(
                aliceActor,
                issueKey,
                UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "@bob @carol"),
            )

            val mentioned = captured.filterIsInstance<IssueMentioned>().single()
            watched.single() shouldBe mentioned.mentionedUserIds
        }

        it("대상이 0명이면 watcher 도 건드리지 않는다") {
            wSut.updateIssue(
                aliceActor,
                issueKey,
                UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "멘션 없음"),
            )

            verify(exactly = 0) { watcherRepo.addAll(any(), any()) }
            verify(exactly = 0) { watcherRepo.add(any(), any()) }
        }

        it("자기 자신만 멘션하면 이벤트도 watcher 도 없다") {
            wSut.updateIssue(
                aliceActor,
                issueKey,
                UpdateIssueRequest(summary = null, expectedVersion = existingVersion, description = "@alice"),
            )

            verify(exactly = 0) { watcherRepo.addAll(any(), any()) }
        }
    }
})

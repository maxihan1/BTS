// IssueAttachmentService 단위 테스트 — MockK. upload/list/download/delete 4동작 + 권한 + 보상 삭제 검증.

package com.bts.issue.attachment

import com.bts.issue.attachment.application.AttachmentStoragePort
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.attachment.domain.Attachment
import com.bts.issue.attachment.repository.AttachmentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * IssueAttachmentService 단위 테스트.
 *
 * storagePort / attachmentRepository / permissionResolver / issueRepository 를 MockK 로 stub.
 *
 * 검증 목록.
 * - upload: UPDATE 미보유 → 403, 보유 시 put→insert 순서 보장, insert 예외 시 remove 보상 + 예외 전파
 * - list: VIEW 미보유 → 403
 * - download: VIEW 미보유 → 403, 교차 이슈 attachmentId → 404
 * - delete: UPDATE 미보유 → 403, 보유 시 remove→deleteById 순서 보장, 교차 이슈 → 404
 * - 이슈 미존재/소프트 삭제 upload → 404
 */
class IssueAttachmentServiceTest : DescribeSpec({

    val storagePort = mockk<AttachmentStoragePort>()
    val attachmentRepository = mockk<AttachmentRepository>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val issueRepository = mockk<IssueRepository>()

    val sut =
        IssueAttachmentService(
            storagePort = storagePort,
            attachmentRepository = attachmentRepository,
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("PROJ-1")
    val issueId = UUID.randomUUID()

    fun stubIssueExists() {
        val issue = mockk<com.bts.issue.domain.Issue>()
        every { issue.id } returns com.bts.issue.domain.IssueId(issueId)
        every { issueRepository.findByKey(issueKey) } returns issue
    }

    fun stubIssueNotFound() {
        every { issueRepository.findByKey(issueKey) } returns null
    }

    fun stubPermission(
        permission: IssuePermission,
        allowed: Boolean,
    ) {
        every {
            permissionResolver.hasPermission(actor.value, permission, IssueScope.Issue(issueKey.value))
        } returns allowed
    }

    fun makeAttachment(attachmentIssueId: UUID = issueId): Attachment =
        Attachment(
            id = UUID.randomUUID(),
            issueId = attachmentIssueId,
            filename = "test.pdf",
            contentType = "application/pdf",
            sizeBytes = 1024L,
            storageKey = "issues/$attachmentIssueId/${UUID.randomUUID()}",
            uploadedBy = actor.value,
            createdAt = Instant.now(),
        )

    afterEach { clearMocks(storagePort, attachmentRepository, permissionResolver, issueRepository) }

    // ── upload ──────────────────────────────────────────────────────────────

    describe("upload") {

        it("UPDATE 권한 없으면 IssueAccessDeniedException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "file.txt",
                    contentType = "text/plain",
                    sizeBytes = 100L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            verify(exactly = 0) { storagePort.put(any(), any(), any(), any()) }
        }

        it("이슈가 없거나 소프트 삭제된 경우 IssueNotFoundException 던진다") {
            stubIssueNotFound()
            // 권한 검증보다 먼저 이슈 조회하지 않는지 확인: 이슈가 없어도 권한 stub 필요
            stubPermission(IssuePermission.UPDATE, true)

            shouldThrow<IssueNotFoundException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "file.txt",
                    contentType = "text/plain",
                    sizeBytes = 100L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            verify(exactly = 0) { storagePort.put(any(), any(), any(), any()) }
        }

        it("UPDATE 권한 있고 이슈 존재하면 put 후 insert 순서로 실행한다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            justRun { storagePort.put(any(), any(), any(), any()) }
            justRun { attachmentRepository.insert(any()) }

            val result =
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "report.pdf",
                    contentType = "application/pdf",
                    sizeBytes = 2048L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )

            result.issueId shouldBe issueId
            result.filename shouldBe "report.pdf"
            result.uploadedBy shouldBe actor.value

            verifyOrder {
                storagePort.put(any(), any(), any(), any())
                attachmentRepository.insert(any())
            }
        }

        it("insert 실패 시 storagePort.remove 보상 호출 후 예외를 전파한다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            justRun { storagePort.put(any(), any(), any(), any()) }
            every { attachmentRepository.insert(any()) } throws RuntimeException("DB error")
            justRun { storagePort.remove(any()) }

            shouldThrow<RuntimeException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "broken.txt",
                    contentType = "text/plain",
                    sizeBytes = 10L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            verify(exactly = 1) { storagePort.remove(any()) }
        }

        it("insert 실패 시 IssueAccessDeniedException 같은 권한 예외는 보상 없이 그대로 전파한다") {
            // 보상 catch가 권한 예외를 삼키지 않는지 확인 — 이 케이스에서 권한예외가 insert 전에 발생하면
            // remove가 호출되지 않아야 한다
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "file.txt",
                    contentType = "text/plain",
                    sizeBytes = 10L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            verify(exactly = 0) { storagePort.remove(any()) }
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    describe("list") {

        it("VIEW 권한 없으면 IssueAccessDeniedException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.VIEW, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.list(actor = actor, issueKey = issueKey)
            }

            verify(exactly = 0) { attachmentRepository.findByIssueId(any()) }
        }

        it("VIEW 권한 있으면 첨부 목록을 반환한다") {
            stubIssueExists()
            stubPermission(IssuePermission.VIEW, true)
            val attachments = listOf(makeAttachment(), makeAttachment())
            every { attachmentRepository.findByIssueId(issueId) } returns attachments

            val result = sut.list(actor = actor, issueKey = issueKey)

            result shouldBe attachments
        }
    }

    // ── download ─────────────────────────────────────────────────────────────

    describe("download") {

        it("VIEW 권한 없으면 IssueAccessDeniedException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.VIEW, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.download(actor = actor, issueKey = issueKey, attachmentId = UUID.randomUUID())
            }
        }

        it("첨부 없으면 IssueNotFoundException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.VIEW, true)
            val attachmentId = UUID.randomUUID()
            every { attachmentRepository.findById(attachmentId) } returns null

            shouldThrow<IssueNotFoundException> {
                sut.download(actor = actor, issueKey = issueKey, attachmentId = attachmentId)
            }
        }

        it("교차 이슈 attachmentId(issueId 불일치)이면 IssueNotFoundException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.VIEW, true)
            val attachmentId = UUID.randomUUID()
            val otherIssueId = UUID.randomUUID() // 다른 이슈의 UUID
            val attachment = makeAttachment(attachmentIssueId = otherIssueId)
            every { attachmentRepository.findById(attachmentId) } returns attachment

            shouldThrow<IssueNotFoundException> {
                sut.download(actor = actor, issueKey = issueKey, attachmentId = attachmentId)
            }

            verify(exactly = 0) { storagePort.get(any()) }
        }

        it("VIEW 권한 있고 issueId 일치하면 메타+스트림을 반환한다") {
            stubIssueExists()
            stubPermission(IssuePermission.VIEW, true)
            val attachment = makeAttachment()
            every { attachmentRepository.findById(attachment.id) } returns attachment
            val stream = ByteArrayInputStream(ByteArray(10))
            every { storagePort.get(attachment.storageKey) } returns stream

            val result = sut.download(actor = actor, issueKey = issueKey, attachmentId = attachment.id)

            result.attachment shouldBe attachment
            result.stream shouldBe stream
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    describe("delete") {

        it("UPDATE 권한 없으면 IssueAccessDeniedException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.delete(actor = actor, issueKey = issueKey, attachmentId = UUID.randomUUID())
            }

            verify(exactly = 0) { storagePort.remove(any()) }
            verify(exactly = 0) { attachmentRepository.deleteById(any()) }
        }

        it("교차 이슈 attachmentId(issueId 불일치)이면 IssueNotFoundException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            val attachmentId = UUID.randomUUID()
            val otherIssueId = UUID.randomUUID()
            val attachment = makeAttachment(attachmentIssueId = otherIssueId)
            every { attachmentRepository.findById(attachmentId) } returns attachment

            shouldThrow<IssueNotFoundException> {
                sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachmentId)
            }

            verify(exactly = 0) { storagePort.remove(any()) }
            verify(exactly = 0) { attachmentRepository.deleteById(any()) }
        }

        it("UPDATE 권한 있고 issueId 일치하면 remove 후 deleteById 순서로 실행한다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            val attachment = makeAttachment()
            every { attachmentRepository.findById(attachment.id) } returns attachment
            justRun { storagePort.remove(attachment.storageKey) }
            every { attachmentRepository.deleteById(attachment.id) } returns true

            sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachment.id)

            verifyOrder {
                storagePort.remove(attachment.storageKey)
                attachmentRepository.deleteById(attachment.id)
            }
        }

        it("MinIO remove 실패해도 deleteById 는 계속 호출한다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            val attachment = makeAttachment()
            every { attachmentRepository.findById(attachment.id) } returns attachment
            every { storagePort.remove(attachment.storageKey) } throws RuntimeException("MinIO 오류")
            every { attachmentRepository.deleteById(attachment.id) } returns true

            // 예외가 전파되지 않아야 함
            sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachment.id)

            verify(exactly = 1) { attachmentRepository.deleteById(attachment.id) }
        }
    }
})

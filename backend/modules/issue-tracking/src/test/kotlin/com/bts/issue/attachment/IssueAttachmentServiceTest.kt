// IssueAttachmentService 단위 테스트 — MockK. upload/list/download/delete 4동작 + 권한 + 보상 삭제 + 스캔 게이트 검증.

package com.bts.issue.attachment

import com.bts.issue.attachment.application.AttachmentInfectedException
import com.bts.issue.attachment.application.AttachmentScanUnavailableException
import com.bts.issue.attachment.application.AttachmentStoragePort
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.attachment.application.ScanVerdict
import com.bts.issue.attachment.application.UnsupportedAttachmentTypeException
import com.bts.issue.attachment.application.VirusScanPort
import com.bts.issue.attachment.domain.Attachment
import com.bts.issue.attachment.repository.AttachmentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * IssueAttachmentService 단위 테스트.
 *
 * storagePort / attachmentRepository / permissionResolver / issueRepository / scanPort 를 MockK 로 stub.
 *
 * 검증 목록.
 * - upload: UPDATE 미보유 → 403, 보유 시 scan→put→insert 순서 보장, insert 예외 시 remove 보상 + 예외 전파
 * - upload scan→INFECTED: AttachmentInfectedException throw + put/insert 미호출 + temp 잔존 0
 * - upload scan→UNAVAILABLE: AttachmentScanUnavailableException 전파 + put/insert 미호출 + temp 잔존 0
 * - upload insert 실패: put/insert 미호출 아님(보상 삭제) + temp 잔존 0
 * - upload 성공: temp 잔존 0
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
    val scanPort = mockk<VirusScanPort>()
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    val sut =
        IssueAttachmentService(
            storagePort = storagePort,
            attachmentRepository = attachmentRepository,
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
            scanPort = scanPort,
            archiveGuard = archiveGuard,
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

    fun makeAttachment(
        attachmentIssueId: UUID = issueId,
        uploader: UUID = actor.value,
    ): Attachment =
        Attachment(
            id = UUID.randomUUID(),
            issueId = attachmentIssueId,
            filename = "test.pdf",
            contentType = "application/pdf",
            sizeBytes = 1024L,
            storageKey = "issues/$attachmentIssueId/${UUID.randomUUID()}",
            uploadedBy = uploader,
            createdAt = Instant.now(),
        )

    afterEach {
        clearMocks(storagePort, attachmentRepository, permissionResolver, issueRepository, scanPort, archiveGuard)
    }

    // ── upload ──────────────────────────────────────────────────────────────

    describe("upload") {

        it("UPDATE 권한 없으면 IssueAccessDeniedException 던진다 (스캔 미도달 — stub 불요)") {
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

        it("이슈가 없거나 소프트 삭제된 경우 IssueNotFoundException 던진다 (스캔 미도달 — stub 불요)") {
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

        it("UPDATE 권한 있고 이슈 존재하면 scan(CLEAN) 후 put → insert 순서로 실행하고 temp 잔존 0") {
            // [C5] 기존 happy-path에 scan→CLEAN stub 추가
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            every { scanPort.scan(any()) } returns ScanVerdict.CLEAN
            justRun { storagePort.put(any(), any(), any(), any()) }
            justRun { attachmentRepository.insert(any()) }

            // temp 디렉토리 스냅샷 — 테스트 전/후 비교
            val tempDir = Path.of(System.getProperty("java.io.tmpdir"))
            val beforeFiles = Files.list(tempDir).use { it.toList().toSet() }

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

            // scan → put → insert 순서
            verifyOrder {
                scanPort.scan(any())
                storagePort.put(any(), any(), any(), any())
                attachmentRepository.insert(any())
            }

            // [C6] 성공 경로 temp 잔존 0
            val afterFiles = Files.list(tempDir).use { it.toList().toSet() }
            val leaked = afterFiles - beforeFiles
            leaked shouldBe emptySet()
        }

        it("허용되지 않은 MIME/확장자는 UnsupportedAttachmentTypeException 던지고 put·insert 미호출") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)

            shouldThrow<UnsupportedAttachmentTypeException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "evil.html",
                    contentType = "text/html",
                    sizeBytes = 100L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            verify(exactly = 0) { storagePort.put(any(), any(), any(), any()) }
            verify(exactly = 0) { attachmentRepository.insert(any()) }
        }

        it("scan 결과 INFECTED이면 AttachmentInfectedException 던지고 put·insert 미호출 + temp 잔존 0") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            every { scanPort.scan(any()) } returns ScanVerdict.INFECTED

            val tempDir = Path.of(System.getProperty("java.io.tmpdir"))
            val beforeFiles = Files.list(tempDir).use { it.toList().toSet() }

            shouldThrow<AttachmentInfectedException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "virus.pdf",
                    contentType = "application/pdf",
                    sizeBytes = 512L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            // [C6] INFECTED 경로 put/insert 미호출
            verify(exactly = 0) { storagePort.put(any(), any(), any(), any()) }
            verify(exactly = 0) { attachmentRepository.insert(any()) }

            // [C6] INFECTED 경로 temp 잔존 0
            val afterFiles = Files.list(tempDir).use { it.toList().toSet() }
            val leaked = afterFiles - beforeFiles
            leaked shouldBe emptySet()
        }

        it("scan이 AttachmentScanUnavailableException 던지면 그대로 전파하고 put·insert 미호출 + temp 잔존 0") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            every { scanPort.scan(any()) } throws AttachmentScanUnavailableException("clamd 미가용")

            val tempDir = Path.of(System.getProperty("java.io.tmpdir"))
            val beforeFiles = Files.list(tempDir).use { it.toList().toSet() }

            shouldThrow<AttachmentScanUnavailableException> {
                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "doc.pdf",
                    contentType = "application/pdf",
                    sizeBytes = 256L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )
            }

            // [C6] UNAVAILABLE 경로 put/insert 미호출
            verify(exactly = 0) { storagePort.put(any(), any(), any(), any()) }
            verify(exactly = 0) { attachmentRepository.insert(any()) }

            // [C6] UNAVAILABLE 경로 temp 잔존 0
            val afterFiles = Files.list(tempDir).use { it.toList().toSet() }
            val leaked = afterFiles - beforeFiles
            leaked shouldBe emptySet()
        }

        it("insert 실패 시 storagePort.remove 보상 호출 후 예외를 전파하고 temp 잔존 0") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            // [C5] happy-path scan stub 추가
            every { scanPort.scan(any()) } returns ScanVerdict.CLEAN
            justRun { storagePort.put(any(), any(), any(), any()) }
            every { attachmentRepository.insert(any()) } throws RuntimeException("DB error")
            justRun { storagePort.remove(any()) }

            val tempDir = Path.of(System.getProperty("java.io.tmpdir"))
            val beforeFiles = Files.list(tempDir).use { it.toList().toSet() }

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

            // [C6] insert 실패 경로 temp 잔존 0
            val afterFiles = Files.list(tempDir).use { it.toList().toSet() }
            val leaked = afterFiles - beforeFiles
            leaked shouldBe emptySet()
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

        // ── 소유권 게이트 (2026-07-27 신설 — 이전에는 업로더 검사가 0건이었다) ──────

        /**
         * 삭제는 **업로더 ∨ `SOFT_DELETE`** 다 — 댓글 삭제와 **같은 술어**다.
         *
         * 2026-07-27 이전에는 이슈 `UPDATE` 하나만 보고 업로더를 확인하지 않아,
         * `EDIT_ISSUE` 를 가진 사람이면 **누구나 남이 올린 첨부를 지울 수 있었다.**
         * 이슈 자식 엔티티 소유권 정책은 `docs/plan/product/issue-tracking.md §A` 행렬이 정본이며,
         * 거기 없는 술어를 만들면 여섯 번째 정책이 생긴다.
         */
        it("업로더가 아니고 SOFT_DELETE 도 없으면 IssueAccessDeniedException 던진다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            stubPermission(IssuePermission.SOFT_DELETE, false)
            val attachmentId = UUID.randomUUID()
            every { attachmentRepository.findById(attachmentId) } returns
                makeAttachment(uploader = UUID.randomUUID())

            shouldThrow<IssueAccessDeniedException> {
                sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachmentId)
            }

            // 스토리지 제거까지 갔다면 비가역 삭제가 일어난 것이다 — 게이트가 그 앞에 있어야 한다.
            verify(exactly = 0) { storagePort.remove(any()) }
            verify(exactly = 0) { attachmentRepository.deleteById(any()) }
        }

        /** 모더레이션 경로 — 남의 첨부라도 `SOFT_DELETE` 보유자는 지울 수 있다(악성 첨부 대응). */
        it("업로더가 아니어도 SOFT_DELETE 보유자면 삭제된다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            stubPermission(IssuePermission.SOFT_DELETE, true)
            val attachmentId = UUID.randomUUID()
            val attachment = makeAttachment(uploader = UUID.randomUUID())
            every { attachmentRepository.findById(attachmentId) } returns attachment
            justRun { storagePort.remove(attachment.storageKey) }
            every { attachmentRepository.deleteById(attachment.id) } returns true

            sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachmentId)

            verify(exactly = 1) { attachmentRepository.deleteById(attachment.id) }
        }

        /**
         * 대조군 — 게이트가 "전부 거부" 로 무너지지 않았음을 확인한다.
         * `SOFT_DELETE` 가 **없어도** 업로더 본인은 지울 수 있어야 한다.
         */
        it("업로더 본인은 SOFT_DELETE 가 없어도 삭제된다") {
            stubIssueExists()
            stubPermission(IssuePermission.UPDATE, true)
            stubPermission(IssuePermission.SOFT_DELETE, false)
            val attachmentId = UUID.randomUUID()
            val attachment = makeAttachment(uploader = actor.value)
            every { attachmentRepository.findById(attachmentId) } returns attachment
            justRun { storagePort.remove(attachment.storageKey) }
            every { attachmentRepository.deleteById(attachment.id) } returns true

            sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachmentId)

            verify(exactly = 1) { attachmentRepository.deleteById(attachment.id) }
        }

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
            // 삭제는 업로더 ∨ SOFT_DELETE — 이 픽스처는 업로더 본인이라 모더레이터가 아니어도 통과한다.
            stubPermission(IssuePermission.SOFT_DELETE, false)
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
            // 삭제는 업로더 ∨ SOFT_DELETE — 이 픽스처는 업로더 본인이라 모더레이터가 아니어도 통과한다.
            stubPermission(IssuePermission.SOFT_DELETE, false)
            val attachment = makeAttachment()
            every { attachmentRepository.findById(attachment.id) } returns attachment
            every { storagePort.remove(attachment.storageKey) } throws RuntimeException("MinIO 오류")
            every { attachmentRepository.deleteById(attachment.id) } returns true

            // 예외가 전파되지 않아야 함
            sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachment.id)

            verify(exactly = 1) { attachmentRepository.deleteById(attachment.id) }
        }
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ─────────────────────────────────────

    describe("아카이브 잠금 (FR-PJ-04 PR-4 Task 9)") {
        context("upload — 아카이브된 프로젝트") {
            it("checkPermission 통과 후 archiveGuard.checkByIssue 가 ProjectArchivedException 을 던지면 그대로 전파된다") {
                stubIssueExists()
                stubPermission(IssuePermission.UPDATE, true)
                every { archiveGuard.checkByIssue(issueKey) } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> {
                    sut.upload(
                        actor = actor,
                        issueKey = issueKey,
                        filename = "report.pdf",
                        contentType = "application/pdf",
                        sizeBytes = 2048L,
                        input = ByteArrayInputStream(ByteArray(0)),
                    )
                }
                verify(exactly = 0) { storagePort.put(any(), any(), any(), any()) }
            }
        }

        context("upload — 활성 프로젝트 (판별자 baseline)") {
            it("archiveGuard.checkByIssue 가 호출되고 정상 업로드된다") {
                stubIssueExists()
                stubPermission(IssuePermission.UPDATE, true)
                every { scanPort.scan(any()) } returns ScanVerdict.CLEAN
                justRun { storagePort.put(any(), any(), any(), any()) }
                justRun { attachmentRepository.insert(any()) }

                sut.upload(
                    actor = actor,
                    issueKey = issueKey,
                    filename = "report.pdf",
                    contentType = "application/pdf",
                    sizeBytes = 2048L,
                    input = ByteArrayInputStream(ByteArray(0)),
                )

                verify(exactly = 1) { archiveGuard.checkByIssue(issueKey) }
            }
        }

        context("delete — 아카이브된 프로젝트") {
            it("checkPermission 통과 후 archiveGuard.checkByIssue 가 ProjectArchivedException 을 던지면 그대로 전파된다") {
                stubIssueExists()
                stubPermission(IssuePermission.UPDATE, true)
                every { archiveGuard.checkByIssue(issueKey) } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> {
                    sut.delete(actor = actor, issueKey = issueKey, attachmentId = UUID.randomUUID())
                }
                verify(exactly = 0) { storagePort.remove(any()) }
                verify(exactly = 0) { attachmentRepository.deleteById(any()) }
            }
        }

        context("delete — 활성 프로젝트 (판별자 baseline)") {
            it("archiveGuard.checkByIssue 가 호출되고 정상 삭제된다") {
                stubIssueExists()
                stubPermission(IssuePermission.UPDATE, true)
                // 삭제는 업로더 ∨ SOFT_DELETE — 이 픽스처는 업로더 본인이라 모더레이터가 아니어도 통과한다.
                stubPermission(IssuePermission.SOFT_DELETE, false)
                val attachment = makeAttachment()
                every { attachmentRepository.findById(attachment.id) } returns attachment
                justRun { storagePort.remove(attachment.storageKey) }
                every { attachmentRepository.deleteById(attachment.id) } returns true

                sut.delete(actor = actor, issueKey = issueKey, attachmentId = attachment.id)

                verify(exactly = 1) { archiveGuard.checkByIssue(issueKey) }
            }
        }
    }
})

// 이슈 첨부 파일 유스케이스 서비스 — upload/list/download/delete 4동작. MinIO I/O 는 트랜잭션 밖.

package com.bts.issue.attachment.application

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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 이슈 첨부 파일 유스케이스 조율 서비스.
 *
 * ## 트랜잭션 경계 (CONCERN-A)
 * MinIO I/O(put/get/remove)가 포함된 [upload]/[download]/[delete] 메서드에는
 * 메서드 전체 @Transactional 을 적용하지 않는다 — 100MB 업로드/다운로드 동안
 * DB 커넥션을 점유하면 커넥션 풀이 고갈된다.
 *
 * DB 작업([AttachmentRepository.insert], [AttachmentRepository.deleteById])은
 * 각각 단일 statement 이므로 자체적으로 원자적이다.
 * Repository 각 메서드가 자체 @Transactional 을 선언한다.
 *
 * Detekt 트랜잭션 강제 룰 확인 결과:
 * - 테스트 디렉토리 및 빌드 설정에서 `TransactionalServiceArchTest` / `@TransactionalAware` 패턴 미존재.
 * - `IssueTypeApplicationService::class.java.getAnnotation(Transactional::class.java)` 형태의
 *   수동 어노테이션 검증 테스트가 일부 존재하나, issue-tracking 전체에 강제하는 ArchUnit 룰은 없음.
 * - 따라서 MinIO I/O 메서드에 @Transactional 생략이 정당하다.
 *
 * ## 권한 검증 순서
 * actor 의 존재 probe 방지를 위해 권한 검증을 이슈 조회와 동시 또는 선행한다.
 * 상세: 이슈 미존재 여부를 권한 없는 요청자에게 노출하지 않기 위해
 * [IssuePermissionResolver.hasPermission] 을 먼저 호출한 뒤 이슈를 조회한다.
 *
 * @param storagePort 오브젝트 스토리지 outbound port (MinIO 어댑터).
 * @param attachmentRepository 첨부 메타데이터 저장소.
 * @param permissionResolver 이슈 권한 판정 port.
 * @param issueRepository 이슈 키 → ID 변환에 사용 (View 권한 검증 미포함 — 첨부 서비스가 직접 권한 처리).
 * @param scanPort 바이러스 스캔 outbound port. fail-closed — 미가용 시 업로드 거부.
 * @param clock 첨부 생성 시각 결정. 테스트에서 고정 시각 주입 가능.
 */
@Service
// 보상 삭제/best-effort 정리에서 모든 예외를 잡아 로그/전파하기 위해 generic catch 사용(기존 관례 동일).
@Suppress("TooGenericExceptionCaught")
class IssueAttachmentService(
    private val storagePort: AttachmentStoragePort,
    private val attachmentRepository: AttachmentRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val issueRepository: IssueRepository,
    private val scanPort: VirusScanPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 첨부 파일을 업로드한다.
     *
     * ## 스캔 게이트 (fail-closed)
     *
     * inbound 스트림을 임시파일에 1회 복사한 뒤 [scanPort] 로 바이러스 스캔을 수행한다.
     * INFECTED → [AttachmentInfectedException], 스캐너 미가용 → [AttachmentScanUnavailableException].
     * 두 경우 모두 MinIO put 및 DB insert 가 호출되지 않는다.
     *
     * ## 임시파일 생명주기
     *
     * 임시파일은 `Files.createTempFile` 직후 `try/finally` 로 감싸
     * INFECTED / UNAVAILABLE / put 실패 / insert 실패 / 정상 성공 모든 경로에서 삭제된다.
     * 100 MB 파일을 메모리에 적재하지 않기 위해 디스크 임시파일을 사용한다.
     *
     * ## 실행 순서
     *
     * 권한 검증 → 타입 검증 → 이슈 조회 → 임시파일 복사 → ClamAV 스캔 → storagePort.put → repository.insert.
     * insert 실패 시 storagePort.remove 로 고아 객체를 보상 삭제한다(기존 관례 유지).
     *
     * @param actor 업로드를 수행하는 행위자.
     * @param issueKey 첨부할 이슈 키.
     * @param filename 원본 파일명.
     * @param contentType MIME 타입.
     * @param sizeBytes 파일 크기(바이트).
     * @param input 업로드 바이트 스트림. 호출자가 close 책임.
     * @return 저장된 [Attachment] 메타데이터.
     * @throws IssueAccessDeniedException UPDATE 권한 미보유 시.
     * @throws UnsupportedAttachmentTypeException 허용되지 않은 MIME/확장자 시([AttachmentTypePolicy]).
     * @throws IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시.
     * @throws AttachmentInfectedException 바이러스 스캔에서 악성코드가 탐지된 경우.
     * @throws AttachmentScanUnavailableException ClamAV 미가용·타임아웃·미상 응답 (fail-closed).
     */
    @Suppress("LongParameterList")
    fun upload(
        actor: ActorId,
        issueKey: IssueKey,
        filename: String,
        contentType: String,
        sizeBytes: Long,
        input: InputStream,
        createdAt: Instant? = null,
        uploadedBy: UUID? = null,
    ): Attachment {
        checkPermission(actor, IssuePermission.UPDATE, issueKey)
        // 허용 타입 검증 — 권한 확인 직후, MinIO put·DB insert 이전(고아 객체·불필요 I/O 방지).
        if (!AttachmentTypePolicy.isAllowed(contentType, filename)) {
            throw UnsupportedAttachmentTypeException(contentType, filename)
        }
        val issueId = resolveIssueId(issueKey)
        val attachmentId = UUID.randomUUID()
        val storageKey = "issues/$issueId/$attachmentId"
        val attachment =
            Attachment(
                id = attachmentId,
                issueId = issueId,
                filename = filename,
                contentType = contentType,
                sizeBytes = sizeBytes,
                storageKey = storageKey,
                uploadedBy = uploadedBy ?: actor.value,
                createdAt = createdAt ?: clock.instant(),
            )

        // inbound 스트림을 임시파일로 복사 — finally 로 모든 경로에서 삭제 보장.
        val temp = writeTempFile(input)
        try {
            scanAndGuard(temp, filename)
            // 임시파일 스트림을 use 로 즉시 닫는다 — MinIO SDK 는 입력 스트림을 close 하지 않으므로
            // 여기서 닫지 않으면 FD 누수 + finally 의 임시파일 삭제가 (Windows 등) 열린 핸들로 실패한다.
            temp.toFile().inputStream().use { stream ->
                storagePort.put(storageKey, stream, sizeBytes, contentType)
            }
            try {
                attachmentRepository.insert(attachment)
            } catch (e: Exception) {
                log.warn("insert 실패 — storageKey={} 보상 삭제 시도. cause={}", storageKey, e.message)
                try {
                    storagePort.remove(storageKey)
                } catch (removeEx: Exception) {
                    log.error(
                        "보상 삭제 실패 — storageKey={} 고아 객체 잔존. cause={}",
                        storageKey,
                        removeEx.message,
                    )
                }
                throw e
            }
        } finally {
            deleteTempFile(temp)
        }

        return attachment
    }

    /**
     * 이슈의 첨부 파일 목록을 반환한다.
     *
     * @param actor 목록을 조회하는 행위자.
     * @param issueKey 조회할 이슈 키.
     * @return [Attachment] 목록 (created_at 내림차순).
     * @throws IssueAccessDeniedException VIEW 권한 미보유 시.
     * @throws IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시.
     */
    fun list(
        actor: ActorId,
        issueKey: IssueKey,
    ): List<Attachment> {
        checkPermission(actor, IssuePermission.VIEW, issueKey)
        val issueId = resolveIssueId(issueKey)
        return attachmentRepository.findByIssueId(issueId)
    }

    /**
     * 첨부 파일 메타데이터와 바이트 스트림을 반환한다.
     *
     * [AttachmentDownloadResult.stream] 은 컨트롤러가 close 해야 한다.
     *
     * @param actor 다운로드를 수행하는 행위자.
     * @param issueKey 소속 이슈 키.
     * @param attachmentId 다운로드할 첨부 UUID.
     * @return 메타데이터 + 스트림 묶음 [AttachmentDownloadResult].
     * @throws IssueAccessDeniedException VIEW 권한 미보유 시.
     * @throws IssueNotFoundException 첨부 미존재 또는 교차 이슈(issueId 불일치) 시.
     */
    fun download(
        actor: ActorId,
        issueKey: IssueKey,
        attachmentId: UUID,
    ): AttachmentDownloadResult {
        checkPermission(actor, IssuePermission.VIEW, issueKey)
        val issueId = resolveIssueId(issueKey)
        val attachment = findAttachmentForIssue(attachmentId, issueId, issueKey)
        val stream = storagePort.get(attachment.storageKey)
        return AttachmentDownloadResult(attachment = attachment, stream = stream)
    }

    /**
     * 첨부 파일을 삭제한다.
     *
     * storagePort.remove 실패는 로그 후 진행한다(고아 바이너리보다 좀비 메타가 더 나쁨).
     *
     * @param actor 삭제를 수행하는 행위자.
     * @param issueKey 소속 이슈 키.
     * @param attachmentId 삭제할 첨부 UUID.
     * @throws IssueAccessDeniedException UPDATE 권한 미보유 시.
     * @throws IssueNotFoundException 첨부 미존재 또는 교차 이슈(issueId 불일치) 시.
     */
    fun delete(
        actor: ActorId,
        issueKey: IssueKey,
        attachmentId: UUID,
    ) {
        checkPermission(actor, IssuePermission.UPDATE, issueKey)
        val issueId = resolveIssueId(issueKey)
        val attachment = findAttachmentForIssue(attachmentId, issueId, issueKey)

        try {
            storagePort.remove(attachment.storageKey)
        } catch (e: Exception) {
            log.error(
                "MinIO remove 실패 — storageKey={} 고아 바이너리 잔존. cause={}",
                attachment.storageKey,
                e.message,
            )
        }

        attachmentRepository.deleteById(attachment.id)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * inbound [InputStream] 을 시스템 임시 디렉토리의 임시파일로 복사한다.
     *
     * 대용량 파일을 메모리에 적재하지 않기 위해 디스크 임시파일을 사용한다.
     * 호출자는 반드시 [deleteTempFile] 로 삭제해야 한다.
     */
    private fun writeTempFile(input: InputStream): Path {
        val temp = Files.createTempFile("bts-attachment-", ".tmp")
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
        return temp
    }

    /**
     * 임시파일을 삭제한다. 삭제 실패는 로그 후 무시한다(이미 삭제됐거나 경쟁 조건).
     */
    private fun deleteTempFile(temp: Path) {
        try {
            Files.deleteIfExists(temp)
        } catch (e: Exception) {
            log.warn("임시파일 삭제 실패 — path={} cause={}", temp, e.message)
        }
    }

    /**
     * [scanPort] 로 바이러스 스캔을 수행하고 [ScanVerdict.INFECTED] 이면 [AttachmentInfectedException] 을 던진다.
     *
     * [AttachmentScanUnavailableException] 은 스캐너 미가용을 나타내므로 그대로 전파한다 (fail-closed).
     */
    private fun scanAndGuard(
        temp: Path,
        filename: String,
    ) {
        val verdict = temp.toFile().inputStream().use { scanPort.scan(it) }
        if (verdict == ScanVerdict.INFECTED) {
            // filename 값은 HTTP 응답에 노출하지 않는다 — 로그에도 원본값 출력 금지(보안 정책).
            log.info("바이러스 스캔 결과: INFECTED — 업로드 차단")
            throw AttachmentInfectedException(filename)
        }
    }

    /**
     * 권한이 없으면 [IssueAccessDeniedException] 을 던진다.
     *
     * 이슈 존재 probe 방지를 위해 이슈 조회 전에 호출한다.
     */
    private fun checkPermission(
        actor: ActorId,
        permission: IssuePermission,
        issueKey: IssueKey,
    ) {
        val allowed =
            permissionResolver.hasPermission(
                actorId = actor.value,
                permission = permission,
                scope = IssueScope.Issue(issueKey.value),
            )
        if (!allowed) {
            throw IssueAccessDeniedException(actor, permission, IssueScope.Issue(issueKey.value))
        }
    }

    /**
     * 이슈 키 → UUID 변환. 미존재/소프트 삭제 시 [IssueNotFoundException].
     */
    private fun resolveIssueId(issueKey: IssueKey): UUID {
        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        return issue.id.value
    }

    /**
     * 첨부를 조회하고 소속 이슈 UUID 를 검증한다.
     *
     * 첨부 미존재 또는 issueId 불일치(교차 이슈) 시 [IssueNotFoundException] 을 던진다.
     * 교차 이슈 존재 여부를 노출하지 않기 위해 404 를 사용한다(존재 probe 방지).
     */
    private fun findAttachmentForIssue(
        attachmentId: UUID,
        issueId: UUID,
        issueKey: IssueKey,
    ): Attachment {
        val attachment = attachmentRepository.findById(attachmentId) ?: throw IssueNotFoundException(issueKey)
        if (attachment.issueId != issueId) {
            throw IssueNotFoundException(issueKey)
        }
        return attachment
    }
}

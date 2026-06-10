// 버전 CRUD ApplicationService — 권한·프로젝트 검증, 도메인 경유, repo 위임

package com.bts.issue.version.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.version.domain.DuplicateVersionNameException
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionAccessDeniedException
import com.bts.issue.version.domain.VersionNotFoundException
import com.bts.issue.version.domain.VersionProjectNotFoundException
import com.bts.issue.version.domain.VersionStatus
import com.bts.issue.version.domain.VersionTransitionNotAllowedException
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 23505 unique_violation SQLState 상수. */
private const val SQL_STATE_UNIQUE_VIOLATION = "23505"

/**
 * 버전 CRUD ApplicationService.
 *
 * 권한 검증, 프로젝트 존재 확인, 도메인 Aggregate 경유, Repository 위임까지 한 흐름을 조율한다.
 * 버전은 사용자(리드) 참조가 없으므로 UserLookupPort 주입 없음.
 *
 * **도메인 우회 금지 (메모리 patch-merge-domain-bypass).**
 * 영속 값은 반드시 도메인 메서드(rename/changeDescription/changeDates/softDelete)의
 * 산출물에서 가져온다. raw 입력을 repo 로 직접 전달하지 않는다.
 *
 * **트랜잭션 경계.**
 * 클래스 레벨 `@Transactional` 이 기본(읽기/쓰기 모두). 읽기 전용 메서드는
 * `@Transactional(readOnly = true)` 를 오버라이드한다.
 *
 * 공개 메서드 7개 + private 헬퍼 9개 = 16개. TooManyFunctions 임계값 11 초과이나
 * 명세 요구 메서드 수로 파일 분리는 과도 — Suppress 처리.
 */
@Suppress("TooManyFunctions")
@Service
@Transactional
class VersionApplicationService(
    private val permissionResolver: VersionPermissionResolver,
    private val projectLookup: ProjectLookup,
    private val repo: VersionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 버전을 생성한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [VersionProjectNotFoundException].
     * 2. CREATE 권한 검증 — 거부 시 [VersionAccessDeniedException].
     * 3. 도메인 [Version.create] 호출.
     * 4. [VersionRepository.insert] — 23505 위반 시 [DuplicateVersionNameException].
     *
     * @param actorId 생성 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param name 버전 이름. trim 후 1~255자.
     * @param description 선택적 설명. null 허용.
     * @param startDate 버전 시작일. null 이면 미지정.
     * @param releaseDate 버전 릴리스 예정일. null 이면 미지정.
     * @return 생성된 [Version].
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionAccessDeniedException 권한이 없을 때.
     * @throws DuplicateVersionNameException 동일 프로젝트 내 이름이 중복될 때.
     *
     * 6개 파라미터(이름·설명·시작일·릴리스일·프로젝트·행위자)는 명세 요구사항으로 축소 불가 — LongParameterList Suppress.
     */
    @Suppress("LongParameterList")
    fun create(
        actorId: UUID,
        projectIdOrKey: String,
        name: String,
        description: String?,
        startDate: LocalDate?,
        releaseDate: LocalDate?,
    ): Version {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, VersionPermission.CREATE, projectId)

        val domain =
            Version.create(
                projectId = projectId,
                name = name,
                description = description,
                startDate = startDate,
                releaseDate = releaseDate,
            )
        return tryInsert(domain, name)
    }

    /**
     * 버전의 이름과 설명을 수정한다.
     *
     * name / description 각각 null 이면 기존 값 유지 (3-state PATCH).
     * 날짜 변경은 [changeDates] 전용 메서드를 사용한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [VersionProjectNotFoundException].
     * 2. UPDATE 권한 검증.
     * 3. 버전 존재 확인 — 미존재 시 [VersionNotFoundException].
     * 4. 도메인 [Version.rename] / [Version.changeDescription] 경유.
     * 5. [VersionRepository.update] 위임.
     *
     * @param actorId 수정 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param versionId 수정할 버전 UUID.
     * @param name 새 이름. null 이면 기존 유지.
     * @param description 새 설명. null 이면 기존 유지.
     * @return 수정된 [Version].
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionAccessDeniedException 권한이 없을 때.
     * @throws VersionNotFoundException 버전이 존재하지 않을 때.
     * @throws DuplicateVersionNameException 동일 프로젝트 내 이름이 중복될 때 (rename 시에만 발생 가능).
     */
    fun update(
        actorId: UUID,
        projectIdOrKey: String,
        versionId: UUID,
        name: String?,
        description: String?,
    ): Version {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, VersionPermission.UPDATE, projectId)
        val existing = findActiveVersion(versionId, projectId)

        val updated = applyNameAndDescription(existing, name, description)
        log.info("version_updated id={} projectId={} actor={}", versionId, projectId, actorId)
        return tryUpdate(updated, name ?: existing.name)
    }

    /**
     * 버전 날짜(startDate, releaseDate)를 변경하거나 해제한다.
     *
     * null 전달 시 해당 날짜 해제 (지정 / 해제 2-state).
     * 이름/설명 변경은 [update] 를 사용한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [VersionProjectNotFoundException].
     * 2. UPDATE 권한 검증.
     * 3. 버전 존재 확인 — 미존재 시 [VersionNotFoundException].
     * 4. 도메인 [Version.changeDates] 경유.
     * 5. [VersionRepository.update] 위임.
     *
     * @param actorId 수정 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param versionId 수정할 버전 UUID.
     * @param startDate 새 시작일. null 이면 해제.
     * @param releaseDate 새 릴리스 예정일. null 이면 해제.
     * @return 수정된 [Version].
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionAccessDeniedException 권한이 없을 때.
     * @throws VersionNotFoundException 버전이 존재하지 않을 때.
     */
    fun changeDates(
        actorId: UUID,
        projectIdOrKey: String,
        versionId: UUID,
        startDate: LocalDate?,
        releaseDate: LocalDate?,
    ): Version {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, VersionPermission.UPDATE, projectId)
        val existing = findActiveVersion(versionId, projectId)

        val updated = existing.changeDates(startDate, releaseDate)
        log.info("version_dates_changed id={} projectId={} actor={}", versionId, projectId, actorId)
        return repo.update(updated)
    }

    /**
     * 버전을 소프트 삭제한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [VersionProjectNotFoundException].
     * 2. DELETE 권한 검증.
     * 3. 버전 존재 확인 — 미존재 시 [VersionNotFoundException].
     * 4. [VersionRepository.softDelete] 위임.
     *
     * @param actorId 삭제 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param versionId 삭제할 버전 UUID.
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionAccessDeniedException 권한이 없을 때.
     * @throws VersionNotFoundException 버전이 존재하지 않을 때.
     */
    fun delete(
        actorId: UUID,
        projectIdOrKey: String,
        versionId: UUID,
    ) {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, VersionPermission.DELETE, projectId)
        val existing = findActiveVersion(versionId, projectId)
        assertNotArchivedForDelete(existing)
        repo.softDelete(versionId, projectId)
        log.info("version_deleted id={} projectId={} actor={}", versionId, projectId, actorId)
    }

    /**
     * 버전의 상태를 전이한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [VersionProjectNotFoundException].
     * 2. UPDATE 권한 검증 — 거부 시 [VersionAccessDeniedException].
     * 3. 버전 존재 확인 — 미존재 시 [VersionNotFoundException].
     * 4. 도메인 전이 메서드 호출 — 불허 전이 시 [VersionTransitionNotAllowedException].
     * 5. [VersionRepository.update] 위임.
     *
     * 전이 그래프.
     * ```
     * UNRELEASED ──release──▶ RELEASED      (releasedAt = Instant.now(clock))
     * RELEASED   ─unrelease─▶ UNRELEASED    (releasedAt = null)
     * UNRELEASED ──archive──▶ ARCHIVED      (releasedAt 유지)
     * RELEASED   ──archive──▶ ARCHIVED      (releasedAt 유지)
     * ARCHIVED   ─unarchive─▶ UNRELEASED    (releasedAt = null)
     * ```
     *
     * @param actorId 전이 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param versionId 전이할 버전 UUID.
     * @param target 목표 [VersionStatus].
     * @return 전이 후 [Version].
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionAccessDeniedException 권한이 없을 때.
     * @throws VersionNotFoundException 버전이 존재하지 않을 때.
     * @throws VersionTransitionNotAllowedException 전이 그래프에 없는 전이 또는 self-transition 시.
     */
    fun changeStatus(
        actorId: UUID,
        projectIdOrKey: String,
        versionId: UUID,
        target: VersionStatus,
    ): Version {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, VersionPermission.UPDATE, projectId)
        val existing = findActiveVersion(versionId, projectId)
        val updated = applyTransition(existing, target)
        log.info(
            "version_status_changed id={} projectId={} actor={} status={}",
            versionId,
            projectId,
            actorId,
            target,
        )
        return repo.update(updated)
    }

    /**
     * 단건 버전을 조회한다.
     *
     * READ 는 권한 게이트 없음 (Jira 동일 정책).
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param versionId 조회할 버전 UUID.
     * @return 활성 [Version].
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionNotFoundException 버전이 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun getById(
        actorId: UUID,
        projectIdOrKey: String,
        versionId: UUID,
    ): Version {
        val projectId = resolveProject(projectIdOrKey)
        log.debug("version_get_by_id id={} projectId={} actor={}", versionId, projectId, actorId)
        return findActiveVersion(versionId, projectId)
    }

    /**
     * 프로젝트 소속 활성 버전 목록을 조회한다.
     *
     * READ 는 권한 게이트 없음 (Jira 동일 정책). name 오름차순 정렬은 repo 에서 처리.
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @return 활성 [Version] 리스트. 없으면 빈 리스트.
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun listByProject(
        actorId: UUID,
        projectIdOrKey: String,
    ): List<Version> {
        val projectId = resolveProject(projectIdOrKey)
        log.debug("version_list_by_project projectId={} actor={}", projectId, actorId)
        return repo.findByProject(projectId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     * 미존재 시 [VersionProjectNotFoundException] 을 던진다.
     */
    private fun resolveProject(projectIdOrKey: String): UUID =
        projectLookup.resolve(projectIdOrKey)
            ?: throw VersionProjectNotFoundException(projectIdOrKey)

    /**
     * 행위자의 권한을 검증한다. 거부 시 [VersionAccessDeniedException] 을 던진다.
     */
    private fun assertPermission(
        actorId: UUID,
        permission: VersionPermission,
        projectId: UUID,
    ) {
        if (!permissionResolver.hasPermission(actorId, permission, projectId)) {
            throw VersionAccessDeniedException(actorId, permission, projectId)
        }
    }

    /**
     * 활성 버전을 조회한다. 미존재 시 [VersionNotFoundException] 을 던진다.
     */
    private fun findActiveVersion(
        versionId: UUID,
        projectId: UUID,
    ): Version =
        repo.findById(versionId, projectId)
            ?: throw VersionNotFoundException(versionId)

    /**
     * ARCHIVED 버전 삭제 시도를 서비스 레벨에서 차단한다.
     *
     * 기존 [delete] 는 [VersionRepository.softDelete] 로 직행하여 도메인의 ARCHIVED 가드를 우회한다.
     * (메모리 patch-merge-domain-bypass 참조). 도메인 softDelete 의 ARCHIVED 가드는 안전망으로 유지하되,
     * 서비스 레벨에서도 명시적으로 거부한다.
     *
     * @param version 삭제 대상 버전.
     * @throws VersionTransitionNotAllowedException ARCHIVED 버전 삭제 시도 시.
     */
    private fun assertNotArchivedForDelete(version: Version) {
        if (version.status == VersionStatus.ARCHIVED) {
            throw VersionTransitionNotAllowedException(
                "Cannot delete an ARCHIVED version (id=${version.id}). Unarchive first.",
            )
        }
    }

    /**
     * target [VersionStatus] 에 따라 도메인 전이 메서드를 선택해 적용한다.
     *
     * 허용 전이 그래프는 도메인 메서드가 검증하므로 불허 경우는 [VersionTransitionNotAllowedException].
     *
     * @param version 현재 상태의 [Version].
     * @param target 목표 상태.
     * @return 전이 후 새 [Version] 인스턴스.
     */
    private fun applyTransition(
        version: Version,
        target: VersionStatus,
    ): Version =
        when (target) {
            VersionStatus.RELEASED -> version.release(Instant.now(clock))
            VersionStatus.UNRELEASED -> {
                if (version.status == VersionStatus.ARCHIVED) {
                    version.unarchive()
                } else {
                    version.unrelease()
                }
            }
            VersionStatus.ARCHIVED -> version.archive()
        }

    /**
     * name/description null-safe 도메인 메서드 적용 — null 이면 기존 값 유지 (3-state PATCH).
     *
     * 도메인 메서드(rename/changeDescription)를 반드시 경유해 불변식을 보장한다.
     */
    private fun applyNameAndDescription(
        version: Version,
        name: String?,
        description: String?,
    ): Version {
        var result = version
        if (name != null) {
            result = result.rename(name)
        }
        if (description != null) {
            result = result.changeDescription(description)
        }
        return result
    }

    /**
     * [VersionRepository.insert] 를 호출하고, 23505 SQLState 위반은
     * [DuplicateVersionNameException] 으로 변환한다.
     *
     * jOOQ 는 Spring PersistenceExceptionTranslator 가 개입하지 않을 경우
     * [DataIntegrityViolationException] 대신 [org.jooq.exception.IntegrityConstraintViolationException]
     * 을 직접 던진다. 두 케이스를 모두 처리한다.
     */
    private fun tryInsert(
        version: Version,
        name: String,
    ): Version =
        try {
            repo.insert(version)
        } catch (ex: DataIntegrityViolationException) {
            translateUniqueViolation(ex, name)
        } catch (ex: org.jooq.exception.IntegrityConstraintViolationException) {
            translateUniqueViolation(ex, name)
        }

    /**
     * [VersionRepository.update] 를 호출하고, 23505 SQLState 위반은
     * [DuplicateVersionNameException] 으로 변환한다.
     *
     * rename 없이 update 하는 경우에는 유니크 충돌이 발생할 수 없지만,
     * 방어적으로 동일한 변환 로직을 적용해 create 와 대칭성을 보장한다.
     */
    private fun tryUpdate(
        version: Version,
        name: String,
    ): Version =
        try {
            repo.update(version)
        } catch (ex: DataIntegrityViolationException) {
            translateUniqueViolation(ex, name)
        } catch (ex: org.jooq.exception.IntegrityConstraintViolationException) {
            translateUniqueViolation(ex, name)
        }

    /**
     * 제약 위반 예외가 23505 unique_violation 에서 비롯됐으면 [DuplicateVersionNameException] 으로
     * 변환한다. 그렇지 않으면 원 예외를 그대로 re-throw 한다.
     */
    @Suppress("ThrowsCount")
    private fun translateUniqueViolation(
        ex: RuntimeException,
        name: String,
    ): Nothing {
        val sqlEx =
            generateSequence(ex.cause) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .firstOrNull()
        if (sqlEx?.sqlState == SQL_STATE_UNIQUE_VIOLATION) {
            log.warn("Duplicate version name={}", name)
            throw DuplicateVersionNameException(name)
        }
        throw ex
    }
}

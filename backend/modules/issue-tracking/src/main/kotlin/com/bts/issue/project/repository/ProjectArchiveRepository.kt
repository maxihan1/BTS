// 프로젝트 archived_at 컬럼 UPDATE 전담 jOOQ Repository (FR-PJ-04 PR-4 Task 5)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ProjectArchiveRepository.archive]/[ProjectArchiveRepository.unarchive] 갱신 후 결과 행.
 *
 * @property key 갱신 대상 프로젝트의 key.
 * @property archivedAt 갱신 후 archived_at 값. archive 는 non-null, unarchive 는 항상 null.
 */
data class ProjectArchiveRow(
    val key: String,
    val archivedAt: Instant?,
)

/**
 * `projects.archived_at` 컬럼의 UPDATE 를 담당하는 Repository.
 *
 * [com.bts.issue.project.archive.ProjectArchiveService] 가 이 Repository 를 통해 DB 에 접근하며,
 * jOOQ 를 직접 import 하지 않는다(ArchUnit 룰 2, hexagonal 경계).
 *
 * **deleted_at IS NULL 필터** — [ProjectSettingsRepository.updateName]
 * 선례와 통일한다. 소프트삭제된 프로젝트는 아카이브/아카이브 해제 대상에서 제외한다.
 *
 * **archived_at 단독 판정과의 관계** — 이 Repository 는 archived_at 을 **쓰는** 쪽이고,
 * [com.bts.issue.project.archive.repository.ProjectArchiveStateRepository] 는 archived_at 을
 * **읽어 판정하는**(단독 판정, deleted_at 미참조) 쪽이다. 두 Repository 는 서로를 참조하지 않는다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class ProjectArchiveRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트를 아카이브한다 — `archived_at` 을 설정한다.
     *
     * 이미 아카이브된 프로젝트에 다시 호출해도 무조건 성공(EC-2 — 200, 409 아님)한다. 재호출은
     * `archived_at` 을 새 값으로 덮어쓴다(별도 "최초 시각 보존" 정책은 두지 않는다 — 스펙 EC-2 는
     * HTTP 상태 코드에 대한 요구사항이지 DB 값 보존에 대한 요구사항이 아니다).
     *
     * @param projectId 갱신 대상 프로젝트 UUID.
     * @param archivedAt 아카이브 시각([java.time.Clock] 주입 — PJ4-5, `Instant.now()` 직접 호출 금지).
     * @return 갱신 후 (key, archivedAt) 행. 미존재·소프트삭제 시 null(TOCTOU 재확인용 — 상위가 404 처리).
     */
    @Transactional
    fun archive(
        projectId: UUID,
        archivedAt: Instant,
    ): ProjectArchiveRow? {
        log.debug("ProjectArchiveRepository.archive projectId={} archivedAt={}", projectId, archivedAt)
        val record =
            dsl
                .update(PROJECTS)
                .set(PROJECTS.ARCHIVED_AT, OffsetDateTime.ofInstant(archivedAt, ZoneOffset.UTC))
                .where(PROJECTS.ID.eq(projectId))
                .and(PROJECTS.DELETED_AT.isNull)
                .returning(PROJECTS.KEY, PROJECTS.ARCHIVED_AT)
                .fetchOne()
        return record?.let { ProjectArchiveRow(it.key, it.archivedAt?.toInstant()) }
    }

    /**
     * 프로젝트의 아카이브를 해제한다 — `archived_at` 을 NULL 로 되돌린다.
     *
     * 이미 활성인 프로젝트에 다시 호출해도 무조건 성공(EC-2 대칭 — 200)한다. 이 작업은
     * [com.bts.issue.project.archive.ProjectArchiveGuard] 잠금 대상이 아니다(D-UNARCHIVE — 아카이브
     * 상태에서도 이 메서드는 정상 동작해야 한다).
     *
     * @param projectId 갱신 대상 프로젝트 UUID.
     * @return 갱신 후 (key, archivedAt=null) 행. 미존재·소프트삭제 시 null(TOCTOU 재확인용).
     */
    @Transactional
    fun unarchive(projectId: UUID): ProjectArchiveRow? {
        log.debug("ProjectArchiveRepository.unarchive projectId={}", projectId)
        val record =
            dsl
                .update(PROJECTS)
                .set(PROJECTS.ARCHIVED_AT, null as OffsetDateTime?)
                .where(PROJECTS.ID.eq(projectId))
                .and(PROJECTS.DELETED_AT.isNull)
                .returning(PROJECTS.KEY, PROJECTS.ARCHIVED_AT)
                .fetchOne()
        return record?.let { ProjectArchiveRow(it.key, it.archivedAt?.toInstant()) }
    }
}

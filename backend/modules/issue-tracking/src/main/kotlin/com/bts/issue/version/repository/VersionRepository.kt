// VersionRepository — versions 테이블 jOOQ DSL 접근. DATA.md §5, §6, §3 준수.
package com.bts.issue.version.repository

import com.bts.issue.jooq.tables.records.VersionsRecord
import com.bts.issue.jooq.tables.references.VERSIONS
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionStatus
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 버전 Repository.
 *
 * jOOQ DSLContext 를 통해 `versions` 테이블에 접근한다.
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * - [insert] — 새 버전을 삽입하고 DB 생성 값(id, createdAt, updatedAt)을 포함한 [Version] 반환.
 * - [findById] — 버전 id + project_id 소속 + 활성(deleted_at IS NULL) 조건으로 조회.
 * - [findByProject] — 프로젝트 소속 활성 버전을 name 오름차순으로 반환.
 * - [update] — name, description, startDate, releaseDate, status, releasedAt 을 갱신하고 [Version] 반환.
 * - [softDelete] — deleted_at 를 현재 UTC 시각으로 설정. 물리 삭제 금지 (DATA.md §3).
 *
 * 동시 생성 race(23505 중복키 오류) 처리는 상위 서비스/컨트롤러 책임.
 * Repository 는 부분 유니크 인덱스 위반을 DataAccessException 으로 그대로 전파한다.
 */
@Repository
class VersionRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 버전을 `versions` 테이블에 삽입하고 DB 생성 값이 채워진 [Version] 을 반환한다.
     *
     * 부분 유니크 인덱스 전제 — `ux_versions_project_id_name_active (WHERE deleted_at IS NULL)`.
     * 소프트 삭제된 동일 이름은 재삽입 허용된다.
     *
     * @param version DB 저장 전 [Version]. id 는 null 이어야 한다.
     * @return DB 생성 id / createdAt / updatedAt 을 포함한 [Version].
     */
    @Transactional
    fun insert(version: Version): Version {
        log.debug("Inserting version name={} projectId={}", version.name, version.projectId)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val record =
            dsl.insertInto(VERSIONS)
                .set(VERSIONS.PROJECT_ID, version.projectId)
                .set(VERSIONS.NAME, version.name)
                .set(VERSIONS.DESCRIPTION, version.description)
                .set(VERSIONS.START_DATE, version.startDate)
                .set(VERSIONS.RELEASE_DATE, version.releaseDate)
                .set(VERSIONS.STATUS, version.status.name)
                .set(VERSIONS.RELEASED_AT, version.releasedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
                .set(VERSIONS.CREATED_AT, now)
                .set(VERSIONS.UPDATED_AT, now)
                .returning()
                .fetchOne()
                ?: error("insert returning() returned null for name=${version.name}")
        return toVersion(record)
    }

    /**
     * 버전 id + project_id 소속 + 활성(deleted_at IS NULL) 조건으로 단건 조회한다.
     *
     * 다른 프로젝트 id 로 접근하면 null 을 반환한다.
     *
     * @param id 조회할 버전 UUID.
     * @param projectId 소속 프로젝트 UUID. id 가 실재해도 소속이 다르면 null 반환.
     * @return 활성 [Version], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(
        id: UUID,
        projectId: UUID,
    ): Version? =
        dsl.selectFrom(VERSIONS)
            .where(VERSIONS.ID.eq(id))
            .and(VERSIONS.PROJECT_ID.eq(projectId))
            .and(VERSIONS.DELETED_AT.isNull)
            .fetchOne()
            ?.let(::toVersion)

    /**
     * 프로젝트 소속 활성 버전을 name 오름차순으로 반환한다.
     *
     * `deleted_at IS NULL` 필터 자동 적용.
     *
     * @param projectId 조회할 프로젝트 UUID.
     * @return 활성 [Version] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByProject(projectId: UUID): List<Version> =
        dsl.selectFrom(VERSIONS)
            .where(VERSIONS.PROJECT_ID.eq(projectId))
            .and(VERSIONS.DELETED_AT.isNull)
            .orderBy(VERSIONS.NAME.asc())
            .fetch()
            .map(::toVersion)

    /**
     * 버전 정보(name, description, startDate, releaseDate)를 갱신하고 갱신된 [Version] 을 반환한다.
     *
     * [version.id] 가 null 이면 [IllegalArgumentException] 을 던진다.
     *
     * @param version 갱신할 값이 채워진 [Version]. [Version.id] 는 non-null 이어야 한다.
     * @return 전달받은 [version] 를 그대로 반환한다(DB 재조회 안 함). 서비스가 도메인 메서드로
     *   만든 객체와 영속 상태가 일치하므로 추가 조회는 불필요하다.
     */
    @Transactional
    fun update(version: Version): Version {
        val id = requireNotNull(version.id) { "version.id must not be null for update" }
        log.debug("Updating version id={} projectId={}", id, version.projectId)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl.update(VERSIONS)
            .set(VERSIONS.NAME, version.name)
            .set(VERSIONS.DESCRIPTION, version.description)
            .set(VERSIONS.START_DATE, version.startDate)
            .set(VERSIONS.RELEASE_DATE, version.releaseDate)
            .set(VERSIONS.STATUS, version.status.name)
            .set(VERSIONS.RELEASED_AT, version.releasedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
            .set(VERSIONS.UPDATED_AT, now)
            .where(VERSIONS.ID.eq(id))
            .and(VERSIONS.PROJECT_ID.eq(version.projectId))
            .and(VERSIONS.DELETED_AT.isNull)
            .execute()
        return version
    }

    /**
     * 버전을 소프트 삭제한다.
     *
     * `deleted_at` 를 현재 UTC 시각으로 설정한다.
     * 이미 삭제된 버전은 영향 행 0 반환 (멱등 처리).
     * 물리 삭제(DELETE) 금지 — DATA.md §3.
     *
     * @param id 삭제할 버전 UUID.
     * @param projectId 소속 프로젝트 UUID. 소속이 다르면 아무 행도 변경되지 않는다.
     */
    @Transactional
    fun softDelete(
        id: UUID,
        projectId: UUID,
    ) {
        log.debug("Soft-deleting version id={} projectId={}", id, projectId)
        dsl.update(VERSIONS)
            .set(VERSIONS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(VERSIONS.ID.eq(id))
            .and(VERSIONS.PROJECT_ID.eq(projectId))
            .and(VERSIONS.DELETED_AT.isNull)
            .execute()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [VersionsRecord] 를 도메인 [Version] 으로 변환한다.
     *
     * status 는 DB VARCHAR 값을 [VersionStatus.valueOf] 로 변환한다.
     * DB CHECK 제약이 유효한 값만 허용하므로 변환 실패 시 프로그래밍 오류다.
     */
    private fun toVersion(record: VersionsRecord): Version =
        Version(
            id = record.id ?: error("versions.id must not be null after DB read"),
            projectId = record.projectId,
            name = record.name,
            description = record.description,
            startDate = record.startDate,
            releaseDate = record.releaseDate,
            status = VersionStatus.valueOf(
                record.status ?: error("versions.status must not be null after DB read"),
            ),
            releasedAt = record.releasedAt?.toInstant(),
            deletedAt = record.deletedAt?.toInstant(),
        )
}

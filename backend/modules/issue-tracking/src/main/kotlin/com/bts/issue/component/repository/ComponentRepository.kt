// ComponentRepository — components 테이블 jOOQ DSL 접근. DATA.md §5, §6, §3 준수.
package com.bts.issue.component.repository

import com.bts.issue.component.domain.Component
import com.bts.issue.jooq.tables.records.ComponentsRecord
import com.bts.issue.jooq.tables.references.COMPONENTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 컴포넌트 Repository.
 *
 * jOOQ DSLContext 를 통해 `components` 테이블에 접근한다.
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * - [insert] — 새 컴포넌트를 삽입하고 DB 생성 값(id, createdAt, updatedAt)을 포함한 [Component] 반환.
 * - [findById] — 컴포넌트 id + project_id 소속 + 활성(deleted_at IS NULL) 조건으로 조회.
 * - [findByProject] — 프로젝트 소속 활성 컴포넌트를 name 오름차순으로 반환.
 * - [softDelete] — deleted_at 를 현재 UTC 시각으로 설정. 물리 삭제 금지 (DATA.md §3).
 *
 * 동시 생성 race(23505 중복키 오류) 처리는 상위 서비스/컨트롤러(Task 6/7) 책임.
 * Repository 는 부분 유니크 인덱스 위반을 DataAccessException 으로 그대로 전파한다.
 */
@Repository
class ComponentRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 컴포넌트를 `components` 테이블에 삽입하고 DB 생성 값이 채워진 [Component] 를 반환한다.
     *
     * 부분 유니크 인덱스 전제 — `ux_components_project_id_name_active (WHERE deleted_at IS NULL)`.
     * 소프트 삭제된 동일 이름은 재삽입 허용된다.
     *
     * @param component DB 저장 전 [Component]. id 는 null 이어야 한다.
     * @return DB 생성 id / createdAt / updatedAt 을 포함한 [Component].
     */
    @Transactional
    fun insert(component: Component): Component {
        log.debug("Inserting component name={} projectId={}", component.name, component.projectId)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val record =
            dsl.insertInto(COMPONENTS)
                .set(COMPONENTS.PROJECT_ID, component.projectId)
                .set(COMPONENTS.NAME, component.name)
                .set(COMPONENTS.DESCRIPTION, component.description)
                .set(COMPONENTS.LEAD_USER_ID, component.leadUserId)
                .set(COMPONENTS.CREATED_AT, now)
                .set(COMPONENTS.UPDATED_AT, now)
                .returning()
                .fetchOne()
                ?: error("insert returning() returned null for name=${component.name}")
        return toComponent(record)
    }

    /**
     * 컴포넌트 id + project_id 소속 + 활성(deleted_at IS NULL) 조건으로 단건 조회한다.
     *
     * 다른 프로젝트 id 로 접근하면 null 을 반환한다 (Task 7 EC-5 대응).
     *
     * @param id 조회할 컴포넌트 UUID.
     * @param projectId 소속 프로젝트 UUID. id 가 실재해도 소속이 다르면 null 반환.
     * @return 활성 [Component], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(
        id: UUID,
        projectId: UUID,
    ): Component? =
        dsl.selectFrom(COMPONENTS)
            .where(COMPONENTS.ID.eq(id))
            .and(COMPONENTS.PROJECT_ID.eq(projectId))
            .and(COMPONENTS.DELETED_AT.isNull)
            .fetchOne()
            ?.let(::toComponent)

    /**
     * 프로젝트 소속 활성 컴포넌트를 name 오름차순으로 반환한다.
     *
     * `deleted_at IS NULL` 필터 자동 적용.
     *
     * @param projectId 조회할 프로젝트 UUID.
     * @return 활성 [Component] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByProject(projectId: UUID): List<Component> =
        dsl.selectFrom(COMPONENTS)
            .where(COMPONENTS.PROJECT_ID.eq(projectId))
            .and(COMPONENTS.DELETED_AT.isNull)
            .orderBy(COMPONENTS.NAME.asc())
            .fetch()
            .map(::toComponent)

    /**
     * 컴포넌트를 소프트 삭제한다.
     *
     * `deleted_at` 를 현재 UTC 시각으로 설정한다.
     * 이미 삭제된 컴포넌트는 영향 행 0 반환 (멱등 처리).
     * 물리 삭제(DELETE) 금지 — DATA.md §3.
     *
     * @param id 삭제할 컴포넌트 UUID.
     * @param projectId 소속 프로젝트 UUID. 소속이 다르면 아무 행도 변경되지 않는다.
     */
    @Transactional
    fun softDelete(
        id: UUID,
        projectId: UUID,
    ) {
        log.debug("Soft-deleting component id={} projectId={}", id, projectId)
        dsl.update(COMPONENTS)
            .set(COMPONENTS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(COMPONENTS.ID.eq(id))
            .and(COMPONENTS.PROJECT_ID.eq(projectId))
            .and(COMPONENTS.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 컴포넌트 정보(name, description, leadUserId)를 갱신하고 갱신된 [Component]를 반환한다.
     *
     * [component.id] 가 null 이면 [IllegalArgumentException] 을 던진다.
     *
     * @param component 갱신할 값이 채워진 [Component]. [Component.id] 는 non-null 이어야 한다.
     * @return 전달받은 [component] 를 그대로 반환한다(DB 재조회 안 함). 서비스가 도메인 메서드로
     *   만든 객체와 영속 상태가 일치하므로 추가 조회는 불필요하다.
     */
    @Transactional
    fun update(component: Component): Component {
        val id = requireNotNull(component.id) { "component.id must not be null for update" }
        log.debug("Updating component id={} projectId={}", id, component.projectId)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl.update(COMPONENTS)
            .set(COMPONENTS.NAME, component.name)
            .set(COMPONENTS.DESCRIPTION, component.description)
            .set(COMPONENTS.LEAD_USER_ID, component.leadUserId)
            .set(COMPONENTS.UPDATED_AT, now)
            .where(COMPONENTS.ID.eq(id))
            .and(COMPONENTS.PROJECT_ID.eq(component.projectId))
            .and(COMPONENTS.DELETED_AT.isNull)
            .execute()
        return component
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [ComponentsRecord] 를 도메인 [Component] 로 변환한다.
     */
    private fun toComponent(record: ComponentsRecord): Component =
        Component(
            id = record.id ?: error("components.id must not be null after DB read"),
            projectId = record.projectId,
            name = record.name,
            description = record.description,
            leadUserId = record.leadUserId,
            deletedAt = record.deletedAt?.toInstant(),
        )
}

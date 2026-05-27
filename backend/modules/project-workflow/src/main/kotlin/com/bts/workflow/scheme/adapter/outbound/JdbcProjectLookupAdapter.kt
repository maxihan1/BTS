// ProjectLookupPort 임시 구현 — projects 테이블 직접 jOOQ 조회 (FR-PM-04 교체 예정)

package com.bts.workflow.scheme.adapter.outbound

import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ProjectLookupPort] 임시 구현체.
 *
 * projectKey → projects.id(UUID) 변환을 위해 `projects` 테이블을 직접 jOOQ 로 조회한다.
 *
 * ## 임시 stub 경고 — FR-PM-04 교체 필수
 * `projects` 테이블은 project-management BC 소유이다.
 * BC 격리 원칙에 따르면 project-workflow BC 는 타 BC 도메인 클래스를 직접 import 하거나
 * 타 BC 전용 테이블에 쓰기(INSERT/UPDATE/DELETE) 해서는 안 된다.
 * 단, **읽기 전용 조회**는 cross-BC 격리의 회색지대로, AlwaysAllowWorkflowSchemePermissionResolver
 * 와 동일한 선례에 따라 임시 stub 으로 도입한다.
 *
 * **FR-PM-04 정식 cross-BC port 도입 시 이 클래스를 삭제하고 project-management 공개 API 구현체로 교체한다.**
 *
 * @param dsl jOOQ DSLContext.
 */
@Component
@Suppress("PropertyName", "VariableNaming") // jOOQ 필드 상수 — SQL 컬럼명 매칭 (UPPER_SNAKE_CASE). FR-PM-04 교체 시 정식 cross-BC port 로 대체 예정.
class JdbcProjectLookupAdapter(private val dsl: DSLContext) : ProjectLookupPort {
    private val log = LoggerFactory.getLogger(javaClass)

    // projects 테이블 — project-management BC 소유. 읽기 전용 참조 (FR-PM-04 교체 예정).
    private val TABLE = DSL.table("projects")
    private val COL_ID = DSL.field("id", UUID::class.java)
    private val COL_KEY = DSL.field("key", String::class.java)

    /**
     * projectKey 로 projects.id(UUID) 를 조회한다.
     *
     * `deleted_at IS NULL` 조건 없이 조회한다. projects soft-delete 정책이 확정되면
     * FR-PM-04 교체 시점에 함께 보완한다.
     *
     * @param projectKey 조회할 프로젝트 키.
     * @return projects.id(UUID), 없으면 null.
     */
    override fun findIdByKey(projectKey: ProjectKey): UUID? {
        log.debug("findIdByKey projectKey={}", projectKey.value)
        return dsl
            .select(COL_ID)
            .from(TABLE)
            .where(COL_KEY.eq(projectKey.value))
            .fetchOne()
            ?.get(COL_ID)
    }
}

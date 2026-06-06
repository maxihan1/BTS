// identity-access BC가 issue-tracking issues 테이블의 보안 판정 컨텍스트를 read-only로 조회하는 포트 및 구현체 (FR-PM-06 PR-B Task 7)

package com.atlas.bts.identity.issuesecurity

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * 이슈 보안 판정에 필요한 한 이슈의 컨텍스트(조회 전용 뷰).
 *
 * [securityLevelId]는 `issues.security_level_id` 원형 그대로 전달한다(NULL=등급 미지정=공개).
 * NULL(공개)과 "등급 id 존재하나 멤버 0인 고아 등급(보수적 차단)"의 구분은 호출 측 판정기
 * ([IssueSecurityDecider])가 멤버 목록과 함께 수행하므로, 본 뷰는 가공 없이 원형을 노출한다(C4).
 *
 * @property securityLevelId 지정된 보안 등급 식별자. 미지정이면 `null`(모든 VIEW 통과자에게 공개).
 * @property reporterId 이슈 보고자 식별자(REPORTER 멤버 판정용). issues.reporter_id 는 NOT NULL.
 * @property assigneeId 이슈 담당자 식별자(ASSIGNEE 멤버 판정용). 미할당이면 `null`.
 */
data class IssueSecurityContext(
    val securityLevelId: UUID?,
    val reporterId: UUID,
    val assigneeId: UUID?,
)

/**
 * cross-BC read-only 조회 포트 — issue-tracking `issues` 테이블의 보안 판정 컨텍스트 (FR-PM-06 PR-B Task 7).
 *
 * identity-access BC 는 issue-tracking BC 코드를 직접 import 하지 않는다(ADR D2 deployment invariant).
 * 동일 PostgreSQL·동일 public 스키마를 공유하므로 DB read-only 쿼리로 대체한다([ProjectDirectory] 동형).
 * 분리 배포 시 SPI(Service Provider Interface)로 교체한다.
 *
 * **의존 컬럼**: `key VARCHAR`, `security_level_id UUID`, `reporter_id UUID`, `assignee_id UUID`,
 * `deleted_at TIMESTAMPTZ`.
 * issues DDL 변경 시 위 다섯 컬럼 유지 여부를 반드시 확인할 것(DDL drift 경고).
 * `key`/`reporter_id`/`assignee_id` 컬럼 소유권은 issue-tracking BC — 정규식·존재 검증은 그쪽이 완료한
 * 것으로 신뢰한다.
 *
 * 구현체: [JdbcIssueSecurityLookup].
 */
interface IssueSecurityLookup {
    /**
     * 이슈 키로 보안 판정 컨텍스트를 조회한다.
     *
     * 소프트삭제(`deleted_at IS NOT NULL`)된 이슈는 존재하지 않는 것으로 취급한다.
     *
     * @param issueKey 조회할 이슈 키(예: "BTS-1"). 정규식 검증은 issue-tracking 소유이므로 그대로 전달한다.
     * @return 활성 이슈의 [IssueSecurityContext], 소프트삭제 또는 미존재이면 `null`.
     */
    fun lookup(issueKey: String): IssueSecurityContext?
}

/**
 * [IssueSecurityLookup] JDBC 구현체 (FR-PM-06 PR-B Task 7).
 *
 * **cross-BC 격리 (ADR D2)**:
 * issue-tracking 코드를 import 하지 않는다. 동일 DB public 스키마의 `issues` 테이블을
 * read-only SQL 로만 접근한다([ProjectDirectory] 동형). 분리 배포 시 SPI 로 교체한다.
 *
 * **SQL 인젝션 방어**:
 * 모든 파라미터를 [NamedParameterJdbcTemplate] 에 바인딩한다(DEVELOPMENT.md §1.1-3).
 * 문자열 결합 방식의 SQL 생성 절대 금지.
 *
 * **트랜잭션**:
 * 읽기 전용 단일 쿼리 — `@Transactional(readOnly = true)`.
 */
@Repository
@Transactional(readOnly = true)
class JdbcIssueSecurityLookup(
    private val jdbc: NamedParameterJdbcTemplate,
) : IssueSecurityLookup {
    override fun lookup(issueKey: String): IssueSecurityContext? =
        jdbc.query(SQL_LOOKUP, mapOf("key" to issueKey), ContextRowMapper).firstOrNull()

    private companion object {
        /**
         * 이슈 키로 보안 판정 컨텍스트 조회 — 소프트삭제 행은 제외한다.
         * key/reporter_id/assignee_id/security_level_id 컬럼은 issue-tracking BC 소유.
         */
        const val SQL_LOOKUP = """
            SELECT security_level_id, reporter_id, assignee_id
            FROM issues
            WHERE key = :key
              AND deleted_at IS NULL
        """
    }

    /** issues 행을 [IssueSecurityContext] 로 변환하는 RowMapper. */
    private object ContextRowMapper : RowMapper<IssueSecurityContext> {
        override fun mapRow(
            rs: ResultSet,
            rowNum: Int,
        ): IssueSecurityContext =
            IssueSecurityContext(
                securityLevelId = rs.getObject("security_level_id", UUID::class.java),
                reporterId = rs.getObject("reporter_id", UUID::class.java),
                assigneeId = rs.getObject("assignee_id", UUID::class.java),
            )
    }
}

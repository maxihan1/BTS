// 사용자가 멤버인 프로젝트 키 집합을 cross-BC read-only로 조회하는 ProjectMembershipPort 구현체

package com.atlas.bts.identity.project

import com.bts.shared.membership.ProjectMembershipPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [ProjectMembershipPort] identity-access BC 구현체 (FR-SR-03 PR2 Task 5).
 *
 * 필터 공유 가시성(FR-SR-03) 판정에서 search-export-import BC 가 "사용자가 멤버인 프로젝트"를
 * 확인할 때 이 어댑터를 호출한다. project_memberships(identity-access)와 projects(issue-tracking)를
 * JOIN 해 사용자가 멤버로 속한 활성 프로젝트의 **키 집합**을 반환한다.
 *
 * **cross-BC 격리 (ADR D2)**:
 * issue-tracking 코드를 직접 import 하지 않는다. 동일 PostgreSQL·동일 public 스키마를 공유하므로
 * projects 테이블을 read-only SQL 로만 접근한다. 분리 배포 시 SPI(Service Provider Interface)로
 * 교체한다. [ProjectDirectory] 와 동일한 read-only cross-BC 선례 패턴이다.
 *
 * **의존 컬럼**:
 * - project_memberships: `project_id UUID`, `user_id UUID`
 * - projects: `id UUID`, `key VARCHAR(10)`, `deleted_at TIMESTAMPTZ`
 *
 * projects DDL 변경 시 위 세 컬럼 유지 여부를 반드시 확인할 것.
 * `key` 컬럼 소유권은 issue-tracking BC — 반환값은 변환 없이 `projects.key` 원형 그대로 전달한다
 * (saved_filter_shares.target_id(PROJECT) 저장 형식과 일치).
 *
 * **SQL 인젝션 방어**:
 * 모든 파라미터를 [NamedParameterJdbcTemplate] 에 바인딩한다 (DEVELOPMENT.md §1.3).
 * 문자열 결합 방식의 SQL 생성 절대 금지.
 *
 * **트랜잭션**:
 * 읽기 전용 단일 조인 쿼리 — `@Transactional(readOnly = true)`.
 *
 * @param jdbc projects/project_memberships read-only 조회용 템플릿
 */
@Component
@Transactional(readOnly = true)
class ProjectMembershipAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectMembershipPort {
    /**
     * [userId] 가 멤버로 속한 프로젝트 중 소프트삭제되지 않은 프로젝트의 키 집합을 반환한다.
     *
     * 소프트삭제(`deleted_at IS NOT NULL`) 프로젝트는 제외한다.
     * 빈 Set 반환 = "멤버십 0개"(fail-closed 방향) — allow-all 이 아니다.
     *
     * @param userId 조회 대상 사용자 UUID
     * @return 멤버로 속한 활성 프로젝트의 `projects.key` 집합. 멤버십이 없으면 빈 Set.
     */
    override fun projectKeysOf(userId: UUID): Set<String> =
        jdbc.query(
            SQL_PROJECT_KEYS_OF_USER,
            mapOf("userId" to userId),
        ) { rs, _ -> rs.getString("key") }
            .toSet()

    private companion object {
        /**
         * 사용자가 멤버인 활성 프로젝트의 키 조회.
         * project_memberships 와 projects 를 project_id = id 로 JOIN 하고 소프트삭제 행은 제외한다.
         * key 컬럼은 issue-tracking BC 소유 (UNIQUE, NOT NULL 보장).
         */
        const val SQL_PROJECT_KEYS_OF_USER = """
            SELECT p.key
            FROM project_memberships m
            JOIN projects p ON m.project_id = p.id
            WHERE m.user_id = :userId
              AND p.deleted_at IS NULL
        """
    }
}

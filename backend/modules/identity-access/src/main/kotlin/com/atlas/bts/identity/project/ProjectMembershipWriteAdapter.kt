// 프로젝트 생성자를 PROJECT_ADMIN 멤버로 등록하는 ProjectMembershipWritePort 구현체 — 호출자 트랜잭션에 참여한다

package com.atlas.bts.identity.project

import com.bts.shared.membership.ProjectMembershipWritePort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ProjectMembershipWritePort] identity-access BC 구현체 (FR-PM-10 / FR-PJ-01).
 *
 * issue-tracking 이 프로젝트를 생성할 때 projects 1행과 project_memberships 1행을 같은 트랜잭션으로
 * 써야 한다(불변식 I1). project_memberships 는 identity-access 소유이므로 이 어댑터가 그 쓰기를 맡는다.
 *
 * ## 트랜잭션 애노테이션이 없는 것은 의도다
 * 호출자(issue-tracking 프로젝트 생성)의 트랜잭션에 참여해야 I1(2행 원자성)이 성립한다.
 * 애노테이션이 없으면 Spring 이 바인딩한 커넥션을 그대로 써서 자동 참여한다.
 * - readOnly = true 는 쓰기라 애초에 틀렸다.
 * - TransactionTemplate 을 쓰면 안 된다. AutomationIssueMutationAdapter 가 그걸 쓰지만 그건
 *   "앙비언트 트랜잭션이 없다"를 전제한 OCC 재시도 격리 설계이고, 앙비언트 트랜잭션이 있는 여기에
 *   복사하면 그 어댑터 KDoc 이 경고한 rollback-only 오염 -> UnexpectedRollbackException 이 부활한다.
 * 회귀 가드 — ProjectMembershipWriteAdapterTest 의 롤백 케이스.
 *
 * ## 절대 규칙 9(트랜잭션 경계 명시) 위반이 아닌 근거
 * 이 어댑터는 트랜잭션 경계의 **소유자가 아니라 참여자**다. 경계 명시는 호출자인 프로젝트 생성
 * 서비스가 하고, 여기서 경계를 또 선언하면 I1 이 깨진다. TransactionalServiceArchTest 룰의 방향도
 * 반대다 — "Transactional 이 붙은 클래스는 빈이어야 한다"이지 "빈이면 Transactional 이어야 한다"가
 * 아니다(PR #6 LocalCredentialService 사고 가드). Transactional 없는 Component 는 발화하지 않는다.
 * DATA.md §6 이 예고한 TransactionalAware 애노테이션은 미구현이라 쓸 수 없다.
 *
 * ## 형제 어댑터와의 비대칭
 * [ProjectMembershipAdapter] 는 클래스 레벨 Transactional(readOnly = true) 를 달지만 이 어댑터는
 * 아무 애노테이션도 달지 않는다. 위 이유에 따른 의도된 비대칭이며 "빠뜨린 것"이 아니다.
 *
 * ## SQL 인젝션 방어
 * 모든 파라미터를 [NamedParameterJdbcTemplate] 에 바인딩한다 (DEVELOPMENT.md §1.3).
 * 문자열 결합 방식의 SQL 생성 절대 금지.
 *
 * @param jdbc project_memberships 쓰기용 템플릿. 호출자 트랜잭션의 커넥션에 자동 바인딩된다.
 */
@Component
class ProjectMembershipWriteAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectMembershipWritePort {
    /**
     * [userId] 를 [projectId] 의 PROJECT_ADMIN 으로 등록한다.
     *
     * id / created_at / updated_at 은 DB DEFAULT 를 사용한다.
     * UNIQUE(project_id, user_id) 위반 시 DataIntegrityViolationException 이 전파되어
     * 호출자 트랜잭션이 롤백된다 — 멱등이 아니다(포트 KDoc 참조).
     *
     * @param projectId 멤버십을 등록할 프로젝트 UUID
     * @param userId PROJECT_ADMIN 으로 등록할 생성자 UUID
     */
    override fun addCreatorAsAdmin(
        projectId: UUID,
        userId: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO project_memberships (project_id, user_id, role)
            VALUES (:projectId, :userId, :role)
            """,
            mapOf("projectId" to projectId, "userId" to userId, "role" to "PROJECT_ADMIN"),
        )
    }
}

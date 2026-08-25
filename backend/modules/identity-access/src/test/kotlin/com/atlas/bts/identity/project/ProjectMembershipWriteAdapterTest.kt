// ProjectMembershipWriteAdapter 통합 테스트 — 호출자 트랜잭션 참여(불변식 I1) 실증 (스펙 §4.4 · DoD-11 PR-1 판)

package com.atlas.bts.identity.project

import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.membership.ProjectMembershipWritePort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * [ProjectMembershipWriteAdapter] 통합 테스트 — 호출자 트랜잭션 참여 실증 (FR-PM-10 / FR-PJ-01).
 *
 * ## 이 테스트가 지키는 것 — 불변식 I1
 * 프로젝트 생성은 projects(issue-tracking) 1행과 project_memberships(identity-access) 1행을
 * **같은 트랜잭션**으로 써야 한다. 쪼개지면 "생성자조차 못 들어가는 프로젝트"가 만들어진다.
 * 어댑터가 자기 트랜잭션 경계를 열지 않고 호출자 트랜잭션에 참여하는 것이 그 성립 조건이고,
 * 이 클래스가 그것을 증명하는 유일한 실증이다.
 *
 * ## 왜 Propagation.NOT_SUPPORTED 인가 (필수 — 없으면 판별력이 0이다)
 * JdbcTest 는 Transactional 메타 애노테이션이다. 기본값대로 두면 테스트 메서드 전체가 하나의
 * 트랜잭션 안에서 돌고, 검증 SELECT 가 **같은 트랜잭션 안에서 미커밋 INSERT 를 그대로 읽는다**.
 * 그러면 아래 두 경우가 똑같이 fail 해서 서로 구분되지 않는다.
 *
 * - 올바른 어댑터(무애노테이션) — 테스트 트랜잭션에 참여, throw 시 rollback-only 마킹만 되고
 *   실제 롤백은 테스트 종료 시점이라 SELECT 는 여전히 1을 본다.
 * - 잘못된 어댑터(REQUIRES_NEW) — 별도 트랜잭션으로 진짜 커밋되어 SELECT 가 1을 본다.
 *
 * NOT_SUPPORTED 로 테스트 트랜잭션을 끄면 검증 SELECT 가 트랜잭션 밖에서 실행되므로
 * "미커밋을 내가 읽는" 오염이 사라지고, 두 경우가 0과 1로 갈라진다.
 * 선례 — RefreshTokenRepositoryTest 의 EC-23 동시성 테스트.
 *
 * ## 정리가 수동인 이유
 * 테스트 트랜잭션이 없으니 자동 롤백도 없다. 쓴 행은 실제로 커밋되므로 AfterEach 로 직접 지운다.
 *
 * ## 픽스처
 * project_memberships.project_id 는 cross-BC 참조라 FK 가 없다(V007, ADR D2).
 * 따라서 실제 projects 행 없이 임의 UUID 를 그대로 쓸 수 있다.
 * user_id 는 users(id) FK 가 있으므로 BeforeEach 에서 사용자를 사전 삽입한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProjectMembershipWriteAdapter::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProjectMembershipWriteAdapterTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    private lateinit var adapter: ProjectMembershipWritePort

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 호출자 트랜잭션 역할 — 어댑터가 이 경계에 참여하는지가 검증 대상이다. */
    @Autowired
    private lateinit var txTemplate: TransactionTemplate

    /** users(id) FK 를 충족하는 픽스처 사용자 */
    private val userId = UUID.randomUUID()

    /** cross-BC 참조라 FK 가 없다 (V007) — 실제 projects 행이 필요 없다. */
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun seedUser() {
        jdbc.update(
            """
            INSERT INTO users (id, username, display_name)
            VALUES (:id, :username, :displayName)
            """,
            mapOf("id" to userId, "username" to "task7-creator", "displayName" to "Task7 Creator"),
        )
    }

    @AfterEach
    fun cleanup() {
        // 테스트 트랜잭션이 없어 자동 롤백이 없다 — 커밋된 행을 직접 지운다. FK 순서 준수.
        jdbc.update("DELETE FROM project_memberships WHERE project_id = :projectId", mapOf("projectId" to projectId))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userId))
    }

    @Test
    fun `호출자 트랜잭션이 롤백되면 addCreatorAsAdmin 도 롤백된다`() {
        assertThatThrownBy {
            txTemplate.executeWithoutResult {
                adapter.addCreatorAsAdmin(projectId, userId)
                // error() 는 IllegalStateException 을 던진다 — detekt UseCheckOrError 준수.
                error("의도적 실패 — 롤백 유발")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        // 어댑터가 자기 트랜잭션을 열었다면(REQUIRES_NEW / TransactionTemplate) 이 행이 커밋돼 살아남는다.
        // NOT_SUPPORTED 라 이 SELECT 는 트랜잭션 밖에서 실행되므로 미커밋 행을 볼 수 없다.
        assertThat(countMemberships()).isZero()
    }

    @Test
    fun `호출자 트랜잭션이 커밋되면 addCreatorAsAdmin 이 영속된다`() {
        txTemplate.executeWithoutResult { adapter.addCreatorAsAdmin(projectId, userId) }

        // 롤백 케이스만 있으면 "아무것도 안 쓰는 어댑터"도 통과한다 — 진짜 커밋을 확인해 그 구멍을 막는다.
        assertThat(countMemberships()).isOne()
    }

    /** 트랜잭션 밖에서 실행되는 검증 조회 — 오직 커밋된 행만 센다. */
    private fun countMemberships(): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM project_memberships
            WHERE project_id = :projectId
              AND user_id = :userId
              AND role = 'PROJECT_ADMIN'
            """,
            mapOf("projectId" to projectId, "userId" to userId),
            Int::class.java,
        ) ?: 0
}

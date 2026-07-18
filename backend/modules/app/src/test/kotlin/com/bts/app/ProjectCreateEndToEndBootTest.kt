// FR-PJ-01 Task 12 — S1(cross-BC 원자성 양성 기준선) + DoD-11(UNIQUE 위반 롤백)을 9-BC 조립 부팅으로 검증

package com.bts.app

import com.bts.issue.project.application.ProjectCreateApplicationService
import com.bts.issue.project.repository.ProjectCreateRepository
import com.bts.shared.membership.ProjectMembershipWritePort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * FR-PJ-01 Task 12 — **S1**(cross-BC 원자성 양성 기준선) + **DoD-11**(UNIQUE 위반 시 원자적 롤백)을
 * :modules:app **9-BC prod 조립 부팅**으로 검증한다.
 *
 * ## 왜 issue-tracking 격리(예: [ProjectCreateApplicationServiceTest])로는 불가능한가
 * 그 단위 테스트는 [ProjectMembershipWritePort] 를 MockK 로 대체하므로 "실제 DB 트랜잭션이 두 BC 를
 * 함께 되감는지"는 검증 범위 밖이다([ProjectCreateApplicationService] KDoc "이 클래스가 증명하지 않는
 * 것"). identity-access 단독 [com.atlas.bts.identity.project.ProjectMembershipWriteAdapterTest] 도
 * `@JdbcTest` 라 `projects` 테이블(issue-tracking 소유)이 스키마에 없어 실제 [ProjectCreateRepository]
 * 를 쓸 수 없다. **실제 어댑터 + 실제 `projects`/`project_memberships` 양쪽 스키마가 모두 존재하는 곳은
 * :modules:app 조립 부팅뿐**이다.
 *
 * ## S1 — 양성 기준선을 먼저 세우는 이유
 * DoD-11(음성·롤백)이 의미를 가지려면 "정상 1회 호출은 두 행이 함께 커밋된다"는 양성 기준선이 먼저
 * 확인돼 있어야 한다. 기준선 없이 롤백만 보면, "애초에 아무것도 안 쓰는 어댑터"도 롤백 단언을
 * 통과해버리는 vacuous 함정([com.atlas.bts.identity.project.ProjectMembershipWriteAdapterTest] KDoc
 * "롤백 케이스만 있으면 아무것도 안 쓰는 어댑터도 통과한다" — 동일 함정 경계).
 *
 * ## DoD-11 — 예외 주입 경로 (실제 실패 경로)
 * [ProjectMembershipWritePort.addCreatorAsAdmin] 은 멱등이 아니다 — `project_memberships`
 * `UNIQUE(project_id, user_id)` 로 2회 호출은 실패한다(포트 KDoc). [ProjectCreateApplicationService.create]
 * 는 정상 흐름에서 이 메서드를 1회만 호출하므로, 이 UNIQUE 위반은 자연 발생하지 않는다 — DB 가 생성하는
 * project.id 를 사전에 예측할 수 없어(랜덤 UUID) 외부에서 미리 충돌 상태를 심어둘 수도 없다. 따라서
 * 이 테스트는 [TransactionTemplate] 로 실제 서비스와 동일한 트랜잭션 경계를 직접 열고, 그 **한 트랜잭션
 * 안에서** [ProjectCreateRepository.insert] → `addCreatorAsAdmin` 1차(성공) → `addCreatorAsAdmin` 2차
 * (동일 projectId+userId, UNIQUE 위반)를 순서대로 호출한다. 2차 호출의 실패가 트랜잭션 전체를 롤백시켜,
 * 1차에서 이미 "성공"했던 멤버십 쓰기까지 함께 되감기는지가 이 테스트의 핵심 관찰이다 — 실제
 * [ProjectMembershipWriteAdapter][com.atlas.bts.identity.project.ProjectMembershipWriteAdapter] 와
 * [ProjectCreateRepository](jOOQ) 가 같은 tx-aware DataSource 위에서 정말로 하나의 트랜잭션을 공유하는지
 * 실증한다(DATA.md §6 I1).
 *
 * ## codereview #3 — create() 자체의 @Transactional 경계 실증 (세 번째 테스트)
 * 위 DoD-11 은 [TransactionTemplate] 로 테스트가 직접 연 트랜잭션 안에서 실패를 재현하므로 "어댑터+DB
 * 가 롤백 가능"은 증명하지만 [ProjectCreateApplicationService.create] 자신이 그 경계(클래스 레벨
 * `@Transactional`)를 선언했는지는 증명하지 않는다. 세 번째 테스트가 `create()` 를 직접 호출해 그
 * 간극을 메운다 — 해당 테스트 KDoc 참조.
 *
 * 사전 조건 — dev postgres 기동(`docker compose -f infra/docker-compose.dev.yml up -d postgres`, 5433).
 * [ProdAssemblyHttpTestBase] 를 상속만 하고 자체 `@SpringBootTest`/`@DynamicPropertySource` 를 추가
 * 선언하지 않아 다른 조립 테스트와 컨텍스트를 공유한다(베이스 KDoc).
 */
class ProjectCreateEndToEndBootTest : ProdAssemblyHttpTestBase() {
    @Autowired
    private lateinit var projectCreateService: ProjectCreateApplicationService

    @Autowired
    private lateinit var projectCreateRepo: ProjectCreateRepository

    @Autowired
    private lateinit var membershipWritePort: ProjectMembershipWritePort

    @Autowired
    private lateinit var txTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /** 매 테스트 전후로 이 테스트가 심은 데이터만 지운다 — 공유 dev postgres(5433)에 잔여물을 남기지 않는다. */
    @BeforeEach
    fun cleanup() {
        jdbc.update("DELETE FROM projects WHERE key IN (?, ?, ?)", KEY_S1, KEY_ROLLBACK, KEY_TX_BOUNDARY)
        // users 삭제가 project_memberships 를 ON DELETE CASCADE 로 함께 지운다(V007).
        // UNSEEDED_CREATOR_ID 는 아래 새 테스트가 의도적으로 users 에 심지 않는 값이라 대상에서 제외한다.
        jdbc.update("DELETE FROM users WHERE id = ?", CREATOR_ID)
    }

    @AfterEach
    fun tearDown() {
        cleanup()
    }

    @Test
    fun `S1 — 프로젝트 생성은 projects·project_memberships 를 같은 트랜잭션으로 함께 쓴다 (cross-BC 원자성 양성 기준선)`() {
        seedCreator()

        val project = projectCreateService.create(CREATOR_ID, KEY_S1, "S1 cross-BC 원자성 기준선")
        val projectId = requireNotNull(project.id) { "생성된 project.id 는 null 일 수 없다" }

        // 두 BC(issue-tracking projects / identity-access project_memberships) 모두 1행씩 커밋됐어야 한다.
        assertThat(countProjectByKey(KEY_S1)).isOne()
        assertThat(countAdminMembership(projectId, CREATOR_ID)).isOne()
    }

    @Test
    fun `DoD-11 — addCreatorAsAdmin 2회 호출로 UNIQUE 위반 시 projects·project_memberships 둘 다 롤백된다`() {
        seedCreator()

        // 사전 상태 — 이 테스트가 심기 전에는 0건(클래스 KDoc "예외 주입 경로").
        assertThat(countProjectByKey(KEY_ROLLBACK)).isZero()
        assertThat(countMembershipsForUser(CREATOR_ID)).isZero()

        assertThatThrownBy {
            txTemplate.executeWithoutResult {
                val project = projectCreateRepo.insert(KEY_ROLLBACK, "DoD-11 롤백 검증")
                val projectId = requireNotNull(project.id) { "생성된 project.id 는 null 일 수 없다" }
                // 1차 — 같은 트랜잭션 안에서는 성공(아직 커밋 前).
                membershipWritePort.addCreatorAsAdmin(projectId, CREATOR_ID)
                // 2차 — 동일 (projectId, userId) 재호출. project_memberships UNIQUE(project_id, user_id)
                // 위반으로 DataIntegrityViolationException 이 전파되어 트랜잭션이 롤백된다(포트 KDoc "멱등이 아니다").
                membershipWritePort.addCreatorAsAdmin(projectId, CREATOR_ID)
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // 롤백 후 — projects(이번 생성 시도분)·project_memberships(1차 성공분 포함) 둘 다 원래 상태(새 행 0).
        // 한쪽만 롤백됐다면(I1 위반) 이 중 하나는 1 이상으로 남는다.
        assertThat(countProjectByKey(KEY_ROLLBACK)).isZero()
        assertThat(countMembershipsForUser(CREATOR_ID)).isZero()
    }

    /**
     * codereview #3(DoD-11 추가) — [ProjectCreateApplicationService.create] 를 **직접 호출**해
     * 그 **클래스 레벨 `@Transactional`** 이 `insert`→`addCreatorAsAdmin` 을 한 트랜잭션으로 묶는지
     * 실증한다.
     *
     * ## 위 DoD-11(TransactionTemplate) 테스트와 중복이 아닌 이유
     * 위 테스트는 [TransactionTemplate] 로 **이 테스트가 직접 연** 트랜잭션 안에서 `insert`→
     * `addCreatorAsAdmin`×2 를 호출한다 — "실 어댑터 + 실 DB 가 한 트랜잭션 안에서 롤백 가능한가"는
     * 증명하지만, **`create()` 자신이 그 트랜잭션 경계를 선언했는지는 증명하지 않는다**(TransactionTemplate
     * 이 대신 경계를 열었으므로). 이 테스트는 `create()` 를 그대로 호출해 그 클래스 레벨
     * `@Transactional` 자체가 경계를 여는지를 본다 — 상보적이며 어느 한쪽이 다른 쪽을 대체하지 않는다.
     *
     * ## 왜 시드하지 않는가 — mock 대신 자연 발생 실패 트리거
     * [ProjectMembershipWritePort.addCreatorAsAdmin] 을 인위로 실패시키려면 보통 mock 이 필요해
     * 보이지만, `project_memberships.user_id` 는 `REFERENCES users(id)`(V007) — creatorId 를 `users`
     * 에 **의도적으로 시드하지 않으면** `addCreatorAsAdmin` 내부 INSERT 가 FK 위반으로 자연 실패한다.
     * `projects` INSERT([ProjectCreateRepository.insert])는 creatorId 를 참조하지 않으므로 먼저
     * 성공한다 — 즉 mock 없이 "insert 성공 후 addCreatorAsAdmin 실패"라는 정확한 순서를 실제 어댑터로
     * 재현할 수 있다. mock([io.mockk.mockk] 등)을 썼다면 [ProjectMembershipWritePort] 빈을 교체해야
     * 해 [ProdAssemblyHttpTestBase] 의 9-BC prod 컨텍스트와 다른 `MergedContextConfiguration` 이
     * 되고(그 베이스 KDoc "★ webEnvironment는 컨텍스트 캐시 키의 일부다"), 같은 JVM 안에 9-BC 컨텍스트가
     * 중복 부팅되어 `@Scheduled` 워커가 같은 5433 dev postgres 를 동시 폴링하는 위험을 새로 들인다.
     * 이 자연 발생 트리거는 실제 프로덕션 컴포넌트만으로 같은 실패를 재현하므로 그 위험이 없다.
     *
     * ## mutation 판별자 (수동 실증, 커밋 대상 아님)
     * `create()` 의 클래스 레벨 `@Transactional` 을 제거하면 [ProjectCreateRepository.insert] 자신의
     * `@Transactional`(REQUIRED, 앙비언트 트랜잭션 없음)이 독립적으로 새 트랜잭션을 열고 즉시
     * 커밋한다 — `addCreatorAsAdmin` 은 그 뒤 트랜잭션 밖에서 실패하므로 이미 커밋된 `projects` 행은
     * 되감기지 않고 남는다. 이 테스트의 마지막 단언(`isZero()`)이 그 경우 `1`을 보고 fail 해야 한다.
     */
    @Test
    fun `create() 도중 addCreatorAsAdmin 실패 시 projects 행이 롤백된다 (서비스 @Transactional 경계 실증)`() {
        // 사전 상태 — 0건(클래스 KDoc "예외 주입 경로"와 동형).
        assertThat(countProjectByKey(KEY_TX_BOUNDARY)).isZero()

        assertThatThrownBy {
            projectCreateService.create(UNSEEDED_CREATOR_ID, KEY_TX_BOUNDARY, "서비스 tx 경계 실증")
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // insert() 자체는 성공했었다(자체 @Transactional 만으로도 커밋될 수 있는 대상) — 그런데도
        // 0건이면 create() 클래스 레벨 @Transactional 이 그 커밋을 감싸 롤백시켰다는 뜻이다.
        assertThat(countProjectByKey(KEY_TX_BOUNDARY)).isZero()
    }

    // ── 시드/조회 ───────────────────────────────────────────────────────────────

    private fun seedCreator() {
        jdbc.update("INSERT INTO users (id, username) VALUES (?, ?)", CREATOR_ID, USERNAME_CREATOR)
    }

    private fun countProjectByKey(key: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE key = ? AND deleted_at IS NULL",
            Int::class.java,
            key,
        ) ?: 0

    private fun countAdminMembership(
        projectId: UUID,
        userId: UUID,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_memberships WHERE project_id = ? AND user_id = ? AND role = 'PROJECT_ADMIN'",
            Int::class.java,
            projectId,
            userId,
        ) ?: 0

    private fun countMembershipsForUser(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_memberships WHERE user_id = ?",
            Int::class.java,
            userId,
        ) ?: 0

    private companion object {
        /** 테스트 전용 생성자 사용자 — DoD-9(T8) 등 다른 조립 테스트의 고정 UUID 와 겹치지 않게 분리. */
        val CREATOR_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        const val USERNAME_CREATOR = "task12-dod11-creator"

        /**
         * codereview #3 전용 — **의도적으로 `users` 에 시드하지 않는** creatorId. `project_memberships
         * .user_id` FK(V007)를 자연 위반시켜 mock 없이 `addCreatorAsAdmin` 실패를 재현한다(위 테스트
         * KDoc "왜 시드하지 않는가"). 다른 조립 테스트의 고정 UUID 와 겹치지 않게 분리.
         */
        val UNSEEDED_CREATOR_ID: UUID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

        /** 테스트 전용 프로젝트 key(2~10자, `^[A-Z][A-Z0-9]{1,9}$`) — 다른 조립 테스트와 섞이지 않게 좁힌다. */
        const val KEY_S1 = "PJTB"
        const val KEY_ROLLBACK = "PJTC"
        const val KEY_TX_BOUNDARY = "PJTD"
    }
}

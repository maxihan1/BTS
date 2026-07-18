// FR-PJ-01 Task 12 — S10(R6 빈 DB 이슈생성) + S11(R6-B 재시드 dangling 매핑 수리)를 9-BC 조립 부팅으로 검증

package com.bts.app

import com.bts.workflow.seed.YamlSeedService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.MessageDigest
import java.util.UUID

/**
 * FR-PJ-01 Task 12 — **S10**(R6 빈 DB 이슈생성) + **S11**(R6-B 재시드 dangling 매핑 수리)를
 * :modules:app **9-BC prod 조립 부팅**으로 검증한다.
 *
 * ## 왜 [ProdAssemblyHttpTestBase]의 공유 dev postgres(5433)를 그대로 쓰지 않는가
 * S10 은 "빈 DB(마이그레이션만 적용, 시드 SQL 손수 실행 없음)"가 핵심 전제다. dev postgres(5433)는
 * 오랜 로컬 개발 이력이 누적된 **비어 있지 않은** DB([ProjectCreatePermissionProdBootTest] 등 여러 조립
 * 테스트가 공유)라 이 전제를 실증할 수 없다. 이 클래스는 [PostgreSQLContainer] 로 **진짜 빈 DB**를
 * 새로 띄우고, 이 클래스 자체의 [DynamicPropertySource] 로 `spring.datasource.*` 를 그 컨테이너로
 * override 한다.
 *
 * ## 왜 [ProdAssemblyHttpTestBase] 는 그대로 상속하는가
 * JWT RSA 키·slack signing secret·automation 암호화 키 등 prod 부팅에 필요한 나머지 더미 설정은
 * 베이스의 [DynamicPropertySource] 를 그대로 재사용한다. Spring 은 테스트 클래스 계층 전체의
 * `@DynamicPropertySource` 정적 메서드를 모두 수집하므로(상속 메서드 + 이 클래스 자체 메서드), 이
 * 클래스만의 추가 `spring.datasource.*` 오버라이드가 별도 컨텍스트 캐시 키를 만들어 — 다른 조립 테스트와
 * 컨텍스트를 공유하지 않고 — 이 빈 컨테이너로 **독립적으로 새로 부팅**된다.
 *
 * ## pgmq 확장 필수 — postgres:16-alpine 불가
 * automation/slack-integration/notification/search-export-import 마이그레이션이 `CREATE EXTENSION
 * pgmq` 를 포함하므로, pgmq 사전 설치된 `quay.io/tembo/pg16-pgmq` 이미지가 필요하다
 * ([com.bts.search.savedfilter.persistence.SearchPersistenceTestBase] 동형 선례,
 * `docs/adr/2026-05-22-pgmq-postgres-image.md`).
 *
 * ## S10 — 매핑을 손수 심지 않는다 (S-3 은폐 차단)
 * 이 파일 어디에도 `workflow_scheme_issue_type_mappings` 에 대한 수동 INSERT 가 없다(REFACTOR 단계에서
 * 재확인한 사실). 이슈 생성이 성공한다면 오직 (1) Flyway 마이그레이션(identity V008/V014 권한 매트릭스 +
 * project-workflow V201 표준 스킴 시드) + (2) `ApplicationReadyEvent` 로 부팅 시 자동 실행되는
 * [YamlSeedService.seedAll] 말미의
 * [com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository.repairDefaultMappings] 백필만으로
 * 채워진 결과여야 한다.
 *
 * ## ★ S10 이 드러낸 선재 결함과 그 수정 — EC-1 D10 auto-assign 500 (T13 이 고침, 이제 GREEN 증거)
 * 이 테스트는 **매핑 백필(R6) 자체는 정상**임을 실측으로 확인했다(부팅 로그 — `repairDefaultMappings`
 * 백필 정상 수행, [S11]에서 매핑 존재를 별도로 재확인). **처음 작성됐을 때(T12) 이슈 생성이 500
 * (`WorkflowSchemeAccessDeniedException`)으로 실패**했다 — R6 이 고친 "매핑 부재로 인한 422"가 아니라
 * **다른, 이전에 발견되지 않은 선재 결함**이었다. 그 결함을 이 PR 의 T13 이 고쳐서 이제 이슈 생성이 201 이다.
 *
 * 근본 원인(고쳐지기 전) — `assignment` 가 없는 신규 프로젝트의 EC-1 D10 auto-assign
 * ([WorkflowResolverImpl][com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl],
 * [WorkflowSchemeApplicationService][com.bts.workflow.scheme.application.WorkflowSchemeApplicationService])
 * 이 `actor = WorkflowSchemeApplicationService.SYSTEM_ACTOR`(nil UUID sentinel)로
 * `assignToProject`를 호출하고, 그 메서드는 `WorkflowSchemePermission.ASSIGN_SCHEME` 권한을
 * `WorkflowSchemeScope.Project` 범위로 요구한다. prod 구현체
 * [com.atlas.bts.identity.permission.IdentityAccessWorkflowSchemePermissionResolver.hasProjectPermission]
 * 은 `membershipRepo.findByProjectAndUser(projectId, actorId)` 로 **actor 의 프로젝트 멤버십**을 먼저
 * 요구하는데, SYSTEM_ACTOR(`00000000-0000-0000-0000-000000000000`)는 `users` 테이블에 존재하지 않는
 * 합성 sentinel 이라 **어떤 프로젝트의 멤버도 될 수 없어**, auto-assign 이 prod 에서 항상 500 이었다.
 *
 * 왜 그때까지 발견되지 않았는가 — 기존 조립 테스트([ProjectCreatePermissionProdBootTest] 등)는 프로젝트
 * 생성까지만 검증하고 그 프로젝트에 이슈를 생성하지 않는다. dev postgres(5433)의 기존 프로젝트들은
 * `infra/local/seed-project.sql` 로 스킴 배정이 수동 시드돼 있어(그 파일 `:10-12` 가 이 500 을 문서화하며
 * 우회) auto-assign 경로 자체를 타지 않는다. "새 프로젝트 생성 직후 그 프로젝트에 이슈를 생성"하는
 * 조합은 이 S10 이 유일하다 — FR-PJ-01 이 프로젝트 생성을 REST 로 열면서 이 선재 결함이 신규 노출됐다.
 *
 * **수정 — T13 (같은 PR).** `WorkflowSchemeApplicationService.assignToProject` 가 SYSTEM_ACTOR(nil UUID)만
 * `requirePermission` 을 건너뛰도록 분기했다(software-scheme auto-assign 한정, 사용자 명시 배정은 권한 유지).
 * 그 결과 이 S10 은 **auto-assign 수정이 prod 조립 부팅에서 실제로 동작함을 증명하는 GREEN 테스트**다.
 * 매핑을 손수 심지 않고 백필+auto-assign 전 경로에 의존하므로, R6 은폐(S-3)도 auto-assign 회귀도 이 테스트가 잡는다.
 *
 * ## S11 — 재현 범위와 그 한계 (정직한 보고)
 * classpath YAML 은 빌드 시점에 고정되므로 조립 부팅 테스트 안에서 "YAML 구조 변경"을 실제로 재현할 수는
 * 없다. 게다가 `workflow_scheme_issue_type_mappings.workflow_id` 는 `REFERENCES workflows(id) ON DELETE
 * RESTRICT`(V201:84)이고 이 FK 는 DELETE 뿐 아니라 **INSERT/UPDATE 시점에도 강제**된다 — 존재하지 않는
 * workflow_id 로 직접 UPDATE 를 시도해 실측으로 확인했다(FK violation, `insert or update on table
 * "workflow_scheme_issue_type_mappings" violates foreign key constraint`). 즉 진짜 "dangling"(FK 를
 * 위반하는 고아 행) 은 DB 레벨에서 애초에 성립할 수 없고, R6-B 의 실제 무결성 보장 기전은
 * `YamlSeedService.applyIfChanged` 자신의 기록→삭제→재INSERT(트랜잭션 내 원자적 재연결)이다 — 그 경로는
 * `com.bts.workflow.seed.YamlSeedServiceTest`(T2, 단위)가 이미 실증했다.
 *
 * 이 클래스는 그 한계 안에서 달성 가능한 최선을 검증한다 — 매핑이 존재하는 상태에서, 실제 부팅 시
 * `ApplicationReadyEvent` 가 호출하는 것과 **동일한** [YamlSeedService.seedAll] 빈·메서드를 재호출해
 * "재부팅"을 대리하고, (1) 재부팅이 예외 없이 성공하는지, (2) 매핑이 같은 workflow 를 가리키며 생존하는지,
 * (3) 중복 행이 쌓이지 않는지(멱등성)를 확인한다.
 *
 * 사전 조건 — 로컬 docker 데몬 기동(Testcontainers 가 컨테이너를 새로 띄운다).
 */
class WorkflowBackfillBootTest : ProdAssemblyHttpTestBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var yamlSeedService: YamlSeedService

    @Test
    fun `S10 — 빈 DB 부팅 후 프로젝트 생성 → 그 프로젝트에 이슈 생성 201 (422 아님, R6)`() {
        seedSystemAdminUser()

        val projectResponse = createProject(RAW_TOKEN_ADMIN, PROJECT_KEY, "R6 빈 DB 검증 프로젝트")
        assertThat(projectResponse.statusCode).isEqualTo(HttpStatus.CREATED)

        val issueResponse = createIssue(RAW_TOKEN_ADMIN, PROJECT_KEY, "R6 빈 DB 이슈 생성 검증")

        // 422(IssueWorkflowNotConfiguredException) 가 아니라 201 이어야 R6 이 고쳐졌다는 증거.
        // 상태코드만으론 공허하므로 본문에 발급된 이슈 키(PROJECT_KEY-1)가 실렸는지도 함께 확인한다.
        assertThat(issueResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(issueResponse.body).contains("\"$PROJECT_KEY-1\"")
    }

    @Test
    fun `S11 — 매핑 존재 상태에서 seedAll 재호출(재부팅 대리)이 성공하고 매핑이 그대로 생존한다 (R6-B)`() {
        val schemeId = findSchemeId(SOFTWARE_SCHEME_KEY)
        val validWorkflowId = findWorkflowId(SOFTWARE_DEFAULT_WORKFLOW_KEY)

        // 사전 조건 — 부팅 시 자동 실행된 seedAll() 이 이미 유효한 default 매핑 1건을 채워 뒀다.
        assertThat(defaultMappingWorkflowId(schemeId)).isEqualTo(validWorkflowId)
        assertThat(defaultMappingRowCount(schemeId)).isOne()

        // "재부팅" 대리 — 실제 ApplicationReadyEvent 가 호출하는 것과 동일한 빈·메서드를 재호출한다.
        // YAML 내용은 변경되지 않았으므로 4 표준 워크플로우는 모두 isDirty=false 로 skip 되고,
        // seedAll() 말미의 repairDefaultMappings() 가 이미 유효한 매핑을 재확인만 한다(수정 없음, 멱등).
        yamlSeedService.seedAll()

        // 재부팅 후에도 같은 workflow 를 가리키며 생존한다(R6-B "재시드 생존") — 중복 삽입도 없다.
        assertThat(defaultMappingWorkflowId(schemeId)).isEqualTo(validWorkflowId)
        assertThat(defaultMappingRowCount(schemeId)).isOne()
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    private fun createProject(
        rawToken: String,
        key: String,
        name: String,
    ): ResponseEntity<String> {
        val body = """{"key":"$key","name":"$name"}"""
        return rest.postForEntity(
            "http://localhost:$port/api/v1/projects",
            HttpEntity(body, jsonAuthHeaders(rawToken)),
            String::class.java,
        )
    }

    private fun createIssue(
        rawToken: String,
        projectKey: String,
        summary: String,
    ): ResponseEntity<String> {
        val body = """{"projectKey":"$projectKey","summary":"$summary"}"""
        return rest.postForEntity(
            "http://localhost:$port/api/v1/issues",
            HttpEntity(body, jsonAuthHeaders(rawToken)),
            String::class.java,
        )
    }

    private fun jsonAuthHeaders(rawToken: String): HttpHeaders =
        HttpHeaders().apply {
            set(HttpHeaders.AUTHORIZATION, "$BEARER_PREFIX$rawToken")
            contentType = MediaType.APPLICATION_JSON
        }

    // ── 시드 ────────────────────────────────────────────────────────────────────

    /** SYSTEM_ADMIN 행위자 + PAT 를 심는다. 빈 DB 라 CREATE_PROJECT grant 시드보다 SYSTEM_ADMIN 이 더 간단하다. */
    private fun seedSystemAdminUser() {
        jdbc.update("INSERT INTO users (id, username) VALUES (?, ?)", USER_ADMIN_ID, USERNAME_ADMIN)
        jdbc.update(
            "INSERT INTO personal_access_tokens (id, user_id, name, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?, NOW() + INTERVAL '1 hour')",
            UUID.randomUUID(),
            USER_ADMIN_ID,
            "task12-s10-pat",
            sha256Hex(RAW_TOKEN_ADMIN),
        )
        jdbc.update("INSERT INTO system_role_assignments (user_id, role) VALUES (?, 'SYSTEM_ADMIN')", USER_ADMIN_ID)
    }

    /** raw PAT → SHA-256 소문자 hex 64자 — PersonalAccessTokenService.sha256Hex 와 동일 알고리즘. */
    private fun sha256Hex(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── DB 조회/오염 (project-workflow 표준 스킴 — S11 전용) ─────────────────────────

    private fun findSchemeId(schemeKey: String): Long =
        jdbc.queryForObject("SELECT id FROM workflow_schemes WHERE key = ?", Long::class.java, schemeKey)
            ?: error("스킴 없음: $schemeKey")

    private fun findWorkflowId(workflowKey: String): UUID =
        jdbc.queryForObject("SELECT id FROM workflows WHERE key = ?", UUID::class.java, workflowKey)
            ?: error("워크플로우 없음: $workflowKey")

    private fun defaultMappingWorkflowId(schemeId: Long): UUID =
        jdbc.queryForObject(
            "SELECT workflow_id FROM workflow_scheme_issue_type_mappings WHERE scheme_id = ? AND issue_type_id IS NULL",
            UUID::class.java,
            schemeId,
        ) ?: error("default 매핑 없음: schemeId=$schemeId")

    /** S11 멱등성 확인용 — 재부팅 대리 전후로 default 매핑이 중복 생성되지 않았는지 센다. */
    private fun defaultMappingRowCount(schemeId: Long): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM workflow_scheme_issue_type_mappings WHERE scheme_id = ? AND issue_type_id IS NULL",
            Int::class.java,
            schemeId,
        ) ?: 0

    companion object {
        /**
         * JVM 단위 singleton 빈 PostgreSQL 컨테이너 — `.apply { start() }` 로 JVM 시작 시점에 기동되며,
         * Ryuk 이 JVM 종료 시 자동 정리한다([com.bts.search.savedfilter.persistence.SearchPersistenceTestBase]
         * 동형 패턴). pgmq 확장 사전 설치 이미지가 필요하다(클래스 KDoc 참조).
         */
        @JvmStatic
        val emptyDbPostgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_task12_empty_boot_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        /**
         * `spring.datasource.*` 를 위 빈 컨테이너로 override — [ProdAssemblyHttpTestBase.props] 와 함께
         * Spring 이 이 클래스 전용의 새 컨텍스트 캐시 키를 만들도록 한다(클래스 KDoc "왜 그대로 상속하는가").
         */
        @JvmStatic
        @DynamicPropertySource
        fun emptyDbProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { emptyDbPostgres.jdbcUrl }
            registry.add("spring.datasource.username") { emptyDbPostgres.username }
            registry.add("spring.datasource.password") { emptyDbPostgres.password }
        }

        const val BEARER_PREFIX = "Bearer "

        /** PAT raw token prefix — PersonalAccessToken.TOKEN_PREFIX 미러링([ProjectCreatePermissionProdBootTest] 동형). */
        const val PAT_PREFIX = "pat_"

        /** PAT body 길이(base62) — PersonalAccessToken.TOKEN_BODY_LENGTH 와 동일. */
        const val PAT_BODY_LENGTH = 48

        val USER_ADMIN_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        const val USERNAME_ADMIN = "task12-s10-system-admin"
        val RAW_TOKEN_ADMIN = PAT_PREFIX + "task12s10".padEnd(PAT_BODY_LENGTH, '0')

        /** 테스트 전용 프로젝트 key(2~10자, `^[A-Z][A-Z0-9]{1,9}$`). */
        const val PROJECT_KEY = "PJTA"

        const val SOFTWARE_SCHEME_KEY = "software-scheme"
        const val SOFTWARE_DEFAULT_WORKFLOW_KEY = "software-default"
    }
}

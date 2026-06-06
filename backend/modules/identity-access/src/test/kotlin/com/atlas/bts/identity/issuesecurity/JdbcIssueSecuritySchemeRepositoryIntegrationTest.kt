// JdbcIssueSecuritySchemeRepository 통합테스트 (prod 프로파일 + 실 PostgreSQL 16) — FR-PM-06 PR-A Task 3

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID

/**
 * [JdbcIssueSecuritySchemeRepository] 통합테스트 (FR-PM-06 PR-A Task 3).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V016 마이그레이션이 적용된 뒤,
 * [IssueSecuritySchemeRepository] 포트의 스킴/등급/멤버 aggregate 연산을 raw SQL 구현으로
 * end-to-end 검증한다.
 *
 * - **스킴 create / findById / findAll / update / delete** — RETURNING 으로 DB 가 채운
 *   id/타임스탬프 회수, findById 는 소속 등급을 함께 로드, delete 는 등급/멤버까지 CASCADE.
 * - **등급 addLevel / listLevels / updateLevel / deleteLevel** — 스킴 소속 등급 CRUD.
 * - **멤버 addMember / listMembers / removeMemberById** — 다형 5종, 중복 멱등(ON CONFLICT DO NOTHING).
 * - **name UNIQUE** — 중복 스킴 이름 create 시 [DuplicateKeyException] 전파.
 * - **is_default 부분 유니크** — 스킴당 둘째 기본 등급 추가 시 무결성 위반 전파.
 *
 * ## Testcontainers 설계
 * [com.atlas.bts.identity.group.JdbcUserGroupRepositoryIntegrationTest] 와 동일한 prod 부팅 셋업
 * (static @Container + @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 복제한다.
 * project_id 는 cross-BC 참조라 FK 가 없어 임의 UUID 로 멤버 픽스처를 구성한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
@Testcontainers
class JdbcIssueSecuritySchemeRepositoryIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로 (PemFileKeyProvider 요구).
         * PemFileKeyProvider 가 BouncyCastle PEMParser 를 사용하므로 BC provider 를 먼저 등록한다.
         */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val privKey = keyPair.private
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(privKey.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 prod 컨텍스트 부팅 (선례 동일)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    @Autowired
    private lateinit var repository: IssueSecuritySchemeRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // 스킴 삭제 → 등급/멤버 CASCADE 로 동반 삭제(테스트 격리).
        jdbc.update("DELETE FROM issue_security_schemes WHERE name LIKE :prefix", mapOf("prefix" to "fr-pm-06-t3-%"))
    }

    // ── 스킴 create / findById ─────────────────────────────────────────────────────

    @Test
    fun `create는 id와 타임스탬프가 채워진 스킴을 반환하고 findById로 재조회된다`() {
        val created = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-create", "설명"))

        assertThat(created.id).isNotNull()
        assertThat(created.name).isEqualTo("fr-pm-06-t3-create")
        assertThat(created.description).isEqualTo("설명")
        assertThat(created.createdAt).isNotNull()
        assertThat(created.updatedAt).isNotNull()

        val found = repository.findById(created.id!!)
        assertThat(found).isNotNull()
        assertThat(found!!.scheme.name).isEqualTo("fr-pm-06-t3-create")
        assertThat(found.levels).isEmpty()
    }

    @Test
    fun `description이 null인 스킴도 생성된다`() {
        val created = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-null-desc", null))

        assertThat(created.description).isNull()
    }

    @Test
    fun `존재하지 않는 스킴 id 조회 시 null을 반환한다`() {
        assertThat(repository.findById(UUID.randomUUID())).isNull()
    }

    // ── 스킴 findById(등급 포함) ───────────────────────────────────────────────────

    @Test
    fun `findById는 소속 등급을 함께 로드한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-with-levels", null))
        repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "임원만", null, isDefault = true))
        repository.addLevel(IssueSecurityLevel.create(scheme.id, "내부용", null, isDefault = false))

        val found = repository.findById(scheme.id)

        assertThat(found).isNotNull()
        assertThat(found!!.levels.map { it.name }).containsExactlyInAnyOrder("임원만", "내부용")
    }

    // ── 스킴 findAll ───────────────────────────────────────────────────────────────

    @Test
    fun `findAll은 각 스킴의 등급을 함께 반환한다`() {
        val a = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-all-a", null))
        val b = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-all-b", null))
        repository.addLevel(IssueSecurityLevel.create(a.id!!, "a-1", null, isDefault = false))
        repository.addLevel(IssueSecurityLevel.create(a.id, "a-2", null, isDefault = false))
        repository.addLevel(IssueSecurityLevel.create(b.id!!, "b-1", null, isDefault = false))

        val all = repository.findAll().filter { it.scheme.name.startsWith("fr-pm-06-t3-all-") }
        val byName = all.associateBy { it.scheme.name }

        assertThat(byName["fr-pm-06-t3-all-a"]!!.levels.map { it.name }).containsExactlyInAnyOrder("a-1", "a-2")
        assertThat(byName["fr-pm-06-t3-all-b"]!!.levels.map { it.name }).containsExactly("b-1")
    }

    // ── 스킴 update / delete ───────────────────────────────────────────────────────

    @Test
    fun `update는 이름과 설명을 갱신한 스킴을 반환한다`() {
        val created = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-upd", "old"))

        val updated = repository.update(created.id!!, "fr-pm-06-t3-upd-new", "new")

        assertThat(updated).isNotNull()
        assertThat(updated!!.name).isEqualTo("fr-pm-06-t3-upd-new")
        assertThat(updated.description).isEqualTo("new")
        assertThat(repository.findById(created.id)!!.scheme.name).isEqualTo("fr-pm-06-t3-upd-new")
    }

    @Test
    fun `존재하지 않는 스킴 update 시 null을 반환한다`() {
        assertThat(repository.update(UUID.randomUUID(), "x", null)).isNull()
    }

    @Test
    fun `delete는 스킴과 등급-멤버를 CASCADE로 함께 제거한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-del", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))
        repository.addMember(SecurityLevelMember.create(level.id!!, MemberType.REPORTER, null))

        assertThat(repository.delete(scheme.id)).isTrue()

        assertThat(repository.findById(scheme.id)).isNull()
        assertThat(repository.listLevels(scheme.id)).isEmpty()
        assertThat(repository.listMembers(level.id)).isEmpty()
    }

    @Test
    fun `존재하지 않는 스킴 delete 시 false를 반환한다`() {
        assertThat(repository.delete(UUID.randomUUID())).isFalse()
    }

    // ── 등급 addLevel / listLevels / updateLevel / deleteLevel ─────────────────────

    @Test
    fun `addLevel은 id와 createdAt이 채워진 등급을 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-add", null))

        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "임원만", "설명", isDefault = true))

        assertThat(level.id).isNotNull()
        assertThat(level.schemeId).isEqualTo(scheme.id)
        assertThat(level.name).isEqualTo("임원만")
        assertThat(level.description).isEqualTo("설명")
        assertThat(level.isDefault).isTrue()
        assertThat(level.createdAt).isNotNull()
    }

    @Test
    fun `listLevels는 스킴의 모든 등급을 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-list", null))
        repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "l1", null, isDefault = false))
        repository.addLevel(IssueSecurityLevel.create(scheme.id, "l2", null, isDefault = false))

        assertThat(repository.listLevels(scheme.id).map { it.name }).containsExactlyInAnyOrder("l1", "l2")
    }

    @Test
    fun `updateLevel은 이름-설명-기본여부를 갱신한 등급을 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-upd", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "old", null, isDefault = false))

        val updated = repository.updateLevel(level.id!!, "new", "desc", isDefault = true)

        assertThat(updated).isNotNull()
        assertThat(updated!!.name).isEqualTo("new")
        assertThat(updated.description).isEqualTo("desc")
        assertThat(updated.isDefault).isTrue()
    }

    @Test
    fun `존재하지 않는 등급 updateLevel 시 null을 반환한다`() {
        assertThat(repository.updateLevel(UUID.randomUUID(), "x", null, isDefault = false)).isNull()
    }

    // ── 등급 findLevelById (plan T5 갭 보강 — 없는 등급 404 vs 빈 등급 200 구분) ────

    @Test
    fun `findLevelById는 존재하는 등급을 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-find", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "임원만", "설명", isDefault = true))

        val found = repository.findLevelById(level.id!!)

        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(level.id)
        assertThat(found.schemeId).isEqualTo(scheme.id)
        assertThat(found.name).isEqualTo("임원만")
        assertThat(found.description).isEqualTo("설명")
        assertThat(found.isDefault).isTrue()
    }

    @Test
    fun `존재하지 않는 등급 findLevelById 시 null을 반환한다`() {
        assertThat(repository.findLevelById(UUID.randomUUID())).isNull()
    }

    @Test
    fun `deleteLevel은 존재하는 등급 삭제 시 true, 없으면 false를 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-del", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))

        assertThat(repository.deleteLevel(level.id!!)).isTrue()
        assertThat(repository.listLevels(scheme.id)).isEmpty()
        assertThat(repository.deleteLevel(UUID.randomUUID())).isFalse()
    }

    // ── 등급 name UNIQUE / is_default 부분 유니크 ──────────────────────────────────

    @Test
    fun `같은 스킴 내 중복 등급 이름 추가 시 DuplicateKeyException이 전파된다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-dup", null))
        repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "dup", null, isDefault = false))

        assertThatThrownBy {
            repository.addLevel(IssueSecurityLevel.create(scheme.id, "dup", null, isDefault = false))
        }.isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun `스킴당 둘째 기본 등급 추가 시 부분 유니크 위반이 전파된다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-lvl-default", null))
        repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "d1", null, isDefault = true))

        assertThatThrownBy {
            repository.addLevel(IssueSecurityLevel.create(scheme.id, "d2", null, isDefault = true))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // ── 멤버 addMember(다형) / listMembers / removeMemberById ───────────────────────

    @Test
    fun `addMember는 다형 5종을 모두 저장하고 listMembers로 조회된다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-member", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))
        val userId = UUID.randomUUID().toString()
        val groupId = UUID.randomUUID().toString()

        repository.addMember(SecurityLevelMember.create(level.id!!, MemberType.REPORTER, null))
        repository.addMember(SecurityLevelMember.create(level.id, MemberType.ASSIGNEE, null))
        repository.addMember(SecurityLevelMember.create(level.id, MemberType.USER, userId))
        repository.addMember(SecurityLevelMember.create(level.id, MemberType.PROJECT_ROLE, "PROJECT_ADMIN"))
        repository.addMember(SecurityLevelMember.create(level.id, MemberType.GROUP, groupId))

        val members = repository.listMembers(level.id)

        assertThat(members.map { it.memberType }).containsExactlyInAnyOrder(
            MemberType.REPORTER,
            MemberType.ASSIGNEE,
            MemberType.USER,
            MemberType.PROJECT_ROLE,
            MemberType.GROUP,
        )
        assertThat(members.first { it.memberType == MemberType.USER }.memberValue).isEqualTo(userId)
        assertThat(members.first { it.memberType == MemberType.REPORTER }.memberValue).isNull()
    }

    @Test
    fun `addMember는 id와 createdAt이 채워진 멤버를 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-member-return", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))

        val member = repository.addMember(SecurityLevelMember.create(level.id!!, MemberType.REPORTER, null))

        assertThat(member.id).isNotNull()
        assertThat(member.levelId).isEqualTo(level.id)
        assertThat(member.createdAt).isNotNull()
    }

    @Test
    fun `addMember는 같은 멤버를 두 번 추가해도 멱등하다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-member-idem", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))
        val userId = UUID.randomUUID().toString()

        repository.addMember(SecurityLevelMember.create(level.id!!, MemberType.USER, userId))
        repository.addMember(SecurityLevelMember.create(level.id, MemberType.USER, userId))

        assertThat(repository.listMembers(level.id)).hasSize(1)
    }

    @Test
    fun `addMember는 member_value가 null인 REPORTER를 두 번 추가해도 멱등하다 (C1 회귀)`() {
        // UNIQUE NULLS NOT DISTINCT 가 없으면 NULL 끼리 서로 다른 값으로 취급돼 ON CONFLICT 미발화 → 중복 행.
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-member-idem-null", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))

        repository.addMember(SecurityLevelMember.create(level.id!!, MemberType.REPORTER, null))
        repository.addMember(SecurityLevelMember.create(level.id, MemberType.REPORTER, null))

        assertThat(repository.listMembers(level.id)).hasSize(1)
    }

    @Test
    fun `removeMemberById는 멤버를 제거하고 true, 없으면 false를 반환한다`() {
        val scheme = repository.create(IssueSecurityScheme.create("fr-pm-06-t3-member-remove", null))
        val level = repository.addLevel(IssueSecurityLevel.create(scheme.id!!, "lvl", null, isDefault = false))
        val member = repository.addMember(SecurityLevelMember.create(level.id!!, MemberType.REPORTER, null))

        assertThat(repository.removeMemberById(member.id!!)).isTrue()
        assertThat(repository.listMembers(level.id)).isEmpty()
        assertThat(repository.removeMemberById(UUID.randomUUID())).isFalse()
    }

    // ── 스킴 name UNIQUE ───────────────────────────────────────────────────────────

    @Test
    fun `중복 스킴 이름 create 시 DuplicateKeyException이 전파된다`() {
        repository.create(IssueSecurityScheme.create("fr-pm-06-t3-dup", null))

        assertThatThrownBy {
            repository.create(IssueSecurityScheme.create("fr-pm-06-t3-dup", null))
        }.isInstanceOf(DuplicateKeyException::class.java)
    }
}

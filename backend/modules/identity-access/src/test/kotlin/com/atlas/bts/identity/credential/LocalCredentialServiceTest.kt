// LocalCredentialService 단위 테스트 — Argon2id 해싱/검증 3개 케이스 + store/verifyForUser/rotate 9개 케이스

package com.atlas.bts.identity.credential

import com.atlas.bts.identity.support.SharedPostgres
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.UUID

class LocalCredentialServiceTest {
    // 기존 hash/verify 케이스는 repo 미사용. relaxed mock 으로 비활성 의존성 주입
    private val sut = LocalCredentialService(repo = mockk(relaxed = true))

    @Test
    fun `hash returns argon2id encoded string`() {
        val plain = "S3cur3P@ss!".toCharArray()

        val hash = sut.hash(plain)

        assertThat(hash).startsWith("\$argon2id\$")
    }

    @Test
    fun `verify returns true for matching plain`() {
        val plain = "S3cur3P@ss!".toCharArray()
        val hash = sut.hash(plain)

        val result = sut.verify(hash, "S3cur3P@ss!".toCharArray())

        assertTrue(result)
    }

    @Test
    fun `verify returns false for mismatching plain`() {
        val plain = "S3cur3P@ss!".toCharArray()
        val hash = sut.hash(plain)

        val result = sut.verify(hash, "WrongP@ss!".toCharArray())

        assertFalse(result)
    }

    // store 테스트

    @Test
    fun `store - 신규 user 저장 시 Repository save 호출 및 엔티티 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val savedCredential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$stubhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns savedCredential

        val result = service.store(userId, "P@ssw0rd!".toCharArray())

        assertThat(result.userId).isEqualTo(userId)
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `store - 같은 user 두 번 호출 시 UPSERT (두 번 모두 save 호출)`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val credential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$stubhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns credential

        service.store(userId, "P@ssw0rd!1".toCharArray())
        service.store(userId, "P@ssw0rd!2".toCharArray())

        verify(exactly = 2) { repo.save(any()) }
    }

    @Test
    fun `store - plain CharArray 는 호출 후 wipe 됨`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val plain = "P@ssw0rd!".toCharArray()
        val credential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$stubhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns credential

        service.store(userId, plain)

        // wipe 후 배열 내 모든 문자가 공백(' ')이어야 한다
        assertTrue(plain.all { it == ' ' })
    }

    // verifyForUser 테스트

    @Test
    fun `verifyForUser - 정상 비밀번호 일치 시 true 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        // 실제 Argon2 해시를 사전 생성
        val rawHash = service.hash("CorrectPass!".toCharArray())
        val now = Instant.now()
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = rawHash,
                createdAt = now,
                updatedAt = now,
            )

        val result = service.verifyForUser(userId, "CorrectPass!".toCharArray())

        assertTrue(result)
    }

    @Test
    fun `verifyForUser - 잘못된 비밀번호 입력 시 false 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val rawHash = service.hash("CorrectPass!".toCharArray())
        val now = Instant.now()
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = rawHash,
                createdAt = now,
                updatedAt = now,
            )

        val result = service.verifyForUser(userId, "WrongPass!".toCharArray())

        assertFalse(result)
    }

    @Test
    fun `verifyForUser - row 없음 시 dummy verify 수행 후 false 반환 (timing attack 방어)`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        // dummy verify 는 DUMMY_HASH 와 비교하므로 실제 verify 결과는 항상 false
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        every { repo.findByUserId(userId) } returns null

        val result = service.verifyForUser(userId, "AnyPass!".toCharArray())

        // false 반환 검증
        assertFalse(result)
        // findByUserId 는 반드시 1회 호출
        verify(exactly = 1) { repo.findByUserId(userId) }
    }

    @Test
    fun `verifyForUser - Argon2 verify 예외 발생 시 false 반환 (EC-07)`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        // 비정상 해시값으로 Argon2 라이브러리가 예외를 던지도록 유도
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "not-a-valid-argon2-hash",
                createdAt = now,
                updatedAt = now,
            )

        val result = service.verifyForUser(userId, "AnyPass!".toCharArray())

        // 예외가 발생해도 false 반환 — 예외 전파 금지 (EC-07)
        assertFalse(result)
    }

    // rotate 테스트

    @Test
    fun `rotate - old 비밀번호 일치 시 store 호출 및 true 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val oldHash = service.hash("OldPass!".toCharArray())
        val newCredential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$newhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = oldHash,
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns newCredential

        val result = service.rotate(userId, "OldPass!".toCharArray(), "NewPass!".toCharArray())

        assertTrue(result)
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `rotate - old 비밀번호 불일치 시 false 반환 및 Repository save 미호출`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val oldHash = service.hash("OldPass!".toCharArray())
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = oldHash,
                createdAt = now,
                updatedAt = now,
            )

        val result = service.rotate(userId, "WrongOld!".toCharArray(), "NewPass!".toCharArray())

        assertFalse(result)
        // save 가 호출되지 않아야 한다 (DB 변경 없음)
        verify(exactly = 0) { repo.save(any()) }
    }
}

/**
 * mustChangePassword 강제 변경 플래그 통합 테스트 (실 repo + Testcontainers PostgreSQL).
 *
 * store(mustChange=true) 후 플래그가 영속되고, rotate 성공 시 UPSERT SET 경로로
 * 플래그가 자동 해제(false)되는지를 실제 PostgreSQL 로 검증한다.
 * 최상위 클래스로 분리한 이유: @Nested inner class 는 바깥 companion 의
 * @DynamicPropertySource 를 적용받지 못해 datasource 주입이 누락된다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StoredPasswordCredentialRepository::class)
class LocalCredentialServiceMustChangeIntegrationTest {
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
    private lateinit var repo: StoredPasswordCredentialRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID
    private lateinit var service: LocalCredentialService

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
        service = LocalCredentialService(repo)
    }

    @Test
    fun `store mustChange=true — 플래그 true 로 영속`() {
        service.store(userId, "InitP@ss1!".toCharArray(), mustChange = true)

        val stored = repo.findByUserId(userId)
        assertThat(stored).isNotNull()
        assertThat(stored!!.mustChangePassword).isTrue()
    }

    @Test
    fun `rotate 성공 — mustChange=true 였던 자격증명이 false 로 자동 해제`() {
        // 강제 변경 대상으로 저장 (mustChange=true)
        service.store(userId, "InitP@ss1!".toCharArray(), mustChange = true)
        assertThat(repo.findByUserId(userId)!!.mustChangePassword).isTrue()

        // 정상 비밀번호 변경 → UPSERT SET 이 mustChange=false 로 덮어쓰며 자동 해제
        val rotated = service.rotate(userId, "InitP@ss1!".toCharArray(), "NewP@ss2!".toCharArray())

        assertTrue(rotated)
        val after = repo.findByUserId(userId)
        assertThat(after).isNotNull()
        assertThat(after!!.mustChangePassword).isFalse()
    }
}

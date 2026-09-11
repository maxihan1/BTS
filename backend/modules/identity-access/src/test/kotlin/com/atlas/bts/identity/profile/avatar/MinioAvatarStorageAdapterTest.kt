// MinioAvatarStorageAdapter 통합 테스트 — Testcontainers MinIO 로 put/get/delete 검증
package com.atlas.bts.identity.profile.avatar

import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName

/**
 * [MinioAvatarStorageAdapter] Testcontainers 통합 테스트.
 *
 * issue-tracking `MinioStorageAdapterTest` 의 JVM singleton 컨테이너 부팅 패턴을 복제한다.
 * (별도 base 클래스는 이번 Task 파일 범위 밖이라 companion 에 인라인한다.)
 *
 * ## 검증 항목
 * - put + get: 저장한 바이트와 회수 바이트가 동일하고 contentType 이 보존된다(statObject 회수).
 * - delete 후 get: [AvatarObjectNotFoundException] 이 발생한다(컨트롤러가 404 매핑).
 * - 없는 key get: [AvatarObjectNotFoundException] 이 발생한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioAvatarStorageAdapterTest {
    private lateinit var adapter: MinioAvatarStorageAdapter
    private val testBucket = "bts-avatars-test"

    /**
     * MinioClient + [MinioAvatarStorageAdapter] 를 초기화한다.
     * singleton 컨테이너의 동적 endpoint/credentials 를 주입한다.
     */
    @BeforeAll
    fun setup() {
        val client =
            MinioClient.builder()
                .endpoint(minioContainer.s3URL)
                .credentials(minioContainer.userName, minioContainer.password)
                .build()

        val properties =
            MinioAvatarStorageConfig.Properties(
                endpoint = minioContainer.s3URL,
                accessKey = minioContainer.userName,
                secretKey = minioContainer.password,
                avatarBucket = testBucket,
            )

        adapter = MinioAvatarStorageAdapter(client, properties)
        adapter.ensureBucket()
    }

    @Test
    fun `put 한 아바타를 get 하면 바이트와 contentType 이 일치한다`() {
        val key = "avatars/user-1/profile.png"
        val content = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        adapter.put(key, content, "image/png")

        val obj = adapter.get(key)
        assertThat(obj.contentType).isEqualTo("image/png")
        val read = obj.content.use { it.readBytes() }
        assertThat(read).isEqualTo(content)
    }

    @Test
    fun `delete 후 get 하면 AvatarObjectNotFoundException`() {
        val key = "avatars/user-2/profile.png"
        adapter.put(key, byteArrayOf(1, 2, 3), "image/png")

        adapter.delete(key)

        assertThatThrownBy { adapter.get(key) }
            .isInstanceOf(AvatarObjectNotFoundException::class.java)
    }

    @Test
    fun `없는 key 를 get 하면 AvatarObjectNotFoundException`() {
        assertThatThrownBy { adapter.get("avatars/missing/none.png") }
            .isInstanceOf(AvatarObjectNotFoundException::class.java)
    }

    companion object {
        /**
         * JVM 단위 singleton MinIO 컨테이너.
         * `.apply { start() }` 로 JVM 시작 시 한 번 기동되며 Ryuk 이 JVM 종료 시 자동 정리한다.
         */
        @JvmStatic
        val minioContainer: MinIOContainer =
            MinIOContainer(
                DockerImageName.parse("quay.io/minio/minio:RELEASE.2023-09-04T19-57-37Z")
                    .asCompatibleSubstituteFor("minio/minio"),
            )
                .apply { start() }
    }
}

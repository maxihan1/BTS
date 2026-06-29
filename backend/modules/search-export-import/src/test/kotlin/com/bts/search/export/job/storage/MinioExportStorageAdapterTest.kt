// MinioExportStorageAdapter 단위 테스트 — MockK로 MinioClient 호출 인자 및 예외 변환 검증

package com.bts.search.export.job.storage

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.errors.MinioException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * [MinioExportStorageAdapter] MockK 단위 테스트.
 *
 * MinioClient 를 MockK 로 대체해 인자 capture 및 예외 변환을 검증한다.
 * Testcontainers 통합 테스트 없이 빠른 피드백을 제공한다.
 *
 * 검증 범위.
 * - (a) put: putObject 호출 인자(bucket, key, size, contentType) capture 검증
 * - (b) openStream: getObject 호출 + InputStream 반환
 * - (c) remove: removeObject 호출 bucket/key 검증
 * - (d) 실패 시 [MinioExportStorageException] 변환
 */
class MinioExportStorageAdapterTest {
    private val bucket = "bts-exports"
    private lateinit var minioClient: MinioClient
    private lateinit var properties: MinioExportStorageConfig.Properties
    private lateinit var adapter: MinioExportStorageAdapter

    @BeforeEach
    fun setup() {
        minioClient = mockk()
        properties = MinioExportStorageConfig.Properties(
            endpoint = "http://localhost:9000",
            accessKey = "minioadmin",
            secretKey = "minioadmin",
            bucket = bucket,
        )
        adapter = MinioExportStorageAdapter(minioClient, properties)
    }

    // ── (a) put ────────────────────────────────────────────────────────────────

    @Test
    fun `put - putObject 호출 인자 검증`() {
        val argsSlot = slot<PutObjectArgs>()
        justRun { minioClient.putObject(capture(argsSlot)) }

        val content = "hello".toByteArray()
        adapter.put(
            key = "ATLAS/job-1.csv",
            inputStream = ByteArrayInputStream(content),
            size = content.size.toLong(),
            contentType = "text/csv; charset=UTF-8",
        )

        verify(exactly = 1) { minioClient.putObject(any()) }
        assertThat(argsSlot.captured.bucket()).isEqualTo(bucket)
        assertThat(argsSlot.captured.`object`()).isEqualTo("ATLAS/job-1.csv")
        assertThat(argsSlot.captured.contentType()).isEqualTo("text/csv; charset=UTF-8")
    }

    @Test
    fun `put - MinioClient 예외 → MinioExportStorageException 변환`() {
        every { minioClient.putObject(any()) } throws RuntimeException("minio error")

        assertThatThrownBy {
            adapter.put(
                key = "ATLAS/job-1.csv",
                inputStream = ByteArrayInputStream(ByteArray(0)),
                size = 0L,
                contentType = "text/csv",
            )
        }.isInstanceOf(MinioExportStorageException::class.java)
            .hasMessageContaining("ATLAS/job-1.csv")
    }

    // ── (b) openStream ─────────────────────────────────────────────────────────

    @Test
    fun `openStream - getObject 호출 후 InputStream 반환`() {
        val argsSlot = slot<GetObjectArgs>()
        val fakeResponse = mockk<GetObjectResponse>(relaxed = true)
        every { minioClient.getObject(capture(argsSlot)) } returns fakeResponse

        val result = adapter.openStream("ATLAS/job-1.csv")

        verify(exactly = 1) { minioClient.getObject(any()) }
        assertThat(argsSlot.captured.bucket()).isEqualTo(bucket)
        assertThat(argsSlot.captured.`object`()).isEqualTo("ATLAS/job-1.csv")
        assertThat(result).isSameAs(fakeResponse)
    }

    @Test
    fun `openStream - MinioClient 예외 → MinioExportStorageException 변환`() {
        every { minioClient.getObject(any<GetObjectArgs>()) } throws RuntimeException("not found")

        assertThatThrownBy { adapter.openStream("ATLAS/missing.csv") }
            .isInstanceOf(MinioExportStorageException::class.java)
            .hasMessageContaining("ATLAS/missing.csv")
    }

    // ── (c) remove ─────────────────────────────────────────────────────────────

    @Test
    fun `remove - removeObject 호출 bucket 과 key 검증`() {
        val argsSlot = slot<RemoveObjectArgs>()
        justRun { minioClient.removeObject(capture(argsSlot)) }

        adapter.remove("ATLAS/job-1.csv")

        verify(exactly = 1) { minioClient.removeObject(any()) }
        assertThat(argsSlot.captured.bucket()).isEqualTo(bucket)
        assertThat(argsSlot.captured.`object`()).isEqualTo("ATLAS/job-1.csv")
    }

    @Test
    fun `remove - MinioClient 예외 → MinioExportStorageException 변환`() {
        every { minioClient.removeObject(any<RemoveObjectArgs>()) } throws RuntimeException("network error")

        assertThatThrownBy { adapter.remove("ATLAS/job-1.csv") }
            .isInstanceOf(MinioExportStorageException::class.java)
            .hasMessageContaining("ATLAS/job-1.csv")
    }
}

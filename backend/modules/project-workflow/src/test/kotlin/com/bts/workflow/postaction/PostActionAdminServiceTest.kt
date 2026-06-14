// PostActionAdminService 단위 테스트 — MockK: repository + factory + 캐시 무효화 검증

package com.bts.workflow.postaction

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.WorkflowPostActionFactory
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PostActionAdminService 단위 테스트.
 *
 * 검증 범위.
 * - listForTransition: repository.findByTransitionId 위임, transitionId 해석 정상
 * - create: factory 검증 통과 후 insert + 캐시 무효화
 * - create 미지원 type: factory 예외 → PostActionValidationException(400)
 * - create 필수키 누락: factory 예외 → PostActionValidationException(400)
 * - create CALL_WEBHOOK 비-http url: PostActionValidationException(400)
 * - update: update + 캐시 무효화
 * - update 미존재 id: PostActionNotFoundException(404)
 * - delete: deleteById + 캐시 무효화
 * - transitionKey 형식 오류(__ 없음): PostActionNotFoundException(404)
 * - 전이 미존재: PostActionNotFoundException(404)
 */
class PostActionAdminServiceTest {

    private lateinit var repository: PostActionRepository
    private lateinit var factory: WorkflowPostActionFactory
    private lateinit var transitionResolver: PostActionTransitionResolver
    private lateinit var workflowCache: WorkflowCache
    private lateinit var service: PostActionAdminService

    private val workflowKey = "test-wf"
    private val transitionKey = "open__in_progress"
    private val transitionId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val postActionId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        repository = mockk()
        factory = mockk()
        transitionResolver = mockk()
        workflowCache = mockk()
        service = PostActionAdminService(repository, factory, transitionResolver, workflowCache)

        // 기본 stub: transitionKey 해석 성공
        every { transitionResolver.resolveTransitionId(workflowKey, "open", "in_progress") } returns transitionId
        // 기본 stub: 캐시 무효화 no-op
        justRun { workflowCache.invalidate(workflowKey) }
    }

    // ── listForTransition ─────────────────────────────────────────────────────

    @Test
    fun `listForTransition - transitionId 로 repository 위임 후 목록 반환`() {
        val rows = listOf(
            PostActionRow(
                id = postActionId,
                transitionId = transitionId,
                type = "CALL_WEBHOOK",
                config = mapOf("url" to "https://x.com", "method" to "POST"),
                displayOrder = 0,
            ),
        )
        every { repository.findByTransitionId(transitionId) } returns rows

        val result = service.listForTransition(workflowKey, transitionKey)

        assertThat(result).hasSize(1)
        assertThat(result.first().type).isEqualTo("CALL_WEBHOOK")
        verify(exactly = 1) { repository.findByTransitionId(transitionId) }
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create - factory 검증 통과 후 insert + 캐시 무효화`() {
        val config = mapOf("url" to "https://hook.example.com", "method" to "POST")
        val inserted = PostActionRow(
            id = postActionId,
            transitionId = transitionId,
            type = "CALL_WEBHOOK",
            config = config,
            displayOrder = 0,
        )
        every { factory.create("CALL_WEBHOOK", config) } returns mockk()
        every { repository.insert(transitionId, "CALL_WEBHOOK", config, 0) } returns inserted

        val result = service.create(workflowKey, transitionKey, "CALL_WEBHOOK", config, 0)

        assertThat(result.id).isEqualTo(postActionId)
        verify(exactly = 1) { factory.create("CALL_WEBHOOK", config) }
        verify(exactly = 1) { workflowCache.invalidate(workflowKey) }
    }

    @Test
    fun `create - 미지원 type 이면 PostActionValidationException(400) 발생`() {
        val config = emptyMap<String, Any?>()
        every { factory.create("UNKNOWN_TYPE", config) } throws
            IllegalArgumentException("지원하지 않는 PostAction type: 'UNKNOWN_TYPE'")

        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "UNKNOWN_TYPE", config, 0)
        }.isInstanceOf(PostActionValidationException::class.java)
    }

    @Test
    fun `create - 필수 config 키 누락이면 PostActionValidationException(400) 발생`() {
        val config = mapOf("url" to "https://x.com")  // method 누락
        every { factory.create("CALL_WEBHOOK", config) } throws
            IllegalArgumentException("PostAction config 에 필수 키 'method' 가 없습니다")

        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "CALL_WEBHOOK", config, 0)
        }.isInstanceOf(PostActionValidationException::class.java)
    }

    @Test
    fun `create - CALL_WEBHOOK url 이 http(s) 아닌 스킴이면 PostActionValidationException(400) 발생`() {
        val config = mapOf("url" to "ftp://malformed.example.com", "method" to "POST")
        every { factory.create("CALL_WEBHOOK", config) } returns mockk()

        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "CALL_WEBHOOK", config, 0)
        }.isInstanceOf(PostActionValidationException::class.java)
    }

    @Test
    fun `create - CALL_WEBHOOK url 이 빈 문자열이면 PostActionValidationException(400) 발생`() {
        val config = mapOf("url" to "", "method" to "POST")
        every { factory.create("CALL_WEBHOOK", config) } returns mockk()

        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "CALL_WEBHOOK", config, 0)
        }.isInstanceOf(PostActionValidationException::class.java)
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update - 존재하는 id 수정 후 캐시 무효화`() {
        val config = mapOf("url" to "https://updated.com", "method" to "PUT")
        val updated = PostActionRow(
            id = postActionId,
            transitionId = transitionId,
            type = "CALL_WEBHOOK",
            config = config,
            displayOrder = 10,
        )
        every { factory.create("CALL_WEBHOOK", config) } returns mockk()
        every { repository.findByTransitionId(transitionId) } returns
            listOf(PostActionRow(postActionId, transitionId, "CALL_WEBHOOK", emptyMap(), 0))
        every { repository.update(postActionId, "CALL_WEBHOOK", config, 10) } returns updated

        val result = service.update(workflowKey, transitionKey, postActionId, "CALL_WEBHOOK", config, 10)

        assertThat(result.config["url"]).isEqualTo("https://updated.com")
        verify(exactly = 1) { workflowCache.invalidate(workflowKey) }
    }

    @Test
    fun `update - 미존재 id 이면 PostActionNotFoundException(404) 발생`() {
        val config = mapOf("url" to "https://x.com", "method" to "POST")
        val nonExistentId = UUID.randomUUID()
        every { factory.create("CALL_WEBHOOK", config) } returns mockk()
        every { repository.findByTransitionId(transitionId) } returns emptyList()

        assertThatThrownBy {
            service.update(workflowKey, transitionKey, nonExistentId, "CALL_WEBHOOK", config, 0)
        }.isInstanceOf(PostActionNotFoundException::class.java)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete - 존재하는 id 삭제 후 캐시 무효화`() {
        every { repository.findByTransitionId(transitionId) } returns
            listOf(PostActionRow(postActionId, transitionId, "CALL_WEBHOOK", emptyMap(), 0))
        justRun { repository.deleteById(postActionId) }

        service.delete(workflowKey, transitionKey, postActionId)

        verify(exactly = 1) { repository.deleteById(postActionId) }
        verify(exactly = 1) { workflowCache.invalidate(workflowKey) }
    }

    @Test
    fun `delete - 미존재 id 이면 PostActionNotFoundException(404) 발생`() {
        val nonExistentId = UUID.randomUUID()
        every { repository.findByTransitionId(transitionId) } returns emptyList()

        assertThatThrownBy {
            service.delete(workflowKey, transitionKey, nonExistentId)
        }.isInstanceOf(PostActionNotFoundException::class.java)
    }

    // ── transitionKey 파싱 오류 / 전이 미존재 ─────────────────────────────────

    @Test
    fun `create - transitionKey 에 __ 없으면 PostActionNotFoundException(404) 발생`() {
        val config = emptyMap<String, Any?>()

        assertThatThrownBy {
            service.create(workflowKey, "open-in_progress", "SET_FIELD", config, 0)
        }.isInstanceOf(PostActionNotFoundException::class.java)
    }

    @Test
    fun `create - 전이 미존재이면 PostActionNotFoundException(404) 발생`() {
        val config = mapOf("field" to "assignee")
        every { transitionResolver.resolveTransitionId(workflowKey, "open", "nonexistent") } returns null

        assertThatThrownBy {
            service.create(workflowKey, "open__nonexistent", "SET_FIELD", config, 0)
        }.isInstanceOf(PostActionNotFoundException::class.java)
    }
}

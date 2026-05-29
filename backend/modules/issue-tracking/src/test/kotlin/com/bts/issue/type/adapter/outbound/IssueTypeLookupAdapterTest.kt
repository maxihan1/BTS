// IssueTypeLookupAdapter 단위 테스트 — IssueTypeRepository MockK + 3 시나리오 검증

package com.bts.issue.type.adapter.outbound

import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.issue.IssueTypeRef
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [IssueTypeLookupAdapter] 단위 테스트.
 *
 * [IssueTypeRepository] 를 MockK 로 mock 처리해 DB 없이 어댑터 로직을 검증한다.
 *
 * ## 검증 항목
 *
 * 1. `lookup(emptyList())` → 빈 map 반환, DB 호출 없음.
 * 2. `lookup(존재 ID 1건)` → IssueTypeRef 단건 반환.
 * 3. `lookup(미존재 ID)` → 결과 map 에서 제외, 예외 없음.
 *
 * 결정 근거: docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md
 */
class IssueTypeLookupAdapterTest {
    private val repository: IssueTypeRepository = mockk()
    private val adapter = IssueTypeLookupAdapter(repository)

    // ── 시나리오 1. 빈 리스트 ─────────────────────────────────────────────────────

    /**
     * PRE_EXISTING: value class + relaxed mock 안티패턴, main 컴파일 실패로 잠복했다가 모듈 컴파일 복구로 노출.
     *
     * `verify(exactly = 0) { repository.findById(any()) }` 는 MockK JvmSignatureValueGenerator 가
     * IssueTypeId(value class) 파라미터에 대한 시그니처 값을 reflection 으로 생성할 때
     * 랜덤 Long 이 음수이면 require(value > 0) 를 위반한다.
     * `confirmVerified(repository)` 로 교체하면 시그니처 생성 없이 "미호출" 을 안전하게 검증한다.
     */
    @Test
    fun `lookup(emptyList) 은 빈 map 을 반환하고 DB 를 호출하지 않는다`() {
        val result = adapter.lookup(emptyList())

        assertThat(result).isEmpty()
        confirmVerified(repository)
    }

    // ── 시나리오 2. 존재하는 ID 단건 ───────────────────────────────────────────────

    @Test
    fun `lookup(존재하는 ID 1건) 은 IssueTypeRef 단건을 반환한다`() {
        val id = IssueTypeId(1L)
        val issueType = stubIssueType(id = id, key = "task", name = "Task")
        every { repository.findById(id) } returns issueType

        val result = adapter.lookup(listOf(id))

        assertThat(result).hasSize(1)
        assertThat(result[id]).isEqualTo(IssueTypeRef(key = "task", name = "Task"))
    }

    // ── 시나리오 3. 미존재 ID ─────────────────────────────────────────────────────

    @Test
    fun `lookup(미존재 ID) 는 결과 map 에서 제외하며 예외를 던지지 않는다`() {
        val unknownId = IssueTypeId(9999L)
        every { repository.findById(unknownId) } returns null

        val result = adapter.lookup(listOf(unknownId))

        assertThat(result).doesNotContainKey(unknownId)
        assertThat(result).isEmpty()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────

    /**
     * 테스트용 IssueType stub 인스턴스를 생성한다.
     *
     * @param id 이슈 타입 ID.
     * @param key 이슈 타입 키 (슬러그).
     * @param name 이슈 타입 표시 이름.
     */
    private fun stubIssueType(
        id: IssueTypeId,
        key: String,
        name: String,
    ): IssueType {
        val now = Instant.now()
        return IssueType(
            id = id,
            key = IssueTypeKey(key),
            name = name,
            description = null,
            iconName = null,
            isStandard = true,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
    }
}

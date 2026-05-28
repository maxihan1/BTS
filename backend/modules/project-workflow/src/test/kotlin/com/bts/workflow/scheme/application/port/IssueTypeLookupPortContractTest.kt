// IssueTypeLookupPort outbound port 계약 검증 — 3 시나리오 + interface 구조 reflection 확인

package com.bts.workflow.scheme.application.port

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberFunctions

/**
 * IssueTypeLookupPort outbound port 계약을 검증한다.
 *
 * Spring 컨텍스트 없이 순수 MockK + Kotlin reflection 을 사용한다.
 *
 * ## 검증 항목
 *
 * 1. [IssueTypeLookupPort] 가 interface 다.
 * 2. `lookup(ids: List<IssueTypeId>): Map<IssueTypeId, IssueTypeRef>` 메서드가 존재한다.
 * 3. `lookup(emptyList())` → empty map 반환.
 * 4. `lookup(존재하는 IssueTypeId 1건)` → IssueTypeRef 단건 반환.
 * 5. `lookup(미존재 IssueTypeId)` → 결과 map 에서 제외 (예외 없음).
 *
 * ## 설계 선례
 *
 * inbound port 계약 테스트: [com.bts.workflow.port.inbound.WorkflowTransitionPortContractTest]
 * outbound port 결정 근거: docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md
 */
class IssueTypeLookupPortContractTest {

    private val portClass: KClass<IssueTypeLookupPort> = IssueTypeLookupPort::class

    // ── 1. interface 존재 ──────────────────────────────────────────────────────

    @Test
    fun `IssueTypeLookupPort 는 interface 다`() {
        assertThat(portClass.java.isInterface).isTrue()
    }

    // ── 2. lookup 시그니처 ──────────────────────────────────────────────────────

    @Test
    fun `lookup 메서드가 존재하며 List-IssueTypeId 파라미터를 받는다`() {
        val lookupFn = portClass.memberFunctions.find { it.name == "lookup" }
        assertThat(lookupFn).isNotNull()

        // 파라미터: this(receiver) + ids(List<IssueTypeId>)
        val params = lookupFn!!.parameters
        val idsParam = params.find { it.type.classifier == List::class }
        assertThat(idsParam).isNotNull()
    }

    @Test
    fun `lookup 반환 타입은 Map 이다`() {
        val lookupFn = portClass.memberFunctions.find { it.name == "lookup" }
        assertThat(lookupFn).isNotNull()
        assertThat(lookupFn!!.returnType.classifier).isEqualTo(Map::class)
    }

    // ── 3. 빈 입력 시나리오 ──────────────────────────────────────────────────────

    @Test
    fun `lookup(emptyList) 는 빈 map 을 반환한다`() {
        val adapter = StubIssueTypeLookupPort(emptyMap())

        val result = adapter.lookup(emptyList())

        assertThat(result).isEmpty()
    }

    // ── 4. 존재하는 ID 단건 시나리오 ────────────────────────────────────────────

    @Test
    fun `lookup(존재하는 IssueTypeId 1건) 은 IssueTypeRef 단건을 반환한다`() {
        val id = IssueTypeId(1L)
        val ref = IssueTypeRef(key = "task", name = "Task")
        val adapter = StubIssueTypeLookupPort(mapOf(id to ref))

        val result = adapter.lookup(listOf(id))

        assertThat(result).hasSize(1)
        assertThat(result[id]).isEqualTo(ref)
    }

    // ── 5. 미존재 ID 시나리오 ────────────────────────────────────────────────────

    @Test
    fun `lookup(미존재 IssueTypeId) 는 결과 map 에서 제외하며 예외를 던지지 않는다`() {
        val adapter = StubIssueTypeLookupPort(emptyMap())
        val unknownId = IssueTypeId(9999L)

        val result = adapter.lookup(listOf(unknownId))

        assertThat(result).doesNotContainKey(unknownId)
    }

    // ── stub 구현체 (계약 시나리오 검증용) ────────────────────────────────────────

    /**
     * 계약 시나리오 검증용 stub 구현체.
     *
     * 생성자로 전달된 [data] map 에서 요청 ID 를 필터링해 반환한다.
     * 미존재 ID 는 자동으로 제외된다.
     */
    private class StubIssueTypeLookupPort(
        private val data: Map<IssueTypeId, IssueTypeRef>,
    ) : IssueTypeLookupPort {
        override fun lookup(ids: List<IssueTypeId>): Map<IssueTypeId, IssueTypeRef> {
            if (ids.isEmpty()) return emptyMap()
            return ids.mapNotNull { id -> data[id]?.let { id to it } }.toMap()
        }
    }
}

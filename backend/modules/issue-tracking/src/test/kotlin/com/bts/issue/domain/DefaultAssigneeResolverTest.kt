// 컴포넌트 후보에서 이슈 기본 담당자를 결정하는 순수 도메인 함수 테스트

package com.bts.issue.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class DefaultAssigneeResolverTest {

    private val leadA = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val leadB = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val existingAssignee = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000010"))

    @Test
    fun `현재 assignee 있으면 후보 무시하고 그대로 반환한다`() {
        // S3 — 이미 담당자가 지정된 이슈는 컴포넌트 리드로 덮어쓰면 안 된다.
        val comp = ComponentLead(
            id = UUID.fromString("00000000-0000-4000-8000-000000000100"),
            name = "Backend",
            leadUserId = leadA,
        )

        val result = DefaultAssigneeResolver.resolve(
            current = existingAssignee,
            candidates = listOf(comp),
        )

        assertEquals(existingAssignee, result)
    }

    @Test
    fun `assignee null이고 단일 리드 컴포넌트이면 그 리드를 반환한다`() {
        // S1 — 기본 할당 대상이 하나뿐인 경우.
        val comp = ComponentLead(
            id = UUID.fromString("00000000-0000-4000-8000-000000000100"),
            name = "Backend",
            leadUserId = leadA,
        )

        val result = DefaultAssigneeResolver.resolve(
            current = null,
            candidates = listOf(comp),
        )

        assertEquals(ActorId(leadA), result)
    }

    @Test
    fun `assignee null이고 리드 다른 두 컴포넌트이면 name 오름차순 첫 번째 리드를 반환한다`() {
        // S4 — 여러 컴포넌트 중 이름 기준으로 첫 번째 리드를 선택한다.
        val compZebra = ComponentLead(
            id = UUID.fromString("00000000-0000-4000-8000-000000000100"),
            name = "Zebra",
            leadUserId = leadA,
        )
        val compAlpha = ComponentLead(
            id = UUID.fromString("00000000-0000-4000-8000-000000000200"),
            name = "Alpha",
            leadUserId = leadB,
        )

        val result = DefaultAssigneeResolver.resolve(
            current = null,
            candidates = listOf(compZebra, compAlpha),
        )

        // "Alpha" < "Zebra" — leadB 가 선택되어야 한다.
        assertEquals(ActorId(leadB), result)
    }

    @Test
    fun `name 동률이면 id 오름차순으로 tiebreak한다`() {
        // FR6 방어 케이스 — name 이 같은 컴포넌트는 현실적으로 없지만 정렬이 결정론적이어야 한다.
        val smallerId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val largerId = UUID.fromString("00000000-0000-4000-8000-000000000002")

        val compSmall = ComponentLead(id = smallerId, name = "Same", leadUserId = leadA)
        val compLarge = ComponentLead(id = largerId, name = "Same", leadUserId = leadB)

        val result = DefaultAssigneeResolver.resolve(
            current = null,
            candidates = listOf(compLarge, compSmall),
        )

        // id 오름차순 → smallerId 컴포넌트의 leadA 가 선택되어야 한다.
        assertEquals(ActorId(leadA), result)
    }

    @Test
    fun `리드 보유 컴포넌트가 없으면 null을 반환한다`() {
        // S5 — 아무도 리드가 없으면 기본 담당자를 결정할 수 없다.
        val comp = ComponentLead(
            id = UUID.fromString("00000000-0000-4000-8000-000000000100"),
            name = "Unowned",
            leadUserId = null,
        )

        val result = DefaultAssigneeResolver.resolve(
            current = null,
            candidates = listOf(comp),
        )

        assertNull(result)
    }
}

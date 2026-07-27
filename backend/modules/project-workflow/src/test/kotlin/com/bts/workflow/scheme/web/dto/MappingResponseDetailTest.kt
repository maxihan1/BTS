// MappingResponseDetail.from 의 isDefault 판정축 단위 테스트 — 조회 실패와 기본 매핑을 구분하는지 검증 (EC-4)

package com.bts.workflow.scheme.web.dto

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [MappingResponseDetail.isDefault] 판정축 검증 (EC-4).
 *
 * ## 이 테스트만이 잡는 회귀
 * `isDefault` 를 `issueTypeKey == null`(= cross-BC 조회 결과)로 판정하면, 이슈타입이 **실재하는데
 * 조회만 실패한** 매핑이 「기본 매핑」으로 둔갑한다. 기본 매핑은 스킴 안에서 이슈타입 지정이 없는
 * 이슈 전부에 적용되는 대체값이므로, 이 둔갑은 화면에서 "이 워크플로우가 모든 이슈타입에 적용됨"
 * 이라는 **거짓 표시**가 된다. 판정은 도메인 필드 [SchemeIssueTypeMapping.issueTypeId] 로만 한다.
 *
 * 두 축을 분리해 못박는다 — (1) issueTypeId 유무, (2) issueTypeRef 유무.
 * 두 축이 독립임을 보이려면 (있음, 없음) 조합이 반드시 있어야 한다(첫 케이스).
 *
 * ## 이름 주의
 * 스킴의 `isStandard`(시스템 표준 스킴 4종 보호)와 **다른 개념**이다. 원래 두 개념이 `isDefault`
 * 한 이름을 겸하고 있었고, 이 PR 이 스킴 쪽을 `isStandard` 로 분리했다.
 */
class MappingResponseDetailTest {
    @Test
    fun `issueTypeId 가 있으나 cross-BC 조회 실패면 issueTypeKey 는 null 이지만 isDefault 는 false 다`() {
        val mapping = mappingWith(id = 1L, issueTypeId = IssueTypeId(99L))

        val detail = MappingResponseDetail.from(mapping, issueTypeRef = null, workflow = someWorkflow())

        assertThat(detail.issueTypeKey).isNull()
        assertThat(detail.issueTypeName).isNull()
        // ★ 조회 실패(ref=null)를 기본 매핑으로 단정하면 안 된다 — issueTypeId 가 판정축이다.
        assertThat(detail.isDefault).isFalse()
    }

    @Test
    fun `issueTypeId 가 null 이면 isDefault 는 true 다`() {
        val mapping = mappingWith(id = 2L, issueTypeId = null)

        val detail = MappingResponseDetail.from(mapping, issueTypeRef = null, workflow = someWorkflow())

        assertThat(detail.isDefault).isTrue()
    }

    @Test
    fun `issueTypeRef 가 있으면 키·이름이 실리고 isDefault 는 false 다`() {
        val mapping = mappingWith(id = 3L, issueTypeId = IssueTypeId(5L))
        val ref = IssueTypeRef(key = "bug", name = "버그")

        val detail = MappingResponseDetail.from(mapping, issueTypeRef = ref, workflow = someWorkflow())

        assertThat(detail.issueTypeKey).isEqualTo("bug")
        assertThat(detail.issueTypeName).isEqualTo("버그")
        assertThat(detail.isDefault).isFalse()
    }

    @Test
    fun `워크플로우 키·이름이 그대로 실린다`() {
        val detail = MappingResponseDetail.from(mappingWith(4L, null), null, someWorkflow())

        assertThat(detail.workflowKey).isEqualTo("simple")
        assertThat(detail.workflowName).isEqualTo("단순 워크플로우")
    }

    private fun mappingWith(
        id: Long,
        issueTypeId: IssueTypeId?,
    ): SchemeIssueTypeMapping =
        SchemeIssueTypeMapping(
            id = id,
            schemeId = WorkflowSchemeId(10L),
            issueTypeId = issueTypeId,
            workflowId = UUID.randomUUID(),
            createdAt = Instant.parse("2026-07-27T00:00:00Z"),
        )

    /** 최소 유효 워크플로우 — 도메인 invariant 는 states 가 비어있지 않을 것만 요구한다. */
    private fun someWorkflow(): Workflow =
        Workflow.of(
            key = "simple",
            name = "단순 워크플로우",
            states = listOf(WorkflowState(key = "open", name = "열림", category = StateCategory.TODO, displayOrder = 0)),
            transitions = emptyList(),
        )
}

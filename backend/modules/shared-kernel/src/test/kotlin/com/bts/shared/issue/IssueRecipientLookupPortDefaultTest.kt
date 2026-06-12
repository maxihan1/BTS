// IssueRecipientLookupPort default 구현 fail-safe 단위 테스트

package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [IssueRecipientLookupPort.findRecipients] default 구현 단위 테스트.
 *
 * adapter 가 등록되지 않은 환경(또는 조회 불가 시)에도 알림 과발송이 없도록
 * 빈 수신자(reporterId=null, assigneeId=null)를 반환하는 fail-safe 동작을 검증한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class IssueRecipientLookupPortDefaultTest {

    @Test
    fun `findRecipients default 는 빈 수신자를 반환한다`() {
        val port = object : IssueRecipientLookupPort {}
        val result = port.findRecipients("PROJ-1")
        assertThat(result.reporterId).isNull()
        assertThat(result.assigneeId).isNull()
    }

    @Test
    fun `findRecipients default 는 이슈 키가 달라도 동일하게 빈 수신자를 반환한다`() {
        val port = object : IssueRecipientLookupPort {}
        val result = port.findRecipients("OTHER-999")
        assertThat(result.reporterId).isNull()
        assertThat(result.assigneeId).isNull()
    }
}

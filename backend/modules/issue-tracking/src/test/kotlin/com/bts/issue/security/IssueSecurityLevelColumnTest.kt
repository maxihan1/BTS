// issue-tracking V014 검증 — issues.security_level_id 컬럼이 jOOQ 상수(ISSUES.SECURITY_LEVEL_ID)로 생성되고 nullable UUID 임을 단언

package com.bts.issue.security

import com.bts.issue.jooq.tables.references.ISSUES
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * FR-PM-06 PR-B Task 3 — issues.security_level_id 컬럼 jOOQ 생성 검증.
 *
 * 이 테스트는 jOOQ 가 생성한 상수 [ISSUES.SECURITY_LEVEL_ID] 를 직접 참조한다.
 * V014 마이그레이션 + init_codegen.sql 미러가 누락되면 상수가 생성되지 않아
 * 컴파일 자체가 실패한다(RED). 미러 누락 회귀를 컴파일 시점에 차단하는 것이 목적이다
 * (jooq-init-codegen-mirror 교훈, V006/V012 선례).
 *
 * 의미. security_level_id = NULL → 등급 미지정(모든 VIEW 통과자에게 공개).
 * 보안 등급은 identity-access BC 소유라 FK 미적용(BC 격리, V007 assignee 동형).
 */
class IssueSecurityLevelColumnTest {
    @Test
    fun `ISSUES_SECURITY_LEVEL_ID 상수가 존재하고 컬럼명이 security_level_id`() {
        // 상수 참조 자체로 jOOQ 생성 여부를 컴파일 시점에 검증.
        assertThat(ISSUES.SECURITY_LEVEL_ID.name).isEqualTo("security_level_id")
    }

    @Test
    fun `security_level_id 는 nullable UUID 타입`() {
        val dataType = ISSUES.SECURITY_LEVEL_ID.dataType
        // 등급 미지정(NULL=공개)을 허용해야 하므로 nullable.
        assertThat(dataType.nullable()).isTrue()
        // 등급 식별자는 identity-access 의 UUID PK 대응.
        assertThat(dataType.type).isEqualTo(UUID::class.java)
    }
}

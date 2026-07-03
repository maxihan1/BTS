// 퀵필터 도메인 — 보드별로 저장된 이름 붙은 필터 조합

package com.bts.agileplanning.domain

import java.util.UUID

data class QuickFilter(
    val id: UUID,
    val boardId: UUID,
    val name: String,
    val query: String,
) {
    init {
        require(name.isNotBlank()) { "QuickFilter.name must not be blank." }
        require(name.length <= 50) {
            "QuickFilter.name must be 50 characters or fewer, but was ${name.length}."
        }
    }
}

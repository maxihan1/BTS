// 전역 상태 카탈로그 쓰기 커맨드 — key 는 생성 때만 받는다

package com.bts.workflow.status.application.command

/** 상태 생성 입력. `key` 는 여기서만 정해지고 이후 불변이다. */
data class CreateStatusCommand(
    val key: String,
    val name: String,
    val description: String?,
    val category: String,
)

/**
 * 상태 수정 입력.
 *
 * ★ `key` 필드가 **없다.** 이슈·보드가 FK 없이 문자열로 참조하므로 바뀌면 조용히 끊긴다.
 * 「받지 않는다」를 런타임 검증이 아니라 **타입으로** 못박는다 — 필드를 더하는 순간
 * `StatusCrudIntegrationTest` 의 리플렉션 단언이 깨진다.
 */
data class UpdateStatusCommand(
    val name: String,
    val description: String?,
    val category: String,
)

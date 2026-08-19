// 전역 상태 카탈로그 REST DTO — 요청·응답

package com.bts.workflow.status.web.dto

import com.bts.workflow.status.application.command.CreateStatusCommand
import com.bts.workflow.status.application.command.UpdateStatusCommand
import com.bts.workflow.status.repository.StatusRow
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/** 상태 생성 요청. `key` 는 여기서만 정해지고 이후 불변이다. */
data class CreateStatusRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val key: String,
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    val description: String? = null,
    @field:Pattern(regexp = "TODO|IN_PROGRESS|DONE")
    val category: String,
) {
    // 블록 본문으로 둔다 — 식 본문이면 ktlint 가 「한 줄로 합쳐라」를, detekt 가 「120자를 넘지 마라」를
    // 동시에 요구해 교착이 된다(두 도구의 줄 길이 기준이 140 / 120 으로 다르다).
    fun toCommand(): CreateStatusCommand {
        return CreateStatusCommand(key = key, name = name, description = description, category = category)
    }
}

/**
 * 상태 수정 요청.
 *
 * ★ `key` 필드가 **없다.** 이슈·보드가 FK 없이 문자열로 참조하므로 바뀌면 조용히 끊긴다.
 * 클라이언트가 `key` 를 보내도 역직렬화에서 버려지고, 그래도 바뀌지 않는다는 것이 계약이다.
 */
data class UpdateStatusRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    val description: String? = null,
    @field:Pattern(regexp = "TODO|IN_PROGRESS|DONE")
    val category: String,
) {
    fun toCommand(): UpdateStatusCommand {
        return UpdateStatusCommand(name = name, description = description, category = category)
    }
}

/** 상태 응답. */
data class StatusResponse(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val category: String,
    val isSystem: Boolean,
) {
    companion object {
        fun from(row: StatusRow): StatusResponse =
            StatusResponse(
                id = row.id,
                key = row.key,
                name = row.name,
                description = row.description,
                category = row.category,
                isSystem = row.isSystem,
            )
    }
}

/** 생성 응답. 만들어진 상태의 id 와 key 를 준다. */
data class CreatedStatusResponse(
    val id: UUID,
    val key: String,
)

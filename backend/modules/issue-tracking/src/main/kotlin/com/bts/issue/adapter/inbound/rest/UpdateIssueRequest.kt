// PATCH /api/v1/issues/{key} 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/**
 * 이슈 수정 REST 요청 바디.
 *
 * 변경 가능한 필드만 포함한다 (partial update, RFC 7396 JSON Merge Patch).
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * ### 3-상태 sentinel 규칙 (B1)
 * - description/environment: null=무변경, ""=DB NULL 클리어, 값=설정.
 *   @Pattern 빈문자열 거부 규칙은 summary 전용 — description/environment 에 복사 금지 (빈문자열=클리어 허용).
 * - labels: null=무변경, []=전체 제거, 값=교체.
 * - priority/impact: null=무변경, 값=설정.
 *
 * @property summary 새 이슈 제목. null 이면 변경하지 않는다 (Jakarta Bean Validation `@Pattern` 은
 *   null 을 통과시키므로 RFC 7396 시맨틱과 호환). 명시적 빈 문자열 또는 공백만으로 구성된 입력은
 *   400 거부 (PR #23 adversarial F-1 — `?: ""` 제거 후 빈 문자열 명시 입력 가드 추가).
 *   regex `^(?=.*\S).+$` = 비공백 문자 1자 이상 포함 강제. 최대 200자.
 * @property typeId 새 이슈 유형 ID. null 이면 변경하지 않는다 (RFC 7396 JSON Merge Patch).
 *   양수 필수. 존재하지 않거나 비활성 타입이면 404 ISSUE_TYPE_NOT_FOUND.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 * @property description Markdown 설명. null=무변경, ""=DB NULL 클리어, 값=설정.
 *   @Pattern 적용 없음 — 빈문자열은 클리어 sentinel 로 유효하다.
 *   최대 65535자 (@Size 제한).
 * @property priority 우선순위 1..5. null=무변경. 범위 밖이면 400.
 * @property labels 라벨 목록. null=무변경, []=전체 제거, 값=교체.
 *   라벨 하나 최대 50자, 목록 최대 20개 (@Size 제한).
 * @property environment 재현 환경 설명. null=무변경, ""=DB NULL 클리어, 값=설정.
 *   @Pattern 적용 없음 — 빈문자열은 클리어 sentinel 로 유효하다.
 *   최대 1000자 (@Size 제한).
 * @property impact 영향도 1..3. null=무변경. 범위 밖이면 400.
 */
data class UpdateIssueRequest(
    @field:Size(max = 200, message = "summary는 200자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*\\S).+$",
        message = "summary가 명시되었으면 공백이 아니어야 합니다.",
    )
    val summary: String?,
    @field:Positive(message = "typeId는 양수여야 합니다.")
    val typeId: Long? = null,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long,
    @field:Size(max = 65535, message = "description은 65535자 이하여야 합니다.")
    val description: String? = null,
    @field:Min(value = 1, message = "priority는 1 이상이어야 합니다.")
    @field:Max(value = 5, message = "priority는 5 이하여야 합니다.")
    val priority: Int? = null,
    @field:Size(max = 20, message = "라벨은 최대 20개까지 허용합니다.")
    val labels: List<
        @Size(max = 50, message = "라벨 하나는 50자 이하여야 합니다.")
        String,
        >? = null,
    @field:Size(max = 1000, message = "environment는 1000자 이하여야 합니다.")
    val environment: String? = null,
    @field:Min(value = 1, message = "impact는 1 이상이어야 합니다.")
    @field:Max(value = 3, message = "impact는 3 이하여야 합니다.")
    val impact: Int? = null,
)

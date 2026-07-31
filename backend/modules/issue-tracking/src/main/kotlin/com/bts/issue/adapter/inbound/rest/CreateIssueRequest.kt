// POST /api/v1/issues 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.openapitools.jackson.nullable.JsonNullable
import java.util.UUID

/**
 * 이슈 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다 (DEVELOPMENT.md — Kotlin prefix 어노테이션 필수).
 *
 * @property projectKey 이슈를 생성할 프로젝트 키. 공백 불가.
 * @property typeId 이슈 유형 id (issue_types.id BIGINT). null 이면 컨트롤러에서 task fallback 처리.
 *   클라이언트가 양수를 전달하면 해당 타입으로 생성한다.
 * @property summary 이슈 제목. 공백 불가, 최대 200자.
 * @property componentIds 이슈에 연결할 컴포넌트 UUID 목록. 생략 시 빈 목록으로 처리한다 (FR-CM-03).
 * @property securityLevelId 이슈에 지정할 보안 등급 UUID (FR-PM-06). 생략/null 이면 등급 없음(공개).
 *   non-null 이면 서비스가 SET_ISSUE_SECURITY 권한 + 적용 스킴 소속(422)을 검증한다.
 * @property description 이슈 설명 (Markdown). null 또는 공백이면 서비스가 템플릿으로 대체한다 (FR-TM-01 옵션 C).
 *   non-blank 이면 요청 값을 그대로 사용하고 템플릿을 조회하지 않는다.
 * @property customFields 커스텀 필드 값 맵 (FR-IS-10). null 이면 빈 맵으로 처리한다.
 *   값 검증(타입/required/미정의키)은 ApplicationService 가 수행한다.
 * @property assigneeId 담당자 UUID **3-state** (FR-UX-09 B1, ADR D-2).
 *   [JsonNullable] presence 로 구분한다 — 필드 부재(undefined)=자동 배정 유지(기존 동작),
 *   명시 null=자동 배정 비활성 후 미할당 확정, 값=해당 사용자로 확정.
 *   기본값 [JsonNullable.undefined] 이므로 본문에 없으면 기존 동작 그대로다.
 *   지정한 사용자가 존재하지 않으면 서비스가 422 (`ASSIGNEE_NOT_FOUND`) 로 거부한다.
 * @property priority 우선순위 1..5 (FR-UX-09 B1). 생략/null 이면 서비스가 도메인 기본값(3, Medium)을 적용한다.
 * @property labels 라벨 목록 (FR-UX-09 B1). 생략/null 이면 빈 목록.
 *   정규화(빈 문자열 제거·중복 제거)는 도메인이 수행하며, 여기서는 개수·길이 상한만 막는다.
 */
data class CreateIssueRequest(
    @field:NotBlank(message = "projectKey는 비어 있을 수 없습니다.")
    val projectKey: String,
    @field:Positive(message = "typeId 는 양수여야 합니다.")
    val typeId: Long? = null,
    @field:NotBlank(message = "summary는 비어 있을 수 없습니다.")
    @field:Size(max = 200, message = "summary는 200자 이하여야 합니다.")
    val summary: String,
    val description: String? = null,
    val componentIds: List<UUID> = emptyList(),
    val securityLevelId: UUID? = null,
    val customFields: Map<String, Any?>? = null,
    // ★requiredMode 명시가 필요한 이유 — JsonNullable<UUID> 는 Kotlin non-null 타입이라
    //   기본값이 있어도 springdoc 이 required 로 판정한다(실측 확인). 그대로 두면 생성된
    //   클라이언트가 assigneeId 를 강제해 기존 소비자가 깨진다. 같은 함정이 componentIds 에
    //   선재하지만(List<UUID> = emptyList()) 그건 이 PR 범위 밖이다.
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val assigneeId: JsonNullable<UUID> = JsonNullable.undefined(),
    @field:Min(value = 1, message = "priority는 1 이상이어야 합니다.")
    @field:Max(value = 5, message = "priority는 5 이하여야 합니다.")
    val priority: Int? = null,
    @field:Size(max = 20, message = "라벨은 최대 20개까지 허용합니다.")
    val labels: List<String>? = null,
) {
    /**
     * 라벨 **개별 길이** 상한 검증 (FR-UX-09 B1).
     *
     * ★`List<@Size(max = 50) String>` 형태의 컨테이너 원소 제약을 쓰지 않는 이유 —
     * `UpdateIssueRequest:80` 이 정확히 그 형태로 적혀 있지만 **실제로 동작하지 않는다**(실측 확인).
     * 51자 라벨이 400 이 아니라 그대로 통과해 도메인 `require` 까지 내려가고,
     * `IssueExceptionHandler` 에 `IllegalArgumentException` 핸들러가 없어 **500** 이 된다.
     * 그 형태를 그대로 복사하면 새 경로도 같은 500 을 갖게 되므로(완제품 기준 위반),
     * 생성 경로는 실제로 동작하는 `@AssertTrue` 로 400 을 보장한다.
     *
     * 수정 경로(`PATCH /issues/{key}`)의 동일한 선재 결함은 이 PR 범위 밖 — TODOS 등재
     * (2026-07-31 Maxi 확정 D-6. 전역 `IllegalArgumentException` 핸들러는 진짜 버그를 400 으로
     * 위장할 위험이 있어 채택하지 않았다).
     *
     * `@JsonIgnore` — 검증 전용 파생 속성이라 요청 스키마에 노출하지 않는다.
     */
    @get:AssertTrue(message = "라벨 하나는 50자 이하여야 합니다.")
    @get:JsonIgnore
    val isLabelsLengthValid: Boolean
        get() = labels?.all { it.length <= LABEL_MAX_LENGTH } != false

    private companion object {
        /** 라벨 한 개의 최대 글자 수. 도메인 `Issue.kt` 의 동명 상수(파일 private)와 값이 같아야 한다. */
        const val LABEL_MAX_LENGTH = 50
    }
}

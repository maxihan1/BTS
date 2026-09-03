// 스프린트 REST API 응답 DTO — agile-planning BC (FR-BL-02 Task 5)
// MatchingDeclarationName: SprintResponses.kt 파일에 SprintResponse 클래스를 정의한다.
// 파일명은 복수형(Responses)으로 "응답 DTO 모음" 의미를 표현하고,
// 현재 클래스는 단수형(Response)이다. 향후 추가 응답 DTO(예: SprintListResponse) 수용을 위해
// 파일명을 유지하고 detekt 규칙을 억제한다.
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.domain.Sprint
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 응답 DTO.
 *
 * 스프린트 단건 조회, 생성, 수정, 전환 결과에 공통으로 사용한다.
 *
 * @property sprintId 스프린트 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property boardId 소속 보드 UUID (FR-BD-04).
 *   스프린트는 프로젝트가 아니라 **보드**에 매달린다(ADR §D2). 이 필드가 없으면 클라이언트가
 *   스프린트의 소속 보드를 알 방법이 없어, 관측이 **요청 바디**로 밀린다 — 서버가 boardId 를
 *   흘려도 화면이 멀쩡해 결함이 안 보인다.
 * @property name 스프린트 이름.
 * @property goal 스프린트 목표 설명. null 이면 목표 미설정.
 * @property status 현재 상태. `"PLANNED"` · `"ACTIVE"` · `"COMPLETED"`.
 * @property startDate 스프린트 시작일. null 이면 미지정.
 * @property endDate 스프린트 종료일. null 이면 미지정.
 * @property version 낙관적 잠금 버전.
 */
data class SprintResponse(
    val sprintId: UUID,
    val projectKey: String,
    val boardId: UUID,
    val name: String,
    val goal: String?,
    val status: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val version: Long,
) {
    companion object {
        /**
         * 도메인 [Sprint] 를 [SprintResponse] 로 변환한다.
         *
         * @param sprint 변환할 스프린트 도메인 객체.
         * @return 응답 DTO 인스턴스.
         */
        fun from(sprint: Sprint): SprintResponse =
            SprintResponse(
                sprintId = sprint.id,
                projectKey = sprint.projectKey,
                boardId = sprint.boardId,
                name = sprint.name,
                goal = sprint.goal,
                status = sprint.status.name,
                startDate = sprint.startDate,
                endDate = sprint.endDate,
                version = sprint.version,
            )
    }
}

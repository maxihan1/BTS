// 이슈 템플릿 단건 응답 DTO — IssueTemplate 도메인 객체를 REST 응답으로 매핑 (FR-TM-01 Task 6)

package com.bts.issue.template.web.dto

import com.bts.issue.template.domain.IssueTemplate
import java.time.Instant
import java.util.UUID

/**
 * 이슈 템플릿 단건 응답 DTO.
 *
 * [IssueTemplate] 도메인 객체를 REST 응답용으로 매핑한다.
 * DB 저장 후에만 응답에 포함된다.
 *
 * @property id 템플릿 UUID.
 * @property projectId 소속 프로젝트 UUID.
 * @property issueTypeId 연결된 이슈 타입 BIGINT.
 * @property name 사용자에게 노출되는 템플릿 이름.
 * @property content 이슈 본문에 채워질 Markdown 내용.
 * @property createdAt 생성 시각.
 * @property updatedAt 최종 수정 시각.
 */
data class IssueTemplateResponse(
    val id: UUID,
    val projectId: UUID,
    val issueTypeId: Long,
    val name: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * [IssueTemplate] 도메인 객체를 [IssueTemplateResponse] 로 변환한다.
         *
         * @param template 변환 대상 도메인 객체.
         * @return 변환된 [IssueTemplateResponse].
         */
        fun from(template: IssueTemplate): IssueTemplateResponse =
            IssueTemplateResponse(
                id = template.id,
                projectId = template.projectId,
                issueTypeId = template.issueTypeId,
                name = template.name,
                content = template.content,
                createdAt = template.createdAt,
                updatedAt = template.updatedAt,
            )
    }
}

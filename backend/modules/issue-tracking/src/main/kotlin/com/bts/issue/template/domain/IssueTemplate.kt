// 이슈 템플릿 Aggregate Root — 프로젝트별 이슈 본문 템플릿을 나타내는 불변 도메인 객체
package com.bts.issue.template.domain

import java.time.Instant
import java.util.UUID

/** 템플릿 name 의 최대 허용 글자 수. */
internal const val NAME_MAX = 100

/**
 * 이슈 템플릿 Aggregate Root.
 *
 * 프로젝트별로 이슈 생성 시 사용할 수 있는 본문 템플릿을 정의한다.
 * 직접 생성자 대신 [IssueTemplate.create] factory 를 통해 불변식을 검증하고 인스턴스를 얻는다.
 *
 * 불변식.
 * - [name] 은 trim 후 빈 문자열 불가, 최대 [NAME_MAX]자.
 * - [content] 는 trim 후 빈 문자열 불가.
 *
 * @property id 템플릿 UUID PK.
 * @property projectId 이 템플릿이 속한 프로젝트 UUID.
 * @property issueTypeId 연결된 이슈 타입 BIGINT FK.
 * @property name 사용자에게 노출되는 템플릿 이름.
 * @property content 이슈 본문에 채워질 마크다운 내용.
 * @property createdAt 생성 시각.
 * @property updatedAt 최종 수정 시각.
 * @property deletedAt 소프트 삭제 시각. 활성 상태이면 null.
 */
data class IssueTemplate(
    val id: UUID,
    val projectId: UUID,
    val issueTypeId: Long,
    val name: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    companion object {
        /**
         * 새 이슈 템플릿을 생성한다.
         *
         * 불변식 위반 시 [InvalidIssueTemplateException] 을 던진다.
         *
         * @param projectId 이 템플릿이 속한 프로젝트 UUID.
         * @param issueTypeId 연결된 이슈 타입 ID.
         * @param name 템플릿 이름. trim 후 빈 문자열 불가, 최대 [NAME_MAX]자.
         * @param content 이슈 본문 내용. trim 후 빈 문자열 불가.
         * @return 생성된 [IssueTemplate] 인스턴스.
         * @throws InvalidIssueTemplateException 불변식 위반 시.
         */
        fun create(
            projectId: UUID,
            issueTypeId: Long,
            name: String,
            content: String,
        ): IssueTemplate {
            validateName(name)
            validateContent(content)
            val now = Instant.now()
            return IssueTemplate(
                id = UUID.randomUUID(),
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = name.trim(),
                content = content.trim(),
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        }
    }

    /**
     * 변경 가능한 필드(name / content)를 교체하여 새 [IssueTemplate] 인스턴스를 반환한다.
     *
     * null = 무변경. copy() 를 직접 사용하지 않고 이 메서드를 경유함으로써
     * create 와 동일한 불변식 검증([validateName], [validateContent])이 항상 적용된다.
     * (메모리: patch-merge-domain-bypass)
     *
     * @param name 새 이름. null 이면 기존 유지. 전달 시 blank 는 422 거부됨.
     * @param content 새 본문 내용. null 이면 기존 유지. 전달 시 blank 는 422 거부됨.
     * @return 변경된 [IssueTemplate] 인스턴스.
     * @throws InvalidIssueTemplateException name 또는 content 불변식 위반 시.
     */
    fun withChanges(
        name: String? = null,
        content: String? = null,
    ): IssueTemplate {
        val newName = name ?: this.name
        val newContent = content ?: this.content
        validateName(newName)
        validateContent(newContent)
        return copy(
            name = newName.trim(),
            content = newContent.trim(),
        )
    }
}

/**
 * [IssueTemplate.name] 불변식 검증.
 *
 * - trim 후 빈 문자열이면 [InvalidIssueTemplateException] 을 던진다.
 * - trim 후 [NAME_MAX] 초과이면 [InvalidIssueTemplateException] 을 던진다.
 *
 * @param name 검증할 name 값.
 * @throws InvalidIssueTemplateException 불변식 위반 시.
 */
private fun validateName(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        throw InvalidIssueTemplateException("name must not be blank")
    }
    if (trimmed.length > NAME_MAX) {
        throw InvalidIssueTemplateException(
            "name must be $NAME_MAX characters or fewer, but was ${trimmed.length}",
        )
    }
}

/**
 * [IssueTemplate.content] 불변식 검증.
 *
 * - trim 후 빈 문자열이면 [InvalidIssueTemplateException] 을 던진다.
 *
 * @param content 검증할 content 값.
 * @throws InvalidIssueTemplateException 불변식 위반 시.
 */
private fun validateContent(content: String) {
    if (content.trim().isEmpty()) {
        throw InvalidIssueTemplateException("content must not be blank")
    }
}

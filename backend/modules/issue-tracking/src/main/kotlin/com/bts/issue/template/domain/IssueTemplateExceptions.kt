// 이슈 템플릿 BC 도메인 예외 계층 — sealed 베이스 + 3 서브클래스
package com.bts.issue.template.domain

import java.util.UUID

/**
 * 이슈 템플릿 BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 */
sealed class IssueTemplateDomainException(message: String) : RuntimeException(message)

/**
 * 지정한 templateId 에 해당하는 활성 템플릿이 존재하지 않을 때.
 *
 * HTTP 404 매핑은 예외 핸들러에서 처리한다.
 *
 * @param templateId 조회 대상 템플릿 UUID.
 */
class IssueTemplateNotFoundException(templateId: UUID) :
    IssueTemplateDomainException("Issue template not found: $templateId")

/**
 * 같은 프로젝트 내에서 동일한 name 의 활성 템플릿이 이미 존재할 때.
 *
 * 부분 유니크 인덱스가 23505(unique_violation)를 던지면 ApplicationService 에서 이 예외로 변환한다.
 *
 * HTTP 409 매핑은 예외 핸들러에서 처리한다.
 *
 * @param name 중복이 발생한 템플릿 name.
 */
class DuplicateIssueTemplateException(name: String) :
    IssueTemplateDomainException("Issue template name already exists in this project: $name")

/**
 * 템플릿 불변식 위반 시 — name 공백·초과, content 공백 등.
 *
 * HTTP 422 매핑은 예외 핸들러에서 처리한다.
 *
 * @param reason 위반 내용 상세 메시지.
 */
class InvalidIssueTemplateException(reason: String) :
    IssueTemplateDomainException("Invalid issue template: $reason")

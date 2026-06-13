// 이슈 링크 BC 도메인 예외 계층 — sealed 베이스 + 7 서브클래스

package com.bts.issue.link.domain

import com.bts.issue.domain.IssueKey
import java.util.UUID

/**
 * 이슈 링크 도메인에서 발생하는 모든 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 *
 * 의도 HTTP 상태는 각 서브클래스 KDoc에 명시한다.
 * 실 HTTP 매핑은 Task 7의 LinkExceptionHandler 에서 처리한다.
 */
sealed class LinkDomainException(message: String) : RuntimeException(message)

/**
 * 링크 대상(source/target/parent/base) 이슈를 찾을 수 없을 때.
 *
 * 의도 HTTP 상태: 404 Not Found
 *
 * 서비스는 이슈 자연키(IssueKey)만 알고 UUID 는 모르므로, 형제
 * [com.bts.issue.domain.IssueNotFoundException] 와 동일하게 key 를 담는다
 * (placeholder UUID 노출·서비스 간 불일치 회피).
 *
 * @param issueKey 존재하지 않는 이슈 키
 */
class LinkedIssueNotFoundException(issueKey: IssueKey) :
    LinkDomainException("Issue not found: ${issueKey.value}")

/**
 * 이슈가 자기 자신을 링크 대상으로 지정할 때.
 *
 * 의도 HTTP 상태: 422 Unprocessable Entity
 *
 * @param issueId 자기참조를 시도한 이슈 UUID
 */
class LinkSelfReferenceException(issueId: UUID) :
    LinkDomainException("Issue cannot link to itself: $issueId")

/**
 * 동일한 source-target-linkType 조합의 링크가 이미 존재할 때.
 *
 * 의도 HTTP 상태: 409 Conflict
 *
 * @param sourceId 링크 출발 이슈 UUID
 * @param targetId 링크 도착 이슈 UUID
 * @param linkType 중복된 링크 타입
 */
class DuplicateLinkException(sourceId: UUID, targetId: UUID, linkType: LinkType) :
    LinkDomainException(
        "Link already exists: $sourceId --[${linkType.code}]--> $targetId",
    )

/**
 * 링크 추가 시 순환 의존이 형성될 때 (blocks 계열에서만 탐지).
 *
 * 의도 HTTP 상태: 409 Conflict
 *
 * @param sourceId 링크 출발 이슈 UUID
 * @param targetId 링크 도착 이슈 UUID
 */
class LinkCycleException(sourceId: UUID, targetId: UUID) :
    LinkDomainException("Link would create a cycle: $sourceId <-> $targetId")

/**
 * 지정한 링크 ID에 해당하는 링크가 존재하지 않을 때.
 *
 * 의도 HTTP 상태: 404 Not Found
 *
 * @param linkId 존재하지 않는 링크 ID
 */
class LinkNotFoundException(linkId: Long) :
    LinkDomainException("Link not found: $linkId")

/**
 * 이슈가 자기 자신을 부모로 지정할 때.
 *
 * parent-child 관계는 issue_links 가 아닌 issues.parent_id 로 관리하지만,
 * 검증 로직은 link 도메인과 동일한 계층에서 처리한다.
 *
 * 의도 HTTP 상태: 422 Unprocessable Entity
 *
 * @param issueId 자기참조를 시도한 이슈 UUID
 */
class ParentSelfReferenceException(issueId: UUID) :
    LinkDomainException("Issue cannot be its own parent: $issueId")

/**
 * parent-child 관계 설정 시 순환 계층이 형성될 때.
 *
 * 의도 HTTP 상태: 409 Conflict
 *
 * @param issueId 부모 지정을 시도한 이슈 UUID
 * @param parentId 지정하려는 부모 이슈 UUID
 */
class ParentCycleException(issueId: UUID, parentId: UUID) :
    LinkDomainException("Setting parent would create a cycle: issue=$issueId, parent=$parentId")

/**
 * [LinkType.fromCode]에 전달한 코드가 알 수 없는 값일 때.
 *
 * 의도 HTTP 상태: 400 Bad Request
 *
 * @param code 알 수 없는 link_type 코드
 */
class InvalidLinkTypeCodeException(code: String) :
    LinkDomainException("Unknown link type code: $code")

/**
 * 그래프 조회 depth 파라미터가 유효하지 않을 때.
 *
 * 의도 HTTP 상태: 400 Bad Request
 *
 * @param rawValue 클라이언트가 전달한 원본 depth 문자열 (null 가능)
 */
class InvalidGraphDepthException(val rawValue: String?) :
    LinkDomainException("유효하지 않은 depth 값입니다. 1~3 사이 정수여야 합니다 (입력: $rawValue).")

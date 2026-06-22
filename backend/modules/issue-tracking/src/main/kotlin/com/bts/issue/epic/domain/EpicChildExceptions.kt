// 에픽-자식 연결 도메인 예외 6종 — FR-EP-01 Task 5 불변식 위반 신호

package com.bts.issue.epic.domain

/**
 * 에픽-자식 연결 요청에서 발생하는 모든 도메인 예외의 베이스.
 *
 * RuntimeException 을 상속하므로 Spring @Transactional 롤백 트리거 대상이다.
 * 각 서브클래스의 message 는 내부 식별자(UUID 등)를 노출하지 않는다.
 */
sealed class EpicChildDomainException(message: String) : RuntimeException(message)

/**
 * 에픽 또는 자식 이슈를 찾을 수 없거나 접근 불가할 때 (404).
 *
 * 보안 N1 — 미존재와 미가시(소프트삭제/권한없음)를 동일하게 처리해 존재 여부를 노출하지 않는다.
 */
class EpicChildNotFoundException(message: String = "이슈를 찾을 수 없습니다.") :
    EpicChildDomainException(message)

/**
 * child 와 epic 이 동일한 이슈를 가리킬 때 (422).
 */
class EpicChildSelfReferenceException(message: String = "이슈는 자기 자신의 에픽이 될 수 없습니다.") :
    EpicChildDomainException(message)

/**
 * child 이슈의 hierarchyLevel 이 0 이 아닐 때 (422).
 *
 * 에픽에 연결 가능한 이슈는 hierarchyLevel=0 (Story/Task/Bug) 만 허용된다.
 * hierarchyLevel=-1 (Subtask) 은 연결 불가.
 */
class EpicChildInvalidTypeException(message: String = "서브태스크는 에픽에 직접 연결할 수 없습니다.") :
    EpicChildDomainException(message)

/**
 * 에픽으로 지정한 대상 이슈의 hierarchyLevel 이 1 이 아닐 때 (422).
 *
 * 에픽(hierarchyLevel=1) 이 아닌 이슈를 epic 으로 지정하려 하면 발생한다.
 */
class EpicTargetNotEpicException(message: String = "지정한 이슈가 에픽 유형이 아닙니다.") :
    EpicChildDomainException(message)

/**
 * child 와 epic 이 서로 다른 프로젝트에 속할 때 (422).
 */
class EpicChildCrossProjectException(message: String = "에픽과 자식 이슈는 같은 프로젝트에 속해야 합니다.") :
    EpicChildDomainException(message)

/**
 * child 이슈가 이미 epic 에 연결되어 있을 때 (409).
 *
 * child.epicId != null 은 이미 어떤 에픽에 속해 있음을 의미한다.
 * 기존 에픽을 해제하려면 먼저 disconnect 를 호출해야 한다.
 */
class EpicChildAlreadyLinkedException(message: String = "이슈가 이미 에픽에 연결되어 있습니다.") :
    EpicChildDomainException(message)

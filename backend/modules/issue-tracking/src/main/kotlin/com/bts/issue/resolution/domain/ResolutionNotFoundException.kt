// 존재하지 않는 Resolution UUID 참조 시 발생하는 도메인 예외 — FR-IS-07 Q3 존재성 검증

package com.bts.issue.resolution.domain

import java.util.UUID

/**
 * 전이 요청에 포함된 resolutionId 가 resolutions 테이블에 존재하지 않을 때.
 *
 * [com.bts.issue.application.IssueApplicationService.transitionIssue] 에서
 * [com.bts.issue.resolution.repository.ResolutionRepository.findById] 호출 후 null 이면 발생한다.
 * 영속(applyTransition) 이전에 검증한다.
 *
 * HTTP 404 + RESOLUTION_NOT_FOUND 에러코드로 매핑한다 ([com.bts.issue.adapter.inbound.rest.IssueExceptionHandler]).
 *
 * @param id 존재하지 않는 Resolution UUID.
 */
class ResolutionNotFoundException(id: UUID) :
    RuntimeException("Resolution not found: $id")

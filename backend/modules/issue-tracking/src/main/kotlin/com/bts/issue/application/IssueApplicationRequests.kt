// IssueApplicationService 입력 DTO — application 계층 request 데이터 클래스

package com.bts.issue.application

import com.bts.issue.domain.ActorId

/**
 * 이슈 생성 요청 DTO.
 *
 * @param projectKey 이슈를 생성할 프로젝트 키. 예: "BTS".
 * @param typeId 이슈 유형 id (issue_types.id FK). V005 이후 필수.
 * @param summary 이슈 제목. 1~255자.
 * @param reporterId 이슈 생성자 ActorId.
 */
data class CreateIssueRequest(
    val projectKey: String,
    val summary: String,
    val reporterId: ActorId,
    val typeId: Long = 0L,
)

/**
 * 이슈 수정 요청 DTO (RFC 7396 JSON Merge Patch 시맨틱).
 *
 * @param summary 새 이슈 제목. null 이면 변경하지 않는다 (RFC 7396 JSON Merge Patch 시맨틱).
 * @param expectedVersion 낙관적 잠금 버전. 읽은 version 값과 일치해야 업데이트가 성공한다.
 */
data class UpdateIssueRequest(
    val summary: String?,
    val expectedVersion: Long,
)

/**
 * 이슈 전이 요청 DTO.
 *
 * workflowKey 는 [com.bts.issue.application.IssueApplicationService.transitionIssue] 가
 * [com.bts.shared.workflow.WorkflowKeyResolver] 를 통해 자동 결정한다.
 * 컨트롤러(transport 계층) 는 toStateKey 만 전달한다.
 *
 * transition identity = (from, to) — ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * @param toStateKey 목표 상태 키. 예: "in_progress".
 * @param expectedVersion 낙관적 잠금 버전.
 */
data class TransitionIssueRequest(
    val toStateKey: String,
    val expectedVersion: Long,
)

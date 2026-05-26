// IssueApplicationService 입력 DTO — application 계층 request 데이터 클래스

package com.bts.issue.application

import com.bts.issue.domain.ActorId

/**
 * 이슈 생성 요청 DTO.
 *
 * @param projectKey 이슈를 생성할 프로젝트 키. 예: "BTS".
 * @param summary 이슈 제목. 1~255자.
 * @param reporterId 이슈 생성자 ActorId.
 */
data class CreateIssueRequest(
    val projectKey: String,
    val summary: String,
    val reporterId: ActorId,
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
 * @param workflowKey 적용할 워크플로우 키. 예: "DEFAULT".
 * @param toStateKey 목표 상태 키. 예: "IN_PROGRESS".
 * @param transitionName 실행할 전이 이름. YAML 워크플로우 정의의 name 과 일치해야 한다.
 * @param expectedVersion 낙관적 잠금 버전.
 */
data class TransitionIssueRequest(
    val workflowKey: String,
    val toStateKey: String,
    val transitionName: String,
    val expectedVersion: Long,
)

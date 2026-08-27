// 상태 이관 실행 시점의 현재 상태가 매핑에 없어 옮길 대상을 정할 수 없을 때의 도메인 예외 (FR-WF-07)

package com.bts.issue.bulk.domain

import com.bts.issue.domain.IssueKey

/**
 * STATUS_MIGRATION 실행 시점에 이슈의 현재 상태가 [BulkOperationPayload.StatusMigration.mappings] 에
 * 없을 때 발생한다.
 *
 * 항목 적재 이후 누군가 그 이슈를 매핑 밖 상태로 옮겼다는 뜻이다. 임의 대상으로 밀어 넣지 않고 이 예외를
 * 올려 그 건만 [FailureReasonCode.STATE_NOT_IN_MAPPING] 으로 FAILED 로 남긴다
 * (`BulkItemExecutor` 가 번역·기록한다).
 *
 * HTTP 로 나가지 않는다 — 워커 내부 경로에서만 발생하며 `BulkItemExecutor` 가 전량 catch 한다.
 *
 * RuntimeException 을 상속하므로 Spring `@Transactional` 롤백 트리거 대상이다.
 *
 * @param issueKey 이관 대상 이슈 키.
 * @param currentStateKey 매핑에 없던 처리 시점의 현재 상태 키.
 */
class StateNotInMigrationMappingException(
    val issueKey: IssueKey,
    val currentStateKey: String,
) : RuntimeException("issue state not in migration mapping: issueKey=${issueKey.value} state=$currentStateKey")

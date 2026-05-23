// 이슈 내부 식별자 VO — UUID 기반, 이슈 키 변경에 불변

package com.bts.issue.domain

import java.util.UUID

/**
 * 이슈 내부 식별자 VO.
 *
 * [IssueKey] (`PROJ-123`) 와 별개로 존재하는 불변 UUID 식별자.
 * 이슈가 이동/이름 변경되어 키가 바뀌더라도 이 id 는 변하지 않는다.
 *
 * @property value 이슈를 식별하는 UUID.
 */
@JvmInline
value class IssueId(val value: UUID)

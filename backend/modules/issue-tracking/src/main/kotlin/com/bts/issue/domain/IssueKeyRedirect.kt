// 이슈 이동 시 old_key → new_key 영구 매핑 도메인 모델 — DATA.md §1.1 이슈 키 영속성 준수

package com.bts.issue.domain

import java.time.OffsetDateTime

/**
 * 이슈 이동 리다이렉트 레코드.
 *
 * **append-only** — 한 번 삽입된 행은 UPDATE/DELETE 불가 (DATA.md §1.1).
 * `old_key` 는 PK 이므로 동일한 old_key 에 대한 중복 삽입은 DB 에서 PK 위반으로 차단된다.
 *
 * 체인 이동(A→B→C) 시 각 단계를 개별 행으로 저장하고, 조회 시 [IssueKeyRedirectRepository.findCurrentKey]
 * 가 while 루프로 최종 키까지 순회한다.
 *
 * @property oldKey 이전 이슈 키 (PK, 불변)
 * @property newKey 이동 후 이슈 키 (불변)
 * @property redirectedAt 리다이렉트 기록 시각 (TIMESTAMPTZ)
 */
data class IssueKeyRedirect(
    val oldKey: IssueKey,
    val newKey: IssueKey,
    val redirectedAt: OffsetDateTime,
)

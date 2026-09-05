// 멘션이 어느 본문에서 나왔는지를 나타내는 출처 상수 (IssueMentioned.sourceField 의 값 집합)

package com.bts.issue.mention

/**
 * [com.bts.issue.event.IssueMentioned.sourceField] 가 가질 수 있는 값의 **전부**.
 *
 * ## 왜 enum 이 아니라 String 상수인가
 * `sourceField` 는 pgmq 로 나가는 **JSON 직렬화 계약**의 일부다. enum 으로 바꾸면 소비자 쪽에
 * 같은 enum 이 있어야 하고, 값이 하나 늘 때마다 큐에 남은 옛 메시지와 새 소비자 사이에
 * 역직렬화 불일치가 생긴다. String 을 유지하되 **생산 측 오타만 막는 것**이 목적이라
 * 상수로 승격했다 — 값이 둘이 되는 순간 리터럴은 오타가 조용히 통과하는 자리가 된다.
 *
 * ## 값을 늘릴 때
 * 소비자(notification BC)는 현재 이 필드로 **분기하지 않는다**(2026-09-05 실측 — main 코드
 * 소비처 0곳). 분기가 생긴 뒤에 값을 늘리면 그 분기의 else 가 조용히 삼키는지 먼저 확인할 것.
 */
object MentionSource {
    /** 이슈 본문(description)에서 발생한 멘션. 생성·수정 두 경로가 공유한다. */
    const val DESCRIPTION = "description"

    /** 댓글 본문에서 발생한 멘션. 이때 `IssueMentioned.commentId` 가 non-null 이다. */
    const val COMMENT = "comment"
}

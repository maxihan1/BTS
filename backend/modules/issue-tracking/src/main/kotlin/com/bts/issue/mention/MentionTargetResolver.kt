// 본문에서 알림·watcher 대상 사용자를 산출하는 무상태 유틸 (네 경로가 공유)

package com.bts.issue.mention

import com.bts.shared.user.UserLookupPort
import java.util.UUID

/**
 * 멘션 대상 산출 결과.
 *
 * @property targets 알림 수신자이자 자동 watcher 대상. UUID 오름차순(결정적 직렬화).
 *   **이 목록 하나가 두 용도를 함께 담는다** — 호출자는 이것을 이벤트와 watcher 등록에
 *   그대로 넘겨야 한다. 각자 다시 계산하면 캡이 걸렸을 때 두 목록이 갈린다(스펙 E5).
 * @property droppedByCap 캡 초과로 잘려나간 username 수. 0 이면 절단이 없었다.
 *   로깅은 호출자 책임이다 — 순수 함수 안에 로거를 두지 않는다.
 */
data class MentionTargets(
    val targets: List<UUID>,
    val droppedByCap: Int,
)

/**
 * 본문에서 **새로 멘션된** 사용자를 산출한다 (FR-MN-03).
 *
 * ## 왜 뽑았나
 * 이 규칙은 원래 `IssueApplicationService.publishMentions` 안에 인라인돼 있었고
 * **이슈 수정 경로 하나만** 썼다. FR-MN-03 이 이슈 생성 · 댓글 작성 · 댓글 수정을 더하면서
 * 같은 규칙이 네 곳에 필요해졌다. 경로마다 따로 구현하면 자기제외·캡·정렬이 경로별로 갈리고,
 * 그런 갈림은 「경로에 따라 다르게 동작」이라는 형태로만 드러나 발견이 늦다.
 *
 * ## 두 모드
 * - **전체** (`before == null`) — 이슈 생성 · 댓글 작성. 비교 대상이 없으므로 본문의 모든 멘션이 신규다.
 * - **diff** (`before != null`) — 이슈 수정 · 댓글 수정. 이미 있던 멘션은 재알림하지 않는다.
 *
 * ## 처리 순서 (순서가 규칙의 일부다)
 * ```
 *   after ──[MentionParser.extract]──► username 집합
 *                                        │
 *   before ─[extract]─► 기존 집합 ────────┤ (diff 모드에서만) 차집합
 *                                        ▼
 *                              [캡 절단 — 알파벳 오름차순]
 *                                        │  droppedByCap 산출
 *                                        ▼
 *                        [findIdsByUsernames] 실재 해석 · 미존재 드롭
 *                                        ▼
 *                              [자기 자신 제외] · [UUID 정렬]
 *                                        ▼
 *                                    targets
 * ```
 * **캡을 해석 앞에 두는 이유** — `findIdsByUsernames` 의 IN 파라미터가 비대해지는 것을 막는 것이
 * 캡의 원래 목적(H1)이기 때문이다. 해석 뒤에 자르면 쿼리는 이미 커진 뒤다.
 *
 * ## 이 함수가 하지 않는 것
 * - **수신자의 이슈 열람 권한 확인.** notification BC 의 `EventRecipientResolver.applyVisibilityFilter`
 *   가 fail-closed 로 이미 판정한다. 여기서 또 하면 판정이 두 곳에 생긴다.
 * - **로깅.** `droppedByCap` 을 반환할 뿐이고 WARN 은 호출자가 남긴다.
 *
 * ⚠ [MentionParser.MENTION_PATTERN] 은 건드리지 않는다 — `MentionExtension` 이 `\G` 앵커를
 * 그 패턴에서 파생하므로 수정하면 렌더링이 함께 깨진다.
 */
object MentionTargetResolver {
    /**
     * 이벤트 1건에 실을 멘션 대상 상한 (H1).
     *
     * IN 파라미터 비대와 거대 payload 를 막는다. `IssueApplicationService` 에 있던 같은 이름의
     * private 상수를 옮긴 것이고 값은 바뀌지 않았다.
     */
    const val MAX_MENTIONS_PER_EVENT = 50

    /**
     * @param before 변경 전 본문. `null` 이면 전체 모드(생성·작성).
     * @param after 변경 후 본문.
     * @param actor 멘션을 작성한 행위자 — 결과에서 제외된다.
     * @param userLookupPort username → id 해석 포트. 빈 주입이 아니라 인자로 받는다([MentionParser] 와 동형).
     */
    fun resolve(
        before: String?,
        after: String?,
        actor: UUID,
        userLookupPort: UserLookupPort,
    ): MentionTargets {
        val added =
            if (before == null) {
                MentionParser.extract(after)
            } else {
                MentionParser.extract(after) - MentionParser.extract(before)
            }
        if (added.isEmpty()) return MentionTargets(emptyList(), 0)

        val droppedByCap = (added.size - MAX_MENTIONS_PER_EVENT).coerceAtLeast(0)
        val capped =
            if (droppedByCap == 0) added else added.sorted().take(MAX_MENTIONS_PER_EVENT).toSet()

        val resolved = userLookupPort.findIdsByUsernames(capped)
        val targets = (resolved.values.toSet() - actor).sorted()
        return MentionTargets(targets, droppedByCap)
    }
}

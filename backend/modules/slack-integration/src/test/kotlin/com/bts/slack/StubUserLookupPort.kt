// slack-integration 통합 테스트용 settable UserLookupPort stub — displayNames/emails 등록 id만 해석

package com.bts.slack

import com.bts.shared.user.UserLookupPort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 통합 테스트가 설치자 표시명을 명시 등록하는 [UserLookupPort] stub (FR-SL-01 D6/D7 Task R2).
 *
 * cross-BC 사용자 표시명 해석 포트는 prod 에서 identity-access `UserLookupAdapter` 가 제공하나 slack
 * test-boot 컨텍스트에는 실 구현이 없다. [com.bts.slack.application.SlackInstallService] 생성자가
 * non-null [UserLookupPort] 를 요구하므로, 이 stub 을 [SlackTestcontainersConfig] 가 `@Bean` 으로 등록해
 * 컨텍스트 로드를 복구한다([SlackContextLoadTest] 회귀 방지).
 *
 * ## settable 선례 ([StubSystemPermissionResolver] 동형)
 * [displayNames] 에 등록된 id 만 표시명을 돌려주고 미등록 id 는 결과 맵에서 제외한다 — prod adapter 의
 * "미존재 id 제외" 시맨틱과 동일하다. 기본값이 비어 있어 테스트가 명시 등록하지 않는 한 아무 이름도
 * 해석되지 않는다(미해석 = installerName null).
 */
class StubUserLookupPort : UserLookupPort {
    /** id → display_name. 테스트가 채우고 비운다(스레드 안전). */
    val displayNames: MutableMap<UUID, String> = ConcurrentHashMap()

    /**
     * id → email. 테스트가 채우고 비운다(스레드 안전) — [com.bts.slack.application.SlackUserConnectionService]
     * 의 이메일 자동해석 연결(FR-SL-02 D6 Task 5)이 [findEmailById] 로 조회한다. 미등록 id 는 null(이메일
     * 없음, [com.bts.slack.application.EmailUnavailableException] 유도)이며 prod adapter 의 "미존재 시 null"
     * 시맨틱과 동일하다.
     */
    val emails: MutableMap<UUID, String> = ConcurrentHashMap()

    override fun exists(userId: UUID): Boolean = displayNames.containsKey(userId)

    override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> =
        ids.mapNotNull { id -> displayNames[id]?.let { id to it } }.toMap()

    override fun findEmailById(userId: UUID): String? = emails[userId]
}

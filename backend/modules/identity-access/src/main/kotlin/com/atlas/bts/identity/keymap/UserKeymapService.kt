// 단축키 커스터마이즈 조회/PATCH 유스케이스 서비스 — 기본값 병합 + 검증우선 (FR-PF-03 Task 4)

package com.atlas.bts.identity.keymap

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 사용자별 단축키 커스터마이즈 조회/PATCH 유스케이스 서비스 (FR-PF-03 Task 4).
 *
 * [UserKeymapRepository] 는 기본값과 다른 override 만 저장한다 — effective 키맵(5종 완비)
 * 계산은 이 서비스가 [KeymapAction.DEFAULT_BINDINGS] 와 병합해 담당한다.
 *
 * ## 검증 우선 원칙
 * [patchKeymap] 은 [KeymapValidator.validate] 를 리포지토리 조회/쓰기보다 먼저 수행한다 — 위반이
 * 하나라도 있으면 [repository] 는 호출되지 않는다(부분 적용 없음, `UserPreferencesService` 선례).
 * 위반 [KeymapViolation.category] 로 예외 타입을 분기한다 — [KeymapViolation.Category.VALIDATION]
 * 이 하나라도 있으면 [KeymapValidationException], 그 외 [KeymapViolation.Category.CONFLICT] 만
 * 있으면 [KeymapConflictException].
 *
 * @param repository user_keymap override 저장소.
 */
@Service
class UserKeymapService(
    private val repository: UserKeymapRepository,
) {
    /**
     * 사용자의 effective 단축키 목록(5종 완비)을 조회한다.
     *
     * 저장된 override 가 없는 action 은 [KeymapAction.DEFAULT_BINDINGS] 값을 그대로 노출한다.
     *
     * @param userId 조회 대상 사용자 id.
     * @return action 5종 전부를 채운 effective 키맵.
     */
    @Transactional(readOnly = true)
    fun getKeymap(userId: UUID): List<KeymapBinding> = mergeWithDefaults(repository.findByUserId(userId))

    /**
     * 단축키 5종을 replace-all PATCH 한다.
     *
     * [bindings] 를 먼저 검증하고(부분 적용 없음), 통과하면 기본값과 다른 action 만
     * [UserKeymapRepository.replaceOverrides] 로 저장한다.
     *
     * @param userId 갱신 대상 사용자 id.
     * @param bindings action 5종 완비 replace-all 요청.
     * @return 갱신 후 effective 키맵([bindings] 그대로 — replace-all 이므로).
     * @throws KeymapValidationException 화이트리스트/형식/빈값 위반이 하나라도 있을 때.
     * @throws KeymapConflictException 완전중복/leader 접두/dead-leader 충돌만 있을 때.
     */
    @Transactional
    fun patchKeymap(
        userId: UUID,
        bindings: List<KeymapBinding>,
    ): List<KeymapBinding> {
        val violations = KeymapValidator.validate(bindings)
        if (violations.isNotEmpty()) {
            throwForViolations(violations)
        }
        repository.replaceOverrides(userId, normalizeOverrides(bindings))
        return bindings
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** [overrides] 를 [KeymapAction.DEFAULT_BINDINGS] 와 병합한다(override 우선, action 기준). */
    private fun mergeWithDefaults(overrides: List<KeymapBinding>): List<KeymapBinding> {
        val overrideByAction = overrides.associateBy { it.action }
        return KeymapAction.DEFAULT_BINDINGS.map { default -> overrideByAction[default.action] ?: default }
    }

    /** [bindings] 중 기본값과 다른 것만 남긴다 — 저장할 override 목록으로 정규화. */
    private fun normalizeOverrides(bindings: List<KeymapBinding>): List<KeymapBinding> {
        val defaultByAction = KeymapAction.DEFAULT_BINDINGS.associate { it.action to it.keyCombo }
        return bindings.filter { it.keyCombo != defaultByAction[it.action] }
    }

    /**
     * [violations] 를 카테고리로 분기해 적절한 예외를 던진다.
     *
     * [KeymapViolation.Category.VALIDATION] 위반이 하나라도 있으면 그것을 우선해
     * [KeymapValidationException] 을 던지고, 그렇지 않으면(전부 CONFLICT) [KeymapConflictException]
     * 을 던진다.
     */
    private fun throwForViolations(violations: List<KeymapViolation>): Nothing {
        val hasValidationViolation = violations.any { it.category == KeymapViolation.Category.VALIDATION }
        if (hasValidationViolation) {
            throw KeymapValidationException("유효하지 않은 단축키 설정입니다.")
        }
        throw KeymapConflictException("겹치는 단축키가 있습니다.", violations)
    }
}

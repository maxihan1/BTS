// 단축키 커스터마이즈 조회/PATCH REST 컨트롤러 — PreferencesController 미러 (FR-PF-03 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.dto.KeymapBindingInput
import com.atlas.bts.identity.dto.KeymapBindingView
import com.atlas.bts.identity.dto.KeymapPatchRequest
import com.atlas.bts.identity.dto.KeymapResponse
import com.atlas.bts.identity.keymap.KeymapAction
import com.atlas.bts.identity.keymap.KeymapBinding
import com.atlas.bts.identity.keymap.KeymapConflictException
import com.atlas.bts.identity.keymap.KeymapValidationException
import com.atlas.bts.identity.keymap.KeymapViolation
import com.atlas.bts.identity.keymap.UserKeymapService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 사용자별 단축키 커스터마이즈 조회/PATCH REST 컨트롤러 ([PreferencesController] 미러, FR-PF-03 Task 5).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users`)
 * - [getMyKeymap]   GET   `/me/keymap` — 본인 effective 단축키 조회(override 병합, 5종 완비).
 * - [patchMyKeymap] PATCH `/me/keymap` — action 5종 완비 replace-all.
 *
 * ## 현재 사용자 식별 (JWT subject 전용, [PreferencesController] 와 동일 원칙)
 * [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 현재 사용자를 식별한다([currentUserId]).
 * principal 이 [Jwt] 가 아니거나(PAT 등) subject 가 UUID 형식이 아니면 401 로 거부한다.
 *
 * ## 예외 → HTTP 매핑
 * 모듈에 전역 `@RestControllerAdvice` 가 없으므로 도메인 예외를 이 컨트롤러 로컬 [ExceptionHandler] 로만
 * 상태 매핑한다(catch-all 금지 — [ResponseStatusException] 로 던지는 401 이 다른 핸들러에 삼켜지면 안
 * 된다). [KeymapValidationException] 은 400, [KeymapConflictException] 은 409 로 매핑한다.
 */
@RestController
@RequestMapping("/api/v1/users")
class KeymapController(
    private val userKeymapService: UserKeymapService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** action 안정 식별자 → 기본 key_combo — [toBindingView] 의 customized 판정용 캐시. */
    private val defaultKeyComboByAction: Map<String, String> =
        KeymapAction.DEFAULT_BINDINGS.associate { it.action to it.keyCombo }

    /**
     * GET `/api/v1/users/me/keymap` — 본인 effective 단축키를 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 [KeymapResponse](override 병합 후 action 5종 완비).
     */
    @GetMapping("/me/keymap")
    fun getMyKeymap(
        @AuthenticationPrincipal jwt: Jwt?,
    ): KeymapResponse {
        val userId = currentUserId(jwt)
        return toResponse(userKeymapService.getKeymap(userId))
    }

    /**
     * PATCH `/api/v1/users/me/keymap` — 본인 단축키 action 5종을 replace-all 로 갱신한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param req replace-all 요청 바디([KeymapPatchRequest]).
     * @return 200 갱신 후 [KeymapResponse].
     * @throws KeymapValidationException 화이트리스트/형식/빈값 위반이 하나라도 있을 때(→ 400).
     * @throws KeymapConflictException 완전중복/leader 접두/dead-leader 충돌만 있을 때(→ 409).
     */
    @PatchMapping("/me/keymap")
    fun patchMyKeymap(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody req: KeymapPatchRequest,
    ): KeymapResponse {
        val userId = currentUserId(jwt)
        val bindings = req.bindings.map(::toBinding)
        return toResponse(userKeymapService.patchKeymap(userId, bindings))
    }

    // ── 로컬 예외 핸들러 (단축키 도메인 예외 → HTTP) ────────────────────────────

    /** 화이트리스트/형식/빈값 검증 실패 → 400. 메시지는 서비스가 사용자 노출용으로 미리 작성한 일반화 값이다. */
    @ExceptionHandler(KeymapValidationException::class)
    fun handleValidation(ex: KeymapValidationException): ResponseEntity<Map<String, String>> {
        log.info("단축키 검증 실패 exceptionType={}", ex.javaClass.simpleName)
        return errorResponse(HttpStatus.BAD_REQUEST, ERROR_KEYMAP_VALIDATION, ex.message ?: DEFAULT_VALIDATION_MESSAGE)
    }

    /** 완전중복/leader 접두/dead-leader 충돌 → 409. [KeymapConflictException.conflicts] 를 `conflicts` 필드로 노출한다. */
    @ExceptionHandler(KeymapConflictException::class)
    fun handleConflict(ex: KeymapConflictException): ResponseEntity<Map<String, Any>> {
        log.info("단축키 충돌 감지 conflictCount={}", ex.conflicts.size)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "code" to ERROR_KEYMAP_CONFLICT,
                "message" to (ex.message ?: DEFAULT_CONFLICT_MESSAGE),
                "conflicts" to ex.conflicts.map(::toConflictView),
            ),
        )
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    /**
     * JWT subject(UUID) 로 현재 사용자를 식별한다([PreferencesController] 와 동일 원칙 — 리소스
     * 조회보다 먼저 인증 주체를 추출한다).
     *
     * @param jwt [AuthenticationPrincipal] 로 주입된 JWT. PAT 등 미지원 인증이면 null.
     * @return 현재 사용자 UUID.
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** effective [KeymapBinding] 목록 → [KeymapResponse]. */
    private fun toResponse(bindings: List<KeymapBinding>): KeymapResponse =
        KeymapResponse(bindings = bindings.map(::toBindingView))

    /** [KeymapBinding] → [KeymapBindingView](trigger/customized 파생). */
    private fun toBindingView(binding: KeymapBinding): KeymapBindingView =
        KeymapBindingView(
            action = binding.action,
            keyCombo = binding.keyCombo,
            trigger = binding.trigger.name.lowercase(),
            customized = binding.keyCombo != defaultKeyComboByAction[binding.action],
        )

    /** [KeymapBindingInput] → 도메인 [KeymapBinding]. */
    private fun toBinding(input: KeymapBindingInput): KeymapBinding =
        KeymapBinding(action = input.action, keyCombo = input.keyCombo)

    /**
     * [KeymapViolation] → [KeymapConflictView].
     *
     * [KeymapConflictException.conflicts] 는 [UserKeymapService] 의 카테고리 분기 불변식에 따라
     * [KeymapViolation.Category.CONFLICT] 위반만 담긴다 — VALIDATION 계열(Whitelist/Format/Blank)
     * 분기는 도달 불가한 방어적 실패로 남긴다.
     */
    private fun toConflictView(violation: KeymapViolation): KeymapConflictView =
        when (violation) {
            is KeymapViolation.Duplicate ->
                KeymapConflictView(
                    type = "duplicate",
                    actions = violation.actions.sorted(),
                    keyCombo = violation.keyCombo,
                )
            is KeymapViolation.LeaderPrefix ->
                KeymapConflictView(
                    type = "leaderPrefix",
                    actions = (violation.leaderActions + violation.singleAction).sorted(),
                )
            is KeymapViolation.DeadLeader ->
                KeymapConflictView(type = "deadLeader", actions = listOf(violation.action))
            is KeymapViolation.Whitelist, is KeymapViolation.Format, is KeymapViolation.Blank ->
                error("VALIDATION 카테고리 위반은 KeymapConflictException 에 담기지 않는다: $violation")
        }

    /** `{"code":..., "message":...}` 본문을 가진 [status] 응답을 생성한다([PreferencesController] 에러 응답 형식 일관). */
    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(status).body(mapOf("code" to code, "message" to message))
    }

    private companion object {
        /** 단축키 검증 실패 에러 코드. */
        const val ERROR_KEYMAP_VALIDATION = "KEYMAP_VALIDATION_FAILED"

        /** 단축키 충돌 에러 코드. */
        const val ERROR_KEYMAP_CONFLICT = "KEYMAP_CONFLICT"

        /** 검증 예외 message 가 비어 있을 때(도달 불가 — 예외는 항상 message 를 채운다) fallback. */
        const val DEFAULT_VALIDATION_MESSAGE = "잘못된 요청입니다."

        /** 충돌 예외 message 가 비어 있을 때(도달 불가) fallback. */
        const val DEFAULT_CONFLICT_MESSAGE = "겹치는 단축키가 있습니다."
    }
}

/**
 * 409 `KEYMAP_CONFLICT` 응답의 `conflicts` 배열 원소 하나.
 *
 * [KeymapViolation] 서브타입마다 다른 필드를 공통 형태로 정규화한다 — 어떤 action 이 겹치는지
 * ([actions]) 와 위반 종류([type])를 항상 담고, 완전중복만 겹치는 [keyCombo] 를 추가로 노출한다.
 *
 * @property type 위반 종류(`duplicate`/`leaderPrefix`/`deadLeader`).
 * @property actions 이 위반에 관련된 action id 목록(오름차순 정렬).
 * @property keyCombo 완전중복([type]=`duplicate`)일 때 겹치는 key_combo, 그 외 null.
 */
data class KeymapConflictView(
    val type: String,
    val actions: List<String>,
    val keyCombo: String? = null,
)

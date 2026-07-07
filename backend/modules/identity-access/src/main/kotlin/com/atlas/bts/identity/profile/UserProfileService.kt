// 사용자 프로필 조회/3-state PATCH/아바타 업로드-삭제-조회 유스케이스 서비스 (FR-PR-01 Task 4)

package com.atlas.bts.identity.profile

import com.atlas.bts.identity.profile.avatar.AvatarObject
import com.atlas.bts.identity.profile.avatar.AvatarObjectNotFoundException
import com.atlas.bts.identity.profile.avatar.AvatarStorageException
import com.atlas.bts.identity.profile.avatar.AvatarStoragePort
import com.atlas.bts.identity.profile.avatar.AvatarTypePolicy
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

private const val MAX_DISPLAY_NAME_LENGTH = 255
private const val DEFAULT_TIMEZONE = "UTC"

/**
 * display_name 출처 방어적 기본값 (FR-PR-04).
 *
 * users.display_name_source 컬럼은 NOT NULL(DEFAULT 'LDAP')이라 행이 존재하면 항상 non-null 이지만,
 * [UserRepository.findDisplayNameSource] 가 (경합·미존재 등으로) null 을 돌려줄 경우를 대비한 fallback.
 */
private const val DEFAULT_DISPLAY_NAME_SOURCE = "LDAP"

/**
 * 사용자 프로필 조회/수정/아바타 유스케이스 조율 서비스 (FR-PR-01 Task 4).
 *
 * users(핵심 신원) + user_profiles(확장 속성) 를 병합해 [ProfileView] 로 노출한다.
 * avatarUrl 파생(다운로드 엔드포인트 경로 조립)은 컨트롤러(Task 5) 책임이며, 이 서비스는
 * `avatar_object_key` 원본 값만 노출한다.
 *
 * ## 트랜잭션 경계
 * [uploadAvatar] / [deleteAvatar] / [getAvatar] 는 MinIO I/O 를 포함하므로 메서드 전체에
 * `@Transactional` 을 붙이지 않는다 (issue-tracking `IssueAttachmentService` 와 동일 원칙 —
 * 대용량 I/O 동안 DB 커넥션을 점유하면 커넥션 풀이 고갈된다). DB 쓰기([UserProfileRepository.setAvatarObjectKey],
 * [UserProfileRepository.clearAvatar])는 각각 단일 UPSERT/UPDATE 문이므로 리포지토리 자체
 * `@Transactional` 로 원자적이다.
 *
 * [patchProfile] 은 displayName(users) + timezone/department(user_profiles) 두 테이블에 쓰므로
 * 단일 `@Transactional` 로 감싸 부분 적용을 막는다(C2). 필드 검증은 DB 쓰기 이전(메서드 최상단)에
 * 모두 완료하므로, 검증 실패 시 아무 write 도 발생하지 않고 예외(unchecked)로 인해 빈 트랜잭션이
 * 그대로 롤백된다 — "검증 먼저, 부분 적용 없음" 요구를 별도 Bean 분리 없이 만족한다.
 *
 * @param userRepository users 테이블 접근 — displayName 갱신 + 존재 확인 + display_name 출처(source) 조회/재동기화.
 * @param profileRepository user_profiles 테이블 접근 — timezone/department/avatar 확장 속성.
 * @param storagePort 아바타 바이너리 오브젝트 스토리지 outbound port.
 * @param externalAccountRepository user_external_accounts 접근 — 외부 IdP 연결(ldapLinked) 판별 및 resync 가드 (FR-PR-04).
 *
 * ## TooManyFunctions 억제 근거
 * 단일 프로필 유스케이스(조회 / 3-state PATCH / 아바타 오케스트레이션 / FR-PR-04 재동기화)를 조율하는 서비스라
 * 응집한 public·private 헬퍼가 자연히 11개를 넘는다. 억지로 클래스를 쪼개면 오히려 응집을 해치므로
 * (ExternalAccountRepository 와 동일 판단) 클래스 단위로 명시 억제한다.
 */
@Suppress("TooManyFunctions")
@Service
class UserProfileService(
    private val userRepository: UserRepository,
    private val profileRepository: UserProfileRepository,
    private val storagePort: AvatarStoragePort,
    private val externalAccountRepository: ExternalAccountRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자 프로필을 조회한다.
     *
     * user_profiles 행이 없으면 defaults(timezone "UTC", department null, avatarObjectKey null)
     * 로 채운다 — 행을 강제로 생성하지 않는다(lazy 생성은 [patchProfile]/[uploadAvatar] 책임).
     *
     * @param userId 조회 대상 사용자 id.
     * @return 병합된 [ProfileView].
     * @throws ProfileUserNotFoundException 사용자가 BTS 에 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun getProfile(userId: UUID): ProfileView = loadView(userId)

    /**
     * 프로필을 3-state PATCH 로 부분 갱신한다.
     *
     * displayName 은 users 테이블에, timezone/department 는 user_profiles 테이블에 각각 반영한다.
     * [patch] 의 각 필드는 [ProfilePatchField.Absent](부재=미변경)와 [ProfilePatchField.Present]
     * (명시값, department 한정 null 허용=삭제)를 구분한다.
     *
     * 검증(displayName 공백/길이, timezone 형식)은 어떤 write 보다도 먼저 수행되므로
     * 하나라도 실패하면 전체가 원자적으로 무효화된다(부분 적용 없음).
     *
     * displayName 을 수정하면 [UserRepository.updateDisplayName] 의 SQL 이 users.display_name_source 를
     * `USER` 로 함께 잠근다(FR-PR-04 Task 2 — 이 서비스는 source 전환을 위한 별도 write 를 하지 않는다).
     * 반환 전 [loadView] 재조회가 그 전환을 [ProfileView.displayNameSource] 에 반영한다.
     *
     * @param userId 갱신 대상 사용자 id.
     * @param patch 3-state 변경 의도.
     * @return 갱신 후 최신 [ProfileView].
     * @throws ProfileValidationException displayName 이 공백이거나 255자 초과, 또는 timezone 이
     *   유효한 IANA 타임존이 아닐 때.
     */
    @Transactional
    fun patchProfile(
        userId: UUID,
        patch: ProfilePatch,
    ): ProfileView {
        val newDisplayName =
            validateIfPresent(patch.displayName) { name ->
                if (name.isBlank() || name.length > MAX_DISPLAY_NAME_LENGTH) {
                    throw ProfileValidationException("표시 이름은 공백일 수 없고 ${MAX_DISPLAY_NAME_LENGTH}자를 초과할 수 없습니다.")
                }
            }
        val newTimezone =
            validateIfPresent(patch.timezone) { tz ->
                try {
                    ZoneId.of(tz)
                } catch (e: DateTimeException) {
                    throw ProfileValidationException("유효하지 않은 타임존입니다.", e)
                }
            }

        if (newDisplayName != null) {
            userRepository.updateDisplayName(userId, newDisplayName)
        }
        applyProfileFields(userId, newTimezone, patch.department)

        return loadView(userId)
    }

    /**
     * display_name 동기화 출처를 LDAP 로 되돌린다 (FR-PR-04 사용자 재동기화).
     *
     * 사용자가 편집해 `source=USER` 로 잠긴 display_name 을 다시 디렉터리 동기화 대상으로 되돌려,
     * 다음 외부 IdP 재로그인(JIT 프로비저닝) 시 디렉터리 값(cn)으로 갱신되게 한다. 즉시 재설정은 하지 않는
     * 지연(lazy) semantics(Maxi 확정)이므로, 반환 뷰의 [ProfileView.displayName] 은 편집값 그대로이고
     * [ProfileView.displayNameSource] 만 `LDAP` 으로 바뀐다.
     *
     * 외부 IdP 계정이 없는 로컬 전용 사용자는 되돌릴 디렉터리 출처가 없어 거부한다. 이 판별은
     * [UserRepository.resyncDisplayNameSource] 호출 이전에 수행하므로, 거부 시 아무 write 도 발생하지 않는다.
     *
     * @param userId 대상 사용자 id(컨트롤러가 JWT subject 로 식별한 본인 — me-scope, Task 5).
     * @return source 전환 후 최신 [ProfileView].
     * @throws DisplayNameNotLdapLinkedException 외부 IdP 계정이 없어 되돌릴 디렉터리 출처가 없을 때(→ 409).
     */
    @Transactional
    fun resyncDisplayName(userId: UUID): ProfileView {
        if (!externalAccountRepository.existsByUserId(userId)) {
            throw DisplayNameNotLdapLinkedException("표시 이름을 디렉터리 값으로 되돌릴 수 없습니다.")
        }
        userRepository.resyncDisplayNameSource(userId)
        return loadView(userId)
    }

    /**
     * 아바타를 업로드한다.
     *
     * ## 실행 순서(C1 — 엄수)
     * ① [AvatarTypePolicy.validate] → ② 기존 avatar_object_key 조회(oldKey) → ③ 신규 key 생성 →
     * ④ [AvatarStoragePort.put](MinIO, tx 밖) → ⑤ [UserProfileRepository.setAvatarObjectKey](DB 커밋) →
     * ⑥ oldKey 존재 + newKey 와 다르면 커밋 후 best-effort 삭제.
     *
     * DB 커밋(⑤) 전에 old key 를 지우면, ④~⑤ 사이 실패 시 사용자가 아바타 없는 상태로 남는다 —
     * 순서를 반대로 하면 안 된다.
     *
     * @param userId 대상 사용자 id.
     * @param bytes 업로드 바이트(검증 전 원본).
     * @param contentType 클라이언트가 보낸 MIME.
     * @return 저장된 새 avatar_object_key.
     * @throws com.atlas.bts.identity.profile.avatar.AvatarValidationException MIME/크기 정책 위반 시.
     */
    fun uploadAvatar(
        userId: UUID,
        bytes: ByteArray,
        contentType: String,
    ): String {
        AvatarTypePolicy.validate(contentType, bytes.size.toLong())
        val oldKey = currentAvatarKey(userId)
        val newKey = "avatars/$userId/${UUID.randomUUID()}.${AvatarTypePolicy.extensionFor(contentType)}"

        storagePort.put(newKey, bytes, contentType)
        profileRepository.setAvatarObjectKey(userId, newKey)

        if (oldKey != null && oldKey != newKey) {
            deleteBestEffort(oldKey)
        }
        return newKey
    }

    /**
     * 아바타를 삭제한다(멱등 — 아바타가 없어도 정상 종료).
     *
     * DB 컬럼을 먼저 NULL 로 커밋한 뒤, 기존 오브젝트를 best-effort 로 삭제한다.
     *
     * @param userId 대상 사용자 id.
     */
    fun deleteAvatar(userId: UUID) {
        val oldKey = currentAvatarKey(userId)
        profileRepository.clearAvatar(userId)
        if (oldKey != null) {
            deleteBestEffort(oldKey)
        }
    }

    /**
     * 아바타 바이너리를 조회한다.
     *
     * @param userId 대상 사용자 id.
     * @return 스트림 + contentType 을 담은 [AvatarObject].
     * @throws AvatarObjectNotFoundException 아바타가 설정되어 있지 않을 때(컨트롤러가 404 매핑).
     */
    fun getAvatar(userId: UUID): AvatarObject {
        val key = currentAvatarKey(userId) ?: throw AvatarObjectNotFoundException("아바타가 설정되어 있지 않습니다.")
        return storagePort.get(key)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * users + user_profiles 를 조회해 [ProfileView] 로 병합한다(private — self-invocation 회피).
     *
     * [ProfileView] 를 만드는 유일한 지점이므로 FR-PR-04 의 `displayNameSource`(users.display_name_source)
     * 와 `ldapLinked`(외부 IdP 연결 존재) 도 여기서 채운다 — 사용자 존재 확인([UserRepository.findById])
     * 이후에 조회하므로, 미존재 사용자는 두 targeted 쿼리를 타지 않고 [ProfileUserNotFoundException] 로 끝난다.
     */
    private fun loadView(userId: UUID): ProfileView {
        val user = userRepository.findById(userId) ?: throw ProfileUserNotFoundException(userId)
        val profile = profileRepository.findByUserId(userId)
        return ProfileView(
            userId = user.id,
            username = user.username,
            email = user.email,
            displayName = user.displayName,
            avatarObjectKey = profile?.avatarObjectKey,
            timezone = profile?.timezone ?: DEFAULT_TIMEZONE,
            department = profile?.department,
            displayNameSource = userRepository.findDisplayNameSource(userId) ?: DEFAULT_DISPLAY_NAME_SOURCE,
            ldapLinked = externalAccountRepository.existsByUserId(userId),
        )
    }

    /**
     * timezone/department 중 하나라도 명시됐으면 현재값과 병합해 [UserProfileRepository.upsertProfile]
     * 을 1회 호출한다. 둘 다 부재면 아무것도 하지 않는다(불필요한 조회/쓰기 회피).
     */
    private fun applyProfileFields(
        userId: UUID,
        newTimezone: String?,
        departmentPatch: ProfilePatchField<String?>,
    ) {
        if (newTimezone == null && departmentPatch == ProfilePatchField.Absent) return

        val current = profileRepository.findByUserId(userId)
        val finalTimezone = newTimezone ?: current?.timezone ?: DEFAULT_TIMEZONE
        val finalDepartment =
            when (departmentPatch) {
                is ProfilePatchField.Present -> departmentPatch.value
                ProfilePatchField.Absent -> current?.department
            }
        profileRepository.upsertProfile(userId, finalTimezone, finalDepartment)
    }

    /**
     * [field] 가 [ProfilePatchField.Present] 면 [validator] 로 값을 검증한 뒤 그 값을 반환하고,
     * [ProfilePatchField.Absent] 면 null(변경 없음)을 반환한다.
     *
     * [validator] 는 검증 실패 시 [ProfileValidationException] 을 던진다 — displayName/timezone
     * 두 검증(각각 다른 규칙)의 "부재면 스킵" 공통 분기를 여기 한 곳으로 모은다.
     */
    private fun <T> validateIfPresent(
        field: ProfilePatchField<T>,
        validator: (T) -> Unit,
    ): T? {
        if (field !is ProfilePatchField.Present) return null
        validator(field.value)
        return field.value
    }

    /** 대상 사용자의 현재 avatar_object_key 를 조회한다(프로필 행 없으면 null) — upload/delete/get 공통 조회. */
    private fun currentAvatarKey(userId: UUID): String? = profileRepository.findByUserId(userId)?.avatarObjectKey

    /** 아바타 오브젝트 삭제를 best-effort 로 수행한다 — 실패해도 로그만 남기고 흐름을 막지 않는다. */
    private fun deleteBestEffort(objectKey: String) {
        try {
            storagePort.delete(objectKey)
        } catch (e: AvatarStorageException) {
            log.warn("아바타 오브젝트 삭제 실패 — 고아 객체 잔존 가능. cause={}", e.message)
        }
    }
}

/**
 * [UserProfileService.getProfile] / [UserProfileService.patchProfile] / [UserProfileService.resyncDisplayName]
 * 반환용 조회 뷰 (FR-PR-01, FR-PR-04).
 *
 * avatarUrl(다운로드 경로) 파생은 컨트롤러(Task 5) 책임 — 여기서는 raw `avatarObjectKey` 만 노출한다.
 *
 * @property displayNameSource display_name 값의 출처 — `LDAP`(디렉터리 동기화) 또는 `USER`(사용자 편집으로 잠김). FR-PR-04.
 * @property ldapLinked 외부 IdP(디렉터리) 계정 연결 여부 — true 면 UI 가 출처 라벨/재동기화 어포던스를 노출한다. FR-PR-04.
 *
 * `displayNameSource`/`ldapLinked` 는 보수적 기본값(출처 미상 → `LDAP` 라벨, 미연결 → false)을 가진다.
 * 프로덕션 유일 생성 지점 [UserProfileService.loadView] 는 항상 두 값을 명시로 채우므로 기본값이 쓰이지 않으며,
 * 기본값은 하위 호환(다른 구성 지점의 필드 fanout 완충)만을 위한 것이다. resync 인가 판정은 이 뷰 필드가 아니라
 * [ExternalAccountRepository.existsByUserId] 를 직접 사용하므로 기본값이 권한 경로에 영향을 주지 않는다.
 */
data class ProfileView(
    val userId: UUID,
    val username: String,
    val email: String?,
    val displayName: String,
    val avatarObjectKey: String?,
    val timezone: String,
    val department: String?,
    val displayNameSource: String = DEFAULT_DISPLAY_NAME_SOURCE,
    val ldapLinked: Boolean = false,
)

/**
 * [UserProfileService.patchProfile] 입력 — displayName/timezone/department 3-state PATCH (FR-PR-01).
 *
 * 각 필드 기본값은 [ProfilePatchField.Absent](미변경)다. department 만 `Present(null)` 로
 * 명시적 삭제를 표현할 수 있다(displayName/timezone 은 값 타입이 `String` 이라 null 표현 불가).
 */
data class ProfilePatch(
    val displayName: ProfilePatchField<String> = ProfilePatchField.Absent,
    val timezone: ProfilePatchField<String> = ProfilePatchField.Absent,
    val department: ProfilePatchField<String?> = ProfilePatchField.Absent,
)

/**
 * PATCH 필드의 3-state 표현 — 부재(미변경) vs 명시(값 또는 null) 를 구분한다 (FR-PR-01).
 *
 * BTS issue-tracking `DatePatch` 와 동형이나, 모듈 격리(C4) 를 위해 identity-access 자체로 정의한다.
 */
sealed interface ProfilePatchField<out T> {
    /** 필드가 요청 바디에 없음 — 현재값 유지. */
    data object Absent : ProfilePatchField<Nothing>

    /** 필드가 명시됨 — [value] 로 설정(department 한정 null 이면 삭제). */
    data class Present<T>(val value: T) : ProfilePatchField<T>
}

/**
 * 프로필 대상 사용자가 BTS 에 존재하지 않을 때 발생하는 예외(→ 404, 컨트롤러 매핑).
 *
 * @param userId 조회에 실패한 사용자 id.
 */
class ProfileUserNotFoundException(userId: UUID) : RuntimeException("user not found: $userId")

/**
 * 프로필 PATCH 필드 검증 실패 예외(→ 400, 컨트롤러 매핑).
 *
 * @param message 사용자 노출용 일반화 메시지(내부 정보 누출 없음).
 * @param cause 원인 예외(예: [DateTimeException]) — 체이닝 보존, 메시지 자체는 노출하지 않는다.
 */
class ProfileValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * display_name 재동기화 대상이 외부 IdP(디렉터리) 연결이 없는 사용자일 때 발생하는 예외(→ 409, 컨트롤러 매핑). FR-PR-04.
 *
 * @param message 사용자 노출용 일반화 메시지 — userId 등 내부 정보를 담지 않는다(DEVELOPMENT.md §1.2).
 */
class DisplayNameNotLdapLinkedException(message: String) : RuntimeException(message)

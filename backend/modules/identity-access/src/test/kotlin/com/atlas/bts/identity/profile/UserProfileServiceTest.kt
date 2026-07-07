// UserProfileService 단위 테스트 — 조회 병합/3-state PATCH/아바타 오케스트레이션 순서·tx경계 (FR-PR-01 Task 4)

package com.atlas.bts.identity.profile

import com.atlas.bts.identity.profile.avatar.AvatarObject
import com.atlas.bts.identity.profile.avatar.AvatarObjectNotFoundException
import com.atlas.bts.identity.profile.avatar.AvatarStoragePort
import com.atlas.bts.identity.profile.avatar.AvatarValidationException
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * [UserProfileService] 단위 테스트 (FR-PR-01 Task 4).
 *
 * MockK 기반 순수 단위 테스트 — [UserRepository] / [UserProfileRepository] / [AvatarStoragePort] 모두 mock.
 *
 * ## 테스트 시나리오
 * - getProfile: users+user_profiles 병합, profile row 없으면 defaults, 사용자 미존재 → 예외.
 * - patchProfile: displayName만/timezone만/department null 3-state/무효 timezone·blank displayName →
 *   `ProfileValidationException` + 부분 적용 없음(검증 먼저).
 * - uploadAvatar: 정책 위반 시 아무 것도 호출 안 됨(C1 순서), put→setAvatarObjectKey 순서,
 *   기존 key 교체 시 커밋 후 old delete, put 실패 시 DB 미변경.
 * - deleteAvatar: clearAvatar 호출 + 커밋 후 best-effort delete, 멱등.
 * - getAvatar: key 없으면 404 신호, 있으면 storage.get 위임.
 * - displayNameSource/ldapLinked/resyncDisplayName(FR-PR-04): getProfile 이 두 필드를 채움(외부계정 유무),
 *   displayName 편집 후 재조회 source=USER 반영, resync 는 외부계정 있으면 LDAP 로 되돌리고 최신 뷰 반환·
 *   없으면 DisplayNameNotLdapLinkedException.
 * - Annotation 회귀 가드: @Service + tx 경계(readOnly/write 조회·수정, MinIO I/O 메서드는 @Transactional 없음).
 */
class UserProfileServiceTest {
    private lateinit var userRepository: UserRepository
    private lateinit var profileRepository: UserProfileRepository
    private lateinit var storagePort: AvatarStoragePort
    private lateinit var externalAccountRepository: ExternalAccountRepository
    private lateinit var service: UserProfileService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        profileRepository = mockk()
        storagePort = mockk()
        externalAccountRepository = mockk()
        service = UserProfileService(userRepository, profileRepository, storagePort, externalAccountRepository)
    }

    private fun persistedUser(displayName: String = "Alice Cooper"): User =
        User(
            id = userId,
            username = "alice",
            email = "alice@bts.local",
            displayName = displayName,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    private fun persistedProfile(
        avatarObjectKey: String? = null,
        timezone: String = "Asia/Seoul",
        department: String? = "Engineering",
    ): UserProfile = UserProfile(userId, avatarObjectKey, timezone, department)

    // ── getProfile ────────────────────────────────────────────────────────────

    @Test
    fun `getProfile — profile row 있으면 users+user_profiles 병합`() {
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns persistedProfile()
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val view = service.getProfile(userId)

        assertThat(view.userId).isEqualTo(userId)
        assertThat(view.username).isEqualTo("alice")
        assertThat(view.email).isEqualTo("alice@bts.local")
        assertThat(view.displayName).isEqualTo("Alice Cooper")
        assertThat(view.avatarObjectKey).isNull()
        assertThat(view.timezone).isEqualTo("Asia/Seoul")
        assertThat(view.department).isEqualTo("Engineering")
    }

    @Test
    fun `getProfile — profile row 없으면 defaults(UTC, department null, avatarObjectKey null)`() {
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns null
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val view = service.getProfile(userId)

        assertThat(view.timezone).isEqualTo("UTC")
        assertThat(view.department).isNull()
        assertThat(view.avatarObjectKey).isNull()
    }

    @Test
    fun `getProfile — 사용자 미존재 시 ProfileUserNotFoundException`() {
        every { userRepository.findById(userId) } returns null

        assertThatThrownBy { service.getProfile(userId) }
            .isInstanceOf(ProfileUserNotFoundException::class.java)

        verify(exactly = 0) { profileRepository.findByUserId(any()) }
    }

    // ── patchProfile ──────────────────────────────────────────────────────────

    @Test
    fun `patchProfile — displayName만 명시하면 updateDisplayName만 호출되고 upsertProfile은 호출 안 됨`() {
        every { userRepository.updateDisplayName(userId, "New Name") } returns Unit
        every { userRepository.findById(userId) } returns persistedUser(displayName = "New Name")
        every { profileRepository.findByUserId(userId) } returns null
        every { userRepository.findDisplayNameSource(userId) } returns "USER"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val patch = ProfilePatch(displayName = ProfilePatchField.Present("New Name"))
        val view = service.patchProfile(userId, patch)

        assertThat(view.displayName).isEqualTo("New Name")
        verify(exactly = 1) { userRepository.updateDisplayName(userId, "New Name") }
        verify(exactly = 0) { profileRepository.upsertProfile(any(), any(), any()) }
    }

    @Test
    fun `patchProfile — timezone만 명시하면 기존 department가 보존된다`() {
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns
            persistedProfile(timezone = "UTC", department = "Sales")
        every { profileRepository.upsertProfile(userId, "Asia/Seoul", "Sales") } returns Unit
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val patch = ProfilePatch(timezone = ProfilePatchField.Present("Asia/Seoul"))
        service.patchProfile(userId, patch)

        verify(exactly = 1) { profileRepository.upsertProfile(userId, "Asia/Seoul", "Sales") }
        verify(exactly = 0) { userRepository.updateDisplayName(any(), any()) }
    }

    @Test
    fun `patchProfile — department null 명시(3-state)는 삭제, timezone은 미변경 시 현재값 유지`() {
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns
            persistedProfile(timezone = "Asia/Seoul", department = "Sales")
        every { profileRepository.upsertProfile(userId, "Asia/Seoul", null) } returns Unit
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val patch = ProfilePatch(department = ProfilePatchField.Present(null))
        service.patchProfile(userId, patch)

        verify(exactly = 1) { profileRepository.upsertProfile(userId, "Asia/Seoul", null) }
    }

    @Test
    fun `patchProfile — profile row가 아예 없을 때 department만 설정하면 timezone은 UTC 기본값`() {
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns null
        every { profileRepository.upsertProfile(userId, "UTC", "Marketing") } returns Unit
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val patch = ProfilePatch(department = ProfilePatchField.Present("Marketing"))
        service.patchProfile(userId, patch)

        verify(exactly = 1) { profileRepository.upsertProfile(userId, "UTC", "Marketing") }
    }

    @Test
    fun `patchProfile — timezone 무효 문자열은 ProfileValidationException, 부분 적용 없음`() {
        val patch =
            ProfilePatch(
                displayName = ProfilePatchField.Present("Valid Name"),
                timezone = ProfilePatchField.Present("Mars/Phobos"),
            )

        assertThatThrownBy { service.patchProfile(userId, patch) }
            .isInstanceOf(ProfileValidationException::class.java)

        verify(exactly = 0) { userRepository.updateDisplayName(any(), any()) }
        verify(exactly = 0) { profileRepository.upsertProfile(any(), any(), any()) }
        verify(exactly = 0) { profileRepository.findByUserId(any()) }
    }

    @Test
    fun `patchProfile — displayName blank는 ProfileValidationException, 부분 적용 없음`() {
        val patch = ProfilePatch(displayName = ProfilePatchField.Present("   "))

        assertThatThrownBy { service.patchProfile(userId, patch) }
            .isInstanceOf(ProfileValidationException::class.java)

        verify(exactly = 0) { userRepository.updateDisplayName(any(), any()) }
        verify(exactly = 0) { profileRepository.upsertProfile(any(), any(), any()) }
    }

    // ── uploadAvatar ──────────────────────────────────────────────────────────

    @Test
    fun `uploadAvatar — 정책 위반(허용되지 않은 MIME)이면 조회·저장 아무 것도 호출 안 됨`() {
        val bytes = byteArrayOf(1, 2, 3)

        assertThatThrownBy { service.uploadAvatar(userId, bytes, "text/html") }
            .isInstanceOf(AvatarValidationException::class.java)

        verify(exactly = 0) { profileRepository.findByUserId(any()) }
        verify(exactly = 0) { storagePort.put(any(), any(), any()) }
        verify(exactly = 0) { profileRepository.setAvatarObjectKey(any(), any()) }
    }

    @Test
    fun `uploadAvatar — 정상 순서는 put 후 setAvatarObjectKey`() {
        every { profileRepository.findByUserId(userId) } returns null
        every { storagePort.put(any(), any(), "image/png") } returns Unit
        every { profileRepository.setAvatarObjectKey(userId, any()) } returns Unit

        service.uploadAvatar(userId, byteArrayOf(1, 2, 3), "image/png")

        verifyOrder {
            storagePort.put(any(), any(), "image/png")
            profileRepository.setAvatarObjectKey(userId, any())
        }
    }

    @Test
    fun `uploadAvatar — 기존 아바타가 있으면 새 key 저장 후 old key를 best-effort 삭제`() {
        every { profileRepository.findByUserId(userId) } returns
            persistedProfile(avatarObjectKey = "avatars/$userId/old.png")
        every { storagePort.put(any(), any(), "image/png") } returns Unit
        every { profileRepository.setAvatarObjectKey(userId, any()) } returns Unit
        every { storagePort.delete("avatars/$userId/old.png") } returns Unit

        service.uploadAvatar(userId, byteArrayOf(1, 2, 3), "image/png")

        verifyOrder {
            storagePort.put(any(), any(), "image/png")
            profileRepository.setAvatarObjectKey(userId, any())
            storagePort.delete("avatars/$userId/old.png")
        }
    }

    @Test
    fun `uploadAvatar — 기존 아바타가 없으면 delete가 호출되지 않는다`() {
        every { profileRepository.findByUserId(userId) } returns null
        every { storagePort.put(any(), any(), "image/png") } returns Unit
        every { profileRepository.setAvatarObjectKey(userId, any()) } returns Unit

        service.uploadAvatar(userId, byteArrayOf(1, 2, 3), "image/png")

        verify(exactly = 0) { storagePort.delete(any()) }
    }

    @Test
    fun `uploadAvatar — put 실패 시 DB는 변경되지 않는다`() {
        every { profileRepository.findByUserId(userId) } returns null
        every { storagePort.put(any(), any(), "image/png") } throws RuntimeException("minio down")

        assertThatThrownBy { service.uploadAvatar(userId, byteArrayOf(1, 2, 3), "image/png") }
            .isInstanceOf(RuntimeException::class.java)

        verify(exactly = 0) { profileRepository.setAvatarObjectKey(any(), any()) }
    }

    // ── deleteAvatar ──────────────────────────────────────────────────────────

    @Test
    fun `deleteAvatar — clearAvatar 호출 후 기존 key를 best-effort 삭제`() {
        every { profileRepository.findByUserId(userId) } returns
            persistedProfile(avatarObjectKey = "avatars/$userId/current.png")
        every { profileRepository.clearAvatar(userId) } returns Unit
        every { storagePort.delete("avatars/$userId/current.png") } returns Unit

        service.deleteAvatar(userId)

        verifyOrder {
            profileRepository.clearAvatar(userId)
            storagePort.delete("avatars/$userId/current.png")
        }
    }

    @Test
    fun `deleteAvatar — 아바타가 없어도 멱등하게 clearAvatar만 호출되고 delete는 호출 안 됨`() {
        every { profileRepository.findByUserId(userId) } returns null
        every { profileRepository.clearAvatar(userId) } returns Unit

        service.deleteAvatar(userId)

        verify(exactly = 1) { profileRepository.clearAvatar(userId) }
        verify(exactly = 0) { storagePort.delete(any()) }
    }

    // ── getAvatar ─────────────────────────────────────────────────────────────

    @Test
    fun `getAvatar — key 없으면 AvatarObjectNotFoundException, storage 호출 안 됨`() {
        every { profileRepository.findByUserId(userId) } returns null

        assertThatThrownBy { service.getAvatar(userId) }
            .isInstanceOf(AvatarObjectNotFoundException::class.java)

        verify(exactly = 0) { storagePort.get(any()) }
    }

    @Test
    fun `getAvatar — key 있으면 storagePort get에 위임`() {
        val expected = AvatarObject(content = ByteArrayInputStream(byteArrayOf(1, 2, 3)), contentType = "image/png")
        every { profileRepository.findByUserId(userId) } returns
            persistedProfile(avatarObjectKey = "avatars/$userId/current.png")
        every { storagePort.get("avatars/$userId/current.png") } returns expected

        val result = service.getAvatar(userId)

        assertThat(result).isSameAs(expected)
        verify(exactly = 1) { storagePort.get("avatars/$userId/current.png") }
    }

    // ── displayNameSource / ldapLinked / resyncDisplayName (FR-PR-04) ───────────

    @Test
    fun `getProfile가 displayNameSource·ldapLinked를 채운다`() {
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns persistedProfile()

        // 외부 IdP 계정 있음 → ldapLinked=true, source는 리포지토리 값 그대로 반영
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"
        every { externalAccountRepository.existsByUserId(userId) } returns true

        val linked = service.getProfile(userId)
        assertThat(linked.ldapLinked).isTrue()
        assertThat(linked.displayNameSource).isEqualTo("LDAP")

        // 외부 IdP 계정 없음(로컬 전용) → ldapLinked=false
        every { userRepository.findDisplayNameSource(userId) } returns "USER"
        every { externalAccountRepository.existsByUserId(userId) } returns false

        val local = service.getProfile(userId)
        assertThat(local.ldapLinked).isFalse()
        assertThat(local.displayNameSource).isEqualTo("USER")
    }

    @Test
    fun `patchProfile로 displayName 편집 시 재조회 결과의 displayNameSource가 USER다`() {
        every { userRepository.updateDisplayName(userId, "Edited Name") } returns Unit
        every { userRepository.findById(userId) } returns persistedUser(displayName = "Edited Name")
        every { profileRepository.findByUserId(userId) } returns persistedProfile()
        // 편집 시 users SQL 이 source 를 USER 로 전환(Task 2) — loadView 재조회가 이를 반영해야 한다
        every { userRepository.findDisplayNameSource(userId) } returns "USER"
        every { externalAccountRepository.existsByUserId(userId) } returns true

        val view = service.patchProfile(userId, ProfilePatch(displayName = ProfilePatchField.Present("Edited Name")))

        assertThat(view.displayNameSource).isEqualTo("USER")
    }

    @Test
    fun `resyncDisplayName이 외부계정 있는 사용자의 source를 LDAP로 되돌리고 최신 뷰를 반환한다`() {
        every { externalAccountRepository.existsByUserId(userId) } returns true
        every { userRepository.resyncDisplayNameSource(userId) } returns Unit
        every { userRepository.findById(userId) } returns persistedUser()
        every { profileRepository.findByUserId(userId) } returns persistedProfile()
        every { userRepository.findDisplayNameSource(userId) } returns "LDAP"

        val view = service.resyncDisplayName(userId)

        assertThat(view.displayNameSource).isEqualTo("LDAP")
        assertThat(view.ldapLinked).isTrue()
        verify(exactly = 1) { userRepository.resyncDisplayNameSource(userId) }
    }

    @Test
    fun `resyncDisplayName이 외부계정 없는 사용자에 DisplayNameNotLdapLinkedException을 던진다`() {
        every { externalAccountRepository.existsByUserId(userId) } returns false

        assertThatThrownBy { service.resyncDisplayName(userId) }
            .isInstanceOf(DisplayNameNotLdapLinkedException::class.java)

        verify(exactly = 0) { userRepository.resyncDisplayNameSource(any()) }
    }

    // ── Annotation 회귀 가드 ──────────────────────────────────────────────────────

    @Test
    fun `서비스는 Service 애노테이션을 가진다`() {
        assertThat(UserProfileService::class.java.isAnnotationPresent(Service::class.java)).isTrue()
    }

    @Test
    fun `getProfile은 readOnly Transactional, patchProfile은 쓰기 Transactional`() {
        val getProfileTx =
            UserProfileService::class.java
                .getMethod("getProfile", UUID::class.java)
                .getAnnotation(Transactional::class.java)
        val patchProfileTx =
            UserProfileService::class.java
                .getMethod("patchProfile", UUID::class.java, ProfilePatch::class.java)
                .getAnnotation(Transactional::class.java)

        assertThat(getProfileTx).isNotNull()
        assertThat(getProfileTx.readOnly).isTrue()
        assertThat(patchProfileTx).isNotNull()
        assertThat(patchProfileTx.readOnly).isFalse()
    }

    @Test
    fun `MinIO I O 메서드(uploadAvatar deleteAvatar getAvatar)는 Transactional이 없다`() {
        val uploadAvatarMethod =
            UserProfileService::class.java.getMethod(
                "uploadAvatar",
                UUID::class.java,
                ByteArray::class.java,
                String::class.java,
            )
        val deleteAvatarMethod = UserProfileService::class.java.getMethod("deleteAvatar", UUID::class.java)
        val getAvatarMethod = UserProfileService::class.java.getMethod("getAvatar", UUID::class.java)

        assertThat(uploadAvatarMethod.isAnnotationPresent(Transactional::class.java)).isFalse()
        assertThat(deleteAvatarMethod.isAnnotationPresent(Transactional::class.java)).isFalse()
        assertThat(getAvatarMethod.isAnnotationPresent(Transactional::class.java)).isFalse()
    }
}

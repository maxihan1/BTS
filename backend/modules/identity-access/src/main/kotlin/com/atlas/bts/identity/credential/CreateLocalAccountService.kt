// 관리자 로컬 계정 생성 서비스 — users + local_credentials 단일 트랜잭션 생성 (FR-AU-05)

package com.atlas.bts.identity.credential

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 로컬 계정 생성 결과.
 *
 * @property user 생성된 [User].
 * @property temporaryPassword 응답용 임시 비밀번호 평문 CharArray.
 *   **호출 측(컨트롤러, Task 4)이 응답 직렬화 직후 wipe 할 책임을 진다** (DEVELOPMENT.md §1.1).
 *   String 화도 컨트롤러가 수행한다 — 서비스는 평문을 String 으로 만들지 않는다.
 */
data class CreatedAccount(
    val user: User,
    val temporaryPassword: CharArray,
) {
    // CharArray 는 참조 동등성을 쓰므로 data class equals/hashCode 를 내용 기반으로 재정의한다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CreatedAccount) return false
        return user == other.user && temporaryPassword.contentEquals(other.temporaryPassword)
    }

    override fun hashCode(): Int = 31 * user.hashCode() + temporaryPassword.contentHashCode()
}

/**
 * 관리자가 로컬 계정을 생성하는 애플리케이션 서비스 (FR-AU-05).
 *
 * ## 책임 경계
 * `users` 행과 `local_credentials` 행 생성만 담당한다. 권한 검증·HTTP 변환·감사 로그는
 * 컨트롤러 계층(Task 4)이 처리한다. 다른 BC 를 직접 호출하지 않는다.
 *
 * ## 트랜잭션 (DEVELOPMENT.md §1.2/§6)
 * `@Service` + 클래스 레벨 `@Transactional` — [create] 의 user INSERT 와 자격증명 store 가
 * 단일 트랜잭션으로 묶인다. 자격증명 저장 실패 시 user INSERT 도 함께 롤백된다.
 *
 * ## 평문 수명 (DEVELOPMENT.md §1.1)
 * [LocalCredentialService.store] 는 전달받은 평문 CharArray 를 해시 후 wipe 한다.
 * 따라서 응답용 평문은 store 호출 **전에** `copyOf()` 로 복제해 [CreatedAccount.temporaryPassword]
 * 로 반환한다. 이 복제본의 wipe 책임은 호출 측에 있다 (CONCERN-3).
 */
@Service
@Transactional
class CreateLocalAccountService(
    private val userRepository: UserRepository,
    private val localCredentialService: LocalCredentialService,
    private val temporaryPasswordGenerator: TemporaryPasswordGenerator,
) {
    /**
     * 신규 로컬 계정을 생성한다 — user INSERT + 임시 비밀번호 자격증명 저장(must_change=true).
     *
     * @param username    로그인 식별자 (UNIQUE)
     * @param email       이메일 (null 허용)
     * @param displayName 화면 표시 이름
     * @return 생성된 [User] 와 응답용 임시 비밀번호를 담은 [CreatedAccount]
     * @throws UsernameTakenException username 이 이미 사용 중일 때
     */
    fun create(
        username: String,
        email: String?,
        displayName: String,
    ): CreatedAccount {
        val user =
            try {
                userRepository.create(username, email, displayName)
            } catch (e: DuplicateKeyException) {
                // 영속 계층의 unique 위반을 도메인 예외로 변환한다 (빈 catch 금지 — 명시적 변환).
                throw UsernameTakenException(username, e)
            }

        val temp = temporaryPasswordGenerator.generate()
        // store 가 temp 를 wipe 하므로 응답용 평문을 먼저 복제한다 (CONCERN-3).
        val forResponse = temp.copyOf()
        localCredentialService.store(user.id, temp, mustChange = true)

        return CreatedAccount(user = user, temporaryPassword = forResponse)
    }
}

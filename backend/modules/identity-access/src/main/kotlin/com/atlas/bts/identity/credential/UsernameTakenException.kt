// 로컬 계정 생성 시 username 중복을 알리는 도메인 예외 (FR-AU-05)

package com.atlas.bts.identity.credential

/**
 * 관리자 로컬 계정 생성 시 username 이 이미 사용 중일 때 던지는 도메인 예외 (FR-AU-05).
 *
 * [com.atlas.bts.identity.user.UserRepository.create] 가 ON CONFLICT 없는 INSERT 로
 * unique 제약을 위반하면, [CreateLocalAccountService] 가 영속 계층의
 * [org.springframework.dao.DuplicateKeyException] 을 이 도메인 예외로 변환한다.
 *
 * 패키지 내 유일한 이름이다 (동명 예외 교차패키지 상태코드 변질 방지).
 * HTTP 상태 매핑(409 Conflict)은 컨트롤러 계층(Task 4)에서 처리한다.
 *
 * @param username 중복으로 거부된 username
 * @param cause 원인 예외 (영속 계층의 [org.springframework.dao.DuplicateKeyException] 등, null 허용)
 */
class UsernameTakenException(
    username: String,
    cause: Throwable? = null,
) : RuntimeException("이미 사용 중인 username 입니다: $username", cause)

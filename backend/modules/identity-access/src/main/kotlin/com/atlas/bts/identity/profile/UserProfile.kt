// user_profiles 테이블 행 매핑 엔티티 — 아바타/타임존/부서 확장 프로필 (FR-PR-01)

package com.atlas.bts.identity.profile

import java.util.UUID

/**
 * 사용자 프로필 확장 엔티티 (FR-PR-01, V027 user_profiles).
 *
 * users 테이블의 핵심 신원 정보(username/email/displayName)와 분리된 확장 속성만 담는다.
 * display_name 은 users 테이블에 그대로 유지된다.
 *
 * ## 필드
 * - [userId]: users.id FK 이자 PK (1:1).
 * - [avatarObjectKey]: MinIO 아바타 오브젝트 키. 미설정 시 null.
 * - [timezone]: IANA 타임존 문자열. 기본값 UTC.
 * - [department]: 부서. nullable — 3-state(설정/해제/미변경) 판정은 서비스 레이어 책임.
 */
data class UserProfile(
    val userId: UUID,
    val avatarObjectKey: String?,
    val timezone: String,
    val department: String?,
)

// user_ooo 테이블 행 매핑 엔티티 — 부재 기간 + 대체 담당자 + 안내 메시지 (FR-PR-03)

package com.atlas.bts.identity.ooo

import java.time.Instant
import java.util.UUID

/**
 * 부재중(Out of Office) 엔티티 (FR-PR-03, V029 user_ooo).
 *
 * users 와 1:1. OOO 는 통짜 값(replace 시맨틱)이라 상태 메시지(FR-PR-02)와 마찬가지로
 * upsert(설정) 또는 delete(해제)로만 다뤄진다.
 *
 * ## 필드
 * - [userId]: users.id FK 이자 PK (1:1).
 * - [startsAt]/[endsAt]: 부재 시작/종료(절대 시각). `endsAt > startsAt` (V029 CHECK).
 * - [delegateUserId]: 대체 담당자 users.id FK. 없어도 OOO 성립.
 * - [delegateName]: 대체 담당자 표시 이름(users.display_name 파생, LEFT JOIN). 대리자 미지정/삭제 시 null.
 * - [message]: 안내 메시지(표시용). 미설정 시 null.
 */
data class OutOfOffice(
    val userId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val delegateUserId: UUID?,
    val delegateName: String?,
    val message: String?,
)

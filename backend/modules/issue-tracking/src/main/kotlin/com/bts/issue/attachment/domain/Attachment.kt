// 이슈 첨부 파일 도메인 모델 — immutable 데이터 클래스. 하드 삭제, deleted_at 없음 (FR-AC-01).

package com.bts.issue.attachment.domain

import java.time.Instant
import java.util.UUID

/**
 * 이슈 첨부 파일 메타데이터 도메인 객체.
 *
 * 바이너리 자체는 MinIO 에 저장되고, 이 클래스는 참조 메타데이터만 보유한다.
 * 소프트 삭제 없음 — 첨부 제거는 물리 DELETE (DATA.md §3).
 *
 * @property id 첨부 고유 식별자 (UUID).
 * @property issueId 소속 이슈 UUID (issues.id FK).
 * @property filename 원본 파일명. 다운로드 시 Content-Disposition 에 사용.
 * @property contentType MIME 타입 (예: image/png). 다운로드 시 Content-Type 에 사용.
 * @property sizeBytes 파일 크기(바이트). 다운로드 시 Content-Length 에 사용.
 * @property storageKey MinIO 객체 키. 응답 DTO 에 노출하지 않는다.
 * @property uploadedBy 업로더 사용자 UUID (identity-access BC 격리로 FK 미적용).
 * @property createdAt 업로드 시각 (TIMESTAMPTZ, DATA.md §4).
 */
data class Attachment(
    val id: UUID,
    val issueId: UUID,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val storageKey: String,
    val uploadedBy: UUID,
    val createdAt: Instant,
)

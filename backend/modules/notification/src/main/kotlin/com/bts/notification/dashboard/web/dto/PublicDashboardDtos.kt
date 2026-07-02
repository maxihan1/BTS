// 익명 공개 대시보드 조회 응답 DTO — 내부 식별자(ownerId/sharedUserIds/version/id)를 구조적으로 배제 (FR-DB-03)
// MatchingDeclarationName/filename: PublicDashboardDtos.kt 파일에 현재 PublicDashboardResponse 클래스만 정의한다.
// 파일명은 복수형(Dtos)으로 "응답 DTO 모음" 의미를 표현하며, 향후 추가 응답 DTO 수용을 위해 파일명을 유지하고 규칙을 억제한다
// (agile-planning SprintResponses.kt 선례와 동일).
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package com.bts.notification.dashboard.web.dto

import com.bts.notification.dashboard.application.PublicDashboardSnapshot

/**
 * 익명(비로그인) 공개 대시보드 조회 응답.
 *
 * 노출 필드는 name·description·layout 3개로 한정한다. ownerId·sharedUserIds·version·id 등
 * 내부 식별자는 필드 자체가 존재하지 않아(구조적 배제) 직렬화 실수로도 새어나갈 수 없다.
 * layout 은 서비스에서 [com.bts.notification.dashboard.application.AnonymousLayoutSanitizer] 로
 * 이미 정화된(데이터 가젯 config 제거) 값이다.
 *
 * @param name 대시보드 이름
 * @param description 대시보드 설명 (없으면 null)
 * @param layout 익명 뷰용으로 정화된 layout JSON 문자열
 */
data class PublicDashboardResponse(
    val name: String,
    val description: String?,
    val layout: String,
) {
    companion object {
        /**
         * 서비스 스냅샷을 응답 DTO 로 변환한다.
         *
         * @param snapshot 서비스가 정화해 반환한 공개 스냅샷
         * @return 노출 필드만 담은 응답 DTO
         */
        fun from(snapshot: PublicDashboardSnapshot): PublicDashboardResponse =
            PublicDashboardResponse(
                name = snapshot.name,
                description = snapshot.description,
                layout = snapshot.layout,
            )
    }
}

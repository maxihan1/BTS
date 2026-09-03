// 프로젝트 목록 관련 경로 헬퍼/상수 — ProjectListTable에서 분리 (Fast Refresh 경고 해소, FR-PJ PR-5)
import type { Project } from '@/api/projects'

/** 프로젝트 생성 라우트 경로 — ProjectListPage 헤더 버튼과 빈 상태 CTA가 공유하는 단일 출처 */
export const NEW_PROJECT_PATH = '/projects/new'

/**
 * 프로젝트 행이 가리켜야 하는 경로를 결정한다.
 *
 * 아카이브된 프로젝트는 보드/백로그 등 활성 뷰가 없으므로(G3), 아카이브 해제
 * 버튼을 보유한 설정 화면(danger zone)으로 보낸다.
 *
 * 활성 프로젝트는 **요약 화면**으로 보낸다(Jira 패리티 J4 · 캠페인 PR ④). Jira 는 프로젝트
 * 진입점이 항상 요약이고, 목록·트리·즐겨찾기·생성 후 이동이 각자 다른 화면으로 가면
 * 「프로젝트를 연다」가 여러 뜻이 된다 — 한 곳으로 모은다.
 */
export function resolveProjectPath(project: Project): string {
  return project.archived === true
    ? `/projects/${project.key}/settings/details`
    : `/projects/${project.key}`
}

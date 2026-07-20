// 프로젝트 목록 관련 경로 헬퍼/상수 — ProjectListTable에서 분리 (Fast Refresh 경고 해소, FR-PJ PR-5)
import type { Project } from '@/api/projects'

/** 프로젝트 생성 라우트 경로 — ProjectListPage 헤더 버튼과 빈 상태 CTA가 공유하는 단일 출처 */
export const NEW_PROJECT_PATH = '/projects/new'

/**
 * 프로젝트 행이 가리켜야 하는 경로를 결정한다.
 *
 * 아카이브된 프로젝트는 보드/백로그 등 활성 뷰가 없으므로(G3), 아카이브 해제
 * 버튼을 보유한 설정 화면(danger zone)으로 보낸다. 활성 프로젝트는 보드로 이동한다.
 */
export function resolveProjectPath(project: Project): string {
  return project.archived === true
    ? `/projects/${project.key}/settings/details`
    : `/projects/${project.key}/board`
}

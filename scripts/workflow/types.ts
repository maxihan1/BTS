// BTS 워크플로우 스크립트가 공유하는 타입 정의

export type TaskType =
  | 'auth'        // 인증/권한/2FA/SSO/CSRF
  | 'backend'     // Kotlin/Spring 일반 (이슈/워크플로우/자동화/알림/Slack)
  | 'ui'          // React 컴포넌트/페이지
  | 'design'      // 신규 디자인 결정 (목업/시안/시스템)
  | 'migration'   // Flyway / DB 스키마
  | 'api'         // REST 엔드포인트 추가/변경
  | 'qa'          // E2E / 테스트 인프라
  | 'bugfix'      // 버그 수정 (fast-track)
  | 'chore'       // 잡일 (fast-track)
  | 'feature'     // 새 기능 (autoplan 후보)
  | 'unknown';    // 분류 실패

export type AgentName =
  | 'security-engineer'
  | 'backend-engineer'
  | 'frontend-engineer'
  | 'designer'
  | 'db-engineer'
  | 'qa-engineer';

export type BoundedContext =
  | 'identity-access'
  | 'issue-tracking'
  | 'project-workflow'
  | 'agile-planning'
  | 'automation'
  | 'notification'
  | 'slack-integration';

export interface ClassifyResult {
  /** 사용자 원문 입력 */
  title: string;
  /** kebab-case 슬러그 (워크트리/브랜치/plan 파일명에 사용) */
  slug: string;
  /** 작업 타입 — 워크플로우 분기 기준 */
  type: TaskType;
  /** 담당 sub-agent */
  agent: AgentName | null;
  /** 주된 바운디드 컨텍스트 (해당 없으면 null) */
  primary_bc: BoundedContext | null;
  /** plan task 수 — /bts-plan 단계에서 jq로 머지됨. 초기 0 */
  task_count: number;
  /** ISO timestamp */
  cached_at: string;
}

export interface ClassifyInput {
  title: string;
}

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
  | 'feature';    // 새 기능 (autoplan 후보)

/**
 * 작업 티어 — 절차 강도/게이트/CI 범위를 정하는 축.
 *
 * `TaskType` 과 **직교**한다. `TaskType` 은 자연어 제목에서 담당 sub-agent 와 리뷰 렌즈를 고르고,
 * `Tier` 는 **변경 경로(표면)** 에서 절차 강도를 고른다. 표면 정본은 `surfaces.ts` 1곳뿐이다.
 */
export type Tier = 'T0' | 'T1' | 'T2' | 'T3';

export type AgentName =
  | 'security-engineer'
  | 'backend-engineer'
  | 'frontend-engineer'  // design 타입도 여기로 — 디자인 스펙 작성이 프론트로 흡수됐다
  | 'db-engineer'
  | 'qa-engineer';

export type BoundedContext =
  | 'identity-access'
  | 'issue-tracking'
  | 'project-workflow'
  | 'agile-planning'
  | 'automation'
  | 'notification'
  | 'slack-integration'
  | 'search-export-import'  // 검색(AQL)/Export/Import/OpenAPI — 이전에는 automation 으로 오분류됐다
  | 'personalization';      // 프로필/환경설정/퀵필터/캘린더 — 논리 BC, 물리적으로는 identity-access 모듈

export interface ClassifyResult {
  /** 사용자 원문 입력 */
  title: string;
  /** kebab-case 슬러그 (워크트리/브랜치/plan 파일명에 사용) */
  slug: string;
  /** 작업 타입 — 워크플로우 분기 기준 */
  type: TaskType;
  /** 담당 sub-agent — detectType 이 항상 유효 타입을 반환하므로 null 이 없다 */
  agent: AgentName;
  /**
   * 착수 시점의 **선언 티어**. 착수 때는 diff 가 없어 경로로 판정할 수 없으므로 기본 T1 이고
   * 사용자 지정이 우선한다(판정 규칙 ②). 머지 전 **실측 티어**는 `detect-tier.ts` 가 낸다.
   */
  tier: Tier;
  /** 주된 바운디드 컨텍스트 (해당 없으면 null) */
  primary_bc: BoundedContext | null;
  /** plan task 수 — /bts-plan 단계에서 jq로 머지됨. 초기 0 */
  task_count: number;
  /** ISO timestamp */
  cached_at: string;
}

export interface ClassifyInput {
  title: string;
  /** 사용자가 티어를 직접 지정했을 때만. 없으면 기본값(T1)이 쓰인다 */
  tier?: Tier;
}

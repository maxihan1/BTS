// 문서 인덱싱 생성기의 상수 단일 출처 — 입력 경로·카테고리·분류 패턴·수동 오버라이드
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(__dirname, '../..');
export const HOME = process.env.HOME || process.env.USERPROFILE || '';

// 메모리는 저장소 밖이다. CI 가 볼 수 없다 — 생성기 자가진단으로 지킨다.
export const MEMORY_DIR = path.join(
  HOME,
  '.claude/projects/-Users-maxi-moff-Projects-BTS/memory',
);

// ★「룰 L 이 강제한다」는 문구를 지웠다 (2026-09-09). **그 룰은 쓰인 적이 없다** —
//   `doc-index-coverage.test.ts` 에 참조 0 인 상수 하나만 남아 있었다.
//   없는 가드를 「강제한다」고 적는 것이 가장 나쁘다. 읽는 쪽이 지켜진다고 믿기 때문이다.
//
//   그리고 이제 필요도 없다. 젠킨스는 판별식을 **조건 없이 전량** 돌리므로
//   「CI 트리거 경로」라는 두 번째 목록이 존재하지 않는다 — 어긋날 대상이 없다.
//   경로별 선별을 다시 도입한다면 그때 테스트를 **실제로 쓰고** 이 주석을 되살려라.
//
//   저장소 안 경로만 인덱싱 대상이다. repoRelative:false 는 검증에서 제외된다.
export const SOURCES = [
  { key: 'plans', dir: 'docs/plans', repoRelative: true },
  { key: 'specs', dir: 'docs/specs', repoRelative: true },
  { key: 'decisions', dir: 'docs/decisions', repoRelative: true },
  { key: 'adr', dir: 'docs/adr', repoRelative: true },
];

export const CATEGORIES = [
  { key: 'workflow', label: '워크플로우·도구·소통' },
  { key: 'backend', label: '백엔드 (Kotlin/jOOQ/트랜잭션/예외)' },
  { key: 'cross-bc', label: 'cross-BC·권한·shared-kernel' },
  { key: 'frontend', label: '프론트 (React/Zod/MSW/E2E/vitest)' },
  { key: 'build', label: '빌드·린트·마이그레이션·SSO' },
  { key: 'uncategorized', label: '미분류 — 정리 필요' },
];

// 현재 MEMORY.md 의 `## ` 그룹 → 카테고리 키. 새 분류 체계를 발명하지 않는다.
export const LEGACY_GROUP_MAP = {
  워크플로우: 'workflow',
  프론트: 'frontend',
  백엔드: 'backend',
  'cross-BC': 'cross-bc',
  마이그레이션: 'build',
  'FR 스코프': 'fr-done',
};

// FR 완료 이력 판정. `\d{2}` 를 요구한다 — `/^fr-/` 로 넓히면
// fr-scope-change-full-sync-rule(워크플로우 규칙)을 완료 이력으로 오분류한다 (스펙 R2).
export const FR_ID_RE = /^fr-([a-z]{2,3})-(\d{2})/;

/**
 * 정본(`docs/plan/`)에 없지만 **오타가 아닌** FR ID.
 *
 * 계획 단계에서 쓰였다가 다른 FR 로 흡수·착지한 ID 다. 문서에 역사적 참조로 남아 있는 것이
 * 정상이므로 경고에서 제외한다. 인덱스에는 여전히 들어가지 않는다 (정본이 아니므로).
 *
 * 근거. `docs/decisions/2026-07-28-fr-ux-07-active-project-context.md` §선점 검증 —
 * "문서에만 있는 FR-AU-12·FR-IS-12 는 선점 아님. 둘 다 fr-index 미등록."
 */
export const KNOWN_HISTORICAL_FR_IDS = new Set([
  'FR-AU-12', // ≡ FR-PM-02 로 흡수 확정 (docs/adr/2026-05-22-issue-permission-resolver-port.md)
  'FR-IS-12', // 실제로는 FR-MV-02 로 착지
]);

// FR ID 형식이 비표준이라 자동 추출이 안 되는 완료 이력 5건 (스펙 §2).
export const MANUAL_FR_OVERRIDE = {
  'fr-bl-d6-d7-backlog-sprint-done': 'FR-BL',
  'fr-pj-pr-2-r6-backfill-reseed-project-create-done': 'FR-PJ',
  'fr-pj-pr-3-list-query-settings-done': 'FR-PJ',
  'fr-pj-pr-4-archive-done': 'FR-PJ',
  'fr-pj-pr-5-project-crud-ui-done': 'FR-PJ',
};

export const CLASSIFY_PATTERNS = [
  [/(^|-)(e2e|msw|zod|react|vitest|playwright|jsx|jsdom|frontend|dialog|form-occ|mutation-setquerydata|tanstack|avatar|date-input|ui-pr)/, 'frontend'],
  [/(^|-)(jooq|transaction|exception|pg-|advisory|multipart|bearer|permitall|patch-merge|no-bump|audit|pgmq|interface-extension|profile-scoped|minio|enablewebmvc|mockk|negative-guard|best-effort|xss|issue-scope|hard-delete|auth-extraction|decorative-annotation|sibling-precedent|comment-backend|path-token|nginx|problemdetail|dev-seed|shared-dev-db|assembly-nonprod|authcontroller|issue-transition|workflow-validator|backend-)/, 'backend'],
  [/(^|-)(crossbc|cross-bc|shared-|resolver|permission|session-management|isomorphic|condition-eval|no-project|vite-preview|prod-assembly|ci-self-hosted|free-tier|use-time|custom-objectmapper|whoami|preseeded|new-bc|test-fake|nonprod-bean|no-backend-ci)/, 'cross-bc'],
  [/(^|-)(detekt|ktlint|migration|archunit|gradle|lint|kotlin-nested|module-first|identity-access-prod|concurrent-testcontainers|flaky|saml|oidc|flexmark|app-test|enum-add|plan-files|issue-tracking-transition|constructor-change|test-count|prod-dead)/, 'build'],
  [/(^|-)(bts-|worktree|parallel|subagent|multisession|memory-|checkpoint|dashboard|merge-|gh-pr|classify|verify-master|gstack|zsh|agent-tuning|harness|two-lists|discriminant|github-actions|runner-platform|debt-record|zero-measurement|feedback-|orchestrator|spec-stated|contrast-matrix|guard-handler|seal-|split-questions|mutation-|rule-reverse|verify-logic|jsx-comment|fr-scope-change|fr-sizing)/, 'workflow'],
];

export const AUTOGEN_HEADER =
  '<!-- 자동 생성 — 직접 수정 금지. 원본을 고치고 `node scripts/build-doc-index.mjs` 재실행 -->';

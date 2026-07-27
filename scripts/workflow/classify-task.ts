// BTS 작업 분류기 — 자연어 입력을 type/agent/primary_bc/slug로 분해.
// /bts-start가 호출, 결과를 .bts-cache/classify.json에 저장해 후속 스킬이 재사용.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import type {
  TaskType,
  AgentName,
  BoundedContext,
  ClassifyResult,
  ClassifyInput,
} from './types.ts';

// ─────────────────────────────────────────────────────────
// 키워드 / 패턴 정의
// ─────────────────────────────────────────────────────────

const AUTH_KEYWORDS = [
  '인증', '권한', '로그인', '로그아웃',
  '2fa', 'totp', 'webauthn', '백업코드', '백업 코드',
  'saml', 'oauth', 'oidc', 'ldap',
  'csrf', 'cors',
  'session', '세션',
  'pat', 'personal access token', '토큰', // 광의 토큰은 다른 영역에도 있어 weak
  // 보강 (2026-05-20 회귀 방지)
  'authn', 'authentication',
  'argon2', 'keycloak',
  'spring security',
  'mfa', 'passkey',
  '비밀번호', 'password',
];

// auth로 강하게 끌어당기는 키워드 (단독으로 auth 확정. 보안 영역 fast-track 우선)
const AUTH_STRONG = [
  '2fa', 'totp', 'saml', 'oauth', 'oidc', 'ldap', 'csrf', 'webauthn',
  // 보강 (2026-05-20). 이 단어들도 단독으로 보안 작업 신호 확정
  'authn', 'authentication',
  'argon2', 'keycloak',
  'spring security',
  'mfa', 'passkey',
];

const MIGRATION_KEYWORDS = [
  'flyway', '마이그레이션', 'migration',
  'alter table', 'create table', 'drop table',
  'schema', '스키마',
];

const MIGRATION_PATH_PATTERNS = [
  /backend\/db\/migration\//,
  /V\d+__/,
];

const DESIGN_KEYWORDS = [
  '디자인', '목업', '시안', '와이어프레임',
  'mockup', 'wireframe',
  '디자인 시스템', 'design.md',
];

const UI_KEYWORDS = [
  '페이지', '화면', '컴포넌트', 'ui',
  '버튼', '카드', '모달', '드롭다운',
  '레이아웃', '반응형',
];

const UI_PATH_PATTERNS = [
  /apps\/web\//,
  /\.tsx$/,
  /page\.tsx$/,
  /packages\/ui\//,
];

const API_KEYWORDS = [
  '엔드포인트', 'endpoint',
  'rest', 'api',
  'route', '/api/',
];

const API_PATH_PATTERNS = [
  /apps\/web\/src\/api\//,
  /backend\/modules\/.*\/api\//,
  /route\.ts$/,
];

const QA_KEYWORDS = [
  'e2e', 'playwright', 'vitest',
  'testcontainers',
  '회귀', '커버리지',
  // "테스트"는 너무 광의 (TDD 모든 단계에서 등장) — 단독 사용 안 함
];

const QA_PATH_PATTERNS = [
  /tests\/e2e\//,
  /tests\/integration\//,
  /\.spec\.ts$/,
];

const FEATURE_TRIGGERS = [
  '만들어줘', '추가해줘', '붙여줘', '넣어줘',
  '새 기능', '새로운', '새 화면', '새 페이지',
  '기능 추가', '추가',
];

// ─────────────────────────────────────────────────────────
// BC 키워드
// ─────────────────────────────────────────────────────────

// 각 BC는 자기 이름 자체를 키워드로 가진다 (입력에 "identity-access §1" 처럼 직접 명시되는 경우).
const BC_KEYWORDS: Record<BoundedContext, string[]> = {
  'identity-access': [
    'identity-access', 'identity access',
    '인증', '로그인', '권한', '계정',
    '2fa', 'totp', 'saml', 'oauth', 'oidc', 'ldap', 'csrf',
    'user', 'session',
    // 보강
    'authn', 'authentication',
    'argon2', 'keycloak',
    'spring security',
    'mfa', 'passkey',
  ],
  'issue-tracking': [
    'issue-tracking', 'issue tracking',
    '이슈', 'issue', '코멘트', 'comment', '첨부', 'attachment',
    '라벨', 'label', '컴포넌트', '버전', 'version',
    '워처', 'watcher',
    // 보강 (2026-07-27 — FR 제목 전수 대조로 누락 실측)
    // '댓글' 은 한국어 실사용에서 '코멘트' 보다 압도적으로 흔한데 빠져 있었다.
    '댓글', '링크', '히스토리', '이력', '커스텀 필드', '커스텀필드',
    '템플릿', '프로젝트', 'resolution', '해결책', '클론', '인쇄',
  ],
  'project-workflow': [
    'project-workflow', 'project workflow',
    '워크플로우', 'workflow',
    '전이', 'transition', 'fsm',
    '상태', 'status',
    '게이트', 'gate',
  ],
  'agile-planning': [
    'agile-planning', 'agile planning', '애자일',
    '스프린트', 'sprint',
    '백로그', 'backlog',
    '보드', 'board', '칸반', 'kanban',
    '에픽', 'epic',
    '번다운', 'burndown',
    // 보강
    '타임라인', 'timeline', '로드맵', 'roadmap', 'gantt', '간트',
    '워크로그', 'worklog', '스윔레인', 'swimlane', 'wip',
    '벨로시티', 'velocity', '번업', 'burnup', 'lexorank',
    '추정', '일정',
  ],
  'automation': [
    'automation',
    '자동화',
    '룰', 'rule',
    '트리거', 'trigger',
    '액션', 'action',
    // 보강
    '규칙', '웹훅', 'webhook', 'gitops',
  ],
  // ★'aql'·'검색'·'search' 는 여기가 아니라 automation 에 잘못 들어 있었다 (2026-07-27 실측).
  //   검색/Export/Import 는 별개 BC 이고 모듈도 backend/modules/search-export-import 로 분리돼 있다.
  'search-export-import': [
    'search-export-import',
    'aql', '검색', 'search',
    'export', '내보내기', 'import', '가져오기',
    'csv', 'xlsx', 'openapi', 'swagger',
    '필터', 'filter',
  ],
  'personalization': [
    'personalization',
    '프로필', 'profile',
    '환경설정', '개인 설정', 'preference',
    '퀵 필터', '퀵필터',
    '캘린더', 'calendar',
    '즐겨찾기', 'favorite',
    '테마', 'theme',
  ],
  'notification': [
    'notification', 'notify',
    '알림',
    '멘션', 'mention',
    '이메일', 'email',
    // 보강
    '대시보드', 'dashboard', '가젯', 'gadget', '위젯', 'widget',
    '인박스', 'inbox', '받은 편지함',
    'stomp', 'websocket', '웹소켓',
    '리포트', 'report', 'cfd', '사이클 타임', '사이클타임',
  ],
  'slack-integration': [
    'slack-integration', 'slack integration',
    'slack', '슬랙',
    'unfurl',
    'slash',
  ],
};

// ─────────────────────────────────────────────────────────
// 유틸리티
// ─────────────────────────────────────────────────────────

// Conventional Commit 접두사 제거 (예. "feat: ...", "fix(scope): ...")
const stripConventionalPrefix = (s: string): string =>
  s.replace(/^(feat|fix|refactor|chore|docs|style|test|perf)(\([^)]+\))?:\s*/i, '');

export const ASCII_SLUG_MAX = 50;

// 문자열의 결정론적 6자리 hex 해시. 한국어-only slug fallback에 사용.
// Node crypto 모듈을 동기로 쓰기 어려운 환경을 위해 간단한 수치 해시 사용.
export const shortHash = (s: string): string => {
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    hash = (hash * 31 + s.charCodeAt(i)) >>> 0;
  }
  return hash.toString(16).padStart(6, '0').slice(0, 6);
};

// 입력을 ASCII-only kebab-case slug로 변환.
// 한국어 등 비-ASCII는 제거하고 ASCII 단어만 추출한다.
// ASCII 단어가 전혀 없으면 'task-<shortHash>' fallback.
export const toSlug = (title: string): string => {
  let s = stripConventionalPrefix(title.trim());
  s = s.toLowerCase();
  // 비-ASCII 문자 (한국어 포함) → 공백으로 치환 후 ASCII 단어만 추출
  s = s.replace(/[^a-z0-9\s]/g, ' ');
  // 다중 공백 압축
  s = s.replace(/\s+/g, ' ').trim();
  // 공백 → '-'
  s = s.replace(/\s/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
  if (s.length === 0) {
    return `task-${shortHash(title)}`;
  }
  return s.slice(0, ASCII_SLUG_MAX).replace(/-+$/, '');
};

/** 한글 음절 1자 판정 — 키워드 경계 검사에 쓴다 */
const isHangulSyllable = (ch: string | undefined): boolean =>
  ch !== undefined && ch >= '가' && ch <= '힣';

/**
 * 키워드 1개가 문자열에 **경계를 지켜** 나타나는지.
 *
 * ★왜 단순 `includes` 가 아닌가. 한국어는 단어 사이에 공백 보장이 없어 접미사 오탐이 난다 —
 * 실측 사례로 "댓글 리액션 추가" 가 automation 으로 갔다. '리**액션**' 이 automation 키워드
 * '액션' 에 걸렸기 때문이다. BC 가 null 로 떨어지는 것(미정의)보다 나쁜 **조용한 오라우팅**이다.
 *
 * 판별식 — 한글 키워드는 **바로 앞에 한글 음절이 붙어 있으면 매치로 치지 않는다.**
 * 한국어 조사는 뒤에 붙으므로('액션을', '이슈의') 뒤는 막지 않고 앞만 막는다.
 * ASCII 키워드('api', 'saml')는 이 규칙과 무관하므로 기존 substring 그대로 둔다 —
 * 영문에 한글 접두사가 붙는 형태는 이 도메인에 없다.
 */
const includesWithBoundary = (haystack: string, keyword: string): boolean => {
  const kw = keyword.toLowerCase();
  if (kw.length === 0) return false;

  let from = 0;
  for (;;) {
    const at = haystack.indexOf(kw, from);
    if (at === -1) return false;
    // 키워드 첫 글자가 한글일 때만 앞 경계를 본다.
    if (!isHangulSyllable(kw[0]) || !isHangulSyllable(haystack[at - 1])) return true;
    from = at + 1;
  }
};

// 키워드 매치 (case-insensitive + 한글 접두사 경계 검사)
const hasAny = (lower: string, keywords: string[]): boolean =>
  keywords.some((kw) => includesWithBoundary(lower, kw));

const hasPathPattern = (raw: string, patterns: RegExp[]): boolean =>
  patterns.some((p) => p.test(raw));

// ─────────────────────────────────────────────────────────
// type 판정 — 우선순위 순서
// ─────────────────────────────────────────────────────────

const detectType = (raw: string): TaskType => {
  const lower = raw.toLowerCase();
  const stripped = stripConventionalPrefix(raw).toLowerCase();
  const hasFixPrefix = /^(fix|refactor)(\([^)]+\))?:/i.test(raw);
  const hasChorePrefix = /^(chore|docs|style|perf|test)(\([^)]+\))?:/i.test(raw);

  // 1. auth strong keywords — 보안 영역은 fast-track 무시 (위험 반경 ↑)
  if (hasAny(stripped, AUTH_STRONG)) return 'auth';

  // 2. migration 키워드 / 경로
  if (hasAny(stripped, MIGRATION_KEYWORDS) || hasPathPattern(raw, MIGRATION_PATH_PATTERNS)) {
    return 'migration';
  }

  // 3. Conventional Commit 접두사 — fast-track 분류
  if (hasChorePrefix) return 'chore';
  if (hasFixPrefix) return 'bugfix';

  // 4. design 키워드 (UI보다 먼저, "디자인 시안"이 UI 키워드와 겹칠 수 있음)
  if (hasAny(stripped, DESIGN_KEYWORDS)) return 'design';

  // 5. qa 키워드 / 경로
  if (hasAny(stripped, QA_KEYWORDS) || hasPathPattern(raw, QA_PATH_PATTERNS)) return 'qa';

  // 6. api 키워드 / 경로
  if (hasAny(stripped, API_KEYWORDS) || hasPathPattern(raw, API_PATH_PATTERNS)) return 'api';

  // 7. ui 키워드 / 경로
  if (hasAny(stripped, UI_KEYWORDS) || hasPathPattern(raw, UI_PATH_PATTERNS)) return 'ui';

  // 8. auth weak keywords (인증/권한/세션 — strong 매치 안 됐지만 BC가 identity-access)
  if (hasAny(stripped, AUTH_KEYWORDS)) return 'auth';

  // 9. feature 트리거 (만들어줘/추가/새 기능)
  if (hasAny(stripped, FEATURE_TRIGGERS)) return 'feature';

  // 10. 기본값. 백엔드
  return 'backend';
};

// ─────────────────────────────────────────────────────────
// agent 매핑
// ─────────────────────────────────────────────────────────

const detectAgent = (type: TaskType): AgentName | null => {
  switch (type) {
    case 'auth':
      return 'security-engineer';
    case 'migration':
      return 'db-engineer';
    case 'ui':
      return 'frontend-engineer';
    case 'design':
      return 'designer';
    case 'qa':
      return 'qa-engineer';
    case 'backend':
    case 'api':
    case 'feature':
    case 'bugfix':
    case 'chore':
      return 'backend-engineer';
    case 'unknown':
      return null;
  }
};

// ─────────────────────────────────────────────────────────
// BC 매핑
// ─────────────────────────────────────────────────────────

const detectBoundedContext = (raw: string, type: TaskType): BoundedContext | null => {
  // migration / qa / design / chore는 BC 무관
  if (type === 'migration' || type === 'qa' || type === 'design' || type === 'chore') {
    return null;
  }

  const lower = stripConventionalPrefix(raw).toLowerCase();

  // 각 BC 키워드 점수 합산
  const scores = (Object.entries(BC_KEYWORDS) as [BoundedContext, string[]][]).map(
    ([bc, kws]) => {
      const score = kws.filter((kw) => includesWithBoundary(lower, kw)).length;
      return [bc, score] as const;
    }
  );

  scores.sort((a, b) => b[1] - a[1]);
  const top = scores[0];
  if (!top || top[1] === 0) {
    // 키워드 못 찾음 → auth는 identity-access 특수 fallback (보안 영역은 BC 신호 약해도 식별 필요).
    // 그 외 타입은 강제 매핑 금지 (null 반환) — 약한 신호로 잘못된 BC에 떨어지는 amplification 방지.
    return type === 'auth' ? 'identity-access' : null;
  }
  return top[0];
};

// ─────────────────────────────────────────────────────────
// 메인 classify
// ─────────────────────────────────────────────────────────

export const classify = (input: ClassifyInput): ClassifyResult => {
  const title = input.title.trim();
  const type = detectType(title);
  const agent = detectAgent(type);
  const primary_bc = detectBoundedContext(title, type);
  const slug = toSlug(title);

  return {
    title,
    slug,
    type,
    agent,
    primary_bc,
    task_count: 0,
    cached_at: new Date().toISOString(),
  };
};

// ─────────────────────────────────────────────────────────
// 캐시 입출력
// ─────────────────────────────────────────────────────────

const CACHE_PATH = '.bts-cache/classify.json';
const CACHE_TTL_MS = 60 * 60 * 1000; // 1시간

const readCache = (): ClassifyResult | null => {
  if (!existsSync(CACHE_PATH)) return null;
  try {
    const raw = readFileSync(CACHE_PATH, 'utf-8');
    const cached = JSON.parse(raw) as ClassifyResult;
    const age = Date.now() - new Date(cached.cached_at).getTime();
    if (age > CACHE_TTL_MS) return null;
    return cached;
  } catch {
    return null;
  }
};

const writeCache = (result: ClassifyResult): void => {
  mkdirSync(dirname(CACHE_PATH), { recursive: true });
  writeFileSync(CACHE_PATH, JSON.stringify(result, null, 2));
};

// ─────────────────────────────────────────────────────────
// CLI 진입점
// ─────────────────────────────────────────────────────────

const parseArgs = (argv: string[]): { title: string; useCache: boolean } => {
  let title = '';
  let useCache = false;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--title' && i + 1 < argv.length) {
      title = argv[i + 1]!;
      i++;
    } else if (argv[i] === '--cache') {
      useCache = true;
    }
  }
  return { title, useCache };
};

const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const { title, useCache } = parseArgs(process.argv.slice(2));
  if (!title) {
    console.error('Usage: classify-task.ts --title "<자연어 입력>" [--cache]');
    process.exit(2);
  }

  if (useCache) {
    const cached = readCache();
    if (cached && cached.title === title) {
      console.log(JSON.stringify(cached, null, 2));
      process.exit(0);
    }
  }

  const result = classify({ title });
  if (useCache) writeCache(result);
  console.log(JSON.stringify(result, null, 2));
}

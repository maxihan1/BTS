// BTS 작업 분류기 — 자연어 입력을 type/agent/primary_bc/slug로 분해.
// /bts-start가 호출, 결과를 .bts-cache/classify.json에 저장해 후속 스킬이 재사용.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { DEFAULT_TIER, TIER_ORDER } from './detect-tier.ts';
import type {
  TaskType,
  AgentName,
  BoundedContext,
  ClassifyResult,
  ClassifyInput,
  Tier,
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
  // ★뒤 슬래시를 요구하지 않는다. 사람이 자연어로 쓰는 형태는 `apps/web`(슬래시 없음)이 흔한데
  //   종전 `/apps\/web\//` 는 그걸 놓쳐 신호 0 으로 읽고 기본값 `backend` 로 떨어뜨렸다(부채 44).
  //   ADR 2026-07-28 §D2 가 같은 오분류를 「3회째」로 기록해 두었다 — 등재보다 7주 앞선다.
  // ★`\b` 를 쓰지 않는 이유. `\b` 는 `b` 다음 `-` 에서 성립하므로 `apps/web-legacy` 가 걸린다(실측).
  //   부정 전방탐색으로 단어 문자와 하이픈을 **둘 다** 막아야 `apps/webhook`·`apps/web-legacy` 가 빠진다.
  /apps\/web(?![\w-])/,
  /\.tsx$/,
  /page\.tsx$/,
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
  // 뒤 슬래시를 요구하지 않는다 — `UI_PATH_PATTERNS` 와 같은 결함이었다(부채 44 와 동형).
  // 이걸 안 고치면 `apps/web/e2e`(슬래시 없음)가 ui 경로에만 걸려 qa 를 놓친다.
  /apps\/web\/e2e(?![\w-])/,
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

/** ASCII 소문자 1자 판정 — 영단어 경계 검사에 쓴다 */
const isAsciiLower = (ch: string | undefined): boolean =>
  ch !== undefined && ch >= 'a' && ch <= 'z';

/**
 * **양쪽 단어 경계를 요구하는 ASCII 키워드 목록** (부채 45).
 *
 * ★길이로 자르지 않는다. 초판 설계는 「길이 ≤ N 인 ASCII 키워드에 경계 요구」였고 N 을
 * 4 로 두든 6 으로 올리든 **진짜 신호를 함께 죽인다** — `argon2`(6자)에 어형이 붙은
 * `Argon2id`(OWASP 권장 표기)와 `ldap`(4자)의 `LDAPS` 가 `auth`/`security-engineer` 에서
 * `backend` 로 떨어졌다(실측). 영어 복수형 `issues`·`users`·`exports` 도 전부 BC 를 잃었다.
 *
 * ★길이는 충돌 성향과 상관이 없다. `csrf`(4)·`oidc`(4)·`aql`(3) 은 15개월간 한 번도 안
 * 부딪혔고 `board`(5)·`action`(6) 은 부딪혔다. 그래서 **실제로 부딪힌 것만 이름으로** 적는다.
 *
 * ★이 형태라야 뮤테이션이 항목별로 비-공허하다 — 한 줄을 지우면 대응 단언 **하나**가 red 다.
 * 단일 상수는 흔들면 전부가 같이 흔들려 그 성질을 가질 수 없다.
 */
const BOUNDARY_ONLY = new Set([
  'pat',    // ⊂ dispatch · patch · path  → auth/security-engineer 오배정
  'ui',     // ⊂ build · guide · requirement
  'api',    // ⊂ rapid
  'rest',   // ⊂ restore · restrict
  'board',  // ⊂ keyboard  → agile-planning 오배정
  'action', // ⊂ transaction · interaction  → automation 오배정
  'label',  // ⊂ relabel  → issue-tracking 오배정
]);

/**
 * 키워드 1개가 문자열에 **경계를 지켜** 나타나는지.
 *
 * ★왜 단순 `includes` 가 아닌가. 한국어는 단어 사이에 공백 보장이 없어 접미사 오탐이 난다 —
 * 실측 사례로 "댓글 리액션 추가" 가 automation 으로 갔다. '리**액션**' 이 automation 키워드
 * '액션' 에 걸렸기 때문이다. BC 가 null 로 떨어지는 것(미정의)보다 나쁜 **조용한 오라우팅**이다.
 *
 * 판별식 두 갈래.
 * - **한글 키워드** — 바로 앞에 한글 음절이 붙어 있으면 매치로 치지 않는다. 한국어 조사는
 *   뒤에 붙으므로('액션을', '이슈의') 뒤는 막지 않고 앞만 막는다.
 * - **ASCII 키워드** — `BOUNDARY_ONLY` 에 적힌 것만 **양쪽** 경계를 요구한다.
 *
 * ★2026-07-27 판에 적혀 있던 「ASCII 키워드는 이 규칙과 무관하므로 기존 substring 그대로
 * 둔다 — 영문에 한글 접두사가 붙는 형태는 이 도메인에 없다」는 **참이지만 무관한 문장이었다.**
 * ASCII 키워드의 실제 위험은 한글 접두사가 아니라 **다른 ASCII 단어**다(`pat` ⊂ `dispatch`).
 * 측정하지 않은 안전 속성을 단정한 문장이 그대로 13개월을 살아남았다.
 *
 * ★**앞만** 보면 안 된다. `patch`·`path` 는 키워드가 index 0 이라 앞 경계가 통과한다.
 * ★분기 순서 — 한글 판정이 **먼저**다. ASCII 분기를 앞에 두면 한글 키워드가 앞 경계 보호를
 *   잃어 `리액션` 오라우팅이 되살아난다.
 */
const includesWithBoundary = (haystack: string, keyword: string): boolean => {
  const kw = keyword.toLowerCase();
  if (kw.length === 0) return false;

  let from = 0;
  for (;;) {
    const at = haystack.indexOf(kw, from);
    if (at === -1) return false;

    if (isHangulSyllable(kw[0])) {
      // 한글 키워드는 앞 경계만 본다.
      if (!isHangulSyllable(haystack[at - 1])) return true;
    } else if (!BOUNDARY_ONLY.has(kw)) {
      // 목록 밖 ASCII 키워드는 종전 부분일치 그대로.
      return true;
    } else {
      // ★경계 문자류는 `[a-z]` 만이다 — 숫자를 포함시키면 `saml2`·`oauth2` 가 깨진다(실측).
      let end = at + kw.length;
      // ★영어 복수형 접미 `s` 1개는 경계로 친다. 없으면 labels·boards·actions 가 BC 를 잃는다.
      //   relabel·keyboard·transaction 은 **앞** 경계에서 걸리므로 이 허용에 영향받지 않는다.
      if (haystack[end] === 's') end += 1;
      if (!isAsciiLower(haystack[at - 1]) && !isAsciiLower(haystack[end])) return true;
    }
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

/**
 * 제목에서 작업 타입을 정한다. **순서가 계약이다** — 아래 도식이 그 계약이고, 번호 주석은 도식의 사본이다.
 *
 * ```
 *  ① auth(strong)   보안은 fast-track 무시 — 위험 반경이 가장 크다
 *  ② migration
 *  ③ chore/fix 접두사
 *  ④ design         「디자인 시안」이 ui 키워드와 겹치므로 ui 보다 앞
 *  ⑤ qa **경로**    ─┐ 경로는 추측이 아니라 사실이다. 퍼지 신호보다 앞선다
 *  ⑥ api             │  ★`apps/web` 은 `apps/web/e2e/` 의 접두사다. ⑤ 를 ⑦ 뒤로 내리면
 *  ⑦ ui             ─┘   Playwright 표면 전체가 qa-engineer 에 도달 불가가 된다
 *  ⑧ qa **키워드**   「E2E」가 제목에 섞였을 뿐일 수 있다 — 부채 33 이 이 자리다
 *  ⑨ auth(weak)
 *  ⑩ feature
 *  ⑪ backend        기본값. 신호 0 이면 여기
 * ```
 *
 * **★⑧ 을 ⑥⑦ 뒤로 내린 근거는 오류 비용의 비대칭이다.** `qa-engineer` 는 구현 코드 수정이
 * 금지돼 있어(`.claude/agents/qa-engineer.md`) 잘못 가면 **복구 불가**다 — 그 에이전트는 일을
 * 시작조차 못 한다. 반대로 `frontend-engineer` 가 E2E 를 쓰는 것은 금지돼 있지 않아 **복구 가능**하다.
 * 그래서 신호가 애매하면 복구 가능한 쪽으로 기운다.
 *
 * **단 이 비대칭은 ⑧ 에만 적용한다.** ⑤ 는 경로가 실제로 `e2e` 를 가리키는 경우이고,
 * 그건 애매한 신호가 아니라 사실이므로 양보하지 않는다.
 */
const detectType = (raw: string): TaskType => {
  const lower = raw.toLowerCase();
  const stripped = stripConventionalPrefix(raw).toLowerCase();
  const hasFixPrefix = /^(fix|refactor)(\([^)]+\))?:/i.test(raw);
  const hasChorePrefix = /^(chore|docs|style|perf|test)(\([^)]+\))?:/i.test(raw);

  // ① auth strong keywords — 보안 영역은 fast-track 무시 (위험 반경 ↑)
  if (hasAny(stripped, AUTH_STRONG)) return 'auth';

  // ② migration 키워드 / 경로
  if (hasAny(stripped, MIGRATION_KEYWORDS) || hasPathPattern(raw, MIGRATION_PATH_PATTERNS)) {
    return 'migration';
  }

  // ③ Conventional Commit 접두사 — fast-track 분류
  if (hasChorePrefix) return 'chore';
  if (hasFixPrefix) return 'bugfix';

  // ④ design 키워드 (UI보다 먼저, "디자인 시안"이 UI 키워드와 겹칠 수 있음)
  if (hasAny(stripped, DESIGN_KEYWORDS)) return 'design';

  // ⑤ qa **경로** — 키워드(⑧)와 쪼갠 이유는 위 KDoc
  if (hasPathPattern(raw, QA_PATH_PATTERNS)) return 'qa';

  // ⑥ api 키워드 / 경로
  if (hasAny(stripped, API_KEYWORDS) || hasPathPattern(raw, API_PATH_PATTERNS)) return 'api';

  // ⑦ ui 키워드 / 경로
  if (hasAny(stripped, UI_KEYWORDS) || hasPathPattern(raw, UI_PATH_PATTERNS)) return 'ui';

  // ⑧ qa **키워드** — 제목에 「E2E」가 섞였을 뿐인 혼합 작업을 여기서 받는다 (부채 33)
  if (hasAny(stripped, QA_KEYWORDS)) return 'qa';

  // ⑨ auth weak keywords (인증/권한/세션 — strong 매치 안 됐지만 BC가 identity-access)
  if (hasAny(stripped, AUTH_KEYWORDS)) return 'auth';

  // ⑩ feature 트리거 (만들어줘/추가/새 기능)
  if (hasAny(stripped, FEATURE_TRIGGERS)) return 'feature';

  // ⑪ 기본값. 백엔드
  return 'backend';
};

// ─────────────────────────────────────────────────────────
// agent 매핑
// ─────────────────────────────────────────────────────────

const detectAgent = (type: TaskType): AgentName => {
  switch (type) {
    case 'auth':
      return 'security-engineer';
    case 'migration':
      return 'db-engineer';
    case 'ui':
    // design 은 별도 에이전트가 아니다 — 새 UI 는 디자인 스펙 작성부터 TSX 구현까지
    // frontend-engineer 가 이어서 한다. 스펙만 쓰고 끊으면 넘기는 왕복이 그대로 비용이 됐다.
    case 'design':
      return 'frontend-engineer';
    case 'qa':
      return 'qa-engineer';
    case 'backend':
    case 'api':
    case 'feature':
    case 'bugfix':
    case 'chore':
      return 'backend-engineer';
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
  // 착수 시점엔 diff 가 없어 표면으로 티어를 잴 수 없다. 기본 T1 이고 사용자 지정이 우선한다
  // (판정 규칙 ②). 실측 티어는 머지 전에 detect-tier.ts 가 변경 경로에서 따로 낸다.
  const tier = input.tier ?? DEFAULT_TIER;

  return {
    title,
    slug,
    type,
    agent,
    tier,
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

const parseArgs = (argv: string[]): { title: string; useCache: boolean; tier: Tier | undefined } => {
  let title = '';
  let useCache = false;
  let tier: Tier | undefined;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--title' && i + 1 < argv.length) {
      title = argv[i + 1]!;
      i++;
    } else if (argv[i] === '--cache') {
      useCache = true;
    } else if (argv[i] === '--tier' && i + 1 < argv.length) {
      const value = argv[i + 1]!;
      if (!(TIER_ORDER as readonly string[]).includes(value)) {
        console.error(`--tier 값이 티어가 아니다: ${value} (T0|T1|T2|T3)`);
        process.exit(2);
      }
      tier = value as Tier;
      i++;
    }
  }
  return { title, useCache, tier };
};

const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const { title, useCache, tier } = parseArgs(process.argv.slice(2));
  if (!title) {
    console.error('Usage: classify-task.ts --title "<자연어 입력>" [--cache] [--tier T0|T1|T2|T3]');
    process.exit(2);
  }

  if (useCache) {
    const cached = readCache();
    // 티어를 새로 지정했으면 캐시를 재사용하지 않는다 — 지정값이 조용히 무시되면
    // 사용자 지정 우선(판정 규칙 ②)이 깨진다.
    if (cached && cached.title === title && (tier === undefined || cached.tier === tier)) {
      console.log(JSON.stringify(cached, null, 2));
      process.exit(0);
    }
  }

  const result = classify({ title, tier });
  if (useCache) writeCache(result);
  console.log(JSON.stringify(result, null, 2));
}

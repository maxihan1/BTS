// 변경 경로를 「작업 표면」으로 가르는 글로브 정본 — 티어 판정이 읽는 유일한 목록
//
// ## 왜 이 파일이 따로 있나
//
// 티어는 **변경 경로**가 정한다(변경 파일 「개수」 규칙은 폐기됐다). 그 경로 목록을
// 판정기(`detect-tier.ts`)와 판별식(`tier-floor.test.ts`)이 **각자 적으면** 두 목록이 갈린다 —
// 이 저장소가 이름 붙인 지배 결함 양식 `[[two-lists-never-check-each-other]]` 그대로다.
// 그래서 글로브 문자열은 여기 한 벌만 두고, 나머지는 전부 **import 만** 한다.
// **다른 어떤 파일도(스킬 본문·CLAUDE.md·문서 표 포함) 글로브를 다시 적지 않는다.**
// 문서에는 표면 **이름**만 적고, 이름 집합의 정합은 `verify-master-plan.sh` 룰이 본다.
//
// ## 이 파일에서 기계가 읽는 것
//
// - `SURFACES` 의 **키(표면 이름) 집합** — `docs/rules/behavior-rules.md` 표면 표와 차집합 0.
//   추출은 `^  [A-Z_]+:` 형태에 의존하므로 **키는 2칸 들여쓰기 + 대문자/밑줄**로만 적는다.
// - 각 글로브 — `tier-floor.test.ts` 가 저장소 실파일과 대조한다. 아무 파일도 안 걸리는
//   글로브는 죽은 규칙이므로 red 가 난다.

import type { Tier } from './types.ts';

/** 표면 1개의 선언 */
export interface Surface {
  /**
   * 저장소 상대 경로 글로브. 지원 문법은 `*`(경로 구분자 제외) · `**`(경로 구분자 포함) ·
   * `?` · `{a,b}` 세 가지뿐이다 — 정규식·부정 패턴은 쓰지 않는다.
   */
  globs: readonly string[];
  /** 이 표면이 요구하는 티어 */
  tier: Tier;
  /** 보안 렌즈가 붙는 표면인가. T2+ 에서 이 렌즈는 생략 불가다 */
  lens?: 'security';
  /** 사람이 읽는 한 줄 근거 */
  note: string;
}

/**
 * 표면 15종.
 *
 * 티어는 **절차 강도**이지 위험도 점수가 아니다. 같은 경로가 여러 표면에 걸릴 수 있으며
 * 그때 어느 표면이 이기는지는 `SURFACE_PRECEDENCE` 가 정한다(여기 선언 순서가 아니다).
 */
export const SURFACES = {
  MIGRATION: {
    globs: ['backend/modules/*/src/main/resources/db/migration/**/*.sql'],
    tier: 'T3',
    note: '비가역 데이터 변경. `db/codegen/**` 은 마이그레이션이 아니라 제외',
  },
  SHARED_KERNEL: {
    globs: ['backend/modules/shared-kernel/**'],
    tier: 'T3',
    note: '전 BC 파급 — 역폐포로 9모듈이 전부 돈다',
  },
  TOPOLOGY: {
    globs: ['backend/settings.gradle.kts'],
    tier: 'T3',
    note: '도메인 경계 이동. 모듈 디렉터리 신설·삭제는 이 파일의 include 등록을 반드시 동반한다',
  },
  SEC_BE: {
    globs: [
      'backend/modules/identity-access/**',
      'backend/modules/*/src/main/kotlin/**/fieldpermission/**',
      'backend/modules/*/src/main/kotlin/**/issuesecurity/**',
      'backend/modules/automation/**/security/**',
      'backend/modules/slack-integration/**/{security,oauth}/**',
      'backend/modules/shared-kernel/**/permission/**',
    ],
    tier: 'T2',
    lens: 'security',
    note: 'identity-access 는 하위 33패키지가 전부 domain/ 밖이라 모듈 전체를 잡는다',
  },
  SEC_FE: {
    globs: [
      'apps/web/src/{auth,components/auth,components/global-permissions,components/field-permissions}/**',
      'apps/web/src/router.ts',
      'apps/web/src/api/client.ts',
      'apps/web/src/api/webauthn.ts',
      'apps/web/src/hooks/use-*permission*.ts',
      'apps/web/src/lib/dashboard-permission.ts',
      'apps/web/src/routes/{login,settings.mfa,settings.password,settings.sessions}.tsx',
    ],
    tier: 'T2',
    lens: 'security',
    note: '라우트 가드·토큰 갱신·권한 훅. admin 화면은 SHELL 로 내렸다',
  },
  API: {
    globs: ['backend/modules/*/src/main/kotlin/**/{web,event,spi,port}/**'],
    tier: 'T2',
    note: 'REST 시그니처 · 이벤트 스킴 · BC 창구',
  },
  DEPS: {
    globs: [
      'backend/build.gradle.kts',
      'backend/settings.gradle.kts',
      'backend/gradle.properties',
      'backend/gradle/wrapper/**',
      'package.json',
      'apps/web/package.json',
      // node 버전 정본. 이 한 줄이 CI 5잡 전부의 node 를 바꾼다 — 성격이 매니페스트다.
      // 없으면 `.nvmrc` 만 바꾸는 PR 이 표면 0 → 기본 T1 로 떨어져 계획 0·리뷰 1 로 통과한다.
      '.nvmrc',
      'pnpm-lock.yaml',
      'pnpm-workspace.yaml',
    ],
    tier: 'T2',
    note: '루트 gradle 4종 + 프론트 매니페스트. 전체 CI 조건은 티어가 아니라 CI 표 소관',
  },
  BE_MAIN: {
    globs: ['backend/modules/*/src/main/**'],
    tier: 'T2',
    note: '도메인 로직 잔여분 — 위 백엔드 표면에 안 걸린 것',
  },
  GUARD_CI: {
    globs: [
      // ★`.github/workflows/**` 를 지웠다(2026-09-09 · P4b). CI 정본이 젠킨스로 옮겨지고
      //   워크플로우 4종을 철거해 **아무 파일도 안 무는 죽은 글로브**가 됐다.
      //   그 자리는 아래 `Jenkinsfile` · `infra/jenkins/**` 가 잇는다.
      '.husky/**',
      'scripts/verify-*.sh',
      'scripts/verify/**',
      'scripts/workflow/*.{ts,mjs}',
      'scripts/doc-index/**',
      'scripts/build-*.mjs',
      // 프로덕션 직전의 마지막 검사대. 훅과 `GIT_*` 스크럽 **같은 한 줄**을 공유하고 그
      // 동일성을 판별식이 강제하는데, 없으면 그 배포 스크립트만 바꾸는 PR 이 표면 0 →
      // 기본 T1 로 떨어져 훅 쪽(T2)과 절차 강도가 갈린다.
      'infra/deploy/**',
      // 호스트 노출면을 정하는 자리. Docker 는 publish 포트를 `INPUT` 정책 밖으로 빼므로
      // 여기 한 줄이 방화벽 전체보다 넓게 작동한다 — `-j DROP` 을 지우는 1행 변경이
      // 표면 0 → T1 로 통과하면 리뷰 1종·계획 0 으로 노출면이 열린다.
      'infra/security/**',
      // CI/CD 정의 정본. `.github/workflows/**` 가 여기 있는 것과 같은 이유다 —
      // 파이프라인이 무엇을 돌리는지를 정하는 자리라 1행 변경이 검증 전체를 끌 수 있다.
      'infra/jenkins/**',
      // ★`Jenkinsfile` 이 아니라 `Jenkinsfile*` 이다 (2026-09-11).
      //
      //   종전에는 정확히 `Jenkinsfile` 이었고, 그래서 **`Jenkinsfile.e2e` 가 안 걸렸다.**
      //   실측. detect-tier.ts Jenkinsfile     → T2 · GUARD_CI
      //         detect-tier.ts Jenkinsfile.e2e → T1 · UNMAPPED
      //
      //   프로덕션 실서버를 상대로 무엇을 검증할지 정하는 파일이 CI 정의보다 **낮은 티어**로
      //   떨어져 있었다. 한 줄만 고쳐도 배포 후 E2E 전체를 끌 수 있는 자리인데 계획 0 · 리뷰 1 이다.
      //
      //   이름을 열거하지 않는다. 열거하면 「파이프라인 파일 목록」과 「글로브 목록」이라는
      //   두 목록이 생기고, 파이프라인을 하나 더 만들 때 뒤쪽이 조용히 낡는다.
      //   계약. scripts/workflow/jenkinsfile-surface-coverage.test.ts
      'Jenkinsfile*',
    ],
    tier: 'T2',
    note: '강제 장치 5층 — CI · 훅 · 판별식·생성기 · 배포 게이트 · 호스트 노출면. 여기가 조용히 망가지면 나머지 전부가 눈이 먼다',
  },
  SHELL: {
    globs: ['apps/web/src/routes/__root.tsx', 'apps/web/src/routes/admin.*'],
    tier: 'T1',
    note: '셸·관리 화면. 가드 토큰 동반 시 T2 승격은 2주 파일럿 대상이라 아직 자동화하지 않는다',
  },
  FE_SRC: {
    globs: ['apps/web/src/**'],
    tier: 'T1',
    note: '단일 SPA 잔여분 — 도메인 분리가 없다',
  },
  HARNESS: {
    globs: ['.claude/**'],
    tier: 'T1',
    note: '스킬·에이전트 본문. 내용 계약은 판별식이 따로 잡는다',
  },
  TEST: {
    globs: [
      'backend/modules/*/src/test/**',
      'apps/web/**/*.{test,spec}.{ts,tsx}',
      'apps/web/e2e/**',
      'apps/web/src/**/__tests__/**',
      'scripts/**/*.test.{ts,mjs}',
    ],
    tier: 'T1',
    note: '테스트 전용 변경은 티어를 올리지 않는다(판정 규칙 ④)',
  },
  STYLE_COPY: {
    globs: ['**/*.css', 'apps/web/src/i18n/**'],
    tier: 'T0',
    note: '순수 스타일·문구',
  },
  DOC: {
    globs: ['docs/**', '*.md', '**/*.html'],
    tier: 'T0',
    note: '문서·목업. 생성물(docs/INDEX*·progress.html)도 여기 걸리지만 판정 입력에서 먼저 빠진다',
  },
} as const satisfies Record<string, Surface>;

export type SurfaceName = keyof typeof SURFACES;

/**
 * 한 경로가 여러 표면에 걸릴 때 **어느 표면으로 세는가**의 순서. 앞이 이긴다.
 *
 * 두 가지 이유로 선언 순서와 다르다.
 * ① `TEST` 가 맨 앞이다 — 보안 모듈의 테스트 1파일이 보안 표면으로 세어지면 규칙 ④ 가 깨진다.
 * ② `BE_MAIN` · `FE_SRC` 는 「잔여」 표면이라 맨 뒤다 — 앞의 좁은 표면이 먼저 가져간다.
 *
 * `tier-floor.test.ts` 가 이 배열과 `SURFACES` 키의 차집합 0 을 강제한다(양방향).
 */
export const SURFACE_PRECEDENCE: readonly SurfaceName[] = [
  'TEST',
  'MIGRATION',
  'SHARED_KERNEL',
  'TOPOLOGY',
  'SEC_BE',
  'SEC_FE',
  'API',
  'DEPS',
  'GUARD_CI',
  'SHELL',
  'STYLE_COPY',
  'HARNESS',
  'DOC',
  'BE_MAIN',
  'FE_SRC',
];

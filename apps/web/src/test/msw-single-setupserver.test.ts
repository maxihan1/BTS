// 로컬 setupServer 인스턴스가 늘어나는 것을 차단하는 판별식 — MSW 이중 디스패치 봉인
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'

/**
 * ★이 판별식이 존재하는 이유 — A/B 실측으로 확정한 근본 원인 (2026-08-09).
 *
 * | 조건 | resolver 호출 | request:start | 고유 requestId |
 * |---|---|---|---|
 * | 전역 `server` 단독 | 1 | 1 | 1 |
 * | 로컬 `setupServer` + 전역 공존 | **2** | 2 | **1** |
 *
 * `uniqueIds=1` 이 결정적이다 — 요청이 두 번 나간 게 아니라 **같은 요청이 두 번 디스패치**된다.
 * 전역 셋업(`src/test/setup.ts`)이 항상 `server.listen()` 을 돌리므로, 테스트 파일이 자기
 * `setupServer` 를 하나 더 띄우면 인터셉터가 2개가 되어 모든 요청이 두 번 처리된다.
 *
 * **상태를 누적(append)하는 목 핸들러가 전부 조용히 중복된다.** 값을 덮어쓰거나 이동시키는
 * 핸들러는 멱등이라 증상이 안 보이고 **추가하는 핸들러만** 드러나므로 오래 잠복한다.
 * 실제로 FR-UX-09 F3 이 이 함정에 걸렸다.
 *
 * ★「본문을 읽는 핸들러는 두 번째 호출이 Body already read 로 자동 실패해 무해하다」는
 * **신뢰할 수 없다** — `issue-handlers.ts` 는 `request.clone().json()` 으로 읽는데도
 * append 가 2회 났다. 이걸 전수 조사의 제외 기준으로 쓰지 말 것.
 *
 * ## 지금 이 판별식이 하는 일 — 동결(freeze)
 *
 * 기존 위반 파일을 [MIGRATION_BASELINE] 으로 얼려 두고 **새 위반만 차단**한다.
 * 전면 이주는 별도 TODOS 항목이다 — 이주가 기계적 치환이 아니기 때문이다.
 * 로컬 서버가 뜨면 전역 핸들러가 통째로 죽으므로(실측), 이주하면 그 파일들에서
 * `/auth/refresh` 가 **처음으로 살아나** 401 자동 재시도가 지금은 실패하던 자리에서
 * 성공한다 — 401/403 을 단언하는 테스트의 결과가 뒤집힌다.
 *
 * ## 이주할 때
 *
 * 1. 로컬 서버 생성부(`const server = setup` + `Server(...)`) + `beforeAll(server.listen)` +
 *    `afterAll(server.close)` 를 제거한다
 * 2. `import { server } from '@/test/server'` + **`beforeEach(() => server.use(...))`**
 *    ★`beforeAll` 이 아니다. 전역 `setup.ts` 의 `afterEach(server.resetHandlers())` 가
 *    런타임 핸들러를 매번 날리므로 `beforeAll` 등록은 첫 테스트 뒤 조용히 사라진다.
 * 3. 이 파일의 [MIGRATION_BASELINE] 에서 그 경로를 **지운다** (래칫).
 *
 * 선례 3건 — `src/mocks/import-handlers.test.ts` · `profile-handlers.test.ts` · `status-handlers.test.ts`.
 */

/** 유일하게 `setupServer` 를 만들어도 되는 파일 — 전역 서버 그 자체. */
const ALLOWED = ['src/test/server.ts'] as const

/**
 * 봉인 시점(2026-08-09)에 이미 로컬 `setupServer` 를 만들고 있던 파일들.
 *
 * **이 목록은 늘리지 않는다.** 줄이기만 한다 — 이주할 때마다 한 줄씩 지운다.
 * 목록에 있는데 실제로는 더 이상 위반이 아닌 항목은 아래 「썩은 항목」 단언이 잡는다.
 */
const MIGRATION_BASELINE: readonly string[] = [
  'src/api/automation-executions.test.ts',
  'src/api/automation-git-webhooks.test.ts',
  'src/api/automation-rules.test.ts',
  'src/api/oidc.test.ts',
  'src/api/providers.test.ts',
  'src/api/saml.test.ts',
  'src/api/useAutomationExecutions.test.tsx',
  'src/api/useAutomationRules.test.tsx',
  'src/api/useGitWebhooks.test.tsx',
  'src/components/automation/AutomationRuleFormDialog.test.tsx',
  'src/components/automation/AutomationRuleList.test.tsx',
  'src/components/automation/GitWebhookSection.test.tsx',
  'src/components/dashboard/ShareDashboardModal.test.tsx',
  'src/hooks/use-dashboards.test.tsx',
  'src/mocks/__tests__/bulk-available-transitions-handler.test.ts',
  'src/mocks/__tests__/bulk-operation-handlers.test.ts',
  'src/mocks/__tests__/changelog-handlers.test.ts',
  'src/mocks/__tests__/create-issue-handler.test.ts',
  'src/mocks/__tests__/custom-field-handlers.test.ts',
  'src/mocks/__tests__/favorite-handlers.test.ts',
  'src/mocks/__tests__/global-permission-handlers.test.ts',
  'src/mocks/__tests__/handlers.integration.test.ts',
  'src/mocks/__tests__/inbox-handlers.test.ts',
  'src/mocks/__tests__/issue-handlers.test.ts',
  'src/mocks/__tests__/issue-permission-handlers.test.ts',
  'src/mocks/__tests__/issue-transition-handlers.test.ts',
  'src/mocks/__tests__/project-member-handlers.test.ts',
  'src/mocks/__tests__/project-permission-handlers.test.ts',
  'src/mocks/__tests__/scheme-handlers.test.ts',
  'src/mocks/account-link-handlers.test.ts',
  'src/mocks/audit-log-handlers.test.ts',
  'src/mocks/auth-handlers.test.ts',
  'src/mocks/automation-execution-handlers.test.ts',
  'src/mocks/automation-rule-handlers.test.ts',
  'src/mocks/backlog-handlers.test.ts',
  'src/mocks/board-handlers.test.ts',
  'src/mocks/cfd-handlers.test.ts',
  'src/mocks/comment-handlers.test.ts',
  'src/mocks/component-handlers.test.ts',
  'src/mocks/create-issue-backlog-sync.test.ts',
  'src/mocks/cycle-time-handlers.test.ts',
  'src/mocks/dashboard-handlers.test.ts',
  'src/mocks/git-webhook-handlers.test.ts',
  'src/mocks/handlers.test.ts',
  'src/mocks/mfa-handlers.test.ts',
  'src/mocks/notification-policy-handlers.test.ts',
  'src/mocks/password-handlers.test.ts',
  'src/mocks/project-lead-handlers.test.ts',
  'src/mocks/saved-filter-handlers.test.ts',
  'src/mocks/slack-channel-mapping-handlers.test.ts',
  'src/mocks/timeline-handlers.test.ts',
  'src/mocks/trusted-devices-handlers.test.ts',
  'src/mocks/velocity-handlers.test.ts',
  'src/mocks/version-handlers.test.ts',
  'src/mocks/webauthn-handlers.test.ts',
  'src/mocks/webhook-handlers.test.ts',
  'src/mocks/worklog-handlers.test.ts',
  'src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx',
  'src/routes/__tests__/projects.$projectKey.settings.slack-channels.test.tsx',
  'src/test/labels.test.ts',
]

/**
 * 탐지 문자열을 **런타임에 조립**한다.
 *
 * 리터럴로 적으면 이 판별식 파일 자신이 위반으로 잡힌다. 자기 자신을 허용목록에 넣는 것은
 * 「판별식은 검사에서 빠진다」는 구멍을 여는 것이라 택하지 않는다 — 그 자리에 진짜
 * `setupServer` 를 두면 아무도 못 잡는다. 문자열을 쪼개면 구멍 없이 자기 탐지만 피한다.
 */
const NEEDLE = 'setupServer' + '('

const WEB_ROOT = resolve(__dirname, '../..')
const SRC = join(WEB_ROOT, 'src')

/** `src` 아래 모든 `.ts`/`.tsx` 를 훑는다. */
function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, acc)
      continue
    }
    if (full.endsWith('.ts') || full.endsWith('.tsx')) acc.push(full)
  }
  return acc
}

/** 저장소 상대 경로로 정규화한다 (Windows 구분자도 `/` 로 통일). */
function toRelative(full: string): string {
  return relative(WEB_ROOT, full).split(sep).join('/')
}

describe('MSW — 로컬 setupServer 는 전역 서버 하나뿐이어야 한다', () => {
  const files = collectSourceFiles(SRC)
  const offenders = files
    .filter((f) => readFileSync(f, 'utf-8').includes(NEEDLE))
    .map(toRelative)
    .sort()

  it('소스를 실제로 훑었다 (비-공허 짝)', () => {
    // 글롭/경로가 깨지면 0건을 훑고 아래 단언이 전부 공허하게 통과한다.
    expect(files.length).toBeGreaterThan(400)
    // 스캐너가 「반드시 걸려야 하는 파일」을 실제로 찾았는가 — 탐지 로직 자체의 대조군.
    expect(offenders).toContain('src/test/server.ts')
  })

  it('★허용목록과 baseline 밖에서 새 로컬 setupServer 가 생기지 않았다', () => {
    const known = new Set<string>([...ALLOWED, ...MIGRATION_BASELINE])
    const fresh = offenders.filter((f) => !known.has(f))

    expect(fresh).toEqual([])
  })

  it('baseline 에 썩은 항목이 없다 (이주하면 목록에서 지울 것 — 래칫)', () => {
    const actual = new Set(offenders)
    const stale = MIGRATION_BASELINE.filter((f) => !actual.has(f))

    expect(stale).toEqual([])
  })

  it('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    const known = new Set<string>([...ALLOWED, ...MIGRATION_BASELINE])
    expect(known.has('src/some/brand-new.test.ts')).toBe(false)
    // 탐지식 자체 — 실제 소스에 쓰는 형태를 잡고, 전역 import 는 안 잡는지.
    const violatingLine = `const server = ${NEEDLE}...handlers)`
    expect(violatingLine.includes(NEEDLE)).toBe(true)
    expect("import { server } from '@/test/server'".includes(NEEDLE)).toBe(false)
  })
})

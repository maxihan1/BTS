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
  // ★2026-08-12 (PR #375) — **전량 이주 완료로 비웠다.**
  //
  // 봉인 시점(2026-08-09)에 59개였다. 이 목록이 빈 순간부터 로컬 `setupServer` 는
  // **신규든 잔존이든 전부 red** 다 — 래칫이 끝까지 감겼다.
  //
  // 다시 채우지 말 것. 새 테스트는 전역 `@/test/server` 에 `beforeEach(() => server.use(...))`
  // 로 등록한다(등록이 `beforeAll` 이면 전역 `resetHandlers()` 때문에 첫 테스트 뒤 사라진다).
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
    // ★이 테스트가 한 번 **항진명제**였다 (게이트2 리뷰 적발).
    //   `violatingLine` 을 NEEDLE 로 조립한 뒤 `violatingLine.includes(NEEDLE)` 을 단언하면
    //   NEEDLE 이 무엇이든 참이라 아무것도 검증하지 않는다. 이제 **실제 판정 경로**
    //   (파일 내용 → 위반 목록 → 허용목록 차집합)를 그대로 태운다.
    const known = new Set<string>([...ALLOWED, ...MIGRATION_BASELINE])

    /**
     * 실제 판정과 같은 절차 — 내용으로 위반을 뽑고 허용목록을 뺀다.
     *
     * @param files 판정할 파일들
     * @param allowlist 허용목록. 생략하면 실제 목록(`ALLOWED` + baseline)을 쓴다
     */
    function freshOffenders(
      files: Array<{ path: string; content: string }>,
      allowlist: Set<string> = known,
    ): string[] {
      return files.filter((f) => f.content.includes(NEEDLE)).map((f) => f.path).filter((p) => !allowlist.has(p))
    }

    // ① 새 파일이 로컬 서버를 만들면 잡힌다.
    expect(
      freshOffenders([
        { path: 'src/some/brand-new.test.ts', content: `const server = ${NEEDLE}...handlers)` },
      ]),
    ).toEqual(['src/some/brand-new.test.ts'])

    // ② 전역 서버를 import 만 하는 파일은 안 잡힌다 (오탐 대조군).
    expect(
      freshOffenders([
        { path: 'src/some/good.test.ts', content: "import { server } from '@/test/server'" },
      ]),
    ).toEqual([])

    // ③ 허용목록에 있는 위반은 「신규」로 세지 않는다 (동결 로직이 실제로 동작하는가).
    //
    // ★2026-08-12 — baseline 이 **비었으므로**(전량 이주) 실제 목록에서 표본을 뽑을 수 없다.
    //   예전 코드는 `MIGRATION_BASELINE[0]` 을 썼고, 목록이 비는 순간 `undefined` 가 되어
    //   이 단언이 **깨졌다**(`expected [ undefined ] to deeply equal []`).
    //   동결 **로직**은 여전히 코드에 살아 있고 누군가 목록을 다시 채울 수 있으므로,
    //   합성 허용목록으로 그 로직만 따로 검증한다 — 실제 빈 목록을 쓰면
    //   「비어서 통과」가 되어 아무것도 재지 않는다.
    const SYNTHETIC_FROZEN = 'src/legacy/frozen-by-baseline.test.ts'
    expect(
      freshOffenders(
        [{ path: SYNTHETIC_FROZEN, content: `const server = ${NEEDLE})` }],
        new Set<string>([...ALLOWED, SYNTHETIC_FROZEN]),
      ),
    ).toEqual([])

    // ③-b 같은 파일이 허용목록에 **없으면** 잡힌다 (③ 이 항진명제가 아님을 고정).
    expect(
      freshOffenders(
        [{ path: SYNTHETIC_FROZEN, content: `const server = ${NEEDLE})` }],
        new Set<string>([...ALLOWED]),
      ),
    ).toEqual([SYNTHETIC_FROZEN])
  })
})

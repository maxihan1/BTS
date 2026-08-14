// 게이트 훅 소비처가 자기 로딩 프레임 계약을 선언했는지 강제하는 판별식 (부채 매핑 15)
//
// ★이 판별식이 재는 것은 「세 화면이 **같은** 계약을 따르는가」가 **아니다.**
// 소비처마다 계약이 다르고, 그게 맞다.
//   · 임포트 라우트 — 로딩 중엔 판정을 하나도 안 그린다. 존재(404)와 권한 두 판정이 경쟁해
//     먼저 정착한 쪽이 **틀린 사유를 먼저** 보여 주던 실제 결함이 있었다.
//   · IssueCreateForm · IssueMetaPanel — 로딩 중에도 폼과 액션이 **열려 있어야** 한다.
//     미지를 거부로 읽으면 CREATE 를 실제로 가진 사용자를 영구 차단한다(훅 KDoc).
// 폐기된 예약 PR #370 본문이 「소비처 3곳 전부가 같은 계약을 따른다」를 완료 조건으로 적었다.
// 그대로 구현하면 회귀다. 그 오류를 버리고 **선언 강제**만 살렸다.
//
// ★재는 것은 「소비처가 전부 어느 쪽 계약을 따르는지 **선언돼 있는가**」 하나다.
// 지금 소비처는 3곳인데 목록을 손으로 관리하면 4번째 소비처에서 재발한다 —
// 이 저장소의 지배 결함 양식(두 목록이 서로를 검사하지 않는다)이라 처방도 표준 그대로다.
// **차집합 판별식 + 비-공허 짝 + CI 배선.**
//
// ★CI 배선을 `scripts/` 가 아니라 여기 둔 이유 (Maxi 확정 2026-08-14).
// `workflow-scripts-ci.yml` 의 `paths` 는 `apps/web/**` 를 **의도적으로** 걸지 않는다(러너 1대 큐).
// 판별식을 거기 두면 **4번째 소비처를 추가하는 바로 그 PR 에서 0회 실행**된다.
// `frontend-ci` 는 `apps/web/**` 를 통째로 걸므로 여기 두면 반드시 돈다.
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'

/**
 * 로딩 프레임 계약 2종.
 *
 * - `no-verdict-while-loading` — 권한/존재가 정착하기 전에는 **판정을 하나도 그리지 않는다**.
 * - `open-while-loading` — 정착 전에도 폼·액션을 **그대로 연다**(미지 ≠ 거부).
 */
type LoadingFrameContract = 'no-verdict-while-loading' | 'open-while-loading'

/**
 * 게이트 훅 소비처 → 그 화면이 따르는 계약.
 *
 * **새 소비처를 만들면 여기에 한 줄 추가한다.** 안 하면 아래 「선언되지 않은 소비처」가 red 다.
 * 반대로 소비처가 아닌 경로를 남겨 두면 「썩은 선언」이 red 다. 양방향이라 한쪽으로 못 샌다.
 */
const LOADING_FRAME_CONTRACTS: Readonly<Record<string, LoadingFrameContract>> = {
  // 존재(404)와 권한 두 판정이 경쟁한다. 먼저 정착한 쪽을 그리면 사용자가 틀린 사유를 먼저 읽는다.
  'src/routes/projects.$projectKey.settings.import.tsx': 'no-verdict-while-loading',
  // 제출 차단 + 「권한이 없습니다」는 **사실 주장**이라, 미지를 거부로 읽으면 거짓 문구가 된다.
  'src/components/issue/IssueCreateForm.tsx': 'open-while-loading',
  // 클론 액션 노출. 로딩 중 숨기면 권한 있는 사용자에게서 액션이 깜빡였다 나타난다.
  'src/components/issue/IssueMetaPanel.tsx': 'open-while-loading',
}

/**
 * 훅 **정의** 파일 — 소비처가 아니다.
 *
 * 정의부에도 이름이 나오므로 스캐너에 걸린다. 여기 한 줄로 빼되, 목록을 늘리지 않는다 —
 * 늘리는 순간 「선언 없이 쓰는 화면」을 허용하는 문이 된다.
 */
const DEFINITION_FILE = 'src/components/issue/create/use-issue-create-permission-gate.ts'

/**
 * 탐지 문자열을 **런타임에 조립**한다.
 *
 * 리터럴로 적으면 이 판별식 파일 자신이 소비처로 잡힌다. 자기 자신을 허용목록에 넣는 것은
 * 「판별식은 검사에서 빠진다」는 구멍을 여는 것이라 택하지 않는다(#383 에서 판별식이 자기
 * 처방문을 3종 오탐한 전례). 문자열을 쪼개면 구멍 없이 자기 탐지만 피한다.
 */
const NEEDLE = 'useIssueCreatePermissionGate' + '('

const WEB_ROOT = resolve(__dirname, '../../../../..')
const SRC = join(WEB_ROOT, 'src')

/** `src` 아래 모든 `.ts`/`.tsx` 를 훑되 **테스트 파일은 제외**한다(EC7). */
function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, acc)
      continue
    }
    if (!full.endsWith('.ts') && !full.endsWith('.tsx')) continue
    if (/\.(test|spec)\.tsx?$/.test(full)) continue
    acc.push(full)
  }
  return acc
}

/** 저장소 상대 경로로 정규화한다 (Windows 구분자도 `/` 로 통일). */
function toRelative(full: string): string {
  return relative(WEB_ROOT, full).split(sep).join('/')
}

/**
 * 실제 소비처 집합과 선언 집합의 **양방향 차집합**.
 *
 * 순수 함수로 뺀 이유 — 소스 스캔 결과에 기대지 않으므로 파일이 어떻게 바뀌어도
 * 아래 대조군 3종이 살아 있다. 스캔이 0건을 돌려주는 사고가 나도 대조군은 red 를 유지한다.
 *
 * @param actual 스캐너가 찾은 실제 소비처 경로들
 * @param declared 레지스트리에 선언된 경로들
 * @returns `undeclared` = 쓰는데 선언 없음 · `stale` = 선언했는데 안 씀
 */
function diffConsumers(
  actual: readonly string[],
  declared: readonly string[],
): { undeclared: string[]; stale: string[] } {
  const actualSet = new Set(actual)
  const declaredSet = new Set(declared)
  return {
    undeclared: actual.filter((p) => !declaredSet.has(p)).sort(),
    stale: declared.filter((p) => !actualSet.has(p)).sort(),
  }
}

const sourceFiles = collectSourceFiles(SRC)
const consumers = sourceFiles
  .filter((f) => readFileSync(f, 'utf-8').includes(NEEDLE))
  .map(toRelative)
  .filter((p) => p !== DEFINITION_FILE)
  .sort()

describe('로딩 프레임 계약 — 소비처 선언 강제', () => {
  it('소스를 실제로 훑었다 (비-공허 짝)', () => {
    // 경로가 깨지면 0건을 훑고 아래 단언이 전부 공허하게 통과한다.
    expect(sourceFiles.length).toBeGreaterThan(400)
    // 스캐너가 「반드시 걸려야 하는 파일」을 실제로 찾았는가 — 탐지 로직 자체의 대조군.
    expect(consumers).toContain('src/components/issue/IssueCreateForm.tsx')
  })

  it('★게이트 훅을 쓰는 화면은 전부 자기 로딩 프레임 계약을 선언한다', () => {
    const { undeclared } = diffConsumers(consumers, Object.keys(LOADING_FRAME_CONTRACTS))

    expect(undeclared).toEqual([])
  })

  it('선언에 썩은 항목이 없다 (소비처가 사라지면 선언도 지울 것)', () => {
    const { stale } = diffConsumers(consumers, Object.keys(LOADING_FRAME_CONTRACTS))

    expect(stale).toEqual([])
  })
})

describe('로딩 프레임 계약 — 차집합 판정 대조군 (비-공허 짝)', () => {
  // ★픽스처 경로는 실물과 겹치지 않는 가짜다. 실물을 쓰면 실물이 바뀔 때 대조군이 함께 죽는다.
  const A = 'src/fixture/a.tsx'
  const B = 'src/fixture/b.tsx'

  it('쓰는데 선언이 없으면 잡는다 (4번째 화면이 조용히 생기는 경우)', () => {
    expect(diffConsumers([A, B], [A])).toEqual({ undeclared: [B], stale: [] })
  })

  it('선언했는데 안 쓰면 잡는다 (화면을 지우고 선언만 남긴 경우)', () => {
    expect(diffConsumers([A], [A, B])).toEqual({ undeclared: [], stale: [B] })
  })

  it('정확히 일치하면 위반 0 이다 (오탐 대조군)', () => {
    expect(diffConsumers([A, B], [B, A])).toEqual({ undeclared: [], stale: [] })
  })
})

// 지금 이 세션이 **무슨 작업인지**를 터미널 탭 제목과 상태줄에 띄운다
//
// ## 왜 있나
//
// 2026-09-04 Maxi. 작업을 병렬로 돌릴 때 **어느 탭이 어느 작업인지 구분이 안 된다.**
// 터미널 탭에도 Claude 앱 세션 목록에도 전부 같은 이름(저장소명 또는 자동 생성 제목)이
// 뜨기 때문이다. 게이트 1·2 처럼 사람이 멈춰 서서 결정하는 자리에서 특히 문제가 된다 —
// 승인 화면만 보고는 **어느 작업의 승인인지** 알 수 없다.
//
// ## 두 곳에 같은 값을 쓴다
//
//   1. **터미널 탭 제목** — OSC 이스케이프. iTerm2·Terminal.app·VS Code 내장 터미널이
//      모두 해석한다. 세션이 살아 있는 동안만 유지된다.
//   2. **`.bts-cache/session-label`** — 상태줄 스크립트가 읽는다. 파일이라 세션을 옮겨도 남는다.
//
// 한 곳만 쓰면 갈린다. 그래서 **이 스크립트 하나가 둘 다** 쓴다.
//
// ## ★값의 정본은 classify.json 이다
//
// 티어·타입·slug 를 손으로 다시 적지 않는다. `bts-start` 가 이미 계산해 캐시에 넣은 값을
// 읽어 쓴다 — 두 자리가 각자 만들면 라벨과 실제 작업이 갈리고, 그러면 라벨이 있으나
// 마나가 아니라 **틀린 정보**가 된다.
//
// 사용. node --experimental-strip-types scripts/workflow/session-label.ts [--step "<단계>"]
// 판별식. scripts/workflow/session-label.test.ts

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 상태줄 스크립트가 읽는 파일. `.gitignore` 대상이라 저장소에 안 남는다. */
export const LABEL_FILE = '.bts-cache/session-label'

/** `bts-start` 가 쓰는 분류 캐시. 라벨 값의 정본. */
export const CLASSIFY_FILE = '.bts-cache/classify.json'

/** 탭 제목 최대 길이. 넘치면 탭이 좁아져 오히려 못 읽는다. */
export const MAX_LEN = 48

export interface LabelParts {
  tier?: string
  type?: string
  slug?: string
  step?: string
}

/**
 * 사람이 탭 하나 보고 「아, 그 작업」이라고 알아보는 최소 표현.
 *
 * 티어를 맨 앞에 두는 이유 — 병렬로 도는 것 중 **무엇이 위험한 작업인지**가
 * 한눈에 갈려야 한다. T3 탭과 T0 탭이 같은 모양이면 라벨의 값이 반감된다.
 */
export function renderLabel(p: LabelParts): string {
  const head = p.tier !== undefined && p.tier !== '' ? p.tier : '?'
  const body = [p.type, p.slug].filter((x) => x !== undefined && x !== '').join(' · ')
  const tail = p.step !== undefined && p.step !== '' ? ` — ${p.step}` : ''
  const full = `${head} ${body}${tail}`.replace(/\s+/g, ' ').trim()
  return full.length <= MAX_LEN ? full : `${full.slice(0, MAX_LEN - 1)}…`
}

/** 터미널 탭 제목을 세우는 OSC 시퀀스 — `ESC ] 0 ; <제목> BEL`. */
export function osc(title: string): string {
  // ★제어문자를 먼저 제거한다. 제목에 이스케이프가 섞이면 터미널이 시퀀스를 잘못 닫고
  //   그 뒤 출력이 통째로 깨진다 — 라벨 하나 붙이려다 화면을 망가뜨리는 교환은 하지 않는다.
  const safe = [...title].filter((c) => c.codePointAt(0)! >= 0x20 && c.codePointAt(0) !== 0x7f).join('')
  return `\u001b]0;${safe}\u0007`
}

function readClassify(): LabelParts {
  const p = path.join(REPO_ROOT, CLASSIFY_FILE)
  if (!fs.existsSync(p)) return {}
  try {
    const j = JSON.parse(fs.readFileSync(p, 'utf-8')) as Record<string, unknown>
    return {
      tier: typeof j.tier === 'string' ? j.tier : undefined,
      type: typeof j.type === 'string' ? j.type : undefined,
      slug: typeof j.slug === 'string' ? j.slug : undefined,
    }
  } catch {
    // 캐시가 깨졌으면 라벨을 안 다는 편이 낫다. 틀린 라벨은 없느니만 못하다.
    return {}
  }
}

function main(argv: string[]): number {
  const i = argv.indexOf('--step')
  const step = i === -1 ? undefined : argv[i + 1]
  const label = renderLabel({ ...readClassify(), step })

  const dir = path.join(REPO_ROOT, path.dirname(LABEL_FILE))
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(REPO_ROOT, LABEL_FILE), label + '\n', 'utf-8')

  process.stdout.write(osc(label))
  process.stderr.write(`▶ 세션 라벨. ${label}\n`)
  return 0
}

if (process.argv[1] !== undefined && process.argv[1].endsWith('session-label.ts')) {
  process.exit(main(process.argv.slice(2)))
}

// 화면 · 서버 문구에 docs/WORDS.md 의 「쓰지 않는 말」과 반말 말끝이 없는지 검사한다 (FR-UX-06 문구 정책 §8)
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

const REPO = resolve(import.meta.dirname, '../../../..')
const WEB = join(REPO, 'apps/web/src')
const BACKEND = join(REPO, 'backend/modules')

const walk = (dir: string, keep: (f: string) => boolean, skipDirs: string[]): string[] =>
  readdirSync(dir).flatMap((f) => {
    const p = join(dir, f)
    if (statSync(p).isDirectory()) return skipDirs.includes(f) ? [] : walk(p, keep, skipDirs)
    return keep(f) ? [p] : []
  })

type Line = { at: string; l: string }

/** 주석(블록 · JSX · HTML · 줄)을 지운 한글 코드 줄. 줄 번호는 지키려고 블록 주석은 줄바꿈만 남긴다 */
const koreanLines = (file: string): Line[] =>
  readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\/|<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ''))
    .split('\n')
    .map((l, i) => ({ at: `${file.replace(`${REPO}/`, '')}:${i + 1}`, l: l.replace(/(^|[^:'"`\\\w])\/\/.*$/, '$1') }))
    .filter(({ l }) => /[가-힣]/.test(l))

const webLines = (): Line[] =>
  [
    ...walk(WEB, (f) => /\.tsx?$/.test(f) && !/\.test\./.test(f), ['__tests__', 'test', 'mocks']),
    join(REPO, 'apps/web/index.html'),
  ]
    .flatMap(koreanLines)
    .filter(({ l }) => !/console\./.test(l))

/**
 * 서버 글 — 로그 호출 · 개발자가 읽는 불변식(`error` · `check` · `require` 와 그 `NotNull`, `IllegalStateException`) ·
 * OpenAPI 설명과 `description =`(개발자 문서 · 마이그레이션이 DB 에 따로 심은 값의 사본)은 뺀다.
 * 여러 줄에 걸친 호출은 괄호 · 중괄호가 닫힐 때까지 함께 뺀다
 */
const NOT_FOR_PEOPLE =
  /(?:\b(?:log|LOG|logger|LOGGER|[a-z]\w*Log|[a-z]\w*Logger)\.(?:debug|info|warn|error|trace)\b|(?<![\w.])(?:error|check|checkNotNull|require|requireNotNull)\b|IllegalStateException\b|@(?:Operation|Parameter|Schema|Tag)\b|\bApiResponses?\(|\bdescription\s*=)/
const serverLines = (): Line[] =>
  walk(BACKEND, (f) => f.endsWith('.kt') && f !== 'OpenApiConfig.kt', ['test', 'build', 'generated']).flatMap((file) => {
    let depth = 0
    const count = (s: string) => (s.match(/[({]/g) ?? []).length - (s.match(/[)}]/g) ?? []).length
    return koreanLinesAll(file).filter(({ l }) => {
      if (depth > 0) {
        depth = Math.max(0, depth + count(l))
        return false
      }
      const at = l.search(NOT_FOR_PEOPLE)
      if (at < 0) return /[가-힣]/.test(l)
      depth = Math.max(0, count(l.slice(at)))
      return false
    })
  })
/** 서버 파일은 한글이 없는 줄도 괄호 짝을 세야 해서 줄을 다 돌려준다 */
const koreanLinesAll = (file: string): Line[] =>
  readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ''))
    .split('\n')
    .map((l, i) => ({ at: `${file.replace(`${REPO}/`, '')}:${i + 1}`, l: l.replace(/(^|[^:"\\\w])\/\/.*$/, '$1') }))

// docs/WORDS.md §1 표의 마지막 열(백틱 안 정규식) — 표에 줄을 더하면 이 검사가 저절로 본다
const table = readFileSync(join(REPO, 'docs/WORDS.md'), 'utf8').split('## 1.')[1] ?? ''
const rows = table.split('\n').filter((l) => l.startsWith('|')).slice(2)
const words = rows
  .map((l) => l.match(/\|\s*`([^`]+)`\s*\|\s*$/))
  .filter((m): m is RegExpMatchArray => m !== null)
  .map((m) => new RegExp((m[1] ?? '').replace(/\\\|/g, '|')))

const offWords = (lines: Line[]) =>
  lines.flatMap(({ at, l }) =>
    words.filter((re) => re.test(l)).map((re) => `${at} 「${l.match(re)?.[0] ?? ''}」 ${l.trim().slice(0, 100)}`),
  )

// 문장 끝 반말 — 「~다」(뒤에 띄어 이어지는 「했다 다시」 는 문장 끝이 아님) · 「~할 것」.
// 「~ㅂ니다」 · 「~습니다」 와 조사 「마다」(「유형마다,」)는 통과
const jong = (c: string) => (c.charCodeAt(0) - 0xac00) % 28
const banmal = (lines: Line[]) =>
  lines
    .filter(({ l }) =>
      [...l.matchAll(/([가-힣])(는)?다(?![가-힣])(?! [가-힣])/g)].some(
        (m) =>
          !(m[1] === '마' && !m[2]) &&
          !(m[1] === '니' && !m[2] && /[가-힣]/.test(l[m.index - 1] ?? '') && jong(l[m.index - 1] ?? '') === 17),
      ) || /[가-힣] 것(?=["'`]|$)/.test(l),
    )
    .map(({ at, l }) => `${at} ${l.trim().slice(0, 100)}`)

describe('표준 단어 (docs/WORDS.md)', () => {
  it('표의 줄마다 정규식을 하나씩 읽는다', () => {
    expect(words.length).toBeGreaterThan(0)
    expect(words.length, '마지막 열 모양이 틀린 줄이 소리 없이 빠지지 않게').toBe(rows.length)
  })

  it('화면 글에 「쓰지 않는 말」이 없다', () => {
    expect(offWords(webLines())).toEqual([])
  })

  it('서버가 사람에게 보내는 글에 「쓰지 않는 말」이 없다', () => {
    expect(offWords(serverLines())).toEqual([])
  })

  it('화면 · 서버 글의 말끝이 반말(「~다」 · 「~할 것」)이 아니다', () => {
    expect(banmal([...webLines(), ...serverLines()])).toEqual([])
  })
})

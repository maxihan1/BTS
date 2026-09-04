// 「이 판별식이 차단한다」고 적힌 곳이 **실재하는 판별식**을 가리키는지 대조한다
//
// ## 무엇을 지키나
//
// 2026-09-04 진단. 커밋 `eca4a9c7f` 에서 판별식 3종이 삭제됐는데, 그것들을 가리키는
// 「되돌리면 이 판별식이 차단한다」는 문장이 **10곳에 그대로 남아 있었다.**
//
//   - ci-runner-label-alignment.test.ts   → CI 워크플로우 4파일에 8곳
//   - runner-healthcheck-wiring.test.ts   → workflow-scripts-ci.yml · 런북
//   - merged-pr-run-cleanup.test.ts       → bts-merge/SKILL.md · behavior-rules.md 배치표
//
// 문서를 읽는 사람도 에이전트도 「안전장치가 있다」고 믿는다. 실제로는 아무것도 막지 않는다.
// 이 저장소가 스스로 이름 붙인 지배 결함 양식 그대로다 — **두 목록이 서로를 안 본다.**
// 처방도 그 양식이 정한 대로다. 차집합 판별식 + 비-공허 짝.
//
// ## ★무엇을 대상에서 빼는가 — 그리고 왜
//
// `docs/plans/**` · `docs/specs/**` · `docs/_archive/**` · `CHANGELOG.md` · `TODOS.md` 는
// **그때 그랬다는 기록**이다. 당시 실재한 판별식을 적은 것이 지금 없다고 해서 거짓이 되지 않는다.
// 기록을 고치면 「무엇이 언제 사라졌나」를 되짚을 근거가 없어진다.
//
// 대상은 **지금 읽고 따르는 문서**뿐이다 — CI 정의 · 스킬 · 규칙 · 런북 · 훅.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/**
 * 지금 읽고 따르는 문서. 여기에 적힌 판별식 이름은 실재해야 한다.
 *
 * 디렉터리는 재귀로 훑는다 — 새 파일이 생겨도 자동으로 대상이 된다(목록을 손으로 유지하면
 * 그 목록 자체가 또 하나의 썩는 두 번째 목록이 된다).
 */
const LIVE_ROOTS: readonly string[] = [
  '.github/workflows',
  '.claude/skills',
  '.claude/agents',
  'docs/rules',
  'docs/runbooks',
  '.husky',
  'CLAUDE.md',
  'DEVELOPMENT.md',
  'DATA.md',
  'CONTRIBUTING.md',
]

/** 판별식이 실제로 사는 곳. */
const DISCRIMINANT_DIRS: readonly string[] = ['scripts/workflow', 'scripts/doc-index', 'scripts']

/**
 * 「이건 삭제됐다」는 정직한 서술의 표시.
 *
 * 이 표시가 같은 줄에 있으면 없는 판별식을 언급해도 통과다 — 주장이 아니라 기록이기 때문이다.
 * 표시를 남용해 「삭제됐지만 여전히 막는다」류를 쓰는 것은 이 판별식이 잡지 못한다.
 * 그 경계는 사람이 리뷰에서 본다.
 */
const DELETED_MARK = /삭제됐다|삭제됨|제거됐다|없어졌다/

function walk(rel: string, out: string[] = []): string[] {
  const abs = path.join(REPO_ROOT, rel)
  if (!fs.existsSync(abs)) return out
  const st = fs.statSync(abs)
  if (st.isFile()) {
    out.push(rel)
    return out
  }
  for (const e of fs.readdirSync(abs)) {
    if (e === 'node_modules' || e.startsWith('.git')) continue
    walk(path.posix.join(rel, e), out)
  }
  return out
}

/** 저장소에 실재하는 판별식 파일 이름(확장자 포함). */
export function existingDiscriminants(): Set<string> {
  const out = new Set<string>()
  for (const dir of DISCRIMINANT_DIRS) {
    for (const rel of walk(dir)) {
      const base = path.basename(rel)
      if (base.endsWith('.test.ts') || base.endsWith('.test.mjs')) out.add(base)
    }
  }
  return out
}

/** 텍스트에서 언급된 판별식 파일 이름을 뽑는다. */
export function referencedDiscriminants(source: string): string[] {
  return [...source.matchAll(/([A-Za-z0-9._-]+\.test\.(?:ts|mjs))/g)].map((m) => m[1])
}

describe('판별식 참조 정합 — 없는 장치를 「막는다」고 적지 않는다', () => {
  const existing = existingDiscriminants()
  const files = LIVE_ROOTS.flatMap((r) => walk(r)).filter(
    (f) => f.endsWith('.md') || f.endsWith('.yml') || f.endsWith('.yaml') || f.startsWith('.husky/'),
  )

  test('★양성 대조군 — 훑기가 비어 있지 않다', () => {
    // 이 판별식이 공허해지는 가장 흔한 경로는 「훑을 파일을 하나도 못 찾는 것」이다.
    assert.ok(files.length > 20, `살아 있는 문서를 ${files.length}개밖에 못 찾았다 — 훑기가 깨졌다`)
    assert.ok(existing.size > 20, `판별식을 ${existing.size}개밖에 못 찾았다 — 경로가 바뀌었다`)
  })

  test('★양성 대조군 — 실제로 참조를 뽑아낸다', () => {
    const total = files.reduce(
      (n, f) => n + referencedDiscriminants(fs.readFileSync(path.join(REPO_ROOT, f), 'utf-8')).length,
      0,
    )
    assert.ok(total > 5, `참조를 ${total}건밖에 못 뽑았다 — 추출기가 깨졌거나 참조가 전부 사라졌다`)
  })

  test('★살아 있는 문서가 가리키는 판별식이 전부 실재한다', () => {
    const dangling: string[] = []
    for (const f of files) {
      const src = fs.readFileSync(path.join(REPO_ROOT, f), 'utf-8')
      for (const [i, line] of src.split('\n').entries()) {
        // ★「그 판별식은 삭제됐다」는 **정직한 서술**은 허용한다.
        //   금지 대상은 없는 장치를 두고 **「막는다·차단한다·강제한다」고 주장하는 것**이다.
        //   같은 줄에 삭제 표시가 있으면 주장이 아니라 사실 기록이므로 통과시킨다 —
        //   이 예외가 없으면 「무엇이 언제 사라졌나」를 문서에 아예 못 적게 된다.
        if (DELETED_MARK.test(line)) continue
        for (const name of referencedDiscriminants(line)) {
          if (!existing.has(name)) dangling.push(`${f}:${i + 1}  →  ${name}`)
        }
      }
    }
    assert.deepEqual(
      dangling,
      [],
      '존재하지 않는 판별식을 가리킨다. 「이것이 차단한다」는 선언이 거짓이 된다.\n' +
        '  판별식을 되살리거나, 그 문장을 지우거나, 무엇이 대신 막는지 적을 것.\n\n' +
        dangling.map((d) => `    ${d}`).join('\n'),
    )
  })
})

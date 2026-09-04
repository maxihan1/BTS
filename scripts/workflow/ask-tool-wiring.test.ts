// 사람이 고르는 자리는 `AskUserQuestion` 으로 낸다 — 산문 3지선다가 다시 스며들지 않게
//
// ## 왜 있나
//
// 이 저장소에서 **세 세션 연속** 같은 지적이 나왔다(2026-08-23 · 08-24 · 09-02).
// 「1. 승인 2. 수정 3. 보류」를 본문에 적고 숫자를 타이핑하게 만든 것이다.
// 무서운 것은 **세 번 다 원인이 달랐다**는 점이다.
//
//   1차 — 「이 질문은 거부됐다」는 과거 기록을 「이 도구를 쓰지 마라」로 오독했다.
//   2차 — 요약을 다 써 내자 **완성된 느낌**이 들어 그 뒤의 질문 단계가 통째로 증발했다.
//   3차 — 한 턴에서 도구를 두 번 썼더니 「질문 단계는 처리했다」는 감각이 생겼다.
//
// 세 번 다 스킬 본문이 도구를 명시하고 있었다. **산문 지시로는 안 막힌다는 것이 이미
// 실증됐다.** 그래서 기계 대조를 둔다 — 사람의 주의력에 세 번 실패한 것을 네 번째로
// 다시 맡기지 않는다.
//
// ## ★무엇을 재는가
//
// 「도구를 실제로 불렀는가」는 잴 수 없다(런타임 행위다). 잴 수 있는 것은
// **선택지가 정해진 자리에 도구 지시가 붙어 있는가**다. 약한 형태임을 인정하고,
// 대신 그 한계를 좁힌다 — 게이트처럼 절대 놓치면 안 되는 자리는 개별 단언으로 못 박는다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const TOOL = 'AskUserQuestion'
const SKILL_DIR = '.claude/skills'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

/** bts 체인 스킬 본문 전량. */
function btsSkills(): { rel: string; text: string }[] {
  const out: { rel: string; text: string }[] = []
  for (const d of fs.readdirSync(path.join(REPO_ROOT, SKILL_DIR))) {
    if (!d.startsWith('bts')) continue
    const dir = path.join(REPO_ROOT, SKILL_DIR, d)
    if (!fs.statSync(dir).isDirectory()) continue
    for (const f of fs.readdirSync(dir)) {
      if (!f.endsWith('.md')) continue
      out.push({ rel: path.posix.join(SKILL_DIR, d, f), text: fs.readFileSync(path.join(dir, f), 'utf-8') })
    }
  }
  return out
}

/**
 * 「사람이 고르는 자리」로 읽히는 줄.
 *
 * 선택지 구분자(`①②③` · `/` 로 나뉜 승인·중단류 · `~할까요`)가 있는 줄을 본다.
 * 완벽한 판별은 불가능하다 — 놓치는 쪽(위음성)을 감수하고 **거짓 red 를 내지 않는** 쪽으로 짠다.
 * 거짓 red 가 잦으면 사람이 판별식을 꺼 버리고, 그 순간 계약 전체가 사라진다.
 */
export function choiceLines(text: string): string[] {
  // 사람이 「고르는」 말. 이것이 없으면 ①②③ 는 절차 순서다(예: ui 시각 검증 트랙 4단계).
  const CHOICE_WORDS = /승인|중단|보류|폐기|이어가기|진행 여부|할까요|어느 쪽|선택|옵션|discard/
  return text.split('\n').filter((l) => {
    if (/①.*②.*③/.test(l) && CHOICE_WORDS.test(l)) return true
    if (/`승인`.*`중단`|`승인`.*`보류`/.test(l)) return true
    if (/3지선다|3 ?옵션/.test(l)) return true
    return false
  })
}

describe('질문 배선 — 선택지가 정해진 자리에 도구가 붙어 있다', () => {
  const skills = btsSkills()

  test('★양성 대조군 — 선택 지점을 실제로 찾아낸다', () => {
    const total = skills.reduce((n, s) => n + choiceLines(s.text).length, 0)
    assert.ok(total >= 4, `선택 지점을 ${total}곳밖에 못 찾았다 — 추출기가 깨졌거나 배선이 사라졌다`)
  })

  test('★선택지가 열거된 줄에는 도구 지시가 붙어 있다', () => {
    const bare: string[] = []
    for (const { rel, text } of skills) {
      const lines = text.split('\n')
      for (const [i, line] of lines.entries()) {
        if (choiceLines(line).length === 0) continue
        // 같은 줄, 또는 바로 앞뒤 2줄 안에 도구 이름이 있으면 배선된 것으로 본다.
        const window = lines.slice(Math.max(0, i - 2), i + 3).join('\n')
        if (!window.includes(TOOL)) bare.push(`${rel}:${i + 1}  ${line.trim().slice(0, 80)}`)
      }
    }
    assert.deepEqual(
      bare,
      [],
      `선택지를 열거해 놓고 ${TOOL} 지시가 없다. 산문 3지선다는 세 세션 연속 재발한 결함이다.\n\n` +
        bare.map((b) => `    ${b}`).join('\n'),
    )
  })
})

describe('게이트 — 개별로 못 박는다', () => {
  const bts = read(`${SKILL_DIR}/bts/SKILL.md`)

  test('★게이트 섹션이 두 게이트 모두에 도구를 요구한다', () => {
    const idx = bts.indexOf('## 게이트')
    assert.ok(idx !== -1, 'bts/SKILL.md 에 게이트 섹션이 없다')
    const section = bts.slice(idx, idx + 2400)
    assert.ok(section.includes(TOOL), `게이트 섹션에 ${TOOL} 지시가 없다`)
    assert.match(
      section,
      /두 게이트 모두|게이트 1[^\n]*필수[\s\S]*게이트 2[^\n]*필수/,
      '게이트 1 에만, 또는 게이트 2 에만 도구가 걸려 있다 — 두 정지선의 배선이 비대칭이면\n' +
        '  약한 쪽에서 산문으로 묻게 된다(2026-09-04 진단에서 게이트 1 이 그 상태였다).',
    )
  })

  test('★게이트 1 을 다루는 스킬도 도구를 안다', () => {
    const rp = read(`${SKILL_DIR}/bts-review-plan/SKILL.md`)
    assert.ok(
      rp.includes(TOOL),
      'bts-review-plan 은 게이트 1 로 넘기는 유일한 스킬인데 도구를 한 번도 언급하지 않는다',
    )
  })

  test('★「요약을 냈다」가 「질문을 냈다」가 아님을 명시한다', () => {
    // 2차 재발의 원인이다 — 요약을 다 쓰면 완성된 느낌이 들어 질문 단계가 증발한다.
    assert.match(
      bts,
      /요약은 질문의 재료|요약을 다 쓴 것이 질문을 낸 것이 아니다/,
      '요약과 질문을 구분하는 문장이 없다 — 2026-08-24 재발의 원인이 그대로 남는다',
    )
  })
})

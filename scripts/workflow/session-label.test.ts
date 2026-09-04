// 세션 라벨 계약 — 값의 정본 · 터미널 안전성 · 배선
//
// ## 무엇을 지키나
//
// 2026-09-04 Maxi. 병렬 작업 시 어느 탭이 어느 작업인지 구분이 안 됐다.
// 특히 게이트에서 문제가 됐다 — **승인 화면만 보고는 어느 작업의 승인인지 알 수 없다.**
//
//   1. 라벨 값을 손으로 만들지 않는다 — `classify.json` 이 정본이다.
//      두 자리가 각자 만들면 라벨과 실제 작업이 갈리고, 그러면 **틀린 라벨**이 된다.
//      없는 라벨보다 틀린 라벨이 나쁘다.
//   2. 제목에 제어문자가 새어 들어가지 않는다 — OSC 시퀀스가 잘못 닫히면
//      그 뒤 터미널 출력이 통째로 깨진다. 라벨 하나 붙이려다 화면을 망가뜨리지 않는다.
//   3. `bts-start` 와 게이트 두 곳이 실제로 이 스크립트를 부른다.
//      안 부르면 1·2 가 전부 장식이다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { LABEL_FILE, MAX_LEN, osc, renderLabel } from './session-label.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT = 'scripts/workflow/session-label.ts'
const START_SKILL = '.claude/skills/bts-start/SKILL.md'
const BTS_SKILL = '.claude/skills/bts/SKILL.md'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

describe('라벨 표현', () => {
  test('티어가 맨 앞에 온다', () => {
    // 병렬로 도는 것 중 무엇이 위험한 작업인지가 한눈에 갈려야 한다.
    const l = renderLabel({ tier: 'T3', type: 'migration', slug: 'add-index' })
    assert.ok(l.startsWith('T3'), `티어가 앞이 아니다: ${l}`)
  })

  test('단계를 붙이면 뒤에 온다', () => {
    const l = renderLabel({ tier: 'T2', type: 'feature', slug: 'x', step: '게이트 1' })
    assert.match(l, /게이트 1$/)
  })

  test('★긴 라벨을 잘라 탭을 망가뜨리지 않는다', () => {
    const l = renderLabel({ tier: 'T3', type: 'migration', slug: 'a'.repeat(200) })
    assert.ok(l.length <= MAX_LEN, `${l.length}자 — 상한 ${MAX_LEN} 초과`)
    assert.ok(l.endsWith('…'), '잘렸으면 잘렸다고 보여야 한다')
  })

  test('분류가 비어도 죽지 않는다', () => {
    const l = renderLabel({})
    assert.ok(l.length > 0, '빈 라벨은 OSC 를 깨뜨린다')
  })
})

describe('★터미널 안전성', () => {
  test('OSC 가 ESC 로 시작해 BEL 로 끝난다', () => {
    const o = osc('T2 feature')
    assert.equal(o.codePointAt(0), 0x1b, 'ESC 로 시작하지 않는다')
    assert.equal(o.slice(1, 4), ']0;', 'OSC 0 시퀀스가 아니다')
    assert.equal(o.codePointAt(o.length - 1), 0x07, 'BEL 로 닫지 않는다')
  })

  test('★제목에 섞인 제어문자를 제거한다', () => {
    // 픽스처에 실제 제어문자를 넣는다. 이스케이프로 적지 않으면 이 테스트가 공허해진다.
    const evil = 'badtitle]0;pwn'
    const o = osc(evil)
    const body = o.slice(4, -1)
    for (const c of body) {
      const cp = c.codePointAt(0)!
      assert.ok(cp >= 0x20 && cp !== 0x7f, `제어문자가 남았다: U+${cp.toString(16)}`)
    }
    // 시퀀스가 정확히 한 번만 닫힌다 — 두 번 닫히면 뒤 출력이 화면에 쏟아진다.
    assert.equal([...o].filter((c) => c.codePointAt(0) === 0x07).length, 1)
  })
})

describe('배선 — 만들어 두고 안 쓰는 상태로 돌아가지 않는다', () => {
  test('★bts-start 가 분류 직후 라벨을 세운다', () => {
    assert.ok(read(START_SKILL).includes(SCRIPT), `${START_SKILL} 이 ${SCRIPT} 를 부르지 않는다`)
  })

  test('★게이트를 내기 전에 라벨을 갱신한다', () => {
    const bts = read(BTS_SKILL)
    assert.ok(bts.includes(SCRIPT), `${BTS_SKILL} 이 ${SCRIPT} 를 부르지 않는다`)
    assert.match(
      bts,
      /session-label\.ts --step/,
      '게이트 라벨에 --step 을 안 넘긴다 — 어느 게이트인지 구분이 안 된다',
    )
  })

  test('라벨 파일이 저장소에 안 남는다', () => {
    const ig = read('.gitignore')
    const dir = LABEL_FILE.split('/')[0]
    assert.ok(
      ig.split('\n').some((l) => l.trim() === `${dir}/` || l.trim() === dir),
      `${dir} 가 .gitignore 에 없다 — 세션마다 다른 값이 커밋에 섞인다`,
    )
  })
})

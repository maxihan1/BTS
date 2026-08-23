// TODOS.md 대량 이동이 내용을 잃지 않았는지 기계로 확인하는 판별식
//
// ## 이 파일이 지키는 것
//
// ① `compareTodoIntegrity` — 두 버전의 항목 `(상태, 제목, 본문)` **멀티셋** 양방향 차집합.
//    사라진 항목·생긴 항목을 **제목과 함께** 열거한다.
// ② `compareAllLines` — 두 버전의 **모든 줄** 멀티셋 차집합. 항목 안팎을 가리지 않으므로
//    파일 머리 산문이나 절 사이 설명이 사라져도 잡는다.
// ③ `judgePureMove` — CLI 가 쓰는 **순수 이동 판정 그 자체**. 판정을 여기서 다시 적지
//    않는다. 다시 적으면 판별기를 어떻게 바꿔도 초록이다(2026-08-18 코드 리뷰 C1 실측).
// ④ **F2b 실파일 축** — merge-base 대비 부채 항목이 사라졌는지를 매 PR 이 검사한다.
//    base 를 못 얻으면 **실패**다. skip 은 exit 0 이라 「안 돌았다」가 「통과」로 보인다.
//
// ## 이 파일이 지키지 **않는** 것
//
// - 항목이 **어느 절에 있는가**. 그건 `todos-structure-contract.test.ts` 소관이다.
// - 두 줄(「쉬운 말」·「방치하면」) 존재. `todos-plain-language-contract.test.ts` 소관.
// - `✅` 섹션 본문의 순수성. `todos-resolved-section-purity.test.ts` 소관.
// - 마스터 계획과의 매핑. `debt-ledger-mapping.test.ts` 소관.
//
// ## ★파서를 새로 적지 않는다
//
// 항목 파싱은 `build-dashboard.mjs` 의 `parseTodos` 를 **import** 한다. `# `·`## ` 를 읽는
// 코드를 새로 적을 때마다 코드펜스 판정을 잊는 것이 이 저장소의 반복 사고다(#389 5회).
// 줄 멀티셋 축(②)은 파싱이 아니라 `split('\n')` 이라 스캐너가 아니다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import {
  compareTodoIntegrity,
  compareAllLines,
  isStructuralLine,
  judgePureMove,
  readBaseTodos,
} from './todos-reorder-integrity.mjs'
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const LEDGER = path.join(REPO_ROOT, 'TODOS.md')

/** 합성 장부. 항목 3건 + 머리 산문. */
const BASE = [
  '# TODOS',
  '',
  '이 줄은 어느 항목에도 속하지 않는 머리 산문이다.',
  '',
  '## ⬜ apps/web — 첫 번째',
  '',
  '**무엇.** 본문 A 첫 줄.',
  '**또.** 본문 A 둘째 줄.',
  '',
  '## ⬜ 도구 — 두 번째',
  '',
  '**무엇.** 본문 B.',
  '',
  '## ✅ 인프라 — 세 번째 (해소)',
  '',
  '**무엇.** 본문 C.',
  '',
].join('\n')

describe('TODOS.md — 대량 이동 무손실 대조', () => {
  test('합성 장부에서 항목을 실제로 수집한다 (비-공허 짝)', () => {
    const r = compareTodoIntegrity(BASE, BASE)
    assert.equal(r.beforeCount, 3, '합성 장부에서 항목 3건을 못 뽑았다 — 파서가 깨졌거나 서식이 바뀌었다')
    assert.deepEqual(r.lost, [])
    assert.deepEqual(r.gained, [])
  })

  test('순수 이동은 차집합 0 이다 (음성 대조군)', () => {
    // 항목 순서만 뒤집는다. 줄 내용은 한 글자도 안 바꾼다.
    const moved = [
      '# TODOS',
      '',
      '이 줄은 어느 항목에도 속하지 않는 머리 산문이다.',
      '',
      '## ✅ 인프라 — 세 번째 (해소)',
      '',
      '**무엇.** 본문 C.',
      '',
      '## ⬜ 도구 — 두 번째',
      '',
      '**무엇.** 본문 B.',
      '',
      '## ⬜ apps/web — 첫 번째',
      '',
      '**무엇.** 본문 A 첫 줄.',
      '**또.** 본문 A 둘째 줄.',
      '',
    ].join('\n')
    const r = compareTodoIntegrity(BASE, moved)
    assert.deepEqual(r.lost, [], '순수 이동인데 항목이 사라졌다고 보고했다')
    assert.deepEqual(r.gained, [], '순수 이동인데 항목이 생겼다고 보고했다')
    const lines = compareAllLines(BASE, moved)
    assert.deepEqual(lines.lost, [], '순수 이동인데 줄이 사라졌다고 보고했다')
    assert.deepEqual(lines.gained, [], '순수 이동인데 줄이 생겼다고 보고했다')
  })

  test('양성 ① — 항목 1건을 통째로 지우면 잡는다', () => {
    const after = BASE.replace('## ⬜ 도구 — 두 번째\n\n**무엇.** 본문 B.\n\n', '')
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.lost.length, 1, '항목이 통째로 사라졌는데 못 잡았다')
    assert.match(r.lost[0], /두 번째/, '사라진 항목을 제목으로 지목하지 못한다')
    assert.deepEqual(r.gained, [])
  })

  test('양성 ② — 본문 1줄만 지워도 잡는다', () => {
    const after = BASE.replace('**또.** 본문 A 둘째 줄.\n', '')
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.lost.length, 1, '본문 1줄 소실을 못 잡았다 — fingerprint 에서 본문이 빠졌다')
    assert.equal(r.gained.length, 1, '본문이 바뀐 항목은 사라진 것 + 생긴 것 양쪽에 나와야 한다')
  })

  test('양성 ③ — 제목 1글자만 바꾸면 개명으로 잡는다 (소실 아님)', () => {
    // 제목만 바뀌고 본문이 같으면 그것은 소실이 아니라 개명이다. 소실로 세면
    // 정당한 제목 수정이 전부 red 가 되어 판별식이 마찰 장치로 전락한다.
    const after = BASE.replace('첫 번째', '첫 번쨰')
    const r = compareTodoIntegrity(BASE, after)
    assert.deepEqual(r.lost, [], '제목만 바뀐 것을 소실로 셌다')
    assert.deepEqual(r.gained, [], '제목만 바뀐 것을 신규로 셌다')
    assert.equal(r.renamed.length, 1, '제목 변경을 개명으로 잡지 못했다')
    assert.match(r.renamed[0], /첫 번째.*→.*첫 번쨰/, '개명 보고가 이전 → 이후를 보여주지 않는다')
  })

  test('양성 ⑥ — 본문이 다르면 개명으로 접지 않는다 (관대함 차단)', () => {
    // 개명 인식이 본문까지 안 보면 「제목이 바뀌고 본문도 통째로 갈린」 항목이
    // 조용히 개명으로 접힌다. 그러면 소실 검사가 무력해진다.
    const after = BASE.replace('첫 번째', '첫 번쨰').replace('**또.** 본문 A 둘째 줄.\n', '')
    const r = compareTodoIntegrity(BASE, after)
    assert.deepEqual(r.renamed, [], '본문이 다른데 개명으로 접었다 — 소실이 숨는다')
    assert.equal(r.lost.length, 1, '본문까지 바뀐 항목을 소실로 잡지 못했다')
    assert.equal(r.gained.length, 1)
  })

  test('양성 ④ — 같은 항목 2건 중 1건만 지워도 잡는다 (멀티셋 축)', () => {
    // 집합으로 세면 이 소실이 조용히 통과한다. 멀티셋이라야 잡힌다.
    const dup = BASE + ['## ⬜ 도구 — 두 번째', '', '**무엇.** 본문 B.', ''].join('\n')
    const r = compareTodoIntegrity(dup, BASE)
    assert.equal(r.lost.length, 1, '중복 항목 1건 제거를 못 잡았다 — 집합으로 세고 있다')
  })

  test('양성 ⑤ — 항목 밖 머리 산문이 사라지면 줄 축이 잡는다', () => {
    // `parseTodos` 는 항목 밖 줄을 버리므로 ① 축은 이것을 못 본다.
    // 그래서 줄 멀티셋 축(②)이 따로 있다 — 사람 판정에 맡기지 않는다.
    const after = BASE.replace('이 줄은 어느 항목에도 속하지 않는 머리 산문이다.\n', '')
    const items = compareTodoIntegrity(BASE, after)
    assert.deepEqual(items.lost, [], '전제 확인 — 항목 축은 머리 산문 소실을 보지 못한다')
    const lines = compareAllLines(BASE, after)
    assert.equal(lines.lost.length, 1, '머리 산문 소실을 줄 축도 못 잡았다')
    assert.match(lines.lost[0], /머리 산문/)
  })

  test('구조 줄의 정의를 판별기에서 직접 읽는다', () => {
    // ★판정을 테스트가 손으로 다시 적으면 판별기를 어떻게 바꿔도 초록이다.
    //   `isStructuralLine` 을 `return true` 로 바꿔도 387종 전량이 통과했던 것이
    //   그 증거다(코드 리뷰 C1 실측). 여기서 정의 자체를 대조한다.
    assert.equal(isStructuralLine('# 화면에서 보이는 것'), true, 'H1 이 구조 줄로 인식되지 않는다')
    assert.equal(isStructuralLine(''), true, '빈 줄이 구조 줄로 인식되지 않는다')
    assert.equal(isStructuralLine('**무엇.** 본문 A 첫 줄.'), false, '본문 줄이 구조 줄로 인식됐다 — 편집이 전부 축복된다')
    assert.equal(isStructuralLine('## ⬜ apps/web — 첫 번째'), false, '항목 헤딩이 구조 줄로 인식됐다')
  })

  test('음성 대조군 — H1 추가만 있으면 순수 이동이다', () => {
    // 재배열은 H1 을 새로 만든다. 그 차이는 정상이고, 그 밖의 줄 차이는 편집이다.
    const after = BASE.replace('## ⬜ apps/web — 첫 번째', '# 화면에서 보이는 것\n\n## ⬜ apps/web — 첫 번째')
    const r = judgePureMove(BASE, after)
    assert.deepEqual(r.editedLost, [], 'H1 만 추가했는데 편집된 줄이 사라졌다고 보고했다')
    assert.deepEqual(r.editedGained, [], 'H1 과 빈 줄 말고 다른 줄이 생겼다 — 이동이 아니라 편집이다')
    assert.deepEqual(r.h1Lost, [], 'H1 을 추가만 했는데 소실로 셌다')
    assert.equal(r.clean, true, 'H1 추가만 있는 재배열을 순수 이동으로 판정하지 못한다')
  })

  test('양성 ⑦ — H1 하나를 지우면 순수 이동이 아니다 (절 통삭제)', () => {
    // 절 H1 이 사라지면 그 절의 항목이 통째로 앞 절에 흡수된다. 항목 축은 지문에 절이
    // 없어 이것을 보지 못하고, 줄 축은 H1 을 구조 줄로 접는다. 그 사이로 절 삭제가
    // 「순수 이동」으로 축복됐다(코드 리뷰 C1 — 실파일에서 EXIT 0 재현).
    const after = BASE.replace('# TODOS\n', '')
    const items = compareTodoIntegrity(BASE, after)
    assert.deepEqual(items.lost, [], '전제 확인 — 항목 축은 H1 소실을 보지 못한다')
    const r = judgePureMove(BASE, after)
    assert.equal(r.h1Lost.length, 1, 'H1 소실을 열거하지 못했다')
    assert.equal(r.clean, false, 'H1 을 통째로 지웠는데 순수 이동이라고 판정했다')
  })

  test('H1 소실은 호출자가 명시로 승인할 때만 통과한다', () => {
    // 절을 실제로 없애는 재배열은 정당하다. 다만 **조용히** 통과하면 안 된다 —
    // 승인은 기본값이 아니라 호출자의 명시 선택이어야 한다.
    const after = BASE.replace('# TODOS\n', '')
    const r = judgePureMove(BASE, after, { allowH1Loss: true })
    assert.equal(r.h1Lost.length, 1, '승인해도 무엇을 승인했는지는 보여야 한다')
    assert.equal(r.clean, true, '명시 승인했는데도 통과시키지 않는다')
  })

  test('본문만 바뀌면 **편집**으로 접히고 소실이 아니다', () => {
    // 2026-08-18 실측 — Transition 용어 전수 교체 PR 이 이것을 밟았다. 항목 본문 안의 낱말
    // 하나를 고쳤을 뿐인데 F2b 가 「부채 항목이 사라졌다」로 red 를 냈다. 개명(제목 축)만
    // 접고 편집(본문 축)을 안 접으면 **어떤 PR 도 부채 항목 본문을 고칠 수 없다**.
    const after = BASE.replace('**무엇.** 본문 B.', '**무엇.** 본문 B — 2026-08-18 실측으로 갱신.')
    const r = compareTodoIntegrity(BASE, after)
    assert.deepEqual(r.lost, [], '본문 편집을 소실로 셌다 — 정당한 갱신이 전부 red 가 된다')
    assert.deepEqual(r.gained, [], '본문 편집을 신규 항목으로 셌다')
    assert.equal(r.edited.length, 1, '편집이 목록에 안 남았다 — 조용히 접으면 삭제와 구분되지 않는다')
  })

  test('음성 대조군 — 제목과 본문이 **함께** 바뀌면 접지 않는다 (소실 1 + 생성 1)', () => {
    // 둘 다 바뀌면 같은 항목이라는 증거가 없다. 접으면 「지우고 새로 썼다」가 통과한다.
    const after = BASE.replace('## ⬜ 도구 — 두 번째', '## ⬜ 도구 — 다른 것').replace('**무엇.** 본문 B.', '**무엇.** 본문 Z.')
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.lost.length, 1, '제목·본문이 모두 바뀐 것을 접었다')
    assert.equal(r.gained.length, 1)
    assert.equal(r.edited.length, 0)
  })

  /**
   * ★해소는 소실이 아닌데 **어느 접기에도 안 걸렸다.**
   *
   * 개명 접기는 `(상태, 본문)` 동일을, 편집 접기는 `(상태, 제목)` 동일을 요구한다.
   * 해소는 **상태가 바뀌는 것**이라 둘 다 못 쓴다. 2026-08-23 실측 — 마커만 `⬜`→`✅` 로
   * 바꿔도(제목·본문 그대로) `lost 1`, 관례대로 제목 괄호와 해소 주석까지 붙여도 `lost 1`,
   * **본문을 통째로 지우고 ✅ 로 바꿔도 `lost 1`** 이었다. 정당한 해소와 본문 삭제가 같은
   * 신호를 낸다 — 엄격한 것이 아니라 **판정 불능**이다.
   *
   * 왜 5일간 잠복했나. F2b 가 착지한 #390(2026-08-18) 이후 ✅ 개수는 76 에서 한 번도 안
   * 움직였다(⬜ 는 28 → 69). 그 사이 장부를 건드린 커밋 7건이 전부 등재였다 —
   * **해소가 한 번도 없었다.** 판별식은 자기가 막는 것을 한 번도 만나지 않았다.
   */
  test('해소 전환은 소실이 아니라 **해소**로 접힌다', () => {
    const after = BASE.replace(
      '## ⬜ 도구 — 두 번째\n\n**무엇.** 본문 B.',
      '## ✅ 도구 — 두 번째 (해소 2026-08-23 · #397)\n\n' +
        '> **✅ 2026-08-23 해소 (#397).** 무엇을 했는지.\n\n**무엇.** 본문 B.',
    )
    const r = compareTodoIntegrity(BASE, after)
    assert.deepEqual(r.lost, [], '⬜→✅ 전환을 소실로 셌다 — 이러면 어떤 PR 도 부채를 닫을 수 없다')
    assert.deepEqual(r.gained, [], '해소된 항목을 신규 항목으로 셌다')
    assert.equal(
      r.resolved.length,
      1,
      '해소가 목록에 안 남았다 — 조용히 접으면 본문 삭제와 구분되지 않는다',
    )
    assert.match(
      r.resolved[0],
      /미착수 — .*→.*해소 — /,
      '해소 표시가 「무엇이 무엇으로」를 안 보여 준다 — 사람이 대조할 수 없다',
    )
  })

  test('음성 대조군 — 해소하면서 본문을 줄이면 접지 않는다 (관대함 차단)', () => {
    // 이 대조군이 없으면 위 접기는 「✅ 로 바꾸기만 하면 무엇을 지워도 통과」가 된다.
    // 접는 조건이 **본문 전 줄 보존**임을 여기서 증명한다.
    const after = BASE.replace(
      '## ⬜ apps/web — 첫 번째\n\n**무엇.** 본문 A 첫 줄.\n**또.** 본문 A 둘째 줄.',
      '## ✅ apps/web — 첫 번째 (해소)\n\n**무엇.** 본문 A 첫 줄.',
    )
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.lost.length, 1, '해소하면서 본문 1줄을 지웠는데 접었다')
    assert.equal(r.resolved.length, 0)
  })

  /**
   * ★접기에 **동일성 요구가 없으면** 판별식이 존재하는 이유 그 자체가 뚫린다.
   *
   * 접는 조건이 「`g` 가 ✅ 이고 `g` 의 본문이 `l` 의 본문을 품는다」뿐이면 `l` 과 `g` 사이에
   * 아무 관계가 없어도 접힌다. 빈 멀티셋은 **무엇에나 포함**되므로 본문 없는 항목은 아무
   * ✅ 에나 붙는다 — 「항목을 지우고 무관한 것을 하나 해소했다」가 소실 0 으로 통과한다.
   *
   * 지금 실장부에는 본문 0줄 항목이 없어 도달 불가지만, 그 안전은 **다른 파일의 계약**
   * (`todos-plain-language-contract` 가 요구하는 정형 두 줄)이 우연히 지키고 있을 뿐이고
   * 이 파일 어디에도 그 의존이 안 적혀 있다 — `[[two-lists-never-check-each-other]]` 의 씨앗이다.
   */
  test('음성 대조군 — 빈 본문 항목 삭제가 무관한 신규 ✅ 에 흡수되지 않는다', () => {
    const before = ['# T', '', '## ⬜ 도구 — 빈 항목', ''].join('\n')
    const after = ['# T', '', '## ✅ 인프라 — 무관한 신규 (해소)', '', '**무엇.** 다른 본문.', ''].join('\n')
    const r = compareTodoIntegrity(before, after)
    assert.equal(r.lost.length, 1, '빈 본문 항목이 무관한 ✅ 로 접혔다 — 삭제가 조용히 통과한다')
    assert.equal(r.resolved.length, 0, '관계 없는 두 항목을 해소 짝으로 셌다')
  })

  /**
   * ★짝이 엇갈리면 **사람이 읽는 이름이 틀린다.** red 는 나므로 가짜 그린은 아니지만,
   * 「해소될 항목이 사라졌다」를 보고 diff 를 열면 그 항목이 ✅ 로 멀쩡히 있다 —
   * 오탐으로 읽고 넘길 유인이 정확히 거기서 생긴다. 이 판별기 머리가 스스로
   * 「사라진 것을 **제목으로 지목**해 준다」고 적은 계약이 그 순간 깨진다.
   */
  test('음성 대조군 — 짝짓기가 엇갈리지 않는다 (본문이 남의 부분집합이어도)', () => {
    const before = [
      '# T', '',
      '## ⬜ 도구 — 사라질 항목', '', '**무엇.** 공통 줄.', '',
      '## ⬜ 인프라 — 해소될 항목', '', '**무엇.** 공통 줄.', '**또.** 고유 줄.', '',
    ].join('\n')
    const after = [
      '# T', '',
      '## ✅ 인프라 — 해소될 항목 (해소)', '', '**무엇.** 공통 줄.', '**또.** 고유 줄.', '',
    ].join('\n')
    const r = compareTodoIntegrity(before, after)
    assert.deepEqual(r.lost, ['미착수 — 도구 — 사라질 항목'], '엉뚱한 항목을 소실로 지목했다')
    assert.equal(r.resolved.length, 1, '정상 해소 1건이 안 접혔다')
    assert.ok(
      r.resolved[0].includes('해소될 항목') && !r.resolved[0].includes('사라질 항목'),
      `짝이 엇갈렸다 — ${r.resolved[0]}`,
    )
  })

  test('해소 접기는 제목 어간이 같을 때만 접는다', () => {
    // 제목을 통째로 바꾸면서 ✅ 로 돌리는 것은 「지우고 새로 썼다」와 구분되지 않는다.
    // 기존 두 접기가 「제목과 본문이 함께 바뀌면 접지 않는다」인 것과 같은 규율이다.
    // 어간은 **끝 괄호 그룹 하나**를 뗀 것이다 — 해소 관례가 바꾸는 곳이 정확히 거기다.
    const after = BASE.replace('## ⬜ 도구 — 두 번째', '## ✅ 도구 — 완전히 다른 제목 (해소)')
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.resolved.length, 0, '제목이 통째로 바뀌었는데 접었다')
    assert.equal(r.lost.length, 1)
  })

  test('양성 ⑩ — 해소 전환은 순수 이동이 아니다', () => {
    // 해소는 정당한 편집이지 이동이 아니다. 개명·편집과 같은 자리에 둔다.
    const after = BASE.replace('## ⬜ 도구 — 두 번째', '## ✅ 도구 — 두 번째 (해소)')
    assert.equal(judgePureMove(BASE, after).clean, false, '상태를 바꿨는데 순수 이동이라고 판정했다')
  })

  test('양성 ⑨ — 본문 편집은 순수 이동이 아니다', () => {
    const after = BASE.replace('**무엇.** 본문 B.', '**무엇.** 본문 B 갱신.')
    assert.equal(judgePureMove(BASE, after).clean, false, '본문을 고쳤는데 순수 이동이라고 판정했다')
  })

  test('양성 ⑧ — 항목 밖 산문 1줄이 바뀌면 순수 이동이 아니다 (줄 축 단독)', () => {
    // 항목 밖 줄은 `parseTodos` 가 버리므로 항목 축이 못 본다. 이 축이 `isStructuralLine`
    // 의 유일한 판별자다 — 항목 안 줄로 시험하면 항목 축이 대신 red 를 내서
    // 구조 줄 판정이 망가져도 초록이 된다.
    const after = BASE.replace('이 줄은 어느 항목에도 속하지 않는 머리 산문이다.', '이 줄은 산문이다.')
    const items = compareTodoIntegrity(BASE, after)
    assert.deepEqual(items.lost, [], '전제 확인 — 항목 축은 항목 밖 줄 편집을 보지 못한다')
    const r = judgePureMove(BASE, after)
    assert.equal(r.editedLost.length, 1, '항목 밖 산문 편집을 못 잡았다 — 구조 줄 판정이 너무 넓다')
    assert.equal(r.editedGained.length, 1)
    assert.equal(r.clean, false, '항목 밖 산문이 바뀌었는데 순수 이동이라고 판정했다')
  })
})

/**
 * base 미획득을 통과로 볼지. **기본은 아니다 — 실패다.**
 *
 * `t.skip` 은 `node --test` 에서 exit 0 이다. 즉 「base 를 못 얻었다」가 「검사가 통과했다」와
 * 구분되지 않는다. 같은 디렉터리의 정본(`changed-paths.ts`)이 정확히 반대를 적는다 —
 * 「부재를 0건으로 바꾸면 그 순간 모든 하한 검사가 공허하게 통과한다」. 이 축도 같은 규칙을
 * 따른다. 정말로 base 가 없는 환경(원격 없는 로컬 클론 등)에서만 명시로 끈다.
 */
const ALLOW_NO_BASE = process.env.BTS_ALLOW_NO_BASE === '1'

describe('TODOS.md — merge-base 대비 항목 소실 (F2b)', () => {
  const base = readBaseTodos(REPO_ROOT)

  /** base 본문. 못 얻었으면 **실패**시킨다 (명시 opt-out 이 있을 때만 null 을 돌려준다). */
  function baseContentOrFail(): string | null {
    if (base.content !== null) return base.content
    assert.ok(
      ALLOW_NO_BASE,
      `base 미획득 (${base.reason}) — 이 축이 조용히 꺼졌다.\n` +
        `CI 라면 fetch-depth: 0 이 사라졌거나 원격 ref 가 없다. 둘 다 배선 결함이다.\n` +
        `정말로 base 가 없는 환경이면 BTS_ALLOW_NO_BASE=1 로 명시해서 끈다 — 침묵으로 끄지 않는다.`,
    )
    console.log(`[F2b] base 미획득 — ${base.reason}. BTS_ALLOW_NO_BASE=1 이라 이 축은 돌지 않았다.`)
    return null
  }

  test('base 획득 결과를 반드시 보고한다 (조용한 통과 차단)', () => {
    assert.ok(
      typeof base.reason === 'string' && base.reason.length > 0,
      'base 획득 결과에 이유 문자열이 없다 — 못 얻었는지 얻었는지 구분할 수 없다',
    )
    // 못 얻은 경우에도 **왜** 못 얻었는지가 출력에 남아야 한다.
    if (base.content === null) {
      console.log(`[F2b] base 미획득 — ${base.reason}. 이 실행에서 소실 검사는 돌지 않았다.`)
    } else {
      console.log(`[F2b] base 획득 — ${base.reason}`)
    }
  })

  test('merge-base 에 있던 부채 항목이 이 브랜치에서 사라지지 않았다', () => {
    const content = baseContentOrFail()
    if (content === null) return
    const current = fs.readFileSync(LEDGER, 'utf-8')
    const r = compareTodoIntegrity(content, current)
    if (r.renamed.length > 0) {
      // 개명은 정당한 편집이라 통과시키되, **무엇이 바뀌었는지는 반드시 보인다.**
      // 조용히 접으면 제목 변경이 기록 없이 지나가고, 제목은 장부의 조인 키다.
      for (const s of r.renamed) console.log(`[F2b] 개명 — ${s}`)
    }
    if (r.resolved.length > 0) {
      // 해소도 같은 이유로 **반드시 보인다.** 부채를 닫는 것은 정당하지만, 어느 항목이
      // 닫혔는지가 안 보이면 「본문을 지우고 ✅ 로 덮었다」와 구분되지 않는다.
      for (const s of r.resolved) console.log(`[F2b] 해소 — ${s}`)
    }
    if (r.edited.length > 0) {
      // 본문 편집도 같은 이유로 **반드시 보인다.** 조용히 접으면 「실측을 지우고 다시 썼다」가
      // 기록 없이 지나간다. 접는 조건(제목 동일 + 본문 줄 수 유지)은 판별기 쪽에 있다.
      for (const s of r.edited) console.log(`[F2b] 본문 편집 — ${s}`)
    }
    assert.deepEqual(
      r.lost,
      [],
      `merge-base 에 있던 부채 항목이 사라졌다 — 이 저장소는 항목을 지우지 않고 ✅ 로 바꾼다.\n` +
        `제목만 바뀐 것은 개명으로 접히므로 여기 나오지 않는다. 여기 나온 것은 본문까지 사라진 것이다.\n\n` +
        r.lost.map((s: string) => `  - ${s}`).join('\n'),
    )
  })

  test('자기 자신과의 비교는 공허하므로 그 사실을 보고한다', () => {
    const content = baseContentOrFail()
    if (content === null) return
    const current = fs.readFileSync(LEDGER, 'utf-8')
    const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: REPO_ROOT, encoding: 'utf-8', env: gitFixtureEnv() }).trim()
    if (base.sha === head) {
      console.log('[F2b] merge-base == HEAD — 비교가 공허하다. 이 축은 PR 브랜치에서만 유효하다.')
    }
    // 공허하든 아니든 파서는 실제로 항목을 봐야 한다 (비-공허 짝).
    // 하한은 **0 초과**다. 숫자를 올리면 근거 없는 매직 넘버가 되고, 장부가 줄어든 날
    // 소실과 무관하게 red 가 난다 — 막으려는 것은 「파서가 0건을 뱉는 것」 하나다.
    const r = compareTodoIntegrity(content, current)
    assert.ok(
      r.beforeCount > 0,
      `merge-base 장부에서 항목을 하나도 못 찾았다 — 파서가 깨졌거나 base 가 엉뚱한 파일이다.`,
    )
  })
})

describe('TODOS.md — base ref 해석 (F2b 배선)', () => {
  test('원격이 없는 체크아웃에서도 기본값이 base 를 얻는다', () => {
    // ★후보 목록을 인자로 넘겨 확인하면 **기본값**은 아무도 안 본다. 실측(2026-08-18) —
    //   기본값을 `['origin/main']` 로 되돌려도 판별식 393종이 전량 초록이었다.
    //   그래서 원격이 아예 없는 저장소를 만들어 **기본 인자 그대로** 부른다.
    //   재현 조건은 원격 이름이 `upstream` 인 클론·fork 체크아웃·로컬 pnpm test:workflow 다.
    //   그 상황에서 다른 판별식은 `main` 폴백으로 살고 이 축만 꺼지면 아무도 모른다.
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-base-ref-'))
    try {
      const git = (...args: string[]) =>
        execFileSync('git', args, { cwd: tmp, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'], env: gitFixtureEnv() })
      git('init', '-q', '-b', 'main')
      git('config', 'user.email', 'test@example.com')
      git('config', 'user.name', 'test')
      fs.writeFileSync(path.join(tmp, 'TODOS.md'), BASE)
      git('add', 'TODOS.md')
      git('commit', '-q', '-m', 'base')

      const r = readBaseTodos(tmp)
      assert.notEqual(
        r.content,
        null,
        `origin 이 없는 저장소에서 기본 후보만으로 base 를 못 얻었다 — ${r.reason}\n` +
          '기본값에 `main` 폴백이 없으면 이 축만 조용히 꺼진다.',
      )
      assert.equal(compareTodoIntegrity(r.content, BASE).beforeCount, 3, '엉뚱한 파일을 읽었다')
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  test('후보가 전부 없으면 이유와 함께 미획득을 알린다', () => {
    const r = readBaseTodos(REPO_ROOT, ['refs/heads/bts-없는-ref-1', 'refs/heads/bts-없는-ref-2'])
    assert.equal(r.content, null, '없는 ref 로도 base 를 얻었다고 답한다')
    assert.match(r.reason, /bts-없는-ref-1/, '실패 이유에 시도한 후보가 안 남는다')
    assert.match(r.reason, /bts-없는-ref-2/, '실패 이유에 시도한 후보가 안 남는다')
  })
})

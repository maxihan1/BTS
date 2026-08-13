// 기술부채 장부(TODOS.md) ↔ 마스터 계획 매핑표의 양방향 차집합 판별식
//
// ## 왜 이 파일이 있나
//
// `docs/plans/2026-08-12-debt24-master.md` 는 **「24건이 어느 PR 에 속하는가」의 정본**이다.
// 그 문서 스스로가 존재 이유를 이렇게 적었다.
//
// > PR 묶음표가 **건수만 적고 어느 항목인지는 안 적으면** 두 목록이 서로를 검사하지 못한다.
// > 이 저장소의 지배적 결함 양식(`two-lists-never-check-each-other`)이 바로 그것이고,
// > 처방은 **차집합 판별식**이다.
//
// 그런데 그 처방이 **문서 안의 bash 스니펫으로만** 존재했다. 아무도 돌리지 않았고, 그래서
// 다음이 조용히 성립했다 — **작성 시점부터 키가 어긋나 있었다.**
//
// | 항목 | 마스터 계획이 적은 줄 | #365 머지 직후 실제 | 현재 실제 |
// |---|---|---|---|
// | OpenAPI required | `2023` | **2033** | **2098** |
// | setupServer 59개 | `2285` | **2295** | **2360** |
// | placeholder/aria-label | `3243` | **3429** | **3531** |
//
// 22건 중 **18건**이 어긋났다. 「자기 결함을 재생산한 봉인」 양식이다.
//
// ## ★왜 줄번호를 키로 쓰지 않는가
//
// 줄번호는 **휘발성**이다. 장부를 한 줄만 고쳐도 그 아래가 전부 밀린다 — PR #366 하나가
// 110줄을 추가했다. 앞으로도 부채를 닫을 때마다 밀린다. 키가 매 PR 마다 깨지는 구조라면
// 그 키는 키가 아니다.
//
// 그래서 **제목**을 키로 쓴다. 제목도 바뀔 수 있지만, 바뀌면 이 판별식이 **즉시 red** 다.
// 핵심은 완벽한 키가 아니라 **조용히 깨지지 않는 것**이다.
//
// ## 정규화 규칙
//
// `## ⬜ <제목> (<상태>)` 에서 **문자열 끝의 괄호 그룹 하나만** 떼어 낸다.
// 상태 표기(`미착수` · `착수` · `선재` · `신규`)는 진행에 따라 바뀌지만 제목 본문은 안 바뀐다.
//
// ★「괄호 앞까지 자르기」로 하면 안 된다 — `apps/web(테스트 인프라) — …` 는 괄호가 **맨 앞**에
// 있어 제목이 통째로 날아가고, `로딩 프레임 계약 — 임포트 페이지는 고정됨(2026-08-10), …` 은
// 괄호가 **중간**에 있다. 끝에 붙은 것만 떼는 이유가 그것이다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const LEDGER = path.join(REPO_ROOT, 'TODOS.md')
const MASTER = path.join(REPO_ROOT, 'docs/plans/2026-08-12-debt24-master.md')

/** 장부에서 미해소를 뜻하는 마커. */
const OPEN_MARKER = '⬜'

/** 장부에서 해소를 뜻하는 마커. */
const DONE_MARKER = '✅'

/** 미해소 행의 PR 열에 허용되는 값. `#NNN` 은 여기 없다 — 아래 판별식 주석 참조. */
const OPEN_PR_ALLOWED = ['미배정', '보류']

/**
 * 제목 끝의 상태 괄호 하나를 떼어 키로 만든다.
 *
 * @param heading `## ⬜ ` 를 제거한 제목 줄
 * @returns 비교에 쓸 키
 */
function normalizeKey(heading: string): string {
  return heading.replace(/\s*\([^()]*\)\s*$/, '').trim()
}

/** 볼드 마크업(`**x**`)을 떼어 셀 값을 비교 가능한 형태로 만든다. */
function stripBold(cell: string): string {
  return cell.replace(/\*\*/g, '').trim()
}

/**
 * 전수 매핑 표의 행을 `{ status, item, pr }` 로 뽑는다.
 *
 * 표 형식. `| # | 상태 | 항목 | PR | 영역 |`
 *
 * ★2번째 열이 상태 마커(`⬜`/`✅`)인 행만 고른다. 이 문서의 다른 표 3종은 2번째 열이
 * 각각 항목명(확정된 결정) · 묶음명(PR 별 집계) · 상태문자열(순서 제약)이라 자동 배제된다 —
 * 「PR 열」이라는 이름만 같고 의미가 다른 표에 이 규칙을 잘못 적용하지 않기 위한 것이다.
 */
function masterMappingRows(): { status: string; item: string; pr: string }[] {
  const lines = fs.readFileSync(MASTER, 'utf-8').split('\n')
  const rows: { status: string; item: string; pr: string }[] = []
  for (const line of lines) {
    if (!line.startsWith('|')) continue
    const cells = line.split('|').map((c) => c.trim())
    // ['', '#', '상태', '항목', 'PR', '영역', '']
    if (cells.length < 6) continue
    const status = stripBold(cells[2] ?? '')
    if (status !== OPEN_MARKER && status !== DONE_MARKER) continue
    rows.push({ status, item: cells[3] ?? '', pr: stripBold(cells[4] ?? '') })
  }
  return rows
}

/** TODOS.md 에서 주어진 마커의 항목 제목 키 목록. **중복을 접지 않는다** — 아래 중복 판정이 쓴다. */
function ledgerKeys(marker: string): string[] {
  const prefix = `## ${marker} `
  return fs
    .readFileSync(LEDGER, 'utf-8')
    .split('\n')
    .filter((l) => l.startsWith(prefix))
    .map((l) => normalizeKey(l.slice(prefix.length)))
}

/** TODOS.md 의 미해소 항목 제목 키 목록. */
function ledgerOpenKeys(): string[] {
  return ledgerKeys(OPEN_MARKER)
}

/** TODOS.md 의 해소 항목 제목 키 목록. */
function ledgerDoneKeys(): string[] {
  return ledgerKeys(DONE_MARKER)
}

/**
 * 마스터 계획 「전수 매핑」 표에서 주어진 상태의 항목 키를 뽑는다.
 *
 * ★파서를 하나로 유지한다. 종전에는 이 함수와 `masterMappingRows()` 가 같은 표를 각자 읽었고
 * `cells[2]` 정규화가 달랐다(한쪽만 `stripBold`). 상태 마커가 `**⬜**` 로 볼드되는 순간 두 파서가
 * **서로 다른 행 집합**을 보게 된다 — 한 표에 리더가 둘인 것 자체가 이 저장소의 지배 결함 양식이다.
 */
function masterKeys(status: string): string[] {
  return masterMappingRows()
    .filter((r) => r.status === status)
    .map((r) => normalizeKey(r.item))
    .filter((k) => k.length > 0)
}

/** 마스터 계획의 미해소 행 항목 키. */
function masterOpenKeys(): string[] {
  return masterKeys(OPEN_MARKER)
}

/** 마스터 계획의 해소 행 항목 키. */
function masterDoneKeys(): string[] {
  return masterKeys(DONE_MARKER)
}

describe('기술부채 장부 ↔ 마스터 계획 매핑', () => {
  test('두 파일이 실재한다 (비-공허 짝)', () => {
    // 경로가 어긋나면 아래 차집합이 전부 「빈 집합 == 빈 집합」으로 공허 통과한다.
    assert.ok(fs.existsSync(LEDGER), `${LEDGER} 가 없다.`)
    assert.ok(fs.existsSync(MASTER), `${MASTER} 가 없다.`)
  })

  test('★파서가 실제로 항목을 찾는다 (비-공허 짝)', () => {
    // 정규식이나 표 형식이 깨지면 0건을 훑고도 차집합 0 이라 통과한다 — 이 저장소가
    // 여러 번 겪은 「훑기 0건이면 단언이 공허」 양식.
    assert.ok(
      ledgerOpenKeys().length >= 10,
      `장부에서 미해소 항목을 ${ledgerOpenKeys().length}건밖에 못 찾았다 — 파서가 깨졌다.`,
    )
    assert.ok(
      masterOpenKeys().length >= 10,
      `마스터 계획에서 미해소 행을 ${masterOpenKeys().length}건밖에 못 찾았다 — 표 형식이 바뀌었다.`,
    )
  })

  test('★★장부에만 있고 마스터 계획에 없는 항목이 0 이다', () => {
    // 이 방향이 새면 **어느 PR 도 담당하지 않는 부채**가 생긴다 — 조용히 영원히 안 닫힌다.
    const master = new Set(masterOpenKeys())
    const orphans = ledgerOpenKeys().filter((k) => !master.has(k))
    assert.deepEqual(
      orphans,
      [],
      `장부의 아래 항목을 마스터 계획이 담당하지 않는다 — 어느 PR 도 이것을 닫지 않는다.\n` +
        orphans.map((o) => `  - ${o}`).join('\n'),
    )
  })

  test('★★마스터 계획에만 있고 장부에 없는 항목이 0 이다', () => {
    // 이 방향이 새면 **이미 닫힌 것을 다시 열거나** 제목이 바뀐 것을 못 따라간 상태다.
    const ledger = new Set(ledgerOpenKeys())
    const stale = masterOpenKeys().filter((k) => !ledger.has(k))
    assert.deepEqual(
      stale,
      [],
      `마스터 계획의 아래 항목이 장부에 미해소로 없다 — 이미 닫혔거나 제목이 바뀌었다.\n` +
        stale.map((s) => `  - ${s}`).join('\n'),
    )
  })

  test('마스터 계획의 항목 키에 중복이 없다', () => {
    // 같은 항목을 두 PR 이 담당하면 반쪽 봉합이 난다.
    const keys = masterOpenKeys()
    const dups = keys.filter((k, i) => keys.indexOf(k) !== i)
    assert.deepEqual(dups, [], `마스터 계획에 중복된 항목이 있다.\n${dups.join('\n')}`)
  })

  test('★장부의 미해소 항목 제목에 중복이 없다', () => {
    // ★위 두 차집합만으로는 **건수 일치가 강제되지 않는다.** 차집합은 집합 연산인데 장부 쪽
    // 파서는 리스트를 돌려주므로, 장부에 같은 제목이 두 번 있으면 집합으로는 같고 건수만 갈린다.
    // 실측 — `## ⬜` 헤딩 하나를 복제해 장부 21 · 마스터 21 로 만들었더니 **전 판정 GREEN** 이었다.
    // `master.md` §전수 매핑이 「건수는 여기 적지 않는다 — 집합 일치가 건수 일치를 포함한다」고
    // 선언한 근거가 이 한 줄이다. 이게 없으면 그 선언이 거짓이 된다.
    const keys = ledgerOpenKeys()
    const dups = keys.filter((k, i) => keys.indexOf(k) !== i)
    assert.deepEqual(dups, [], `장부에 같은 제목의 미해소 항목이 둘 이상 있다.\n${dups.join('\n')}`)
  })

  test('★★마스터 계획이 ✅ 라 적은 항목은 장부에서도 ✅ 다', () => {
    // ⬜ 는 양방향으로 재지만 ✅ 는 **한 방향만** 잰다. 장부의 `## ✅` 는 저장소 전체 이력이라
    // 이 마스터 계획(부채 26건)의 범위를 크게 넘는다 — 역방향을 걸면 항상 red 다.
    // 이 방향이 새면 **닫히지 않은 것을 닫혔다고 적은 상태**가 되어, ⬜ 차집합이 그 항목을
    // 아예 안 보게 된다(양쪽 다 ⬜ 목록에서 빠지므로 조용히 통과한다).
    const ledger = new Set(ledgerDoneKeys())
    const lying = masterDoneKeys().filter((k) => !ledger.has(k))
    assert.deepEqual(
      lying,
      [],
      `마스터 계획이 ✅ 라 적었으나 장부에 해소로 없다 — 안 닫힌 것을 닫혔다고 적었거나 제목이 갈렸다.\n` +
        lying.map((s) => `  - ${s}`).join('\n'),
    )
  })
})

// ## 왜 PR 열에도 판별식이 필요한가 (2026-08-14)
//
// 위 4 판정은 **항목 제목 열만** 읽는다(`cells[3]`). **PR 열(`cells[4]`)은 아무도 읽지 않았다.**
// 그래서 다음이 조용히 성립했다 — **PR 을 닫아도 CI 는 초록이다.**
//
// 실측으로 이미 새고 있었다.
//
// | 자리 | 장부가 적은 것 | 실제 |
// |---|---|---|
// | 매핑 `4` (CI 벽시계) | `#366` | **#366 은 2026-08-12 머지됐고 이 항목은 안 닫혔다** |
// | 매핑 `31` (backend-ci 모듈 미선별) | `미배정` | **열린 PR #379 가 그 일을 하고 있다** |
//
// 같은 파일 안에서 **두 방향으로 동시에** 틀려 있었는데 어느 판정도 red 가 아니었다.
// `two-lists-never-check-each-other` 가 PR 열에 그대로 열려 있었던 것이다.
//
// ## ★왜 「⬜ 행에는 PR 번호를 못 쓴다」인가
//
// PR 번호는 **머지된 뒤에야 불변**이다. 열린 PR 은 닫히거나 브랜치가 사라질 수 있고,
// 그 순간 장부의 주장이 거짓이 되는데 **거짓이 된 시점을 아무도 모른다**(네트워크 없이는
// CI 가 PR 상태를 볼 수 없다). 그래서 형식 규칙으로 바꾼다 —
//
// - `⬜`(미해소) → `미배정` 또는 `보류`. **PR 번호 금지.**
// - `✅`(해소) → `#NNN`. 머지된 PR 번호는 불변 이력이라 썩지 않는다.
//
// **대가.** 「지금 누가 이 항목을 하고 있나」가 장부에서 사라진다. 그 정보는 전이(轉移)
// 상태이므로 PR 자신과 `.claude/STATE.md`(머신 로컬 · `.gitignore` 대상)가 갖는다 — 장부는
// **불변 사실만** 담는다. 이 대가를 치르는 이유는 2026-08-14 에 예약 draft PR 7건(#368~#374)을
// 폐기하면서 「예약 PR 로 담당을 표시하는 방식」 자체를 그만뒀기 때문이다.
//
// ## ★이 판별식이 재지 **않는** 것 (설계상 한계 — 알고 남긴다)
//
// 1. **PR 번호의 실재를 안 잰다.** `/^#\d+$/` 는 `#999999` 도 통과시킨다. 「이 항목을 실제로 닫은
//    PR 인가」는 네트워크 없이 못 재고 CI 에는 네트워크가 없다. 원 결함(매핑 `4` 가 머지된 `#366` 을
//    들고 썩음)은 위 ⬜ 규칙이 **구조적으로** 막으므로 잔여 위험은 낮다.
// 2. **머지 방향을 강제하지 않는다.** PR 이 부채를 닫고 머지됐는데 아무도 장부를 `✅ #NNN` 으로
//    안 옮기면, 장부·마스터가 **둘 다 ⬜ 로 일치**해 조용히 통과한다. 이 방향의 강제 지점은
//    `/bts-merge` 스킬 안이다 — 장부 항목 `20` 이 「강제 지점은 훅도 CI 도 아니다」로 이미 같은
//    결론에 도달해 있다. **이 판별식은 결함을 없앤 것이 아니라 「거짓 주장」에서 「침묵」으로 좁혔다.**
// 3. **선행 `|` 없는 GFM 표 행을 못 본다.** `line.startsWith('|')` 때문이다. 다만 ⬜ 행이 사라지면
//    장부↔마스터 차집합이, ✅ 행이 사라지면 위 「마스터 ✅ ⊆ 장부 ✅」가 각각 잡는다.
describe('기술부채 마스터 계획 — PR 열 형식', () => {
  test('★파서가 실제로 PR 열을 훑는다 (비-공허 짝)', () => {
    // 표 형식이 바뀌거나 `cells[2]` 필터가 어긋나면 0행을 훑고도 아래 판정이 전부
    // 「빈 배열 == 빈 배열」로 공허 통과한다.
    //
    // ★하한을 **방향별로** 둔다. 전체 하한만 두면 파서가 퇴화해 ✅ 를 1행만 잡아도
    // 「양쪽 다 잡힌다」가 통과하고 **✅ 판정이 사실상 공허해진다.**
    const rows = masterMappingRows()
    const open = rows.filter((r) => r.status === OPEN_MARKER).length
    const done = rows.filter((r) => r.status === DONE_MARKER).length
    assert.ok(
      rows.length >= 20,
      `전수 매핑 표에서 ${rows.length}행밖에 못 찾았다 — 표 형식이 바뀌었거나 필터가 어긋났다.`,
    )
    assert.ok(open >= 10, `⬜ 행을 ${open}행밖에 못 찾았다 — 파서가 퇴화했다.`)
    assert.ok(done >= 5, `✅ 행을 ${done}행밖에 못 찾았다 — 파서가 퇴화해 ✅ 판정이 공허해진다.`)
  })

  test('★★미해소(⬜) 행의 PR 열에 PR 번호가 없다', () => {
    // 새면 「닫힌 PR 이 담당자로 남은 장부」가 된다. 닫는 쪽은 CI 가 못 보므로 형식으로 막는다.
    const violations = masterMappingRows()
      .filter((r) => r.status === OPEN_MARKER)
      .filter((r) => !OPEN_PR_ALLOWED.includes(r.pr))
    assert.deepEqual(
      violations.map((v) => `${v.item} → ${v.pr}`),
      [],
      `미해소 항목의 PR 열은 ${OPEN_PR_ALLOWED.join(' 또는 ')} 여야 한다.\n` +
        `PR 번호는 머지 전까지 썩는다 — 담당은 PR 자신과 .claude/STATE.md 가 갖는다.\n` +
        violations.map((v) => `  - ${v.item} → ${v.pr}`).join('\n'),
    )
  })

  test('★★해소(✅) 행의 PR 열이 PR 번호다', () => {
    // 반대 방향. ✅ 인데 `미배정` 이면 **무엇이 닫았는지 추적 불가**가 되어 회귀 시 되돌아갈
    // 지점을 잃는다.
    const violations = masterMappingRows()
      .filter((r) => r.status === DONE_MARKER)
      .filter((r) => !/^#\d+$/.test(r.pr))
    assert.deepEqual(
      violations.map((v) => `${v.item} → ${v.pr}`),
      [],
      `해소된 항목의 PR 열은 \`#NNN\` 이어야 한다 — 무엇이 닫았는지가 유일한 되돌림 지점이다.\n` +
        violations.map((v) => `  - ${v.item} → ${v.pr}`).join('\n'),
    )
  })
})

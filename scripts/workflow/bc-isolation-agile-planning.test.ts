// agile-planning 프로덕션 소스에 issue-tracking 직접 import 가 0건인지 훑는 BC 경계 판별식
//
// ## 왜 있나 — 그리고 이것이 왜 **보조선**인가
//
// 부채 177 Task 14 의 초판은 「`com.bts.issue` 직접 import 0건」**만** 쟀다. 그런데 이 PR 이
// 실제로 넓힌 결합은 직접 import 가 아니라 **shared-kernel 포트 경유**다 —
// `BoardIssueView.customFields`(Task 5) 를 어댑터(Task 6)가 채워 agile-planning 으로 흘린다.
// 즉 초판 판별식은 **초록인 채로 그 위험을 통과시킨다.** 리뷰 BLOCKER B1 이 그 자리다.
//
// 그래서 주 방어선은 여기가 아니라 저쪽이다 —
// `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardIssueViewFieldSetTest.kt`
// 가 포트 VO 의 **필드 이름 집합**을 정확한 동등으로 고정한다. 포트를 넓히는 것 자체는 허용된
// 경로이므로 처방은 금지가 아니라 **가시화**다.
//
// ## 이 파일이 그럼에도 더하는 것 — 그리고 더하지 못하는 것
//
// 정직하게 적는다. 직접 import 축은 **이미 세 겹**이다.
//   ① agile-planning 의 gradle 의존에 issue-tracking 이 없다 → `import com.bts.issue.…` 는
//      컴파일이 안 된다(2026-09-05 실측 — `Unresolved reference 'issue'`).
//   ② `AgilePlanningBcArchTest` 룰 1 이 바이트코드 의존을 막는다.
//   ③ 이 파일이 소스 텍스트를 훑는다.
//
// ③ 이 ①② 위에 더하는 것은 좁다. **컴파일 전에** · **모듈 선택(`select-backend-modules`)이
// agile-planning 을 건너뛰어도** · **쓰이지 않아 바이트코드에 안 남는 import 줄까지** 잡는다는 것뿐이다.
// ①이 사라지는 날(누가 `implementation(project(":modules:issue-tracking"))` 를 넣는 날)이
// 이 판별식의 값이 오르는 날이다. 그날이 오기 전까지 이것은 **싸고 빠른 사전 경보**지
// 유일한 방어선이 아니다.
//
// ## ★못 보는 축 — 이것으로 「BC 가 격리돼 있다」를 증명하지 마라
//
//   - **포트 경유 결합 그 자체.** 이 PR 의 실제 결합이 그것이고, 여기서는 보이지 않는다.
//     `BoardIssueViewFieldSetTest` 가 진다.
//   - **리플렉션·문자열 경유 의존.** `Class.forName("com.bts.issue…")` · jOOQ 생성 클래스
//     이름 문자열 · 타 BC 테이블명을 문자열로 읽는 SQL 은 줄머리가 `import` 가 아니라 안 잡힌다.
//   - **gradle 의존 선언.** `build.gradle.kts` 에 issue-tracking 이 추가되는 순간은 안 본다.
//     그 뒤로 **쓰이는** 의존은 ArchUnit 이, **안 쓰여 바이트코드에 안 남는** import 줄은
//     이 파일이 잡는다 — 둘 다 필요한 이유다.
//   - **`src/test`.** 훑지 않는다. 테스트에는 픽스처가 살고, 픽스처의 import 는 위반이 아니다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const REPO_ROOT = path.resolve(import.meta.dirname, '../..')

/** 훑을 대상 — agile-planning 의 프로덕션 소스 루트. 테스트는 대상이 아니다(픽스처가 산다). */
const SCAN_ROOT = 'backend/modules/agile-planning/src/main'

/** 금지 패키지 접두 — issue-tracking BC 의 내부. */
const FORBIDDEN_PACKAGE = 'com.bts.issue'

/**
 * 카나리 문자열.
 *
 * 스캐너가 파일을 **실제로 읽는다**는 증거다. `readFileSync` 가 빈 문자열을 주거나 glob 이
 * 0개를 잡아도 「위반 0건」은 성립해 조용히 초록이 된다. agile-planning 이 issue-tracking 과
 * 말을 섞는 **허용된** 통로가 이 패키지이므로, 이것이 잡힌다는 것은 곧 「본문을 봤다」다.
 */
const CANARY_IMPORT = 'com.bts.shared.board'

/**
 * 픽스처 안에 심는 줄 주석의 여는 자리. **런타임에 잇는다.**
 *
 * 소스에 그대로 적으면 `git-fixture-isolation.test.ts` 의 「scripts 전량에서 살아남은 줄
 * 주석이 하나도 없다」가 이 픽스처를 위반으로 읽는다. `board-summary-contract.test.ts` 가
 * 자기 미끼에 쓴 처방을 그대로 따른다.
 */
const LINE_COMMENT_OPEN = '/'.repeat(2)
/** 픽스처용 블록 주석 여는 자리. 같은 이유로 런타임에 잇는다. */
const BLOCK_COMMENT_OPEN = `/${'*'}`
/** 픽스처용 블록 주석 닫는 자리. */
const BLOCK_COMMENT_CLOSE = `${'*'}/`

/**
 * 금지 import 줄을 잡는 패턴.
 *
 * 줄머리가 `import` 여야 한다 — KDoc 본문의 ` * com.bts.issue.…` 같은 **설명 문장**은
 * 위반이 아니다(실제로 `BoardResponses.kt` 가 봉투 형태를 설명하며 그 이름을 적고 있다).
 * 접두 뒤에는 `.` 이나 공백·줄끝만 온다 — `com.bts.issuetracking` 처럼 이름이 겹치는
 * 다른 패키지를 물면 오탐이 되고, 오탐은 사람이 판별식을 끄게 만든다.
 */
const FORBIDDEN_IMPORT_PATTERN = new RegExp(
  `^\\s*import\\s+${FORBIDDEN_PACKAGE.replace(/\./g, '\\.')}(?=[.\\s;]|$)`,
)

/**
 * 디렉터리 아래 `.kt` 파일의 절대 경로를 재귀로 모은다.
 *
 * 없는 경로면 빈 배열이다 — 여기서 던지면 「경로가 틀렸다」가 스택 트레이스에 묻히고,
 * 부르는 쪽의 비-공허 단언이 그것을 red 로 잡는 편이 낫다.
 *
 * @param absoluteDir 훑을 디렉터리의 절대 경로.
 * @returns `.kt` 파일 절대 경로 목록(정렬).
 */
function collectKotlinFiles(absoluteDir: string): string[] {
  if (!fs.existsSync(absoluteDir)) {
    return []
  }
  const found: string[] = []
  for (const entry of fs.readdirSync(absoluteDir, { withFileTypes: true })) {
    const child = path.join(absoluteDir, entry.name)
    if (entry.isDirectory()) {
      found.push(...collectKotlinFiles(child))
      continue
    }
    if (entry.name.endsWith('.kt')) {
      found.push(child)
    }
  }
  return found.sort()
}

/**
 * 주석을 **줄 수를 유지한 채** 걷어낸다.
 *
 * 블록 주석은 개행만 남기고 공백으로 덮고, 줄 주석은 **줄 전체가 주석일 때만** 비운다.
 * 줄 수를 유지하는 이유는 위반 메시지의 줄 번호가 원본과 어긋나면 안 되기 때문이다.
 * 줄 전체일 때만 지우는 이유는 문자열 안의 `//`(URL·경로)를 주석으로 오인하지 않기 위해서다.
 *
 * @param source 원본 텍스트.
 * @returns 주석이 걷힌, 같은 줄 수의 텍스트.
 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .split('\n')
    .map((line) => (line.trim().startsWith(LINE_COMMENT_OPEN) ? '' : line))
    .join('\n')
}

/**
 * 한 파일 본문에서 금지 import 가 있는 줄 번호를 뽑는다.
 *
 * @param source `.kt` 파일 전문.
 * @returns 1-기반 줄 번호 목록.
 */
function forbiddenImportLines(source: string): number[] {
  return stripComments(source)
    .split('\n')
    .map((line, index) => (FORBIDDEN_IMPORT_PATTERN.test(line) ? index + 1 : 0))
    .filter((lineNumber) => lineNumber > 0)
}

/**
 * 스캔 대상 파일을 전부 읽어 `[저장소 상대 경로, 본문]` 으로 돌려준다.
 *
 * describe 바깥이 아니라 테스트 안에서 부른다 — 로드 시점에 읽으면 경로가 틀렸을 때
 * 존재 단언조차 못 돌고 수집 단계에서 통째로 죽는다.
 *
 * @returns `[상대 경로, 본문]` 쌍 목록.
 */
function scannedSources(): ReadonlyArray<readonly [string, string]> {
  return collectKotlinFiles(path.join(REPO_ROOT, SCAN_ROOT)).map(
    (absolute) =>
      [path.relative(REPO_ROOT, absolute), fs.readFileSync(absolute, 'utf-8')] as const,
  )
}

describe('agile-planning BC 경계 — 비-공허', () => {
  test('스캔 루트가 실재한다', () => {
    // 경로가 틀리면 아래 위반 목록이 빈 집합에서 나와 조용히 통과한다.
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, SCAN_ROOT)),
      `스캔 루트가 없다 — ${SCAN_ROOT}`,
    )
  })

  test('★스캔한 코틀린 파일이 1개 이상이다', () => {
    const scannedCount = scannedSources().length
    assert.ok(scannedCount > 0, `스캔한 .kt 파일이 0개다 — 「위반 0건」이 가짜 초록이 된다`)
  })

  test(`★카나리 — 스캐너가 본문을 실제로 읽는다(${CANARY_IMPORT} 를 담은 파일이 있다)`, () => {
    // 개수 단언만으로는 「파일은 세었는데 본문은 못 읽었다」를 못 가른다.
    const readers = scannedSources().filter(([, source]) => source.includes(CANARY_IMPORT))
    assert.ok(
      readers.length > 0,
      `${CANARY_IMPORT} 를 담은 파일이 하나도 안 잡혔다 — 본문을 안 읽고 있다`,
    )
  })
})

describe(`agile-planning BC 경계 — ${FORBIDDEN_PACKAGE} 직접 import 0건`, () => {
  test('★src/main 어디에도 issue-tracking 직접 import 가 없다', () => {
    const violations = scannedSources().flatMap(([relative, source]) =>
      forbiddenImportLines(source).map((lineNumber) => `${relative}:${lineNumber}`),
    )
    assert.deepEqual(
      violations,
      [],
      `agile-planning 은 ${FORBIDDEN_PACKAGE} 를 직접 import 할 수 없다 — ` +
        'shared-kernel 포트나 이벤트(pgmq)를 통해서만 통신한다',
    )
  })
})

describe('스캐너 계약 — 픽스처로 직접 잰다', () => {
  const REAL_IMPORT_FIXTURE = [
    'package com.bts.agileplanning.application',
    '',
    'import com.bts.agileplanning.domain.Board',
    'import com.bts.issue.repository.IssueRepository',
    '',
    'class BoardService',
  ].join('\n')

  const BLOCK_COMMENT_FIXTURE = [
    BLOCK_COMMENT_OPEN + '*',
    ' * 봉투 형태는 issue-tracking 의',
    ' * import com.bts.issue.adapter.inbound.rest.DataResponse 와 같다.',
    ` ${BLOCK_COMMENT_CLOSE}`,
    'class BoardResponses',
  ].join('\n')

  const LINE_COMMENT_FIXTURE = [
    `${LINE_COMMENT_OPEN} import com.bts.issue.repository.IssueRepository`,
    `    ${LINE_COMMENT_OPEN} import com.bts.issue.domain.Issue`,
    'class BoardService',
  ].join('\n')

  const NEIGHBOUR_PACKAGE_FIXTURE = [
    'import com.bts.issuetracking.repository.IssueRepository',
    'import com.bts.issues.Foo',
    'class BoardService',
  ].join('\n')

  test('실제 import 줄을 줄 번호와 함께 잡는다', () => {
    assert.deepEqual(forbiddenImportLines(REAL_IMPORT_FIXTURE), [4])
  })

  test('★KDoc 블록 주석 안의 같은 문자열은 안 잡는다', () => {
    // 이 형태는 저장소에 실재한다 — BoardResponses.kt 가 봉투 형태를 설명하며 그 이름을 적는다.
    assert.deepEqual(forbiddenImportLines(BLOCK_COMMENT_FIXTURE), [])
  })

  test('줄 주석 안의 같은 문자열은 안 잡는다', () => {
    assert.deepEqual(forbiddenImportLines(LINE_COMMENT_FIXTURE), [])
  })

  test('접두가 겹치는 다른 패키지는 안 잡는다', () => {
    assert.deepEqual(forbiddenImportLines(NEIGHBOUR_PACKAGE_FIXTURE), [])
  })

  test('★없는 디렉터리를 훑으면 0개다 — 비-공허 단언이 필요한 이유', () => {
    // 스캔 루트가 사라지면 위반 목록은 빈 배열이 되어 「0건」으로 통과한다.
    // 그 상태를 red 로 바꾸는 것은 위 「스캔한 코틀린 파일이 1개 이상이다」뿐이다.
    assert.deepEqual(collectKotlinFiles(path.join(REPO_ROOT, SCAN_ROOT, 'no-such-dir')), [])
  })
})

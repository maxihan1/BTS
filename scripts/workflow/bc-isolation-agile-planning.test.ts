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
// ## 이 파일이 그럼에도 더하는 것 — 실측에 맞춰 정정(2026-09-06)
//
// 정직하게 적는다. 직접 import 축은 **이미 두 겹**이다.
//   ① agile-planning 의 gradle 의존에 issue-tracking 이 없다 → `import com.bts.issue.…` 는
//      컴파일이 안 된다(2026-09-05 실측 — `Unresolved reference 'issue'`).
//   ② `AgilePlanningBcArchTest` 룰 1 이 바이트코드 의존을 막는다.
//
// 초판은 이 자리에 고유 가치를 셋 적었는데 **둘이 실측으로 무너졌다.** 지운다.
//   - ❌ 「모듈 선택이 agile-planning 을 건너뛰어도 돈다」 —
//     `select-backend-modules.ts` 에 agile-planning 소스 한 파일만 줘도 `["agile-planning"]` 을
//     돌려준다(2026-09-06 실측). import 는 그 모듈을 건드려야 들어가므로 그 사각은 없다.
//   - ❌ 「안 쓰여 바이트코드에 안 남는 import 줄까지 잡는다」 — 코틀린에서 **미해결** 패키지의
//     미사용 import 는 경고가 아니라 컴파일 에러다. gradle 의존이 추가된 **뒤에야**, 그것도
//     ArchUnit 대비로만 참인 좁은 축이다.
//
// 남는 값은 둘이다.
//   ① **스펙 C-5 ④ 의 이행.** X9(한 PR 이 4개 BC 를 건드린다)를 확정하며 붙인 완화책 네 항목 중
//     기계 방어선으로 지목된 것이 「agile-planning 의 `com.bts.issue` 직접 import 0건」이다
//     (계획 189행). 그 요구를 문자 그대로 이행하는 파일이 여기다.
//   ② **보험.** 위 ①은 `implementation(project(":modules:issue-tracking"))` 한 줄로 사라지고,
//     ②은 바이트코드만 본다. 둘이 사라지거나 비켜 가는 날 남는 것이 이 텍스트 훑기다.
//
// ## ★★이 판별식은 PR CI 에서 돌지 않는다 — 저장소 선재 상태(이 파일 범위 밖이라 안 고친다)
//
// `pnpm test:workflow` 를 부르는 유일한 잡은 `.github/workflows/workflow-scripts-ci.yml:114` 인데
// 그 파일의 `on:` 은 `workflow_dispatch:` 뿐이다(2026-08-21 self-hosted 전환에서 의도적으로 줄였다).
// `backend-ci.yml` 도 `on:` 이 `workflow_dispatch:` 뿐이라 **자매 방어선인 ArchUnit 룰 1 도
// PR CI 에서는 안 돈다.** `.husky/pre-commit` 은 lint-staged 와 `build-doc-index --check` 만 돈다.
//
// 유일한 기계 강제 지점은 `.husky/pre-push` 다. 거기서
//   - 이 파일은 `node --experimental-strip-types --test 'scripts/**/*.test.ts' …` 로
//     **조건 없이 전량** 돌고,
//   - ArchUnit 은 `push-backend-tests.ts` 가 agile-planning 을 고를 때만 돈다.
// `git push --no-verify` 면 둘 다 함께 꺼진다.
// **「CI 가 지켜 주는 방어선」으로 믿지 마라 — 지켜 주는 것은 푸시 훅 하나뿐이다.**
//
// ## ★못 보는 축 — 이것으로 「BC 가 격리돼 있다」를 증명하지 마라
//
//   - **포트 경유 결합 그 자체.** 이 PR 의 실제 결합이 그것이고, 여기서는 보이지 않는다.
//     `BoardIssueViewFieldSetTest` 가 진다.
//   - **리플렉션·문자열 경유 의존.** `Class.forName("com.bts.issue…")` · jOOQ 생성 클래스
//     이름 문자열 · 타 BC 테이블명을 문자열로 읽는 SQL 은 줄머리가 `import` 가 아니라 안 잡힌다.
//   - **gradle 의존 선언.** `build.gradle.kts` 에 issue-tracking 이 추가되는 순간은 안 본다.
//     그 한 줄이 들어간 **뒤에야** 이 파일의 고유 축이 생긴다 — 해소된 import 는 쓰이면
//     ArchUnit 이, 안 쓰여 바이트코드에 안 남으면 이 파일이 잡는다.
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
 * 블록 주석의 본문을 **줄 수를 유지한 채** 공백으로 덮는다.
 *
 * 재는 것이 둘이고, 둘 다 아래 「블록 주석 본문…」 테스트 **하나**가 판별한다.
 * ① 블록 주석 안의 `import` 줄은 위반이 아니다. ② 그 뒤 진짜 위반의 줄 번호는 **원본 그대로**다
 * (개행을 안 남기고 지우면 번호가 밀린다).
 *
 * ★줄 주석은 **여기서 손대지 않는다.** [FORBIDDEN_IMPORT_PATTERN] 이 줄머리를 `import` 로
 * 앵커하므로 `// import com.bts.issue…` 는 애초에 물리지 않는다. 줄 주석까지 지우는 코드를
 * 두면 **어떤 테스트도 그 존재를 판별하지 못하는 죽은 코드**가 되고, 그 KDoc 은 검증되지 않는
 * 효능을 주장하게 된다(「판정을 지워도 통과했다」의 표준 양식). 그래서 뺐다.
 *
 * @param source 원본 텍스트.
 * @returns 블록 주석 본문이 공백이 된, 같은 줄 수의 텍스트.
 */
function eraseBlockComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
}

/**
 * 한 파일 본문에서 금지 import 가 있는 줄 번호를 뽑는다.
 *
 * @param source `.kt` 파일 전문.
 * @returns 1-기반 줄 번호 목록.
 */
function forbiddenImportLines(source: string): number[] {
  return eraseBlockComments(source)
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

  /**
   * ★이 픽스처 하나가 [eraseBlockComments] 를 판별한다.
   *
   * 줄머리에 별표가 **없는** 블록 주석 본문이라 [FORBIDDEN_IMPORT_PATTERN] 의 `^\\s*import`
   * 앵커가 못 막는다. 즉 2행을 걸러 내는 것은 앵커가 아니라 블록 주석 지우기다.
   * 그리고 7행이 **7** 로 보고되어야 줄 수 보존까지 함께 증명된다.
   *
   * 이전 판은 본문이 ` * import …`(별표 선행)이라 앵커가 이미 막고 있었고, 그래서
   * [eraseBlockComments] 를 항등함수로 만들어도 9/9 초록이었다 — 판정을 지워도 통과하는
   * 공허 테스트였다(2026-09-06 독립 검증 DRIFT D1).
   */
  const BLOCK_COMMENT_FIXTURE = [
    BLOCK_COMMENT_OPEN,
    'import com.bts.issue.repository.IssueRepository',
    BLOCK_COMMENT_CLOSE,
    'package com.bts.agileplanning.application',
    '',
    'import com.bts.agileplanning.domain.Board',
    'import com.bts.issue.repository.IssueRepository',
  ].join('\n')

  /** 저장소에 실재하는 형태 — `BoardResponses.kt` 가 봉투 형태를 설명하며 그 이름을 적는다. */
  const KDOC_MENTION_FIXTURE = [
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

  test('★블록 주석 본문의 import 는 안 잡고, 뒤따르는 진짜 위반은 원본 줄 번호로 잡는다', () => {
    // INVARIANT. eraseBlockComments 를 항등함수로 바꾸면 [2, 7] 이 되어 red 다.
    //            개행을 안 남기고 지우면 [5] 가 되어 역시 red 다(2026-09-06 실측).
    //            이 파일에서 그 함수를 판별하는 단언은 **여기 하나**다.
    assert.deepEqual(forbiddenImportLines(BLOCK_COMMENT_FIXTURE), [7])
  })

  test('KDoc 별표 본문의 언급은 안 잡는다 — 이 축을 막는 것은 앵커다', () => {
    // 주석 지우기가 아니라 줄머리 앵커가 막는다. 앵커를 느슨하게 푸는 순간 red 가 난다.
    assert.deepEqual(forbiddenImportLines(KDOC_MENTION_FIXTURE), [])
  })

  test('줄 주석 안의 같은 문자열은 안 잡는다 — 이 축도 앵커가 막는다', () => {
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

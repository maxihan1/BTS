// `bts-merge` 스킬의 기계 대조 계약 — 대시보드 폴백 경로 · 「체크 0건」 판정
//
// ## 왜 이 파일이 다시 생겼나
//
// 이 두 계약은 원래 `merged-pr-run-cleanup.test.ts` 가 지켰다. 그 판별식이
// 커밋 `eca4a9c7f` 에서 삭제됐는데 **스킬 본문의 「이제 두 목록을 짝지어 막는다」는 문장은
// 그대로 남았다.** 2026-09-04 진단이 그것을 적발했다.
//
// 문장만 지우면 가드가 사라진 채로 굳는다. 지켜야 할 성질 자체는 여전히 유효하므로,
// **없어진 가드를 되살린다.** 이름은 새로 짓는다 — 삭제된 파일명을 되쓰면 그때의
// 넓은 계약(러너 정리·gh 이음매)까지 되살아난 것처럼 읽힌다.
//
// ## 무엇을 지키나
//
//   1. 대시보드 수동 폴백 경로 == 생성기의 실제 출력 경로.
//      틀린 경로가 26일간 문서에 남아 폴백이 pathspec 오류로 죽었다(2026-07-17 적발).
//   2. 머지 전 CI 확인이 **「체크 0건」을 통과로 읽지 않는다.**
//      CI 자동 실행을 끈 뒤(2026-08-21) `gh pr checks` 는 보통 아무것도 안 낸다.
//      「빨간불이 아니다」를 「초록이다」로 읽으면 검증 없는 머지가 된다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const MERGE_SKILL = '.claude/skills/bts-merge/SKILL.md'
const GENERATOR = 'scripts/build-dashboard.mjs'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

/** 생성기가 실제로 쓰는 출력 경로. 저장소 루트 기준. */
export function generatorOutputPath(source: string): string | null {
  const m = /OUTPUT_PATH\s*=\s*[^\n]*?['"`]([^'"`]+\.html)['"`]/.exec(source)
  if (m !== null) return m[1].replace(/^\.\//, '')
  const j = /join\([^)]*?['"`]([^'"`]*progress\.html)['"`]\)/.exec(source)
  return j === null ? null : j[1].replace(/^\.\//, '')
}

describe('bts-merge — 대시보드 폴백 경로', () => {
  const gen = read(GENERATOR)
  const skill = read(MERGE_SKILL)

  test('★생성기에서 출력 경로를 읽어낸다 (양성 대조군)', () => {
    const out = generatorOutputPath(gen)
    assert.ok(out !== null, `${GENERATOR} 에서 출력 경로를 못 읽었다 — 추출기가 깨졌다`)
    assert.match(out!, /\.html$/)
  })

  test('★스킬의 폴백 명령이 생성기의 실제 출력을 가리킨다', () => {
    const out = generatorOutputPath(gen)!
    const base = path.posix.basename(out)
    const lines = skill
      .split('\n')
      .filter((l) => l.includes('git add') && l.includes(base))

    assert.ok(
      lines.length > 0,
      `스킬의 폴백 명령에 ${base} 를 add 하는 줄이 없다 — 계약이 지킬 대상을 잃었다`,
    )
    for (const line of lines) {
      assert.ok(
        line.includes(out),
        `폴백 경로가 생성기 출력과 다르다.\n    생성기: ${out}\n    스킬:   ${line.trim()}\n` +
          '  종전에 이 불일치가 26일간 남아 폴백이 pathspec 오류로 죽었다(2026-07-17).',
      )
    }
  })

  test('★틀린 경로를 본문 어디에도 적지 않는다', () => {
    // 「설명하려고 옮겨 적는 것」도 금지다 — 다음 사람이 그것을 복사한다.
    assert.doesNotMatch(
      skill,
      /docs\/plan\/progress\.html/,
      '틀린 경로(docs/plan/progress.html)가 본문에 있다',
    )
  })
})

describe('bts-merge — 머지 전 CI 확인', () => {
  const skill = read(MERGE_SKILL)

  test('★「체크 0건」을 통과로 읽지 않는다고 명시한다', () => {
    // ★같은 **줄**에서 짝을 본다. 두 문구가 파일 어딘가에 따로 있기만 하면 통과하는
    //   느슨한 형태로 두면, 판정 표를 지워도 초록이 된다(뮤테이션으로 확인함).
    const paired = skill
      .split('\n')
      .filter((l) => /체크 0건|no checks/.test(l) && /통과가 아니다|초록이 아니다/.test(l))

    assert.ok(
      paired.length > 0,
      'CI 체크가 0건일 때 「통과가 아니다」라는 판정이 한 줄로 붙어 있지 않다.\n' +
        '  CI 자동 실행을 끈 뒤(2026-08-21) `gh pr checks` 는 보통 아무것도 내지 않는다.\n' +
        '  「빨간불이 아니다」를 「초록이다」로 읽으면 검증 없는 머지가 된다.\n' +
        '  판정 표의 해당 행을 지우거나 문구만 바꾸면 이 단언이 red 가 된다.',
    )
  })

  test('★젠킨스 「판정 불가」도 통과로 읽지 않는다고 명시한다', () => {
    // ★2026-09-09 확장. `.husky/pre-push` 에서 백엔드·프론트 테스트를 걷어내 젠킨스로 옮겼다.
    //   그 순간 「로컬에서 돌렸으니 됐다」가 성립하지 않고, 머지 전 유일한 기계 근거가
    //   젠킨스 빌드가 된다. `gh pr checks` 의 「체크 0건」에 걸어 둔 것과 **같은 보장**을
    //   새 자리에도 건다 — 안 걸면 젠킨스 쪽에만 그 구멍이 다시 열린다.
    //
    //   같은 줄에서 짝을 보는 이유도 같다. 두 문구가 파일 어딘가에 따로 있기만 하면
    //   판정 표를 지워도 통과하는 느슨한 형태가 된다.
    const paired = skill
      .split('\n')
      .filter((l) => /판정 불가/.test(l) && /통과가 아니다|초록이 아니다/.test(l))

    assert.ok(
      paired.length > 0,
      '젠킨스 빌드가 「판정 불가」일 때 「통과가 아니다」라는 판정이 한 줄로 붙어 있지 않다.\n' +
        '  빌드 없음 · 도는 중 · **다른 브랜치의 빌드** · 젠킨스 무응답이 전부 판정 불가다.\n' +
        '  그중 다른 브랜치의 초록을 이 브랜치의 초록으로 읽는 것이 가장 나쁜 오독이다.',
    )
  })

  test('★젠킨스 조회 도구를 실제로 부른다', () => {
    // 판정 표만 있고 부르는 명령이 없으면 그 표는 장식이다.
    assert.match(
      skill,
      /jenkins-build-status\.ts/,
      'bts-merge 가 jenkins-build-status.ts 를 부르지 않는다 — 판정 표가 근거를 못 얻는다',
    )
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, 'scripts/workflow/jenkins-build-status.ts')),
      '스킬이 가리키는 조회 도구가 실재하지 않는다',
    )
  })

  test('★같은 파일 안에서 전제가 어긋나지 않는다', () => {
    // Step 5 는 「CI 자동 실행을 껐으므로 run 이 0건」이라고 적는다.
    // Step 1 이 그 사실을 모르는 채 「초록 확인」만 지시하면 두 절의 전제가 갈린다.
    if (/자동 실행을 껐/.test(skill)) {
      assert.ok(
        /체크 0건/.test(skill),
        'Step 5 는 CI 가 꺼진 것을 아는데 Step 1 의 확인 절차가 그것을 반영하지 않는다',
      )
    }
  })
})

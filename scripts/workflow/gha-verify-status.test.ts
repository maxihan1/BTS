// GHA 검증 상태 판정이 「체크 0건」을 통과로 읽지 않는지 대조 — 검증 없는 머지를 막는 자리
//
// ## 무엇을 지키나
//
// `jenkins-build-status.ts:13-19` 가 세운 3분기 계약을 GHA 쪽에서도 그대로 지킨다.
// 종료 코드 0(초록) · 1(빨강) · 2(판정 불가)이고, **2 를 0 으로 뭉개지 않는 것**이 핵심이다.
//
// `merge-skill-contract.test.ts` 가 스킬 본문 쪽에서 같은 성질을 지킨다. 이 파일은
// 판정 함수 자체를 값으로 흔든다 — 문장이 아니라 동작을 본다.
//
// ## ★`skipped` 를 어떻게 볼 것인가 — 이 저장소 고유의 판단
//
// 일반적인 CI 에서 `skipped` 는 이상 신호지만, `verify.yml` 은 route 판정에 따라 잡을
// **의도적으로** 건너뛴다. 문서만 고친 PR 이면 backend·frontend·e2e 가 전부 skipped 다.
// 그것을 빨강으로 보면 모든 문서 PR 이 막힌다.
//
// 그래서 skipped 는 초록으로 인정하되 **전부 skipped 인 경우만 판정 불가**로 가른다.
// `discriminants` 잡이 조건 없이 항상 도므로 정상이면 최소 1건이 success 이고,
// 전부 skipped 는 그 무조건성이 깨졌다는 신호이기 때문이다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { judge } from './gha-verify-status.ts'
import type { Check } from './gha-verify-status.ts'

/** 끝난 체크 하나. */
function done(name: string, conclusion: string): Check {
  return { name, conclusion, status: 'completed' }
}

/** 아직 안 끝난 체크 하나. */
function running(name: string): Check {
  return { name, conclusion: null, status: 'in_progress' }
}

describe('GHA 검증 상태 3분기', () => {
  test('★★① 체크 0건은 통과가 아니다', () => {
    assert.equal(
      judge([]),
      'unknown',
      '★체크 0건을 초록으로 읽었다 — 워크플로우가 안 떴거나 지워졌을 때\n' +
        '  「빨간불이 아니니 통과」가 되어 **검증 없는 머지**가 된다.',
    )
  })

  test('★② 아직 안 끝난 체크가 있으면 판정을 미룬다', () => {
    assert.equal(judge([done('a', 'success'), running('b')]), 'unknown')
    assert.equal(judge([running('a')]), 'unknown')
  })

  test('★③ 실패 계열이 하나라도 있으면 빨강', () => {
    for (const bad of ['failure', 'timed_out', 'action_required', 'cancelled', 'stale']) {
      assert.equal(
        judge([done('ok', 'success'), done('bad', bad)]),
        'red',
        `'${bad}' 를 빨강으로 안 봤다`,
      )
    }
  })

  test('★★④ skipped 는 초록으로 인정한다 — 이 저장소에서는 의도된 경로다', () => {
    // 문서만 고친 PR 의 실제 모양. discriminants 만 돌고 나머지는 route 가 건너뛴다.
    assert.equal(
      judge([done('discriminants', 'success'), done('backend', 'skipped'), done('frontend', 'skipped')]),
      'green',
      '★skipped 를 초록에서 뺐다 — 문서만 고친 PR 이 전부 막힌다.',
    )
  })

  test('★★⑤ 그런데 전부 skipped 면 판정 불가다', () => {
    assert.equal(
      judge([done('backend', 'skipped'), done('frontend', 'skipped')]),
      'unknown',
      '★전부 skipped 를 초록으로 읽었다 — 아무것도 검증되지 않았는데 통과가 된다.\n' +
        '  `discriminants` 는 조건 없이 항상 돌므로 정상이면 최소 1건이 success 다.',
    )
  })

  test('★⑥ 모르는 결론값을 초록으로 뭉개지 않는다', () => {
    assert.equal(
      judge([done('a', 'success'), done('b', 'something_new')]),
      'unknown',
      '★GitHub 이 결론값을 추가하면 조용히 통과한다 — 모르는 값은 사람이 봐야 한다.',
    )
  })

  test('★⑦ 전부 success 면 초록', () => {
    assert.equal(judge([done('a', 'success'), done('b', 'success')]), 'green')
  })

  test('★★⑧ 비-공허 짝 — 판정이 세 값을 모두 실제로 낸다', () => {
    const verdicts = new Set([
      judge([]),
      judge([done('a', 'failure')]),
      judge([done('a', 'success')]),
    ])
    assert.deepEqual(
      [...verdicts].sort(),
      ['green', 'red', 'unknown'],
      '★세 분기 중 안 나오는 값이 있다 — 그 분기는 죽은 코드이고 계약이 반쪽이다.',
    )
  })
})

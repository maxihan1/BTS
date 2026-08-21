// 푸시 훅 셸 소스를 읽는 어휘·파서 한 벌 — 훅 배선 판별식은 여기만 쓴다
//
// ## 왜 따로 있나
//
// 훅 배선을 재는 판정은 한 자리에 머물지 않는다. 「판별식 전량을 무조건 부르는가」 옆에
// 「그 앞에서 환경을 지우는가」가 붙고, 그 뒤에 오는 판정도 **같은 훅 파일**을 읽는다.
// 어휘와 파서를 판정 파일 안에 두면 그 파일이 줄수 상한에 걸릴 때 **쪼개면서 복제**된다 —
// 테스트 파일은 export 를 하지 않으므로 나뉜 쪽은 자기 사본을 만드는 수밖에 없다.
// 그 순간 「블록을 여는 토큰」이 두 벌이 되고, 둘은 서로를 검사하지 않는다.
// 이 저장소가 이미 이름 붙인 지배 결함 양식(`two-lists-never-check-each-other`)이다.
//
// 그래서 어휘와 파서는 여기 한 벌만 둔다.
//
// ## 이 모듈은 판정하지 않는다
//
// 여기 있는 것은 **훅 소스를 읽어 주는 것**뿐이다. 「무엇이 위반인가」는 판별식 파일에 남는다.
// 판정을 이리로 끌고 오면 실패 메시지가 대상에서 멀어지고, 무엇보다 이 모듈이
// 자기가 검사받아야 할 대상을 스스로 정의하게 된다.
//
// 판별식. scripts/workflow/discriminant-hook-wiring.test.ts

/** 푸시 훅의 저장소 상대 경로. 실패 메시지에도 이 문자열을 그대로 싣는다. */
export const HOOK = '.husky/pre-push'

/**
 * 셸 블록을 여는/닫는 토큰. 대상 줄이 **블록 안에 있으면** 조건부 실행이다.
 *
 * ★훅 전체에 조건문을 금지하지 않는다. 훅에는 판별식 말고 다른 명령도 산다(백엔드 모듈
 *   테스트 등). 그것들은 조건을 가져도 된다 — 재는 것은 **판정이 지목한 그 한 줄의 도달성**이다.
 *   종전 규칙은 「훅 어디에도 조건문 금지」였고, 정당한 조건문을 막아 다음 사람이 이 판별식을
 *   지우게 만드는 형태였다(2026-08-21 정정).
 */
export const BLOCK_OPEN = /^(if|for|while|case|until)\b/
export const BLOCK_CLOSE = /^(fi|done|esac)\b/

/**
 * 호출 자체를 조건부로 만드는 연산자.
 *
 * ★`&&`·`||` 는 겉보기에 조건문이 아니지만 앞 명령이 실패하면 판별식이 통째로 스킵되고
 *   훅은 초록이다 — 조용한 부재다.
 */
export const GUARD_OPERATORS = ['&&', '||']

/** pnpm 래퍼. 워크트리에서 모듈 재설치를 유발해 무-TTY 로 죽는다. */
export const PNPM_WRAPPER = /(^|[;&|(\s])(npx\s+)?pnpm(\s|$)/

/** 주석과 빈 줄을 제거한 실행 줄만. 판정 대상은 「무엇이 적혀 있나」가 아니라 「무엇이 실행되나」다. */
export function commandLines(sh: string): string[] {
  return sh
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '' && !l.startsWith('#'))
}

/**
 * `matches` 가 처음 참이 되는 줄이 놓인 셸 블록 깊이. 0 이면 무조건 도달한다.
 *
 * 그런 줄이 아예 없으면 `null` — **부재를 0 으로 바꾸지 않는다.** 0 으로 돌려주면
 * 「없는 줄」이 「무조건 실행되는 줄」과 같은 값이 되어, 호출이 통째로 사라진 훅이 초록이 된다.
 * 부재는 호출자가 자기 실패 메시지로 판정한다.
 *
 * ★대상 줄인지를 **블록 토큰보다 먼저** 본다. 그래서 대상 줄 자신이 `if` 로 시작해도
 *   그 줄까지의 깊이가 나온다 — 자기 자신이 여는 블록은 자기 도달성을 못 막는다.
 *
 * @param lines 주석을 걷어낸 실행 줄 (`commandLines` 산출물)
 * @param matches 깊이를 재고 싶은 줄을 고르는 술어
 */
export function blockDepthAt(lines: string[], matches: (line: string) => boolean): number | null {
  let depth = 0
  for (const line of lines) {
    if (matches(line)) return depth
    if (BLOCK_OPEN.test(line)) depth += 1
    else if (BLOCK_CLOSE.test(line)) depth = Math.max(0, depth - 1)
  }
  return null
}

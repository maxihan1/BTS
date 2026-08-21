// git 을 spawn 할 때 넘길 env 에서 GIT_ 네임스페이스를 통째로 걷어내는 스크럽 헬퍼

/**
 * `GIT_` 로 시작하는 키를 **전량** 뺀 `env` 사본을 만든다.
 *
 * 지울 변수를 열거하지 않는다. 열거하면 「git 이 넣는 목록」과 「우리가 지우는 목록」이라는
 * 두 목록이 생기고 둘은 서로를 검사하지 않는다. 접두로만 판정한다.
 *
 * @param {NodeJS.ProcessEnv} [base] 바탕이 될 환경변수. 기본값은 `process.env`
 * @returns {NodeJS.ProcessEnv} 스크럽된 사본 — 원본은 안 건드린다
 */
export function gitFixtureEnv(base = process.env) {
  /** @type {NodeJS.ProcessEnv} */
  const scrubbed = {};
  for (const [key, value] of Object.entries(base)) {
    if (key.startsWith('GIT_')) continue;
    scrubbed[key] = value;
  }
  return scrubbed;
}

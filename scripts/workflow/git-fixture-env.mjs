// git 을 spawn 할 때 넘길 env 에서 GIT_ 네임스페이스를 통째로 걷어내는 스크럽 헬퍼

/**
 * git 이 훅 프로세스에 심는 환경변수의 네임스페이스 접두.
 *
 * 지울 변수를 **열거하지 않는 것이 요점**이다. 열거하면 「git 이 넣는 목록」과
 * 「우리가 지우는 목록」이라는 두 목록이 생기고, 둘은 서로를 검사하지 않는다.
 * git 이 변수를 하나 더 넣는 순간 우리 목록은 조용히 낡는다. 접두로만 판정한다.
 */
const GIT_ENV_PREFIX = 'GIT_';

/**
 * `GIT_` 로 시작하는 키를 **전량** 뺀 `env` 사본을 만든다.
 *
 * `GIT_DIR` 는 `cwd` 를 이긴다 — 그래서 `cwd` 만으로 격리한 픽스처가 훅 안에서는
 * 실저장소에 걸린다. git 을 spawn 하는 쪽은 이 함수가 돌려준 `env` 를 넘겨야 한다.
 *
 * @param {NodeJS.ProcessEnv} [base] 바탕이 될 환경변수. 기본값은 `process.env`
 * @returns {NodeJS.ProcessEnv} 스크럽된 사본 — 원본은 안 건드린다
 */
export function gitFixtureEnv(base = process.env) {
  /** @type {NodeJS.ProcessEnv} */
  const scrubbed = {};
  for (const [key, value] of Object.entries(base)) {
    if (key.startsWith(GIT_ENV_PREFIX)) continue;
    scrubbed[key] = value;
  }
  return scrubbed;
}

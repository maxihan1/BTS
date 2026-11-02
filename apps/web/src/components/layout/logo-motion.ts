// 위쪽 바 로고 움직임(체크 · 글자 · 밑줄)을 마우스를 올릴 때 처음부터 다시 돌린다 — Test Commander logoMotion.ts 와 같은 규칙

/** index.css 의 로고 움직임 이름 — 이것만 다시 돌린다 */
const LOGO_ANIMATIONS = new Set(['brand-logo-check', 'brand-logo-slide', 'brand-logo-line'])

/**
 * 체크 · 글자가 아직 도는 중이면 다시 시작하지 않는다 — 로고 위를 오가며 지날 때마다 글자가 깜빡인다.
 * 밑줄만 흐려지는 중이면 막지 않는다 — 밑줄은 글자보다 1초 넘게 더 돌아 그동안 마우스 올림이 먹히지 않는다.
 * 움직임 줄이기면 움직임이 없어 아무것도 안 한다.
 */
export function replayLogoMotion(logo: Element): void {
  if (typeof logo.getAnimations !== 'function') return
  const anims = logo
    .getAnimations({ subtree: true })
    .filter((a): a is CSSAnimation => LOGO_ANIMATIONS.has((a as CSSAnimation).animationName))
  if (anims.some((a) => a.animationName !== 'brand-logo-line' && a.playState === 'running')) return
  for (const a of anims) {
    a.cancel()
    a.play()
  }
}

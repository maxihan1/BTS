// 위쪽 바 로고 움직임을 마우스 올림에 다시 돌리는 규칙 테스트
import { describe, expect, it, vi } from 'vitest'
import { replayLogoMotion } from '../logo-motion'

const anim = (animationName: string, playState: AnimationPlayState) => ({
  animationName,
  playState,
  cancel: vi.fn(),
  play: vi.fn(),
})

const logoWith = (anims: ReturnType<typeof anim>[]) =>
  ({ getAnimations: () => anims }) as unknown as Element

describe('replayLogoMotion', () => {
  it('다 끝난 로고는 체크 · 글자 · 밑줄을 처음부터 다시 돌린다', () => {
    const anims = [
      anim('brand-logo-check', 'finished'),
      anim('brand-logo-slide', 'finished'),
      anim('brand-logo-line', 'finished'),
    ]
    replayLogoMotion(logoWith(anims))
    for (const a of anims) {
      expect(a.cancel).toHaveBeenCalledOnce()
      expect(a.play).toHaveBeenCalledOnce()
    }
  })

  it('체크나 글자가 아직 도는 중이면 다시 시작하지 않는다 — 로고 위를 오갈 때 깜빡이지 않게', () => {
    const anims = [anim('brand-logo-check', 'finished'), anim('brand-logo-slide', 'running')]
    replayLogoMotion(logoWith(anims))
    for (const a of anims) expect(a.play).not.toHaveBeenCalled()
  })

  it('밑줄만 흐려지는 중이면 다시 돌린다', () => {
    const anims = [anim('brand-logo-slide', 'finished'), anim('brand-logo-line', 'running')]
    replayLogoMotion(logoWith(anims))
    for (const a of anims) expect(a.play).toHaveBeenCalledOnce()
  })

  it('로고 움직임이 아닌 것은 건드리지 않는다', () => {
    const other = anim('fade-in', 'finished')
    replayLogoMotion(logoWith([other]))
    expect(other.play).not.toHaveBeenCalled()
  })

  it('getAnimations 가 없는 환경(jsdom)에서는 아무것도 안 한다', () => {
    expect(() => replayLogoMotion({} as Element)).not.toThrow()
  })
})

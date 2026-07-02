// PublicGadgetRenderer 렌더 테스트 — FR-DB-03 D6/D7 Task-7 RED (화이트리스트 fail-closed)
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { DashboardTile } from '@/lib/dashboard-layout'
import { PublicGadgetRenderer } from './PublicGadgetRenderer'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const TILE_BASE = { i: 'tile-1', x: 0, y: 0, w: 6, h: 4 }

afterEach(() => {
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 화이트리스트 통과 — 정적 가젯 2종
// ─────────────────────────────────────────────────────────────────────────────

describe('PublicGadgetRenderer — 화이트리스트 통과 (정적 가젯)', () => {
  it('text_widget → TextWidgetGadget을 렌더링한다 (config.markdown 추출)', () => {
    const tile: DashboardTile = { ...TILE_BASE, gadgetType: 'text_widget', config: { markdown: '공개 텍스트' } }
    render(<PublicGadgetRenderer tile={tile} />)
    expect(screen.getByText('공개 텍스트')).toBeInTheDocument()
  })

  it('link_list → LinkListGadget을 렌더링한다 (config.links 추출)', () => {
    const tile: DashboardTile = {
      ...TILE_BASE,
      gadgetType: 'link_list',
      config: { links: [{ label: '공식 사이트', url: 'https://example.com' }] },
    }
    render(<PublicGadgetRenderer tile={tile} />)
    const link = screen.getByText('공식 사이트')
    expect(link.closest('a')).toHaveAttribute('href', 'https://example.com')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 화이트리스트 차단 — 데이터 가젯 4종 + 미지 타입 (fail-closed, EC-2)
// ─────────────────────────────────────────────────────────────────────────────

describe('PublicGadgetRenderer — 화이트리스트 차단 (fail-closed)', () => {
  const dataGadgetTypes = ['assigned_to_me', 'recently_created', 'filter_result', 'issue_count']

  it.each(dataGadgetTypes)('%s → 로그인 필요 플레이스홀더를 표시하고 네트워크 호출이 없다', (gadgetType) => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const tile: DashboardTile = { ...TILE_BASE, gadgetType, config: { projectKey: 'ATLAS' } }
    render(<PublicGadgetRenderer tile={tile} />)
    expect(screen.getByText('로그인이 필요한 가젯입니다')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('미지(신규) gadgetType → 로그인 필요 플레이스홀더를 표시한다 (신규 타입 자동 차단)', () => {
    const tile: DashboardTile = { ...TILE_BASE, gadgetType: 'brand_new_future_gadget' }
    render(<PublicGadgetRenderer tile={tile} />)
    expect(screen.getByText('로그인이 필요한 가젯입니다')).toBeInTheDocument()
  })

  it('gadgetType 없는 legacy 타일도 플레이스홀더를 표시한다 (fail-closed)', () => {
    const tile: DashboardTile = { ...TILE_BASE, title: '레거시 타일' }
    render(<PublicGadgetRenderer tile={tile} />)
    expect(screen.getByText('로그인이 필요한 가젯입니다')).toBeInTheDocument()
  })

  it('플레이스홀더는 muted 텍스트 스타일이며 destructive 스타일이 아니다', () => {
    const tile: DashboardTile = { ...TILE_BASE, gadgetType: 'issue_count' }
    render(<PublicGadgetRenderer tile={tile} />)
    const message = screen.getByText('로그인이 필요한 가젯입니다')
    expect(message.className).toContain('text-muted-foreground')
    expect(message.className).not.toContain('destructive')
  })
})

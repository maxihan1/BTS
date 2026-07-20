// PageLayout 컴포넌트 테스트 — maxWidth 클래스 매핑·className 병합·main 미렌더 구조 회귀 가드 (FR-UX-06 PR13 Task 2, PL-1)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PageLayout } from '../PageLayout'

describe('PageLayout', () => {
  it('children을 렌더한다', () => {
    render(
      <PageLayout>
        <p>본문 콘텐츠</p>
      </PageLayout>,
    )

    expect(screen.getByText('본문 콘텐츠')).toBeInTheDocument()
  })

  it("maxWidth 기본값은 '4xl' → max-w-4xl 클래스가 컨테이너에 적용된다", () => {
    const { container } = render(
      <PageLayout>
        <p>본문</p>
      </PageLayout>,
    )

    const wrapper = container.firstElementChild
    expect(wrapper).not.toBeNull()
    expect(wrapper).toHaveClass('max-w-4xl')
  })

  it("maxWidth='2xl' → max-w-2xl 클래스가 컨테이너에 적용된다", () => {
    const { container } = render(
      <PageLayout maxWidth="2xl">
        <p>본문</p>
      </PageLayout>,
    )

    expect(container.firstElementChild).toHaveClass('max-w-2xl')
  })

  it("maxWidth='7xl' → max-w-7xl 클래스가 컨테이너에 적용된다", () => {
    const { container } = render(
      <PageLayout maxWidth="7xl">
        <p>본문</p>
      </PageLayout>,
    )

    expect(container.firstElementChild).toHaveClass('max-w-7xl')
  })

  it('className prop이 컨테이너 클래스에 병합된다', () => {
    const { container } = render(
      <PageLayout className="custom-page-class">
        <p>본문</p>
      </PageLayout>,
    )

    expect(container.firstElementChild).toHaveClass('custom-page-class')
  })

  it('★구조 회귀 가드 — 렌더 결과에 <main> 태그가 없다(문서 main은 ShellLayout 소유)', () => {
    const { container } = render(
      <PageLayout>
        <p>본문</p>
      </PageLayout>,
    )

    expect(container.querySelector('main')).toBeNull()
  })

  it('컨테이너는 <div>로 렌더된다(main 아님)', () => {
    const { container } = render(
      <PageLayout>
        <p>본문</p>
      </PageLayout>,
    )

    expect(container.firstElementChild?.tagName).toBe('DIV')
  })
})

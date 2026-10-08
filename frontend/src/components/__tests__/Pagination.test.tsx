import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Pagination } from '../Pagination';

const base = { page: 2, hasPrev: true, hasNext: true, onPrev: vi.fn(), onNext: vi.fn() };

describe('Pagination', () => {
  it('renders nothing when there is only one page', () => {
    const { container } = render(<Pagination {...base} page={1} hasPrev={false} hasNext={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('is a labelled navigation landmark showing the page number', () => {
    render(<Pagination {...base} />);
    expect(screen.getByRole('navigation', { name: /pagination/i })).toBeInTheDocument();
    expect(screen.getByText('Page 2')).toHaveAttribute('aria-live', 'polite');
  });

  it('disables Previous on the first page', () => {
    render(<Pagination {...base} page={1} hasPrev={false} />);
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeEnabled();
  });

  it('disables Next on the last page', () => {
    render(<Pagination {...base} hasNext={false} />);
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });

  it('disables both buttons while disabled (e.g. loading)', () => {
    render(<Pagination {...base} disabled />);
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });

  it('calls onPrev and onNext', () => {
    const onPrev = vi.fn();
    const onNext = vi.fn();
    render(<Pagination {...base} onPrev={onPrev} onNext={onNext} />);
    fireEvent.click(screen.getByRole('button', { name: /previous/i }));
    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(onPrev).toHaveBeenCalledOnce();
    expect(onNext).toHaveBeenCalledOnce();
  });
});

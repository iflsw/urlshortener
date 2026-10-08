interface PaginationProps {
  page: number;
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
  disabled?: boolean;
}

/** Prev/Next for cursor paging. There is no total, so it shows "Page X" rather than "Page X of Y". */
export function Pagination({ page, hasPrev, hasNext, onPrev, onNext, disabled }: PaginationProps) {
  if (!hasPrev && !hasNext) return null;

  return (
    <nav className="pagination" aria-label="Pagination">
      <button type="button" className="btn-secondary" onClick={onPrev} disabled={disabled || !hasPrev}>
        Previous
      </button>
      <span className="pagination__page" aria-live="polite">
        Page {page}
      </span>
      <button type="button" className="btn-secondary" onClick={onNext} disabled={disabled || !hasNext}>
        Next
      </button>
    </nav>
  );
}

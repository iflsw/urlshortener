import { useUrlShortener } from './hooks/useUrlShortener';
import { ShortenForm } from './components/ShortenForm';
import { ResultBanner } from './components/ResultBanner';
import { UrlTable } from './components/UrlTable';
import { Pagination } from './components/Pagination';

export default function App() {
  const {
    urls,
    page,
    hasPrev,
    hasNext,
    loading,
    error,
    lastCreated,
    shorten,
    deleteUrl,
    nextPage,
    prevPage,
    clearLastCreated,
  } = useUrlShortener();

  const confirmAndDelete = (alias: string) => {
    if (window.confirm(`Delete the short URL "${alias}"? Links using it will stop working.`)) {
      void deleteUrl(alias);
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header__inner">
          <div className="logo">
            <span className="logo__mark" aria-hidden="true">⌁</span>
            <span className="logo__name">Snip</span>
          </div>
          <p className="tagline">Long URLs, made short.</p>
        </div>
      </header>

      <main className="app-main">
        <section className="card shorten-card" aria-labelledby="shorten-heading">
          <h2 id="shorten-heading" className="card__title">
            Shorten a URL
          </h2>
          <ShortenForm onSubmit={shorten} onSubmitAttempt={clearLastCreated} />
        </section>

        {lastCreated && (
          <ResultBanner result={lastCreated} onDismiss={clearLastCreated} />
        )}

        {error && (
          <div className="banner banner--error" role="alert">
            <strong>Error:</strong> {error}
          </div>
        )}

        <section className="card" aria-labelledby="list-heading">
          <h2 id="list-heading" className="card__title">
            Shortened URLs
          </h2>

          {loading && urls.length === 0 ? (
            <div className="loading" aria-live="polite" aria-busy="true">
              Loading…
            </div>
          ) : (
            // Keep showing the current rows while the next page loads, to avoid flicker.
            <div aria-busy={loading}>
              <UrlTable urls={urls} onDelete={confirmAndDelete} disabled={loading} />
              <Pagination
                page={page}
                hasPrev={hasPrev}
                hasNext={hasNext}
                onPrev={prevPage}
                onNext={nextPage}
                disabled={loading}
              />
            </div>
          )}
        </section>
      </main>

      <footer className="app-footer">
        <p>Snip URL Shortener</p>
      </footer>
    </div>
  );
}

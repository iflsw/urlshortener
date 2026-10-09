import type { UrlListItem } from '../types/api';
import { isHttpUrl } from '../utils/safeUrl';

interface UrlTableProps {
  urls: UrlListItem[];
  onDelete: (alias: string) => void;
  disabled?: boolean;
}

/** Renders a URL as an external link only if it is http(s); anything else is shown as inert text. */
function ExternalUrl({ url, className }: { url: string; className: string }) {
  if (!isHttpUrl(url)) {
    return <span className={className} title={url}>{url}</span>;
  }
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" className={className} title={url}>
      {url}
    </a>
  );
}

export function UrlTable({ urls, onDelete, disabled }: UrlTableProps) {
  if (urls.length === 0) {
    return (
      <div className="empty-state">
        <p>No shortened URLs yet.</p>
      </div>
    );
  }

  return (
    <div className="url-table-wrapper">
      <table className="url-table">
        <thead>
          <tr>
            <th scope="col">Short URL</th>
            <th scope="col">Original URL</th>
            <th scope="col" className="actions-cell">
              Actions
            </th>
          </tr>
        </thead>
        <tbody>
          {urls.map((url) => (
            <tr key={url.alias}>
              <td>
                <ExternalUrl url={url.shortUrl} className="short-url-link" />
              </td>
              <td>
                <ExternalUrl url={url.fullUrl} className="full-url" />
              </td>
              <td className="actions-cell">
                <button
                  type="button"
                  className="btn-danger"
                  onClick={() => onDelete(url.alias)}
                  disabled={disabled}
                  aria-label={`Delete ${url.alias}`}
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

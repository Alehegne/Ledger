import { Link } from "react-router-dom";

export const NotFoundPage = () => {
  return (
    <div className="mx-auto max-w-2xl page-card p-6">
      <p className="page-header-kicker">Not found</p>
      <h2 className="mt-2 text-2xl font-semibold text-slate-900">
        This page is not available.
      </h2>
      <p className="mt-2 text-sm text-slate-600">
        The address may be incorrect or the page may have moved.
      </p>
      <div className="mt-5">
        <Link to="/" className="btn-secondary" aria-label="Return to dashboard">
          Return to dashboard
        </Link>
      </div>
    </div>
  );
};

import { Link } from "react-router-dom";

export const AccessDeniedPage = () => {
  return (
    <div className="mx-auto max-w-2xl page-card border-red-200 bg-red-50 p-6 text-red-900">
      <p className="text-xs uppercase tracking-[0.18em] text-red-700">
        Access denied
      </p>
      <h2 className="mt-2 text-2xl font-semibold">
        You do not have permission to view this page.
      </h2>
      <p className="mt-2 text-sm text-red-800">
        Your current role does not include access to this area. Contact your
        organization administrator if access is required.
      </p>
      <div className="mt-5">
        <Link to="/" className="btn-secondary" aria-label="Return to dashboard">
          Return to dashboard
        </Link>
      </div>
    </div>
  );
};

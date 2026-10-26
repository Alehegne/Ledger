import {
  BrowserRouter,
  Navigate,
  Outlet,
  Route,
  Routes,
} from "react-router-dom";
import { AppProviders } from "./app/providers";
import { useAuth } from "./app/auth-context";
import { RequireAuth, RequireRole } from "./app/route-guards";
import { AppShell } from "./components/layout/app-shell";
import { AccessDeniedPage } from "./features/auth/access-denied-page";
import { NotFoundPage } from "./features/auth/not-found-page";
import { LoginPage } from "./features/auth/login-page";
import { DashboardPage } from "./features/dashboard/dashboard-page";
import { AccountsPage } from "./features/accounts/accounts-page";
import { JournalPage } from "./features/journal/journal-page";
import { ReportsPage } from "./features/reports/reports-page";
import { OrganizationPage } from "./features/organization/organization-page";

const ProtectedLayout = () => {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  );
};

const PublicLayout = () => {
  const { session } = useAuth();

  if (session) {
    return <Navigate to="/" replace />;
  }

  return <Outlet />;
};

function App() {
  return (
    <AppProviders>
      <BrowserRouter>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path="/login" element={<LoginPage />} />
          </Route>

          <Route element={<ProtectedLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="/accounts" element={<AccountsPage />} />
            <Route
              path="/journal"
              element={
                <RequireRole allowedRoles={["ADMIN", "ACCOUNTANT", "VIEWER"]}>
                  <JournalPage />
                </RequireRole>
              }
            />
            <Route
              path="/journal-entries"
              element={<Navigate to="/journal" replace />}
            />
            <Route
              path="/journal-entries/new"
              element={<Navigate to="/journal" replace />}
            />
            <Route
              path="/journal-entries/:entryId"
              element={<Navigate to="/journal" replace />}
            />
            <Route path="/reports" element={<ReportsPage />} />
            <Route
              path="/reports/general-ledger"
              element={<Navigate to="/reports" replace />}
            />
            <Route
              path="/reports/trial-balance"
              element={<Navigate to="/reports" replace />}
            />
            <Route
              path="/users"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/settings"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/organization/users"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route
              path="/organization/settings"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <OrganizationPage />
                </RequireRole>
              }
            />
            <Route path="/forbidden" element={<AccessDeniedPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    </AppProviders>
  );
}

export default App;

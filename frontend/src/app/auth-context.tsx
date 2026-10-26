import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
} from "react";
import type { AuthSession } from "../types/domain";

const AUTH_STORAGE_KEY = "ledger.frontend.auth.session";

interface AuthContextValue {
  session: AuthSession | null;
  setSession: (session: AuthSession | null) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const readStoredSession = (): AuthSession | null => {
  const raw = localStorage.getItem(AUTH_STORAGE_KEY);
  if (!raw) return null;

  try {
    return JSON.parse(raw) as AuthSession;
  } catch {
    localStorage.removeItem(AUTH_STORAGE_KEY);
    return null;
  }
};

export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [session, setSessionState] = useState<AuthSession | null>(() =>
    readStoredSession(),
  );

  const setSession = useCallback((next: AuthSession | null) => {
    setSessionState(next);
    if (next) {
      localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(next));
    } else {
      localStorage.removeItem(AUTH_STORAGE_KEY);
    }
  }, []);

  const logout = useCallback(() => setSession(null), [setSession]);

  const value = useMemo(
    () => ({ session, setSession, logout }),
    [session, setSession, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("Auth context is unavailable");
  return ctx;
};

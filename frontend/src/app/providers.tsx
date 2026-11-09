import { useMemo, useState } from "react";
import {
  MutationCache,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import { queryClient as defaultQueryClient } from "./query-client";
import { AuthProvider, useAuth } from "./auth-context";
import { ToastProvider } from "./toast-provider";
import { useToast } from "./toast-store";
import { ServiceContext } from "../services/interfaces/service-context";
import { createHttpClient } from "../services/api/http-client";
import { createService } from "../services/create-service";

const InnerProviders = ({ children }: { children: React.ReactNode }) => {
  const { session, getSession, setSession, logout } = useAuth();
  const { showError } = useToast();
  const [client] = useState(
    () =>
      new QueryClient({
        mutationCache: new MutationCache({
          onError: (error) => {
            showError(error);
          },
        }),
        defaultOptions: defaultQueryClient.getDefaultOptions(),
      }),
  );

  const httpClient = useMemo(
    () =>
      createHttpClient({
        getToken: () => getSession()?.token ?? null,
        getRefreshToken: () => getSession()?.refreshToken ?? null,
        onSessionRefreshed: setSession,
        onUnauthorized: logout,
      }),
    [getSession, setSession, logout],
  );

  const service = useMemo(
    () =>
      createService({
        getToken: () => getSession()?.token ?? null,
        getRefreshToken: () => getSession()?.refreshToken ?? null,
        onSessionRefreshed: setSession,
        onUnauthorized: logout,
        currentSession: session,
        httpClient,
      }),
    [getSession, setSession, logout, session, httpClient],
  );

  return (
    <QueryClientProvider client={client}>
      <ServiceContext.Provider value={service}>
        {children}
      </ServiceContext.Provider>
    </QueryClientProvider>
  );
};

export const AppProviders = ({ children }: { children: React.ReactNode }) => {
  return (
    <ToastProvider>
      <AuthProvider>
        <InnerProviders>{children}</InnerProviders>
      </AuthProvider>
    </ToastProvider>
  );
};

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
import { createService } from "../services/create-service";

const InnerProviders = ({ children }: { children: React.ReactNode }) => {
  const auth = useAuth();
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

  const service = useMemo(
    () =>
      createService({
        getToken: () => auth.session?.token ?? null,
        onUnauthorized: auth.logout,
        currentSession: auth.session,
      }),
    [auth.session, auth.logout],
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

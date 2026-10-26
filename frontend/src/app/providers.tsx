import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './query-client';
import { AuthProvider, useAuth } from './auth-context';
import { ServiceContext } from '../services/interfaces/service-context';
import { createService } from '../services/create-service';

const InnerProviders = ({ children }: { children: React.ReactNode }) => {
  const auth = useAuth();

  const service = createService({
    getToken: () => auth.session?.token ?? null,
    onUnauthorized: auth.logout,
    currentSession: auth.session,
  });

  return <ServiceContext.Provider value={service}>{children}</ServiceContext.Provider>;
};

export const AppProviders = ({ children }: { children: React.ReactNode }) => {
  return (
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <InnerProviders>{children}</InnerProviders>
      </QueryClientProvider>
    </AuthProvider>
  );
};

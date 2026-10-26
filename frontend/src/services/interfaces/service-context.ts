import { createContext, useContext } from 'react';
import type { LedgerService } from './ledger-service';

export const ServiceContext = createContext<LedgerService | null>(null);

export const useLedgerService = () => {
  const service = useContext(ServiceContext);
  if (!service) {
    throw new Error('Ledger service context is not available');
  }
  return service;
};

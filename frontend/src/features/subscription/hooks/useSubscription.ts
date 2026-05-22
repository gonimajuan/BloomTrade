import { useQuery } from '@tanstack/react-query';
import { getSubscriptionStatus } from '@/features/subscription/api/subscriptionApi';

export const SUBSCRIPTION_QUERY_KEY = ['subscription', 'me'] as const;

interface Options {
  /** Si true, hace polling cada 2s (útil en PremiumSuccessPage mientras llega el webhook). */
  polling?: boolean;
}

export function useSubscription({ polling = false }: Options = {}) {
  return useQuery({
    queryKey: SUBSCRIPTION_QUERY_KEY,
    queryFn: getSubscriptionStatus,
    staleTime: 30_000,
    refetchInterval: polling ? 2_000 : false,
  });
}

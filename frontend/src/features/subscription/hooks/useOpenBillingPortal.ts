import { useMutation } from '@tanstack/react-query';
import { openBillingPortal } from '@/features/subscription/api/subscriptionApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { PortalSessionResponse } from '@/types/api';

/**
 * Abre el Customer Portal de Stripe (HU-F06 v1.2). Al volver al `/premium`, el `useSubscription`
 * se invalida para reflejar los cambios sincronizados por webhook.
 */
export function useOpenBillingPortal() {
  return useMutation<PortalSessionResponse, ParsedError, void>({
    mutationFn: async () => {
      try {
        return await openBillingPortal();
      } catch (err) {
        throw parseError(err);
      }
    },
    onSuccess: (data) => {
      window.location.href = data.portalUrl;
    },
  });
}

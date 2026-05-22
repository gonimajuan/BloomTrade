import { useMutation } from '@tanstack/react-query';
import { createCheckoutSession } from '@/features/subscription/api/subscriptionApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type {
  BillingPlan,
  CheckoutSessionResponse,
} from '@/types/api';

/**
 * Mutación que crea Checkout Session y redirige al usuario a la URL hosted de Stripe.
 *
 * <p>El redirect ocurre en `onSuccess` con `window.location.href` (saliendo de la SPA — Stripe
 * Checkout es una página hosted, no un embed). Al volver, el usuario aterriza en
 * `/premium/success` (success_url) o `/premium/cancel` (cancel_url).
 */
export function useStartCheckout() {
  return useMutation<CheckoutSessionResponse, ParsedError, BillingPlan>({
    mutationFn: async (plan) => {
      try {
        return await createCheckoutSession({ plan });
      } catch (err) {
        throw parseError(err);
      }
    },
    onSuccess: (data) => {
      window.location.href = data.checkoutUrl;
    },
  });
}

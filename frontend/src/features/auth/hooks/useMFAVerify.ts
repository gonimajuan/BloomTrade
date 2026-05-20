import { useMutation } from '@tanstack/react-query';
import { apiClient } from '@/lib/apiClient';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { MfaVerifyRequest, MfaVerifyResponse } from '@/types/api';

/** Mutación del paso 2 (verificación de OTP, spec HU-F02 §6.1.2). */
export function useMFAVerify() {
  return useMutation<MfaVerifyResponse, ParsedError, MfaVerifyRequest>({
    mutationFn: async (payload) => {
      try {
        const { data } = await apiClient.post<MfaVerifyResponse>(
          '/auth/mfa/verify',
          payload,
        );
        return data;
      } catch (err) {
        throw parseError(err);
      }
    },
  });
}

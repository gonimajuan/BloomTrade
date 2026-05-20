import { useMutation } from '@tanstack/react-query';
import { apiClient } from '@/lib/apiClient';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { MfaResendRequest, MfaResendResponse } from '@/types/api';

/** Mutación de reenvío de OTP (spec HU-F02 §6.1.3). */
export function useMFAResend() {
  return useMutation<MfaResendResponse, ParsedError, MfaResendRequest>({
    mutationFn: async (payload) => {
      try {
        const { data } = await apiClient.post<MfaResendResponse>(
          '/auth/mfa/resend',
          payload,
        );
        return data;
      } catch (err) {
        throw parseError(err);
      }
    },
  });
}

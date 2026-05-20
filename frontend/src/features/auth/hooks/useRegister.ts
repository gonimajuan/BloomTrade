import { useMutation } from '@tanstack/react-query';
import { apiClient } from '@/lib/apiClient';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { RegisterRequest, RegisterResponse } from '@/types/api';

/**
 * Mutación de registro (spec HU-F01 §12.3). Devuelve la {@link ParsedError} ya normalizada,
 * para que el componente acceda directamente a code/message/fieldErrors.
 */
export function useRegister() {
  return useMutation<RegisterResponse, ParsedError, RegisterRequest>({
    mutationFn: async (payload) => {
      try {
        const { data } = await apiClient.post<RegisterResponse>(
          '/auth/register',
          payload,
        );
        return data;
      } catch (err) {
        throw parseError(err);
      }
    },
  });
}

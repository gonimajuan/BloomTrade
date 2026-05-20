import { useMutation } from '@tanstack/react-query';
import { apiClient } from '@/lib/apiClient';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { LoginRequest, LoginResponse } from '@/types/api';

/** Mutación del paso 1 de login (spec HU-F02 §6.1.1). */
export function useLogin() {
  return useMutation<LoginResponse, ParsedError, LoginRequest>({
    mutationFn: async (payload) => {
      try {
        const { data } = await apiClient.post<LoginResponse>(
          '/auth/login',
          payload,
        );
        return data;
      } catch (err) {
        throw parseError(err);
      }
    },
  });
}

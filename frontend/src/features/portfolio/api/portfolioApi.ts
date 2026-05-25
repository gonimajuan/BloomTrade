import { apiClient } from '@/lib/apiClient';
import type { BalanceResponse, PortfolioPositionsResponse } from '@/types/api';

/** GET /api/v1/portfolio/balance (HU-F21). */
export async function getBalance(): Promise<BalanceResponse> {
  const { data } = await apiClient.get<BalanceResponse>('/portfolio/balance');
  return data;
}

/** GET /api/v1/portfolio/positions (HU-F16) — incluye pendingOrders y marketDataAvailable. */
export async function getPositions(): Promise<PortfolioPositionsResponse> {
  const { data } = await apiClient.get<PortfolioPositionsResponse>('/portfolio/positions');
  return data;
}

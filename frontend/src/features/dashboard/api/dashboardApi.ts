import { apiClient } from '@/lib/apiClient';
import type { DashboardSnapshotResponse } from '@/types/api';

/** GET /api/v1/dashboard/snapshot (HU-F18). */
export async function getDashboardSnapshot(): Promise<DashboardSnapshotResponse> {
  const { data } = await apiClient.get<DashboardSnapshotResponse>('/dashboard/snapshot');
  return data;
}

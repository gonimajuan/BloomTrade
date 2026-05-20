// Tipos del contrato HTTP HU-F01 — mirror manual de RegisterRequest/Response/ErrorResponse
// del backend. Cuando el backend está arriba: `npm run gen:api` puede generar tipos desde
// /v3/api-docs (STACK.md §3.4); por ahora se mantienen aquí para no acoplar el dev del
// frontend a tener el backend corriendo.

export type DocumentType = 'CC' | 'CE' | 'PASAPORTE';
export type UserRole = 'INVESTOR' | 'BROKER' | 'ADMIN' | 'LEGAL' | 'BOARD';
export type UserStatus = 'ACTIVE' | 'BLOCKED' | 'SUSPENDED';

export interface RegisterRequest {
  email: string;
  password: string;
  nombreCompleto: string;
  tipoDocumento: DocumentType;
  numeroDocumento: string;
  telefono: string;
  aceptaTerminos: boolean;
}

export interface RegisterResponse {
  id: string;
  email: string;
  nombreCompleto: string;
  rol: UserRole;
  estado: UserStatus;
  createdAt: string;
}

export interface FieldErrorItem {
  field: string;
  code: string;
  message: string;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  traceId: string;
  fieldErrors?: FieldErrorItem[];
}

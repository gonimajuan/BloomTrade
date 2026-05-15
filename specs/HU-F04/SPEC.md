# spec.md — Configuración de perfil y preferencias de notificación

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F04 (BT-7 en Jira), HU-F20 (BT-9 en Jira) |
| Sprint | 1 |
| Prioridad MoSCoW | Must |
| Estado | Ready |
| Autor | *[Tu nombre]* |
| Fecha creación | 2026-05-08 |
| Última actualización | 2026-05-08 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 3 |

---

## 2. Historia(s) de usuario

### HU-F04 — Configurar perfil

**Como** inversionista, **quiero** configurar mi perfil con datos editables y preferencias, **para** que el sistema refleje mi información actual y opere según mis preferencias.

### HU-F20 — Configurar notificaciones

**Como** inversionista, **quiero** configurar mi canal preferido de notificaciones (Email, SMS o WhatsApp), **para** recibir alertas del sistema por el medio que más me conviene.

### Resumen unificado

Ambas HUs se bundlean en una **única pantalla de "Mi perfil"** porque comparten el mismo modelo de datos (`User`), el mismo endpoint (`PATCH /me`), y la misma UX (formulario con secciones). El bundle expone dos endpoints (GET y PATCH del recurso `/me`) que permiten al usuario:

1. Consultar todos los datos de su cuenta (campos editables y read-only)
2. Actualizar los campos editables: `nombreCompleto`, `telefono`, `notificationChannel`, `tickersOfInterest`
3. Cancelar la edición sin guardar cambios

---

## 3. Contexto y dependencias

### Por qué importa

Es la primera feature del MVP que **ejercita autenticación real con JWT**. Toda HU posterior asume que este flujo funciona: el JWT se valida correctamente, el usuario actual se identifica por `sub` del token, y los endpoints autenticados rechazan requests sin auth. Además, esta spec habilita la HU-F18 Dashboard al introducir `tickersOfInterest`, sin el cual el dashboard no sabe qué activos mostrar.

### Dependencias técnicas

- **HU-F01 Registrarse** — el usuario debe existir con todos los campos base
- **HU-F02 + HU-F03 Login + MFA** — el usuario debe poder autenticarse y obtener JWT
- **ProtectedRoute, AuthContext, jwtInterceptor** — implementados en HU-F02-F03

### Features que dependen de esta

- **HU-F18 Dashboard** — usa `tickersOfInterest` para saber qué precios mostrar
- **HU-F09 y HU-F10 Trading** — usan `notificationChannel` para enrutar notificaciones de orden
- **Cualquier endpoint autenticado posterior** — usa la misma infraestructura de auth que se ejercita aquí por primera vez

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | INVESTOR (en MVP) | Iniciador (consulta y modifica su propio perfil) |
| Sistema BloomTrade | — | Receptor / procesador |

### Precondiciones del sistema

- El usuario tiene una sesión JWT activa (access token vigente)
- El usuario existe en `app.users` con `estado = ACTIVE`
- La migración Flyway V3 está aplicada (columnas `notification_channel` y `tickers_of_interest` existen)

### Datos requeridos en el sistema

- Configuración Spring Security activa con filtro JWT que valida `Authorization: Bearer {token}`
- Bean `IAuthentication` de AuthService (introducido en HU-F02-F03) registrado en el contexto Spring
- Catálogo de 25 tickers válidos disponible como constante en código (definido en `ARCHITECTURE.md` §1)

---

## 5. Flujos

### 5.1 Flujo principal — consultar y actualizar perfil

#### Paso 1: Consulta del perfil

1. Usuario autenticado navega a `/profile` en el frontend
2. Frontend envía `GET /api/v1/me` con header `Authorization: Bearer {accessToken}` (vía jwtInterceptor)
3. Backend valida el JWT vía `IAuthentication.validateToken()`: firma HS256, `jti` no revocado, `exp` futura
4. Backend extrae `userId` del claim `sub` del JWT
5. Backend consulta `app.users` por ese `userId`
6. Backend retorna 200 con el perfil completo (campos editables + read-only)
7. Frontend mapea la respuesta a estado del formulario y muestra la página

#### Paso 2: Actualización parcial

8. Usuario edita uno o más campos editables (`nombreCompleto`, `telefono`, `notificationChannel`, `tickersOfInterest`)
9. Frontend habilita el botón "Guardar cambios" cuando detecta el form como dirty
10. Usuario presiona "Guardar cambios"
11. Frontend valida campos en cliente con Zod (mismos rules que server-side)
12. Frontend envía `PATCH /api/v1/me` con SOLO los campos modificados (no envía campos sin cambios)
13. Backend valida el JWT (igual que en GET)
14. Backend valida la estructura del request body (Bean Validation): cada campo opcional, si está presente debe cumplir su regla
15. Backend abre transacción
16. Backend consulta el `User` por `userId`
17. Backend aplica los cambios solo en los campos presentes en el payload
18. Backend persiste y commitea
19. Backend determina qué campos cambiaron (comparando entity pre vs post)
20. Backend emite eventos de auditoría:
    - Si cambió cualquier cosa: `PROFILE_UPDATED` con `details.changedFields: ["nombreCompleto", "telefono", ...]`
    - Si cambió específicamente `notificationChannel`: además `NOTIFICATION_CHANNEL_CHANGED` con `details: { from, to }`
21. Backend responde 200 con el perfil completo actualizado
22. Frontend actualiza el estado del formulario con la respuesta y, si cambió `nombreCompleto`, actualiza el `AuthContext` para que el header del app refleje el nuevo nombre
23. Frontend muestra toast verde "Cambios guardados"

**Postcondiciones:**
- Los campos modificados están persistidos en `app.users`
- El `updated_at` de la fila del usuario refleja el momento del cambio
- Evento `PROFILE_UPDATED` indexado en ElasticSearch con la lista de campos modificados (NO los valores)
- Si aplica, evento `NOTIFICATION_CHANNEL_CHANGED` indexado con el cambio explícito
- El frontend tiene el perfil actualizado en memoria

### 5.2 Flujos alternativos

#### 5.2.1 Cancelar edición con cambios sin guardar

**Cuándo se activa:** Usuario edita uno o más campos pero presiona "Cancelar" o navega fuera de la página antes de guardar

1. Frontend detecta intent de navegar fuera o presión del botón Cancelar
2. Frontend verifica si el formulario está dirty
3. Si dirty: muestra modal de confirmación "¿Descartar cambios sin guardar?"
4. Si el usuario confirma "Descartar": frontend resetea el formulario al estado original (de la última respuesta de GET /me) y navega/oculta el formulario
5. Si el usuario cancela el modal: permanece en la página con los cambios pendientes

**Postcondiciones específicas:** No se envía ningún request al backend. El estado persistido no cambia. Cumple el criterio HU-F04 E3.

#### 5.2.2 Actualización sin cambios reales

**Cuándo se activa:** El usuario envía `PATCH /me` con un payload donde los valores son iguales a los actuales (caso raro pero posible si el frontend no compara correctamente)

1. Backend detecta que ningún campo cambió tras aplicar el payload
2. Backend retorna 200 con el perfil sin haber tocado la BD (o tras un no-op COMMIT)
3. NO se emite evento de auditoría `PROFILE_UPDATED`
4. Frontend muestra toast "Sin cambios" (en lugar de "Cambios guardados")

### 5.3 Flujos de error

#### 5.3.1 Request sin autenticación

**Cuándo se dispara:** Header `Authorization` ausente o malformado
**Respuesta:** HTTP 401 Unauthorized con código `AUTH_REQUIRED`
**Estado final:** Sin cambios
**Evento de auditoría:** `ACCESS_DENIED` con `details.reason="AUTH_REQUIRED"`

#### 5.3.2 Access token expirado

**Cuándo se dispara:** El JWT del header tiene `exp` en el pasado
**Respuesta:** HTTP 401 Unauthorized con código `TOKEN_EXPIRED`
**Estado final:** Sin cambios
**Evento de auditoría:** `ACCESS_DENIED` con `details.reason="TOKEN_EXPIRED"`

> El frontend interpreta este código específico y dispara `/refresh` transparentemente vía jwtInterceptor (comportamiento definido en HU-F02-F03 spec §12).

#### 5.3.3 Access token revocado

**Cuándo se dispara:** El `jti` del JWT está en `revoked:{jti}` de Redis (logout previo)
**Respuesta:** HTTP 401 Unauthorized con código `TOKEN_REVOKED`
**Estado final:** Sin cambios
**Evento de auditoría:** `ACCESS_DENIED` con `details.reason="TOKEN_REVOKED"`

#### 5.3.4 Validación de campos falló

**Cuándo se dispara:** Cualquier campo del PATCH viola su regla de validación
**Respuesta:** HTTP 400 Bad Request con código `VALIDATION_FAILED` y `fieldErrors[]` con detalles
**Estado final:** Sin cambios (transacción rollback)
**Evento de auditoría:** No se emite (las validaciones son ruido)

#### 5.3.5 Intento de modificar campo read-only

**Cuándo se dispara:** El payload PATCH incluye `email`, `tipoDocumento`, `numeroDocumento`, `rol`, `estado`, `password`, `id`, `createdAt`, o cualquier otro campo no editable
**Respuesta:** HTTP 400 Bad Request con código `READ_ONLY_FIELD_MODIFIED` y mensaje `"El campo '{campo}' no puede ser modificado desde el perfil"`
**Estado final:** Sin cambios
**Evento de auditoría:** No se emite (intento de validación)

> **Decisión:** Rechazar explícitamente en lugar de ignorar silenciosamente. Razón: si el cliente envía `email` esperando que se actualice, ignorarlo es un bug silencioso. Rechazar es más predecible.

#### 5.3.6 Ticker fuera del catálogo

**Cuándo se dispara:** `tickersOfInterest` contiene un símbolo que no está en los 25 activos permitidos
**Respuesta:** HTTP 400 Bad Request con código `INVALID_TICKER` y mensaje indicando el ticker rechazado
**Estado final:** Sin cambios
**Evento de auditoría:** No se emite

#### 5.3.7 Demasiados tickers seleccionados

**Cuándo se dispara:** `tickersOfInterest` tiene más de 25 elementos
**Respuesta:** HTTP 400 Bad Request con código `TOO_MANY_TICKERS`
**Estado final:** Sin cambios
**Evento de auditoría:** No se emite

#### 5.3.8 Tickers duplicados

**Cuándo se dispara:** `tickersOfInterest` contiene duplicados (ej: `["AAPL", "MSFT", "AAPL"]`)
**Respuesta:** HTTP 400 Bad Request con código `DUPLICATE_TICKERS`
**Estado final:** Sin cambios
**Evento de auditoría:** No se emite

#### 5.3.9 Error técnico

**Cuándo se dispara:** BD inaccesible, error en transacción, error inesperado
**Respuesta:** HTTP 500 Internal Server Error con código `INTERNAL_ERROR`
**Estado final:** Rollback de transacción
**Evento de auditoría:** `PROFILE_UPDATE_FAILED` con `details.reason="TECHNICAL_ERROR"`, `details.errorClass`

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

#### 6.1.1 `GET /api/v1/me`

**Propósito:** Obtener el perfil completo del usuario autenticado.

**Auth requerido:** Sí — `Authorization: Bearer {accessToken}`

```yaml
paths:
  /api/v1/me:
    get:
      summary: Obtiene el perfil completo del usuario autenticado
      tags: [Profile]
      security:
        - bearerAuth: []
      responses:
        '200':
          description: Perfil del usuario autenticado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileResponse'
              example:
                id: "550e8400-e29b-41d4-a716-446655440000"
                email: "juan.perez@example.com"
                nombreCompleto: "Juan Pérez García"
                tipoDocumento: "CC"
                numeroDocumento: "1234567890"
                telefono: "+573001234567"
                rol: "INVESTOR"
                estado: "ACTIVE"
                notificationChannel: "EMAIL"
                tickersOfInterest: ["AAPL", "MSFT", "TSLA"]
                createdAt: "2026-05-08T14:32:18.123Z"
                updatedAt: "2026-05-08T16:45:02.456Z"
        '401':
          description: No autenticado, token expirado o revocado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

#### 6.1.2 `PATCH /api/v1/me`

**Propósito:** Actualizar parcialmente el perfil del usuario autenticado. Solo los campos presentes en el payload se modifican.

**Auth requerido:** Sí

```yaml
paths:
  /api/v1/me:
    patch:
      summary: Actualiza parcialmente el perfil del usuario autenticado
      tags: [Profile]
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UpdateProfileRequest'
            example:
              nombreCompleto: "Juan Carlos Pérez García"
              notificationChannel: "WHATSAPP"
              tickersOfInterest: ["AAPL", "MSFT", "GOOGL", "TSLA"]
      responses:
        '200':
          description: Perfil actualizado exitosamente
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileResponse'
        '400':
          description: Validación falló o intento de modificar campo read-only
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '401':
          description: No autenticado
        '500':
          description: Error técnico

components:
  schemas:
    UserProfileResponse:
      type: object
      required: [id, email, nombreCompleto, tipoDocumento, numeroDocumento, telefono, rol, estado, notificationChannel, tickersOfInterest, createdAt, updatedAt]
      properties:
        id:
          type: string
          format: uuid
          description: Read-only
        email:
          type: string
          format: email
          description: Read-only en MVP
        nombreCompleto:
          type: string
          description: Editable
        tipoDocumento:
          type: string
          enum: [CC, CE, PASAPORTE]
          description: Read-only
        numeroDocumento:
          type: string
          description: Read-only
        telefono:
          type: string
          description: Editable
        rol:
          type: string
          enum: [INVESTOR, BROKER, ADMIN, LEGAL, BOARD]
          description: Read-only
        estado:
          type: string
          enum: [ACTIVE, BLOCKED, SUSPENDED]
          description: Read-only
        notificationChannel:
          type: string
          enum: [EMAIL, SMS, WHATSAPP]
          description: Editable. Default EMAIL.
        tickersOfInterest:
          type: array
          maxItems: 25
          uniqueItems: true
          items:
            type: string
            enum: [AAPL, MSFT, JNJ, JPM, XOM, GOOGL, AMZN, META, TSLA, NVDA, HSBA, BP, GSK, ULVR, BARC, "7203", "6758", "9984", "8306", "6861", BHP, CBA, CSL, WES, WOW]
          description: Editable. Default vacío.
        createdAt:
          type: string
          format: date-time
          description: Read-only
        updatedAt:
          type: string
          format: date-time
          description: Read-only

    UpdateProfileRequest:
      type: object
      description: Todos los campos son opcionales. Solo los presentes serán actualizados (semántica PATCH).
      properties:
        nombreCompleto:
          type: string
          minLength: 3
          maxLength: 100
        telefono:
          type: string
          pattern: '^\+[1-9]\d{1,14}$'
        notificationChannel:
          type: string
          enum: [EMAIL, SMS, WHATSAPP]
        tickersOfInterest:
          type: array
          maxItems: 25
          uniqueItems: true
          items:
            type: string
            enum: [AAPL, MSFT, JNJ, JPM, XOM, GOOGL, AMZN, META, TSLA, NVDA, HSBA, BP, GSK, ULVR, BARC, "7203", "6758", "9984", "8306", "6861", BHP, CBA, CSL, WES, WOW]
```

**Validaciones del PATCH (server-side):**

| Campo | Regla | Error si falla |
|---|---|---|
| `nombreCompleto` | 3-100 chars, no solo whitespace | `VALIDATION_INVALID_NAME` |
| `telefono` | Formato E.164 | `VALIDATION_INVALID_PHONE` |
| `notificationChannel` | Enum estricto | `VALIDATION_INVALID_CHANNEL` |
| `tickersOfInterest` | Array, ≤25 items, sin duplicados, cada elemento en catálogo de 25 | `INVALID_TICKER`, `TOO_MANY_TICKERS`, `DUPLICATE_TICKERS` |
| Cualquier campo read-only presente | El payload contiene `email`, `tipoDocumento`, `numeroDocumento`, `rol`, `estado`, `password`, `id`, `createdAt`, `updatedAt` | `READ_ONLY_FIELD_MODIFIED` |

**Códigos de error específicos del endpoint:**

| Código | HTTP | Cuándo |
|---|---|---|
| `READ_ONLY_FIELD_MODIFIED` | 400 | Payload incluye campo no editable |
| `INVALID_TICKER` | 400 | Ticker no está en el catálogo de 25 |
| `TOO_MANY_TICKERS` | 400 | Más de 25 tickers |
| `DUPLICATE_TICKERS` | 400 | Lista tiene duplicados |
| `VALIDATION_FAILED` | 400 | Genérico de validación de formato |

### 6.2 Endpoints modificados

No aplica.

### 6.3 Esquemas de datos compartidos

`UserProfileResponse` se introduce aquí. Su sub-shape `UserSummary` (id, email, nombreCompleto, rol) ya estaba definido en HU-F02-F03; `UserProfileResponse` es el "fat version" para la pantalla de perfil.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Archivo:** `backend/src/main/resources/db/migration/V3__user_profile_extension.sql`

**Schema afectado:** `app`

### 7.2 Tablas modificadas

#### `app.users` — añadir dos columnas

```sql
ALTER TABLE app.users
    ADD COLUMN notification_channel    VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN tickers_of_interest     JSONB       NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE app.users
    ADD CONSTRAINT chk_users_notification_channel
        CHECK (notification_channel IN ('EMAIL', 'SMS', 'WHATSAPP'));

-- Constraint: tickers_of_interest debe ser array JSON con máximo 25 elementos
ALTER TABLE app.users
    ADD CONSTRAINT chk_users_tickers_count
        CHECK (jsonb_typeof(tickers_of_interest) = 'array'
               AND jsonb_array_length(tickers_of_interest) <= 25);

-- Índice GIN sobre el array para queries futuras del tipo "qué usuarios tienen AAPL"
CREATE INDEX idx_users_tickers_of_interest ON app.users USING GIN (tickers_of_interest);
```

**Justificación de campos:**
- `notification_channel VARCHAR(20)`: longitud suficiente para los tres valores enum. Check constraint asegura integridad a nivel BD.
- `tickers_of_interest JSONB`: alternativa a tabla relacional separada. JSONB ofrece (1) consultas eficientes con operadores `@>`, (2) índice GIN para búsquedas de tipo "qué usuarios tienen X ticker", (3) menos joins en queries de perfil. Para 25 elementos máximo, JSONB es la opción correcta de modelado.
- `DEFAULT 'EMAIL'` para canal: todos los usuarios existentes (post-migration) tendrán EMAIL como canal por defecto. Cumple con el comportamiento esperado: registro auto-asigna EMAIL.
- `DEFAULT '[]'::jsonb` para tickers: los usuarios pre-existentes tendrán lista vacía. El frontend del dashboard maneja este caso con placeholder "Configura tus intereses".

**Justificación de constraints e índice:**
- `chk_users_notification_channel`: defensa en profundidad. La aplicación valida el enum, pero la BD también — protección contra inserts directos o bugs de mapper.
- `chk_users_tickers_count`: protección contra crecimiento descontrolado. Si por bug el código permite >25, la BD rechaza.
- `idx_users_tickers_of_interest GIN`: necesario para HU-F19 Configurar alertas de precio (post-MVP) que necesitaría buscar "usuarios interesados en ticker X". Crearlo ahora es trivial; crearlo después de tener 10k usuarios sería pesado.

### 7.3 Datos semilla

No aplica.

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

| Módulo | Rol | Componentes específicos tocados |
|---|---|---|
| AuthService | Iniciador | `MeController`, `ProfileService`, `UserRepository`, `UserProfileMapper`, `IAuthentication` (validación de token) |
| AuditService | Notificado | `AuditLogger` (eventos `PROFILE_UPDATED`, `NOTIFICATION_CHANNEL_CHANGED`, `ACCESS_DENIED`, `PROFILE_UPDATE_FAILED`) |

> **Nota arquitectónica:** La gestión de perfil vive dentro de AuthService porque el entity `User` y su persistencia son responsabilidad de ese módulo (definido al crear `app.users` en HU-F01). En un futuro post-MVP donde se separen los módulos en servicios independientes, una posible refactorización sería extraer un `UserManagementService` dedicado, pero para MVP mantener la cohesión en AuthService es lo más simple y correcto.

### 8.2 Interfaces consumidas

| Interfaz | Módulo que la expone | Para qué se usa aquí |
|---|---|---|
| `IAuthentication` | AuthService (introducida en HU-F02-F03 §8.3) | Validar el JWT en cada request y resolver el `userId` del usuario autenticado |
| `IAudit` | AuditService | Emitir eventos de auditoría de cambios de perfil |

### 8.3 Interfaces expuestas

Ninguna nueva. Los endpoints son HTTP REST consumidos directamente por el frontend.

### 8.4 Tácticas de Bass aplicadas

| Táctica | ID | Cómo se materializa en esta feature |
|---|---|---|
| Autorizar actores | TAC-S2 | `IAuthentication.validateToken()` garantiza que solo el usuario dueño del JWT puede modificar su perfil. No hay forma de modificar perfil ajeno desde `/me`. |
| Mantener registro de auditoría | TAC-S4 | Eventos `PROFILE_UPDATED` y `NOTIFICATION_CHANNEL_CHANGED` emitidos. Cambios en datos del usuario son trazables forensemente. |
| Encapsular | TAC-M3 | Lógica de actualización parcial encapsulada en `ProfileService`. Catálogo de 25 tickers encapsulado en constante `AllowedTickers` reutilizable. |

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

| `event_type` | Trigger | Campos extra en `details` |
|---|---|---|
| `PROFILE_UPDATED` | Cualquier cambio efectivo en el perfil | `{ changedFields: ["nombreCompleto", "telefono", ...] }` — solo los nombres de los campos, NO los valores (PII) |
| `NOTIFICATION_CHANNEL_CHANGED` | Cambio específico del campo `notificationChannel` | `{ from: "EMAIL", to: "WHATSAPP" }` — sí incluye valores porque son enums no-PII |
| `PROFILE_UPDATE_FAILED` | Error técnico durante actualización | `{ reason: "TECHNICAL_ERROR", errorClass }` |
| `ACCESS_DENIED` | Request a `/me` sin auth válida | `{ reason: "AUTH_REQUIRED" \| "TOKEN_EXPIRED" \| "TOKEN_REVOKED" }` |

> **GET /me NO se audita.** El usuario consultando su propio perfil es una operación de lectura propia que generaría ruido masivo en el log de auditoría sin valor forense. Si en el futuro hay requerimiento regulatorio de auditar lecturas, se puede agregar.

### 9.2 Notificaciones

No aplica. La actualización del perfil **no dispara notificaciones al usuario** en el MVP. Razones:
- El usuario sabe lo que cambió (lo acaba de cambiar él mismo)
- Notificar cambios propios genera ruido en MailHog
- Si en producción se requiere por seguridad ("notifica cuando se cambia el teléfono"), se agrega post-MVP

### 9.3 Cambios en caché Redis

No aplica. El perfil se lee directamente de la BD en cada request.

> **Decisión de no usar caché:** Aunque GET /me se ejecuta frecuentemente, no se cachea en MVP porque (1) las invalidaciones tras PATCH agregan complejidad sin beneficio claro a escala MVP, (2) la lectura de una fila de `app.users` por PK es trivialmente rápida en PostgreSQL.

### 9.4 Llamadas a APIs externas

No aplica. Esta feature no consume Alpaca, Polygon, Stripe ni Twilio.

> **Nota importante:** Cambiar `notificationChannel` a SMS o WHATSAPP **no dispara verificación** del número (decisión registrada al inicio). El cambio se persiste y los próximos envíos de notificación irán por ese canal. Si el número es inválido o Twilio falla, eso se reflejará en futuros envíos (eventos `NOTIFICATION_FAILED` se manejarán en NotificationService cuando aplique).

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Configuración de perfil y preferencias de notificación

  Antecedentes:
    Dado que existe un usuario autenticado con perfil:
      | campo                | valor                       |
      | email                | juan@example.com            |
      | nombreCompleto       | Juan Pérez                  |
      | tipoDocumento        | CC                          |
      | numeroDocumento      | 1234567890                  |
      | telefono             | +573001234567               |
      | rol                  | INVESTOR                    |
      | estado               | ACTIVE                      |
      | notificationChannel  | EMAIL                       |
      | tickersOfInterest    | []                          |

  Escenario: Consulta del perfil autenticado
    Cuando el usuario envía GET /api/v1/me con su access token
    Entonces el sistema responde 200
    Y el cuerpo incluye todos los campos del perfil, incluidos los read-only
    Y NO se emite evento de auditoría (lecturas propias no se auditan)

  Escenario: Actualización de un solo campo editable
    Cuando el usuario envía PATCH /api/v1/me con body { "nombreCompleto": "Juan Carlos Pérez" }
    Entonces el sistema responde 200 con el perfil actualizado
    Y app.users tiene nombreCompleto="Juan Carlos Pérez"
    Y los demás campos permanecen sin cambio
    Y se emite PROFILE_UPDATED con details.changedFields=["nombreCompleto"]
    Y NO se emite NOTIFICATION_CHANNEL_CHANGED

  Escenario: Cambio de canal de notificación
    Cuando el usuario envía PATCH /api/v1/me con body { "notificationChannel": "WHATSAPP" }
    Entonces el sistema responde 200
    Y app.users tiene notification_channel="WHATSAPP"
    Y se emiten DOS eventos: PROFILE_UPDATED con changedFields=["notificationChannel"]
      Y NOTIFICATION_CHANNEL_CHANGED con from="EMAIL", to="WHATSAPP"

  Escenario: Actualización combinada de varios campos
    Cuando el usuario envía PATCH /api/v1/me con body:
      | campo                | valor                              |
      | nombreCompleto       | Juan Carlos Pérez                  |
      | telefono             | +573109876543                      |
      | notificationChannel  | SMS                                |
      | tickersOfInterest    | ["AAPL","MSFT","GOOGL"]            |
    Entonces el sistema responde 200 con el perfil actualizado
    Y los 4 campos están persistidos
    Y se emite PROFILE_UPDATED con changedFields=["nombreCompleto","telefono","notificationChannel","tickersOfInterest"]
    Y se emite NOTIFICATION_CHANNEL_CHANGED con from="EMAIL", to="SMS"

  Escenario: PATCH sin cambios efectivos
    Cuando el usuario envía PATCH /api/v1/me con body { "nombreCompleto": "Juan Pérez" }
    Y "Juan Pérez" es exactamente el valor actual
    Entonces el sistema responde 200 con el perfil sin cambios
    Y NO se emite PROFILE_UPDATED

  Escenario: Intento de modificar campo read-only
    Cuando el usuario envía PATCH /api/v1/me con body { "email": "otro@example.com" }
    Entonces el sistema responde 400 con código READ_ONLY_FIELD_MODIFIED
    Y el mensaje indica "El campo 'email' no puede ser modificado desde el perfil"
    Y app.users.email permanece sin cambio
    Y NO se emite evento de auditoría

  Escenario: Intento de cambiar rol
    Cuando el usuario envía PATCH /api/v1/me con body { "rol": "ADMIN" }
    Entonces el sistema responde 400 con código READ_ONLY_FIELD_MODIFIED
    Y app.users.rol permanece "INVESTOR"

  Escenario: Cancelación de edición con cambios sin guardar
    Dado que el usuario está en /profile y ha editado nombreCompleto pero NO ha guardado
    Cuando presiona el botón "Cancelar"
    Entonces el frontend muestra modal "¿Descartar cambios sin guardar?"
    Y al confirmar "Descartar", el formulario se resetea a los valores originales
    Y NO se envía request al backend
    Y app.users permanece sin cambios

  Escenario: Request sin autenticación
    Cuando se envía GET /api/v1/me sin header Authorization
    Entonces el sistema responde 401 con código AUTH_REQUIRED
    Y se emite ACCESS_DENIED con reason="AUTH_REQUIRED"

  Escenario: Request con access token expirado
    Dado un access token cuyo exp ya pasó
    Cuando se envía GET /api/v1/me con ese token
    Entonces el sistema responde 401 con código TOKEN_EXPIRED
    Y se emite ACCESS_DENIED con reason="TOKEN_EXPIRED"

  Escenario: Selección válida de tickers de interés
    Cuando el usuario envía PATCH /api/v1/me con body { "tickersOfInterest": ["AAPL","TSLA","BP","7203","BHP"] }
    Entonces el sistema responde 200
    Y app.users.tickers_of_interest = ["AAPL","TSLA","BP","7203","BHP"]
    Y se emite PROFILE_UPDATED con changedFields=["tickersOfInterest"]

  Escenario: Limpiar lista de tickers
    Dado que tickersOfInterest actual es ["AAPL","MSFT"]
    Cuando el usuario envía PATCH /api/v1/me con body { "tickersOfInterest": [] }
    Entonces el sistema responde 200
    Y app.users.tickers_of_interest = []

  Esquema del escenario: Validación de tickers
    Cuando se envía PATCH /api/v1/me con body { "tickersOfInterest": <valor> }
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor                                              | httpStatus | errorCode          |
      | ["AAPL", "INVALID"]                                | 400        | INVALID_TICKER     |
      | ["AAPL", "AAPL"]                                   | 400        | DUPLICATE_TICKERS  |
      | ["aapl"]                                           | 400        | INVALID_TICKER     |
      | (26 tickers válidos)                               | 400        | TOO_MANY_TICKERS   |
      | "AAPL"                                             | 400        | VALIDATION_FAILED  |

  Esquema del escenario: Validación de teléfono
    Cuando se envía PATCH /api/v1/me con body { "telefono": <valor> }
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor          | httpStatus | errorCode                |
      | "3001234567"   | 400        | VALIDATION_INVALID_PHONE |
      | "+0123456789"  | 400        | VALIDATION_INVALID_PHONE |
      | "+573001234"   | 200        | (none)                   |

  Esquema del escenario: Validación de notificationChannel
    Cuando se envía PATCH /api/v1/me con body { "notificationChannel": <valor> }
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor         | httpStatus | errorCode                  |
      | "EMAIL"       | 200        | (none)                     |
      | "SMS"         | 200        | (none)                     |
      | "WHATSAPP"    | 200        | (none)                     |
      | "PUSH"        | 400        | VALIDATION_INVALID_CHANNEL |
      | "email"       | 400        | VALIDATION_INVALID_CHANNEL |
      | (vacío)       | 400        | VALIDATION_REQUIRED        |
```

### 11.2 Criterios no funcionales verificables

| Criterio | Medida | Cómo se verifica |
|---|---|---|
| GET /me responde en <200ms p95 | 100 requests autenticadas | JMeter o `time curl` |
| PATCH /me responde en <300ms p95 | 100 requests autenticadas | JMeter |
| El `password_hash` NUNCA aparece en la respuesta de GET /me | Inspección del JSON | Manual + test automatizado |
| Los valores de `nombreCompleto` y `telefono` NUNCA aparecen en eventos de auditoría | Búsqueda en Kibana de valores específicos de un usuario de test tras múltiples PROFILE_UPDATED | Inspección |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/profile`

**Propósito:** Vista única donde el usuario consulta y edita su perfil completo (datos personales + canal de notificación + tickers de interés).

**Acceso:** Protegida — solo usuarios autenticados. Usa `<ProtectedRoute>` (introducido en HU-F02-F03).

**Componente principal:** `ProfilePage.tsx`

**Estructura visual** (scroll vertical, sin tabs):

```
┌────────────────────────────────────────────────────┐
│ Header del app (con AppHeader de HU-F02-F03)       │
├────────────────────────────────────────────────────┤
│                                                    │
│ Mi perfil                                          │
│                                                    │
│ ── Información personal ──                         │
│ Email:               juan@example.com  [read-only] │
│ Nombre completo:     [_________________________]   │
│ Tipo de documento:   CC [read-only]                │
│ Número de documento: 1234567890 [read-only]        │
│ Teléfono:            [+573001234567____________]   │
│                                                    │
│ ── Preferencias de notificación ──                 │
│ Canal preferido:                                   │
│   ( ) Email                                        │
│   ( ) SMS                                          │
│   (•) WhatsApp                                     │
│                                                    │
│ ── Mercados y acciones de interés ──               │
│ Selecciona las acciones para tu dashboard          │
│ (máximo 25, mínimo 1 para activar dashboard)       │
│                                                    │
│ NYSE                                               │
│ [✓] AAPL  [✓] MSFT  [ ] JNJ  [ ] JPM  [ ] XOM     │
│ NASDAQ                                             │
│ [✓] GOOGL [✓] AMZN  [ ] META [✓] TSLA [ ] NVDA    │
│ ... etc para LSE, TSE, ASX                         │
│                                                    │
│ 5 de 25 seleccionados                              │
│                                                    │
│                  [Cancelar] [Guardar cambios]      │
└────────────────────────────────────────────────────┘
```

**Elementos visibles:**

| Elemento | Tipo | Comportamiento |
|---|---|---|
| Email | Read-only display | Color gris, label "no editable" pequeño debajo |
| Nombre completo | Input text | Editable, validación on blur |
| Tipo y número de documento | Read-only display | Gris |
| Teléfono | Phone input | Editable, formato E.164, prefijo +57 default |
| Canal de notificación | Radio button group | Selección excluyente entre 3 opciones |
| Tickers grid | Checkbox group agrupado por mercado | Multi-selección, hasta 25 |
| Contador "X de 25 seleccionados" | Texto dinámico | Se actualiza al marcar/desmarcar |
| Botón "Guardar cambios" | Submit primary | Habilitado solo si form is dirty AND válido. Spinner durante submit. |
| Botón "Cancelar" | Secondary | Si form dirty → muestra modal de confirmación. Si no dirty → no hace nada (o navega fuera). |

**Estados de la página:**

| Estado | Trigger | UI resultante |
|---|---|---|
| Loading inicial | GET /me en progreso | Skeleton de los campos |
| Idle clean | Form cargado, sin cambios | Botón "Guardar" deshabilitado |
| Idle dirty | Form con cambios sin guardar | Botón "Guardar" habilitado, bordes de campos modificados resaltados |
| Validating | Usuario completando campos | Mensajes de validación inline |
| Submitting | PATCH /me en progreso | Botón con spinner "Guardando...", form deshabilitado |
| Success | PATCH respondió 200 | Toast verde "Cambios guardados", form vuelve a estado clean con datos actualizados |
| Error 400 con fieldErrors | Validación server-side falló | Errores específicos en campos correspondientes |
| Error 400 READ_ONLY_FIELD_MODIFIED | Bug del frontend que envió campo read-only | Banner rojo, mensaje técnico (no debería pasar en condiciones normales) |
| Error 401 | Token expirado durante el flujo | jwtInterceptor maneja refresh transparente; si falla, redirección a /login |
| Error 500 | Error técnico backend | Banner rojo "Error al guardar. Por favor intenta de nuevo." |
| Modal "Descartar cambios" | Usuario cancela con form dirty | Modal con "Descartar" y "Seguir editando" |

### 12.2 Componentes nuevos a crear

| Componente | Ubicación | Propósito |
|---|---|---|
| `ProfilePage` | `src/pages/ProfilePage.tsx` | Página completa, orquesta secciones |
| `PersonalInfoSection` | `src/features/profile/components/PersonalInfoSection.tsx` | Sección Información personal |
| `NotificationChannelSection` | `src/features/profile/components/NotificationChannelSection.tsx` | Sección de canal preferido (radio group) |
| `TickersOfInterestSection` | `src/features/profile/components/TickersOfInterestSection.tsx` | Sección con grid agrupado por mercado |
| `MarketTickerGroup` | `src/features/profile/components/MarketTickerGroup.tsx` | Sub-componente: un mercado con sus 5 checkboxes |
| `DiscardChangesModal` | `src/components/DiscardChangesModal.tsx` | Modal genérico de confirmación de descarte (reutilizable) |

### 12.3 Hooks o utilidades nuevas

| Item | Ubicación | Propósito |
|---|---|---|
| `useProfile` | `src/features/profile/hooks/useProfile.ts` | React Query: fetch del perfil con `useQuery('profile', fetchMe)` |
| `useUpdateProfile` | `src/features/profile/hooks/useUpdateProfile.ts` | React Query: mutation para PATCH /me. Invalida cache de profile en success. |
| `updateProfileSchema` | `src/features/profile/schemas/updateProfile.ts` | Zod schema parcial para validación client-side |
| `useDirtyForm` | `src/hooks/useDirtyForm.ts` | Hook genérico: detecta cambios entre values inicial y actual del form. Reutilizable. |
| `useDiscardChangesPrompt` | `src/hooks/useDiscardChangesPrompt.ts` | Hook: registra el form como "dirty" y maneja el prompt de descarte al navegar fuera |
| `ALLOWED_TICKERS` | `src/constants/tickers.ts` | Constante con los 25 tickers agrupados por mercado, usado por la UI del grid |

### 12.4 Cambios de routing

| Ruta | Componente | Acceso |
|---|---|---|
| `/profile` | `ProfilePage` | Protegida con `ProtectedRoute` |

Agregar link a `/profile` en `AppHeader` (introducido en HU-F02-F03) — botón con avatar/inicial del usuario que despliega menú: "Mi perfil", "Cerrar sesión".

---

## 13. Fuera de alcance de esta spec

- **Cambio de email** — implica re-verificación; fuera del MVP
- **Cambio de password desde perfil** — diferido a post-MVP junto con HU-F08 Recuperar contraseña
- **Cambio de tipo o número de documento** — requiere proceso KYC; fuera del MVP
- **Verificación de número de teléfono al cambiarlo** — sin verificación en MVP (decisión registrada al inicio)
- **Notificación al usuario cuando se cambian sus propios datos** — fuera del MVP (decisión registrada)
- **Múltiples canales de notificación simultáneos** — solo uno en MVP (decisión registrada)
- **Configuración de tipo de orden predeterminado** (mencionado en PDF §perfil) — fuera del MVP, post-MVP
- **Configuración de visualización del portafolio** (gráfico vs lista) — fuera del MVP, post-MVP
- **Watchlist con alertas** — HU-F23 (premium), post-MVP
- **Alertas de precio configurables** — HU-F19 (premium), post-MVP
- **Configuración de seguridad adicional para órdenes grandes** (mencionado en PDF) — fuera del MVP
- **Selección de comisionista** — HU-F05, post-MVP
- **Eliminación o desactivación de cuenta** — fuera del MVP
- **Audit log visible al usuario** ("ve quién accedió a tu cuenta") — fuera del MVP

---

## 14. Preguntas abiertas

Ninguna. Todas las decisiones críticas resueltas previo a la redacción de esta spec.

---

## 15. Definition of Done específica de esta spec

- ☐ Migración Flyway `V3__user_profile_extension.sql` creada y aplicada
- ☐ Endpoints `GET /me` y `PATCH /me` documentados en Swagger UI con todos los códigos de respuesta
- ☐ Todos los escenarios Gherkin de §11 traducidos a tests automatizados pasando
- ☐ Tests verifican que GET /me **nunca** devuelve `password_hash`
- ☐ Tests verifican que los valores PII (nombre, teléfono) **nunca** aparecen en eventos de auditoría
- ☐ Eventos `PROFILE_UPDATED` y `NOTIFICATION_CHANNEL_CHANGED` verificables en Kibana
- ☐ Página `/profile` accesible en `http://localhost:5173/profile` solo con sesión activa
- ☐ Acceso sin auth a `/profile` redirige a `/login` (cumplimiento de `ProtectedRoute`)
- ☐ Modal "Descartar cambios" funciona al cancelar con form dirty
- ☐ Grid de 25 tickers agrupado por mercado, con contador "X de 25"
- ☐ Constante `ALLOWED_TICKERS` definida en frontend y validación equivalente en backend (single source of truth conceptual: ambos derivan del catálogo de `ARCHITECTURE.md` §1)
- ☐ Tras un PATCH exitoso, el header del app refleja inmediatamente el nuevo `nombreCompleto` (via actualización del AuthContext)
- ☐ La pantalla se demuestra funcionalmente con `docker compose up`: login → /profile → editar → guardar → ver cambios persistidos

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-08 | Versión inicial | Tercera spec del MVP (bundle HU-F04 + HU-F20) |

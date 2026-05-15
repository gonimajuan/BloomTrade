# spec.md — Suscripción a plan premium con Stripe

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F06 (BT-10 en Jira) |
| Sprint | 1 |
| Prioridad MoSCoW | Must |
| Estado | Ready |
| Autor | *[Tu nombre]* |
| Fecha creación | 2026-05-08 |
| Última actualización | 2026-05-08 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 4 |

---

## 2. Historia(s) de usuario

### HU-F06 — Suscribirse a plan premium

**Como** inversionista, **quiero** suscribirme a un plan premium (mensual o anual), **para** acceder a funcionalidades adicionales del sistema.

### Resumen del alcance

Esta spec cubre el **ciclo de vida completo de una suscripción**:
1. Activación vía Stripe Checkout Session (USD 12/mes o USD 120/año)
2. Consulta del estado actual de suscripción
3. Cancelación programada (mantiene acceso hasta fin del período)
4. Procesamiento de eventos asíncronos vía webhooks de Stripe (4 tipos)
5. Downgrade automático en caso de fallo de pago en renovación

> **Sobre las funcionalidades premium:** la activación de suscripción habilita el estado `isPremium=true`, pero las features reales que dependen de premium (HU-F19 alertas de precio, HU-F23 watchlist) son post-MVP. Esta spec persiste y gestiona el estado; el *enforcement* en endpoints específicos se construirá cuando las features se implementen.

---

## 3. Contexto y dependencias

### Por qué importa

Es la primera integración real con API externa del MVP y la única del Sprint 1. Materializa TAC-M1 (intermediario) y TAC-I2 (adaptar la interfaz) vía `StripeAdapter`. También es la primera vez que el sistema procesa webhooks (operación asíncrona con consideraciones de idempotencia y seguridad por firma).

### Dependencias técnicas

- **HU-F02 + HU-F03** — el usuario debe estar autenticado para iniciar checkout
- **HU-F04 + HU-F20** — el `notificationChannel` se respeta para emails de suscripción
- **Día 0 (Bootstrap)** — Stripe CLI configurado para forwarding de webhooks locales (`stripe listen`); variables `STRIPE_SECRET_KEY`, `STRIPE_PRICE_MONTHLY`, `STRIPE_PRICE_YEARLY`, `STRIPE_WEBHOOK_SECRET` configuradas

### Features que dependen de esta

- **HU-F19 Configurar alertas de precio** (post-MVP) — requiere `isPremium=true`
- **HU-F23 Configurar Watchlist** (post-MVP) — requiere `isPremium=true`
- **HU-F32 Reportes empresariales** (post-MVP) — usa data de suscripciones para revenue

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | INVESTOR | Iniciador de checkout y cancelación |
| Stripe | Externo | Iniciador de webhooks |
| Sistema BloomTrade | — | Receptor / procesador |

### Precondiciones del sistema

- Usuario tiene sesión JWT activa
- Usuario existe en `app.users` con `estado = ACTIVE`
- Si crea checkout: usuario NO debe tener una suscripción con `status = ACTIVE`
- Stripe Test Mode configurado, dos `Price` IDs (mensual y anual) creados en el Stripe Dashboard
- `STRIPE_WEBHOOK_SECRET` configurado en variables de entorno
- Para desarrollo local: `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe` corriendo en una terminal

### Datos requeridos en el sistema

- Migración Flyway V4 aplicada (columnas `stripe_customer_id` en `users`, tablas `subscriptions` y `stripe_webhook_events`)
- Plantillas de email creadas: `welcome-premium.html`, `subscription-scheduled-to-cancel.html`, `subscription-expired.html`, `subscription-payment-failed.html`

---

## 5. Flujos

### 5.1 Flujo principal — activación de suscripción

#### Paso 1: Crear Checkout Session

1. Usuario autenticado navega a `/premium`
2. Frontend hace `GET /api/v1/subscriptions/me` para verificar estado actual
3. Frontend muestra dos cards de plan (mensual y anual) con botones "Activar"
4. Usuario presiona "Activar plan mensual" (o anual)
5. Frontend envía `POST /api/v1/subscriptions/checkout-session` con body `{ plan: "MONTHLY" }`
6. Backend valida JWT y resuelve `userId`
7. Backend consulta `app.subscriptions` por `user_id` con status `ACTIVE`
8. Si encuentra una activa → devuelve 409 `SUBSCRIPTION_ALREADY_ACTIVE`
9. Backend consulta `app.users.stripe_customer_id` del usuario
10. Si es `NULL`: backend invoca `StripeAdapter.createCustomer(email, nombreCompleto)` → recibe `cus_xxx`
11. Backend persiste `stripe_customer_id = "cus_xxx"` en `app.users` (en transacción)
12. Backend invoca `StripeAdapter.createCheckoutSession()` con:
    - `customer = stripe_customer_id`
    - `mode = "subscription"`
    - `line_items = [{ price: STRIPE_PRICE_MONTHLY, quantity: 1 }]` (o YEARLY según el plan)
    - `success_url = "{FRONTEND_URL}/premium/success?session_id={CHECKOUT_SESSION_ID}"`
    - `cancel_url = "{FRONTEND_URL}/premium/cancel"`
    - `metadata = { userId, plan }`
13. Stripe responde con `Session` que incluye `url` (la URL hosted del checkout)
14. Backend emite evento `CHECKOUT_SESSION_CREATED` a AuditService
15. Backend responde 200 con `{ checkoutUrl: session.url, sessionId: session.id }`
16. Frontend redirige al usuario a `checkoutUrl` (saliendo de la app temporalmente)

#### Paso 2: Usuario completa el pago en Stripe

17. Usuario llena la tarjeta de prueba `4242 4242 4242 4242` en la página de Stripe
18. Stripe procesa el pago y crea: `Customer` (si no existía), `Subscription`, `Invoice`, `PaymentIntent`
19. Stripe redirige al usuario a `success_url` (frontend recibe el `session_id` en query param)
20. **Asíncronamente y en paralelo**, Stripe envía webhook `checkout.session.completed` al backend

#### Paso 3: Backend procesa el webhook (asíncrono)

21. Stripe hace `POST /api/v1/webhooks/stripe` con el evento `checkout.session.completed`
22. Backend lee header `Stripe-Signature` y body raw
23. Backend valida firma usando `STRIPE_WEBHOOK_SECRET` (vía librería oficial `stripe-java`)
24. Backend intenta INSERT en `app.stripe_webhook_events` con `stripe_event_id` único
25. Si ya existía (unique violation) → backend responde 200 inmediatamente, emite `STRIPE_WEBHOOK_DUPLICATE`
26. Backend abre transacción
27. Backend extrae del evento: `subscription_id`, `customer_id`, `metadata.userId`, `metadata.plan`
28. Backend hace `StripeAdapter.retrieveSubscription(subscription_id)` para obtener detalles completos (current_period_start, current_period_end, status)
29. Backend INSERT en `app.subscriptions`:
    - `user_id`, `stripe_customer_id`, `stripe_subscription_id`
    - `plan = "MONTHLY"` (de metadata)
    - `status = "ACTIVE"`
    - `current_period_start`, `current_period_end` (de Stripe)
    - `cancel_at_period_end = false`
30. Backend actualiza estado del webhook event a `PROCESSED` en `app.stripe_webhook_events`
31. Backend hace COMMIT
32. Backend dispara email "Bienvenido a Premium" al usuario (canal según preferencia)
33. Backend emite evento `SUBSCRIPTION_ACTIVATED` a AuditService
34. Backend responde 200 a Stripe (sin body)

#### Paso 4: Frontend confirma con backend

35. La página `/premium/success?session_id=cs_xxx` carga
36. Frontend hace `GET /api/v1/subscriptions/me` para obtener el estado actualizado
37. Si la respuesta indica `status = ACTIVE`: frontend muestra "¡Bienvenido a Premium!" + redirección a `/dashboard` tras 3s
38. Si la respuesta indica `status = NONE` (webhook aún no procesado): frontend muestra "Procesando tu pago, esto puede tardar unos segundos..." con polling cada 2s hasta detectar `ACTIVE` o timeout de 30s

**Postcondiciones:**
- Fila nueva en `app.subscriptions` con `status = ACTIVE`
- `app.users.stripe_customer_id` poblado para el usuario
- Fila en `app.stripe_webhook_events` con `status = PROCESSED`
- Eventos en ElasticSearch: `CHECKOUT_SESSION_CREATED`, `STRIPE_WEBHOOK_RECEIVED`, `SUBSCRIPTION_ACTIVATED`
- Email de bienvenida visible en MailHog (o canal preferido del usuario)
- `GET /api/v1/subscriptions/me` devuelve la suscripción activa
- `isPremium = true` para el usuario

### 5.2 Flujos alternativos

#### 5.2.1 Cancelación de suscripción (programada)

**Cuándo se activa:** Usuario presiona "Cancelar suscripción" en `/premium`

1. Frontend muestra modal de confirmación: "Tu acceso premium continúa hasta {currentPeriodEnd}. ¿Confirmas la cancelación?"
2. Usuario confirma
3. Frontend envía `POST /api/v1/subscriptions/cancel`
4. Backend valida JWT y resuelve `userId`
5. Backend consulta `app.subscriptions` por `user_id` con `status = ACTIVE`
6. Si no encuentra: devuelve 404 `NO_ACTIVE_SUBSCRIPTION`
7. Backend invoca `StripeAdapter.cancelSubscriptionAtPeriodEnd(stripe_subscription_id)`
8. Stripe marca `cancel_at_period_end = true` y responde con `Subscription` actualizada
9. Backend actualiza `app.subscriptions`: `cancel_at_period_end = true` (status permanece ACTIVE)
10. Backend dispara email "Tu suscripción se cancelará el {currentPeriodEnd}"
11. Backend emite evento `SUBSCRIPTION_CANCELLED_SCHEDULED` a AuditService
12. Backend responde 200 con la suscripción actualizada

**Asíncronamente:** Stripe envía webhook `customer.subscription.updated` (manejado en §5.2.2)

**Postcondiciones:**
- `cancel_at_period_end = true` en `app.subscriptions`
- `status` permanece `ACTIVE` — el usuario mantiene acceso premium hasta `currentPeriodEnd`
- Email de confirmación enviado

#### 5.2.2 Procesamiento de webhook `customer.subscription.updated`

**Cuándo se activa:** Stripe envía este webhook tras cualquier cambio de estado de la suscripción (cancelación programada, cambio de plan futuro, etc.)

1. Backend recibe webhook, valida firma, verifica idempotencia (igual que §5.1 pasos 22-26)
2. Backend extrae `subscription_id` y campos relevantes del evento
3. Backend consulta `app.subscriptions` por `stripe_subscription_id`
4. Si no encuentra: emite `STRIPE_WEBHOOK_ORPHAN` (logueado, no error fatal) y responde 200
5. Backend actualiza la fila con los datos del evento:
    - `status` (mapea del status de Stripe al enum interno)
    - `cancel_at_period_end`
    - `current_period_end` (si cambió)
6. Backend detecta transición de interés:
    - Si `cancel_at_period_end` pasó de `false` a `true`: emite `SUBSCRIPTION_CANCELLED_SCHEDULED` (idempotente — pudo haber sido emitido ya por el endpoint de cancelación; el audit log acepta duplicados con diferentes timestamps)
    - Si pasó de `true` a `false`: usuario revirtió la cancelación (caso raro, no manejado en UI MVP)
7. Backend hace COMMIT y responde 200 a Stripe

#### 5.2.3 Procesamiento de webhook `customer.subscription.deleted`

**Cuándo se activa:** Stripe envía este webhook cuando la suscripción termina definitivamente (post `cancel_at_period_end` cuando llega el período, o por otras razones)

1. Backend recibe webhook, valida firma, idempotencia
2. Backend extrae `subscription_id`
3. Backend consulta `app.subscriptions` por `stripe_subscription_id`
4. Backend actualiza `status = CANCELLED`
5. Backend dispara email "Tu suscripción premium ha terminado"
6. Backend emite evento `SUBSCRIPTION_TERMINATED`
7. Backend responde 200

**Postcondiciones:**
- Suscripción en estado terminal `CANCELLED`
- `isPremium` ahora computa como `false`
- El usuario puede iniciar una nueva suscripción (resubscribirse)

#### 5.2.4 Procesamiento de webhook `invoice.payment_failed`

**Cuándo se activa:** Una renovación automática de Stripe falla (tarjeta vencida, fondos insuficientes, etc.)

1. Backend recibe webhook, valida firma, idempotencia
2. Backend extrae `subscription_id` del invoice
3. Backend consulta `app.subscriptions`
4. Backend actualiza `status = PAST_DUE` (downgrade inmediato — sin grace period en MVP)
5. Backend dispara email "Tu pago falló — re-suscríbete para mantener acceso premium"
6. Backend emite evento `SUBSCRIPTION_PAYMENT_FAILED`
7. Backend responde 200

**Postcondiciones:**
- Suscripción en estado terminal `PAST_DUE`
- `isPremium = false`
- El usuario puede iniciar una nueva suscripción

#### 5.2.5 Resubscripción tras cancelación o fallo

**Cuándo se activa:** Usuario con suscripción en estado `CANCELLED` o `PAST_DUE` quiere volver a suscribirse

- Sigue exactamente el flujo principal §5.1
- Backend permite porque la suscripción anterior NO está en `ACTIVE`
- Resultado: nueva fila en `app.subscriptions` con `status = ACTIVE`. La fila vieja queda en su estado terminal como histórico.

### 5.3 Flujos de error

#### 5.3.1 Usuario ya tiene suscripción activa

**Cuándo se dispara:** `POST /subscriptions/checkout-session` cuando ya existe fila con `status = ACTIVE` para ese usuario
**Respuesta:** HTTP 409 Conflict con código `SUBSCRIPTION_ALREADY_ACTIVE` y mensaje incluyendo el `currentPeriodEnd` de la suscripción actual
**Estado final:** Sin cambios
**Evento de auditoría:** No se emite (no es interesante forense)

#### 5.3.2 Plan inválido

**Cuándo se dispara:** Body de checkout-session contiene `plan` distinto a `MONTHLY` o `YEARLY`
**Respuesta:** HTTP 400 Bad Request con código `VALIDATION_INVALID_PLAN`
**Estado final:** Sin cambios

#### 5.3.3 Stripe API responde con error al crear Customer o CheckoutSession

**Cuándo se dispara:** Excepción de `stripe-java` durante llamadas al API de Stripe
**Respuesta:** HTTP 502 Bad Gateway con código `STRIPE_API_ERROR`
**Estado final:** Sin cambios persistidos (transacción rollback). Si se alcanzó a crear Customer en Stripe pero falló el checkout, el `stripe_customer_id` queda persistido en `app.users` (Stripe Customers son reusables, no es problemático tener uno "huérfano").
**Evento de auditoría:** `CHECKOUT_SESSION_FAILED` con `details.reason = "STRIPE_API_ERROR"`, `details.stripeErrorCode`

#### 5.3.4 Webhook con firma inválida

**Cuándo se dispara:** El header `Stripe-Signature` no coincide con el HMAC esperado, o está ausente
**Respuesta:** HTTP 400 Bad Request con código `WEBHOOK_SIGNATURE_INVALID`. NO se procesa el evento.
**Estado final:** Sin cambios. Sin INSERT en `app.stripe_webhook_events`.
**Evento de auditoría:** `STRIPE_WEBHOOK_SIGNATURE_FAILED` con `details.ipOrigin`, `details.attemptedEventType` (si se pudo parsear sin verificar firma — opcional)

> **Importante de seguridad:** un atacante que conozca el endpoint del webhook NO puede inyectar eventos falsos sin la firma válida. Sin embargo, podría intentar provocar denial of service. El rate limiting a este endpoint es una mejora post-MVP.

#### 5.3.5 Webhook con evento duplicado

**Cuándo se dispara:** Ya existe fila con ese `stripe_event_id` en `app.stripe_webhook_events`
**Respuesta:** HTTP 200 OK (no es error — Stripe puede reintentar eventos legítimamente)
**Estado final:** Sin cambios. El evento no se reprocesa.
**Evento de auditoría:** `STRIPE_WEBHOOK_DUPLICATE` con `details.stripeEventId`

#### 5.3.6 Webhook para suscripción no encontrada (orfan)

**Cuándo se dispara:** Webhook menciona `subscription_id` que no existe en `app.subscriptions` (caso teóricamente imposible pero defensivo)
**Respuesta:** HTTP 200 OK (no queremos que Stripe reintente esto)
**Estado final:** Sin cambios
**Evento de auditoría:** `STRIPE_WEBHOOK_ORPHAN` con `details.stripeSubscriptionId`, `details.eventType` (severidad WARNING en logs)

#### 5.3.7 Usuario intenta cancelar sin suscripción activa

**Cuándo se dispara:** `POST /subscriptions/cancel` cuando no hay fila `ACTIVE`
**Respuesta:** HTTP 404 Not Found con código `NO_ACTIVE_SUBSCRIPTION`
**Estado final:** Sin cambios

#### 5.3.8 Usuario abandona el checkout en Stripe

**Cuándo se dispara:** Usuario en la página de Stripe presiona "Cancelar" o cierra el navegador
**Respuesta:** Stripe redirige a `cancel_url = /premium/cancel`. Frontend muestra mensaje "Pago no completado". NO se envía webhook de Stripe en este caso.
**Estado final:** Sin cambios en BD (no había creado nada). El `Checkout Session` queda en Stripe en estado `expired` tras 24h sin acción del usuario.
**Evento de auditoría:** No se emite (no hay forma confiable de detectar el abandono server-side)

#### 5.3.9 Error técnico durante procesamiento de webhook

**Cuándo se dispara:** Excepción durante el flujo de procesamiento tras validar firma e insertar en `stripe_webhook_events`
**Respuesta:** HTTP 500 Internal Server Error. Stripe interpretará esto como "no procesado" y reintentará automáticamente (estrategia exponential backoff con 24h de ventana). La transacción hace rollback, removiendo el registro de `stripe_webhook_events` para permitir el reintento.
**Estado final:** Rollback completo
**Evento de auditoría:** `STRIPE_WEBHOOK_PROCESSING_FAILED` con `details.errorClass`, `details.stripeEventId`

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

#### 6.1.1 `POST /api/v1/subscriptions/checkout-session`

**Propósito:** Crear una Checkout Session en Stripe y devolver la URL para que el frontend redirija al usuario.

**Auth requerido:** Sí

```yaml
paths:
  /api/v1/subscriptions/checkout-session:
    post:
      summary: Inicia el flujo de compra de suscripción premium
      tags: [Subscriptions]
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [plan]
              properties:
                plan:
                  type: string
                  enum: [MONTHLY, YEARLY]
            example:
              plan: "MONTHLY"
      responses:
        '200':
          description: Checkout Session creada
          content:
            application/json:
              schema:
                type: object
                properties:
                  checkoutUrl:
                    type: string
                    format: uri
                    description: URL hosted por Stripe; el frontend debe redirigir aquí
                  sessionId:
                    type: string
                    description: ID de la Checkout Session (referencia, no usar para auth)
              example:
                checkoutUrl: "https://checkout.stripe.com/c/pay/cs_test_..."
                sessionId: "cs_test_abc123"
        '400':
          description: Plan inválido
        '401':
          description: No autenticado
        '409':
          description: El usuario ya tiene una suscripción activa
        '502':
          description: Stripe API no responde
```

#### 6.1.2 `GET /api/v1/subscriptions/me`

**Propósito:** Devolver el estado de suscripción actual del usuario autenticado.

**Auth requerido:** Sí

```yaml
paths:
  /api/v1/subscriptions/me:
    get:
      summary: Estado de suscripción del usuario autenticado
      tags: [Subscriptions]
      security:
        - bearerAuth: []
      responses:
        '200':
          description: Estado de suscripción
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SubscriptionStatusResponse'
              example:
                isPremium: true
                subscription:
                  id: "f47ac10b-58cc-4372-a567-0e02b2c3d479"
                  plan: "MONTHLY"
                  status: "ACTIVE"
                  currentPeriodStart: "2026-05-08T14:32:18Z"
                  currentPeriodEnd: "2026-06-08T14:32:18Z"
                  cancelAtPeriodEnd: false
                  createdAt: "2026-05-08T14:32:18Z"
        '401':
          description: No autenticado

components:
  schemas:
    SubscriptionStatusResponse:
      type: object
      required: [isPremium]
      properties:
        isPremium:
          type: boolean
          description: True si existe suscripción con status=ACTIVE
        subscription:
          oneOf:
            - $ref: '#/components/schemas/SubscriptionDto'
            - type: 'null'
          description: La suscripción más reciente del usuario, o null si nunca ha tenido una. Incluye estados terminales (CANCELLED, PAST_DUE).

    SubscriptionDto:
      type: object
      properties:
        id:
          type: string
          format: uuid
        plan:
          type: string
          enum: [MONTHLY, YEARLY]
        status:
          type: string
          enum: [ACTIVE, CANCELLED, PAST_DUE]
        currentPeriodStart:
          type: string
          format: date-time
        currentPeriodEnd:
          type: string
          format: date-time
        cancelAtPeriodEnd:
          type: boolean
          description: Si true, la suscripción NO se renovará al final del período actual
        createdAt:
          type: string
          format: date-time
```

> **Datos sensibles ocultos:** la respuesta de `GET /subscriptions/me` NUNCA incluye `stripe_customer_id` ni `stripe_subscription_id`. Esos son detalles internos de la integración y no deben filtrar al cliente.

#### 6.1.3 `POST /api/v1/subscriptions/cancel`

**Propósito:** Programar la cancelación de la suscripción activa al fin del período.

**Auth requerido:** Sí

```yaml
paths:
  /api/v1/subscriptions/cancel:
    post:
      summary: Programa cancelación al fin del período actual
      tags: [Subscriptions]
      security:
        - bearerAuth: []
      responses:
        '200':
          description: Cancelación programada exitosamente
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SubscriptionDto'
              example:
                id: "f47ac10b-..."
                plan: "MONTHLY"
                status: "ACTIVE"
                currentPeriodEnd: "2026-06-08T14:32:18Z"
                cancelAtPeriodEnd: true
        '401':
          description: No autenticado
        '404':
          description: No hay suscripción activa para cancelar
        '502':
          description: Stripe API no responde
```

#### 6.1.4 `POST /api/v1/webhooks/stripe`

**Propósito:** Recibir y procesar webhooks de Stripe.

**Auth requerido:** No (autoriza con `Stripe-Signature` header)

```yaml
paths:
  /api/v1/webhooks/stripe:
    post:
      summary: Endpoint de webhooks de Stripe
      tags: [Webhooks]
      security: []
      parameters:
        - in: header
          name: Stripe-Signature
          required: true
          schema:
            type: string
          description: HMAC firmado por Stripe usando el webhook secret
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              description: Estructura de Event de Stripe. NO documentar internamente — usar la librería stripe-java para parsear.
      responses:
        '200':
          description: Evento procesado o ignorado (duplicado/orfan). Stripe interpreta 200 como "no reintentar".
        '400':
          description: Firma inválida o body malformado. Stripe NO reintenta sobre 400.
        '500':
          description: Error técnico durante procesamiento. Stripe reintentará con backoff.
```

> **Importante:** este endpoint es la ÚNICA ruta del proyecto que está exenta de:
> - Spring Security JWT filter
> - CSRF protection
> - Rate limiting basado en sesión
>
> Su única autenticación es la verificación HMAC del `Stripe-Signature` header. Cualquier cambio a estas exenciones debe hacerse con extrema cautela.

### 6.2 Endpoints modificados

#### `GET /api/v1/me` (de HU-F04)

**Cambio:** La respuesta `UserProfileResponse` incorpora un campo nuevo `isPremium: boolean`. Esto evita que el frontend tenga que hacer una llamada adicional a `/subscriptions/me` para saber si el usuario es premium para decisiones de UI simples.

El detalle completo de la suscripción (`status`, `currentPeriodEnd`, etc.) se sigue consultando vía `GET /subscriptions/me` cuando el frontend necesite mostrarlo (página de premium, gestión).

```yaml
# Extensión a UserProfileResponse de HU-F04
UserProfileResponse:
  # ... todos los campos previos ...
  isPremium:
    type: boolean
    description: True si existe suscripción activa para este usuario
```

### 6.3 Esquemas de datos compartidos

`SubscriptionDto` y `SubscriptionStatusResponse` se introducen aquí. Cuando HU-F19 y HU-F23 (post-MVP) verifiquen acceso premium, lo harán consultando `subscriptionService.isPremium(userId)` directamente, no estos DTOs.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Archivo:** `backend/src/main/resources/db/migration/V4__subscriptions.sql`

**Schema afectado:** `app`

### 7.2 Modificación a `app.users`

```sql
ALTER TABLE app.users
    ADD COLUMN stripe_customer_id VARCHAR(50);

CREATE INDEX idx_users_stripe_customer_id ON app.users (stripe_customer_id);
```

**Justificación:**
- `VARCHAR(50)`: los Customer IDs de Stripe tienen prefijo `cus_` + 14-16 chars. 50 da margen amplio.
- Nullable: usuarios pre-existentes y nuevos sin suscripción no tienen Customer aún.
- Índice: búsquedas inversas (rara vez, pero útil para debugging y para resolución desde webhooks).

### 7.3 Tablas nuevas

#### `app.subscriptions`

```sql
CREATE TABLE app.subscriptions (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    stripe_customer_id       VARCHAR(50)  NOT NULL,
    stripe_subscription_id   VARCHAR(50)  NOT NULL,
    plan                     VARCHAR(15)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    current_period_start     TIMESTAMPTZ  NOT NULL,
    current_period_end       TIMESTAMPTZ  NOT NULL,
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT false,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_subscription_plan   CHECK (plan IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT chk_subscription_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'PAST_DUE')),
    CONSTRAINT uq_subscription_stripe_id UNIQUE (stripe_subscription_id)
);

CREATE INDEX idx_subscriptions_user_id ON app.subscriptions (user_id);
CREATE INDEX idx_subscriptions_status  ON app.subscriptions (status);

-- Garantiza máximo UNA suscripción ACTIVE por usuario
CREATE UNIQUE INDEX uq_one_active_subscription_per_user
    ON app.subscriptions (user_id)
    WHERE status = 'ACTIVE';
```

**Justificación:**
- Múltiples filas por usuario permitidas (historial de suscripciones)
- Pero solo UNA `ACTIVE` enforced vía partial unique index — invariante a nivel BD, no solo a nivel app
- `ON DELETE CASCADE`: si el usuario se elimina, sus suscripciones también (cumplimiento GDPR)
- `uq_subscription_stripe_id`: defensa contra inserciones duplicadas si un webhook se procesa dos veces (idempotencia secundaria)
- Sin FK hacia `stripe_customer_id` porque no hay tabla canonical de customers — el ID se duplica intencionalmente para queries directas

#### `app.stripe_webhook_events`

```sql
CREATE TABLE app.stripe_webhook_events (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    stripe_event_id      VARCHAR(80)  NOT NULL,
    event_type           VARCHAR(80)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    received_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at         TIMESTAMPTZ,
    error_message        TEXT,
    payload              JSONB        NOT NULL,
    CONSTRAINT uq_stripe_event_id UNIQUE (stripe_event_id),
    CONSTRAINT chk_webhook_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'DUPLICATE', 'ORPHAN'))
);

CREATE INDEX idx_webhook_events_type ON app.stripe_webhook_events (event_type);
CREATE INDEX idx_webhook_events_status ON app.stripe_webhook_events (status);
```

**Justificación:**
- `stripe_event_id UNIQUE`: el corazón de la idempotencia. Stripe puede reintentar el mismo evento múltiples veces; este UNIQUE garantiza que solo se procese una.
- `payload JSONB`: almacena el evento completo para debugging y replay manual si fuera necesario
- `status`: ciclo de vida del procesamiento
- `error_message`: si `status = FAILED`, captura la causa

### 7.4 Datos semilla

No aplica.

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

| Módulo | Rol | Componentes específicos tocados |
|---|---|---|
| AuthService | Iniciador (extensión) | `SubscriptionController`, `SubscriptionService`, `SubscriptionRepository`, `StripeEventRepository`, sub-paquete `auth/subscription/` |
| IntegrationService | Intermediario | `StripeAdapter` (todas las llamadas a Stripe pasan por aquí) |
| AuditService | Notificado | `AuditLogger` (8+ event types nuevos, ver §9.1) |
| NotificationService | Notificado | `SubscriptionEmailDispatcher`, 4 plantillas nuevas |

> **Decisión arquitectónica:** la lógica de suscripciones vive en `auth/subscription/` como sub-paquete de AuthService. Razón: las suscripciones son una propiedad del usuario, AuthService ya gestiona el lifecycle del usuario (incluyendo perfil), y esto evita crear un décimo módulo arquitectónico. Si en el futuro las suscripciones crecen significativamente (cupones, múltiples productos, billing complejo), refactorizar a módulo dedicado `SubscriptionService` es un cambio aislado.

### 8.2 Interfaces consumidas

| Interfaz | Módulo que la expone | Para qué se usa aquí |
|---|---|---|
| `IAuthentication` | AuthService | Validar JWT y resolver `userId` en endpoints autenticados |
| `IAudit` | AuditService | Emitir todos los eventos de §9.1 |
| `INotification` | NotificationService | Disparar emails de bienvenida, cancelación programada, expiración, fallo de pago |
| `IPayment` | IntegrationService.StripeAdapter | Crear Customer, crear Checkout Session, cancelar Subscription, recuperar Subscription detalles, validar webhook signature |

> **Nota:** `IPayment` es una interfaz introducida aquí pero ya prevista en `ARCHITECTURE.md` §5 con la línea `IntegrationService EXPONE IPayment CONSUME PortfolioService`. En el MVP, el consumidor real de `IPayment` es AuthService.subscription (no PortfolioService). La interfaz arquitectónica preexistente se ajusta a la realidad: cualquier módulo que necesite procesar pagos consume `IPayment`.

### 8.3 Interfaces expuestas

| Interfaz | Quién la consumirá | Contrato |
|---|---|---|
| `ISubscriptionStatus` | HU-F19, HU-F23 (post-MVP), endpoints futuros con verificación premium | Método `isPremium(userId): boolean` y `getCurrentSubscription(userId): Subscription \| null` |

### 8.4 Tácticas de Bass aplicadas

| Táctica | ID | Cómo se materializa en esta feature |
|---|---|---|
| Usar un intermediario | TAC-M1 | `StripeAdapter` es el ÚNICO punto de contacto con la API de Stripe. Si mañana se cambia a otra pasarela de pago, solo cambia este adapter. |
| Adaptar la interfaz | TAC-I2 | `StripeAdapter` traduce conceptos de Stripe (Customer, Subscription, Invoice, PaymentIntent) a conceptos del dominio interno (Subscription con plan + status + period) |
| Mantener registro de auditoría | TAC-S4 | 10+ event types capturando todo el ciclo de vida de la suscripción |
| Encapsular | TAC-M3 | Lógica de idempotencia encapsulada en `StripeEventRepository`; lógica de cálculo de `isPremium` encapsulada en `SubscriptionService.isPremium()` |
| Retry | TAC-D2 | El `RetryPolicy` de IntegrationService (3 reintentos con 1s/3s/5s) aplica a llamadas síncronas hacia Stripe. Para webhooks, Stripe es quien reintenta (su propia política exponencial) |

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

| `event_type` | Trigger | Campos extra en `details` |
|---|---|---|
| `CHECKOUT_SESSION_CREATED` | POST /subscriptions/checkout-session exitoso | `{ plan, stripeSessionId, stripeCustomerId }` |
| `CHECKOUT_SESSION_FAILED` | Error al crear checkout | `{ reason, stripeErrorCode }` |
| `SUBSCRIPTION_ACTIVATED` | Webhook checkout.session.completed procesado | `{ subscriptionId, plan, periodEnd, stripeSubscriptionId }` |
| `SUBSCRIPTION_CANCELLED_SCHEDULED` | Usuario presiona cancelar O webhook detecta cancel_at_period_end=true | `{ subscriptionId, periodEnd, source: "USER_REQUEST" \| "WEBHOOK" }` |
| `SUBSCRIPTION_TERMINATED` | Webhook customer.subscription.deleted procesado | `{ subscriptionId, reason: "PERIOD_END" \| "OTHER" }` |
| `SUBSCRIPTION_PAYMENT_FAILED` | Webhook invoice.payment_failed procesado | `{ subscriptionId, stripeInvoiceId }` |
| `STRIPE_WEBHOOK_RECEIVED` | Webhook llegó al endpoint (post signature validation) | `{ stripeEventId, eventType }` |
| `STRIPE_WEBHOOK_DUPLICATE` | Webhook ya procesado anteriormente | `{ stripeEventId, eventType }` |
| `STRIPE_WEBHOOK_SIGNATURE_FAILED` | Firma HMAC inválida | `{ ipOrigin }` (severidad WARNING) |
| `STRIPE_WEBHOOK_ORPHAN` | Webhook refiere subscription_id no existente en BD | `{ stripeSubscriptionId, eventType }` (WARNING) |
| `STRIPE_WEBHOOK_PROCESSING_FAILED` | Excepción durante procesamiento | `{ stripeEventId, errorClass }` (ERROR) |

### 9.2 Notificaciones

Todas se envían vía el `notificationChannel` configurado por el usuario en HU-F20 (default EMAIL → MailHog).

| Trigger | Plantilla | Contenido resumido |
|---|---|---|
| `SUBSCRIPTION_ACTIVATED` | `welcome-premium.html` | Bienvenida, descripción de funcionalidades premium habilitadas, link a `/premium` para gestionar |
| `SUBSCRIPTION_CANCELLED_SCHEDULED` | `subscription-scheduled-to-cancel.html` | Confirmación de cancelación, mensaje "Tu acceso premium continúa hasta {currentPeriodEnd}", instrucciones para revertir (re-suscribirse si quiere) |
| `SUBSCRIPTION_TERMINATED` | `subscription-expired.html` | "Tu suscripción premium ha finalizado", invitación a re-suscribirse con link a `/premium` |
| `SUBSCRIPTION_PAYMENT_FAILED` | `subscription-payment-failed.html` | "Tu pago de renovación falló. Has perdido acceso premium. Re-suscríbete con un método de pago válido." con link a `/premium` |

### 9.3 Cambios en caché Redis

No aplica directamente. Sin embargo: la flag `isPremium` en `UserProfileResponse` (GET /me) se calcula consultando BD en cada request. Para una optimización post-MVP, se podría cachear en Redis con invalidación en eventos de suscripción. **No es necesario en MVP** — la query es trivial (un PK lookup).

### 9.4 Llamadas a APIs externas

| API externa | Método (operación) | Adapter | Cuándo se invoca |
|---|---|---|---|
| Stripe API | `customers.create` | StripeAdapter | Primera vez que un usuario inicia checkout (si `stripe_customer_id` es null) |
| Stripe API | `checkout.sessions.create` | StripeAdapter | Cada POST /subscriptions/checkout-session exitoso |
| Stripe API | `subscriptions.retrieve` | StripeAdapter | Cada webhook `checkout.session.completed` (para obtener detalles del Subscription) |
| Stripe API | `subscriptions.update` (cancel_at_period_end=true) | StripeAdapter | Cada POST /subscriptions/cancel |
| Stripe API | `webhooks.constructEvent` (validación de firma) | StripeAdapter | Cada llamada a /webhooks/stripe (no es API call, es validación local con `stripe-java`) |

> **RetryPolicy:** las 4 primeras (llamadas síncronas que pueden fallar por red) se envuelven en `RetryPolicy` de Resilience4j (3 reintentos a 1s, 3s, 5s — TAC-D2). La quinta (validación de firma) es local, no necesita retry.

---

## 10. Atributos de calidad aplicables

### 10.1 Escenarios de calidad referenciados

| ID escenario | Atributo | Cómo esta feature lo soporta |
|---|---|---|
| ESC-I2 | Interoperabilidad | "Stripe rechaza el pago de suscripción premium → mensaje de error mostrado al usuario en <3 segundos". Se cumple porque el rechazo de Stripe llega como respuesta síncrona a la llamada `checkout.sessions.create` (no se requiere webhook). El flujo de error 5.3.3 se ejecuta dentro de la misma request del usuario. |

### 10.2 Constraints específicos

| Constraint | Medida |
|---|---|
| Endpoint POST /subscriptions/checkout-session responde en <2s p95 | Incluye latencia de llamada a Stripe API |
| Webhook procesado y respuesta 200 a Stripe en <5s | Stripe considera "timeout" si tarda más de 10s y reintentará |
| `stripe_customer_id` NUNCA expuesto en respuestas API | Verificable por inspección |
| `stripe_subscription_id` NUNCA expuesto en respuestas API | Verificable por inspección |
| Datos de tarjeta NUNCA pasan por servidores de BloomTrade (responsabilidad de Stripe Checkout) | Verificable por inspección de código backend (no debe existir referencia a `card_number`, `cvv`, etc.) |

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Suscripción premium con Stripe

  Antecedentes:
    Dado un usuario autenticado "juan@example.com" sin suscripción previa
    Y Stripe Test Mode con price IDs configurados:
      | plan    | price_id              |
      | MONTHLY | price_monthly_test    |
      | YEARLY  | price_yearly_test     |
    Y stripe-cli reenviando webhooks a localhost:8080/api/v1/webhooks/stripe

  Escenario: Activación exitosa de suscripción mensual
    Cuando el usuario envía POST /api/v1/subscriptions/checkout-session con body { "plan": "MONTHLY" }
    Entonces el sistema responde 200 con checkoutUrl que apunta a checkout.stripe.com
    Y si el usuario no tenía stripe_customer_id, se le crea uno en Stripe y se persiste en app.users
    Y se emite CHECKOUT_SESSION_CREATED

    Cuando el usuario completa el pago en Stripe con la tarjeta 4242 4242 4242 4242
    Y Stripe envía webhook checkout.session.completed
    Entonces el backend valida la firma y procesa el evento
    Y se inserta una fila en app.subscriptions con status="ACTIVE", plan="MONTHLY", cancel_at_period_end=false
    Y se inserta una fila en app.stripe_webhook_events con status="PROCESSED"
    Y se envía email "Bienvenido a Premium" a MailHog
    Y se emiten STRIPE_WEBHOOK_RECEIVED y SUBSCRIPTION_ACTIVATED a ElasticSearch

    Cuando el frontend hace GET /api/v1/subscriptions/me
    Entonces la respuesta indica isPremium=true y la suscripción con sus detalles

  Escenario: Activación exitosa de suscripción anual
    Cuando el usuario envía POST /api/v1/subscriptions/checkout-session con body { "plan": "YEARLY" }
    Y completa el pago en Stripe
    Y se procesa el webhook checkout.session.completed
    Entonces se inserta fila en app.subscriptions con plan="YEARLY"
    Y current_period_end es exactamente 1 año después de current_period_start (con tolerancia ±1 día por timezone)

  Escenario: Usuario con suscripción activa intenta suscribirse de nuevo
    Dado que el usuario ya tiene una fila en app.subscriptions con status="ACTIVE"
    Cuando envía POST /api/v1/subscriptions/checkout-session
    Entonces el sistema responde 409 con código SUBSCRIPTION_ALREADY_ACTIVE
    Y NO se crea Checkout Session en Stripe
    Y NO se inserta nada en BD

  Escenario: Cancelación programada de suscripción activa
    Dado un usuario con suscripción ACTIVE, plan="MONTHLY", currentPeriodEnd=2026-06-08
    Cuando envía POST /api/v1/subscriptions/cancel
    Entonces el sistema invoca Stripe API para marcar cancel_at_period_end=true
    Y actualiza app.subscriptions: cancel_at_period_end=true, status permanece "ACTIVE"
    Y envía email "Tu suscripción se cancelará el 2026-06-08" a MailHog
    Y emite SUBSCRIPTION_CANCELLED_SCHEDULED

    Cuando el frontend hace GET /api/v1/subscriptions/me
    Entonces la respuesta indica isPremium=true (todavía premium hasta fin de período)
    Y subscription.cancelAtPeriodEnd=true

  Escenario: Suscripción expira tras cancelación programada
    Dado un usuario con suscripción ACTIVE y cancel_at_period_end=true
    Y han pasado los días hasta current_period_end
    Cuando Stripe envía webhook customer.subscription.deleted
    Entonces el backend procesa el evento
    Y app.subscriptions.status pasa a "CANCELLED"
    Y se envía email "Tu suscripción premium ha terminado"
    Y se emite SUBSCRIPTION_TERMINATED

    Cuando el frontend hace GET /api/v1/subscriptions/me
    Entonces isPremium=false
    Y subscription.status="CANCELLED"

  Escenario: Fallo de renovación automática (PAST_DUE)
    Dado un usuario con suscripción ACTIVE y current_period_end venciendo
    Cuando Stripe intenta renovar y la tarjeta falla
    Y Stripe envía webhook invoice.payment_failed
    Entonces el backend procesa el evento
    Y app.subscriptions.status pasa a "PAST_DUE" inmediatamente
    Y isPremium=false (downgrade inmediato, sin grace period)
    Y se envía email "Tu pago falló - re-suscríbete"
    Y se emite SUBSCRIPTION_PAYMENT_FAILED

  Escenario: Re-suscripción tras cancelación
    Dado un usuario con suscripción terminada (status="CANCELLED")
    Cuando inicia un nuevo checkout y completa el pago
    Entonces se inserta una NUEVA fila en app.subscriptions con status="ACTIVE"
    Y la fila vieja con status="CANCELLED" permanece como histórico

  Escenario: Re-suscripción tras PAST_DUE
    Dado un usuario con suscripción terminada (status="PAST_DUE")
    Cuando inicia un nuevo checkout y completa el pago con tarjeta válida
    Entonces se inserta una nueva fila ACTIVE
    Y el usuario recupera acceso premium

  Escenario: Webhook con firma inválida es rechazado
    Cuando llega un POST a /api/v1/webhooks/stripe con Stripe-Signature inválido
    Entonces el backend responde 400 con código WEBHOOK_SIGNATURE_INVALID
    Y NO inserta nada en app.stripe_webhook_events
    Y NO procesa el evento
    Y se emite STRIPE_WEBHOOK_SIGNATURE_FAILED como WARNING

  Escenario: Webhook duplicado es ignorado
    Dado que ya existe fila en app.stripe_webhook_events con stripe_event_id="evt_abc"
    Cuando Stripe envía el mismo evento (mismo evt_abc) por segunda vez
    Entonces el backend valida firma exitosamente
    Y detecta el duplicado vía unique constraint
    Y responde 200 OK
    Y NO reprocesa el evento (status del subscription no cambia)
    Y se emite STRIPE_WEBHOOK_DUPLICATE

  Escenario: Cancelación sin suscripción activa
    Dado un usuario sin suscripción ACTIVE
    Cuando envía POST /api/v1/subscriptions/cancel
    Entonces el sistema responde 404 con código NO_ACTIVE_SUBSCRIPTION

  Escenario: Stripe API caído al crear checkout
    Dado que Stripe API no responde (simulado con WireMock devolviendo 503)
    Cuando el usuario envía POST /api/v1/subscriptions/checkout-session
    Entonces el RetryPolicy intenta 3 veces a 1s, 3s, 5s
    Y tras 3 fallos, el sistema responde 502 con código STRIPE_API_ERROR
    Y se emite CHECKOUT_SESSION_FAILED con reason="STRIPE_API_ERROR"

  Escenario: Endpoint webhook sin Stripe-Signature
    Cuando llega un POST a /webhooks/stripe sin header Stripe-Signature
    Entonces el sistema responde 400 con código WEBHOOK_SIGNATURE_INVALID
    Y NO se ejecuta lógica de procesamiento

  Esquema del escenario: Validación del campo plan
    Cuando se envía POST /api/v1/subscriptions/checkout-session con body { "plan": <valor> }
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor       | httpStatus | errorCode                |
      | "MONTHLY"   | 200        | (none)                   |
      | "YEARLY"    | 200        | (none)                   |
      | "monthly"   | 400        | VALIDATION_INVALID_PLAN  |
      | "WEEKLY"    | 400        | VALIDATION_INVALID_PLAN  |
      | ""          | 400        | VALIDATION_REQUIRED      |
      | null        | 400        | VALIDATION_REQUIRED      |
```

### 11.2 Criterios no funcionales verificables

| Criterio | Medida | Cómo se verifica |
|---|---|---|
| POST /subscriptions/checkout-session responde en <2s p95 | 50 requests | JMeter |
| Webhook responde 200 a Stripe en <5s | Inspección de logs de stripe-cli en local | Manual |
| `stripe_customer_id` NUNCA aparece en respuestas API | Inspección de respuesta JSON de /me y /subscriptions/me | Manual + test |
| Datos de tarjeta NUNCA llegan al backend | Inspección manual de logs durante un checkout | Manual |
| Idempotencia: enviar el mismo webhook 100 veces resulta en una sola fila procesada | Test de integración con WireMock | Test automatizado |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/premium`

**Propósito:** Landing page de gestión de suscripción. Punto único para ver estado, suscribirse, o cancelar.

**Acceso:** Protegida — solo usuarios autenticados.

**Componente principal:** `PremiumPage.tsx`

**Comportamiento según estado de suscripción** (obtenido de GET /subscriptions/me al cargar la página):

**Estado A: Sin suscripción (isPremium=false, subscription=null)**

| Elemento | Detalle |
|---|---|
| Título | "Activa BloomTrade Premium" |
| Card "Plan Mensual" | USD $12/mes. Botón "Activar mensual" |
| Card "Plan Anual" | USD $120/año (equivalente a $10/mes, ahorra $24). Botón "Activar anual" |
| Lista de funcionalidades premium | "Alertas de precio personalizadas (próximamente)", "Watchlist con notificaciones (próximamente)" — indicando que son post-MVP en demos |

**Estado B: Suscripción ACTIVA sin cancelación programada**

| Elemento | Detalle |
|---|---|
| Banner verde | "Eres Premium 🌟" |
| Plan actual | "Plan Mensual / Anual" |
| Detalle | "Tu próximo cargo es el {currentPeriodEnd}" |
| Botón secundario | "Cancelar suscripción" — abre modal de confirmación |

**Estado C: Suscripción ACTIVA con cancelación programada (cancelAtPeriodEnd=true)**

| Elemento | Detalle |
|---|---|
| Banner amarillo | "Tu suscripción terminará el {currentPeriodEnd}" |
| Plan actual | "Plan Mensual / Anual" |
| Mensaje | "Mantienes acceso premium hasta esa fecha. Después podrás re-suscribirte cuando quieras." |
| (Sin botón de cancelar — ya está cancelada) |
| (Sin botón de reactivar — fuera de alcance del MVP) |

**Estado D: Suscripción CANCELLED o PAST_DUE**

| Elemento | Detalle |
|---|---|
| Banner gris (CANCELLED) o rojo (PAST_DUE) | "Tu suscripción premium terminó" / "Tu pago falló y perdiste acceso premium" |
| Estado idéntico a A (cards de planes con botones de re-activación) |

**Modal de confirmación de cancelación:**

```
┌──────────────────────────────────────────────┐
│ ¿Cancelar tu suscripción premium?            │
├──────────────────────────────────────────────┤
│                                              │
│ Tu acceso premium continuará hasta el        │
│ {currentPeriodEnd}. No se hará ningún cargo  │
│ adicional. Podrás re-suscribirte cuando      │
│ quieras.                                     │
│                                              │
│       [Mantener premium] [Sí, cancelar]      │
└──────────────────────────────────────────────┘
```

#### Página `/premium/success`

**Propósito:** Confirmar al usuario que el pago fue procesado y el premium activado.

**Acceso:** Pública (Stripe redirige aquí). El frontend valida sesión propia detrás.

**Comportamiento:**

1. Al cargar, lee `session_id` de query string
2. Hace GET /api/v1/subscriptions/me con polling cada 2s
3. Si subscription.status === 'ACTIVE': muestra "¡Bienvenido a Premium! 🎉" + auto-redirige a `/dashboard` tras 3s
4. Si tras 30s no llega ACTIVE: muestra "Tu pago está procesándose. Te notificaremos por email cuando se complete." con link a `/dashboard`

#### Página `/premium/cancel`

**Propósito:** Mensaje cuando el usuario abandona el checkout en Stripe.

**Acceso:** Pública.

**Comportamiento:** Mensaje "Pago no completado. No se hizo ningún cargo a tu tarjeta." con botones "Volver a intentar" (→ /premium) y "Ir al dashboard" (→ /dashboard).

### 12.2 Componentes nuevos a crear

| Componente | Ubicación | Propósito |
|---|---|---|
| `PremiumPage` | `src/pages/PremiumPage.tsx` | Orquesta los 4 estados (A, B, C, D) |
| `PremiumPlanCard` | `src/features/subscription/components/PremiumPlanCard.tsx` | Card de plan con precio y botón |
| `PremiumStatusBanner` | `src/features/subscription/components/PremiumStatusBanner.tsx` | Banner según estado (verde/amarillo/rojo/gris) |
| `CancelSubscriptionModal` | `src/features/subscription/components/CancelSubscriptionModal.tsx` | Modal de confirmación |
| `PremiumSuccessPage` | `src/pages/PremiumSuccessPage.tsx` | Página /premium/success con polling |
| `PremiumCancelPage` | `src/pages/PremiumCancelPage.tsx` | Página /premium/cancel |

### 12.3 Hooks o utilidades nuevas

| Item | Ubicación | Propósito |
|---|---|---|
| `useSubscription` | `src/features/subscription/hooks/useSubscription.ts` | React Query: GET /subscriptions/me con polling configurable |
| `useStartCheckout` | `src/features/subscription/hooks/useStartCheckout.ts` | Mutation: POST /subscriptions/checkout-session + redirección a checkoutUrl |
| `useCancelSubscription` | `src/features/subscription/hooks/useCancelSubscription.ts` | Mutation: POST /subscriptions/cancel + invalidación de cache |

### 12.4 Cambios de routing

| Ruta | Componente | Acceso |
|---|---|---|
| `/premium` | `PremiumPage` | Protegida |
| `/premium/success` | `PremiumSuccessPage` | Pública (validación de sesión interna) |
| `/premium/cancel` | `PremiumCancelPage` | Pública |

Agregar entrada "Premium" o "Mi plan" en el menú del `AppHeader` (introducido en HU-F02-F03).

---

## 13. Fuera de alcance de esta spec

- **Activación de suscripción durante el registro** — HU-F01 ya está cerrada, no se reabre. La activación solo desde post-login
- **Cambio de plan (mensual → anual o viceversa)** — fuera del MVP
- **Reembolsos parciales o totales** — usuario debe contactar soporte; no hay flujo automatizado
- **Grace period en PAST_DUE** — downgrade inmediato en MVP (decisión registrada)
- **Cupones, descuentos, trials gratis** — fuera del MVP
- **Notificación de "tu tarjeta vence pronto"** — Stripe ya envía estos emails automáticamente desde su dashboard; BloomTrade no replica
- **Receipt/invoice descargable desde la app** — Stripe envía estos por email; no se replica en UI
- **Multi-divisa** — solo USD
- **Suscripciones empresariales o de grupo** — fuera del MVP
- **Enforcement de premium en endpoints específicos** — no hay endpoints premium aún en MVP; el estado se persiste y queda listo para HU-F19/F23
- **Reactivar suscripción antes de que termine el período (deshacer cancel_at_period_end)** — fuera del MVP; el usuario debe esperar a que termine y re-suscribirse

---

## 14. Preguntas abiertas

Ninguna. Todas las decisiones críticas resueltas previo a la redacción de esta spec.

---

## 15. Definition of Done específica de esta spec

- ☐ Migración Flyway `V4__subscriptions.sql` creada y aplicada
- ☐ Los 4 endpoints documentados en Swagger UI
- ☐ `app.users.stripe_customer_id` agregado correctamente
- ☐ Tablas `app.subscriptions` y `app.stripe_webhook_events` creadas con constraints e índices
- ☐ El índice único parcial `uq_one_active_subscription_per_user` garantiza no haber dos ACTIVE por usuario (verificable con test de integración)
- ☐ Los 4 handlers de webhook implementados y testeados:
  - `checkout.session.completed` (activación)
  - `customer.subscription.updated` (cancelación programada y otros cambios)
  - `customer.subscription.deleted` (terminación)
  - `invoice.payment_failed` (downgrade inmediato)
- ☐ Idempotencia verificada: enviar el mismo webhook 100 veces no causa cambios de estado adicionales
- ☐ Signature verification del webhook funcionando con `STRIPE_WEBHOOK_SECRET`
- ☐ Las 4 plantillas de email creadas: `welcome-premium.html`, `subscription-scheduled-to-cancel.html`, `subscription-expired.html`, `subscription-payment-failed.html`
- ☐ Página `/premium` renderiza los 4 estados (sin suscripción, activa, activa con cancelación programada, terminada)
- ☐ Demo end-to-end: usuario nuevo → registra → login → /premium → activa mensual → tarjeta 4242 → vuelve a /premium/success → ve banner premium → cancela → ve banner "termina el X"
- ☐ Todos los 11+ event types de §9.1 verificables en Kibana
- ☐ `stripe_customer_id` NO aparece en respuestas API (verificable por inspección y test)
- ☐ `stripe-cli` configurado y documentado en README.md para forwarding local de webhooks
- ☐ Variables de entorno documentadas en `.env.example`: `STRIPE_SECRET_KEY`, `STRIPE_PRICE_MONTHLY`, `STRIPE_PRICE_YEARLY`, `STRIPE_WEBHOOK_SECRET`
- ☐ Endpoint `/api/v1/webhooks/stripe` está excluido de Spring Security JWT filter y CSRF (verificable inspeccionando la configuración)

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-08 | Versión inicial | Cuarta spec del MVP (HU-F06), cierra el Sprint 1 |

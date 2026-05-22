# plan.md — HU-F06 Suscripción premium con Stripe

> Plan técnico derivado de `specs/HU-F06-suscripción-premium/SPEC.md` v1.2.
> Estado: **pendiente de aprobación humana** (SDD Paso 2).

---

## 1. Objetivo

Implementar el **ciclo de vida completo de una suscripción** vía Stripe Test Mode:
1. Activación: `POST /subscriptions/checkout-session` → Stripe Checkout hosted → `4242 4242 4242 4242` → webhook `checkout.session.completed` → fila `ACTIVE` en BD.
2. Self-service: `POST /subscriptions/portal-session` → Customer Portal de Stripe (cancel, reactivar, ver invoices, actualizar tarjeta).
3. Sync vía webhooks: 4 tipos (`checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_failed`) con signature verification + idempotencia.
4. Lectura: `GET /subscriptions/me` lee BD (no Stripe).

Es la **primera integración real con API externa** del MVP y materializa TAC-M1 (`StripeAdapter` intermediario), TAC-I2 (adaptar interfaz), TAC-D2 (RetryPolicy 3×1s/3s/5s) y TAC-S4 (auditoría inmutable con 11 event types).

Tamaño: **1 día apretado**. La complejidad real está en webhooks (raw body + signature + idempotencia) y en el cableado del Customer Portal — el resto es CRUD con la SDK.

---

## 2. Decisiones técnicas concretas

| # | Decisión | Justificación |
|---|---|---|
| **D1** | **stripe-java** versión 28.x (última estable al instalar). Sin override de `apiVersion` — la SDK usa la última API version (`2026-04-22.dahlia` o superior). Skill: *"Always use the latest API version and SDK"*. | STACK.md §2.3 declara `stripe-java` aprobado; se concreta versión al ejecutar Lote A (`mvn dependency:resolve`). |
| **D2** | **RAK (Restricted API Key) `rk_test_...`** con permisos mínimos: Customers (write), Checkout Sessions (write), Subscriptions (read), Billing Portal Sessions (write), Webhook Endpoints (read opcional). Env var: `STRIPE_API_KEY` (genérica, vale RAK o sk_). Borrar `STRIPE_SECRET_KEY` si existe en `.env.example`. Documentar en README que se debe crear la RAK manualmente en Stripe Dashboard antes del primer arranque. | Skill `security.md`: *"Do not default to recommending secret keys. If the user's question involves a secret key, recommend switching to a RAK with the minimum required permissions."* Para MVP académico el blast radius es chico, pero respetar la mejor práctica cuesta cero. |
| **D3** | **NO pasar `payment_method_types`** al crear Checkout Session. Habilita Dynamic Payment Methods. | Trap explícito en skill `billing.md`: hardcodear `['card']` bloquea métodos alternativos que mejoran conversión. Cuesta cero respetarlo. |
| **D4** | **Idempotency-Key outbound** en `checkout.sessions.create` y `customers.create`. Estrategia simple: UUID v4 generado en el controller, NO persistido. Protege contra doble-click del usuario. `subscription.update` y `billingPortal.sessions.create` no requieren idempotency key crítico (cancel es idempotente de facto; portal session es read-only de facto). | Skill `references/payments.md` patrón estándar. Persistir el key sería over-engineering MVP. |
| **D5** | **Webhook endpoint EXENTO de Spring Security** (excluido del JWT filter chain). Body como `@RequestBody String rawBody` para preservar bytes exactos (signature requiere raw). Verificación con `Webhook.constructEvent(rawBody, sigHeader, STRIPE_WEBHOOK_SECRET)` que ya hace timing-safe comparison + tolerance 300s. Fallo → 400 `WEBHOOK_SIGNATURE_INVALID`. | Skill `security.md`: *"Always verify webhook signatures... Do not process webhook events without verifying their signatures."* CSRF ya está disabled global por D17 HU-F02 (es OK). |
| **D6** | **Idempotencia inbound** vía tabla `app.stripe_webhook_events` con `stripe_event_id` UNIQUE. Flujo: (1) `Webhook.constructEvent` valida firma; (2) `INSERT INTO stripe_webhook_events ... status='RECEIVED'`; si UNIQUE violation → 200 `STRIPE_WEBHOOK_DUPLICATE`; (3) procesar evento; (4) `UPDATE ... status='PROCESSED'`. Todo en una sola transacción — si falla, rollback completo (incluyendo el INSERT inicial) y Stripe reintentará. | SPEC §5.3.5 + §5.3.9. Patrón estándar de idempotencia con BD. |
| **D7** | **Customer Portal endpoint `/portal-session`** (v1.2 SPEC) reemplaza `/cancel`. Una sola configuración del Portal se hace **una vez** en Stripe Dashboard Test Mode → activar features: cancelación, actualización de método de pago, descarga de invoices. `return_url = {FRONTEND_URL}/premium` (env var). | SPEC v1.2 §5.2.1 + skill `billing.md`: "*recommend the Customer Portal*". Ahorra 1h de implementación frontend (modal + endpoint /cancel + hook). |
| **D8** | **Persistencia split del Customer create** (anti-orphan): el flow `POST /checkout-session` hace (1) `createCustomer` si null → commit `stripe_customer_id` en una sub-transacción; (2) `createCheckoutSession` en otra. Si (2) falla, el customer queda en Stripe pero también en BD — es reusable en el siguiente intento. SPEC §5.3.3 ya documenta que customers "huérfanos" en Stripe son aceptables. Implementación: dos métodos `@Transactional` separados en `SubscriptionService` o `REQUIRES_NEW` en uno. | Sin esto, si la transacción es única y falla el checkout, el customer_id se rollbackea de BD pero NO de Stripe → próximo intento crea otro customer (acumulación de huérfanos). |
| **D9** | **Sub-paquete `auth/subscription/`** dentro del módulo AuthService (mismo patrón que HU-F04+F20 `auth/profile/`). SPEC §8.1 nota arquitectónica. No se crea un módulo nuevo. | Cohesión con `User` entity. Evita crecer la lista de 9 módulos del ARCHITECTURE.md §3. |
| **D10** | **Smart Retries de Stripe habilitado por default**. NO se configura nada. Para MVP, escuchar `invoice.payment_failed` y degradar a `PAST_DUE` **inmediatamente** (agresivo, sin grace period). | SPEC §5.2.4. Alternativa "esperar a que Stripe declare la sub muerta" requiere más estados. Para MVP académico la simplicidad gana. Decisión consciente registrada. |
| **D11** | **Notifier (HU-F02-F03)** se extiende con 4 métodos: `sendWelcomePremiumEmail`, `sendCancellationScheduledEmail`, `sendSubscriptionExpiredEmail`, `sendSubscriptionPaymentFailedEmail`. Implementación en `MailNotifier` con templates Thymeleaf nuevos. | Reusa infraestructura existente (JavaMailSender, Thymeleaf, audit `*_EMAIL_FAILED`). Patrón ya consolidado por D7 HU-F02. |
| **D12** | **NO se materializa interface `PaymentGateway` ni `SubscriptionStatus` como beans públicos en MVP.** `SubscriptionService` cumple ambos roles. Si HU-F19 (post-MVP, alertas premium) lo necesita, se extraen ahí. La nota del SPEC v1.1 §8.3 sobre `SubscriptionStatus` queda como diseño a futuro. | YAGNI. Esto evita boilerplate sin uso real. Las interfaces inter-módulo se justifican cuando hay >1 consumidor. |
| **D13** | **4 templates Thymeleaf nuevos** en `resources/templates/email/`:<br>• `welcome-premium.html` — variables `{nombreCompleto, plan, currentPeriodEnd}`<br>• `subscription-scheduled-to-cancel.html` — variables `{nombreCompleto, currentPeriodEnd}`<br>• `subscription-expired.html` — variables `{nombreCompleto, plan}`<br>• `subscription-payment-failed.html` — variables `{nombreCompleto, plan}` | SPEC §9.2. Estilo inline-CSS análogo a `welcome.html` y `otp.html`. |
| **D14** | **AuditEventType extender con 11 entries**: `CHECKOUT_SESSION_CREATED`, `CHECKOUT_SESSION_FAILED`, `BILLING_PORTAL_SESSION_CREATED`, `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_CANCELLED_SCHEDULED`, `SUBSCRIPTION_REACTIVATED`, `SUBSCRIPTION_TERMINATED`, `SUBSCRIPTION_PAYMENT_FAILED`, `STRIPE_WEBHOOK_RECEIVED`, `STRIPE_WEBHOOK_DUPLICATE`, `STRIPE_WEBHOOK_SIGNATURE_FAILED`, `STRIPE_WEBHOOK_ORPHAN`, `STRIPE_WEBHOOK_PROCESSING_FAILED`. | SPEC v1.2 §9.1. Total: ~13 nuevos eventos (vs 11 de v1.1 — agregamos `BILLING_PORTAL_SESSION_CREATED` y `SUBSCRIPTION_REACTIVATED`). |
| **D15** | **Migración V4** según SPEC §7.2/7.3 sin cambios. Tres operaciones DDL: ALTER `app.users` + CREATE `app.subscriptions` + CREATE `app.stripe_webhook_events`. Constraints + índices según spec. | DDL ya validado en SPEC. |
| **D16** | **Coverage target 60-70%** [[feedback-coverage-vs-velocidad]]. Foco crítico: `SubscriptionService` (lógica de checkout + cancel + portal), `StripeWebhookHandler` (signature + idempotency + 4 event types), `StripeAdapter` (mocks via WireMock). Skip tests triviales del controller. | Memoria viva. La criticidad real está en (1) signature verification (security), (2) idempotencia (no duplicar suscripciones), (3) no leak de stripe_customer_id/stripe_subscription_id en responses (SPEC §10.2). |
| **D17** | **Frontend `PremiumPage`** orquesta 4 estados A/B/C/D (SPEC §12.1) con un solo componente que ramifica según `useSubscription().data.status`. 2 hooks: `useStartCheckout` (mutation → redirect), `useOpenBillingPortal` (mutation → redirect). 3 pages: `PremiumPage`, `PremiumSuccessPage` (polling), `PremiumCancelPage`. | SPEC §12.2/12.3. Mismo patrón que HU-F04 (ProfilePage con secciones inline) — minimiza proliferación de archivos. |
| **D18** | **`stripe-cli` requerido en dev local**: `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe`. README + `.env.example` documentan instrucciones. NO se commitea ningún ejecutable; el desarrollador lo instala manualmente. Para `docker compose up` el flujo funciona sin webhooks (uno puede probar el checkout pero el `app.subscriptions` no se actualiza hasta que el webhook llegue). | Limitación conocida del local-dev sin túnel; aceptable para demo MVP. Post-MVP: ngrok o cloudflared. |
| **D19** | **Tests IT con WireMock** simulando Stripe (Postgres + Redis reales, Stripe mockeado). `STRIPE_API_KEY` en `application-test.yml` puede ser `rk_test_dummy_for_wiremock`. NO se hacen llamadas reales a Stripe en CI. | STACK.md §2.3 ya tiene WireMock 3.x aprobado. Pre-aprobada para HU-F02 (no se usó). |
| **D20** | **Frontend NO almacena `session_id` ni `portal_url`** en localStorage. `PremiumSuccessPage` lee `session_id` del query param y hace polling a `GET /subscriptions/me`. `portal_url` se consume inmediatamente con `window.location.href = portalUrl`. | Coherente con D12 HU-F02 (estado de sesión solo en memoria). |
| **D21** | **`stripe_customer_id` y `stripe_subscription_id` NUNCA en respuestas API**. `SubscriptionMapper` los excluye explícitamente; test verifica que el JSON serializado no los contiene (igual al test de no-leak `passwordHash` del Lote D HU-F04). | SPEC §10.2 constraint NO-NEGOCIABLE. |

---

## 3. Cambios de dependencias

**Backend:**
- ➕ **`stripe-java`** versión a fijar al instalar (objetivo: última estable, ~28.x).
- ➕ **`wiremock-standalone`** ya en `<dependency>` de testing por HU-F02 (verificar).
- Resto: NINGUNO (Spring Mail + Thymeleaf + resilience4j + jjwt ya aprobados).

**STACK.md**: actualizar §2.3 con la versión exacta de `stripe-java` en el mismo PR (regla CLAUDE.md #6).

**Frontend:** NINGUNO. Reusa axios, react-query, react-router-dom 6.27.0, RHF+zod.

**`.env.example`**: agregar/actualizar variables (skill `security.md` — RAK):
```
STRIPE_API_KEY=rk_test_REPLACE_WITH_RAK
STRIPE_WEBHOOK_SECRET=whsec_REPLACE_FROM_CLI
STRIPE_PRICE_MONTHLY=price_REPLACE_FROM_DASHBOARD
STRIPE_PRICE_YEARLY=price_REPLACE_FROM_DASHBOARD
FRONTEND_URL=http://localhost:5173
```

Y eliminar (si están sin uso): `STRIPE_SECRET_KEY` (renombrar), `STRIPE_PUBLISHABLE_KEY` (no se usa — Checkout es hosted).

---

## 4. Reuso de HU-F01/F02/F04 y cosas nuevas

**Reutilizado tal cual:**
- `shared/web/{ErrorResponse, GlobalExceptionHandler, ValidationMessages, TraceIdFilter}` — extender con nuevos códigos
- `audit/{Auditor, AuditEvent, AuditEventType}` — agregar 13 entries al enum
- `notification/{Notifier, MailNotifier}` — extender con 4 métodos
- `auth/security/{JwtAuthenticationFilter, AuthenticatedUser}` — reuso directo
- `auth/domain/User` — MODIFICADO: + `stripe_customer_id` field (nullable)
- `auth/repository/UserRepository` — sin cambios (lookups por id existentes)
- `config/SecurityConfig` — agregar exención del webhook endpoint
- `application.yml` — sin cambios estructurales
- Frontend `apiClient`, `errorParser`, `messages.es.ts`, `AuthContext` — extender

**Nuevo** (estructura):

```
backend/src/main/java/co/edu/unbosque/bloomtrade/auth/
└── subscription/
    ├── controller/{SubscriptionController, StripeWebhookController}.java
    ├── service/SubscriptionService.java
    ├── service/StripeWebhookHandler.java          (lógica de 4 event types)
    ├── domain/{Subscription, SubscriptionStatus, BillingPlan}.java  (entity + enums)
    ├── domain/StripeWebhookEvent.java             (entity de idempotencia)
    ├── repository/{SubscriptionRepository, StripeWebhookEventRepository}.java
    ├── dto/{CheckoutSessionRequest, CheckoutSessionResponse,
    │       PortalSessionResponse, SubscriptionStatusResponse, SubscriptionDto}.java
    ├── mapper/SubscriptionMapper.java
    └── exception/{SubscriptionAlreadyActiveException, NoStripeCustomerException,
                   StripeApiException, WebhookSignatureInvalidException}.java

backend/src/main/java/co/edu/unbosque/bloomtrade/integration/
└── stripe/
    ├── StripeAdapter.java                          (intermediario, TAC-M1)
    ├── StripeConfig.java                           (bean Stripe.apiKey config)
    └── dto/                                         (records internos del adapter)

backend/src/main/resources/templates/email/
├── welcome-premium.html
├── subscription-scheduled-to-cancel.html
├── subscription-expired.html
└── subscription-payment-failed.html

backend/src/main/resources/db/migration/
└── V4__subscriptions.sql                           (DDL SPEC §7)

frontend/src/
├── features/subscription/
│   ├── api/subscriptionApi.ts                      (getMe, createCheckoutSession, openPortal)
│   ├── hooks/{useSubscription, useStartCheckout, useOpenBillingPortal}.ts
│   └── components/PremiumPlanCard.tsx              (card de plan reusable)
├── pages/{PremiumPage, PremiumSuccessPage, PremiumCancelPage}.tsx
├── types/api.ts                                    (extendido con subscription types)
└── App.tsx                                         (+ 3 rutas)
```

---

## 5. Hallazgos / deuda a abordar dentro de este bundle

| # | Hallazgo | Acción |
|---|---|---|
| G1 | `.env.example` actual lista `STRIPE_SECRET_KEY` (sigue de Día 0). Renombrar a `STRIPE_API_KEY` + comentario "usa RAK" (D2). | Lote A |
| G2 | El SecurityConfig actual tiene `.anyRequest().authenticated()`. El webhook endpoint debe ser `permitAll` (autoriza con HMAC, no JWT). | Lote A o D |
| G3 | `User.stripe_customer_id` requiere extensión del entity + getter (no setter — agregamos método de dominio `linkStripeCustomer(String customerId)` análogo a HU-F04 D19). | Lote A |
| G4 | `ARCHITECTURE.md` §5 todavía lista `IPayment` con prefijo. Deuda doc-only declarada — NO se arregla aquí. | (sin acción) |
| G5 | El SPEC §6.2 dice que `GET /me` debe extender `UserProfileResponse` con `isPremium`. HU-F04 D10 confirmó que HU-F06 lo agregaría. Implementación: agregar campo `boolean isPremium` al record + en `ProfileService.getMe` consultar `subscriptionRepository.existsByUserIdAndStatus(userId, ACTIVE)`. Test de UserProfileMapperTest se ajusta. | Lote C |
| G6 | Plantilla Maven de stripe-java puede requerir activar `bouncycastle` u otra crypto provider — verificar en Lote A. | Lote A |

---

## 6. Mapeo arquitectónico (SPEC §8) → paquetes

| Componente SPEC | Paquete | Notas |
|---|---|---|
| `StripeAdapter` (TAC-M1, IntegrationService) | `integration/stripe/StripeAdapter` | Único punto de contacto con Stripe API. RetryPolicy aquí. |
| `SubscriptionController` | `auth/subscription/controller/SubscriptionController` | 3 endpoints: checkout-session, portal-session, me. |
| `StripeWebhookController` | `auth/subscription/controller/StripeWebhookController` | Endpoint dedicado al webhook. EXENTO del JWT filter. |
| `SubscriptionService` | `auth/subscription/service/SubscriptionService` | Orquesta los flujos síncronos (D8 split de transacciones). |
| `StripeWebhookHandler` | `auth/subscription/service/StripeWebhookHandler` | Procesa los 4 event types con idempotencia. |
| `Notifier` con 4 nuevos métodos | reuso `notification/MailNotifier` | D11. |
| `Auditor` con 13 nuevos events | reuso `audit/AuditEventType` | D14. |

---

## 7. Orden de implementación — 8 lotes con HITOs

```
LOTE A — Setup: dependencia, env vars, migración V4, entidades
  └── pom.xml: + stripe-java <última estable>
  └── STACK.md §2.3 actualizado con versión exacta
  └── .env.example: STRIPE_API_KEY (RAK), STRIPE_WEBHOOK_SECRET, PRICE IDs, FRONTEND_URL
  └── V4__subscriptions.sql (DDL SPEC §7)
  └── User entity: + stripe_customer_id + linkStripeCustomer(...)
  └── Subscription entity + SubscriptionStatus + BillingPlan enums
  └── StripeWebhookEvent entity
  └── SubscriptionRepository + StripeWebhookEventRepository
  └── StripeConfig (@Configuration setea Stripe.apiKey desde env)
  └── SecurityConfig: + permitAll("/api/v1/webhooks/stripe")
                                                      ← HITO 1 (mvn compile + Flyway V4 aplicada)

LOTE B — StripeAdapter (TAC-M1)
  └── StripeAdapter con métodos:
      · createCustomer(email, name) → String (cus_)
      · createCheckoutSession(customerId, priceId, plan, urls, metadata) → Session
      · createBillingPortalSession(customerId, returnUrl) → Session
      · constructWebhookEvent(rawBody, sigHeader, secret) → Event
  └── RetryPolicy Resilience4j (3×1s/3s/5s) aplicado a llamadas API
  └── Idempotency-Key por llamada (D4)
                                                      ← Lote B verde (sin tests aún, se cubren en F)

LOTE C — SubscriptionService + Controllers síncronos
  └── DTOs: CheckoutSessionRequest, CheckoutSessionResponse, PortalSessionResponse,
            SubscriptionStatusResponse, SubscriptionDto
  └── SubscriptionMapper (excluye stripe_customer_id / stripe_subscription_id, D21)
  └── 4 excepciones + handlers en GlobalExceptionHandler
  └── ValidationMessages: + SUBSCRIPTION_ALREADY_ACTIVE, NO_STRIPE_CUSTOMER,
                          STRIPE_API_ERROR, VALIDATION_INVALID_PLAN
  └── SubscriptionService:
      · createCheckoutSession(userId, plan): D8 split — primero ensureStripeCustomer
        en su propia tx, luego createCheckoutSession Stripe en otra tx
      · openBillingPortal(userId): valida stripe_customer_id, llama Stripe, audita
      · getStatus(userId): consulta repository y mapea
      · isPremium(userId): boolean (consumido por ProfileService — G5)
  └── SubscriptionController: 3 endpoints + OpenAPI completo
  └── G5: extender UserProfileResponse + UserProfileMapper con isPremium
                                                      ← HITO 2 (curl POST /checkout-session devuelve checkoutUrl)

LOTE D — Webhook handler + idempotencia
  └── StripeWebhookController:
      · @PostMapping("/api/v1/webhooks/stripe")
      · @RequestBody String rawBody + @RequestHeader("Stripe-Signature") String sig
      · invoca handler.handle(rawBody, sig)
  └── StripeWebhookHandler:
      · construct event (verifica firma)
      · INSERT stripe_webhook_events RECEIVED (catch UNIQUE → 200 DUPLICATE)
      · switch eventType: 4 handlers privados
      · checkout.session.completed → INSERT subscription ACTIVE + email + audit
      · customer.subscription.updated → detectar transición + sync + email
        (incluye SUBSCRIPTION_REACTIVATED si cancel_at_period_end false→true)
      · customer.subscription.deleted → CANCELLED + email + audit
      · invoice.payment_failed → PAST_DUE + email + audit
      · UPDATE stripe_webhook_events PROCESSED
  └── 3 excepciones: WebhookSignatureInvalidException + ... + handler en Global
  └── AuditEventType: +13 entries
                                                      ← HITO 3 (curl con `stripe trigger` o cURL manual con webhook simulado responde 200, fila en subscriptions)

LOTE E — Notification + 4 templates email
  └── notification/dto/{WelcomePremiumCommand, CancelScheduledCommand,
                        SubscriptionExpiredCommand, PaymentFailedCommand}.java
  └── Notifier interface: + 4 métodos
  └── MailNotifier: implementa los 4 con @Async + Thymeleaf + audit on fail
  └── resources/templates/email/welcome-premium.html (variables D13)
  └── resources/templates/email/subscription-scheduled-to-cancel.html
  └── resources/templates/email/subscription-expired.html
  └── resources/templates/email/subscription-payment-failed.html
                                                      ← Lote E verde (verificado en HITO 3 IT)

LOTE F — Tests backend (unit + IT con WireMock)
  └── Unit:
      · SubscriptionMapperTest — no leak de stripe_customer_id / stripe_subscription_id (D21)
      · SubscriptionServiceTest — happy paths checkout + portal + getStatus + isPremium
                                   + 409 SUBSCRIPTION_ALREADY_ACTIVE
                                   + 409 NO_STRIPE_CUSTOMER
                                   + 502 STRIPE_API_ERROR (con RetryPolicy verificado)
      · StripeWebhookHandlerTest — 4 event types happy + idempotencia (mismo event 100×)
                                    + signature inválida + orphan + processing error
  └── IT:
      · SubscriptionFlowIT (WireMock Stripe + Postgres real):
        · happy completo: register → login → MFA → POST checkout-session
          → simular webhook checkout.session.completed → GET /me isPremium=true
        · cancel flow: webhook customer.subscription.updated cancel_at_period_end=true
          → SUBSCRIPTION_CANCELLED_SCHEDULED + email
        · expire flow: webhook customer.subscription.deleted → CANCELLED + email
        · payment_failed flow: → PAST_DUE + email
        · webhook firma inválida → 400
        · webhook duplicado 100× → solo 1 PROCESSED, 99 DUPLICATE
      · OpenApiContractIT extender — 3 endpoints subscription documentados
                                                      ← HITO 4 (`mvn verify` BUILD SUCCESS)

LOTE G — Frontend
  └── types/api.ts: + SubscriptionStatusResponse, SubscriptionDto, BillingPlan, etc.
  └── features/subscription/api/subscriptionApi.ts: getMe, createCheckoutSession, openPortal
  └── features/subscription/hooks/{useSubscription, useStartCheckout, useOpenBillingPortal}.ts
  └── features/subscription/components/PremiumPlanCard.tsx
  └── pages/PremiumPage.tsx — orquesta 4 estados A/B/C/D (D17)
  └── pages/PremiumSuccessPage.tsx — polling con `session_id` del query
  └── pages/PremiumCancelPage.tsx — mensaje + back to dashboard
  └── App.tsx: + 3 rutas (/premium, /premium/success, /premium/cancel)
  └── AppHeader: agregar item "Premium" (o "Mi plan") en el menú
  └── messages.es.ts: + códigos de los errores del bundle
                                                      ← HITO 5 (E2E manual con stripe-cli forwarding:
                                                                login → /premium → activar mensual
                                                                → checkout con 4242 4242 4242 4242
                                                                → /premium/success → banner premium
                                                                → portal → cancelar
                                                                → /premium banner "se cancelará el X")

LOTE H — Cierre
  └── APRENDIZAJES.md sección "Día 4 — HU-F06" ([[feedback-actualizar-aprendizajes]])
  └── AGENTS.md "Trabajo activo" actualizado
  └── PR feat/HU-F06-suscripcion-premium → main con plantilla CONVENTIONS §4.1
                                                      ← HITO 6 (PR abierto + CI verde)
```

---

## 8. Estrategia de tests (CONVENTIONS §7, [[feedback-coverage-vs-velocidad]])

**Foco crítico** — tests no-negociables:
- **Signature verification**: webhook con firma alterada → 400.
- **Idempotencia inbound**: 100× el mismo `stripe_event_id` → 1 PROCESSED + 99 DUPLICATE; estado de subscription cambia exactamente 1 vez.
- **No leak de IDs internos**: `stripe_customer_id`, `stripe_subscription_id` NUNCA en respuestas API.
- **No PII en audit**: emails / tarjetas / nombres NO en `details` de eventos `STRIPE_*`.

**Skip explícito** [[feedback-coverage-vs-velocidad]]:
- Tests de las 4 plantillas Thymeleaf (renderizado verificable por inspección visual en MailHog).
- Tests de `PremiumPlanCard` / `PremiumSuccessPage` / `PremiumCancelPage` (componentes UI puros).
- Tests del controller del webhook (cubierto por `SubscriptionFlowIT`).

---

## 9. Trazabilidad criterios SPEC §11.1 → artefacto

| Escenario Gherkin | Verificado por |
|---|---|
| Activación mensual exitosa | `SubscriptionFlowIT#shouldActivateMonthly` + HITO 5 manual |
| Activación anual exitosa | `SubscriptionServiceTest#shouldActivateYearly` |
| Suscripción ya activa → 409 | `SubscriptionServiceTest#shouldRejectDuplicateActiveSubscription` + `MeFlowIT` |
| Cancelación vía Portal (v1.2) | `SubscriptionFlowIT#shouldHandleCancelAtPeriodEndWebhook` + HITO 5 manual |
| Reactivación vía Portal (v1.2) | `SubscriptionFlowIT#shouldHandleReactivationWebhook` |
| Expiración tras cancelación | `SubscriptionFlowIT#shouldHandleSubscriptionDeletedWebhook` |
| Fallo de renovación → PAST_DUE | `SubscriptionFlowIT#shouldHandlePaymentFailedWebhook` |
| Re-suscripción tras cancellation | `SubscriptionServiceTest#shouldAllowResubscribeAfterCancelled` |
| Webhook firma inválida | `StripeWebhookHandlerTest#shouldRejectInvalidSignature` + `SubscriptionFlowIT` |
| Webhook duplicado | `StripeWebhookHandlerTest#shouldDeduplicateByEventId` (test parametrizado 100×) |
| Portal sin Stripe customer | `SubscriptionServiceTest#shouldReject409WhenNoStripeCustomer` |
| Stripe API caído + retry | `StripeAdapterTest#shouldRetryThreeTimesOn503` (WireMock) |
| Validación plan inválido | `SubscriptionServiceTest` parametrizado |
| `stripe_customer_id` no en responses | `SubscriptionMapperTest#shouldNotLeakStripeIds` |

---

## 10. Preguntas abiertas

| # | Pregunta | Propuesta |
|---|---|---|
| **Q1** | ¿Las 2 Prices (`STRIPE_PRICE_MONTHLY`, `STRIPE_PRICE_YEARLY`) existen ya en el Stripe Dashboard del usuario, o las creamos al arranque? | **Propuesta**: el usuario las crea manualmente UNA VEZ en Stripe Dashboard Test Mode (USD $12/mo, USD $120/yr) y copia los IDs al `.env`. README documenta el paso. NO se intenta crearlas programáticamente (over-engineering MVP). |
| **Q2** | ¿Customer Portal configurado de antemano en Stripe Dashboard? Las features (cancel, update payment, view invoices) requieren activación manual. | **Propuesta**: igual que Q1 — manual UNA VEZ, documentado en README. Stripe Dashboard → Settings → Billing → Customer Portal → Activate. |
| **Q3** | El SPEC §10.2 incluye un constraint "Datos de tarjeta NUNCA pasan por servidores de BloomTrade". Como usamos Checkout hosted + Portal hosted, esto es **estructuralmente imposible** — no requiere test. ¿Cómo lo demuestro en el DoD? | **Propuesta**: assertion en el test de inspección de código (grep del backend buscando `card_number`, `cvv`, `expMonth`, `expYear` → debe devolver 0). Test trivial pero satisface la DoD. |
| **Q4** | El "happy" del HITO 5 requiere `stripe-cli` corriendo. Si el evaluador del curso no lo tiene, ¿cómo demostramos el bundle? | **Propuesta**: README incluye sección "Demo sin stripe-cli" que muestra el flujo del checkout (visible en Stripe Dashboard) y explica que el webhook se procesará automáticamente en producción real. Para la demo grabada del curso usamos stripe-cli. |

---

## 11. Cronograma

| Bloque | Estimación | Lotes |
|---|---|---|
| Mañana Día 4 | ~3h | A (setup + migración + entidades) + B (StripeAdapter) |
| Mediodía | ~2h | C (Service + Controllers síncronos) — incluye G5 isPremium en /me |
| Tarde temprana | ~2h | D (WebhookHandler) + HITO 3 |
| Tarde tardía | ~1.5h | E (templates email) + F (tests backend) → HITO 4 |
| Noche | ~2h | G (frontend) → HITO 5 |
| Cierre | ~30 min | H (APRENDIZAJES + AGENTS + PR) → HITO 6 |

**Total: ~11h** — apretado. Si algo derrama a Día 5, se prioriza Lote F (tests) sobre Lote G (frontend) — el backend es la deuda crítica para SonarCloud.

**Riesgos:**
- R1 — stripe-java version drift (D1): si la última estable trae breaking changes vs docs en español, agrega ~30 min de debugging. Mitigación: arrancar Lote A primero para fallar rápido.
- R2 — Customer Portal config en Dashboard (Q2): si no está activado, el botón "Gestionar suscripción" falla con error de Stripe. Mitigación: documentado en README + checklist DoD.
- R3 — `stripe-cli` no instalado en máquina del usuario: HITO 5 no completable visualmente. Mitigación: instalación trivial en Windows con `scoop install stripe-cli` (registrar en README).
- R4 — Webhook handling es la primera transacción cross-system del proyecto. Si hay un bug subtle en la idempotencia, costo de debug puede ser alto. Mitigación: SubscriptionFlowIT con WireMock cubre el caso desde Lote F.

---

## 12. Definition of Done de este bundle

Se considera terminado cuando estén verdes todas las casillas de SPEC v1.2 §15 y, además:

- ☐ 8 lotes implementados, HITOs 1-6 verdes
- ☐ `mvn verify` verde (~25-30 tests nuevos)
- ☐ `npm run lint && build` verde
- ☐ E2E manual con stripe-cli: register → login → /premium → activar mensual → 4242 4242 4242 4242 → /premium/success → ver banner premium → portal → cancelar → ver banner "se cancelará el X"
- ☐ Demo visual de Stripe Dashboard mostrando Customer, Subscription, e Invoice creados
- ☐ Inspección manual: respuestas API NO contienen `stripe_customer_id` ni `stripe_subscription_id`
- ☐ Inspección manual: no aparece la substring `card_number` ni `cvv` en logs durante un checkout
- ☐ Swagger UI muestra 3 endpoints subscription + webhook
- ☐ README actualizado con sección "Setup Stripe" (crear RAK, Prices, Portal config, stripe-cli)
- ☐ `.env.example` actualizado con las 5 variables Stripe + comentarios
- ☐ STACK.md §2.3 actualizado con versión exacta de stripe-java
- ☐ Migración V4 aplicada UNA vez (verificable en `flyway_schema_history`)
- ☐ PR abierto con plantilla CONVENTIONS §4.1 + checklist DoD
- ☐ APRENDIZAJES.md con sección "Día 4 — HU-F06" ([[feedback-actualizar-aprendizajes]])
- ☐ AGENTS.md "Trabajo activo" actualizado

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-21 | Versión inicial | Plan técnico derivado de SPEC v1.2 + consulta a skill `stripe-best-practices` |

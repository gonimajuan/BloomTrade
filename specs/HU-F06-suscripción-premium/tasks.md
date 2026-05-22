# tasks.md — HU-F06 Suscripción premium con Stripe

> Descomposición granular del `plan.md` (SDD Paso 3).
> Cadencia: lotes lógicos con validación en HITOs [[feedback-cadencia-sdd]].
> Rama: `feat/HU-F06-suscripcion-premium`. Commits con `refs HU-F06 specs/HU-F06-suscripción-premium/SPEC.md` + `Co-authored-by: Claude <noreply@anthropic.com>`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho · ✗ cancelado/diferido

---

## Lote A — Setup: dependencia + env vars + migración V4 + entidades

- ☐ **T1.1** `backend/pom.xml` MODIFICADO: + `<dependency>com.stripe:stripe-java:<latest>` (verificar Maven Central al instalar, target ~28.x).
- ☐ **T1.2** `STACK.md` §2.3 MODIFICADO: agregar fila `stripe-java <version exacta>` con cell "Uso: integración con Stripe (Checkout, Portal, Subscriptions, Webhooks)".
- ☐ **T1.3** `.env.example` MODIFICADO: agregar bloque Stripe con `STRIPE_API_KEY=rk_test_REPLACE` (RAK), `STRIPE_WEBHOOK_SECRET=whsec_REPLACE_FROM_CLI`, `STRIPE_PRICE_MONTHLY=price_REPLACE`, `STRIPE_PRICE_YEARLY=price_REPLACE`, `FRONTEND_URL=http://localhost:5173`. Eliminar/renombrar `STRIPE_SECRET_KEY` si existe (D2).
- ☐ **T1.4** `docker-compose.yml` MODIFICADO: agregar `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_MONTHLY`, `STRIPE_PRICE_YEARLY`, `FRONTEND_URL` al servicio `backend` (con `:?` para que falle si están vacíos, mismo patrón que JWT_SECRET).
- ☐ **T1.5** `backend/src/main/resources/db/migration/V4__subscriptions.sql` — DDL completo de SPEC §7.2/7.3:
    - `ALTER TABLE app.users ADD COLUMN stripe_customer_id VARCHAR(50)` + `CREATE INDEX idx_users_stripe_customer_id`
    - `CREATE TABLE app.subscriptions` con campos, check constraints `chk_subscription_plan`/`chk_subscription_status`, `uq_subscription_stripe_id UNIQUE`, índices, y `uq_one_active_subscription_per_user` (UNIQUE PARTIAL WHERE status='ACTIVE')
    - `CREATE TABLE app.stripe_webhook_events` con `stripe_event_id UNIQUE`, check `chk_webhook_status`, índices
- ☐ **T1.6** `auth/domain/User.java` MODIFICADO: + `@Column(name="stripe_customer_id", length=50) String stripeCustomerId` (nullable) + método de dominio `linkStripeCustomer(String customerId)` (análogo a `applyProfileUpdate` HU-F04 D19) que setea SOLO si `stripeCustomerId == null` (idempotente).
- ☐ **T1.7** `auth/subscription/domain/{SubscriptionStatus, BillingPlan}.java` — enums (ACTIVE, CANCELLED, PAST_DUE; MONTHLY, YEARLY).
- ☐ **T1.8** `auth/subscription/domain/Subscription.java` — entity JPA mapeada a `app.subscriptions` con `@Enumerated(STRING)` para status/plan, `@CreationTimestamp/@UpdateTimestamp`. Sin Lombok `@Data` (CONVENTIONS §5.4.3); usar `@Getter` + métodos de dominio `markAsActive`, `markAsCancelled`, `markAsPastDue`, `scheduleCancellation(periodEnd)`, `reactivate()`.
- ☐ **T1.9** `auth/subscription/domain/StripeWebhookEvent.java` — entity para `app.stripe_webhook_events`. Campos: id, stripeEventId, eventType, status (enum interno `RECEIVED/PROCESSED/FAILED/DUPLICATE/ORPHAN`), receivedAt, processedAt, errorMessage, payload (JSONB con `@JdbcTypeCode(SqlTypes.JSON)`).
- ☐ **T1.10** `auth/subscription/repository/SubscriptionRepository.java` — JpaRepository. Métodos: `findByUserIdAndStatus(UUID, SubscriptionStatus)`, `findFirstByUserIdOrderByCreatedAtDesc(UUID)` (para getCurrent en SubscriptionStatusResponse), `findByStripeSubscriptionId(String)`, `existsByUserIdAndStatus(UUID, SubscriptionStatus)` (para isPremium).
- ☐ **T1.11** `auth/subscription/repository/StripeWebhookEventRepository.java` — JpaRepository. Método: `existsByStripeEventId(String)` (para idempotency pre-check; opcional, el UNIQUE constraint también atrapa).
- ☐ **T1.12** `integration/stripe/StripeConfig.java` — `@Configuration` que lee `${STRIPE_API_KEY}` y setea `Stripe.apiKey = key` al arrancar. Falla rápido si vacío (mismo patrón JwtService HU-F02).
- ☐ **T1.13** `config/SecurityConfig.java` MODIFICADO: + `.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe").permitAll()` (autoriza con HMAC, no JWT).
- ☐ **T1.14** Smoke local: `docker compose up -d --build backend` → verificar logs de Flyway aplicando V4 + Hibernate sin errores. `psql ... \d app.subscriptions` confirma columnas + constraints + índices. **← HITO 1** (compile + Flyway V4 verde).

## Lote B — StripeAdapter (TAC-M1 intermediario)

- ☐ **T2.1** `integration/stripe/StripeAdapter.java` — clase con métodos públicos:
    - `createCustomer(String email, String name) throws StripeException` → retorna `String customerId` (cus_...)
    - `createCheckoutSession(String customerId, String priceId, BillingPlan plan, String successUrl, String cancelUrl, UUID userId) throws StripeException` → retorna `Session`. **No incluye `payment_method_types`** (D3 trap). Usa `Idempotency-Key` UUID.randomUUID() (D4).
    - `createBillingPortalSession(String customerId, String returnUrl) throws StripeException` → retorna `Session` del portal.
    - `retrieveSubscription(String subscriptionId) throws StripeException` → para obtener detalles tras webhook.
    - `constructWebhookEvent(String rawBody, String sigHeader, String webhookSecret) throws SignatureVerificationException` → wraps `Webhook.constructEvent(...)`.
- ☐ **T2.2** Anotar los métodos con `@Retry(name="stripeApi")` de Resilience4j; configurar `resilience4j.retry.instances.stripeApi` en `application.yml` con `maxAttempts=3`, `waitDuration=1s` con `exponentialBackoff` (1s, 3s, 5s aprox). Excluir `SignatureVerificationException` y `InvalidRequestException` del retry (errores deterministas).
- ☐ **T2.3** `auth/subscription/exception/StripeApiException.java` — `RuntimeException` que envuelve `StripeException`. El handler en GlobalExceptionHandler la mapea a 502 STRIPE_API_ERROR.

## Lote C — SubscriptionService + Controllers síncronos + extender /me con isPremium

- ☐ **T3.1** DTOs en `auth/subscription/dto/`:
    - `CheckoutSessionRequest(@NotNull BillingPlan plan)` — record con validation
    - `CheckoutSessionResponse(String checkoutUrl, String sessionId)`
    - `PortalSessionResponse(String portalUrl)`
    - `SubscriptionDto(UUID id, BillingPlan plan, SubscriptionStatus status, Instant currentPeriodStart, Instant currentPeriodEnd, boolean cancelAtPeriodEnd, Instant createdAt)`
    - `SubscriptionStatusResponse(boolean isPremium, SubscriptionDto subscription)` — subscription puede ser null
- ☐ **T3.2** `auth/subscription/mapper/SubscriptionMapper.java` — MapStruct. Mapea `Subscription` → `SubscriptionDto`. **NO mapea `stripeCustomerId` ni `stripeSubscriptionId`** (D21). Test SubscriptionMapperTest (en Lote F) verifica el JSON no los contiene.
- ☐ **T3.3** Excepciones en `auth/subscription/exception/`:
    - `SubscriptionAlreadyActiveException` (con campo `currentPeriodEnd` para incluir en mensaje)
    - `NoStripeCustomerException`
    - `WebhookSignatureInvalidException` (para Lote D)
- ☐ **T3.4** `shared/web/GlobalExceptionHandler.java` MODIFICADO: + 4 handlers (409 SUBSCRIPTION_ALREADY_ACTIVE, 409 NO_STRIPE_CUSTOMER, 502 STRIPE_API_ERROR, 400 WEBHOOK_SIGNATURE_INVALID).
- ☐ **T3.5** `validation-messages.properties` MODIFICADO: + `SUBSCRIPTION_ALREADY_ACTIVE=Ya tienes una suscripción activa. Vence el {0}.`, + `NO_STRIPE_CUSTOMER=No tienes una suscripción que gestionar.`, + `STRIPE_API_ERROR=Stripe no responde. Intenta de nuevo en unos minutos.`, + `WEBHOOK_SIGNATURE_INVALID=Firma de webhook inválida.`, + `VALIDATION_INVALID_PLAN=Plan inválido (debe ser MONTHLY o YEARLY).`
- ☐ **T3.6** `auth/subscription/service/SubscriptionService.java`:
    - `@Transactional createCheckoutSession(UUID userId, BillingPlan plan)`:
        1. Lock optimistic check: `subscriptionRepository.existsByUserIdAndStatus(userId, ACTIVE)` → si true, lanza `SubscriptionAlreadyActiveException`.
        2. `ensureStripeCustomer(userId)` — método interno con `@Transactional(propagation=REQUIRES_NEW)` (D8): si user.stripeCustomerId == null → `stripeAdapter.createCustomer(...)` + `user.linkStripeCustomer(...)` + save. Si ya existe, return.
        3. `stripeAdapter.createCheckoutSession(...)` con priceId leído de env por plan.
        4. Audit `CHECKOUT_SESSION_CREATED` con details (plan, stripeSessionId, stripeCustomerId).
        5. Return `CheckoutSessionResponse(session.getUrl(), session.getId())`.
        6. Catch `StripeApiException` → audit `CHECKOUT_SESSION_FAILED` + relanza.
    - `@Transactional openBillingPortal(UUID userId)`:
        1. `user.getStripeCustomerId()` — si null lanza `NoStripeCustomerException` (409).
        2. `stripeAdapter.createBillingPortalSession(customerId, returnUrl)` con `returnUrl=${FRONTEND_URL}/premium`.
        3. Audit `BILLING_PORTAL_SESSION_CREATED`.
        4. Return `PortalSessionResponse(session.getUrl())`.
    - `@Transactional(readOnly=true) getStatus(UUID userId): SubscriptionStatusResponse`:
        1. `findFirstByUserIdOrderByCreatedAtDesc` → puede ser null.
        2. `isPremium = (sub != null && sub.getStatus() == ACTIVE)`.
        3. Map a DTO.
    - `@Transactional(readOnly=true) isPremium(UUID userId): boolean` (consumido por ProfileService G5).
- ☐ **T3.7** `auth/subscription/controller/SubscriptionController.java`:
    - `@RequestMapping("/api/v1/subscriptions")` + `@Tag(name="Subscriptions")`.
    - `POST /checkout-session` con `@Valid @RequestBody CheckoutSessionRequest`. OpenAPI: 200, 400, 401, 409, 502.
    - `GET /me`. OpenAPI: 200, 401.
    - `POST /portal-session`. OpenAPI: 200, 401, 409, 502.
    - userId desde `@AuthenticationPrincipal AuthenticatedUser`.
- ☐ **T3.8** **G5** — extender HU-F04 `UserProfileResponse` con `boolean isPremium`:
    - `auth/profile/dto/UserProfileResponse.java` MODIFICADO: + `boolean isPremium` como último campo.
    - `auth/profile/service/ProfileService.java` MODIFICADO: inyectar `SubscriptionService`; en `getMe(...)` y en `updateMe(...)` post-update, llamar `subscriptionService.isPremium(userId)` y pasar al constructor del response.
    - `auth/profile/mapper/UserProfileMapper.java` MODIFICADO: aceptar `boolean isPremium` como segundo argumento de `toResponse(...)` (MapStruct `@Mapping(target="isPremium", source="isPremium")` con segundo param).
    - Actualizar `UserProfileMapperTest` (Lote D HU-F04) — agregar test que verifica que el campo está presente y respeta el flag.
    - Actualizar `ProfileServiceTest` para mockear `subscriptionService.isPremium(...)`.
- ☐ **T3.9** AuditEventType MODIFICADO: + `CHECKOUT_SESSION_CREATED`, `CHECKOUT_SESSION_FAILED`, `BILLING_PORTAL_SESSION_CREATED`.
- ☐ **T3.10** Smoke local: `docker compose up -d --build backend` → con token válido, `curl -X POST .../checkout-session -d '{"plan":"MONTHLY"}'` → 200 con `checkoutUrl` que apunta a `https://checkout.stripe.com/...`. **← HITO 2**.

## Lote D — WebhookHandler + idempotencia + 4 event types

- ☐ **T4.1** `auth/subscription/service/StripeWebhookHandler.java`:
    - `handle(String rawBody, String sigHeader, String ipOrigin)` — método público invocado por el controller.
    - Try: `Event event = stripeAdapter.constructWebhookEvent(rawBody, sigHeader, ${STRIPE_WEBHOOK_SECRET})`.
    - Catch `SignatureVerificationException` → audit `STRIPE_WEBHOOK_SIGNATURE_FAILED` (WARNING) + lanza `WebhookSignatureInvalidException`.
    - Try INSERT en `stripe_webhook_events(stripe_event_id, event_type, status='RECEIVED', payload=rawBody)`; catch `DataIntegrityViolationException` (UNIQUE) → audit `STRIPE_WEBHOOK_DUPLICATE` + return 200 sin reprocesar.
    - Audit `STRIPE_WEBHOOK_RECEIVED` con `{stripeEventId, eventType}`.
    - Switch event.getType() — 4 handlers privados (T4.2–T4.5).
    - Si éxito: `UPDATE stripe_webhook_events SET status='PROCESSED', processed_at=now() WHERE stripe_event_id=...`.
    - Si excepción en el procesamiento (no signature): audit `STRIPE_WEBHOOK_PROCESSING_FAILED` + UPDATE `status='FAILED'` + relanza (transacción rollback → Stripe reintentará).
    - Todo en una sola `@Transactional` por evento.
- ☐ **T4.2** `handleCheckoutSessionCompleted(Session session)`:
    - Extract `subscription_id` y `customer_id` del session; `metadata.userId`, `metadata.plan`.
    - `subscription = stripeAdapter.retrieveSubscription(subscription_id)` para obtener period_start, period_end, status.
    - INSERT en `app.subscriptions` con `status=ACTIVE, cancel_at_period_end=false`.
    - Audit `SUBSCRIPTION_ACTIVATED` con `{subscriptionId, plan, periodEnd, stripeSubscriptionId}`.
    - Enqueue `notifier.sendWelcomePremiumEmail(...)` (Lote E).
- ☐ **T4.3** `handleSubscriptionUpdated(Subscription stripeSub)`:
    - Find `app.subscriptions` by stripeSubscriptionId; si no existe → audit `STRIPE_WEBHOOK_ORPHAN` + return (no error).
    - Detect transición de `cancel_at_period_end`:
        - false → true: `sub.scheduleCancellation(periodEnd)` + audit `SUBSCRIPTION_CANCELLED_SCHEDULED` + email `sendCancellationScheduledEmail(...)`.
        - true → false: `sub.reactivate()` + audit `SUBSCRIPTION_REACTIVATED` + email "tu suscripción continúa activa".
    - Detect cambio de `status` (mapeo Stripe→interno) y sync `current_period_end`.
- ☐ **T4.4** `handleSubscriptionDeleted(Subscription stripeSub)`:
    - Find local; si no existe → orphan.
    - `sub.markAsCancelled()` (status=CANCELLED).
    - Audit `SUBSCRIPTION_TERMINATED` con `{subscriptionId, reason='PERIOD_END'}`.
    - Email `sendSubscriptionExpiredEmail(...)`.
- ☐ **T4.5** `handleInvoicePaymentFailed(Invoice invoice)`:
    - Find local by `invoice.subscription`; si no existe → orphan.
    - `sub.markAsPastDue()` (status=PAST_DUE, sin grace period — D10).
    - Audit `SUBSCRIPTION_PAYMENT_FAILED` con `{subscriptionId, stripeInvoiceId}`.
    - Email `sendSubscriptionPaymentFailedEmail(...)`.
- ☐ **T4.6** `auth/subscription/controller/StripeWebhookController.java`:
    - `@PostMapping("/api/v1/webhooks/stripe")` (sin `@Tag` ya que es interno; o `@Tag(name="Webhooks")` para Swagger).
    - `@RequestBody String rawBody, @RequestHeader("Stripe-Signature") String signature, HttpServletRequest httpRequest`.
    - Llama `handler.handle(rawBody, signature, clientIp(httpRequest))`.
    - Return `ResponseEntity.ok().build()` (sin body).
- ☐ **T4.7** AuditEventType MODIFICADO: + `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_CANCELLED_SCHEDULED`, `SUBSCRIPTION_REACTIVATED`, `SUBSCRIPTION_TERMINATED`, `SUBSCRIPTION_PAYMENT_FAILED`, `STRIPE_WEBHOOK_RECEIVED`, `STRIPE_WEBHOOK_DUPLICATE`, `STRIPE_WEBHOOK_SIGNATURE_FAILED`, `STRIPE_WEBHOOK_ORPHAN`, `STRIPE_WEBHOOK_PROCESSING_FAILED` (10 entries — sumadas a las 3 del Lote C = 13 totales del bundle).
- ☐ **T4.8** Smoke local con stripe-cli: instalar `stripe-cli` si falta (`scoop install stripe-cli`); `stripe login` (browser); `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe` deja corriendo en otra terminal; `stripe trigger checkout.session.completed` → backend procesa, fila aparece en `app.subscriptions`. **← HITO 3**.

## Lote E — Notification + 4 templates email

- ☐ **T5.1** `notification/dto/{WelcomePremiumCommand, CancellationScheduledCommand, SubscriptionExpiredCommand, SubscriptionPaymentFailedCommand}.java` — records con campos `{userId, email, nombreCompleto, plan, currentPeriodEnd}` (los que apliquen).
- ☐ **T5.2** `notification/Notifier.java` MODIFICADO: + 4 métodos: `sendWelcomePremiumEmail(WelcomePremiumCommand)`, `sendCancellationScheduledEmail(...)`, `sendSubscriptionExpiredEmail(...)`, `sendSubscriptionPaymentFailedEmail(...)`.
- ☐ **T5.3** `notification/MailNotifier.java` MODIFICADO: implementa los 4 métodos con `@Async` + JavaMailSender + Thymeleaf + try/catch que audita `*_EMAIL_FAILED` en caso de fallo (mismo patrón welcome/otp).
- ☐ **T5.4** AuditEventType MODIFICADO: + `WELCOME_PREMIUM_EMAIL_FAILED`, `CANCELLATION_SCHEDULED_EMAIL_FAILED`, `SUBSCRIPTION_EXPIRED_EMAIL_FAILED`, `SUBSCRIPTION_PAYMENT_FAILED_EMAIL_FAILED` (4 entries de fallback de email).
- ☐ **T5.5** `resources/templates/email/welcome-premium.html` — Thymeleaf, estilo inline-CSS análogo a `welcome.html`. Variables `{nombreCompleto, plan, currentPeriodEnd}`. Mensaje "Bienvenido a Premium, disfruta de las funcionalidades exclusivas".
- ☐ **T5.6** `resources/templates/email/subscription-scheduled-to-cancel.html` — "Tu suscripción se cancelará el {currentPeriodEnd}. Mantienes acceso hasta esa fecha. Puedes reactivarla desde tu portal de pagos en cualquier momento."
- ☐ **T5.7** `resources/templates/email/subscription-expired.html` — "Tu suscripción premium ha finalizado. Re-suscríbete cuando quieras."
- ☐ **T5.8** `resources/templates/email/subscription-payment-failed.html` — "Tu pago de renovación falló. Has perdido acceso premium. Actualiza tu método de pago en el portal o re-suscríbete."

## Lote F — Tests backend (unit + IT con WireMock)

- ☐ **T6.1** Unit `SubscriptionMapperTest` — verifica que el JSON serializado de `SubscriptionDto` NO contiene `stripeCustomerId`, `stripeSubscriptionId`, ni las substrings `cus_` y `sub_` (D21 — análogo al test de no-leak `passwordHash`).
- ☐ **T6.2** Unit `SubscriptionServiceTest` (Mockito):
    - `shouldCreateCheckoutSessionWhenNoActiveSubscription` (happy)
    - `shouldCreateCustomerOnFirstCheckout` (verifica linkStripeCustomer)
    - `shouldReuseExistingCustomerIdOnSecondCheckout` (no doble createCustomer)
    - `shouldReject409WhenAlreadyActive`
    - `shouldOpenBillingPortalWhenStripeCustomerExists`
    - `shouldReject409NoStripeCustomerWhenNeverPaid`
    - `shouldReturnIsPremiumTrueWhenActiveSubscriptionExists`
    - `shouldReturnIsPremiumFalseWhenNoSubOrCancelled`
    - `shouldAuditCHECKOUT_SESSION_FAILEDAndRethrowOnStripeError`
- ☐ **T6.3** Unit `StripeWebhookHandlerTest` (Mockito):
    - `shouldRejectInvalidSignature` (signature exception → audit WARNING + relanza)
    - `shouldDeduplicateByEventIdOn100Repetitions` (parametrizado/loop — verifica idempotencia)
    - `shouldHandleCheckoutSessionCompleted` (INSERT subscription + audit + email)
    - `shouldHandleSubscriptionUpdatedCancelTransition` (false→true emite SUBSCRIPTION_CANCELLED_SCHEDULED + email)
    - `shouldHandleSubscriptionUpdatedReactivateTransition` (true→false emite SUBSCRIPTION_REACTIVATED)
    - `shouldHandleSubscriptionDeleted` (status=CANCELLED + email)
    - `shouldHandleInvoicePaymentFailed` (status=PAST_DUE + email — D10 inmediato)
    - `shouldEmitOrphanWhenSubscriptionNotFoundLocally`
- ☐ **T6.4** Unit `StripeAdapterTest` (Mockito mockeando `Stripe`/`StripeException`):
    - `shouldRetryThreeTimesWithExponentialBackoff` (verifica RetryPolicy con WireMock 503)
    - `shouldNotRetryOnSignatureVerificationException` (terminal)
    - `shouldPassIdempotencyKeyOnCheckoutCreation`
    - `shouldNotIncludePaymentMethodTypesInCheckoutParams` (D3 trap — inspecciona params builder)
- ☐ **T6.5** IT `SubscriptionFlowIT` (WireMock + Postgres real perfil test):
    - Setup WireMock simulando endpoints Stripe (customers/create, checkout/sessions, billing_portal/sessions).
    - `shouldActivateMonthlySubscription`: registro → login → MFA → POST checkout-session → mock Stripe responde Session → simular webhook `checkout.session.completed` con firma válida → GET /subscriptions/me devuelve isPremium=true + status=ACTIVE.
    - `shouldHandleCancelAtPeriodEndWebhook`: precondición fila ACTIVE → simular webhook `customer.subscription.updated` con cancel_at_period_end=true → fila sigue ACTIVE pero cancelAtPeriodEnd=true → audit SUBSCRIPTION_CANCELLED_SCHEDULED.
    - `shouldHandleReactivationWebhook`: precondición cancel_at_period_end=true → webhook con cancel_at_period_end=false → audit SUBSCRIPTION_REACTIVATED.
    - `shouldHandleSubscriptionDeletedWebhook`: → status=CANCELLED + audit SUBSCRIPTION_TERMINATED.
    - `shouldHandlePaymentFailedWebhook`: → status=PAST_DUE + audit SUBSCRIPTION_PAYMENT_FAILED (D10 inmediato).
    - `shouldRejectInvalidSignature`: webhook con firma alterada → 400 + audit STRIPE_WEBHOOK_SIGNATURE_FAILED.
    - `shouldDeduplicateRepeatedWebhook`: enviar el mismo evento 5 veces → solo 1 PROCESSED, 4 DUPLICATE, app.subscriptions tiene 1 fila.
    - `shouldExtendMeResponseWithIsPremium`: post-activación, GET /api/v1/me incluye `isPremium=true` (G5 trazabilidad).
- ☐ **T6.6** IT `OpenApiContractIT` MODIFICADO — + 3 tests verificando que GET/POST `/subscriptions/me`, POST `/subscriptions/checkout-session`, POST `/subscriptions/portal-session` y POST `/webhooks/stripe` están documentados con `@Operation`.
- ☐ **T6.7** Test de inspección de código (Q3 plan): grep en `backend/src/main` buscando `card_number`, `cvv`, `expMonth`, `expYear`, `cardNumber` → debe devolver 0 matches. Ejecutar como parte del Lote F.
- ☐ **T6.8** `mvn verify` completo. **← HITO 4** (BUILD SUCCESS, +25-30 tests).

## Lote G — Frontend (PremiumPage + hooks + rutas)

- ☐ **T7.1** `frontend/src/types/api.ts` MODIFICADO: + `BillingPlan` ('MONTHLY'|'YEARLY'), + `SubscriptionStatus` ('ACTIVE'|'CANCELLED'|'PAST_DUE'), + `SubscriptionDto`, + `SubscriptionStatusResponse`, + `CheckoutSessionRequest`/`Response`, + `PortalSessionResponse`. Extender `UserProfileResponse` con `isPremium: boolean`.
- ☐ **T7.2** `frontend/src/features/subscription/api/subscriptionApi.ts` — wrappers de axios: `getStatus()`, `createCheckoutSession(plan)`, `openPortal()`.
- ☐ **T7.3** `frontend/src/features/subscription/hooks/useSubscription.ts` — React Query `useQuery(['subscription','me'], getStatus, {staleTime: 30_000})`. Acepta opción `polling: true` para `PremiumSuccessPage` (refetchInterval cada 2s).
- ☐ **T7.4** `frontend/src/features/subscription/hooks/useStartCheckout.ts` — `useMutation(createCheckoutSession)` con `onSuccess: ({checkoutUrl}) => window.location.href = checkoutUrl`.
- ☐ **T7.5** `frontend/src/features/subscription/hooks/useOpenBillingPortal.ts` — `useMutation(openPortal)` con `onSuccess: ({portalUrl}) => window.location.href = portalUrl`.
- ☐ **T7.6** `frontend/src/lib/messages.es.ts` MODIFICADO: + `SUBSCRIPTION_ALREADY_ACTIVE`, + `NO_STRIPE_CUSTOMER`, + `STRIPE_API_ERROR`, + `VALIDATION_INVALID_PLAN`.
- ☐ **T7.7** `frontend/src/features/subscription/components/PremiumPlanCard.tsx` — card reusable con props `{plan, priceLabel, savingsLabel?, ctaLabel, onSelect, disabled}`.
- ☐ **T7.8** `frontend/src/pages/PremiumPage.tsx` — orquesta 4 estados (D17):
    - Estado A (sin sub): título "Activa BloomTrade Premium" + 2 cards (mensual/anual) + lista de features premium.
    - Estado B (ACTIVE sin cancel): banner verde "Eres Premium" + plan actual + "Próximo cargo: {periodEnd}" + botón "Gestionar suscripción" (→ `useOpenBillingPortal`).
    - Estado C (ACTIVE con cancel): banner amarillo "Tu suscripción terminará el {periodEnd}" + botón "Gestionar suscripción" para reactivar.
    - Estado D (CANCELLED/PAST_DUE): banner gris/rojo + cards de planes (re-suscribirse).
- ☐ **T7.9** `frontend/src/pages/PremiumSuccessPage.tsx` — lee `session_id` del query string + `useSubscription({polling: true})`. Si `status === 'ACTIVE'` → muestra "¡Bienvenido a Premium! 🌟" + redirect a `/dashboard` tras 3s. Si tras 30s no llega → "Procesando tu pago, te notificaremos por email" + link a /dashboard.
- ☐ **T7.10** `frontend/src/pages/PremiumCancelPage.tsx` — mensaje "Pago no completado. No se hizo cargo." + botones "Volver a intentar" (→ /premium) y "Ir al dashboard".
- ☐ **T7.11** `frontend/src/App.tsx` MODIFICADO: + 3 rutas (`/premium` protegida, `/premium/success` pública, `/premium/cancel` pública).
- ☐ **T7.12** `frontend/src/components/AppHeader.tsx` MODIFICADO: + link "Premium" en el menú (entre "Mi perfil" y "Cerrar sesión").
- ☐ **T7.13** E2E manual con stripe-cli forwarding (D18):
    - `docker compose up -d --build frontend backend`
    - `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe` en otra terminal
    - Browser: registrar usuario → login + MFA → /premium → "Activar mensual" → checkout hosted → 4242 4242 4242 4242 + cualquier CVV + cualquier fecha futura → /premium/success polling → banner premium en /premium → "Gestionar suscripción" → cancel en portal → volver a /premium → banner amarillo "se cancelará el X".
    - Verificar email en MailHog: bienvenida + cancelación programada.
    - Verificar `app.subscriptions` en psql.
    - **← HITO 5** (E2E completo verde).

## Lote H — Cierre

- ☐ **T8.1** `README.md` MODIFICADO: agregar sección "Setup Stripe" con pasos:
    1. Crear cuenta Stripe + Test Mode
    2. Crear 2 Prices (USD $12/mo, USD $120/yr) → copiar IDs
    3. Crear RAK con permisos mínimos (D2) → copiar `rk_test_...`
    4. Activar Customer Portal en Dashboard (Q2)
    5. Instalar `stripe-cli` y `stripe login` (D18)
    6. Copiar `STRIPE_WEBHOOK_SECRET` del output de `stripe listen`
    7. Poblar `.env`
    8. `docker compose up -d`
- ☐ **T8.2** `APRENDIZAJES.md` MODIFICADO: sección "Día 4 — HU-F06 Stripe" en primera persona, estilo Día 0/1/2-3/3 ([[feedback-actualizar-aprendizajes]]). Temas anticipados: webhook signature + raw body en Spring, idempotencia con UNIQUE constraint, Customer Portal vs endpoint custom, RAK vs sk_, Dynamic Payment Methods trap, stripe-cli forwarding local, WireMock para tests IT.
- ☐ **T8.3** `AGENTS.md` MODIFICADO: sección "Trabajo activo" actualizada al cierre (HU-F06, lotes A-H, hitos 1-6, decisiones D1-Dxx).
- ☐ **T8.4** Mensaje de commit grande preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f06.txt` ([[feedback-commit-file-ruta-completa]]).
- ☐ **T8.5** PR `feat/HU-F06-suscripcion-premium` → `main` con plantilla CONVENTIONS §4.1 + checklist DoD. **← HITO 6** (PR abierto + CI verde + listo para squash merge).

## Deuda nueva identificada (para post-bundle)

- **Q1 plan resuelto manualmente**: las 2 Prices se crean a mano en Stripe Dashboard antes del primer deploy. Si en algún momento se quiere automatizar, hay `Price.create(...)` en la SDK, pero solo se justifica si hay muchos planes.
- **Q2 plan resuelto manualmente**: Customer Portal config se activa una vez en Dashboard. Si Stripe lanza Portal Configuration API en el futuro y queremos versionarlo, hay `BillingPortal.Configurations.create(...)`.
- **Webhook reconciliation job**: si por algún motivo se pierde un webhook (Stripe falla retries), no hay reconciliación periódica. Para producción real → job nocturno que lee subscriptions activas en Stripe y compara con BD. Post-MVP.
- **stripe-cli en CI**: actualmente el HITO 4 (mvn verify) usa WireMock, no llama a Stripe real. Si se quiere validar contra Stripe Test Mode real en CI, se puede correr `stripe trigger ...` en un GitHub Action — over-engineering MVP académico.
- **Tests frontend del Lote G saltados** (continúa política [[feedback-coverage-vs-velocidad]]): si SonarCloud lo flagea, registrar.
- **Multi-currency**: hoy hardcodeado USD. Post-MVP si se necesita.
- **Plan upgrade/downgrade in-app**: hoy el usuario debe cancelar y re-suscribirse manualmente. Customer Portal soporta upgrades nativamente si se configura — verificar en Q2 setup.

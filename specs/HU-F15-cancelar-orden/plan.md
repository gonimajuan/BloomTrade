# plan.md — HU-F15 Cancelar orden Market

> Plan técnico derivado de `specs/HU-F15-cancelar-orden/SPEC.md` v1.0.
> Estado: **pendiente de aprobación humana** (SDD Paso 2).
> Modificaciones a `SPEC.md` se difieren a HITO 6 (consolidación v1.1) — patrón HU-F06/F09/F10/F16/F18.

---

## 1. Objetivo

Implementar el **ciclo completo de cancelación de orden Market**, agregando el primer transición outbound-broker al andamio de trading single-user. F15 introduce:

1. **Endpoint nuevo** `POST /api/v1/orders/{id}/cancel` consumido por botones "Cancelar" en `PendingOrdersPanel` (`/portfolio`) y `RecentOrdersWidget` (`/dashboard`).
2. **Adapter** `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)` con **patrón canónico async**: `DELETE /v2/orders/{id}` + polling 200ms × 10 sobre `GET /v2/orders/{id}` hasta `status=canceled`. Tres outcomes: `CANCELED` (happy ≤2s), `PENDING_CANCEL` (timeout), `RACE_FILLED` (Alpaca llenó justo antes).
3. **`TradingService.cancelOrder`** con dispatch BUY/SELL: refund balance (BUY) o restore position (SELL, incluso re-INSERT si fue eliminada) en mismo tx con lock canónico `balances → positions` (heredado D17 F10).
4. **`OrderReconciliationService` v2** — extiende v1 (Día 10) para procesar transiciones outbound del broker: `canceled` (user request o broker TIF day), `rejected` (Alpaca rechaza encolada), `expired` (TIF day). Aplica reverse cuando corresponde.
5. **Estados de orden** — agrega `CANCELED` y `EXPIRED` al enum `OrderStatus`. Migración V6 (D25) extiende `chk_order_status` + 3 columnas nuevas (`cancel_requested_at`, `canceled_at`, `expired_at`).
6. **Idempotencia implícita** por `order.id` + status check — sin clave externa estilo `clientOrderId`.
7. **6 audit events nuevos** (`ORDER_CANCEL_REQUESTED`, `ORDER_CANCELED`, `ORDER_EXPIRED`, `ORDER_DUPLICATE_CANCEL_REQUEST`, `ORDER_CANCEL_REJECTED`, `ORDER_CANCEL_FAILED`) + 2 templates email (reuso EXPIRED via flag).
8. **Frontend** — 2 componentes nuevos (`useCancelOrder` hook + `CancelOrderButton`), 2 modificados (PendingOrdersPanel + RecentOrdersWidget), `OrderResponse` extendido con 4 campos.

**Tamaño estimado:** **~1 día efectivo** (30-35% del esfuerzo de F09, mayor que F10 por el polling + reconcile v2 + 2 estados nuevos en enum). Reparto: Lote A backend enums + V6 + adapter ~20%; Lote B service + tests unit ~25%; Lote C reconcile v2 + tests IT ~25%; Lote D frontend ~15%; Lote E mvn verify + IT cross-user ~10%; Lote F cierre ~5%.

**Lo crítico de la HU:** primer feature que toca el reconcile lazy + primer feature con polling-timeout como happy path alternativo. Patrón es replicable para futuras transiciones outbound del broker (Limit orders, etc., post-MVP).

---

## 2. Decisiones técnicas concretas

> D1–D11 son las cerradas por el cuestionario humano del SDD Paso 1 (Q1–Q11). D12–D21 son las 10 que el SPEC §14 dejó diferidas — ahora cerradas con recomendación + justificación. D22–Dxx serán emergentes durante implementación (siguiendo el patrón D23–D29 de F09, D17–D21 de F10, D25–D27 de F18+F17).

### 2.1 Decisiones cerradas por el cuestionario humano (D1–D11)

| # | Decisión | Justificación |
|---|---|---|
| **D1** | **Solo `PENDING + alpacaOrderId` es cancelable.** El caso edge "placeOrder en pleno polling de submitMarketOrder" queda fuera de scope. | (a) Alineado con ROADMAP §3.4 línea 172 ("solo aplica para órdenes en estado Pending"). (b) Race condition durante `submitMarketOrder` requiere coordinación con `clientOrderLocks` D25 F09 — complejidad desproporcionada para MVP single-user. (c) Toda orden persisted-PENDING tiene alpacaOrderId (D29 F09 asegura), entonces es defensa redundante pero útil. |
| **D2** | **Endpoint `POST /api/v1/orders/{id}/cancel`** (no `DELETE /api/v1/orders/{id}`). | (a) La orden NO se borra de BD — sigue existiendo con `status=CANCELED`. DELETE sugiere borrado físico. (b) "verb-action" sobre state transitions no idempotentes en URL es el patrón explícito de la API. (c) Coherente con futuras transiciones (ej: `/api/v1/orders/{id}/expire` post-MVP). |
| **D3** | **`OrderStatus` agrega 2 valores: `CANCELED` + `EXPIRED`.** `REJECTED` existente cubre el caso outbound `rejected` desde Alpaca. NO agregar `IN_REVIEW / STOPPED / CANCEL_PENDING` (descartado por D17). El reconcile v2 maneja las 3 transiciones outbound (`canceled/rejected/expired`). | (a) ARCHITECTURE.md §9 lista todos estos estados como dominio, pero MVP single-user solo necesita 6 (los 4 actuales + 2 nuevos). (b) Sobre-ingeniería rechazada conscientemente — agregar IN_REVIEW/STOPPED inflaría el enum sin caso de uso real en Alpaca paper. (c) Cierra deuda #27 (HU-F17 D-CANCELLED-STATUS-POSTMVP). |
| **D4** | **Polling canónico async (igual que `submitMarketOrder` F09 D25).** Timeout 2s = 200ms × 10 intentos. Reusa `RestClient` del `AlpacaTradingAdapter` heredado. Tres outcomes: `CancelOutcome.CANCELED`, `CancelOutcome.PENDING_CANCEL`, `CancelOutcome.RACE_FILLED`. | (a) Consistencia con el placeOrder pattern. (b) Trade-off elegido conscientemente sobre la opción "trust + reconcile lazy" — el usuario eligió consistencia hard sobre latencia. (c) El usuario decide aceptar +200-2000ms latencia para garantizar que el balance se refunde en el mismo request HTTP cuando es posible. (d) **NO** se usa otra retry policy distinta a `alpacaApi` (3×1s/3s/5s para el DELETE inicial). El polling de 2s es loop interno, NO retries de la policy. |
| **D5** | **Polling-OK → refund/restore INMEDIATO en mismo tx** que marca `CANCELED`. Lock canónico balances→positions (D17 F10) aplicado en `TradingService.cancelOrder`. | (a) Determinismo MVP single-user — el usuario que canceló ve el efecto en el mismo HTTP response. (b) Una sola TX = una sola unidad de rollback si algo falla. (c) `@TransactionalEventListener(AFTER_COMMIT)` (heredado F09/F10) maneja el audit + email post-commit como side-effect no transaccional. |
| **D6** | **Polling-timeout → `200 OK` + `cancelRequestedAt`** (flag en `Order`), `status` sigue `PENDING`. Reconcile lazy v2 materializa después. NO se introduce 11vo estado al enum. NO se responde 504 GATEWAY_TIMEOUT. | (a) Menor superficie de estados al enum. (b) Reconcile v2 ya está en scope F15 — reusarlo para esta transición evita duplicación. (c) UX clara: frontend renderea fila con `opacity-60` + spinner + label "Cancelando…" hasta el próximo polling 30s. (d) Trade-off: si reconcile nunca corre (usuario nunca refresca portfolio), drift indefinido — pero MVP single-user con `refetchInterval: 30000` + `refetchOnWindowFocus` lo absorbe. |
| **D7** | **Idempotencia implícita por `order.id` + status check.** NO header `X-Idempotency-Key`. NO clave externa. | (a) Identidad natural del recurso ya cumple el rol. (b) 2da call sobre `CANCELED` → `200 OK` con `alreadyCanceledAt`. (c) 2da call sobre `PENDING + cancelRequestedAt` → `200 OK` con `cancelRequestedAt` existente (no segundo `DELETE` a Alpaca). (d) 409 reservado solo para estados terminales no cancelables (`EXECUTED / REJECTED / FAILED / EXPIRED`). |
| **D8** | **Audit `ORDER_CANCELED` + email "Tu orden fue cancelada"** con 2 templates Thymeleaf (`order-canceled-buy.html`, `order-canceled-sell.html`). Templates reusados con flag para `EXPIRED` (decisión D14 abajo). | (a) Simetría con F09/F10 — cada transición genera audit + email. (b) Listener heredado `OrderEventListener` extendido con `handleOrderCanceled` por side. (c) **NO** se envía email en `ORDER_CANCEL_REQUESTED` polling-timeout — esperar que reconcile materialice. (d) **NO** se envía email en `ORDER_CANCEL_REJECTED / ORDER_CANCEL_FAILED` — feedback inmediato en UI es suficiente. |
| **D9** | **UI: botón "Cancelar" en `PendingOrdersPanel` (`/portfolio`) + `RecentOrdersWidget` (`/dashboard`).** Hook `useCancelOrder` único compartido. | (a) Conveniencia para el usuario MVP — puede cancelar sin saltar página. (b) Hook único minimiza duplicación de invalidación de queries. (c) `<CancelOrderButton order={...} />` componente reutilizable encapsula el `window.confirm` + estado visual. |
| **D10** | **Confirm dialog con `window.confirm`** (no modal custom). Texto adaptado dinámicamente por side: BUY → "Se restaurarán USD X a tu saldo"; SELL → "Se restaurarán N acciones a tu posición". | (a) `window.confirm` es nativo + accesible + sin dependencias. (b) Patrón "destructive action needs friction" sin sobre-ingeniería. (c) Texto i18n vía `messages.es.ts` con interpolación. (d) **NO** undo-pattern (toast "Deshacer 5s") — descartado por complejidad y race con polling. |
| **D11** | **Visual feedback polling-timeout**: fila con `opacity-60` + botón disabled + spinner `<Loader2 className="animate-spin h-4 w-4" />` + label "Cancelando…" + `aria-busy="true"`. | (a) Reusa el pattern `isFetching` del P1-2 audit Día 10 (PositionsTable + PendingOrdersPanel ya tienen `opacity-60` cuando refetch). (b) `aria-busy` cumple WCAG. (c) Al próximo refetch 30s, si reconcile materializó, la fila desaparece (filtro `status=PENDING` ya no aplica). |

### 2.2 Decisiones diferidas en SPEC §14 — ahora cerradas (D12–D21)

| # | Decisión | Justificación |
|---|---|---|
| **D12 — D-TRADING-METHOD-CANCEL** | **`TradingService.cancelOrder(userId, orderId)` único con dispatch interno por `order.side`.** NO métodos separados `cancelBuyOrder` + `cancelSellOrder`. Helper interno `applyRefundOrRestore(order)` con switch por side. | (a) ~80% del flujo es común (validación + adapter call + audit + email). (b) Simétrico a D5 F10 (D-TRADING-METHOD). (c) Tests de regresión más sólidos: cambios al flujo común impactan ambos paths. (d) El único divergence es la mutación a balance/position. |
| **D13 — D-RESTORE-AVG-BUY-PRICE** | **Persistir `avg_buy_price_at_submission` en `Order` para SELL queued.** Snapshot al INSERT (= `position.avg_buy_price` actual). Al cancelar SELL queued cuya posición fue eliminada, el re-INSERT a `app.positions` usa este valor. | (a) Sin este campo, recuperar el `avg_buy_price` requeriría leer audit log → frágil. (b) Alternativa rechazada: usar `quoted_unit_price` del Order — incorrecto, ese es el precio de venta estimado, no el costo histórico. (c) Storage cost: 1 columna `NUMERIC(19,4) NULL` en `app.orders` aplicada solo a SELL. (d) **Backfill**: las órdenes SELL existentes en BD (de F10) NO tendrán este campo (NULL). El restore en cancel se hace solo para SELLs nuevas (post-V6). Las viejas que terminen en cancel cae a §5.2 D-RESTORE-AVG-BUY-PRICE-BACKFILL plan.md → usar `quoted_unit_price` como fallback con warning log. |
| **D14 — D-RESPONSE-CODE-TIMEOUT** | **Polling-timeout responde `200 OK`** (no 202 Accepted). | (a) Consistencia con happy-path. (b) Frontend distingue por presencia de `cancelRequestedAt` poblado, no por status code. (c) 202 implicaría procesamiento async sin garantía de éxito — semánticamente acertado pero confuso para clientes que tratan 2xx como éxito uniforme. (d) Frontend muestra toast informativo distinto ("Cancelación en proceso…") según el body. |
| **D15 — D-EMAIL-EXPIRED-REUSE** | **Reusar `order-canceled-{buy,sell}.html` para `EXPIRED`** con flag `isExpired=true` en el Thymeleaf context. NO crear `order-expired-*.html`. | (a) Copy difiere en una sola palabra ("fue cancelada" vs "expiró sin ejecutarse"). (b) Duplicar template es overkill — Thymeleaf `th:text="${isExpired ? 'expiró sin ejecutarse' : 'fue cancelada'}"`. (c) Menos archivos a mantener cuando wording cambie. |
| **D16 — D-DUPLICATE-CANCEL-UI** | **Botón cancel en AMBOS PendingOrdersPanel + RecentOrdersWidget**, no solo en uno. Hook `useCancelOrder` único. | (a) UX MVP — usuario en /dashboard cancela sin saltar a /portfolio. (b) Hook único + `queryClient.invalidateQueries(['balance', 'positions', 'recentOrders'])` mantiene consistencia. (c) Component `<CancelOrderButton order={...} />` reutilizable. |
| **D17 — D-RACE-FILLED-UX** | **Polling devuelve `RACE_FILLED` → tratar como EXECUTED (no refund + counter-order).** Aplicar el fill: `status=EXECUTED`, ajustar balance por diferencia `execution_total - quoted_total` (BUY) o acreditar `execution_total` (SELL queued no acredita en pending). | (a) Contra-intuitivo pero es la realidad del broker — Alpaca confirma el fill antes del cancel. (b) Counter-order alternativo agrega comisión doble + complejidad por edge case rarísimo en paper trading. (c) Frontend muestra toast distinto: "Tu orden se ejecutó antes de que llegara la cancelación. La cancelación no fue aplicada." (d) Audit `ORDER_EXECUTED` (no `ORDER_CANCELED`). |
| **D18 — D-AUDIT-ORDER-CANCEL-FAILED-EMAIL** | **NO enviar email en caso `BROKER_UNAVAILABLE` (502).** Solo audit `ORDER_CANCEL_FAILED`. | (a) Usuario ve el toast rojo inmediato + puede reintentar. (b) Email sería ruido para un error transitorio. (c) Audit log permite oncall (en producción) detectar tasas anormales. (d) Frontend mensaje: "El broker no respondió. Tu orden sigue en cola. Intenta de nuevo en unos minutos." |
| **D19 — D-RECONCILE-LAZY-V2-SCOPE** | **Reconcile v2 NO maneja `partially_filled` en F15.** Solo `canceled / rejected / expired`. Si emerge en testing, registrar como deuda post-F15. | (a) Market Orders en Alpaca paper raramente parcial-fillan. (b) `partially_filled` requeriría reverse parcial (`refund = quoted_total - partial_filled_total`) + lógica de "qué hacer con la parte cancelada" — complejidad alta. (c) Si Alpaca devuelve `partially_filled` durante el polling, el adapter lanza `AlpacaUnexpectedStatusException` → `TradingService` propaga + log error + el frontend muestra "Error inesperado, contacta soporte" (caso operacional fuera de happy path). (d) Si emerge en testing real con Alpaca paper, registrar deuda y completar en F15 v2. |
| **D20 — D-CANCEL-BUTTON-PLACEMENT** | **Texto "Cancelar" como label completo del botón** (no ícono solo X). Variant `ghost`, size `sm`. | (a) Accesibilidad — sin tooltip dependency. (b) Botón destructivo necesita ser explícito visualmente. (c) Consistencia con UX patterns existentes (PendingOrdersPanel no tiene íconos de acción). |
| **D21 — D-FRONTEND-INVALIDATION-STRATEGY** | **`queryClient.invalidateQueries` granular** post-cancel: `['balance']`, `['positions']`, `['recentOrders']`. NO `refetchType: 'all'`. | (a) Performance — evita refetches innecesarios (ej: `useSubscription` no se afecta por cancel). (b) Previsibilidad para tests. (c) Patrón consistente con F16+F21 y F18+F17. (d) Si emerge alguna query no-invalidada que muestra estado stale, agregar a la lista (no a `'all'`). |

### 2.3 Decisiones emergentes del diseño (D22–D24)

> Surgieron al estructurar plan.md (post-SPEC v1.0), no son SPEC-level. Documentadas acá para que tasks.md las aplique.

| # | Decisión | Justificación |
|---|---|---|
| **D22 — D-RECONCILE-LAZY-V2-INLINE-EXTRACT** | **`OrderReconciliationService` v2 extrae el código de transición a métodos privados reutilizables**: `applyCanceledTransition(order, alpacaSnapshot)`, `applyExpiredTransition(order, alpacaSnapshot)`, `applyRejectedTransition(order, alpacaSnapshot)`. Los 3 son llamados también por `TradingService.cancelOrder` en el caso `5.3.4 Alpaca rechaza el DELETE` (drift detected). | (a) DRY entre reconcile v2 y cancel-with-drift. (b) Tests pueden cubrir las 3 transiciones aisladas. (c) `TradingService.cancelOrder` no duplica lógica de reverse. |
| **D23 — D-RESERVE-COLUMNS-MIGRATION** | **V6 agrega 4 columnas (3 timestamps + 1 avg_buy_price_at_submission) en una sola migración**, no 4 migraciones separadas. Idempotente con `ADD COLUMN IF NOT EXISTS`. | (a) Cohesión — todas las columnas son F15 y son aditivas non-breaking. (b) Una migración = un commit Flyway = más limpio. (c) `ADD COLUMN IF NOT EXISTS` permite re-corridas sin error si por alguna razón el script se rompe parcial. |
| **D24 — D-NO-NEW-EXCEPTION-FOR-RACE-FILLED** | **`CancelOutcome.RACE_FILLED` NO es una excepción** sino un valor del sealed type `CancelOutcome`. `TradingService.cancelOrder` lo procesa como caso del switch, no como `catch`. | (a) Race-filled NO es error — es happy-path alternativo donde la orden se ejecutó en lugar de cancelarse. (b) Modelarlo como excepción haría que el listener post-commit no dispare correctamente (excepción → rollback). (c) Sealed type idiomático Java 21. |

### 2.4 Decisiones emergentes durante implementación (D25–Dxx)

> **Sección reservada — patrón estable F09/F10/F16+F21/F18+F17.** [[feedback-decisiones-emergentes-patron]] indica que toda HU no-trivial genera 2-7 emergentes. Acá se documentarán al cierre de cada lote, no antes. Si esta sección queda vacía al final del Lote F → flag para revisión.

| # | Decisión | Lote | Justificación / causa raíz |
|---|---|---|---|
| **D25 — D-MIGRATION-V6-NOT-V8** | **La migración nueva es V6** (no V8 como anticipaban SPEC/plan/tasks draft inicial). Verificación pre-coding T1.2 mostró que el último Flyway aplicado es V5; no existen V6 ni V7 intermedios. | A | Asunción incorrecta del draft v1.0: contaba con migraciones V6/V7 hipotéticas de "polish" Día 10. En realidad el Día 10 cerró sin migración nueva (era solo doc + reconcile lazy + bug fixes). Patch aplicado a SPEC §3+§7+§15, plan.md §3+§4+§5, tasks.md T1.6+T1.7+T1.23+T1.25. |
| **D26 — D-NO-ALPACA-CANCELED-AT** | **NO agregar columna `alpaca_canceled_at`** en V6. El timestamp del canceled-at de Alpaca se mapea directamente a `canceled_at` (nuestra columna nueva). Si emerge necesidad de distinguir "cuándo Alpaca confirmó" vs "cuándo BloomTrade transicionó", se agrega en V7 futura. | A | Verificación T1.10: el campo NO existe en V5 (lo asumíamos como anticipado por F09). Costo de no agregarlo: si Alpaca reporta `canceled_at` con drift (ej: TIF day expirado a las 4pm pero reconcile lo detecta a las 9am del día siguiente), perdemos esa precisión. Aceptable porque (a) MVP single-user con reconcile lazy on-GET es near-realtime, (b) audit log preserva `event_time` real del reconcile. |
| **D27 — D-RECORD-EXTENSION-BREAKS-POSITIONAL-CONSTRUCTORS** | **Extender `AlpacaOrderResponse` record con 2 campos nuevos (`canceledAt`, `expiredAt`) rompió 4 call sites positional en tests existentes** (`TradingServiceTest`, `OrderReconciliationServiceTest`). Patches triviales: agregar `null, null` al final del constructor. | A | Records en Java son retro-compatibles para deserialización JSON (Jackson tolera campos nuevos con `@JsonProperty`) pero NO para construcción positional Java. Lección: si un record es consumido por tests con `new XxxRecord(...)` posicional, agregar campos requiere update simultáneo de todos los call sites. Alternativa futura: factory methods nominales (`AlpacaOrderResponse.of(status, ...)`) en lugar de constructor positional. |
| **D28 — D-CONSTRUCTOR-EXTENSION-CASCADES** | **Constructor `AlpacaTradingAdapter` extendido (1 → 3 args con `cancelPollingIntervalMs/MaxAttempts`) y constructor `TradingService` extendido (12 → 13 con `Auditor`; después 13 → 14 con `OrderReconciliationService`)** rompieron tests existentes. Fix trivial: agregar mocks + pasar al constructor. | A+B+C | Cada nueva dependencia inyectada via constructor cascada a TODO test que construye el bean manualmente (unit Mockito). Trade-off: constructor injection es la convención correcta (CLAUDE.md regla #10), el costo es maintainability de tests. Si los unit tests escalan demasiado, mover a `@SpringBootTest` con `@MockBean` (auto-wired) — pero eso es más lento. |
| **D29 — D-IDEMPOTENT-CANCEL-ON-PENDING-WITH-FLAG** | **2da llamada de cancel sobre orden en `PENDING + cancel_requested_at` retorna 200 idempotent SIN segundo DELETE a Alpaca.** El short-circuit detecta el flag pre-existente, emite `ORDER_DUPLICATE_CANCEL_REQUEST` y devuelve el body actual sin tocar el adapter. | B | El SPEC §5.2.4 lo previó como flujo (idempotency en polling-timeout) — implementado tal cual. Valor: doble-click del usuario sobre orden en "Cancelando…" no genera traffic extra a Alpaca ni audit duplicado. Test IT `cancel_idempotentPollingTimeout_secondCallNoSecondDelete` lo verifica con `wm.verify(1, deleteRequestedFor(...))`. |
| **D30 — D-ORDER-CANCEL-EMAIL-FAILED-AUDIT-EVENT** | **`AuditEventType` agregó 7 entries en F15** (no 6 como SPEC §9.1): los 6 explícitos del SPEC + un séptimo `ORDER_CANCEL_EMAIL_FAILED` para simetría con los otros `*_EMAIL_FAILED` heredados de F09 (`ORDER_EXECUTED_EMAIL_FAILED`, `ORDER_REJECTED_EMAIL_FAILED`, etc.). | B | Convención existing: cada email falló tiene su event type específico para distinguir en Kibana qué tipo de email rota. Para HU-F15 hay 2 templates (canceled-buy + canceled-sell que también sirven para EXPIRED), pero un solo event type `ORDER_CANCEL_EMAIL_FAILED` los cubre. |
| **D31 — D-CANCEL-RETURNS-ORDERRESPONSE-DIRECT** | **`TradingService.cancelOrder` retorna directo `OrderResponse`, no un wrapper `OrderResponseWithRefund`.** El SPEC sugería wrapper para distinguir HTTP status codes (200 happy vs 200 polling-timeout vs 200 race-filled), pero todos son 200 — el cliente diferencia por el body (`status`, `cancelRequestedAt`, `refundedAmount`). | B | Wrapper innecesario. Simpler API surface = menos types nuevos, menos casts en el controller. Trade-off aceptado: el HTTP status no comunica el outcome — el cliente DEBE inspeccionar el body. Pero ese es el patrón estándar REST (200 con body diferenciador). |
| **D32 — D-ORDERRECONCILIATIONSERVICE-EVENTPUBLISHER-DEPENDENCY** | **`OrderReconciliationService` v2 requiere `ApplicationEventPublisher` para publicar `OrderCanceledEvent`/`OrderExpiredEvent`.** v1 NO lo necesitaba (los fills materializados retroactivamente NO emitían eventos para evitar emails confusos). | C | El cambio rompió `OrderReconciliationServiceTest` constructor — fix trivial agregando `@Mock` + arg. La diferencia de comportamiento v1 vs v2 (v1 silent vs v2 publish) está documentada en el javadoc del service. Razón: cancel/expired SÍ son acciones esperadas por el usuario (o por el broker explícitamente) → email es expected feedback; fills retroactivos NO. |
| **D33 — D-TRADINGSERVICE-RECONCILIATION-NO-CYCLE** | **`TradingService` inyecta `OrderReconciliationService` directamente** (sin `@Lazy`) — no hay ciclo de dependencia. La cadena existente `OrderReconciliationService → PortfolioService → @Lazy OrderReconciliationService` ya está rota con `@Lazy` desde Día 10; agregar `TradingService → OrderReconciliationService` NO crea ciclo nuevo. | C | Verificado al compilar — Spring no se quejó. Trade-off potencial futuro: si `OrderReconciliationService` evoluciona a depender de `TradingService` (improbable), habría que romper con `@Lazy`. Para F15 el grafo es limpio. |
| **D34 — D-REFUNDED-AMOUNT-SCALE-2** | **`OrderMapper.toResponseWithRefund` aplica `setScale(2, HALF_UP)` al `refundedAmount` antes de `toPlainString`.** Sin esto, el response trae `"1020.0000"` (4 decimales del BD NUMERIC(19,4)) en lugar de `"1020.00"` (currency presentation). | C | Bug atrapado por IT `cancelBuy_pollingOK` (jsonPath assertion exacta). Lección: las assertions IT con literales exactos (`.value("1020.00")`) detectan inconsistencias de scale que los unit con comparación numérica `isEqualByComparingTo` no detectarían. Patrón consistente: el mapper aplica scale de presentación; el servicio mantiene scale interno full. |
| **D35 — D-NO-TOAST-SYSTEM-FRONTEND** | **Frontend NO tiene sistema toast global.** Para MVP F15 uso `window.confirm` (friction destructivo) + `window.alert` (feedback éxito/error) en `CancelOrderButton`. Funcional pero UX modesta. | D | Deuda registrada para iteración revamp UI post-F15. Trade-off aceptado: sin overhead de instalar/integrar `react-hot-toast` o `sonner` (no estaban en `package.json` heredado). El refetch granular del hook actualiza balance/positions/recentOrders en background — el usuario VE el efecto incluso si el alert se cierra rápido. |

---

## 3. Cambios de dependencias

**Backend (`pom.xml`):** NINGUNO. Resilience4j retry, Spring `RestClient`, WireMock, Thymeleaf — todo presente desde F06+F09. El polling 200ms × 10 dentro del adapter NO requiere library nueva (loop manual con `Thread.sleep` o equivalente — decisión D-POLLING-IMPL en tasks.md).

**STACK.md:** NINGÚN cambio.

**Frontend (`package.json`):** NINGUNO. Reusa `@tanstack/react-query`, `axios`, `lucide-react` (Loader2 ya importado para spinners).

**`.env` y `.env.example`:** NINGÚN cambio. Reusa las 5 vars `ALPACA_*` + `TRADING_DEFAULT_COMMISSION_PCT` heredadas de F09.

**`docker-compose.yml`:** NINGÚN cambio.

**ARCHITECTURE.md:** NINGÚN cambio inmediato — los estados `Cancelada` + `Expirada` ya están listados en §9 (líneas 362-373). F15 los materializa en código sin modificar el doc maestro. Deuda registrada en AGENTS.md "consolidar §9 con el código real cuando todos los estados estén implementados" queda fuera de scope F15.

**`application.yml`:** **CAMBIO menor** — agregar config para polling cancel:
```yaml
trading:
  cancel:
    polling:
      interval-ms: 200
      max-attempts: 10  # = 2s timeout total
```
**Decisión D-POLLING-CONFIG plan.md**: hard-code está OK para MVP, pero externalizar a `application.yml` permite ajustar en testing (e.g., `application-test.yml` puede setear `interval-ms: 10` para velocidad de tests).

**Setup manual del humano antes del Lote A o HITO 5 (smoke E2E):**

1. **Verificación pre-Lote A** (D23 D-RESERVE-COLUMNS-MIGRATION): ✅ EJECUTADA — `docker exec bloomtrade-postgres psql -U bloomtrade -c "\d app.orders"` confirmó que las 3 columnas nuevas NO existen y `alpaca_canceled_at` tampoco (D26). Última migración aplicada: V5.
2. **Pre-HITO 5 demo manual** (mercado abierto o WireMock standalone): tener al menos 1 orden BUY queued (`status=PENDING+alpacaOrderId`) del usuario testing. Provocación más fácil: colocar BUY Market en horario pre-mercado (NYSE 4:00am-9:30am ET → corresponde a horario madrugada-mañana COL) → Alpaca paper devuelve `accepted` (no `filled`) → orden queda PENDING+alpacaOrderId. Si mercado abierto, usar setup WireMock con `wm.stubFor(post("/v2/orders").willReturn(status accepted))` para forzar el escenario.

---

## 4. Reuso de HUs previas y cosas nuevas

**Reutilizado tal cual (sin modificaciones):**

- `IntegrationConfig` + `AlpacaTradingAdapter.submitMarketOrder/getOrder` + `MarketDataAdapter` — solo agrega `cancelOrder()` al adapter trading.
- `CommissionManager` + `ConfigurationManager` + `MarketScheduleManager` (stub).
- `PortfolioService.credit / upsertPosition` — exactly los métodos para refund/restore. **CRÍTICO**: verificar que `upsertPosition` maneje el caso re-INSERT cuando la fila fue eliminada (D-RESTORE-AVG-BUY-PRICE D13).
- `Order` entity base + `OrderSide / OrderType` enums (sin cambios).
- `OrderMapper` — extensión retro-compatible (4 campos nuevos en `OrderResponse`).
- `GlobalExceptionHandler` — extender con 2 handlers nuevos (404 ORDER_NOT_FOUND, 409 ORDER_NOT_CANCELABLE, 502 BROKER_UNAVAILABLE — el último puede reusar handler de F09 para Alpaca down).
- `JwtAuthenticationFilter` + `AuthenticatedUser` — sin cambios.
- `OrderReconciliationService` v1 (Día 10 checkpoint 2) — extendido a v2 (no reescrito).

**Extendido (modificación a archivos F09/F10/F16+F21/F18+F17/Día 10):**

```
backend/src/main/java/co/edu/unbosque/bloomtrade/

├── trading/
│   ├── service/TradingService.java              ← + cancelOrder(userId, orderId) método nuevo
│   │                                              + helper privado applyRefundOrRestore(order, refundContext)
│   ├── service/OrderReconciliationService.java  ← v1→v2: agrega handling de outbound canceled/rejected/expired
│   │                                              + métodos privados applyCanceledTransition / applyExpiredTransition / applyRejectedTransition (D22)
│   ├── controller/OrderController.java          ← + POST /api/v1/orders/{id}/cancel endpoint
│   ├── dto/OrderResponse.java                   ← + canceledAt, cancelRequestedAt, expiredAt, refundedAmount, restoredQty
│   ├── dto/CancelOutcome.java                    ← NUEVO sealed type: CANCELED / PENDING_CANCEL / RACE_FILLED (D24)
│   ├── exception/                                ← + OrderNotFoundException
│   │                                              + OrderNotCancelableException
│   ├── event/                                    ← + OrderCanceledEvent (record)
│   │                                              + OrderExpiredEvent (record)
│   │                                              + OrderCancelPendingEvent (record, info-only para audit)
│   ├── event/OrderEventListener.java            ← + handleOrderCanceled (dispatch por side al Notifier)
│   │                                              + handleOrderExpired (reusa templates -canceled con flag)
│   ├── domain/Order.java                        ← + cancelRequestedAt, canceledAt, expiredAt, avgBuyPriceAtSubmission (campos)
│   │                                              + markCancelRequested(), markCanceled(alpacaCanceledAt), markExpired() (métodos dominio)
│   │                                              + isCancelable() (boolean)
│   ├── domain/OrderStatus.java                  ← + CANCELED, EXPIRED (enum values)
│   └── repository/OrderRepository.java          ← + findByIdAndUserId(orderId, userId): Optional<Order> (defensa anti-enumeración)

├── integration/
│   ├── trading/AlpacaTradingAdapter.java        ← + cancelOrder(alpacaOrderId): CancelOutcome
│   │                                              con DELETE + polling 200ms × 10 + parsing GET respuesta
│   ├── trading/exception/                        ← + AlpacaOrderNotFoundException (404 desde Alpaca)
│   │                                              + AlpacaOrderNotCancelableException (422 desde Alpaca — orden ya filled)

├── audit/
│   └── domain/AuditEventType.java               ← + ORDER_CANCEL_REQUESTED
│                                                  + ORDER_CANCELED
│                                                  + ORDER_EXPIRED
│                                                  + ORDER_DUPLICATE_CANCEL_REQUEST
│                                                  + ORDER_CANCEL_REJECTED
│                                                  + ORDER_CANCEL_FAILED

├── notification/
│   ├── service/Notifier.java                    ← + notifyOrderCanceledBuy(emailCommand)
│   │                                              + notifyOrderCanceledSell(emailCommand)
│   ├── service/MailNotifier.java                ← + impl de los 2 métodos arriba + flag isExpired en context
│   └── dto/OrderCanceledEmailCommand.java       ← NUEVO record (orderId, side, ticker, quantity, refundedAmount o restoredQty, isExpired)

└── shared/web/
    ├── GlobalExceptionHandler.java              ← + 2 handlers (404 ORDER_NOT_FOUND, 409 ORDER_NOT_CANCELABLE)
    └── ValidationMessages.java                  ← + 3 códigos
```

**Templates Thymeleaf** (`backend/src/main/resources/templates/`):

```
NUEVOS:
order-canceled-buy.html   ← reuso para EXPIRED via th:if=isExpired
order-canceled-sell.html  ← idem
```

**Migración** (`backend/src/main/resources/db/migration/`):

```
V6__add_canceled_expired_status_and_cancel_columns.sql
  - ALTER chk_order_status (4→6 valores)
  - ADD cancel_requested_at, canceled_at, expired_at, avg_buy_price_at_submission
  - CREATE INDEX idx_orders_cancel_requested_at (partial)
```

**Frontend** (`frontend/src/`):

```
features/trading/
├── components/
│   └── CancelOrderButton.tsx        ← NUEVO: button + window.confirm + visual feedback cancelRequestedAt
├── hooks/
│   └── useCancelOrder.ts            ← NUEVO: useMutation + invalidación granular ['balance', 'positions', 'recentOrders']
└── api/ordersApi.ts                 ← + cancelOrder(orderId): Promise<OrderResponse>

features/portfolio/
└── components/PendingOrdersPanel.tsx ← + columna "Acciones" con <CancelOrderButton order={row} />

features/dashboard/
└── components/RecentOrdersWidget.tsx ← + columna "Acciones" condicional (status === 'PENDING')

types/api.ts                          ← + canceledAt, cancelRequestedAt, expiredAt, refundedAmount, restoredQty
                                        + OrderStatus union extender con 'CANCELED' | 'EXPIRED'
i18n/messages.es.ts                   ← + ORDER_NOT_FOUND, ORDER_NOT_CANCELABLE, BROKER_UNAVAILABLE
                                        + ORDER_CANCEL_SUCCESS, ORDER_CANCEL_PENDING, ORDER_CANCEL_RACE_FILLED
```

---

## 5. Lotes de implementación (A–F)

> Cada lote tiene un HITO de validación clara. [[feedback-cadencia-sdd]]: validar en hitos significativos (compila / mvn verify / E2E manual), NO archivo-por-archivo.

### Lote A — Backend infraestructura: enum + V6 + adapter (HITO 1)

**Contenido:**

- Pre-verificación D23: estado actual de `app.orders` schema.
- `OrderStatus` enum: agregar `CANCELED`, `EXPIRED` (2 valores nuevos).
- `Order` entity: 4 campos nuevos (`cancelRequestedAt`, `canceledAt`, `expiredAt`, `avgBuyPriceAtSubmission`) + 3 métodos dominio.
- Migración V6 con `chk_order_status` extendido + 3 columnas + 1 índice parcial.
- `AlpacaTradingAdapter.cancelOrder(alpacaOrderId): CancelOutcome` con polling.
- `CancelOutcome` sealed type (3 variantes).
- 2 excepciones Alpaca nuevas (`AlpacaOrderNotFoundException`, `AlpacaOrderNotCancelableException`).
- 6 entries nuevas en `AuditEventType`.
- Tests unit: `AlpacaTradingAdapterTest` +4 (cancel happy, polling timeout, 404, 422).
- Tests unit: `OrderStatusTest` (opcional, trivial).
- Compilación + tests unit verdes.

**HITO 1 criterio:** `mvn -pl backend clean compile test-compile && mvn -pl backend test -Dtest='*Test'` (skip IT). Migración V6 aplica limpia en `localhost:5433/bloomtrade_test`. `mvn flyway:info -pl backend` lista V6.

### Lote B — Backend service: TradingService.cancelOrder + tests unit (HITO 2)

**Contenido:**

- `OrderRepository.findByIdAndUserId(orderId, userId): Optional<Order>` (defensa anti-enumeración).
- `OrderResponse` extendido con 4 campos nuevos. `OrderMapper` actualizado para poblarlos.
- 3 events nuevos: `OrderCanceledEvent`, `OrderExpiredEvent`, `OrderCancelPendingEvent` (records).
- 2 excepciones nuevas: `OrderNotFoundException`, `OrderNotCancelableException`.
- 2 handlers nuevos en `GlobalExceptionHandler` (404 ORDER_NOT_FOUND, 409 ORDER_NOT_CANCELABLE). 502 BROKER_UNAVAILABLE reusa handler existente F09.
- `OrderController.cancelOrder(orderId, principal)` endpoint.
- `TradingService.cancelOrder(userId, orderId)`:
  - Lock canónico `app.user_balances` (defensa heredada D17 F10).
  - `findByIdAndUserId` → 404 si vacío.
  - Status check → 409 si no cancelable; short-circuit idempotency si CANCELED o cancel_requested_at.
  - Emit audit `ORDER_CANCEL_REQUESTED`.
  - Llama `AlpacaTradingAdapter.cancelOrder` → 3 outcomes (D24).
  - Switch:
    - CANCELED → `applyRefundOrRestore` (BUY: credit; SELL: upsertPosition con D13) → mark `CANCELED` + publish `OrderCanceledEvent`.
    - PENDING_CANCEL → mark `cancel_requested_at` + publish `OrderCancelPendingEvent`.
    - RACE_FILLED → tratar como EXECUTED (D17 D-RACE-FILLED-UX): ajustar balance/position por execution_total real + publish `OrderExecutedEvent`.
  - `noRollbackFor` aplicado a las 2 excepciones nuevas + `BROKER_UNAVAILABLE` (defensa heredada D24 F09 / D18 F10).
- Helper privado `applyRefundOrRestore(order, refundContext)`.
- 3 templates Thymeleaf nuevos: `order-canceled-buy.html`, `order-canceled-sell.html` (reuso EXPIRED via flag).
- 2 métodos nuevos en `Notifier` + impl en `MailNotifier`.
- `OrderEventListener.handleOrderCanceled` (`@TransactionalEventListener(AFTER_COMMIT)`) con dispatch por side.
- Tests unit `TradingServiceTest`: +10 escenarios (BUY happy, SELL happy con re-INSERT, polling-OK vs timeout, RACE_FILLED BUY ajuste balance, NOT_FOUND cross-user, NOT_CANCELABLE × 4 estados, idempotencia × 2, Alpaca 502).
- Tests unit `OrderEventListenerTest`: +3 (cancel BUY → email + audit; cancel SELL → email + audit; cancel-pending → solo audit no email).

**HITO 2 criterio:** `mvn -pl backend test` verde (sin IT). Coverage `PortfolioService.credit` ya cubierto F10; `TradingService.cancelOrder` ≥85% por los 10 escenarios.

### Lote C — Backend reconcile v2 + tests IT (HITO 3)

**Contenido:**

- `OrderReconciliationService.reconcileOrder(order)` v2:
  - v1 actual maneja `PENDING → EXECUTED` (Alpaca filled).
  - Agregar branches: `PENDING → CANCELED` (Alpaca canceled), `PENDING → EXPIRED` (Alpaca expired), `PENDING → REJECTED` (Alpaca rejected post-encolado).
  - Cada branch llama a método privado correspondiente (D22): `applyCanceledTransition / applyExpiredTransition / applyRejectedTransition`.
  - Reverse aplicado (credit BUY / upsertPosition SELL) en cada caso.
  - Emit audit + publish event para post-commit email.
- **Refactor TradingService.cancelOrder** caso §5.3.4 (Alpaca DELETE devuelve 404/422 — drift): invoca inline los métodos `applyXxxTransition` de reconcile v2 (D22).
- Tests IT (`OrderReconciliationServiceIT`):
  - +1: reconcile detecta `canceled` outbound → status CANCELED + refund (BUY) → audit + email.
  - +1: reconcile detecta `expired` outbound → status EXPIRED + refund (BUY) → email con `isExpired=true`.
  - +1: reconcile detecta `rejected` outbound → status REJECTED + refund → reusa `order-rejected-*.html` existente.
  - +1: reconcile detecta `partially_filled` outbound → lanza `AlpacaUnexpectedStatusException` (D19 D-RECONCILE-LAZY-V2-SCOPE).
- Tests IT (`TradingControllerCancelIT`) — los E2E HTTP felices:
  - +1: cancel BUY happy polling-OK → 200 + balance refunded + audit + email.
  - +1: cancel SELL happy polling-OK con re-INSERT → 200 + position restored + audit + email.
  - +1: cancel polling-timeout → 200 + cancelRequestedAt + sin refund + sin email (verify MailHog vacío).
  - +1: cancel pendiente + GET subsequent → reconcile lazy v2 materializa → status CANCELED + refund + email tardío.
  - +1: cancel RACE_FILLED → 200 con EXECUTED status (no CANCELED) + balance ajustado.
  - +1: cancel cross-user → 404 ORDER_NOT_FOUND.
  - +1: cancel sobre EXECUTED → 409 ORDER_NOT_CANCELABLE.
  - +1: cancel concurrencia × 2 simultáneos mismo order → uno procesa, otro short-circuit, audit `ORDER_DUPLICATE_CANCEL_REQUEST`.
  - +1: cancel con Alpaca 503 post-retries → 502 BROKER_UNAVAILABLE + sin modificación BD.
  - +1: cancel Alpaca devuelve 404 sobre el DELETE (drift) → reconcile inline materializa estado real de Alpaca.

**HITO 3 criterio:** `mvn -pl backend verify` (incluyendo IT) verde. Tests acumulados ≈373+ (363 actuales + 10 unit Lote B + 11 IT Lote C aproximado, pero algunos IT se solapan con reconcile — final count emerge).

### Lote D — Frontend (HITO 4)

**Contenido:**

- `types/api.ts` extender `OrderResponse` (4 campos) + `OrderStatus` union (+2 valores).
- `ordersApi.ts` +`cancelOrder(orderId): Promise<OrderResponse>`.
- `useCancelOrder.ts` hook (`useMutation` + `onSuccess: invalidateQueries(['balance', 'positions', 'recentOrders'])`).
- `CancelOrderButton.tsx` component:
  - `window.confirm` con texto adaptado por side (D10).
  - Si `order.cancelRequestedAt` set: render disabled + spinner + label "Cancelando…" (D11).
  - Else: render botón "Cancelar" + onClick → confirm → mutate.
- `PendingOrdersPanel.tsx` modificación: columna "Acciones" + `<CancelOrderButton order={row} />`.
- `RecentOrdersWidget.tsx` modificación: columna "Acciones" condicional `{row.status === 'PENDING' && <CancelOrderButton order={row} />}`.
- `messages.es.ts` +6 keys (D10/D11).
- Toast handling en `useCancelOrder.onSuccess` distingue por response body:
  - `status === 'CANCELED'` → toast verde `ORDER_CANCEL_SUCCESS` con refund description.
  - `cancelRequestedAt && status === 'PENDING'` → toast info `ORDER_CANCEL_PENDING`.
  - `status === 'EXECUTED'` (race) → toast info `ORDER_CANCEL_RACE_FILLED`.
- Toast handling en `useCancelOrder.onError`:
  - 404 → toast amber "Orden no encontrada".
  - 409 → toast amber con `ORDER_NOT_CANCELABLE` interpolando currentStatus.
  - 502 → toast rojo `BROKER_UNAVAILABLE`.
- `npm run build` verde.
- Tests vitest: skip por velocidad [[feedback-coverage-vs-velocidad]]. Validación HITO 5 smoke visual humano.

**HITO 4 criterio:** `npm run build` verde + `npm test -- --run` 27/27 verde (sin nuevos). Render manual de `/portfolio` y `/dashboard` con storybook-equivalente (preview de componentes en dev mode con `npm run dev`).

### Lote E — Tests IT cross-user adicionales + mvn verify final (HITO 5)

**Contenido:**

- `TradingControllerCancelIT` +2 IT pendientes del Lote C:
  - Idempotency en flujo polling-timeout: 2 cancels en serie sobre PENDING+cancelRequestedAt → segundo short-circuit sin segundo DELETE a Alpaca (verify WireMock `verify(1, deleteRequestedFor(...))`).
  - Edge case: cancel mientras reconcile lazy materializa EXECUTED en otra request → race entre POST `/cancel` y GET `/portfolio` → 409 ORDER_NOT_CANCELABLE.
- `mvn -pl backend verify` completo: backend tests acumulados.
- `npm run build` final + smoke visual humano:
  - Login → `/portfolio` → ver sección "Órdenes en cola" si existen.
  - Click "Cancelar" → confirm dialog → cancelar → ver toast + balance update + fila desaparece.
  - Repetir desde `/dashboard` widget `RecentOrdersWidget`.
  - Validar caso polling-timeout simulado (puede requerir WireMock standalone o mock manual en frontend para forzar el escenario).
  - Validar caso race-filled simulado.

**HITO 5 criterio:** demo manual del usuario humano. Smoke aceptado verbalmente. `mvn verify` final verde. Tests acumulados confirmados.

### Lote F — Cierre (HITO 6)

**Contenido:**

- `plan.md` §2.4: completar decisiones emergentes D25–Dxx (patrón estable: F09 7, F10 5, F16+F21 2, F18+F17 3).
- `APRENDIZAJES.md` sección "Día 11 — HU-F15" con reflexiones técnicas en primera persona.
  - Pattern reusable: polling canónico async (DELETE + polling 200ms × 10) como template para futuras transiciones outbound.
  - Reconcile v2 como instancia del patrón "lazy reconcile on read" — escalable a otros estados outbound.
  - Decisión RACE_FILLED contra-intuitiva: aprendizaje de modelar estados del broker como sealed types en lugar de excepciones (D24).
  - Idempotencia implícita por `order.id` vs clientOrderId: validación de cuando cada uno aplica.
  - Migración aditiva V6 vs reescritura: trade-off de estados nuevos al enum.
- `AGENTS.md` handoff actualizado:
  - Sección "Trabajo activo" → branch cerrada, próximo paso = revamp UI con `frontend-design` skill (sesión separada).
  - Sección "Cómo continuar" nueva: post-F15 → revamp UI completo.
  - Deudas vivas: cerradas #27 (CANCELLED status enum), #30 (reconcile v2 outbound). Nuevas si emergen.
- Commit message preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f15.txt` (ruta completa P6).
- SPEC.md NO bump v1.1 si las decisiones emergentes son implementation-detail.
- Verificación final pre-commit: `mvn verify` verde, `npm run build` verde, sin archivos `*.swp` o trazas debug.

**HITO 6 criterio:** humano firma el commit `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f15.txt`, push, abre PR contra `main`, mergea con squash. Branch `feat/HU-F15-cancelar-orden` borrada en remoto.

---

## 6. Riesgos identificados

| # | Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|---|
| R1 | **Polling Alpaca no termina en 2s consistentemente** — Alpaca paper bajo carga responde lento. | Media | Bajo (degrada a polling-timeout, no error) | El path polling-timeout está modelado como happy-path alternativo (D6). Reconcile v2 lo materializa después. Tests IT con WireMock `withFixedDelay(3000)` verifican el path. |
| R2 | **Race condition cancel + reconcile concurrent** — usuario hace POST `/cancel` exactamente cuando otro GET dispara reconcile-lazy que ya materializó EXECUTED. | Baja | Medio (response inconsistente) | Lock pessimistic sobre `app.user_balances` serializa. `findByIdAndUserId` dentro del lock garantiza el estado leído == estado al momento del check. Test IT específico cubre. |
| R3 | **D13 backfill SELL queued pre-V6** — órdenes SELL queued creadas antes de V6 NO tienen `avg_buy_price_at_submission`. Si se cancelan, re-INSERT de posición usaría NULL. | Baja (MVP single-user, pocas órdenes legacy) | Bajo | Fallback documentado: usar `quoted_unit_price` + warning log. Mejor: V6 incluye UPDATE backfill que setea `avg_buy_price_at_submission = quoted_unit_price` para SELL queued legacy. Decisión: **incluir UPDATE backfill en V6**. |
| R4 | **RACE_FILLED durante el polling cambia el balance debitado** — BUY queued tenía balance debitado optimista (D29 F09); Alpaca llena justo durante el cancel. El `execution_total` real puede diferir del `quoted_total` por slippage. | Media (slippage normal) | Bajo | `applyRaceFilledTransition`: si BUY, calcular `delta = execution_total - quoted_total`; si > 0 cobrar extra, si < 0 refundar la diferencia. Mismo path que F09 D29 fill-late-arrival. |
| R5 | **OrderReconciliationService v1 tiene side effects no contemplados al extender a v2** — v1 maneja PENDING→EXECUTED y agregar 3 branches más podría romper algún caso edge actual. | Media | Medio (regresión en flujos F09/F10/F16 funcionando) | (a) v2 NO modifica el código path PENDING→EXECUTED existente — solo agrega branches nuevos. (b) Tests IT actuales de F09/F10 que pasan por reconcile v1 SIGUEN verdes post-v2 (regression test). (c) Lote C arranca con `mvn verify` baseline + comparar resultados post-cambio. |
| R6 | **`@TransactionalEventListener(AFTER_COMMIT)` y el polling-timeout** — si polling-timeout commit pasa, `OrderCancelPendingEvent` dispara pero NO debe enviar email (D8). Si listener falla en distinguir → email enviado de más. | Baja | Bajo (email duplicado, usuario confuso) | Listener `OrderEventListener.handleOrderCancelPending` debe NO llamar a `Notifier`. Solo audit. Test unit cubre. |
| R7 | **Frontend invalidación granular pierde alguna query** — D21 lista 3 keys, pero si emerge otra query no-listada (ej: `useSubscription.summary` con campo derivado de orders), queda stale. | Baja | Bajo | Audit manual de queries usadas en `/portfolio` y `/dashboard` al final del Lote D. Lista emergente se agrega a `invalidateQueries`. |
| R8 | **Demo manual sin órdenes queued para cancelar** — si NYSE está abierto y Alpaca paper llena todo inmediato, no hay PENDING+alpacaOrderId para probar. | Alta | Bajo (bloquea HITO 5) | Setup explícito: en horario pre-mercado (early morning COL) hacer BUY Market → queda queued. Alternativa: WireMock standalone que devuelve `accepted` no-terminal para forzar el estado. |
| R9 | **Coverage cae bajo 60% por agregar mucho código sin tests proporcionales** | Media | Bajo [[feedback-coverage-vs-velocidad]] | Tests críticos (TradingService.cancelOrder, OrderReconciliationService v2, AlpacaTradingAdapter.cancelOrder) sí están cubiertos. Tests triviales (DTOs, mappers) sin tests adicionales = coverage no-crítico cae aceptable. Documentar como D-COVERAGE-F15 en §2.4 si emerge. |

---

## 7. Estimación de esfuerzo

| Lote | HITO | Esfuerzo estimado |
|---|---|---|
| A | 1 (compile + unit) | ~2-3h (enum + V6 + adapter + unit tests) |
| B | 2 (mvn test) | ~3-4h (service + handlers + listener + 10 unit tests) |
| C | 3 (mvn verify) | ~3-4h (reconcile v2 + 11 IT tests con WireMock + drift inline) |
| D | 4 (npm build) | ~1.5-2h (hook + component + 2 integrations + types) |
| E | 5 (smoke + verify final) | ~1h (2 IT adicionales + demo manual) |
| F | 6 (commit + push) | ~0.5-1h (APRENDIZAJES + handoff + commit) |
| **Total** | — | **~11-15h de trabajo efectivo** (= ~1.5-2 días intensos con [[feedback-cadencia-sdd]]) |

**Confianza:** alta. El andamio existe completo (F09 + F10 + F16+F21 + F18+F17 + Día 10 reconcile v1). F15 es 100% extensión del modelo existente, sin verticales nuevas. Las decisiones D-xx ya están cerradas — no se espera "stop & ask" mayor durante implementación.

---

## 8. Definition of Done — referencia rápida

Ver SPEC §15 para checklist completo. Resumen:

- ☐ V6 aplicada + 2 enum values + 4 columnas + 1 índice.
- ☐ `mvn verify` verde (target: ~373-385 tests).
- ☐ `npm run build` verde + 27/27 vitest.
- ☐ Smoke E2E manual HITO 5 verbal aceptado.
- ☐ APRENDIZAJES.md Día 11.
- ☐ AGENTS.md handoff post-F15 → revamp UI.
- ☐ Commit firmado por humano + PR + squash a main.

---

## Changelog

| Versión | Fecha | Autor | Cambios |
|---|---|---|---|
| 1.0 | 2026-05-26 | Juan | Versión inicial. Resuelve 11 decisiones cerradas en SDD Paso 1 (D1–D11) + 10 diferidas en SPEC §14 (D12–D21) + 3 emergentes del diseño (D22–D24). Estructura 6 lotes con HITOs. 9 riesgos identificados con mitigación. Esfuerzo estimado ~11-15h. §2.4 reservada para D25–Dxx emergentes durante implementación. |

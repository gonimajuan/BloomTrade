# plan.md — HU-F09 Orden de compra Market

> Plan técnico derivado de `specs/HU-F09-orden-compra-market/SPEC.md` v1.0.
> Estado: **pendiente de aprobación humana** (SDD Paso 2).
> Modificaciones a `SPEC.md` se difieren a HITO 6 (consolidación v1.1) — patrón HU-F06.

---

## 1. Objetivo

Implementar el **ciclo completo de orden Market BUY** con cumplimiento del requisito crítico de ARCH §9 (*"La comisión se informa al usuario ANTES de confirmar la orden"*):

1. **`POST /api/v1/orders/quote`** — informativo, sin persistencia: precio Alpaca Data + comisión calculada + total + estado de fondos + estado de mercado.
2. **`POST /api/v1/orders`** — transaccional con lock pessimistic sobre `app.user_balances`: validaciones → INSERT order PENDING → submit Alpaca → UPDATE order EXECUTED/REJECTED/FAILED → debit balance + upsert position → COMMIT → notify + audit async.

Materializa por primera vez:
- TAC-M1 + TAC-I2 (`AlpacaTradingAdapter` + `MarketDataAdapter`, ambos contra Alpaca).
- TAC-D2 (`@Retry` 3×1s/3s/5s sobre ambas APIs Alpaca).
- TAC-M3 (`CommissionManager` encapsula cálculo; `OrderOrchestrator` encapsula secuencia post-Alpaca).
- TAC-S4 (7 event types nuevos en AuditService).
- TAC-S2 (filtro por `rol=INVESTOR` + `estado=ACTIVE` en controller).
- TAC-M2 (`ConfigurationManager` lee `config.commission_rates` en runtime).

Es la HU del MVP que **toca más módulos** (6: Trading, Portfolio, Integration, Admin, Notification, Audit). F10/F16/F18/F21 reutilizan el andamio que se monta acá.

**Tamaño:** **1.5–2 días apretados.** Lote A (setup BD + entidades) es ~30% del trabajo; Lotes B–D (adapters + service + orchestrator) son el core ~50%; Lotes E–G (notification + tests + frontend) son ~20%.

---

## 2. Decisiones técnicas concretas

> Las decisiones del cuestionario humano se prefijan D1–D8. Las **emergentes** del diseño se numeran D9–Dxx.

### 2.1 Decisiones del cuestionario (D1–D8)

| # | Decisión | Justificación |
|---|---|---|
| **D1** | **`RestClient` nativo de Spring** (no SDK `alpaca-java`). Un solo `RestClient.Builder` configurado en `IntegrationConfig` con timeouts (connect 2s, read 5s). DTOs locales en `integration/alpaca/dto/`. Tests con `MockRestServiceServer` o WireMock. | (a) `STACK.md` §2.3 no lista `alpaca-java` — agregar SDK no-oficial es deuda. (b) Spring 6.1+ `RestClient` es la API oficial para sync HTTP. (c) Solo necesitamos 2 endpoints (POST orders, GET latest-quote) — DTOs locales caben en ~80 LOC. (d) WireMock estabiliza tests sin importar el cliente. |
| **D2** | **NO materializar `PriorityQueue` + `ThreadPool` en TradingService.** ARCH §4 los lista; MVP los difiere. Implementación: el endpoint POST /orders es síncrono request-response normal. Comentario en `TradingService.java` referencia esta decisión + ESC-R1 deuda. | P1 (velocidad sobre cobertura). El test JMeter del Día 10 medirá el gap real frente a ESC-R1. Post-MVP la introducción es localizada (envolver `placeOrder` en `@Async` + `CompletableFuture` o migrar a Spring Batch). |
| **D3** | **NO introducir `PriceCache` (Redis) en F09.** `MarketDataAdapter.getLatestPrice(ticker)` llama a Alpaca Data API en cada quote/orden. HU-F18 lo migrará a cache TTL 30s. | MVP single-user testing: ~10-20 quotes en demo. Sin riesgo de rate limit. Beneficio: 1 menos componente que validar en F09. |
| **D4** | **NO implementar slippage tolerance.** El `executionUnitPrice` de Alpaca puede diferir del `quotedUnitPrice` sin que esto rechace la orden. Documentado en SPEC §10.2 como deuda. | En paper trading con tickers líquidos (AAPL, MSFT…) el slippage es <0.1%. El quote es informativo, no contrato. Implementarlo correctamente requiere cuidar bordes (¿qué hace el usuario si rechazamos? ¿retry automático?). Sin valor en MVP. |
| **D5** | **Solo AuditService → ElasticSearch para trazabilidad de transiciones.** NO tabla `app.order_state_transitions`. Los 4 estados (PENDING → EXECUTED/REJECTED/FAILED) se reconstruyen en Kibana queryando por `orderId`. | ARCH §9 exige trazabilidad, no especifica medio. ES cumple. Tabla extra = más DDL + más repository + más tests para 0 valor MVP. |
| **D6** | **Fail-fast con `MARKET_DATA_UNAVAILABLE`** ante rate-limit o caída de Alpaca Data API. Pero log discrimina la causa: si la respuesta es 429, audit event `MARKET_DATA_RATE_LIMITED` (WARNING); si 5xx/timeout, `MARKET_DATA_API_ERROR`. Misma respuesta 502 al usuario. | Diagnóstico fácil en Kibana sin cambiar la UX. ~5 LOC extra. |
| **D7** | **`MAX_QUANTITY_PER_ORDER = 10_000`** como `public static final int` en `TradingService.java`. NO configurable en MVP. | Hardcoded está bien para MVP. Post-MVP movible a `config.trading_settings` sin migración disruptiva (es validación, no se persiste). |
| **D8** | **Tests IT setup de balance vía `BalanceInitializer.initialize(user, BigDecimal)` + `UserBalanceRepository.save()` en `@BeforeEach`** de la test class. NO `@Sql` scripts, NO test fixtures Java separados. | Reusa la infra de HU-F01 sin nuevas abstracciones. El test ve exactamente el mismo path de inicialización que producción. |

### 2.2 Decisión arquitectónica clave del pivote (D9)

| # | Decisión | Justificación |
|---|---|---|
| **D9 — D-MD-PROVIDER** | **Alpaca-only para market data en MVP.** Drop Polygon.io del Sprint 2 y posiblemente del MVP entero. Razones: (1) usuario reportó degradación reciente en Polygon free tier; (2) Alpaca paper trading account ya incluye market data gratis vía `https://data.alpaca.markets`; (3) `STACK.md` §7.2 ya documenta esta migración como contingencia ("*Plan de migración: si se necesita real-time, se cambia a tier pago o se reemplaza por Alpaca Market Data sin tocar DashboardService*"). **Efectos sobre SPEC v1.0**: <br>• §3 vars de entorno: drop `POLYGON_API_KEY`, `POLYGON_BASE_URL`; agregar `ALPACA_DATA_BASE_URL=https://data.alpaca.markets`<br>• §5.1 paso 7 + §5.3.8: el quote consulta `GET /v2/stocks/{symbol}/quotes/latest` de Alpaca Data, no `/v2/snapshot/...` de Polygon<br>• §8.1 IntegrationService: `AlpacaAdapter` se divide en 2 clases (`AlpacaTradingAdapter`, `MarketDataAdapter`) ambas en `integration/alpaca/` con un `RestClient.Builder` compartido<br>• §8.4 Retry: `@Retry(name="alpacaTradingApi")` y `@Retry(name="alpacaDataApi")` configurados separados (timeouts/backoffs distintos potencialmente)<br>• §9.1 audit: `MARKET_DATA_API_ERROR` reemplaza `POLYGON_API_ERROR`; +`MARKET_DATA_RATE_LIMITED` por D6<br>• §9.4: una sola fila de "Alpaca" con 2 endpoints distintos<br>• §15 DoD: drop `POLYGON_*`, agregar `ALPACA_DATA_BASE_URL`<br>**Efectos sobre STACK.md**: §7.2 (Polygon) se actualiza en el mismo PR para reflejar la migración ejecutada. | (a) Reduce 1 dependencia externa = 1 menos punto de fallo. (b) Reusa credenciales + adapter pattern + WireMock setup. (c) Alpaca data delayed 15min es aceptable para paper trading demo (STACK.md §7.1 lo dice). (d) Si post-MVP necesitamos real-time real, reintroducir Polygon es un PR aislado vía TAC-M1. |

### 2.3 Decisiones emergentes del diseño (D10–D22)

| # | Decisión | Justificación |
|---|---|---|
| **D10** | **Estructura de paquetes nueva** dentro de `co.edu.unbosque.bloomtrade`:<br>• `trading/` (módulo completo nuevo): `controller/`, `service/`, `domain/`, `dto/`, `mapper/`, `exception/`, `repository/`<br>• `portfolio/`: extender con `domain/Position.java`, `repository/PositionRepository.java`, `service/PortfolioService.java` (clase nueva; `BalanceInitializer` ya existe)<br>• `admin/` (módulo nuevo): `domain/CommissionRate.java`, `repository/CommissionRateRepository.java`, `service/{ConfigurationManager, CommissionManager, MarketScheduleManager}.java`<br>• `integration/alpaca/`: `{IntegrationConfig, AlpacaTradingAdapter, MarketDataAdapter}.java`, sub-paquete `dto/` con DTOs locales | ARCH §3 catálogo de 9 módulos. F09 es la HU que materializa 4 de ellos por primera vez. |
| **D11** | **`MarketScheduleManager` stub MVP**: método `isOpenNow(String ticker): boolean` siempre retorna `true`. Implementación con `// TODO: HU-F14`. La estructura de la clase + interfaz pública queda en su lugar para que HU-F14 (post-MVP) la hidrate sin refactor. | ROADMAP §3.1 explícitamente difiere HU-F14. Stub mantiene la simetría arquitectónica de ARCH §4. |
| **D12** | **`BigDecimal` + `RoundingMode.HALF_UP` en TODA aritmética financiera.** `NUMERIC(19,4)` en BD para precios y multiplicaciones; redondeo a 2 decimales al persistir la comisión (`commission.setScale(2, HALF_UP)`). Total `quoted_total = (quoted_unit_price × quantity) + quoted_commission` con la misma precisión. Test parametrizado verifica casos límite (`1000.001 × 0.02 = 20.00`, no `20.0000200`). | CLAUDE.md regla #9 (BigDecimal obligatorio). HALF_UP es el comportamiento esperado por usuarios financieros. |
| **D13** | **Lock pessimistic `SELECT FOR UPDATE`** sobre `app.user_balances` dentro de la `@Transactional` de `TradingService.placeOrder`. Implementación: `@Lock(LockModeType.PESSIMISTIC_WRITE)` en el método del `UserBalanceRepository.findByUserIdForUpdate(UUID)`. Garantiza serialización ante doble-click o pestañas paralelas. | CHECK constraint BD ya garantiza no-negative balance, pero el lock previene la race "ambos leen 1000, ambos calculan suficiente, uno commitea y el otro falla con CHECK violation" — el segundo falla limpio con `INSUFFICIENT_FUNDS` (esperado) en lugar de excepción genérica de constraint. |
| **D14** | **`client_order_id` también enviado en el body a Alpaca.** Alpaca lo respeta como key de deduplicación nativa: dos POST con mismo `client_order_id` devuelven la misma orden de Alpaca, no crean dos. Esto da idempotencia end-to-end: BloomTrade idempotente vía `UNIQUE` constraint + Alpaca idempotente vía su soporte nativo. | Si BloomTrade idempotente pero Alpaca crea órdenes duplicadas en backend retry, tendríamos 1 fila BD pero N órdenes en Alpaca. Esto evita esa divergencia. |
| **D15** | **Notificación + audit async post-commit** con `@TransactionalEventListener(phase=AFTER_COMMIT)`. El `TradingService.placeOrder` publica un `OrderExecutedEvent` (o `OrderRejectedEvent` / `OrderFailedEvent`) al final de la transacción; un `@Component OrderEventListener` lo recibe POST-commit y dispara `notifier.notifyOrderExecuted(...)` + `auditor.emit(ORDER_EXECUTED)`. | Evita que un fallo de SMTP o de ES en MailHog/Logstash bloquee la transacción de la orden. Patrón ya usado en `RegistrationEventListener` (HU-F01). Reuso conceptual. |
| **D16** | **`OrderStatus` enum estrechado a 4 valores: `PENDING, EXECUTED, REJECTED, FAILED`.** ARCH §9 lista 10 estados; F09 implementa los 4 críticos del flujo BUY Market. `CANCELLED` se introduce en HU-F15 (stretch). `EXPIRED, IN_REVIEW, STOPPED` quedan post-MVP. | YAGNI. Un enum con 10 valores sin handlers reales para 6 de ellos invita bugs. Cada extensión futura agrega su valor + handler en el mismo PR. CHECK constraint BD también estrechado. |
| **D17** | **Inconsistencia Alpaca-éxito-commit-local-falla NO mitigada en MVP.** Si Alpaca acepta la orden y luego falla el COMMIT local (ej: BD cae justo entre Alpaca response y commit), tendremos orden ejecutada en Alpaca paper pero no registrada en BloomTrade. Mitigación documentada como deuda: reconciliation job nocturno comparando `alpaca_order_id` registrados en BD con órdenes en Alpaca paper. El `client_order_id` enviado a Alpaca permite reconciliación manual mientras tanto. | Mitigación correcta (outbox pattern, transactional outbox + relay) es ~1 semana de trabajo. ROADMAP no lo permite. Para paper trading académico, el riesgo es aceptable. |
| **D18** | **`ConfigurationManager` como capa delgada sobre `CommissionRateRepository`.** No persiste settings en cache app-level; cada `commissionManager.calculate(...)` lee la fila activa con `findFirstByRoleAndValidToIsNullOrderByValidFromDesc(role)`. Posible cache @Cacheable de Spring si surge necesidad real. | Single user, single query per order, índice indexa — sin necesidad de cache. TAC-M2 (diferir el enlace) se cumple porque el cambio en BD se refleja inmediato. |
| **D19** | **Tests IT con WireMock simulando AMBOS endpoints Alpaca** (trading + data) en distintos paths. Test class única `TradingControllerIT` cubre los escenarios principales. Tests específicos del orchestrator + idempotencia van en `TradingServiceIT`. | Reusa WireMock de HU-F06 (aprobada en `STACK.md` §2.3). Postgres real vía perfil `test` (D16 HU-F01). |
| **D20** | **`AlpacaTradingAdapter` y `MarketDataAdapter` comparten `RestClient.Builder` configurado en `IntegrationConfig` pero NO `RestClient`.** Cada adapter tiene su propio `RestClient` con baseUrl distinto (`paper-api.alpaca.markets` vs `data.alpaca.markets`). Headers de auth (`APCA-API-KEY-ID`, `APCA-API-SECRET-KEY`) configurados como request initializer común — los mismos creds funcionan en ambas APIs. | Separación limpia por endpoint base. Misma seguridad. |
| **D21** | **`GlobalExceptionHandler` extender con 6 handlers nuevos.** Mapeo a HTTP + códigos en `validation-messages.properties`:<br>• `InvalidTickerException` → 400 `INVALID_TICKER`<br>• `InvalidQuantityException` → 400 `INVALID_QUANTITY`<br>• `InvalidSideException` → 400 `INVALID_SIDE` o `SIDE_NOT_YET_IMPLEMENTED`<br>• `InsufficientFundsException` → 409 `INSUFFICIENT_FUNDS` con details `{balance, required, shortfall}`<br>• `AlpacaOrderRejectedException` → 422 `ALPACA_ORDER_REJECTED` con `details.alpacaReason`<br>• `AlpacaApiException` / `MarketDataUnavailableException` → 502 `ALPACA_API_ERROR` / `MARKET_DATA_UNAVAILABLE`<br>• `AccountNotActiveException` → 403 `ACCOUNT_NOT_ACTIVE` (puede ya existir de HU-F02; verificar) | Pattern ya consolidado por HU-F01–F06. Reusa `ErrorResponse` + `FieldErrorItem`. |
| **D22** | **Coverage target 60-70%** [[feedback-coverage-vs-velocidad]]. Foco crítico: `TradingService` (lógica core), `CommissionManager` (regla de negocio + bordes BigDecimal), `OrderOrchestrator` (secuencia post-Alpaca), `AlpacaTradingAdapter` + `MarketDataAdapter` (via WireMock). Skip tests triviales de controller + mapper salvo el que verifica no-leak de credenciales/IDs internos. | Memoria viva. Criticidad real: (1) `BigDecimal` exacto, (2) saldo no-negativo bajo concurrencia, (3) idempotencia, (4) rollback completo en errores externos. |

### 2.5 Decisiones emergentes durante implementación Lote H + H.5 (D28–D29)

Surgieron al validar el HITO 8 humano con el stack Docker corriendo y `.env` real del usuario.

| # | Decisión | Justificación |
|---|---|---|
| **D28** | **`ALPACA_BASE_URL` NO debe incluir `/v2`** en `.env` ni en defaults de `application.yml`. El `AlpacaTradingAdapter` prepende `/v2/orders` y `/v2/orders/{id}`, y `MarketDataAdapter` prepende `/v2/stocks/.../quotes/latest`. | El usuario pobló `.env` con `https://paper-api.alpaca.markets/v2` copiando del dashboard de Alpaca que muestra "API Endpoint: …/v2" como hint. Resultado: URL final `…/v2/v2/orders` → 404 NOT_FOUND. El `.env.example` ya estaba correcto sin `/v2`; el SPEC v1.1 lo deja documentado para la próxima persona. **Aprendizaje**: instructions como "copia del dashboard" sin especificar el formato exacto producen este tipo de bugs subterráneos. `IntegrationConfig.validateCredentials` ya loguea la URL final al startup — habría que agregarle un check explícito de "no terminar en /v2" en próxima iteración. |
| **D29** | **`status=accepted` tras polling sincrónico → `PENDING + alpacaOrderId`**, no `FAILED`. El cash reservado SÍ se debita para evitar double-spend con órdenes concurrentes. Email + audit `ORDER_QUEUED` separados de FAILED (template ámbar + texto "se ejecutará al abrir el mercado"). | Bug emergente del HITO 8: el demo se hizo fuera de horario NYSE (viernes 21:36 COL = 22:36 ET); Alpaca aceptó la orden pero NO la llenó dentro de los 600 ms de polling sync (3 × 200 ms). El backend marcaba como FAILED con `ALPACA_PENDING_TIMEOUT`, semánticamente incorrecto: la orden no falló, está en cola hasta apertura. Mini-fix Lote H.5: nuevo método `Order.linkToAlpaca(alpacaOrderId)` que setea el ID sin transicionar de estado; nuevo `OrderQueuedEvent` + `OrderEventListener.onOrderQueued`; nuevo email template `order-queued-buy.html`; nuevo `AuditEventType.ORDER_QUEUED`. Frontend `OrderConfirmationToast` detecta `status === 'PENDING'` y renderiza palette ámbar. **Deuda registrada**: reconciliation contra Alpaca cuando el fill finalmente ocurra (job nocturno o webhook) — actualmente esa orden queda en `PENDING + alpacaOrderId != null` indefinidamente. Extiende la deuda #8 del AGENTS.md (Alpaca-paper vs BloomTrade BD). |

---

### 2.4 Decisiones emergentes durante implementación Lote G (D23–D27)

Surgieron al validar concurrencia + rollback semantics con tests IT reales (`TradingControllerIT` + `TradingServiceConcurrencyIT`). Cada una se documenta acá para que el SPEC v1.1 (Lote I) las consolide.

| # | Decisión | Justificación |
|---|---|---|
| **D23** | **Pre-validación optimista de fondos ANTES del INSERT order** (vía `userBalanceRepository.findBalanceProjectionByUserId` — projection-only, ver D26). SPEC §5.1 paso 13 lo menciona; D23 lo materializa explícitamente como check separado del débito post-Alpaca. | Test `placeOrder_insufficientFunds_returns409_noOrderCreated` espera 0 filas en BD cuando funds insuficientes. Sin pre-check, la orden entraba como PENDING + Alpaca submission + débito fallaba → fila quedaba REJECTED. El pre-check corta antes y deja BD limpia. |
| **D24** | **`@Transactional(noRollbackFor = {AlpacaApi, AlpacaRejected, InsufficientFunds}.class)` en `TradingService.placeOrderTx`** + mismo `noRollbackFor=InsufficientFundsException` en `PortfolioService.debit`. | El default Spring rollbackea cualquier RuntimeException, descartando las filas FAILED/REJECTED que el catch quería preservar. Adicional: el nested `@Transactional` de `debit` marca la tx outer como rollback-only al lanzar la excepción — el flag rollback-only "gana" sobre el `noRollbackFor` del outer. Agregar `noRollbackFor` en AMBOS niveles fue necesario. Esto es un caso clásico de "Spring nested tx propagation gotcha". |
| **D25** | **Serialización por `clientOrderId` vía `ConcurrentHashMap<UUID, Object>` + `synchronized` block + self-injection lazy** para que la `@Transactional` commitee DENTRO del lock. Sin esto: 10 threads concurrentes con misma `clientOrderId` cada uno ve `findByClientOrderId` empty (los 10 en paralelo), los 10 intentan INSERT, el `uq_orders_client_order_id` UNIQUE captura 9 con `DataIntegrityViolationException`. | El patrón `@Transactional` outer + `synchronized` block dentro libera el lock ANTES del commit (porque commit ocurre en el proxy AOP tras el método retornar). Para que el commit ocurra dentro del synchronized: mover `@Transactional` a un método interno `placeOrderTx` invocado via `self.placeOrderTx(...)` (self-proxy con `@Lazy` para evitar ciclo). El `clientOrderLocks` map crece monotónico — deuda registrada en javadoc, MVP single-user no tiene impacto. |
| **D26** | **Pre-check de fondos usa projection-only (`findBalanceProjectionByUserId`)** que retorna `BigDecimal`, NO carga el entity `UserBalance` al session L1 cache. | Bug observado en `TradingServiceConcurrencyIT.concurrency_twoOrdersOverlapBalance_exactlyOneSucceeds`: ambos threads ejecutaban exitosamente cuando deberían serializarse. Causa: `portfolioService.getBalance()` cargaba `UserBalance` al cache de la session JPA; luego `findByUserIdForUpdate` (con `@Lock(PESSIMISTIC_WRITE)`) reutilizaba la entity cacheada SIN emitir SELECT FOR UPDATE real → lock pessimistic inútil. La projection devuelve el valor sin tocar el cache. Hibernate quirk conocido. |
| **D27** | **Retries acelerados en perfil `test`**: `resilience4j.retry.instances.{alpacaTradingApi,alpacaDataApi}.wait-duration: 50ms` con `exponential-backoff-multiplier: 2` (vs 1s × 3 del perfil dev). Mantiene `max-attempts: 3`. | Sin override, cada test de retry-then-fallback (Alpaca down) tomaba ~13s (1s + 3s + 9s backoff). Con 3 tests así, suite IT se demoraba ~40s. Con override: ~300ms total. Solo afecta `application-test.yml`, dev queda intacto. |

---

## 3. Cambios de dependencias

**Backend (`pom.xml`):**
- ➕ NADA NUEVO directamente. `RestClient` viene en `spring-boot-starter-web` (ya presente). `Resilience4j` ya presente para `@Retry` (HU-F06). `WireMock` ya presente.
- **Verificar al arrancar Lote A**: `resilience4j-spring-boot3` está en classpath (debería estar tras HU-F06).

**STACK.md**: §7.2 (Polygon) **se actualiza en el PR** para reflejar D9 — marca Polygon como diferido a post-MVP y declara Alpaca Data como proveedor MVP. Sin cambios a §2.3 (deps backend).

**Frontend (`package.json`):** NINGUNO. Reusa axios, react-query, react-router-dom 6.27.0, RHF+zod.

**`.env.example`** — agregar:
```
# Alpaca paper trading (HU-F09)
ALPACA_API_KEY_ID=PK_REPLACE_FROM_DASHBOARD
ALPACA_API_SECRET_KEY=REPLACE_64_CHARS_FROM_DASHBOARD
ALPACA_BASE_URL=https://paper-api.alpaca.markets
ALPACA_DATA_BASE_URL=https://data.alpaca.markets
TRADING_DEFAULT_COMMISSION_PCT=0.02
```

**`docker-compose.yml`** — propagar las 5 vars al servicio `backend` con `:?` para que falle si vacías (mismo patrón JWT_SECRET, STRIPE_API_KEY).

**Setup manual del humano antes del HITO 2** (~5 min):
1. Crear cuenta paper trading en https://alpaca.markets/signup (free).
2. Dashboard → Paper Trading → API Keys → Generate. Copiar `Key ID` (`PK...`) + `Secret`.
3. Poblar las 2 vars en `.env`.

---

## 4. Reuso de HUs previas y cosas nuevas

**Reutilizado tal cual:**
- `shared/web/{ErrorResponse, GlobalExceptionHandler, ValidationMessages, TraceIdFilter}` — extender con códigos nuevos.
- `audit/{Auditor, AuditEvent, AuditEventType}` — agregar 7 entries al enum.
- `notification/{Notifier, MailNotifier}` — extender con 3 métodos (`notifyOrderExecuted`, `notifyOrderRejected`, `notifyOrderFailed`).
- `auth/security/{JwtAuthenticationFilter, AuthenticatedUser}` — reuso directo. El controller usa `@AuthenticationPrincipal AuthenticatedUser`.
- `auth/domain/{User, UserRole, UserStatus}` — leído para validación `estado=ACTIVE`. No se modifica.
- `auth/profile/catalog/AllowedTickers` — reusado para validar ticker ∈ 25. **NO duplicar lista.**
- `portfolio/{UserBalance, UserBalanceRepository, BalanceInitializer, DefaultBalanceInitializer}` — extender repository con `findByUserIdForUpdate(UUID)` (D13).
- `config/{AsyncConfig, JwtAuthenticationFilter, SecurityConfig}` — sin cambios estructurales.

**Nuevo** (estructura de paquetes — D10):

```
backend/src/main/java/co/edu/unbosque/bloomtrade/

├── trading/                              ← MÓDULO NUEVO
│   ├── controller/OrderController.java
│   ├── service/TradingService.java
│   ├── service/OrderOrchestrator.java
│   ├── domain/{Order, OrderSide, OrderType, OrderStatus}.java
│   ├── dto/{QuoteRequest, QuoteResponse, PlaceOrderRequest, OrderResponse}.java
│   ├── mapper/OrderMapper.java
│   ├── exception/{InvalidTickerException, InvalidQuantityException,
│   │              InvalidSideException, InsufficientFundsException,
│   │              AlpacaOrderRejectedException, AlpacaApiException,
│   │              MarketDataUnavailableException, AccountNotActiveException}.java
│   ├── repository/OrderRepository.java
│   └── event/{OrderExecutedEvent, OrderRejectedEvent, OrderFailedEvent,
│              OrderEventListener}.java     ← D15 post-commit
│
├── portfolio/                            ← EXTENDIDO
│   ├── domain/Position.java               (nuevo)
│   ├── repository/PositionRepository.java (nuevo)
│   ├── repository/UserBalanceRepository.java  ← + findByUserIdForUpdate(UUID)
│   └── service/PortfolioService.java      (nuevo — debit, upsertPosition, getBalance)
│
├── admin/                                ← MÓDULO NUEVO
│   ├── domain/CommissionRate.java
│   ├── repository/CommissionRateRepository.java
│   └── service/{ConfigurationManager, CommissionManager, MarketScheduleManager}.java
│
└── integration/
    └── alpaca/                           ← SUB-MÓDULO NUEVO
        ├── IntegrationConfig.java        (RestClient.Builder + headers compartidos)
        ├── AlpacaTradingAdapter.java     (POST /v2/orders)
        ├── MarketDataAdapter.java        (GET /v2/stocks/{sym}/quotes/latest)
        └── dto/{AlpacaOrderRequest, AlpacaOrderResponse,
                AlpacaLatestQuoteResponse}.java
```

**Migración Flyway V5** — un solo archivo:
- `backend/src/main/resources/db/migration/V5__trading_orders_positions_commissions.sql`

**Frontend** (nuevo):
```
frontend/src/
├── pages/TradePage.tsx
├── features/trading/
│   ├── components/{OrderForm, TickerDropdown, OrderQuotePanel,
│   │               OrderConfirmationToast}.tsx
│   ├── hooks/{useQuote, useSubmitOrder}.ts
│   └── api/tradingApi.ts
├── types/api.ts                          ← + OrderSide, OrderType, OrderStatus,
│                                            QuoteRequest, QuoteResponse,
│                                            PlaceOrderRequest, OrderResponse
└── shared/utils/formatBigDecimal.ts      ← nuevo, reutilizable
```

`App.tsx` agrega ruta `/trade` protegida. `AppHeader.tsx` agrega link "Operar".

---

## 5. Orden de lotes (resumen — detalle en `tasks.md`)

| Lote | Resumen | HITO de validación |
|---|---|---|
| **A** | Setup: env vars + STACK.md §7.2 update (D9) + migración V5 + entidades JPA + enums + repositories. Sin lógica de servicio aún. | **HITO 1**: `mvn compile` verde + Flyway V5 aplica + `psql \d` confirma tablas/constraints/seed. |
| **B** | `IntegrationConfig` + `AlpacaTradingAdapter` + `MarketDataAdapter` con `@Retry`. DTOs Alpaca. Sin uso aún. | **HITO 2**: compile + tests unitarios del adapter con `MockRestServiceServer` (3 happy + 3 retry/error). |
| **C** | `ConfigurationManager` + `CommissionManager` + `MarketScheduleManager` (stub). Tests unitarios. | **HITO 3**: compile + `CommissionManagerTest` con casos BigDecimal HALF_UP verde. |
| **D** | `PortfolioService` (`debit`, `upsertPosition`, `getBalance`) + extensión de `UserBalanceRepository.findByUserIdForUpdate`. | **HITO 4**: compile + `PortfolioServiceTest` (debit happy, debit balance-zero, upsert nuevo, upsert existente con avg_buy_price recalculado). |
| **E** | `TradingService` (`quote`, `placeOrder`) + `OrderOrchestrator` + `OrderController` + DTOs + mapper + 8 excepciones + 6 handlers en `GlobalExceptionHandler`. | **HITO 5**: compile + `mvn verify` con tests unitarios verdes. Endpoints documentados en Swagger UI. |
| **F** | `OrderEventListener` (D15) + `Notifier.notifyOrderExecuted/Rejected/Failed` + 3 templates Thymeleaf. AuditEventType +7. | **HITO 6**: compile + test del listener verifica que evento se dispara post-commit. |
| **G** | Tests IT con WireMock (`TradingControllerIT`, `TradingServiceIT`): happy, Alpaca down, Alpaca rechaza, market data down, insufficient funds, idempotencia, concurrencia. | **HITO 7**: `mvn verify` con todos los IT verdes. |
| **H** | Frontend: types + `tradingApi.ts` + 2 hooks + 4 componentes + `TradePage` + ruta + link header + `messages.es.ts` +10 códigos. | **HITO 8**: `npm run build` verde + smoke E2E manual (humano valida cargando localhost:5173 tras `docker compose up`). |
| **I** | Cierre: STACK.md §7.2 actualizado (D9), SPEC v1.1 consolidado (changelog + cambios surgicales por D9), APRENDIZAJES.md sección "Día 6", AGENTS.md handoff actualizado. | **HITO 9**: commit message preparado en `%TEMP%\bt-hu-f09.txt`. Humano firma + PR. |

---

## 6. Riesgos identificados

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Alpaca Data API rate-limit o down → bloquea quotes | Media | Alto | D6 fail-fast + HU-F18 cache (post-F09). Comentar al humano que arme `.env` correctamente antes del HITO 5. |
| Alpaca Paper account compartida — fondos virtuales se acaban en pruebas | Baja | Medio | Alpaca paper resetea trimestralmente. Documentar en README. Si pasa: reset manual desde dashboard Alpaca. |
| `BigDecimal` con division → `ArithmeticException: non-terminating decimal expansion` | Media | Alto | Siempre usar `divide(divisor, 4, RoundingMode.HALF_UP)`. Lint check + test parametrizado. |
| Lock pessimistic + transacción larga (Alpaca tarda 500ms) → contención | Media | Bajo | MVP single user — no aplica. Documentado como deuda. Post-MVP: outbox pattern. |
| Diferencia entre quote precio y execution precio confunde al usuario | Alta | Bajo | UI educa: "Precio estimado" + "Precio real de ejecución". Tooltip en formulario. |
| El humano olvida poblar `.env` con creds Alpaca → backend no arranca | Media | Bajo | `IntegrationConfig` valida al arrancar (fail-fast) + log claro. README documenta setup. |
| Inconsistencia Alpaca-éxito + commit-local-fallido (D17) | Baja | Medio | Deuda registrada. `client_order_id` permite reconcilación manual. |
| `app.user_balances.CHECK (balance >= 0)` se viola en concurrencia → error 500 genérico al usuario | Baja | Bajo | Lock pessimistic D13 lo previene. Si llega: `GlobalExceptionHandler` mapea `DataIntegrityViolationException` a 409 (handler existente o agregar). |
| Tests IT con WireMock no estabilizan (timeouts) | Media | Medio | Timeouts del `RestClient` configurados explícitos (connect 2s, read 5s); WireMock con response inmediato. Si flakea: aumentar timeouts en perfil `test`. |

---

## 7. Validaciones de DoD por HITO

Resumen de qué se verifica en cada HITO; el detalle granular vive en `tasks.md`:

- **HITO 1** (Lote A): `mvn compile` + `docker compose up -d backend` + logs muestran "Flyway V5 applied" + `psql -c "\d app.orders"` confirma 14 columnas + 5 constraints + 5 índices + seed INSERT en `config.commission_rates` resulta en 1 fila `(INVESTOR, 0.0200)`.
- **HITO 2** (Lote B): tests del adapter con MockRestServiceServer cubren: 200 happy path, 429 rate limit (1 retry, eventual 429), 503 (3 retries, finally fail), timeout (3 retries, finally fail), respuesta malformada (parse error).
- **HITO 3** (Lote C): `CommissionManagerTest` con 6 casos BigDecimal incluyendo bordes (`0.01` precio, `9999.99` precio, división con remainder).
- **HITO 4** (Lote D): `PortfolioServiceTest` 4 casos. `UserBalanceRepositoryTest` verifica `findByUserIdForUpdate` toma el lock (manualmente con dos threads).
- **HITO 5** (Lote E): Swagger UI accesible en localhost:8080/swagger-ui.html; los dos endpoints aparecen con request/response examples. `TradingServiceTest` cubre 8 casos.
- **HITO 6** (Lote F): test del listener con `@TransactionalEventListener`: assert que el evento NO se procesa antes del commit (rollback de la TX no dispara notification).
- **HITO 7** (Lote G): `TradingControllerIT` con 7 escenarios; `TradingServiceIT` con idempotencia × 10 + concurrencia × 2.
- **HITO 8** (Lote H): demo manual humano: `docker compose up -d --build` → login → /trade → AAPL × 10 → quote → confirm → toast éxito → MailHog tiene email + Kibana tiene 2 events (`ORDER_CREATED`, `ORDER_EXECUTED`) + `psql` confirma fila en `orders` con `status=EXECUTED`, `user_balances.balance` decrementado, `positions` con la nueva posición.
- **HITO 9** (Lote I): commit listo + APRENDIZAJES con sección "Día 6 — HU-F09" + handoff AGENTS.md actualizado.

---

## 8. Cambios en documentos maestros

- **STACK.md** §7.2 (Polygon): actualizar para reflejar D9 — Polygon diferido a post-MVP, Alpaca Data es el proveedor MVP. Agregar entrada al historial al final del archivo.
- **ARCHITECTURE.md** §4 IntegrationService: `MarketDataAdapter` ya menciona "Polygon.io (o Alpha Vantage como alterno)" — actualizar a "Alpaca Data (o Polygon.io como alterno)". Sin cambios estructurales.
- **SPEC.md**: deferred a HITO 6 (Lote I) — consolidación v1.1 que aplica las modificaciones puntuales documentadas en D9. Mismo patrón HU-F06 v1.2.

---

## 9. Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-22 | Versión inicial. D1–D8 del cuestionario humano + D9 (D-MD-PROVIDER: Alpaca-only) + D10–D22 emergentes del diseño. | Cierre del SDD Paso 2 para HU-F09. Pendiente de aprobación humana antes de Paso 3 (tasks). |

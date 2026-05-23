# tasks.md — HU-F09 Orden de compra Market

> Descomposición granular del `plan.md` (SDD Paso 3).
> Cadencia: lotes lógicos con validación en HITOs [[feedback-cadencia-sdd]].
> Rama: `feat/HU-F09-orden-compra-market`.
> Commits con `refs HU-F09 specs/HU-F09-orden-compra-market/SPEC.md` + `Co-authored-by: Claude <noreply@anthropic.com>`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho · ✗ cancelado/diferido

---

## Lote A — Setup: env vars + STACK.md + migración V5 + entidades JPA

> Objetivo: stack compila, V5 aplica limpio, entidades + repositories + enums listos para los lotes siguientes. SIN lógica de servicio aún.

### Configuración

- ☐ **T1.1** `.env.example` MODIFICADO — agregar bloque Alpaca con `ALPACA_API_KEY_ID=PK_REPLACE_FROM_DASHBOARD`, `ALPACA_API_SECRET_KEY=REPLACE_64_CHARS_FROM_DASHBOARD`, `ALPACA_BASE_URL=https://paper-api.alpaca.markets`, `ALPACA_DATA_BASE_URL=https://data.alpaca.markets`, `TRADING_DEFAULT_COMMISSION_PCT=0.02`. Sección comentada explica para qué sirve cada una.
- ☐ **T1.2** `docker-compose.yml` MODIFICADO — propagar 5 vars al servicio `backend` con sintaxis `${VAR_NAME:?}` (mismo patrón que `JWT_SECRET` y `STRIPE_API_KEY`) para que falle fast si están vacías.
- ☐ **T1.3** `STACK.md` §7.2 MODIFICADO — Polygon.io marcado como **diferido a post-MVP**. Reemplazar el bloque de Polygon con: "*Para el MVP se usa Alpaca Market Data (incluido en la cuenta paper trading) como proveedor único. Polygon queda diferido a post-MVP. Decisión: D9 D-MD-PROVIDER en `specs/HU-F09-orden-compra-market/plan.md`.*". Agregar entrada al historial al final del archivo.
- ☐ **T1.4** `application.yml` MODIFICADO — agregar `resilience4j.retry.instances.alpacaTradingApi` y `resilience4j.retry.instances.alpacaDataApi` con `max-attempts=3`, `wait-duration=1s`, `enable-exponential-backoff=true`, `exponential-backoff-multiplier=3` (resulta en 1s, 3s, 9s — aceptable vs el 1s/3s/5s del SPEC dado que el multiplier de Resilience4j es geométrico). Documentar el ajuste como D6 nota en plan.md si surge en code review.
- ☐ **T1.5** `application.yml` MODIFICADO — agregar bloque `alpaca:` con `key-id: ${ALPACA_API_KEY_ID}`, `secret-key: ${ALPACA_API_SECRET_KEY}`, `trading-base-url: ${ALPACA_BASE_URL}`, `data-base-url: ${ALPACA_DATA_BASE_URL}`, `trading-timeout-connect: 2s`, `trading-timeout-read: 5s`. Y bloque `trading:` con `default-commission-pct: ${TRADING_DEFAULT_COMMISSION_PCT:0.02}`, `max-quantity-per-order: 10000`.

### Migración V5

- ☐ **T1.6** `backend/src/main/resources/db/migration/V5__trading_orders_positions_commissions.sql` — DDL completo de SPEC §7.2:
    1. `CREATE TABLE app.orders` con 17 columnas, 4 CHECK constraints, 1 UNIQUE, 4 índices + 1 UNIQUE índice parcial `idx_orders_alpaca_order_id`.
    2. `CREATE TABLE app.positions` con 7 columnas, 1 CHECK, 1 UNIQUE `(user_id, ticker)`, 1 índice.
    3. `CREATE TABLE config.commission_rates` con 7 columnas, 2 CHECK, 1 UNIQUE parcial `WHERE valid_to IS NULL`.
    4. `INSERT INTO config.commission_rates (role, percentage) VALUES ('INVESTOR', 0.0200);` — seed.
    5. Header del archivo con `-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada` + ref a la SPEC + plan.

### Entidades JPA + enums

- ☐ **T1.7** `trading/domain/OrderSide.java` — enum `{BUY, SELL}`. SELL incluido para anticipar F10 pero handler lanza `INVALID_SIDE` con código `SIDE_NOT_YET_IMPLEMENTED` hasta F10.
- ☐ **T1.8** `trading/domain/OrderType.java` — enum con un solo valor `MARKET` por ahora. `@JsonValue` para serialización.
- ☐ **T1.9** `trading/domain/OrderStatus.java` — enum `{PENDING, EXECUTED, REJECTED, FAILED}` (D16 — 4 valores estrechados, no los 10 de ARCH §9).
- ☐ **T1.10** `trading/domain/Order.java` — entity JPA mapeada a `app.orders`. Sin Lombok `@Data` (CONVENTIONS §5.4.3). Usar `@Getter` + métodos de dominio:
    - `markAsExecuted(String alpacaOrderId, BigDecimal executionUnitPrice)` — setea status, alpacaOrderId, executionUnitPrice, executionTotal (= executionUnitPrice × quantity + quotedCommission), executedAt = Instant.now().
    - `markAsRejected(String errorCode, String errorMessage)` — setea status REJECTED + error fields.
    - `markAsFailed(String errorCode, String errorMessage)` — setea status FAILED + error fields.
    - Factory estático `Order.newPending(...)` que recibe todos los datos del quote + clientOrderId y construye con status PENDING.
    - `@CreationTimestamp` en `submittedAt`. `executedAt` se setea manualmente.
- ☐ **T1.11** `trading/repository/OrderRepository.java` — `JpaRepository<Order, UUID>`. Métodos:
    - `findByClientOrderId(UUID clientOrderId): Optional<Order>` — para idempotencia.
    - `findByUserIdOrderBySubmittedAtDesc(UUID userId): List<Order>` — para HU-F17 (post-MVP); incluido como prep.
- ☐ **T1.12** `portfolio/domain/Position.java` — entity JPA mapeada a `app.positions`. Sin Lombok `@Data`. Métodos de dominio:
    - `incrementBy(int additionalQty, BigDecimal additionalUnitPrice)` — recalcula `quantity_new = quantity + additionalQty` y `avg_buy_price_new = ((quantity × avg_buy_price) + (additionalQty × additionalUnitPrice)) / quantity_new` con `setScale(4, HALF_UP)`. Setea `updatedAt = Instant.now()`.
    - Factory estático `Position.newPosition(userId, ticker, quantity, avgBuyPrice)`.
- ☐ **T1.13** `portfolio/repository/PositionRepository.java` — `JpaRepository<Position, UUID>`. Métodos:
    - `findByUserIdAndTicker(UUID userId, String ticker): Optional<Position>` — para upsert decision.
    - `findByUserId(UUID userId): List<Position>` — para HU-F16 (prep).
- ☐ **T1.14** `portfolio/repository/UserBalanceRepository.java` MODIFICADO — agregar método con lock pessimistic:
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM UserBalance b WHERE b.userId = :userId")
    Optional<UserBalance> findByUserIdForUpdate(@Param("userId") UUID userId);
    ```
- ☐ **T1.15** `admin/domain/CommissionRate.java` — entity JPA. Sin Lombok `@Data`. Usar `@Getter` para id, role, percentage, validFrom, validTo. Sin métodos de dominio (es config inmutable desde el código; HU-F30 expondrá UI para crear nuevas filas).
- ☐ **T1.16** `admin/repository/CommissionRateRepository.java` — método `findFirstByRoleAndValidToIsNullOrderByValidFromDesc(String role): Optional<CommissionRate>` (la fila "activa" para el rol).

### Verificación HITO 1

- ☐ **T1.17** `mvn -f backend/pom.xml compile` verde.
- ☐ **T1.18** `docker compose up -d --build backend` — verificar logs muestran "Successfully applied 1 migration to schema ... V5". Hibernate no falla en startup.
- ☐ **T1.19** `psql -h localhost -p 5433 -U bloomtrade -d bloomtrade -c "\d app.orders"` — confirma 14 columnas + 5 constraints + 5 índices.
- ☐ **T1.20** `psql ... -c "SELECT role, percentage FROM config.commission_rates;"` — devuelve `(INVESTOR, 0.0200)`.
- ☐ **T1.21** `psql ... -c "\d app.positions"` y `\d config.commission_rates` — confirman estructura.
- ☐ **T1.22** **← HITO 1 ✅** compile + Flyway V5 verde + entidades cargadas + seed presente.

---

## Lote B — Adapters Alpaca (IntegrationService — TAC-M1/I2/D2)

> Objetivo: dos clases que envuelven los 2 endpoints Alpaca con retries + serialización. Tests con `MockRestServiceServer`. Sin uso aún.

### Config + cliente compartido

- ☐ **T2.1** `integration/alpaca/IntegrationConfig.java` — `@Configuration` con dos `@Bean RestClient`:
    - `alpacaTradingRestClient(RestClient.Builder builder, @Value("${alpaca.trading-base-url}") String baseUrl, AlpacaProperties props)` — `baseUrl(...)` + headers comunes (`APCA-API-KEY-ID`, `APCA-API-SECRET-KEY`) + timeouts vía `requestFactory(new HttpComponentsClientHttpRequestFactory(...))`.
    - `alpacaDataRestClient(...)` análogo con `data-base-url`.
    - `AlpacaProperties` como `@ConfigurationProperties("alpaca")` record con todos los campos.
    - Fail-fast en startup si `key-id` o `secret-key` están vacíos (log + `IllegalStateException`).

### DTOs

- ☐ **T2.2** `integration/alpaca/dto/AlpacaOrderRequest.java` — record con campos exactos del API Alpaca: `symbol`, `qty`, `side`, `type`, `time_in_force` (= `"day"`), `client_order_id`. Usa `@JsonProperty` para snake_case si los nombres Java son camelCase.
- ☐ **T2.3** `integration/alpaca/dto/AlpacaOrderResponse.java` — record con campos relevantes: `id` (alpacaOrderId), `client_order_id`, `status`, `filled_avg_price` (puede ser null antes de fill), `filled_qty`, `created_at`. Para market orders en paper trading, el status final ('filled' o 'rejected') vuelve en la respuesta inicial (ms-sub-segundo).
- ☐ **T2.4** `integration/alpaca/dto/AlpacaLatestQuoteResponse.java` — record que mapea el shape del endpoint `/v2/stocks/{symbol}/quotes/latest`. Estructura: `{ quote: { ap (ask price), bp (bid price), as (ask size), bs (bid size), t (timestamp) }, symbol }`. Helper method `getMidPrice(): BigDecimal` = `(ap + bp) / 2` redondeado a 4 decimales. Si el bid o ask es 0 (mercado cerrado), usar el otro lado. Si ambos son 0, lanzar excepción `MarketDataUnavailableException`.

### Excepciones del integration

- ☐ **T2.5** `integration/alpaca/AlpacaApiException.java` — `RuntimeException` con `attempts` y `lastError`. Mapeada a 502 ALPACA_API_ERROR.
- ☐ **T2.6** `integration/alpaca/AlpacaOrderRejectedException.java` — `RuntimeException` con `alpacaReason` y `alpacaCode` (raw del response). Mapeada a 422 ALPACA_ORDER_REJECTED.
- ☐ **T2.7** `integration/alpaca/MarketDataUnavailableException.java` — `RuntimeException`. Mapeada a 502 MARKET_DATA_UNAVAILABLE.

### Adapters

- ☐ **T2.8** `integration/alpaca/AlpacaTradingAdapter.java` — clase con un solo método público:
    ```java
    @Retry(name = "alpacaTradingApi", fallbackMethod = "submitMarketOrderFallback")
    public AlpacaOrderResponse submitMarketOrder(SubmitMarketOrderCommand command) { ... }
    ```
    - Construye `AlpacaOrderRequest` desde el command.
    - POST a `/v2/orders` vía `alpacaTradingRestClient`.
    - Detecta status `rejected` en la respuesta → lanza `AlpacaOrderRejectedException` (NO retryable).
    - Detecta 4xx no-rejected (400, 401, 403) → lanza `AlpacaApiException` con `attempts=1` (NO retryable — error determinista).
    - Detecta 5xx, 429, timeouts → lanza `AlpacaApiException`. Resilience4j retry los reintenta. Tras 3 fallos, fallback method lanza `AlpacaApiException(attempts=3, lastError=...)`.
- ☐ **T2.9** `integration/alpaca/MarketDataAdapter.java` — clase con un solo método público:
    ```java
    @Retry(name = "alpacaDataApi", fallbackMethod = "getLatestPriceFallback")
    public BigDecimal getLatestPrice(String ticker) { ... }
    ```
    - GET a `/v2/stocks/{ticker}/quotes/latest` vía `alpacaDataRestClient`.
    - Extrae `quote.ap` y `quote.bp` del response; calcula mid-price.
    - 429 (rate limited) → emite WARN log + lanza `MarketDataUnavailableException`. Resilience4j retry no aplica al 429 (deterministic durante 1min window) — agregar a `ignored-exceptions` en `application.yml`. **Update:** revisar comportamiento exacto en T2.10.
    - 5xx, timeout → `MarketDataUnavailableException`. Retryable.
- ☐ **T2.10** Configurar `application.yml` `resilience4j.retry.instances.alpacaTradingApi.retry-exceptions` y `.ignore-exceptions` para distinguir retryables (5xx, timeout) de no-retryables (rejected, 4xx, signature). Igual para `alpacaDataApi`.
- ☐ **T2.11** `trading/service/SubmitMarketOrderCommand.java` — record con `clientOrderId`, `ticker`, `quantity`, `side` (BUY/SELL — preparado para F10). Solo input limpio al adapter.

### Tests del Lote B

- ☐ **T2.12** `AlpacaTradingAdapterTest.java` — usa `MockRestServiceServer` (o WireMock — preferir `MockRestServiceServer` por velocidad de setup; reservar WireMock para los IT del Lote G):
    - happy path: 200 OK con status=filled → retorna `AlpacaOrderResponse` con `id` y `filled_avg_price`.
    - rejected: 200 OK con status=rejected → lanza `AlpacaOrderRejectedException`.
    - 4xx invalid: 400 → lanza `AlpacaApiException` con `attempts=1`.
    - 5xx server error: 503 × 3 → lanza `AlpacaApiException` con `attempts=3`.
    - timeout: simular `ResourceAccessException` × 3 → lanza `AlpacaApiException`.
    - 429: comportamiento esperado (depende de config T2.10).
- ☐ **T2.13** `MarketDataAdapterTest.java`:
    - happy path: 200 OK → retorna `BigDecimal` mid-price.
    - 429: lanza `MarketDataUnavailableException` con audit `MARKET_DATA_RATE_LIMITED` (verificar con `@MockBean Auditor` — pero como audit emite via listener post-flow, este test puede ser unit puro sin tocar Auditor).
    - 503 × 3: lanza `MarketDataUnavailableException`.
    - quote con bid=0, ask=0: lanza `MarketDataUnavailableException`.

### Verificación HITO 2

- ☐ **T2.14** `mvn -f backend/pom.xml -Dtest='AlpacaTradingAdapterTest,MarketDataAdapterTest' test` verde.
- ☐ **T2.15** **← HITO 2 ✅** adapters listos + tests verdes.

---

## Lote C — AdminService: ConfigurationManager + CommissionManager + MarketScheduleManager

> Objetivo: cálculo de comisión robusto con BigDecimal + stub de market schedule. Tests parametrizados de bordes.

- ☐ **T3.1** `admin/service/ConfigurationManager.java` — clase con método:
    ```java
    public BigDecimal getCommissionPercentage(String role) {
        return commissionRateRepository
            .findFirstByRoleAndValidToIsNullOrderByValidFromDesc(role)
            .map(CommissionRate::getPercentage)
            .orElse(new BigDecimal(defaultCommissionPct));  // de @Value
        }
    ```
    - `@Value("${trading.default-commission-pct:0.02}")` como fallback. Log WARN si se usa el fallback.
- ☐ **T3.2** `admin/service/CommissionManager.java` — clase con método:
    ```java
    public BigDecimal calculate(String role, BigDecimal subtotal) {
        BigDecimal pct = configurationManager.getCommissionPercentage(role);
        return subtotal.multiply(pct).setScale(2, RoundingMode.HALF_UP);
    }
    ```
    - Test parametrizado verifica casos de borde (ver T3.5).
- ☐ **T3.3** `admin/service/MarketScheduleManager.java` — stub MVP:
    ```java
    public boolean isOpenNow(String ticker) {
        // TODO: HU-F14 — validar contra horarios de mercado de ARCH §1
        // Para MVP single-user demo, asumimos siempre abierto.
        return true;
    }
    ```
    Javadoc claro explicando que es stub.
- ☐ **T3.4** `CommissionManagerTest.java` — `@ParameterizedTest` con `@CsvSource`:
    ```
    "1000.00, 0.02, 20.00"
    "1845.00, 0.02, 36.90"
    "1000.001, 0.02, 20.00"    // verifica HALF_UP (no 20.00002)
    "0.01, 0.02, 0.00"          // verifica HALF_UP redondea 0.0002 a 0.00
    "9999.99, 0.02, 200.00"
    "1234.56, 0.0250, 30.86"    // verifica con porcentaje 4-decimal
    ```
    Test verifica scale=2 y value exactos.
- ☐ **T3.5** `ConfigurationManagerTest.java`:
    - happy: fila INVESTOR en BD → retorna 0.02.
    - fallback: BD vacía → retorna defaultCommissionPct + log WARN (verificar con `@MockBean Logger` o capturando appender).
- ☐ **T3.6** `MarketScheduleManagerTest.java` — 1 test verifica que retorna `true` para cualquier ticker (anti-regresión cuando F14 lo implemente).
- ☐ **T3.7** **← HITO 3 ✅** `mvn -Dtest='CommissionManagerTest,ConfigurationManagerTest,MarketScheduleManagerTest' test` verde.

---

## Lote D — PortfolioService: debit + upsertPosition + getBalance

> Objetivo: operaciones de portafolio encapsuladas. Lock pessimistic verificado.

- ☐ **T4.1** `portfolio/service/PortfolioService.java` — clase con métodos:
    ```java
    @Transactional
    public BigDecimal debit(UUID userId, BigDecimal amount) {
        UserBalance balance = userBalanceRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new IllegalStateException("Balance not found"));
        BigDecimal newBalance = balance.getBalance().subtract(amount);
        if (newBalance.signum() < 0) {
            throw new InsufficientFundsException(balance.getBalance(), amount);
        }
        balance.setBalance(newBalance);
        return newBalance;
    }

    @Transactional
    public Position upsertPosition(UUID userId, String ticker, int additionalQty, BigDecimal unitPrice) {
        return positionRepository.findByUserIdAndTicker(userId, ticker)
            .map(existing -> {
                existing.incrementBy(additionalQty, unitPrice);
                return existing;
            })
            .orElseGet(() -> positionRepository.save(
                Position.newPosition(userId, ticker, additionalQty, unitPrice)
            ));
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID userId) { ... }

    @Transactional(readOnly = true)
    public List<Position> getPositions(UUID userId) { ... }
    ```
- ☐ **T4.2** `trading/exception/InsufficientFundsException.java` — `RuntimeException(BigDecimal balance, BigDecimal required)`. Getters para mapper. Computa `shortfall = required - balance` para el response.
- ☐ **T4.3** `PortfolioServiceTest.java` — usar `@DataJpaTest` o `@SpringBootTest` con perfil `test` (D16 HU-F01: postgres real localhost:5433/bloomtrade_test):
    - `debit_happyPath_reducesBalance`: usuario con balance 10000, debit 1881.90 → newBalance 8118.10.
    - `debit_insufficientFunds_throwsAndDoesNotPersist`: balance 100, debit 1000 → throws `InsufficientFundsException`; assert balance permanece 100 tras rollback.
    - `debit_exactBalance_resultsInZero`: balance 1000, debit 1000 → newBalance 0.00 (no falla CHECK).
    - `upsertPosition_newTicker_inserts`: sin posición previa → 1 fila nueva con `qty=10, avg_buy_price=184.62`.
    - `upsertPosition_existingTicker_updatesAndRecalculatesAvgPrice`: posición previa `{qty=10, avg_buy=184.62}` + nueva compra `{qty=10, unit_price=190.00}` → `{qty=20, avg_buy=187.31}`. Verifica scale=4.
- ☐ **T4.4** `UserBalanceRepositoryConcurrencyTest.java` — test específico del lock pessimistic. Usa `CompletableFuture` con 2 threads que intentan `findByUserIdForUpdate` simultáneamente sobre el mismo userId; el segundo debe esperar al commit del primero. Verifica con timestamps (`System.nanoTime()` diff). Si el test es flaky, marca `@Disabled` con TODO + documenta como deuda; la garantía formal viene del Lote G concurrencia E2E.
- ☐ **T4.5** **← HITO 4 ✅** `mvn -Dtest='PortfolioServiceTest,UserBalanceRepositoryConcurrencyTest' test` verde.

---

## Lote E — TradingService + OrderOrchestrator + OrderController + DTOs + Errors

> Objetivo: la lógica core de F09. Es el lote más grande. Al terminar, los 2 endpoints están en Swagger UI y funcionan en happy path con WireMock.

### DTOs API

- ☐ **T5.1** `trading/dto/QuoteRequest.java` — record con `@NotBlank ticker`, `@NotNull side` (`OrderSide` enum), `@NotNull @Min(1) @Max(10000) Integer quantity`.
- ☐ **T5.2** `trading/dto/QuoteResponse.java` — record con todos los campos de SPEC §6.1.1:
    ```java
    public record QuoteResponse(
        String ticker, OrderSide side, int quantity,
        String estimatedUnitPrice, String estimatedSubtotal,
        String commission, String estimatedTotal, String currency,
        String userBalance, boolean sufficientFunds, boolean marketOpen,
        Instant quotedAt
    ) {}
    ```
    Todos los montos son `String` (no `BigDecimal`) para preservar precisión en JSON.
- ☐ **T5.3** `trading/dto/PlaceOrderRequest.java` — record con `@NotNull UUID clientOrderId`, `@NotBlank ticker`, `@NotNull side`, `@NotNull type` (`OrderType` enum, solo MARKET), `@NotNull @Min(1) @Max(10000) Integer quantity`. Validator custom `@AllowedTicker` (de HU-F04) sobre `ticker`.
- ☐ **T5.4** `trading/dto/OrderResponse.java` — record con campos de SPEC §6.1.2. Igual que QuoteResponse: montos como `String`.
- ☐ **T5.5** `trading/mapper/OrderMapper.java` — MapStruct interface. Mapea `Order` → `OrderResponse`. Use `@Named` qualifier para convertir `BigDecimal` → `String` con `.toPlainString()`. **NO mapea credenciales Alpaca** (no las tiene). Sí mapea `alpacaOrderId` (es público).

### Excepciones + handlers globales

- ☐ **T5.6** Crear excepciones en `trading/exception/`:
    - `InvalidTickerException` (String ticker) — 400 INVALID_TICKER.
    - `InvalidQuantityException` (int quantity, int max) — 400 INVALID_QUANTITY.
    - `InvalidSideException` (String message) — 400 INVALID_SIDE / SIDE_NOT_YET_IMPLEMENTED.
    - `AccountNotActiveException` (UserStatus actualState) — 403 ACCOUNT_NOT_ACTIVE.
    - (las otras 4 ya creadas en Lotes B y D).
- ☐ **T5.7** `shared/web/GlobalExceptionHandler.java` MODIFICADO — agregar 8 handlers:
    - `InvalidTickerException` → 400.
    - `InvalidQuantityException` → 400.
    - `InvalidSideException` → 400 (puede usar el message como código si distingue casos).
    - `InsufficientFundsException` → 409 con `details = { balance, required, shortfall }`.
    - `AlpacaOrderRejectedException` → 422 con `details = { alpacaReason, alpacaCode }`.
    - `AlpacaApiException` → 502 con `details = { attempts, lastError }`.
    - `MarketDataUnavailableException` → 502.
    - `AccountNotActiveException` → 403.
- ☐ **T5.8** `validation-messages.properties` MODIFICADO — agregar códigos:
    ```
    INVALID_TICKER=Este ticker no está habilitado para operar.
    INVALID_QUANTITY=La cantidad debe ser un entero positivo entre 1 y {0}.
    INVALID_SIDE=Operación inválida.
    SIDE_NOT_YET_IMPLEMENTED=La venta estará disponible próximamente.
    INSUFFICIENT_FUNDS=Saldo insuficiente. Tu saldo: USD {0}, requerido: USD {1}.
    ALPACA_ORDER_REJECTED=El mercado rechazó tu orden: {0}.
    ALPACA_API_ERROR=Alpaca no respondió. Tu saldo está intacto. Intenta nuevamente.
    MARKET_DATA_UNAVAILABLE=No se pudo obtener el precio actual. Intenta de nuevo.
    ACCOUNT_NOT_ACTIVE=Tu cuenta no está activa. Contacta soporte.
    ```

### TradingService (lógica core)

- ☐ **T5.9** `trading/service/TradingService.java` con dos métodos públicos:
    - `@Transactional(readOnly = true) public QuoteResponse quote(UUID userId, QuoteRequest request)`:
        1. Validar ticker ∈ `AllowedTickers` (de HU-F04). Si no: `InvalidTickerException`.
        2. Validar `quantity` ∈ [1, 10000]. Si no: `InvalidQuantityException`.
        3. Validar `side`: si SELL → `InvalidSideException("SIDE_NOT_YET_IMPLEMENTED")`. Si BUY, continuar.
        4. Verificar usuario `estado=ACTIVE` (lookup con `userRepository.findById`). Si no: `AccountNotActiveException`.
        5. `BigDecimal unitPrice = marketDataAdapter.getLatestPrice(ticker)`. Si falla: propaga `MarketDataUnavailableException`.
        6. `BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity))`.
        7. `BigDecimal commission = commissionManager.calculate("INVESTOR", subtotal)`.
        8. `BigDecimal total = subtotal.add(commission)`.
        9. `BigDecimal balance = portfolioService.getBalance(userId)`.
        10. `boolean sufficientFunds = balance.compareTo(total) >= 0`.
        11. `boolean marketOpen = marketScheduleManager.isOpenNow(ticker)`.
        12. Return `QuoteResponse` con todos los campos formateados (`.setScale(2, HALF_UP).toPlainString()` para subtotal/commission/total/balance; unitPrice mantiene scale=4).
    - `@Transactional public OrderResponse placeOrder(UUID userId, PlaceOrderRequest request)`:
        1. Verificar idempotencia: `orderRepository.findByClientOrderId(request.clientOrderId())`. Si existe: emitir audit `ORDER_DUPLICATE_REQUEST` (eventPublisher), retornar `OrderResponse` con HTTP 200 (controller distinguirá 200 vs 201 según marca).
        2. Repetir validaciones del quote (ticker, quantity, side, account).
        3. `unitPrice = marketDataAdapter.getLatestPrice(ticker)` — re-fetch fresh.
        4. `subtotal`, `commission`, `quotedTotal` como antes.
        5. `marketOpen = marketScheduleManager.isOpenNow(ticker)`. Si false: `InvalidSideException("MARKET_CLOSED")` (D11 stub siempre true; este path no se ejecuta en MVP pero el código está listo).
        6. **Crear y persistir Order PENDING**: `Order order = Order.newPending(userId, request, unitPrice, commission, quotedTotal); orderRepository.save(order);` — flush para tener id.
        7. **Llamar Alpaca**: `AlpacaOrderResponse alpacaResp = alpacaTradingAdapter.submitMarketOrder(SubmitMarketOrderCommand.from(order))`.
        8. **Procesar respuesta**:
            - Si `alpacaResp.status() == "filled"`:
                - `BigDecimal executionUnitPrice = new BigDecimal(alpacaResp.filledAvgPrice())`.
                - `BigDecimal executionTotal = executionUnitPrice.multiply(BigDecimal.valueOf(quantity)).add(commission)`.
                - `order.markAsExecuted(alpacaResp.id(), executionUnitPrice)`.
                - `portfolioService.debit(userId, executionTotal)` — toma lock + decrementa. Si `InsufficientFundsException`: el saldo cambió entre quote y execution (concurrencia). Re-marcar order como REJECTED con `INSUFFICIENT_FUNDS` y propagar.
                - `portfolioService.upsertPosition(userId, ticker, quantity, executionUnitPrice)`.
                - Publicar `OrderExecutedEvent(order.getId(), userId, ...)` para post-commit dispatch (Lote F).
        9. **Si Alpaca lanza `AlpacaOrderRejectedException`**: `order.markAsRejected("ALPACA_ORDER_REJECTED", ex.getAlpacaReason()); orderRepository.save(order); publishEvent(OrderRejectedEvent)`; relanzar.
        10. **Si Alpaca lanza `AlpacaApiException`**: `order.markAsFailed("ALPACA_API_ERROR", ex.getMessage()); orderRepository.save(order); publishEvent(OrderFailedEvent)`; relanzar.
        11. Return mapper.toResponse(order).
- ☐ **T5.10** `trading/service/SubmitMarketOrderCommand.java` — método estático `from(Order)` que extrae los campos a enviar a Alpaca.

### Controller

- ☐ **T5.11** `trading/controller/OrderController.java`:
    - `@RestController @RequestMapping("/api/v1/orders") @Tag(name = "Orders") @Validated`
    - `POST /quote` con `@Valid @RequestBody QuoteRequest`. Resuelve `userId` desde `@AuthenticationPrincipal AuthenticatedUser principal -> principal.userId()`. OpenAPI: 200, 400, 401, 403, 502.
    - `POST /` con `@Valid @RequestBody PlaceOrderRequest`. Devuelve `ResponseEntity<OrderResponse>` con status 201 (nuevo) o 200 (idempotente — detectar por flag interno o por timestamp comparado a `Instant.now() - 1s`). OpenAPI: 200, 201, 400, 401, 403, 409, 422, 502.
    - Anotaciones SpringDoc @Operation / @ApiResponse / @ExampleObject con bodies reales.

### Tests del Lote E

- ☐ **T5.12** `TradingServiceTest.java` — `@ExtendWith(MockitoExtension.class)` con `@Mock` para todos los repositorios y adapters. Cubre:
    - `quote_happyPath`: retorna response con valores esperados.
    - `quote_invalidTicker`: lanza `InvalidTickerException`.
    - `quote_marketDataDown`: lanza `MarketDataUnavailableException`.
    - `placeOrder_happyPath_executedExtractsAllSideEffects`: verifica que Order se persiste con status EXECUTED, debit llamado, upsertPosition llamado, evento publicado.
    - `placeOrder_idempotency_returnsExistingOrder`: cuando `findByClientOrderId` retorna Optional con orden EXECUTED, NO se llama Alpaca, NO se debita.
    - `placeOrder_alpacaRejected_marksRejectedAndDoesNotDebit`: lanza AlpacaOrderRejectedException → orden status REJECTED, debit nunca llamado.
    - `placeOrder_alpacaApiError_marksFailedAndDoesNotDebit`: lanza AlpacaApiException → orden status FAILED.
    - `placeOrder_insufficientFundsAtExecution`: portfolioService.debit lanza InsufficientFundsException → orden se actualiza a REJECTED con código INSUFFICIENT_FUNDS, transacción rollback (verificar que verify(orderRepo).save(...) ocurre con status REJECTED — pero el commit final no ocurre porque excepción propaga).
    - `placeOrder_accountSuspended_throws403`: usuario con estado SUSPENDED → lanza AccountNotActiveException.

### Verificación HITO 5

- ☐ **T5.13** `mvn -Dtest='TradingServiceTest' test` verde.
- ☐ **T5.14** `mvn verify` total verde (con tests previos).
- ☐ **T5.15** `docker compose up -d --build backend` + abrir `http://localhost:8080/swagger-ui.html` — los 2 endpoints aparecen con request/response examples completos.
- ☐ **T5.16** **← HITO 5 ✅** lógica core lista + tests unit verdes + Swagger OK.

---

## Lote F — Notification + AuditEventType + 3 templates Thymeleaf

> Objetivo: efectos colaterales post-orden (email + audit) se disparan correctamente y NO bloquean la transacción.

### Eventos de dominio

- ☐ **T6.1** `trading/event/OrderExecutedEvent.java` — record con `orderId`, `userId`, `ticker`, `quantity`, `executionUnitPrice`, `executionTotal`, `commission`, `newBalance`.
- ☐ **T6.2** `trading/event/OrderRejectedEvent.java` — record con `orderId`, `userId`, `ticker`, `quantity`, `reason`, `alpacaReason` (nullable).
- ☐ **T6.3** `trading/event/OrderFailedEvent.java` — record con `orderId`, `userId`, `ticker`, `quantity`, `errorCode`, `errorMessage`.

### Listener post-commit (D15)

- ☐ **T6.4** `trading/event/OrderEventListener.java` — `@Component` con métodos `@TransactionalEventListener(phase=AFTER_COMMIT)`:
    - `onOrderExecuted(OrderExecutedEvent event)`: invoca `notifier.notifyOrderExecuted(...)` (async vía `@Async` ya configurado HU-F02) + `auditor.emit(ORDER_CREATED, ...)` + `auditor.emit(ORDER_EXECUTED, ...)`.
    - `onOrderRejected(OrderRejectedEvent event)`: notifier.notifyOrderRejected (si reason != INSUFFICIENT_FUNDS — D-no-email-on-funds-insufficient SPEC §9.2) + audit `ORDER_REJECTED`.
    - `onOrderFailed(OrderFailedEvent event)`: notifier.notifyOrderFailed + audit `ORDER_FAILED`.

### Extensión Notifier

- ☐ **T6.5** `notification/Notifier.java` MODIFICADO — agregar 3 métodos en la interface (sin prefijo `I` — D1 HU-F01):
    ```java
    void notifyOrderExecuted(UUID userId, OrderExecutedEvent event);
    void notifyOrderRejected(UUID userId, OrderRejectedEvent event);
    void notifyOrderFailed(UUID userId, OrderFailedEvent event);
    ```
- ☐ **T6.6** `notification/MailNotifier.java` MODIFICADO — implementar los 3 métodos. Cada uno: (a) carga el usuario para obtener `nombreCompleto` + `notificationChannel`; (b) si channel != EMAIL, log + skip (canales SMS/WhatsApp post-MVP); (c) renderiza template Thymeleaf con vars; (d) envía vía JavaMailSender; (e) catch + audit `*_EMAIL_FAILED` si SMTP error.

### Templates

- ☐ **T6.7** `backend/src/main/resources/templates/email/order-executed-buy.html` — Thymeleaf inline-CSS. Vars: `{nombreCompleto, ticker, quantity, executionUnitPrice, executionTotal, commission, newBalance, executedAt}`. Mensaje en español. Mismo estilo visual que `welcome-premium.html`.
- ☐ **T6.8** `backend/src/main/resources/templates/email/order-rejected-buy.html` — Vars: `{nombreCompleto, ticker, quantity, reason, alpacaReason}`.
- ☐ **T6.9** `backend/src/main/resources/templates/email/order-failed-buy.html` — Vars: `{nombreCompleto, ticker, quantity, errorMessage}`.

### AuditEventType + dispatch

- ☐ **T6.10** `audit/AuditEventType.java` MODIFICADO — agregar 7 entries:
    ```java
    ORDER_CREATED,
    ORDER_EXECUTED,
    ORDER_REJECTED,
    ORDER_FAILED,
    ORDER_DUPLICATE_REQUEST,
    ORDER_BLOCKED_BY_ACCOUNT_STATUS,
    QUOTE_FAILED,
    MARKET_DATA_RATE_LIMITED,   // D6
    MARKET_DATA_API_ERROR,       // D6
    ORDER_EXECUTED_EMAIL_FAILED,
    ORDER_REJECTED_EMAIL_FAILED,
    ORDER_FAILED_EMAIL_FAILED
    ```
    (10 nuevos en total — F09 emite 7 desde flujo principal, 3 son los `*_EMAIL_FAILED` análogos al patrón HU-F06.)

### Tests del Lote F

- ☐ **T6.11** `OrderEventListenerTest.java` — `@SpringBootTest` con perfil `test`. Cubre:
    - `onOrderExecuted_disparaNotifierYAuditor`: publicar evento en TX abierta → verificar que NO se invocan; commit la TX → verificar que sí se invocan (uno con `Mockito.verify(...)`).
    - `onOrderExecuted_noDispara_siTransaccionRollback`: publicar evento en TX, rollback → verificar que NO se invocan.
- ☐ **T6.12** `MailNotifierOrderTest.java` — opcional, smoke test: invoca método con userId real (perfil test BD) y verifica que se envía email (MailHog o GreenMail).

### Verificación HITO 6

- ☐ **T6.13** `mvn -Dtest='OrderEventListenerTest' test` verde.
- ☐ **T6.14** **← HITO 6 ✅** efectos colaterales async listos.

---

## Lote G — Tests IT con WireMock (Alpaca trading + data)

> Objetivo: cobertura E2E del backend sin dependencia Alpaca real. WireMock estabiliza ambos endpoints.

- ☐ **T7.1** `TradingControllerIT.java` — `@SpringBootTest(webEnvironment=RANDOM_PORT)` con perfil `test`. WireMock arrancado con `@RegisterExtension`. Dos stubs configurados en `@BeforeEach`: trading endpoint + data endpoint. Postgres real (localhost:5433/bloomtrade_test).
    - `@BeforeEach` crea usuario con `userRepository.save(...)` y balance con `balanceInitializer.initialize(user, 10000.00)` (D8).
    - Test 1 `quote_happyPath_returns200`: stub data 200 con quote → request quote → 200 + body correcto.
    - Test 2 `placeOrder_happyPath_executed_persistsOrderDebitsBalanceUpsertsPosition`: stubs ambos endpoints en happy → request placeOrder → 201 + body + assert BD (order EXECUTED, balance decrementado, position creada).
    - Test 3 `placeOrder_idempotency_secondCallReturns200WithSameId`: ejecuta Test 2 happy + segunda request mismo clientOrderId → 200 (no 201) + mismo id. Verifica que Alpaca solo fue llamado 1 vez (WireMock.verify).
    - Test 4 `placeOrder_alpacaDown_returns502_orderFAILED_balanceUntouched`: stub trading 503 × 3 → request → 502 + assert BD (order FAILED + balance original).
    - Test 5 `placeOrder_alpacaRejected_returns422_orderREJECTED_balanceUntouched`: stub trading 200 con `status=rejected, reject_reason="qty exceeds buying power"` → 422 + assert.
    - Test 6 `placeOrder_marketDataDown_returns502`: stub data 503 × 3 → 502.
    - Test 7 `placeOrder_insufficientFunds_returns409_noOrderCreated`: usuario con balance 100 → request order de ~1881 → 409 + assert NO fila en orders.
    - Test 8 `placeOrder_invalidTicker_returns400`: ticker "GME" → 400.
    - Test 9 `placeOrder_suspendedAccount_returns403`: usuario estado=SUSPENDED → 403.
- ☐ **T7.2** `TradingServiceConcurrencyIT.java` — `@SpringBootTest`:
    - Test 1 `idempotency_tenSimultaneousRequestsSameClientId_resultsInOneRow`: lanza 10 `placeOrder` en `CompletableFuture.allOf` con MISMO clientOrderId; assert: `orderRepository.findByClientOrderId(id)` retorna 1, Alpaca trading fue llamado 1 vez, balance decrementado 1 vez.
    - Test 2 `concurrency_twoOrdersOverlapBalance_exactlyOneSucceeds`: usuario con balance 2000.00 (suficiente para UNA orden ~1881.90 pero no dos); lanza 2 `placeOrder` con clientOrderIds distintos en paralelo; assert: exactamente 1 retorna EXECUTED + balance decrementado por esa orden; la otra retorna 409 INSUFFICIENT_FUNDS o falla con CHECK constraint mapeado a 409.

### Verificación HITO 7

- ☐ **T7.3** `mvn verify` completo verde (todos los unit + IT + concurrencia).
- ☐ **T7.4** **← HITO 7 ✅** backend completamente cubierto en pruebas automatizadas.

---

## Lote H — Frontend: TradePage + componentes + hooks + ruta

> Objetivo: usuario humano puede entrar a `/trade`, ver quote, confirmar orden, ver confirmación.

### Types + API

- ☐ **T8.1** `frontend/src/types/api.ts` MODIFICADO — agregar tipos: `OrderSide`, `OrderType`, `OrderStatus`, `QuoteRequest`, `QuoteResponse`, `PlaceOrderRequest`, `OrderResponse` (de SPEC §12.4).
- ☐ **T8.2** `frontend/src/features/trading/api/tradingApi.ts` NUEVO — funciones:
    - `requestQuote(req: QuoteRequest): Promise<QuoteResponse>` — POST a `/api/v1/orders/quote` vía `apiClient`.
    - `placeOrder(req: PlaceOrderRequest): Promise<{ data: OrderResponse, isIdempotent: boolean }>` — POST a `/api/v1/orders`. Detecta 200 vs 201 desde el response status del axios para flag isIdempotent.

### Hooks

- ☐ **T8.3** `frontend/src/features/trading/hooks/useQuote.ts` NUEVO — react-query `useMutation`. Maneja error codes: INVALID_TICKER, INVALID_QUANTITY, MARKET_DATA_UNAVAILABLE, ACCOUNT_NOT_ACTIVE, AUTHENTICATION_REQUIRED.
- ☐ **T8.4** `frontend/src/features/trading/hooks/useSubmitOrder.ts` NUEVO — react-query `useMutation`. Genera `clientOrderId = crypto.randomUUID()` internamente. Maneja error codes: INSUFFICIENT_FUNDS, ALPACA_ORDER_REJECTED, ALPACA_API_ERROR, MARKET_DATA_UNAVAILABLE, AccountNotActive, etc.

### Componentes

- ☐ **T8.5** `frontend/src/features/trading/components/TickerDropdown.tsx` NUEVO — dropdown agrupado por mercado, alimentado de `frontend/src/constants/tickers.ts` (existente — HU-F04). Markets: NYSE, NASDAQ, LSE, TSE, ASX.
- ☐ **T8.6** `frontend/src/features/trading/components/OrderForm.tsx` NUEVO — RHF + zod. Schema:
    ```typescript
    const orderFormSchema = z.object({
      ticker: z.string().refine(t => ALLOWED_TICKERS.includes(t)),
      side: z.enum(["BUY", "SELL"]),
      quantity: z.number().int().min(1).max(10000),
    });
    ```
    - SELL deshabilitado con tooltip.
    - Botón "Obtener quote" → invoca `useQuote.mutate(...)`.
- ☐ **T8.7** `frontend/src/features/trading/components/OrderQuotePanel.tsx` NUEVO — recibe `QuoteResponse` por prop. Renderiza tabla con precio/comisión/total/saldo después. Botón "Confirmar compra" deshabilitado si !sufficientFunds || !marketOpen. Al click → `useSubmitOrder.mutate(...)`.
- ☐ **T8.8** `frontend/src/features/trading/components/OrderConfirmationToast.tsx` NUEVO — toast (puede ser via `react-hot-toast` si ya está, o un simple `<div>` con animación CSS).
- ☐ **T8.9** `frontend/src/pages/TradePage.tsx` NUEVO — orquesta los 3 componentes. State machine simple: `IDLE -> QUOTE_LOADING -> QUOTE_SHOWN -> ORDER_SUBMITTING -> SUCCESS|ERROR`.

### Routing + i18n

- ☐ **T8.10** `frontend/src/App.tsx` MODIFICADO — agregar `<Route path="/trade" element={<RequireAuth><TradePage /></RequireAuth>} />`.
- ☐ **T8.11** `frontend/src/components/AppHeader.tsx` MODIFICADO — agregar link "Operar" → `/trade` (entre "Premium" y "Perfil" o donde tenga sentido).
- ☐ **T8.12** `frontend/src/i18n/messages.es.ts` MODIFICADO — agregar 10 códigos de SPEC §12.6:
    ```typescript
    INVALID_TICKER: "Este ticker no está habilitado para operar.",
    INVALID_QUANTITY: "La cantidad debe ser un entero positivo entre 1 y 10000.",
    INVALID_SIDE: "Operación inválida.",
    SIDE_NOT_YET_IMPLEMENTED: "La venta estará disponible próximamente.",
    INVALID_CLIENT_ORDER_ID: "Error técnico al generar la orden. Recarga la página.",
    INSUFFICIENT_FUNDS: "Saldo insuficiente. Tu saldo: USD {balance}, requerido: USD {required}.",
    ALPACA_API_ERROR: "El mercado no respondió. Tu saldo está intacto. Intenta de nuevo.",
    ALPACA_ORDER_REJECTED: "El mercado rechazó tu orden: {alpacaReason}.",
    MARKET_DATA_UNAVAILABLE: "No se pudo obtener el precio actual. Intenta de nuevo.",
    ACCOUNT_NOT_ACTIVE: "Tu cuenta no está activa. Contacta soporte.",
    ```

### Tests frontend

- ☐ **T8.13** Saltados [[feedback-coverage-vs-velocidad]] — mismo criterio que HU-F04/F06 Lote G. Se valida en HITO 8 humano.

### Verificación HITO 8

- ☐ **T8.14** `npm run build` verde (frontend).
- ☐ **T8.15** Demo manual del humano: `docker compose up -d --build`, login con usuario test, navegar `/trade`, seleccionar AAPL × 10, "Obtener quote", ver panel con precio + comisión + total, "Confirmar compra", ver toast éxito, abrir MailHog (`localhost:8025`) → ver email "Tu orden de compra...", abrir Kibana (`localhost:5601`) → ver eventos ORDER_CREATED + ORDER_EXECUTED.
- ☐ **T8.16** Smoke con `psql`:
    ```sql
    SELECT id, ticker, status, execution_unit_price, execution_total FROM app.orders;
    SELECT balance FROM app.user_balances WHERE user_id = '...';
    SELECT ticker, quantity, avg_buy_price FROM app.positions WHERE user_id = '...';
    ```
- ☐ **T8.17** **← HITO 8 ✅** frontend funcional + E2E manual confirmado.

---

## Lote I — Cierre: SPEC v1.1 + STACK.md + APRENDIZAJES + handoff

> Objetivo: documentación final actualizada. Commit listo para firma humana.

- ☐ **T9.1** `STACK.md` §7.2 (Polygon) MODIFICADO — marcar Polygon como diferido a post-MVP. Agregar entrada al historial al final con referencia a D9 D-MD-PROVIDER.
- ☐ **T9.2** `ARCHITECTURE.md` §4 IntegrationService MODIFICADO — `MarketDataAdapter` ya dice "Polygon.io (o Alpha Vantage como alterno)"; cambiar a "Alpaca Data (o Polygon.io como alterno)". Agregar entrada al historial.
- ☐ **T9.3** `specs/HU-F09-orden-compra-market/SPEC.md` MODIFICADO — bump a v1.1:
    - §1 Metadatos: `Versión spec` = `1.1`, `Última actualización` = `2026-05-XX` (día real de cierre).
    - §3 Variables de entorno: drop `POLYGON_API_KEY`, `POLYGON_BASE_URL`; agregar `ALPACA_DATA_BASE_URL`.
    - §5.1 paso 7 y §5.3.8: reemplazar "Polygon" → "Alpaca Data API".
    - §6.1.1 line ~447, §6.1.2 line ~571: actualizar descripciones.
    - §8.1: `MarketDataAdapter` ahora en `integration/alpaca/`, no `integration/polygon/`.
    - §8.4 retry config: `@Retry(name="alpacaDataApi")` no `polygonApi`.
    - §9.1/§9.2/§9.3/§9.4: actualizar referencias.
    - §10.2, §11, §12.1: actualizar referencias.
    - §13: agregar Polygon como deferido.
    - §14: marcar D-MD-CACHE, D-POLYGON-FREE-LIMITS, D-ALPACA-CLIENT, etc. como resueltas en plan.md.
    - §15 DoD: ajustar lista de env vars.
    - Changelog: agregar fila v1.1 con razón.
- ☐ **T9.4** `APRENDIZAJES.md` MODIFICADO — agregar sección "Día 6 — HU-F09 Compra Market" con ~8-10 reflexiones técnicas. Áreas potenciales: BigDecimal traps, pivote Polygon→Alpaca, lock pessimistic vs optimistic, @TransactionalEventListener vs commit chains, IntegrationConfig + RestClient pattern, idempotencia doble (BD + Alpaca), HALF_UP en HALF_UP rounded subtotals.
- ☐ **T9.5** `AGENTS.md` MODIFICADO — actualizar sección "Trabajo activo":
    - Branch: `main` (HU-F09 mergeada — PR #X, fecha)
    - HU activa: Ninguna (próximo: HU-F10 Día 7)
    - Sprint: 2 en curso, HU-F09 cerrada, próximo Día 7
    - Próximas opciones: HU-F10 Venta Market (reutiliza andamio F09)
    - Deuda viva: extender con (1) Polygon como alterno de market data, (2) reconciliation Alpaca-BD, (3) outbox pattern, (4) PriorityQueue+ThreadPool, (5) slippage tolerance.
    - Sección "Cómo continuar": handoff completo Sprint 2 día 7.
- ☐ **T9.6** Preparar mensaje de commit en `C:\Users\juang\AppData\Local\Temp\bt-hu-f09.txt` [[feedback-commit-file-ruta-completa]]:
    ```
    feat(trading): cierra HU-F09 — orden de compra Market con Alpaca paper trading

    Implementa el ciclo completo de compra Market sobre los 25 activos del MVP:
    quote previo (con precio + comisión + total + estado de fondos), confirmación
    transaccional con lock pessimistic, integración Alpaca (trading + data API),
    notificación email + auditoría async post-commit.

    Materializa 4 módulos nuevos del catálogo ARCH §3 (Trading, Portfolio.positions,
    Admin, Integration.Alpaca). El SPEC bump a v1.1 consolida la decisión D9
    (D-MD-PROVIDER: Alpaca-only para market data, drop Polygon del MVP).

    refs HU-F09 specs/HU-F09-orden-compra-market/SPEC.md

    Co-authored-by: Claude <noreply@anthropic.com>
    ```

### Verificación HITO 9

- ☐ **T9.7** `mvn verify` final verde en la branch.
- ☐ **T9.8** `npm run build` final verde.
- ☐ **T9.9** Documentos maestros consistentes con el código (revisión manual rápida).
- ☐ **T9.10** **← HITO 9 ✅** todo listo para `git add` + `git commit -F %TEMP%\bt-hu-f09.txt` + push + PR del humano.

---

## Resumen de archivos a crear/modificar

**Backend nuevos (~30 archivos Java):**
- `trading/`: 4 domain + 4 dto + 1 mapper + 8 exceptions + 1 repository + 3 events + 1 listener + 1 controller + 2 services (TradingService, OrderOrchestrator si lo splitamos; en plan está como uno solo) = ~25
- `portfolio/`: 1 domain (Position) + 1 repository (PositionRepository) + 1 service (PortfolioService) = 3
- `admin/`: 1 domain (CommissionRate) + 1 repository + 3 services = 5
- `integration/alpaca/`: 1 config + 2 adapters + 3 DTOs + 3 exceptions = 9
- 1 migración SQL
- 3 templates Thymeleaf

**Backend modificados:**
- `pom.xml` (no realmente; verificar resilience4j ya)
- `application.yml` (retry configs + alpaca block + trading block)
- `docker-compose.yml` (vars Alpaca al servicio backend)
- `.env.example`
- `shared/web/GlobalExceptionHandler.java` (+8 handlers)
- `shared/web/validation-messages.properties` (+10 códigos)
- `notification/Notifier.java` + `MailNotifier.java` (+3 métodos)
- `audit/AuditEventType.java` (+10 entries)
- `portfolio/repository/UserBalanceRepository.java` (+findByUserIdForUpdate)
- `STACK.md` §7.2 + historial
- `ARCHITECTURE.md` §4 + historial

**Frontend nuevos (~10 archivos):**
- 1 page (TradePage)
- 4 componentes (OrderForm, TickerDropdown, OrderQuotePanel, OrderConfirmationToast)
- 2 hooks (useQuote, useSubmitOrder)
- 1 api (tradingApi.ts)
- 1 util (formatBigDecimal.ts opcional)

**Frontend modificados:**
- `types/api.ts` (+7 tipos)
- `App.tsx` (+ruta)
- `AppHeader.tsx` (+link)
- `messages.es.ts` (+10 códigos)

**Total estimado**: ~50 archivos nuevos + ~15 modificados. Mismo orden de magnitud que HU-F06.

---

## Notas finales para la sesión de implementación

1. **Antes de Lote A**: el humano debe tener creds Alpaca paper (5 min de setup). Sin eso el HITO 5 no se puede validar end-to-end (pero los Lotes A–D y los unit tests con MockRestServiceServer no requieren creds reales).
2. **Tests con perfil `test`**: respetar D16 HU-F01 — Postgres en `localhost:5433/bloomtrade_test`, NO Testcontainers.
3. **Cadencia de validación**: hacer commit-prep en cada HITO (1, 4, 5, 8). El commit firma final del humano va en HITO 9.
4. **Skip de tests frontend**: documentado como deuda análoga a HU-F04/F06 (P1).

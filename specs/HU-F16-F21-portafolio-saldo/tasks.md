# tasks.md — Bundle HU-F16 + HU-F21

> Descomposición granular del `plan.md` v1.0 en tareas verificables. Cada tarea cierra cuando el código compila, el test específico pasa o el archivo cumple su contrato. Cadencia: validación en HITOs (final de lote), NO tras cada tarea — [[feedback-cadencia-sdd]].

---

## Lote A — Backend foundation → HITO 1

### A.1 DTOs (sin lógica, solo records/classes)

- ☐ **T1.1** Crear `portfolio/dto/BalanceResponse.java` como `record BalanceResponse(String balance, String currency, Instant lastUpdatedAt)`. Anotar con `@Schema(...)` Swagger (description: "Saldo del usuario autenticado").
- ☐ **T1.2** Crear `portfolio/dto/PendingOrderDto.java` como `record PendingOrderDto(UUID orderId, UUID clientOrderId, String ticker, OrderSide side, int quantity, Instant submittedAt, String quotedTotal)`. Importar `OrderSide` desde `trading.domain`. Anotar `@Schema`.
- ☐ **T1.3** Crear `portfolio/dto/PositionDto.java` como `record PositionDto(String ticker, int quantity, String avgCost, String costBasis, String currency, String currentPrice, String marketValue, String unrealizedPnL, String unrealizedPnLPct)`. Todos los campos numéricos son `String` (BigDecimal stringificado, D10). Anotar `@Schema` mencionando que los 4 últimos pueden ser `null`.
- ☐ **T1.4** Crear `portfolio/dto/PortfolioPositionsResponse.java` como `record PortfolioPositionsResponse(List<PositionDto> positions, List<PendingOrderDto> pendingOrders, String marketDataAvailable, Instant fetchedAt)`. `marketDataAvailable` es `String` con valores válidos `"true"|"false"|"partial"`. Documentar enum en `@Schema`.

### A.2 Repository derived queries

- ☐ **T1.5** Modificar `trading/repository/OrderRepository.java`: agregar `List<Order> findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(UUID userId, OrderStatus status);`. Verificar que Spring Data resuelve el método sin custom `@Query`.
- ☐ **T1.6** Modificar `portfolio/repository/PositionRepository.java`: agregar `List<Position> findByUserIdAndQuantityGreaterThan(UUID userId, Integer minQuantity);`. Verificar resolución automática.

### A.3 PortfolioService extensions

- ☐ **T1.7** Modificar constructor de `PortfolioService` para inyectar `OrderRepository`. Actualizar callers de tests si hace falta (PortfolioServiceTest construye el service directo).
- ☐ **T1.8** Agregar `@Transactional(readOnly = true) public List<Order> getPendingOrders(UUID userId)` que llama `orderRepository.findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(userId, OrderStatus.PENDING)`. Javadoc explicando que retorna SOLO órdenes encoladas en Alpaca (no las intermedias).
- ☐ **T1.9** Agregar `@Transactional(readOnly = true) public UserBalance getBalanceEntity(UUID userId)` que llama `userBalanceRepository.findById(userId).orElseThrow(...)`. Javadoc: "Devuelve la entidad completa para acceder a updatedAt/currency. Usar getBalance(userId) si solo se necesita el monto."
- ☐ **T1.10** Modificar `getPositions(UUID userId)` para llamar `positionRepository.findByUserIdAndQuantityGreaterThan(userId, 0)` en lugar de `findByUserId(userId)`. Actualizar javadoc explicando filtro defensivo (D12).

### A.4 Tests unit Lote A

- ☐ **T1.11** Actualizar `PortfolioServiceTest`: agregar mock de `OrderRepository` al setup. Verificar que tests existentes siguen verdes.
- ☐ **T1.12** Agregar test `getPositions_excludesZeroQuantityPositions()`: persistir 2 positions (qty=5, qty=0 forzado por reflection o save directo) → `getPositions` retorna 1.
- ☐ **T1.13** Agregar 4 tests para `getPendingOrders`:
  - `returnsEmptyListWhenNoOrders()`.
  - `returnsOnlyPendingWithAlpacaOrderId()` — verifica filtro status=PENDING + alpacaOrderId not null.
  - `excludesExecutedOrders()` — orden EXECUTED no aparece.
  - `excludesPendingWithoutAlpacaOrderId()` — orden PENDING pero alpacaOrderId=null no aparece.
- ☐ **T1.14** Agregar 2 tests para `getBalanceEntity`:
  - `returnsEntityWhenExists()` — verifica balance y updatedAt presentes.
  - `throwsIllegalStateExceptionWhenNotFound()`.

### A.5 Validación HITO 1

- ☐ **H1** Ejecutar `cd backend && ./mvnw compile`. Esperado: BUILD SUCCESS.
- ☐ **H1** Ejecutar `./mvnw test -Dtest=PortfolioServiceTest`. Esperado: todos verdes (los previos + 7 nuevos).
- ☐ **H1** No avanzar a Lote B si H1 no está verde.

---

## Lote B — Orchestration market data → HITO 2

### B.1 Configuración

- ☐ **T2.1** Crear `portfolio/config/PortfolioConfig.java` como `@Configuration`. Agregar `@Bean(destroyMethod = "shutdown") public ExecutorService marketDataExecutor()` que retorna `Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "market-data-fanout"); t.setDaemon(true); return t; })`. Daemon=true para que no bloquee shutdown si destroyMethod falla.

### B.2 Orchestrator

- ☐ **T2.2** Crear `portfolio/service/MarketDataOrchestrator.java` como `@Component`. Inyectar `MarketDataAdapter` + `@Qualifier("marketDataExecutor") ExecutorService`. Método público:
  ```java
  public Map<String, BigDecimal> fetchPrices(Collection<String> tickers)
  ```
  Implementación:
  - Si `tickers.isEmpty()` → return `Map.of()`.
  - Stream sobre tickers → `CompletableFuture.supplyAsync(() -> adapter.getLatestPrice(ticker), executor).completeOnTimeout(null, 1500, TimeUnit.MILLISECONDS).exceptionally(t -> { log.warn(...); return null; })`.
  - Collect a `Map<String, CompletableFuture<BigDecimal>>`.
  - `CompletableFuture.allOf(...).join()` para esperar todos (cada uno con su cap individual).
  - Mapear a `Map<String, BigDecimal>` con `.get()` (todos completados, sin block).
  - Loguear INFO con count de éxitos/fallos.

### B.3 Tests Lote B

- ☐ **T2.3** Crear `MarketDataOrchestratorTest.java` con `@ExtendWith(MockitoExtension.class)`. Mock `MarketDataAdapter`. Para el executor, usar `Executors.newFixedThreadPool(2)` real (la concurrencia es parte del test). Cleanup en `@AfterEach`.
- ☐ **T2.4** Test `fetchPrices_emptyInput_returnsEmptyMap()`.
- ☐ **T2.5** Test `fetchPrices_allSuccess_returnsAllPrices()` — 3 tickers, mock retorna BigDecimals distintos.
- ☐ **T2.6** Test `fetchPrices_oneException_returnsNullForThatTicker()` — 1 de 3 tickers el mock lanza `MarketDataUnavailableException`.
- ☐ **T2.7** Test `fetchPrices_oneTimeout_returnsNullForThatTicker()` — mock con `Thread.sleep(2000)` para 1 ticker. Verificar que el test no toma más de ~2s total (timeout funciona).
- ☐ **T2.8** Test `fetchPrices_allFail_returnsAllNulls()` — 3 tickers, todos lanzan excepción.

### B.4 Validación HITO 2

- ☐ **H2** Ejecutar `./mvnw test -Dtest=MarketDataOrchestratorTest`. Esperado: 6 verdes.
- ☐ **H2** Verificar log del test: WARN por cada ticker fallido con razón clara (timeout vs exception).

---

## Lote C — Controller + IT → HITO 3

### C.1 Mapper

- ☐ **T3.1** Crear `portfolio/web/PortfolioMapper.java` como `@Component`. Métodos:
  - `BalanceResponse toBalanceResponse(UserBalance entity)`: stringifica balance con `setScale(2, HALF_UP).toPlainString()`. Currency desde entity. `lastUpdatedAt` desde `getUpdatedAt()` con fallback a `getCreatedAt()` si null (defensa).
  - `PendingOrderDto toPendingOrderDto(Order order)`: mapeo directo de campos. `quotedTotal` desde `order.getQuotedTotal()` stringificado scale=2 o null si no existe.
  - `PositionDto toPositionDto(Position p, BigDecimal currentPriceOrNull)`:
    - `avgCost = p.getAvgBuyPrice().setScale(2, HALF_UP).toPlainString()`.
    - `costBasis = p.getAvgBuyPrice().multiply(BigDecimal.valueOf(p.getQuantity())).setScale(2, HALF_UP).toPlainString()`.
    - Si `currentPriceOrNull != null`:
      - `currentPrice = currentPriceOrNull.setScale(2, HALF_UP).toPlainString()`.
      - `marketValue = currentPriceOrNull.multiply(BigDecimal.valueOf(p.getQuantity())).setScale(2, HALF_UP).toPlainString()`.
      - `unrealizedPnL = (marketValue - costBasis).setScale(2, HALF_UP).toPlainString()` (signed).
      - `unrealizedPnLPct = ((pnl / costBasis) * 100).setScale(2, HALF_UP).toPlainString()` (signed).
    - Si null: 4 campos finales = null.
    - `currency` = "USD" hardcoded.
  - `PortfolioPositionsResponse toPositionsResponse(List<Position> positions, Map<String, BigDecimal> prices, List<Order> pendingOrders)`:
    - Map positions a PositionDto usando `prices.get(p.getTicker())`.
    - Map pendingOrders a PendingOrderDto.
    - Calcular `marketDataAvailable`:
      - `positions.isEmpty()` → "true".
      - Todos con price NO null → "true".
      - Todos con price null → "false".
      - Mezcla → "partial".
    - `fetchedAt = Instant.now()`.

### C.2 Controller

- ☐ **T3.2** Crear `portfolio/web/PortfolioController.java` como `@RestController @RequestMapping("/api/v1/portfolio")`. Inyectar `PortfolioService`, `MarketDataOrchestrator`, `PortfolioMapper`. Métodos:
  - `@GetMapping("/balance") public BalanceResponse getBalance(@AuthenticationPrincipal User user)`:
    - `UserBalance entity = portfolioService.getBalanceEntity(user.getId())`.
    - `return mapper.toBalanceResponse(entity)`.
    - Log INFO con userId + balance + latency.
  - `@GetMapping("/positions") public PortfolioPositionsResponse getPositions(@AuthenticationPrincipal User user)`:
    - `List<Position> positions = portfolioService.getPositions(user.getId())`.
    - `List<Order> pending = portfolioService.getPendingOrders(user.getId())`.
    - `Set<String> tickers = positions.stream().map(Position::getTicker).collect(toSet())`.
    - `Map<String, BigDecimal> prices = orchestrator.fetchPrices(tickers)`.
    - `return mapper.toPositionsResponse(positions, prices, pending)`.
    - Log INFO con userId + count positions + count nulls + latency.
  - Anotar ambos con `@Operation`/`@ApiResponse` Swagger.

### C.3 Tests mapper Lote C

- ☐ **T3.3** Crear `PortfolioMapperTest.java`.
- ☐ **T3.4** Test `toPositionDto_withCurrentPrice_calculatesAllFields()`: position(qty=10, avgBuy=189.45), price=193.20 → DTO con currentPrice="193.20", marketValue="1932.00", unrealizedPnL="37.50", unrealizedPnLPct="1.98".
- ☐ **T3.5** Test `toPositionDto_withNullPrice_nullsMarketFields()`.
- ☐ **T3.6** Test `toBalanceResponse_happy()` + `toBalanceResponse_fallsBackToCreatedAtWhenUpdatedAtNull()`.
- ☐ **T3.7** Test `toPositionsResponse_calculatesMarketDataAvailable()` — parametrizado o 4 tests separados para los 4 casos (all OK, all null, partial, empty).

### C.4 Tests IT Lote C

- ☐ **T3.8** Crear `integration/portfolio/PortfolioControllerIT.java` con setup MockMvc + WireMock stub Alpaca data API en puerto fijo (heredar config de `TradingControllerIT` F09). Setup helper: `seedUser(...)`, `seedPosition(userId, ticker, qty, avgPrice)`, `seedPendingOrder(userId, ticker, side, qty, alpacaId)`, `stubAlpacaQuote(ticker, bid, ask)`.
- ☐ **T3.9** Test AC-01: 2 posiciones, Alpaca responde 2 quotes → 200 OK con marketDataAvailable="true" + valores correctos.
- ☐ **T3.10** Test AC-02: Alpaca devuelve 503 a todo → marketDataAvailable="false" + currentPrice null.
- ☐ **T3.11** Test AC-03: 3 posiciones, 1 ticker da 404 → marketDataAvailable="partial".
- ☐ **T3.12** Test AC-04: usuario sin posiciones → 200 OK con arrays vacíos + WireMock verify NO calls a `/quotes/latest`.
- ☐ **T3.13** Test AC-05: 0 posiciones + 1 pending order (BUY 5 TSLA con alpacaOrderId) → pendingOrders[0] poblado correctamente.
- ☐ **T3.14** Test AC-07: sin JWT → 401.
- ☐ **T3.15** Test AC-21-01 + AC-21-04: GET /balance happy + 401 sin JWT.

### C.5 Validación HITO 3

- ☐ **H3** Ejecutar `./mvnw verify -Dtest=PortfolioControllerIT,PortfolioMapperTest`. Esperado: todos verdes.
- ☐ **H3** Abrir Swagger UI (`http://localhost:8080/swagger-ui/index.html` con backend levantado) y verificar que los 2 endpoints aparecen con ejemplos.

---

## Lote D — Frontend → HITO 4

### D.1 Types + API

- ☐ **T4.1** Modificar `frontend/src/types/api.ts`: agregar interfaces `BalanceResponse`, `PendingOrderDto`, `PositionDto`, `PortfolioPositionsResponse`. Mirror exact de DTOs backend (todos los numéricos `string` para BigDecimal).
- ☐ **T4.2** Crear `frontend/src/features/portfolio/api/portfolioApi.ts` con `getBalance()` y `getPositions()` que llaman al axios instance configurado (interceptor 401 → /login).

### D.2 Hooks

- ☐ **T4.3** Crear `frontend/src/features/portfolio/hooks/useBalance.ts`: `useQuery({ queryKey: ['portfolio', 'balance'], queryFn: getBalance, staleTime: 30_000, refetchOnWindowFocus: true })`.
- ☐ **T4.4** Crear `frontend/src/features/portfolio/hooks/usePortfolioPositions.ts`: análogo pero para positions.

### D.3 Componentes

- ☐ **T4.5** Crear `frontend/src/features/portfolio/components/MarketDataBanner.tsx`: props `{ status: 'true'|'false'|'partial' }`. Si `'true'` → render nothing. Si `'partial'` → banner naranja con icono `AlertTriangle` + texto messages.es. Si `'false'` → banner amarillo + texto distinto.
- ☐ **T4.6** Crear `frontend/src/features/portfolio/components/BalanceCard.tsx`: props `{ data: BalanceResponse, onRefresh: () => void }`. Render: título "Saldo disponible", monto formateado con `Intl.NumberFormat("es-CO", { style: "currency", currency: "USD" })`, footer "Actualizado: hace Xs" (relative time desde data.lastUpdatedAt), botón `↻` (`RefreshCw` de lucide-react) con aria-label de messages.es.
- ☐ **T4.7** Crear `frontend/src/features/portfolio/components/PositionsTable.tsx`: props `{ positions: PositionDto[] }`. Tabla con columnas: Ticker / Cant / Avg Cost / Actual / Valor / P&L%. P&L con color según D7 + icono `TrendingUp`/`TrendingDown` de lucide-react. Si currentPrice null → mostrar "—". Empty state inline si `positions.length === 0` con CTA a `/trade`.
- ☐ **T4.8** Crear `frontend/src/features/portfolio/components/PendingOrdersPanel.tsx`: props `{ orders: PendingOrderDto[] }`. Si `orders.length === 0` → null. Else `<details open>` con summary "Órdenes en cola (n)" + lista de orders con badge naranja. Solo BUY o SELL distingue con color o icono.
- ☐ **T4.9** Crear `frontend/src/features/portfolio/PortfolioPage.tsx`: usa hooks, dispara ambos en paralelo (React Query lo hace por default). Layout: container max-w-4xl + space-y-6 stack de BalanceCard + MarketDataBanner + PositionsTable + PendingOrdersPanel. Manejo de loading (spinner) y error global (`<ErrorBanner>` reutilizado si existe, sino div simple).

### D.4 Wiring

- ☐ **T4.10** Modificar `frontend/src/App.tsx`: agregar ruta `<Route path="/portfolio" element={<ProtectedRoute><PortfolioPage /></ProtectedRoute>} />`.
- ☐ **T4.11** Modificar `frontend/src/components/AppHeader.tsx`: link "Portafolio" entre "Trade" y "Premium". Visible solo si autenticado (consistente con otros links).
- ☐ **T4.12** Modificar `frontend/src/lib/messages.es.ts`: agregar todas las claves `portfolio.*` listadas en SPEC §12.2.

### D.5 Validación HITO 4

- ☐ **H4** Ejecutar `cd frontend && npm run build`. Esperado: BUILD SUCCESS sin warnings nuevos.
- ☐ **H4** `docker compose up -d backend frontend` (con backend del Lote C ya commited mentalmente). Login → /portfolio → ver render integral. Smoke visual humano confirmado (no E2E completo en este HITO).

---

## Lote E — Tests adicionales → HITO 5

- ☐ **T5.1** Agregar test `PortfolioControllerIT#getPositions_isolatedPerUser_userBSeenByA()`: crear users A y B, A tiene 2 posiciones, B tiene 1. A llama /positions con su JWT → ve solo sus 2. WireMock verify que ALPACA solo se llama para los tickers de A.
- ☐ **T5.2** Agregar test `PortfolioControllerIT#getBalance_isolatedPerUser()`: A tiene saldo 5000, B tiene 8000. A llama /balance → ve 5000.
- ☐ **T5.3** Agregar test `PortfolioControllerIT#getBalance_reflectsUpdatedAtAfterCredit()`: bootstrap usuario, capturar updatedAt inicial, simular credit directo via PortfolioService.credit (helper de setup, no HTTP), llamar GET /balance, verificar que `lastUpdatedAt > initial` y balance es el actualizado.
- ☐ **T5.4** Agregar test `PortfolioControllerIT#getPositions_excludesQuantityZeroDefense()`: insertar manualmente (via repository en setup) una Position con quantity=0 — debería ser imposible por flujo normal pero defendemos vs bug futuro. GET /positions NO la incluye.

### E.1 Validación HITO 5

- ☐ **H5** Ejecutar `./mvnw verify` (suite completa, ~5-7 min). Esperado: todos los tests verdes (los previos + ~30 nuevos del bundle).
- ☐ **H5** Verificar cobertura del módulo portfolio en el reporte JaCoCo (`target/site/jacoco/index.html`). Aceptable: ≥75% por D-AUDIT-EVENTS-COVERAGE de F09. NO bloqueante por [[feedback-coverage-vs-velocidad]] — si queda bajo, documentar como deuda.

---

## Lote F — Cierre → HITO 6

- ☐ **T6.1** Agregar sección "Día 8 — HU-F16+F21 (Portafolio y Saldo)" a `APRENDIZAJES.md` en primera persona. Posibles temas:
  - Bundle vs split de HUs (cuándo conviene).
  - Mark-to-market con fallback elegante: trade-off UX/complejidad.
  - CompletableFuture + completeOnTimeout: patrón limpio para fan-out con SLA estricto.
  - Por qué un ExecutorService dedicado y no ForkJoinPool.commonPool.
  - Reflexión meta sobre SDD: el SPEC pequeño no significa plan trivial (16 decisiones D1–D16 igual).
  - Mitigación de deuda viva #8/#12 vía pendingOrders[]: cómo una sección frontend ataca una deuda de backend.
- ☐ **T6.2** Actualizar `AGENTS.md`:
  - Bloque "Trabajo activo": branch, HU activa, sprint, próximo paso.
  - Mover "Cómo continuar (post HU-F10 → HU-F16+F21)" a sección histórica.
  - Nueva sección "Cómo continuar (post HU-F16+F21 → HU-F18 Día 9)" con prerequisitos, decisiones cerradas, alcance estimado.
  - Sumar a "Deuda viva" si emergió alguna durante implementación.
- ☐ **T6.3** Si hubo decisiones emergentes durante implementación, completar `plan.md` §2.4 con D17+ siguiendo patrón F09/F10.
- ☐ **T6.4** Preparar mensaje de commit en `C:\Users\juang\AppData\Local\Temp\bt-hu-f16-f21.txt` (ruta completa literal P6, NO `$env:TEMP\...`). Formato Conventional Commits:
  ```
  feat(portfolio): cierra HU-F16+F21 — consultar portafolio y saldo

  - 2 endpoints REST: GET /api/v1/portfolio/positions (mark-to-market) y /balance.
  - Fan-out paralelo a Alpaca data API con timeout 1.5s por ticker + degradación elegante.
  - Sección pendingOrders[] mitiga deuda viva #8 y #12 (visibilidad de órdenes encoladas).
  - Frontend /portfolio: BalanceCard + PositionsTable con P&L color-coded + PendingOrdersPanel.
  - 30+ tests nuevos (unit Service + Mapper + Orchestrator, IT Controller con WireMock).
  - Sin migración Flyway nueva, sin dependencias nuevas, sin audit events.

  refs HU-F16 HU-F21
  spec specs/HU-F16-F21-portafolio-saldo/SPEC.md

  Co-authored-by: Claude <noreply@anthropic.com>
  ```
- ☐ **T6.5 (humano)** `git add -A && git status` (verificar staging) → `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f16-f21.txt`.
- ☐ **T6.6 (humano)** `git push -u origin feat/HU-F16-F21-portafolio-saldo` → `gh pr create ...` → Squash and merge.

---

## Resumen de tareas

| Lote | Tasks | Tests nuevos | HITO |
|---|---|---|---|
| A | 14 (T1.1–T1.14) | 7 unit | H1: compile + unit verdes |
| B | 8 (T2.1–T2.8) | 6 unit | H2: orchestrator tests verdes |
| C | 15 (T3.1–T3.15) | 5 mapper + 8 IT | H3: IT cumple AC del SPEC |
| D | 12 (T4.1–T4.12) | 0 (smoke humano) | H4: build verde + render visual |
| E | 4 (T5.1–T5.4) | 4 IT | H5: mvn verify completo verde |
| F | 4 (T6.1–T6.4) + 2 humano | — | H6: commit + push + PR |
| **Total** | **57 tareas + 2 humano** | **~30 nuevos** | **6 HITOs** |

---

## Notas de ejecución (operacional)

- **Cadencia**: validación SOLO en final de lote (HITO). No micro-checkpoint tras cada task. Si algo bloquea dentro de un lote, parar y diagnosticar antes de seguir acumulando.
- **PowerShell**: comandos PS para Windows. `;` o `if ($?) { ... }` para chaining, nunca `&&`.
- **Maven Wrapper**: siempre `./mvnw` (o `mvnw.cmd` en PS), nunca `mvn` global.
- **Docker**: levantar `docker compose up -d` antes de H3 (para Postgres bloomtrade_test) y H4 (para smoke visual). Idealmente queda corriendo desde el inicio del Día 8.
- **WireMock**: la config base ya está en `TradingControllerIT` F09 — copiar el setup `@TestConfiguration` con `WireMockExtension` o `@SpringBootTest` properties.
- **Decisiones emergentes**: cualquier desvío del plan se documenta inmediatamente en `plan.md` §2.4 como D17, D18, ... con patrón F09 D23–D29 (Síntoma → Causa raíz → Decisión → Trade-off).
- **Smoke E2E manual** (post-merge): el usuario lo hará a su ritmo, NO bloquea el merge según indicación 2026-05-24 ("Los smoke tests los tendré que hacer después").

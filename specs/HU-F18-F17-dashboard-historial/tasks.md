# tasks.md — Bundle HU-F18 + HU-F17

> Descomposición granular del `plan.md` v1.0 en tareas verificables. Cada tarea cierra cuando el código compila, el test específico pasa o el archivo cumple su contrato. Cadencia: validación en HITOs (final de lote), NO tras cada tarea — [[feedback-cadencia-sdd]].

---

## Lote A — Cache Redis + Market Data Bars extensions → HITO 1

### A.1 Redis configuration

- ☐ **T1.1** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/cache/RedisConfig.java` como `@Configuration`. `@Bean public RedisTemplate<String, String> stringRedisTemplate(RedisConnectionFactory cf)`. Setear `StringRedisSerializer` para key y value. Spring Boot auto-config maneja el `ConnectionFactory` desde `spring.data.redis.host/port` env vars.
- ☐ **T1.2** Verificar en `application.yml` (o `application-dev.yml`) que `spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}` y `spring.data.redis.port=${SPRING_DATA_REDIS_PORT:6379}` están seteados con env vars + defaults. Si no, agregar.
- ☐ **T1.3** Verificar en `application-test.yml`: agregar `spring.data.redis.host=localhost` y `spring.data.redis.port=6379` (puerto del Redis del docker-compose). Si test profile no debe asumir Redis disponible, registrar como deuda y proceder.

### A.2 Alpaca bars DTOs

- ☐ **T1.4** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/dto/AlpacaBar.java` como `record AlpacaBar(@JsonProperty("t") Instant timestamp, @JsonProperty("o") BigDecimal open, @JsonProperty("h") BigDecimal high, @JsonProperty("l") BigDecimal low, @JsonProperty("c") BigDecimal close, @JsonProperty("v") Long volume)`. Fields según Alpaca v2 bars response.
- ☐ **T1.5** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/dto/AlpacaBarsResponse.java` como `record AlpacaBarsResponse(@JsonProperty("bars") Map<String, List<AlpacaBar>> bars, @JsonProperty("next_page_token") String nextPageToken)`. La key del map es el ticker; ignoramos paginación en V1 (limit=50 cubre el día).
- ☐ **T1.6** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/IntradayBar.java` (en módulo dashboard, no integration — es DTO interno consumido por DashboardService/Mapper) como `record IntradayBar(Instant timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume)`. Sin anotaciones Jackson (no se serializa al cliente).

### A.3 MarketDataAdapter.getIntradayBars

- ☐ **T1.7** Modificar `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/MarketDataAdapter.java`: agregar método:
  ```java
  @Retry(name = "alpacaDataApi", fallbackMethod = "getIntradayBarsFallback")
  public List<IntradayBar> getIntradayBars(String ticker) { ... }
  ```
  Implementación:
  - Calcular `start = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)` (00:00Z hoy).
  - URI: `/v2/stocks/{symbol}/bars` con query params `timeframe=15Min`, `start={start ISO}`, `limit=50`, `adjustment=raw`, `feed=iex`.
  - Llamada vía `restClient.get().uri(...).retrieve().body(AlpacaBarsResponse.class)`.
  - Si `response.bars()` es null o no contiene la key del ticker → return `List.of()`.
  - Else map `AlpacaBar` → `IntradayBar` y retornar.
  - Catch `HttpClientErrorException`: si 429 lanzar `MarketDataUnavailableException` (mismo patrón que `getLatestPrice`); otros 4xx lanzar también.
- ☐ **T1.8** Agregar método `private List<IntradayBar> getIntradayBarsFallback(String ticker, Throwable t)` que propaga `MarketDataUnavailableException` igual que el de `getLatestPrice`.

### A.4 CachedMarketDataAdapter

- ☐ **T1.9** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/CachedMarketDataAdapter.java` como `@Component`. Constructor inyecta `MarketDataAdapter marketDataAdapter` + `RedisTemplate<String, String> redisTemplate` + `@Qualifier("marketDataExecutor") ExecutorService executor`. Constantes:
  ```java
  private static final String KEY_PREFIX = "market-data:price:";
  private static final Duration TTL = Duration.ofSeconds(30);
  ```
- ☐ **T1.10** Agregar método público singular `BigDecimal getLatestPrice(String ticker)`:
  - Try: `String cached = redisTemplate.opsForValue().get(KEY_PREFIX + ticker)`. Si != null → log DEBUG "cache hit" + return `new BigDecimal(cached)`.
  - Si cache miss → `BigDecimal price = marketDataAdapter.getLatestPrice(ticker)`. Log DEBUG "cache miss".
  - Set en Redis: `redisTemplate.opsForValue().set(KEY_PREFIX + ticker, price.toPlainString(), TTL)`.
  - Return price.
  - Si `MarketDataUnavailableException`: propagar (no cachear nulls, D16).
  - Si `RedisConnectionFailureException` (o `RedisSystemException`): log WARN "Redis down, fallback to direct" + return `marketDataAdapter.getLatestPrice(ticker)`.
- ☐ **T1.11** Agregar método público batch `Map<String, BigDecimal> getLatestPrices(Collection<String> tickers)`:
  - Si `tickers.isEmpty()` → return `Map.of()`.
  - Construir lista de keys: `tickers.stream().map(t -> KEY_PREFIX + t).toList()`.
  - `multiGet` en Redis: `List<String> cachedValues = redisTemplate.opsForValue().multiGet(keys)` (mismo orden que keys).
  - Separar hits (no-null) de misses (null).
  - Para cada miss: `CompletableFuture.supplyAsync(() -> marketDataAdapter.getLatestPrice(ticker), executor).completeOnTimeout(null, 1500, MS).exceptionally(t -> { log.warn(...); return null; })`.
  - `CompletableFuture.allOf(...).join()`.
  - Set en Redis los exitosos (no los nulls): para cada `(ticker, price)` con price != null → `set(KEY_PREFIX + ticker, price.toPlainString(), TTL)`.
  - Merge hits + misses → return `Map<String, BigDecimal>` (con nulls para los que fallaron).
  - Wrap toda la lógica Redis con try/catch `RedisConnectionFailureException` → fallback a fan-out directo sin cache.
  - Log INFO al final: `tickers={size} cacheHits={n} alpacaCalls={m} elapsedMs={ms}`.

### A.5 Tests unit Lote A

- ☐ **T1.12** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/alpaca/CachedMarketDataAdapterTest.java` con `@ExtendWith(MockitoExtension.class)`. Mocks: `MarketDataAdapter`, `RedisTemplate<String, String>`, `ValueOperations<String, String>` (devuelto por `redisTemplate.opsForValue()`).
- ☐ **T1.13** Test `getLatestPrice_cacheHit_returnsCachedWithoutCallingAdapter()`: `valueOps.get("market-data:price:AAPL")` retorna `"193.20"` → método retorna `new BigDecimal("193.20")` Y `marketDataAdapter` mock verify `never()`.
- ☐ **T1.14** Test `getLatestPrice_cacheMiss_callsAdapterAndSetsCache()`: `valueOps.get(...)` retorna null, `marketDataAdapter.getLatestPrice("AAPL")` retorna `new BigDecimal("190.00")` → método retorna BigDecimal("190.00") Y `valueOps.set("market-data:price:AAPL", "190.00", Duration.ofSeconds(30))` invocado exactly once.
- ☐ **T1.15** Test `getLatestPrice_adapterThrowsMarketDataUnavailable_propagatesAndDoesNotCache()`: adapter lanza `MarketDataUnavailableException` → assertThrows + verify `valueOps.set(...)` NUNCA invocado.
- ☐ **T1.16** Test `getLatestPrice_redisDown_fallsBackToAdapterDirectly()`: `valueOps.get(...)` lanza `RedisConnectionFailureException`, adapter retorna `new BigDecimal("191.50")` → método retorna 191.50 sin propagar la excepción Redis Y log WARN registrado.
- ☐ **T1.17** Test `getLatestPrices_partialCacheHit_fetchesMissesAndCachesNew()`: tickers=["AAPL","MSFT","TSLA"], multiGet retorna `["193.20", null, null]`, adapter mockea getLatestPrice("MSFT")→412.00 y getLatestPrice("TSLA")→ lanza MarketDataUnavailableException → result map = {AAPL=193.20, MSFT=412.00, TSLA=null}, valueOps.set invocado solo para MSFT (no para TSLA porque falló).
- ☐ **T1.18** Test `getLatestPrices_emptyInput_returnsEmptyMap()`: tickers=[] → return Map.of() sin invocar redis ni adapter.

### A.6 Tests bars adapter

- ☐ **T1.19** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/alpaca/MarketDataAdapterBarsTest.java` con setup `MockRestServiceServer` sobre el RestClient alpacaData (heredar patrón de `MarketDataAdapterTest` existente para `getLatestPrice`).
- ☐ **T1.20** Test `getIntradayBars_happy_returnsParsedBars()`: stub URI `/v2/stocks/AAPL/bars?timeframe=15Min&...` retorna JSON `{"bars":{"AAPL":[{"t":"2026-05-24T13:30:00Z","o":189.50,"h":189.80,"l":189.30,"c":189.60,"v":1500000},...]}}` → método retorna List<IntradayBar> con 1+ elemento.
- ☐ **T1.21** Test `getIntradayBars_emptyBars_returnsEmptyList()`: stub retorna `{"bars":{}}` → return `List.of()`.
- ☐ **T1.22** Test `getIntradayBars_404_throwsMarketDataUnavailable()`: stub responde 404 → `MarketDataUnavailableException`.
- ☐ **T1.23** Test `getIntradayBars_5xxRetries_thenThrows()`: stub responde 503 a 3 intentos → `MarketDataUnavailableException` tras retries (verificar con `application-test.yml` retry config — 3 attempts × 50ms).

### A.7 Validación HITO 1

- ☐ **H1** Ejecutar `cd backend && .\mvnw.cmd compile`. Esperado: BUILD SUCCESS.
- ☐ **H1** Ejecutar `.\mvnw.cmd test -Dtest='CachedMarketDataAdapterTest,MarketDataAdapterBarsTest'`. Esperado: ≥10 verdes (7 cache + 4 bars).
- ☐ **H1** Levantar Redis si no está: `docker compose up -d redis`. Verificar `docker exec bloomtrade-redis redis-cli ping` retorna `PONG`.
- ☐ **H1** No avanzar a Lote B si H1 no está verde.

---

## Lote B — Backend Dashboard module → HITO 2

### B.1 DTOs Dashboard

- ☐ **T2.1** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/TickerDashboardDto.java` como `record TickerDashboardDto(String ticker, String currentPrice, String openPrice, String dayChangePct, List<String> sparkline)`. Anotar `@Schema` mencionando que campos numéricos son BigDecimal stringificados y pueden ser null si market data no disponible. `sparkline` es lista de closes ordenada asc.
- ☐ **T2.2** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/MarketGroupDto.java` como `record MarketGroupDto(String market, List<TickerDashboardDto> items)`. `market` valor del enum `Market` stringificado.
- ☐ **T2.3** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/AccountEquityDto.java` como `record AccountEquityDto(String balance, String positionsMarketValue, String equity, String costBasisTotal, String unrealizedPnL, String unrealizedPnLPct, String currency)`. Todos BigDecimal stringified excepto currency="USD".
- ☐ **T2.4** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/DashboardSnapshotResponse.java` como `record DashboardSnapshotResponse(List<MarketGroupDto> tickers, AccountEquityDto equity, String marketDataAvailable, Instant fetchedAt)`.

### B.2 PortfolioService.getAccountEquity

- ☐ **T2.5** Modificar `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/service/PortfolioService.java`: agregar método:
  ```java
  @Transactional(readOnly = true)
  public AccountEquityDto getAccountEquity(UUID userId, Map<String, BigDecimal> prices) { ... }
  ```
  Implementación:
  - `UserBalance balance = userBalanceRepository.findById(userId).orElseThrow(...)`.
  - `List<Position> positions = positionRepository.findByUserIdAndQuantityGreaterThan(userId, 0)` (reuso del método de F16+F21).
  - Si `positions.isEmpty()`: retornar `AccountEquityDto(balance="...", positionsMarketValue="0.00", equity=balance, costBasisTotal="0.00", unrealizedPnL="0.00", unrealizedPnLPct=null, currency="USD")` (pct=null porque costBasis=0 no permite ratio).
  - Else iterar positions:
    - Para cada Position p: `BigDecimal price = prices.get(p.getTicker())`. Si price != null: acumular `positionsMarketValue += qty × price`. Acumular siempre `costBasisTotal += qty × avgBuyPrice`. Si ALGUNA price es null y la position tiene qty>0: marcar `marketValueIsPartial = true`.
    - Si TODOS los prices están null: `positionsMarketValue = null`, `equity = null`, `unrealizedPnL = null`, `unrealizedPnLPct = null`.
    - Si parcial: V1 política → **solo computar marketValue de las que tienen precio. Si parcial, marketValue es el sum de los que sí (incompleto), pero registrar warning interno y retornar todos los campos con scale=2.** **Decisión sub D-EQUITY-PARTIAL**: parcial → marketValue presenta el sum parcial y unrealizedPnL es marketValue − costBasis (también con scope parcial). Documentar en javadoc que parcial = sum-of-available.
    - Si todos: marketValue=Σ(qty×price), equity=balance+marketValue, pnL=marketValue−costBasis, pct=(pnL/costBasis)×100 scale=2.
  - Stringificar todos los BigDecimal con scale=2 HALF_UP.

### B.3 BarsOrchestrator

- ☐ **T2.6** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/service/BarsOrchestrator.java` como `@Component`. Inyectar `MarketDataAdapter marketDataAdapter` + `@Qualifier("marketDataExecutor") ExecutorService executor`. Constante `PER_TICKER_TIMEOUT_MS = 1500L`.
  Método público:
  ```java
  public Map<String, List<IntradayBar>> fetchBars(Collection<String> tickers)
  ```
  Implementación análoga a `MarketDataOrchestrator.fetchPrices`:
  - Si empty → return `Map.of()`.
  - Stream sobre tickers → `CompletableFuture.supplyAsync(() -> marketDataAdapter.getIntradayBars(ticker), executor).completeOnTimeout(List.of(), 1500, MS).exceptionally(t -> { log.warn(...); return List.of(); })`. **NOTA**: timeout default es `List.of()` (no null) — más simple para downstream consumers que no tienen que null-check.
  - `allOf(...).join()`.
  - Collect a `Map<String, List<IntradayBar>>`.
  - Log INFO con counts.

### B.4 DashboardService

- ☐ **T2.7** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/service/DashboardService.java` como `@Service`. Constructor inyecta: `CachedMarketDataAdapter cachedMarketDataAdapter`, `BarsOrchestrator barsOrchestrator`, `PortfolioService portfolioService`, `DashboardMapper dashboardMapper`.
  Método público:
  ```java
  public DashboardSnapshotResponse getSnapshot(UUID userId) { ... }
  ```
  Implementación (sigue el pseudo-código del plan §3 Lote B):
  - `Map<Market, List<String>> byMarket = AllowedTickers.byMarket()`.
  - `List<String> allTickers = byMarket.values().stream().flatMap(List::stream).toList()` (25 tickers).
  - `Map<String, BigDecimal> prices = cachedMarketDataAdapter.getLatestPrices(allTickers)`.
  - `Map<String, List<IntradayBar>> barsMap = barsOrchestrator.fetchBars(allTickers)`.
  - `AccountEquityDto equity = portfolioService.getAccountEquity(userId, prices)`.
  - Construir lista de `MarketGroupDto` iterando `byMarket.entrySet()`. Para cada (Market m, List<String> ts), mapear cada ticker a `TickerDashboardDto` vía `dashboardMapper.toTickerDashboardDto(t, prices.get(t), barsMap.get(t))`. Wrap en `MarketGroupDto`.
  - Calcular `marketDataAvailable`: contar tickers con price==null. Si 0 nulls (de 25) → `"true"`. Si 0<nulls<25 → `"partial"`. Si 25 nulls → `"false"`.
  - Retornar `dashboardMapper.toDashboardSnapshotResponse(groups, equity, marketDataAvailable, Instant.now())`.

### B.5 DashboardMapper

- ☐ **T2.8** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/web/DashboardMapper.java` como `@Component`. Métodos:
  - `TickerDashboardDto toTickerDashboardDto(String ticker, BigDecimal currentPrice, List<IntradayBar> bars)`:
    - `currentPriceStr = currentPrice == null ? null : currentPrice.setScale(2, HALF_UP).toPlainString()`.
    - `openPrice = bars == null || bars.isEmpty() ? null : bars.get(0).open()`. (D17: first bar's open).
    - `openPriceStr = openPrice == null ? null : openPrice.setScale(2, HALF_UP).toPlainString()`.
    - `dayChangePct`: si openPrice == null OR currentPrice == null OR openPrice.signum()==0 → null. Else `((currentPrice − openPrice) / openPrice × 100).setScale(2, HALF_UP).toPlainString()`.
    - `sparkline`: si bars empty → `List.of()`. Else map cada bar a `bar.close().setScale(2, HALF_UP).toPlainString()` en orden cronológico (bars vienen así de Alpaca, no resortar).
    - Return new TickerDashboardDto(...).
  - `MarketGroupDto toMarketGroupDto(Market market, List<TickerDashboardDto> items)` — trivial wrap.
  - `DashboardSnapshotResponse toDashboardSnapshotResponse(List<MarketGroupDto> groups, AccountEquityDto equity, String marketDataAvailable, Instant fetchedAt)` — trivial wrap.

### B.6 DashboardController

- ☐ **T2.9** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/web/DashboardController.java` como `@RestController @RequestMapping("/api/v1/dashboard")`. Constructor inyecta `DashboardService dashboardService`.
  Método:
  ```java
  @GetMapping("/snapshot")
  public DashboardSnapshotResponse getSnapshot(@AuthenticationPrincipal AuthenticatedUser principal) {
      long start = System.nanoTime();
      DashboardSnapshotResponse response = dashboardService.getSnapshot(principal.userId());
      log.info("GET /dashboard/snapshot userId={} marketDataAvailable={} elapsedMs={}", 
               principal.userId(), response.marketDataAvailable(), 
               (System.nanoTime() - start) / 1_000_000);
      return response;
  }
  ```
  Anotar con `@Operation` Swagger + `@ApiResponse(responseCode="200", schema=...)`.

### B.7 Tests unit Lote B

- ☐ **T2.10** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/dashboard/service/DashboardServiceTest.java` con Mockito mocks de `CachedMarketDataAdapter`, `BarsOrchestrator`, `PortfolioService`, `DashboardMapper`.
- ☐ **T2.11** Test `getSnapshot_happy_returnsAllPopulated()`: mocks retornan prices con 25 valores, bars con 25 lists no vacías, equity populated → marketDataAvailable="true".
- ☐ **T2.12** Test `getSnapshot_allPricesNull_marketDataAvailableFalse()`: cachedMarketDataAdapter retorna map con todos null → marketDataAvailable="false".
- ☐ **T2.13** Test `getSnapshot_somePricesNull_marketDataAvailablePartial()`: 10 nulls de 25 → "partial".
- ☐ **T2.14** Test `getSnapshot_userHasNoPositions_equityFromMockOnly()`: portfolioService.getAccountEquity retorna AccountEquityDto sin positions → flow OK sin NPE.
- ☐ **T2.15** Test `getSnapshot_groupsByMarketInCorrectOrder()`: verify que la lista de MarketGroupDto en el response sigue orden NYSE, NASDAQ, LSE, TSE, ASX.

- ☐ **T2.16** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/portfolio/service/PortfolioServiceAccountEquityTest.java`.
- ☐ **T2.17** Test `getAccountEquity_happy_calculatesAllFields()`: balance=5000, posición AAPL qty=10 avg=189.45, price=193.20 → equity=balance+(10×193.20)=6932, costBasisTotal=10×189.45=1894.50, pnL=marketValue−costBasis=37.50, pct=1.98.
- ☐ **T2.18** Test `getAccountEquity_noPositions_equityEqualsBalance_pctIsNull()`.
- ☐ **T2.19** Test `getAccountEquity_pricesAllNull_marketValueAndPnLNull()`.
- ☐ **T2.20** Test `getAccountEquity_pricesPartial_sumOnlyAvailableMarketValues()` (D-EQUITY-PARTIAL).

- ☐ **T2.21** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/dashboard/web/DashboardMapperTest.java`.
- ☐ **T2.22** Test `toTickerDashboardDto_happy_calculatesDayChangePct()`: currentPrice=193.20, bars[0].open=189.50 → dayChangePct="1.95".
- ☐ **T2.23** Test `toTickerDashboardDto_priceNull_allDerivedNull()`: currentPrice=null → currentPrice/dayChangePct todos null. Pero openPrice puede no ser null (viene de bars).
- ☐ **T2.24** Test `toTickerDashboardDto_emptyBars_openPriceAndDayChangePctNull()`: bars=[] → openPrice=null, sparkline=[].
- ☐ **T2.25** Test `toTickerDashboardDto_sparklineHasScale2()`: bars con closes 189.50001 → sparkline=["189.50"].

- ☐ **T2.26** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/dashboard/service/BarsOrchestratorTest.java` análogo a `MarketDataOrchestratorTest` con ≥4 tests (happy, empty, exception, timeout).

### B.8 Test IT Lote B

- ☐ **T2.27** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/dashboard/DashboardControllerIT.java` con setup MockMvc + WireMock para Alpaca + Redis real del docker-compose.
  Setup:
  - `@SpringBootTest` con perfil `test`.
  - `@AutoConfigureMockMvc`.
  - `@Autowired RedisTemplate<String, String> redisTemplate`.
  - WireMock en puerto fijo (heredar de F09/F16 IT setup).
  - Helper `seedUser`, `seedBalance(userId, amount)`, `seedPosition(userId, ticker, qty, avgPrice)`, `stubAlpacaQuote(ticker, bid, ask)`, `stubAlpacaBars(ticker, bars: List<Map>)`.
  - `@AfterEach`: limpiar `redisTemplate.delete(redisTemplate.keys("market-data:*"))`.

- ☐ **T2.28** Test AC-01 `getSnapshot_happyCacheMiss_returnsAllPopulated()`:
  - Seed user con balance 5234.45 + 2 positions AAPL(10, 189.45) MSFT(5, 412).
  - Stub Alpaca quotes para los 25 tickers (basta con responder a los que se pidan).
  - Stub Alpaca bars para los 25 (con 1-3 bars cada uno).
  - `mockMvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", "Bearer " + jwt))`.
  - Esperado: 200 OK. `jsonPath("$.equity.equity")` ≈ "9208.95" o similar según mocks. `jsonPath("$.marketDataAvailable")` = "true". `jsonPath("$.tickers")` length=5 (mercados). `jsonPath("$.tickers[0].market")` = "NYSE".
  - Verify Redis: `redisTemplate.keys("market-data:price:*")` size=25.
- ☐ **T2.29** Test AC-02 `getSnapshot_cacheHit_secondCallDoesNotCallAlpaca()`:
  - Primera llamada como en T2.28 → 200 OK + cache poblado.
  - WireMock `resetRequests()` para no contar las primeras.
  - Segunda llamada → 200 OK con mismos precios.
  - WireMock `verify(0, getRequestedFor(urlPathMatching(".*quotes/latest")))` — confirma cache hit.
  - (Bars sí pueden ser invocados; en V1 no se cachean.)
- ☐ **T2.30** Test AC-03 `getSnapshot_alpacaDown_marketDataAvailableFalse()`:
  - Cache vacío (sin setup previo).
  - WireMock stub para `/quotes/latest` retorna 503 a todo.
  - GET /snapshot → 200 OK.
  - `jsonPath("$.marketDataAvailable")` = "false".
  - `jsonPath("$.tickers[0].items[0].currentPrice")` = null o ausente.
- ☐ **T2.31** Test AC-04 `getSnapshot_userWithoutPositions_equityFromBalanceOnly()`:
  - Seed user con balance 10000, sin positions.
  - GET /snapshot → 200 OK.
  - `jsonPath("$.equity.balance")` = "10000.00", `equity.equity` = "10000.00", `equity.unrealizedPnLPct` = null.

### B.9 Validación HITO 2

- ☐ **H2** Ejecutar `.\mvnw.cmd test -Dtest='DashboardServiceTest,DashboardMapperTest,PortfolioServiceAccountEquityTest,BarsOrchestratorTest'`. Esperado: ≥17 unit verdes.
- ☐ **H2** Ejecutar `.\mvnw.cmd verify -Dtest=DashboardControllerIT`. Esperado: 4 IT verdes.
- ☐ **H2** Verificar log del IT: ver "INFO ... cacheHits=25 alpacaCalls=0" en el segundo request del test cacheHit.
- ☐ **H2** No avanzar a Lote C si H2 no está verde.

---

## Lote C — Backend Order History module → HITO 3

### C.1 OrderRepository extends JpaSpecificationExecutor

- ☐ **T3.1** Modificar `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/repository/OrderRepository.java`: agregar `extends JpaSpecificationExecutor<Order>` a la herencia. Verificar que el método existente `findAll(Specification, Pageable)` esté disponible para llamar (es auto-provided).

### C.2 OrderSpecifications

- ☐ **T3.2** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/repository/OrderSpecifications.java` como class final con constructor privado y métodos estáticos:
  ```java
  public static Specification<Order> byUser(UUID userId) {
      return (root, query, cb) -> cb.equal(root.get("userId"), userId);
  }
  public static Specification<Order> byTicker(String ticker) {
      return (root, query, cb) -> cb.equal(root.get("ticker"), ticker);
  }
  public static Specification<Order> bySide(OrderSide side) {
      return (root, query, cb) -> cb.equal(root.get("side"), side);
  }
  ```
  Cada predicado se compone con `Specification.where(...).and(...)` en el caller.

### C.3 DTOs F17

- ☐ **T3.3** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/dto/PaginationDto.java` como `record PaginationDto(int page, int size, long totalElements, int totalPages)`.
- ☐ **T3.4** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/dto/OrderHistoryDto.java` como `record OrderHistoryDto(UUID orderId, UUID clientOrderId, String ticker, OrderSide side, int quantity, OrderStatus status, Instant submittedAt, Instant executedAt, String executionTotal, String averageFillPrice, String commission, String alpacaOrderId, String failureReason)`. Todos los BigDecimal stringificados scale=2.
- ☐ **T3.5** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/dto/OrderHistoryResponse.java` como `record OrderHistoryResponse(List<OrderHistoryDto> content, PaginationDto pagination)`.

### C.4 OrderHistoryMapper

- ☐ **T3.6** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/web/OrderHistoryMapper.java` como `@Component`. Métodos:
  - `OrderHistoryDto toOrderHistoryDto(Order order)`: stringifica BigDecimals con scale=2 HALF_UP. Null-safe para campos opcionales (`executionTotal`, `averageFillPrice`, `commission`, `executedAt`, `alpacaOrderId`, `failureReason`).
  - `PaginationDto toPaginationDto(Page<?> page)`: extrae number, size, totalElements, totalPages.
  - `OrderHistoryResponse toOrderHistoryResponse(Page<Order> page)`: combina ambos.

### C.5 OrderHistoryService

- ☐ **T3.7** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/service/OrderHistoryService.java` como `@Service`. Constructor inyecta `OrderRepository`. Método:
  ```java
  @Transactional(readOnly = true)
  public Page<Order> list(UUID userId, Optional<String> ticker, Optional<OrderSide> side, Pageable pageable) {
      Specification<Order> spec = Specification.where(OrderSpecifications.byUser(userId));
      if (ticker.isPresent()) spec = spec.and(OrderSpecifications.byTicker(ticker.get()));
      if (side.isPresent()) spec = spec.and(OrderSpecifications.bySide(side.get()));
      return orderRepository.findAll(spec, pageable);
  }
  ```

### C.6 OrderHistoryController

- ☐ **T3.8** Crear `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/web/OrderHistoryController.java` como `@RestController @RequestMapping("/api/v1/orders")`. Constructor inyecta `OrderHistoryService` + `OrderHistoryMapper`.
  Método:
  ```java
  @GetMapping
  public OrderHistoryResponse list(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @RequestParam(required = false) Optional<String> ticker,
      @RequestParam(required = false) Optional<OrderSide> side,
      @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.DESC) 
      @Parameter(hidden = true) Pageable pageable
  ) {
      // Validar size cap (D21)
      if (pageable.getPageSize() > 100) {
          throw new InvalidRequestParameterException("size", "Máximo permitido: 100");
      }
      Page<Order> page = orderHistoryService.list(principal.userId(), ticker, side, pageable);
      return orderHistoryMapper.toOrderHistoryResponse(page);
  }
  ```
  Anotar con `@Operation` Swagger.

### C.7 Exception handler para parsing inválido

- ☐ **T3.9** Verificar `backend/src/main/java/co/edu/unbosque/bloomtrade/common/GlobalExceptionHandler.java`: si `MethodArgumentTypeMismatchException` no está manejado (típico para `?side=FOO` donde Spring no puede convertir a OrderSide enum), agregar handler que retorne 400 `INVALID_REQUEST_PARAMETER` con detalle del campo. Si ya existe (de F09), no duplicar.
- ☐ **T3.10** Verificar manejo de `InvalidRequestParameterException` (custom). Si no existe esa excepción, crear `backend/src/main/java/co/edu/unbosque/bloomtrade/common/exception/InvalidRequestParameterException.java` o reusar una existente similar. Asociar con handler 400.

### C.8 Tests unit Lote C

- ☐ **T3.11** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/trading/history/service/OrderHistoryServiceTest.java` con mock de `OrderRepository`.
- ☐ **T3.12** Test `list_noFilters_callsRepoWithUserOnlySpec()`: ticker=Optional.empty(), side=Optional.empty() → verify `orderRepository.findAll(specCaptor, pageableCaptor)` invocado y el spec capturado debería corresponder a "user-only" (verify via mockito's specCaptor + reflection o simplemente test indirecto vía IT).
- ☐ **T3.13** Test `list_filterTicker_addsTickerSpec()`.
- ☐ **T3.14** Test `list_filterSide_addsSideSpec()`.
- ☐ **T3.15** Test `list_filterBoth_composesSpec()`.

- ☐ **T3.16** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/trading/history/repository/OrderSpecificationsTest.java` con `@DataJpaTest` o test directo sin Spring (usando mocks de `Root`, `CriteriaQuery`, `CriteriaBuilder` o test integrado que persiste y verifica).
- ☐ **T3.17** Test `byUser_filtersByUserId()`: persistir 2 orders de users distintos → query con `byUser(uid1)` retorna 1.
- ☐ **T3.18** Test `byTicker_filtersByTickerEquals()`.
- ☐ **T3.19** Test `bySide_filtersBySideEquals()`.

- ☐ **T3.20** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/trading/history/web/OrderHistoryMapperTest.java`.
- ☐ **T3.21** Test `toOrderHistoryDto_executedOrder_allFieldsPopulated()`: order EXECUTED con executionTotal/averageFillPrice/commission/executedAt → DTO con todos los strings scale=2.
- ☐ **T3.22** Test `toOrderHistoryDto_pendingOrder_executionFieldsNull()`.
- ☐ **T3.23** Test `toOrderHistoryDto_failedOrder_failureReasonPopulated()`.

### C.9 Tests IT Lote C

- ☐ **T3.24** Crear `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/trading/OrderHistoryControllerIT.java` con setup similar a otros IT (MockMvc + perfil test, sin WireMock — no toca Alpaca). Setup helper `seedOrder(userId, ticker, side, qty, status)`.
- ☐ **T3.25** Test AC-01 `list_noFilters_returnsPaginatedDesc()`: seed 25 órdenes (varias combinaciones) → GET `/orders?page=0&size=10` → content.length=10, pagination.totalElements=25, ordenadas DESC por submittedAt.
- ☐ **T3.26** Test AC-02 `list_filterTicker_returnsOnlyMatchingTicker()`: GET `/orders?ticker=AAPL` → todas tienen ticker="AAPL".
- ☐ **T3.27** Test AC-04 `list_filterTickerAndSide_returnsOnlyBoth()`: GET `/orders?ticker=AAPL&side=BUY`.
- ☐ **T3.28** Test AC-05 `list_isolatedPerUser()`: 2 users con órdenes, cada uno solo ve las suyas.
- ☐ **T3.29** Test AC-06 `list_invalidSideParam_returns400()`: GET `/orders?side=FOO` → 400 INVALID_REQUEST_PARAMETER.
- ☐ **T3.30** Test AC-07 `list_sizeAboveCap_returns400()`: GET `/orders?size=200` → 400.

### C.10 Validación HITO 3

- ☐ **H3** Ejecutar `.\mvnw.cmd test -Dtest='OrderHistoryServiceTest,OrderSpecificationsTest,OrderHistoryMapperTest'`. Esperado: ≥10 unit verdes.
- ☐ **H3** Ejecutar `.\mvnw.cmd verify -Dtest=OrderHistoryControllerIT`. Esperado: 6 IT verdes.
- ☐ **H3** Abrir Swagger UI → verificar que `GET /api/v1/orders` aparece con los query params documentados.

---

## Lote D — Frontend Dashboard page → HITO 4

### D.1 Types

- ☐ **T4.1** Modificar `frontend/src/types/api.ts`: agregar interfaces TypeScript:
  ```ts
  export interface TickerDashboardDto { ticker: string; currentPrice: string | null; openPrice: string | null; dayChangePct: string | null; sparkline: string[]; }
  export interface MarketGroupDto { market: 'NYSE' | 'NASDAQ' | 'LSE' | 'TSE' | 'ASX'; items: TickerDashboardDto[]; }
  export interface AccountEquityDto { balance: string; positionsMarketValue: string | null; equity: string | null; costBasisTotal: string | null; unrealizedPnL: string | null; unrealizedPnLPct: string | null; currency: 'USD'; }
  export interface DashboardSnapshotResponse { tickers: MarketGroupDto[]; equity: AccountEquityDto; marketDataAvailable: 'true' | 'false' | 'partial'; fetchedAt: string; }
  
  export interface OrderHistoryDto { orderId: string; clientOrderId: string; ticker: string; side: 'BUY' | 'SELL'; quantity: number; status: 'PENDING' | 'EXECUTED' | 'FAILED' | 'REJECTED' | 'CANCELLED'; submittedAt: string; executedAt: string | null; executionTotal: string | null; averageFillPrice: string | null; commission: string | null; alpacaOrderId: string | null; failureReason: string | null; }
  export interface PaginationDto { page: number; size: number; totalElements: number; totalPages: number; }
  export interface OrderHistoryResponse { content: OrderHistoryDto[]; pagination: PaginationDto; }
  ```

### D.2 API wrappers

- ☐ **T4.2** Crear `frontend/src/features/dashboard/api/dashboardApi.ts`:
  ```ts
  export async function fetchDashboardSnapshot(): Promise<DashboardSnapshotResponse> {
      const { data } = await axiosClient.get<DashboardSnapshotResponse>('/api/v1/dashboard/snapshot');
      return data;
  }
  ```
- ☐ **T4.3** Crear `frontend/src/features/dashboard/api/ordersApi.ts`:
  ```ts
  export interface FetchOrdersParams { page?: number; size?: number; ticker?: string; side?: 'BUY' | 'SELL'; }
  export async function fetchOrders(params: FetchOrdersParams = {}): Promise<OrderHistoryResponse> {
      const { data } = await axiosClient.get<OrderHistoryResponse>('/api/v1/orders', { params });
      return data;
  }
  ```

### D.3 Hooks

- ☐ **T4.4** Crear `frontend/src/features/dashboard/hooks/useDashboardSnapshot.ts`:
  ```ts
  export function useDashboardSnapshot() {
      return useQuery({
          queryKey: ['dashboard', 'snapshot'],
          queryFn: fetchDashboardSnapshot,
          refetchInterval: 30_000,
          refetchIntervalInBackground: true,
          staleTime: 25_000,
      });
  }
  ```
- ☐ **T4.5** Crear `frontend/src/features/dashboard/hooks/useOrdersRecent.ts`:
  ```ts
  export function useOrdersRecent() {
      return useQuery({
          queryKey: ['orders', 'recent'],
          queryFn: () => fetchOrders({ page: 0, size: 10 }),
          refetchInterval: 30_000,
          refetchIntervalInBackground: true,
          staleTime: 25_000,
      });
  }
  ```

### D.4 Componentes leaf

- ☐ **T4.6** Crear `frontend/src/features/dashboard/components/Sparkline.tsx`:
  ```tsx
  interface SparklineProps { data: string[]; positive: boolean | null; }
  export function Sparkline({ data, positive }: SparklineProps) {
      if (!data || data.length === 0) {
          return <div className="text-slate-400 text-center w-[100px]">—</div>;
      }
      const numbers = data.map(d => ({ value: parseFloat(d) }));
      const stroke = positive == null ? '#94a3b8' : positive ? '#10b981' : '#f43f5e';
      return (
          <LineChart width={100} height={30} data={numbers}>
              <Line type="monotone" dataKey="value" stroke={stroke} dot={false} strokeWidth={1.5} isAnimationActive={false} />
          </LineChart>
      );
  }
  ```
- ☐ **T4.7** Crear `frontend/src/features/dashboard/components/TickerRow.tsx`: props `{ ticker: TickerDashboardDto }`. Render una fila con:
  - Ticker badge (font-mono, font-bold).
  - currentPrice (formato USD es-CO) o "—" si null.
  - dayChangePct con icono TrendingUp/Down + color (D7) o "—".
  - Sparkline con `positive = dayChangePct > 0`.
- ☐ **T4.8** Crear `frontend/src/features/dashboard/components/MarketDataBanner.tsx`: props `{ status: 'true'|'false'|'partial' }`. Si "true" → null. Si "partial" → banner naranja. Si "false" → banner amarillo. Texto desde `messages.es.ts`.
- ☐ **T4.9** Crear `frontend/src/features/dashboard/components/EquityCard.tsx`: props `{ equity: AccountEquityDto, fetchedAt: string, onRefresh: () => void }`. Render:
  - Título "Equity total".
  - Monto principal: `equity.equity || equity.balance` con `Intl.NumberFormat("es-CO", { style: "currency", currency: "USD" })`. Si equity es null, mostrar `dashboard.equityWithoutPrices` con balance.
  - Si unrealizedPnL no null: línea con texto color según signo + ícono TrendingUp/Down.
  - Footer "Actualizado hace Xs" (relative time desde fetchedAt).
  - Botón `↻` con aria-label de `messages.es`.

### D.5 Componentes container

- ☐ **T4.10** Crear `frontend/src/features/dashboard/components/TickerGrid.tsx`: props `{ tickers: MarketGroupDto[] }`. Render:
  - Grid responsive `grid grid-cols-1 md:grid-cols-2 xl:grid-cols-5 gap-4`.
  - Cada cell = una MarketGroupDto.
  - Header del cell: `<h3>` con `market` (NYSE/NASDAQ/etc).
  - Lista vertical de `TickerRow` debajo.
- ☐ **T4.11** Crear `frontend/src/features/dashboard/components/RecentOrdersWidget.tsx`: usa hook `useOrdersRecent()`. Render `<details open>` con summary "Últimas 10 órdenes". Body: tabla con headers (Ticker, Lado, Cantidad, Estado, Fecha) + filas. Si data.content.length=0 → empty state "Aún no has colocado órdenes." con CTA a `/trade`. Side: BUY="COMPRA" verde, SELL="VENTA" rojo. Status con badge según copy. Fecha formateada con `date-fns format(parseISO(submittedAt), "dd-MMM-yyyy HH:mm", { locale: es })`.

### D.6 Página

- ☐ **T4.12** Crear `frontend/src/features/dashboard/DashboardPage.tsx`:
  ```tsx
  export function DashboardPage() {
      const queryClient = useQueryClient();
      const snapshot = useDashboardSnapshot();
      const handleRefresh = () => {
          queryClient.invalidateQueries({ queryKey: ['dashboard'] });
          queryClient.invalidateQueries({ queryKey: ['orders'] });
      };
      if (snapshot.isLoading) return <Spinner />;
      if (snapshot.isError) return <ErrorBanner />;
      const data = snapshot.data!;
      return (
          <div className="container max-w-6xl mx-auto p-6 space-y-6">
              <EquityCard equity={data.equity} fetchedAt={data.fetchedAt} onRefresh={handleRefresh} />
              <MarketDataBanner status={data.marketDataAvailable} />
              <TickerGrid tickers={data.tickers} />
              <RecentOrdersWidget />
          </div>
      );
  }
  ```

### D.7 Wiring

- ☐ **T4.13** Modificar `frontend/src/App.tsx`: agregar ruta `<Route path="/dashboard" element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />`. Decisión sub: ¿`/` redirige a `/dashboard` para autenticados? Confirmar con humano durante implementación HITO 4 — si sí, agregar `<Route path="/" element={<Navigate to="/dashboard" replace />} />` o similar.
- ☐ **T4.14** Modificar `frontend/src/components/AppHeader.tsx`: link "Dashboard" como PRIMER link de navegación autenticada (antes de Trade).
- ☐ **T4.15** Modificar `frontend/src/lib/messages.es.ts`: agregar todas las claves listadas en plan §3 Lote D. Aprox 20 keys nuevas:
  ```ts
  dashboard: {
      title: "Dashboard",
      equity: { headline: "Equity total", pnlPositive: "P&L no realizado: +{amount} ({pct})", pnlNegative: "P&L no realizado: −{amount} ({pct})", pnlNeutral: "P&L no realizado: —", withoutPrices: "Equity = balance {balance} (precios de mercado no disponibles)" },
      refreshAria: "Actualizar",
      banner: { partial: "Algunos precios no se pudieron obtener. Marcados con —", down: "Precios de mercado temporalmente no disponibles. Mostramos información disponible." },
      ticker: { priceUnavailable: "—" },
      orders: {
          title: "Últimas 10 órdenes",
          empty: "Aún no has colocado órdenes.",
          emptyCta: "Operar ahora",
          tableHeaders: { ticker: "Ticker", side: "Lado", quantity: "Cantidad", status: "Estado", date: "Fecha" },
          sideBuy: "COMPRA",
          sideSell: "VENTA",
          status: { PENDING: "EN COLA", EXECUTED: "EJECUTADA", FAILED: "FALLIDA", REJECTED: "RECHAZADA", CANCELLED: "CANCELADA" }
      }
  }
  ```

### D.8 Validación HITO 4

- ☐ **H4** Ejecutar `cd frontend && npm run build`. Esperado: BUILD SUCCESS sin warnings nuevos.
- ☐ **H4** `docker compose up -d --build` (rebuild para que frontend tome cambios).
- ☐ **H4 (humano)** Smoke visual: login → /dashboard → ver card equity + grid 5 mercados × 5 tickers con sparklines renderizando + widget órdenes con últimas operaciones del demo. Dejar abierto ~30s para ver el refetch automático en Network tab.
- ☐ **H4 (humano)** Verificar Redis: `docker exec bloomtrade-redis redis-cli KEYS "market-data:price:*"` muestra 25 keys.

---

## Lote E — Tests IT exhaustivos + limpieza deuda → HITO 5

### E.1 IT adicionales

- ☐ **T5.1** Agregar `DashboardControllerIT#getSnapshot_isolatedPerUser_equityNotLeaked()`: 2 usuarios con posiciones distintas, A llama /snapshot con su JWT → equity.positionsMarketValue solo refleja AAPL de A, NO MSFT de B.
- ☐ **T5.2** Agregar `DashboardControllerIT#getSnapshot_withoutJwt_returns403orWithComment()`: GET sin Authorization header → 403 (con comentario "expecting 401 globally — see D17 F16+F21 cross-cutting deuda diferida a mini-HU token-rotation-logout").
- ☐ **T5.3** Agregar `DashboardControllerIT#getSnapshot_cacheTtlExpires_secondCallHitsAlpaca()`: primera call popula cache, manipular tiempo `redisTemplate.expire("market-data:price:AAPL", Duration.ZERO)` para forzar expiración inmediata, WireMock resetRequests, segunda call → WireMock verify count > 0 para `/quotes/latest`.
- ☐ **T5.4** (Opcional, deferred si infra no permite mockear Redis fácil): `DashboardControllerIT#getSnapshot_redisDown_fallsBackToAlpaca()` — saltar si no es trivial.

- ☐ **T5.5** Agregar `OrderHistoryControllerIT#list_filterSideOnly()`: AC-03 explícito (en C9 solo se cubría filter combinado y ticker-only).

### E.2 Limpieza deuda viva del handoff F16+F21

- ☐ **T5.6** Eliminar dead code `InvalidSideException.sideNotYetImplemented()`. Pasos:
  - `grep -r sideNotYetImplemented backend/` para confirmar 0 callers productivos.
  - Borrar el método en `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/exception/InvalidSideException.java`.
  - Si hay tests que invocan ese método, borrarlos o reescribirlos.
- ☐ **T5.7** (Opcional, si Lote E va adelantado) Cierre deuda #15: extender `frontend/src/features/trade/hooks/useTickerOptions.ts` para filtrar `TickerDropdown` por posiciones cuando side=SELL.
  - Consumir `usePortfolioPositions()` (de F16+F21).
  - Si side === 'SELL', filter `ALL_TICKERS` para solo incluir los que están en `positions.map(p => p.ticker)`.
  - Si lista vacía (sin posiciones), mostrar mensaje "No tienes posiciones para vender".
  - **Tarea bonus** — solo si tiempo. Si no, dejar como deuda viva con nota actualizada.

### E.3 Validación HITO 5

- ☐ **H5** Ejecutar `.\mvnw.cmd verify` (suite completa). Esperado: TODOS los tests del proyecto verdes (~310 totales, 286 actuales + ~24 nuevos del bundle).
- ☐ **H5** Verificar `grep -r sideNotYetImplemented backend/` → 0 matches (deuda #16 cerrada).
- ☐ **H5** Verificar cobertura módulos dashboard + trading.history en JaCoCo (`backend/target/site/jacoco/index.html`). Aceptable: ≥75% por convención del proyecto. Si bajo, documentar como D-COVERAGE-DEUDA en plan §2.5 — NO bloqueante por [[feedback-coverage-vs-velocidad]].

---

## Lote F — Cierre → HITO 6

- ☐ **T6.1** Agregar sección "Día 9 — HU-F18+F17 (Dashboard + Historial)" a `APRENDIZAJES.md` en primera persona. Posibles temas:
  - Cache Redis primer uso real en el proyecto: por qué wrapper Decorator vs `@Cacheable`, control fino sobre exceptions, TTL nativo Redis.
  - Sparklines con recharts: por qué LineChart sin ejes/grid/tooltip, escala automática, performance con 25 instancias por render.
  - Specification API: cuándo conviene sobre derived methods (filters dinámicos opcionales).
  - F17 promovido: la regla §3.4 del ROADMAP en acción — desarrollo a tiempo permite expandir scope.
  - Cierre de deuda viva en el mismo PR donde se gana contexto del módulo: #19 cache + #16 dead code + opcionalmente #15.
  - Bundle vs split: cuándo dos HUs comparten suficiente página/módulo para justificar bundle (F18+F17) vs cuándo no (F18 sola).
  - Reflexión meta sobre SDD: este es el 4º bundle del Sprint 2, el flujo SPEC→plan→tasks ya es muscle memory; los tiempos de redacción de docs se acortaron significativamente.
  - Costo de polling vs cache: matemática real del rate limit Alpaca con TTL 30s.

- ☐ **T6.2** Actualizar `AGENTS.md`:
  - Bloque "Trabajo activo": branch `feat/HU-F18-F17-dashboard-historial`, HU activa, sprint Día 9 cerrado, próximo paso Día 10 estabilización.
  - Mover "Cómo continuar (post HU-F16+F21 → HU-F18 Día 9)" a sección histórica.
  - Nueva sección "Cómo continuar (post HU-F18+F17 → Día 10 estabilización + Sprint Review/Retro)" con:
    - Estado final de HUs del MVP (todas 9 ☑ + F17 bonus).
    - Pre-requisitos para Día 10: JMeter setup, scripts JMeter para ESC-R1 + ESC-R2.
    - Documentación final pendiente: diagramas C4, secuencia, Sprint 1+2 Review/Retro diferidos, Informe Final.
  - Actualizar "Deuda viva": marcar #19 cerrada, #16 cerrada, posiblemente #15 cerrada. Agregar nuevas deudas si surgieron (D-SPARKLINE-CACHE V2, D-CACHE-STALE V2, D-METRICS-CACHE-HIT-RATIO).

- ☐ **T6.3** Si hubo decisiones emergentes durante implementación, completar `plan.md` §2.4 con D25+ siguiendo patrón F09/F10/F16+F21 (Síntoma → Causa raíz → Resolución).

- ☐ **T6.4** Preparar mensaje de commit en `C:\Users\juang\AppData\Local\Temp\bt-hu-f18-f17.txt` (ruta completa literal P6, NO `$env:TEMP\...`). Formato Conventional Commits:
  ```
  feat(dashboard): cierra bundle HU-F18 + HU-F17 — dashboard 25 activos + historial

  - 3 endpoints REST: GET /api/v1/dashboard/snapshot (25 tickers + equity + sparklines),
    GET /api/v1/orders (paginado con filtros ticker+side).
  - Cache Redis PriceCache TTL 30s amortiguando rate-limit Alpaca (cierra deuda viva #19).
  - Wrapper CachedMarketDataAdapter (Decorator pattern) sin tocar MarketDataOrchestrator F16+F21.
  - F17 promovido al MVP per ROADMAP §3.4 (desarrollo a tiempo).
  - Frontend /dashboard: EquityCard + TickerGrid responsive 5×5 + sparklines recharts + RecentOrdersWidget.
  - Polling React Query 30s + botón refresh manual.
  - Limpieza dead code InvalidSideException.sideNotYetImplemented (deuda viva #16).
  - 24 tests nuevos (unit + IT con WireMock + Redis real).
  - Sin migración Flyway nueva, sin dependencias nuevas, sin audit events.

  refs HU-F18 HU-F17
  spec specs/HU-F18-F17-dashboard-historial/SPEC.md

  Co-authored-by: Claude <noreply@anthropic.com>
  ```

- ☐ **T6.5 (humano)** `git add -A && git status` (verificar staging) → `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f18-f17.txt`.
- ☐ **T6.6 (humano)** `git push -u origin feat/HU-F18-F17-dashboard-historial` → `gh pr create ...` → Squash and merge.

---

## Resumen de tareas

| Lote | Tasks | Tests nuevos | HITO |
|---|---|---|---|
| A | 23 (T1.1–T1.23) | 10 unit (7 cache + 4 bars) | H1: compile + cache+bars verdes |
| B | 31 (T2.1–T2.31) | 13 unit + 4 IT = 17 | H2: Dashboard service + controller verde |
| C | 30 (T3.1–T3.30) | 10 unit + 6 IT = 16 | H3: Order History service + controller verde |
| D | 15 (T4.1–T4.15) | 0 (smoke humano) | H4: build verde + smoke visual |
| E | 7 (T5.1–T5.7) | 4 IT + deuda cleanup | H5: mvn verify completo verde (~310) + #16 cerrada |
| F | 4 (T6.1–T6.4) + 2 humano | — | H6: commit + push + PR |
| **Total** | **~110 tareas + 2 humano** | **~47 nuevos** | **6 HITOs** |

---

## Notas de ejecución (operacional)

- **Cadencia**: validación SOLO al final de lote (HITO) per [[feedback-cadencia-sdd]]. No micro-checkpoint tras cada task. Si algo bloquea dentro de un lote, parar y diagnosticar antes de seguir acumulando.
- **PowerShell**: comandos PS para Windows. `;` o `if ($?) { ... }` para chaining, nunca `&&`. Maven wrapper: siempre `.\mvnw.cmd` (no `mvn` global).
- **Docker prerequisito**: `docker compose up -d redis postgres` antes de H1 (tests cache) y H2/H3 (IT con Postgres). Idealmente todo el stack corriendo desde el inicio del Día 9.
- **WireMock**: config base ya está en `DashboardControllerIT` patterns previos (F09 `TradingControllerIT`, F16 `PortfolioControllerIT`). Copiar setup + extender stubs para `/v2/stocks/bars`.
- **Redis test**: usar el del docker-compose (D15). `@AfterEach` limpia `market-data:*` keys.
- **Decisiones emergentes**: cualquier desvío del plan se documenta inmediatamente en `plan.md` §2.4 como D25+ con patrón F09 D23–D29 (Síntoma → Causa raíz → Decisión → Trade-off). Memory predice 5-7 esperadas.
- **Patrón AuthenticatedUser**: TODOS los nuevos controllers usan `@AuthenticationPrincipal AuthenticatedUser principal` + `principal.userId()`, NUNCA `User` entity JPA. Memory `feedback_authenticatedprincipal_no_user.md`. Grep al codebase antes de elegir el tipo en cada controller nuevo.
- **Patrón noRollbackFor**: bundle es 100% read-only (sin `@Transactional` con throw), no aplica. Pero si emerge: memory `feedback_norollbackfor_nested_transactional.md` ya lo predice.
- **Smoke visual humano** (HITO 4): el usuario lo hará a su ritmo, NO bloquea el merge (per indicación 2026-05-24 estilo F16+F21). HITO 5 (mvn verify) sí es bloqueante.
- **Promoción F17**: justificada explícitamente en SPEC §1 y plan §1. Si en cualquier momento Día 9 se desborda, F17 se puede recortar (Lote C entero + Lote E.T5.5 + parte D.T4.5/T4.11) sin tocar F18.

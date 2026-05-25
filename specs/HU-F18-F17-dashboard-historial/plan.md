# plan.md — Bundle HU-F18 + HU-F17 (Dashboard + Historial de órdenes)

> Plan de implementación derivado de `SPEC.md` v1.0. Sigue la cadencia de lotes A–F + HITOs validables (igual que F16+F21). Cualquier decisión emergente durante implementación se agrega a §2.4 como D-XX siguiendo patrón F09/F10/F16+F21.

---

## 1. Estrategia general

**Tamaño del bundle**: F18 cubre dashboard + cache Redis + sparklines (~70% del esfuerzo de F09). F17 es un endpoint con filtros + paginación + widget (~30%). Estimado conjunto: **10–12 h netas** de implementación.

Read-only **excepto el cache Redis** (escritura de keys con TTL). Sin migración Flyway, sin Alpaca trading, sin event listeners, sin emails, sin nuevos audit events.

**Orden de ejecución (top-down):**

1. **Lote A — Backend foundation: cache Redis + market data extensions**. `RedisConfig` + `CachedMarketDataAdapter` decorator + `MarketDataAdapter.getIntradayBars` + DTOs Alpaca bars. HITO 1: compile + unit tests del cache verdes.
2. **Lote B — Backend Dashboard module**. `DashboardController` + `DashboardService` + DTOs + Mapper + `PortfolioService.getAccountEquity`. HITO 2: unit tests verdes + IT del endpoint /dashboard/snapshot.
3. **Lote C — Backend Order History module**. `OrderRepository extends JpaSpecificationExecutor` + `OrderSpecifications` + `OrderHistoryService` + `OrderHistoryController` + DTOs + Mapper + validación side enum. HITO 3: unit tests + IT del endpoint /orders.
4. **Lote D — Frontend Dashboard page**. `DashboardPage` + `EquityCard` + `TickerGrid` + `TickerRow` + `Sparkline` + `RecentOrdersWidget` + `MarketDataBanner` + 2 hooks + 2 API wrappers + ruta + AppHeader link + messages. HITO 4: `npm run build` verde + smoke visual humano.
5. **Lote E — Tests IT exhaustivos + `mvn verify` completo + limpieza deuda menor**. Cross-user, Alpaca down, cache stale-on-miss. Eliminar dead code `InvalidSideException.sideNotYetImplemented()` (deuda viva #16). HITO 5: `mvn verify` verde con ~310 tests.
6. **Lote F — Cierre**. APRENDIZAJES.md sección "Día 9" + AGENTS.md handoff + commit message preparado. HITO 6: commit + push + PR (humano).

**Pre-requisitos verificados (2026-05-24):**

- HU-F16+F21 mergeada en `main` (commit `1074775`, PR #8). `MarketDataOrchestrator` + `AllowedTickers.byMarket()` + `PortfolioService.getBalance/getPositions` + `OrderRepository.findByUserIdOrderBySubmittedAtDesc` listos.
- Branch `feat/HU-F18-F17-dashboard-historial` creada desde `main` actualizado.
- Redis 7 en docker-compose corriendo (puerto 6379). Healthcheck `redis-cli ping` configurado. Env vars `SPRING_DATA_REDIS_HOST=redis`, `SPRING_DATA_REDIS_PORT=6379` ya inyectadas vía `docker-compose.yml`.
- `spring-boot-starter-data-redis` ya en `backend/pom.xml:58` (sin agregar dependencia).
- `recharts 2.13.0` ya en `frontend/package.json:26` (sin agregar dependencia).
- `AllowedTickers.byMarket()` retorna `Map<Market, List<String>>` con orden NYSE → NASDAQ → LSE → TSE → ASX. 25 tickers totales.
- `MarketDataOrchestrator.fetchPrices(Collection<String>)` ya implementado con fan-out paralelo cap 1.5s/ticker — reusado tal cual.
- `MarketDataAdapter.getLatestPrice(ticker)` ya retorna mid-price `(ask+bid)/2`. Mismo `RestClient` (`@Qualifier("alpacaDataRestClient")`) se reusa para `getIntradayBars`.

---

## 2. Decisiones técnicas

### 2.1 Decisiones cerradas pre-implementación (D1–D16)

| # | Decisión | Resolución | Justificación |
|---|---|---|---|
| **D1** | **D-CACHE-LOCATION**: ¿interponer cache en `MarketDataOrchestrator` o crear wrapper? | Wrapper `CachedMarketDataAdapter` (Decorator pattern) que envuelve `MarketDataAdapter`. Inyectado con `@Primary` o `@Qualifier("cachedMarketDataAdapter")` y consumido por `DashboardService` (NO por `MarketDataOrchestrator` para no tocar F16+F21). | Cero impacto en `/portfolio/positions` (F16+F21 mantiene su orchestrator sin cache — su request rate es mucho menor, no necesita cache). El cache solo aplica al alto-volumen del polling 30s de F18. Separation of concerns. |
| **D2** | **D-CACHE-IMPL**: `@Cacheable` declarativo vs `RedisTemplate` manual | `RedisTemplate<String, String>` manual. BigDecimal serializado como string (consistent con el resto de DTOs). Hidden detrás de `CachedMarketDataAdapter`. | `@Cacheable` no permite handling fino de exceptions (necesario para "no cachear nulls/MarketDataUnavailable"). Manual da control total + logs DEBUG por hit/miss. Pequeño boilerplate aceptable. |
| **D3** | **D-CACHE-KEY-FORMAT**: prefijo y namespace | `market-data:price:{ticker}` (lowercase prefix). Ej: `market-data:price:AAPL`. | Simple, legible en `redis-cli KEYS market-data:*`. Si en futuro hay otros caches (bars, fundamentals), comparten prefix `market-data:`. No usamos prefix `bloomtrade:` porque Redis es del backend exclusivo — no hay tenancy multi-app en MVP. |
| **D4** | **D-CACHE-STALE-ON-ERROR**: ¿devolver valor expirado como fallback si Alpaca cae? | **No en V1.** Cache miss → si Alpaca falla, retornar null (igual que F16). | Complejidad sin valor MVP. Implementarlo requiere TTL "soft expiration" + tracking aparte del TTL nativo Redis. Si Alpaca cae sostenidamente, el banner amarillo cumple su rol UX. Deuda registrada §2.5. |
| **D5** | **D-SPARKLINE-EMBEDDING**: ¿embeber sparklines en `/snapshot` o endpoint separado? | **Embeber en `/snapshot`.** El response incluye `TickerDashboardDto.sparkline: BigDecimal[]` con los closes de hoy. NO se expone `/api/v1/market/intraday/{ticker}` como endpoint público en V1 (queda como método interno del adapter). | Evita 25 round-trips paralelos extra desde el frontend. Payload total estimado ~30kB (25 × 50 bars × 10 chars). Aceptable sobre HTTP/2. Si el response crece, V2 lo separa con flag `?includeSparklines=false`. |
| **D6** | **D-SPARKLINE-COST**: rate-limit Alpaca para bars | Alpaca Market Data (paper account) tiene rate limit 200 requests/min según docs públicas (IEX feed). Polling 30s × 25 spot + 25 bars = 50 calls/min/usuario. **Cabe sin contention.** Si en runtime detectamos 429: activar D-SPARKLINE-CACHE (TTL 5min) como hot-fix. | Cálculo: 25 × 2 (spot+bars) calls per refresh / 30s = 100 calls/min. Si el cache de precios funciona y solo 50% son cache miss → ~50-75 calls/min sostenidos. Margen del 30% bajo límite. Aceptable. |
| **D7** | **D-SPARKLINE-CACHE**: ¿cachear bars en V1? | **No en V1.** Cada poll re-fetches todos los 25 bars. | Tradeoff: simplicidad implementation V1. Si D6 falla (rate-limit golpea), agregar V2 con key `market-data:bars:{ticker}:{date}` TTL 300s. Deuda registrada §2.5. |
| **D8** | **D-DASHBOARD-MODULE**: ¿módulo `dashboard/` separado o expandir `portfolio/`? | **Módulo `dashboard/` separado.** `.gitkeep` ya existe en `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/`. Crear `dashboard.web.DashboardController` + `dashboard.service.DashboardService` + `dashboard.dto.*`. | ARCHITECTURE.md §3 lista Dashboard como módulo aparte. Coherencia. Dashboard depende de Portfolio (vía `PortfolioService.getAccountEquity`) + Integration (vía `CachedMarketDataAdapter`) — acoplamiento de DAG limpio. |
| **D9** | **D-EQUITY-CALCULATION-PLACE**: `DashboardService` o `PortfolioService` | `PortfolioService.getAccountEquity(UUID userId, Map<String, BigDecimal> prices): AccountEquityDto`. El `DashboardService` orquesta: obtiene `prices` del cache/adapter y llama a `PortfolioService.getAccountEquity(userId, prices)`. | Coherencia con `getBalance` y `getPositions` (todos read-only del módulo Portfolio). `DashboardService` se mantiene como orquestador puro. PortfolioService tiene visibilidad total a balance + positions. |
| **D10** | **D-ORDER-FILTERS-IMPL**: Specification vs derived methods | **Specification + `JpaSpecificationExecutor<Order>`.** `OrderRepository` extiende `JpaSpecificationExecutor<Order>`. `OrderSpecifications` factory estática con `byUser(UUID)`, `byTicker(String)`, `bySide(OrderSide)` que retornan `Specification<Order>` componibles con `.and(...)`. | Flexible para futuras adiciones (status, date range). Sin proliferación de métodos derivados. Spring Data lo soporta nativamente. |
| **D11** | **D-EMPTY-SPARKLINE**: render en frontend si `sparkline: []` | Render `<div className="text-slate-400 text-center">—</div>` con `width: 100px` (misma anchura que el LineChart) para no romper layout grid. | Visual feedback claro. Sin LineChart vacío (recharts renderiza extraño con data=[]). |
| **D12** | **D-FRONTEND-LAYOUT-DASHBOARD**: grid de los 25 tickers | Desktop (>1024px): 5 columnas (1 por mercado side-by-side), cada columna con su header de mercado arriba + 5 cards de ticker. Tablet (768–1024px): 2 columnas (mercados stacked en pairs). Mobile (<768px): 1 columna, mercados stacked vertically con todos sus tickers. | Tailwind responsive utilities (`grid grid-cols-1 md:grid-cols-2 xl:grid-cols-5`). Mantiene a la vez la jerarquía visual mercado→ticker y el responsive. |
| **D13** | **D-DISPLAY-SCALE**: precisión visual de BigDecimal | TODOS los stringificados en DTOs con `setScale(2, RoundingMode.HALF_UP)`: `currentPrice`, `openPrice`, `dayChangePct`, `equity.*`, `executionTotal`, etc. Cálculos intermedios mantienen scale=4 para precisión. Sparkline values también scale=2. | Consistente con F09/F10/F16+F21 D10. |
| **D14** | **D-MAPPER-FRAMEWORK**: MapStruct o manual | Mappers manuales en `DashboardMapper` y `OrderHistoryMapper` (componentes Spring). | Consistente con todas las HUs previas. Cálculos compuestos (dayChangePct, equity, pnl) más legibles imperativos. |
| **D15** | **D-REDIS-TEST-PROFILE**: cómo testear contra Redis | Tests IT usan el **Redis del docker-compose** (puerto 6379 local) — mismo patrón que `bloomtrade_test` Postgres (HU-F01 D16). NO usar embedded Redis library (deprecada para Spring Boot 3). Limpieza por test: `@AfterEach redisTemplate.delete(redisTemplate.keys("market-data:*"))`. | Consistencia con el setup de tests existente. Postgres test corre en el mismo Redis local. Tradeoff: dependencia de Docker corriendo al ejecutar IT (ya es así para Postgres). |
| **D16** | **D-CACHE-NULLS**: política sobre cache miss con error | Si `MarketDataAdapter.getLatestPrice(ticker)` lanza `MarketDataUnavailableException`, `CachedMarketDataAdapter` propaga la excepción **sin** poblar Redis con null. El `MarketDataOrchestrator` (que llama al wrapper) ya captura la excepción y devuelve null para ese ticker. | Cachear nulls amplificaría el problema: si Alpaca cae 5s y cacheamos null, el usuario ve "no disponible" 30s aún cuando Alpaca se recupere. Mejor: re-intentar cada request hasta que un valor real entre al cache. |

### 2.2 Decisiones operativas adicionales

| # | Decisión | Resolución |
|---|---|---|
| **D17** | **D-OPENPRICE-SOURCE**: ¿de dónde sale `openPrice` para `dayChangePct`? | `openPrice` = close de la **primera barra** intradía devuelta por Alpaca bars endpoint (cronológicamente más antigua). Si bars retorna 0 barras, openPrice=null y dayChangePct=null. **NO** usar Alpaca quote `prevClose` u otra fuente (evita doble round-trip). |
| **D18** | **D-BARS-PARAMS**: parámetros del endpoint Alpaca bars | `GET /v2/stocks/{symbol}/bars?timeframe=15Min&start=YYYY-MM-DDT00:00:00Z&end={now}&limit=50&adjustment=raw&feed=iex`. `start` = inicio del día UTC actual (00:00Z). 24h × 60min / 15min = 96 bars máximo. `limit=50` cubre desde apertura NYSE (13:30Z) hasta cualquier hora razonable. |
| **D19** | **D-CACHE-TTL-IMPL**: cómo setear TTL en Redis | `redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(30))`. Single atomic SET con TTL. |
| **D20** | **D-MARKET-GROUPING**: cómo agrupar tickers por mercado en backend | `DashboardService` obtiene `Map<Market, List<String>>` de `AllowedTickers.byMarket()` y itera. Para cada market, mapea sus 5 tickers a `TickerDashboardDto[]` y los empaqueta en `MarketGroupDto`. Order preservation por `LinkedHashMap` ya en `AllowedTickers`. |
| **D21** | **D-PAGINATION-CAP**: cap de `size` en `/orders` | `@RequestParam(name="size", defaultValue="20") @Max(100) int size` con validation Bean Validation. Si `>100`, 400 con `INVALID_REQUEST_PARAMETER`. |
| **D22** | **D-SIDE-PARAM-PARSING**: cómo parsear `?side=BUY` enum | `@RequestParam(name="side", required=false) Optional<OrderSide> side` con `OrderSide` enum existente. Spring lo parsea case-sensitive por default. Si valor inválido (`?side=FOO`), Spring lanza `MethodArgumentTypeMismatchException` → `GlobalExceptionHandler` lo mapea a 400 (handler ya existe de F09). |
| **D23** | **D-AUTH-PRINCIPAL-TYPE**: tipo del `@AuthenticationPrincipal` | `@AuthenticationPrincipal AuthenticatedUser principal` (record), NO `User` entity JPA. Convención del proyecto registrada en memory `feedback_authenticatedprincipal_no_user.md`. Acceder al userId via `principal.userId()`. |
| **D24** | **D-NOROLLBACKFOR-TRANSACTIONAL**: cualquier @Transactional anidado | F18+F17 son 100% read-only. **NO se requiere noRollbackFor.** Pero si emerge un escenario con `@Transactional(readOnly=true)` que llama otro método con throw, aplicar el patrón establecido en F09 D27, F10 D18, F16+F21 D-emergente (registered en memory `feedback_norollbackfor_nested_transactional.md`). |

### 2.3 Riesgos identificados

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Alpaca rate-limit 429 (50+ calls/min/usuario) | Baja-Media | Medio (banner amarillo + sparklines vacíos) | Cache TTL 30s amortigua (D6). Si golpea: activar D-SPARKLINE-CACHE V2 con TTL 5min. |
| Redis cae durante request | Baja (healthcheck Docker) | Bajo (degrada a Alpaca directo) | `CachedMarketDataAdapter` captura `RedisConnectionFailureException`, log WARN, fallback a `marketDataAdapter.getLatestPrice` sin cache. Sigue funcionando, paga el costo en Alpaca. |
| Test IT cuelga porque Redis test data no se limpia entre tests | Media | Bajo (lentitud, no falso positivo) | `@AfterEach` con `keys("market-data:*") + delete()` en cada IT que toca cache. |
| Bars endpoint con timeframe inválido | Baja | Bajo (Alpaca devuelve 4xx) | Hardcoded `timeframe=15Min` (D18) — sin parametrización usuario. |
| 25 sparklines + 25 spot prices saturan el response | Baja | Bajo (UI degrada) | Mediciones reales en HITO 4: si payload >100kB, evaluar D-SPARKLINE-CACHE+lazy load. |
| Cross-user data leakage en `/orders` | Baja | Alto | `Specification.byUser(uid)` aplicado SIEMPRE (no opcional). Test IT explícito. |
| Performance: cold start cache miss completo | Baja-Media | Medio (primera carga ~3-5s) | NFR-PERF-DASHBOARD acepta p95 ≤3s en cache miss frío. Documentado al usuario. |
| `LinkedHashMap` de `AllowedTickers.byMarket()` muta entre versiones | Nula (Map.copyOf inmutable) | N/A | Verificado en código fuente. |
| `OrderSide` enum no encaja con parsing de Spring | Baja | Bajo | Spring soporta enum default. Test IT explícito (`?side=BUY` y `?side=FOO`). |
| Test perfil tiene cache contamination entre features | Baja | Bajo | Limpieza `market-data:*` en `@AfterEach` de IT que tocan cache. Tests de F16+F21 NO tocan cache (siguen usando MarketDataOrchestrator directo). |

### 2.4 Decisiones emergentes durante implementación

> Espacio reservado para D25+ que aparezcan durante implementación, siguiendo patrón F09 D23–D29 / F10 D17–D21 / F16+F21 D17–D18. Memory `feedback_decisiones_emergentes_patron.md` predice 5–7 decisiones emergentes por HU no-trivial. Verificar §2.4 antes de HITO 6.

| # | Decisión | Síntoma → Causa raíz → Resolución |
|---|---|---|
| **D25** | **`AllowedTickers.byMarket()` no preservaba orden de iteración** | **Síntoma**: `DashboardServiceTest#getSnapshot_groupsInCorrectOrder_NYSE_NASDAQ_LSE_TSE_ASX` falla — orden real `[NYSE, TSE, ASX, LSE, NASDAQ]` vs esperado `[NYSE, NASDAQ, LSE, TSE, ASX]`. **Causa raíz**: `Map.copyOf(map)` retorna un Map inmutable cuya iteración es "unspecified" según Java spec — preserva contenido pero NO orden del LinkedHashMap original. El profile de HU-F04+F20 que consume este catálogo no detectó la regresión porque solo usa `contains(ticker)` (membership check), nunca itera. F18 es la primera HU que itera por orden. **Resolución**: cambiar a `Collections.unmodifiableMap(LinkedHashMap)` que sí preserva orden. SPEC §2 C4 y plan D20 ya documentan la garantía de orden — el bug estaba en la implementación del catálogo, no en el spec. Sin bump SPEC. |
| **D26** | **`RedisConfig.stringRedisTemplate` chocaba con Spring Boot auto-config** | **Síntoma**: `Failed to load ApplicationContext` con `BeanDefinitionOverrideException: Invalid bean definition with name 'stringRedisTemplate'` en cualquier `@SpringBootTest` desde que se agregó `RedisConfig`. **Causa raíz**: Spring Boot `RedisAutoConfiguration` ya provee `stringRedisTemplate: StringRedisTemplate` (extends `RedisTemplate<String, String>`) cuando `spring-boot-starter-data-redis` está en classpath, con los serializers correctos. Mi `RedisConfig.stringRedisTemplate` duplicaba el bean con mismo name → Spring rechaza por default (no allow override). **Resolución**: borrar `RedisConfig.java` entero. `CachedMarketDataAdapter` inyecta `RedisTemplate<String, String> redisTemplate` por tipo; Spring resuelve al `StringRedisTemplate` auto-config (upcast natural). Cero líneas de config necesarias para Redis. Aprendizaje: confiar en auto-config Spring Boot antes de escribir RedisConfig manual. Plan D2 sobrestima la necesidad de control fino — para nuestro caso (TTL Duration, exception handling en el wrapper) el auto-config es suficiente. |
| **D27** | **`OrderSpecificationsTest` puro saltado — IT cubre los predicados** | **Síntoma**: el plan §3 Lote C T3.16–T3.19 propone test puro con mocks de `Root`/`CriteriaQuery`/`CriteriaBuilder` para cada predicado de `OrderSpecifications`. **Razón para saltarlo**: (a) mockear Criteria API requiere stubear ~5 niveles de interfaces internas Hibernate por test, frágil ante upgrades; (b) los 3 predicados (`byUser`/`byTicker`/`bySide`) son one-liners triviales (`cb.equal(root.get(field), value)`) sin lógica condicional; (c) `OrderHistoryControllerIT` (T3.24–T3.30) ejerce los predicados via HTTP real sobre BD real con datos seedeados — si un predicado mappea al campo equivocado, los IT lo detectan inmediato. **Resolución**: skip `OrderSpecificationsTest`. La cobertura efectiva viene del IT (6 tests). Si en el futuro `OrderSpecifications` gana lógica condicional (e.g., LIKE patterns, OR composiciones), agregar tests unit en ese momento. Decisión consistente con [[feedback-coverage-vs-velocidad]]: ROI alto, cobertura efectiva preservada. |

### 2.5 Deudas registradas (no bloqueantes)

- **D-SPARKLINE-CACHE V2**: si rate-limit golpea, agregar key `market-data:bars:{ticker}:{date}` TTL 5min.
- **D-CACHE-STALE-ON-ERROR V2**: implementar "stale fallback" si Alpaca cae sostenidamente. Requiere TTL soft + tracking aparte.
- **D-REDIS-HEALTH-BANNER**: si Redis cae, mostrar banner UX (no solo log). Deuda menor.
- **D-ORDERS-UI-FILTERS-POSTMVP**: F17 widget MVP no tiene UI de filtros. Página `/orders` dedicada queda post-MVP.
- **D-EQUITY-HISTORY-POSTMVP**: curva de equity diaria (chart equity vs tiempo). Requiere snapshot nocturno.
- **D-TOP-MOVERS-POSTMVP**: top 3 ganadores / perdedores. Descartado en C3 SPEC.
- **D-METRICS-CACHE-HIT-RATIO**: micrometer counters para hit/miss. Útil para dashboard ops post-MVP.

---

## 3. Lotes y archivos

### Lote A — Backend foundation: cache Redis + market data extensions (HITO 1: compile + unit tests del cache verdes)

**Archivos por crear:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/cache/RedisConfig.java` — `@Configuration` con `@Bean RedisTemplate<String, String>`. ConnectionFactory de Spring Boot auto-config. Serializer: `StringRedisSerializer` para key y value (BigDecimal stringificado).
- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/CachedMarketDataAdapter.java` — `@Component` decorator. Constructor inyecta `MarketDataAdapter marketDataAdapter` + `RedisTemplate<String, String> redisTemplate`. Métodos:
  - `BigDecimal getLatestPrice(String ticker)` — cache check → si hit, return; si miss, delegate al adapter + set Redis TTL 30s + return. Captura `RedisConnectionFailureException` → log WARN, delegate directo sin cache.
  - Constante `private static final Duration TTL = Duration.ofSeconds(30);` y `private static final String KEY_PREFIX = "market-data:price:";`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/dto/AlpacaBarsResponse.java` — record con `Map<String, List<AlpacaBar>> bars`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/dto/AlpacaBar.java` — record con `Instant t, BigDecimal o, h, l, c, Long v`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/dto/IntradayBar.java` — DTO interno (no Alpaca-specific) con `Instant timestamp, BigDecimal open, high, low, close, Long volume`.

**Archivos por modificar:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/MarketDataAdapter.java` — agregar método:
  ```java
  @Retry(name = "alpacaDataApi", fallbackMethod = "getIntradayBarsFallback")
  public List<IntradayBar> getIntradayBars(String ticker) { ... }
  ```
  Con fallback que lanza `MarketDataUnavailableException`. URI: `/v2/stocks/{symbol}/bars?timeframe=15Min&start={today00Z}&limit=50&adjustment=raw&feed=iex`.

**Tests:**

- `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/alpaca/CachedMarketDataAdapterTest.java` (≥6 tests, `MarketDataAdapter` mockeado, Redis embebido o mocked via `@MockBean RedisTemplate`):
  - `getLatestPrice_cacheHit_returnsCachedAndDoesNotCallAdapter`.
  - `getLatestPrice_cacheMiss_callsAdapterAndSetsCache`.
  - `getLatestPrice_adapterThrows_propagatesAndDoesNotCacheNull`.
  - `getLatestPrice_redisDown_fallsBackToAdapterDirectly`.
  - `getLatestPrice_ttlIs30Seconds_verifyArgCaptor`.
  - `getLatestPrice_keyFormat_isMarketDataPriceUppercase`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/alpaca/MarketDataAdapterBarsTest.java` (≥4 tests con `MockRestServiceServer`):
  - `getIntradayBars_happy_returnsParsedBars`.
  - `getIntradayBars_emptyBars_returnsEmptyList`.
  - `getIntradayBars_alpaca5xx_retries_then_throws`.
  - `getIntradayBars_alpaca404_throws_MarketDataUnavailable`.

### Lote B — Backend Dashboard module (HITO 2: unit tests + IT del /dashboard/snapshot)

**Archivos por crear:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/DashboardSnapshotResponse.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/MarketGroupDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/TickerDashboardDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/dto/AccountEquityDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/web/DashboardMapper.java` — manual. Métodos:
  - `toTickerDashboardDto(String ticker, BigDecimal currentPrice, List<IntradayBar> bars): TickerDashboardDto`. Calcula `openPrice` (D17), `dayChangePct`, `sparkline: List<String>`.
  - `toMarketGroupDto(Market market, List<TickerDashboardDto> items): MarketGroupDto`.
  - `toDashboardSnapshotResponse(List<MarketGroupDto> groups, AccountEquityDto equity, String marketDataAvailable, Instant fetchedAt): DashboardSnapshotResponse`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/service/DashboardService.java` — orquesta:
  1. `AllowedTickers.byMarket()` → Map<Market, List<String>>.
  2. Flat list de 25 tickers.
  3. Para cada ticker: prices vía fan-out (D-MARKET-DATA-FANOUT) sobre `CachedMarketDataAdapter` (NO sobre el `MarketDataOrchestrator` existente — D1).  Alternativa: reusar `MarketDataOrchestrator` pero inyectando `CachedMarketDataAdapter` en su lugar via `@Qualifier`. **Decisión sub: reusar `MarketDataOrchestrator` pero crear una segunda instancia que use el cached adapter.** Ver implementación abajo.
  4. Para cada ticker: bars vía fan-out paralelo (mismo executor que MarketDataOrchestrator).
  5. `portfolioService.getAccountEquity(userId, prices)`.
  6. Mapear todo a `DashboardSnapshotResponse`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/web/DashboardController.java` — 1 endpoint `GET /api/v1/dashboard/snapshot`. `@AuthenticationPrincipal AuthenticatedUser principal`. Delega a `DashboardService.getSnapshot(principal.userId())`.

**Refinamiento sub-decisión D1 (resolución concreta)**:

Para evitar duplicar la lógica de fan-out paralelo, **NO crear `MarketDataOrchestrator` separado**. En su lugar: crear `BarsOrchestrator` separado (porque devuelve `Map<String, List<IntradayBar>>`, no `Map<String, BigDecimal>`) y **usar `CachedMarketDataAdapter` en `DashboardService` directamente** para precios (sin orchestrator de cache — el cache es serial check + parallel fetch solo de los faltantes).

Implementación final de `DashboardService.getSnapshot`:
```java
public DashboardSnapshotResponse getSnapshot(UUID userId) {
    Map<Market, List<String>> byMarket = AllowedTickers.byMarket();
    List<String> allTickers = byMarket.values().stream().flatMap(List::stream).toList();
    
    // Prices: cache check + fan-out solo de los faltantes
    Map<String, BigDecimal> prices = cachedMarketDataAdapter.getLatestPrices(allTickers);
    
    // Bars: siempre fan-out (no cacheamos en V1)
    Map<String, List<IntradayBar>> barsMap = barsOrchestrator.fetchBars(allTickers);
    
    // Equity
    AccountEquityDto equity = portfolioService.getAccountEquity(userId, prices);
    
    // Mapping
    List<MarketGroupDto> groups = byMarket.entrySet().stream()
        .map(e -> dashboardMapper.toMarketGroupDto(e.getKey(), 
            e.getValue().stream()
                .map(t -> dashboardMapper.toTickerDashboardDto(t, prices.get(t), barsMap.get(t)))
                .toList()))
        .toList();
    
    String marketDataAvailable = computeAvailability(prices, barsMap);
    return dashboardMapper.toDashboardSnapshotResponse(groups, equity, marketDataAvailable, Instant.now());
}
```

Entonces `CachedMarketDataAdapter.getLatestPrices(Collection<String>)` (plural, batch) hace:
1. `multiGet` de Redis para los 25 keys.
2. Para los hits, popula el map.
3. Para los misses, fan-out paralelo (reusando el `marketDataExecutor` bean del Lote B F16) a `MarketDataAdapter.getLatestPrice` con cap 1.5s/ticker.
4. Set en Redis los exitosos (no los nulls).

Esto reusa la maquinaria de fan-out existente sin duplicar.

**Archivos por modificar:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/service/PortfolioService.java` — agregar método:
  ```java
  public AccountEquityDto getAccountEquity(UUID userId, Map<String, BigDecimal> prices) { ... }
  ```
  Calcula equity, positionsMarketValue, costBasisTotal, unrealizedPnL, unrealizedPnLPct. `null`-aware: si todos los prices son null para tickers en posición, positionsMarketValue=null, equity=null, etc. Devuelve `AccountEquityDto`.

- `backend/src/main/java/co/edu/unbosque/bloomtrade/integration/alpaca/CachedMarketDataAdapter.java` — agregar método batch:
  ```java
  public Map<String, BigDecimal> getLatestPrices(Collection<String> tickers) { ... }
  ```
  Lógica descrita arriba.

- `backend/src/main/java/co/edu/unbosque/bloomtrade/dashboard/service/BarsOrchestrator.java` (nuevo) — fan-out paralelo de `MarketDataAdapter.getIntradayBars` sobre `marketDataExecutor`. Mismo patrón que `MarketDataOrchestrator` pero retorna `Map<String, List<IntradayBar>>` con `null`-value para tickers fallidos.

**Tests:**

- `backend/src/test/java/co/edu/unbosque/bloomtrade/dashboard/service/DashboardServiceTest.java` (≥5 tests, todos mocks):
  - `getSnapshot_happy_returnsAllPopulated`.
  - `getSnapshot_pricesAllNull_marketDataAvailableFalse`.
  - `getSnapshot_pricesPartial_marketDataAvailablePartial`.
  - `getSnapshot_userHasNoPositions_equityIsBalanceOnly`.
  - `getSnapshot_emptyBarsForTicker_dayChangePctIsNull`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/portfolio/service/PortfolioServiceAccountEquityTest.java` (≥4 tests):
  - `getAccountEquity_happy_calculatesAllFields`.
  - `getAccountEquity_noPositions_equityEqualsBalance_pctIsNull`.
  - `getAccountEquity_pricesAllNull_marketValueAndPnLNull`.
  - `getAccountEquity_pricesPartial_marketValueExcludesNulls_pnLPartial`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/dashboard/web/DashboardMapperTest.java` (≥4 tests):
  - `toTickerDashboardDto_happy_calculatesDayChangePct`.
  - `toTickerDashboardDto_priceNull_allDerivedNull`.
  - `toTickerDashboardDto_emptyBars_openPriceNull`.
  - `toTickerDashboardDto_sparklineStringScale2`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/dashboard/DashboardControllerIT.java` (≥4 tests con MockMvc + WireMock + Redis real):
  - HU-F18-AC-01: happy snapshot cache miss.
  - HU-F18-AC-02: cache hit (segundo request) no invoca Alpaca para prices.
  - HU-F18-AC-03: Alpaca down (WireMock 503) → marketDataAvailable="false".
  - HU-F18-AC-04: usuario sin posiciones → equity solo balance.

### Lote C — Backend Order History module (HITO 3: unit tests + IT del /orders)

**Archivos por crear:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/dto/OrderHistoryResponse.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/dto/OrderHistoryDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/dto/PaginationDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/web/OrderHistoryMapper.java` — manual. `toOrderHistoryDto(Order)`, `toPaginationDto(Page<?>)`, `toOrderHistoryResponse(Page<Order>)`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/repository/OrderSpecifications.java` — factory estática:
  ```java
  public static Specification<Order> byUser(UUID userId) { ... }
  public static Specification<Order> byTicker(String ticker) { ... }
  public static Specification<Order> bySide(OrderSide side) { ... }
  ```
  Cada uno con builder lambda `(root, query, cb) -> cb.equal(root.get("..."), value)`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/service/OrderHistoryService.java` — método `list(UUID userId, Optional<String> ticker, Optional<OrderSide> side, Pageable pageable): Page<Order>`. Compone Specification dinámica.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/history/web/OrderHistoryController.java` — 1 endpoint `GET /api/v1/orders`. Query params: `Optional<String> ticker`, `Optional<OrderSide> side`, `@PageableDefault(size=20, sort="submittedAt", direction=DESC) Pageable pageable`. `@AuthenticationPrincipal AuthenticatedUser principal`. Cap size con `@Max(100)` (D21).

**Archivos por modificar:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/repository/OrderRepository.java` — agregar herencia:
  ```java
  public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {
      // ... existing methods ...
  }
  ```

**Tests:**

- `backend/src/test/java/co/edu/unbosque/bloomtrade/trading/history/service/OrderHistoryServiceTest.java` (≥4 tests con repository mockeado):
  - `list_noFilters_returnsAllOrderedDesc`.
  - `list_filterTicker_appliesSpec`.
  - `list_filterSide_appliesSpec`.
  - `list_filterBoth_appliesCombinedSpec`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/trading/history/repository/OrderSpecificationsTest.java` (≥3 tests):
  - `byUser_buildsEqualPredicate`.
  - `byTicker_buildsEqualPredicate`.
  - `bySide_buildsEqualPredicate`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/trading/history/web/OrderHistoryMapperTest.java` (≥3 tests):
  - `toOrderHistoryDto_executedOrder_allFieldsPopulated`.
  - `toOrderHistoryDto_pendingOrder_executionFieldsNull`.
  - `toOrderHistoryDto_failedOrder_failureReasonPopulated`.
- `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/trading/OrderHistoryControllerIT.java` (≥5 tests):
  - HU-F17-AC-01: paginado sin filtros.
  - HU-F17-AC-02: filtro ticker.
  - HU-F17-AC-04: filtros combinados ticker+side.
  - HU-F17-AC-05: cross-user.
  - HU-F17-AC-06: side=FOO → 400.

### Lote D — Frontend Dashboard page (HITO 4: npm run build verde + smoke visual)

**Archivos por crear:**

- `frontend/src/features/dashboard/DashboardPage.tsx`
- `frontend/src/features/dashboard/components/EquityCard.tsx`
- `frontend/src/features/dashboard/components/TickerGrid.tsx`
- `frontend/src/features/dashboard/components/TickerRow.tsx`
- `frontend/src/features/dashboard/components/Sparkline.tsx` — recharts `<LineChart width={100} height={30}>`. Props: `data: number[]`, `positive: boolean | null`. Empty: render `—`.
- `frontend/src/features/dashboard/components/RecentOrdersWidget.tsx`
- `frontend/src/features/dashboard/components/MarketDataBanner.tsx` — reuso conceptual del de F16 (mismo patrón 3 estados).
- `frontend/src/features/dashboard/hooks/useDashboardSnapshot.ts` — React Query `refetchInterval: 30000, refetchIntervalInBackground: true`.
- `frontend/src/features/dashboard/hooks/useOrdersRecent.ts` — React Query `staleTime: 30s`. Query key `['orders', 'recent']`. Sin refetchInterval explícito (cuando F18 polling invalida `['dashboard']` también invalida `['orders', 'recent']` via `queryClient.invalidateQueries(['orders', 'recent'])` en el handler del botón refresh + on focus refetch). Alternativa simpler: agregar `refetchInterval: 30000` igual que dashboard. **Decisión final: agregar refetchInterval=30000 al hook de orders también** (consistencia + ahorra invalidate manual).

**Archivos por modificar:**

- `frontend/src/features/dashboard/api/dashboardApi.ts` (nuevo) — `fetchDashboardSnapshot(): Promise<DashboardSnapshotResponse>`.
- `frontend/src/features/dashboard/api/ordersApi.ts` (nuevo) — `fetchOrders({page, size, ticker, side}): Promise<OrderHistoryResponse>`.
- `frontend/src/types/api.ts` — agregar `DashboardSnapshotResponse`, `MarketGroupDto`, `TickerDashboardDto`, `AccountEquityDto`, `OrderHistoryResponse`, `OrderHistoryDto`, `PaginationDto`.
- `frontend/src/App.tsx` — ruta `/dashboard` envuelta en `<ProtectedRoute>`. **Decisión sub**: ¿ruta `/` redirige a `/dashboard`? Confirmar con humano en HITO 4 — propuesta: sí, dashboard como landing autenticada (post-MVP cualquier ajuste).
- `frontend/src/components/AppHeader.tsx` — link "Dashboard" primero, antes de "Trade".
- `frontend/src/lib/messages.es.ts` — claves `dashboard.title`, `dashboard.equityHeadline`, `dashboard.pnlPositive`, `dashboard.pnlNegative`, `dashboard.pnlNeutral`, `dashboard.equityWithoutPrices`, `dashboard.refreshAria`, `dashboard.marketDataBanner.false`, `dashboard.marketDataBanner.partial`, `dashboard.ticker.priceUnavailable`, `dashboard.orders.title`, `dashboard.orders.empty`, `dashboard.orders.emptyCta`, `dashboard.orders.tableHeaders.*`, `dashboard.orders.sideBuy`, `dashboard.orders.sideSell`, `dashboard.orders.status.*`.

**Sin tests frontend** ([[feedback-coverage-vs-velocidad]], patrón F09/F10/F16+F21).

### Lote E — Tests IT exhaustivos + `mvn verify` completo + limpieza deuda (HITO 5: mvn verify verde)

**Tests adicionales:**

- `DashboardControllerIT`:
  - HU-F18-AC-05: polling intervalado (skip — es comportamiento de cliente, no testeable server-side).
  - HU-F18-AC-06: cross-user equity.
  - HU-F18-AC-07: sin JWT → 401 (o 403 con comentario D17 F16+F21).
  - Cache TTL respetado: test con manipulación de tiempo (sleep 31s) o con `redisTemplate.expire(key, Duration.ZERO)` para forzar expiración → segundo request va a Alpaca.
  - Redis down simulation: test que skipea si infra no permite, o `@MockBean RedisTemplate` que lanza `RedisConnectionFailureException` → request sigue OK con Alpaca directo.
- `OrderHistoryControllerIT`:
  - HU-F17-AC-03: filtro side solo.
  - HU-F17-AC-07: size=200 → 400 (D21).
  - HU-F17-AC-08: aún no aplica (es frontend).

**Limpieza deuda viva del handoff F16+F21:**

- **Deuda #16**: borrar `InvalidSideException.sideNotYetImplemented()` dead code post HU-F10. Verificar con `grep -r sideNotYetImplemented backend/` que no haya callers.
- **Deuda #15** (opcional, si tiempo): extender `useTickerOptions` para filter `TickerDropdown` por posiciones cuando side=SELL — F18 hace `usePortfolioPositions` igual, ya hay datos disponibles. Cierre si Lote E pasa rápido.

### Lote F — Cierre (HITO 6: commit + push + PR)

- `APRENDIZAJES.md` — sección "Día 9 — HU-F18+F17" con 6–9 reflexiones (sobre cache Redis primer uso, sobre sparklines + recharts, sobre Specification, sobre F17 promovido, sobre cierre de deudas vivas).
- `AGENTS.md` — actualizar bloque "Trabajo activo" + nueva sección "Cómo continuar (post HU-F18+F17 → Día 10 estabilización)".
- `plan.md` §2.4 — agregar D25+ con decisiones emergentes si surgieron (predicción de memory: 5-7 esperadas).
- Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f18-f17.txt` (ruta completa literal, P6).

---

## 4. Tabla de HITOs validables

| HITO | Lote | Cómo se verifica | Criterio de éxito |
|---|---|---|---|
| 1 | A | `./mvnw compile` + `./mvnw test -Dtest='CachedMarketDataAdapterTest,MarketDataAdapterBarsTest'` | Compila + ≥10 tests verdes |
| 2 | B | `./mvnw test -Dtest='DashboardServiceTest,DashboardMapperTest,PortfolioServiceAccountEquityTest'` + `./mvnw verify -Dtest=DashboardControllerIT` | ≥13 unit + 4 IT verdes |
| 3 | C | `./mvnw test -Dtest='OrderHistoryServiceTest,OrderSpecificationsTest,OrderHistoryMapperTest'` + `./mvnw verify -Dtest=OrderHistoryControllerIT` | ≥10 unit + 5 IT verdes |
| 4 | D | `cd frontend && npm run build` + humano abre `/dashboard` en browser | Build verde + smoke visual OK |
| 5 | E | `./mvnw verify` (suite completa) + `grep -r sideNotYetImplemented backend/` retorna 0 matches | Todos los tests del proyecto verdes (~310 esperados, 286 actuales + ~24 nuevos) + dead code eliminado |
| 6 | F | `git status` clean + commit firmado por humano | Working tree clean tras commit |

---

## 5. Estimación de tiempo

| Lote | Estimado | Notas |
|---|---|---|
| A | 2 h | RedisConfig + CachedMarketDataAdapter (decorator con TTL) + getIntradayBars + 10 unit tests con curva mental Redis + MockRestServiceServer |
| B | 3 h | DashboardService + BarsOrchestrator (fan-out paralelo análogo a F16) + Mapper con cálculos compuestos + 13 unit + 4 IT con WireMock + Redis real |
| C | 2 h | Specifications + Service + Controller + Mapper + 10 unit + 5 IT con datos seeded en `app.orders` |
| D | 2.5 h | Frontend desde cero (DashboardPage + 6 components + Sparkline recharts + 2 hooks polling 30s + types + ruta + link + 20+ copys) |
| E | 1.5 h | 4 IT adicionales (cache TTL, Redis down) + cleanup deuda #16 + deuda #15 si tiempo |
| F | 0.5 h | Docs + handoff + commit message |
| **Total** | **~11.5 h** | Compatible con 1 jornada larga Día 9 o splitable a 2 jornadas |

---

## 6. Dependencias entre lotes

```
A ──► B ──► C ──► D ──► E ──► F
       │
       └─► (B depende de A: CachedMarketDataAdapter + getIntradayBars)
       
C es independiente de B (otro endpoint completamente), puede paralelizarse con B si tiempo.
D depende de B+C (consume ambos endpoints).
E es adicional a B+C.
F al final.
```

- **Sequencializo por simplicidad** mental (un humano review por vez). Si la jornada va adelantada, paralelizar B y C es viable.
- **D no puede arrancar antes de B+C** porque consume los contratos API.
- **E puede empezar en paralelo con D** si los 9 IT base ya están verdes.

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-24 | Versión inicial | Plan derivado de SPEC v1.0. 16 decisiones técnicas principales D1-D16 + 8 operativas D17-D24 cerradas pre-implementación. 6 lotes A–F con HITOs validables. Estimado 11.5h compatible con Día 9 del ROADMAP (jornada larga). Sin decisiones emergentes todavía — §2.4 vacío al inicio (predicción memory: 5-7 esperadas). Aprovecha reuso máximo: `MarketDataOrchestrator`, `AllowedTickers`, `OrderRepository`, `marketDataExecutor` bean, `RestClient` Alpaca data. Cubre deuda viva #19 (cache Redis) + #16 (dead code SELL) + opcionalmente #15 (filtro SELL dropdown). |

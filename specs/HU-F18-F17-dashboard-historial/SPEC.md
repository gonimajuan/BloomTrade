# SPEC.md — Dashboard de acciones + Historial de órdenes (bundle HU-F18 + HU-F17)

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F18 (BT-22 en Jira) + HU-F17 (BT-21 en Jira, **promovido** desde post-MVP) |
| Sprint | 2 |
| Prioridad MoSCoW | Must (HU-F18) + Should (HU-F17 promovido) |
| Estado | Draft |
| Autor | Juan |
| Fecha creación | 2026-05-24 |
| Última actualización | 2026-05-24 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 9 |
| HUs predecesoras | HU-F09 (compra Market, PR #6) + HU-F10 (venta Market, PR #7) + HU-F16+F21 (portafolio+saldo, PR #8) — todas mergeadas en `main`. El `MarketDataOrchestrator` (fan-out paralelo con cap 1.5s), el catálogo `AllowedTickers.byMarket()` y el `OrderRepository.findByUserIdOrderBySubmittedAtDesc(...)` ya existen completos y se reusan masivamente. |
| Modalidad | **SPEC bundle**: HU-F18 (dashboard 25 activos con precios + P&L de cuenta + sparklines) + HU-F17 (historial de órdenes) **comparten la misma página `/dashboard`** — F17 se manifiesta como widget "Últimas 10 órdenes" dentro del dashboard, y como endpoint backend reusable. F17 normalmente está en backlog post-MVP (ROADMAP §8.1) pero se **promueve al MVP** por la regla §3.4 ("HU-F23/HU-F17 Consultar historial de órdenes" como #3 en orden de promoción) — desarrollo va a tiempo tras cerrar F16+F21 el Día 8 sin desbordar. Promoción explícita registrada acá. |
| Promoción ROADMAP | HU-F17 se promueve al MVP siguiendo §3.4: tras cerrar Sprint 1 a tiempo y entregar 3 bundles del Sprint 2 (F09, F10, F16+F21) en 3 días sin acumular deuda funcional bloqueante. La promoción se justifica por reuso máximo: `OrderRepository` y el modelo `Order` ya existen completos; solo se agrega un endpoint nuevo con filtros + paginación + widget frontend embebido. ROI alto para una sola sesión adicional. |

---

## 2. Historia(s) de usuario

### HU-F18 — Dashboard de acciones

**Como** inversionista, **quiero** ver un dashboard con los 25 activos disponibles para operar (precio actual, variación intradía, sparkline visual) y un resumen del estado financiero de mi cuenta (equity total + P&L no realizado), **para** detectar oportunidades de trading de un vistazo y entender cómo mi portafolio evolucionó respecto a mi costo de adquisición.

### HU-F17 — Consultar historial de órdenes

**Como** inversionista, **quiero** consultar todas las órdenes que he colocado (ejecutadas, en cola, fallidas, rechazadas) con filtros mínimos por ticker y por lado (BUY/SELL) y paginación, **para** auditar mis operaciones pasadas y verificar el estado de órdenes recientes sin tener que abrir Kibana o consultar la BD directamente.

### Resumen del alcance del bundle

Esta spec cubre **tres endpoints HTTP nuevos** sobre dos módulos + **una página frontend `/dashboard`** que los compone:

1. **`GET /api/v1/dashboard/snapshot`** (HU-F18) — Devuelve un snapshot consolidado: (a) precio actual + variación intradía + 25 sparklines de los 25 activos del catálogo `AllowedTickers`, agrupados por mercado; (b) equity total del usuario (balance + valor de mercado de posiciones) con P&L no realizado en USD y %. Mark-to-market amortiguado por `PriceCache` Redis TTL 30s.
2. **`GET /api/v1/market/intraday/{ticker}`** (HU-F18, sub-endpoint) — Devuelve serie temporal de barras intradía del ticker (timeframe 15Min, desde apertura de mercado hoy) para renderizar sparklines individuales. **Decisión de embebido vs endpoint separado se diferiere a plan** — la opción favorita es incluir las series dentro del response de `/snapshot` para evitar 25 round-trips adicionales.
3. **`GET /api/v1/orders`** (HU-F17) — Devuelve listado paginado de órdenes del usuario autenticado con filtros opcionales `ticker` y `side`. Sort por `submitted_at DESC` fijo (no parametrizable en MVP).
4. **Frontend `/dashboard`** — Página única con cuatro secciones: (a) card de equity total + P&L no realizado arriba; (b) grid agrupado por mercado con las 25 filas (ticker, precio, variación %, mini-sparkline); (c) widget "Últimas 10 órdenes" colapsable abajo (consume `/api/v1/orders?page=0&size=10`); (d) banner amarillo si market data en degradación parcial/total.

### Decisiones cerradas pre-redacción (no se discuten en el plan)

- **C1** — Slug del bundle: `HU-F18-F17-dashboard-historial`.
- **C2** — Bundle justificado por **cohesión UI** (`/dashboard` compartido) y por la **promoción explícita de F17** al MVP siguiendo ROADMAP §3.4. Sin bundle: F17 quedaría como página `/orders` separada con duplicación de andamio (AppHeader link, route, hook, controller, exception handlers ya conocidos). Validado con humano 2026-05-24.
- **C3** — **Widgets de F18 incluidos**: (a) tabla de 25 activos con ticker, precio actual, variación intradía %, mini-sparkline; (b) card equity total + P&L no realizado. **Widgets descartados explícitamente**: top movers (top 3 ganadores/perdedores), watchlists personalizadas. Razón: minimizar superficie del MVP y mantener el dashboard como vista panorámica, no analítica avanzada (post-MVP HU-F19 alertas / HU-F23 watchlist).
- **C4** — **Lista de 25 activos = `AllowedTickers.byMarket()` en backend.** Confirmado en `co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers` línea 49: ya devuelve `Map<Market, List<String>>` con orden de inserción (oeste a este por timezone). El frontend NO manda lista en request — el backend la determina. Cualquier cambio futuro del catálogo es server-side, sin redeploy frontend.
- **C5** — **Refresh strategy de F18**: polling intervalado cada **30s** vía React Query `refetchInterval`, **+ botón manual** de refresh, **sin pause cuando la pestaña queda oculta** (`refetchIntervalInBackground: true`). Razón: simplicidad de implementación; MVP single-user. Trade-off de costo Alpaca aceptado: ~120 calls/hora por usuario, manejable con cache Redis TTL 30s (a partir del 2º request idéntico en 30s sale del cache, no de Alpaca).
- **C6** — **Cache Redis `PriceCache` con TTL 30s.** Cubre la deuda viva #19 del handoff F16+F21. El cache se chequea ANTES de invocar `MarketDataAdapter.getLatestPrice(ticker)`. Cache miss → llamada a Alpaca → set en Redis con TTL 30s → return. Cache hit → return directo. Resiliencia: si Alpaca falla, el cache stale NO se sirve (TTL ya expiró por definición de cache miss); el frontend recibe `currentPrice=null` y banner amarillo, igual que en F16. **Sparklines NO se cachean en V1** (impacto: cada polling re-pega las 25 series; aceptable si Alpaca free tier lo permite — verificar en plan D-SPARKLINE-COST). Si rate-limit golpea, V2 cachea bars con TTL más largo (~5min) por ser series intradía menos volátiles.
- **C7** — **P&L card = equity + no realizado vs avgCost.** Equity actual = `balance + Σ(qty × currentPrice)`. P&L no realizado = `Σ(qty × currentPrice) − Σ(qty × avgBuyPrice)`. P&L % = `pnL / costBasisTotal × 100`. **Descartado** comparación contra "equity al cierre anterior" (requeriría snapshot diario y job nocturno fuera del MVP).
- **C8** — **F17 widget embebido, NO página dedicada.** En `/dashboard` aparece sección colapsable "Últimas 10 órdenes" con tabla compacta (ticker, side, qty, status, fecha). Sin filtros UI en el widget. **El endpoint backend SÍ soporta filtros** (ticker + side) y **paginación** para reuso futuro (HU-F23 watchlist post-MVP podría consumirlo, y un eventual `/orders` page completa). Trade-off aceptado: el usuario no puede filtrar el historial desde UI en MVP — si necesita más, consulta Kibana o psql. Decisión registrada como deuda menor.
- **C9** — **Reuso del `MarketDataOrchestrator`** sin modificaciones para fan-out de las 25 llamadas a `getLatestPrice`. El cap 1.5s/ticker + executor dedicado ya es óptimo. Solo se interpone el cache Redis entre `MarketDataOrchestrator` y `MarketDataAdapter`. Decisión de dónde interponer el cache (en el orchestrator o como wrapper sobre `MarketDataAdapter`) se diferiere a plan D-CACHE-LOCATION.
- **C10** — **Sparklines: endpoint Alpaca `/v2/stocks/{symbol}/bars?timeframe=15Min&start=YYYY-MM-DDT13:30:00Z`** (apertura NYSE en UTC). Devuelve series de barras OHLCV. Frontend renderiza solo el `close` con recharts `<LineChart>` 100×30px en cada fila. **MVP solo intradía del día actual.** Si el mercado está cerrado en hora del request, se trae el last trading day disponible (Alpaca lo maneja transparente).
- **C11** — **F17 endpoint backend**: `GET /api/v1/orders?ticker={t}&side={BUY|SELL}&page=0&size=10`. Spring Data `Pageable` para paginación. Sort fijo `submitted_at DESC`. Filtros opcionales aplicados vía `Specification` JPA o método derivado con `Optional`. **Decisión de implementación** (Specification vs derived methods múltiples) diferida a plan D-ORDER-FILTERS-IMPL.
- **C12** — **NO se auditan reads.** Consistente con F16+F21 §9.1: ni `/dashboard/snapshot` ni `/orders` ni `/market/intraday/*` emiten audit events. Si compliance lo exige post-MVP, deuda.
- **C13** — **Sin migración Flyway.** F17 consulta `app.orders` (existente V5). F18 no toca BD.

### Decisiones diferidas a `plan.md`

- **D-CACHE-LOCATION** — ¿interponer cache en `MarketDataOrchestrator.fetchPrices` (cambio en F16+F21 código, riesgo de regresión en `/portfolio/positions`) o crear un nuevo componente `CachedMarketDataAdapter` que envuelve `MarketDataAdapter` y se inyecta en su lugar (cleaner, sin tocar orchestrator)? Inclinación: wrapper.
- **D-CACHE-IMPL** — `RedisTemplate<String, BigDecimal>` manual vs `@Cacheable` Spring + `RedisCacheManager` declarativo. Trade-off: `@Cacheable` más limpio pero menos control fino (TTL puro, sin handling de exceptions inline). `RedisTemplate` da control total.
- **D-CACHE-KEY-FORMAT** — `market-data:price:{ticker}` vs `bloomtrade:cache:price:{ticker}`. Convención de naming.
- **D-CACHE-STALE-ON-ERROR** — si `MarketDataAdapter` lanza `MarketDataUnavailableException` pero hay un valor en Redis con TTL **expirado** (Redis lo borra) o **a punto de expirar**, ¿devolver el stale como fallback? V1 MVP: no, complejidad sin valor. Pero registrar como deuda futura.
- **D-SPARKLINE-EMBEDDING** — ¿embeber sparklines en `/dashboard/snapshot` (response gordo con 25 series) vs endpoint separado `/api/v1/market/intraday/{ticker}` que el frontend llama N veces? Inclinación: embebido para evitar 25 round-trips, asume payload aceptable (~25 × 50 barras × 4 floats = ~5kB).
- **D-SPARKLINE-COST** — Si Alpaca free tier rate-limita en 25 calls a `/bars` cada 30s + 25 a `/quotes/latest` cada 30s = ~100 calls/min, ¿es sostenible? Si no, decidir entre (a) reducir polling de sparklines (e.g., refresh cada 60s para series), (b) cache de bars con TTL más largo (5min), (c) reducir tickers a un subset.
- **D-SPARKLINE-CACHE** — ¿cachear las series de bars también en V1 con TTL más largo? Si D-SPARKLINE-COST exige sí, agregar `bars:{ticker}:{date}` keys.
- **D-DASHBOARD-MODULE** — Hay `backend/.../bloomtrade/dashboard/.gitkeep` existente sugiriendo que el módulo `dashboard/` está reservado. ¿Crear `DashboardController` + `DashboardService` ahí, o expandir `PortfolioController`? Inclinación fuerte: `dashboard/` separado (ARCHITECTURE.md menciona dashboard como cliente del trading/portfolio).
- **D-EQUITY-CALCULATION-PLACE** — ¿En `DashboardService` o reutilizar `PortfolioService` con un método nuevo `getAccountEquity(userId, prices: Map<String, BigDecimal>): EquityDto`? Inclinación: `PortfolioService.getAccountEquity(...)` por coherencia con `getBalance` + `getPositions`.
- **D-ORDER-FILTERS-IMPL** — `Specification<Order>` con builder dinámico vs métodos derivados múltiples (`findByUserIdAndTicker`, `findByUserIdAndSide`, `findByUserIdAndTickerAndSide`). Trade-off: Specification más flexible (escala a más filtros futuro) pero más boilerplate; derived methods más simple pero 4 métodos si ambos filtros se vuelven obligatorios. Inclinación: Specification.
- **D-EMPTY-SPARKLINE** — Si Alpaca devuelve 0 bars (ticker sin trades intradía hoy, o weekend/holiday), ¿qué muestra el frontend? Inclinación: dash horizontal estático "—" en lugar del LineChart vacío.
- **D-DASHBOARD-EMPTY-STATE** — Si el usuario está logueado pero sin posiciones (caso F18-AC-04 análogo a F16), el card de P&L muestra equity = balance + 0, P&L = 0, %=0. Sin empty state especial — la tabla 25 sí tiene datos siempre. Decisión menor confirmada acá.
- **D-FRONTEND-LAYOUT-DASHBOARD** — Orden vertical: equity card → grid 25 → widget órdenes. Confirmado en C8. Pero detalles de diseño del grid (5 columnas por mercado × 5 filas vs tabla larga con header de mercado) van a plan.

---

## 3. Contexto y dependencias

### Por qué importa

HU-F18 + HU-F17 cierran el círculo de UX completo del MVP:
1. **F18 = decision support** — el usuario llega al dashboard, escanea los 25 activos, ve cuáles se mueven más, y decide qué operar.
2. **F18 P&L card = feedback** — ve el impacto agregado de TODAS sus operaciones pasadas en un solo número.
3. **F17 historial = trazabilidad** — verifica el estado de órdenes recientes sin abrir Kibana ni psql.

Estos tres elementos juntos eliminan la necesidad de "consultar la BD para ver qué pasó" — el sistema le da al usuario una vista consolidada.

Académicamente:
- **F18 ejercita el módulo Dashboard** (ARCHITECTURE.md §3) que en F09/F10/F16/F21 quedó como módulo placeholder con `.gitkeep`.
- **F18 introduce el `PriceCache` Redis** que el ARCHITECTURE.md menciona pero ningún feature anterior usó. Cubre la deuda viva #19 del handoff F16+F21.
- **F17 cierra el lifecycle de orden** — registro (F09/F10) → ejecución (F09/F10) → consulta histórica (F17). Sin F17, las órdenes ejecutadas quedan como filas en `app.orders` sin superficie de consulta.

### Dependencias técnicas

- **HU-F09 + HU-F10 + HU-F16 + HU-F21 mergeadas en `main`** — verificado en `git log origin/main`: PR #6 (F09), PR #7 (F10), PR #8 (F16+F21, commit `1074775`).
- **`MarketDataOrchestrator.fetchPrices(Collection<String>): Map<String, BigDecimal>`** existente (F16 Lote B) — fan-out paralelo con cap 1.5s/ticker, executor dedicado de 8 threads daemon. Reusado tal cual en F18.
- **`MarketDataAdapter.getLatestPrice(ticker)`** existente (F09 Lote B). En F18 se envuelve con cache Redis (D-CACHE-LOCATION decide cómo).
- **`AllowedTickers.byMarket(): Map<Market, List<String>>`** existente (F04+F20). Devuelve los 25 tickers agrupados por mercado en orden de inserción. F18 lo consume directamente.
- **`PortfolioService.getBalance(userId)` + `getPositions(userId)`** existentes (F09, F10, F16+F21). F18 los reusa para calcular equity.
- **`OrderRepository.findByUserIdOrderBySubmittedAtDesc(userId)`** existente (F09) con comentario "prep para HU-F17 (post-MVP, historial)". F17 lo extiende con filtros y paginación.
- **Redis 7 en docker-compose** corriendo desde Día 0. `spring-boot-starter-data-redis` ya en `pom.xml` (línea 58). Env vars `SPRING_DATA_REDIS_HOST/PORT` configuradas. Sin cambios de infra.
- **`JwtAuthenticationFilter`** popula `@AuthenticationPrincipal AuthenticatedUser` (convención del proyecto — feedback memory: NO usar User entity JPA en controllers, usar el record `AuthenticatedUser` + `principal.userId()`). Endpoints `/dashboard/*` y `/orders` requieren autenticación.

### Variables de entorno nuevas

**Ninguna.** Redis ya configurado. Alpaca ya configurado.

### Migraciones BD nuevas

**Ninguna** (C13).

### Features que dependen de esta

- Ninguna en el MVP — F18+F17 son las últimas HUs funcionales del Sprint 2. El Día 10 es estabilización + pruebas de carga + documentación.

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | Único actor con JWT válido | Realiza las 3 consultas. Ve solo SUS datos (filter por `user_id` en `/orders` y `/dashboard`). El catálogo de 25 activos es global (no filtrado por usuario). |
| `JwtAuthenticationFilter` | Componente seguridad (HU-F02) | Resuelve `@AuthenticationPrincipal AuthenticatedUser`. |
| `DashboardService` (nuevo) | Servicio de dominio del módulo Dashboard | Orquesta snapshot: catálogo + cache prices + bars + equity. |
| `PortfolioService` | Servicio existente | Provee `getBalance`, `getPositions`, **+nuevo `getAccountEquity(userId, prices)`** para el card de P&L. |
| `MarketDataOrchestrator` | Componente existente (F16) | Fan-out paralelo para las 25 llamadas a precio. |
| `CachedMarketDataAdapter` (nuevo, según D-CACHE-LOCATION) | Wrapper sobre `MarketDataAdapter` | Cachea precios en Redis con TTL 30s. |
| `MarketDataAdapter` | Adapter existente (F09) | Provee `getLatestPrice(ticker)` y **+nuevo `getIntradayBars(ticker)`** para sparklines. |
| `OrderRepository` | Repository existente | Extendido con `findAll(Specification<Order>, Pageable)` o derived methods para filtros. |
| `AllowedTickers` | Catálogo existente | Provee los 25 tickers agrupados. |
| `Auditor` | NO usado en este bundle | (Reads no se auditan, C12.) |

### Precondiciones

- Usuario tiene cuenta activa (HU-F01) y sesión JWT válida (HU-F02).
- Redis corriendo y accesible (docker-compose). Si Redis cae, los endpoints **siguen funcionando** sin cache (degradación: cada request va a Alpaca). Deuda futura: detectar Redis down y agregar banner.
- Alpaca data API accesible para precios y bars. Si caído, fallback a `currentPrice=null` por ticker + sparklines vacíos (igual que F16 §5.3).

### Postcondiciones

- **Ninguna mutación de BD.** Read-only.
- Cache Redis poblado/refrescado con TTL 30s sobre los 25 keys de precio.
- Sin emails, sin eventos, sin audit events.

---

## 5. Flujos

### 5.1 Flujo principal — HU-F18 GET /dashboard/snapshot (cache miss + happy path)

```
1. Usuario navega a /dashboard → React Query dispara `useDashboardSnapshot()` y `useOrdersRecent()` en paralelo. Polling intervalado: refetchInterval=30000.
2. Hook `useDashboardSnapshot` hace GET /api/v1/dashboard/snapshot con Authorization: Bearer <jwt>.
3. JwtAuthenticationFilter valida y popula SecurityContext con AuthenticatedUser.
4. DashboardController.getSnapshot(@AuthenticationPrincipal AuthenticatedUser principal):
   4.1. Resuelve catálogo: List<String> tickers = AllowedTickers.byMarket().values().stream().flatMap(...).toList(); (25 tickers).
   4.2. DashboardService.getSnapshot(principal.userId(), tickers):
       4.2.1. Cache check: para cada ticker, intentar GET en Redis key `market-data:price:{ticker}`. 
              Cache miss (primera vez) → fan-out a MarketDataOrchestrator.fetchPrices(tickers).
              Cache hit → recoger valores de Redis directo.
              Mixto → tickers en cache + fan-out solo para los faltantes.
       4.2.2. Para cada precio recién obtenido (no del cache), SET en Redis con TTL 30s.
       4.2.3. Map<String, BigDecimal> prices listo (con null para tickers que fallaron).
       4.2.4. Para sparklines: fan-out paralelo a MarketDataAdapter.getIntradayBars(ticker) por cada ticker. 
              (D-SPARKLINE-COST decide si se cachea bars también.)
       4.2.5. Para variación intradía %: calcular (currentPrice − openPrice) / openPrice × 100 usando primera barra de la serie (open de hoy).
              Si no hay bars (cerrado/holiday), variación = null.
       4.2.6. Construir TickerDashboardDto[] con ticker, market, currentPrice, openPrice, dayChangePct, sparkline[].
       4.2.7. PortfolioService.getAccountEquity(userId, prices): calcular equity + P&L no realizado.
       4.2.8. Determinar marketDataAvailable: "true" | "partial" | "false" (igual semántica que F16).
   4.3. Mapear a DashboardSnapshotResponse{ tickers: [{market, items: [...]}], equity, marketDataAvailable, fetchedAt }.
5. Controller responde 200 OK con el JSON.
6. Frontend renderiza:
   - Card "Equity total" arriba con USD formato es-CO + delta P&L con signo + color.
   - Grid agrupado por mercado (NYSE, NASDAQ, LSE, TSE, ASX) con 5 filas cada uno.
   - Cada fila: ticker, currentPrice (o "—"), dayChangePct color-coded, mini-sparkline recharts <LineChart>.
   - Banner amarillo si marketDataAvailable!="true".
```

### 5.2 Flujo principal — HU-F17 GET /orders (paginado, sin filtros)

```
1. Hook `useOrdersRecent()` hace GET /api/v1/orders?page=0&size=10 con Bearer <jwt>.
2. JwtAuthenticationFilter valida y popula SecurityContext.
3. OrderHistoryController.list(@AuthenticationPrincipal AuthenticatedUser principal, @RequestParam Optional<String> ticker, @RequestParam Optional<OrderSide> side, Pageable pageable):
   3.1. OrderHistoryService.list(principal.userId(), filters: {ticker, side}, pageable):
       3.1.1. Build Specification<Order>: where(userIdEquals(uid)).and(tickerEquals(ticker, if present)).and(sideEquals(side, if present)).
       3.1.2. orderRepository.findAll(spec, pageable).
   3.2. Map<Page<Order>, Page<OrderHistoryDto>> con OrderHistoryMapper.
4. Controller responde 200 OK con OrderHistoryResponse{ content: [...], pagination: {page, size, totalPages, totalElements} }.
5. Frontend renderiza tabla compacta en widget "Últimas 10 órdenes" con ticker, side, qty, status badge, fecha es-CO + hora.
```

### 5.3 Flujo principal — HU-F17 GET /orders con filtros

```
1. (Post-MVP UI o curl manual) GET /api/v1/orders?ticker=AAPL&side=BUY&page=0&size=20.
2-3. Mismo que 5.2 pero Specification incluye los predicados.
4. Response solo con las órdenes que coinciden.
```

### 5.4 Flujo alterno — `/dashboard/snapshot` con cache hit total (segundo+ request en <30s)

```
4.2.1'. Todas las 25 keys están en Redis (no expiradas). Sin invocación a MarketDataOrchestrator.
4.2.4'. Sparklines: si NO se cachean (V1), se invocan igual a Alpaca bars endpoint.
        Si SÍ se cachean (V2 si D-SPARKLINE-CACHE aprueba), sale de Redis también.
4.2.8'. marketDataAvailable="true" (todos los precios vinieron del cache, sin fallos recientes).
Latencia esperada: <300ms total (vs ~1.5-2s en cache miss).
```

### 5.5 Flujo alterno — `/dashboard/snapshot` con Alpaca data API caído (cache miss + fallback)

```
4.2.1''. Cache miss completo (primera carga del día o tras 30s sin tráfico).
4.2.1'''. MarketDataOrchestrator.fetchPrices(tickers): todos los futures terminan con null por timeout o exception.
4.2.2''. NO se popula Redis con nulls (solo precios válidos van al cache).
4.2.4''. Sparklines: MarketDataAdapter.getIntradayBars(ticker) lanza MarketDataUnavailableException → atrapada por ticker → sparkline=[] (array vacío).
4.2.5''. dayChangePct=null para todos.
4.2.7''. PortfolioService.getAccountEquity con prices todos null: equity = balance solo (sin valor de mercado de posiciones); P&L no realizado = null (no calculable).
4.2.8''. marketDataAvailable="false".
5''. Controller responde 200 OK (no 502).
6''. Frontend: banner amarillo "Precios de mercado no disponibles" + tabla con "—" en precio y % + card equity muestra solo balance "Equity = balance USD X (precios no disponibles)" sin línea de P&L.
```

### 5.6 Flujo alterno — Usuario sin posiciones

```
4.2.7'''. PortfolioService.getAccountEquity con positions=[]: equity = balance; P&L unrealized = 0; pnLPct = null (división por 0 evitada).
6'''. Frontend: card equity muestra USD del balance, "P&L sin posiciones" o vacío. Grid 25 igual con datos market data normales.
Widget "Últimas 10 órdenes" muestra empty state "Aún no has colocado órdenes." si el endpoint devuelve content vacío.
```

### 5.7 Flujo de error — Sin JWT

```
3'. JwtAuthenticationFilter rechaza con 401 (o 403 según D17 F16+F21 — diferido a mini-HU token-rotation-logout).
6''''. Frontend interceptor → redirige a /login.
```

### 5.8 Flujo informativo — Polling intervalado en pestaña activa

```
T=0s: usuario abre /dashboard → snapshot inicial (cache miss → fetch Alpaca → set Redis).
T=30s: React Query refetch automático → snapshot (cache hit, ~300ms).
T=60s: refetch → cache acabó de expirar (30s+) → fetch Alpaca → set Redis nuevamente.
T=90s: refetch → cache hit.
...
Si tab cambia a background: refetch sigue corriendo (C5: refetchIntervalInBackground=true).
Si usuario hace click en botón "↻" en cualquier momento: refetch inmediato + invalidación de queryKey ['dashboard', 'snapshot'].
```

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

| Método | Path | Auth | Descripción |
|---|---|---|---|
| GET | `/api/v1/dashboard/snapshot` | Bearer JWT | Snapshot completo: 25 tickers con precio + sparkline + equity del usuario |
| GET | `/api/v1/orders` | Bearer JWT | Historial paginado de órdenes con filtros opcionales `ticker` y `side` |
| GET | `/api/v1/market/intraday/{ticker}` | Bearer JWT | (Solo si D-SPARKLINE-EMBEDDING decide separar) Bars intradía de un ticker individual |

### 6.2 `GET /api/v1/dashboard/snapshot`

#### Request

```
GET /api/v1/dashboard/snapshot
Authorization: Bearer <jwt>
```

Sin query params en MVP. (Futuro: `?includeSparklines=false` para skip sparklines si performance lo demanda.)

#### Response 200 OK

```yaml
components:
  schemas:
    DashboardSnapshotResponse:
      type: object
      required: [tickers, equity, marketDataAvailable, fetchedAt]
      properties:
        tickers:
          type: array
          description: Grupos agrupados por mercado, en orden NYSE → NASDAQ → LSE → TSE → ASX (oeste a este por timezone).
          items: { $ref: '#/components/schemas/MarketGroupDto' }
        equity:
          $ref: '#/components/schemas/AccountEquityDto'
        marketDataAvailable:
          type: string
          enum: ["true", "false", "partial"]
          description: |
            Estado del mark-to-market (igual semántica que F16):
            - "true": todos los precios y sparklines están poblados.
            - "partial": algunos tickers tienen currentPrice o sparkline en null/vacío.
            - "false": ningún precio disponible.
        fetchedAt:
          type: string
          format: date-time
          description: Instante UTC de generación del response.
        cacheBuckets:
          type: integer
          minimum: 0
          maximum: 25
          description: |
            (Opcional, debug-friendly) Cantidad de tickers servidos desde cache Redis (vs cache miss). 
            Útil para validar manualmente el comportamiento del cache durante smoke. Si no se quiere exponer, omitir del DTO.

    MarketGroupDto:
      type: object
      required: [market, items]
      properties:
        market:
          type: string
          enum: [NYSE, NASDAQ, LSE, TSE, ASX]
        items:
          type: array
          items: { $ref: '#/components/schemas/TickerDashboardDto' }

    TickerDashboardDto:
      type: object
      required: [ticker]
      properties:
        ticker:
          type: string
          example: "AAPL"
        currentPrice:
          type: [string, "null"]
          description: BigDecimal stringified scale=2. null si market data no disponible.
          example: "193.20"
        openPrice:
          type: [string, "null"]
          description: BigDecimal stringified scale=2, precio de apertura intradía hoy (primera barra).
          example: "189.50"
        dayChangePct:
          type: [string, "null"]
          description: BigDecimal stringified scale=2. (currentPrice − openPrice) / openPrice × 100. null si openPrice o currentPrice son null.
          example: "1.95"
        sparkline:
          type: array
          description: Serie de close prices de las barras intradía (15min) ordenados ascendentemente por timestamp. Vacío si bars no disponibles.
          items:
            type: string
            description: BigDecimal stringified scale=2 (close).
          example: ["189.50", "190.10", "190.45", "191.20", "192.80", "193.20"]

    AccountEquityDto:
      type: object
      required: [balance, currency]
      properties:
        balance:
          type: string
          description: BigDecimal stringified, balance USD disponible. Scale=2.
          example: "5234.45"
        positionsMarketValue:
          type: [string, "null"]
          description: BigDecimal stringified, Σ(qty × currentPrice). null si todas las posiciones no tienen precio.
          example: "3974.50"
        equity:
          type: [string, "null"]
          description: BigDecimal stringified, balance + positionsMarketValue. null si positionsMarketValue es null.
          example: "9208.95"
        costBasisTotal:
          type: [string, "null"]
          description: BigDecimal stringified, Σ(qty × avgBuyPrice). null si no hay posiciones.
          example: "3954.50"
        unrealizedPnL:
          type: [string, "null"]
          description: BigDecimal stringified, positionsMarketValue − costBasisTotal. null si cualquiera de los dos es null.
          example: "20.00"
        unrealizedPnLPct:
          type: [string, "null"]
          description: BigDecimal stringified, (unrealizedPnL / costBasisTotal) × 100. Scale=2. null si costBasisTotal es 0 o null.
          example: "0.51"
        currency:
          type: string
          enum: [USD]
```

#### Ejemplo de response (happy path)

```json
{
  "tickers": [
    {
      "market": "NYSE",
      "items": [
        { "ticker": "AAPL", "currentPrice": "193.20", "openPrice": "189.50", "dayChangePct": "1.95", "sparkline": ["189.50", "190.10", "190.45", "191.20", "192.80", "193.20"] },
        { "ticker": "MSFT", "currentPrice": "408.50", "openPrice": "412.00", "dayChangePct": "-0.85", "sparkline": ["412.00", "411.30", "410.50", "409.10", "408.20", "408.50"] }
      ]
    },
    { "market": "NASDAQ", "items": [/* ... */] },
    { "market": "LSE", "items": [/* ... */] },
    { "market": "TSE", "items": [/* ... */] },
    { "market": "ASX", "items": [/* ... */] }
  ],
  "equity": {
    "balance": "5234.45",
    "positionsMarketValue": "3974.50",
    "equity": "9208.95",
    "costBasisTotal": "3954.50",
    "unrealizedPnL": "20.00",
    "unrealizedPnLPct": "0.51",
    "currency": "USD"
  },
  "marketDataAvailable": "true",
  "fetchedAt": "2026-05-25T14:02:11Z"
}
```

### 6.3 `GET /api/v1/orders`

#### Request

```
GET /api/v1/orders?ticker=AAPL&side=BUY&page=0&size=10
Authorization: Bearer <jwt>
```

| Query param | Tipo | Obligatorio | Valor default | Descripción |
|---|---|---|---|---|
| `ticker` | string | No | — | Filtra por ticker exacto (case-sensitive). |
| `side` | enum {BUY, SELL} | No | — | Filtra por lado. |
| `page` | integer | No | 0 | Página 0-indexed. |
| `size` | integer | No | 20 | Tamaño de página. Cap MVP=100. |

Sort fijo: `submitted_at DESC` (no parametrizable).

#### Response 200 OK

```yaml
components:
  schemas:
    OrderHistoryResponse:
      type: object
      required: [content, pagination]
      properties:
        content:
          type: array
          items: { $ref: '#/components/schemas/OrderHistoryDto' }
        pagination:
          $ref: '#/components/schemas/PaginationDto'

    OrderHistoryDto:
      type: object
      required: [orderId, ticker, side, quantity, status, submittedAt]
      properties:
        orderId:
          type: string
          format: uuid
        clientOrderId:
          type: string
          format: uuid
        ticker:
          type: string
        side:
          type: string
          enum: [BUY, SELL]
        quantity:
          type: integer
        status:
          type: string
          enum: [PENDING, EXECUTED, FAILED, REJECTED, CANCELLED]
        submittedAt:
          type: string
          format: date-time
        executedAt:
          type: [string, "null"]
          format: date-time
        executionTotal:
          type: [string, "null"]
          description: BigDecimal stringified scale=2. Total ejecutado (con comisión). null si no se ejecutó.
        averageFillPrice:
          type: [string, "null"]
          description: BigDecimal stringified scale=2. null si no se ejecutó.
        commission:
          type: [string, "null"]
          description: BigDecimal stringified scale=2.
        alpacaOrderId:
          type: [string, "null"]
          description: ID de la orden en Alpaca paper trading.
        failureReason:
          type: [string, "null"]
          description: Código machine-readable si status=FAILED|REJECTED (ej. INSUFFICIENT_FUNDS, MARKET_REJECTED).

    PaginationDto:
      type: object
      required: [page, size, totalElements, totalPages]
      properties:
        page: { type: integer }
        size: { type: integer }
        totalElements: { type: integer, format: int64 }
        totalPages: { type: integer }
```

#### Ejemplo de response

```json
{
  "content": [
    {
      "orderId": "11111111-2222-3333-4444-555555555555",
      "clientOrderId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      "ticker": "AAPL",
      "side": "BUY",
      "quantity": 10,
      "status": "EXECUTED",
      "submittedAt": "2026-05-22T14:30:15Z",
      "executedAt": "2026-05-22T14:30:18Z",
      "executionTotal": "1932.00",
      "averageFillPrice": "193.20",
      "commission": "9.66",
      "alpacaOrderId": "alpaca-abc-123",
      "failureReason": null
    }
  ],
  "pagination": { "page": 0, "size": 10, "totalElements": 47, "totalPages": 5 }
}
```

### 6.4 `GET /api/v1/market/intraday/{ticker}` (solo si D-SPARKLINE-EMBEDDING decide separar)

#### Request

```
GET /api/v1/market/intraday/AAPL
Authorization: Bearer <jwt>
```

#### Response 200 OK

```yaml
components:
  schemas:
    IntradaySeriesResponse:
      type: object
      required: [ticker, bars, fetchedAt]
      properties:
        ticker: { type: string }
        bars:
          type: array
          items: { $ref: '#/components/schemas/IntradayBarDto' }
        fetchedAt:
          type: string
          format: date-time

    IntradayBarDto:
      type: object
      required: [timestamp, close]
      properties:
        timestamp:
          type: string
          format: date-time
          description: Instante UTC del inicio del bar (15min).
        open: { type: string, description: BigDecimal stringified }
        high: { type: string, description: BigDecimal stringified }
        low: { type: string, description: BigDecimal stringified }
        close: { type: string, description: BigDecimal stringified }
        volume: { type: integer, format: int64 }
```

### 6.5 Códigos de error compartidos

| HTTP | Código aplicación | Cuándo |
|---|---|---|
| 401 | `AUTHENTICATION_REQUIRED` | Sin JWT / JWT inválido / JWT expirado. |
| 403 | (sin JWT, según D17 F16+F21 cross-cutting deuda) | Documentado como deuda. |
| 400 | `INVALID_REQUEST_PARAMETER` | `?side=FOO` (no es BUY/SELL), `?page=-1`, `?size=200` (>cap). Handler estándar de F09. |
| 500 | `INTERNAL_ERROR` | Excepción no manejada. |

> **Importante:** Igual que F16, `502 MARKET_DATA_UNAVAILABLE` NO se emite. Alpaca caído → `marketDataAvailable="false"` con 200 OK.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Ninguna** (C13).

### 7.2 Modificaciones a tablas existentes

**Ninguna.**

### 7.3 Índices nuevos sugeridos (no bloqueantes)

- `app.orders (user_id, submitted_at DESC)` — composite index para optimizar el sort fijo de F17. Diferido a post-MVP: con volumen del MVP (decenas de órdenes por usuario), seq scan + sort en memoria es aceptable.
- `app.orders (user_id, ticker, submitted_at DESC)` — para filter+sort combinado. Diferido.

### 7.4 Datos semilla

**Ninguno.**

### 7.5 Redis keys nuevos (no son tablas BD, pero documentamos)

- `market-data:price:{ticker}` — BigDecimal stringificado (o serializado nativo según D-CACHE-IMPL). TTL 30s. Set por `CachedMarketDataAdapter` (o equivalente). 25 keys totales steady-state.
- (Opcional, V2) `market-data:bars:{ticker}:{date}` — serie JSON de bars intradía. TTL 5min. Si D-SPARKLINE-CACHE aprueba.

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados (ARCHITECTURE.md §3)

| Módulo | Rol en F18+F17 |
|---|---|
| **Dashboard** | **Owner principal de F18.** `DashboardController` (nuevo) + `DashboardService` (nuevo) en `co.edu.unbosque.bloomtrade.dashboard.*`. El módulo `.gitkeep` deja de ser placeholder. |
| **Trading** | **Owner de F17.** `OrderHistoryController` (nuevo) + `OrderHistoryService` (nuevo) en `co.edu.unbosque.bloomtrade.trading.history.*`. Reusa `OrderRepository`. |
| **Portfolio** | Provee `PortfolioService.getAccountEquity(...)` (nuevo método). |
| **Integration** | `MarketDataAdapter` extendido con `getIntradayBars(ticker)`. **+nuevo `CachedMarketDataAdapter`** (decoradoryque wrappea adapter + Redis) según D-CACHE-LOCATION. |
| **Auth** | `JwtAuthenticationFilter` resuelve `AuthenticatedUser`. NO se modifica. |
| **Notification** | NO involucrado. |
| **Audit** | NO involucrado (reads no se auditan). |
| **Reporting** | NO involucrado (post-MVP). |
| **Configuration** | NO involucrado. |
| **Persistence** | `OrderRepository` extendido con `JpaSpecificationExecutor<Order>`. |

### 8.2 Componentes nuevos por crear

**Backend (módulo Dashboard, paquete `co.edu.unbosque.bloomtrade.dashboard`):**
- `dashboard.web.DashboardController` — REST controller con 1 endpoint `/snapshot`.
- `dashboard.service.DashboardService` — orquestación de cache + market data + equity.
- `dashboard.dto.DashboardSnapshotResponse`, `MarketGroupDto`, `TickerDashboardDto`, `AccountEquityDto` — DTOs.
- `dashboard.web.DashboardMapper` — mapper manual (BigDecimal stringification, cálculo dayChangePct).

**Backend (módulo Trading, paquete `co.edu.unbosque.bloomtrade.trading.history` — nuevo sub-paquete):**
- `trading.history.web.OrderHistoryController` — REST controller con 1 endpoint `/orders`.
- `trading.history.service.OrderHistoryService` — orquestación filtros + paginación.
- `trading.history.dto.OrderHistoryResponse`, `OrderHistoryDto`, `PaginationDto` — DTOs.
- `trading.history.web.OrderHistoryMapper` — mapper manual.
- `trading.history.repository.OrderSpecifications` — fábrica estática de `Specification<Order>` (D-ORDER-FILTERS-IMPL).

**Backend (módulo Integration, paquete `co.edu.unbosque.bloomtrade.integration.alpaca`):**
- `CachedMarketDataAdapter` (o equivalente, según D-CACHE-LOCATION) — wrapper sobre `MarketDataAdapter` con cache Redis.
- DTOs adicionales en `integration.alpaca.dto.*` para bars (e.g., `AlpacaBarsResponse`, `AlpacaBar`).
- Nuevo método `MarketDataAdapter.getIntradayBars(ticker): List<IntradayBar>` (o un nuevo `MarketDataBarsAdapter` separado).

**Backend (extensiones a existentes):**
- `PortfolioService.getAccountEquity(userId, prices: Map<String, BigDecimal>): AccountEquityDto` — método nuevo read-only.
- `OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order>` — agregar interface a la herencia.
- Configuración Redis: `application.yml` con `spring.data.redis.host`/`port` (ya seteado vía env vars en docker-compose); posible `RedisConfig` con `RedisTemplate<String, BigDecimal>` y/o `@EnableCaching` + `RedisCacheManager` (D-CACHE-IMPL).

**Frontend (módulo `features/dashboard/` — nuevo):**
- `frontend/src/features/dashboard/DashboardPage.tsx` — página completa.
- `frontend/src/features/dashboard/components/EquityCard.tsx` — card de equity + P&L.
- `frontend/src/features/dashboard/components/TickerGrid.tsx` — grid agrupado por mercado.
- `frontend/src/features/dashboard/components/TickerRow.tsx` — fila individual (ticker + precio + delta + sparkline).
- `frontend/src/features/dashboard/components/Sparkline.tsx` — wrapper sobre recharts LineChart.
- `frontend/src/features/dashboard/components/RecentOrdersWidget.tsx` — widget de "Últimas 10 órdenes" (F17 embebido).
- `frontend/src/features/dashboard/components/MarketDataBanner.tsx` — reuso o variante del banner F16.
- `frontend/src/features/dashboard/hooks/useDashboardSnapshot.ts` — React Query hook con `refetchInterval: 30000`.
- `frontend/src/features/dashboard/hooks/useOrdersRecent.ts` — React Query hook para historial.
- `frontend/src/features/dashboard/api/dashboardApi.ts` — wrappers HTTP.
- `frontend/src/features/dashboard/api/ordersApi.ts` — wrapper HTTP F17.

**Frontend (extensiones a existentes):**
- `frontend/src/types/api.ts` — +types `DashboardSnapshotResponse`, `MarketGroupDto`, `TickerDashboardDto`, `AccountEquityDto`, `OrderHistoryResponse`, `OrderHistoryDto`, `PaginationDto`.
- `frontend/src/App.tsx` — ruta nueva `/dashboard` (protegida). Si la ruta `/` no estaba ya redirigiendo a algo, considerar `/` → `/dashboard` para usuarios autenticados.
- `frontend/src/components/AppHeader.tsx` — link "Dashboard" (probablemente el primero del header).
- `frontend/src/lib/messages.es.ts` — +copys `dashboard.*` y `orders.*`.

### 8.3 Tácticas Bass aplicadas (ARCHITECTURE.md §6)

- **TAC-P1 (Caching):** Redis `PriceCache` TTL 30s desacopla el polling 30s/usuario del rate-limit de Alpaca. Cumple deuda viva #19.
- **TAC-D5 (Defensa en profundidad — fallback degradado):** mismo patrón de F16 — Alpaca caído → `marketDataAvailable="false"` + 200 OK, no 502.
- **TAC-P2 (Parallel fan-out):** `MarketDataOrchestrator` ya cableado, 25 calls concurrentes con cap 1.5s/ticker, bounded en ~2s.
- **TAC-S2 (Authenticated request scope):** F17 endpoint filtra por `userId` desde JWT, imposible leakar cross-user.
- **TAC-T3 (Testability via mocking):** `MarketDataAdapter` y `RedisTemplate` mockeables → tests IT con WireMock + Embedded Redis (o testcontainer si simple). Decisión de Redis test infra en plan.

### 8.4 Patrones de diseño

- **Decorator:** `CachedMarketDataAdapter` envuelve `MarketDataAdapter` con cache transparente. Cliente (DashboardService / PortfolioService) no nota la diferencia.
- **Specification:** `OrderSpecifications.byUser(uid).and(byTicker(t).orNoOp()).and(bySide(s).orNoOp())` para filtros dinámicos F17.
- **DTO + Mapper:** patrón consistente con todas las HUs previas.
- **Adapter:** `MarketDataAdapter` extendido con `getIntradayBars` mantiene desacoplamiento Alpaca.
- **Repository (con Specification):** `JpaSpecificationExecutor` da builder dinámico sin escribir SQL manual.

---

## 9. Efectos colaterales

### 9.1 Auditoría

**Ninguno** (C12).

### 9.2 Notificaciones

**Ninguna.**

### 9.3 Logs

- `INFO` al entrar a cada endpoint con `userId`, latency total, cacheBuckets (cantidad servida desde Redis vs Alpaca).
- `WARN` por ticker que falle market data (igual que F16).
- `WARN` si Redis cae (conexión falla) — el endpoint sigue funcionando sin cache, pero registra el evento.
- `DEBUG` cache hit/miss por ticker (solo en dev profile).
- `ERROR` excepciones no manejadas.

### 9.4 Métricas (Micrometer)

- Reusa timer estándar `http.server.requests` con tags `uri=/api/v1/dashboard/snapshot` y `/api/v1/orders`.
- **Métricas custom sugeridas** (deferred si tiempo):
  - `bloomtrade.market_data.cache.hits` — counter.
  - `bloomtrade.market_data.cache.misses` — counter.
  - Ratio hits/(hits+misses) como dashboard signal de salud del cache.

### 9.5 Eventos

**Ninguno.**

---

## 10. Atributos de calidad aplicables

| Atributo (ARCHITECTURE.md §4) | Cómo aplica a F18+F17 | NFR concreto |
|---|---|---|
| **Performance** | Polling 30s × 25 tickers genera ~50 calls/min/usuario sin cache. Con cache TTL 30s, el factor cae a ~25 calls/min en steady-state (cache hit rate ~50% si polling=TTL exactos). | **NFR-PERF-DASHBOARD**: p95 de `/snapshot` ≤ 3s con cache miss frío, ≤ 500ms con cache hit. `/orders` p95 ≤ 300ms para hasta 100 órdenes en BD. |
| **Availability** | Alpaca caído NO rompe el endpoint (fallback). Redis caído NO rompe el endpoint (pasa directo a Alpaca, degradación de costo no de funcionalidad). | **NFR-AVAIL-DASHBOARD**: `/snapshot` mantiene 99% de disponibilidad funcional aún con Alpaca o Redis caídos individualmente. |
| **Security** | F17 estricto filter por userId. Sin parámetros que permitan impersonation. | **NFR-SEC-DASHBOARD/ORDERS**: tests IT cross-user que confirman no leak. |
| **Usability** | Sparklines visuales facilitan detección rápida de movers. Color-coded delta. Empty states explicativos. | Validado en HITO 5 manual. |
| **Maintainability** | Patrón consistente con F09/F10/F16+F21. Sin desviar de stack approved. | Code review. |
| **Testability** | Cache con TTL testeable vía manipulación de tiempo en Redis (`EXPIRE` manual). MarketDataAdapter ya mockeable. | Tests IT con WireMock + Redis real del docker-compose (perfil test). |
| **Scalability** | Cache amortigua spike de polling. Si N usuarios crece, calls a Alpaca se mantienen ~constante mientras TTL no se reduzca. | MVP single-user no aplica, pero patrón está listo. |

---

## 11. Criterios de aceptación

### HU-F18-AC-01 — Snapshot completo cache miss (primera carga)

```gherkin
Dado un usuario autenticado con balance USD 5234.45 y 2 posiciones (10 AAPL avgCost 189.45, 5 MSFT avgCost 412.00)
Y Alpaca data API responde para los 25 tickers (todos) con precios actuales y bars intradía
Y Redis está vacío (sin keys market-data:price:*)
Cuando hace GET /api/v1/dashboard/snapshot
Entonces recibe 200 OK con:
  - tickers contiene 5 MarketGroupDto (NYSE, NASDAQ, LSE, TSE, ASX) cada uno con 5 items
  - Para AAPL: currentPrice="193.20", openPrice="189.50", dayChangePct="1.95", sparkline.length > 0
  - equity = { balance:"5234.45", positionsMarketValue:"3974.50", equity:"9208.95", costBasisTotal:"3954.50", unrealizedPnL:"20.00", unrealizedPnLPct:"0.51", currency:"USD" }
  - marketDataAvailable = "true"
Y Redis contiene 25 keys "market-data:price:*" con TTL ~30s cada uno
Y la latencia total del endpoint es < 3s p95
```

### HU-F18-AC-02 — Snapshot con cache hit (segundo request en <30s)

```gherkin
Dado un usuario que ya hizo /snapshot hace 10s (Redis poblado con 25 keys TTL ~20s restantes)
Y Alpaca data API NO debería ser invocado para precios spot (sí para bars en V1)
Cuando hace GET /api/v1/dashboard/snapshot nuevamente
Entonces recibe 200 OK con los mismos precios (del cache) o ligeramente diferentes si hubo refresh parcial
Y cacheBuckets = 25 (todos del cache, opcional en response)
Y MarketDataAdapter.getLatestPrice NO se invocó (verificable por mock interaction count o log INFO sin "Alpaca Data ticker=...")
Y la latencia total < 500ms p95
```

### HU-F18-AC-03 — Snapshot con Alpaca caído (fallback)

```gherkin
Dado un usuario autenticado con posiciones
Y Alpaca data API devuelve 503 para todas las llamadas
Y Redis está vacío (sin cache previo)
Cuando hace GET /api/v1/dashboard/snapshot
Entonces recibe 200 OK con:
  - tickers presentes pero currentPrice/openPrice/dayChangePct todos null en cada item, sparkline=[]
  - equity.balance="5234.45" (sigue presente, viene de BD no de Alpaca)
  - equity.positionsMarketValue = null
  - equity.equity = null
  - equity.unrealizedPnL = null
  - marketDataAvailable = "false"
Y Redis NO se contamina con valores null (los nulls no se cachean)
Y el frontend muestra banner amarillo + card equity con solo balance
```

### HU-F18-AC-04 — Usuario sin posiciones

```gherkin
Dado un usuario recién registrado con balance USD 10000 y sin posiciones
Cuando hace GET /api/v1/dashboard/snapshot
Entonces recibe 200 OK con:
  - tickers completos (mercado se sigue mostrando aún sin posiciones)
  - equity = { balance:"10000.00", positionsMarketValue:"0.00", equity:"10000.00", costBasisTotal:"0.00", unrealizedPnL:"0.00", unrealizedPnLPct: null, currency:"USD" }
  - marketDataAvailable = "true" (depende de Alpaca)
Y el frontend muestra card equity sin línea de P&L pct (o muestra "—")
```

### HU-F18-AC-05 — Polling intervalado refresca cache

```gherkin
Dado un usuario con /dashboard abierto en pestaña activa
Y han pasado 30s desde el último refresh
Cuando React Query dispara refetch automático
Entonces el snapshot se refresca:
  - Si Redis aún tiene cache fresco (race: dos pollings en mismo TTL): cache hit, sin tráfico Alpaca extra
  - Si Redis expiró: cache miss → Alpaca → repuebla
Y el frontend re-renderiza con los nuevos precios (o los mismos si no cambiaron)
```

### HU-F18-AC-06 — Aislamiento cross-user del equity

```gherkin
Dado usuario A con posiciones AAPL y usuario B con posiciones MSFT
Cuando A llama /snapshot con su JWT
Entonces equity.positionsMarketValue solo contempla AAPL (no MSFT de B)
Y no hay forma de manipular el request para ver equity de B
```

### HU-F18-AC-07 — Sin JWT rechaza 401

```gherkin
Dado un cliente sin Authorization header
Cuando hace GET /api/v1/dashboard/snapshot
Entonces recibe 401 (o 403 según D17 F16+F21 cross-cutting deuda — comentado en test)
```

### HU-F17-AC-01 — Listado paginado sin filtros

```gherkin
Dado un usuario con 47 órdenes en app.orders (entre BUY/SELL/PENDING/EXECUTED/FAILED variadas)
Cuando hace GET /api/v1/orders?page=0&size=10
Entonces recibe 200 OK con:
  - content: 10 OrderHistoryDto ordenados por submittedAt DESC
  - pagination: { page:0, size:10, totalElements:47, totalPages:5 }
```

### HU-F17-AC-02 — Filtro por ticker

```gherkin
Dado un usuario con órdenes en AAPL, MSFT, TSLA
Cuando hace GET /api/v1/orders?ticker=AAPL&page=0&size=20
Entonces recibe solo órdenes con ticker="AAPL"
Y pagination.totalElements refleja solo el conteo filtrado
```

### HU-F17-AC-03 — Filtro por side

```gherkin
Dado un usuario con 5 órdenes BUY y 3 SELL
Cuando hace GET /api/v1/orders?side=BUY
Entonces recibe solo las 5 BUY
```

### HU-F17-AC-04 — Filtros combinados ticker+side

```gherkin
Dado un usuario con 3 BUY AAPL, 2 SELL AAPL, 1 BUY MSFT
Cuando hace GET /api/v1/orders?ticker=AAPL&side=BUY
Entonces recibe las 3 BUY AAPL únicamente
```

### HU-F17-AC-05 — Aislamiento cross-user

```gherkin
Dado usuario A con órdenes AAPL y usuario B con órdenes MSFT
Cuando A llama /orders con su JWT y sin filtros
Entonces ve solo órdenes de A (ninguna de B)
Y no hay manera de manipular el request para ver las de B
```

### HU-F17-AC-06 — Parámetro side inválido devuelve 400

```gherkin
Dado un usuario autenticado
Cuando hace GET /api/v1/orders?side=FOO
Entonces recibe 400 INVALID_REQUEST_PARAMETER con detalle del campo
```

### HU-F17-AC-07 — size sobre el cap devuelve 400 o se trunca

```gherkin
Dado un usuario autenticado
Cuando hace GET /api/v1/orders?size=200
Entonces recibe 400 INVALID_REQUEST_PARAMETER con mensaje "size máximo 100" (preferido sobre silencio truncado)
```

### HU-F17-AC-08 — Widget en /dashboard renderiza últimas 10

```gherkin
Dado un usuario con 47 órdenes
Cuando navega a /dashboard
Entonces el widget "Últimas 10 órdenes" muestra las 10 más recientes (por submittedAt DESC)
Y cada fila tiene ticker, side (BUY/SELL), qty, status badge color-coded, fecha+hora es-CO
```

### HU-F17-AC-09 — Widget empty state

```gherkin
Dado un usuario recién registrado sin órdenes
Cuando navega a /dashboard
Entonces el widget muestra "Aún no has colocado órdenes." sin tabla
```

### HU-F18+F17-AC-AGG-01 — Página /dashboard render integral

```gherkin
Dado un usuario logueado con posiciones + órdenes históricas + saldo populado
Cuando navega a /dashboard
Entonces ve simultáneamente (cargas paralelas vía React Query):
  - Card "Equity total" arriba con USD formato es-CO + P&L con signo + color
  - Grid 5×5 (mercados × tickers) con sparklines renderizando
  - Banner si market data degradado
  - Widget "Últimas 10 órdenes" colapsable con tabla
Y la primera visualización completa (cache miss → fetch → render) es <5s en p95
```

---

## 12. UI y experiencia

### 12.1 Layout de `/dashboard`

```
┌──────────────────────────────────────────────────────────────┐
│ AppHeader [DASHBOARD][Trade][Portafolio][Premium]            │
├──────────────────────────────────────────────────────────────┤
│ ╔═══════════════════════════════════════════════════════╗   │
│ ║  Equity total                          [↻ Actualizar] ║   │
│ ║  USD 9.208,95                                         ║   │
│ ║  P&L no realizado: +USD 20,00 (+0,51%)  ← color verde ║   │
│ ║  Última actualización: hace 8 s                       ║   │
│ ╚═══════════════════════════════════════════════════════╝   │
│                                                              │
│ [Banner amarillo si marketDataAvailable!="true"]             │
│                                                              │
│ NYSE                                                         │
│ ┌──────┬─────────┬─────────┬──────────────────────────┐    │
│ │AAPL  │ 193.20  │ +1,95%  │ ▁▂▃▄▅▆ (sparkline)       │    │
│ │MSFT  │ 408.50  │ -0,85%  │ ▆▅▄▃▂▁                   │    │
│ │JNJ   │ ...     │ ...     │ ...                       │    │
│ └──────┴─────────┴─────────┴──────────────────────────┘    │
│                                                              │
│ NASDAQ                                                       │
│ [misma estructura]                                           │
│                                                              │
│ LSE / TSE / ASX                                              │
│ [...]                                                        │
│                                                              │
│ ▼ Últimas 10 órdenes                                         │
│ ┌──────┬──────┬─────┬───────────┬─────────────────────┐    │
│ │Ticker│Lado  │Cant │Estado     │Fecha                │    │
│ ├──────┼──────┼─────┼───────────┼─────────────────────┤    │
│ │AAPL  │COMPRA│ 10  │EJECUTADA  │22-may-2026 09:30    │    │
│ │MSFT  │VENTA │  5  │EJECUTADA  │20-may-2026 14:15    │    │
│ └──────┴──────┴─────┴───────────┴─────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

Si usuario sin posiciones: card equity muestra solo balance. Grid de tickers se mantiene completo.
Si usuario sin órdenes: widget muestra empty state.

### 12.2 Wording

| Elemento | Texto |
|---|---|
| Título página | "Dashboard" (o solo `/dashboard` en breadcrumb) |
| Card equity headline | "Equity total" |
| Card equity P&L positivo | "P&L no realizado: +USD {amount} (+{pct}%)" |
| Card equity P&L negativo | "P&L no realizado: −USD {amount} ({pct}%)" (negativo con minus sign Unicode) |
| Card equity P&L zero / null | "P&L no realizado: —" |
| Card equity sin precios | "Equity = balance USD {balance} (precios de mercado no disponibles)" |
| Botón refresh | Icono ↻ con aria-label "Actualizar" |
| Banner amarillo (igual F16) | "Precios de mercado temporalmente no disponibles. Mostramos información disponible." |
| Banner naranja partial | "Algunos precios no se pudieron obtener. Marcados con —" |
| Header grupo mercado | Nombre del mercado en mayúsculas (NYSE, NASDAQ, LSE, TSE, ASX) |
| Header columnas tickers | "Ticker", "Precio", "Variación día", "Intradía" |
| Header widget órdenes | "Últimas 10 órdenes" |
| Widget empty | "Aún no has colocado órdenes." con CTA "Operar ahora" → /trade |
| Header columnas órdenes | "Ticker", "Lado", "Cantidad", "Estado", "Fecha" |
| Lado BUY | "COMPRA" |
| Lado SELL | "VENTA" |
| Status PENDING | "EN COLA" (badge naranja) |
| Status EXECUTED | "EJECUTADA" (badge verde) |
| Status FAILED | "FALLIDA" (badge rojo) |
| Status REJECTED | "RECHAZADA" (badge rojo oscuro) |
| Status CANCELLED | "CANCELADA" (badge gris) |

Todos los copys van a `frontend/src/lib/messages.es.ts` con keys `dashboard.*` y `orders.*`.

### 12.3 Formato de moneda y números

- Moneda: `Intl.NumberFormat("es-CO", { style: "currency", currency: "USD", minimumFractionDigits: 2 })` → "USD 9.208,95".
- Porcentaje: `Intl.NumberFormat("es-CO", { style: "decimal", minimumFractionDigits: 2, maximumFractionDigits: 2 })` + "%".
- Fechas: `date-fns/format` con locale `es` → "22-may-2026 09:30".

### 12.4 Color y a11y

- P&L positivo: `text-emerald-600` + icono ▲.
- P&L negativo: `text-rose-600` + icono ▼.
- P&L zero/null: `text-slate-500` + "—".
- Status badges con colores Tailwind + texto, NO solo colores.
- Sparkline con `stroke="currentColor"` y `text-emerald-500` si dayChangePct≥0, `text-rose-500` si <0, `text-slate-400` si null.

### 12.5 Refresh

- React Query `refetchInterval: 30000`, `refetchIntervalInBackground: true` (C5).
- Botón `↻` en card equity que invalida `queryKey: ['dashboard']` (afecta a snapshot y a recent orders).
- Sin pause manual en tab hidden (decisión MVP — se acepta el costo).

### 12.6 Sparkline

- recharts `<LineChart width={100} height={30} data={...}>` con `<Line dataKey="close" dot={false} strokeWidth={1.5}>`.
- Sin ejes, sin grid, sin tooltip (componente minimalista inline).
- Si serie vacía: render `—` con `text-slate-400` (D-EMPTY-SPARKLINE).

### 12.7 Responsive

- Desktop (>1024px): grid 5 columnas (mercados side-by-side) con cards.
- Tablet (768-1024px): grid 2 columnas, mercados stack vertically.
- Mobile (<768px): single column, todos los mercados stacked, sparklines persisten (más pequeñas).

### 12.8 Widget "Últimas 10 órdenes"

- Colapsable con `<details>` HTML semántico (`open` por default si count>0, cerrado si count==0).
- En mobile: cards verticales por orden en lugar de tabla.

---

## 13. Fuera de alcance de esta spec

- **HU-F17 con UI de filtros** (ticker + side). El widget MVP es solo "últimas 10". Si el usuario quiere filtrar, consume el endpoint directamente o se promueve UI en post-MVP.
- **Página `/orders` dedicada**. C8: solo widget embebido. Deuda menor registrada.
- **Top movers** (top 3 ganadores / perdedores). Descartado en C3.
- **Watchlists personalizadas**. HU-F23 post-MVP.
- **Alertas de precio**. HU-F19 post-MVP.
- **Curva de equity histórica** (chart de equity diario en el tiempo). Requiere snapshot job nocturno. Post-MVP.
- **Comparación contra benchmarks** (S&P 500, etc.). Post-MVP.
- **WebSocket / streaming de precios.** Polling 30s es suficiente para MVP. Streaming post-MVP.
- **Export CSV de historial de órdenes.** Post-MVP.
- **Sort parametrizable en /orders.** Sort fijo submittedAt DESC.
- **Filtros adicionales en /orders** (status, rango de fechas). Solo ticker+side en MVP.
- **Sparklines interactivas** (hover, tooltip, zoom). Solo visualización estática.
- **Comparación intradía hoy vs ayer.** Solo se muestra hoy. dayChangePct es vs open de hoy, no vs close de ayer.
- **Cache de bars Redis (D-SPARKLINE-CACHE).** V1 sin cache de bars; V2 si rate-limit lo exige.
- **Server-Sent Events para refrescos in-band.** Polling es simpler.
- **Detección y banner de Redis down.** Si Redis cae, sigue funcionando con degradación de costo. Sin banner.

---

## 14. Preguntas abiertas

| # | Pregunta | Posible respuesta | Decisión |
|---|---|---|---|
| Q1 | ¿Se cachean los sparklines (bars) en V1 también? | Si Alpaca free tier resiste 50 calls/min (25 spot + 25 bars / 30s), no. Si rate-limita, sí con TTL 5min. | Decisión en plan D-SPARKLINE-CACHE tras prueba inicial. Default: no en V1. |
| Q2 | ¿La ruta `/` redirige a `/dashboard` cuando el usuario está autenticado? | Probablemente sí: dashboard es la landing natural. Actualmente la ruta probable es `/trade` o `/portfolio`. | Decisión menor en plan o durante implementación frontend. Confirmar con humano. |
| Q3 | ¿`AccountEquityDto.unrealizedPnLPct` cuando `costBasisTotal = 0` y `unrealizedPnL = 0`? | Devolver null (no se puede calcular ratio) o "0.00". Frontend renderiza "—" en ambos casos. | Cerrada: null cuando costBasisTotal=0. |
| Q4 | ¿`OrderHistoryDto` incluye `quotedTotal`, `quotedCommission`, `quotedUnitPrice` (snapshot al placeOrder) o solo execution fields? | Útil para auditar discrepancias quoted vs executed. | Cerrada: incluir TODOS los campos relevantes del Order (quoted + executed). El frontend decide cuáles renderizar. |
| Q5 | ¿`/dashboard/snapshot` requiere ser usuario premium? | No, dashboard es feature core MVP, no premium-only. | Cerrada: no. |
| Q6 | ¿El sort fijo de `/orders` es `submitted_at DESC` o `executed_at DESC`? | submitted_at porque executed_at puede ser null para pending/failed. | Cerrada en §6.3: submitted_at DESC. |
| Q7 | ¿Empty sparkline con `text-slate-400 "—"` consume la misma anchura que la sparkline normal para no romper layout? | Sí, mantener width=100px con flex/grid alignment. | Detalle frontend, no requiere SPEC. |
| Q8 | ¿`DashboardController` debería emitir métricas micrometer custom (cache hit/miss ratio)? | Útil pero deuda menor MVP. | Cerrada §9.4: deferred. |

---

## 15. Definition of Done específica de esta spec

### Backend

- ☐ **NO se crea migración Flyway nueva** (C13).
- ☐ `DashboardController` nuevo con 1 endpoint `GET /api/v1/dashboard/snapshot`, `@AuthenticationPrincipal AuthenticatedUser principal` (NO `User` entity — convención del proyecto registrada en memory).
- ☐ `OrderHistoryController` nuevo con 1 endpoint `GET /api/v1/orders`, params `Optional<String> ticker`, `Optional<OrderSide> side`, `Pageable pageable` (Spring Data).
- ☐ `DashboardService` orquesta cache + market data + equity.
- ☐ `OrderHistoryService` orquesta filtros + paginación.
- ☐ `PortfolioService.getAccountEquity(userId, prices: Map<String, BigDecimal>): AccountEquityDto` agregado.
- ☐ `OrderRepository` extiende `JpaSpecificationExecutor<Order>`.
- ☐ `OrderSpecifications` factory estática con `byUser`, `byTicker`, `bySide` que devuelven `Specification<Order>` componibles.
- ☐ Cache Redis implementado según D-CACHE-LOCATION + D-CACHE-IMPL. Keys con prefijo `market-data:price:`. TTL 30s.
- ☐ `MarketDataAdapter` extendido con `getIntradayBars(ticker): List<IntradayBar>` (o nuevo `MarketDataBarsAdapter` separado, decisión en plan).
- ☐ 7+ DTOs en `co.edu.unbosque.bloomtrade.dashboard.dto.*` y `trading.history.dto.*` con `@Schema` Swagger.
- ☐ Mappers manuales (`DashboardMapper`, `OrderHistoryMapper`) con BigDecimal stringification.
- ☐ `GlobalExceptionHandler`: agregar handler si `ConstraintViolationException` o `MethodArgumentTypeMismatchException` no están cubiertos para el `side=FOO` inválido.
- ☐ Logs INFO al entrar a cada endpoint con `userId` + latency + cacheBuckets.
- ☐ Swagger UI muestra ambos endpoints con request/response docs.
- ☐ **NO se agregan audit events nuevos** (C12).

### Frontend

- ☐ Ruta nueva `/dashboard` protegida (envuelta en `<ProtectedRoute>`).
- ☐ `DashboardPage` ensambla `EquityCard + TickerGrid + RecentOrdersWidget + MarketDataBanner`.
- ☐ `useDashboardSnapshot` con `refetchInterval: 30000` + `refetchIntervalInBackground: true`.
- ☐ `useOrdersRecent` para widget últimas 10.
- ☐ `dashboardApi.ts`, `ordersApi.ts` wrappers HTTP.
- ☐ `types/api.ts` extendido con 7+ types nuevos.
- ☐ `messages.es.ts` con copys de §12.2.
- ☐ `AppHeader` con link "Dashboard" (probablemente primero).
- ☐ `Sparkline` component reusable basado en recharts.
- ☐ Banner condicional para los 3 estados de `marketDataAvailable`.
- ☐ Widget colapsable con `<details>` o estado React.
- ☐ Empty states tanto para tickers (caso degradado) como para órdenes (sin historial).
- ☐ P&L con color + icono ▲/▼ (a11y).
- ☐ Botón refresh manual invalidando `['dashboard']` queryKey.
- ☐ `npm run build` verde.

### Tests

- ☐ `DashboardServiceTest` unit: ≥5 escenarios (cache miss happy, cache hit happy, mixto, Alpaca caído fallback, sin posiciones equity).
- ☐ `OrderHistoryServiceTest` unit: ≥4 escenarios (sin filtros paginado, filter ticker, filter side, filter combinado).
- ☐ `OrderSpecificationsTest` unit: ≥3 escenarios (predicates compuestos).
- ☐ `DashboardMapperTest` unit: ≥4 escenarios (toTickerDashboardDto happy, toTickerDashboardDto con price null, equity con posiciones, equity sin posiciones).
- ☐ `OrderHistoryMapperTest` unit: ≥3 escenarios (toOrderHistoryDto ejecutada, pending, failed).
- ☐ `CachedMarketDataAdapterTest` unit: ≥4 escenarios (cache hit, cache miss → set, Alpaca error no cachea null, TTL respeta).
- ☐ `DashboardControllerIT` integración con MockMvc + WireMock + Redis real (perfil test): ≥4 escenarios (AC-01 cache miss, AC-02 cache hit, AC-03 Alpaca down, AC-06 cross-user).
- ☐ `OrderHistoryControllerIT` integración: ≥5 escenarios (AC-01 paginado, AC-02 filter ticker, AC-04 filtros combinados, AC-05 cross-user, AC-06 side inválido).
- ☐ `mvn verify` verde — target ≥300 tests totales (286 actuales + ~20 nuevos).

### E2E manual (HITO 5 humano)

- ☐ Login → /dashboard → ver card equity con datos del usuario + grid 25 tickers + sparklines + widget órdenes.
- ☐ Polling 30s: dejar la pestaña abierta y verificar (a) que después de ~30s se ve un refresh implícito en Network tab; (b) que el card "Última actualización" actualiza.
- ☐ Click botón ↻ → re-fetch inmediato.
- ☐ Simular Alpaca down (DNS bloqueado a data.alpaca.markets) → ver banner amarillo + tabla con "—".
- ☐ Verificar Redis: `docker exec bloomtrade-redis redis-cli KEYS "market-data:*"` muestra los 25 keys con TTL.
- ☐ Operar en /trade y volver a /dashboard → widget órdenes refrescado con la nueva entry.

### Documentación / cierre

- ☐ `APRENDIZAJES.md` sección "Día 9 — HU-F18+F17" en primera persona.
- ☐ `AGENTS.md` handoff actualizado con cierre del bundle + estado deudas (#19 cubierta por C6; #15 oportunidad de cierre si se aprovecha el momento; #16 `InvalidSideException.sideNotYetImplemented()` dead code borrar en este bundle).
- ☐ `plan.md` con decisiones D1–Dxx (D-CACHE-LOCATION, D-CACHE-IMPL, D-CACHE-KEY-FORMAT, D-CACHE-STALE-ON-ERROR, D-SPARKLINE-EMBEDDING, D-SPARKLINE-COST, D-SPARKLINE-CACHE, D-DASHBOARD-MODULE, D-EQUITY-CALCULATION-PLACE, D-ORDER-FILTERS-IMPL, D-EMPTY-SPARKLINE, D-FRONTEND-LAYOUT-DASHBOARD) + decisiones emergentes durante implementación en §2.4.
- ☐ `tasks.md` con descomposición granular T1.x–TN.x por lote.
- ☐ Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f18-f17.txt`.
- ☐ Sin cambios a `STACK.md` (sin nuevas libs — Redis ya en pom, recharts ya en package.json) ni a `ARCHITECTURE.md` (el módulo `dashboard/` ya existe en estructura).

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-24 | Versión inicial del bundle | SPEC bundle para cerrar Sprint 2 con dashboard + historial. F18 es Must del MVP; F17 se promueve desde post-MVP siguiendo ROADMAP §3.4 por ir a tiempo tras F09/F10/F16+F21. Bundle por cohesión UI (`/dashboard` compartido con widget de órdenes). 13 decisiones cerradas pre-redacción (C1–C13) tras cuestionario con humano: tabla 25+sparkline+P&L card, widget embebido sin página dedicada, polling 30s simple, cache Redis TTL 30s (cubre deuda #19), reuso `MarketDataOrchestrator`+`AllowedTickers`. 12 decisiones diferidas a plan (D-CACHE-LOCATION, D-CACHE-IMPL, D-CACHE-KEY-FORMAT, D-CACHE-STALE-ON-ERROR, D-SPARKLINE-EMBEDDING, D-SPARKLINE-COST, D-SPARKLINE-CACHE, D-DASHBOARD-MODULE, D-EQUITY-CALCULATION-PLACE, D-ORDER-FILTERS-IMPL, D-EMPTY-SPARKLINE, D-FRONTEND-LAYOUT-DASHBOARD). Cubre deuda viva #19 del handoff F16+F21 (cache Redis) + oportunidad de cierre de #15 (filtrar SELL por posiciones) y #16 (borrar dead code `sideNotYetImplemented`). |

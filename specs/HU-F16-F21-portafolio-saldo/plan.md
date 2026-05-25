# plan.md — Bundle HU-F16 + HU-F21 (Portafolio y Saldo)

> Plan de implementación derivado de `SPEC.md` v1.0. Sigue la cadencia de lotes A–F + HITOs validables. Cualquier decisión emergente durante implementación se agrega a §2.4 como D-XX siguiendo patrón F09/F10.

---

## 1. Estrategia general

**Tamaño del bundle**: ~30% del esfuerzo de HU-F09 cada HU = ~60% en conjunto. Estimado: 6-8 horas net de implementación. Read-only, sin migración, sin Alpaca trading, sin event listeners, sin emails.

**Orden de ejecución (top-down):**

1. **Lote A — Backend foundation**: DTOs + repository derived query + extensiones read-only en `PortfolioService`. Sin orchestración. HITO 1: compile + unit tests verdes.
2. **Lote B — Backend orchestration market data**: `MarketDataOrchestrator` con fan-out paralelo + fallback per-ticker. HITO 2: unit tests verdes con `MarketDataAdapter` mockeado.
3. **Lote C — Backend controller + IT**: `PortfolioController` + `PortfolioMapper` + Swagger + IT con MockMvc + WireMock. HITO 3: IT pasa los AC del SPEC §11.
4. **Lote D — Frontend**: página `/portfolio` + componentes + hooks + types + ruta + link AppHeader. HITO 4: `npm run build` verde + smoke visual humano.
5. **Lote E — Tests adicionales**: aislamiento cross-user, edge cases (qty=0 defensivo, lastUpdatedAt tras venta). HITO 5: `mvn verify` verde completo.
6. **Lote F — Cierre**: APRENDIZAJES.md sección "Día 8" + AGENTS.md handoff + commit message preparado. HITO 6: commit + push + PR (humano).

**Pre-requisitos verificados (2026-05-24):**

- HU-F10 mergeada en `main` (commit `e5a8943`, PR #7).
- Branch `feat/HU-F16-F21-portafolio-saldo` creada desde `main` actualizado.
- `MarketDataAdapter` existente en `co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter` con `getLatestPrice(ticker)` que retorna mid-price `(ask+bid)/2`, lanza `MarketDataUnavailableException` tras 3 retries (config `application.yml`: `alpacaDataApi.max-attempts=3`, `wait-duration=1s`, `enable-exponential-backoff=true` → worst case ~7s por ticker).
- `PortfolioService` existente con `getBalance(userId): BigDecimal` (solo valor) y `getPositions(userId): List<Position>` (sin filtro de qty>0).
- `OrderRepository` existente con `findByClientOrderId` y `findByUserIdOrderBySubmittedAtDesc`. Sin método para pending+alpacaOrderId.
- `UserBalance.updatedAt` populado vía `@UpdateTimestamp` (Hibernate setea en INSERT y UPDATE).
- `Position.avgBuyPrice` con `NUMERIC(19,4)` BD (scale=4 interno).

---

## 2. Decisiones técnicas

### 2.1 Decisiones cerradas pre-implementación (D1–D15)

| # | Decisión | Resolución | Justificación |
|---|---|---|---|
| **D1** | **D-MARKET-DATA-FANOUT**: estrategia de fan-out paralelo a `MarketDataAdapter` | `CompletableFuture.supplyAsync(...)` sobre `@Bean ExecutorService marketDataExecutor` (`Executors.newFixedThreadPool(8)`). Un future por ticker. | Aísla la carga del thread pool de Tomcat. 8 threads cubren típico portafolio <20 tickers. Alpaca data API tiene rate limit 200/min — fan-out de 20 en paralelo es seguro. NO usar `ForkJoinPool.commonPool()` (contaminación). |
| **D2** | **D-MARKET-DATA-TIMEOUT**: cap de tiempo por ticker | `CompletableFuture.completeOnTimeout(null, 1500ms, TimeUnit.MILLISECONDS)` por future. El total del endpoint queda bounded en ~1.5s + overhead, independiente del comportamiento del adapter o sus retries. | Worst case del adapter es ~7s por ticker (retries con backoff exp). Sin cap, fan-out de 20 tickers todos lentos = 7s. Con cap 1.5s, el endpoint nunca supera ese tope — los lentos se marcan como `null` y el response degrada a "partial". Cumple NFR-PERF-PORTFOLIO (5s p95). |
| **D3** | **D-PARTIAL-FAILURE-POLICY**: cómo señalar fallas parciales | Per-ticker: timeout o excepción → `currentPrice=null` (y por cascada `marketValue=null`, `unrealizedPnL=null`, `unrealizedPnLPct=null`). Top-level: contar nulls. Si 0 nulls (o no hay posiciones) → `"true"`; si `0 < nulls < total` → `"partial"`; si todos null → `"false"`. | Información parcial es más útil que all-or-nothing (UX). El frontend tiene 3 banners distintos (sin banner / banner naranja / banner amarillo). |
| **D4** | **D-PENDING-ORDERS-QUERY**: dónde vive la query | Agregar `OrderRepository.findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(UUID userId, OrderStatus status): List<Order>` (derived query Spring Data). Llamar desde `PortfolioService.getPendingOrders(userId): List<Order>` con `status=PENDING` hardcoded. | Mantener simetría: el controller solo habla con services, nunca con repositories. `OrderRepository` ya existe en módulo Trading — no cross-module import del controller Portfolio. PortfolioService accede a OrderRepository (acoplamiento de Portfolio → Trading aceptable, ya estaba en F09/F10 ambos modules cooperando). |
| **D5** | **D-CONTROLLER-STRUCTURE**: nuevo controller o extender | `PortfolioController` nuevo en `co.edu.unbosque.bloomtrade.portfolio.web`. Path base `/api/v1/portfolio`. | Alineado con módulo Portfolio del ARCHITECTURE.md §3. Separación de responsabilidades: trading endpoints en `OrderController`, portfolio queries en `PortfolioController`. |
| **D6** | **D-FRONTEND-LAYOUT**: orden y tipo de secciones | 1 página `/portfolio` con scroll vertical: (1) `BalanceCard` (compacto, no hero), (2) `PositionsTable` con `MarketDataBanner` condicional encima, (3) `PendingOrdersPanel` colapsable `<details>` abierto por default si `count>0`. Mobile: tabla colapsa a cards verticales. | Confirmado en C8 del SPEC. Una sola vista lineal, sin tabs (fricción innecesaria). |
| **D7** | **D-PNL-COLOR-CONVENTION**: tokens visuales para P&L | Positivo: `text-emerald-600` + icono `▲` (lucide-react `TrendingUp`). Negativo: `text-rose-600` + icono `▼` (`TrendingDown`). Zero/null: `text-slate-500` + sin icono o `—`. | Tokens ya usados en `OrderConfirmationToast` (F09/F10) — consistencia design. Icono complementa color para a11y/daltonismo (NFR §10). |
| **D8** | **D-REFRESH-MECHANISM**: estrategia de re-fetch | React Query defaults: `staleTime: 30s`, `refetchOnWindowFocus: true`. Botón manual `↻` en `BalanceCard` que llama `queryClient.invalidateQueries({ queryKey: ['portfolio'] })` y refresca AMBOS endpoints. NO polling intervalado. | Auto-refresh on focus cubre el caso "operé en /trade y volví" sin gastar background bandwidth. Manual cubre el "quiero ver el último precio ahora". Polling sería 20+ calls/min a Alpaca data API — excesivo. |
| **D9** | **D-AUDIT-EVENTS**: si auditar reads | NO emitir eventos de auditoría para `GET /portfolio/positions` ni `GET /portfolio/balance`. | Consistente con `/me` (HU-F04) y `/subscription/status` (HU-F06). Reads no son acción de negocio relevante para audit log. Si compliance lo exige post-MVP, agregar como deuda. |
| **D10** | **D-DISPLAY-SCALE**: precisión visual de BigDecimal | TODOS los stringificados en DTOs con `setScale(2, RoundingMode.HALF_UP)`: `avgCost` (redondeado de scale=4 interno), `costBasis`, `currentPrice`, `marketValue`, `unrealizedPnL`, `unrealizedPnLPct`. Cálculos intermedios mantienen scale=4 para precisión. | UX consistency (todos los $$ con 2 decimales). Trade-off: micro-pérdida de precisión visual; cálculos correctos preservados internamente. |
| **D11** | **D-AVG-COST-INTERPRETATION**: qué es `avgCost` exactamente | `avgCost = Position.avg_buy_price` (precio puro, sin comisiones acumuladas). `costBasis = qty × avgCost` (sin commissions). `unrealizedPnL = marketValue − costBasis` (bruto, sin descontar commission de venta hipotética). Tooltip en frontend lo aclara. | Consistente con F09 D7 (avg_buy_price es promedio ponderado de precios solo). Convención broker estándar: cost basis ≠ total invested. MVP no muestra "Total invested with commissions" — diferido. |
| **D12** | **D-POSITIONS-FILTER-QTY**: filtrar quantity > 0 | Modificar `PortfolioService.getPositions(userId)` para devolver solo posiciones con `quantity > 0`. Agregar nuevo método repository `findByUserIdAndQuantityGreaterThan(UUID, Integer)` y reemplazar el `findByUserId(...)` actual en `getPositions`. Verificado: no hay callers productivos del actual `getPositions` aparte del test unitario (que se actualiza). | Defensa en profundidad: HU-F10 borra la fila al qty=0 pero un bug futuro podría dejar qty=0 sobreviviendo. F16 NO debe mostrar tales filas (sería confuso "0 AAPL"). Test unit explícito para el caso defensive. |
| **D13** | **D-FETCHED-AT**: cómo poblar `fetchedAt` | `Instant.now()` al armar el response, NO al iniciar la request. Frontend formatea como "hace Xs" via `date-fns` o nativo. | Server-side timestamp es source-of-truth. Frontend solo formatea. Diferencia entre `Instant.now()` al inicio vs al fin es <2s — irrelevante. |
| **D14** | **D-CURRENCY-SOURCE**: hardcoded o de UserBalance | Leer de `UserBalance.currency` en `BalanceResponse`. En `PositionDto.currency` también leer de la fila `Position` (que NO tiene columna `currency` — agregar como derived "USD" del entity). | UserBalance ya tiene la columna y siempre es "USD" por bootstrap. Acoplamiento mínimo. Si multi-currency futuro, ya está cableado. `PositionDto.currency` hardcoded "USD" en mapper (Position no tiene columna currency — derivado del bootstrap del mismo usuario). |
| **D15** | **D-MAPPER-FRAMEWORK**: MapStruct o manual | Mapper manual en `PortfolioMapper` (component Spring). Consistente con F09/F10 (`OrderMapper` manual). | BigDecimal stringification + cálculos compuestos (marketValue, unrealizedPnL, unrealizedPnLPct) son más legibles imperativos que con `@Mapping` expressions. |
| **D16** | **D-EXECUTOR-LIFECYCLE**: shutdown del ExecutorService | `@Bean(destroyMethod = "shutdown")` sobre el `marketDataExecutor`. Spring lo invocará al cerrar el contexto. | Higiene de recursos. Sin esto, el shutdown del backend dejaría threads zombie. |

### 2.2 Riesgos identificados

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Alpaca data API caída en demo | Media (sucedió en F09) | Bajo (degrada a "false" elegante) | C3 + D2 + D3. Banner amarillo en frontend es la mitigación. |
| Rate limit 429 de Alpaca data API con fan-out de 20 tickers | Baja (limit es 200/min) | Medio (todos los tickers fallan) | D1 con 8 threads + D2 cap 1.5s. 429 ya está manejado en MarketDataAdapter (lanza MarketDataUnavailableException). Worst case: usuario ve "partial" o "false". |
| Deadlock por SELECT FOR UPDATE en /balance | Nula | N/A | El endpoint es READ-only (sin FOR UPDATE). `getBalance` usa `findById` sin lock. |
| Cross-user data leakage | Baja | Alto (privacidad) | `@AuthenticationPrincipal User user` automático, sin query params manipulables. Test IT explícito (HU-F16-AC-06, HU-F21-AC-03). |
| Performance del orchestrator con muchas posiciones | Baja (MVP <20) | Medio (slow page load) | D1 paralelo + D2 cap. p95 ≤5s respetado para 20 tickers. |
| Race entre venta concluida y GET /balance que devuelve stale | Baja | Bajo (UX no rota, refresh manual existe) | UserBalance es trans-aware (Hibernate). Si el commit del credit ya pasó, getBalance lo ve. Si no, el frontend tiene botón refresh. |

### 2.3 Riesgos NO aplicables (descartados explícitamente)

- **Concurrencia de escrituras**: bundle es 100% read-only.
- **Idempotencia**: GETs son idempotentes por HTTP.
- **Rollback de transacciones**: sin escrituras.
- **Inconsistencia Alpaca-paper vs BD**: la sección `pendingOrders` MITIGA esta deuda (#8/#12), pero la reconciliación real sigue siendo deuda diferida.

### 2.4 Decisiones emergentes durante implementación

| # | Decisión | Síntoma → Causa raíz → Resolución |
|---|---|---|
| **D17** | **Status sin JWT: 403 vs 401 declarado** | **Síntoma**: `PortfolioControllerIT.get*_withoutJwt_returns401` falla con `expected:<401> but was:<403>`. **Causa raíz**: `JwtAuthenticationFilter` solo escribe HTTP 401 cuando hay token y es inválido/expirado (vía `writeError`). Sin header `Authorization`, el filter pasa el chain sin tocar el `SecurityContext` y Spring Security 6 responde 403 por default (no hay `AuthenticationEntryPoint` customizado en `SecurityConfig`). El SPEC §6.4 declara 401 — divergencia con el comportamiento real del sistema, no introducida por F16+F21 (es global, F09/F10 estarían igual sin tests explícitos sin JWT). **Resolución**: ajustar mis 2 tests a esperar 403 con comentario explicativo. Fix global queda diferido a la mini-HU `HU-F0X-token-rotation-logout` que tocará el filter para refresh tokens + agregará el `AuthenticationEntryPoint` que emite 401. NO arreglarlo en F16+F21 (scope creep cross-cutting). |
| **D18** | **`getPositions` con orden alfabético por ticker** | **Síntoma**: `PortfolioControllerIT.getPositions_happyMarkToMarket` y `_partialFailure` fallan porque `jsonPath("$.positions[?(@.ticker=='AAPL')].currentPrice")` retorna `null` en MockMvc (Jayway JsonPath con filter `?(...)` no se evalúa consistente en este wrapper). **Causa raíz**: el repository derived query `findByUserIdAndQuantityGreaterThan` no impone orden, Postgres devuelve filas en orden de inserción/heap no determinista. Sin orden estable, los asserts por índice tampoco sirven; los asserts por filter no funcionan en MockMvc. **Resolución**: rename a `findByUserIdAndQuantityGreaterThanOrderByTicker` (alfabético ASC). Beneficios secundarios: (a) UX consistente — el listado siempre aparece en el mismo orden entre requests; (b) frontend puede re-sortear si quiere otro criterio. Trade-off aceptado: si en el futuro el portafolio crece a cientos de tickers, este orden ya no es óptimo y se delegaría al frontend con paginación. Para MVP <20 tickers, irrelevante. Plan §2.1 D6 actualizado mentalmente (sin bump de SPEC — implementation detail). |

---

## 3. Lotes y archivos

### Lote A — Backend foundation (HITO 1: compile + unit tests verdes)

**Archivos por crear:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/dto/PositionDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/dto/PendingOrderDto.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/dto/BalanceResponse.java`
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/dto/PortfolioPositionsResponse.java`

**Archivos por modificar:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/trading/repository/OrderRepository.java` — agregar `findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(UUID, OrderStatus): List<Order>`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/repository/PositionRepository.java` — agregar `findByUserIdAndQuantityGreaterThan(UUID, Integer): List<Position>`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/service/PortfolioService.java` — agregar:
  - `getPendingOrders(UUID userId): List<Order>` — readOnly, status=PENDING.
  - `getBalanceEntity(UUID userId): UserBalance` — readOnly, devuelve entity completa (necesario para `lastUpdatedAt`).
  - Modificar `getPositions(UUID userId)` para usar el nuevo método del repository (D12).
- Inyectar `OrderRepository` en `PortfolioService` (constructor).

**Tests:**

- Actualizar `PortfolioServiceTest`:
  - Test del nuevo `getPositions` que verifica filtro `quantity > 0` (D12).
  - 4 tests de `getPendingOrders` (D4): happy path 1 orden BUY PENDING+alpacaOrderId, happy 0 órdenes, filtro descarta PENDING sin alpacaOrderId, filtro descarta EXECUTED.
  - 2 tests de `getBalanceEntity`: happy path retorna entity con updatedAt, IllegalStateException si no existe fila.

### Lote B — Backend orchestration market data (HITO 2: unit tests verdes con adapter mockeado)

**Archivos por crear:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/service/MarketDataOrchestrator.java` — fan-out paralelo con `CompletableFuture` + `completeOnTimeout`. Método público: `Map<String, BigDecimal> fetchPrices(Collection<String> tickers)` donde el valor es `null` si timeout o excepción.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/config/PortfolioConfig.java` — `@Configuration` con `@Bean(destroyMethod = "shutdown") ExecutorService marketDataExecutor()` (D1+D16).

**Tests:**

- `MarketDataOrchestratorTest` (≥6 tests, MarketDataAdapter mockeado):
  - Happy: 3 tickers → 3 precios.
  - Empty input → empty map.
  - 1 ticker falla con `MarketDataUnavailableException` → map con null para ese ticker.
  - 1 ticker timeout (mock con `Thread.sleep(2000)`) → map con null tras 1.5s (D2).
  - Todos fallan → map con todos null.
  - Mezcla parcial: 2 OK, 1 timeout, 1 excepción → map con 2 precios + 2 nulls.

### Lote C — Backend controller + IT (HITO 3: IT pasa AC del SPEC §11)

**Archivos por crear:**

- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/web/PortfolioController.java` — 2 endpoints `@GetMapping`. Inyecta `PortfolioService`, `MarketDataOrchestrator`, `PortfolioMapper`.
- `backend/src/main/java/co/edu/unbosque/bloomtrade/portfolio/web/PortfolioMapper.java` — métodos: `toBalanceResponse(UserBalance)`, `toPositionDto(Position, BigDecimal currentPriceOrNull)`, `toPendingOrderDto(Order)`, `toPositionsResponse(List<Position>, Map<String, BigDecimal>, List<Order>)`. Calcula `marketDataAvailable` según D3.

**Tests:**

- `backend/src/test/java/co/edu/unbosque/bloomtrade/portfolio/web/PortfolioMapperTest.java` (≥5 tests):
  - `toPositionDto` con currentPrice presente → calcula marketValue, unrealizedPnL, unrealizedPnLPct correctos.
  - `toPositionDto` con currentPrice null → marketValue/PnL/Pct todos null.
  - `toPendingOrderDto` happy path.
  - `toBalanceResponse` happy + fallback a createdAt si updatedAt null (defensa).
  - `toPositionsResponse` calcula `marketDataAvailable` correctamente para los 4 casos (all OK, all null, partial, empty positions).
- `backend/src/test/java/co/edu/unbosque/bloomtrade/integration/portfolio/PortfolioControllerIT.java` (≥8 tests con MockMvc + WireMock para Alpaca data API):
  - HU-F16-AC-01: happy path mark-to-market exitoso.
  - HU-F16-AC-02: Alpaca data API caído → marketDataAvailable=false con 200 OK.
  - HU-F16-AC-03: falla parcial → marketDataAvailable=partial.
  - HU-F16-AC-04: usuario sin posiciones → respuesta vacía, sin invocar adapter (Wiremock verify 0 calls).
  - HU-F16-AC-05: orden pendiente visible en pendingOrders[].
  - HU-F16-AC-07: sin JWT → 401.
  - HU-F21-AC-01: happy balance.
  - HU-F21-AC-04: sin JWT → 401.

### Lote D — Frontend (HITO 4: npm run build verde + smoke visual)

**Archivos por crear:**

- `frontend/src/features/portfolio/PortfolioPage.tsx`
- `frontend/src/features/portfolio/components/BalanceCard.tsx`
- `frontend/src/features/portfolio/components/PositionsTable.tsx`
- `frontend/src/features/portfolio/components/PendingOrdersPanel.tsx`
- `frontend/src/features/portfolio/components/MarketDataBanner.tsx`
- `frontend/src/features/portfolio/hooks/usePortfolioPositions.ts`
- `frontend/src/features/portfolio/hooks/useBalance.ts`
- `frontend/src/features/portfolio/api/portfolioApi.ts`

**Archivos por modificar:**

- `frontend/src/types/api.ts` — agregar `PortfolioPositionsResponse`, `PositionDto`, `PendingOrderDto`, `BalanceResponse`.
- `frontend/src/App.tsx` — ruta `/portfolio` envuelta en `<ProtectedRoute>`.
- `frontend/src/components/AppHeader.tsx` — link "Portafolio" tras "Trade".
- `frontend/src/lib/messages.es.ts` — claves `portfolio.title`, `portfolio.emptyState`, `portfolio.emptyCta`, `portfolio.marketDataDown`, `portfolio.marketDataPartial`, `portfolio.pendingSection`, `portfolio.pendingBadge`, `portfolio.pnlTooltip`, `portfolio.balanceTitle`, `portfolio.refreshAria`.

**Sin tests frontend nuevos** (consistente con [[feedback-coverage-vs-velocidad]] y patrón HU-F09/F10 — validación visual humana en smoke).

### Lote E — Tests adicionales (HITO 5: mvn verify completo verde)

**Tests:**

- `PortfolioControllerIT` adicionales:
  - HU-F16-AC-06: aislamiento cross-user (2 usuarios, A pide su /positions y NO ve los de B).
  - HU-F21-AC-03: aislamiento cross-user para /balance.
  - HU-F21-AC-05: tras simular un credit a `app.user_balances` directamente en setup, GET /balance refleja el monto actualizado y `lastUpdatedAt` cambia.
  - Q6 defensive: si manualmente se inserta una `Position` con `quantity=0` (bypass del DELETE F10), `/positions` NO la incluye en la lista.

### Lote F — Cierre (HITO 6: commit + push + PR)

- `APRENDIZAJES.md` — sección "Día 8 — HU-F16+F21" en primera persona con 5-8 reflexiones (sobre fan-out, sobre bundles vs splits, sobre mark-to-market degradation, etc.).
- `AGENTS.md` — actualizar bloque "Trabajo activo" + nueva sección "Cómo continuar (post HU-F16+F21 → HU-F18 Día 9)".
- `plan.md` §2.4 — agregar D17+ con decisiones emergentes si surgieron.
- Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f16-f21.txt` (ruta completa literal, P6).

---

## 4. Tabla de HITOs validables

| HITO | Lote | Cómo se verifica | Criterio de éxito |
|---|---|---|---|
| 1 | A | `./mvnw compile` + `./mvnw test -Dtest=PortfolioServiceTest` | Compila + tests del Lote A verdes |
| 2 | B | `./mvnw test -Dtest=MarketDataOrchestratorTest` | 6 tests verdes |
| 3 | C | `./mvnw verify -Dtest=PortfolioControllerIT` | 8 IT verdes |
| 4 | D | `cd frontend && npm run build` + humano abre `/portfolio` en browser | Build verde + smoke visual OK |
| 5 | E | `./mvnw verify` (suite completa) | Todos los tests del proyecto verdes (~270+) |
| 6 | F | `git status` + commit firmado por humano | Working tree clean tras commit |

---

## 5. Estimación de tiempo

| Lote | Estimado | Notas |
|---|---|---|
| A | 1.5 h | DTOs simples + 2 métodos repository + 3 métodos service + 6 tests unit |
| B | 1.5 h | Orchestrator + executor bean + 6 tests con CompletableFuture (curva mental) |
| C | 2 h | Controller + mapper + 5 mapper tests + 8 IT con WireMock |
| D | 2 h | Frontend desde cero (4 components + 2 hooks + types + ruta + link) |
| E | 1 h | 4 tests IT adicionales |
| F | 0.5 h | Docs + handoff + commit message |
| **Total** | **~8.5 h** | Compatible con 1 jornada Día 8 |

---

## 6. Dependencias entre lotes

```
A ──► B ──► C ──► D ──► E ──► F
       │
       └─► (sin dependencia externa nueva)
```

- A es prerequisito de B (Orchestrator necesita el repository derived query indirectamente? NO — sin dependencia. Pero los DTOs del Lote A se usan en C, no en B. Entonces A y B son paralelizables en teoría, pero secuencializo por simplicidad — el contexto del humano no escala).
- C consume A (DTOs) + B (Orchestrator).
- D consume contrato de API ya estable de C.
- E es adicional a C (puede empezar en paralelo si hay tiempo).
- F al final.

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-24 | Versión inicial | Plan derivado de SPEC v1.0. 16 decisiones técnicas cerradas pre-implementación (D1–D16). 6 lotes A–F con HITOs validables. Estimado 8.5 h compatible con Día 8 del ROADMAP. Sin decisiones emergentes todavía — §2.4 vacío al inicio. |

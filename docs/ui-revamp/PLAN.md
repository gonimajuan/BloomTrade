# Plan: Revamp UI — `feat/revamp-ui`

> **Esto NO es una HU funcional.** Es un revamp visual del producto previo a la
> demo in-vivo. Documentado acá para preservar el rastro académico de SDD: las
> decisiones de diseño también fueron planificadas y aprobadas, no improvisadas.
> No reemplaza ninguna `specs/HU-FXX/SPEC.md`.

---

## 1. Contexto

Al cierre de HU-F15 (2026-05-27) el MVP queda funcionalmente completo: 9 HUs
del Sprint 2 §2.1 + bonus F17 + F15. El producto **funciona**, pero el UI fue
construido optimizando velocidad — Tailwind defaults, sin design system, sin
toasts (`window.alert/confirm`), sin atmósfera de marca. Próxima evaluación es
demo in-vivo: el profesor ve el producto en pantalla, no lee tests.

Este revamp aplica un design system coherente sobre las 6 pantallas
autenticadas + LoginPage + AppHeader, manteniendo cero cambios en backend,
contratos API, lógica de hooks, o tests existentes.

## 2. Decisiones de diseño aprobadas

| Decisión | Valor | Racional |
|---|---|---|
| Estética | Glassmorphism | Distintiva, premium, se aleja del aesthetic AI genérico (`frontend-design` skill principle) |
| Accent color | Violet (#8B5CF6 violet-500) | No se pisa con emerald (P&L+) ni rose (P&L−). Estética Linear/Vercel. |
| Tipografía | Space Grotesk Variable | Personality geométrica distinta a system fonts; self-hosted vía `@fontsource-variable` |
| Background | 3 orbes blurred animados | Provee "luz" para el glassmorphism; CSS keyframes 24–32s (cero JS loop) |
| Animaciones | framer-motion 11.x | Page transitions, modal scale, stagger, hover lifts (~50kb gz) |
| Toast system | sonner 1.x | Reemplaza `window.alert/confirm` del HU-F15 D35 (deuda viva #33) |
| Responsive | Desktop only (≥1024px) | Foco demo in-vivo; mobile no rompe pero no está pulido |
| AppHeader | Top nav glass + user dropdown | Incremental; no requiere refactor de App.tsx layout |
| Primitives | Propios (NO shadcn/ui) | shadcn aparece "aprobado" en §3.2 línea 137 pero nunca instalado; sus defaults son demasiado neutrales, queremos algo distintivo |

## 3. Dependencias nuevas (patch STACK.md §3.2 + §14)

```
framer-motion                       ^11.x   ~50kb gz   animaciones
sonner                              ^1.x    ~10kb gz   toasts
@fontsource-variable/space-grotesk  ^5.x    ~30kb gz   tipografía
clsx                                ^2.x    ~500B      class utility
tailwind-merge                      ^2.x    ~6kb gz    cn() helper
```

Bundle estimado: 3377 → ~3400 módulos (+~80kb gz total).

## 4. Design tokens (`tailwind.config.js` extend)

- **Font family:** `'Space Grotesk Variable'` first, system fallbacks después.
- **Shadows custom:**
  - `glass-sm` / `glass` / `glass-lg` — sombras suaves + inner ring blanco sutil para depth.
  - `glow-violet` / `glow-violet-sm` — glow del accent en hover/focus.
  - `glow-emerald-sm` / `glow-rose-sm` — glow semántico para P&L destacado.
- **Keyframes:** `orb-drift-1/2/3` (24–32s loops, ease-in-out, transforms compuestos), `fade-in`, `slide-up`.
- **Animations:** wrappers de los keyframes.

P&L (`emerald-400` positivo, `rose-400` negativo) es **inviolable** — convención
semántica del producto.

## 5. Primitives nuevos (`src/components/ui/`)

| Componente | Variantes | Notas |
|---|---|---|
| `Card` | `glass` / `glass-elevated` / `glass-outline` | Base de casi todo el revamp |
| `Button` | `primary` / `ghost` / `destructive` / `subtle`, sizes `sm/md/lg` | `primary` = violet glow |
| `Badge` | `neutral` / `success` / `error` / `warning` / `accent` | Pills pequeñas |
| `Input` | (single variant) | Focus violet ring + glass bg |
| `Modal` | (sizing por prop) | Backdrop blur + scale-in framer; scroll lock + Escape; sin Radix (impl manual) |
| `UserDropdown` | (componente compuesto) | Avatar + nombre + items; click-outside + Escape; sin Radix |

`cn()` util en `src/lib/cn.ts` (clsx + twMerge).

## 6. Lotes (6 hitos)

| Lote | HITO | Contenido | Estimado |
|---|---|---|---|
| A | 1 | Install deps + STACK.md patch + @fontsource + tailwind.config extend + cn() + GlassBackground + App.tsx root | ~30 min |
| B | 2 | Primitives + sonner setup + reemplazo `CancelOrderButton`. Tests vitest mínimos. | ~45 min |
| C | 3 | AppHeader glass + LoginPage hero | ~45 min |
| D | 4 | DashboardPage + PortfolioPage revamp completo + migrar window.alert/confirm restantes a sonner | ~1.5h |
| E | 5 | TradePage + ProfilePage + PremiumPage/Success/Cancel | ~1h |
| F | 6 | `npm run build` + `npm test` + APRENDIZAJES.md "Día 12" + AGENTS.md handoff + commit msg | ~30 min |

**Cadencia:** lotes completos, validación humana al cierre de cada HITO,
no después de cada micro-tarea. (Ver `feedback_cadencia_sdd.md` en memoria.)

## 7. Riesgos identificados

- **R1**: `AnimatePresence` de framer-motion con react-router 6 requiere wrapper específico. Fallback: CSS transitions sin tocar el plan general.
- **R2**: `backdrop-filter` en Dashboard (25 tickers + SparklinePanel) puede tirar FPS. Mitigación: `backdrop-blur` solo en cards top-level, no anidados.
- **R3**: Recharts no toma automáticamente el dark theme. Ajustar `colors` props en `Sparkline` y `SparklinePanel`.

## 8. Scope excluido (NO se toca)

- Backend (cero cambios).
- Tests backend (cero cambios, 410 siguen verdes).
- Lógica de hooks React Query (`useBalance`, `useDashboardSnapshot`, etc.).
- Contratos API.
- Tests vitest existentes (27/27 deben seguir verdes; se suman nuevos para primitives).
- Mobile responsive (<1024px queda como hoy).

## 9. Decisiones emergentes durante implementación

Mismo patrón que `specs/HU-FXX/plan.md` §2.4 — decisiones tomadas DURANTE la ejecución
que el plan original no contemplaba.

| ID | Lote | Decisión | Racional |
|---|---|---|---|
| **D1** | C | Scope extendido a las 4 pages pre-auth (Login + Register + MFA + Terms) + 3 componentes compartidos (PhoneInput, TermsCheckbox, PasswordStrengthIndicator), en lugar de "solo LoginPage" como decía el plan original. | El flujo de onboarding es visualmente cohesivo. Dejar Register/MFA/Terms con look antiguo se sentiría inconsistente al primer login. Esfuerzo extra absorbido (~30 min) sin sacrificar otros lotes. |
| **D2** | D | `BalanceCard` inicialmente usó `as-section` prop inexistente (typo del refactor). Fix: `role="region"`. | El Card primitive es siempre `<div>`; para landmarks ARIA se usa `role`, no polymorphic `as`. Quedaba React warning silencioso por atributo HTML desconocido. Convención del proyecto: no hacer Card polymorphic salvo necesidad real. |
| **D3** | E | `OrderConfirmationToast.tsx` (180 LOC) **borrado** y reemplazado por `sonner.toast.success/info` inline en `TradePage`. El plan original decía "refactor", no borrar. | Tras introducir sonner global en Lote B, el componente custom se volvió redundante. Borrar > refactorear cuando el componente cubre un caso de uso ya resuelto por una decisión previa. Beneficio: unifica el sistema de feedback con el cancel UX de HU-F15 (mismo lugar visual, misma API). |
| **D4** | E | BUY/SELL toggle en `OrderForm` implementado como **segmented pill control** con radio inputs `sr-only` (en lugar de radios browser-default). Patrón reusado en `ProfilePage` para canal de notificación (3 pills). | Native radios son feos cross-browser. Custom controls sin radio nativo pierden semántica para screen readers + keyboard. Pattern `sr-only` + label-as-button resuelve a11y vs estética sin compromiso. Establece convención reusable. |
| **D5** | E | Tickers de interés en `ProfilePage` refactoreados como **pills glass** (checkbox `sr-only` + label seleccionable con border violet). | Mismo patrón de D4 aplicado a checkboxes. El grid original (checkbox + border ligero) se veía denso e ilegible en dark theme. Pills glass son más distintivos y consistentes con el resto del revamp. |
| **D6** | D | Recharts (`Sparkline` + `SparklinePanel`) requirió **tunneo manual** de colors (CartesianGrid, Tooltip, YAxis, Line stroke) porque la lib no detecta dark theme automáticamente. | Era el riesgo previsto **R3** del plan — se materializó como esperado, no fue blocker. Los colors emerald-400/rose-400 (semánticos P&L) preservados; el resto (grid, axis labels) movido a slate-400 y white/8. Tooltip migrado a glass dark con backdrop-blur. |

**Observación meta:** 6 decisiones emergentes en 5 lotes de implementación — más bajo que el promedio
de HUs funcionales (F09=7, F10=5, F15=11). Razonable porque el revamp es de superficie visual, no
introduce comportamientos nuevos. Las decisiones son sobre **forma**, no sobre **lógica**, y la forma
tiene menos edge cases imprevisibles.

## 10. Verificación final (HITO 6)

- `npm run build` verde sin warnings nuevos.
- `npm test` 27/27 (existentes) + nuevos primitives verdes.
- Smoke visual humano (responsabilidad del humano): login → dashboard → portfolio → trade → profile → premium; cancelar una orden y ver el toast sonner.
- APRENDIZAJES.md sección "Día 12 — Revamp UI" con reflexiones técnicas y meta.
- AGENTS.md handoff actualizado con estado pre-merge.
- Commit message en `C:\Users\juang\AppData\Local\Temp\bt-revamp-ui.txt`.

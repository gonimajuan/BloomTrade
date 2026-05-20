# CONVENTIONS.md — BloomTrade

**Convenciones del proyecto | Ingeniería de Software 2 | Universidad El Bosque | 2026**

Este documento define **cómo se trabaja** en este proyecto: estructura del repositorio, política de branches y commits, estilo de código, tests, documentación, y uso de Claude Code. Toda decisión registrada aquí es vinculante para humanos y para agentes (Claude Code).

Complementa a `ARCHITECTURE.md` (qué se construye) y `STACK.md` (con qué se construye).

---

## 1. Estructura del repositorio

**Monorepo único** con backend, frontend, infraestructura y documentación juntos. Razón: equipo de un solo desarrollador, simplifica CI/CD y trazabilidad spec→código.

```
bloomtrade/
├── ARCHITECTURE.md         ← Constitución arquitectónica
├── STACK.md                ← Stack tecnológico vinculante
├── CONVENTIONS.md          ← Este archivo
├── README.md               ← Cómo levantar el proyecto en local
├── ROADMAP.md              ← Plan de sprints y entregables
├── .gitignore
├── .env.example            ← Template de variables de entorno
├── docker-compose.yml      ← Orquestación local completa
├── docker-compose.dev.yml  ← Override para desarrollo
│
├── backend/                ← Spring Boot 3 + Java 21
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│
├── frontend/               ← React 18 + TS + Vite
│   ├── package.json
│   ├── Dockerfile
│   └── src/
│
├── specs/                  ← Specs SDD por feature (ver §10)
│   ├── HU-F01-registro/
│   ├── HU-F02-iniciar-sesion/
│   └── ...
│
├── load-tests/             ← Planes JMeter
│   ├── loadtest_orders.jmx
│   └── ...
│
├── docs/                   ← Diagramas C4, UML, BPMN, evidencias
│   ├── c4/
│   ├── sequence/
│   ├── deployment/
│   └── prompts/            ← Bitácora de prompts a Claude Code
│
└── .github/
    └── workflows/
        └── ci.yml
```

---

## 2. Estrategia de branches — GitHub Flow

### 2.1 Reglas

1. **`main` siempre desplegable.** Todo lo que está en `main` debe poder construirse y arrancarse sin error.
2. **Toda feature se desarrolla en una rama derivada de `main`.**
3. **El merge a `main` es siempre vía Pull Request**, nunca push directo (configurado en branch protection).
4. **Una rama feature por historia de usuario** o por subdivisión clara de una historia muy grande.
5. **Las ramas feature son cortas** — idealmente <3 días de vida.

### 2.2 Naming de ramas

Formato: `tipo/HU-FXX-descripcion-corta-en-kebab-case`

Tipos válidos:
- `feat/` — nueva funcionalidad
- `fix/` — corrección de bug
- `chore/` — tareas de mantenimiento (deps, configs, docs sin cambio funcional)
- `docs/` — solo documentación
- `refactor/` — refactor sin cambio de comportamiento
- `test/` — solo agregar tests

Ejemplos válidos:
- `feat/HU-F02-iniciar-sesion`
- `feat/HU-F09-orden-compra-market`
- `fix/HU-F09-comision-redondeo`
- `chore/upgrade-spring-boot-3-3-1`

Ejemplos inválidos:
- `mi-feature` (sin tipo, sin HU, sin estructura)
- `Juan/login` (no usar nombres de personas)
- `feat/login` (sin referencia a HU)

### 2.3 Branch protection sobre `main`

Configurado en GitHub:
- Requiere PR para mergear
- Requiere status checks pasando: `backend-build-test`, `frontend-build-test`, `sonar-scan`
- Permite que el autor del PR lo apruebe (equipo de uno)
- Linear history obligatorio (sin merge commits — usar **Squash and merge**)

---

## 3. Commits — Conventional Commits

### 3.1 Formato obligatorio

```
<tipo>(<ámbito>): <descripción imperativa, minúscula, sin punto final>

[cuerpo opcional explicando el porqué]

[footer opcional con referencias y trailers]
```

### 3.2 Tipos válidos

Los mismos que las ramas: `feat`, `fix`, `chore`, `docs`, `refactor`, `test`. Adicionalmente:
- `perf` — mejora de rendimiento
- `style` — cambios de formato sin lógica (espacios, comas, etc.)
- `build` — cambios al sistema de build
- `ci` — cambios al pipeline

### 3.3 Ámbito

El **paquete o módulo** afectado en kebab-case. Ejemplos: `auth`, `trading`, `portfolio`, `notification`, `dashboard`, `admin`, `audit`, `integration`, `monitoring`, `frontend`, `infra`, `ci`, `docs`.

### 3.4 Ejemplos correctos

```
feat(auth): implementa endpoint POST /auth/login con JWT

fix(trading): corrige redondeo de comisión a 2 decimales con BigDecimal

refs HU-F09 specs/HU-F09-orden-compra/spec.md

Co-authored-by: Claude <noreply@anthropic.com>
```

```
feat(trading): agrega validación de fondos suficientes en orden de compra

La validación se hace antes de encolar la orden, devolviendo
422 INSUFFICIENT_FUNDS si Total + Comisión > saldo.

refs HU-F09
```

```
chore(deps): actualiza spring-boot a 3.3.2

Sin cambios funcionales.
```

### 3.5 Ejemplos incorrectos

```
update code              ← sin tipo, sin ámbito, descripción inútil
WIP                      ← prohibido, usar branches WIP
arreglos                 ← sin tipo, vago
feat: cambios            ← sin ámbito, descripción vacía
```

### 3.6 Referencias obligatorias

Todo commit funcional (`feat`, `fix`) debe referenciar:
- La HU en formato `refs HU-FXX` o `closes HU-FXX`
- Si existe spec asociada, la ruta: `refs specs/HU-FXX-.../spec.md`

### 3.7 Co-author de Claude Code

**Cuando un commit incluye código generado o sustancialmente sugerido por Claude Code, debe agregarse el trailer:**

```
Co-authored-by: Claude <noreply@anthropic.com>
```

Esto es **obligatorio** y es parte central de la justificación académica del uso de IA. La regla: si abriste Claude Code para ese cambio, va el trailer. Si solo escribiste tú, no va.

---

## 4. Pull Requests

### 4.1 Plantilla obligatoria

Configurada en `.github/pull_request_template.md`. Un PR sin completar todas las secciones no se mergea.

```markdown
## Resumen
<una línea: qué hace este PR>

## Historia de usuario
- HU: HU-FXX — [Título]
- Spec: specs/HU-FXX-slug/spec.md
- Sprint: BT Sprint X

## Cambios principales
- [ ] ...
- [ ] ...

## Cómo probarlo localmente
1. ...
2. ...

## Definition of Done
- [ ] Spec actualizada y vigente
- [ ] Código implementa todos los criterios de aceptación de la HU
- [ ] Tests unitarios para la lógica nueva (cobertura >= objetivo)
- [ ] Tests de integración si toca persistencia o servicios externos
- [ ] Documentación OpenAPI actualizada (si toca endpoints)
- [ ] Logs de auditoría emitidos según ARCHITECTURE.md §12 (si aplica)
- [ ] Migración Flyway nueva (si toca BD), nunca se modifica una existente
- [ ] CI pipeline en verde
- [ ] SonarCloud sin nuevos issues bloqueantes
- [ ] STACK.md actualizado si se introdujo dependencia nueva
- [ ] CONVENTIONS.md actualizado si se cambió alguna convención

## Uso de Claude Code
- [ ] Sí — los commits relevantes incluyen `Co-authored-by: Claude`
- [ ] No

## Notas para el revisor
<cualquier cosa que necesite atención>
```

### 4.2 Política de aprobación

Equipo de un desarrollador → **auto-approval permitido**, pero el PR debe abrirse igual y el pipeline debe pasar. Ningún merge sin pipeline verde, ni siquiera "rapidito".

### 4.3 Tamaño de PR

Objetivo: **<400 líneas de código añadidas/modificadas** por PR. PRs más grandes se descomponen. Un PR enorme es imposible de revisar (incluso por uno mismo) y derrota el propósito SDD.

### 4.4 Strategy de merge

**Squash and merge.** Razones: historia lineal en `main`, un commit por feature, mensaje del squash sigue Conventional Commits.

---

## 5. Estilo de código — Backend (Java)

### 5.1 Formato

- **Indentación:** 4 espacios, nunca tabs
- **Longitud máxima de línea:** 120 caracteres
- **Encoding:** UTF-8
- **Final de línea:** LF (no CRLF)

### 5.2 Herramientas

- **Formateador:** `spotless-maven-plugin` con perfil **Google Java Style**, ejecutado en `mvn verify`
- **Linter:** Checkstyle integrado en build, falla el build si hay violaciones
- **Análisis estático:** SonarCloud (en CI, no local)

### 5.3 Nomenclatura

| Elemento | Convención | Ejemplo |
|---|---|---|
| Paquete | minúsculas, sin underscores | `co.edu.unbosque.bloomtrade.trading` |
| Clase | PascalCase | `OrderOrchestrator` |
| Interfaz | PascalCase, sin prefijo `I` | `OrderRepository`, no `IOrderRepository` |
| Método | camelCase, verbo + sustantivo | `placeBuyOrder`, `validateFunds` |
| Variable | camelCase | `totalAmount`, `commissionPercent` |
| Constante | UPPER_SNAKE_CASE | `DEFAULT_COMMISSION_PERCENT` |
| Enum | PascalCase para tipo, UPPER_SNAKE para valores | `OrderStatus.PENDING`, `OrderStatus.IN_EXECUTION` |
| DTO | sufijo `Request`, `Response`, `Dto` | `PlaceOrderRequest`, `OrderResponse` |
| Excepción | sufijo `Exception` | `InsufficientFundsException` |

### 5.4 Reglas de oro

1. **Dinero siempre con `BigDecimal`.** Cero excepciones.
2. **Inmutabilidad por defecto.** Usar `record` para DTOs. Para entidades JPA, campos `private final` cuando posible.
3. **Lombok sí, pero con criterio:** `@Getter`, `@Builder`, `@RequiredArgsConstructor` están bien. **`@Data` y `@AllArgsConstructor` están prohibidos** en entidades JPA (rompen equals/hashCode con relaciones).
4. **No usar `Optional` como parámetro o como campo** — solo como retorno.
5. **No `null` retornado de métodos públicos** — usar `Optional` o lanzar excepción específica.
6. **`@Transactional` siempre en la capa de módulos**, nunca en controllers ni en repositories.
7. **Inyección por constructor**, nunca por field. Combinar con `@RequiredArgsConstructor` de Lombok.
8. **Logs: nunca `System.out.println`.** Usar SLF4J: `private static final Logger log = LoggerFactory.getLogger(...)`. Niveles: `ERROR` para fallos no recuperables, `WARN` para reintentables o inesperados, `INFO` para hitos de negocio, `DEBUG` para diagnóstico de dev.
9. **No catch genérico de `Exception`** salvo en el handler global (`@RestControllerAdvice`).
10. **Nunca exponer entidades JPA en controllers** — siempre mapear a DTO.

### 5.5 Estructura interna por módulos

Cada paquete de módulos (`auth/`, `trading/`, etc.) sigue esta estructura:

```
trading/
├── controller/        ← @RestController, mapea HTTP a service
├── service/           ← Lógica de negocio, @Service, @Transactional
├── repository/        ← @Repository, interfaces JPA
├── domain/            ← Entidades JPA, value objects, enums
├── dto/               ← Records de request/response
├── mapper/            ← MapStruct mappers entity ↔ dto
└── exception/         ← Excepciones específicas del dominio
```

---

## 6. Estilo de código — Frontend (TypeScript / React)

### 6.1 Formato

- **Indentación:** 2 espacios
- **Longitud máxima de línea:** 100 caracteres
- **Comillas:** dobles para JSX, simples para JS/TS strings (configurado en Prettier)
- **Punto y coma:** sí (configurado en Prettier)

### 6.2 Herramientas

- **Formateador:** Prettier
- **Linter:** ESLint con `@typescript-eslint` y `eslint-plugin-react`
- Ambos corren en pre-commit (vía `husky` + `lint-staged`) y en CI

### 6.3 Nomenclatura

| Elemento | Convención | Ejemplo |
|---|---|---|
| Componente React | PascalCase, archivo igual | `OrderForm.tsx` |
| Hook custom | camelCase con prefijo `use` | `useOrderForm.ts` |
| Función / variable | camelCase | `placeOrder`, `totalAmount` |
| Constante exportada | UPPER_SNAKE_CASE | `DEFAULT_PAGE_SIZE` |
| Tipo / interface TS | PascalCase, sin prefijo `I` o `T` | `Order`, `OrderRequest` |
| Archivo de tipos | `.ts`, kebab-case | `order-types.ts` |
| Archivo de página | PascalCase | `DashboardPage.tsx` |

### 6.4 Reglas de oro

1. **TypeScript estricto:** `strict: true` en `tsconfig.json`. Sin `any` salvo justificación en comentario.
2. **Tipos generados desde OpenAPI** son la fuente de verdad para contratos con backend.
3. **Componentes funcionales con hooks**, nunca clases.
4. **Un componente por archivo.**
5. **Side effects siempre en `useEffect`** o en handlers, nunca en el render.
6. **Estado del servidor via React Query**, no en `useState`.
7. **Formularios con `react-hook-form` + `zod`**, nunca controlados manualmente para todo.
8. **Sin `console.log` en código mergeado.** Usar la utilidad `logger` del proyecto si se necesita logging.
9. **Estilos vía Tailwind utility classes.** Si una clase utility se repite mucho, abstraer en componente.
10. **Accesibilidad:** todo botón es `<button>`, todo link es `<a>` o `<Link>`. Nunca `<div onClick>`.

---

## 7. Tests

### 7.1 Cobertura mínima

| Capa | Cobertura mínima | Verificado por |
|---|---|---|
| Módulos y dominio (lógica de negocio) | **80%** | SonarCloud quality gate |
| Total del proyecto | **60%** | SonarCloud quality gate |
| Controllers | tests de smoke al menos | Revisión |
| DTOs y entidades sin lógica | sin cobertura requerida | — |

### 7.2 Backend — qué se testea

- **Unitarios** (`src/test/java/.../unit/`): clases de `service/` con dependencias mockeadas (Mockito). Una clase de test por clase de producción. Sufijo `Test`. Ejemplo: `OrderServiceTest`.
- **Integración** (`src/test/java/.../integration/`): tests con Spring Context completo, PostgreSQL y Redis vía Testcontainers, APIs externas vía WireMock. Sufijo `IT`. Ejemplo: `OrderFlowIT`.
- **Tests de contrato OpenAPI**: validan que el spec generado contiene los endpoints documentados.

### 7.3 Frontend — qué se testea

- **Componentes** con `@testing-library/react` (Vitest). Probar comportamiento, no implementación.
- **Hooks** custom con `renderHook` de testing library.
- **No se hacen tests E2E en el MVP** (Cypress/Playwright fuera de alcance por tiempo).

### 7.4 Reglas de tests

1. **Un test, una assertion conceptual.** Si necesita 5 asserts, probablemente son 5 tests.
2. **Naming:** `should{ExpectedBehavior}When{Condition}`. Ejemplo: `shouldRejectOrderWhenFundsInsufficient`.
3. **Arrange / Act / Assert** explícito, separado por líneas en blanco.
4. **No tests con `Thread.sleep`** o equivalentes — usar `Awaitility`.
5. **Datos de prueba en builders / factories**, no inline repetido.
6. **Tests independientes** — el orden de ejecución no debe importar, no compartir estado.

---

## 8. Documentación

### 8.1 Qué se documenta y dónde

| Tipo | Ubicación | Formato |
|---|---|---|
| Arquitectura | `ARCHITECTURE.md` | Markdown |
| Stack tecnológico | `STACK.md` | Markdown |
| Convenciones | `CONVENTIONS.md` | Markdown |
| Roadmap y sprints | `ROADMAP.md` | Markdown |
| Setup local | `README.md` | Markdown |
| Specs por feature | `specs/HU-FXX-slug/spec.md` | Markdown |
| Plan por feature | `specs/HU-FXX-slug/plan.md` | Markdown |
| Tareas por feature | `specs/HU-FXX-slug/tasks.md` | Markdown |
| Diagramas C4, UML | `docs/` | PNG/SVG + fuente editable |
| Bitácora de prompts | `docs/prompts/sprint-X.md` | Markdown |
| API contract | Generada por SpringDoc | OpenAPI 3.0 JSON |
| Comentarios de código | En el código | Javadoc / TSDoc para públicos no triviales |

### 8.2 Cuándo se actualiza

- `STACK.md` y `ARCHITECTURE.md`: en el mismo PR que introduce el cambio
- Specs de feature: **antes** de empezar a codificar la feature
- Bitácora de prompts: durante el sprint, no al final

### 8.3 Reglas de comentarios en código

- **El código se explica solo cuando es posible.** Comentarios para el "porqué", no para el "qué".
- **Javadoc para todo método público** de `service/` y todo controller.
- **Los TODOs incluyen referencia:** `// TODO(HU-F12): manejar caso X cuando se implemente`.

---

## 9. Definition of Done — por feature

Una historia se considera **terminada** únicamente cuando:

1. ☐ Existe `specs/HU-FXX-slug/spec.md` cerrada y mergeada en `main`
2. ☐ Todos los criterios de aceptación de la HU están implementados
3. ☐ Tests unitarios cubren la lógica nueva con coverage ≥ objetivo
4. ☐ Tests de integración cubren al menos el camino feliz si la HU toca persistencia o APIs externas
5. ☐ Endpoint documentado en OpenAPI con `@Operation` y `@ApiResponses`
6. ☐ Eventos de auditoría emitidos correctamente (verificable en Kibana en local)
7. ☐ Migración Flyway aplicada (si tocó BD)
8. ☐ Frontend implementado y conectado (si la HU es end-to-end)
9. ☐ El flujo se puede demostrar manualmente arrancando `docker-compose up`
10. ☐ PR mergeado en `main` con pipeline verde y SonarCloud sin issues bloqueantes
11. ☐ HU marcada como **Done** en Jira

---

## 10. Definition of Done — por sprint

Un sprint se considera cerrado cuando:

1. ☐ Todas las HUs comprometidas están en estado **Done** o explícitamente trasladadas al backlog
2. ☐ La rama `main` puede levantarse con `docker-compose up` sin errores
3. ☐ Existe demo manual grabada o documentada del incremento entregado
4. ☐ Sprint Review documentado: qué se entregó, qué quedó pendiente, lecciones
5. ☐ Sprint Retro documentado: qué mejorar para el siguiente
6. ☐ Backlog refinado para el siguiente sprint

---

## 11. Uso de Claude Code

Esta sección es **especialmente crítica** porque la justificación académica del uso de IA depende de su cumplimiento riguroso.

### 11.1 Filosofía

Claude Code se usa como **amplificador**, no como **sustituto** del juicio del desarrollador. El desarrollador:
- **Define** la spec
- **Decide** la arquitectura
- **Revisa** lo que Claude produce
- **Ejecuta** y prueba
- **Firma** cada commit

Claude Code:
- **Implementa** según spec
- **Sugiere** opciones cuando hay ambigüedad
- **Genera** boilerplate, tests, documentación
- **Refactoriza** bajo dirección humana

### 11.2 Flujo SDD obligatorio

Para cada feature (HU):

1. **Humano** escribe `specs/HU-FXX-slug/spec.md` siguiendo plantilla (definida en sprint posterior)
2. **Humano** invoca a Claude Code con la spec para producir `plan.md` técnico
3. **Humano** revisa, ajusta y aprueba el plan
4. **Claude Code** descompone en `tasks.md`
5. **Humano** ejecuta tareas con Claude Code, una por una, revisando cada output
6. **Humano** ejecuta tests, ajusta, refactoriza con Claude Code si hace falta
7. **Humano** abre PR, revisa diff completo, mergea

**Está prohibido saltarse pasos 1–4.** Pedirle a Claude Code "implementa la HU-F09" sin spec ni plan no es SDD — es vibe coding y derrota el propósito académico.

### 11.3 Bitácora de prompts

Por cada sprint se mantiene un archivo `docs/prompts/sprint-X.md` con:

- Prompts no triviales que se le dieron a Claude Code
- Para cada uno: qué se le pidió, qué propuso, qué se aceptó/rechazó/modificó, por qué
- Capturas o transcripciones de conversaciones críticas

Plantilla por entrada:

```markdown
### 2026-MM-DD — HU-FXX — Tarea Y

**Contexto:** [qué estaba haciendo]
**Prompt:**
> [texto del prompt]

**Respuesta de Claude (resumen):** [qué propuso]
**Decisión humana:** [acepté tal cual / modifiqué X / rechacé porque Y]
**Commit asociado:** `abc1234`
```

> **Actualizado 2026-05-19:** consultado el profesor, la bitácora **NO es un entregable prioritario**. Los `spec.md` y `plan.md` por HU son la **principal evidencia SDD** que se evalúa. Esta sección queda como sugerencia opcional para tracking interno del desarrollador, no como artefacto académico evaluable. El esfuerzo que iría a la bitácora se reasigna a calidad y completitud de los SPECs (secciones faltantes, decisiones documentadas, trazabilidad criterios↔artefactos, changelogs).

### 11.4 Acciones permitidas a Claude Code

✅ Generar implementaciones desde specs claras
✅ Escribir tests unitarios y de integración
✅ Sugerir refactors
✅ Explicar código existente
✅ Detectar bugs en revisión
✅ Documentar (Javadoc, TSDoc, OpenAPI annotations)
✅ Configurar herramientas (Maven, Vite, Docker, GitHub Actions)
✅ Migraciones SQL bajo dirección

### 11.5 Acciones prohibidas a Claude Code

🚫 **Tomar decisiones arquitectónicas no contempladas en `ARCHITECTURE.md`** sin discusión explícita registrada en bitácora
🚫 **Introducir dependencias no listadas en `STACK.md`** sin actualizar el documento en el mismo PR
🚫 **Modificar migraciones Flyway ya mergeadas**
🚫 **Hacer commits directamente** — los commits los firma siempre el humano
🚫 **Deshabilitar tests** para "que pase el pipeline" sin justificación documentada
🚫 **Generar código sin tests asociados** para lógica de negocio
🚫 **Usar credenciales reales** en specs, prompts, código o bitácora — siempre placeholders o variables de entorno

### 11.6 Co-author obligatorio

Todo commit cuyo código fue significativamente generado o sugerido por Claude Code lleva el trailer:

```
Co-authored-by: Claude <noreply@anthropic.com>
```

Criterio: si abriste Claude Code para producir o modificar lo que entra en ese commit, va el trailer. Tipear sin asistencia → sin trailer.

---

## 12. Definition of Ready — para empezar a codificar una HU

Antes de pedirle a Claude Code que implemente, la HU debe cumplir:

1. ☐ Existe `specs/HU-FXX-slug/spec.md` con flujos principales y de error claros
2. ☐ Criterios de aceptación traducidos a verificables (ya sean tests automáticos o manuales)
3. ☐ Contratos de datos definidos (request/response shape)
4. ☐ Módulos y componentes del `ARCHITECTURE.md` involucrados están identificados
5. ☐ Eventos de auditoría a emitir están listados
6. ☐ Notificaciones a disparar están listadas (si aplica)
7. ☐ Plan técnico (`plan.md`) revisado por humano

Si falta alguno → la HU no está lista. **Codificar antes de tiempo es la garantía de retrabajo.**

---

## 13. Excepciones y desviaciones

Cualquier desviación de estas convenciones requiere:

1. Justificación escrita en el PR que la introduce
2. Si la desviación se vuelve regla, **actualizar este documento** en el mismo PR

No hay convenciones intocables — pero sí hay convenciones que no se cambian sin documentar el porqué.

---

## 14. Versionado de este documento

Mismo proceso que `STACK.md` y `ARCHITECTURE.md`. Cambios via PR, justificación en commit, historial al final.

### Historial de cambios

| Fecha | Cambio | Razón |
|---|---|---|
| 2026-05-07 | Versión inicial | Cierre de fase de diseño, inicio de implementación SDD |

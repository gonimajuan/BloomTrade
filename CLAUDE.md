# CLAUDE.md — BloomTrade

> **Este archivo es leído automáticamente por Claude Code en cada sesión.** Define las reglas de cómo trabajas en este proyecto. Antes de tocar código, lee este archivo completo y los documentos a los que referencia.

---

## Qué es este proyecto

BloomTrade es una plataforma web de Day Trading que permite a inversionistas operar en cinco mercados internacionales (NYSE, NASDAQ, LSE, TSE, ASX). Proyecto académico de Ingeniería de Software 2, Universidad El Bosque, 2026. Stack: Java 21 + Spring Boot 3 + React 18 + PostgreSQL + Redis + ELK + Docker Compose.

**Modalidad de desarrollo:** Spec-Driven Development (SDD) con asistencia de Claude Code. El desarrollo es solo, con plazo crítico de 2 semanas para el MVP.

---

## Documentos autoritativos (leer en este orden)

1. **`ARCHITECTURE.md`** — qué se construye (módulos, interfaces, tácticas de Bass). **Esto es la constitución del proyecto.**
2. **`STACK.md`** — con qué se construye (tecnologías, versiones, librerías aprobadas)
3. **`CONVENTIONS.md`** — cómo se construye (estilo de código, git, tests, política de uso de Claude Code)
4. **`ROADMAP.md`** — cuándo se construye (sprints, alcance del MVP, plan día por día)
5. **`README.md`** — cómo levantar el proyecto en local
6. **`specs/HU-FXX-slug/spec.md`** — especificación detallada por feature

Toda decisión registrada en estos documentos es **vinculante**. No los modifiques sin pedirme aprobación explícita.

---

## Reglas inviolables

### Sobre arquitectura
1. **No inventes arquitectura.** Si `ARCHITECTURE.md` no describe algo, pregunta antes de improvisar.
2. **Los módulos del sistema son los 9 listados en `ARCHITECTURE.md` §3.** No agregues módulos sin discusión.
3. **Toda comunicación con APIs externas pasa por `IntegrationService`.** Excepción documentada: SMTP a MailHog (ver `ARCHITECTURE.md` §8).
4. **Las interfaces entre módulos son interfaces Java en el mismo JVM** (monolito modular). No introduzcas llamadas HTTP entre módulos internos.

### Sobre dependencias
5. **No introduzcas librerías que no estén en `STACK.md` §2.3 (backend) o §3.2 (frontend).** Si crees que se necesita una nueva, propóntmela primero.
6. **Cualquier cambio a `STACK.md` lo hace el humano**, no tú. Tú propones, yo apruebo, yo edito.

### Sobre código
7. **Sigue `CONVENTIONS.md` al pie de la letra.** Naming, estilo, estructura de paquetes.
8. **BCrypt obligatorio para passwords.** Nunca plaintext. Nunca SHA1/MD5.
9. **`BigDecimal` para todo monto financiero.** Nunca `double` o `float`.
10. **Inyección por constructor**, nunca por field. `@Autowired` en campos está prohibido.
11. **No catches genéricos de `Exception`** salvo en el handler global.
12. **Una migración Flyway ya mergeada NUNCA se modifica.** Siempre se crea una nueva.

### Sobre tests
13. **Toda lógica de negocio nueva requiere tests.** Sin tests no se mergea.
14. **Coverage objetivo:** 80% en servicios y dominio, 60% global (`CONVENTIONS.md` §7.1).
15. **No deshabilites tests para hacer pasar el pipeline.** Arregla el test o pregúntame.

### Sobre git
16. **Cada feature en su propia rama** (`feat/HU-FXX-descripcion`), nunca commits directos a `main`.
17. **Commits siguen Conventional Commits** (`feat(auth): ...`, etc., ver `CONVENTIONS.md` §3).
18. **Los commits los firma el humano siempre.** Tú produces los archivos; yo los reviso, hago `git add` y `git commit`.
19. **Si te ayudé a producir el cambio del commit**, el humano agrega trailer `Co-authored-by: Claude <noreply@anthropic.com>`.

### Sobre prohibiciones específicas
20. **No hagas commits autónomamente.**
21. **No fuerces push.**
22. **No modifiques `.git/` ni configuración de Git sin pedírmelo.**
23. **No ejecutes comandos destructivos** (`rm -rf`, `DROP`, etc.) sin confirmación explícita previa.
24. **No expongas secretos en código.** Variables sensibles van a `.env` (gitignored).

---

## Flujo de trabajo SDD

Para implementar una HU, siempre seguimos este ciclo:

### Paso 1 — Leer la spec
- Lee `specs/HU-FXX-slug/spec.md` completa, especialmente §5 (flujos), §6 (contratos API), §11 (criterios de aceptación) y §15 (Definition of Done).
- Si encuentras ambigüedad, pregúntame **antes** de codificar.

### Paso 2 — Producir un plan
- Crea o actualiza `specs/HU-FXX-slug/plan.md` con: orden de archivos a crear/modificar, dependencias entre tareas, decisiones técnicas concretas (qué clases, qué endpoints, qué tests).
- **Espera mi aprobación del plan antes de escribir código.**

### Paso 3 — Descomponer en tareas
- Crea `specs/HU-FXX-slug/tasks.md` con la lista granular de tareas, cada una verificable independientemente.

### Paso 4 — Implementar tarea por tarea
- Una tarea a la vez. Después de cada tarea me pides que valide antes de seguir.
- Para cada tarea: implementación + tests + verificación de que compila.

### Paso 5 — Verificación final
- Antes de declarar la feature terminada: corre tests, verifica el DoD de §15 de la spec.

**Lo crítico:** no saltes pasos. Pedirte "implementa HU-F09" sin spec ni plan no es SDD, es vibe coding. El propósito del proyecto es demostrar SDD, así que el proceso importa tanto como el resultado.

---

## Convenciones de comunicación entre tú y yo

- **Si no tienes información suficiente, pregunta.** No improvises.
- **Si propones una decisión técnica, justifícala** con referencia a `ARCHITECTURE.md`, `STACK.md`, o `CONVENTIONS.md`.
- **Si encuentras inconsistencia entre la spec y los documentos maestros, párate** y me preguntas. No la "corrijas" silenciosamente.
- **Cuando termines una tarea, dime explícitamente:**
  - Qué archivos creaste/modificaste
  - Qué tests pasaron
  - Qué quedó pendiente

---

## Estructura del repositorio

```
bloomtrade/
├── CLAUDE.md              ← este archivo
├── ARCHITECTURE.md
├── STACK.md
├── CONVENTIONS.md
├── ROADMAP.md
├── README.md
├── docker-compose.yml
├── .env.example
├── .gitignore
├── backend/               ← Spring Boot 3 + Java 21
├── frontend/              ← React 18 + TypeScript + Vite
├── specs/                 ← Specs SDD por feature
│   ├── _template/spec.md
│   ├── HU-F01-registrarse/
│   │   ├── spec.md
│   │   ├── plan.md        ← lo creas durante implementación
│   │   └── tasks.md       ← lo creas durante implementación
│   └── ...
├── docs/                  ← Diagramas C4, secuencia, despliegue
│   └── prompts/           ← Bitácora de prompts (la mantenemos juntos)
├── load-tests/            ← Planes JMeter
└── .github/workflows/     ← CI/CD GitHub Actions
```

---

## Cómo manejamos el contexto entre sesiones

Tu memoria entre sesiones es limitada. Para mantener continuidad:
- **Empezamos cada sesión recordando dónde quedamos** (yo te digo, o reviso `git log` con tu ayuda).
- **Las decisiones técnicas significativas las documentamos** en la spec correspondiente (no en chat).
- **Si una decisión no está en la spec ni en los documentos maestros, no existe.** No asumas que la "recuerdas" de una sesión anterior.

---

## Lo primero que haces cuando abro Claude Code

En tu primera sesión y al inicio de cada sesión nueva:

1. **Lee este `CLAUDE.md`**
2. **Confirma que estás listo** describiendo brevemente: qué es el proyecto, qué estilo arquitectónico usamos, en qué sprint estamos según `ROADMAP.md`.
3. **Espera mi indicación** sobre qué vamos a hacer en esta sesión.

No empieces a leer todos los documentos automáticamente — eso satura tu contexto y limita lo que puedes hacer después. Lee los documentos relevantes a la tarea concreta cuando llegue el momento.

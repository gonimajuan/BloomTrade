# plan.md — HU-F04 + HU-F20 Perfil + Notificaciones (bundle)

> Plan técnico derivado de `specs/HU-F04-F20-perfil-notificaciones/SPEC.md` v1.1.
> Estado: **pendiente de aprobación humana** (SDD Paso 2).

---

## 1. Objetivo

Implementar la pantalla **Mi perfil** y sus dos endpoints `GET /api/v1/me` + `PATCH /api/v1/me`. Es la primera feature del MVP que **ejercita el `JwtAuthenticationFilter` real** introducido en HU-F02-F03 — desde aquí toda HU posterior asume que la cadena de autenticación funciona end-to-end.

Bundle incluye:
- HU-F04 — campos editables del perfil (`nombreCompleto`, `telefono`) + cancelación de edición con confirmación
- HU-F20 — selector de canal de notificación (`EMAIL` / `SMS` / `WHATSAPP`)
- Bonus de habilitación de Día 9: campo `tickersOfInterest` (subset de los 25 activos) que el Dashboard de HU-F18 va a consumir. **Se introduce aquí** para evitar otra migración Flyway en Día 9.

Tamaño esperado: **~1 día completo** (Día 3 del ROADMAP). La feature es pequeña en lógica pero amplia en superficie (3 endpoints OpenAPI + 6 componentes React + tests). El riesgo principal es la UI del grid de tickers (no la persistencia).

---

## 2. Decisiones técnicas concretas

| # | Decisión | Justificación |
|---|---|---|
| **D1** | **Sub-paquete `auth/profile/`** dentro del módulo AuthService (mismo patrón que HU-F06 va a aplicar con `auth/subscription/`). No se crea un módulo nuevo. | SPEC §8.1 nota arquitectónica. La cohesión es alta: el `User` entity ya vive en AuthService. Evita crecer la lista de 9 módulos del `ARCHITECTURE.md` §3. |
| **D2** | **Semántica PATCH**: `UpdateProfileRequest` es un record Java con los 4 campos editables, **todos opcionales (`null` = no enviar)**. En el service: `if (req.nombreCompleto() != null) user.setNombreCompleto(req.nombreCompleto())`. **`null` literal del JSON se trata como "no enviar"**, no como "borrar a null"; ningún campo editable acepta `null` semántico (los de la BD son NOT NULL). | SPEC §6.1.2 dice PATCH parcial. Distinguir `{"nombreCompleto": null}` de campo ausente requeriría `JsonNullable<T>` (no aprobado en STACK.md) o un `Map<String,Object>` (anti-tipado). La simplificación "null = ausente" es estándar JAX-RS PATCH y suficiente para el MVP. Documentado para que el frontend nunca envíe `null` literal. |
| **D3** | **Detección de campos read-only**: `FAIL_ON_UNKNOWN_PROPERTIES=true` global en el `ObjectMapper` de Spring + handler dedicado en `GlobalExceptionHandler` para `UnrecognizedPropertyException` que devuelve `400 READ_ONLY_FIELD_MODIFIED` con `fieldErrors[].field = propertyName`. | SPEC §5.3.5 exige rechazar `email`/`rol`/etc explícitamente, no ignorar silenciosamente. Esta opción es idiomática Jackson, no requiere DTO defensivo con todos los campos read-only declarados, y reporta el nombre exacto del campo intentado. |
| **D4** | **`tickers_of_interest JSONB` ↔ `List<String>`** vía Hibernate 6 nativo: `@JdbcTypeCode(SqlTypes.JSON)` en el field de la entidad. Sin librerías extra (no `hypersistence-utils` ni `hibernate-types`, ambas fuera de STACK.md). | Spring Boot 3.3 viene con Hibernate 6.5; el mapeo JSON es nativo. Mantiene índice GIN funcional (la migración lo crea). |
| **D5** | **Catálogo de tickers como constante única (TAC-M3)**:<br>• Backend: `co.edu.unbosque.bloomtrade.auth.profile.AllowedTickers` — `Set<String>` inmutable + `Map<Market, List<String>>` para agrupación, generado con `Set.of(...)` / `Map.of(...)` (orden insertion-preserved via `LinkedHashMap` factory).<br>• Frontend: `src/constants/tickers.ts` exporta `ALLOWED_TICKERS: Record<Market, readonly string[]>`. | SPEC §12.3 ítem `ALLOWED_TICKERS`. Single source of truth conceptual: ambos derivan del catálogo de `ARCHITECTURE.md` §1. Si se cambian los 25 activos, hay que tocar ambos puntos — el constraint de la migración V3 ya enforce ≤25, y el `@AllowedTicker` validator (D6) enforce contenido. |
| **D6** | **Validación con Bean Validation custom**:<br>• `@AllowedTicker` sobre cada elemento de la lista — usa `AllowedTickers.contains(value)`.<br>• `@NoDuplicates` sobre la lista entera.<br>• `@Size(max=25)` estándar.<br>• `@Pattern("^\\+[1-9]\\d{1,14}$")` (E.164) para teléfono.<br>• `@Size(min=3, max=100)` para nombre. | Bean Validation idiomático. `@AllowedTicker` rechaza con código `INVALID_TICKER`, `@NoDuplicates` con `DUPLICATE_TICKERS`, `@Size` con `TOO_MANY_TICKERS` (cuando el error code es customizado por message). Reusa el mecanismo del `GlobalExceptionHandler` ya establecido en HU-F01 (D10: SCREAMING_SNAKE como message). |
| **D7** | **Detección de cambios efectivos**: el `ProfileService.updateMe()` toma un snapshot del `User` (record `UserSnapshot` con los 4 campos editables) antes del set, aplica los cambios, y compara post-set vs snapshot. La lista `changedFields: List<String>` resultante se usa para (a) decidir si emitir `PROFILE_UPDATED` (vacía → no emite, SPEC §5.2.2), (b) poblar `details.changedFields` del evento, (c) decidir si emitir `NOTIFICATION_CHANNEL_CHANGED`. | Cero ambigüedad sobre qué cambió. El snapshot evita query extra a la BD. La comparación es trivial (4 campos). Encapsulada en un método privado del service. |
| **D8** | **Audit events nuevos (extender `AuditEventType` enum)**: `PROFILE_UPDATED`, `NOTIFICATION_CHANNEL_CHANGED`, `PROFILE_UPDATE_FAILED`. `ACCESS_DENIED` ya existe (HU-F02 D9). El `Auditor.audit(...)` ya existe y se reusa tal cual. | SPEC §9.1. Agrega 3 al enum sin tocar la interfaz `Auditor`. Los detalles van en el `Map<String,Object> details` que `AuditEvent` ya acepta. |
| **D9** | **Path `/api/v1/me`** (no `/api/v1/profile` ni `/api/v1/users/{id}`). El controller resuelve `userId` **exclusivamente** del `SecurityContextHolder` — nunca del path/body/query. `MeController` agrupa GET y PATCH bajo el mismo path. | SPEC §6.1 + §10.1 (anti-tampering). Conveniencia REST: el recurso "el yo autenticado" no necesita id en el path. |
| **D10** | **`isPremium` queda FUERA de scope de este bundle.** El SPEC §6 no lo lista en `UserProfileResponse`; HU-F06 (Día 4) introducirá la columna `is_premium` (computada o materializada) y extenderá el response. | SPEC HU-F06 v1.1 §6.2 explícitamente declara "endpoint modificado por HU-F06". Implementar `isPremium` aquí adelantaría trabajo de HU-F06 sin tener la migración V4 ni el modelo de suscripciones. |
| **D11** | **`AppHeader` link a perfil**: el componente ya existe (HU-F02 Lote H) con el menú "Mi perfil / Cerrar sesión" — el item `Mi perfil` está cableado a un placeholder. Aquí se cambia el `to="#"` por `to="/profile"`. | Cero overhead: el menú visual ya está. Solo navega. |
| **D12** | **React Query cache key**: `['profile', 'me']`. Tras un `PATCH` exitoso, `useUpdateProfile` invalida esa key (`queryClient.invalidateQueries`) y además **actualiza optimistamente** el `AuthContext` si cambió el `nombreCompleto` (el header del app refleja inmediatamente). | SPEC §5.1 paso 22 + §12.1 "Submitting → Success". El optimistic update mantiene la UI consistente sin esperar al re-fetch. |
| **D13** | **Form con `react-hook-form` + `zod`**: schema `updateProfileSchema` paralelo al backend (mismas reglas). `useForm({ defaultValues: profileFromQuery, resolver: zodResolver(updateProfileSchema) })`. El **dirty detection** del propio `react-hook-form` (`formState.isDirty`) dispara el flag para el botón "Guardar" y el modal de descarte. | Mismo patrón LoginForm/RegisterForm. `formState.isDirty` es exactamente lo que necesita SPEC §12.1 "Idle dirty". |
| **D14** | **UI del grid de tickers**: componente `TickersOfInterestSection` orquesta 5 `MarketTickerGroup` (uno por mercado: NYSE, NASDAQ, LSE, TSE, ASX), cada uno con 5 checkboxes. El state interno es un `Set<string>` (re-creado vía `useFieldArray` o controlando manualmente). Contador "X de 25 seleccionados" derivado. Al marcar el 26°, se rechaza con toast. | SPEC §12.1 wireframe + §12.2. Set evita duplicados naturalmente; el `<=25` se enforce en el handler de toggle ANTES del set, no en el render. |
| **D15** | **Modal de descarte**: `DiscardChangesModal` se monta con `useDiscardChangesPrompt(isDirty, onConfirm)`. El hook intercepta (a) `react-router`'s `useBlocker` (v6.4+) y (b) el `beforeunload` del window. Si `isDirty === false`, los listeners no se instalan. | SPEC §5.2.1. `useBlocker` es la API recomendada de v6. Hay un detalle de upgrade: el `react-router-dom` actual del proyecto necesita verificar versión — si está en <6.4, se usa `<Prompt>` deprecado o se inline-rolls un confirm en el handler de cancel/navegación. **Verificar en Lote E**. |
| **D16** | **Coverage objetivo ~60-70%** (continúa [[feedback-coverage-vs-velocidad]] / D17 HU-F01 / D16 HU-F02). Foco: `ProfileService` (cambio-detection, idempotencia, dispatcher de audit events), validadores custom (`AllowedTickerValidator`, `NoDuplicatesValidator`), mapper (no leak de `passwordHash`). Skip: controllers triviales, DTOs sin lógica. | Memoria viva del proyecto. El test crítico de **no leak de `passwordHash`** es no-negociable. |
| **D17** | **PATCH idempotente**: el service compara cambios (D7). Si `changedFields.isEmpty()`, **no emite audit event** y **no incrementa `updated_at`** (se hace un return temprano del flujo). El response sigue siendo 200 con el perfil sin cambios. | SPEC §5.2.2. Evita ruido en Kibana cuando el frontend manda PATCHes idempotentes (usuario presiona "Guardar" dos veces seguidas sin tocar nada). |
| **D18** | **No PII en logs de auditoría**: `PROFILE_UPDATED.details.changedFields` lleva solo nombres de campo (`["nombreCompleto", "telefono"]`), NO los valores. `NOTIFICATION_CHANNEL_CHANGED.details.{from,to}` SÍ lleva valores porque son enums no-PII. Verificación: test parametrizado que asserta que el JSON serializado del audit event no contiene una substring del nombre real del usuario de prueba. | SPEC §9.1 nota + §10.2 constraint. Es la decisión que el SPEC marca como no-negociable. |

---

## 3. Cambios de dependencias

**Backend: NINGUNO.** Todo cubierto por lo aprobado en HU-F01/F02:
- `spring-boot-starter-data-jpa` + Hibernate 6 (incluye `@JdbcTypeCode(SqlTypes.JSON)` nativo)
- `spring-boot-starter-validation` — Bean Validation custom (D6)
- `spring-boot-starter-security` — `SecurityContextHolder` + `JwtAuthenticationFilter` ya en cadena
- `flyway-core` — migración V3
- `mapstruct` — `UserProfileMapper` (record → record)

**Frontend: NINGUNO.** Reusa lo de HU-F02-F03:
- `@tanstack/react-query` — `useProfile`, `useUpdateProfile`
- `axios` + `apiClient` con `jwtInterceptor` (HU-F02 G)
- `react-hook-form` + `zod` + `@hookform/resolvers`
- `react-router-dom` — `useBlocker` (verificar versión en D15)

**STACK.md / CONVENTIONS.md / ARCHITECTURE.md: sin cambios.**

> ⚠️ **Deuda doc-only ya registrada en SPEC v1.1 §8.2**: `ARCHITECTURE.md` §5 todavía lista interfaces con prefijo `I` (`IAuthentication`, `IAudit`, `INotification`, `IPayment`). El SPEC v1.1 alinea con D1 HU-F01 (sin prefijo `I`). Esto NO se arregla en este PR — se difiere a un PR `docs(architecture): aplicar D1 a §5`. NO bloqueante.

---

## 4. Reuso de Día 1-2 y cosas nuevas

**Reutilizado tal cual** (mismo archivo, lo tocamos solo si el cambio se justifica):
- `shared/web/{ErrorResponse, FieldErrorItem, GlobalExceptionHandler, ValidationMessages}` — extender con código `READ_ONLY_FIELD_MODIFIED` + handler `UnrecognizedPropertyException` (D3)
- `audit/{Auditor, AuditEvent, AuditEventType}` — agregar 3 entries al enum (D8)
- `auth/domain/User` — **MODIFICADO**: 2 campos nuevos (`notificationChannel`, `tickersOfInterest`) + getters/setters (Lombok `@Getter @Setter` ya en uso)
- `auth/repository/UserRepository` — sin cambios estructurales (los nuevos campos se cargan con el findById existente)
- `auth/security/{JwtAuthenticationFilter, AuthenticatedUser}` — reuso directo; el principal del `SecurityContextHolder` ya lleva `userId`
- `config/SecurityConfig` — agregar `/api/v1/me/**` como `authenticated()`. Por defecto cualquier ruta no listada es `denyAll` o `authenticated` (verificar configuración real)
- `frontend/src/lib/{apiClient, errorParser, messages.es}` — extender mensajes ES con los códigos nuevos (`READ_ONLY_FIELD_MODIFIED`, `INVALID_TICKER`, `TOO_MANY_TICKERS`, `DUPLICATE_TICKERS`)
- `frontend/src/components/AppHeader` — cambiar `to="#"` por `to="/profile"` en el item del menú (D11)
- `frontend/src/features/auth/context/AuthContext` — exponer un `updateUser(partial: Partial<UserSummary>)` para el optimistic update del header (D12)

**Nuevo** (estructura):

```
backend/src/main/java/co/edu/unbosque/bloomtrade/auth/
└── profile/
    ├── controller/MeController.java
    ├── service/ProfileService.java
    ├── domain/{NotificationChannel, Market}.java               (enums)
    ├── dto/{UpdateProfileRequest, UserProfileResponse}.java    (records)
    ├── mapper/UserProfileMapper.java                           (MapStruct)
    ├── validation/{AllowedTicker, AllowedTickerValidator,
    │              NoDuplicates, NoDuplicatesValidator}.java
    ├── catalog/AllowedTickers.java                             (Set<String> + Map<Market,List<String>>)
    └── exception/{ReadOnlyFieldModifiedException,
                   InvalidTickerException,
                   TooManyTickersException,
                   DuplicateTickersException}.java

backend/src/main/resources/db/migration/
└── V3__user_profile_extension.sql                              (ya descrito en SPEC §7)

frontend/src/
├── features/profile/
│   ├── hooks/{useProfile, useUpdateProfile, useDirtyForm, useDiscardChangesPrompt}.ts
│   ├── schemas/updateProfile.ts                                (zod)
│   ├── components/{
│   │       PersonalInfoSection,
│   │       NotificationChannelSection,
│   │       TickersOfInterestSection,
│   │       MarketTickerGroup,
│   │       SaveCancelBar
│   │   }.tsx
│   └── api/profileApi.ts                                       (wrappers de axios)
├── components/DiscardChangesModal.tsx                          (genérico, reusable)
├── pages/ProfilePage.tsx
├── constants/tickers.ts                                        (ALLOWED_TICKERS por Market)
├── types/profile.ts                                            (Profile, NotificationChannel, Market)
└── App.tsx                                                     (MODIFICADO: + ruta /profile dentro de ProtectedRoute)
```

---

## 5. Hallazgos / deuda a abordar dentro de este bundle

| # | Hallazgo | Acción en este bundle |
|---|---|---|
| G1 | El `AppHeader` (HU-F02 H) ya muestra el item "Mi perfil" en el menú con `to="#"` placeholder. | D11 — cambiar a `to="/profile"`. Cero código nuevo del componente. |
| G2 | El SPEC HU-F04 v1.0 referenciaba `IAuthentication.validateToken()` — interface inexistente. v1.1 corrigió a `JwtAuthenticationFilter` + `SecurityContextHolder`. | Plan ya asume v1.1. Verificado en SPEC §8.2. |
| G3 | El SPEC v1.0 asumía refresh transparente (D18 lo difiere). v1.1 corrigió. | Plan asume v1.1: 401 → redirect a `/login` (sin refresh). |
| G4 | `react-router-dom` versión y disponibilidad de `useBlocker` — no verificado. Si es <6.4, D15 cambia. | **Lote E paso 1: verificar `package.json`**. Si falta upgrade, decisión en Q1 abajo. |
| G5 | `ARCHITECTURE.md` §5 todavía lista interfaces con prefijo `I`. | Deuda doc-only declarada — NO se arregla aquí. |
| G6 | `FAIL_ON_UNKNOWN_PROPERTIES` no está explícitamente configurado en el proyecto. Default Spring Boot 3 es **false**. | Lote C paso 0 — agregar `spring.jackson.deserialization.fail-on-unknown-properties=true` a `application.yml`. Verificar que ningún DTO previo se rompa (HU-F01 RegisterRequest, HU-F02 LoginRequest/MfaVerifyRequest — los 3 tienen schemas estrictos, no debería romper nada). |

---

## 6. Mapeo arquitectónico (SPEC §8) → paquetes

| Componente SPEC | Paquete Java | Notas |
|---|---|---|
| `MeController` | `auth/profile/controller/MeController` | GET + PATCH bajo `/api/v1/me`. Sin sub-paths. |
| `ProfileService` | `auth/profile/service/ProfileService` | `@Transactional` en `updateMe`; `@Transactional(readOnly=true)` en `getMe`. |
| `UserProfileMapper` | `auth/profile/mapper/UserProfileMapper` | MapStruct. Mapea `User` → `UserProfileResponse`. Verifica explícitamente que NO mapea `passwordHash`. |
| `JwtAuthenticationFilter` + `SecurityContextHolder` (SPEC §8.2) | reuso de `auth/security/*` (HU-F02-F03) | Cero cambios. |
| `Auditor` (SPEC §8.2, sin prefijo `I` por D1) | reuso de `audit/Auditor` | Solo se extiende el enum `AuditEventType`. |
| Catálogo de 25 tickers (SPEC §8.4 TAC-M3) | `auth/profile/catalog/AllowedTickers` | Encapsulado. |
| Validadores Bean Validation custom | `auth/profile/validation/*` | `@AllowedTicker`, `@NoDuplicates`. |

---

## 7. Orden de implementación — 7 lotes con HITOs

```
LOTE A — Migración V3 + entidad extendida
  └── V3__user_profile_extension.sql (DDL ya en SPEC §7)
  └── User entity: +notificationChannel (NotificationChannel enum)
                   +tickersOfInterest (List<String> con @JdbcTypeCode(SqlTypes.JSON))
  └── NotificationChannel, Market enums
  └── Spring Boot arranca → Flyway aplica V3 → tablas verdes
                                                      ← HITO 1 (mvn compile + arranque verde)

LOTE B — Catálogo + validadores + audit enum
  └── AllowedTickers (catálogo de 25, agrupados por Market)
  └── @AllowedTicker + AllowedTickerValidator (mensaje: "INVALID_TICKER")
  └── @NoDuplicates + NoDuplicatesValidator (mensaje: "DUPLICATE_TICKERS")
  └── AuditEventType: +PROFILE_UPDATED, +NOTIFICATION_CHANNEL_CHANGED, +PROFILE_UPDATE_FAILED
  └── ValidationMessages: nuevos códigos
                                                      ← HITO 2 (unit tests validadores verdes)

LOTE C — DTO + Mapper + Service + Controller + handler global
  └── application.yml: fail-on-unknown-properties=true (G6)
  └── UpdateProfileRequest record (4 fields, Bean Validation)
  └── UserProfileResponse record (11 fields, todos required excepto los nuevos)
  └── UserProfileMapper (MapStruct, explicit ignore de passwordHash)
  └── 4 excepciones (ReadOnlyFieldModified, InvalidTicker, TooManyTickers, DuplicateTickers)
  └── GlobalExceptionHandler: +handler UnrecognizedPropertyException → READ_ONLY_FIELD_MODIFIED
                              +handlers de las 4 nuevas
  └── ProfileService.getMe()
  └── ProfileService.updateMe() con UserSnapshot + cambio-detection (D7)
  └── MeController GET + PATCH
  └── SecurityConfig: /api/v1/me/** authenticated
                                                      ← HITO 3 (curl GET/PATCH /me con token HU-F02 funciona end-to-end)

LOTE D — Tests backend
  └── Unit (Mockito):
      · ProfileServiceTest — happy GET, happy PATCH (1 field), happy PATCH (todos), 
                              idempotente (sin cambios → no audit), 
                              cambio canal → 2 eventos, cambio tickers válido,
                              error PII no leak (assert JSON details)
      · AllowedTickerValidatorTest — válido, inválido, lower-case, vacío
      · NoDuplicatesValidatorTest — sin duplicados, con duplicados, lista vacía
      · UserProfileMapperTest — no leak passwordHash (assert que el field no existe en el output)
  └── IT (Postgres real perfil test):
      · MeFlowIT — registro (HU-F01) → login + MFA (HU-F02-F03) → token → GET /me 200
                   → PATCH /me solo nombre → 200 → BD verifica updated
                   → PATCH /me canal → assert NOTIFICATION_CHANNEL_CHANGED en log
                   → PATCH /me con email → 400 READ_ONLY_FIELD_MODIFIED
                   → PATCH /me con ticker inválido → 400 INVALID_TICKER
                   → GET /me sin token → 401
  └── OpenApiContractIT (existente) — extender con 2 endpoints
                                                      ← HITO 4 (mvn verify verde, +30 tests aprox)

LOTE E — Frontend infra (constantes + hooks + schema + tipos)
  └── Verificar package.json: react-router-dom >= 6.4 (G4). Si falta, Q1 abajo.
  └── constants/tickers.ts: ALLOWED_TICKERS por Market (5 mercados × 5 tickers)
  └── types/profile.ts: Profile, NotificationChannel, Market
  └── features/profile/schemas/updateProfile.ts (zod, paralelo a backend)
  └── features/profile/api/profileApi.ts (getMe, patchMe)
  └── features/profile/hooks/useProfile.ts (react-query)
  └── features/profile/hooks/useUpdateProfile.ts (mutation + invalidate + optimistic AuthContext)
  └── features/profile/hooks/useDirtyForm.ts (wrapper sobre formState.isDirty con callback)
  └── features/profile/hooks/useDiscardChangesPrompt.ts (useBlocker + beforeunload)
  └── components/DiscardChangesModal.tsx (genérico, recibe título/descripción/CTAs como props)
  └── messages.es.ts: +4 códigos (READ_ONLY_FIELD_MODIFIED, INVALID_TICKER, TOO_MANY_TICKERS, DUPLICATE_TICKERS)

LOTE F — Frontend pages + componentes + routing
  └── features/profile/components/PersonalInfoSection.tsx (read-only + editables)
  └── features/profile/components/NotificationChannelSection.tsx (radio group)
  └── features/profile/components/MarketTickerGroup.tsx (5 checkboxes + label del mercado)
  └── features/profile/components/TickersOfInterestSection.tsx (5 MarketTickerGroups + contador)
  └── features/profile/components/SaveCancelBar.tsx (sticky bottom o inline)
  └── pages/ProfilePage.tsx (orquesta secciones + estado del form + states A-G del SPEC §12.1)
  └── App.tsx: +<Route path="/profile" element={<ProtectedRoute><ProfilePage/></ProtectedRoute>}/>
  └── AppHeader: actualizar item "Mi perfil" → to="/profile" (D11)
                                                      ← HITO 5 (E2E manual: login → menú "Mi perfil" → edita nombre + canal + 3 tickers → guarda → recarga → cambios persistidos)

LOTE G — Tests frontend + cierre
  └── useProfile.test.tsx (fetch happy + 401 invalida)
  └── useUpdateProfile.test.tsx (mutation + optimistic AuthContext update)
  └── ProfilePage.test.tsx (renderiza secciones, botón Guardar deshabilitado al inicio,
                            dirty habilita, submit invoca mutation)
  └── TickersOfInterestSection.test.tsx (contador correcto, +1 ticker, -1 ticker,
                                          intento de 26° rechazado)
  └── DiscardChangesModal flow: dirty + cancelar → modal aparece;
                                clean + cancelar → modal NO aparece
  └── messages.es.ts: snapshot test si aplica
  └── APRENDIZAJES.md sección "Día 3 — HU-F04+F20" (P4)
  └── tasks.md actualizado con marca de completados
  └── PR feat/HU-F04-F20-perfil-notificaciones → main con plantilla CONVENTIONS §4.1
                                                      ← HITO 6 (npm run lint && test && build verdes + PR abierto)
```

---

## 8. Estrategia de tests (CONVENTIONS §7, [[feedback-coverage-vs-velocidad]])

**Unit (Mockito puro) — backend:**

| Clase de prod | Test | Casos críticos |
|---|---|---|
| `ProfileService` | `ProfileServiceTest` | GET happy, PATCH 1 field, PATCH N fields, PATCH idempotente (no audit), cambio canal emite 2 eventos, error PII no aparece en details |
| `AllowedTickerValidator` | `AllowedTickerValidatorTest` | válidos del catálogo, inválido `"FOO"`, sensible a mayúsculas `"aapl"` → falla, null en lista (sí permitido? se decide en D6 — propuesta: rechazar) |
| `NoDuplicatesValidator` | `NoDuplicatesValidatorTest` | `["A","B"]` ok, `["A","A"]` falla, `[]` ok, `null` ok (otro validador maneja) |
| `UserProfileMapper` | `UserProfileMapperTest` | mapeo completo + assertion explícita que ninguna versión del response contiene `passwordHash` o `password_hash` |

**Integración (Postgres real perfil test, perfil HU-F01 D16) — backend:**

| Clase | Cobertura |
|---|---|
| `MeFlowIT` | E2E desde HU-F01-F03 hasta PATCH /me: 8 escenarios siguiendo SPEC §11.1 (consultar, actualizar 1, actualizar N, read-only rechazo, ticker inválido, tickers duplicados, demasiados tickers, sin auth) |
| `MePiiAuditIT` | Disparar PATCHes con nombres/teléfonos reconocibles → query a la tabla local `audit_events` (o ES si habilitado) → assert que `details` JSON no contiene el valor PII (search by substring) |
| `OpenApiContractIT` (extender) | GET y PATCH /me documentados con `@Operation` y `@ApiResponse` |

**Frontend (Vitest + RTL):**

| Componente / hook | Test |
|---|---|
| `useProfile` | fetch ok, 401 (cuando vence token) limpia AuthContext (delegado al interceptor de HU-F02-G — no se duplica el test) |
| `useUpdateProfile` | success invalida cache + optimistic AuthContext update si cambió nombreCompleto |
| `ProfilePage` | render con datos de perfil, botón Guardar deshabilitado al inicio, mostrar 4 secciones |
| `TickersOfInterestSection` | contador, toggle ticker, 26° rechazado, count visual |
| `DiscardChangesModal` (flow integrado) | dirty + cancelar → modal; confirm "Descartar" → reset; modal cancel → permanece |

Skip explícitos:
- `PersonalInfoSection`, `NotificationChannelSection`, `SaveCancelBar`: componentes de presentación pura, test no agrega valor [[feedback-coverage-vs-velocidad]]
- `MeController` unit test: trivial, cubierto por `MeFlowIT`

---

## 9. Trazabilidad criterios de aceptación → artefacto

| Escenario Gherkin SPEC §11.1 | Verificado por |
|---|---|
| "Consulta del perfil autenticado" | `MeFlowIT#shouldGetMe` + manual HITO 5 |
| "Actualización de un solo campo editable" | `ProfileServiceTest#updateSingleField` + `MeFlowIT#patchSingleField` |
| "Cambio de canal de notificación" | `ProfileServiceTest#changeNotificationChannel` (2 eventos emitidos) + `MeFlowIT#patchNotificationChannel` |
| "Actualización combinada de varios campos" | `ProfileServiceTest#patchAllFields` + `MeFlowIT` |
| "PATCH sin cambios efectivos" | `ProfileServiceTest#idempotentPatch` (no audit) |
| "Intento de modificar campo read-only" | `MeFlowIT#patchEmailRejected` |
| "Intento de cambiar rol" | `MeFlowIT#patchRolRejected` |
| "Cancelación de edición con cambios sin guardar" | Frontend test del flujo DiscardChangesModal |
| "Request sin autenticación" | `MeFlowIT#getMeWithoutTokenReturns401` |
| "Request con access token expirado" | `MeFlowIT#getMeWithExpiredTokenReturns401` |
| "Selección válida de tickers de interés" | `ProfileServiceTest#updateTickers` + `MeFlowIT#patchTickers` |
| "Limpiar lista de tickers" | `MeFlowIT#patchTickersEmpty` |
| Esquemas "Validación de tickers" / "telefono" / "notificationChannel" | Tests parametrizados de los validators + handler global |
| §10.2 constraint "GET /me <200ms p95" | Inspección manual o JMeter en HITO 6 |
| §10.2 constraint "no PII en audit" | `MePiiAuditIT` |
| §10.2 constraint "MeController no acepta userId por path/body/query" | Test específico: `MeFlowIT#getMeIgnoresQueryUserId` |

---

## 10. Preguntas abiertas

| # | Pregunta | Propuesta |
|---|---|---|
| **Q1** | `react-router-dom` versión en el `package.json` actual del frontend. Si es <6.4, `useBlocker` no existe; el modal de descarte (D15) tendría que usar (a) un `<Prompt>` deprecado o (b) un confirm inline en el botón Cancel + un `beforeunload` listener manual. | **Verificar en Lote E paso 1**. Si <6.4: opción (b) — Cancel button intercepta `formState.isDirty`, abre el modal y solo navega tras confirmación. `beforeunload` cubre el caso del cierre de pestaña. NO se hace upgrade del router en este bundle (fuera de scope, riesgo de regresión en HU-F02 rutas). |
| **Q2** | El SPEC §12.1 dice "el header del app refleja inmediatamente el nuevo `nombreCompleto` (via actualización del AuthContext)". ¿`updateUser` se agrega al `AuthContext` o cada feature parchea el localStorage del `AuthContext`? | **Propuesta: agregar `AuthContext.updateUser(partial)`** como API pública — más limpio que side-effects desde fuera. Cambio pequeño al provider de HU-F02-G. |
| **Q3** | El catálogo de 25 tickers vive duplicado: backend (Java `Set.of(...)`) y frontend (`ALLOWED_TICKERS`). ¿Generar uno desde el otro? | **No en este bundle.** Generar tickers desde el `/v3/api-docs` (OpenAPI) requiere agregarlos al schema OpenAPI con `enum`, lo cual el SPEC ya hace (`UserProfileResponse.tickersOfInterest.items.enum`). Post-MVP se puede agregar un script `gen:tickers` que extrae el enum del OpenAPI. Por ahora, comentario `// SINCRONIZAR con backend AllowedTickers` en ambos archivos. |
| **Q4** | ¿`PATCH /me` con `tickersOfInterest: null` (literal JSON `null`) debe (a) ser tratado como "no enviar" (D2) o (b) "limpiar la lista a `[]`"? | **Propuesta D2: (a) "no enviar".** Para limpiar, el cliente envía `[]` explícitamente. Documentar este matiz en la OpenAPI description del campo. |

---

## 11. Cronograma

| Bloque | Estimación | Lotes incluidos |
|---|---|---|
| Mañana Día 3 | ~3h | A (migración + entidad), B (validadores + audit), C (DTO+Mapper+Service+Controller) |
| Mediodía Día 3 | ~1h | D (tests backend) — incluye `mvn verify` |
| Tarde Día 3 | ~3h | E (frontend infra), F (frontend pages + E2E manual) |
| Final Día 3 | ~1h | G (tests frontend + APRENDIZAJES + PR) |

**Total: ~8h efectivas** = un día completo de trabajo. Sin margen significativo, pero realista dado que (a) no hay decisiones arquitectónicas abiertas, (b) la lógica de negocio es trivial, (c) se reutilizan mayormente componentes existentes del frontend, (d) la complejidad está en la UI del grid de tickers, que es bien acotada.

**Riesgos:**
- R1 — `useBlocker` no disponible (Q1) → 30 min extra para inline el confirm en Cancel. Aceptable.
- R2 — `@JdbcTypeCode(SqlTypes.JSON)` falla en runtime (incompatibilidad PostgreSQL JSONB vs Hibernate) → fallback a `@Converter` JPA con String. ~1h pérdida pero baja probabilidad (Spring Boot 3.3 + PG 16 es combinación bien probada).
- R3 — `FAIL_ON_UNKNOWN_PROPERTIES=true` rompe algún DTO previo (HU-F01 RegisterRequest) → revisar en Lote C paso 0 ANTES de subir el flag. Si rompe, restringir el strict mode solo al endpoint `/me`.

**Si algo se derrama a Día 4:** se prioriza dejar HU-F04 backend cerrado (PATCH funcional + tests) y se difiere el frontend de HU-F20 (radio group de canal) — el frontend de F04 sí queda. ROADMAP §3.4 ya contempla "cortar HU-F20" como opción de degradación al cierre del Día 5; aplicar el mismo principio aquí si Día 3 no cierra.

---

## 12. Definition of Done de este bundle

Se considera terminado cuando estén verdes todas las casillas de SPEC §15 y, además:

- ☐ 7 lotes implementados, HITOs 1-6 alcanzados
- ☐ `mvn verify` verde (~30 tests nuevos esperados)
- ☐ `npm run lint && test && build` verde
- ☐ E2E manual: registro → login + MFA → menú "Mi perfil" → edita los 4 campos → guarda → recarga → cambios persistidos → cancelar con cambios sin guardar → modal de descarte
- ☐ Inspección manual de `password_hash`: GET /me NO lo devuelve (assertion en test + verificación en Postman/curl)
- ☐ Inspección manual de Kibana (o tabla audit_events local): `PROFILE_UPDATED.details` NO contiene los valores PII
- ☐ Inspección OpenAPI: Swagger UI muestra GET y PATCH /me con todos los códigos de error documentados
- ☐ `AppHeader.tsx`: el link "Mi perfil" navega a `/profile` (no a `#`)
- ☐ Migración V3 aplicada exactamente UNA vez (verificable: `flyway_schema_history` tiene una entry V3)
- ☐ PR abierto con plantilla CONVENTIONS §4.1 + checklist DoD + label `feat`
- ☐ `APRENDIZAJES.md` con sección "Día 3 — HU-F04+F20" ([[feedback-actualizar-aprendizajes]])
- ☐ `AGENTS.md` "Trabajo activo" actualizado al cierre

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-20 | Versión inicial | Plan técnico derivado de SPEC v1.1 |

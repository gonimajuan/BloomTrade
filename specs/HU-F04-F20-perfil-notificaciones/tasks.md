# tasks.md — HU-F04 + HU-F20 Perfil + Notificaciones (bundle)

> Descomposición granular del `plan.md` (SDD Paso 3).
> Cadencia: lotes lógicos, validación en HITOs (no tras cada archivo) [[feedback-cadencia-sdd]].
> Rama: `feat/HU-F04-F20-perfil-notificaciones`. Commits con `refs HU-F04 HU-F20 specs/HU-F04-F20-perfil-notificaciones/SPEC.md` + `Co-authored-by: Claude <noreply@anthropic.com>`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho · ✗ cancelado/diferido

---

## Lote A — Migración Flyway V3 + entidad extendida

- ☐ **T1.1** `backend/src/main/resources/db/migration/V3__user_profile_extension.sql` — DDL completa del SPEC §7.2: `ALTER TABLE app.users ADD COLUMN notification_channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL'` + `ADD COLUMN tickers_of_interest JSONB NOT NULL DEFAULT '[]'::jsonb` + 2 check constraints (`chk_users_notification_channel`, `chk_users_tickers_count`) + índice GIN `idx_users_tickers_of_interest`.
- ☐ **T1.2** `auth/profile/domain/NotificationChannel.java` — enum `{EMAIL, SMS, WHATSAPP}`.
- ☐ **T1.3** `auth/profile/domain/Market.java` — enum `{NYSE, NASDAQ, LSE, TSE, ASX}` (Lote B y frontend lo reusan).
- ☐ **T1.4** `auth/domain/User.java` MODIFICADO: + `@Enumerated(STRING) @Column(name="notification_channel") NotificationChannel notificationChannel` y + `@JdbcTypeCode(SqlTypes.JSON) @Column(name="tickers_of_interest", columnDefinition="jsonb") List<String> tickersOfInterest`. Getters/setters Lombok. Defaults inicializados en el constructor para entities nuevas (`EMAIL`, `new ArrayList<>()`).
- ☐ **T1.5** Arrancar Spring Boot localmente (`./mvnw spring-boot:run`) → verificar que Flyway aplica V3 (mirar `flyway_schema_history` en Postgres del compose). **← HITO 1** (`mvn compile` verde + arranque sin errores de Flyway/Hibernate sobre el JSONB).

## Lote B — Catálogo + validadores + audit enum

- ☐ **T2.1** `auth/profile/catalog/AllowedTickers.java` — `Set<String>` inmutable (`Set.of(...)`) con los 25 + `Map<Market, List<String>>` (`LinkedHashMap.of(...)` o un `static final` builder) preservando el orden de SPEC §1.1: NYSE [AAPL,MSFT,JNJ,JPM,XOM], NASDAQ [GOOGL,AMZN,META,TSLA,NVDA], LSE [HSBA,BP,GSK,ULVR,BARC], TSE ["7203","6758","9984","8306","6861"], ASX [BHP,CBA,CSL,WES,WOW]. Método `contains(String)` y `byMarket()`.
- ☐ **T2.2** `auth/profile/validation/AllowedTicker.java` — anotación `@Target({ELEMENT_TYPE})`, `@Constraint(validatedBy=AllowedTickerValidator.class)`, `message="INVALID_TICKER"`.
- ☐ **T2.3** `auth/profile/validation/AllowedTickerValidator.java` — `isValid` que consulta `AllowedTickers.contains(value)`. `null` se considera válido (otro validador `@NotNull` maneja eso si aplica).
- ☐ **T2.4** `auth/profile/validation/NoDuplicates.java` — anotación análoga, `message="DUPLICATE_TICKERS"`.
- ☐ **T2.5** `auth/profile/validation/NoDuplicatesValidator.java` — `isValid(List<?> list)` que compara `list.size() == new HashSet<>(list).size()`. Null y vacío → válidos.
- ☐ **T2.6** `audit/AuditEventType.java` MODIFICADO: + `PROFILE_UPDATED`, `NOTIFICATION_CHANNEL_CHANGED`, `PROFILE_UPDATE_FAILED`. (ACCESS_DENIED ya existe.)
- ☐ **T2.7** `shared/web/ValidationMessages.properties` MODIFICADO: + `READ_ONLY_FIELD_MODIFIED=El campo '{0}' no puede ser modificado desde el perfil`, + `INVALID_TICKER=El ticker '{0}' no está en el catálogo permitido`, + `TOO_MANY_TICKERS=No puedes seleccionar más de 25 tickers`, + `DUPLICATE_TICKERS=La lista de tickers no puede contener duplicados`. **← Lote B verde** (`mvn -Dtest=AllowedTickerValidatorTest,NoDuplicatesValidatorTest test` verde tras Lote D).

## Lote C — DTO + Mapper + Service + Controller + handler global

- ☐ **T3.0** `application.yml` MODIFICADO: `spring.jackson.deserialization.fail-on-unknown-properties=true` (G6 del plan). Verificar primero que ningún DTO previo se rompa: RegisterRequest (HU-F01), LoginRequest/MfaVerifyRequest/MfaResendRequest (HU-F02). Los 4 ya son strict — sin riesgo esperado. Si rompe alguno, fallback: configurar el flag solo para el endpoint `/me` vía custom `ObjectMapper`.
- ☐ **T3.1** `auth/profile/dto/UserProfileResponse.java` — record con 11 campos (id, email, nombreCompleto, tipoDocumento, numeroDocumento, telefono, rol, estado, notificationChannel, tickersOfInterest, createdAt, updatedAt). **SIN `passwordHash`.** Sin `isPremium` (D10: fuera de scope, HU-F06 lo agrega).
- ☐ **T3.2** `auth/profile/dto/UpdateProfileRequest.java` — record con 4 campos opcionales: `@Size(min=3,max=100) String nombreCompleto`, `@Pattern("^\\+[1-9]\\d{1,14}$") String telefono`, `NotificationChannel notificationChannel`, `@Size(max=25) @NoDuplicates List<@AllowedTicker String> tickersOfInterest`. Todos `null`-aceptable a nivel record (Bean Validation se salta `null`s; el `@NotNull` NO se usa para ningún campo).
- ☐ **T3.3** `auth/profile/mapper/UserProfileMapper.java` — MapStruct interface. `UserProfileResponse toResponse(User user)`. **Assertion explícita en el test (Lote D) que el método NO mapea `passwordHash`** (no es un campo del DTO, no se filtra; pero un test snapshot del JSON serializado lo verifica end-to-end).
- ☐ **T3.4** `auth/profile/exception/ReadOnlyFieldModifiedException.java` — `RuntimeException` con `String fieldName`.
- ☐ **T3.5** `auth/profile/exception/{InvalidTickerException, TooManyTickersException, DuplicateTickersException}.java` — para errors que NO vienen de Bean Validation (poco frecuente, pero defensivo). En la práctica casi todos los errores los lanza Bean Validation y los captura `MethodArgumentNotValidException`.
- ☐ **T3.6** `shared/web/GlobalExceptionHandler.java` MODIFICADO: + handler `UnrecognizedPropertyException` (Jackson) → 400 `READ_ONLY_FIELD_MODIFIED` con `fieldErrors[]={field=propertyName, code=READ_ONLY_FIELD_MODIFIED}`. Subir el code al `error` top-level cuando es el único fieldError (mantiene D14 HU-F01). + handler para `ReadOnlyFieldModifiedException`, `InvalidTickerException`, `TooManyTickersException`, `DuplicateTickersException` → 400 con sus codes específicos.
- ☐ **T3.7** `auth/profile/service/ProfileService.java`:
    - `@Transactional(readOnly=true) UserProfileResponse getMe(UUID userId)` — `userRepository.findById(userId).orElseThrow(...)` (no debería pasar, el JWT garantiza usuario válido). Map con `UserProfileMapper.toResponse`.
    - `@Transactional UserProfileResponse updateMe(UUID userId, UpdateProfileRequest req)`:
        1. Cargar User.
        2. Snapshot pre-cambios (record interno `UserSnapshot(nombreCompleto, telefono, notificationChannel, tickersOfInterest)`).
        3. Aplicar cada campo presente (`if (req.X() != null) user.setX(req.X())`).
        4. Calcular `List<String> changedFields` comparando snapshot vs entity post-set.
        5. Si `changedFields.isEmpty()`: return `mapper.toResponse(user)` sin tocar nada más (idempotente — D17).
        6. Save (Spring Data auto-detecta cambios; `updated_at` se actualiza vía `@PreUpdate` o trigger).
        7. Emitir `PROFILE_UPDATED` con `details={changedFields}` (D18: solo nombres).
        8. Si `changedFields.contains("notificationChannel")`: emitir `NOTIFICATION_CHANNEL_CHANGED` con `details={from, to}`.
        9. Return mapper.toResponse(user updated).
    - Captura `DataAccessException` → audit `PROFILE_UPDATE_FAILED` + relanza (mismo patrón LoginService).
- ☐ **T3.8** `auth/profile/controller/MeController.java`:
    - `GET /api/v1/me` → recibe `Authentication auth`, extrae `((AuthenticatedUser) auth.getPrincipal()).userId()`, llama `profileService.getMe(userId)`. OpenAPI 200/401.
    - `PATCH /api/v1/me` → `@Valid @RequestBody UpdateProfileRequest req` + extracción de userId. OpenAPI 200/400/401/500.
    - NUNCA acepta `userId` por path/body/query (constraint §10.2).
- ☐ **T3.9** `config/SecurityConfig.java` MODIFICADO: + `.requestMatchers("/api/v1/me/**").authenticated()`. Verificar que no haya un `permitAll` previo que solape.
- ☐ **T3.10** Smoke manual end-to-end con curl (usando un token obtenido de HU-F02): `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/me` → 200 con perfil. `curl -X PATCH ... -d '{"nombreCompleto":"X"}'` → 200 con perfil updated. **← HITO 3** (curl GET/PATCH /me end-to-end verde).

## Lote D — Tests backend

- ☐ **T4.1** Unit `AllowedTickerValidatorTest` — happy `"AAPL"`, fail `"FOO"`, fail case-sensitive `"aapl"`, null → válido, vacío → fail.
- ☐ **T4.2** Unit `NoDuplicatesValidatorTest` — `["A","B"]` ok, `["A","A"]` fail, `["A","A","A"]` fail (más de un duplicado), `[]` ok, `null` ok.
- ☐ **T4.3** Unit `UserProfileMapperTest` — happy mapping; assertion explícita: `objectMapper.writeValueAsString(response)` NO contiene `passwordHash`, NO contiene `password_hash`, NO contiene la substring `$2a$` (hash BCrypt).
- ☐ **T4.4** Unit `ProfileServiceTest` — happy GET, happy PATCH single, happy PATCH all, PATCH idempotente (sin cambios efectivos → NO se llama `auditor.audit(...)` con `PROFILE_UPDATED`), cambio canal emite EXACTAMENTE 2 eventos (`PROFILE_UPDATED` + `NOTIFICATION_CHANNEL_CHANGED`), error en BD audita `PROFILE_UPDATE_FAILED` y relanza. Test parametrizado: cambiar 1, 2, 3, 4 campos a la vez → `changedFields` correctos.
- ☐ **T4.5** Unit `ProfileServicePiiTest` — el detalle del evento emitido no contiene el valor de `nombreCompleto` ni de `telefono`. Inspecciona el `AuditEvent` capturado por un `@MockBean Auditor` y verifica el contenido del `details`.
- ☐ **T4.6** IT `MeFlowIT` (Postgres + Redis del compose, perfil test):
    - `shouldGetMe`: registrar usuario (reusa flujo HU-F01) → login + MFA (reusa HU-F02) → GET /me con token → 200 con perfil completo.
    - `shouldPatchNameOnly`: PATCH → 200 → BD verifica `nombreCompleto` updated, otros campos intactos, `updated_at` cambió.
    - `shouldPatchNotificationChannel`: PATCH → 200 → BD updated → tabla de audit local (o ES si habilitado) tiene `NOTIFICATION_CHANNEL_CHANGED`.
    - `shouldRejectEmailPatch`: `{"email": "x"}` → 400 `READ_ONLY_FIELD_MODIFIED` con `fieldErrors[0].field="email"`.
    - `shouldRejectInvalidTicker`: `{"tickersOfInterest":["FOO"]}` → 400 `INVALID_TICKER`.
    - `shouldRejectDuplicateTickers`: `["AAPL","AAPL"]` → 400 `DUPLICATE_TICKERS`.
    - `shouldRejectTooManyTickers`: 26 válidos → 400 `TOO_MANY_TICKERS`.
    - `shouldReturn401WithoutToken`: GET sin Authorization → 401.
    - `shouldIgnoreQueryUserId`: GET /me?userId=otra-uuid → la respuesta es del usuario del token, NO del query (constraint §10.2).
- ☐ **T4.7** IT `OpenApiContractIT` MODIFICADO — + 2 tests para `GET /api/v1/me` y `PATCH /api/v1/me`: assertion de presencia del `@Operation` y los códigos de respuesta documentados (200, 400, 401). **← HITO 4** (`mvn verify` BUILD SUCCESS, +~25-30 tests respecto al baseline de HU-F02).

## Lote E — Frontend infra (constantes + hooks + schema + tipos)

- ☐ **T5.0** Verificar `frontend/package.json` → versión de `react-router-dom`. Si >= 6.4 → usar `useBlocker` (D15). Si <6.4 → fallback Q1 del plan (Cancel button inline + `beforeunload` manual).
- ☐ **T5.1** `frontend/src/constants/tickers.ts` — exporta `MARKETS: readonly Market[] = ['NYSE','NASDAQ','LSE','TSE','ASX']` y `ALLOWED_TICKERS: Record<Market, readonly string[]>` con los 25 (mismo orden que backend T2.1). Comentario `// SINCRONIZAR con backend AllowedTickers.java`.
- ☐ **T5.2** `frontend/src/types/profile.ts` — types `Profile`, `NotificationChannel`, `Market`, `UpdateProfilePayload`. Importar de `types/api.ts` si openapi-typescript ya los generó tras Lote D.
- ☐ **T5.3** `frontend/src/features/profile/schemas/updateProfile.ts` — zod schema paralelo a backend. `nombreCompleto: z.string().min(3).max(100).optional()`, `telefono: z.string().regex(/^\+[1-9]\d{1,14}$/).optional()`, `notificationChannel: z.enum(['EMAIL','SMS','WHATSAPP']).optional()`, `tickersOfInterest: z.array(z.enum([...25])).max(25).refine(noDuplicates).optional()`.
- ☐ **T5.4** `frontend/src/features/profile/api/profileApi.ts` — `getMe(): Promise<Profile>` y `patchMe(payload: UpdateProfilePayload): Promise<Profile>` con axios + `apiClient`.
- ☐ **T5.5** `frontend/src/features/profile/hooks/useProfile.ts` — `useQuery({queryKey: ['profile','me'], queryFn: getMe, staleTime: 60_000})`.
- ☐ **T5.6** `frontend/src/features/profile/hooks/useUpdateProfile.ts` — `useMutation` con `mutationFn: patchMe`, `onSuccess(data)` invalida `['profile','me']` y si cambió `nombreCompleto` llama `authContext.updateUser({nombreCompleto: data.nombreCompleto})` (D12).
- ☐ **T5.7** `frontend/src/features/profile/hooks/useDirtyForm.ts` — wrapper que recibe `formState.isDirty` + `onDiscard` y expone `{isDirty, requestDiscard, confirm}`.
- ☐ **T5.8** `frontend/src/features/profile/hooks/useDiscardChangesPrompt.ts` — instala `useBlocker(isDirty)` (D15) + `beforeunload` listener. Si T5.0 detectó <6.4 → fallback. Devuelve `{blocker, isBlocked, confirmNavigate, cancelNavigate}`.
- ☐ **T5.9** `frontend/src/components/DiscardChangesModal.tsx` — componente genérico con props `{open, onConfirm, onCancel, title, description, confirmLabel, cancelLabel}`. Reusable.
- ☐ **T5.10** `frontend/src/lib/messages.es.ts` MODIFICADO: + 4 códigos (`READ_ONLY_FIELD_MODIFIED`: "El campo {field} no puede ser modificado desde el perfil", `INVALID_TICKER`: "El ticker {field} no es válido", `TOO_MANY_TICKERS`: "Máximo 25 tickers", `DUPLICATE_TICKERS`: "No puede haber tickers duplicados").
- ☐ **T5.11** `frontend/src/features/auth/context/AuthContext.tsx` MODIFICADO: + método `updateUser(partial: Partial<UserSummary>): void` (Q2 del plan, D12).

## Lote F — Frontend pages + componentes + routing

- ☐ **T6.1** `frontend/src/features/profile/components/PersonalInfoSection.tsx` — read-only display (email, tipoDocumento, numeroDocumento) + input editable (nombreCompleto, telefono). Recibe `register` + `errors` de RHF como props.
- ☐ **T6.2** `frontend/src/features/profile/components/NotificationChannelSection.tsx` — radio group (EMAIL/SMS/WHATSAPP) controlado vía RHF `Controller`.
- ☐ **T6.3** `frontend/src/features/profile/components/MarketTickerGroup.tsx` — recibe `market: Market`, `tickers: readonly string[]`, `selected: Set<string>`, `onToggle(ticker)`. 5 checkboxes en grid horizontal.
- ☐ **T6.4** `frontend/src/features/profile/components/TickersOfInterestSection.tsx` — orquesta 5 `MarketTickerGroup`. Estado interno `Set<string>` derivado del valor del form (RHF `Controller`). Contador "X de 25 seleccionados". Toggle rechaza si `selected.size === 25 && !selected.has(ticker)` con toast `TOO_MANY_TICKERS`.
- ☐ **T6.5** `frontend/src/features/profile/components/SaveCancelBar.tsx` — barra con botones "Cancelar" y "Guardar cambios". Recibe `isDirty`, `isSubmitting`, `onCancel`, `onSave`. Habilita Save solo si `isDirty && isValid && !isSubmitting`.
- ☐ **T6.6** `frontend/src/pages/ProfilePage.tsx`:
    - `useProfile()` para fetch inicial.
    - `useForm({defaultValues: profileFromQuery, resolver: zodResolver(updateProfileSchema)})`.
    - `useUpdateProfile()` para submit.
    - `useDiscardChangesPrompt(formState.isDirty)`.
    - Renderiza secciones + `SaveCancelBar` + `DiscardChangesModal`.
    - Maneja estados A-G del SPEC §12.1 (loading, idle clean/dirty, validating, submitting, success, error).
- ☐ **T6.7** `frontend/src/App.tsx` MODIFICADO: + `<Route path="/profile" element={<ProtectedRoute><ProfilePage/></ProtectedRoute>}/>`.
- ☐ **T6.8** `frontend/src/components/AppHeader.tsx` MODIFICADO: cambiar `to="#"` → `to="/profile"` en el item "Mi perfil" del menú (D11).
- ☐ **T6.9** E2E manual con `docker compose up`: registrarse → login + MFA → click "Mi perfil" en el menú → editar nombreCompleto → marcar 3 tickers (uno por mercado) → cambiar canal a WHATSAPP → Guardar → toast verde → recargar página → cambios persistidos. Editar otra vez sin guardar → Cancelar → modal aparece → "Descartar" → form vuelve al estado anterior. **← HITO 5** (E2E manual verde).

## Lote G — Tests frontend + cierre

- ☐ **T7.1** `useProfile.test.tsx` — fetch happy (mock de `getMe`).
- ☐ **T7.2** `useUpdateProfile.test.tsx` — mutation success invalida cache + llama `authContext.updateUser` cuando cambió `nombreCompleto`.
- ☐ **T7.3** `ProfilePage.test.tsx` — renderiza con datos de perfil mockeados, botón Guardar deshabilitado al inicio, editar nombre habilita Guardar, submit dispara mutación.
- ☐ **T7.4** `TickersOfInterestSection.test.tsx` — contador "0 de 25" al inicio, toggle ticker incrementa, toggle del mismo decrementa, intento de 26° muestra toast (mock de toast handler).
- ☐ **T7.5** `DiscardChangesModal.test.tsx` (flow integrado en `ProfilePage.test.tsx` o separado) — dirty + Cancel → modal aparece; modal Discard → reset form; modal Cancel → permanece dirty.
- ☐ **T7.6** Smoke de Swagger UI: abrir `http://localhost:8080/swagger-ui.html` → verificar que GET y PATCH `/api/v1/me` están listados con todos los códigos de respuesta y schemas.
- ☐ **T7.7** Verificación DoD spec §15 — marcar cada ítem (los 13 listados). Capturas/notas donde aplique.
- ☐ **T7.8** `APRENDIZAJES.md` MODIFICADO: sección "Día 3 — HU-F04+F20" en primera persona, estilo Día 0/1/2-3 ([[feedback-actualizar-aprendizajes]]). Headers por tema (Hibernate JSONB, validación custom, useBlocker, RHF + zod paralelo). Bold los insights clave + por qué.
- ☐ **T7.9** `AGENTS.md` MODIFICADO: sección "Trabajo activo" actualizada al cierre del bundle (branch, HU, sprint, lotes cerrados, hitos).
- ☐ **T7.10** PR `feat/HU-F04-F20-perfil-notificaciones` → `main` con plantilla CONVENTIONS §4.1 + checklist DoD. **← HITO 6** (PR abierto + CI verde + listo para merge/squash).

## Deuda nueva identificada (para post-bundle)

- **G4 del plan**: `react-router-dom` `useBlocker` — si T5.0 detecta versión <6.4, se aplicó el fallback (Cancel inline + `beforeunload`). Upgrade del router queda como mini-task post-MVP — bajo riesgo.
- **G5/G6 del plan**: `ARCHITECTURE.md` §5 todavía lista interfaces con prefijo `I` — deuda doc-only declarada en SPEC v1.1. PR separado `docs(architecture): aplicar D1 a §5` post-MVP.
- **`FAIL_ON_UNKNOWN_PROPERTIES=true`**: si rompe algún DTO previo (G6), se aplicó el fallback (ObjectMapper específico para `/me`). Considerar uniformar a strict en todos los DTOs como deuda técnica.
- **Sincronización backend ↔ frontend del catálogo de tickers**: hoy duplicado en `AllowedTickers.java` y `tickers.ts`. Post-MVP: script `npm run gen:tickers` que extraiga el enum desde `/v3/api-docs`.
- **`isPremium` en UserProfileResponse**: D10 — HU-F06 (Día 4) lo agregará. Documentado.

package co.edu.unbosque.bloomtrade.auth.subscription.dto;

/** Respuesta exitosa de {@code POST /subscriptions/portal-session} (HU-F06 v1.2 §6.1.3). */
public record PortalSessionResponse(String portalUrl) {}

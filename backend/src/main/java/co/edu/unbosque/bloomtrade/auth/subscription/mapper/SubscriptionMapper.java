package co.edu.unbosque.bloomtrade.auth.subscription.mapper;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.SubscriptionDto;
import org.mapstruct.Mapper;

/**
 * Mapeo entity → DTO de suscripción (CONVENTIONS §5.4.10). Por declaración del
 * {@link SubscriptionDto}, los campos {@code stripeCustomerId} y {@code stripeSubscriptionId}
 * NO existen en el destino — MapStruct los ignora automáticamente.
 *
 * <p>El test {@code SubscriptionMapperTest} (Lote F) verifica explícitamente que el JSON
 * serializado no contiene {@code stripeCustomerId}, {@code stripeSubscriptionId}, ni las
 * substrings {@code cus_} / {@code sub_} (HU-F06 §10.2 constraint NO-NEGOCIABLE).
 */
@Mapper(componentModel = "spring")
public interface SubscriptionMapper {

    SubscriptionDto toDto(Subscription subscription);
}

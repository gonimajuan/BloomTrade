package co.edu.unbosque.bloomtrade.auth.profile.mapper;

import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapeo entity → DTO del perfil completo (CONVENTIONS §5.4.10). El {@code password_hash} y el
 * campo {@code aceptoTerminosAt} no se mapean: {@link UserProfileResponse} no los declara.
 *
 * <p>El test {@code UserProfileMapperTest} (Lote D) verifica explícitamente que el JSON serializado
 * de la respuesta no contiene ni {@code passwordHash} ni la substring del hash BCrypt
 * {@code $2a$}.
 */
@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    /**
     * Mapea el {@link User} y el flag {@code isPremium} (calculado por {@code SubscriptionService}
     * en HU-F06 G5) al {@link UserProfileResponse}. La firma con dos parámetros es la forma
     * idiomática de MapStruct para inyectar valores derivados externos.
     */
    @Mapping(target = "isPremium", source = "isPremium")
    UserProfileResponse toResponse(User user, boolean isPremium);
}

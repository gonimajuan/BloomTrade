package co.edu.unbosque.bloomtrade.auth.mapper;

import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterResponse;
import org.mapstruct.Mapper;

/**
 * Mapeo entity → DTO (CONVENTIONS §5.4.10 — nunca exponer entidades JPA). El
 * {@code password_hash} no se mapea: {@link RegisterResponse} no lo declara.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    RegisterResponse toResponse(User user);
}

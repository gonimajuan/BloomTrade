package co.edu.unbosque.bloomtrade.auth;

import org.springframework.stereotype.Service;

// Skeleton de Día 0. Firmas de la API definidas para que SecurityConfig y los tests
// existan; implementación real en HU-F02 (Día 2): firmará con jjwt 0.12, secret desde
// JWT_SECRET, TTL desde JWT_ACCESS_TTL_MINUTES / JWT_REFRESH_TTL_DAYS.
@Service
public class JwtService {

    public String generateAccessToken(String subject) {
        // TODO HU-F02
        throw new UnsupportedOperationException("JwtService.generateAccessToken — pendiente HU-F02");
    }

    public String generateRefreshToken(String subject) {
        // TODO HU-F02
        throw new UnsupportedOperationException("JwtService.generateRefreshToken — pendiente HU-F02");
    }

    public boolean isValid(String token) {
        // TODO HU-F02
        throw new UnsupportedOperationException("JwtService.isValid — pendiente HU-F02");
    }

    public String extractSubject(String token) {
        // TODO HU-F02
        throw new UnsupportedOperationException("JwtService.extractSubject — pendiente HU-F02");
    }
}

package co.edu.unbosque.bloomtrade.auth.profile.exception;

/** Lista de tickers con duplicados (spec §5.3.8). Defensiva — Bean Validation suele atajarlo. */
public class DuplicateTickersException extends RuntimeException {

    public DuplicateTickersException() {
        super("La lista de tickers contiene duplicados");
    }
}

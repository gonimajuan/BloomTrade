package co.edu.unbosque.bloomtrade.auth.profile.exception;

/** Lista de tickers con más de 25 elementos (spec §5.3.7). Defensiva — Bean Validation suele atajarlo. */
public class TooManyTickersException extends RuntimeException {

    public TooManyTickersException() {
        super("Demasiados tickers en la lista (máximo 25)");
    }
}

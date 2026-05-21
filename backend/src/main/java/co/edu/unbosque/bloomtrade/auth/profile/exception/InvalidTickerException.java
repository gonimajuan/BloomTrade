package co.edu.unbosque.bloomtrade.auth.profile.exception;

/** Ticker fuera del catálogo de 25 (spec §5.3.6). Defensiva — Bean Validation suele atajarlo antes. */
public class InvalidTickerException extends RuntimeException {

    private final String ticker;

    public InvalidTickerException(String ticker) {
        super("Ticker no permitido: " + ticker);
        this.ticker = ticker;
    }

    public String getTicker() {
        return ticker;
    }
}

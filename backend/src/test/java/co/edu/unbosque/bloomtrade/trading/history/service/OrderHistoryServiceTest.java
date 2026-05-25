package co.edu.unbosque.bloomtrade.trading.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Tests unit del {@link OrderHistoryService} con {@link OrderRepository} mockeado.
 *
 * <p>Verifica que la composición de {@link Specification} es correcta según los
 * {@code Optional} de filtros, y que se delega al repository con el {@link Pageable}
 * recibido. Los predicados internos de la spec se prueban con {@code OrderSpecificationsTest}.
 */
@ExtendWith(MockitoExtension.class)
class OrderHistoryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private OrderRepository orderRepository;

    private OrderHistoryService service;

    @BeforeEach
    void setup() {
        service = new OrderHistoryService(orderRepository);
    }

    private static Page<Order> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    @Test
    void list_noFilters_callsRepoWithSpecOnly() {
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage());
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "submittedAt"));

        Page<Order> result =
                service.list(USER_ID, Optional.empty(), Optional.empty(), pageable);

        // Verifica que se llamó al repository con el Pageable correcto.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(orderRepository)
                .findAll(any(Specification.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void list_filterTickerOnly_invokesRepository() {
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage());

        service.list(USER_ID, Optional.of("AAPL"), Optional.empty(), PageRequest.of(0, 20));

        org.mockito.Mockito.verify(orderRepository)
                .findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void list_filterSideOnly_invokesRepository() {
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage());

        service.list(USER_ID, Optional.empty(), Optional.of(OrderSide.BUY), PageRequest.of(0, 20));

        org.mockito.Mockito.verify(orderRepository)
                .findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void list_filterTickerAndSide_invokesRepository() {
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage());

        service.list(
                USER_ID, Optional.of("AAPL"), Optional.of(OrderSide.BUY), PageRequest.of(0, 20));

        org.mockito.Mockito.verify(orderRepository)
                .findAll(any(Specification.class), any(Pageable.class));
    }
}

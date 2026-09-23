package com.xploits.kitrequester.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The kit-requester texts moved to the catalogs verbatim, and read in English too. */
class KitTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    @Test
    void theStatusReadsExactlyAsBefore() {
        Msg courier = Msg.of(KitText.STATUS_COURIER, "courier", "ValorKnight27");
        Msg status = Msg.of(KitText.STATUS,
            "state", KitText.STATE_AWAIT_DELIVERY,
            "batch", List.of(1, 2, 3, 4, 5),
            "courier", courier,
            "pending", 2,
            "delivered", 5,
            "wait", 120);
        assertEquals("Estado: AWAIT_DELIVERY | pedido: [1, 2, 3, 4, 5] | courier: ValorKnight27 | pendientes: 2"
            + " | entregados: 5 | siguiente pedido en: 120 s", ES.render(status));
    }

    @Test
    void theDepositAbortsExceededNoticeReadsExactlyAsBefore() {
        assertEquals("El depósito automático falló 3 veces seguidas (ender chest bloqueado o una interacción "
            + "ajena constante): pausado.", ES.render(Msg.of(KitText.DEPOSIT_ABORTS_EXCEEDED, "max", 3)));
    }

    @Test
    void theUnknownCourierRejectionReadsInEnglish() {
        assertEquals("Unknown courier NewCourier1 not accepted; add it to known-couriers if legitimate.",
            EN.render(Msg.of(KitText.UNKNOWN_COURIER_REJECTED, "requester", "NewCourier1")));
    }

    @Test
    void everyOrderStateHasAKeyInBothCatalogs() {
        for (OrderMachine.State state : OrderMachine.State.values()) {
            ES.render(Msg.of(KitText.of(state)));
            EN.render(Msg.of(KitText.of(state)));
        }
    }
}

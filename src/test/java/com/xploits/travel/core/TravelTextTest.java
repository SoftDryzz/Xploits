package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The auto-travel texts moved to the catalogs verbatim, and read in English too. */
class TravelTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private static Msg tightZigzag() {
        PatternParams tight = new PatternParams(50, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(new Waypoint(0, 0), Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            tight, 300, RoutePlanner.DEFAULT_WAYPOINT_MARGIN);
        return Msg.of(TravelText.NOT_FLYING, "reason", route.rejection());
    }

    @Test
    void aNestedRouteRejectionReadsExactlyAsBefore() {
        assertEquals("No se vuela: ZIGZAG con el periodo en 100 bloques y la amplitud efectiva en 50 deja sus"
            + " waypoints a 111 bloques unos de otros, y hacen falta 300: es el doble de waypoint-margin, que está"
            + " en 150 bloques, porque Baritone empieza a aterrizar a 48 bloques de su objetivo y hay que"
            + " cambiárselo antes de que llegue, y nunca menos de 300, que es lo más corto que una elytra con"
            + " cohetes vuela como tramo en vez de como bamboleo. Con huecos más cortos el módulo suelta un"
            + " waypoint y el siguiente en el mismo tick, el patrón se consume sin volarse y la ruta sale recta"
            + " sin avisar. Sube el periodo a 296 bloques, o la amplitud a 283, o elige RECTO..", ES.render(tightZigzag()));
    }

    @Test
    void theSlashPrefixRejectionReadsExactlyAsBefore() {
        assertEquals("el prefijo de Baritone empieza por barra (\"/\"), y con barra no funciona nada: "
            + "el cliente manda por el camino de comando todo lo que empiece por \"/\", que es justo el que "
            + "Baritone no engancha, así que los comandos irían al servidor en vez de a Baritone; y la red "
            + "cancelaría de paso todos los demás comandos con barra mientras durase el viaje, el \"/tpy\" de "
            + "auto-tpy y los susurros de kit-requester incluidos. "
            + "Pon en baritone-prefix el prefijo que Baritone escucha, de fábrica \"#\"",
            ES.render(SafetyNet.prefixRejection("/")));
    }

    @Test
    void theSameRejectionReadsInEnglish() {
        assertEquals("Not flying: ZIGZAG with the period at 100 blocks and the effective amplitude at 50 leaves its"
            + " waypoints 111 blocks apart, and 300 are needed: it is twice waypoint-margin, which is at 150 blocks,"
            + " because Baritone starts landing 48 blocks from its goal and it has to be changed before it gets"
            + " there, and never less than 300, which is the shortest a rocket-powered elytra flies as a leg rather"
            + " than as a wobble. With shorter gaps the module drops a waypoint and the next one in the same tick,"
            + " the pattern is used up without being flown and the route goes straight without warning. Raise the"
            + " period to 296 blocks, or the amplitude to 283, or choose RECTO..", EN.render(tightZigzag()));
    }

    @Test
    void aShortTripIsRejectedWithItsNumbersAsArguments() {
        Route route = RoutePlanner.plan(new Waypoint(0, 0), Destination.coordinates(500, 0), FlightPattern.ZIGZAG,
            PatternParams.defaults(), 300, RoutePlanner.DEFAULT_WAYPOINT_MARGIN);
        assertEquals(Msg.of(TravelText.LATERAL_STEP_OVER_TRIP, "pattern", "ZIGZAG", "step", TravelText.NAME_PERIOD,
            "value", 2000.0, "distance", 500.0), route.rejection());
    }
}

package com.xploits.pvp.core;

import com.xploits.pvp.core.AllyPolicy.Allegiance;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllyPolicyTest {
    private static final Set<String> SIN_COURIERS = Set.of();
    private static final Set<String> SIN_USUARIOS = Set.of();

    @Test
    void unDesconocidoEsObjetivo() {
        assertEquals(Allegiance.AJENO,
            AllyPolicy.of("Mallory", false, Set.of("StormAegis44"), Set.of("xto2002")));
        assertFalse(AllyPolicy.of("Mallory", false, Set.of("StormAegis44"), Set.of("xto2002")).isOurs());
    }

    @Test
    void elCourierDeKitRequesterNoSeAtaca() {
        Allegiance allegiance = AllyPolicy.of("StormAegis44", false, Set.of("StormAegis44"), SIN_USUARIOS);
        assertEquals(Allegiance.COURIER, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals("es courier de kit-requester", allegiance.reason());
    }

    @Test
    void laListaUsersDeAutoTpyNoSeAtaca() {
        Allegiance allegiance = AllyPolicy.of("Dryzzical", false, SIN_COURIERS, Set.of("Dryzzical"));
        assertEquals(Allegiance.USUARIO_TPY, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals("está en la lista users de auto-tpy", allegiance.reason());
    }

    @Test
    void elAmigoDeMeteorNoSeAtaca() {
        Allegiance allegiance = AllyPolicy.of("Dryzzical", true, SIN_COURIERS, SIN_USUARIOS);
        assertEquals(Allegiance.AMIGO, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals("es amigo de Meteor", allegiance.reason());
    }

    /** El trato es incondicional: da igual en qué fase o con qué provocación, sigue siendo nuestro. */
    @Test
    void elMotivoDeAjenoEstaVacio() {
        assertEquals("", Allegiance.AJENO.reason());
        assertFalse(Allegiance.AJENO.isOurs());
    }

    @Test
    void elAmigoMandaSobreLasOtrasListas() {
        assertEquals(Allegiance.AMIGO,
            AllyPolicy.of("StormAegis44", true, Set.of("StormAegis44"), Set.of("StormAegis44")));
    }

    @Test
    void losNombresDistinguenMayusculas() {
        assertEquals(Allegiance.AJENO,
            AllyPolicy.of("stormaegis44", false, Set.of("StormAegis44"), SIN_USUARIOS));
        assertEquals(Allegiance.AJENO,
            AllyPolicy.of("DRYZZICAL", false, SIN_COURIERS, Set.of("Dryzzical")));
    }

    @Test
    void losEspaciosSobrantesDeLaListaNoDesprotegen() {
        assertEquals(Allegiance.COURIER,
            AllyPolicy.of("StormAegis44", false, Set.of("  StormAegis44 "), SIN_USUARIOS));
        assertEquals(Allegiance.USUARIO_TPY,
            AllyPolicy.of("Dryzzical", false, SIN_COURIERS, Set.of("Dryzzical\t")));
    }

    @Test
    void lasEntradasEnBlancoNoEmparejanConNadie() {
        assertEquals(Allegiance.AJENO,
            AllyPolicy.of("Mallory", false, Set.of("", "   "), Set.of("\t")));
    }

    @Test
    void unNombreVacioONuloEsObjetivoYNoRevienta() {
        assertEquals(Allegiance.AJENO, AllyPolicy.of(null, false, Set.of(""), Set.of("")));
        assertEquals(Allegiance.AJENO, AllyPolicy.of("", false, Set.of(""), Set.of("")));
        assertEquals(Allegiance.AJENO, AllyPolicy.of("   ", false, Set.of("   "), Set.of("   ")));
    }

    @Test
    void unaEntradaNulaEnLaListaNoRevienta() {
        List<String> conNulo = Arrays.asList(null, "StormAegis44");
        assertEquals(Allegiance.COURIER, AllyPolicy.of("StormAegis44", false, conNulo, SIN_USUARIOS));
        assertEquals(Allegiance.AJENO, AllyPolicy.of("Mallory", false, conNulo, SIN_USUARIOS));
    }

    @Test
    void listasNulasSeTratanComoVacias() {
        assertEquals(Allegiance.AJENO, AllyPolicy.of("StormAegis44", false, null, null));
        assertEquals(Allegiance.AMIGO, AllyPolicy.of("Dryzzical", true, null, null));
    }

    /** El nombre del jugador también se recorta: lo que decide es quién es, no cómo llegó escrito. */
    @Test
    void elNombreDelJugadorTambienSeRecorta() {
        assertEquals(Allegiance.COURIER,
            AllyPolicy.of(" StormAegis44 ", false, Set.of("StormAegis44"), SIN_USUARIOS));
    }
}

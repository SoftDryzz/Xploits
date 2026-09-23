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
        assertEquals(PvpText.ALLY_COURIER, allegiance.reason());
    }

    @Test
    void laListaUsersDeAutoTpyNoSeAtaca() {
        Allegiance allegiance = AllyPolicy.of("Dryzzical", false, SIN_COURIERS, Set.of("Dryzzical"));
        assertEquals(Allegiance.USUARIO_TPY, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals(PvpText.ALLY_TPY_USER, allegiance.reason());
    }

    @Test
    void elAmigoDeMeteorNoSeAtaca() {
        Allegiance allegiance = AllyPolicy.of("Dryzzical", true, SIN_COURIERS, SIN_USUARIOS);
        assertEquals(Allegiance.AMIGO, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals(PvpText.ALLY_FRIEND, allegiance.reason());
    }

    /** El trato es incondicional: da igual en qué fase o con qué provocación, sigue siendo nuestro. */
    @Test
    void elMotivoDeAjenoEstaVacio() {
        assertEquals(PvpText.NOTHING, Allegiance.AJENO.reason());
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
            AllyPolicy.of("StormAegis44", false, AllyPolicy.names(List.of("  StormAegis44 ")), SIN_USUARIOS));
        assertEquals(Allegiance.USUARIO_TPY,
            AllyPolicy.of("Dryzzical", false, SIN_COURIERS, AllyPolicy.names(List.of("Dryzzical\t"))));
    }

    @Test
    void lasEntradasEnBlancoNoEmparejanConNadie() {
        assertEquals(Allegiance.AJENO, AllyPolicy.of("Mallory", false,
            AllyPolicy.names(List.of("", "   ")), AllyPolicy.names(List.of("\t"))));
    }

    @Test
    void unNombreVacioONuloEsObjetivoYNoRevienta() {
        assertEquals(Allegiance.AJENO, AllyPolicy.of(null, false, Set.of(""), Set.of("")));
        assertEquals(Allegiance.AJENO, AllyPolicy.of("", false, Set.of(""), Set.of("")));
        assertEquals(Allegiance.AJENO, AllyPolicy.of("   ", false, Set.of("   "), Set.of("   ")));
    }

    @Test
    void listasNulasSeTratanComoVacias() {
        assertEquals(Allegiance.AJENO, AllyPolicy.of("StormAegis44", false, null, null));
        assertEquals(Allegiance.AMIGO, AllyPolicy.of("Dryzzical", true, null, null));
    }

    // --- names(): el recorte se hace una vez por lista y por tick, no una vez por jugador mirado ---

    @Test
    void namesRecortaLosEspaciosYTiraLasEntradasEnBlanco() {
        assertEquals(Set.of("StormAegis44", "Dryzzical"),
            AllyPolicy.names(List.of("  StormAegis44 ", "Dryzzical\t", "", "   ")));
    }

    @Test
    void namesTrataLasEntradasNulasYLaListaNulaSinFallar() {
        assertEquals(Set.of("StormAegis44"), AllyPolicy.names(Arrays.asList(null, "StormAegis44")));
        assertTrue(AllyPolicy.names(null).isEmpty());
    }

    @Test
    void namesNoJuntaNombresQueSoloSeParecenEnMinusculas() {
        assertEquals(Set.of("StormAegis44", "stormaegis44"),
            AllyPolicy.names(List.of("StormAegis44", " stormaegis44")));
    }

    /** El nombre del jugador también se recorta: lo que decide es quién es, no cómo llegó escrito. */
    @Test
    void elNombreDelJugadorTambienSeRecorta() {
        assertEquals(Allegiance.COURIER,
            AllyPolicy.of(" StormAegis44 ", false, Set.of("StormAegis44"), SIN_USUARIOS));
    }
}

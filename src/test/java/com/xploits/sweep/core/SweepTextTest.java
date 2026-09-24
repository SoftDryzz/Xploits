package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The nether-sweep texts moved to the catalogs verbatim, and read in English too. */
class SweepTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private static SweepTally tallyWithGaps() {
        SweepArea area = new SweepArea(0, 0, 9, 9);
        java.util.List<String> row = new java.util.ArrayList<>();
        for (int z = 0; z <= 9; z++) row.add("0," + z);
        SweepTally tally = SweepTally.of(area, Coverage.ofLines(row));
        tally.record(5, 5);
        tally.record(5, 6);
        return tally;
    }

    @Test
    void aNestedPlanRejectionReadsExactlyAsBefore() {
        Msg rejection = SweepPlanner.plan(SweepArea.ofChunks(0, 0, 15, 15), Coverage.empty(), 0).rejection();
        assertEquals("No se barre: El barrido con la anchura de pasada -el ajuste lane-width- en 0 chunks no avanza ni"
            + " una banda: cada pasada saldría encima de la anterior y el recorrido del área no terminaría nunca."
            + " Degradarla a 1 chunk en silencio sería peor que pararse: el barrido volaría con una separación"
            + " inventada, dejaría franjas sin mirar y las marcaría como peinadas igual. Sube lane-width a 1 chunk o"
            + " más. Normalmente no se teclea -se deja en 0 y sale medida del flujo de chunks que manda el"
            + " servidor-, así que si ha llegado aquí en 0 es que la medida todavía no tiene muestras o que"
            + " lane-width-margin la ha dejado en eso.",
            ES.render(Msg.of(SweepText.NOT_SWEEPING, "reason", rejection)));
    }

    @Test
    void theOversizeRejectionReadsExactlyAsBefore() {
        assertEquals("El área son 5000x5000 chunks, 25000000 en total, y el tope está en 4000000. No se recorta a"
            + " espaldas de nadie: un barrido que se guardara un trozo del rectángulo para sí y luego dijera"
            + " «terminado» dejaría terreno sin mirar dado por peinado, que es justo lo que este módulo existe para"
            + " no hacer. Ni manteniendo el lado corto, de 5000 chunks, cabe nada: acerca las dos esquinas por los"
            + " dos ejes. Acerca chunk-x-1 a chunk-x-2, o chunk-z-1 a chunk-z-2. Para hacerse una idea: el tope son"
            + " más de veinte horas de vuelo, y la caja entera que has cruzado en meses cabe veinte veces dentro de"
            + " él, así que pasarse suele ser un dedo de más al teclear una coordenada.",
            ES.render(SweepArea.ofChunks(0, 0, 4_999, 4_999).oversizeRejection()));
    }

    @Test
    void theCoverageSummaryReadsExactlyAsBeforeAndInEnglish() {
        Msg summary = tallyWithGaps().summary();
        assertEquals("Cobertura del área: 12 de 100 chunks (12 %), 10 que ya estaban vistos al planificar y 2 que han"
            + " llegado durante el barrido. Quedan 88 chunks del área que NO han llegado nunca", ES.render(summary));
        assertEquals("Area coverage: 12 of 100 chunks (12 %), 10 already seen when planning and 2 that arrived during"
            + " the sweep. 88 chunks of the area NEVER arrived", EN.render(summary));
    }
}

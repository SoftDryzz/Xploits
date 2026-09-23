package com.xploits.shared.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogTest {
    enum K implements MessageKey {
        PLAIN, DISTANCE, HEALTH, NESTED, WRAPPER, APOS, BRACES, MULTI, MISSING;

        @Override
        public String area() {
            return "test";
        }
    }

    private static final String ES = """
        # comment
        test.plain=Hola
        test.distance=a {distance} bloques
        test.health=vida {health,1}
        test.wrapper=No se vuela: {reason}.
        test.nested=faltan {n} cohetes
        test.apos=95 % d'acord
        test.braces={{literal}} y {n}
        test.multi=una\\nlínea\\\\dos
        """;

    private final List<String> problems = new ArrayList<>();

    private Catalog es() {
        return Catalog.parse(Language.ES, ES, problems::add);
    }

    @Test
    void rendersPlainAndNamedPlaceholders() {
        assertEquals("Hola", es().render(K.PLAIN));
        assertEquals("a 1500 bloques", es().render(K.DISTANCE, "distance", 1500));
    }

    @Test
    void decimalsUseTheLanguageSeparator() {
        assertEquals("vida 18,5", es().render(K.HEALTH, "health", 18.5));
        Catalog en = Catalog.parse(Language.EN, "test.health=health {health,1}\n", problems::add);
        assertEquals("health 18.5", en.render(K.HEALTH, "health", 18.5));
        assertEquals("health 7.0", en.render(K.HEALTH, "health", 7.0));
    }

    @Test
    void decimalsWithoutPrecisionDropTrailingZeros() {
        Catalog en = Catalog.parse(Language.EN, "test.distance={distance}\n", problems::add);
        assertEquals("2.5", en.render(K.DISTANCE, "distance", 2.50));
        assertEquals("3", en.render(K.DISTANCE, "distance", 3.0));
    }

    @Test
    void integersAreNotGrouped() {
        assertEquals("a 123456 bloques", es().render(K.DISTANCE, "distance", 123456L));
    }

    @Test
    void msgArgumentsRenderInTheSameLanguage() {
        Msg reason = Msg.of(K.NESTED, "n", 3);
        assertEquals("No se vuela: faltan 3 cohetes.", es().render(K.WRAPPER, "reason", reason));
    }

    @Test
    void keyArgumentsRenderAsTheirText() {
        assertEquals("No se vuela: Hola.", es().render(K.WRAPPER, "reason", K.PLAIN));
    }

    @Test
    void apostrophesAndPercentAreLiteral() {
        assertEquals("95 % d'acord", es().render(K.APOS));
    }

    @Test
    void doubledBracesAreLiteral() {
        assertEquals("{literal} y 4", es().render(K.BRACES, "n", 4));
    }

    @Test
    void escapes() {
        assertEquals("una\nlínea\\dos", es().render(K.MULTI));
    }

    @Test
    void missingKeyOrArgumentNeverThrowsAndIsReportedOnce() {
        Catalog c = es();
        assertEquals("‹test.missing›", c.render(K.MISSING));
        assertEquals("‹test.missing›", c.render(K.MISSING));
        assertEquals("a ‹distance› bloques", c.render(K.DISTANCE));
        assertEquals(2, problems.size());
    }

    @Test
    void placeholdersAreNamesOnly() {
        assertEquals(Set.of("health"), Catalog.placeholders("vida {health,1} {{no}}"));
    }

    @Test
    void parseRejectsBrokenFiles() {
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(Language.ES, "sin igual\n", problems::add));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(Language.ES, "test.a=1\ntest.a=2\n", problems::add));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(Language.ES, "Test.A=x\n", problems::add));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(Language.ES, "test.a=abre { sin cerrar\n", problems::add));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(Language.ES, "test.a=mal \\q\n", problems::add));
    }

    @Test
    void valueKeepsLeadingSpacesAndCrlfIsTolerated() {
        Catalog c = Catalog.parse(Language.ES, "test.plain=  sangrado\r\n", problems::add);
        assertEquals("  sangrado", c.render(K.PLAIN));
    }
}

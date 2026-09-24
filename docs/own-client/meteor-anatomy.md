# Anatomía de Meteor

Dónde está su código, qué hace cada parte, y qué vale la pena replicar, mejorar o ignorar.

---

## Dónde está el código

**El repositorio:** <https://github.com/MeteorDevelopment/meteor-client> (GPL-3.0).

**El jar de fuentes, ya descargado en esta máquina** por Gradle al compilar el addon:

```
~/.gradle/caches/modules-2/files-2.1/meteordevelopment/meteor-client/
    1.21.11-SNAPSHOT/<hash>/meteor-client-1.21.11-SNAPSHOT-sources.jar
```

Descomprimirlo y leerlo es la forma más rápida de resolver cualquier duda. **Este repo tiene una
regla escrita sobre eso**: no afirmar nunca qué hace una API de Meteor sin haberla leído ahí. Se
saltó dos veces y las dos costaron una ronda entera de trabajo.

Hay además una copia remapeada a nombres legibles dentro del proyecto:

```
<proyecto>/.gradle/loom-cache/remapped_mods/.../meteor-client-*-sources.jar
```

---

## El mapa, por subsistema

Todo cuelga de `meteordevelopment/meteorclient/`.

### `systems/` — el chasis

Lo que de verdad define un cliente. Un `System` es una cosa que persiste y se guarda a disco:
módulos, ajustes, amigos, waypoints, macros, cuentas, perfiles, proxies, HUD.

**`systems/modules/`** es el corazón: `Module`, `Modules`, `Category` y los 197 módulos repartidos
en seis categorías (`combat`, `misc`, `movement`, `player`, `render`, `world`).

**Lo que hay que entender de `Module` antes de replicarlo**, porque son decisiones con consecuencias:

- Un módulo tiene `onActivate()` / `onDeactivate()` y se suscribe solo al bus.
- **`toggle()` desuscribe el módulo ANTES de llamar a `onDeactivate()`.** Cualquier oyente que
  tenga que sobrevivir a la desactivación va suscrito aparte. En Xploits esto costó una ronda.
- **`Modules.onGameLeft()` llama a `onDeactivate()` pero NO marca el módulo como inactivo**, para
  que vuelva solo al reentrar. Tocar otros módulos ahí los deja suscritos dos veces el resto de la
  sesión, porque el bus no deduplica.

**Si construís el vuestro, estas tres son las que arreglaría.** Que el ciclo de vida de un módulo
sea sutil es la fuente de fallos más cara que tiene Meteor.

### `settings/` — 36 ficheros

Ajustes tipados (`BoolSetting`, `DoubleSetting`, `EnumSetting`, `StringListSetting`…) con
serialización, valores por defecto, rangos y visibilidad condicional.

Dos cosas que conviene saber y que no son obvias:

- **`sliderRange` es solo cosmético**: acota el widget, no el valor. Lo que clava el valor es
  `.min()`/`.max()`, y sin eso el jugador puede teclear cualquier cosa o heredarla de la config.
- **Un valor persistido que no pasa la validación se descarta al cargar** y el ajuste vuelve a su
  valor de fábrica. Es lo que permite subir un mínimo y que las configuraciones viejas se arreglen
  solas.

`Module.settings` es público y `Settings.get(nombre, tipo)` devuelve el ajuste tipado, así que **un
módulo puede leer el ajuste de otro** sin reflexión ni mixins. Xploits lo usa para comprobar que el
`anti-suicide` de `CrystalAura` sigue encendido antes de fiarse de él.

### `events/` — 69 ficheros, y `orbit`

El bus no está en Meteor: es una librería aparte, **`meteordevelopment:orbit`**. Vale la pena leerla
entera porque es pequeña y define cómo se comporta todo lo demás.

Tres cosas suyas con consecuencias:

- **No deduplica.** Suscribir dos veces el mismo objeto lo registra dos veces, y `unsubscribe` quita
  **una sola** copia.
- **La prioridad ordena, y a igualdad manda el orden de suscripción.** `insert()` solo adelanta a
  los estrictamente menores.
- **Cancelar corta el bucle**: ningún oyente posterior ve ese evento.

En un cliente propio, **deduplicar al suscribir** es una línea y ahorra una clase entera de fallos.

### `mixin/` — 212 ficheros, y la parte que subestima todo el mundo

Es el pegamento con Minecraft. Cada mixin engancha un método del juego para publicar un evento,
cambiar un comportamiento o exponer un campo privado.

**Es el subsistema más grande y el que más se rompe al cambiar de versión de Minecraft.** Si algún
día vuestro cliente sobrevive a una actualización o muere en ella, se decidirá aquí.

**Consejo con coste real detrás:** cada mixin es deuda. Un cliente con 50 mixins bien elegidos
sobrevive a una versión nueva en un fin de semana; uno con 212 tarda semanas. Antes de añadir uno,
mirad si el dato se puede sacar de una API pública.

### `gui/` — 134 ficheros

ClickGUI, HUD, widgets, temas. Es la parte más vistosa y la que más tiempo come.

**Es lo último que haría.** Un cliente con una GUI fea pero funcional y buenos módulos es útil; uno
con una GUI preciosa y tres módulos, no.

### `utils/` — 128 ficheros

Lo compartido, y donde está buena parte del valor real de Meteor:

| Paquete | Qué resuelve |
|---|---|
| `utils/player/` | Inventario, vida, daño entrante, agujeros, rotaciones |
| `utils/entity/` | Objetivos, cálculo de daño de cristales, bloques de rodeado |
| `utils/world/` | Bloques, chunks, dimensiones |
| `utils/render/` | Colores, texto, dibujo |

Dos joyas que Xploits usa y que un cliente propio necesita sí o sí:

- **`PlayerUtils.possibleHealthReductions()`** — el daño que ya te apunta: cristales colocados, gente
  con espada cerca, camas en el Nether, caída. Es el dato con el que se decide todo lo defensivo.
- **`DamageUtils.crystalDamage(objetivo, posición)`** — cuánto haría un cristal puesto ahí.

### `renderer/`, `commands/`, `addons/`, `pathing/`, `asm/`

- **`renderer/`** (23) — dibujo 3D/2D. Necesario, y más pequeño de lo que parece.
- **`commands/`** (58) — el sistema de comandos con Brigadier.
- **`addons/`** (3) — el punto de extensión. Si queréis que otros escriban para vuestro cliente,
  esto es lo que hay que diseñar bien desde el principio.
- **`pathing/`** (6) — una abstracción sobre pathfinding. **Ojo:** su detección de Baritone hace un
  `Class.forName("baritone.api.BaritoneAPI")`, que **falla con el jar standalone ofuscado**. En las
  instancias de esta máquina, todo lo de Meteor que usa Baritone está muerto en silencio. Ver
  [Integrar Baritone](integrar-baritone.md).
- **`asm/`** (6) — manipulación de bytecode en carga.

---

## Qué replicar, qué mejorar, qué ignorar

**Replicar casi tal cual** — están bien resueltos y no hay por qué reinventarlos:

- El modelo `System` que persiste a disco.
- Los ajustes tipados con validación y visibilidad condicional.
- La separación módulo / categoría.

**Mejorar** — aquí es donde un cliente propio gana de verdad:

| Qué | Por qué |
|---|---|
| **El ciclo de vida de un módulo** | Desuscribir antes de `onDeactivate()` y no marcar inactivo al salir del mundo son dos trampas que ya nos costaron rondas |
| **Deduplicar en el bus** | Una línea; evita que un módulo acabe con sus manejadores ejecutándose por duplicado toda la sesión |
| **Menos mixins** | Es lo que decide si sobrevivís a la próxima versión de Minecraft |
| **Rangos duros por defecto** | Que `sliderRange` no clave el valor es una fuente de fallos silenciosos |
| **Tests** | Meteor prácticamente no tiene. Vosotros tenéis ~640 y esa es vuestra ventaja real |

**Ignorar al principio:** la GUI entera, los perfiles, los proxies, las cuentas, las macros. Todo eso
se añade cuando el cliente ya hace algo que merezca la pena configurar.

---

## Otros clientes que vale la pena leer

No para copiar, sino para ver cómo resolvieron lo mismo:

- **Meteor** — el que tenéis delante y el mejor documentado por dentro.
- **Baritone** — no es un cliente, pero su separación entre `api` e implementación es un ejemplo de
  cómo se publica una frontera estable.
- **Fabric API** — para entender qué os da la plataforma sin mixins. Cada cosa que saquéis de aquí
  es un mixin que no escribís.

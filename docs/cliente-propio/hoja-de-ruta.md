# Hoja de ruta

En qué orden construirlo, qué se lleva de Xploits tal cual, y qué hace que un cliente sea
profesional y no un montón de funciones.

---

## Lo que ya está hecho y se lleva sin tocar

Son Java puro: no importan `net.minecraft` ni `meteordevelopment`. Compilan en cualquier proyecto.

| Pieza | Qué resuelve | Tests |
|---|---|---|
| `pvp/core/` | El criterio de combate entero: fase ofensiva, postura defensiva, propiedad de módulos, quién es de los tuyos | ~170 |
| `sweep/core/` | Planificación de pasadas, cobertura previa, recuento de lo que llega, presupuesto de cohetes | ~200 |
| `travel/core/` | Geometría de los cuatro patrones, guion de Baritone, vigilante de atasco, préstamo de módulos | ~180 |
| `elytra/core/` | Cuándo cambiar la elytra y por cuál | |
| `stash/core/` | Índice de contenedores y búsqueda | |
| `kitrequester/core/`, `autotpy/core/` | Máquina de estados de kits, política de TPA | |

**Unas 640 pruebas en total.** Esto es lo difícil, lo que costó las rondas y lo que nadie más tiene.
Todo lo demás del addon es traducción a Meteor y se reescribe.

**Lo que hay que reescribir por cada módulo** es su adaptador: suscribirse a eventos, traducir el
estado del juego a los valores simples que el núcleo entiende, y ejecutar lo que el núcleo decide.
Son grandes en líneas y delgados en decisiones.

---

## El orden

### Fase 0 — Decidir el camino (antes de escribir nada)

Leed [las licencias](README.md#lo-primero-las-licencias) y decidid: **fork de Meteor** (rápido,
GPL-3.0, heredáis su arquitectura) o **cliente propio sobre Fabric** (lento, libre, los núcleos
encajan directamente).

Y comprobad si existe un **jar de API de Baritone para 1.21.11**. Si no existe, [lo que dice ese
documento](integrar-baritone.md) cambia y hay que planificarlo distinto.

### Fase 1 — El chasis mínimo

Lo mínimo para que un módulo exista y se pueda encender:

1. **Bus de eventos.** `orbit` es pequeño y está probado. Si escribís el vuestro, **deduplicad al
   suscribir**: es una línea y evita una clase entera de fallos.
2. **`Module` con ciclo de vida claro.** Y arreglad de entrada las dos trampas de Meteor: que se
   desuscriba después de `onDeactivate()`, no antes, y que salir del mundo no deje módulos en un
   estado a medias.
3. **Ajustes tipados**, con validación de rango de verdad y no solo cosmética del deslizador.
4. **Persistencia.** Un fichero por sistema. Lo que no valide al cargar, al valor de fábrica.

**Criterio para pasar de fase:** un módulo de prueba que se enciende, guarda un ajuste, lo recupera
al reiniciar y reacciona a un tick.

### Fase 2 — Un núcleo encima, el más difícil

**Portad `pvp/core/` primero**, y no por orden alfabético: es el que más depende del mundo —vida,
daño entrante, bloques, objetivos— así que es el que os va a decir si vuestra frontera
núcleo/adaptador está bien dibujada.

Si `CombatSnapshot` se puede rellenar sin ensuciar el núcleo, el diseño aguanta. Si no, mejor
descubrirlo aquí que con seis módulos portados.

Necesitaréis el equivalente de `PlayerUtils.possibleHealthReductions()`. Está en Meteor y merece la
pena leer cómo lo calcula.

**Criterio para pasar de fase:** el combate decide igual que en el addon, con los mismos tests en
verde.

### Fase 3 — Mixins, los justos

Aquí es donde se decide si vuestro cliente sobrevive a la próxima versión de Minecraft.

**Por cada mixin, preguntad antes si el dato se puede sacar de Fabric API o de una API pública.**
Cada uno que evitéis es tiempo que no gastaréis en la siguiente actualización.

Meteor tiene 212. Un cliente con 50 bien elegidos sobrevive a una versión nueva en un fin de semana.

### Fase 4 — Baritone por su API

Ver [Integrar Baritone](integrar-baritone.md). Desaparecen la red de chat, el guion de comandos y el
prefijo; y podéis **leer** sus ajustes, no solo escribirlos.

**Criterio para pasar de fase:** un viaje con patrón completo sin que se escriba una sola línea en
el chat.

### Fase 5 — El resto de los núcleos

`sweep`, `travel`, `elytra`, `stash`. Ya con el camino hecho, son adaptadores.

### Fase 6 — GUI

**Lo último.** Una lista de módulos con casillas y campos es suficiente para que el cliente sea útil.
Lo bonito viene después, cuando ya hay algo que configurar.

---

## Las cuatro cosas que lo hacen profesional

No son funciones. Son lo que separa un cliente vivo de uno abandonado.

### 1. Que se construya de cero con un comando

En una máquina limpia, `./gradlew build` y sale el jar. Sin pasos manuales, sin «primero copia
esto». Si hay que explicar cómo compilarlo, no está terminado.

### 2. Que tenga tests de lo que decide

**Ya lo tenéis, y es vuestra ventaja real.** Meteor prácticamente no tiene. Un cliente que puede
cambiar su criterio de combate y saber en treinta segundos si rompió algo juega en otra liga.

Y la parte que importa de verdad: **verificad por mutación**. Un test verde solo demuestra que el
test pasa. En este repo ha habido tests en verde durante dos rondas protegiendo código que podía
romperse sin que se enteraran.

### 3. Que diga lo que hace y lo que no

Es el principio que gobierna todo Xploits: **un fallo nunca puede parecerse a un resultado normal**.

Un módulo que no puede hacer lo que promete se niega y dice qué tocar. Una medida que no existe
lanza en vez de devolver cero. Una zona mal cubierta avisa fuerte en vez de decir «terminado».

Esto no es cosmético: es lo que hace que un usuario confíe en el cliente. La confianza se pierde una
vez y no vuelve.

### 4. Que sobreviva a una versión de Minecraft

Depende casi entero de cuántos mixins tengáis y de lo frágiles que sean. Un cliente que tarda tres
meses en actualizarse pierde a sus usuarios en el primer mes.

Y una regla que ya está escrita en [Convenciones](../convenciones.md) y que aquí vale el doble:
**no reimplementéis lo que ya funciona**. En la última rama del addon se estuvo a punto de reescribir
un detector de bases, un mapa de cobertura y un registro de chunks — los tres existían ya,
instalados y mejores.

---

## El consejo que más vale

**Empezad por el chasis mínimo y un solo núcleo**, y comprobad que el conjunto se sostiene antes de
portar los demás. Si a mitad se ve que no compensa, no habéis perdido nada: los núcleos siguen
funcionando en el addon, que sigue siendo útil.

Lo que hundiría el proyecto es empezar por la GUI o por replicar los 197 módulos de Meteor. Es la
parte más vistosa, la más larga, y la que menos tiene que ver con lo que hace especial vuestro
trabajo.

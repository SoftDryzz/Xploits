# Problemas conocidos

Fallos que **sabemos** que existen, con su síntoma y qué hacer. Si algo te pasa y no está aquí, es
un fallo nuevo y merece la pena anotarlo.

---

## Confirmados, sin cerrar

### `nether-sweep` puede medir la anchura de pasada de más

**Síntoma:** el barrido anuncia una anchura de pasada grande (más de 20 chunks) y al aterrizar la
cobertura sale por debajo del 95 %, con aviso fuerte.

**Qué pasa.** El módulo descarta las muestras tomadas en movimiento, pero mira la velocidad del tick
en que el chunk **llega** — y la premisa del filtro es justo que el tick de llegada no es el de
encolado. Al aterrizar tras un vuelo largo, la cola acumulada se drena **con el jugador ya parado**:
esas muestras pasan el filtro y son las más infladas de todas.

**Consecuencia:** pasadas más separadas de lo que el servidor cubre, con franjas entre ellas que
nunca llegan.

**Qué hacer.** Enciende el módulo **después** de aterrizar y espera unos segundos antes de dar la
vuelta de medición, para que la cola del vuelo se haya drenado. Y **mira siempre el mensaje de
cobertura al aterrizar**: es la red que caza esto.

**Cierre pendiente:** exigir que la velocidad lleve ~40 ticks por debajo del tope antes de aceptar
muestras, en vez de mirar solo el último tick.

### El aviso por enlace consumido afirma más de lo que puede

**Síntoma:** al lanzar un barrido sale un aviso que dice que se perderá alguna esquina pero que *«la
banda se cruza igual»*.

**Qué pasa.** Eso es cierto cuando el enlace consumido es corto (última banda estrecha) y **falso
cuando es de banda entera**, que por construcción siempre supera el radio de cobertura: deja hasta
dos tercios de la banda sin entregar en un extremo, un 22 % integrado.

**Cuándo te toca:** con anchuras de pasada pequeñas (servidor cargado) o con `waypoint-margin` alto.

**Qué hacer.** Si ves ese aviso, baja `waypoint-margin` o vuelve a medir la anchura. Y otra vez: el
mensaje de cobertura al aterrizar es la red.

**Cierre pendiente:** rechazar cuando el enlace supere el radio de cobertura, y avisar solo por
debajo.

### La primera pasada de un barrido corto puede consumirse sin volarse

**Síntoma:** un barrido de área pequeña (eje largo entre 10 y 19 chunks) termina sospechosamente
rápido.

**Qué pasa.** Al arranque de la primera pasada se llega desde la aproximación, en una dirección
cualquiera, así que puede soltarse estando ya dentro de la pasada. La comprobación que impide que una
pasada se consuma sin volarla no cubre ese caso.

**Qué hacer.** Usa áreas con el eje largo bien por encima de 19 chunks.

### Los ajustes de Baritone no se restauran si te desconectas mal

**Síntoma:** después de una desconexión brusca durante un vuelo, tus `#elytra` manuales se comportan
distinto.

**Qué pasa.** Si la desconexión llega con el jugador ya nulo, los comandos de restauración no salen y
los cinco ajustes se quedan con sus valores de vuelo — que Baritone persiste a disco.

**Qué hacer.** Repasa `elytraAutoSwap`, `elytraAutoJump`, `elytraAllowEmergencyLand`,
`elytraConserveFireworks` y `elytraFireworkSpeed` con `#set <nombre>`.

### Tres módulos de combate no se pueden apagar a mano mientras `auto-pvp` los dirige

**Síntoma:** apagas `auto-trap`, `auto-city` o `auto-anvil` y se vuelven a encender solos.

**Qué pasa.** Esos tres se apagan solos por diseño cuando terminan su trabajo, así que el director no
puede distinguir «se apagó él» de «lo apagó el jugador» y los vuelve a tomar. Con `surround` sí se
pudo cerrar, porque su autoapagado es medible desde fuera.

**Qué hacer.** Apagar `auto-pvp` mientras los quieras para ti.

### La consola necesita Windows Terminal con su configuración de ventanas por defecto

La ventana pide su tamaño con una secuencia que Windows Terminal respeta. Si lo configuras para abrir
en pestañas (`windowingBehavior`), esa secuencia puede cambiar el tamaño de tu otra ventana o no hacer
nada. Con el conhost clásico, seleccionar texto congela la ventana mientras dure la selección; el
juego no se entera, porque solo se comunican por ficheros.

---

## Calibraciones sin medir

Números elegidos con un razonamiento pero sin datos de vuelo real detrás. Si notas que alguno molesta,
es candidato a ajustarse.

| Qué | Valor | Por qué se eligió |
|---|---|---|
| Muestreo de cohetes | cada 1.000 bloques | Por tick no puede ser: la tasa saldría de dividir un tick de vuelo entre un cohete |
| Caducidad del gasto medido | factor ×1 sobre la propia evidencia | Puede caducar pronto al principio del vuelo y tardar en vuelos largos |
| Suelo de cobertura | 95 % | Primer valor sin dato detrás. Probablemente lo mueva la primera hora de vuelo real |
| Tope de área | 4.000.000 de chunks | Cruce de tres cotas: lo que tardaría en volarse, que no estorbe al uso normal, y lo que cuesta recorrerlo |

---

## Cosas que parecen fallos y no lo son

**«Enciendo `auto-travel` o `nether-sweep` y no pasa nada.»** No vuelan al encenderse: arman el
viaje y esperan su comando. Al encenderlos te lo dicen por chat.

**«Un barrido de 3×3 chunks se rechaza.»** Correcto. Hace falta un eje largo de unos 19 chunks: por
debajo, los vértices quedan tan juntos que se consumirían en el mismo tick y habría pasadas que no se
vuelan **y que el barrido daría por peinadas igual**.

**«`SEÑUELO` se rechaza en modo autopista.»** Correcto. Apuntar 30° fuera te saca del corredor, y eso
llama más la atención que ir recto.

**«Sale un aviso de que un comando `#` se canceló.»** Es la red funcionando. Significa que Baritone
no está interceptando sus propios comandos y que, si los escribieras a mano, se publicarían en el
chat del servidor.

**«`auto-pvp` no enciende nada aunque tengo a alguien delante.»** Mira `.xploits pvp`: probablemente
te falten recursos (obsidiana, cristales) o el objetivo sea de los tuyos. El comando lo dice.

**«No puedo lanzar un barrido porque estoy viajando.»** Correcto: los dos dirigen al mismo Baritone
y `#elytra` solo admite un objetivo. Corta el primero.

**«Cierro la consola con la X y dice "con la X o desde fuera".»** Windows no deja ejecutar nada a un
programa cuando cierras su ventana con la X; se comprobó. Así que el juego no puede distinguir la X de
una ventana matada desde el administrador de tareas, y el aviso dice las dos cosas en vez de fingir.
Salir con `0` sí se distingue.

---

## Sin verificar dentro del juego

Cosas que están implementadas y razonadas pero que **nadie ha visto funcionar todavía**:

- Si 6b6t permite barrer el Nether sin limitarlo ni expulsar por volar largo.
- Si el cambio de elytra entra bien planeando rápido.
- Si `auto-web` se auto-entierra al colocar telaraña bajo el enemigo.
- Si el servidor manda chunks al ritmo que asume la planificación de pasadas.
- Si 6b6t manda el contenido de las shulkers dentro de los cofres (decide la mitad del diseño de
  `stash-keeper`).

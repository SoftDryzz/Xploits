# Convenciones

Cómo se trabaja en este repo, y por qué. Casi todas estas reglas salen de un fallo concreto que
costó una ronda de revisión.

---

## Idioma

**El repo está pasando a inglés por fases** (versionado, selector de idioma, código, documentación e
historial). Mientras dure:

- **Los mensajes de commit, en inglés**, desde la 0.2.0. Ver [Versionado](VERSIONING.md).
- **Todo lo nuevo, en inglés**: código, comentarios, javadoc y documentación que se creen a partir de
  ahora.
- **Lo que ya existe sigue en español** hasta que su fase lo traduzca. No se traduce de paso al tocar
  un fichero: se traduce entero, en su fase, para no dejar ficheros mitad y mitad.

**Excepción: los identificadores de los ajustes de Meteor van en inglés** (`waypoint-margin`,
`lane-width`, `spiral-radius`). No es incoherencia: son parte de la interfaz de Meteor, que el
jugador ve junto a los ajustes de los demás módulos. Un módulo con los nombres en español dentro de
la misma ventana parece otro addon.

**Y un mensaje de rechazo que nombre un ajuste tiene que nombrarlo exactamente como aparece en la
interfaz.** Si divergen, el mensaje manda al jugador a buscar algo que no existe con ese nombre.

---

## Verificar antes de afirmar

**Nunca afirmes qué hace una API de Meteor, de Minecraft o de Baritone sin haberlo leído.** El
sources jar de Meteor está en el caché de Gradle; el de Minecraft remapeado, en el del proyecto;
Baritone hay que desensamblarlo con `javap` porque viene ofuscado.

Esta regla existe porque **se han dado por buenas dos cosas falsas sobre Meteor** por razonar en vez
de leer. Y la tercera vez que se leyó de verdad, apareció esto: `blocksMovement()` considera sólida
una losa, así que estar de pie sobre una losa clasificaba al enemigo como enterrado y apagaba el aura
de cristales en mitad del combate.

**Las specs llevan una sección «Hechos verificados de la API»** con el dato y la consecuencia. Si un
hecho no está ahí, no está verificado.

---

## Núcleo puro y adaptador fino

La regla está en [Arquitectura](arquitectura.md). En la práctica:

- Si te ves escribiendo una decisión en el adaptador, **va al núcleo**.
- El núcleo no importa `net.minecraft` ni `meteordevelopment`. Ni uno.
- El adaptador **mide y ejecuta**; no decide.

Se ha roto tres veces y las tres costaron una ronda.

---

## Tests: la mutación no es opcional

**Un test verde solo demuestra que el test pasa.**

Antes de dar por buena una protección, **rompe a propósito lo que protege y comprueba que algún test
se pone en rojo**. Si sobrevive, ese test no protege nada.

Esto no es teoría. En este repo:

- Un test del planificador llevaba **dos rondas en verde** protegiendo código que podía romperse sin
  que se enterara, porque otro arreglo del mismo commit tapaba el hueco.
- El test que comprobaba la cobertura del área **afirmaba la propiedad correcta** y aun así no la
  comprobaba: la tolerancia coincidía con la separación entre pasadas, así que solo restringía eso.
- Una mutación pasó en verde porque el `sed` no llegó a aplicarse. **Verifica que mutaste de verdad**
  antes de aceptar el resultado.

**Compara contra valores calculados a mano**, no contra la misma fórmula que usa el código: si
comparten el error, el test pasa igual.

---

## Rechazar antes que degradar

Cuando algo no se puede hacer como se pidió, **se rechaza diciendo qué ajuste tocar y a qué valor**.
Nunca se hace algo parecido en silencio.

La doctrina literal, que está escrita en el código: *«se acota lo que sigue funcionando acotado; se
rechaza lo que no»*.

El caso que la justifica: un patrón de despiste que no cabe en la distancia del viaje devolvía la
ruta recta. El jugador creía que ondulaba, ajustaba su comportamiento a una protección que no existía
y volaba una línea recta hasta su base. **Cinco puertas distintas llevaban a ese mismo fallo** y se
cerraron una a una.

**Un rechazo que no dice cómo salir del atasco es casi tan malo como el silencio.** El mensaje nombra
el ajuste, su valor actual y a cuánto ponerlo.

---

## El sesgo importa: pregunta hacia qué lado se equivoca

Cuando elijas un redondeo, un umbral o un valor por defecto, pregúntate **qué pasa si te equivocas en
cada dirección**. Casi nunca es simétrico.

- Estrechar una pasada de más cuesta vuelo. Ensancharla de más deja franjas sin mirar **marcadas
  como peinadas**. Se redondea hacia abajo.
- Subestimar la distancia que queda parece prudente y es lo contrario: hace que el módulo diga «te
  llegan los cohetes» cuando no llegan.
- Dejar el aura de cristales encendida de más cuesta unos cristales. Apagarla de menos cuesta la
  pelea.

---

## Un fallo nunca puede parecerse a un resultado normal

Es el principio que gobierna todo lo demás.

- Una medida que no existe **lanza**, no devuelve cero.
- Una zona mal cubierta **avisa fuerte con toast**, no sale en un `info` que se puede apagar.
- Un método llamado «total» **incluye todo** lo que dice incluir. Ese fallo concreto se arregló
  **dos veces** en la misma rama, un nivel más arriba cada vez.
- «No lo sé» es motivo de cautela, nunca de continuar callando.

---

## Commits

**Ninguna línea de atribución, de ninguna clase.** Ni `Co-Authored-By`, ni `Generated with`, ni
mención a ninguna herramienta. El commit termina en su última línea de texto.

**El mensaje explica el porqué, no el qué.** El diff ya dice qué cambió. Lo que no se puede
reconstruir después es por qué se eligió eso y no lo otro.

`git add` por nombre de fichero, nunca `git add -A`.

---

## Flujo de trabajo

1. **Diseño primero**, en `docs/superpowers/specs/`, con los hechos de la API verificados.
2. **Plan** en `docs/superpowers/plans/`, partido en tareas con su deliverable y sus tests.
3. **Implementación** tarea a tarea, cada una con su revisión antes de pasar a la siguiente.
4. **Revisión de rama completa** al final, que es la única que ve los **huecos entre tareas**.

El paso 4 no es ceremonia. En la última rama encontró dos fallos críticos que las seis revisiones
por tarea no podían ver, porque cada una miraba su trozo y en su trozo no faltaba nada.

---

## Lo que no se hace

**No se reimplementa lo que ya funciona.** Antes de construir algo, mira si alguno de los mods
instalados ya lo hace. En la última rama se estuvo a punto de reescribir un detector de bases, un
mapa de cobertura y un registro de chunks — los tres existían ya, instalados y mejores.

La mitad del diseño de un módulo puede ser **la lista de lo que no hace y quién lo hace en su lugar**.

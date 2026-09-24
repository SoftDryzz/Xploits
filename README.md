# Xploits

Addon de [Meteor Client](https://meteorclient.com/) para Minecraft **1.21.11**, pensado para 6b6t y
otros servidores anarchy. Ocho módulos que se encienden por separado.

La idea de fondo: **ningún módulo hace algo a medias sin decirlo**. Si no puede cumplir lo que
promete, se niega y explica qué ajuste tocar — en vez de hacer algo parecido y callar.

---

## Antes de instalar

**Copia `xploits-<versión>.jar` a la carpeta `mods/` de tu instancia**, junto a Meteor, y **borra
cualquier `xploits-*.jar` anterior**: el nombre lleva la versión, y con dos a la vez el addon se carga
dos veces. Reinicia el juego. Los módulos salen en la ClickGUI, categoría **Xploits**. Qué cambia en
cada versión: [CHANGELOG](CHANGELOG.md).

### Qué necesitas instalado, y qué deja de funcionar si falta

Este addon **se apoya en otros mods a propósito**: cuando algo ya está resuelto y bien resuelto, lo
usa en vez de reimplementarlo peor. El precio es que hay que tenerlos.

| Mod | Obligatorio para | Si falta |
|---|---|---|
| **Meteor Client 1.21.11** | Todo | El addon no carga |
| **[Baritone](https://github.com/cabaletta/baritone)** | `auto-travel`, `nether-sweep` | Los dos **se niegan a lanzar** y lo dicen. Los otros cinco módulos funcionan igual |
| **[Trouser Streak](https://github.com/etianl/Trouser-Streak)** → `NewerNewChunks` | `nether-sweep` | El barrido vuela, pero **replanifica terreno que ya habías cubierto** y no deja rastro para la próxima vez. Avisa antes de despegar |
| **Trouser Streak** → `BaseFinder` | `nether-sweep` | El barrido vuela y **no encuentra nada**: es quien detecta portales, skybuilds y construcciones en el techo. Avisa antes de despegar |
| **`stash-finder`** (viene con Meteor) | `nether-sweep` | El barrido vuela y no registra contenedores. Avisa antes de despegar |

⚠️ **Los tres detectores de `nether-sweep` tienen que estar encendidos, no solo instalados.** El
módulo te avisa con toast antes de despegar si alguno falta, **pero no te lo impide**: puedes volar
una hora y no registrar nada.

### Qué módulos de Meteor toca el addon

Esto conviene saberlo porque son módulos **tuyos** que el addon enciende, apaga o consulta.

| Quién | Qué toca | Cómo |
|---|---|---|
| `auto-pvp` | `crystal-aura`, `auto-trap`, `auto-web`, `auto-anvil`, `auto-city`, `surround`, `hole-filler`, `anti-anvil`, `anti-bed`, `anti-anchor` | Los enciende y apaga según la situación. **Solo apaga los que encendió él**: si tocas uno a mano, deja de tocarlo |
| `auto-pvp` | Tu **lista de amigos de Meteor** | Mete a los tuyos mientras está encendido, para que esos diez tampoco les ataquen. Al apagarlo quita **solo los que puso él** |
| `auto-pvp` | El ajuste `anti-suicide` de `crystal-aura` | Solo lo **lee**, para saber si puede fiarse de que Meteor no te mate con tu propio cristal |
| `auto-travel`, `nether-sweep` | `elytra-fly`, `elytra-replace` | Los toman prestados durante el vuelo y los devuelven **al estado que tenían** |
| `auto-travel`, `nether-sweep` | Cinco ajustes de **Baritone** | Los cambia al despegar y los devuelve al aterrizar. ⚠️ Baritone los guarda en disco |

Todo esto, con el detalle de qué persiste y qué puede salir mal, en [Seguridad](docs/seguridad.md).

---

## Los ocho módulos

### `auto-travel` — volar a algún sitio sin dejar una flecha hacia tu base

Vuela con elytra usando Baritone, por una ruta con **patrón de despiste** para que tu traza no sea
una recta que apunte a donde vives.

**Encenderlo no vuela**: arma el viaje y espera. Se lanza con `.xploits travel go`.

**El destino se pide de tres maneras** (`destination-mode`):

| Modo | Qué pides | Ajustes |
|---|---|---|
| `COORDENADAS` | Un punto del mundo | `x`, `z` |
| `RELATIVO` | Un desplazamiento desde donde estés: 5000 y −3000 es «5000 en X y −3000 en Z desde aquí» | `offset-x`, `offset-z` |
| `AUTOPISTA` | Un eje y cuántos bloques volar por él | `axis`, `highway-distance` |

**`RELATIVO` es el que menos rastro deja.** Meteor guarda los ajustes en
`<instancia>/meteor-client/modules.nbt`: con `COORDENADAS` tu destino acaba escrito ahí; con un
desplazamiento, solo cuánto te mueves, que no dice desde dónde. Si venías de usar coordenadas, pon
`x` y `z` a 0: cambiar de modo no borra lo que ya se guardó.

**Las autopistas son ocho:** las cuatro rectas (`X_PLUS`, `X_MINUS`, `Z_PLUS`, `Z_MINUS`) y las
cuatro diagonales (`X_PLUS_Z_PLUS`, `X_PLUS_Z_MINUS`, `X_MINUS_Z_PLUS`, `X_MINUS_Z_MINUS`). En
todas, `highway-distance` son **bloques volados**: 20 000 por una diagonal avanzan unos 14 142 en
X y otros tantos en Z, y cuestan los mismos cohetes que 20 000 en recto.

| Patrón | Qué hace | Cuándo |
|---|---|---|
| `RECTO` | Nada | **En autopista.** Ahí tu traza es una más entre miles; ondular solo gasta cohetes y te saca del corredor |
| `ZIGZAG` | Ondula a los lados a menudo | Que quien te vea de lejos no pueda trazar tu rumbo con una regla |
| `QUIEBRO` | Igual, con tramos largos y desvíos anchos | Contra quien te vio desde más lejos. Gasta bastante más |
| `ESPIRAL` | Recto casi todo, espiral al final | **Protege la llegada**: no te acercas a casa en línea recta |
| `SEÑUELO` | Apunta a un sitio falso y corrige a mitad | **Protege la salida**: contra quien te ve despegar |

**El desvío se paga en cohetes.** La espiral es cara en absoluto: su largo depende del radio y las
vueltas, no de la distancia. **Baja `spiral-turns` a 0,5** salvo que quieras pagarlo — cuadruplica
el coste frente a 1,5 para solo un 31 % más de desvío.

**Para fundar una base**, dos viajes: primero por autopista con `RECTO` lo más lejos que aguante el
inventario; luego sales de la autopista y vas a las coordenadas con `ESPIRAL`. El punto donde
abandonas la autopista es la pista más fuerte que vas a dejar: no lo hagas en una coordenada
redonda ni dos veces en el mismo sitio.

### `nether-sweep` — peinar terreno para encontrar bases

Vuela un rectángulo del **Nether** con pasadas de cortacésped, para que el terreno pase por delante
de tu cliente.

**Este módulo vuela, no detecta.** Quien encuentra y guarda son tres mods que ya tienes:

- **`BaseFinder`** (Trouser Streak) — portales abiertos, construcciones en el techo, skybuilds.
- **`stash-finder`** (Meteor) — cofres, barriles, shulkers, cofres de ender.
- **`NewerNewChunks`** (Trouser Streak) — registra qué chunks te han llegado. **El barrido lo lee** y
  planifica solo sobre los huecos, así que no repite lo que llevas meses acumulando.

**Con esos tres apagados vuelas una hora y no se registra nada.** El módulo te avisa antes de
despegar, pero no te lo impide.

Por qué el Nether: un portal en `(x, z)` es una base del Overworld en `(8x, 8z)`. Cada bloque que
vuelas ahí cubre **64 veces** más superficie.

**Encenderlo no vuela**: se lanza con `.xploits sweep go`.

**Tres cosas que te pasarán la primera vez:**

1. **Un rectángulo pequeño se rechaza.** Hace falta un eje largo de unos 19 chunks. Es correcto.
2. **Hay que medir andando, no volando.** El módulo mide él solo a qué distancia te manda chunks el
   servidor, pero solo acepta muestras con el jugador casi parado. **Enciende el módulo y da una
   vuelta andando** unos segundos. La medida se tira al lanzar: para relanzar, otra vuelta.
3. **Al aterrizar te dice cuánto cubrió de verdad**, no solo que terminó. Si entrega menos del 95 %
   el aviso sale **fuerte, con toast**: esa zona no está peinada entera y hay que relanzar el mismo
   rectángulo, que se replanifica solo sobre lo que falte. **Ese aviso es la red de dos fallos
   conocidos** — ver [Problemas conocidos](docs/problemas-conocidos.md).

### `auto-pvp` — que los módulos de combate se enciendan cuando toca

**No pelea.** Enciende y apaga los de Meteor según la situación, y solo apaga los que encendió él:
si tocas uno a mano, deja de tocarlo.

Decide por **dos ejes a la vez**: en qué fase está el enemigo (acercándose, en superficie, rodeado,
enterrado, huyendo) y qué te está apuntando a ti. Lo segundo con el daño que ya tienes encima
—cristales colocados, gente con espada—, no con un contador de tótems.

**No ataca a los tuyos**: tus amigos de Meteor, los couriers de `kit-requester` y tu lista de
`auto-tpy`. Y como los cinco módulos de combate eligen su propio objetivo, mientras está encendido
**mete a los tuyos en tu lista de amigos de Meteor** para que ellos tampoco. Al apagarlo quita solo
los que puso él, nunca uno que ya tuvieras. Ver [Seguridad](docs/seguridad.md).

### `elytra-replace` — cambiar la elytra antes de que se rompa

Dos porcentajes independientes: a cuánto cambiar la puesta, y el mínimo que debe tener la de
repuesto. Elige **la peor que pase el mínimo**, para no gastar la buena. Funciona con o sin
`ElytraFly`.

### `kit-requester` — pedir kits

Pide kits a SnifferBuddy en lotes y acepta la TPA del courier que los entrega.

> **Los bots de kits llevan tiempo caídos.** El módulo funciona, pero no esperes que llegue nada
> mientras sigan así.

### `auto-tpy` — aceptar TPAs

Acepta al instante las de tu lista y las de tus amigos de Meteor. **Nunca acepta `/tpahere`**: solo
trae gente hacia ti, nunca te mueve a ti.

### `stash-keeper` — recordar dónde viste las cosas

Apunta el contenido de los contenedores que abres y de los shulkers que ves. **No mueve nada.**
Luego `.xploits find <ítem>` te dice dónde estaba.

### `consola` — ver lo que hace el addon en una ventana aparte

Abre una ventana de terminal con el logo XTO2002 arriba, el estado del juego debajo y el registro de
todo lo que dicen los módulos de Xploits. Para tenerla en la otra pantalla mientras juegas, o para
leer después qué pasó.

- **Se enciende y se apaga como cualquier módulo.** Si la dejas encendida, se abre sola al arrancar
  el juego, y no se cierra al salir de un mundo ni al morir.
- **El menú va por números:** escribe el número y pulsa Enter. `1` todo, `2` pvp, `3` travel,
  `4` sweep, `5` solo avisos, `6` pausa, `0` salir.
- **Nunca enseña coordenadas.** Lo que en el chat lleva una posición, en la ventana sale con la
  distancia o sin nada. El chat no cambia.
- **Lo que escribe se queda en disco** mientras está encendida: `meteor-client/xploits/consola/historial/`,
  un fichero por día, 30 días como mucho.
- Si el juego se cierra o se cuelga, la ventana **se queda abierta** y lo dice, para que puedas leer
  lo último que pasó.

Necesita Windows Terminal, que es la consola por defecto de Windows 11.

---

## Comandos

| Comando | Qué hace |
|---|---|
| `.xploits status` | Estado general |
| `.xploits find <ítem>` | Dónde viste ese ítem |
| `.xploits stash` | Estado del índice de contenedores |
| `.xploits pvp` | Fase, postura, tu vida y el daño que te apunta |
| `.xploits travel` · `go` · `stop` | Estado del viaje, lanzarlo, cortarlo |
| `.xploits sweep` · `go` · `stop` | Estado del barrido, lanzarlo, cortarlo |
| `.xploits reload` | Recarga los datos guardados |

---

## Lo que hay que saber de los dos que vuelan

**No se pueden usar a la vez.** Los dos dirigen al mismo Baritone y `#elytra` solo admite un
objetivo: el segundo se lo quitaría al primero, el primero cortaría a los 45 segundos con su propia
restauración —parando el vuelo del segundo a mitad— y el segundo diagnosticaría un atasco falso.
Cada uno comprueba al otro y **se niega a lanzar** mientras el otro vuele. Se usan en el mismo
viaje: vuelas con `auto-travel`, lo paras al llegar, y barres.

**El prefijo de Baritone no puede empezar por `/`.** Con barra el comando va por el camino de
comando del servidor, que Baritone no escucha, y de paso la red de seguridad se comería todos los
demás comandos con barra. Se rechaza al lanzar y se explica.

**Antes de lanzar un viaje** necesitas elytra puesta y cohetes. `elytra-replace` cambia la que
lleves, pero no te pone ninguna. Y si tienes `elytra-fly` con `chest-swap` en `Always` o
`WaitForGround`, se niega a lanzar: apagar `elytra-fly` con eso puesto te cambia la elytra por la
pechera justo antes del despegue. Pon `chest-swap` en `Never`.

**Al aterrizar**, `elytra-fly` y `elytra-replace` vuelven **al estado que tenían antes**, no a uno
declarado; si los mueves a mano durante el vuelo, manda lo que tú dejaste.

**Si sale un aviso de que un comando `#` se canceló, no es un fallo**: es la red haciendo su
trabajo. Significa que Baritone no está interceptando sus propios comandos, y que **si los
escribieras a mano se publicarían en el chat del servidor**.

---

## Dónde se guardan tus datos

```
<instancia>/meteor-client/xploits/        Cola de kits y progreso
<instancia>/meteor-client/xploits/stash/  Índice de contenedores, por mundo
<instancia>/meteor-client/xploits/consola/  Historial de la consola, 30 días como mucho
<instancia>/meteor-client/modules.nbt     Ajustes (los escribe Meteor)
<instancia>/meteor-client/friends.nbt     Lista de amigos (la escribe Meteor)
```

⚠️ **Tus coordenadas viven en más sitios de los que crees**: en `modules.nbt` si pones un destino
absoluto, en los logs de Minecraft anteriores a la 0.3.1 (desde entonces se tapan, ver [Coordenadas
en los logs](#coordenadas-en-los-logs)) y en los datos de Xaero. Si compartes un log para que te
ayuden con algo, **límpialo antes**. Ver [Seguridad](docs/seguridad.md).

---

## Idioma

El módulo `xploits` tiene un ajuste `language`: `Auto`, `Español` o `English`. En `Auto` sigue el
idioma de Minecraft —cualquier `es_*` da español, cualquier otro da inglés—; con `Español` o
`English` se queda fijo ahí pase lo que pase con el juego.

`.xploits language` dice cuál está activo ahora mismo. `.xploits language auto|es|en` lo cambia. El
chat, los toasts y la ventana de la consola cambian al momento; las descripciones del ClickGUI, en
el siguiente reinicio.

⚠️ **Con Minecraft en inglés, el addon arranca en inglés.** Si quieres español desde el principio,
pon `.xploits language es` la primera vez.

## Coordenadas en los logs

Minecraft copia cada línea del chat en `logs/latest.log`, así que cualquier posición que salga en el
chat acaba en disco. El ajuste `hide-coordinates-in-log` del módulo `xploits` las tapa con `***` en
esa copia (en pantalla se siguen viendo): `Off`, `Baritone` (solo sus líneas), `All` (por defecto) o
`All but Baritone`. También tapa los `Saving region x,z` que Baritone escribe por su cuenta.
`auto-travel` y `nether-sweep` además activan la censura de Baritone (`censorCoordinates`,
`censorRanCommands`) antes del primer objetivo, y la dejan puesta.

La consola oculta las coordenadas por defecto. Con su ajuste `hide-coordinates` apagado muestra lo
mismo que el chat y las guarda en disco (hasta 30 días de historial).

Los logs anteriores a la 0.3.1 no se limpian.

---

## Si algo no funciona

Lee **[Problemas conocidos](docs/problemas-conocidos.md)**: están los fallos que sabemos que existen,
con su síntoma y qué hacer.

Lo más habitual:

- **«Lo enciendo y no pasa nada.»** `auto-travel` y `nether-sweep` no vuelan al encenderse. Al
  encenderlos te lo dicen por chat.
- **«Me rechaza y no sé por qué.»** El mensaje dice el ajuste, su valor actual y a qué ponerlo. Si
  te manda a un ajuste que no existe con ese nombre, **eso sí es un fallo nuestro**.

---

## Para quien toque el código

| Documento | Para qué |
|---|---|
| [Arquitectura](docs/arquitectura.md) | Cómo está montado, y qué sería portable a otro cliente |
| [Seguridad](docs/seguridad.md) | Qué toca cada módulo, qué persiste a disco, qué puede filtrarse |
| [Problemas conocidos](docs/problemas-conocidos.md) | Fallos con nombre y apellidos |
| [Convenciones](docs/convenciones.md) | Cómo se trabaja aquí y por qué |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | El diseño de cada módulo y el porqué de cada decisión |
| [Construir un cliente propio](docs/cliente-propio/) | Si esto deja de ser un addon: licencias, anatomía de Meteor, Baritone por su API y hoja de ruta |

**Compilar:** `./gradlew build` → `build/libs/xploits-<versión>.jar`.

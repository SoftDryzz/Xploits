# Construir un cliente propio

Documentación para pasar de «un addon de Meteor» a «un cliente propio». Empieza por aquí.

| Documento | Qué resuelve |
|---|---|
| Este | El marco: qué implica de verdad, y las licencias, que condicionan todo lo demás |
| [Anatomía de Meteor](anatomia-de-meteor.md) | Dónde está su código, qué hace cada parte y cuánto trabajo es cada una |
| [Integrar Baritone](integrar-baritone.md) | Cómo hacerlo bien, y por qué en un cliente propio se hace **mucho mejor** que en el addon |
| [Hoja de ruta](hoja-de-ruta.md) | En qué orden construirlo, y qué se lleva de Xploits tal cual |

---

## Lo primero: las licencias

Esto no es papeleo. Decide qué podéis hacer con vuestro cliente, y conviene saberlo **antes** de
escribir una línea, no después.

### Meteor Client — GPL-3.0

Verificado en su repositorio. Si usáis su código —aunque sea parcialmente— vuestro cliente hereda
estas obligaciones:

- **Tiene que ser de código abierto**, con las fuentes publicadas.
- **Tiene que llevar la misma licencia**, GPL-3.0.
- **No puede ser cerrado ni ofuscado.**
- **Tenéis que decir de forma clara y visible a todos los usuarios** que usáis código de Meteor.

**Qué significa en la práctica.** Un cliente derivado de Meteor es legítimo y hay muchos, pero tiene
que ser abierto. Si la intención era algo cerrado o de pago, **el camino no es derivar de Meteor**:
es escribir el vuestro desde cero sobre Fabric, sin mirar su código.

Y ojo con la vía de en medio, que es la trampa: leer su código «para inspirarse» y reescribirlo de
memoria es exactamente lo que la GPL considera obra derivada. La frontera limpia es **usar sus ideas
públicas** (que un cliente tiene módulos, ajustes y un bus de eventos lo sabe cualquiera) frente a
**reproducir su implementación**.

### Baritone — LGPL-3.0, y esto sí es buena noticia

La LGPL es más permisiva para este caso: **podéis enlazar contra `baritone.api` sin que vuestro
código herede la licencia**. Lo que sí hereda es Baritone mismo si lo modificáis.

Su propio proyecto dice que **solo `baritone.api` está soportado**; todo lo de fuera puede cambiar sin
aviso. Ver [Integrar Baritone](integrar-baritone.md).

### Lo vuestro

Los núcleos puros de Xploits son código propio y podéis licenciarlos como queráis — **siempre que no
acaben enlazados dentro de una obra derivada de Meteor**, en cuyo caso el conjunto va bajo GPL-3.0.

---

## Cuánto trabajo es, sin adornos

Estos son los ficheros de código de Meteor 1.21.11, contados en su jar de fuentes:

| Parte | Ficheros | Qué es |
|---|---|---|
| **Mixins** | **212** | La parte que la gente subestima. Es lo que engancha con Minecraft |
| Módulos | 197 | Las funciones en sí, en seis categorías |
| GUI | 134 | ClickGUI, HUD, widgets, temas |
| Utils | 128 | Todo lo compartido: inventario, jugador, entidades, render |
| Eventos | 69 | El vocabulario del bus |
| Comandos | 58 | El sistema de comandos del cliente |
| Ajustes | 36 | Tipos de ajuste y su serialización |
| Renderer | 23 | Dibujo en 3D y 2D |

**La conclusión honesta:** replicar Meteor entero es un proyecto de años. Un cliente propio útil no
lo replica — **elige qué parte le importa y usa lo demás**.

Y la parte que os importa ya la tenéis escrita: el criterio de combate, la geometría de rutas, el
barrido, el índice de contenedores. Eso es lo difícil y lo que nadie más tiene. Lo que falta es el
chasis.

---

## Las tres formas de hacerlo

**1. Fork de Meteor.** Partís de su código y lo modificáis. Tenéis los 197 módulos y los 212 mixins
desde el minuto uno; a cambio, GPL-3.0 y arrastráis su arquitectura y sus decisiones. Es el camino
más rápido a algo usable y el que menos libertad deja.

**2. Cliente propio sobre Fabric, con Meteor como referencia conceptual.** Escribís vuestro chasis
—módulos, ajustes, bus de eventos, GUI— y portáis vuestros núcleos encima. Mucho más trabajo, pero
el resultado es vuestro y podéis arreglar las cosas que a Meteor le salieron torcidas. **Es el
camino que la arquitectura de Xploits ya anticipa**: los núcleos no tocan Meteor precisamente para
esto.

**3. Seguir como addon y mejorar el addon.** No es rendirse: es reconocer que el 90 % del valor que
tenéis está en los núcleos, y que el chasis de Meteor funciona. Es la opción con mejor relación
resultado/esfuerzo, y la que deja abierta la 2 para más adelante.

**Mi recomendación honesta: la 2, pero por partes y sin prisa** — y mientras tanto, seguir con la 3.
Empezar por el chasis mínimo (módulos + ajustes + bus + una GUI fea pero funcional), portar **un**
núcleo encima, y comprobar que el conjunto se sostiene antes de portar los demás. Si a mitad se ve
que no compensa, no habéis perdido los núcleos: siguen funcionando en el addon.

Lo que **no** recomiendo es empezar replicando la GUI o el sistema de render. Es la parte más
vistosa, la más larga y la que menos aporta a lo que hace especial a vuestro trabajo.

---

## Qué hace «profesional» a un cliente

No son las funciones. Son cuatro cosas aburridas que casi ningún cliente pequeño tiene:

1. **Que se pueda construir de cero con un comando**, en otra máquina, sin pasos manuales.
2. **Que tenga tests de lo que decide.** Vosotros ya lo tenéis: ~640. Eso es rarísimo en este mundillo.
3. **Que diga lo que hace y lo que no.** Un fallo que parece un resultado normal es lo que mata la
   confianza de un usuario, y no se recupera.
4. **Que sobreviva a una versión de Minecraft.** Es lo que de verdad separa un cliente vivo de uno
   abandonado, y depende casi entero de cuántos mixins tengáis y de lo frágiles que sean.

Los cuatro están en [Hoja de ruta](hoja-de-ruta.md).

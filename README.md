# VII · El Carro

<p align="center"><img src="docs/carta.svg" alt="VII · El Carro" width="180"></p>

App Android que muestra **más de 5 rutas distintas** hacia un destino —incluidos desvíos y vías
poco usadas— para carro o moto, y abre la elegida en **Google Maps** obligándolo a seguirla.

Kotlin · Jetpack Compose · osmdroid (mapa de OpenStreetMap) · API mínima 26 · compileSdk 37 ·
sin servidor propio y sin clave obligatoria.

## Cómo se usa

1. En **Google Maps** o **Waze**, abre un lugar → **Compartir** → **El Carro**.
   (También: escribir una dirección, pegar un enlace o coordenadas, o "Abrir con" en enlaces `geo:`.)
2. El Carro toma tu ubicación actual como salida y busca rutas (10–15 s).
3. Verás el mapa con todas las rutas en colores y una tarjeta por ruta: tiempo, distancia, minutos de
   más y cuánto comparte con la más rápida. Toca una para resaltarla.
4. **Abrir en Google Maps**: navega por esa ruta exacta. Para lograrlo, El Carro le pasa a Google
   1–8 puntos intermedios sobre los tramos que son propios de esa ruta (Google los anuncia como paradas).
   **Waze** solo acepta el destino (no puntos intermedios), por eso el botón de Waze aparece solo en la más rápida.

## Cómo consigue tantas rutas

Los motores de rutas dan 2 o 3 alternativas. El Carro hace unas 13 consultas por búsqueda a
**Valhalla** (motor libre sobre OpenStreetMap; servidor público de FOSSGIS, sin clave) y luego filtra:

| Paso | Qué pide |
|---|---|
| Base | La ruta principal + 2 alternativas del motor |
| Perfiles | La más corta en distancia · por vías secundarias (sin autopistas) · sin peajes |
| Desvíos | Pasar por un punto a la izquierda y a la derecha de la ruta base, al 30 %, 50 % y 70 % del camino (separación = 15 % del viaje, entre 400 m y 4 km) |
| Bloqueos | Prohibir un pedazo de la ruta base (al 25 %, 50 % y 75 %) para que busque otro camino |

Luego se ordenan por tiempo y se descartan las que **comparten más del 75 %** del recorrido con una
ya elegida o que tardan **más de +60 %** que la más rápida (ambos ajustables). Para comparar
recorridos, cada ruta se pasa a celdas de ~35 m.

Prueba real (7 nov 2026, Usaquén → Kennedy, 19 km): 15 candidatas → **7 rutas distintas** de 51 a 74 min.

## Tráfico en vivo (opcional)

Con una clave de la **API Routes de Google** (Ajustes → Tráfico en vivo) se suman las alternativas de
Google y cada tarjeta muestra "con el tráfico de ahora". Para medir cada ruta, se le pide a Google
la misma ruta con sus puntos intermedios. Google cobra por consulta (con un cupo gratis mensual):
cada búsqueda gasta 1 + N consultas (N = rutas mostradas). La clave se guarda cifrada con el Android Keystore.

Sin clave todo funciona; solo no hay tráfico en vivo (los tiempos son los típicos de OpenStreetMap).

## Carro o moto

Valhalla tiene perfil de moto (`motorcycle`), pero en ciudad suele dar los mismos tiempos que el carro;
la diferencia está en el perfil "vías secundarias" (evita troncales) y en Google Maps, que se abre en
modo **dos ruedas** (`two-wheeler`) y, con clave, la API de Google también calcula para moto
donde Google lo ofrece.

## Ajustes

Rutas a mostrar (3–10) · tiempo extra máximo (+20 a +150 %) · puntos intermedios en Google Maps (1–8) ·
evitar peajes · clave de Google · servidor Valhalla (por si tienes uno propio).

## Partes del código

| Carpeta | Qué hay |
|---|---|
| `nucleo/` | Kotlin puro, sin Android, con pruebas: JSON mínimo, geometría (polilíneas, desplazar, remuestrear), lectura de enlaces de Google Maps/Waze/geo (incluido el geohash de Waze), plan de consultas, selección por parecido, anclas para Google Maps, clientes de Valhalla/Google/Nominatim y el `Buscador` que lo orquesta |
| `red/Http.kt` | GET/POST y seguir redirecciones de enlaces cortos (maps.app.goo.gl) |
| `datos/` | Ajustes, destinos recientes, ubicación con el GPS (sin Play Services) y la clave cifrada |
| `ui/` | Compose: pantalla única, mapa osmdroid con las rutas, ajustes |

Uso justo de servidores públicos: las consultas a Valhalla van una tras otra (con una pausa corta) y
Nominatim (búsqueda de direcciones) se consulta solo al pulsar Buscar, máximo una vez por segundo.

## Pruebas

`app/src/test/.../NucleoTest.kt`: 17 pruebas del núcleo (JSON, polilíneas, enlaces, selección,
anclas, plan de desvíos, Valhalla, Google, Nominatim y el buscador completo con red simulada).
Pasan con `kotlinc` + JUnit. La capa de Android (Compose, osmdroid) se compila en Android Studio.

## Estado

Primera versión, 7 nov 2026. Falta: primer build en Android Studio y prueba en el Motorola.
Ideas: elegir la salida en el mapa, guardar rutas favoritas, avisar si la ruta elegida pasa por
zonas que el usuario marque como "evitar".

Datos de mapa y rutas © colaboradores de OpenStreetMap (ODbL).

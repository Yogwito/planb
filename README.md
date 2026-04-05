# Threaded

## 1. Nombre del proyecto

**Threaded** es un juego multijugador 2D desarrollado en Java, JavaFX y Maven, con comunicación distribuida mediante sockets UDP.

## 2. Descripción general

El proyecto implementa una experiencia cooperativa en tiempo real para 2 a 4 jugadores. Cada jugador ejecuta su propia instancia del programa y se conecta a una sala compartida. El juego está inspirado en puzzles cooperativos por salas, pero incorpora una identidad propia: los personajes están unidos por un “hilo” elástico que limita su separación y condiciona el movimiento del grupo.

La arquitectura separa interfaz, lógica de juego, estado compartido y comunicación de red. El host actúa como autoridad de la simulación y distribuye snapshots a los clientes para mantener el estado sincronizado.

## 3. Concepto del juego

Cada sala contiene plataformas, un botón, una puerta, monedas, bloques empujables y una zona de salida. Los jugadores deben coordinarse para avanzar, pero al mismo tiempo compiten por puntaje individual.

La tensión principal del juego aparece porque:

- todos deben llegar para completar la sala;
- el hilo limita cuánto puede alejarse un jugador del resto;
- cada jugador quiere sumar más puntos que los demás.

## 4. Objetivo del jugador

El objetivo inmediato es superar todas las salas de la campaña. Para conseguirlo, los jugadores deben:

- moverse por plataformas 2D;
- activar el botón que abre la puerta;
- empujar objetos cuando sea necesario;
- recoger monedas;
- llegar juntos a la salida.

El objetivo competitivo es terminar la campaña con el mayor puntaje.

## 5. Mecánicas principales

Las mecánicas implementadas actualmente son:

- movimiento horizontal;
- salto;
- plataformas estáticas;
- botón que abre la puerta;
- puerta bloqueante;
- zona de salida;
- reinicio de sala por caída;
- monedas con puntaje individual;
- bloque empujable;
- cable elástico entre jugadores;
- ranking en tiempo real;
- campaña de 5 niveles.

## 6. Reglas del juego

1. El control principal usa **mouse**: click izquierdo para mover y click derecho, o click por encima del personaje, para saltar.
2. Los jugadores están unidos por un hilo elástico que intenta corregir separaciones excesivas y bloquea separaciones extremas.
3. Para abrir la puerta de la sala, al menos un jugador debe presionar el botón.
4. Si un jugador cae al vacío, la sala se reinicia para todos.
5. Los jugadores pueden recoger monedas para aumentar su puntaje individual.
6. Los jugadores pueden empujar ciertos bloques del escenario.
7. La sala solo se completa cuando **todos los jugadores conectados** están dentro de la salida.
8. La campaña termina al completar la última sala.
9. Gana el jugador con más puntos; si hay empate, gana quien llegó antes y con menos caídas.

## 7. Sistema de puntaje

El sistema de puntaje está definido en `GameConfig` y aplicado por `HostMatchService`.

Puntajes actuales:

- Activar el botón por primera vez: `+25`
- Llegar primero a la salida: `+100`
- Llegar segundo: `+70`
- Llegadas posteriores: `+50`
- Moneda pequeña: `+10`
- Moneda grande: `+25`
- Caída al vacío: `-15`

El host es la única autoridad que modifica el puntaje. Los clientes solo visualizan el resultado por snapshot.

## 8. Condiciones de victoria y derrota

### Victoria de sala

Una sala se supera cuando todos los jugadores conectados alcanzan la zona de salida.

### Derrota de sala

Si cualquier jugador conectado cae al vacío, la sala se reinicia.

### Victoria final

Al completar la última sala, se muestra una pantalla final con:

- nombre de cada jugador;
- puntaje total;
- tiempo total de la partida;
- ganador.

## 9. Arquitectura del sistema

El proyecto está organizado por capas:

- `presentation`: JavaFX, FXML y controladores de interfaz.
- `application`: servicios de sesión, loop del host y casos de uso.
- `domain`: entidades y reglas puras del juego.
- `infrastructure`: red UDP, serialización y audio.

Esta separación facilita mantenimiento, pruebas y defensa técnica durante la sustentación.

## 10. Estructura de paquetes

### `com.dino`

- `MainApp`: arranque de JavaFX y composición principal de servicios.

### `com.dino.application`

- `services`
  - `SessionService`: estado compartido de lobby y partida.
  - `HostMatchService`: simulación autoritativa del host.
  - `EventBus`: comunicación desacoplada entre lógica, UI y sonido.
- `usecases`
  - `CreateSessionUseCase`
  - `JoinSessionUseCase`

### `com.dino.domain`

- `entities`
  - `Player`
  - `PlatformTile`
  - `ButtonSwitch`
  - `Door`
  - `ExitZone`
  - `CollectibleItem`
  - `PushBlock`
- `rules`
  - `GameRules`
- `events`
  - `EventNames`

### `com.dino.infrastructure`

- `network`
  - `UdpPeer`
- `serialization`
  - `MessageSerializer`
- `audio`
  - `SoundManager`

### `com.dino.presentation`

- `controllers`
  - `StartMenuController`
  - `LobbyController`
  - `GameController`
  - `GameOverController`
- `components`
  - `ScoreBoardObserver`
  - `EventLogObserver`

## 11. Flujo general de una partida

1. El usuario abre la aplicación.
2. Ingresa nombre y parámetros de red.
3. Elige crear sala o unirse.
4. Ambos jugadores llegan al lobby y se marcan como listos.
5. El host inicia la partida.
6. El host carga el nivel 1 y comienza la simulación.
7. Los clientes envían input; el host simula y manda snapshots.
8. Los jugadores avanzan por las salas, suman puntos y evitan caer.
9. Al completar la última sala, el host publica `GAME_OVER`.
10. Se abre la pantalla final con el ranking.

## 12. Flujo host/cliente

### Host

- crea la sala;
- enlaza el socket UDP;
- acepta `JOIN`, `READY`, `INPUT`, `HEARTBEAT` y `ACK`;
- simula el juego;
- genera snapshots;
- decide puntajes, reinicios, avance de nivel y fin de campaña.

### Cliente

- se une a una sala existente;
- enlaza su propio socket UDP;
- envía `JOIN`, `READY`, `INPUT`, `HEARTBEAT` y `ACK`;
- no resuelve reglas críticas;
- recibe snapshots y los representa visualmente.

## 13. Cómo funciona la comunicación UDP

La comunicación es exclusivamente por UDP.

### Mensajes importantes

- `JOIN`: un cliente solicita entrar al lobby.
- `READY`: un jugador se marca como listo.
- `LOBBY_SNAPSHOT`: estado compartido del lobby.
- `START_GAME`: señal para abrir la partida.
- `INPUT`: intención de movimiento/salto enviada al host.
- `SNAPSHOT`: estado autoritativo de la partida.
- `HEARTBEAT`: pulso liviano para indicar que un peer sigue vivo.
- `ACK`: confirmación ligera de transiciones críticas.
- `DISCONNECT`: salida voluntaria de un jugador.
- `GAME_OVER`: cierre de la campaña.

### Modelo de sincronización

- El host simula.
- El cliente no corrige la lógica.
- Los snapshots incluyen jugadores, nivel, puerta, botón, salida, monedas y bloques empujables.
- El cliente suaviza visualmente a los remotos, pero no altera el estado lógico.

## 14. Patrones de diseño utilizados

### Observer

Se usa mediante `EventBus`.

Permite que:

- `HostMatchService` publique eventos,
- `GameController` reaccione visualmente,
- `SoundManager` dispare sonidos,
- `EventLogObserver` actualice el log,
- `ScoreBoardObserver` reconstruya el ranking.

### Policy / Rules Object

`GameRules` centraliza reglas puras del dominio:

- colisiones base;
- botón;
- salida;
- distancia del hilo.

Esto evita duplicar lógica entre render, red y simulación.

## 15. Principios SOLID aplicados

### Single Responsibility Principle

- `UdpPeer` solo transporta datagramas.
- `SessionService` solo conserva estado compartido.
- `HostMatchService` solo simula la partida.
- `SoundManager` solo reacciona a eventos de audio.

### Open/Closed Principle

Se añadieron nuevos elementos como `PushBlock` sin rehacer el sistema completo de red o controladores.

### Liskov Substitution Principle

Las entidades del dominio funcionan como DTOs simples y no introducen jerarquías problemáticas ni contratos incompatibles.

### Interface Segregation Principle

La UI no depende de detalles de bajo nivel del socket; solo conoce servicios y snapshots.

### Dependency Inversion Principle

Los controladores trabajan con servicios de aplicación (`SessionService`, `EventBus`, `HostMatchService`) en vez de manipular directamente la capa de red.

## 16. Tecnologías usadas

- Java 21
- Maven
- JavaFX
- Jackson
- Java Sound API
- JUnit 5

## 17. Cómo ejecutar el proyecto

Desde la raíz del repositorio:

```bash
mvn javafx:run
```

También se puede ejecutar desde IntelliJ importando el proyecto Maven y corriendo `MainApp`.

## 18. Cómo correr tests

```bash
mvn test
```

La suite valida reglas puras, serialización, snapshots y partes clave del loop autoritativo.

## 19. Cómo probar dos instancias localmente

### Instancia 1 (host)

1. Ejecutar la aplicación.
2. Elegir **Crear sala**.
3. Escribir nombre.
4. Usar puerto local, por ejemplo `5000`.
5. Elegir `2` jugadores esperados.

### Instancia 2 (cliente)

1. Ejecutar otra instancia.
2. Elegir **Unirse**.
3. Escribir nombre distinto.
4. Usar otro puerto local, por ejemplo `5001`.
5. Ingresar la IP y puerto del host.

### Verificación mínima

- ambos aparecen en el lobby;
- ambos pueden marcar `Listo`;
- el host inicia la partida;
- ambos ven movimiento, botón, puerta, monedas y bloque empujable;
- ambos reciben la pantalla final.

## 20. Posibles problemas comunes y soluciones

### El cliente no entra a la partida

- Verificar IP y puerto del host.
- Revisar firewall local.
- Confirmar que ambos equipos estén en la misma red.

### El juego se siente con lag

- El proyecto ya reenvía inputs, usa heartbeats y snapshots con secuencia.
- Aun así, redes Wi‑Fi inestables pueden generar jitter visual.

### Un jugador queda “fantasma”

- El host expira peers inactivos por timeout.
- Si el problema persiste, volver al menú y crear una sesión nueva.

### No hay sonido

- Verificar configuración de audio del equipo.
- El sonido es procedural y no depende de archivos externos, pero sí de soporte de Java Sound.

## 21. Estado actual del proyecto

Estado actual:

- campaña de 5 niveles;
- juego funcional con host autoritativo;
- comunicación por UDP;
- puntaje individual;
- sonidos automáticos;
- render con suavizado visual;
- documentación técnica en español;
- suite de tests en verde.

Lo que sigue pendiente fuera del código es la validación manual final en dos instancias reales y la preparación del PDF con pantallazos.

## 22. Trabajo futuro o mejoras posibles

- más niveles;
- más objetos interactivos;
- selector de volumen;
- mejores indicadores de calidad de conexión;
- editor de niveles;
- tutorial inicial;
- efectos visuales y animaciones más avanzadas;
- reconexión más robusta de clientes.

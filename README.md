# Threaded

Juego multijugador 2D con Java, JavaFX y sockets UDP. Dos o más jugadores comparten una campaña corta de salas cooperativas con una restricción de distancia llamada `thread`: si se separan demasiado, el cable se tensa y termina corrigiendo su movimiento. Deben colaborar para abrir la puerta y llegar a la salida, pero compiten por el mayor puntaje final.

## Reglas del MVP

1. El movimiento temporal usa `flechas izquierda/derecha` y `Space` para saltar.
2. Los jugadores no pueden separarse más que la distancia máxima del `thread`, y además el cable actúa como resorte.
3. Un jugador debe activar el botón para abrir la puerta.
4. Si un jugador cae al vacío, la sala se reinicia para todos.
5. La campaña avanza por varias salas; la partida termina cuando todos los jugadores conectados completan la última salida.
6. Gana el jugador con más puntos; en empate, gana quien llegó antes y con menos caídas.

## Puntaje

- Activar el botón por primera vez: `+25`.
- Llegar primero a la salida: `+100`.
- Llegar segundo: `+70`.
- Llegadas posteriores: `+50`.
- Caerse al vacío: `-15`.

## Capas de la arquitectura

- `presentation`: controladores JavaFX y FXML.
  - Ejemplos: `StartMenuController`, `LobbyController`, `GameController`, `GameOverController`.
- `application`: lógica de sesión, loop del host y casos de uso.
  - Ejemplos: `HostMatchService`, `SessionService`, `CreateSessionUseCase`, `JoinSessionUseCase`.
- `infrastructure`: comunicación UDP, serialización y sonido.
  - Ejemplos: `UdpPeer`, `MessageSerializer`, `SoundManager`.
- `domain`: entidades y reglas puras del juego.
  - Ejemplos: `Player`, `PlatformTile`, `ButtonSwitch`, `Door`, `ExitZone`, `GameRules`.

## Patrones de diseño usados

- `Observer`:
  - `EventBus` desacopla gameplay, audio y UI.
  - `ScoreBoardObserver` y `EventLogObserver` escuchan snapshots y eventos.
- `Strategy / Policy` simple:
  - `GameRules` concentra reglas puras de colisión, salida y restricción del hilo.
  - Esto evita duplicar reglas entre render, red y host loop.

## SOLID aplicado

- `S`:
  - `UdpPeer` solo maneja UDP.
  - `SessionService` concentra estado compartido.
  - `HostMatchService` resuelve la simulación autoritativa.
- `O`:
  - Se agregaron nuevas reglas y entidades sin rehacer la capa de red.
- `L`:
  - Las entidades simples (`Door`, `ButtonSwitch`, `PlatformTile`) son intercambiables en snapshots sin contratos especiales.
- `I`:
  - La lógica de UI no depende de detalles de UDP; solo consume estado/eventos.
- `D`:
  - Controladores dependen de servicios centrales (`SessionService`, `EventBus`) y no de implementaciones de bajo nivel del socket.

## Comunicación

- La comunicación entre instancias es exclusivamente por `UDP`.
- El host es autoritativo:
  - recibe input,
  - simula,
  - resuelve colisiones,
  - actualiza puntajes,
  - difunde snapshots.
- El cliente suaviza visualmente a los jugadores remotos, pero no altera el estado lógico recibido del host.

## Flujo de pantallas

1. `start_menu.fxml`: captura nombres y parámetros de conexión.
2. `lobby.fxml`: espera jugadores y confirma `ready`.
3. `game.fxml`: sala jugable con sincronización en tiempo real.
4. `game_over.fxml`: ganador, nombres, puntajes y tiempo total.

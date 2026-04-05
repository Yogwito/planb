# Threaded

Juego multijugador 2D con Java, JavaFX y sockets UDP. Dos o más jugadores comparten una campaña corta de salas cooperativas con una restricción de distancia llamada `thread`: si se separan demasiado, el cable se tensa y termina corrigiendo su movimiento. Deben colaborar para abrir la puerta y llegar a la salida, pero compiten por el mayor puntaje final.

## Reglas del MVP

1. El control principal usa `mouse`: click izquierdo para moverte y click derecho, o un click por encima del personaje, para saltar. Como respaldo, también funcionan `flechas izquierda/derecha` y `Space`.
2. Los jugadores no pueden separarse más que la distancia máxima del `thread`, y además el cable actúa como resorte.
3. Los jugadores pueden interactuar con elementos del nivel y empujar cajas/bloques móviles sincronizados por UDP.
4. Un jugador debe activar el botón para abrir la puerta.
5. Si un jugador cae al vacío, la sala se reinicia para todos.
6. La campaña avanza por varias salas; la partida termina cuando todos los jugadores conectados completan la última salida.
7. Gana el jugador con más puntos; en empate, gana quien llegó antes y con menos caídas.

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

## Buenas practicas aplicadas

- Configuracion centralizada en `GameConfig` para evitar numeros magicos.
- Reglas del juego concentradas en `GameRules` para no duplicar logica entre host y render.
- Snapshots defensivos en `SessionService` para no exponer estado mutable directo a la UI.
- Simulacion critica concentrada en `HostMatchService`, con el cliente limitado a input y render.
- Suite automatizada con `mvn test` para validar reglas base, serializacion y loop autoritativo.

## Comunicación

- La comunicación entre instancias es exclusivamente por `UDP`.
- El host es autoritativo:
  - recibe input,
  - simula,
  - resuelve colisiones,
  - actualiza puntajes,
  - difunde snapshots.
- El cliente suaviza visualmente a los jugadores remotos, pero no altera el estado lógico recibido del host.
- Topología usada:
  - un jugador crea la sala y actúa como `host`;
  - los demás clientes se conectan directamente por UDP al host;
  - el host redistribuye snapshots al resto.
- Esto mantiene una comunicación distribuida real entre computadores distintos, sin TCP y sin servidor externo.

## Qué mostrar en la sustentación

- Dos instancias en pantallas diferentes, cada una con su propio nombre.
- Movimiento en vivo por UDP.
- Interacción con botón, puerta, monedas y bloque empujable.
- Puntaje en tiempo real.
- Sonidos automáticos por salto, colisión, puntos, tensión del hilo, reinicio y fin.
- Pantalla final con nombres, puntajes, tiempo total y ganador.

## Base para el PDF de entrega

El documento puede salir directamente de esta estructura:

1. Descripcion del juego y objetivo cooperativo/competitivo.
2. Reglas del juego y sistema de puntaje.
3. Arquitectura por capas: `presentation`, `application`, `domain`, `infrastructure`.
4. Patrones usados: `Observer` y `Policy/GameRules`.
5. Comunicacion UDP host autoritativo entre peers reales.
6. Aplicacion concreta de SOLID.
7. Dificultades y soluciones:
   - sincronizacion UDP,
   - suavizado visual del remoto,
   - tension del hilo,
   - reinicios y transiciones criticas.
8. Pantallazos:
   - menu inicial,
   - lobby,
   - partida en vivo,
   - puerta/boton/monedas/caja empujable,
   - pantalla final.

## Flujo de pantallas

1. `start_menu.fxml`: captura nombres y parámetros de conexión.
2. `lobby.fxml`: espera jugadores y confirma `ready`.
3. `game.fxml`: sala jugable con sincronización en tiempo real.
4. `game_over.fxml`: ganador, nombres, puntajes y tiempo total.

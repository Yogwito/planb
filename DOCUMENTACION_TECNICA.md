# Documentación Técnica de Threaded

## 1. Responsabilidades por capa

### Capa `presentation`

Responsable de:

- cargar vistas FXML;
- capturar input del usuario;
- dibujar el juego;
- presentar HUD, ranking y feedback visual;
- cambiar entre pantallas.

Clases principales:

- `StartMenuController`
- `LobbyController`
- `GameController`
- `GameOverController`

### Capa `application`

Responsable de:

- almacenar el estado de la sesión;
- ejecutar la simulación autoritativa del host;
- orquestar casos de uso de crear sala y unirse.

Clases principales:

- `SessionService`
- `HostMatchService`
- `CreateSessionUseCase`
- `JoinSessionUseCase`
- `EventBus`

### Capa `domain`

Responsable de:

- modelar entidades del juego;
- declarar eventos;
- concentrar reglas puras.

Clases principales:

- `Player`
- `PlatformTile`
- `ButtonSwitch`
- `Door`
- `ExitZone`
- `CollectibleItem`
- `PushBlock`
- `GameRules`

### Capa `infrastructure`

Responsable de:

- comunicación UDP;
- serialización/deserialización;
- sonido procedural.

Clases principales:

- `UdpPeer`
- `MessageSerializer`
- `SoundManager`

## 2. Flujo de entrada del usuario

### Menú inicial

El jugador escribe:

- nombre;
- IP local;
- puerto local;
- si es cliente: IP y puerto del host.

Luego decide si crear sala o unirse.

### Lobby

Cada jugador se marca como listo.

El host:

- acepta jugadores;
- mantiene snapshots del lobby;
- inicia la partida cuando corresponde.

### Partida

El control principal es por mouse:

- click izquierdo: mover;
- click derecho o click alto: saltar.

Como respaldo temporal:

- flecha izquierda;
- flecha derecha;
- espacio.

## 3. Flujo de red

### Unión de un cliente

1. El cliente crea su `UdpPeer`.
2. Hace `bind` local.
3. Envía `JOIN` al host.
4. El host registra el peer y lo agrega al estado.
5. El host redistribuye `LOBBY_SNAPSHOT`.

### Durante el lobby

- Los clientes envían `READY`.
- Los clientes envían `HEARTBEAT`.
- El host difunde snapshots del lobby.

### Al iniciar la partida

1. El host crea `HostMatchService`.
2. Inicializa el nivel.
3. Genera un snapshot inicial.
4. Difunde `START_GAME` varias veces para tolerar pérdida de paquetes.

### Durante la partida

- El cliente envía `INPUT`.
- El host simula.
- El host difunde `SNAPSHOT`.
- El cliente interpola visualmente.

### Al final

- El host publica `GAME_OVER`.
- Difunde el mensaje varias veces para reducir fallos por pérdida puntual.
- Ambas instancias cambian a la pantalla final.

## 4. Flujo de snapshots

`SessionService` es la fuente de datos del snapshot.

Cada snapshot incluye:

- secuencia (`seq`);
- tiempo transcurrido;
- estado de ejecución;
- nivel actual;
- jugadores;
- plataformas;
- puntos de spawn;
- botón;
- puerta;
- salida;
- bloques empujables;
- monedas;
- metadatos críticos si aplica.

### Razón de diseño

Se eligió snapshot completo porque:

- simplifica la sincronización;
- reduce lógica distribuida en cliente;
- refuerza el modelo host autoritativo;
- es suficiente para el tamaño del juego.

## 5. Ciclo de vida de una partida

1. `MainApp` crea servicios base.
2. `StartMenuController` inicializa host o cliente.
3. `LobbyController` mantiene el lobby.
4. El host llama `HostMatchService.initWorld()`.
5. `GameController` entra al loop principal.
6. El host ejecuta `tick(dt)`.
7. Los clientes reciben snapshots y renderizan.
8. Si todos llegan a la salida, se avanza de nivel.
9. Si se completa el último nivel, se publica `GAME_OVER`.
10. `GameOverController` presenta el resultado final.

## 6. Responsabilidades de `MainApp`

`MainApp` no contiene reglas de juego.

Responsabilidades reales:

- crear el `Stage` principal;
- inicializar servicios globales;
- abrir la primera escena;
- reiniciar el runtime al volver al menú;
- cerrar red y audio de forma limpia.

## 7. Responsabilidades de `SessionService`

`SessionService` es un contenedor de estado compartido.

Responsabilidades:

- guardar identidad local y datos de red;
- almacenar jugadores y objetos del nivel;
- construir snapshots;
- aplicar snapshots recibidos;
- mantener información de peers;
- entregar copias defensivas para la UI.

No debe:

- resolver gameplay;
- dibujar;
- enviar paquetes por sí mismo.

## 8. Responsabilidades de `HostMatchService`

`HostMatchService` es la autoridad del juego.

Responsabilidades:

- recibir input ya validado a nivel de protocolo;
- mover jugadores;
- aplicar gravedad;
- resolver colisiones;
- aplicar el hilo elástico;
- simular bloques empujables;
- actualizar botón, puerta y salida;
- asignar puntaje;
- reiniciar salas;
- avanzar niveles;
- terminar la campaña.

No debe:

- cargar escenas JavaFX;
- renderizar;
- serializar mensajes.

## 9. Responsabilidades de `GameController`

`GameController` es el punto donde convergen:

- render;
- input local;
- feedback visual;
- integración con red;
- loop de la partida.

Responsabilidades:

- traducir mouse a input de juego;
- enviar input al host o a la simulación local si es host;
- recibir mensajes de red;
- actualizar cámara, partículas y HUD;
- dibujar jugadores, objetos, cable y nivel.

Es una clase naturalmente grande porque integra varias responsabilidades de presentación en tiempo real, pero la lógica de reglas sigue afuera, en `HostMatchService` y `GameRules`.

## 10. Rol de `EventBus`

`EventBus` implementa Observer.

Sirve para:

- notificar cambios sin acoplar clases;
- permitir que UI, audio y lógica reaccionen al mismo evento;
- evitar dependencias directas entre `HostMatchService` y controladores.

Ejemplo:

- `HostMatchService` publica `PLAYER_JUMPED`;
- `GameController` genera feedback visual;
- `SoundManager` reproduce sonido;
- `EventLogObserver` agrega texto al log.

## 11. Rol de `UdpPeer`

`UdpPeer` encapsula `DatagramSocket`.

Responsabilidades:

- hacer `bind`;
- enviar mapas serializados;
- recibir datagramas;
- enviar ráfagas para mensajes críticos;
- cerrar el socket.

No interpreta reglas de negocio.

## 12. Rol de `MessageSerializer`

`MessageSerializer` define el protocolo lógico de mensajes.

Responsabilidades:

- serializar JSON;
- deserializar JSON;
- construir mensajes con la clave `type`;
- centralizar nombres de mensajes (`JOIN`, `INPUT`, `SNAPSHOT`, etc.).

## 13. Reglas del dominio

Las reglas puras están en `GameRules`.

Ejemplos:

- intersección jugador/plataforma;
- intersección jugador/puerta;
- intersección jugador/bloque;
- pulsación de botón;
- detección de salida;
- distancia máxima permitida por el hilo.

Esto hace que la simulación del host y los tests se mantengan coherentes.

## 14. Cómo se desacopla UI, lógica y comunicación

El desacoplamiento se logra así:

- la UI no resuelve física ni puntaje;
- la lógica del host no conoce controles JavaFX;
- la red no conoce reglas del juego;
- los eventos conectan módulos sin referencias directas;
- los snapshots transportan estado, no decisiones del cliente.

En términos prácticos:

- `GameController` capta input;
- `HostMatchService` decide resultado;
- `SessionService` lo almacena;
- `UdpPeer` lo transporta;
- `EventBus` lo anuncia;
- la UI lo muestra.

## 15. Cómo defender la arquitectura en una sustentación

Una defensa técnica corta y sólida puede seguir este orden:

1. El juego usa **UDP real** entre instancias distintas.
2. El **host es autoritativo**: clientes no deciden colisiones ni puntajes.
3. La sincronización se hace por **snapshots completos**.
4. La UI está separada de la lógica y la red.
5. Se usa `EventBus` como **Observer**.
6. Se usa `GameRules` como objeto de **reglas/política**.
7. `SessionService` centraliza el estado compartido.
8. `HostMatchService` centraliza la simulación.
9. Hay tests automáticos para reglas, snapshots y comportamiento crítico del host.

## 16. Recomendación para cambios en vivo durante la sustentación

Si durante la sustentación piden modificar algo, las zonas más seguras para tocar son:

- `GameConfig`: balance de movimiento, puntaje, hilo y cámara;
- `HostMatchService`: reglas de sala, scoring y progresión;
- `GameRules`: validaciones puras;
- `GameController`: textos de HUD, feedback visual y entrada;
- `README.md` / `DOCUMENTACION_TECNICA.md`: explicación y evidencia arquitectónica.

Estas zonas son defendibles porque tienen responsabilidades claras y no exigen rehacer todo el sistema.

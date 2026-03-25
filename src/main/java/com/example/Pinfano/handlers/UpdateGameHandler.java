package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class UpdateGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);
    private final String gameTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        var logger = context.getLogger();
        logger.log("[START] UpdateGameHandler invocado");
        try {
            if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
                return response(200, "OK");
            }
            logger.log("[INPUT] Body recibido: " + event.getBody());
            JSONObject body = new JSONObject(event.getBody());
            String codigoGame = body.getString("codigoGame");
            JSONArray fichaArr = body.getJSONArray("ficha");
            int fichaFirst = fichaArr.getInt(0);
            int fichaSecond = fichaArr.getInt(1);
            int posicion = body.getInt("posicion");

            // Obtener JSON completo desde Dynamo mediante GetGameByCodigo
            logger.log("[INFO] Procesando codigoGame: " + codigoGame + " | Ficha: " + fichaArr + " | Posicion: " + posicion);
            // Obtener JSON completo
            GetGameByCodigoHandler getHandler = new GetGameByCodigoHandler();
            APIGatewayProxyRequestEvent getReq = new APIGatewayProxyRequestEvent();
            getReq.setHeaders(Map.of("X-codigoGame", codigoGame));
            logger.log("[STEP 1] Llamando a GetGameByCodigoHandler...");
            APIGatewayProxyResponseEvent getRes = getHandler.handleRequest(getReq, context);
            if (getRes.getStatusCode() != 200) {
                logger.log("[ERROR] Partida no encontrada en Dynamo para codigo: " + codigoGame);
                return error("Partida no encontrada");
            }
            JSONObject fullItem = new JSONObject(getRes.getBody());
            String idGame = fullItem.getString("idGame");
            JSONObject gameJson = fullItem.getJSONObject("json");
            int turnoActual = gameJson.getInt("turno");
            logger.log("[INFO] Turno actual antes de update: " + turnoActual + " | idGame: " + idGame);

            //PASOOOOOO
            if (posicion == -1) {
                logger.log("[INFO] Jugador PASA el turno");

                // 1. Extremos del tablero actual
                JSONArray tableroActual = gameJson.getJSONArray("tablero");
                Set<Integer> nuevos = new LinkedHashSet<>();

                for (int i = 0; i < tableroActual.length(); i++) {
                    nuevos.add(tableroActual.getJSONArray(i).getInt(0));
                }

                // 2. Recuperar lo que había antes en el paso del jugador
                JSONArray pasoArr = gameJson.getJSONArray("paso");
                String anteriorPaso = pasoArr.getString(turnoActual - 1);

                // Convertir lo anterior a Set
                Set<Integer> anteriores = new LinkedHashSet<>();
                if (!anteriorPaso.equals("") && !anteriorPaso.equals("VACIO")) {
                    for (String s : anteriorPaso.split("-")) {
                        if (!s.isEmpty()) anteriores.add(Integer.parseInt(s));
                    }
                }

                // 3. Unir anteriores + nuevos sin duplicados
                anteriores.addAll(nuevos);

                // 4. Convertir a formato “1-3-4”
                StringBuilder pasoFinal = new StringBuilder();
                for (Integer n : anteriores) {
                    if (pasoFinal.length() > 0) pasoFinal.append("-");
                    pasoFinal.append(n);
                }

                // 5. Guardar el resultado
                pasoArr.put(turnoActual - 1,
                        pasoFinal.length() == 0 ? "VACIO" : pasoFinal.toString());

                // 6. Avanzar turno
                int nuevoTurno = (turnoActual % 4) + 1;

                gameJson.put("turno", nuevoTurno);
                gameJson.put("paso", pasoArr);

                // 7. Guardar en DB y enviar WS
                Table table = dynamoDB.getTable(gameTable);
                Item item = table.getItem("idGame", idGame);
                item.withJSON("json", gameJson.toString());
                table.putItem(item);

                JSONObject sendData = new JSONObject();
                sendData.put("type", "gameUpdated");
                sendData.put("codigoGame", codigoGame);
                sendData.put("json", gameJson);
                sendData.put("turno", nuevoTurno);

                new SendJsonHandler().sendUpdateToAll(sendData, context);
                return response(200, gameJson.toString());
            }

            // MOVIO FICHA NO PASA
            JSONArray tablero = gameJson.getJSONArray("tablero");
            JSONArray fichasSalidas = gameJson.getJSONArray("fichasSalidas");
            String keyJugador = switch (turnoActual) {
                case 1 -> "fichasJ1";
                case 2 -> "fichasBot1";
                case 3 -> "fichasJ2";
                default -> "fichasBot2";
            };
            logger.log("[INFO] Actualizando fichas de: " + keyJugador);
            JSONArray fichasJugador = gameJson.getJSONArray(keyJugador);
            // ORDEN CORRECTO EN TABLERO
            JSONArray anterior = tablero.getJSONArray(posicion);
            int extremoAnterior = anterior.getInt(0);
            int nuevoFirst, nuevoSecond;
            // ... (Lógica de nuevoFirst/nuevoSecond igual)
            if (fichaFirst == extremoAnterior) {
                nuevoFirst = fichaSecond;
                nuevoSecond = fichaFirst;
            } else if (fichaSecond == extremoAnterior) {
                nuevoFirst = fichaFirst;
                nuevoSecond = fichaSecond;
            } else {
                // No encaja → pero lo pones igual
                nuevoFirst = fichaFirst;
                nuevoSecond = fichaSecond;
            }
            JSONArray nuevaFicha = new JSONArray()
                    .put(nuevoFirst)
                    .put(nuevoSecond);

            tablero.put(posicion, nuevaFicha);
            // Guardar ficha en fichasSalidas
            JSONArray salida = new JSONArray();
            salida.put(fichaFirst);
            salida.put(fichaSecond);
            fichasSalidas.put(salida);
            // Eliminar ficha del jugador
            for (int i = 0; i < fichasJugador.length(); i++) {
                JSONArray f = fichasJugador.getJSONArray(i);
                if ((f.getInt(0) == fichaFirst && f.getInt(1) == fichaSecond) ||
                        (f.getInt(0) == fichaSecond && f.getInt(1) == fichaFirst)) {
                    fichasJugador.remove(i);
                    break;
                }
            }

            // Recalcular puntos
            int suma = 0;
            for (int i = 0; i < tablero.length(); i++) {
                JSONArray f = tablero.getJSONArray(i);
                if (f.getInt(0) >= 0) suma += f.getInt(0);
            }
            int puntosA = gameJson.optInt("puntosA");
            int puntosB = gameJson.optInt("puntosB");
            if (suma > 0 && suma % 5 == 0) {
                if (turnoActual == 1 || turnoActual == 3) puntosA += suma;
                else puntosB += suma;
            }

            // CHECK 1: ¿Se superó la puntuación límite? → Fin de partida
            if (puntosA >= gameJson.getInt("puntos") || puntosB >= gameJson.getInt("puntos")) {
                return finalizarPartida(idGame, codigoGame, puntosA, puntosB, gameJson, context);
            }

            // CHECK 2: ¿El jugador se quedó sin fichas? → Fin de ronda
            if (fichasJugador.isEmpty()) {
                return finalizarRonda(idGame, codigoGame, turnoActual, gameJson, context);
            }

            //SE JUEGA NORMAL NI FIN DE RONDA NI FIN DE PARTIDA NI PASO
            // Log de eliminación de ficha
            int initialSize = gameJson.getJSONArray(keyJugador).length();
            // ... (Tu bucle for de eliminar ficha)
            logger.log("[INFO] Fichas del jugador tras eliminar: " + gameJson.getJSONArray(keyJugador).length() + " (Antes: " + initialSize + ")");
            // Avanzar turno
            int nuevoTurno = turnoActual + 1;
            if (nuevoTurno > 4) nuevoTurno = 1;
            // Actualizar JSON general
            gameJson.put("tablero", tablero);
            gameJson.put("fichasSalidas", fichasSalidas);
            gameJson.put(keyJugador, fichasJugador);
            gameJson.put("turno", nuevoTurno);
            gameJson.put("puntosA", puntosA);
            gameJson.put("puntosB", puntosB);
            // Guardar en DynamoDB
            logger.log("[STEP 2] Guardando en DynamoDB table: " + gameTable);
            Table table = dynamoDB.getTable(gameTable);
            Item item = table.getItem("idGame", idGame);
            item.withJSON("json", gameJson.toString());
            table.putItem(item);
            logger.log("[SUCCESS] DynamoDB actualizado correctamente");
            // LLAMAR A SENDJSON
            logger.log("[STEP 3] Preparando envío WebSocket vía SendJsonHandler");
            JSONObject sendData = new JSONObject();
            sendData.put("type", "gameUpdated");
            sendData.put("codigoGame", codigoGame);
            sendData.put("json", gameJson);
            sendData.put("turno", nuevoTurno);
            new SendJsonHandler().sendUpdateToAll(sendData, context);
            logger.log("[SUCCESS] Proceso completo finalizado");
            return response(200, gameJson.toString());
        } catch (Exception e) {
            logger.log("[FATAL ERROR] Exception: " + e.getMessage());
            e.printStackTrace();
            return error(e.getMessage());
        }
    }

    //FINALIZAR RONDA
    private APIGatewayProxyResponseEvent finalizarRonda(
            String idGame,
            String codigoGame,
            int turnoActual,
            JSONObject gameJson,
            Context context
    ) {
        var logger = context.getLogger();
        logger.log("[Ronda] Finalizando ronda por jugador sin fichas");
        // 1️⃣ Identificar equipo ganador
        String ganadorEquipo = (turnoActual == 1 || turnoActual == 3) ? "A" : "B";
        // 2️⃣ Calcular puntos obtenidos en la ronda
        int puntosRonda = calcularPuntosRonda(gameJson, logger);
        // 3️⃣ Sumar al equipo ganador
        int puntosA = gameJson.getInt("puntosA");
        int puntosB = gameJson.getInt("puntosB");
        if (ganadorEquipo.equals("A")) puntosA += puntosRonda;
        else puntosB += puntosRonda;
        gameJson.put("puntosA", puntosA);
        gameJson.put("puntosB", puntosB);
        // 4️⃣ Resetear estado del JSON para nueva ronda (Ciclo 1, 2, 3, 4 -> 1)
        int siguienteMano = (gameJson.getInt("mano") % 4) + 1;
        gameJson.put("mano", siguienteMano);
        // tablero vacío
        JSONArray tableroNuevo = new JSONArray();
        tableroNuevo.put(new JSONArray());
        tableroNuevo.put(new JSONArray());
        tableroNuevo.put(new JSONArray());
        tableroNuevo.put(new JSONArray());
        gameJson.put("tablero", tableroNuevo);
        // fichasSalidas vacías
        gameJson.put("fichasSalidas", new JSONArray());
        // pinfano vacío → el primer doble que salga se convierte en el nuevo pinfano
        JSONArray pinfanoArr = new JSONArray();
        pinfanoArr.put(-1);
        pinfanoArr.put(-1);
        gameJson.put("pinfano", pinfanoArr);
        // paso vacío
        JSONArray pasoArr = new JSONArray();
        pasoArr.put("");
        pasoArr.put("");
        pasoArr.put("");
        pasoArr.put("");
        gameJson.put("paso", pasoArr);
        // 5️⃣ Reparto nuevo
        Map<String, JSONArray> nuevas = repartirFichasNuevaRonda();
        gameJson.put("fichasJ1", nuevas.get("J1"));
        gameJson.put("fichasBot1", nuevas.get("J2"));
        gameJson.put("fichasJ2", nuevas.get("J3"));
        gameJson.put("fichasBot2", nuevas.get("J4"));
        // 6️⃣ Guardar en DynamoDB
        logger.log("[Ronda] Guardando nueva ronda en DB");
        Table table = dynamoDB.getTable(gameTable);
        Item item = table.getItem("idGame", idGame);
        item.withJSON("json", gameJson.toString());
        table.putItem(item);
        // 7️⃣ Enviar WebSocket
        enviarWS_RondaTerminada(codigoGame, gameJson, context);
        return response(200, gameJson.toString());
    }
    // 🔵 FINALIZAR PARTIDA COMPLETA
    private APIGatewayProxyResponseEvent finalizarPartida(
            String idGame,
            String codigoGame,
            int puntosA,
            int puntosB,
            JSONObject gameJson,
            Context context
    ) {
        var logger = context.getLogger();
        logger.log("[Partida] Finalizando partida");
        // 1️⃣ Determinar equipo ganador
        String ganador = (puntosA > puntosB) ? "A" : "B";
        // 2️⃣ Enviar WS a todos
        enviarWS_PartidaTerminada(codigoGame, ganador, puntosA, puntosB, context);
        // 3️⃣ Marcar partida terminada
        Table table = dynamoDB.getTable(gameTable);
        Item item = table.getItem("idGame", idGame);
        gameJson.put("terminado", true);
        item.withJSON("json", gameJson.toString());
        table.putItem(item);
        try {
            logger.log("[Partida] TODO OK");
        } catch (Exception e) {
            logger.log("[Partida] Error llamando a DeleteGame: " + e.getMessage());
        }
        // 5️⃣ Sumar +10 rango a usuarios del equipo ganador
        sumarRangoUsuariosDelEquipoGanador(gameJson, ganador, logger);
        return response(200, "{\"fin\":true}");
    }
    // CALCULAR PUNTOS DE UNA RONDA
    private int calcularPuntosRonda(JSONObject gameJson, LambdaLogger logger) {
        int suma = 0;
        JSONArray j1 = gameJson.getJSONArray("fichasJ1");
        JSONArray j2 = gameJson.getJSONArray("fichasBot1");
        JSONArray j3 = gameJson.getJSONArray("fichasJ2");
        JSONArray j4 = gameJson.getJSONArray("fichasBot2");
        suma += sumarFichas(j1);
        suma += sumarFichas(j2);
        suma += sumarFichas(j3);
        suma += sumarFichas(j4);
        // Redondeo a múltiplo de 5
        int resto = suma % 5;
        if (resto != 0) suma += (5 - resto);
        logger.log("[Ronda] Puntos sumados = " + suma);
        return suma;
    }

    private int sumarFichas(JSONArray arr) {
        int total = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONArray f = arr.getJSONArray(i);
            total += f.getInt(0);
            total += f.getInt(1);
        }
        return total;
    }
    // 🔵 REPARTIR 28 FICHAS A 4 JUGADORES
    private Map<String, JSONArray> repartirFichasNuevaRonda() {
        List<int[]> fichas = new ArrayList<>();
        // Generar todas las fichas
        for (int i = 0; i <= 6; i++) {
            for (int j = i; j <= 6; j++) {
                fichas.add(new int[]{i, j});
            }
        }
        // Mezclar
        Collections.shuffle(fichas);
        Map<String, JSONArray> out = new HashMap<>();
        out.put("J1", new JSONArray());
        out.put("J2", new JSONArray());
        out.put("J3", new JSONArray());
        out.put("J4", new JSONArray());

        // Reparto
        for (int i = 0; i < 7; i++) out.get("J1").put(new JSONArray(fichas.remove(0)));
        for (int i = 0; i < 7; i++) out.get("J2").put(new JSONArray(fichas.remove(0)));
        for (int i = 0; i < 7; i++) out.get("J3").put(new JSONArray(fichas.remove(0)));
        for (int i = 0; i < 7; i++) out.get("J4").put(new JSONArray(fichas.remove(0)));

        return out;
    }

    // 🔵 SUMAR RANGO A GANADORES
    private void sumarRangoUsuariosDelEquipoGanador(JSONObject gameJson, String ganador, LambdaLogger logger) {
        try {
            JSONArray players = gameJson.getJSONArray("listaPlayers");

            // jugadores: [J1, J2, J3, J4]
            List<String> equipo = new ArrayList<>();

            if (ganador.equals("A")) {
                equipo.add(players.getString(0)); // J1
                equipo.add(players.getString(2)); // J3
            } else {
                equipo.add(players.getString(1)); // J2
                equipo.add(players.getString(3)); // J4
            }

            for (String user : equipo) {
                if (!user.equalsIgnoreCase("bot")) {
                    actualizarRangoUsuario(user, logger);
                }
            }

        } catch (Exception e) {
            logger.log("[Rango] ERROR: " + e.getMessage());
        }
    }
    private void actualizarRangoUsuario(String username, LambdaLogger logger) {
        try {
            Table t = dynamoDB.getTable("PinfanoUsers");
            Item usr = t.getItem("username", username);

            int rango = usr.getInt("rango");
            rango += 10;

            usr.withInt("rango", rango);
            t.putItem(usr);

            logger.log("[Rango] +" + username);

        } catch (Exception e) {
            logger.log("[Rango] ERROR actualizando: " + e.getMessage());
        }
    }
    // 🔵 WEBSOCKET: RONDA TERMINADA
    private void enviarWS_RondaTerminada(
            String codigoGame,
            JSONObject json,
            Context context
    ) {
        JSONObject msg = new JSONObject();
        msg.put("type", "rondaTerminada");
        msg.put("codigoGame", codigoGame);
        msg.put("json", json);
        new SendJsonHandler().sendUpdateToAll(msg, context);
    }
    // 🔵 WEBSOCKET: PARTIDA TERMINADA
    private void enviarWS_PartidaTerminada(
            String codigoGame,
            String ganador,
            int puntosA,
            int puntosB,
            Context context
    ) {
        JSONObject msg = new JSONObject();
        msg.put("type", "partidaTerminada");
        msg.put("codigoGame", codigoGame);
        msg.put("ganador", ganador);
        msg.put("puntosA", puntosA);
        msg.put("puntosB", puntosB);

        new SendJsonHandler().sendUpdateToAll(msg, context);
    }
    private APIGatewayProxyResponseEvent response(int code, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withHeaders(Map.of(
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*"
                ))
                .withBody(body);
    }

    private APIGatewayProxyResponseEvent error(String msg) {
        return response(400, "{\"error\":\"" + msg + "\"}");
    }
}
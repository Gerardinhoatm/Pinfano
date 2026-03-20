package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

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
            int posicion = body.getInt("posicion");

            // 1. Obtener la partida actual desde DynamoDB usando el GetGameByCodigoHandler
            GetGameByCodigoHandler getHandler = new GetGameByCodigoHandler();
            APIGatewayProxyRequestEvent getReq = new APIGatewayProxyRequestEvent();
            getReq.setHeaders(Map.of("X-codigoGame", codigoGame));

            APIGatewayProxyResponseEvent getRes = getHandler.handleRequest(getReq, context);

            if (getRes.getStatusCode() != 200) {
                logger.log("[ERROR] Partida no encontrada para codigo: " + codigoGame);
                return error("Partida no encontrada");
            }

            JSONObject fullItem = new JSONObject(getRes.getBody());
            String idGame = fullItem.getString("idGame");
            JSONObject gameJson = fullItem.getJSONObject("json");
            int turnoActual = gameJson.getInt("turno");

            // ================================================================
            // CASO A: EL JUGADOR PASA TURNO (posicion == -1)
            // ================================================================
            if (posicion == -1) {
                logger.log("[INFO] El jugador " + turnoActual + " no tiene movimientos. Registrando PASO.");

                String fallos = body.optString("fallos", "VACIO");
                JSONArray pasoArray = gameJson.getJSONArray("paso");

                // Actualizamos la posición del jugador en el array de pasos (índice 0-3)
                pasoArray.put(turnoActual - 1, fallos);
                gameJson.put("paso", pasoArray);

                int nuevoTurno = (turnoActual % 4) + 1;
                gameJson.put("turno", nuevoTurno);

                return guardarYNotificar(idGame, gameJson, codigoGame, nuevoTurno, context);
            }

            // ================================================================
            // CASO B: EL JUGADOR REALIZA UNA JUGADA (posicion >= 0)
            // ================================================================
            JSONArray fichaArr = body.getJSONArray("ficha");
            int fichaFirst = fichaArr.getInt(0);
            int fichaSecond = fichaArr.getInt(1);

            JSONArray tablero = gameJson.getJSONArray("tablero");
            JSONArray fichasSalidas = gameJson.getJSONArray("fichasSalidas");

            String keyJugador = switch (turnoActual) {
                case 1 -> "fichasJ1";
                case 2 -> "fichasBot1";
                case 3 -> "fichasJ2";
                default -> "fichasBot2";
            };

            JSONArray fichasJugador = gameJson.getJSONArray(keyJugador);

            // Lógica de orientación en el tablero
            JSONArray anterior = tablero.getJSONArray(posicion);
            int extremoAnterior = anterior.getInt(0);

            int nuevoFirst, nuevoSecond;
            if (fichaFirst == extremoAnterior) {
                nuevoFirst = fichaSecond;
                nuevoSecond = fichaFirst;
            } else if (fichaSecond == extremoAnterior) {
                nuevoFirst = fichaFirst;
                nuevoSecond = fichaSecond;
            } else {
                nuevoFirst = fichaFirst;
                nuevoSecond = fichaSecond;
            }

            JSONArray nuevaFicha = new JSONArray().put(nuevoFirst).put(nuevoSecond);
            tablero.put(posicion, nuevaFicha);

            // Registrar en fichas que ya han salido
            JSONArray salida = new JSONArray().put(fichaFirst).put(fichaSecond);
            fichasSalidas.put(salida);

            // Eliminar la ficha de la mano del jugador
            for (int i = 0; i < fichasJugador.length(); i++) {
                JSONArray f = fichasJugador.getJSONArray(i);
                if ((f.getInt(0) == fichaFirst && f.getInt(1) == fichaSecond) ||
                        (f.getInt(0) == fichaSecond && f.getInt(1) == fichaFirst)) {
                    fichasJugador.remove(i);
                    break;
                }
            }

            // Recalcular puntos (Suma de los extremos del tablero)
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

            // Actualizar campos del JSON antes de guardar
            int nuevoTurno = (turnoActual % 4) + 1;
            gameJson.put("tablero", tablero);
            gameJson.put("fichasSalidas", fichasSalidas);
            gameJson.put(keyJugador, fichasJugador);
            gameJson.put("turno", nuevoTurno);
            gameJson.put("puntosA", puntosA);
            gameJson.put("puntosB", puntosB);

            return guardarYNotificar(idGame, gameJson, codigoGame, nuevoTurno, context);

        } catch (Exception e) {
            logger.log("[FATAL ERROR] Exception: " + e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * Centraliza el guardado en DynamoDB y la notificación masiva por WebSocket.
     */
    private APIGatewayProxyResponseEvent guardarYNotificar(String idGame, JSONObject gameJson, String codigoGame, int nuevoTurno, Context context) {
        var logger = context.getLogger();

        // Guardar actualización en DynamoDB
        Table table = dynamoDB.getTable(gameTable);
        Item item = table.getItem("idGame", idGame);
        item.withJSON("json", gameJson.toString());
        table.putItem(item);
        logger.log("[SUCCESS] DynamoDB actualizado");

        // Preparar datos para el WebSocket
        JSONObject sendData = new JSONObject();
        sendData.put("codigoGame", codigoGame);
        sendData.put("json", gameJson);
        sendData.put("turno", nuevoTurno);

        // Enviar a todos los jugadores conectados
        SendJsonHandler sender = new SendJsonHandler();
        sender.sendUpdateToAll(sendData, context);
        logger.log("[SUCCESS] Notificación enviada a todos los jugadores");

        return response(200, gameJson.toString());
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
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

        try {
            if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
                return response(200, "OK");
            }

            JSONObject body = new JSONObject(event.getBody());
            String codigoGame = body.getString("codigoGame");
            JSONArray fichaArr = body.getJSONArray("ficha");
            int fichaFirst = fichaArr.getInt(0);
            int fichaSecond = fichaArr.getInt(1);
            int posicion = body.getInt("posicion");

            // Obtener JSON completo desde Dynamo mediante GetGameByCodigo
            GetGameByCodigoHandler getHandler = new GetGameByCodigoHandler();
            APIGatewayProxyRequestEvent getReq = new APIGatewayProxyRequestEvent();
            getReq.setHttpMethod("GET");
            getReq.setHeaders(Map.of("X-codigoGame", codigoGame));

            APIGatewayProxyResponseEvent getRes = getHandler.handleRequest(getReq, context);
            if (getRes.getStatusCode() != 200) return error("Partida no encontrada");

            JSONObject fullItem = new JSONObject(getRes.getBody());
            String idGame = fullItem.getString("idGame");
            JSONObject gameJson = fullItem.getJSONObject("json");

            // Actualizar tablero y estado del jugador
            JSONArray tablero = gameJson.getJSONArray("tablero");
            JSONArray fichasSalidas = gameJson.getJSONArray("fichasSalidas");

            int turnoActual = gameJson.getInt("turno");

            String keyJugador = switch (turnoActual) {
                case 1 -> "fichasJ1";
                case 2 -> "fichasBot1";
                case 3 -> "fichasJ2";
                default -> "fichasBot2";
            };

            JSONArray fichasJugador = gameJson.getJSONArray(keyJugador);

            // ORDEN CORRECTO EN TABLERO
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
            Table table = dynamoDB.getTable(gameTable);
            Item item = table.getItem("idGame", idGame);
            item.withJSON("json", gameJson.toString());
            table.putItem(item);

            // 🔥 LLAMAR A SENDJSON PARA MANDAR A TODOS ← IMPORTANTE
            JSONObject sendData = new JSONObject();
            sendData.put("codigoGame", codigoGame);
            sendData.put("jsonGame", gameJson);
            sendData.put("turno", nuevoTurno);

            SendJsonHandler sender = new SendJsonHandler();
            sender.sendUpdateToAll(sendData, context);

            return response(200, gameJson.toString());

        } catch (Exception e) {
            return error(e.getMessage());
        }
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
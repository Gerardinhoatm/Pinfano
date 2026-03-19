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
            JSONArray fichaArr = body.getJSONArray("ficha");
            int posicion = body.getInt("posicion");

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

            // Lógica de fichas
            JSONArray tablero = gameJson.getJSONArray("tablero");
            JSONArray fichasSalidas = gameJson.getJSONArray("fichasSalidas");

            String keyJugador = switch (turnoActual) {
                case 1 -> "fichasJ1";
                case 2 -> "fichasBot1";
                case 3 -> "fichasJ2";
                default -> "fichasBot2";
            };
            logger.log("[INFO] Actualizando fichas de: " + keyJugador);

            // ... (Lógica de nuevoFirst/nuevoSecond igual)

            // Log de eliminación de ficha
            int initialSize = gameJson.getJSONArray(keyJugador).length();
            // ... (Tu bucle for de eliminar ficha)
            logger.log("[INFO] Fichas del jugador tras eliminar: " + gameJson.getJSONArray(keyJugador).length() + " (Antes: " + initialSize + ")");

            // Avanzar turno
            int nuevoTurno = turnoActual + 1;
            if (nuevoTurno > 4) nuevoTurno = 1;
            gameJson.put("turno", nuevoTurno);
            logger.log("[INFO] Nuevo turno calculado: " + nuevoTurno);

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
            sendData.put("codigoGame", codigoGame);
            sendData.put("json", gameJson);
            sendData.put("turno", nuevoTurno);

            SendJsonHandler sender = new SendJsonHandler();
            sender.sendUpdateToAll(sendData, context);
            logger.log("[SUCCESS] Proceso completo finalizado");

            return response(200, gameJson.toString());

        } catch (Exception e) {
            logger.log("[FATAL ERROR] Exception: " + e.getMessage());
            e.printStackTrace();
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
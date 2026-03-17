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

        if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(corsHeaders());
        }

        try {
            // --- Parsear body de la peticion ---
            JSONObject body = new JSONObject(event.getBody());
            String codigoGame = body.getString("codigoGame");
            JSONArray fichaArr = body.getJSONArray("ficha");
            int fichaFirst = fichaArr.getInt(0);
            int fichaSecond = fichaArr.getInt(1);
            int posicion = body.getInt("posicion");

            context.getLogger().log("UpdateGame: codigoGame=" + codigoGame +
                    ", ficha=[" + fichaFirst + "," + fichaSecond + "], posicion=" + posicion + "\n");

            // --- Llamar directamente a GetGameByCodigoHandler ---
            GetGameByCodigoHandler getHandler = new GetGameByCodigoHandler();
            APIGatewayProxyRequestEvent getRequest = new APIGatewayProxyRequestEvent();
            getRequest.setHttpMethod("GET");
            getRequest.setHeaders(Map.of("X-codigoGame", codigoGame));

            APIGatewayProxyResponseEvent getResponse = getHandler.handleRequest(getRequest, context);

            if (getResponse.getStatusCode() != 200) {
                return error("No se pudo obtener la partida: " + getResponse.getBody());
            }

            JSONObject fullItem = new JSONObject(getResponse.getBody());
            String idGame = fullItem.getString("idGame");
            JSONObject gameJson = fullItem.getJSONObject("json");

            context.getLogger().log("Partida encontrada: idGame=" + idGame + "\n");

            // --- Actualizar tablero ---
            JSONArray tablero = gameJson.getJSONArray("tablero");
            JSONArray fichasSalidas = gameJson.has("fichasSalidas")
                    ? gameJson.getJSONArray("fichasSalidas") : new JSONArray();
            int turnoActual = gameJson.getInt("turno");
            int puntosA = gameJson.optInt("puntosA", 0);
            int puntosB = gameJson.optInt("puntosB", 0);

            // --- Determinar fichas del jugador actual ---
            String keyJugador;
            if (turnoActual == 1) keyJugador = "fichasJ1";
            else if (turnoActual == 2) keyJugador = "fichasBot1";
            else if (turnoActual == 3) keyJugador = "fichasJ2";
            else keyJugador = "fichasBot2";

            JSONArray fichasJugador = gameJson.getJSONArray(keyJugador);

            // --- Sustituir ficha en tablero con orden correcto ---
            JSONArray fichaAntigua = tablero.getJSONArray(posicion);

            int fichaAntiguaFirst = fichaAntigua.getInt(0);

            int newFirst, newSecond;

            // Comprobar cuál de los números de fichaActual coincide con fichaAntigua.first
            if (fichaFirst == fichaAntiguaFirst) {
                newSecond = fichaFirst;
                newFirst = fichaSecond;
            } else if (fichaSecond == fichaAntiguaFirst) {
                newSecond = fichaSecond;
                newFirst = fichaFirst;
            } else {
                // Si no coincide ninguno, dejamos fichaActual tal cual
                newFirst = fichaFirst;
                newSecond = fichaSecond;
            }

            JSONArray nuevaFichaTablero = new JSONArray();
            nuevaFichaTablero.put(newFirst);
            nuevaFichaTablero.put(newSecond);

            tablero.put(posicion, nuevaFichaTablero);

            // --- Añadir ficha a fichasSalidas ---
            JSONArray nuevaFichaSalida = new JSONArray();
            nuevaFichaSalida.put(fichaFirst);
            nuevaFichaSalida.put(fichaSecond);
            fichasSalidas.put(nuevaFichaSalida);

            // --- Quitar ficha del jugador ---
            for (int i = 0; i < fichasJugador.length(); i++) {
                JSONArray f = fichasJugador.getJSONArray(i);
                if ((f.getInt(0) == fichaFirst && f.getInt(1) == fichaSecond) ||
                        (f.getInt(0) == fichaSecond && f.getInt(1) == fichaFirst)) {
                    fichasJugador.remove(i);
                    break;
                }
            }

            // --- Recalcular puntos ---
            int sumaExtremos = 0;
            for (int i = 0; i < tablero.length(); i++) {
                JSONArray f = tablero.getJSONArray(i);
                if (f.length() >= 2 && f.getInt(0) >= 0) sumaExtremos += f.getInt(0);
            }
            if (sumaExtremos > 0 && sumaExtremos % 5 == 0) {
                if (turnoActual == 1 || turnoActual == 3) puntosA += sumaExtremos;
                else puntosB += sumaExtremos;
            }

            // --- Avanzar turno ---
            int nuevoTurno = turnoActual + 1;
            if (nuevoTurno > 4) nuevoTurno = 1;

            // --- Actualizar JSON ---
            gameJson.put("tablero", tablero);
            gameJson.put("fichasSalidas", fichasSalidas);
            gameJson.put(keyJugador, fichasJugador);
            gameJson.put("turno", nuevoTurno);
            gameJson.put("puntosA", puntosA);
            gameJson.put("puntosB", puntosB);

            // --- Guardar en DynamoDB ---
            Table table = dynamoDB.getTable(gameTable);
            Item existingItem = table.getItem("idGame", idGame);
            if (existingItem == null) return error("Item no encontrado en DynamoDB con idGame=" + idGame);

            existingItem.withJSON("json", gameJson.toString());
            table.putItem(existingItem);
            context.getLogger().log("Partida guardada en DynamoDB con idGame=" + idGame + "\n");

            // --- Devolver JSON actualizado ---
            JSONObject responseBody = new JSONObject();
            responseBody.put("json", gameJson);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(corsHeaders())
                    .withBody(responseBody.toString());

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage() + "\n");
            return error("Error interno: " + e.getMessage());
        }
    }

    private Map<String, String> corsHeaders() {
        return Map.of(
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Headers", "*"
        );
    }

    private APIGatewayProxyResponseEvent error(String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withHeaders(corsHeaders())
                .withBody("{\"error\":\"" + msg + "\"}");
    }
}
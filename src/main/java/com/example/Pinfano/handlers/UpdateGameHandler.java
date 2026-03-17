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
            JSONObject body = new JSONObject(event.getBody());
            String codigoGame = body.getString("codigoGame");
            JSONArray fichaArr = body.getJSONArray("ficha");
            int fichaFirst = fichaArr.getInt(0);
            int fichaSecond = fichaArr.getInt(1);
            int posicion = body.getInt("posicion");

            Table table = dynamoDB.getTable(gameTable);

            // --- Obtener partida actual ---
            Item gameItem = table.scan("codigoGame = :codigo", null, Map.of(":codigo", codigoGame))
                    .iterator().next();

            JSONObject gameJson = new JSONObject(gameItem.getJSON("json"));

            // --- Tablero y fichas dentro del json ---
            JSONArray tablero = gameJson.getJSONArray("tablero");
            JSONArray fichasSalidas = gameJson.has("fichasSalidas") ? gameJson.getJSONArray("fichasSalidas") : new JSONArray();
            JSONArray fichasJ1 = gameJson.getJSONArray("fichasJ1");    // jugador 1
            JSONArray fichasJ2 = gameJson.getJSONArray("fichasBot1");  // jugador 2
            JSONArray fichasJ3 = gameJson.getJSONArray("fichasJ2");    // jugador 3
            JSONArray fichasJ4 = gameJson.getJSONArray("fichasBot2");  // jugador 4
            int turno = gameJson.getInt("turno");
            int puntosA = gameJson.optInt("puntosA", 0);
            int puntosB = gameJson.optInt("puntosB", 0);

            // --- Sustituir ficha en tablero ---
            JSONObject fichaActual = tablero.getJSONObject(posicion);
            int coincidente = fichaActual.getInt("first") == fichaFirst || fichaActual.getInt("second") == fichaFirst ? fichaFirst : fichaSecond;
            int otro = (coincidente == fichaFirst) ? fichaSecond : fichaFirst;

            JSONObject nuevaFicha = new JSONObject();
            nuevaFicha.put("first", otro);  // nuevo valor izquierda
            nuevaFicha.put("second", coincidente);  // coincidente a derecha
            tablero.put(posicion, nuevaFicha);

            // --- Añadir ficha a fichasSalidas ---
            JSONArray nuevaFichaArr = new JSONArray();
            nuevaFichaArr.put(otro);
            nuevaFichaArr.put(coincidente);
            fichasSalidas.put(nuevaFichaArr);
            gameJson.put("fichasSalidas", fichasSalidas);

            // --- Quitar ficha del jugador correspondiente ---
            JSONArray fichasJugador;
            if (turno == 1) fichasJugador = fichasJ1;
            else if (turno == 2) fichasJugador = fichasJ2;
            else if (turno == 3) fichasJugador = fichasJ3;
            else fichasJugador = fichasJ4;

            for (int i = 0; i < fichasJugador.length(); i++) {
                JSONArray f = fichasJugador.getJSONArray(i);
                if (f.getInt(0) == fichaFirst && f.getInt(1) == fichaSecond) {
                    fichasJugador.remove(i);
                    break;
                }
            }

            // --- Recalcular puntos ---
            int sumaIzq = 0;
            for (int i = 0; i < tablero.length(); i++) {
                JSONObject f = tablero.getJSONObject(i);
                if (!f.isNull("first")) sumaIzq += f.getInt("first");
            }
            if (sumaIzq % 5 == 0) {
                if (turno == 1 || turno == 3) puntosA += sumaIzq;
                else puntosB += sumaIzq;
            }

            // --- Avanzar turno ---
            turno++;
            if (turno > 4) turno = 1;

            // --- Guardar todos los cambios en el campo json ---
            gameJson.put("tablero", tablero);
            gameJson.put("fichasJ1", fichasJ1);
            gameJson.put("fichasBot1", fichasJ2);
            gameJson.put("fichasJ2", fichasJ3);
            gameJson.put("fichasBot2", fichasJ4);
            gameJson.put("turno", turno);
            gameJson.put("puntosA", puntosA);
            gameJson.put("puntosB", puntosB);

            gameItem.withJSON("json", gameJson.toString());
            table.putItem(gameItem);

            return response("JSON del juego actualizado correctamente");

        } catch (Exception e) {
            return error("Error interno: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent error(String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("{\"error\":\"" + msg + "\"}");
    }

    private APIGatewayProxyResponseEvent response(String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("{\"message\":\"" + msg + "\"}");
    }
}
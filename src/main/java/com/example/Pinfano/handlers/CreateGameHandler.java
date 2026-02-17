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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CreateGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Methods", "POST, OPTIONS",
                            "Access-Control-Allow-Headers", "*"
                    ));
        }

        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String idGame = body.get("idGame").asText();
            String codigoGame = body.get("codigoGame").asText();
            String json = body.get("json").asText();
            String estado = body.get("estado").asText();
            boolean terminado = body.get("terminado").asBoolean();
            int turno = body.has("turno") ? body.get("turno").asInt() : 0;
            int puntosA = body.has("puntosA") ? body.get("puntosA").asInt() : 0;
            int puntosB = body.has("puntosB") ? body.get("puntosB").asInt() : 0;
            int mano = body.has("mano") ? body.get("mano").asInt() : 0;
            int puntos = body.has("puntos") ? body.get("puntos").asInt() : 0;

            JsonNode listaPlayersNode = body.get("listaPlayers");
            List<String> listaPlayers = new ArrayList<>();

            if (listaPlayersNode != null && listaPlayersNode.isArray()) {
                for (JsonNode jugador : listaPlayersNode) {
                    if (jugador.isNull()) {
                        listaPlayers.add("VACIO");
                    } else {
                        listaPlayers.add(jugador.asText());
                    }
                }
            }

            JsonNode pinfanoNode = body.get("pinfano");
            List<Integer> pinfano = new ArrayList<>();
            if (pinfanoNode != null && pinfanoNode.isArray()) {
                pinfano.add(pinfanoNode.get(0).asInt());
                pinfano.add(pinfanoNode.get(1).asInt());
            }

            JsonNode pasoNode = body.get("paso");
            List<String> paso = new ArrayList<>();
            if (pasoNode != null && pasoNode.isArray()) {
                for (JsonNode p : pasoNode) {
                    if (p.isNull()) paso.add(null);
                    else paso.add(p.asText());
                }
            }

            JsonNode tableroNode = body.get("tablero");
            List<List<Integer>> tablero = new ArrayList<>();

            if (tableroNode != null && tableroNode.isArray()) {
                for (JsonNode ficha : tableroNode) {
                    List<Integer> duo = new ArrayList<>();
                    duo.add(ficha.get(0).asInt());
                    duo.add(ficha.get(1).asInt());
                    tablero.add(duo);
                }
            }

            JsonNode listaFichasNode = body.get("listaFichas");
            List<List<List<Integer>>> listaFichas = new ArrayList<>();
            if (listaFichasNode != null && listaFichasNode.isArray()) {
                for (JsonNode jugadorNode : listaFichasNode) {
                    List<List<Integer>> fichasJugador = new ArrayList<>();

                    for (JsonNode ficha : jugadorNode) {
                        List<Integer> duo = new ArrayList<>();
                        duo.add(ficha.get(0).asInt());
                        duo.add(ficha.get(1).asInt());
                        fichasJugador.add(duo);
                    }
                    listaFichas.add(fichasJugador);
                }
            }

            JsonNode fichasSalidasNode = body.get("fichasSalidas");
            List<List<Integer>> fichasSalidas = new ArrayList<>();

            if (fichasSalidasNode != null && fichasSalidasNode.isArray()) {
                for (JsonNode ficha : fichasSalidasNode) {
                    List<Integer> duo = new ArrayList<>();
                    duo.add(ficha.get(0).asInt());
                    duo.add(ficha.get(1).asInt());
                    fichasSalidas.add(duo);
                }
            }


            Table table = dynamoDB.getTable(gamesTable);

            Item item = new Item()
                    .withPrimaryKey("idGame", idGame)
                    .withString("codigoGame", codigoGame)
                    .withString("json", json)
                    .withString("estado", estado)
                    .withBoolean("terminado", terminado)
                    .withInt("turno", turno)
                    .withInt("puntosA", puntosA)
                    .withInt("puntosB", puntosB)
                    .withInt("mano", mano)
                    .withInt("puntos", puntos)
                    .withList("listaPlayers", listaPlayers)
                    .withList("pinfano", pinfano)
                    .withList("listaFichas", listaFichas)
                    .withList("tablero", tablero)
                    .withList("fichasSalidas", fichasSalidas)
                    .withList("paso", paso);


            table.putItem(item);

            return createResponse(200, "Partida creada correctamente.");

        } catch (Exception e) {
            context.getLogger().log("Error creando partida: " + e.getMessage());
            return createResponse(500, "Error al crear partida.");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,POST"))
                .withBody("{\"message\":\"" + body + "\"}");
    }
}
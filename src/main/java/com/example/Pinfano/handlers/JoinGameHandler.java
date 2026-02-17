package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            context.getLogger().log("=== JOIN GAME HANDLER INICIADO ===\n");

            // Leer BODY desde el cliente
            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();
            String username = body.get("username").asText();

            context.getLogger().log("Body recibido: " + request.getBody() + "\n");
            context.getLogger().log("Código recibido: " + codigo + "\n");
            context.getLogger().log("Username recibido: " + username + "\n");

            Table table = dynamoDB.getTable(gamesTable);

            // Buscar partida por código (Scan)
            context.getLogger().log("Ejecutando scan por codigoGame...\n");
            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigo));

            ItemCollection<ScanOutcome> results = table.scan(scan);
            Iterator<Item> iterator = results.iterator();

            if (!iterator.hasNext()) {
                context.getLogger().log("No se encontró partida con ese código\n");
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = iterator.next();
            String idGame = game.getString("idGame");
            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");

            context.getLogger().log("Lista de jugadores antes de insertar: " + objectMapper.writeValueAsString(players) + "\n");
            context.getLogger().log("Partida encontrada. idGame: " + idGame + ", estado: " + estado + "\n");

            // Insertar jugador en el primer hueco NULL o null usando while
            int i = 0;
            boolean insertado = false;
            while (i < players.size() && !insertado) {
                Object p = players.get(i);
                if (p == null) {
                    Map<String, Object> nuevoJugador = new HashMap<>();
                    nuevoJugador.put("S", username);
                    players.set(i, nuevoJugador);
                    context.getLogger().log("Jugador insertado en el hueco (null) en posición: " + i + "\n");
                    insertado = true;
                } else if (p instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("NULL"))) {
                    Map<String, Object> nuevoJugador = new HashMap<>();
                    nuevoJugador.put("S", username);
                    players.set(i, nuevoJugador);
                    context.getLogger().log("Jugador insertado en el hueco (NULL) en posición: " + i + "\n");
                    insertado = true;
                }
                i++;
            }

            if (!insertado) {
                context.getLogger().log("No se encontró ningún hueco para insertar al jugador, revisar listaPlayers.\n");
                return createResponse(200, "{\"joined\":false, \"reason\":\"SIN_HUECO\"}");
            }

            // Comprobar si quedan NULL o null para activar partida
            boolean quedanHuecos = false;
            for (Object p : players) {
                if (p == null || (p instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("NULL")))) {
                    quedanHuecos = true;
                    break;
                }
            }

            if (!quedanHuecos) {
                estado = "A"; // Todos los jugadores están → activar partida
                context.getLogger().log("Todos los jugadores están. Cambiando estado a: " + estado + "\n");
            }

            // Guardar cambios en DynamoDB
            table.updateItem(
                    "idGame", idGame,
                    "SET listaPlayers = :p, estado = :e",
                    new ValueMap()
                            .withList(":p", players)
                            .withString(":e", estado)
            );

            context.getLogger().log("Jugador insertado correctamente. Lista de jugadores después de insertar: " + objectMapper.writeValueAsString(players) + "\n");

            // Respuesta OK
            return createResponse(200,
                    "{ \"joined\": true, \"estadoFinal\":\"" + estado + "\" }");

        } catch (Exception e) {
            String msg = "ERROR_INTERNO: " + e.getMessage();
            context.getLogger().log(msg + "\n");
            return createResponse(200, "{\"joined\":false, \"reason\":\"" + msg + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int status, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "*"
                ))
                .withBody(body);
    }
}


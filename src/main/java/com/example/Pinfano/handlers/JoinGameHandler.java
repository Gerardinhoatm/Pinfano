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

            // ============================
            // Leer BODY desde el cliente
            // ============================
            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();
            String username = body.get("username").asText();

            context.getLogger().log("Body recibido: " + request.getBody() + "\n");
            context.getLogger().log("Código recibido: " + codigo + "\n");
            context.getLogger().log("Username recibido: " + username + "\n");

            Table table = dynamoDB.getTable(gamesTable);

            // ============================
            // Buscar partida por código (Scan)
            // ============================
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
            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");

            context.getLogger().log("Lista de jugadores antes de insertar: " + objectMapper.writeValueAsString(players) + "\n");
            context.getLogger().log("Partida encontrada. codigoGame: " + codigo + ", estado: " + estado + "\n");

            // Buscar primer "VACIO"
            int i = 0;
            boolean insertado = false;
            while (i < players.size() && !insertado) {
                Object p = players.get(i);
                if (p instanceof String && p.equals("VACIO")) {
                    players.set(i, username);  // Insertamos directamente el username
                    insertado = true;
                }
                i++;
            }

            if (!insertado) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"SIN_HUECO\"}");
            }

            // Revisar si quedan huecos para cambiar estado
            boolean quedanHuecos = players.stream().anyMatch(p -> p instanceof String && p.equals("VACIO"));
            if (!quedanHuecos) {
                estado = "A"; // Todos los jugadores están → activar partida
            }

            // ============================
            // Guardar cambios en DynamoDB
            // ============================
            // Actualizamos directamente el Item obtenido del scan
            game.withList("listaPlayers", players)
                    .withString("estado", estado);

            // Guardamos el Item completo de nuevo
            table.putItem(game);

            context.getLogger().log("Jugador insertado correctamente. Lista de jugadores ahora: "
                    + objectMapper.writeValueAsString(players) + "\n");

            // ============================
            // Respuesta OK
            // ============================
            return createResponse(200,
                    "{ \"joined\": true, \"estadoFinal\":\"" + estado + "\" }");

        } catch (Exception e) {
            String msg = "ERROR_INTERNO: " + e.getMessage();
            System.out.println(msg);
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
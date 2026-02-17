package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
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

        context.getLogger().log("=== JOIN GAME HANDLER INICIADO ===\n");

        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            context.getLogger().log("Request OPTIONS recibido.\n");
            return createResponse(200, "{\"message\":\"OK\"}");
        }

        try {
            // ============================
            // Leer BODY
            // ============================
            context.getLogger().log("Body recibido: " + request.getBody() + "\n");

            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();
            String username = body.get("username").asText();

            context.getLogger().log("Código recibido: " + codigo + "\n");
            context.getLogger().log("Username recibido: " + username + "\n");

            Table table = dynamoDB.getTable(gamesTable);

            // ============================
            // Buscar partida por código
            // ============================
            context.getLogger().log("Ejecutando query por codigoGame...\n");

            QuerySpec query = new QuerySpec()
                    .withKeyConditionExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigo));

            ItemCollection<QueryOutcome> results = table.query(query);

            Iterator<Item> iterator = results.iterator();
            if (!iterator.hasNext()) {
                context.getLogger().log("No existe partida con ese codigo.\n");
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = iterator.next();
            context.getLogger().log("Partida encontrada: " + game.toJSONPretty() + "\n");

            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");
            String idGame = game.getString("idGame");

            context.getLogger().log("Estado actual partida: " + estado + "\n");
            context.getLogger().log("ID Game: " + idGame + "\n");
            context.getLogger().log("Lista players: " + players + "\n");

            // ============================
            // Validar estado
            // ============================
            if (!"P".equalsIgnoreCase(estado)) {
                context.getLogger().log("La partida NO permite unirse (estado != P)\n");
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_PERMITE_UNIRSE\"}");
            }

            // ============================
            // Buscar hueco NULL
            // ============================
            int indexLibre = -1;

            for (int i = 0; i < players.size(); i++) {
                Object p = players.get(i);
                context.getLogger().log("Revisando slot " + i + ": " + p + "\n");

                if (p instanceof Map<?, ?> map) {
                    if (map.containsKey("NULL") && Boolean.TRUE.equals(map.get("NULL"))) {
                        context.getLogger().log("Hueco libre encontrado en posicion: " + i + "\n");
                        indexLibre = i;
                        break;
                    }
                }
            }

            if (indexLibre == -1) {
                context.getLogger().log("Partida LLENA.\n");
                return createResponse(200, "{\"joined\":false, \"reason\":\"LLENO\"}");
            }

            // Insertar jugador
            Map<String, Object> nuevoJugador = new HashMap<>();
            nuevoJugador.put("S", username);

            players.set(indexLibre, nuevoJugador);

            context.getLogger().log("Jugador insertado en posición " + indexLibre + "\n");
            context.getLogger().log("Lista actualizada: " + players + "\n");

            // ============================
            // Comprobar si aún quedan NULL
            // ============================
            boolean quedanHuecos = players.stream().anyMatch(p ->
                    p instanceof Map<?, ?> map &&
                            map.containsKey("NULL") &&
                            Boolean.TRUE.equals(map.get("NULL"))
            );

            context.getLogger().log("Quedan huecos? " + quedanHuecos + "\n");

            if (!quedanHuecos) {
                estado = "A";
                context.getLogger().log("La partida está completa. Nuevo estado: A\n");
            }

            // ============================
            // Guardar
            // ============================
            context.getLogger().log("Actualizando partida en DynamoDB...\n");

            table.updateItem(
                    "idGame", idGame,
                    "SET listaPlayers = :p, estado = :e",
                    new ValueMap()
                            .withList(":p", players)
                            .withString(":e", estado)
            );

            context.getLogger().log("Partida actualizada correctamente.\n");

            return createResponse(200, "{ \"joined\": true, \"estadoFinal\":\"" + estado + "\" }");

        } catch (Exception e) {

            context.getLogger().log("ERROR INTERNO: " + e.getMessage() + "\n");
            context.getLogger().log("STACKTRACE:\n");

            for (StackTraceElement st : e.getStackTrace()) {
                context.getLogger().log(st.toString() + "\n");
            }

            return createResponse(200,
                    "{\"joined\":false, \"reason\":\"ERROR_INTERNO: " + e.getMessage() + "\"}");
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

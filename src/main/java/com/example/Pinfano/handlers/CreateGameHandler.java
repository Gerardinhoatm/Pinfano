package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String TABLE_NAME = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        context.getLogger().log(">>> [CreateGame] INICIO\n");

        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            context.getLogger().log(">>> [CreateGame] Body recibido: " + body + "\n");

            String username = body.get("username").asText();
            String letra = body.get("letra").asText();
            int numJugadores = body.get("numJugadores").asInt();
            int puntos = body.get("puntos").asInt();

            List<String> jugadores = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                String key = "jugador" + i;
                if (body.has(key)) {
                    jugadores.add(body.get(key).asText());
                } else {
                    jugadores.add("VACIO");
                }
            }

            context.getLogger().log(">>> [CreateGame] username=" + username + ", letra=" + letra +
                    ", numJugadores=" + numJugadores + ", puntos=" + puntos + ", jugadores=" + jugadores + "\n");

            // ----------------------
            // Generar idGame y codigoGame
            // ----------------------
            String idGame = UUID.randomUUID().toString();
            String codigoGame = generarCodigoPartida();

            context.getLogger().log(">>> [CreateGame] idGame=" + idGame + ", codigoGame=" + codigoGame + "\n");

            // ----------------------
            // Crear jsonGame
            // ----------------------
            ObjectNode jsonGame;

            if (letra.equalsIgnoreCase("d")) {
                context.getLogger().log(">>> [CreateGame] Letra D → leyendo fichas de ReadTilesHandler\n");

                // Simular petición a ReadTilesHandler
                APIGatewayV2HTTPEvent readEvent = new APIGatewayV2HTTPEvent();
                readEvent.setBody("{}");
                ReadTilesHandler readHandler = new ReadTilesHandler();
                APIGatewayV2HTTPResponse readResponse = readHandler.handleRequest(readEvent, context);

                String jsonStr = readResponse.getBody();
                jsonGame = (ObjectNode) objectMapper.readTree(jsonStr);

                context.getLogger().log(">>> [CreateGame] JSON leído: " + jsonGame + "\n");

                // Añadir los campos obligatorios
                jsonGame.put("numJugadores", numJugadores);
                jsonGame.put("codigoGame", codigoGame);
                for (int i = 0; i < jugadores.size(); i++) {
                    String key = "jugador" + (i + 1);
                    jsonGame.put(key, jugadores.get(i));
                }
                jsonGame.put("puntos", puntos);
                jsonGame.put("username", username);
                jsonGame.put("estado", numJugadores == 1 ? "A" : "P");
                jsonGame.put("terminado", false);
                List<String> pasosIniciales = Arrays.asList("1-3", "VACIO", "VACIO", "VACIO");
                jsonGame.putPOJO("paso", pasosIniciales);
                jsonGame.putPOJO("listaPlayers", jugadores);

            } else if(letra.equalsIgnoreCase("g")){
                context.getLogger().log(">>> [CreateGame] Letra G → generando partida REAL aleatoria\n");

                jsonGame = objectMapper.createObjectNode();

                // ======================================
                // 1. GENERAR TODAS LAS FICHAS [0..6]
                // ======================================
                List<int[]> fichas = new ArrayList<>();
                for (int i = 0; i <= 6; i++) {
                    for (int j = i; j <= 6; j++) {
                        fichas.add(new int[]{i, j});
                    }
                }
                Collections.shuffle(fichas);

                // ======================================
                // 2. REPARTO ALEATORIO — 4 JUGADORES × 7 FICHAS
                // ======================================
                List<int[]> fichasJ1   = new ArrayList<>();
                List<int[]> fichasBot1 = new ArrayList<>();
                List<int[]> fichasJ2   = new ArrayList<>();
                List<int[]> fichasBot2 = new ArrayList<>();

                Map<String, List<int[]>> manos = Map.of(
                        "fichasJ1",   fichasJ1,
                        "fichasBot1", fichasBot1,
                        "fichasJ2",   fichasJ2,
                        "fichasBot2", fichasBot2
                );

                int index = 0;
                for (List<int[]> lista : manos.values()) {
                    for (int k = 0; k < 7; k++) {
                        lista.add(fichas.get(index++));
                    }
                }

                // ==================================================
                // 3. CALCULAR TURNO: jugador que tenga {6,6}
                // ==================================================
                int turno = 0;
                if (contains66(fichasJ1)) turno = 1;
                else if (contains66(fichasBot1)) turno = 2;
                else if (contains66(fichasJ2)) turno = 3;
                else if (contains66(fichasBot2)) turno = 4;

                // mano = jugador que empieza = turno
                jsonGame.put("mano", turno);
                jsonGame.put("turno", turno);

                // ==================================================
                // 4. GUARDAR LAS FICHAS EN FORMATO DYNAMODB
                // ==================================================
                jsonGame.putPOJO("fichasJ1", fichasJ1);
                jsonGame.putPOJO("fichasBot1", fichasBot1);
                jsonGame.putPOJO("fichasJ2", fichasJ2);
                jsonGame.putPOJO("fichasBot2", fichasBot2);

                // ==================================================
                // 5. CAMPOS OBLIGATORIOS
                // ==================================================
                jsonGame.put("terminado", false);
                jsonGame.put("estado", numJugadores == 1 ? "A" : "P");
                jsonGame.put("puntos", puntos);
                jsonGame.put("puntosA", 0);
                jsonGame.put("puntosB", 0);
                jsonGame.put("codigoGame", codigoGame);
                jsonGame.put("username", username);

                // VACIOS
                jsonGame.put("pinfano", "VACIO");
                jsonGame.put("tablero", "VACIO");
                jsonGame.put("fichasSalidas", "VACIO");
                List<String> pasosIniciales = Arrays.asList("VACIO", "VACIO", "VACIO", "VACIO");
                jsonGame.putPOJO("paso", pasosIniciales);

                // ==================================================
                // 6. JUGADORES Y LISTA
                // ==================================================
                for (int i = 0; i < jugadores.size(); i++) {
                    jsonGame.put("jugador" + (i + 1), jugadores.get(i));
                }
                jsonGame.put("listaPlayers", jugadores.toString());
            } else {
                context.getLogger().log(">>> [CreateGame] Letra inválida: " + letra);
                return createResponse(400, "Letra inválida: " + letra);
            }

            context.getLogger().log(">>> [CreateGame] JSON final antes de guardar: " + jsonGame + "\n");

            // ----------------------
            // Guardar en DynamoDB
            // ----------------------
            Table table = dynamoDB.getTable(TABLE_NAME);
            Item item = new Item()
                    .withPrimaryKey("idGame", idGame)
                    .withString("codigoGame", codigoGame)
                    .withMap("json", objectMapper.convertValue(jsonGame, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}))                    .withString("estado", jsonGame.get("estado").asText())
                    .withNumber("numJugadores", numJugadores)
                    .withNumber("puntos", puntos)
                    .withBoolean("terminado", false)
                    .withList("listaPlayers", jugadores);

            context.getLogger().log(">>> [CreateGame] Guardando partida en DynamoDB...\n");
            table.putItem(new PutItemSpec().withItem(item));
            context.getLogger().log(">>> [CreateGame] Guardado correcto\n");

            // ----------------------
            // Llamar AddGameToUserHandler
            // ----------------------
            context.getLogger().log(">>> [CreateGame] Llamando a AddGameToUserHandler...\n");

            ObjectNode addGameBody = objectMapper.createObjectNode();
            addGameBody.put("username", username);
            addGameBody.put("codigoGame", codigoGame);

            APIGatewayProxyRequestEvent addGameRequest = new APIGatewayProxyRequestEvent();
            addGameRequest.setHttpMethod("POST");
            addGameRequest.setBody(addGameBody.toString());

            AddGameToUserHandler addGameHandler = new AddGameToUserHandler();
            addGameHandler.handleRequest(addGameRequest, context);

            context.getLogger().log(">>> [CreateGame] AddGameToUserHandler ejecutado correctamente\n");

            // ----------------------
            // Respuesta final
            // ----------------------
            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("idGame", idGame);
            responseNode.put("codigoGame", codigoGame);
            responseNode.set("json", jsonGame);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Headers", "*",
                            "Access-Control-Allow-Methods", "OPTIONS,POST"))
                    .withBody(responseNode.toString());

        } catch (Exception e) {
            context.getLogger().log(">>> [CreateGame] ERROR: " + e + "\n");
            return createResponse(500, "Error interno: " + e.getMessage());
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

    private String generarCodigoPartida() {
        String letras = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String numeros = "0123456789";
        Random random = new Random();
        List<Character> codigo = new ArrayList<>();
        for (int i = 0; i < 4; i++) codigo.add(letras.charAt(random.nextInt(letras.length())));
        for (int i = 0; i < 4; i++) codigo.add(numeros.charAt(random.nextInt(numeros.length())));
        Collections.shuffle(codigo);
        StringBuilder sb = new StringBuilder();
        for (char c : codigo) sb.append(c);
        return sb.toString();
    }

    private boolean contains66(List<int[]> mano) {
        for (int[] f : mano) {
            if (f[0] == 6 && f[1] == 6) return true;
        }
        return false;
    }
}
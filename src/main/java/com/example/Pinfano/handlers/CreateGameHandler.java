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
                jsonGame.put("paso", objectMapper.valueToTree(pasosIniciales));
                jsonGame.put("listaPlayers", objectMapper.valueToTree(jugadores));


            } else if (letra.equalsIgnoreCase("g")) {
                context.getLogger().log(">>> [CreateGame] Letra G → generando json a boleo\n");

                jsonGame = objectMapper.createObjectNode();

                // Todas las fichas
                Map<PairInt, String> todasFichas = todasFichas();
                List<String> listaFichas = new ArrayList<>(todasFichas.values());
                Collections.shuffle(listaFichas);

                Map<String, List<String>> manoJugadores = new HashMap<>();
                int turno = 1;
                String jugadorCon66 = username;

                for (int i = 0; i < jugadores.size(); i++) {
                    String j = jugadores.get(i);
                    if (j.equals("VACIO")) {
                        manoJugadores.put("jugador" + (i + 1), new ArrayList<>());
                        continue;
                    }
                    List<String> mano = new ArrayList<>();
                    for (int k = 0; k < 7 && !listaFichas.isEmpty(); k++) {
                        String ficha = listaFichas.remove(0);
                        mano.add(ficha);
                        if (ficha.equals("seisseis")) { // {6,6}
                            turno = i + 1;
                            jugadorCon66 = j;
                        }
                    }
                    manoJugadores.put("jugador" + (i + 1), mano);
                }

                jsonGame.put("turno", turno);
                jsonGame.put("puntos", puntos);
                jsonGame.put("puntosA", 0);
                jsonGame.put("puntosB", 0);
                jsonGame.put("pinFano", "VACIO");
                jsonGame.put("terminado", false);
                jsonGame.put("estado", numJugadores == 1 ? "A" : "P");

                // Tablero a VACIO
                jsonGame.put("tablero", "VACIO");

                // Mano por jugador (guardamos la cantidad de fichas)
                for (int i = 0; i < jugadores.size(); i++) {
                    String key = "mano" + (i + 1);
                    jsonGame.put(key, manoJugadores.get("jugador" + (i + 1)).size());
                    jsonGame.put("jugador" + (i + 1), jugadores.get(i));
                }

                // Fichas salidas y paso
                jsonGame.put("fichasSalidas", "VACIO");
                List<String> pasosIniciales = Arrays.asList("3-1", "VACIO", "VACIO", "VACIO");
                jsonGame.put("paso", objectMapper.valueToTree(pasosIniciales));
                jsonGame.put("listaPlayers", objectMapper.valueToTree(jugadores));

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
                    .withMap("json", objectMapper.convertValue(jsonGame, Map.class))
                    .withString("estado", jsonGame.get("estado").asText())
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

    private Map<PairInt, String> todasFichas() {
        Map<PairInt, String> mapaFichas = new HashMap<>();
        String[] nombres = {"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis"};

        for (int i = 0; i <= 6; i++) {
            for (int j = i; j <= 6; j++) {
                String nombre = nombres[i] + nombres[j];
                PairInt clave = new PairInt(i, j);
                mapaFichas.put(clave, nombre);
            }
        }

        return mapaFichas;
    }
    // Clase interna para reemplazar Pair
    public static class PairInt {
        public final int left;
        public final int right;

        public PairInt(int left, int right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PairInt)) return false;
            PairInt pair = (PairInt) o;
            return left == pair.left && right == pair.right;
        }

        @Override
        public int hashCode() {
            return 31 * left + right;
        }

        @Override
        public String toString() {
            return "(" + left + "," + right + ")";
        }
    }
}
package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;

import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApi;
import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApiClientBuilder;
import com.amazonaws.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.List;

public class SendPlayerJoinedHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);

    private final String CONNECTIONS_TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");
    private final String GAMES_TABLE = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        String body = event.getBody();
        String codigoGame = "";

        try {
            JSONObject json = new JSONObject(body);
            codigoGame = json.getString("codigoGame");
        } catch (Exception e) {
            context.getLogger().log("❌ Error parsing JSON: " + e.getMessage());
            return null;
        }

        Table gamesTable = dynamo.getTable(GAMES_TABLE);
        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);

        try {
            // 1. BUSCAR LA PARTIDA EN PinfanoGames POR codigoGame
            Item gameItem = null;
            ItemCollection<ScanOutcome> items = gamesTable.scan(new ScanSpec()
                    .withFilterExpression("codigoGame = :cg")
                    .withValueMap(new ValueMap().withString(":cg", codigoGame)));

            for (Item item : items) {
                gameItem = item;
                break;
            }

            if (gameItem == null) {
                context.getLogger().log("⚠️ Partida no encontrada: " + codigoGame);
                return null;
            }

            // 2. LOGICA DE CONTEO: Jugadores que no son VACIO ni BOT
            List<Object> listaPlayersRaw = gameItem.getList("listaPlayers");
            int unidos = 0;
            for (Object p : listaPlayersRaw) {
                String pStr = p.toString();
                if (pStr.contains("S=")) pStr = ((java.util.Map<?,?>)p).get("S").toString();

                if (!pStr.equalsIgnoreCase("VACIO") && !pStr.toUpperCase().startsWith("BOT")) {
                    unidos++;
                }
            }

            int maxPlayers = gameItem.getInt("numJugadores");
            String estado = gameItem.getString("estado"); // "P" (Pendiente) o "A" (Activo/Lleno)

            // 3. PREPARAR EL MENSAJE JSON
            JSONObject msgJson = new JSONObject();

            if ("A".equals(estado)) {
                // PARTIDA LLENA: Mandamos a jugar
                msgJson.put("type", "startGame");

                // Extraer el turno desde el campo 'json' interno
                String jsonAttr = gameItem.getString("json");
                JSONObject innerJson = new JSONObject(jsonAttr);
                msgJson.put("turno", innerJson.optInt("turno", 1));

                // Enviamos la lista para que Android calcule su propio índice (playerIndex)
                JSONArray playersArray = new JSONArray();
                for (Object p : listaPlayersRaw) {
                    String pStr = p.toString();
                    if (pStr.contains("S=")) pStr = ((java.util.Map<?,?>)p).get("S").toString();
                    playersArray.put(pStr);
                }
                msgJson.put("listaPlayers", playersArray);

            } else {
                // PARTIDA PENDIENTE: Solo actualizar contador
                msgJson.put("type", "playerJoined");
                msgJson.put("connectedPlayers", unidos);
                msgJson.put("maxPlayers", maxPlayers);
            }

            // 4. ENVIAR A TODAS LAS CONEXIONES SUSCRITAS A ESTE CÓDIGO
            AmazonApiGatewayManagementApi apiGatewayClient = AmazonApiGatewayManagementApiClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                    "https://" + event.getRequestContext().getDomainName() + "/" + event.getRequestContext().getStage(),
                                    "eu-central-1"
                            )
                    ).build();

            ItemCollection<ScanOutcome> subscribers = connectionsTable.scan(new ScanSpec()
                    .withFilterExpression("codigoGame = :cg")
                    .withValueMap(new ValueMap().withString(":cg", codigoGame)));

            for (Item conn : subscribers) {
                String targetId = conn.getString("connectionId");
                try {
                    apiGatewayClient.postToConnection(new PostToConnectionRequest()
                            .withConnectionId(targetId)
                            .withData(ByteBuffer.wrap(msgJson.toString().getBytes())));
                } catch (Exception e) {
                    context.getLogger().log("❌ Error enviando a " + targetId + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            context.getLogger().log("❌ Error crítico en handler: " + e.getMessage());
        }

        return null;
    }
}
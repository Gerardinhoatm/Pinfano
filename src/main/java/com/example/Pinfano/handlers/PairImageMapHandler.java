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

import java.util.HashMap;
import java.util.Map;

public class PairImageMapHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String tableName = System.getenv().getOrDefault("DUPLA_IMAGENES_TABLE", "DuplaImagenes");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return createResponse(200, "OK");
        }

        try {
            Table table = dynamoDB.getTable(tableName);

            if ("POST".equalsIgnoreCase(request.getHttpMethod())) {
                // Crear mapa si no existe
                Item existing = table.getItem("id", "domino_fichas");
                if (existing != null) {
                    return createResponse(200, "El mapa de fichas ya está creado.");
                }

                Map<String, String> mapaFichas = new HashMap<>();
                mapaFichas.put("(0,0)", "cerocero");
                mapaFichas.put("(0,1)", "cerouno");
                mapaFichas.put("(0,2)", "cerodos");
                mapaFichas.put("(0,3)", "cerotres");
                mapaFichas.put("(0,4)", "cerocuatro");
                mapaFichas.put("(0,5)", "cerocinco");
                mapaFichas.put("(0,6)", "ceroseis");
                mapaFichas.put("(1,1)", "unouno");
                mapaFichas.put("(1,2)", "unodos");
                mapaFichas.put("(1,3)", "unotres");
                mapaFichas.put("(1,4)", "unocuatro");
                mapaFichas.put("(1,5)", "unocinco");
                mapaFichas.put("(1,6)", "unoseis");
                mapaFichas.put("(2,2)", "dosdos");
                mapaFichas.put("(2,3)", "dostres");
                mapaFichas.put("(2,4)", "doscuatro");
                mapaFichas.put("(2,5)", "doscinco");
                mapaFichas.put("(2,6)", "dosseis");
                mapaFichas.put("(3,3)", "trestres");
                mapaFichas.put("(3,4)", "trescuatro");
                mapaFichas.put("(3,5)", "trescinco");
                mapaFichas.put("(3,6)", "tresseis");
                mapaFichas.put("(4,4)", "cuatrocuatro");
                mapaFichas.put("(4,5)", "cuatrocinco");
                mapaFichas.put("(4,6)", "cuatroseis");
                mapaFichas.put("(5,5)", "cincocinco");
                mapaFichas.put("(5,6)", "cincoseis");
                mapaFichas.put("(6,6)", "seisseis");

                table.putItem(new Item()
                        .withPrimaryKey("id", "domino_fichas")
                        .withMap("mapa", mapaFichas));

                return createResponse(200, "Mapa creado correctamente.");
            }

            else if ("GET".equalsIgnoreCase(request.getHttpMethod())) {
                Item item = table.getItem("id", "domino_fichas");
                if (item == null) {
                    return createResponse(404, "Mapa no encontrado.");
                }

                Map<String, Object> mapa = item.getMap("mapa");
                String json = new com.google.gson.Gson().toJson(Map.of("mapa", mapa));

                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(Map.of(
                                "Content-Type", "application/json",
                                "Access-Control-Allow-Origin", "*",
                                "Access-Control-Allow-Headers", "*",
                                "Access-Control-Allow-Methods", "OPTIONS,GET,POST"))
                        .withBody(json);
            }

            else {
                return createResponse(405, "Método no permitido");
            }

        } catch (Exception e) {
            context.getLogger().log("❌ Error: " + e.getMessage());
            return createResponse(500, "Error interno del servidor");
        }
    }


    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,POST"))
                .withBody("{\"message\":\"" + message + "\"}");
    }
}
package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class UpdateGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);
    private final String gameTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        var logger = context.getLogger();
        logger.log("[START] UpdateGameHandler invocado");
        try {
            if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
                return response(200, "OK");
            }
            JSONObject body = new JSONObject(event.getBody());
            String codigoGame = body.getString("codigoGame");

            // Obtener partida
            GetGameByCodigoHandler getHandler = new GetGameByCodigoHandler();
            APIGatewayProxyRequestEvent getReq = new APIGatewayProxyRequestEvent();
            getReq.setHeaders(Map.of("X-codigoGame", codigoGame));
            APIGatewayProxyResponseEvent getRes = getHandler.handleRequest(getReq, context);

            if (getRes.getStatusCode() != 200) return error("Partida no encontrada");

            JSONObject fullItem = new JSONObject(getRes.getBody());
            String idGame = fullItem.getString("idGame");

            // Cambia la línea problemática por esto:
            Object jsonRaw = fullItem.get("json");
            JSONObject gameJson;
            if (jsonRaw instanceof String) {
                gameJson = new JSONObject((String) jsonRaw);
            } else {
                gameJson = (JSONObject) jsonRaw;
            }
            int turnoActual = gameJson.getInt("turno");
            int posicion = body.getInt("posicion");
            if (posicion == -2) {
                return ejecutarTurnoBot(idGame, codigoGame, gameJson, turnoActual, context);
            } else {
                if (posicion == -1) {
                    return procesarPasoHumano(idGame, codigoGame, gameJson, turnoActual, context);
                } else {
                    JSONArray ficha = body.getJSONArray("ficha");
                    return procesarJugadaEfectiva(idGame, codigoGame, gameJson, turnoActual, ficha, posicion, context);
                }
            }
        } catch (Exception e) {
            logger.log("[FATAL ERROR]: " + e.getMessage());
            return error(e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent ejecutarTurnoBot(String idGame, String codigoGame, JSONObject gameJson, int turnoActual, Context context) {
        LambdaLogger logger = context.getLogger();
        String keyJugador = obtenerKeyFichas(turnoActual);
        JSONArray fichasBot = gameJson.getJSONArray(keyJugador);

        // 1. Obtener extremos actuales del tablero
        int[] extremos = obtenerExtremosActuales(gameJson);

        // 2. BACKTRACKING / HEURÍSTICA
        JugadaOptima mejor = buscarMejorJugada(fichasBot, extremos, gameJson, turnoActual);

        if (mejor != null) {
            logger.log("[BOT] Decide jugar: " + mejor.ficha + " en posicion " + mejor.posicion);

            // Convertimos JSONArray → JSONArray real como la espera procesarJugadaEfectiva
            JSONArray fichaElegida = new JSONArray()
                    .put(mejor.ficha.getInt(0))
                    .put(mejor.ficha.getInt(1));

            return procesarJugadaEfectiva(
                    idGame,
                    codigoGame,
                    gameJson,
                    turnoActual,
                    fichaElegida,
                    mejor.posicion,
                    context
            );
        }
        logger.log("[BOT] No tiene jugadas, PASA");
        return procesarPasoHumano(idGame, codigoGame, gameJson, turnoActual, context);
    }

    private JugadaOptima buscarMejorJugada(JSONArray fichas, int[] extremos, JSONObject gameJson, int turnoBot) {
        JugadaOptima mejor = null;
        for (int i = 0; i < fichas.length(); i++) {
            JSONArray f = fichas.getJSONArray(i);
            for (int p = 0; p < 4; p++) {
                if (f.getInt(0) == extremos[p] || f.getInt(1) == extremos[p]) {
                    int score = calcularHeuristica(f, p, extremos, gameJson, turnoBot);
                    if (mejor == null || score > mejor.puntuacion) {
                        mejor = new JugadaOptima(f, p, score);
                    }
                }
            }
        }
        return mejor;
    }

    private int calcularHeuristica(JSONArray ficha, int pos, int[] extremos, JSONObject gameJson, int turnoBot) {
        int v1 = ficha.getInt(0);
        int v2 = ficha.getInt(1);
        int nuevoExtremo = (v1 == extremos[pos]) ? v2 : v1;
        int score = 0;

        // Prioridad 1: Fastidiar al rival (si el siguiente rival falló a este número)
        int rivalSig = (turnoBot % 4); // El índice en el array paso es (turno - 1)
        String historialPasoRival = gameJson.getJSONArray("paso").getString(rivalSig);
        if (historialPasoRival.contains(String.valueOf(nuevoExtremo))) score += 1000;

        // Prioridad 2: Ayudar compañero (evitar que el compañero falle)
        int compañeroIdx = (turnoBot + 1) % 4;
        String historialPasoComp = gameJson.getJSONArray("paso").getString(compañeroIdx);
        if (historialPasoComp.contains(String.valueOf(nuevoExtremo))) score -= 500;

        // Prioridad 3: Múltiplos de 5
        int sumaSimulada = nuevoExtremo;
        for (int i = 0; i < 4; i++) {
            if (i != pos) sumaSimulada += extremos[i];
        }
        if (sumaSimulada % 5 == 0) score += sumaSimulada;

        return score;
    }

    private APIGatewayProxyResponseEvent procesarJugadaEfectiva(String idGame, String codigoGame, JSONObject gameJson, int turnoActual, JSONArray ficha, int posicion, Context context) {
        LambdaLogger logger = context.getLogger();
        JSONArray tablero = gameJson.getJSONArray("tablero");
        JSONArray fichasSalidas = gameJson.getJSONArray("fichasSalidas");
        String keyJugador = obtenerKeyFichas(turnoActual);
        JSONArray fichasJugador = gameJson.getJSONArray(keyJugador);

        // Lógica de orientación de la ficha
        JSONArray celdaTablero = tablero.getJSONArray(posicion);
        // Si la celda está vacía, usamos el pinfano como referencia de extremo
        int extremoAnterior = celdaTablero.length() > 0 ? celdaTablero.getInt(0) : gameJson.getJSONArray("pinfano").getInt(0);

        int f1 = ficha.getInt(0);
        int f2 = ficha.getInt(1);
        JSONArray fichaOrientada = (f1 == extremoAnterior) ? new JSONArray().put(f2).put(f1) : new JSONArray().put(f1).put(f2);

        tablero.put(posicion, fichaOrientada);
        fichasSalidas.put(new JSONArray().put(f1).put(f2));

        // Eliminar de la mano
        for (int i = 0; i < fichasJugador.length(); i++) {
            JSONArray f = fichasJugador.getJSONArray(i);
            if ((f.getInt(0) == f1 && f.getInt(1) == f2) || (f.getInt(0) == f2 && f.getInt(1) == f1)) {
                fichasJugador.remove(i);
                break;
            }
        }

        // Puntos
        int suma = 0;
        for (int i = 0; i < tablero.length(); i++) {
            JSONArray f = tablero.getJSONArray(i);
            if (f.length() > 0) suma += f.getInt(0);
        }
        int puntosA = gameJson.getInt("puntosA");
        int puntosB = gameJson.getInt("puntosB");
        if (suma > 0 && suma % 5 == 0) {
            if (turnoActual == 1 || turnoActual == 3) puntosA += suma; else puntosB += suma;
            gameJson.put("puntosA", puntosA);
            gameJson.put("puntosB", puntosB);
        }

        // Checks de fin
        if (puntosA >= gameJson.getInt("puntos") || puntosB >= gameJson.getInt("puntos")) {
            return finalizarPartida(idGame, codigoGame, puntosA, puntosB, gameJson, context);
        }
        if (fichasJugador.isEmpty()) {
            return finalizarRonda(idGame, codigoGame, turnoActual, gameJson, context);
        }

        // Turno normal
        int nuevoTurno = (turnoActual % 4) + 1;
        gameJson.put("turno", nuevoTurno);

        // Guardar y Notificar
        Table table = dynamoDB.getTable(gameTable);
        Item item = table.getItem("idGame", idGame);
        item.withJSON("json", gameJson.toString());
        table.putItem(item);

        JSONObject sendData = new JSONObject().put("type", "gameUpdated").put("codigoGame", codigoGame).put("json", gameJson).put("turno", nuevoTurno);
        new SendJsonHandler().sendUpdateToAll(sendData, context);

        return response(200, gameJson.toString());
    }

    private APIGatewayProxyResponseEvent procesarPasoHumano(String idGame, String codigoGame, JSONObject gameJson, int turnoActual, Context context) {
        JSONArray tableroActual = gameJson.getJSONArray("tablero");
        JSONArray pinfano = gameJson.getJSONArray("pinfano");
        Set<Integer> nuevosExtremos = new LinkedHashSet<>();
        for (int i = 0; i < 4; i++) {
            JSONArray f = tableroActual.getJSONArray(i);
            nuevosExtremos.add(f.length() > 0 ? f.getInt(0) : pinfano.getInt(0));
        }

        JSONArray pasoArr = gameJson.getJSONArray("paso");
        String anteriorPaso = pasoArr.getString(turnoActual - 1);
        Set<Integer> anteriores = new TreeSet<>();
        if (!anteriorPaso.isEmpty() && !anteriorPaso.equals("VACIO")) {
            for (String s : anteriorPaso.split("-")) anteriores.add(Integer.parseInt(s));
        }
        anteriores.addAll(nuevosExtremos);

        StringBuilder sb = new StringBuilder();
        for (Integer n : anteriores) {
            if (sb.length() > 0) sb.append("-");
            sb.append(n);
        }
        pasoArr.put(turnoActual - 1, sb.toString());

        int nuevoTurno = (turnoActual % 4) + 1;
        gameJson.put("turno", nuevoTurno);
        gameJson.put("paso", pasoArr);

        dynamoDB.getTable(gameTable).getItem("idGame", idGame).withJSON("json", gameJson.toString());
        // Reutilizamos el guardado de Dynamo
        Table table = dynamoDB.getTable(gameTable);
        Item item = table.getItem("idGame", idGame);
        item.withJSON("json", gameJson.toString());
        table.putItem(item);

        JSONObject sendData = new JSONObject().put("type", "gameUpdated").put("codigoGame", codigoGame).put("json", gameJson).put("turno", nuevoTurno);
        new SendJsonHandler().sendUpdateToAll(sendData, context);
        return response(200, gameJson.toString());
    }

    private int[] obtenerExtremosActuales(JSONObject gameJson) {
        int[] ex = new int[4];
        JSONArray tablero = gameJson.getJSONArray("tablero");
        int pinfanoVal = gameJson.getJSONArray("pinfano").getInt(0);
        for (int i = 0; i < 4; i++) {
            JSONArray f = tablero.getJSONArray(i);
            ex[i] = (f.length() > 0) ? f.getInt(0) : pinfanoVal;
        }
        return ex;
    }

    private String obtenerKeyFichas(int turno) {
        return switch (turno) {
            case 1 -> "fichasJ1";
            case 2 -> "fichasBot1";
            case 3 -> "fichasJ2";
            default -> "fichasBot2";
        };
    }

    // --- MÉTODOS DE FINALIZACIÓN (Tus métodos originales corregidos) ---

    private APIGatewayProxyResponseEvent finalizarRonda(String idGame, String codigoGame, int turnoActual, JSONObject gameJson, Context context) {
        String ganadorEquipo = (turnoActual == 1 || turnoActual == 3) ? "A" : "B";
        int puntosRonda = calcularPuntosRonda(gameJson, context.getLogger());
        int puntosA = gameJson.getInt("puntosA");
        int puntosB = gameJson.getInt("puntosB");
        if (ganadorEquipo.equals("A")) puntosA += puntosRonda; else puntosB += puntosRonda;

        gameJson.put("puntosA", puntosA);
        gameJson.put("puntosB", puntosB);
        gameJson.put("mano", (gameJson.getInt("mano") % 4) + 1);
        gameJson.put("tablero", new JSONArray("[[],[],[],[]]"));
        gameJson.put("fichasSalidas", new JSONArray());
        gameJson.put("pinfano", new JSONArray("[-1,-1]"));
        gameJson.put("paso", new JSONArray("[\"\",\"\",\"\",\"\"]"));

        Map<String, JSONArray> nuevas = repartirFichasNuevaRonda();
        gameJson.put("fichasJ1", nuevas.get("J1")).put("fichasBot1", nuevas.get("J2"))
                .put("fichasJ2", nuevas.get("J3")).put("fichasBot2", nuevas.get("J4"));

        Table table = dynamoDB.getTable(gameTable);
        Item item = table.getItem("idGame", idGame);
        item.withJSON("json", gameJson.toString());
        table.putItem(item);

        enviarWS_RondaTerminada(codigoGame, gameJson, context);
        return response(200, gameJson.toString());
    }

    private APIGatewayProxyResponseEvent finalizarPartida(String idGame, String codigoGame, int ptsA, int ptsB, JSONObject gameJson, Context context) {
        String ganador = (ptsA > ptsB) ? "A" : "B";
        enviarWS_PartidaTerminada(codigoGame, ganador, ptsA, ptsB, context);
        gameJson.put("terminado", true);
        Table table = dynamoDB.getTable(gameTable);
        table.putItem(table.getItem("idGame", idGame).withBoolean("terminado", true).withJSON("json", gameJson.toString()));
        sumarRangoUsuariosDelEquipoGanador(gameJson, ganador, context.getLogger());
        return response(200, "{\"fin\":true}");
    }

    private int calcularPuntosRonda(JSONObject gameJson, LambdaLogger logger) {
        int suma = sumarFichas(gameJson.getJSONArray("fichasJ1")) + sumarFichas(gameJson.getJSONArray("fichasBot1")) +
                sumarFichas(gameJson.getJSONArray("fichasJ2")) + sumarFichas(gameJson.getJSONArray("fichasBot2"));
        int resto = suma % 5;
        if (resto != 0) suma += (5 - resto);
        return suma;
    }

    private int sumarFichas(JSONArray arr) {
        int t = 0;
        for (int i = 0; i < arr.length(); i++) t += arr.getJSONArray(i).getInt(0) + arr.getJSONArray(i).getInt(1);
        return t;
    }

    private Map<String, JSONArray> repartirFichasNuevaRonda() {
        List<int[]> f = new ArrayList<>();
        for (int i = 0; i <= 6; i++) for (int j = i; j <= 6; j++) f.add(new int[]{i, j});
        Collections.shuffle(f);
        Map<String, JSONArray> out = new HashMap<>();
        String[] keys = {"J1", "J2", "J3", "J4"};
        for (String k : keys) {
            JSONArray a = new JSONArray();
            for (int i = 0; i < 7; i++) a.put(new JSONArray(f.remove(0)));
            out.put(k, a);
        }
        return out;
    }

    private void sumarRangoUsuariosDelEquipoGanador(JSONObject gameJson, String ganador, LambdaLogger logger) {
        try {
            JSONArray players = gameJson.getJSONArray("listaPlayers");
            List<Integer> indices = ganador.equals("A") ? Arrays.asList(0, 2) : Arrays.asList(1, 3);
            for (int i : indices) {
                String u = players.getString(i);
                if (!u.equalsIgnoreCase("bot")) actualizarRangoUsuario(u, logger);
            }
        } catch (Exception e) { logger.log("Error rango: " + e.getMessage()); }
    }

    private void actualizarRangoUsuario(String username, LambdaLogger logger) {
        try {
            Table t = dynamoDB.getTable("PinfanoUsers");
            Item usr = t.getItem("username", username);
            if (usr != null) t.putItem(usr.withInt("rango", usr.getInt("rango") + 10));
        } catch (Exception e) { logger.log("Error actualizando usuario: " + e.getMessage()); }
    }

    private void enviarWS_RondaTerminada(String cod, JSONObject json, Context ctx) {
        new SendJsonHandler().sendUpdateToAll(new JSONObject().put("type", "gameUpdated").put("codigoGame", cod).put("json", json), ctx);
    }

    private void enviarWS_PartidaTerminada(String cod, String gan, int pA, int pB, Context ctx) {
        new SendJsonHandler().sendUpdateToAll(new JSONObject().put("type", "partidaTerminada").put("codigoGame", cod).put("ganador", gan).put("puntosA", pA).put("puntosB", pB), ctx);
    }

    private APIGatewayProxyResponseEvent response(int code, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(code).withHeaders(Map.of("Access-Control-Allow-Origin", "*", "Access-Control-Allow-Headers", "*")).withBody(body);
    }

    private APIGatewayProxyResponseEvent error(String msg) {
        return response(400, "{\"error\":\"" + msg + "\"}");
    }

    private static class JugadaOptima {
        JSONArray ficha; int posicion; int puntuacion;
        JugadaOptima(JSONArray f, int p, int s) { this.ficha = f; this.posicion = p; this.puntuacion = s; }
    }
}
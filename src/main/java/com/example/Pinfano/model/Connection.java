package com.example.Pinfano.model;

public class Connection {
    private String connectionId;
    private String username;
    private boolean loggedIn;

    public Connection() {}

    public Connection(String connectionId, String username, boolean loggedIn) {
        this.connectionId = connectionId;
        this.username = username;
        this.loggedIn = loggedIn;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public void setLoggedIn(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }
}

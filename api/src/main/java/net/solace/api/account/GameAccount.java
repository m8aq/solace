package net.solace.api.account;

public class GameAccount {
    private final String username;
    private final String password;
    private String displayName;
    private String bankPin;

    public boolean isJagexLauncher() {
        return this.username != null && this.password != null && this.displayName != null;
    }

    public GameAccount(String username, String password, String displayName, String bankPin) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.bankPin = bankPin;
    }

    public GameAccount(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getBankPin() {
        return this.bankPin;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setBankPin(String bankPin) {
        this.bankPin = bankPin;
    }
}


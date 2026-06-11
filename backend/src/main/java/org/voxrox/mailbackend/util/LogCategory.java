package org.voxrox.mailbackend.util;

public enum LogCategory {

    BOOT("BOOT"), STORAGE("STORAGE"),

    API("API"), ACCOUNT("ACCOUNT"), AUTH("AUTH"), SEARCH("SEARCH"), SYNC("SYNC"),

    DATABASE("DATABASE"), IMAP("IMAP"), SMTP("SMTP"), ATTACHMENT("ATTACHMENT"),

    SECURITY("SECURITY"), ERROR("ERROR"), CRITICAL("CRITICAL");

    private final String label;

    LogCategory(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return "[" + label + "]";
    }
}

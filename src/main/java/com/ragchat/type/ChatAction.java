package com.ragchat.type;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ChatAction {
    ADD, CANCEL, UPDATE, DELETE;

    @JsonCreator
    public static ChatAction from(String s) {
        return ChatAction.valueOf(s.toUpperCase());
    }
}
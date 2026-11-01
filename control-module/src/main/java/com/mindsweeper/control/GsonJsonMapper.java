package com.mindsweeper.control;

import com.google.gson.Gson;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

public class GsonJsonMapper implements JsonMapper {

    private final Gson gson = new Gson();

    @Override
    public String toJsonString(Object object, Type type) {
        return gson.toJson(object, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return gson.fromJson(json, targetType);
    }
}
package com.continuum.handler;

import java.util.Map;

@FunctionalInterface
public interface StepHandler {
    void execute(Map<String, Object> inputs) throws Exception;
}

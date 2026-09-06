package org.evomaster.client.java.controller.mongo.selectors;

import org.evomaster.client.java.controller.mongo.operations.BitsAllClearOperation;
import org.evomaster.client.java.controller.mongo.operations.QueryOperation;

import java.util.Objects;

/**
 * { field: { $bitsAllClear: value } }
 */
public class BitsAllClearSelector extends SingleConditionQuerySelector {

    public static final String BITS_ALL_CLEAR_OPERATOR = "$bitsAllClear";

    @Override
    protected QueryOperation parseValue(String fieldName, Object value) {
        Objects.requireNonNull(fieldName);
        // MongoDB accepts any integer bitmask, so it can arrive as an Integer as well as a Long
        return value instanceof Integer || value instanceof Long
                ? new BitsAllClearOperation(fieldName, ((Number) value).longValue())
                : null;
    }

    @Override
    protected String operator() {
        return BITS_ALL_CLEAR_OPERATOR;
    }
}

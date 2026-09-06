package org.evomaster.client.java.controller.mongo.selectors;

import org.evomaster.client.java.controller.mongo.operations.BitsAnyClearOperation;
import org.evomaster.client.java.controller.mongo.operations.QueryOperation;

import java.util.Objects;

/**
 * { field: { $bitsAnyClear: value } }
 */
public class BitsAnyClearSelector extends SingleConditionQuerySelector {

    public static final String BITS_ANY_CLEAR_OPERATOR = "$bitsAnyClear";

    @Override
    protected QueryOperation parseValue(String fieldName, Object value) {
        Objects.requireNonNull(fieldName);
        // MongoDB accepts any integer bitmask, so it can arrive as an Integer as well as a Long
        return value instanceof Integer || value instanceof Long
                ? new BitsAnyClearOperation(fieldName, ((Number) value).longValue())
                : null;
    }

    @Override
    protected String operator() {
        return BITS_ANY_CLEAR_OPERATOR;
    }
}

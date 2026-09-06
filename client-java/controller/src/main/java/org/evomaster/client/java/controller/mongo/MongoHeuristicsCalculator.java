package org.evomaster.client.java.controller.mongo;

import org.evomaster.client.java.controller.internal.db.mongo.MongoDistanceWithMetrics;
import org.evomaster.client.java.controller.mongo.geometry.GeoJsonPoint;
import org.evomaster.client.java.controller.mongo.geometry.GeoJsonUtils;
import org.evomaster.client.java.controller.mongo.operations.*;
import org.evomaster.client.java.controller.mongo.utils.BsonHelper;
import org.evomaster.client.java.distance.heuristics.Truthness;
import org.evomaster.client.java.instrumentation.coverage.methodreplacement.RegexDistanceUtils;
import org.evomaster.client.java.instrumentation.shared.StringSpecialization;
import org.evomaster.client.java.instrumentation.shared.StringSpecializationInfo;
import org.evomaster.client.java.instrumentation.shared.TaintType;
import org.evomaster.client.java.instrumentation.staticstate.ExecutionTracer;
import org.evomaster.client.java.sql.heuristic.SqlExpressionEvaluator;
import org.evomaster.client.java.sql.internal.TaintHandler;

import static org.evomaster.client.java.controller.mongo.utils.BsonHelper.*;
import static org.evomaster.client.java.distance.heuristics.TruthnessUtils.*;
import static org.evomaster.client.java.sql.heuristic.ConversionHelper.convertToInstant;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class MongoHeuristicsCalculator {

    // TODO these constants should be replaced by DistanceHelper constants
    public static final double C = 0.1;
    // TODO These constants should be refactored by TruthnessUtils constants
    public static final Truthness TRUE_C = new Truthness(1.0, C);
    public static final Truthness C_FALSE = new Truthness(C, 1.0);


    private final TaintHandler taintHandler;

    public MongoHeuristicsCalculator() {
        this(null);
    }

    public MongoHeuristicsCalculator(TaintHandler taintHandler) {
        this.taintHandler = taintHandler;
    }


    public MongoDistanceWithMetrics computeDistanceDocuments(Object query, Iterable<?> documents) {
        long count = StreamSupport.stream(documents.spliterator(), false).count();
        Truthness heuristicScoreCollection = getTruthnessToEmpty((int) count).invert();

        QueryOperation queryOperation = parseQuery(query);
        Truthness hCondition = computeHeuristicOnDocuments(queryOperation, documents);

        Truthness hQuery = buildAndAggregationTruthness(heuristicScoreCollection, hCondition);

        // Map truthness to distance where 0 is true.
        // If it's true, distance 0.
        // If it's false, distance is 1.0 - ofTrue.
        double distance = hQuery.isTrue() ? 0.0 : 1.0 - hQuery.getOfTrue();

        return new MongoDistanceWithMetrics(distance, (int) count);
    }

    private Truthness computeHeuristicOnDocuments(QueryOperation operation, Iterable<?> documents) {
        long count = StreamSupport.stream(documents.spliterator(), false).count();
        if (count == 0) {
            return C_FALSE;
        }

        double maxOfTrue = 0;
        boolean first = true;
        for (Object doc : documents) {
            double ofTrue = computeHeuristicOnDocument(operation, doc).getOfTrue();
            if (first || ofTrue > maxOfTrue) {
                maxOfTrue = ofTrue;
            }
            first = false;
        }

        return buildSafeScaledTruthness(maxOfTrue);
    }

    private static Truthness buildSafeScaledTruthness(double maxOfTrue) {
        if (maxOfTrue == 1.0) {
            return TRUE_C;
        } else {
            return buildScaledTruthness(C, maxOfTrue);
        }
    }

    /**
     * Compute a "branch" distance heuristics.
     *
     * @param query    the QUERY clause that we want to resolve as true
     * @param document a document in the database for which we want to calculate the distance
     * @return a branch distance, where 0 means that the document would make the QUERY resolve as true
     */
    Truthness computeHeuristicDocument(Object query, Object document) {
        QueryOperation operation = parseQuery(query);
        return computeHeuristicOnDocument(operation, document);
    }

    private QueryOperation parseQuery(Object query) {
        return new QueryParser().parse(query);
    }

    private Truthness computeHeuristicOnDocument(QueryOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        if (operation instanceof AndOperation) {
            return computeHeuristic((AndOperation) operation, document);
        } else if (operation instanceof OrOperation) {
            return computeHeuristic((OrOperation) operation, document);
        } else if (operation instanceof NorOperation) {
            return computeHeuristic((NorOperation) operation, document);
        } else if (operation instanceof ExistsOperation) {
            return computeHeuristic((ExistsOperation) operation, document);
        } else if (operation instanceof EqualsOperation<?>) {
            return computeHeuristic((EqualsOperation<?>) operation, document);
        } else if (operation instanceof NotEqualsOperation<?>) {
            return computeHeuristic((NotEqualsOperation<?>) operation, document);
        } else if (operation instanceof GreaterThanOperation<?>) {
            return computeHeuristic((GreaterThanOperation<?>) operation, document);
        } else if (operation instanceof GreaterThanEqualsOperation<?>) {
            return computeHeuristic((GreaterThanEqualsOperation<?>) operation, document);
        } else if (operation instanceof LessThanOperation<?>) {
            return computeHeuristic((LessThanOperation<?>) operation, document);
        } else if (operation instanceof LessThanEqualsOperation<?>) {
            return computeHeuristic((LessThanEqualsOperation<?>) operation, document);
        } else if (operation instanceof InOperation<?>) {
            return computeHeuristic((InOperation<?>) operation, document);
        } else if (operation instanceof NotInOperation<?>) {
            return computeHeuristic((NotInOperation<?>) operation, document);
        } else if (operation instanceof AllOperation<?>) {
            return computeHeuristic((AllOperation<?>) operation, document);
        } else if (operation instanceof SizeOperation) {
            return computeHeuristic((SizeOperation) operation, document);
        } else if (operation instanceof ModOperation) {
            return computeHeuristic((ModOperation) operation, document);
        } else if (operation instanceof BitsOperation) {
            return computeHeuristic((BitsOperation) operation, document);
        } else if (operation instanceof NotOperation) {
            return computeHeuristic((NotOperation) operation, document);
        } else if (operation instanceof TypeOperation) {
            return computeHeuristic((TypeOperation) operation, document);
        } else if (operation instanceof RegexOperation) {
            return computeHeuristic((RegexOperation) operation, document);
        } else if (operation instanceof NearSphereOperation) {
            return computeHeuristic((NearSphereOperation) operation, document);
        } else if (operation instanceof NearOperation) {
            return computeHeuristic((NearOperation) operation, document);
        } else if (operation instanceof ElemMatchOperation) {
            return computeHeuristic((ElemMatchOperation) operation, document);
        } else if (operation instanceof TrueOperation) {
            return computeHeuristic((TrueOperation) operation, document);
        } else {
            throw new IllegalArgumentException("Unsupported QueryOperation type: " + operation.getClass().getName());
        }
    }

    private Truthness computeHeuristic(RegexOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);
        Objects.requireNonNull(operation.getPattern());
        Objects.requireNonNull(operation.getOptions());

        Object fieldValue = getValue(document, operation.getFieldName());
        if (!(fieldValue instanceof String)) {
            return C_FALSE;
        }

        final String inputValue = (String) fieldValue;

        final Pattern pattern = operation.getPattern();
        final String patternString = pattern.pattern();

        if (taintHandler!=null) {
            final int patternFlags = pattern.flags();
            // TODO: tainting should take into account the pattern flags, which can change the matching behavior
            // TODO: regex could be a partial word match (MongoDB $regex) instead of a whole word match (Matcher.matches())
            taintHandler.handleTaintForRegex(inputValue, patternString);
        }


        Matcher matcher = pattern.matcher(inputValue);
        boolean matches = matcher.find();

        if (matches) {
            return TRUE_C;
        } else {
            // TODO this does not take into account pattern flags, which can change the matching behavior
            final int distance = RegexDistanceUtils.getStandardDistance(inputValue, patternString);
            // The distance approximation can be zero even when Java's matcher rejects the input
            // (for example, when flags affect line terminators). Keep non-matches strictly false.
            double ofTrue = 1d / (1.1d + distance);
            return buildSafeScaledTruthness(ofTrue);
        }
    }

    private Truthness computeHeuristic(NearOperation operation, Object document) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(document);
        Objects.requireNonNull(operation.getFieldName());

        final String fieldName = operation.getFieldName();
        final Object fieldValue = getValue(document, fieldName);

        final double longitude = operation.getLongitude();
        final double latitude = operation.getLatitude();

        GeoSpatialModel model = operation.hasLegacyCoordinates()
                ? GeoSpatialModel.PLANAR
                : GeoSpatialModel.SPHERICAL;
        return computeHeuristic(operation, longitude, latitude, fieldValue, model);
    }

    /**
     * This one-line implementation is kept for consistency with the other computeHeuristic methods,
     * even though it always returns TRUE_C.
     *
     * @param operation
     * @param document
     * @return
     */
    private Truthness computeHeuristic(TrueOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);
        return TRUE_C;
    }

    private Truthness computeHeuristicComparisonNonNullValues(Object actualValue, Object expectedValue, SqlExpressionEvaluator.ComparisonOperatorType comparisonOperatorType) {
        Objects.requireNonNull(actualValue);
        Objects.requireNonNull(expectedValue);
        if (!isTypeSupportedForComparison(actualValue) || !isTypeSupportedForComparison(expectedValue)) {
            /*
                A value of a type this calculator cannot compare, eg a sub-document. MongoDB does
                not fail on those, it simply does not match them, so neither should we: throwing
                here would escape all the way out of the heuristics computation for the action.
                They are handled like any other pair of incomparable values, see below.
             */
            return incomparableValues(comparisonOperatorType);
        }

        final Truthness truthnessOfComparison;
        if (actualValue instanceof Number && expectedValue instanceof Number) {
            truthnessOfComparison = SqlExpressionEvaluator.calculateTruthnessForNumberComparison((Number) actualValue, (Number) expectedValue, comparisonOperatorType);

        } else if (actualValue instanceof String && expectedValue instanceof String) {
            String actualString = (String) actualValue;
            String expectedString = (String) expectedValue;
            if (taintHandler != null && comparisonOperatorType == SqlExpressionEvaluator.ComparisonOperatorType.EQUALS_TO) {
                taintHandler.handleTaintForStringEquals(actualString, expectedString, false);
            }
            truthnessOfComparison = SqlExpressionEvaluator.calculateTruthnessForStringComparison(actualString, expectedString, comparisonOperatorType);

        } else if (actualValue instanceof Boolean && expectedValue instanceof Boolean) {
            int actualIntValue = toIntValue((Boolean) actualValue);
            int expectedIntValue = toIntValue((Boolean) expectedValue);
            truthnessOfComparison = SqlExpressionEvaluator.calculateTruthnessForNumberComparison(
                    actualIntValue, expectedIntValue, comparisonOperatorType);

        } else if (actualValue instanceof List<?> && expectedValue instanceof List<?>) {
            truthnessOfComparison = calculateTruthnessForListComparison((List<?>) actualValue, (List<?>) expectedValue, comparisonOperatorType);

        } else if (actualValue instanceof Date && expectedValue instanceof Date) {
            truthnessOfComparison = SqlExpressionEvaluator.calculateTruthnessForInstantComparison(convertToInstant(actualValue), convertToInstant(expectedValue), comparisonOperatorType);


        } else if (BsonHelper.isBsonTimestamp(actualValue) && BsonHelper.isBsonTimestamp(expectedValue)) {
            long actualTimestamp = BsonHelper.getBsonTimestampValue(actualValue);
            long expectedTimestamp = BsonHelper.getBsonTimestampValue(expectedValue);
            truthnessOfComparison = SqlExpressionEvaluator.calculateTruthnessForNumberComparison(actualTimestamp, expectedTimestamp, comparisonOperatorType);

        } else if (BsonHelper.isObjectId(actualValue) || BsonHelper.isObjectId(expectedValue)) {
            String actualString = actualValue.toString();
            String expectedString = expectedValue.toString();
            if (taintHandler != null && comparisonOperatorType == SqlExpressionEvaluator.ComparisonOperatorType.EQUALS_TO) {
                taintHandler.handleTaintForStringEquals(actualString, expectedString, false);
            }
            truthnessOfComparison = SqlExpressionEvaluator.calculateTruthnessForStringComparison(actualString, expectedString, comparisonOperatorType);

        } else {
            // no comparison logic is defined for this combination of types
            truthnessOfComparison = incomparableValues(comparisonOperatorType);
        }
        return truthnessOfComparison;
    }

    /**
     * The score of a comparison between two values that cannot be compared with each other,
     * either because they are of different BSON types or because this calculator has no
     * comparison logic for them. MongoDB considers such values to be different from each other:
     * an equality check is then false, but an inequality check is true. Ordering comparisons do
     * not match across different BSON types either, so those stay false as well.
     */
    private static Truthness incomparableValues(SqlExpressionEvaluator.ComparisonOperatorType comparisonOperatorType) {
        return comparisonOperatorType == SqlExpressionEvaluator.ComparisonOperatorType.NOT_EQUALS_TO
                ? TRUE_C
                : C_FALSE;
    }

    private static int toIntValue(Boolean actualValue) {
        return actualValue ? 1 : 0;
    }

    /**
     * Checks if the provided value is of a supported type for comparison.
     *
     * @param value
     * @return
     */
    private static boolean isTypeSupportedForComparison(Object value) {
        Objects.requireNonNull(value);

        return value instanceof String ||
                value instanceof Number ||
                value instanceof Boolean ||
                value instanceof Date ||
                value instanceof List<?> ||
                BsonHelper.isObjectId(value) ||
                BsonHelper.isBsonTimestamp(value);
    }

    /**
     * Computes the heuristic score for a {"f",{"$eq": value }} query.
     * If the field "f" is not present, and the expected value is null, the condition is satisfied.
     * If the field "f" is not present, but the expected value is not null, the condition is not satisfied.
     * If the field "f" is present, null values are considered equal, and non-null values are compared
     * using the corresponding heuristic score for non-null values.
     *
     * @param operation the  {"f",{"$eq": value }} query
     * @param document  the BSON document to evaluate the heuristic score against
     * @return
     */
    private Truthness computeHeuristic(EqualsOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        String fieldName = operation.getFieldName();
        Object expectedValue = operation.getValue();

        Object actualValue;
        if (documentContainsField(document, fieldName)) {
            actualValue = getValue(document, fieldName);
        } else {
            actualValue = null;
        }

        return computeHeuristicForMatchedValue(expectedValue, actualValue);
    }

    private Truthness computeHeuristicComparisonNullableValues(Object expectedValue, Object actualValue, SqlExpressionEvaluator.ComparisonOperatorType comparisonOperatorType) {
        if (expectedValue == null || actualValue == null) {
            switch (comparisonOperatorType) {
                case EQUALS_TO:
                    return (expectedValue == null && actualValue == null) ? TRUE_C : C_FALSE;
                case NOT_EQUALS_TO:
                    return (expectedValue == null && actualValue == null) ? C_FALSE : TRUE_C;
                case GREATER_THAN:
                case GREATER_THAN_EQUALS:
                case MINOR_THAN:
                case MINOR_THAN_EQUALS:
                    return C_FALSE;
                default:
                    throw new IllegalArgumentException("Unsupported comparison operator type: " + comparisonOperatorType);
            }
        } else {
            Truthness valTruthness = computeHeuristicComparisonNonNullValues(actualValue,
                    expectedValue,
                    comparisonOperatorType);
            return buildSafeScaledTruthness(valTruthness);
        }
    }


    /**
     * Computes the heuristic score for a {"f",{"$ne": value}} query.
     * Evaluates whether the value of the specified field in a document is not equal
     * to the expected value. If the condition is satisfied, the score is inverted to
     * reflect the distance from the condition being false.
     *
     * @param operation the {"f",{"$ne": value}} query encapsulated as a NotEqualsOperation.
     *                  This operation specifies the field name and the expected value
     *                  for the inequality check.
     * @param document  the BSON document to evaluate the heuristic score against.
     *                  The document may or may not contain the field to be checked.
     * @return a Truthness object representing the distance of the document from meeting
     * the inequality condition, where one of the values (true or false) is 1,
     * and the other represents the distance to the alternate condition.
     */
    private Truthness computeHeuristic(NotEqualsOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        Object expectedValue = operation.getValue();
        String fieldName = operation.getFieldName();

        Object actualValue;
        if (documentContainsField(document, fieldName)) {
            actualValue = getValue(document, fieldName);
        } else {
            actualValue = null;
        }
        return computeHeuristicForMatchedValue(expectedValue, actualValue).invert();
    }


    private Truthness computeHeuristic(GreaterThanOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        Object expectedValue = operation.getValue();
        String fieldName = operation.getFieldName();

        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, fieldName);
            return computeHeuristicOnFieldValue(actualValue,
                    value -> computeHeuristicComparisonNullableValues(
                            expectedValue,
                            value,
                            SqlExpressionEvaluator.ComparisonOperatorType.GREATER_THAN));
        }
    }

    private Truthness computeHeuristic(GreaterThanEqualsOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        final String fieldName = operation.getFieldName();
        final Object expectedValue = operation.getValue();

        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, fieldName);
            return computeHeuristicOnFieldValue(actualValue,
                    value -> computeHeuristicComparisonNullableValues(
                            expectedValue,
                            value,
                            SqlExpressionEvaluator.ComparisonOperatorType.GREATER_THAN_EQUALS));
        }
    }

    private Truthness computeHeuristic(LessThanOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        final String fieldName = operation.getFieldName();
        final Object expectedValue = operation.getValue();

        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, fieldName);
            return computeHeuristicOnFieldValue(actualValue,
                    value -> computeHeuristicComparisonNullableValues(
                            expectedValue,
                            value,
                            SqlExpressionEvaluator.ComparisonOperatorType.MINOR_THAN));
        }
    }

    private Truthness computeHeuristic(LessThanEqualsOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        final String fieldName = operation.getFieldName();
        final Object expectedValue = operation.getValue();

        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, fieldName);
            return computeHeuristicOnFieldValue(actualValue,
                    value -> computeHeuristicComparisonNullableValues(
                            expectedValue,
                            value,
                            SqlExpressionEvaluator.ComparisonOperatorType.MINOR_THAN_EQUALS));
        }
    }

    private Truthness computeHeuristic(OrOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        Truthness[] results = operation.getConditions().stream()
                .map(condition -> computeHeuristicOnDocument(condition, document))
                .toArray(Truthness[]::new);
        return buildOrAggregationTruthness(results);
    }

    private Truthness computeHeuristic(AndOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        Truthness[] results = operation.getConditions().stream()
                .map(condition -> computeHeuristicOnDocument(condition, document))
                .toArray(Truthness[]::new);
        return buildAndAggregationTruthness(results);
    }


    private Truthness computeHeuristic(InOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        return computeHeuristicForMembership(operation.getFieldName(), operation.getValues(), document);
    }

    /**
     * Computes the heuristic score for the membership of a field's value in a list of expected
     * values, ie the condition shared by {"f",{"$in": [...]}} and, negated, by
     * {"f",{"$nin": [...]}}. The condition holds when the field matches any one of the expected
     * values, in the sense of {@link #computeHeuristicForMatchedValue(Object, Object)}.
     *
     * @param fieldName      the name of the field the query is about
     * @param expectedValues the values the query lists as candidates
     * @param document       the BSON document to evaluate the heuristic score against
     * @return a Truthness object representing how close the field is to holding one of the values
     */
    private Truthness computeHeuristicForMembership(String fieldName, List<?> expectedValues, Object document) {
        Objects.requireNonNull(expectedValues);

        if (expectedValues.isEmpty()) {
            // no candidate value can be matched
            return C_FALSE;
        }

        final Object actualValue;
        if (documentContainsField(document, fieldName)) {
            actualValue = getValue(document, fieldName);
        } else {
            // If the document does not have a field
            // with that name, we consider the field
            // value to be null
            actualValue = null;
        }

        return buildOrAggregationTruthness(expectedValues.stream()
                .map(expectedValue -> computeHeuristicForMatchedValue(expectedValue, actualValue))
                .toArray(Truthness[]::new));
    }

    private Truthness computeHeuristic(NotInOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        final String fieldName = operation.getFieldName();

        if (!documentContainsField(document, fieldName)) {
            // a value that is not there cannot be one of the excluded ones
            return TRUE_C;
        }
        /*
            $nin is the negation of $in, so it must consider the array elements in the same way:
            a document whose array holds any of the excluded values does not match.
         */
        return computeHeuristicForMembership(fieldName, operation.getValues(), document).invert();
    }

    /**
     * Computes the heuristic score for a {"f",{"$all": [v1, ..., vn] }} query.
     * The condition holds when "f" matches every one of the expected values, so the score is an
     * AND aggregation over them. Note the direction: extra elements in the document are
     * irrelevant, whereas a missing expected value makes the condition false.
     * The field does not have to hold an array: a scalar matches a $all listing only values
     * equal to it, which is why {"f": "a"} is matched by {"$all": ["a"]}.
     *
     * @param operation the {"f",{"$all": [...]}} query encapsulated as an AllOperation
     * @param document  the BSON document to evaluate the heuristic score against
     * @return a Truthness object representing the distance of the document from meeting the condition
     */
    private Truthness computeHeuristic(AllOperation<?> operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        List<?> expectedValues = operation.getValues();
        final String fieldName = operation.getFieldName();
        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        } else if (expectedValues.isEmpty()) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, fieldName);
            Truthness res = buildAndAggregationTruthness(expectedValues
                    .stream()
                    .map(expectedValue -> computeHeuristicForMatchedValue(expectedValue, actualValue))
                    .toArray(Truthness[]::new));
            return buildSafeScaledTruthness(res);
        }
    }

    /**
     * Computes the heuristic score for MongoDB's matching of a field against a single value,
     * ie the condition that the field holds that value. The field matches when its own value is
     * the expected one, and also, if it holds an array, when any element of that array is.
     * Both readings apply at once: {"f": ["a","b"]} is matched both by the value ["a","b"] and
     * by the value "a".
     *
     * @param expectedValue the value the query requires the field to hold
     * @param actualValue   the value held by the field in the document, possibly an array or null
     * @return a Truthness object representing how close the field is to holding the expected value
     */
    private Truthness computeHeuristicForMatchedValue(Object expectedValue, Object actualValue) {
        return computeHeuristicOnFieldValue(actualValue,
                value -> computeHeuristicComparisonNullableValues(expectedValue, value,
                        SqlExpressionEvaluator.ComparisonOperatorType.EQUALS_TO));
    }

    /**
     * Scores a condition on a field, following MongoDB's rule that a field holding an array
     * satisfies a condition when the array itself does, or when any one of its elements does.
     * The rule applies to every condition expressed on a field, not only to equality: for
     * example {"a": [1,2,3]} satisfies {"$gt": 2}, because one of its elements does.
     * Keeping the array itself among the options is also what makes an empty array work: it
     * holds no element to score, but it can still satisfy the condition on its own.
     *
     * @param actualValue  the value held by the field in the document, possibly an array or null
     * @param scoreOfValue the score of the condition on a single value
     * @return a Truthness object representing how close the field is to satisfying the condition
     */
    private Truthness computeHeuristicOnFieldValue(Object actualValue, Function<Object, Truthness> scoreOfValue) {

        Truthness wholeValue = scoreOfValue.apply(actualValue);

        if (!(actualValue instanceof List<?>)) {
            return wholeValue;
        }

        List<?> actualValueList = (List<?>) actualValue;
        Truthness[] options = new Truthness[actualValueList.size() + 1];
        options[0] = wholeValue;
        for (int i = 0; i < actualValueList.size(); i++) {
            options[i + 1] = scoreOfValue.apply(actualValueList.get(i));
        }
        return buildOrAggregationTruthness(options);
    }

    private static Truthness buildSafeScaledTruthness(Truthness truthness) {
        return buildSafeScaledTruthness(truthness.getOfTrue());
    }


    private Truthness computeHeuristic(SizeOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);
        Objects.requireNonNull(operation.getValue());

        if (!documentContainsField(document, operation.getFieldName())) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, operation.getFieldName());
            if (actualValue == null || !(actualValue instanceof List<?>)) {
                return C_FALSE;
            } else {
                int actualSize = ((List<?>) actualValue).size();
                int expectedSize = operation.getValue().intValue();
                Truthness res = getEqualityTruthness(actualSize, expectedSize);
                return buildSafeScaledTruthness(res);
            }
        }
    }


    private Truthness computeHeuristic(ElemMatchOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);
        Objects.requireNonNull(operation.getFieldName());
        Objects.requireNonNull(operation.getCondition());

        if (!documentContainsField(document, operation.getFieldName())) {
            return C_FALSE;
        } else {
            Object actualValue = getValue(document, operation.getFieldName());
            if (actualValue == null || !(actualValue instanceof List<?>)) {
                return C_FALSE;
            } else {
                List<?> actualList = (List<?>) actualValue;
                if (actualList.isEmpty()) {
                    return C_FALSE;
                } else {
                    Truthness orAggregation = buildOrAggregationTruthness(actualList.stream()
                            .map(listElement -> computeHeuristicOnElemMatchElement(
                                    operation.getCondition(), listElement, document))
                            .toArray(Truthness[]::new));
                    return buildSafeScaledTruthness(orAggregation);
                }
            }
        }
    }

    private Truthness computeHeuristicOnElemMatchElement(QueryOperation condition, Object element, Object documentTemplate) {
        if (isBsonDocument(element)) {
            return computeHeuristicOnDocument(condition, element);
        }

        if (!(condition instanceof QueryOperationWithField)) {
            return C_FALSE;
        }

        Object elementDocument = newDocument(documentTemplate);
        appendToDocument(elementDocument, ((QueryOperationWithField) condition).getFieldName(), element);
        return computeHeuristicOnDocument(condition, elementDocument);
    }

    private Truthness computeHeuristic(ExistsOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        String expectedFieldName = operation.getFieldName();
        Set<String> actualFieldNames = documentKeys(document);
        final Truthness res;
        if (actualFieldNames.isEmpty()) {
            res = C_FALSE;
        } else {
            Truthness orTruthness = buildOrAggregationTruthness(actualFieldNames.stream()
                    .map(actualFieldName ->
                            computeHeuristicComparisonNonNullValues(actualFieldName,
                                    expectedFieldName,
                                    SqlExpressionEvaluator.ComparisonOperatorType.EQUALS_TO))
                    .toArray(Truthness[]::new));
            res = buildSafeScaledTruthness(orTruthness);
        }

        if (operation.getBoolean() == true) {
            // "true" case of exists operation
            return res;
        } else {
            // "false" case of exists operation
            return res.invert();
        }
    }

    private static void requireNonNullQueryAndDocument(QueryOperation operation, Object document) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(document);
        if (!isBsonDocument(document)) {
            throw new IllegalArgumentException("The provided document is not a valid BSON document: " + document);
        }
    }

    private Truthness computeHeuristic(ModOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);
        Objects.requireNonNull(operation.getDivisor());
        Objects.requireNonNull(operation.getRemainder());

        long divisor = operation.getDivisor().longValue();
        long expectedRemainder = operation.getRemainder().longValue();

        final String fieldName = operation.getFieldName();
        final Object actualValue;
        if (!documentContainsField(document, fieldName)) {
            actualValue = null;
        } else {
            actualValue = getValue(document, fieldName);
        }

        return computeHeuristicOnFieldValue(actualValue, value -> {
            if (!(value instanceof Number)) {
                return C_FALSE;
            }
            long actualRemainder = ((Number) value).longValue() % divisor;
            return buildSafeScaledTruthness(getEqualityTruthness(actualRemainder, expectedRemainder));
        });
    }

    private Truthness computeHeuristic(BitsOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        String fieldName = operation.getFieldName();
        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        }

        Object actualValue = getValue(document, fieldName);
        return computeHeuristicOnFieldValue(actualValue, value -> scoreOfBits(operation, value));
    }

    private Truthness scoreOfBits(BitsOperation operation, Object actualValue) {
        if (!(actualValue instanceof Number)) {
            return C_FALSE;
        }

        /*
            A bitwise operator only applies to a number that is an integer: 3.0 is matched,
            whereas 3.5 is not, rather than being truncated to 3.
         */
        double asDouble = ((Number) actualValue).doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble != Math.floor(asDouble)) {
            return C_FALSE;
        }

        final long bitmask = operation.getBitmask();
        final long maskedValue = ((Number) actualValue).longValue() & bitmask;
        final int numberOfSetBitsInMaskedValue = Long.bitCount(maskedValue);
        final int numberOfBitsInMask = Long.bitCount(bitmask);
        if (operation instanceof BitsAllClearOperation) {
            Truthness equalityTruthness = getEqualityTruthness(numberOfSetBitsInMaskedValue, 0);
            return buildSafeScaledTruthness(equalityTruthness);
        } else if (operation instanceof BitsAllSetOperation) {
            Truthness equalityTruthness = getEqualityTruthness(numberOfSetBitsInMaskedValue, numberOfBitsInMask);
            return buildSafeScaledTruthness(equalityTruthness);
        } else if (operation instanceof BitsAnyClearOperation) {
            Truthness lessThanTruthness = getLessThanTruthness(numberOfSetBitsInMaskedValue, numberOfBitsInMask);
            return buildSafeScaledTruthness(lessThanTruthness);
        } else if (operation instanceof BitsAnySetOperation) {
            Truthness lessThanTruthness = getLessThanTruthness(0, numberOfSetBitsInMaskedValue);
            return buildSafeScaledTruthness(lessThanTruthness);
        } else {
            throw new IllegalArgumentException("Unsupported BitsOperation type: " + operation.getClass().getName());
        }
    }


    private Truthness computeHeuristic(NotOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        /*
            No special case for a missing field here. Several operators do match a document in
            which the field is absent (eg $ne, $nin, and $exists with "false"), and $not must then
            be false. The inner operators already treat an absent field as a null value, so
            negating their score is correct whether or not the field is there.
         */
        QueryOperation condition = operation.getCondition();
        return computeHeuristicOnDocument(condition, document).invert();
    }

    private Truthness computeHeuristic(NorOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        Truthness orRes = buildOrAggregationTruthness(operation.getConditions()
                .stream()
                .map(condition -> computeHeuristicOnDocument(condition, document))
                .toArray(Truthness[]::new));
        return orRes.invert();
    }

    private Truthness computeHeuristic(TypeOperation operation, Object document) {
        requireNonNullQueryAndDocument(operation, document);

        String fieldName = operation.getFieldName();
        if (!documentContainsField(document, fieldName)) {
            return C_FALSE;
        } else {
            final Object bsonType = operation.getType();
            String expectedType = getType(bsonType);

            Object actualValue = getValue(document, fieldName);
            String actualType = actualValue == null ? "null" : actualValue.getClass().getTypeName();

            final Truthness equalityTruthness = SqlExpressionEvaluator.getEqualityTruthness(actualType, expectedType);
            return buildSafeScaledTruthness(equalityTruthness);
        }
    }

    private Truthness computeHeuristic(NearSphereOperation operation, Object document) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(document);
        Objects.requireNonNull(operation.getFieldName());

        final String fieldName = operation.getFieldName();
        final Object fieldValue = getValue(document, fieldName);

        final double longitude = operation.getLongitude();
        final double latitude = operation.getLatitude();

        return computeHeuristic(operation, longitude, latitude, fieldValue, GeoSpatialModel.SPHERICAL);
    }

    /**
     * Enumeration representing the geospatial model used for distance calculations.
     * PLANAR: Uses Euclidean distance for flat surfaces.
     * SPHERICAL: Uses Haversine distance for spherical surfaces (e.g., Earth)
     */
    private enum GeoSpatialModel {
        /**
         * PLANAR: Uses Euclidean distance for flat surfaces.
         */
        PLANAR,
        /**
         * SPHERICAL: Uses Haversine distance for spherical surfaces (e.g., Earth)
         */
        SPHERICAL
    }

    private static Truthness computeHeuristic(AbstractProximityOperation abstractProximityOperation,
                                              double longitude,
                                              double latitude,
                                              Object fieldValue,
                                              GeoSpatialModel geoSpatialModel) {

        Objects.requireNonNull(abstractProximityOperation);
        double x1 = geoSpatialModel == GeoSpatialModel.SPHERICAL ? Math.toRadians(longitude) : longitude;
        double y1 = geoSpatialModel == GeoSpatialModel.SPHERICAL ? Math.toRadians(latitude) : latitude;
        double x2;
        double y2;

    /*
      GeoJSON Point in document.
      type key is case-sensitive.
      (https://datatracker.ietf.org/doc/html/rfc7946#section-1.4)
     */
        if (isBsonDocument(fieldValue)
                && GeoJsonUtils.isGeoJsonPoint(fieldValue)) {
            GeoJsonPoint geoJsonPoint = GeoJsonUtils.toGeoJsonPoint(fieldValue);
            x2 = geoSpatialModel == GeoSpatialModel.SPHERICAL
                    ? Math.toRadians(geoJsonPoint.getLongitude())
                    : geoJsonPoint.getLongitude();
            y2 = geoSpatialModel == GeoSpatialModel.SPHERICAL
                    ? Math.toRadians(geoJsonPoint.getLatitude())
                    : geoJsonPoint.getLatitude();
        } else {
            return C_FALSE;
        }
        double distanceBetweenPoints;
        switch (geoSpatialModel) {
            case PLANAR:
                distanceBetweenPoints = euclideanDistance(x1, y1, x2, y2);
                break;
            case SPHERICAL:
                distanceBetweenPoints = haversineDistance(x1, y1, x2, y2);
                break;
            default:
                throw new IllegalArgumentException("Unsupported GeoSpatialModel: " + geoSpatialModel);
        }
        double max = abstractProximityOperation.hasMaxDistance()
                ? abstractProximityOperation.getMaxDistance()
                : Double.MAX_VALUE;

        double min = abstractProximityOperation.hasMinDistance()
                ? abstractProximityOperation.getMinDistance()
                : 0.0;

        if (min <= distanceBetweenPoints
                && distanceBetweenPoints <= max) {
            return TRUE_C;
        }

        return (distanceBetweenPoints > max)
                ? getEqualityTruthness(distanceBetweenPoints, max)
                : getEqualityTruthness(distanceBetweenPoints, min);
    }


    /**
     * Calculates the Haversine distance between two geographical points specified
     * in radians. The Haversine formula determines the great-circle distance between
     * two points on a sphere given their latitudes and longitudes.
     *
     * @param x1 the longitude of the first point in radians
     * @param y1 the latitude of the first point in radians
     * @param x2 the longitude of the second point in radians
     * @param y2 the latitude of the second point in radians
     * @return the Haversine distance between the two points in meters
     */
    private static double haversineDistance(
            double x1,
            double y1,
            double x2,
            double y2) {

        // Earth's radius in meters
        double radius = 6371000.0;

        double dLat = y2 - y1;
        double dLon = x2 - x1;

        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(y1) * Math.cos(y2)
                * Math.pow(Math.sin(dLon / 2), 2);

        double c = 2 * Math.atan2(
                Math.sqrt(a),
                Math.sqrt(1 - a));

        return radius * c;
    }

    /**
     * Calculates the Euclidean distance between two points in a 2D Cartesian coordinate system.
     * The Euclidean distance is the straight-line distance between two points in Euclidean space.
     *
     * @param x1 the x-coordinate of the first point
     * @param y1 the y-coordinate of the first point
     * @param x2 the x-coordinate of the second point
     * @param y2 the y-coordinate of the second point
     * @return the Euclidean distance between the two points
     */
    private static double euclideanDistance(
            double x1,
            double y1,
            double x2,
            double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    private Truthness calculateTruthnessForListComparison(List<?> actualList, List<?> expectedList, SqlExpressionEvaluator.ComparisonOperatorType comparisonOperatorType) {
        Objects.requireNonNull(actualList);
        Objects.requireNonNull(expectedList);

        final Truthness truthness;
        if (actualList.size() != expectedList.size()) {
            truthness = C_FALSE;
        } else if (actualList.isEmpty()) {
            // two empty arrays are equal, and there is no element to aggregate over
            truthness = TRUE_C;
        } else {
            Truthness[] arrayOfTruthnesses = new Truthness[actualList.size()];
            for (int i = 0; i < actualList.size(); i++) {
                arrayOfTruthnesses[i] = computeHeuristicComparisonNullableValues(
                        actualList.get(i),
                        expectedList.get(i),
                        SqlExpressionEvaluator.ComparisonOperatorType.EQUALS_TO);
            }
            Truthness unscaledTruthness = buildAndAggregationTruthness(arrayOfTruthnesses);
            truthness = buildSafeScaledTruthness(unscaledTruthness);
        }
        switch (comparisonOperatorType) {
            case EQUALS_TO:
                return truthness;
            case NOT_EQUALS_TO:
                return truthness.invert();
            default:
                throw new IllegalArgumentException("Unsupported binary operator: " + comparisonOperatorType);
        }
    }


}

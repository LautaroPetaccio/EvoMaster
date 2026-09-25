package org.evomaster.client.java.controller.mongo.geometry;

import org.bson.Document;
import org.evomaster.client.java.controller.mongo.MongoHeuristicsCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Properties of the geometry distances as a search heuristic: zero exactly on a match and a
 * gradient toward the matching region.
 */
class GeoJsonDistanceGradientTest {

    private static final String SQUARE = "{type:'Polygon',coordinates:[[[0,0],[10,0],[10,10],[0,10],[0,0]]]}";

    private static GeoJsonPoint point(double x, double y) {
        return new GeoJsonPoint(x, y);
    }

    private static GeoJsonLineString line(GeoJsonPoint... points) {
        return new GeoJsonLineString(Arrays.asList(points));
    }

    private static GeoJsonPolygon square(double x0, double y0, double x1, double y1) {
        return new GeoJsonPolygon(new GeoJsonLineRing(Arrays.asList(
                point(x0, y0), point(x1, y0), point(x1, y1), point(x0, y1), point(x0, y0))),
                Collections.emptySet());
    }

    /** Distance reported by the query calculator for a single {@code loc} value. */
    private static double queryDistance(String query, String location) {
        return new MongoHeuristicsCalculator().computeDistanceDocuments(Document.parse(query),
                Collections.singletonList(Document.parse("{loc:" + location + "}"))).mongoDistance;
    }

    private static String geoPoint(double x, double y) {
        return "{type:'Point',coordinates:[" + x + "," + y + "]}";
    }

    // ---------------------------------------------------------------------------------------------
    // Zero exactly on a match
    // ---------------------------------------------------------------------------------------------

    @Test
    void testContainmentDistanceIsZeroExactlyInsideAcrossSweep() {
        GeoJsonPolygon area = square(0, 0, 10, 10);
        for (int i = -40; i <= 440; i++) {
            double x = i / 40.0;
            boolean inside = x >= 0 && x <= 10;
            double distance = GeoJsonGeometryIntersection.distanceToContainment(point(x, 5), area);
            assertEquals(inside, distance == 0.0, "x=" + x + " distance=" + distance);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Gradient toward the matching region
    // ---------------------------------------------------------------------------------------------

    @Test
    void testCalculatorDistanceDecreasesStrictlyAsPointApproaches() {
        String query = "{loc:{$geoWithin:{$geometry:" + SQUARE + "}}}";
        double previous = Double.MAX_VALUE;
        for (double x = 40; x > 10; x -= 0.5) {
            double distance = queryDistance(query, geoPoint(x, 5));
            assertTrue(distance < previous, "x=" + x + " distance=" + distance + " previous=" + previous);
            previous = distance;
        }
    }

    @Test
    void testMovingFarthestOutsideVertexCloserReducesContainmentDistance() {
        GeoJsonPolygon area = square(0, 0, 10, 10);
        double before = GeoJsonGeometryIntersection.distanceToContainment(
                line(point(20, 5), point(15, 6)), area);
        double after = GeoJsonGeometryIntersection.distanceToContainment(
                line(point(18, 5), point(15, 6)), area);
        assertTrue(after < before, "before=" + before + " after=" + after);
    }

    @Test
    void testMovingEitherOutsideVertexCloserReducesContainmentDistance() {
        GeoJsonPolygon area = square(0, 0, 10, 10);
        double before = GeoJsonGeometryIntersection.distanceToContainment(
                line(point(20, 5), point(20, 6)), area);
        double after = GeoJsonGeometryIntersection.distanceToContainment(
                line(point(15, 5), point(20, 6)), area);
        assertTrue(after < before, "before=" + before + " after=" + after);
    }

    @Test
    void testNegatedContainmentDistanceDecreasesAsPointApproachesBoundary() {
        String query = "{loc:{$not:{$geoWithin:{$geometry:" + SQUARE + "}}}}";
        double center = queryDistance(query, geoPoint(5, 5));
        double nearBoundary = queryDistance(query, geoPoint(9.9, 5));
        assertTrue(nearBoundary < center, "center=" + center + " nearBoundary=" + nearBoundary);
    }

    @Test
    void testNegatedContainmentDistanceIsZeroOutsideControl() {
        String query = "{loc:{$not:{$geoWithin:{$geometry:" + SQUARE + "}}}}";
        assertEquals(0.0, queryDistance(query, geoPoint(20, 5)));
    }

    @Test
    void testDistantGeometryScoresCloserThanNonGeometryValue() {
        String query = "{loc:{$geoWithin:{$geometry:" + SQUARE + "}}}";
        double distantPoint = queryDistance(query, geoPoint(170, 80));
        double nonGeometry = queryDistance(query, "'not a geometry'");
        assertTrue(distantPoint < nonGeometry, "distantPoint=" + distantPoint + " nonGeometry=" + nonGeometry);
    }

    @Test
    void testNearbyGeometryScoresCloserThanNonGeometryValueControl() {
        String query = "{loc:{$geoWithin:{$geometry:" + SQUARE + "}}}";
        double nearbyPoint = queryDistance(query, geoPoint(12, 5));
        double nonGeometry = queryDistance(query, "'not a geometry'");
        assertTrue(nearbyPoint < nonGeometry, "nearbyPoint=" + nearbyPoint + " nonGeometry=" + nonGeometry);
    }
}

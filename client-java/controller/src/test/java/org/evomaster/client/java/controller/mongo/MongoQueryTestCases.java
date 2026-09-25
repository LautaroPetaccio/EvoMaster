package org.evomaster.client.java.controller.mongo;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Shared BSON snapshots for the calculator tests and the independent live MongoDB oracle. */
public final class MongoQueryTestCases {
    private MongoQueryTestCases() {
    }

    public static Stream<Case> scenarios() {
        return Stream.of(geometryCases(), geometryPredicateCases(), geometryQueryFormCases(),
                storedGeometryCases(), queryCases(), bsonCases()).flatMap(cases -> cases);
    }

    private static Stream<Case> geometryCases() {
        List<Case> cases = new ArrayList<>();
        Document square = geometry("Polygon", "[[[0,0],[10,0],[10,10],[0,10],[0,0]]]");
        Document donut = geometry("Polygon",
                "[[[0,0],[10,0],[10,10],[0,10],[0,0]],[[4,4],[4,6],[6,6],[6,4],[4,4]]]");
        Document inside = geometry("Point", "[1,1]");
        Document outside = geometry("Point", "[20,20]");

        add(cases, "line crosses polygon hole", geo("$geoWithin", donut),
                geometry("LineString", "[[2,5],[8,5]]"), false);
        add(cases, "polygon covers excluded hole", geo("$geoWithin", donut),
                geometry("Polygon", "[[[2,2],[8,2],[8,8],[2,8],[2,2]]]"), false);
        add(cases, "line stays in filled area", geo("$geoWithin", donut),
                geometry("LineString", "[[1,1],[3,1]]"), true);
        add(cases, "point lies in excluded hole", geo("$geoWithin", donut),
                geometry("Point", "[5,5]"), false);
        Document islands = geometry("MultiPolygon",
                "[[[[0,0],[2,0],[2,2],[0,2],[0,0]]],[[[4,0],[6,0],[6,2],[4,2],[4,0]]]]");
        add(cases, "line crosses gap between multipolygon members", geo("$geoWithin", islands),
                geometry("LineString", "[[1,1],[5,1]]"), false);
        add(cases, "disconnected points can occupy separate multipolygon members", geo("$geoWithin", islands),
                geometry("MultiPoint", "[[1,1],[5,1]]"), true);

        String[] operators = {"$box", "$polygon", "$center", "$centerSphere"};
        String[] shapes = {"[[0,0],[2,2]]", "[[0,0],[2,0],[2,2],[0,2]]",
                "[[1,1],1]", "[[1,1],0.01]"};
        for (int i = 0; i < operators.length; i++) {
            add(cases, operators[i] + " matches legacy coordinate pair", legacy(operators[i], shapes[i]),
                    Arrays.asList(1, 1), true);
            add(cases, operators[i] + " rejects outside legacy coordinate pair", legacy(operators[i], shapes[i]),
                    Arrays.asList(20, 20), false);
        }
        // MongoDB 7 accepts this representation too, despite narrower wording in its manual.
        add(cases, "box matches GeoJSON point control", legacy("$box", shapes[0]), inside, true);

        Document elevatedSquare = geometry("Polygon",
                "[[[0,0,100],[10,0,100],[10,10,100],[0,10,100],[0,0,100]]]");
        add(cases, "polygon query includes altitude", geo("$geoWithin", elevatedSquare), inside, true);
        add(cases, "line query includes altitude", geo("$geoIntersects",
                geometry("LineString", "[[0,0,100],[1,0,100]]")), geometry("Point", "[0,0]"), true);
        add(cases, "stored polygon includes altitude", geo("$geoWithin", square),
                geometry("Polygon", "[[[1,1,100],[2,1,100],[1,2,100],[1,1,100]]]"), true);

        Document endpointLine = geometry("LineString", "[[0.7,0],[0.1,0]]");
        add(cases, "exact line endpoint must not throw", geo("$geoIntersects", endpointLine),
                geometry("Point", "[0.1,0]"), true);
        add(cases, "line starting point control", geo("$geoIntersects", endpointLine),
                geometry("Point", "[0.7,0]"), true);
        add(cases, "point away from line control", geo("$geoIntersects", endpointLine),
                geometry("Point", "[0.1,1]"), false);

        for (String operator : new String[]{"$geoWithin", "$geoIntersects"}) {
            Document query = geo(operator, square);
            add(cases, operator + " matches single geometry array", query, Arrays.asList(inside), true);
            add(cases, operator + " matches any geometry in array", query, Arrays.asList(inside, outside), true);
            add(cases, operator + " rejects array of outside geometries", query, Arrays.asList(outside), false);
            add(cases, operator + " negation rejects matching geometry array",
                    new Document("loc", new Document("$not", query.get("loc"))), Arrays.asList(inside), false);
            add(cases, operator + " does not recursively flatten nested geometry arrays", query,
                    Arrays.asList(Arrays.asList(inside)), false);
            add(cases, operator + " respects MultiPoint semantics", query,
                    geometry("MultiPoint", "[[1,1],[20,20]]"), operator.equals("$geoIntersects"));
            add(cases, operator + " respects GeometryCollection semantics", query,
                    new Document("type", "GeometryCollection").append("geometries", Arrays.asList(inside, outside)),
                    operator.equals("$geoIntersects"));
        }

        for (String crs : new String[]{"urn:ogc:def:crs:OGC:1.3:CRS84", "EPSG:4326"}) {
            Document declaredSquare = Document.parse(square.toJson()).append("crs",
                    new Document("type", "name").append("properties", new Document("name", crs)));
            for (String operator : new String[]{"$geoWithin", "$geoIntersects"}) {
                add(cases, operator + " accepts explicit default CRS " + crs, geo(operator, declaredSquare), inside, true);
            }
        }

        for (String operator : new String[]{"$center", "$centerSphere"}) {
            add(cases, operator + " zero radius matches center", legacy(operator, "[[1,1],0]"), inside, true);
            add(cases, operator + " zero radius excludes other points", legacy(operator, "[[1,1],0]"), outside, false);
        }

        add(cases, "zero width box matches point on segment", legacy("$box", "[[0,0],[0,1]]"),
                geometry("Point", "[0,0.5]"), true);
        add(cases, "zero width box excludes point off segment", legacy("$box", "[[0,0],[0,1]]"), inside, false);
        add(cases, "point box matches identical point", legacy("$box", "[[0,0],[0,0]]"),
                geometry("Point", "[0,0]"), true);
        add(cases, "point box excludes another point", legacy("$box", "[[0,0],[0,0]]"), inside, false);

        add(cases, "ring closure treats signed zero as same longitude", geo("$geoWithin",
                geometry("Polygon", "[[[0.0,0.0],[2,0],[0,2],[-0.0,0.0]]]")),
                geometry("Point", "[0.5,0.5]"), true);
        add(cases, "ring closure treats signed zero as same latitude", geo("$geoWithin",
                geometry("Polygon", "[[[0.0,0.0],[2,0],[0,2],[0.0,-0.0]]]")),
                geometry("Point", "[0.5,0.5]"), true);

        String[] objectPositions = {"[{x:0,y:0},{x:2,y:2}]",
                "[{x:0,y:0},{x:2,y:0},{x:2,y:2},{x:0,y:2}]",
                "[{x:1,y:1},1]", "[{x:1,y:1},0.01]"};
        String[] objectShapes = {"{a:[0,0],b:[2,2]}",
                "{a:[0,0],b:[2,0],c:[2,2],d:[0,2]}",
                "{a:[1,1],b:1}", "{a:[1,1],b:0.01}"};
        for (int i = 0; i < operators.length; i++) {
            add(cases, operators[i] + " accepts ordered document coordinates", legacy(operators[i], objectPositions[i]),
                    inside, true);
            add(cases, operators[i] + " accepts ordered document shape", legacy(operators[i], objectShapes[i]), inside, true);
        }

        // Flat legacy regions only match points. Converting every region to the same
        // polygon representation must not lose this restriction; spherical regions
        // can contain these non-point geometries.
        Document[] nonPointGeometries = {
                geometry("LineString", "[[4,4],[6,6]]"),
                geometry("Polygon", "[[[4,4],[6,4],[5,6],[4,4]]]"),
                geometry("MultiPoint", "[[4,4],[6,6]]")
        };
        String[] flatOperators = {"$box", "$polygon", "$center"};
        String[] flatShapes = {"[[0,0],[10,10]]", "[[0,0],[10,0],[10,10],[0,10]]", "[[5,5],4]"};
        for (int i = 0; i < flatOperators.length; i++) {
            for (Document nonPointGeometry : nonPointGeometries) {
                add(cases, flatOperators[i] + " excludes stored " + nonPointGeometry.getString("type"),
                        legacy(flatOperators[i], flatShapes[i]), nonPointGeometry, false);
            }
        }
        for (Document nonPointGeometry : nonPointGeometries) {
            add(cases, "centerSphere contains stored " + nonPointGeometry.getString("type") + " control",
                    legacy("$centerSphere", "[[5,5],0.1]"), nonPointGeometry, true);
        }
        // The matching GeoJSON Point control for $box is already covered above.
        for (int i = 1; i < flatOperators.length; i++) {
            add(cases, flatOperators[i] + " matches stored Point control",
                    legacy(flatOperators[i], flatShapes[i]), geometry("Point", "[5,5]"), true);
        }
        add(cases, "tiny segment midpoint remains an intersection",
                geo("$geoIntersects", geometry("LineString", "[[0,0],[1e-170,0]]")),
                geometry("Point", "[5e-171,0]"), true);
        return cases.stream();
    }

    private static Stream<Case> geometryPredicateCases() {
        List<Case> cases = new ArrayList<>();

        Document highLatitudeBox = geometry("Polygon", "[[[-10,50],[10,50],[10,60],[-10,60],[-10,50]]]");
        add(cases, "polygon edge between parallel vertices bulges toward the pole", geo("$geoWithin", highLatitudeBox),
                geometry("Point", "[0,60.3]"), true);
        add(cases, "point just north of southern parallel vertices lies outside polygon", geo("$geoWithin", highLatitudeBox),
                geometry("Point", "[0,50.1]"), false);
        add(cases, "point beyond northern edge bulge lies outside polygon control", geo("$geoWithin", highLatitudeBox),
                geometry("Point", "[0,61]"), false);
        add(cases, "line crossing only the bulge of a high latitude edge intersects it",
                geo("$geoIntersects", geometry("LineString", "[[-10,60],[10,60]]")),
                geometry("LineString", "[[0,60.2],[0,60.5]]"), true);

        Document antimeridianLine = geometry("LineString", "[[179,0],[-179,0]]");
        add(cases, "short antimeridian line does not reach the prime meridian", geo("$geoIntersects", antimeridianLine),
                geometry("Point", "[0,0]"), false);
        add(cases, "short antimeridian line crosses the 180th meridian", geo("$geoIntersects", antimeridianLine),
                geometry("LineString", "[[180,-1],[180,1]]"), true);
        add(cases, "antimeridian polygon contains point on the 180th meridian", geo("$geoWithin",
                geometry("Polygon", "[[[179,-1],[-179,-1],[-179,1],[179,1],[179,-1]]]")),
                geometry("Point", "[180,0]"), true);

        Document polarCap = geometry("Polygon", "[[[0,80],[90,80],[180,80],[-90,80],[0,80]]]");
        add(cases, "ring around the pole contains nearby polar point", geo("$geoWithin", polarCap),
                geometry("Point", "[45,85]"), true);
        add(cases, "ring around the pole excludes lower latitude point control", geo("$geoWithin", polarCap),
                geometry("Point", "[45,75]"), false);

        Document unitSquare = geometry("Polygon", "[[[0,0],[1,0],[1,1],[0,1],[0,0]]]");
        add(cases, "polygons sharing only a corner do not intersect", geo("$geoIntersects", unitSquare),
                geometry("Polygon", "[[[1,1],[2,1],[2,2],[1,2],[1,1]]]"), false);
        add(cases, "line touching polygon only at a corner does not intersect", geo("$geoIntersects", unitSquare),
                geometry("LineString", "[[1,1],[2,2]]"), false);
        add(cases, "point on polygon corner intersects control", geo("$geoIntersects", unitSquare),
                geometry("Point", "[1,1]"), true);
        return cases.stream();
    }

    private static Stream<Case> geometryQueryFormCases() {
        List<Case> cases = new ArrayList<>();
        Document square = geometry("Polygon", "[[[0,0],[10,0],[10,10],[0,10],[0,0]]]");
        Document inside = geometry("Point", "[1,1]");
        Document outside = geometry("Point", "[20,20]");

        Document strictWinding = Document.parse("{type:'Polygon',coordinates:[[[0,0],[0,10],[10,10],[10,0],[0,0]]],"
                + "crs:{type:'name',properties:{name:'urn:x-mongodb:crs:strictwinding:EPSG:4326'}}}");
        add(cases, "strict winding clockwise ring contains its exterior", geo("$geoWithin", strictWinding), outside, true);
        add(cases, "strict winding clockwise ring excludes enclosed point", geo("$geoWithin", strictWinding),
                geometry("Point", "[5,5]"), false);

        add(cases, "$geoIntersects accepts Point query geometry", geo("$geoIntersects", inside), square, true);
        add(cases, "$geoIntersects Point query rejects disjoint polygon", geo("$geoIntersects", outside), square, false);
        add(cases, "$geoIntersects accepts legacy pair query geometry",
                new Document("loc", new Document("$geoIntersects", new Document("$geometry", Arrays.asList(1, 1)))),
                inside, true);

        add(cases, "center accepts point between approximating vertices", legacy("$center", "[[0,0],10]"),
                geometry("Point", "[9.9319,0.9782]"), true);
        add(cases, "centerSphere radius is a great circle distance at high latitude",
                legacy("$centerSphere", "[[0,60],0.1]"), geometry("Point", "[10,60]"), true);
        add(cases, "centerSphere excludes point beyond great circle radius control",
                legacy("$centerSphere", "[[0,60],0.1]"), geometry("Point", "[13,60]"), false);
        add(cases, "center crossing the antimeridian keeps its interior", legacy("$center", "[[179,0],2]"),
                geometry("Point", "[179.5,0]"), true);
        add(cases, "box with planar coordinates beyond longitude range", legacy("$box", "[[0,0],[300,80]]"),
                geometry("Point", "[100,10]"), true);
        add(cases, "reversed box corners describe the same box control", legacy("$box", "[[2,2],[0,0]]"), inside, true);
        add(cases, "meridian and equator edges include boundary point control", geo("$geoWithin", square),
                geometry("Point", "[10,5]"), true);

        cases.add(new Case("geoWithin inside nor accepts non-geometry value control",
                new Document("$nor", Arrays.asList(geo("$geoWithin", square))), new Document("loc", "abc"), true));
        cases.add(new Case("geoWithin inside elemMatch matches any subdocument control",
                Document.parse("{a:{$elemMatch:" + geo("$geoWithin", square).toJson() + "}}"),
                new Document("a", Arrays.asList(new Document("loc", outside), new Document("loc", inside))), true));
        return cases.stream();
    }

    private static Stream<Case> storedGeometryCases() {
        List<Case> cases = new ArrayList<>();
        Document square = geometry("Polygon", "[[[0,0],[10,0],[10,10],[0,10],[0,0]]]");
        Document point = geometry("Point", "[1,1]");

        add(cases, "$geoIntersects matches stored legacy coordinate pair", geo("$geoIntersects", square),
                Arrays.asList(1, 1), true);
        add(cases, "$geoIntersects matches stored legacy coordinate document", geo("$geoIntersects", square),
                Document.parse("{lng:1,lat:1}"), true);
        add(cases, "$geoWithin geometry matches stored legacy coordinate pair", geo("$geoWithin", square),
                Arrays.asList(1, 1), true);
        add(cases, "$geoIntersects rejects outside stored legacy coordinate pair control", geo("$geoIntersects", square),
                Arrays.asList(20, 20), false);

        add(cases, "stored Point accepts altitude", geo("$geoWithin", square), geometry("Point", "[1,1,5]"), true);
        add(cases, "stored nested GeometryCollection is not a geometry", geo("$geoIntersects", square),
                new Document("type", "GeometryCollection").append("geometries", Arrays.asList(
                        new Document("type", "GeometryCollection").append("geometries", Arrays.asList(point)))),
                false);

        add(cases, "stored self-intersecting ring is not a geometry", geo("$geoIntersects", square),
                geometry("Polygon", "[[[1,1],[3,3],[3,1],[1,3],[1,1]]]"), false);
        add(cases, "stored ring with repeated vertex is not a geometry", geo("$geoIntersects", square),
                geometry("Polygon", "[[[1,1],[3,1],[2,2],[3,3],[1,3],[2,2],[1,1]]]"), false);
        add(cases, "stored hole outside shell is not a geometry", geo("$geoIntersects", square),
                geometry("Polygon", "[[[1,1],[3,1],[3,3],[1,3],[1,1]],[[5,5],[6,5],[6,6],[5,6],[5,5]]]"), false);
        add(cases, "stored hole crossing shell is not a geometry", geo("$geoIntersects", square),
                geometry("Polygon", "[[[1,1],[3,1],[3,3],[1,3],[1,1]],[[2,2],[4,2],[4,4],[2,4],[2,2]]]"), false);
        add(cases, "stored hole inside shell control", geo("$geoIntersects", square),
                geometry("Polygon", "[[[1,1],[5,1],[5,5],[1,5],[1,1]],[[2,2],[3,2],[3,3],[2,3],[2,2]]]"), true);

        add(cases, "document without type never matches geoWithin control", geo("$geoWithin", square),
                Document.parse("{coordinates:[1,1]}"), false);
        cases.add(new Case("missing field never matches geoWithin control", geo("$geoWithin", square),
                new Document("other", 1), false));
        return cases.stream();
    }

    private static void add(List<Case> cases, String name, Document query, Object location, boolean matches) {
        cases.add(new Case(name, query, new Document("loc", location), matches));
    }

    private static Document geometry(String type, String coordinates) {
        return Document.parse("{type:'" + type + "',coordinates:" + coordinates + "}");
    }

    public static Document geo(String operator, Document geometry) {
        return new Document("loc", new Document(operator, new Document("$geometry", geometry)));
    }

    private static Document legacy(String operator, String shape) {
        return Document.parse("{loc:{$geoWithin:{" + operator + ":" + shape + "}}}");
    }

    /** The performance test and live oracle intentionally use exactly the same detailed lines. */
    public static Document detailedLine(int size, double latitude) {
        List<List<Double>> coordinates = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            coordinates.add(Arrays.asList(10.0 * i / size, latitude));
        }
        return new Document("type", "LineString").append("coordinates", coordinates);
    }

    private static Stream<Case> queryCases() {
        List<Case> cases = new ArrayList<>();
        add(cases, "logical operator must retain sibling filter",
                "{$or:[{b:1},{b:2}],b:2}", "{b:1}", false);
        add(cases, "sibling filter before logical operator is valid",
                "{b:1,$or:[{b:1},{b:2}]}", "{b:1}", true);
        add(cases, "standalone logical operator control",
                "{$or:[{b:1},{b:2}]}", "{b:1}", true);

        add(cases, "elemMatch supports type operator", "{a:{$elemMatch:{$type:'number'}}}", "{a:[1,2]}", true);
        add(cases, "elemMatch supports exists operator", "{a:{$elemMatch:{$exists:true}}}", "{a:[1,2]}", true);
        add(cases, "elemMatch must not split range across nested array elements",
                "{a:{$elemMatch:{$gt:2,$lt:5}}}", "{a:[[1,6]]}", false);
        add(cases, "elemMatch scalar equality must not unwrap a nested array",
                "{a:{$elemMatch:{$eq:1}}}", "{a:[[1,2]]}", false);
        add(cases, "elemMatch negation compares the nested array itself",
                "{a:{$elemMatch:{$not:{$eq:1}}}}", "{a:[[1,2]]}", true);
        add(cases, "empty elemMatch accepts an array element", "{a:{$elemMatch:{}}}", "{a:[[]]}", true);
        add(cases, "elemMatch supports numeric array index", "{a:{$elemMatch:{'0':1}}}", "{a:[[1,2]]}", true);
        add(cases, "elemMatch scalar range control", "{a:{$elemMatch:{$gt:2,$lt:5}}}", "{a:[3,6]}", true);
        add(cases, "not equals must exclude matching nested array", "{a:{$ne:[1,2]}}", "{a:[[1,2],3]}", false);
        add(cases, "not equals different nested array control", "{a:{$ne:[1,2]}}", "{a:[[1,3],3]}", true);

        add(cases, "type query inspects immediate array elements", "{a:{$type:'int'}}", "{a:[1,2]}", true);
        add(cases, "type array still matches the array itself", "{a:{$type:'array'}}", "{a:[1,2]}", true);
        add(cases, "bitwise query accepts binary stored value", "{a:{$bitsAllSet:1}}",
                "{a:{$binary:{'base64':'AQ==','subType':'00'}}}", true);
        add(cases, "bitwise integer control", "{a:{$bitsAllSet:1}}", "{a:1}", true);
        add(cases, "modulo rejects NaN instead of converting it to zero", "{a:{$mod:[2,0]}}",
                "{a:{$numberDouble:'NaN'}}", false);
        add(cases, "modulo integer control", "{a:{$mod:[2,0]}}", "{a:4}", true);
        add(cases, "bitwise double must fit signed 64 bit range", "{a:{$bitsAllSet:1}}",
                "{a:{$numberDouble:'9.223372036854776E18'}}", false);
        add(cases, "distinct int64 values above double precision stay unequal",
                "{a:{$eq:{$numberLong:'9007199254740992'}}}", "{a:{$numberLong:'9007199254740993'}}", false);
        add(cases, "identical int64 precision control",
                "{a:{$eq:{$numberLong:'9007199254740993'}}}", "{a:{$numberLong:'9007199254740993'}}", true);
        add(cases, "timestamp seconds use unsigned ordering",
                "{a:{$gt:{$timestamp:{'t':2147483647,'i':0}}}}", "{a:{$timestamp:{'t':2147483648,'i':0}}}", true);
        add(cases, "timestamp increments remain distinct",
                "{a:{$eq:{$timestamp:{'t':1700000000,'i':0}}}}", "{a:{$timestamp:{'t':1700000000,'i':1}}}", false);

        add(cases, "document inequality does not require unequal field names",
                "{a:{$ne:{x:1}}}", "{a:{x:2}}", true);
        add(cases, "strict document greater than compares values after equal keys",
                "{a:{$gt:{x:1}}}", "{a:{x:2}}", true);
        add(cases, "strict document less than compares values after equal keys",
                "{a:{$lt:{x:2}}}", "{a:{x:1}}", true);
        add(cases, "numeric BSON representation must not skip remaining fields",
                "{a:{$eq:{x:1,y:2}}}", "{a:{x:1.0,y:99}}", false);
        add(cases, "numeric BSON representation must not skip extra fields",
                "{a:{$eq:{x:1}}}", "{a:{x:1.0,y:99}}", false);
        add(cases, "equivalent document numeric representation control",
                "{a:{$eq:{x:1,y:2}}}", "{a:{x:1.0,y:2}}", true);
        add(cases, "binary equality includes subtype",
                "{a:{$eq:{$binary:{'base64':'AQI=','subType':'00'}}}}",
                "{a:{$binary:{'base64':'AQI=','subType':'80'}}}", false);
        add(cases, "identical binary subtype control",
                "{a:{$eq:{$binary:{'base64':'AQI=','subType':'80'}}}}",
                "{a:{$binary:{'base64':'AQI=','subType':'80'}}}", true);

        add(cases, "regex ignores nonstring elements of a mixed array", "{v:{$regex:'foo'}}", "{v:[1,'foo']}", true);
        add(cases, "in regex preserves case insensitive options",
                "{v:{$in:[{$regularExpression:{'pattern':'^abc$','options':'i'}}]}}", "{v:'ABC'}", true);
        add(cases, "in regex does not stringify numeric values",
                "{v:{$in:[{$regularExpression:{'pattern':'^123$','options':''}}]}}", "{v:123}", false);
        add(cases, "in regex safely rejects null",
                "{v:{$in:[{$regularExpression:{'pattern':'foo','options':''}}]}}", "{v:null}", false);
        add(cases, "in numeric literal control", "{v:{$in:[123]}}", "{v:123}", true);
        add(cases, "all regex matches array strings",
                "{v:{$all:[{$regularExpression:{'pattern':'foo','options':''}}]}}", "{v:['foobar']}", true);
        add(cases, "all regex matches a scalar string",
                "{v:{$all:[{$regularExpression:{'pattern':'foo','options':''}}]}}", "{v:'foobar'}", true);
        add(cases, "extended regex preserves space inside character class",
                "{v:{$regex:'^[ a]$',$options:'x'}}", "{v:' '}", true);
        add(cases, "default regex dot matches carriage return", "{v:{$regex:'^.$'}}", "{v:'\\r'}", true);
        add(cases, "default regex end anchor does not ignore carriage return",
                "{v:{$regex:'^foo$'}}", "{v:'foo\\r'}", false);
        add(cases, "literal regex query can match stored regex",
                "{v:{$regularExpression:{'pattern':'foo','options':'i'}}}",
                "{v:{$regularExpression:{'pattern':'foo','options':'i'}}}", true);
        add(cases, "stored regex equality includes options",
                "{v:{$eq:{$regularExpression:{'pattern':'foo','options':''}}}}",
                "{v:{$regularExpression:{'pattern':'foo','options':'i'}}}", false);
        add(cases, "BSON string ordering follows UTF8 rather than UTF16",
                "{v:{$gt:'\\uE000'}}", "{v:'\\uD800\\uDC00'}", true);

        cases.add(new Case("legacy near accepts legacy stored point",
                Document.parse("{loc:{$near:[0,0],$maxDistance:1}}"), Document.parse("{loc:[0,0]}"), true,
                new Document("loc", "2d")));
        cases.add(new Case("legacy near excludes distant point control",
                Document.parse("{loc:{$near:[0,0],$maxDistance:1}}"), Document.parse("{loc:[2,2]}"), false,
                new Document("loc", "2d")));

        String[] nearbyGeometryTypes = {"Point", "LineString", "MultiPoint", "Polygon"};
        String[] nearbyGeometryCoordinates = {"[0,0]", "[[0,0],[1,1]]", "[[0,0],[1,1]]",
                "[[[0,0],[1,0],[0,1],[0,0]]]"};
        for (String operator : new String[]{"$near", "$nearSphere"}) {
            Document proximityQuery = Document.parse("{loc:{" + operator
                    + ":{$geometry:{type:'Point',coordinates:[0,0]},$maxDistance:1}}}");
            for (int i = 0; i < nearbyGeometryTypes.length; i++) {
                cases.add(new Case(operator + " matches nearby stored " + nearbyGeometryTypes[i],
                        proximityQuery, Document.parse("{loc:{type:'" + nearbyGeometryTypes[i]
                                + "',coordinates:" + nearbyGeometryCoordinates[i] + "}}"),
                        true, new Document("loc", "2dsphere")));
            }
            cases.add(new Case(operator + " rejects distant LineString control", proximityQuery,
                    Document.parse("{loc:{type:'LineString',coordinates:[[1,1],[2,2]]}}"),
                    false, new Document("loc", "2dsphere")));
            for (int maxDistance : new int[]{20000000, 21000000}) {
                cases.add(new Case(operator + " antipodal point with maximum distance " + maxDistance,
                        Document.parse("{loc:{" + operator
                                + ":{$geometry:{type:'Point',coordinates:[0,8]},$maxDistance:"
                                + maxDistance + "}}}"),
                        Document.parse("{loc:{type:'Point',coordinates:[180,-8]}}"),
                        maxDistance == 21000000, new Document("loc", "2dsphere")));
            }
        }

        add(cases, "implicit empty document equality", "{a:{}}", "{a:{}}", true);
        add(cases, "explicit empty document equality control", "{a:{$eq:{}}}", "{a:{}}", true);
        add(cases, "literal plural comments field must be preserved",
                "{a:{$eq:{$comments:'x',v:1}}}", "{a:{$comments:'x',v:1}}", true);
        add(cases, "removing literal plural comments changes equality",
                "{a:{$eq:{$comments:'x',v:1}}}", "{a:{v:1}}", false);

        add(cases, "numeric high bits are sign extended for negative values",
                "{a:{$bitsAllSet:[64]}}", "{a:-1}", true);
        add(cases, "numeric high bits are clear for positive values",
                "{a:{$bitsAllClear:[64]}}", "{a:1}", true);
        add(cases, "positive numeric high bit is not set", "{a:{$bitsAllSet:[64]}}", "{a:1}", false);
        add(cases, "negative numeric high bit is not clear", "{a:{$bitsAllClear:[64]}}", "{a:-1}", false);
        add(cases, "MinKey equality", "{a:{$minKey:1}}", "{a:{$minKey:1}}", true);
        add(cases, "MaxKey sorts above an ordinary number", "{a:{$lt:{$maxKey:1}}}", "{a:1}", true);
        add(cases, "MinKey and MaxKey remain distinct", "{a:{$eq:{$minKey:1}}}", "{a:{$maxKey:1}}", false);
        add(cases, "bitwise decimal integrality must not be rounded through double",
                "{a:{$bitsAllSet:1}}", "{a:{$numberDecimal:'1.0000000000000000000000001'}}", false);
        add(cases, "integral decimal bitwise control", "{a:{$bitsAllSet:1}}", "{a:{$numberDecimal:'1'}}", true);

        add(cases, "size accepts an integral double", "{a:{$size:2.0}}", "{a:[1,2]}", true);
        add(cases, "size accepts an integral Int64", "{a:{$size:{$numberLong:'2'}}}", "{a:[1,2]}", true);
        add(cases, "size accepts an integral decimal", "{a:{$size:{$numberDecimal:'2'}}}", "{a:[1,2]}", true);
        add(cases, "size Int32 control", "{a:{$size:2}}", "{a:[1,2]}", true);
        add(cases, "fixture preserves the type of a small Int64", "{a:{$type:'long'}}",
                "{a:{$numberLong:'2'}}", true);

        add(cases, "array greater than uses array ordering", "{a:{$gt:[1]}}", "{a:[2]}", true);
        add(cases, "array less than uses array ordering", "{a:{$lt:[2]}}", "{a:[1]}", true);
        add(cases, "nested array ordering does not throw", "{a:{$gt:[1]}}", "{a:[[2]]}", true);
        add(cases, "empty array satisfies inclusive array comparison", "{a:{$lte:[]}}", "{a:[]}", true);
        add(cases, "array greater than negative control", "{a:{$gt:[2]}}", "{a:[1]}", false);
        add(cases, "ne does not execute stored array regex as a predicate", "{a:{$ne:'foobar'}}",
                "{a:[{$regularExpression:{'pattern':'foo','options':''}}]}", true);
        add(cases, "ne null safely compares a stored array regex", "{a:{$ne:null}}",
                "{a:[{$regularExpression:{'pattern':'foo','options':''}}]}", true);
        add(cases, "eq stored array regex and string control", "{a:{$eq:'foobar'}}",
                "{a:[{$regularExpression:{'pattern':'foo','options':''}}]}", false);
        add(cases, "nin stored array regex and string control", "{a:{$nin:['foobar']}}",
                "{a:[{$regularExpression:{'pattern':'foo','options':''}}]}", true);

        add(cases, "positive infinity equals itself", "{v:{$eq:{$numberDouble:'Infinity'}}}",
                "{v:{$numberDouble:'Infinity'}}", true);
        add(cases, "negative infinity equals itself", "{v:{$eq:{$numberDouble:'-Infinity'}}}",
                "{v:{$numberDouble:'-Infinity'}}", true);
        add(cases, "NaN satisfies inclusive less than itself", "{v:{$lte:{$numberDouble:'NaN'}}}",
                "{v:{$numberDouble:'NaN'}}", true);
        add(cases, "NaN satisfies inclusive greater than itself", "{v:{$gte:{$numberDouble:'NaN'}}}",
                "{v:{$numberDouble:'NaN'}}", true);
        add(cases, "NaN equality control", "{v:{$eq:{$numberDouble:'NaN'}}}",
                "{v:{$numberDouble:'NaN'}}", true);
        add(cases, "NaN strict comparison control", "{v:{$lt:{$numberDouble:'NaN'}}}",
                "{v:{$numberDouble:'NaN'}}", false);
        add(cases, "near zero distinct double comparison must not throw", "{v:{$eq:0}}", "{v:1e-17}", false);
        add(cases, "zero double equality control", "{v:{$eq:0}}", "{v:0.0}", true);
        return cases.stream();
    }

    private static void add(List<Case> cases, String name, String query, String document, boolean matches) {
        cases.add(new Case(name, Document.parse(query), Document.parse(document), matches));
    }

    private static Stream<Case> bsonCases() {
        List<Case> cases = new ArrayList<>();
        add(cases, "ObjectId greater than supports range queries",
                "{a:{$gt:{$oid:'000000000000000000000001'}}}",
                "{a:{$oid:'000000000000000000000002'}}", true);
        add(cases, "ObjectId less than uses unsigned byte order",
                "{a:{$lt:{$oid:'800000000000000000000000'}}}",
                "{a:{$oid:'7fffffffffffffffffffffff'}}", true);
        add(cases, "ObjectId inclusive comparison accepts equality",
                "{a:{$lte:{$oid:'000000000000000000000001'}}}",
                "{a:{$oid:'000000000000000000000001'}}", true);
        add(cases, "ObjectId nonmatching range must not throw",
                "{a:{$gt:{$oid:'000000000000000000000002'}}}",
                "{a:{$oid:'000000000000000000000001'}}", false);
        add(cases, "ObjectId equality control",
                "{a:{$eq:{$oid:'000000000000000000000001'}}}",
                "{a:{$oid:'000000000000000000000001'}}", true);
        add(cases, "distinct ObjectId equality control",
                "{a:{$eq:{$oid:'000000000000000000000001'}}}",
                "{a:{$oid:'000000000000000000000002'}}", false);

        add(cases, "binary greater than supports ordered bytes",
                "{a:{$gt:{$binary:{'base64':'AQ==','subType':'00'}}}}",
                "{a:{$binary:{'base64':'Ag==','subType':'00'}}}", true);
        add(cases, "binary ordering compares length before bytes",
                "{a:{$gt:{$binary:{'base64':'/w==','subType':'00'}}}}",
                "{a:{$binary:{'base64':'AAA=','subType':'00'}}}", true);

        add(cases, "stored BSON symbol equals its string value", "{a:{$eq:'hello'}}",
                "{a:{$symbol:'hello'}}", true);
        add(cases, "different BSON symbol and string control", "{a:{$eq:'other'}}",
                "{a:{$symbol:'hello'}}", false);
        add(cases, "stored BSON JavaScript equality", "{a:{$eq:{$code:'return 1'}}}",
                "{a:{$code:'return 1'}}", true);
        add(cases, "different stored BSON JavaScript inequality control", "{a:{$ne:{$code:'return 2'}}}",
                "{a:{$code:'return 1'}}", true);
        add(cases, "stored BSON JavaScript with scope equality",
                "{a:{$eq:{$code:'return x','$scope':{x:1}}}}", "{a:{$code:'return x','$scope':{x:1}}}", true);
        add(cases, "stored BSON JavaScript different scope control",
                "{a:{$eq:{$code:'return x','$scope':{x:2}}}}", "{a:{$code:'return x','$scope':{x:1}}}", false);

        add(cases, "maximum BSON date equality control",
                "{a:{$eq:{$date:{$numberLong:'9223372036854775807'}}}}",
                "{a:{$date:{$numberLong:'9223372036854775807'}}}", true);
        add(cases, "extreme BSON date ordering control",
                "{a:{$gt:{$date:{$numberLong:'-9223372036854775808'}}}}",
                "{a:{$date:{$numberLong:'9223372036854775807'}}}", true);
        return cases.stream();
    }


    /** Independent snapshots preserve BSON types and prevent insertion from mutating a fixture. */
    public static final class Case {
        private static final JsonWriterSettings SNAPSHOT_FORMAT = JsonWriterSettings.builder()
                .outputMode(JsonMode.EXTENDED).build();
        private final String name;
        private final String query;
        private final String document;
        private final String index;
        public final boolean matches;

        Case(String name, Document query, Document document, boolean matches) {
            this(name, query, document, matches, null);
        }

        Case(String name, Document query, Document document, boolean matches, Document index) {
            this.name = name;
            // Relaxed JSON would silently turn a small Int64 into Int32 on the next parse.
            this.query = query.toJson(SNAPSHOT_FORMAT);
            this.document = document.toJson(SNAPSHOT_FORMAT);
            this.matches = matches;
            this.index = index == null ? null : index.toJson(SNAPSHOT_FORMAT);
        }

        public Document query() {
            return Document.parse(query);
        }

        public Document document() {
            return Document.parse(document);
        }

        public Document index() {
            return index == null ? null : Document.parse(index);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

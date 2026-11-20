
package com.travelplanner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocationValidationService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, LocationResult> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DestinationArea> boundsCache = new ConcurrentHashMap<>();

    private static final Object RATE_LIMIT_LOCK = new Object();
    private static long lastRequestTime = 0;

    private static final Logger logger = LoggerFactory.getLogger(LocationValidationService.class);

    private static final double MAX_NEARBY_DISTANCE_KM = 40.0;

    public LocationValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // Validate an attraction against the destination boundary and locality.
    public LocationResult validatePlace(String placeName, String destination) {

        String cacheKey = (placeName + "|" + destination)
                .toLowerCase(Locale.ROOT).trim();

        LocationResult cachedResult = cache.get(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }

        try {
            DestinationArea area = getDestinationArea(destination);

            if (area == null) {
                return new LocationResult(
                        "UNKNOWN", "",
                        "Could not find destination boundary.");
            }

            if (area.geometry() == null) {
                return new LocationResult(
                        "UNKNOWN", "",
                        "An exact destination boundary polygon was not available. Manual verification is recommended.");
            }

            String cleanName = cleanPlaceName(placeName);

            if (cleanName.isBlank()) {
                return new LocationResult(
                        "UNKNOWN", "",
                        "Attraction name is empty.");
            }

            Set<String> searchQueries = new LinkedHashSet<>();
            searchQueries.add(cleanName + ", " + destination);
            searchQueries.add(cleanName + " in " + destination);

            for (String alternateName : getAlternateNames(cleanName)) {
                searchQueries.add(alternateName + ", " + destination);
            }

            JsonNode nearbyResult = null;
            JsonNode insideUnknownResult = null;
            JsonNode outsideResult = null;

            for (String query : searchQueries) {
                JsonNode results = searchLocation(query, false);

                if (results == null || !results.isArray() || results.isEmpty()) {
                    continue;
                }

                for (JsonNode place : results) {

                    if (!isRequestedPlaceMatch(place, cleanName)) {
                        logger.warn(
                                "Ignoring unrelated location result | Requested: {} | Result: {}",
                                cleanName,
                                place.path("display_name").asText());
                        continue;
                    }

                    // Roads are rejected, except the exact Robber's Cave path.
                    if (isRoadResult(place)
                            && !isKnownNamedAttractionPath(place, cleanName)) {
                        logger.warn(
                                "Ignoring road result | Requested: {} | Result: {}",
                                cleanName,
                                place.path("display_name").asText());
                        continue;
                    }

                    boolean nearbyTown = isNearbyTownResult(
                            place, cleanName, destination, area);

                    // Accept only tourist places or narrowly scoped exceptions.
                    if (!isTouristAttractionResult(place)
                            && !nearbyTown
                            && !isKnownVisitorInstitution(place, cleanName)
                            && !isKnownSpecialAttraction(place, cleanName)) {

                        logger.warn(
                                "Ignoring non-tourist location | Requested: {} | Result: {} | Class: {} | Type: {}",
                                cleanName,
                                place.path("display_name").asText(),
                                getOsmClass(place),
                                place.path("type").asText());
                        continue;
                    }

                    if (place.path("lat").isMissingNode()
                            || place.path("lon").isMissingNode()) {
                        continue;
                    }

                    double latitude;
                    double longitude;

                    try {
                        latitude = Double.parseDouble(place.path("lat").asText());
                        longitude = Double.parseDouble(place.path("lon").asText());
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    boolean insideDestination = isInsideGeometry(
                            longitude, latitude, area.geometry());

                    String category = nearbyTown
                            ? "NEARBY"
                            : classifyPlace(
                                    place, destination, area, latitude, longitude);

                    JsonNode address = place.path("address");

                    double distanceFromCenter = calculateDistanceKm(
                            area.latitude(), area.longitude(),
                            latitude, longitude);

                    logger.info(
                            "LOCATION DIAGNOSTIC | Place: {} | Destination: {} | Latitude: {} | Longitude: {} | Center Lat: {} | Center Lon: {} | Distance: {} km | Inside Boundary: {} | Address: {} | Category: {}",
                            cleanName,
                            destination,
                            latitude,
                            longitude,
                            area.latitude(),
                            area.longitude(),
                            String.format(Locale.ROOT, "%.2f", distanceFromCenter),
                            insideDestination,
                            address,
                            category);

                    // CITY requires both locality match and boundary match.
                    if ("CITY".equals(category) && insideDestination) {
                        LocationResult result = new LocationResult(
                                "VALID",
                                place.path("display_name").asText(),
                                "Place locality matches the destination city and its coordinates are inside the destination boundary.",
                                "CITY");
                        cache.put(cacheKey, result);
                        return result;
                    }

                    // A different identified locality can only be NEARBY.
                    if ("NEARBY".equals(category)) {
                        if (nearbyResult == null) {
                            nearbyResult = place;
                        }
                        continue;
                    }

                    // Inside boundary but locality could not be confirmed.
                    if (insideDestination && insideUnknownResult == null) {
                        insideUnknownResult = place;
                    }

                    // Other results outside destination boundary.
                    if (!insideDestination && outsideResult == null) {
                        outsideResult = place;
                    }
                }
            }

            if (nearbyResult != null) {
                LocationResult result = new LocationResult(
                        "VALID",
                        nearbyResult.path("display_name").asText(),
                        "The requested place was matched in a different identified locality within the nearby distance limit. Treat it as an optional nearby excursion.",
                        "NEARBY");
                cache.put(cacheKey, result);
                return result;
            }

            if (insideUnknownResult != null) {
                LocationResult result = new LocationResult(
                        "UNKNOWN",
                        insideUnknownResult.path("display_name").asText(),
                        "Place is inside the available administrative boundary, but its exact destination city locality could not be confirmed.",
                        "UNKNOWN");
                cache.put(cacheKey, result);
                return result;
            }

            if (outsideResult != null) {
                LocationResult result = new LocationResult(
                        "UNKNOWN",
                        outsideResult.path("display_name").asText(),
                        "Search results were found outside the destination boundary. Manual review is recommended.",
                        "UNKNOWN");
                cache.put(cacheKey, result);
                return result;
            }

            LocationResult result = new LocationResult(
                    "UNKNOWN", "",
                    "No matching place was found in available searches.");
            cache.put(cacheKey, result);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LocationResult(
                    "UNKNOWN", "",
                    "Location verification was interrupted.");

        } catch (Exception e) {
            logger.error(
                    "Location verification failed for place: {} in destination: {}",
                    placeName,
                    destination,
                    e);
            return new LocationResult(
                    "UNKNOWN", "",
                    "Location verification is temporarily unavailable.");
        }
    }

    // Read OSM class/category safely.
    private String getOsmClass(JsonNode place) {
        String resultClass = place.path("class").asText("");

        if (resultClass.isBlank()) {
            resultClass = place.path("category").asText("");
        }

        return resultClass.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isRoadResult(JsonNode place) {
        return "highway".equals(getOsmClass(place));
    }

    // Accept an exact nearby city/town within 40 km.
    // It does not have to be outside the destination's administrative polygon.
    private boolean isNearbyTownResult(
            JsonNode place,
            String requestedName,
            String destination,
            DestinationArea area) {

        if (!"place".equals(getOsmClass(place))) {
            return false;
        }

        String type = place.path("type").asText("")
                .toLowerCase(Locale.ROOT);

        if (!"town".equals(type) && !"city".equals(type)) {
            return false;
        }

        String requested = normalizeName(requestedName);
        String destinationCity = normalizeName(
                destination.split(",")[0].trim());

        // Never classify the destination city itself as a nearby town.
        if (requested.isBlank() || requested.equals(destinationCity)) {
            return false;
        }

        // The OSM name must match the requested town exactly.
        if (!isRequestedPlaceMatch(place, requestedName)) {
            return false;
        }

        String resultName = normalizeName(place.path("name").asText(""));

        if (resultName.isBlank() || !resultName.equals(requested)) {
            return false;
        }

        try {
            double latitude = Double.parseDouble(place.path("lat").asText());
            double longitude = Double.parseDouble(place.path("lon").asText());

            double distance = calculateDistanceKm(
                    area.latitude(), area.longitude(),
                    latitude, longitude);

            // A separate exact town within 40 km is a nearby excursion,
            // even when it lies inside the larger district boundary.
            boolean accepted = distance <= MAX_NEARBY_DISTANCE_KM;

            logger.info(
                    "NEARBY TOWN CHECK | Requested: {} | Town: {} | Distance: {} km | Accepted: {}",
                    requestedName,
                    resultName,
                    String.format(Locale.ROOT, "%.2f", distance),
                    accepted);

            return accepted;

        } catch (NumberFormatException e) {
            logger.warn(
                    "Nearby town has invalid coordinates | Requested: {}",
                    requestedName);
            return false;
        }
    }

    // Narrow exception for the Forest Research Institute visitor site.
    private boolean isKnownVisitorInstitution(
            JsonNode place,
            String requestedName) {

        String requested = normalizeName(requestedName);
        String resultName = normalizeName(place.path("name").asText(""));

        boolean requestedIsFri = requested.equals("forestresearchinstitute")
                || requested.equals("dehradunforestresearchinstitute");

        return requestedIsFri
                && resultName.equals("forestresearchinstitute")
                && "amenity".equals(getOsmClass(place))
                && "university".equalsIgnoreCase(place.path("type").asText(""));
    }

    // Exact named path exception for Robber's Cave.
    private boolean isKnownNamedAttractionPath(
            JsonNode place,
            String requestedName) {

        String requested = normalizeName(requestedName);
        String resultName = normalizeName(place.path("name").asText(""));

        return requested.equals("robberscave")
                && resultName.equals("robberscave")
                && "path".equalsIgnoreCase(place.path("type").asText(""));
    }

    // Narrow exceptions for real attractions whose OSM tagging is unusual.
    private boolean isKnownSpecialAttraction(
            JsonNode place,
            String requestedName) {

        String requested = normalizeName(requestedName);
        String resultName = normalizeName(place.path("name").asText(""));
        String resultClass = getOsmClass(place);
        String resultType = place.path("type").asText("")
                .toLowerCase(Locale.ROOT);

        if (requested.equals("robberscave")
                && resultName.equals("robberscave")
                && "highway".equals(resultClass)
                && "path".equals(resultType)) {
            return true;
        }

        if (requested.equals("sahastradhara")
                && resultName.equals("sahastradhara")
                && "place".equals(resultClass)
                && "village".equals(resultType)) {
            return true;
        }

        return false;
    }

    // Only genuine tourist places should be accepted.
    private boolean isTouristAttractionResult(JsonNode place) {
        String resultClass = getOsmClass(place);
        String resultType = place.path("type").asText("")
                .toLowerCase(Locale.ROOT);

        if ("highway".equals(resultClass)) {
            return false;
        }

        if ("tourism".equals(resultClass)
                && Set.of(
                        "hotel", "guest_house", "hostel", "motel", "apartment")
                        .contains(resultType)) {
            return false;
        }

        if ("amenity".equals(resultClass)
                && Set.of(
                        "restaurant", "cafe", "fast_food", "bar", "pub")
                        .contains(resultType)) {
            return false;
        }

        if ("tourism".equals(resultClass)
                && Set.of(
                        "attraction", "museum", "viewpoint", "zoo",
                        "gallery", "theme_park", "aquarium", "artwork",
                        "information", "picnic_site")
                        .contains(resultType)) {
            return true;
        }

        if ("historic".equals(resultClass)
                && Set.of(
                        "monument", "memorial", "fort", "castle", "ruins",
                        "archaeological_site", "temple")
                        .contains(resultType)) {
            return true;
        }

        if ("amenity".equals(resultClass)
                && "place_of_worship".equals(resultType)) {
            return true;
        }

        if ("amenity".equals(resultClass)
                && "marketplace".equals(resultType)) {
            return true;
        }

        if ("leisure".equals(resultClass)
                && Set.of("park", "garden", "nature_reserve")
                        .contains(resultType)) {
            return true;
        }

        if ("natural".equals(resultClass)
                && Set.of(
                        "waterfall", "spring", "cave_entrance",
                        "peak", "beach", "cave")
                        .contains(resultType)) {
            return true;
        }

        return "water".equals(resultClass) && "lake".equals(resultType);
    }

    // Match actual OSM names, not arbitrary text inside display_name/address.
    private boolean isRequestedPlaceMatch(
            JsonNode place,
            String requestedName) {

        String requested = normalizeName(requestedName);

        if (requested.isBlank()) {
            return false;
        }

        List<String> candidates = new ArrayList<>();

        candidates.add(place.path("name").asText(""));

        JsonNode namedetails = place.path("namedetails");

        candidates.add(namedetails.path("name").asText(""));
        candidates.add(namedetails.path("name:en").asText(""));
        candidates.add(namedetails.path("alt_name").asText(""));
        candidates.add(namedetails.path("official_name").asText(""));

        for (String candidate : candidates) {

            String resultName = normalizeName(candidate);

            logger.info(
                    "NAME MATCH DEBUG | Requested: {} | Candidate: {} | Normalized: {} | OSM Class: {} | OSM Type: {}",
                    requested,
                    candidate,
                    resultName,
                    getOsmClass(place),
                    place.path("type").asText(""));

            if (resultName.isBlank()) {
                continue;
            }

            // Exact name match.
            if (resultName.equals(requested)) {
                return true;
            }
            // Verified alternate names for Sisodia Rani Garden.
            if (Set.of(
                    "sisodiaranipark",
                    "sisodiaranigarden",
                    "sisodiarani",
                    "sisodiaranikabagh").contains(requested)
                    && Set.of(
                            "sisodiaranipark",
                            "sisodiaranigarden",
                            "sisodiarani",
                            "sisodiaranikabagh").contains(resultName)) {
                return true;
            }

            // Raj Bhavan may be listed as Raj Bhavan Uttarakhand.
            // This is a narrow, explicitly verified name variation.
            if (requested.equals("rajbhavan")
                    && resultName.equals("rajbhavanuttarakhand")) {
                return true;
            }

            // Mall of Dehradun alternate name.
            if (requested.equals("dehradunmall")
                    && resultName.equals("mallofdehradun")) {
                return true;
            }

            // Match Malsi Deer Park with its verified OSM name.
            if (requested.equals("malsideerpark")
                    && resultName.equals("dehradunzoomalsideerpark")) {
                return true;
            }

            // Verified OSM name variation for Gun Hill.
            if (requested.equals("gunhill")
                    && resultName.equals("gunhillropeway")) {
                return true;
            }

            // Verified OSM names for Rajaji National Park.
            if (requested.equals("rajajinationalpark")
                    && Set.of(
                            "rajajinationalparktigerreserve",
                            "rajajitigerreserve")
                            .contains(resultName)) {
                return true;
            }

            // Dehradun Zoo may be listed as Dehradun Zoo (Malsi Deer Park).
            if (requested.equals("dehradunzoo")
                    && "tourism".equals(getOsmClass(place))
                    && Set.of("zoo", "attraction").contains(
                            place.path("type").asText("")
                                    .toLowerCase(Locale.ROOT))
                    && resultName.equals("dehradunzoomalsideerpark")) {
                return true;
            }

            // Forest Research Institute.
            if (requested.equals("dehradunforestresearchinstitute")
                    && resultName.equals("forestresearchinstitute")) {
                return true;
            }
            // Birla Temple is also known as Birla Mandir.
            if (requested.equals("birlatemple")
                    && resultName.equals("birlamandir")
                    && "amenity".equals(getOsmClass(place))
                    && "place_of_worship".equalsIgnoreCase(
                            place.path("type").asText(""))) {
                return true;
            }

            // Laxman / Lakshman Jhula.
            if ((requested.equals("laxmanjhula")
                    || requested.equals("lakshmanjhula"))
                    && (resultName.equals("laxmanjhula")
                            || resultName.equals("lakshmanjhula"))) {
                return true;
            }

            // Clock Tower / Ghanta Ghar.
            if ((requested.equals("clocktower")
                    || requested.equals("ghantaghar"))
                    && (resultName.equals("clocktower")
                            || resultName.equals("ghantaghar"))) {
                return true;
            }
        }

        return false;
    }

    // Classify a place as CITY, NEARBY or UNKNOWN.
    private String classifyPlace(
            JsonNode place,
            String destination,
            DestinationArea area,
            double latitude,
            double longitude) {

        JsonNode address = place.path("address");

        if (address.isMissingNode() || !address.isObject()) {
            return "UNKNOWN";
        }

        String destinationCity = normalizeName(
                destination.split(",")[0].trim());

        String city = address.path("city").asText("");
        String town = address.path("town").asText("");
        String municipality = address.path("municipality").asText("");
        String village = address.path("village").asText("");

        String locality = "";

        if (!city.isBlank()) {
            locality = city;
        } else if (!town.isBlank()) {
            locality = town;
        } else if (!municipality.isBlank()) {
            locality = municipality;
        } else if (!village.isBlank()) {
            locality = village;
        }

        if (locality.isBlank()) {
            return "UNKNOWN";
        }

        String normalizedLocality = normalizeName(locality);

        // Exact city-name match.
        if (normalizedLocality.equals(destinationCity)) {
            return "CITY";
        }

        // Handle administrative names such as
        // "Jaipur Municipal Corporation".
        String simplifiedLocality = normalizedLocality
                .replaceAll("municipal corporation$", "")
                .replaceAll("municipality$", "")
                .replaceAll("municipal council$", "")
                .replaceAll("municipal committee$", "")
                .replaceAll("nagar nigam$", "")
                .replaceAll("nagar palika$", "")
                .replaceAll("nagar parishad$", "")
                .trim();

        if (!simplifiedLocality.isBlank()
                && simplifiedLocality.equals(destinationCity)) {
            return "CITY";
        }

        double distance = calculateDistanceKm(
                area.latitude(), area.longitude(),
                latitude, longitude);

        return distance <= MAX_NEARBY_DISTANCE_KM
                ? "NEARBY"
                : "UNKNOWN";
    }

    // Normalize names before comparing. Ignore a leading "The".
    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.toLowerCase(Locale.ROOT).trim();
        cleaned = cleaned.replaceFirst("^the\\s+", "");

        return cleaned.replaceAll("[^a-z0-9]", "");
    }

    // Calculate distance using the Haversine formula.
    private double calculateDistanceKm(
            double lat1,
            double lon1,
            double lat2,
            double lon2) {

        double earthRadius = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                        * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return earthRadius * 2 * Math.atan2(
                Math.sqrt(a), Math.sqrt(1 - a));
    }

    // Alternate attraction names.
    private List<String> getAlternateNames(String placeName) {
        List<String> alternateNames = new ArrayList<>();
        String normalizedName = normalizeName(placeName);

        switch (normalizedName) {
            case "anokhimuseumofhandprinting":
                alternateNames.add("Anokhi Museum");
                break;

            case "sisodiaranipark":
            case "sisodiaranigarden":
            case "sisodiarani":
            case "sisodiaranikabagh":
                alternateNames.add("Sisodia Rani Garden");
                alternateNames.add("Sisodia Rani Bagh");
                break;

            case "dehradunforestresearchinstitute":
                alternateNames.add("Forest Research Institute");
                break;

            case "laxmanjhula":
            case "lakshmanjhula":
                alternateNames.add("Laxman Jhula");
                alternateNames.add("Lakshman Jhula");
                break;

            case "clocktower":
                alternateNames.add("Ghanta Ghar");
                alternateNames.add("Clock Tower");
                break;
            case "birlatemple":
                alternateNames.add("Birla Mandir");
                break;

            case "sahastradhara":
                alternateNames.add("Sahastradhara Waterfall");
                break;

            case "robberscave":
                alternateNames.add("Robber's Cave");
                break;

            case "dehradunmall":
                alternateNames.add("Mall of Dehradun");
                break;

            default:
                break;
        }

        return alternateNames;
    }

    // Clean generic descriptions from attraction names.
    private String cleanPlaceName(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return "";
        }

        String cleanName = placeName.trim();

        cleanName = cleanName.replaceAll(
                "(?i)^(?:the\\s+)?(?:local|nearby|famous)\\s+"
                        + "(?:street food market|handicraft market|"
                        + "shopping street|market|bazaar|temple|"
                        + "zoo|museum|fort|lake)\\s*,\\s*",
                "");

        // Remove a generic trailing description, not the attraction's proper name.
        cleanName = cleanName.replaceFirst(
                "(?i)\\s+observatory$",
                "");

        return cleanName.trim();
    }

    // Get the destination's geographical boundary.
    private DestinationArea getDestinationArea(String destination)
            throws Exception {

        if (destination == null || destination.isBlank()) {
            return null;
        }

        String key = destination.toLowerCase(Locale.ROOT).trim();
        DestinationArea cachedArea = boundsCache.get(key);

        if (cachedArea != null) {
            return cachedArea;
        }

        Set<String> searchQueries = new LinkedHashSet<>();
        String cleanDestination = destination.trim();

        if (cleanDestination.toLowerCase(Locale.ROOT).contains("india")) {
            searchQueries.add(cleanDestination);
        } else {
            searchQueries.add(cleanDestination + ", India");
        }

        String cityName = cleanDestination.split(",")[0].trim();
        if (!cityName.isBlank()) {
            searchQueries.add(cityName + ", India");
        }

        for (String query : searchQueries) {
            JsonNode results = searchLocation(query, true);

            if (results == null || !results.isArray() || results.isEmpty()) {
                continue;
            }

            for (JsonNode result : results) {
                JsonNode geometry = result.path("geojson");
                String geometryType = geometry.path("type").asText();
                JsonNode coordinates = geometry.path("coordinates");

                if (coordinates.isMissingNode()
                        || coordinates.isNull()
                        || coordinates.isEmpty()) {
                    continue;
                }

                if (!"Polygon".equals(geometryType)
                        && !"MultiPolygon".equals(geometryType)) {
                    continue;
                }

                double centerLatitude;
                double centerLongitude;

                try {
                    centerLatitude = Double.parseDouble(
                            result.path("lat").asText());
                    centerLongitude = Double.parseDouble(
                            result.path("lon").asText());
                } catch (NumberFormatException e) {
                    continue;
                }

                DestinationArea area = new DestinationArea(
                        geometry.deepCopy(),
                        centerLatitude,
                        centerLongitude);

                boundsCache.put(key, area);
                return area;
            }
        }

        return null;
    }

    // Check whether a coordinate is inside a GeoJSON polygon.
    private boolean isInsideGeometry(
            double longitude,
            double latitude,
            JsonNode geometry) {

        String type = geometry.path("type").asText();
        JsonNode coordinates = geometry.path("coordinates");

        if ("Polygon".equals(type)) {
            return isInsidePolygon(longitude, latitude, coordinates);
        }

        if ("MultiPolygon".equals(type)) {
            for (JsonNode polygon : coordinates) {
                if (isInsidePolygon(longitude, latitude, polygon)) {
                    return true;
                }
            }
        }

        return false;
    }

    // Check polygon outer boundary and inner holes.
    private boolean isInsidePolygon(
            double longitude,
            double latitude,
            JsonNode polygon) {

        if (!polygon.isArray() || polygon.isEmpty()) {
            return false;
        }

        if (!isInsideRing(longitude, latitude, polygon.get(0))) {
            return false;
        }

        for (int i = 1; i < polygon.size(); i++) {
            if (isInsideRing(longitude, latitude, polygon.get(i))) {
                return false;
            }
        }

        return true;
    }

    // Ray-casting algorithm for point-in-polygon checking.
    private boolean isInsideRing(
            double longitude,
            double latitude,
            JsonNode ring) {

        if (!ring.isArray() || ring.size() < 4) {
            return false;
        }

        boolean inside = false;
        int j = ring.size() - 1;

        for (int i = 0; i < ring.size(); i++) {
            JsonNode pointI = ring.get(i);
            JsonNode pointJ = ring.get(j);

            if (!pointI.isArray()
                    || !pointJ.isArray()
                    || pointI.size() < 2
                    || pointJ.size() < 2) {
                j = i;
                continue;
            }

            double xi = pointI.get(0).asDouble();
            double yi = pointI.get(1).asDouble();
            double xj = pointJ.get(0).asDouble();
            double yj = pointJ.get(1).asDouble();

            boolean intersects = ((yi > latitude) != (yj > latitude))
                    && (longitude < (xj - xi)
                            * (latitude - yi) / (yj - yi) + xi);

            if (intersects) {
                inside = !inside;
            }

            j = i;
        }

        return inside;
    }

    // Search location using OpenStreetMap Nominatim.
    private JsonNode searchLocation(
            String searchQuery,
            boolean includePolygon) throws Exception {

        waitForRateLimit();

        String encodedQuery = URLEncoder.encode(
                searchQuery, StandardCharsets.UTF_8);

        String url = "https://nominatim.openstreetmap.org/search"
                + "?q=" + encodedQuery
                + "&format=jsonv2"
                + "&addressdetails=1"
                + "&namedetails=1"
                + "&limit=5";

        if (includePolygon) {
            url += "&polygon_geojson=1";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header(
                        "User-Agent",
                        "TripMate/1.0 (student travel planner project)")
                .header("Accept-Language", "en")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Location API returned HTTP " + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    // Respect Nominatim public API rate limit.
    private void waitForRateLimit() throws InterruptedException {
        synchronized (RATE_LIMIT_LOCK) {
            long now = System.currentTimeMillis();
            long waitTime = 1000 - (now - lastRequestTime);

            if (waitTime > 0) {
                Thread.sleep(waitTime);
            }

            lastRequestTime = System.currentTimeMillis();
        }
    }

    private record DestinationArea(
            JsonNode geometry,
            double latitude,
            double longitude) {
    }

    public record LocationResult(
            String status,
            String displayName,
            String message,
            String category) {

        public LocationResult(
                String status,
                String displayName,
                String message) {
            this(status, displayName, message, "UNKNOWN");
        }
    }
}

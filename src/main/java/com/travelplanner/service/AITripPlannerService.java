
package com.travelplanner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.travelplanner.dto.TripPlanRequest;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AITripPlannerService {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final LocationValidationService locationValidationService;

        public AITripPlannerService(
                        LocationValidationService locationValidationService) {

                this.locationValidationService = locationValidationService;
        }

        private static final Logger logger = LoggerFactory.getLogger(AITripPlannerService.class);

        private final HttpClient httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();

        private static final int MAX_RETRIES = 1;
        // Maximum additional AI attempts for replacing rejected attractions.
        private static final int MAX_REPLACEMENT_ATTEMPTS = 2;

        private static final String[] BUDGET_CATEGORIES = {
                        "Accommodation",
                        "Food",
                        "Local Transport",
                        "Activities",
                        "Emergency / Extra"
        };

        public String generatePlan(TripPlanRequest trip) throws Exception {

                // Validate input
                if (trip.getDestination() == null
                                || trip.getDestination().isBlank()
                                || trip.getStartDate() == null
                                || trip.getEndDate() == null
                                || trip.getBudget() == null
                                || trip.getBudget().signum() <= 0) {

                        throw new IllegalArgumentException(
                                        "Please provide valid destination, dates and budget.");
                }

                // Validate travel dates
                if (trip.getEndDate().isBefore(trip.getStartDate())
                                || trip.getStartDate().isBefore(LocalDate.now())) {

                        throw new IllegalArgumentException(
                                        "Please provide valid future travel dates.");
                }

                long days = ChronoUnit.DAYS.between(
                                trip.getStartDate(), trip.getEndDate()) + 1;

                if (days > 30) {
                        throw new IllegalArgumentException(
                                        "Trip duration cannot exceed 30 days.");
                }

                String prompt = """
                                                                Create a realistic, geographically sensible travel itinerary.

                                                                TRIP DETAILS:
                                                                Destination: %s
                                                                Start date: %s
                                                                End date: %s
                                                                Total days: %d
                                                                Maximum budget: INR %s

                                                                DATE AND BUDGET:
                                                                1. Use exactly the supplied travel dates and create exactly %d days.
                                                                2. Never exceed the maximum budget.
                                                                3. Use INR for all budget amounts.
                                                                4. All prices are approximate estimates, not verified current prices.
                                                                5. Never invent exact ticket prices, opening hours, hotel bookings or reservations.
                                                                6. If an exact price is unknown, write "Please verify locally".
                                                                7. Estimate accommodation for the actual number of nights between the supplied dates.
                                                                8. Do not confuse trip duration in days with the number of hotel nights.

                                                                DESTINATION ACCURACY:
                                                                1. Recommend genuine, identifiable attractions relevant to the selected destination.
                                                                2. Never invent places or mix attractions from different cities.
                                                                3. Use the actual name of every known named attraction. Java will independently validate locations.
                                                                4. Never replace a known named attraction with a general experience merely because its location is uncertain.
                                                                5. Do not use generic descriptions such as "local park", "nearby waterfall",
                                                                   "local market", "local art scene" or "shopping experience" as named attractions.
                                                                6. Do not assume a fixed city or use hardcoded city-specific attraction lists.
                                                                7. Do not treat UNKNOWN location validation as proof that a place is fake.
                                                                8. Do not invent restaurant names, facilities or business details.
                                                                9. Prefer established, recognizable attractions over obscure or uncertain places.
                                                                10. Use the destination's actual name and relevant attractions for every new trip.

                                                                ATTRACTION CLASSIFICATION:
                                                                1. Keep CITY ATTRACTIONS and NEARBY EXCURSIONS separate.
                                                                2. Mark a main-destination attraction with [CITY ATTRACTION].
                                                                3. City attractions should be relevant to the selected city.
                                                                4. Mark an excursion outside the selected city with
                                                                   [OPTIONAL NEARBY EXCURSION].
                                                                5. A nearby excursion must include its actual town or city name.
                                                                6. Never present a nearby town as part of the main destination.
                                                                7. Do not include distant excursions merely to fill the itinerary.
                                                                8. Named temples, forts, museums, zoos, lakes, waterfalls, viewpoints
                                                                   and landmarks must retain their actual names.
                                                                9. Use [GENERAL EXPERIENCE] only for activities without a specific
                                                                   named tourist destination, such as free time, local food tasting,
                                                                   general walks or relaxation.
                                                                10. Travel, departure, return and transfer activities are not attractions.

                                                                ITINERARY QUALITY:
                                                                1. Each day must contain separate Morning, Afternoon and Evening activities.
                                                                2. Try to provide three distinct, genuine sightseeing activities per day
                                                                   whenever enough suitable attractions are available.
                                                                3. Each sightseeing activity must contain only one named attraction.
                                                                4. Never repeat a named attraction anywhere in the itinerary.
                                                                5. Do not invent attractions or repeat places to fill activity slots.
                                                                6. If sufficient reliable attractions are unavailable, use meaningful
                                                                   general experiences or free time instead of inventing places.
                                                                7. Do not use general experiences as a substitute for known named attractions.
                                                                8. Write the actual attraction name clearly, without mixing it
                                                                   with descriptive instructions.
                                                                9. Group geographically close attractions on the same day where practical.
                                                                10. Avoid unnecessary travel between distant places.
                                                                11. Do not combine a city attraction and a nearby excursion in one activity.
                                                                12. Keep every day relevant to the selected destination.
                                                                13. Distribute attractions across all travel days instead of filling
                                                                    only the first few days with sightseeing.
                                                                14. Prefer different attractions and experiences on different days.
                                                                15. Do not force an optional nearby excursion when enough city attractions
                                                                    are available.

                                ATTRACTION DISCOVERY AND DISTRIBUTION:
                                1. Before creating the day-wise itinerary, identify a broad selection of
                                   genuine, established tourist attractions in the selected destination.
                                2. Consider different attraction types, including historical monuments,
                                   forts, museums, temples, gardens, lakes, viewpoints, cultural sites,
                                   heritage areas and other established visitor attractions.
                                3. Consider the entire trip duration before assigning attractions to days.
                                4. Distribute suitable named attractions across all travel days as evenly
                                   as practical.
                                5. Do not fill the earliest days with most of the attractions while leaving
                                   later days empty.
                                6. Prioritize genuine city attractions first. Consider nearby excursions
                                   only when appropriate and clearly outside the main destination.
                                7. Use different attraction types where suitable instead of repeatedly
                                   selecting the same type of place.
                                8. If you know additional genuine attractions relevant to the destination,
                                   consider them before using general experiences.
                                9. Do not claim that an attraction is verified, open or available.
                                   Java will independently validate the suggested place names.
                                10. Never fabricate a place to achieve a target number of attractions.
                                11. If the destination has limited suitable attractions, use meaningful
                                    general experiences rather than inventing or repeating attractions.

                                                                REQUIRED OUTPUT FORMAT:

                                                                Trip Summary:
                                                                Destination:
                                                                Travel Dates:
                                                                Duration:

                                                                Day 1 - [Date]
                                                                Morning Activity: [classification] [one activity]
                                                                Afternoon Activity: [classification] [one activity]
                                                                Evening Activity: [classification] [one activity]

                                                                Repeat the same structure for all %d days.
                                                                Use the exact supplied dates.
                                                                Do not skip any day or activity heading.

                                                                BUDGET BREAKDOWN:
                                                                Provide this section exactly once after all travel days.
                                                                Use these exact category labels:

                                                                Accommodation: INR [amount]
                                                                Food: INR [amount]
                                                                Local Transport: INR [amount]
                                                                Activities: INR [amount]
                                                                Emergency / Extra: INR [amount]
                                                                Total Estimated Cost: INR [sum]

                                                                BUDGET RULES:
                                                                1. Include all five categories exactly once.
                                                                2. Total Estimated Cost must equal the sum of the five categories.
                                                                3. Total Estimated Cost must not exceed INR %s.
                                                                4. Use plain category labels without Markdown bold formatting.
                                                                5. Keep the budget realistic for the selected destination and trip duration.

                                                                TRAVEL TIPS:
                                                                Provide exactly five practical travel tips in one section.
                                                                Make the tips relevant to the selected destination.

                                                                Do not repeat the budget or travel tips inside individual days.
                                                                Return the complete itinerary with all required sections.
                                                                """

                                .formatted(
                                                trip.getDestination(),
                                                trip.getStartDate(),
                                                trip.getEndDate(),
                                                days,
                                                trip.getBudget().toPlainString(),
                                                days,
                                                days,
                                                trip.getBudget().toPlainString());

                logger.info(
                                "PROMPT CREATED SUCCESSFULLY | Days: {} | Days Type: {}",
                                days,
                                ((Object) days).getClass().getName());
                // First attempt + maximum two retries
                String currentPrompt = prompt;

                for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {

                        String itinerary = callOllama(currentPrompt);

                        logger.info("AI response received on attempt {}", attempt + 1);

                        // Validate attractions and add location labels.
                        itinerary = validateItineraryPlaces(
                                        itinerary,
                                        trip.getDestination());
                        itinerary = replaceRejectedAttractionSlots(itinerary, trip.getDestination());

                        BigDecimal actualTotal = calculateBudgetTotal(itinerary);

                        if (actualTotal != null) {

                                // If AI exceeds the budget, Java will adjust all categories.
                                if (actualTotal.compareTo(trip.getBudget()) > 0) {

                                        logger.warn(
                                                        "AI budget {} exceeds maximum budget {}. Adjusting with Java.",
                                                        actualTotal,
                                                        trip.getBudget());

                                        return adjustBudgetToLimit(
                                                        itinerary,
                                                        actualTotal,
                                                        trip.getBudget());
                                }

                                // If AI budget is within limit, Java corrects the final total.
                                logger.info("AI budget validated successfully: {}", actualTotal);

                                return replaceTotal(itinerary, actualTotal);
                        }

                        if (attempt < MAX_RETRIES) {

                                String actualAmount = actualTotal == null
                                                ? "Unable to extract all five categories"
                                                : actualTotal.toPlainString();

                                String requiredReduction = actualTotal == null
                                                ? "Provide all five category amounts clearly."
                                                : actualTotal.subtract(trip.getBudget()).toPlainString();

                                currentPrompt = """
                                                Your previous itinerary has an incorrect budget.

                                                MAXIMUM BUDGET: INR %s
                                                ACTUAL CATEGORY TOTAL: INR %s
                                                REQUIRED REDUCTION: INR %s

                                                STRICT CORRECTION RULES:
                                                1. Reduce the category amounts so their sum is within the maximum budget.
                                                2. Do not increase any category amount unnecessarily.
                                                3. Calculate the sum of all five categories correctly.
                                                4. Total Estimated Cost must equal the actual category sum.
                                                5. Never write a false total.
                                                6. Include all five categories exactly once.
                                                7. Keep the same destination and travel dates.
                                                8. Keep the day-wise plan and five travel tips.
                                                9. Return the complete corrected itinerary.
                                                10. Use plain category labels without Markdown bold formatting.

                                                PREVIOUS ITINERARY:
                                                %s
                                                """
                                                .formatted(
                                                                trip.getBudget().toPlainString(),
                                                                actualAmount,
                                                                requiredReduction,
                                                                itinerary);
                        }
                }

                throw new IllegalStateException(
                                "AI could not generate a valid budget after 2 retries. "
                                                + "Please try again.");
        }

        // Extract possible attraction names from the AI itinerary.
        private java.util.List<String> extractPlaceNames(String itinerary) {

                java.util.Set<String> places = new java.util.LinkedHashSet<>();

                String[] patterns = {

                                // Visit, Explore, Discover and Tour.
                                "(?i)\\b(?:Explore|Visit|Discover|Tour)\\s+(?:the\\s+)?([^\\n.!()]+)",

                                // Guided tour.
                                "(?i)\\b(?:take\\s+)?(?:a\\s+)?(?:guided\\s+)?tour\\s+of\\s+(?:the\\s+)?([^\\n.!()]+)",

                                // Walk or stroll, including "stroll at".
                                "(?i)\\b(?:take\\s+)?(?:a\\s+)?(?:leisurely\\s+)?(?:stroll|walk)\\s+(?:in|around|along|at)\\s+(?:the\\s+)?([^\\n.!()]+)",

                                // Sunset and boat rides.
                                "(?i)\\b(?:boat ride on|sunset view at|watch the sunset at)\\s+(?:the\\s+)?([^\\n.!()]+)",

                                // Day trips.
                                "(?i)\\b(?:take\\s+)?(?:a\\s+)?day trip to\\s+(?:the\\s+)?([^\\n.!()]+)",

                                // Treks and hikes.
                                "(?i)\\b(?:take\\s+)?(?:a\\s+)?(?:trek|hike)\\s+to\\s+(?:the\\s+)?([^\\n.!()]+)",

                                // Relax at a named place.
                                "(?i)\\brelax\\s+at\\s+(?:the\\s+)?([^\\n.!()]+)"
                };

                for (String regex : patterns) {

                        Matcher matcher = Pattern.compile(regex).matcher(itinerary);

                        while (matcher.find()) {

                                String rawPlace = matcher.group(1)
                                                .replaceAll("[*`_#]", "")
                                                .replaceAll(
                                                                "(?i)\\s+(?:entry fee|free entry|approximate cost|estimated cost|please verify locally).*",
                                                                "")
                                                .replaceAll("\\s*\\([^)]*\\)", "")
                                                .trim();

                                rawPlace = rawPlace.replaceAll(
                                                "^[\\s,;:-]+|[\\s,;:-]+$",
                                                "").trim();

                                // Remove descriptions after commas.
                                rawPlace = rawPlace.replaceAll(
                                                "(?i)\\s*,.*$",
                                                "").trim();

                                // Remove introductory words accidentally captured.
                                rawPlace = rawPlace.replaceAll(
                                                "(?i)^(?:to|at|in|around|along)\\s+",
                                                "").trim();

                                // Remove descriptive words before actual place names.
                                rawPlace = rawPlace.replaceAll(
                                                "(?i)^(?:the\\s+)?(?:iconic|famous|historic|beautiful|"
                                                                + "bustling|nearby|local|vibrant|ancient|"
                                                                + "colourful|colorful|popular|traditional)\\s+",
                                                "").trim();

                                // Remove phrases such as:
                                // "streets of Johari Bazaar" -> "Johari Bazaar"
                                rawPlace = rawPlace.replaceAll(
                                                "(?i)^(?:streets?|gardens?|shores?|banks?|"
                                                                + "surroundings?|areas?)\\s+of\\s+",
                                                "").trim();

                                // Remove introductory articles.
                                rawPlace = rawPlace.replaceAll(
                                                "(?i)^(?:the|a|an)\\s+",
                                                "").trim();

                                if (isGenericPlaceName(rawPlace)) {
                                        continue;
                                }

                                // Split multiple named attractions.
                                String[] separatePlaces = rawPlace.split(
                                                "(?i)\\s+(?:and|&)\\s+");

                                for (String place : separatePlaces) {

                                        place = place.trim()
                                                        .replaceAll(
                                                                        "^[,;:\\s]+|[,;:\\s]+$",
                                                                        "");

                                        if (!isGenericPlaceName(place)) {
                                                places.add(place);
                                        }
                                }
                        }
                }

                return new java.util.ArrayList<>(places);
        }

        // Check whether extracted text is a generic description.
        private boolean isGenericPlaceName(String place) {

                if (place == null || place.isBlank()) {
                        return true;
                }

                String name = place.toLowerCase(java.util.Locale.ROOT)
                                .trim()
                                .replaceAll("[^a-z0-9\\s]", "")
                                .replaceAll("\\s+", " ")
                                .replaceFirst("^(?:the|a|an)\\s+", "");
                return name.isBlank()
                                || name.matches(
                                                "(?:museum|fort|lake|temple|zoo|market|markets|"
                                                                + "bazaar|bazaars|area|areas|"
                                                                + "local area|"
                                                                + "city|town|region|culture|"
                                                                + "architecture|"
                                                                + "local culture|local experiences|"
                                                                + "shopping areas|shopping streets|"
                                                                + "street food markets|"
                                                                + "handicraft market|handicraft markets|"
                                                                + "local market|local markets|"
                                                                + "local streets|local shops|"
                                                                + "local streets and shops|"
                                                                + "markets and shops|"
                                                                + "local markets and shops|"
                                                                + "streets|shops|"
                                                                + "fort surroundings|"
                                                                + "forts surroundings|"
                                                                + "lake view|"
                                                                + "lakeside view|"
                                                                + "surroundings|"
                                                                + "nearby areas|"
                                                                + "of the region|of the city|"
                                                                + "culture of the region|"
                                                                + "culture of the city|"
                                                                + "history of the region|"
                                                                + "art scene|"
                                                                + "local art scene|"
                                                                + "food scene|"
                                                                + "local food scene|"
                                                                + "nightlife|"
                                                                + "local nightlife|"
                                                                + "shopping experience|"
                                                                + "nearby waterfalls|"
                                                                + "waterfalls|"
                                                                + "local park|"
                                                                + "park|"
                                                                + "local walk|"
                                                                + "general walk|"
                                                                + "general walk through the city|"
                                                                + "city walk|"
                                                                + "local stroll|"
                                                                + "general stroll|"
                                                                + "riverside stroll|"
                                                                + "river walk|"
                                                                + "local cafe|"
                                                                + "local cafes|"
                                                                + "local experience|"
                                                                + "general local experience)");
        }

        // Validate day-wise activities, remove unverified places and prevent
        // duplicates.

        private String validateItineraryPlaces(
                        String itinerary, String destination) {

                String[] lines = itinerary.split("\\n", -1);

                logger.info("RAW AI ITINERARY BEFORE LOCATION VALIDATION:\n{}", itinerary);

                StringBuilder updatedItinerary = new StringBuilder();

                java.util.Set<String> seenPlaces = new java.util.HashSet<>();

                Pattern activityPattern = Pattern.compile(
                                "^\\s*(?:#{1,6}\\s*)?"
                                                + "(?:[-*•+]\\s*)?"
                                                + "(?:🌅\\s*|☀️\\s*|🌙\\s*)?"
                                                + "(?:\\*\\*)?"
                                                + "(Morning|Afternoon|Evening)"
                                                + "(?:\\s+Activity)?"
                                                + "\\s*:?[ ]*"
                                                + "(?:\\*\\*)?"
                                                + "\\s*:?[ ]*"
                                                + "(.*)?$",
                                Pattern.CASE_INSENSITIVE);

                Pattern structuralHeading = Pattern.compile(
                                "^\\s*#{1,6}\\s+.*$");
                Pattern dayHeading = Pattern.compile(
                                "^\\s*(?:#{1,6}\\s*)?Day\\s+\\d+\\b.*$",
                                Pattern.CASE_INSENSITIVE);

                int validatedCount = 0;
                int generalActivityCount = 0;
                int duplicateCount = 0;
                int rejectedCount = 0;

                for (int i = 0; i < lines.length; i++) {

                        String line = lines[i];

                        Matcher matcher = activityPattern.matcher(line);

                        if (!matcher.matches()) {
                                updatedItinerary.append(line).append("\n");
                                continue;
                        }

                        String period = matcher.group(1).trim();

                        StringBuilder activityBuilder = new StringBuilder();

                        String inlineActivity = matcher.group(2);

                        boolean explicitlyGeneralExperience = false;
                        String activityCost = "";

                        if (inlineActivity != null && !inlineActivity.isBlank()) {

                                if (containsGeneralExperienceLabel(inlineActivity)) {
                                        explicitlyGeneralExperience = true;
                                }

                                activityBuilder.append(inlineActivity.trim());
                        }

                        // Read activity continuation lines.
                        while (i + 1 < lines.length) {

                                String nextLine = lines[i + 1].trim();

                                Matcher nextActivityMatcher = activityPattern.matcher(lines[i + 1]);

                                if (nextActivityMatcher.matches()) {
                                        break;
                                }

                                if (structuralHeading.matcher(nextLine).matches()
                                                || dayHeading.matcher(nextLine).matches()) {
                                        break;
                                }

                                String normalizedNextLine = nextLine
                                                .replaceAll("[*`_#]", "")
                                                .trim();

                                if (normalizedNextLine.matches(
                                                "(?i)^(Budget Breakdown|Travel Tips)\\s*:?\\s*$")
                                                || normalizedNextLine.matches(
                                                                "(?i)^(Accommodation|Food|Local Transport|Activities|"
                                                                                + "Emergency\\s*/\\s*Extra|Total Estimated Cost)\\s*:.*$")) {

                                        break;
                                }

                                i++;

                                if (nextLine.isBlank()) {
                                        continue;
                                }

                                if (containsGeneralExperienceLabel(nextLine)) {
                                        explicitlyGeneralExperience = true;
                                }

                                nextLine = nextLine.replaceFirst(
                                                "^(?:[-*•+]\\s*)", "").trim();

                                nextLine = nextLine.replaceAll(
                                                "[*`_#]", "").trim();

                                if (nextLine.isBlank()) {
                                        continue;
                                }
                                // Extract only the actual activity name.
                                if (nextLine.matches("(?i)^Activity\\s*:.*$")) {

                                        nextLine = nextLine.replaceFirst(
                                                        "(?i)^Activity\\s*:\\s*",
                                                        "").trim();

                                } else if (nextLine.matches(
                                                "(?i)^Cost(?: Estimate)?\\s*:.*$")) {
                                        activityCost = nextLine.replaceFirst(
                                                        "(?i)^Cost Estimate\\s*:\\s*",
                                                        "").trim();

                                        continue;

                                } else if (nextLine.matches("(?i)^Description\\s*:.*$")) {

                                        continue;
                                }

                                if (nextLine.matches("(?i)^Location\\s*:.*$")) {
                                        continue;
                                }

                                if (nextLine.matches(
                                                "(?i)^\\[(?:DEHRADUN\\s+)?CITY ATTRACTION\\]$"
                                                                + "|^\\[(?:OPTIONAL\\s+)?NEARBY EXCURSION\\]$"
                                                                + "|^\\[GENERAL EXPERIENCE\\]$")) {

                                        continue;
                                }

                                if (activityBuilder.length() > 0) {
                                        activityBuilder.append(" ");
                                }

                                activityBuilder.append(nextLine);
                        }

                        String activity = activityBuilder.toString().trim();

                        if (containsGeneralExperienceLabel(activity)) {
                                explicitlyGeneralExperience = true;
                        }

                        activity = activity.replaceAll(
                                        "(?i)\\s*\\[(?:DEHRADUN\\s+)?CITY ATTRACTION\\]"
                                                        + "|\\s*\\[(?:OPTIONAL\\s+)?NEARBY EXCURSION\\]"
                                                        + "|\\s*\\[GENERAL EXPERIENCE\\]",
                                        " ").trim();

                        activity = activity.replaceAll("[*`_#]", "").trim();

                        activity = activity.replaceFirst(
                                        "^[\\s:–—-]+", "").trim();

                        if (activity.isBlank()) {
                                updatedItinerary.append(period)
                                                .append(" Activity:\n");
                                continue;
                        }

                        String cost = activityCost.isBlank()
                                        ? ""
                                        : " (Cost Estimate: " + activityCost + ")";

                        String searchName = extractPlaceName(activity);

                        boolean travelActivity = isTravelActivity(activity);

                        boolean generalActivity = isGeneralActivity(activity);

                        boolean namedDiningActivity = isNamedDiningActivity(activity);

                        logger.info(
                                        "LOCATION DEBUG | Activity: [{}] | Extracted Name: [{}] | "
                                                        + "Travel: {} | General Activity: {} | "
                                                        + "Dining: {} | Explicit General Label: {}",
                                        activity,
                                        searchName,
                                        travelActivity,
                                        generalActivity,
                                        namedDiningActivity,
                                        explicitlyGeneralExperience);

                        // Travel activities are not sightseeing attractions.
                        if (travelActivity) {

                                updatedItinerary.append(period)
                                                .append(" Activity: [TRAVEL ACTIVITY] ")
                                                .append(activity)
                                                .append(cost)
                                                .append("\n");

                                continue;
                        }

                        // Restaurant names are not verified as tourist attractions.
                        // Keep the dining experience without claiming a specific
                        // restaurant has been independently verified.
                        if (namedDiningActivity) {

                                updatedItinerary.append(period)
                                                .append(" Activity: [GENERAL EXPERIENCE] ")
                                                .append("Enjoy a local dining experience")
                                                .append(cost)
                                                .append("\n");

                                generalActivityCount++;

                                continue;
                        }

                        // Generic experiences do not need location validation.
                        if (generalActivity) {

                                updatedItinerary.append(period)
                                                .append(" Activity: [GENERAL EXPERIENCE] ")
                                                .append(activity)
                                                .append(cost)
                                                .append("\n");

                                generalActivityCount++;

                                continue;
                        }

                        // Split combined names such as "City Palace and Amber Fort".
                        java.util.List<String> placeNames = splitCompositePlaceNames(searchName);

                        int validPlacesForActivity = 0;
                        int duplicatesForActivity = 0;
                        int rejectedPlacesForActivity = 0;

                        boolean costAdded = false;

                        for (String placeName : placeNames) {

                                LocationValidationService.LocationResult result = locationValidationService
                                                .validatePlace(
                                                                placeName,
                                                                destination);

                                validatedCount++;

                                if ("VALID".equalsIgnoreCase(result.status())
                                                && ("CITY".equalsIgnoreCase(result.category())
                                                                || "NEARBY".equalsIgnoreCase(result.category()))) {

                                        String canonicalName = placeName;

                                        String duplicateKey = canonicalName
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .replaceAll("[^a-z0-9]", "");

                                        if (!seenPlaces.add(duplicateKey)) {

                                                duplicateCount++;
                                                duplicatesForActivity++;

                                                logger.warn(
                                                                "Duplicate attraction skipped: {}",
                                                                placeName);

                                                continue;
                                        }

                                        String label;

                                        if ("CITY".equalsIgnoreCase(result.category())) {
                                                label = "[CITY ATTRACTION]";
                                        } else {
                                                label = "[OPTIONAL NEARBY EXCURSION]";
                                        }

                                        updatedItinerary.append(period)
                                                        .append(" Activity: ")
                                                        .append(label)
                                                        .append(" ")
                                                        .append(placeName);

                                        // Attach the original cost only once.
                                        if (!costAdded) {
                                                updatedItinerary.append(cost);
                                                costAdded = true;
                                        }

                                        updatedItinerary.append("\n");

                                        validPlacesForActivity++;

                                        logger.info(
                                                        "Verified attraction retained: {} | Category: {}",
                                                        placeName,
                                                        result.category());

                                } else {

                                        rejectedCount++;
                                        rejectedPlacesForActivity++;

                                        logger.warn(
                                                        "Unverified attraction skipped: {} | Status: {} | "
                                                                        + "Category: {} | Details: {}",
                                                        placeName,
                                                        result.status(),
                                                        result.category(),
                                                        result.message());
                                }
                        }

                        // If at least one attraction was valid, keep the valid ones.
                        // Invalid or duplicate components are not allowed to remove
                        // the valid attraction from a combined activity.
                        if (validPlacesForActivity > 0) {
                                continue;
                        }

                        // No valid attraction remained. Preserve this slot for AI replacement.
                        updatedItinerary.append(period)
                                        .append(" Activity: [[TRIPMATE_REPLACEMENT_SLOT]]")
                                        .append(cost)
                                        .append("\n");
                }

                logger.info("Total actual locations checked: {}", validatedCount);
                logger.info("Total general activities: {}", generalActivityCount);
                logger.info("Total duplicate attractions replaced: {}", duplicateCount);
                logger.info("Total unverified attractions replaced: {}", rejectedCount);

                return updatedItinerary.toString().stripTrailing();
        }

        // Create a normalized key to identify duplicate attraction names.
        private String createPlaceDuplicateKey(String placeName) {

                if (placeName == null || placeName.isBlank()) {
                        return "";
                }

                return placeName
                                .toLowerCase(java.util.Locale.ROOT)
                                .replaceAll("[^a-z0-9]", "");
        }

        // Collect attractions that are already verified in the itinerary.
        private java.util.Set<String> collectExistingPlaceKeys(String itinerary) {

                java.util.Set<String> existingPlaces = new java.util.HashSet<>();

                String[] lines = itinerary.split("\\n");

                Pattern verifiedPlacePattern = Pattern.compile(
                                "\\[(?:CITY ATTRACTION|OPTIONAL NEARBY EXCURSION)\\]\\s*(.+)$",
                                Pattern.CASE_INSENSITIVE);

                for (String line : lines) {

                        Matcher matcher = verifiedPlacePattern.matcher(line);

                        if (matcher.find()) {

                                String placeName = extractPlaceName(matcher.group(1));

                                String duplicateKey = createPlaceDuplicateKey(placeName);

                                if (!duplicateKey.isBlank()) {
                                        existingPlaces.add(duplicateKey);
                                }
                        }
                }

                return existingPlaces;
        }

        // Collect actual verified attraction names for the AI prompt.
        private java.util.Set<String> collectExistingPlaceNames(String itinerary) {

                java.util.Set<String> existingNames = new java.util.LinkedHashSet<>();

                String[] lines = itinerary.split("\\n");

                Pattern verifiedPlacePattern = Pattern.compile(
                                "\\[(?:CITY ATTRACTION|OPTIONAL NEARBY EXCURSION)\\]\\s*(.+)$",
                                Pattern.CASE_INSENSITIVE);

                for (String line : lines) {

                        Matcher matcher = verifiedPlacePattern.matcher(line);

                        if (matcher.find()) {

                                String placeName = extractPlaceName(matcher.group(1));

                                if (!placeName.isBlank()) {
                                        existingNames.add(placeName);
                                }
                        }
                }

                return existingNames;
        }

        // Replace missing attraction slots with independently verified attractions.
        private String replaceRejectedAttractionSlots(
                        String itinerary,
                        String destination) {

                final String marker = "[[TRIPMATE_REPLACEMENT_SLOT]]";

                int missingSlots = 0;

                for (String line : itinerary.split("\\n")) {
                        if (line.contains(marker)) {
                                missingSlots++;
                        }
                }

                if (missingSlots == 0) {
                        return itinerary;
                }

                logger.info(
                                "Attraction replacement started | Destination: {} | Missing slots: {}",
                                destination,
                                missingSlots);

                java.util.Set<String> existingPlaceKeys = collectExistingPlaceKeys(itinerary);

                java.util.Set<String> existingPlaceNames = collectExistingPlaceNames(itinerary);

                // Keep this set across attempts to prevent repeated candidates.
                java.util.Set<String> rejectedCandidateKeys = new java.util.HashSet<>();

                String updatedItinerary = itinerary;

                for (int attempt = 1; attempt <= MAX_REPLACEMENT_ATTEMPTS; attempt++) {

                        int remainingSlots = 0;

                        for (String line : updatedItinerary.split("\\n")) {
                                if (line.contains(marker)) {
                                        remainingSlots++;
                                }
                        }

                        if (remainingSlots == 0) {
                                break;
                        }

                        logger.info(
                                        "Attraction replacement attempt {} | Remaining slots: {}",
                                        attempt,
                                        remainingSlots);

                        try {

                                String replacementPrompt = buildReplacementPrompt(
                                                destination,
                                                remainingSlots,
                                                existingPlaceNames);

                                String aiResponse = callOllama(replacementPrompt);

                                java.util.List<String> candidates = parseReplacementCandidates(aiResponse);

                                logger.info(
                                                "AI replacement candidates received: {}",
                                                candidates.size());
                                for (String candidate : candidates) {

                                        if (remainingSlots <= 0) {
                                                break;
                                        }

                                        String candidateKey = createPlaceDuplicateKey(candidate);

                                        if (candidateKey.isBlank()
                                                        || existingPlaceKeys.contains(candidateKey)
                                                        || rejectedCandidateKeys.contains(candidateKey)) {

                                                logger.warn(
                                                                "Replacement candidate skipped as duplicate: {}",
                                                                candidate);

                                                continue;
                                        }

                                        rejectedCandidateKeys.add(candidateKey);

                                        LocationValidationService.LocationResult result = locationValidationService
                                                        .validatePlace(
                                                                        candidate,
                                                                        destination);

                                        if (!"VALID".equalsIgnoreCase(result.status())
                                                        || !("CITY".equalsIgnoreCase(result.category())
                                                                        || "NEARBY".equalsIgnoreCase(
                                                                                        result.category()))) {

                                                logger.warn(
                                                                "Replacement candidate rejected: {} | Status: {} | Category: {}",
                                                                candidate,
                                                                result.status(),
                                                                result.category());

                                                continue;
                                        }
                                        String canonicalName = candidate.trim();

                                        String canonicalKey = createPlaceDuplicateKey(canonicalName);
                                        if (canonicalKey.isBlank()
                                                        || existingPlaceKeys.contains(canonicalKey)) {

                                                logger.warn(
                                                                "Verified replacement is still a duplicate: {}",
                                                                canonicalName);

                                                continue;
                                        }

                                        String label = "CITY".equalsIgnoreCase(result.category())
                                                        ? "[CITY ATTRACTION]"
                                                        : "[OPTIONAL NEARBY EXCURSION]";

                                        // Replace only the first missing activity slot.
                                        updatedItinerary = updatedItinerary.replaceFirst(
                                                        java.util.regex.Pattern.quote(marker),
                                                        java.util.regex.Matcher.quoteReplacement(
                                                                        label + " " + canonicalName));

                                        existingPlaceKeys.add(canonicalKey);
                                        existingPlaceNames.add(canonicalName);

                                        remainingSlots--;

                                        logger.info(
                                                        "Verified replacement attraction added: {} | Category: {}",
                                                        canonicalName,
                                                        result.category());
                                }

                        } catch (Exception e) {

                                logger.error(
                                                "AI attraction replacement failed on attempt {}: {}",
                                                attempt,
                                                e.getMessage());

                                break;
                        }
                }
                // Remove any replacement markers that could not be filled.
                if (updatedItinerary.contains(marker)) {

                        logger.warn(
                                        "Some attraction slots could not be replaced. "
                                                        + "Using safe general experience fallback.");

                        updatedItinerary = updatedItinerary.replace(
                                        marker,
                                        "[GENERAL EXPERIENCE] "
                                                        + "Enjoy free time or choose a locally confirmed activity");
                }

                return updatedItinerary;
        }

        // Ask AI for fresh attraction names for the missing itinerary slots.
        private String buildReplacementPrompt(
                        String destination,
                        int requiredCount,
                        java.util.Set<String> existingPlaceKeys) {

                StringBuilder prompt = new StringBuilder();

                prompt.append("You are a careful travel research assistant.\n");
                prompt.append("Destination: ").append(destination).append("\n");
                prompt.append("Required new attractions: ").append(requiredCount).append("\n\n");

                prompt.append("Suggest exactly ").append(requiredCount)
                                .append(" different, real tourist attractions in the destination.\n");

                prompt.append("Rules:\n");
                prompt.append("1. Suggest only actual, named tourist attractions.\n");
                prompt.append("2. Do not invent attraction names.\n");
                prompt.append("3. Prefer attractions located inside the destination city.\n");
                prompt.append("4. Do not suggest restaurants, hotels, roads, generic activities or shopping experiences.\n");
                prompt.append("5. Do not repeat any existing attraction listed below.\n");
                prompt.append("6. Return only a numbered list, one attraction name per line.\n");
                prompt.append("7. Do not include descriptions or explanations.\n\n");

                prompt.append("Already used attractions (do not repeat):\n");

                if (existingPlaceKeys.isEmpty()) {
                        prompt.append("None\n");
                } else {
                        for (String place : existingPlaceKeys) {
                                prompt.append("- ").append(place).append("\n");
                        }
                }
                logger.info("REPLACEMENT PROMPT CREATED SUCCESSFULLY");
                return prompt.toString();
        }

        // Extract attraction names from the AI replacement response.
        private java.util.List<String> parseReplacementCandidates(String response) {

                java.util.List<String> candidates = new java.util.ArrayList<>();

                if (response == null || response.isBlank()) {
                        return candidates;
                }

                String[] lines = response.split("\\n");

                for (String line : lines) {

                        String placeName = line.trim();

                        // Remove numbering and list markers.
                        placeName = placeName.replaceFirst(
                                        "^\\s*(?:\\d+[.)]|[-*•])\\s*", "");

                        // Remove Markdown formatting.
                        placeName = placeName.replaceAll("[*`_#]", "").trim();

                        // Ignore empty or excessively long responses.
                        if (placeName.isBlank() || placeName.length() > 120) {
                                continue;
                        }

                        candidates.add(placeName);
                }

                return candidates;
        }

        // Split combined attraction names only when each part looks like
        // an independently named place.
        private java.util.List<String> splitCompositePlaceNames(String searchName) {

                java.util.List<String> names = new java.util.ArrayList<>();

                if (searchName == null || searchName.isBlank()) {
                        return names;
                }

                String[] parts = searchName.split("(?i)\\s+(?:and|&)\\s+");

                // Do not split a name if it does not contain a clear combination.
                if (parts.length < 2) {
                        names.add(searchName.trim());
                        return names;
                }

                java.util.List<String> cleanedParts = new java.util.ArrayList<>();

                for (String part : parts) {

                        String cleaned = part.trim()
                                        .replaceFirst("(?i)^(?:the|a|an)\\s+", "")
                                        .replaceAll("^[,;:\\s]+|[,;:\\s]+$", "")
                                        .trim();

                        // Require at least two words in every part.
                        // This helps avoid splitting names such as
                        // "Rock and Roll Hall of Fame".
                        if (cleaned.isBlank()
                                        || cleaned.split("\\s+").length < 2
                                        || isGenericPlaceName(cleaned)) {

                                names.clear();
                                names.add(searchName.trim());
                                return names;
                        }

                        cleanedParts.add(cleaned);
                }

                names.addAll(cleanedParts);

                return names;
        }

        // Detect named restaurants and meal-at-place activities.
        // These are handled as general dining experiences, not as
        // independently verified tourist attractions.
        private boolean isNamedDiningActivity(String activity) {

                if (activity == null || activity.isBlank()) {
                        return false;
                }

                String text = activity.toLowerCase(java.util.Locale.ROOT);

                return text.matches("(?s).*\\b(?:restaurant|cafe|café|eatery)\\b.*")
                                || text.matches(
                                                "(?s).*\\b(?:breakfast|brunch|lunch|dinner|dining)\\s+at\\s+.+");
        }

        // Detect explicit general-experience labels, including standalone lines.
        private boolean containsGeneralExperienceLabel(String text) {

                if (text == null || text.isBlank()) {
                        return false;
                }

                return Pattern.compile(
                                "(?i)\\[\\s*GENERAL\\s+EXPERIENCE\\s*\\]")
                                .matcher(text)
                                .find();
        }

        // Extract only the actual attraction name from the AI activity.
        private String extractPlaceName(String activity) {

                if (activity == null || activity.isBlank()) {
                        return "";
                }

                String name = activity.trim();

                // Remove Markdown formatting.
                name = name.replaceAll("[*`_#]", "").trim();

                // Remove the Activity prefix.
                name = name.replaceFirst(
                                "(?i)^\\s*Activity\\s*:\\s*",
                                "").trim();

                // Remove AI-generated category labels.
                name = name.replaceAll(
                                "(?i)\\s*\\[(?:CITY ATTRACTION|OPTIONAL NEARBY EXCURSION|GENERAL EXPERIENCE|TRAVEL ACTIVITY)\\]\\s*",
                                " ").trim();

                // Remove cost information.
                name = name.replaceFirst(
                                "(?i)\\s*(?:Cost|Estimated Cost|Approximate Cost)\\s*:\\s*(?:INR|Rs\\.?|₹)?\\s*[0-9,]+(?:\\.[0-9]+)?.*$",
                                "").trim();

                // Remove descriptions inside parentheses.
                name = name.replaceAll("\\s*\\([^)]*\\)", "").trim();

                // Remove descriptions after a spaced dash.
                name = name.replaceFirst(
                                "\\s+[–—-]\\s+.*$",
                                "").trim();
                // Remove descriptive phrases attached to actual place names.
                name = name.replaceFirst(
                                "(?i)\\s+(?:for|to)\\s+"
                                                + "(?:traditional|sightseeing|exploring|"
                                                + "explore|enjoying|enjoy|visiting|"
                                                + "visit|photography|shopping|"
                                                + "block printing|its architecture|"
                                                + "the architecture|the scenery)\\b.*$",
                                "").trim();

                // Remove general walk/stroll instructions,
                // but preserve the actual place name.
                name = name.replaceFirst(
                                "(?i)^(?:take\\s+)?(?:a\\s+)?"
                                                + "(?:general\\s+)?(?:leisurely\\s+)?"
                                                + "(?:walk|stroll)\\s+"
                                                + "(?:through|in|around|along|at)\\s+"
                                                + "(?:the\\s+)?",
                                "").trim();

                // Remove common introductory activity instructions.
                name = name.replaceFirst(
                                "(?i)^(?:visit|explore|discover|tour|see|enjoy)\\s+(?:the\\s+)?",
                                "").trim();

                // Remove introductory articles.
                name = name.replaceFirst(
                                "(?i)^(?:the|a|an)\\s+",
                                "").trim();

                // Remove trailing punctuation.
                name = name.replaceAll("[.!]+$", "").trim();

                return normalizePlaceName(name);
        }

        // Extract a clean place name before sending it to location search.
        private String normalizePlaceName(String activity) {

                if (activity == null || activity.isBlank()) {
                        return "";
                }

                String name = activity.trim();

                // Remove optional excursion prefix.
                name = name.replaceFirst(
                                "(?i)^Optional\\s+Nearby\\s+Excursion\\s*:\\s*",
                                "");

                // Remove common activity instructions.
                name = name.replaceFirst(
                                "(?i)^(?:visit|explore|discover|tour|see|enjoy|"
                                                + "take a walk around|walk around|"
                                                + "stroll around|relax at)\\s+(?:the\\s+)?",
                                "");

                // Extract restaurant or hotel name from dining activities.
                name = name.replaceFirst(
                                "(?i)^(?:Local\\s+)?(?:Dinner|Lunch|Breakfast|"
                                                + "Dining)\\s+at\\s+",
                                "");

                // Normalize viewpoint spelling.
                name = name.replaceAll(
                                "(?i)\\bView\\s+Point\\b",
                                "Viewpoint");

                // Remove unnecessary surrounding punctuation.
                name = name.replaceAll(
                                "^[\\s,;:-]+|[\\s,;:-]+$",
                                "").trim();

                return name;
        }

        // Identify pure travel and transfer activities.
        // Mixed travel + sightseeing activities must go through place validation.
        private boolean isTravelActivity(String activity) {

                if (activity == null || activity.isBlank()) {
                        return false;
                }

                String name = activity.trim()
                                .toLowerCase(java.util.Locale.ROOT)
                                .replaceAll("\\s+", " ");

                // Do not classify mixed sightseeing activities as pure travel.
                if (name.matches(".*\\b(?:visit|visiting|explore|exploring|"
                                + "sightseeing|tour|touring|discover|"
                                + "see|seeing|enjoy|enjoying)\\b.*")) {
                        return false;
                }

                return name.matches(
                                "^(?:travel|drive|transfer|journey|commute|"
                                                + "depart|departure|proceed|head|return|"
                                                + "leave|reach|arrive|"
                                                + "take a taxi|take a bus|take a train|"
                                                + "take a scenic drive|"
                                                + "go|travel back)"
                                                + "\\b.*$")
                                || name.matches(
                                                ".*\\b(?:depart|departure|"
                                                                + "return to|travel to|travel from|"
                                                                + "drive to|drive back|"
                                                                + "leave for|arrive at|"
                                                                + "head back to|"
                                                                + "journey back to|"
                                                                + "scenic drive to|"
                                                                + "take a scenic drive to)\\b.*");
        }

        // Identify general experiences that do not need place validation.
        private boolean isGeneralActivity(String activity) {

                if (activity == null || activity.isBlank()) {
                        return true;
                }

                String name = activity.toLowerCase(Locale.ROOT)
                                .replaceAll("[*`_#]", "")
                                .replaceAll("\\s+", " ")
                                .trim();

                // IMPORTANT:
                // A general walk mentioning a specific place
                // must be sent for actual location validation.
                if (name.matches(
                                ".*\\b(?:general\\s+)?(?:walk|stroll)\\s+"
                                                + "(?:through|in|around|along|at)\\s+.*")) {

                        String extractedPlace = extractPlaceName(activity);

                        if (!isGenericPlaceName(extractedPlace)) {

                                logger.info(
                                                "Named place found inside a general walk: {}",
                                                extractedPlace);

                                return false;
                        }
                }

                // Generic experiences that do not identify a specific attraction.
                if (name.matches(
                                ".*\\b(?:free time|relaxation|leisure|"
                                                + "street food|local food|food tasting|"
                                                + "local shopping|shopping experience|"
                                                + "general walk|city walk|general stroll|"
                                                + "riverside stroll|river walk|"
                                                + "relax at (?:the )?(?:hotel|resort|accommodation)|"
                                                + "relax at your accommodation|"
                                                + "popular eatery|local eatery|"
                                                + "local dining|local nightlife|"
                                                + "food scene|art scene)\\b.*")) {

                        return true;
                }

                // Generic activities without a named destination.
                if (name.matches(
                                ".*\\b(?:try|taste|eat|enjoy|experience|"
                                                + "spend|take)\\b.*"
                                                + "\\b(?:street food|local food|"
                                                + "food tasting|shopping|"
                                                + "general walk|city walk|"
                                                + "riverside stroll|river walk)\\b.*")) {

                        return true;
                }

                // Generic accommodation relaxation.
                if (name.matches(
                                ".*\\brelax\\s+at\\s+(?:(?:the|your)\\s+)?"
                                                + "(?:hotel|resort|accommodation)\\b.*")) {

                        return true;
                }

                // Generic free-time and accommodation descriptions.
                if (name.matches(
                                ".*\\b(?:free time|your accommodation|"
                                                + "the hotel|your hotel)\\b.*")) {

                        return true;
                }

                // Generic walks without a named tourist destination.
                if (name.matches(
                                ".*\\b(?:general walk|general stroll|"
                                                + "scenic walk through the city)\\b.*")) {

                        return true;
                }

                // Preserve all other named-place candidates for Java validation.
                return false;
        }

        private String callOllama(String prompt) throws Exception {
                logger.info("CALL OLLAMA STARTED");

                Map<String, Object> requestBody = Map.of(
                                "model", "qwen2.5:7b",
                                "stream", false,
                                "messages", new Object[] {
                                                Map.of(
                                                                "role", "system",
                                                                "content",

                                                                """
                                                                                You are TripMate, a careful and reliable AI travel planning assistant.

                                                                                DESTINATION ACCURACY:
                                                                                1. Recommend only genuine, identifiable tourist attractions relevant to the exact destination.
                                                                                2. Never invent attraction names or confuse similarly named places in different cities.
                                                                                3. Never present an attraction from another city, state or country as being inside the selected destination.
                                                                                4. Use the actual name of every genuine named attraction you know, even if its exact location is uncertain. Java will independently validate it.
                                                                                5. Never replace a known named attraction with a GENERAL EXPERIENCE just because its location is uncertain.
                                                                                6. Do not present generic areas, streets, markets, parks or art scenes as verified named attractions.
                                                                                7. Do not invent opening hours, prices, facilities, restaurants or bookings.
                                                                                8. Keep activities geographically relevant to the selected destination.

                                                                                NAMED ATTRACTIONS AND GENERAL EXPERIENCES:
                                                                                1. Named temples, monasteries, forts, museums, zoos, deer parks, waterfalls, lakes, viewpoints and landmarks must retain their actual names.
                                                                                2. GENERAL EXPERIENCE is only for activities without a specific named destination, such as free time, relaxation, a general city walk or local food tasting.
                                                                                3. Never use a general experience as a substitute for an unverified named attraction.
                                                                                4. Travel activities are not sightseeing attractions.

                                                                                NEARBY EXCURSIONS:
                                                                                1. Keep main-city attractions separate from nearby excursions.
                                                                                2. Never present another town or city as part of the main destination.
                                                                                3. Clearly label nearby excursions as OPTIONAL and include their actual town or city name.
                                                                                4. Do not repeat attractions or invent places to fill itinerary slots.

                                                                                BUDGET:
                                                                                1. Respect the user's maximum budget.
                                                                                2. Provide five budget categories in INR.
                                                                                3. Total Estimated Cost must equal the sum of all five categories.
                                                                                4. Clearly identify costs as estimates, not verified prices.

                                                                                OUTPUT:
                                                                                1. Follow the requested travel dates exactly.
                                                                                2. Generate a complete day-wise itinerary with morning, afternoon and evening activities.
                                                                                3. Include one budget breakdown and five practical travel tips.
                                                                                4. Return a clear, readable and properly formatted itinerary.
                                                                                """),
                                                Map.of(
                                                                "role", "user",
                                                                "content", prompt)
                                },
                                "options", Map.of(
                                                "temperature", 0.2,
                                                "num_predict", 2500));

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:11434/api/chat"))
                                .timeout(Duration.ofSeconds(600))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                .build();

                long ollamaStartTime = System.nanoTime();

                logger.info("TripMate: Sending request to Ollama...");

                HttpResponse<String> response = httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString());

                long ollamaEndTime = System.nanoTime();

                double ollamaTimeSeconds = (ollamaEndTime - ollamaStartTime) / 1_000_000_000.0;

                logger.info("TripMate: Ollama response received in {} seconds",
                                ollamaTimeSeconds);

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException(
                                        "Ollama API returned HTTP "
                                                        + response.statusCode() + ": " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());
                double totalSeconds = root.path("total_duration").asDouble() / 1_000_000_000.0;
                double loadSeconds = root.path("load_duration").asDouble() / 1_000_000_000.0;
                double promptSeconds = root.path("prompt_eval_duration").asDouble() / 1_000_000_000.0;
                double generationSeconds = root.path("eval_duration").asDouble() / 1_000_000_000.0;

                logger.info(
                                "OLLAMA PERFORMANCE | Total: {} sec | Model Load: {} sec | Prompt Processing: {} sec | Token Generation: {} sec | Prompt Tokens: {} | Generated Tokens: {}",
                                totalSeconds,
                                loadSeconds,
                                promptSeconds,
                                generationSeconds,
                                root.path("prompt_eval_count").asInt(),
                                root.path("eval_count").asInt());
                JsonNode content = root.path("message").path("content");

                if (content.isMissingNode()
                                || content.isNull()
                                || content.asText().isBlank()) {

                        throw new IllegalStateException(
                                        "Ollama returned an empty itinerary.");
                }

                return content.asText();
        }

        // Calculate the actual sum of all five categories.
        private BigDecimal calculateBudgetTotal(String itinerary) {

                try {
                        BigDecimal total = BigDecimal.ZERO;

                        for (String category : BUDGET_CATEGORIES) {

                                BigDecimal amount = extractAmount(itinerary, category);

                                if (amount == null || amount.signum() < 0) {
                                        return null;
                                }

                                total = total.add(amount);
                        }

                        return total;

                } catch (Exception e) {
                        return null;
                }
        }

        // Automatically adjust AI budget to the user's maximum budget.
        private String adjustBudgetToLimit(
                        String itinerary,
                        BigDecimal originalTotal,
                        BigDecimal maximumBudget) {

                BigDecimal budget = maximumBudget.setScale(2, RoundingMode.HALF_UP);

                BigDecimal factor = budget.divide(
                                originalTotal,
                                12,
                                RoundingMode.DOWN);

                BigDecimal[] adjustedAmounts = new BigDecimal[BUDGET_CATEGORIES.length];

                BigDecimal allocated = BigDecimal.ZERO;

                // Proportionally adjust the first four categories.
                for (int i = 0; i < BUDGET_CATEGORIES.length - 1; i++) {

                        BigDecimal originalAmount = extractAmount(itinerary, BUDGET_CATEGORIES[i]);

                        if (originalAmount == null) {
                                throw new IllegalStateException(
                                                "Could not read budget category: " + BUDGET_CATEGORIES[i]);
                        }

                        adjustedAmounts[i] = originalAmount
                                        .multiply(factor)
                                        .setScale(2, RoundingMode.DOWN);

                        allocated = allocated.add(adjustedAmounts[i]);
                }

                // Assign the remaining amount to Emergency / Extra.
                int lastIndex = BUDGET_CATEGORIES.length - 1;

                adjustedAmounts[lastIndex] = budget.subtract(allocated);

                // Replace all five original category amounts.
                String correctedItinerary = itinerary;

                for (int i = 0; i < BUDGET_CATEGORIES.length; i++) {

                        correctedItinerary = replaceCategoryAmount(
                                        correctedItinerary,
                                        BUDGET_CATEGORIES[i],
                                        adjustedAmounts[i]);
                }

                logger.info("Java adjusted itinerary budget to INR {}", budget);

                return replaceTotal(correctedItinerary, budget);
        }

        // Replace one category amount in the itinerary.
        private String replaceCategoryAmount(
                        String itinerary,
                        String category,
                        BigDecimal amount) {

                String regex = "(?im)^(\\s*[-*•]?\\s*(?:\\*\\*)?"
                                + Pattern.quote(category)
                                + "(?:\\*\\*\\s*:\\s*|:\\s*\\*\\*\\s*|:\\s*))"
                                + "(?:(?:INR|Rs\\.?|₹)\\s*)?"
                                + "[0-9,]+(?:\\.[0-9]+)?";

                Matcher matcher = Pattern.compile(regex).matcher(itinerary);

                if (!matcher.find()) {
                        throw new IllegalStateException(
                                        "Could not replace budget category: " + category);
                }

                String replacement = matcher.group(1)
                                + "INR "
                                + amount.stripTrailingZeros().toPlainString();

                return matcher.replaceFirst(
                                Matcher.quoteReplacement(replacement));
        }

        // Extract amounts even when AI uses Markdown formatting.
        private BigDecimal extractAmount(String text, String label) {

                String cleanText = text
                                .replaceAll("[*`_]", "")
                                .replace("\u00A0", " ");

                String regex = "(?im)^\\s*[-*•]?\\s*"
                                + Pattern.quote(label)
                                + "\\s*:\\s*(?:INR|Rs\\.?|₹)?\\s*"
                                + "([0-9,]+(?:\\.[0-9]+)?)";

                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(cleanText);

                if (!matcher.find()) {
                        return null;
                }

                String number = matcher.group(1).replace(",", "");

                return new BigDecimal(number);
        }

        // Replace AI's stated total with Java-calculated total.
        private String replaceTotal(String itinerary, BigDecimal actualTotal) {

                String regex = "(?im)^\\s*[-*•+]?\\s*"
                                + Pattern.quote("Total Estimated Cost")
                                + "\\s*:\\s*(?:INR|Rs\\.?|₹)?\\s*"
                                + "[0-9,]+(?:\\.[0-9]+)?";

                Matcher matcher = Pattern.compile(regex).matcher(itinerary);

                String formattedTotal = "Total Estimated Cost: INR "
                                + actualTotal.stripTrailingZeros().toPlainString();

                if (matcher.find()) {
                        return matcher.replaceFirst(
                                        Matcher.quoteReplacement(formattedTotal));
                }

                return itinerary + "\n\n" + formattedTotal;
        }
}


package com.travelplanner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class DemoItineraryService {

    public Map<String, Object> generateItinerary(
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal budget) {

        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException(
                    "Destination is required.");
        }

        if (startDate == null || endDate == null
                || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "Please enter valid travel dates.");
        }

        if (budget == null
                || budget.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Budget must be greater than zero.");
        }

        destination = destination.trim();

        // Destination ke hisaab se activities select karein
        List<String[]> activities = getActivities(destination);

        List<Map<String, Object>> days = new ArrayList<>();

        LocalDate date = startDate;
        int dayNumber = 1;

        while (!date.isAfter(endDate)) {

            // Har din activities ko rotate karein
            String[] dailyActivities = activities.get((dayNumber - 1)
                    % activities.size());

            Map<String, Object> day = new LinkedHashMap<>();

            day.put("day", dayNumber);
            day.put("date", date.toString());
            day.put("morning", dailyActivities[0]);
            day.put("afternoon", dailyActivities[1]);
            day.put("evening", dailyActivities[2]);

            days.add(day);

            date = date.plusDays(1);
            dayNumber++;
        }

        // Budget breakdown
        Map<String, Object> breakdown = new LinkedHashMap<>();

        breakdown.put("stay",
                budget.multiply(new BigDecimal("0.40"))
                        .setScale(2, RoundingMode.HALF_UP));

        breakdown.put("food",
                budget.multiply(new BigDecimal("0.25"))
                        .setScale(2, RoundingMode.HALF_UP));

        breakdown.put("transport",
                budget.multiply(new BigDecimal("0.20"))
                        .setScale(2, RoundingMode.HALF_UP));

        breakdown.put("activities",
                budget.multiply(new BigDecimal("0.15"))
                        .setScale(2, RoundingMode.HALF_UP));

        // API response
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("mode", "DEMO");
        result.put("message",
                "Sample itinerary: activities and costs are estimates.");
        result.put("destination", destination);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("budget", budget);
        result.put("totalDays", days.size());
        result.put("itinerary", days);
        result.put("budgetBreakdown", breakdown);

        return result;
    }

    private List<String[]> getActivities(String destination) {

        String place = destination.toLowerCase(Locale.ROOT);

        switch (place) {

            case "goa":
                return List.of(
                        new String[] {
                                "Visit a popular beach in Goa.",
                                "Enjoy Goan food and coastal sightseeing.",
                                "Explore a local market or watch the sunset."
                        },
                        new String[] {
                                "Explore Old Goa's historic churches.",
                                "Visit Fort Aguada and nearby viewpoints.",
                                "Relax at a beachside cafe."
                        },
                        new String[] {
                                "Discover a quieter beach in Goa.",
                                "Try local seafood or Goan vegetarian dishes.",
                                "Enjoy an evening stroll along the coast."
                        },
                        new String[] {
                                "Visit a local attraction or spice plantation.",
                                "Explore Panjim and its colourful streets.",
                                "Browse local shops and markets."
                        });

            case "manali":
                return List.of(
                        new String[] {
                                "Explore Mall Road in Manali.",
                                "Visit Hadimba Devi Temple.",
                                "Enjoy an evening walk through the town."
                        },
                        new String[] {
                                "Take in mountain views around Solang Valley.",
                                "Enjoy local Himachali food.",
                                "Relax at a cafe overlooking the mountains."
                        },
                        new String[] {
                                "Visit the Vashisht area and its temple.",
                                "Explore local handicraft shops.",
                                "Enjoy the mountain scenery before sunset."
                        });

            case "jaipur":
                return List.of(
                        new String[] {
                                "Explore Amber Fort.",
                                "Try traditional Rajasthani food.",
                                "Browse Jaipur's local markets."
                        },
                        new String[] {
                                "Visit Hawa Mahal from the outside.",
                                "Explore the City Palace area.",
                                "Enjoy an evening walk in the old city."
                        },
                        new String[] {
                                "Visit Jantar Mantar.",
                                "Discover local crafts and textiles.",
                                "Try a traditional Rajasthani dinner."
                        });

            case "agra":
                return List.of(
                        new String[] {
                                "Visit the Taj Mahal.",
                                "Explore local Mughlai cuisine.",
                                "Enjoy views around the Taj Mahal area."
                        },
                        new String[] {
                                "Explore Agra Fort.",
                                "Browse local marble handicraft shops.",
                                "Relax at a cafe in the city."
                        },
                        new String[] {
                                "Visit Mehtab Bagh.",
                                "Try a local sweet such as petha.",
                                "Explore a nearby market."
                        });

            case "delhi":
            case "new delhi":
                return List.of(
                        new String[] {
                                "Explore the Red Fort area.",
                                "Try local food in Old Delhi.",
                                "Walk through a nearby market."
                        },
                        new String[] {
                                "Visit India Gate.",
                                "Explore the National Museum area.",
                                "Enjoy an evening walk around Connaught Place."
                        },
                        new String[] {
                                "Visit Qutub Minar.",
                                "Discover Delhi's local cuisine.",
                                "Browse shops in a popular market."
                        });

            case "mumbai":
                return List.of(
                        new String[] {
                                "Visit the Gateway of India.",
                                "Explore the Colaba area.",
                                "Enjoy an evening walk along Marine Drive."
                        },
                        new String[] {
                                "Explore the Chhatrapati Shivaji Maharaj Terminus area.",
                                "Try Mumbai street food.",
                                "Watch the sunset by the sea."
                        },
                        new String[] {
                                "Visit a museum or local attraction.",
                                "Explore a neighbourhood food market.",
                                "Relax at a seaside promenade."
                        });

            case "shimla":
                return List.of(
                        new String[] {
                                "Explore Shimla's Mall Road.",
                                "Visit Christ Church in the Ridge area.",
                                "Enjoy an evening stroll through town."
                        },
                        new String[] {
                                "Explore the Jakhoo Temple area.",
                                "Try local Himachali food.",
                                "Enjoy mountain views from a viewpoint."
                        },
                        new String[] {
                                "Discover Shimla's colonial-era architecture.",
                                "Browse local handicraft shops.",
                                "Relax at a cafe with mountain views."
                        });

            default:
                return List.of(
                        new String[] {
                                "Explore a popular attraction in " + destination + ".",
                                "Enjoy local food and sightseeing in " + destination + ".",
                                "Visit a local market or relax at a scenic spot."
                        },
                        new String[] {
                                "Discover a cultural or historic site in " + destination + ".",
                                "Try a regional dish and explore the neighbourhood.",
                                "Enjoy a relaxed evening in " + destination + "."
                        },
                        new String[] {
                                "Explore a park, viewpoint, or local landmark in " + destination + ".",
                                "Browse local shops and discover regional crafts.",
                                "Enjoy a scenic walk before the day ends."
                        });
        }
    }
}

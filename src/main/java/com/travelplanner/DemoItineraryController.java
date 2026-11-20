
package com.travelplanner;

import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.travelplanner.dto.TripPlanRequest;

@RestController
@RequestMapping("/api/demo-itinerary")
public class DemoItineraryController {

    private final DemoItineraryService demoService;

    public DemoItineraryController(DemoItineraryService demoService) {
        this.demoService = demoService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @Valid @RequestBody TripPlanRequest request) {
        try {
            Map<String, Object> result = demoService.generateItinerary(
                    request.getDestination(),
                    request.getStartDate(),
                    request.getEndDate(),
                    request.getBudget());

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not create demo itinerary."));
        }
    }
}

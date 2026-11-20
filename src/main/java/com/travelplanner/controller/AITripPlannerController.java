
package com.travelplanner.controller;

import com.travelplanner.dto.TripPlanRequest;
import com.travelplanner.service.AITripPlannerService;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AITripPlannerController {

        private static final Logger logger = LoggerFactory.getLogger(AITripPlannerController.class);

        private final AITripPlannerService aiTripPlannerService;

        public AITripPlannerController(
                        AITripPlannerService aiTripPlannerService) {
                this.aiTripPlannerService = aiTripPlannerService;
        }

        @PostMapping("/plan")
        public ResponseEntity<Map<String, String>> generatePlan(
                        @Valid @RequestBody TripPlanRequest request) {

                logger.info("AI Trip Planner endpoint was called.");

                // Validate travel dates
                if (request.getEndDate().isBefore(request.getStartDate())
                                || request.getStartDate().isBefore(LocalDate.now())) {

                        return ResponseEntity.badRequest().body(
                                        Map.of(
                                                        "error",
                                                        "Please provide valid future travel dates."));
                }

                try {
                        String itinerary = aiTripPlannerService.generatePlan(request);

                        if (itinerary == null || itinerary.isBlank()) {
                                logger.error("AI service returned an empty itinerary.");

                                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                                                Map.of(
                                                                "error",
                                                                "AI returned an empty itinerary. Please try again."));
                        }

                        return ResponseEntity.ok(
                                        Map.of("itinerary", itinerary));

                } catch (IllegalArgumentException e) {

                        // Print complete exception details in the Spring Boot terminal.
                        logger.error("AI planning IllegalArgumentException details:", e);

                        return ResponseEntity.badRequest().body(
                                        Map.of(
                                                        "error",
                                                        e.getMessage() != null
                                                                        ? e.getMessage()
                                                                        : "Invalid trip planning request."));

                } catch (Exception e) {

                        // Detailed error stays in the backend terminal.
                        logger.error("AI itinerary generation failed.", e);

                        // Do not expose internal exception details to the client.
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                                        Map.of(
                                                        "error",
                                                        "AI itinerary generation failed. Check the Spring Boot terminal logs."));
                }
        }
}